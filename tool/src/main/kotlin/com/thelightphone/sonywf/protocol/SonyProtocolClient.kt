package com.thelightphone.sonywf.protocol

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * High-level Sony headphone protocol driver supporting BOTH wire dialects.
 *
 * Wraps a [LightSerialConnection] (the only `android`-adjacent type this class
 * touches — everything else is pure Kotlin framing/parsing) and:
 *  - runs the Init handshake with retry and DETECTS the dialect (v1/v2) from the
 *    Init reply length,
 *  - DISCOVERS device capabilities at runtime (which battery types answer, which
 *    ANC layout the device uses), so nothing is hardcoded per model,
 *  - manages the alternating sequence number,
 *  - auto-replies with an Ack to every unsolicited Command1/Command2 message,
 *  - sends commands strictly sequentially (one outstanding, awaiting its Ack),
 *  - exposes device state as [StateFlow]s for the UI to observe.
 *
 * @param connection live byte transport to the headphones.
 * @param scope scope that owns the inbound collector and auto-Ack writes;
 *   cancelling it (or calling [stop]) tears the driver down.
 */
class SonyProtocolClient(
    private val connection: LightSerialConnection,
    private val scope: CoroutineScope,
) {
    // ---- Exposed state -----------------------------------------------------

    private val _connected = MutableStateFlow(false)
    /** True once the Init handshake completed and the dialect is known. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _dialect = MutableStateFlow<SonyDialect?>(null)
    val dialect: StateFlow<SonyDialect?> = _dialect.asStateFlow()

    private val _ancSupported = MutableStateFlow(false)
    /** True once a valid ANC status reply/notify has been observed. */
    val ancSupported: StateFlow<Boolean> = _ancSupported.asStateFlow()

    private val _ancMode = MutableStateFlow(AncMode.OFF)
    val ancMode: StateFlow<AncMode> = _ancMode.asStateFlow()

    private val _ambientLevel = MutableStateFlow(0)
    val ambientLevel: StateFlow<Int> = _ambientLevel.asStateFlow()

    private val _voicePassthrough = MutableStateFlow(false)
    val voicePassthrough: StateFlow<Boolean> = _voicePassthrough.asStateFlow()

    private val _battery = MutableStateFlow(SonyBattery())
    val battery: StateFlow<SonyBattery> = _battery.asStateFlow()

    private val _firmwareVersion = MutableStateFlow<String?>(null)
    val firmwareVersion: StateFlow<String?> = _firmwareVersion.asStateFlow()

    // ---- Internal state ----------------------------------------------------

    private val decoder = SonyFrameDecoder()
    private val sendMutex = Mutex() // enforces strictly-sequential sends

    /** Sequence number to stamp on the next outgoing command. */
    @Volatile
    private var seq: Int = 0

    /** Completed by the inbound loop when the Ack for an in-flight send lands. */
    @Volatile
    private var pendingAck: CompletableDeferred<Unit>? = null

    /** Completed by the inbound loop when the Init reply (Command1, payload[0]==0x01) arrives. */
    @Volatile
    private var pendingInitReply: CompletableDeferred<SonyMessage>? = null

    /** Opcode the inbound loop should match to complete [pendingQuery]. */
    @Volatile
    private var pendingQueryOpcode: Int = -1

    /** Completed by the inbound loop when a reply with [pendingQueryOpcode] arrives. */
    @Volatile
    private var pendingQuery: CompletableDeferred<SonyMessage>? = null

    /** Discovered ANC layout; null until ANC is discovered (or found unsupported). */
    @Volatile
    private var ancSubByte: Int = SonyCommands.V2_ANC_SUB_STANDARD

    @Volatile
    private var ancWind: Boolean = false

    private var receiveJob: Job? = null

    // ---- Lifecycle ---------------------------------------------------------

    /**
     * Start collecting inbound bytes, run the Init handshake (retrying Init up to
     * [INIT_MAX_ATTEMPTS] times at [INIT_RETRY_MS] intervals), detect the dialect
     * from the Init reply length, then discover capabilities: probe every battery
     * type for the dialect and probe the ANC variant, best-effort fetching the
     * firmware string. Populates the exposed [StateFlow]s.
     *
     * @throws IllegalStateException if no Init reply arrives after the retries
     *   (callers use this to reject a non-Sony device during connection probing).
     */
    suspend fun start() {
        if (receiveJob == null) {
            receiveJob = scope.launch {
                connection.incoming.collect { chunk -> onChunk(chunk) }
            }
        }

        val initReply = CompletableDeferred<SonyMessage>()
        pendingInitReply = initReply
        var reply: SonyMessage? = null
        var attempts = 0
        while (attempts < INIT_MAX_ATTEMPTS && reply == null) {
            connection.write(SonyFrame.encode(SonyFrame.TYPE_COMMAND1, seq, SonyCommands.INIT_PAYLOAD))
            attempts++
            reply = withTimeoutOrNull(INIT_RETRY_MS) { initReply.await() }
        }
        pendingInitReply = null

        val detected = reply?.let { SonyResponses.parseInitReplyDialect(it.payload) }
            ?: throw IllegalStateException("No Sony Init reply after $attempts attempt(s); not a Sony device")

        _dialect.value = detected
        _connected.value = true

        discoverBattery(detected)
        discoverAnc(detected)
        discoverFirmware()
    }

    /** Cancel the inbound collector and close the transport. Idempotent. */
    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        connection.close()
    }

    // ---- Discovery ---------------------------------------------------------

    /**
     * Probe each battery type for the dialect sequentially (each awaits its Ack).
     * Replies are merged into [battery] by the inbound loop as they arrive;
     * unsupported types are silently ignored by the device and stay null.
     */
    private suspend fun discoverBattery(dialect: SonyDialect) {
        for (type in SonyCommands.batteryTypesFor(dialect)) {
            sendCommand(SonyFrame.TYPE_COMMAND1, SonyCommands.batteryGet(dialect, type))
        }
    }

    /**
     * Probe the ANC variant. For V2, try the wind sub-byte (0x17) first and fall
     * back to the standard sub-byte (0x15); for V1, use sub 0x02. The discovered
     * sub-byte + wind flag drive [setAnc]. [ancSupported] flips true whenever a
     * valid ANC reply is observed (handled in the inbound loop).
     */
    private suspend fun discoverAnc(dialect: SonyDialect) {
        when (dialect) {
            SonyDialect.V2 -> {
                var reply = query(SonyCommands.ancGet(dialect, SonyCommands.V2_ANC_SUB_WIND), SonyResponses.ANC_RET)
                if (reply != null) {
                    ancSubByte = SonyCommands.V2_ANC_SUB_WIND
                    ancWind = reply.payload.size > 7
                    return
                }
                reply = query(SonyCommands.ancGet(dialect, SonyCommands.V2_ANC_SUB_STANDARD), SonyResponses.ANC_RET)
                if (reply != null) {
                    ancSubByte = SonyCommands.V2_ANC_SUB_STANDARD
                    ancWind = false
                }
            }
            SonyDialect.V1 -> {
                val reply = query(SonyCommands.ancGet(dialect, SonyCommands.V1_ANC_SUB), SonyResponses.ANC_RET)
                if (reply != null) {
                    ancSubByte = SonyCommands.V1_ANC_SUB
                    ancWind = (reply.payload.size > 3 && (reply.payload[3].toInt() and 0xFF) == 0x02)
                }
            }
        }
    }

    /** Best-effort firmware fetch; ignores absence of a reply. */
    private suspend fun discoverFirmware() {
        val reply = query(SonyCommands.firmwareGet(), SonyResponses.FIRMWARE_RET)
        reply?.let { SonyResponses.parseFirmware(it.payload)?.let { fw -> _firmwareVersion.value = fw } }
    }

    // ---- Commands ----------------------------------------------------------

    /**
     * Set the noise-cancelling mode using the discovered dialect + ANC layout.
     * No-op if ANC was not discovered ([ancSupported] is false). [level] applies
     * to [AncMode.AMBIENT] and is coerced into `0..20`. Waits for the Ack, then
     * optimistically reflects the request in the exposed state.
     */
    suspend fun setAnc(mode: AncMode, level: Int, voicePassthrough: Boolean) {
        if (!_ancSupported.value) return
        val dialect = _dialect.value ?: return
        val payload = SonyCommands.ancSet(dialect, ancSubByte, ancWind, mode, level, voicePassthrough)
        sendCommand(SonyFrame.TYPE_COMMAND1, payload)
        _ancMode.value = mode
        _ambientLevel.value = level.coerceIn(0, 20)
        _voicePassthrough.value = voicePassthrough
    }

    // ---- Send / receive plumbing ------------------------------------------

    /**
     * Encode and write a command with the current [seq], then wait for its Ack
     * before returning. Serialised by [sendMutex] so only one command is ever
     * outstanding. Times out after [ACK_TIMEOUT_MS] so a lost Ack cannot wedge
     * the driver forever.
     */
    private suspend fun sendCommand(type: Int, payload: ByteArray) {
        sendMutex.withLock {
            val ack = CompletableDeferred<Unit>()
            pendingAck = ack
            connection.write(SonyFrame.encode(type, seq, payload))
            withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() }
            if (pendingAck === ack) pendingAck = null // clear on timeout
        }
    }

    /**
     * Send a command (awaiting its Ack) and then await a reply whose opcode is
     * [replyOpcode], up to [REPLY_TIMEOUT_MS]. Returns the reply message, or null
     * if none arrived in time. The pending-reply slot is registered BEFORE the
     * command is written so a fast reply cannot be missed.
     */
    private suspend fun query(payload: ByteArray, replyOpcode: Int): SonyMessage? {
        val deferred = CompletableDeferred<SonyMessage>()
        pendingQueryOpcode = replyOpcode
        pendingQuery = deferred
        return try {
            sendCommand(SonyFrame.TYPE_COMMAND1, payload)
            withTimeoutOrNull(REPLY_TIMEOUT_MS) { deferred.await() }
        } finally {
            pendingQuery = null
            pendingQueryOpcode = -1
        }
    }

    private fun onChunk(chunk: ByteArray) {
        for (message in decoder.feed(chunk)) handleMessage(message)
    }

    private fun handleMessage(message: SonyMessage) {
        when (message.type) {
            SonyFrame.TYPE_ACK -> {
                // The device's Ack seq is (1 - our command's seq); adopting it
                // naturally toggles our sequence number for the next command.
                seq = message.seq
                val ack = pendingAck
                pendingAck = null
                ack?.complete(Unit)
            }

            SonyFrame.TYPE_COMMAND1, SonyFrame.TYPE_COMMAND2 -> {
                val payload = message.payload
                val opcode = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else -1

                if (opcode == SonyResponses.INIT_REPLY_MARKER) {
                    val d = pendingInitReply
                    if (d != null && !d.isCompleted) d.complete(message)
                } else {
                    _dialect.value?.let { applyEvent(it, message) }
                }

                // Complete an outstanding reply query, if this matches.
                if (opcode != -1 && opcode == pendingQueryOpcode) {
                    val q = pendingQuery
                    pendingQuery = null
                    pendingQueryOpcode = -1
                    q?.complete(message)
                }

                // Auto-Ack every device Command1/Command2 with seq = (1 - msg.seq).
                val ackSeq = (1 - message.seq) and 0xFF
                scope.launch {
                    connection.write(SonyFrame.encode(SonyFrame.TYPE_ACK, ackSeq, EMPTY_PAYLOAD))
                }
            }
        }
    }

    private fun applyEvent(dialect: SonyDialect, message: SonyMessage) {
        when (val event = SonyResponses.parse(dialect, message)) {
            is SonyEvent.Anc -> {
                _ancSupported.value = true
                _ancMode.value = event.status.mode
                _ambientLevel.value = event.status.ambientLevel
                _voicePassthrough.value = event.status.voicePassthrough
            }
            is SonyEvent.Battery -> {
                _battery.value = _battery.value.mergedWith(event.battery)
            }
            is SonyEvent.Firmware -> {
                _firmwareVersion.value = event.version
            }
            null -> Unit
        }
    }

    private companion object {
        const val INIT_RETRY_MS = 1500L
        const val INIT_MAX_ATTEMPTS = 3
        const val ACK_TIMEOUT_MS = 2000L
        const val REPLY_TIMEOUT_MS = 1000L
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}
