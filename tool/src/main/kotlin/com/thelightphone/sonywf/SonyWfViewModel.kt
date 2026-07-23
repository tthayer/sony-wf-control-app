package com.thelightphone.sonywf

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.bluetooth.LightBluetoothException
import com.thelightphone.sdk.bluetooth.LightBluetoothSerial
import com.thelightphone.sdk.bluetooth.LightSerialConnection
import com.thelightphone.sonywf.protocol.AncMode
import com.thelightphone.sonywf.protocol.SonyBattery
import com.thelightphone.sonywf.protocol.SonyProtocolClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/** UI state for the Sony headphone control screen. */
sealed interface SonyUiState {
    /** Probing paired devices / running the Init handshake. */
    data object Connecting : SonyUiState

    /** No paired device answered the Sony protocol. */
    data object NotFound : SonyUiState

    /** Bluetooth is unavailable on this device (e.g. the emulator has no radio). */
    data object Unsupported : SonyUiState

    /**
     * Connected and live. [model] is the Bluetooth device name (the closest thing
     * to a model, since the protocol has no model query). [ancSupported] gates the
     * ANC controls; [battery] carries whatever the device actually reports.
     */
    data class Connected(
        val model: String,
        val ancSupported: Boolean,
        val mode: AncMode,
        val ambientLevel: Int,
        val voicePassthrough: Boolean,
        val battery: SonyBattery,
    ) : SonyUiState

    /** The connection dropped or errored. */
    data class Failed(val message: String) : SonyUiState
}

class SonyWfViewModel(
    private val bluetooth: LightBluetoothSerial,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<SonyUiState>(SonyUiState.Connecting)
    val state: StateFlow<SonyUiState> = _state.asStateFlow()

    private var connection: LightSerialConnection? = null
    private var client: SonyProtocolClient? = null
    private var connectJob: Job? = null
    private var mirrorJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = connect()

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = teardown()

    override fun onAppPause() = teardown()

    /**
     * Probe paired devices for a Sony-protocol headphone and connect to the first
     * that completes the Init handshake. Sony-named devices are tried first; the v2
     * service UUID is tried before v1. No hardcoded model list — whatever answers
     * the handshake is used, and its capabilities are discovered at runtime.
     */
    private fun connect() {
        if (connectJob?.isActive == true || client != null) return
        if (!bluetooth.isSupported) {
            _state.value = SonyUiState.Unsupported
            return
        }
        _state.value = SonyUiState.Connecting
        connectJob = viewModelScope.launch {
            try {
                val paired = bluetooth.pairedDevices()
                // Sony-named devices first; only fall back to probing others if none match.
                val sony = paired.filter { isLikelySony(it.name) }
                val candidates = if (sony.isNotEmpty()) sony else paired
                for (device in candidates) {
                    for (uuid in listOf(SONY_SERVICE_UUID_V2, SONY_SERVICE_UUID_V1)) {
                        val conn = try {
                            bluetooth.connect(device.address, uuid)
                        } catch (e: LightBluetoothException) {
                            null
                        } ?: continue
                        val protocol = SonyProtocolClient(conn, viewModelScope)
                        try {
                            protocol.start() // throws if the device isn't speaking the Sony protocol
                        } catch (e: Exception) {
                            runCatching { protocol.stop() }
                            runCatching { conn.close() }
                            continue
                        }
                        connection = conn
                        client = protocol
                        mirrorState(protocol, device.name ?: "Sony headphones")
                        return@launch
                    }
                }
                _state.value = SonyUiState.NotFound
            } catch (e: LightBluetoothException) {
                _state.value = SonyUiState.Failed(e.message ?: "Could not connect")
            }
        }
    }

    /** Collapse the client's flows into a single Connected state. */
    private fun mirrorState(protocol: SonyProtocolClient, model: String) {
        mirrorJob?.cancel()
        mirrorJob = viewModelScope.launch {
            combine(
                protocol.ancMode,
                protocol.ambientLevel,
                protocol.voicePassthrough,
                protocol.battery,
                protocol.ancSupported,
            ) { mode, level, voice, battery, ancSupported ->
                SonyUiState.Connected(
                    model = model,
                    ancSupported = ancSupported,
                    mode = mode,
                    ambientLevel = level,
                    voicePassthrough = voice,
                    battery = battery,
                )
            }.collect { _state.value = it }
        }
    }

    /** Cycle Off → Noise-Cancel → Ambient → Off. */
    fun cycleMode() {
        val s = _state.value as? SonyUiState.Connected ?: return
        if (!s.ancSupported) return
        val next = when (s.mode) {
            AncMode.OFF -> AncMode.ANC
            AncMode.ANC -> AncMode.AMBIENT
            AncMode.AMBIENT -> AncMode.OFF
        }
        viewModelScope.launch {
            runCatching { client?.setAnc(next, s.ambientLevel, s.voicePassthrough) }
        }
    }

    /** Step the ambient level 0 → 5 → 10 → 15 → 20 → 0 (only meaningful in Ambient). */
    fun cycleAmbientLevel() {
        val s = _state.value as? SonyUiState.Connected ?: return
        if (!s.ancSupported || s.mode != AncMode.AMBIENT) return
        val base = (s.ambientLevel / 5) * 5
        val next = (base + 5).let { if (it > 20) 0 else it }
        viewModelScope.launch {
            runCatching { client?.setAnc(AncMode.AMBIENT, next, s.voicePassthrough) }
        }
    }

    /** Toggle Sony "Focus on Voice" (voice passthrough). */
    fun toggleVoice() {
        val s = _state.value as? SonyUiState.Connected ?: return
        if (!s.ancSupported) return
        viewModelScope.launch {
            runCatching { client?.setAnc(s.mode, s.ambientLevel, !s.voicePassthrough) }
        }
    }

    /** Retry after a failure / not-found. */
    fun retry() {
        teardown()
        connect()
    }

    private fun teardown() {
        mirrorJob?.cancel(); mirrorJob = null
        connectJob?.cancel(); connectJob = null
        client?.stop(); client = null
        connection?.close(); connection = null
    }

    override fun onBackPressed(): Boolean = false

    private fun isLikelySony(name: String?): Boolean {
        if (name == null) return false
        return SONY_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
    }

    companion object {
        /** Sony's proprietary RFCOMM service UUID for the v2 protocol dialect. */
        val SONY_SERVICE_UUID_V2: UUID =
            UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")

        /** Sony's proprietary RFCOMM service UUID for the older v1 dialect. */
        val SONY_SERVICE_UUID_V1: UUID =
            UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")

        private val SONY_NAME_HINTS = listOf("WF-", "WH-", "WI-", "LinkBuds", "Sony")
    }
}
