package com.thelightphone.sonywf.protocol

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import com.thelightphone.sonywf.update.FirmwareUpdateMethod
import com.thelightphone.sonywf.update.FirmwareUpdateMethods
import com.thelightphone.sonywf.update.UpdateCapability
import com.thelightphone.sonywf.update.UpdateParams
import com.thelightphone.sonywf.update.UpdtMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _modelName = MutableStateFlow<String?>(null)
    /** Device-reported model name (V2 only); null until discovered. */
    val modelName: StateFlow<String?> = _modelName.asStateFlow()

    private val _supportFunctions = MutableStateFlow<Set<Int>?>(null)
    /** Table-1 support-function bytes; null until discovered, always null on V1. */
    val supportFunctions: StateFlow<Set<Int>?> = _supportFunctions.asStateFlow()

    private val _updateMethod = MutableStateFlow<FirmwareUpdateMethod?>(null)
    /** Firmware-update transport; null until known, [FirmwareUpdateMethod.NONE] on V1. */
    val updateMethod: StateFlow<FirmwareUpdateMethod?> = _updateMethod.asStateFlow()

    private val _updateCapability = MutableStateFlow<UpdateCapability?>(null)
    val updateCapability: StateFlow<UpdateCapability?> = _updateCapability.asStateFlow()

    private val _updateParams = MutableStateFlow<UpdateParams?>(null)
    val updateParams: StateFlow<UpdateParams?> = _updateParams.asStateFlow()

    private val _settings = MutableStateFlow<List<SettingState>>(emptyList())
    /** Device settings found by [discoverSettings], in display order; empty until then. */
    val settings: StateFlow<List<SettingState>> = _settings.asStateFlow()

    private val _playback = MutableStateFlow<PlaybackState?>(null)
    /** Playback controls/volume; null when the device does not advertise them. */
    val playback: StateFlow<PlaybackState?> = _playback.asStateFlow()

    private val _autoAmbient = MutableStateFlow<Boolean?>(null)
    /** Auto Ambient Sound (0x19 byte [7]); null unless the device uses the 0x19 layout. */
    val autoAmbient: StateFlow<Boolean?> = _autoAmbient.asStateFlow()

    private val _canPowerOff = MutableStateFlow(false)
    val canPowerOff: StateFlow<Boolean> = _canPowerOff.asStateFlow()

    private val _notifications = MutableSharedFlow<SonyMessage>(
        replay = 0,
        // A firmware transfer bursts notifications; dropping the oldest keeps the
        // inbound loop non-blocking rather than losing the newest status.
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /**
     * Every inbound Command1/Command2 message, emitted before the auto-Ack.
     * The FOTA state machine drives itself off this: it survives a connection
     * close (the flow never completes), which is required during install.
     */
    val notifications: SharedFlow<SonyMessage> = _notifications.asSharedFlow()

    // ---- Internal state ----------------------------------------------------

    private val decoder = SonyFrameDecoder()
    private val sendMutex = Mutex() // enforces strictly-sequential sends
    private val queryMutex = Mutex() // one pending reply slot, so one query at a time

    /** Sequence number to stamp on the next outgoing command. */
    @Volatile
    private var seq: Int = 0

    /** Completed by the inbound loop when the Ack for an in-flight send lands. */
    @Volatile
    private var pendingAck: CompletableDeferred<Unit>? = null

    /**
     * Ack seq the outstanding frame expects, i.e. `1 - frame.seq`; -1 when no
     * frame is outstanding. A resend can draw a duplicate Ack, so an Ack that
     * does not match must neither complete a wait nor toggle [seq].
     */
    @Volatile
    private var pendingAckSeq: Int = -1

    /** Completed by the inbound loop when the Init reply (Command1, payload[0]==0x01) arrives. */
    @Volatile
    private var pendingInitReply: CompletableDeferred<SonyMessage>? = null

    /** Opcode the inbound loop should match to complete [pendingQuery]. */
    @Volatile
    private var pendingQueryOpcode: Int = -1

    /**
     * Optional `payload[1]` the reply must also carry. Needed because opcode
     * 0x05 answers both the firmware-version and the model-name query.
     */
    @Volatile
    private var pendingQuerySub: Int = -1

    /** Completed by the inbound loop when a reply with [pendingQueryOpcode] arrives. */
    @Volatile
    private var pendingQuery: CompletableDeferred<SonyMessage>? = null

    /** Discovered ANC layout; null until ANC is discovered (or found unsupported). */
    @Volatile
    private var ancSubByte: Int = SonyCommands.V2_ANC_SUB_STANDARD

    @Volatile
    private var ancWind: Boolean = false

    /** Last reported 0x19-layout noise-adaptive bytes, echoed by [setAnc]. */
    @Volatile
    private var ancNoiseAdaptive: Int = SonyCommands.NOISE_ADAPTIVE_OFF

    @Volatile
    private var ancAdaptiveSensitivity: Int = 0

    // ---- Battery accumulation ----------------------------------------------
    //
    // Battery replies arrive as separate SINGLE / DUAL / CASE messages that we
    // fold into one snapshot. The charging case is published ONLY once the
    // device has also reported a DUAL (per-bud) battery: only earbuds have a
    // case, and reporting per-bud batteries is the robust, model-agnostic signal
    // that a case exists. This is order-independent — a CASE reply that arrives
    // before any DUAL reply is held and surfaces once [dualSeen] flips true; an
    // over-ear device that never reports dual never shows a case.

    private var battSingle: Int? = null
    private var battLeft: Int? = null
    private var battRight: Int? = null
    private var battCase: Int? = null

    @Volatile
    private var dualSeen: Boolean = false

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
                try {
                    connection.incoming.collect { chunk -> onChunk(chunk) }
                } finally {
                    // The transport is gone for good; the FOTA install phase
                    // watches this to know it must redial after the reboot.
                    _connected.value = false
                }
            }
        }

        val initReply = CompletableDeferred<SonyMessage>()
        pendingInitReply = initReply
        var reply: SonyMessage? = null
        var attempts = 0
        while (attempts < INIT_MAX_ATTEMPTS && reply == null) {
            // Registered so the Init Ack still toggles seq even though this
            // write waits for the Init REPLY rather than for its Ack.
            pendingAckSeq = (1 - seq) and 0xFF
            connection.write(SonyFrame.encode(SonyFrame.TYPE_COMMAND1, seq, SonyCommands.INIT_PAYLOAD))
            attempts++
            reply = withTimeoutOrNull(INIT_RETRY_MS) { initReply.await() }
        }
        pendingInitReply = null
        pendingAckSeq = -1

        val detected = reply?.let { SonyResponses.parseInitReplyDialect(it.payload) }
            ?: throw IllegalStateException("No Sony Init reply after $attempts attempt(s); not a Sony device")

        _dialect.value = detected
        _connected.value = true

        discoverBattery(detected)
        discoverAnc(detected)
        discoverFirmware()
        if (detected == SonyDialect.V2) {
            discoverUpdateSupport()
        } else {
            _updateMethod.value = FirmwareUpdateMethod.NONE // v1 never speaks Tandem FOTA
        }
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
     * Probe the ANC variant. For V2, try the noise-adaptive sub-byte (0x19,
     * WF-1000XM6), then the wind sub-byte (0x17), then the standard sub-byte
     * (0x15); for V1, use sub 0x02. The discovered
     * sub-byte + wind flag drive [setAnc]. [ancSupported] flips true whenever a
     * valid ANC reply is observed (handled in the inbound loop).
     */
    private suspend fun discoverAnc(dialect: SonyDialect) {
        when (dialect) {
            SonyDialect.V2 -> {
                // Only a well-formed 9-byte reply counts: other devices ignore 0x19.
                val adaptive = query(
                    SonyCommands.ancGet(dialect, SonyCommands.V2_ANC_SUB_ADAPTIVE),
                    SonyResponses.ANC_RET,
                    replySub = SonyCommands.V2_ANC_SUB_ADAPTIVE,
                )
                if (adaptive != null && SonyResponses.parseAnc(dialect, adaptive.payload) != null) {
                    ancSubByte = SonyCommands.V2_ANC_SUB_ADAPTIVE
                    ancWind = false
                    return
                }
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
        val reply = query(
            SonyCommands.firmwareGet(),
            SonyResponses.FIRMWARE_RET,
            replySub = SonyCommands.FIRMWARE_SUB,
        )
        reply?.let { SonyResponses.parseFirmware(it.payload)?.let { fw -> _firmwareVersion.value = fw } }
    }

    /**
     * V2-only firmware-update discovery: model name, support-function table,
     * then (if the device advertises any update function) the FOTA capability
     * and params for the selected inquired type. Every reply is best-effort —
     * a missing one leaves its flow null and does NOT fail [start].
     *
     * The flows are populated by the inbound loop ([applyEvent]) so unsolicited
     * copies of the same messages keep them current too.
     */
    private suspend fun discoverUpdateSupport() {
        query(
            SonyCommands.modelNameGet(),
            SonyResponses.FIRMWARE_RET,
            replySub = SonyCommands.DEVICE_INFO_MODEL_SUB,
            timeoutMs = CONTROL_REPLY_TIMEOUT_MS,
        )
        query(
            SonyCommands.supportFunctionGet(),
            SonyResponses.SUPPORT_FUNCTION_RET,
            timeoutMs = CONTROL_REPLY_TIMEOUT_MS,
        )

        val fns = _supportFunctions.value ?: return
        val inq = FirmwareUpdateMethods.updtInquiredType(fns) ?: return
        query(
            UpdtMessages.getCapability(inq),
            UpdtMessages.UPDT_RET_CAPABILITY,
            timeoutMs = CONTROL_REPLY_TIMEOUT_MS,
        )
        query(
            UpdtMessages.getParam(inq),
            UpdtMessages.UPDT_RET_PARAM,
            timeoutMs = CONTROL_REPLY_TIMEOUT_MS,
        )
    }

    // ---- Settings ----------------------------------------------------------

    /**
     * V2 only: read every [SonySettingsCatalog] setting the device advertises
     * (capability first where the option list is device-specific), the
     * general-setting slots, and playback. Separate from [start] so the
     * controls appear before this finishes; replies also keep arriving as
     * notifies afterwards and are folded in by the inbound loop.
     */
    suspend fun discoverSettings() {
        if (_dialect.value != SonyDialect.V2) return
        val fns = _supportFunctions.value ?: return
        val found = ArrayList<SettingState>()
        for (setting in SonySettingsCatalog.ALL) {
            if (setting.function !in fns) continue
            found.add(readSetting(setting))
        }
        for (slot in SonySettingsCatalog.GENERAL_SETTING_SLOTS) {
            if (slot !in fns) continue
            val cap = query(SonySettingsCatalog.generalSettingCapabilityGet(slot), 0xd1, replySub = slot)
            val title = cap?.let { SonySettingsCatalog.generalSettingTitle(it.payload) } ?: continue
            val setting = SonySettingsCatalog.generalSetting(slot, title) ?: continue
            found.add(readSetting(setting))
        }
        _settings.value = found
        _canPowerOff.value = SonyPlayback.POWER_OFF_FUNCTION in fns

        if (SonyPlayback.FUNCTION in fns) {
            val steps = query(SonyPlayback.CAPABILITY_GET, 0xa1, replySub = 0x01)
                ?.let { SonyPlayback.parseCapability(it.payload) }
            if (steps != null && steps > 0) {
                _playback.value = PlaybackState(volumeMax = steps - 1)
                query(SonyPlayback.VOLUME_GET, 0xa7, replySub = 0x20)
                query(SonyPlayback.STATUS_GET, 0xa3, replySub = 0x01)
                query(SonyPlayback.METADATA_GET, 0xa7, replySub = 0x01)
            }
        }
    }

    private suspend fun readSetting(setting: SonySetting): SettingState {
        var options = setting.options
        setting.capabilityGet?.let { cap ->
            val reply = query(cap, (cap[0].toInt() and 0xFF) + 1, replySub = setting.sub)
            reply?.let { SonySettingsCatalog.optionsFromCapability(setting, it.payload) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { options = it }
        }
        val reply = query(setting.getPayload(), setting.retOpcode, replySub = setting.sub)
        return SettingState(setting, options, reply?.let { setting.optionIndex(it.payload, options) })
    }

    /** Select option [index] of the setting with [key]; reflected once acked. */
    suspend fun setSetting(key: String, index: Int) {
        val state = _settings.value.firstOrNull { it.setting.key == key } ?: return
        val option = state.options.getOrNull(index) ?: return
        sendCommand(SonyFrame.TYPE_COMMAND1, state.setting.setPayload(option))
        _settings.value = _settings.value.map { if (it.setting.key == key) it.copy(selected = index) else it }
    }

    suspend fun playbackControl(command: Int) {
        if (_playback.value == null) return
        sendCommand(SonyFrame.TYPE_COMMAND1, SonyPlayback.control(command))
    }

    /** Step the music volume by [delta], clamped to the device's range. */
    suspend fun stepVolume(delta: Int) {
        val p = _playback.value ?: return
        val current = p.volume ?: return
        val next = (current + delta).coerceIn(0, p.volumeMax)
        if (next == current) return
        sendCommand(SonyFrame.TYPE_COMMAND1, SonyPlayback.volumeSet(next))
        _playback.value = p.copy(volume = next)
    }

    /** Turn Auto Ambient Sound on/off, keeping the current mode, voice and level. */
    suspend fun setAutoAmbient(on: Boolean) {
        if (_autoAmbient.value == null) return
        ancNoiseAdaptive = if (on) SonyCommands.NOISE_ADAPTIVE_ON else SonyCommands.NOISE_ADAPTIVE_OFF
        setAnc(_ancMode.value, _ambientLevel.value, _voicePassthrough.value)
        _autoAmbient.value = on
    }

    /** USER_POWER_OFF. The device drops the link without replying. */
    suspend fun powerOff() {
        if (!_canPowerOff.value) return
        sendCommand(SonyFrame.TYPE_COMMAND1, SonyPlayback.POWER_OFF)
    }

    /** Fold a RET/NTFY for a known setting or playback message into state. */
    private fun applySettingEvent(payload: ByteArray): Boolean {
        val list = _settings.value
        val i = list.indexOfFirst { it.setting.matches(payload) }
        if (i >= 0) {
            val state = list[i]
            val selected = state.setting.optionIndex(payload, state.options) ?: return true
            if (selected != state.selected) {
                _settings.value = list.toMutableList().also { it[i] = state.copy(selected = selected) }
            }
            return true
        }
        val p = _playback.value ?: return false
        SonyPlayback.parseVolume(payload)?.let { _playback.value = p.copy(volume = it); return true }
        SonyPlayback.parsePlaying(payload)?.let { _playback.value = p.copy(playing = it); return true }
        SonyPlayback.parseTrack(payload)?.let { _playback.value = p.copy(track = it); return true }
        return false
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
        val payload = SonyCommands.ancSet(
            dialect, ancSubByte, ancWind, mode, level, voicePassthrough,
            noiseAdaptive = ancNoiseAdaptive,
            adaptiveSensitivity = ancAdaptiveSensitivity,
        )
        sendCommand(SonyFrame.TYPE_COMMAND1, payload)
        _ancMode.value = mode
        _ambientLevel.value = level.coerceIn(0, 20)
        _voicePassthrough.value = voicePassthrough
    }

    // ---- Send / receive plumbing ------------------------------------------

    /**
     * Write a frame with the current [seq] and wait for its Ack; on timeout
     * resend the IDENTICAL frame (same seq, per spec-tandem-fota §1.3) up to
     * [maxResends] times. Returns false if no Ack ever arrived.
     *
     * Serialised by [sendMutex] so only one frame is ever outstanding
     * (stop-and-wait, window size 1). A transport write failure counts as a
     * failed attempt rather than propagating: callers decide what a dead link
     * means, and the FOTA install phase expects the link to drop.
     */
    suspend fun sendReliable(type: Int, payload: ByteArray, ackTimeoutMs: Long, maxResends: Int): Boolean =
        sendMutex.withLock {
            // Encode once so every resend is byte-identical, seq included.
            val frame = SonyFrame.encode(type, seq, payload)
            val expectedAckSeq = (1 - seq) and 0xFF
            var attempt = 0
            while (attempt <= maxResends) {
                val ack = CompletableDeferred<Unit>()
                pendingAck = ack
                pendingAckSeq = expectedAckSeq
                val written = try {
                    connection.write(frame)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                val acked = if (written) withTimeoutOrNull(ackTimeoutMs) { ack.await() } != null else null
                if (pendingAck === ack) { // clear on timeout / failure
                    pendingAck = null
                    pendingAckSeq = -1
                }
                if (acked == true) return@withLock true
                attempt++
            }
            false
        }

    /**
     * Encode and write a command with the current [seq], then wait for its Ack
     * before returning. Times out after [ACK_TIMEOUT_MS] so a lost Ack cannot
     * wedge the driver forever; no resend, matching the pre-FOTA behaviour.
     */
    private suspend fun sendCommand(type: Int, payload: ByteArray) {
        sendReliable(type, payload, ACK_TIMEOUT_MS, maxResends = 0)
    }

    /**
     * Send a command (awaiting its Ack) and then await a reply whose opcode is
     * [replyOpcode] (and, when given, whose `payload[1]` is [replySub]), up to
     * [timeoutMs]. Returns the reply message, or null if none arrived in time.
     * The pending-reply slot is registered BEFORE the command is written so a
     * fast reply cannot be missed.
     */
    private suspend fun query(
        payload: ByteArray,
        replyOpcode: Int,
        replySub: Int = -1,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): SonyMessage? = queryMutex.withLock {
        val deferred = CompletableDeferred<SonyMessage>()
        pendingQueryOpcode = replyOpcode
        pendingQuerySub = replySub
        pendingQuery = deferred
        try {
            sendCommand(SonyFrame.TYPE_COMMAND1, payload)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingQuery = null
            pendingQueryOpcode = -1
            pendingQuerySub = -1
        }
    }

    private fun onChunk(chunk: ByteArray) {
        for (message in decoder.feed(chunk)) handleMessage(message)
    }

    private fun handleMessage(message: SonyMessage) {
        when (message.type) {
            SonyFrame.TYPE_ACK -> {
                // Ignore an Ack that is not for the outstanding frame: a stale
                // duplicate (the device Acking a resend twice) would otherwise
                // complete the NEXT frame's wait and desync the seq.
                if (message.seq == pendingAckSeq) {
                    // The device's Ack seq is (1 - our command's seq); adopting
                    // it toggles our sequence number for the next command.
                    seq = message.seq
                    pendingAckSeq = -1
                    val ack = pendingAck
                    pendingAck = null
                    ack?.complete(Unit)
                }
            }

            SonyFrame.TYPE_COMMAND1, SonyFrame.TYPE_COMMAND2 -> {
                val payload = message.payload
                val opcode = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else -1

                _notifications.tryEmit(message)

                if (opcode == SonyResponses.INIT_REPLY_MARKER) {
                    val d = pendingInitReply
                    if (d != null && !d.isCompleted) d.complete(message)
                } else {
                    _dialect.value?.let { applyEvent(it, message) }
                }

                // Complete an outstanding reply query, if this matches.
                val subMatches = pendingQuerySub == -1 ||
                    (payload.size >= 2 && (payload[1].toInt() and 0xFF) == pendingQuerySub)
                if (opcode != -1 && opcode == pendingQueryOpcode && subMatches) {
                    val q = pendingQuery
                    pendingQuery = null
                    pendingQueryOpcode = -1
                    pendingQuerySub = -1
                    q?.complete(message)
                }

                // Auto-Ack every device Command1/Command2 with seq = (1 - msg.seq).
                // A dead link must not tear the driver down: the FOTA install
                // phase expects to keep receiving after the socket drops.
                val ackSeq = (1 - message.seq) and 0xFF
                scope.launch {
                    try {
                        connection.write(SonyFrame.encode(SonyFrame.TYPE_ACK, ackSeq, EMPTY_PAYLOAD))
                    } catch (_: Exception) {
                        // ignored: nothing to do about an un-Ackable notification
                    }
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
                event.status.noiseAdaptive?.let {
                    ancNoiseAdaptive = it
                    _autoAmbient.value = it == SonyCommands.NOISE_ADAPTIVE_ON
                }
                event.status.adaptiveSensitivity?.let { ancAdaptiveSensitivity = it }
            }
            is SonyEvent.Battery -> {
                val kind = SonyResponses.batteryReplyKind(dialect, message.payload)
                val batt = event.battery
                if (kind != null) {
                    // Overlay this reply's non-null fields onto the running
                    // accumulators (never overwrite a known value with null).
                    batt.single?.let { battSingle = it }
                    batt.left?.let { battLeft = it }
                    batt.right?.let { battRight = it }
                    batt.case?.let { battCase = it }
                    if (kind == SonyResponses.SonyBatteryKind.DUAL) dualSeen = true
                    _battery.value = SonyBattery(
                        single = battSingle,
                        left = battLeft,
                        right = battRight,
                        case = if (dualSeen) battCase else null,
                    )
                }
            }
            is SonyEvent.Firmware -> {
                _firmwareVersion.value = event.version
            }
            is SonyEvent.ModelName -> {
                _modelName.value = event.name
            }
            is SonyEvent.SupportFunctions -> {
                _supportFunctions.value = event.functions
                _updateMethod.value = FirmwareUpdateMethods.fromSupportFunctions(event.functions)
            }
            null -> if (!applySettingEvent(message.payload)) applyUpdtEvent(message)
        }
    }

    /**
     * UPDT replies the pure protocol parsers do not own (they live in the
     * `update` package). Only the two cached-state messages are handled here;
     * the FOTA state machine reads everything else off [notifications].
     */
    private fun applyUpdtEvent(message: SonyMessage) {
        val payload = message.payload
        when (if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else -1) {
            UpdtMessages.UPDT_RET_CAPABILITY ->
                UpdtMessages.parseCapability(payload)?.let { _updateCapability.value = it }
            UpdtMessages.UPDT_RET_PARAM ->
                UpdtMessages.parseParam(payload)?.let { _updateParams.value = it }
        }
    }

    companion object {
        /** Ack timeout + resend budget for UPDT control frames (Command1). */
        const val CONTROL_ACK_TIMEOUT_MS = 2000L
        const val CONTROL_RESENDS = 3

        /** Ack timeout + resend budget for firmware chunks (spec-tandem-fota §1.3). */
        const val LARGE_ACK_TIMEOUT_MS = 5000L
        const val LARGE_RESENDS = 2

        private const val INIT_RETRY_MS = 1500L
        private const val INIT_MAX_ATTEMPTS = 3
        private const val ACK_TIMEOUT_MS = 2000L
        private const val REPLY_TIMEOUT_MS = 1000L

        /** Reply timeout for the slower device-info / UPDT discovery queries. */
        private const val CONTROL_REPLY_TIMEOUT_MS = 2000L

        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
