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
 * High-level Sony WF-1000XM5 protocol driver.
 *
 * Wraps a [LightSerialConnection] (the only `android`-adjacent type this class
 * touches — everything else is pure Kotlin framing/parsing) and:
 *  - runs the Init handshake with retry,
 *  - manages the alternating sequence number,
 *  - auto-replies with an Ack to every unsolicited Command1/Command2 message,
 *  - sends commands strictly sequentially (one outstanding, awaiting its Ack),
 *  - exposes device state as [StateFlow]s for the UI to observe.
 *
 * @param connection live byte transport to the earbuds.
 * @param scope scope that owns the inbound collector and auto-Ack writes;
 *   cancelling it (or calling [stop]) tears the driver down.
 */
class SonyProtocolClient(
    private val connection: LightSerialConnection,
    private val scope: CoroutineScope,
) {
    // ---- Exposed state -----------------------------------------------------

    private val _ancMode = MutableStateFlow(AncMode.OFF)
    val ancMode: StateFlow<AncMode> = _ancMode.asStateFlow()

    private val _ambientLevel = MutableStateFlow(0)
    val ambientLevel: StateFlow<Int> = _ambientLevel.asStateFlow()

    private val _voicePassthrough = MutableStateFlow(false)
    val voicePassthrough: StateFlow<Boolean> = _voicePassthrough.asStateFlow()

    private val _leftBattery = MutableStateFlow<Int?>(null)
    /** Left earbud battery percentage, or null until first reported. */
    val leftBattery: StateFlow<Int?> = _leftBattery.asStateFlow()

    private val _rightBattery = MutableStateFlow<Int?>(null)
    /** Right earbud battery percentage, or null until first reported. */
    val rightBattery: StateFlow<Int?> = _rightBattery.asStateFlow()

    private val _caseBattery = MutableStateFlow<Int?>(null)
    /** Charging-case battery percentage, or null until first reported. */
    val caseBattery: StateFlow<Int?> = _caseBattery.asStateFlow()

    // ---- Internal state ----------------------------------------------------

    private val decoder = SonyFrameDecoder()
    private val sendMutex = Mutex() // enforces strictly-sequential sends

    /** Sequence number to stamp on the next outgoing command. */
    @Volatile
    private var seq: Int = 0

    /** Completed by the inbound loop when the Ack for an in-flight send lands. */
    @Volatile
    private var pendingAck: CompletableDeferred<Unit>? = null

    /** Completed by the inbound loop on the very first chunk of any bytes. */
    @Volatile
    private var firstBytes: CompletableDeferred<Unit> = CompletableDeferred()

    private var receiveJob: Job? = null

    // ---- Lifecycle ---------------------------------------------------------

    /**
     * Start collecting inbound bytes and run the Init handshake. Sends the Init
     * command (Command1 with payload `[0x00, 0x00]`) and resends it every
     * [INIT_RETRY_MS] up to [INIT_MAX_ATTEMPTS] times until any bytes arrive.
     * Suspends until bytes arrive or the attempts are exhausted.
     */
    suspend fun start() {
        if (receiveJob == null) {
            receiveJob = scope.launch {
                connection.incoming.collect { chunk -> onChunk(chunk) }
            }
        }

        var attempts = 0
        while (attempts < INIT_MAX_ATTEMPTS && !firstBytes.isCompleted) {
            connection.write(SonyFrame.encode(SonyFrame.TYPE_COMMAND1, seq, INIT_PAYLOAD))
            attempts++
            withTimeoutOrNull(INIT_RETRY_MS) { firstBytes.await() }
        }
    }

    /** Cancel the inbound collector and close the transport. Idempotent. */
    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        connection.close()
    }

    // ---- Commands ----------------------------------------------------------

    /**
     * Set the noise-cancelling mode. [level] applies to [AncMode.AMBIENT] and
     * is coerced into `0..20`. Sends a committed change (drag=1), waits for the
     * Ack, then optimistically reflects the request in the exposed state.
     */
    suspend fun setAnc(mode: AncMode, level: Int, voicePassthrough: Boolean) {
        sendCommand(SonyFrame.TYPE_COMMAND1, SonyCommands.ancSet(mode, level, voicePassthrough))
        _ancMode.value = mode
        _ambientLevel.value = level.coerceIn(0, 20)
        _voicePassthrough.value = voicePassthrough
    }

    /** Request battery status for both earbuds and the case. */
    suspend fun refreshBattery() {
        sendCommand(SonyFrame.TYPE_COMMAND1, SonyCommands.getBattery(SonyCommands.BATTERY_TARGET_HEADPHONES))
        sendCommand(SonyFrame.TYPE_COMMAND1, SonyCommands.getBattery(SonyCommands.BATTERY_TARGET_CASE))
    }

    /** Request the current ANC/ambient status. */
    suspend fun refreshAncStatus() {
        sendCommand(SonyFrame.TYPE_COMMAND1, SonyCommands.getAncStatus())
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

    private fun onChunk(chunk: ByteArray) {
        if (!firstBytes.isCompleted) firstBytes.complete(Unit)
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
                // A reply or unsolicited notify: update state, then Ack it.
                SonyResponses.parse(message)?.let { applyEvent(it) }
                val ackSeq = (1 - message.seq) and 0xFF
                scope.launch {
                    connection.write(SonyFrame.encode(SonyFrame.TYPE_ACK, ackSeq, EMPTY_PAYLOAD))
                }
            }
        }
    }

    private fun applyEvent(event: SonyEvent) {
        when (event) {
            is SonyEvent.Anc -> {
                _ancMode.value = event.status.mode
                _ambientLevel.value = event.status.ambientLevel
                _voicePassthrough.value = event.status.voicePassthrough
            }
            is SonyEvent.Battery -> {
                val status = event.status
                when (status.target) {
                    BatteryTarget.CASE -> status.level?.let { _caseBattery.value = it }
                    BatteryTarget.HEADPHONES -> {
                        status.left?.let { _leftBattery.value = it }
                        status.right?.let { _rightBattery.value = it }
                    }
                }
            }
        }
    }

    private companion object {
        const val INIT_RETRY_MS = 1500L
        const val INIT_MAX_ATTEMPTS = 3
        const val ACK_TIMEOUT_MS = 2000L
        val INIT_PAYLOAD = byteArrayOf(0x00, 0x00)
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}
