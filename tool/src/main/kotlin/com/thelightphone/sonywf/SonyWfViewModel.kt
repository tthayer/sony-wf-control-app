package com.thelightphone.sonywf

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.bluetooth.LightBluetoothException
import com.thelightphone.sdk.bluetooth.LightBluetoothSerial
import com.thelightphone.sdk.bluetooth.LightSerialConnection
import com.thelightphone.sonywf.protocol.AncMode
import com.thelightphone.sonywf.protocol.SonyProtocolClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/** UI state for the Sony WF control screen. */
sealed interface SonyUiState {
    /** Opening the RFCOMM link / running the Init handshake. */
    data object Connecting : SonyUiState

    /** No WF-1000XM5 is paired — the user must pair in system Bluetooth settings. */
    data object NotPaired : SonyUiState

    /** Bluetooth is unavailable on this device (e.g. the emulator has no radio). */
    data object Unsupported : SonyUiState

    /** Connected and live. Values mirror the earbuds' current state. */
    data class Connected(
        val mode: AncMode,
        val ambientLevel: Int,
        val voicePassthrough: Boolean,
        val leftBattery: Int?,
        val rightBattery: Int?,
        val caseBattery: Int?,
    ) : SonyUiState

    /** The connection dropped or never established. */
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

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        connect()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = teardown()

    override fun onAppPause() = teardown()

    /** Find the paired WF-1000XM5, open the link, start the protocol, mirror its state. */
    private fun connect() {
        if (connectJob?.isActive == true || client != null) return
        if (!bluetooth.isSupported) {
            _state.value = SonyUiState.Unsupported
            return
        }
        _state.value = SonyUiState.Connecting
        connectJob = viewModelScope.launch {
            try {
                // Match the WF-1000X family (XM5, and the newer XM6 which speaks
                // the same serial protocol) by name prefix.
                val device = bluetooth.pairedDevices()
                    .firstOrNull { it.name?.contains("WF-1000X", ignoreCase = true) == true }
                if (device == null) {
                    _state.value = SonyUiState.NotPaired
                    return@launch
                }
                val conn = bluetooth.connect(device.address, SONY_SERVICE_UUID)
                connection = conn
                val protocol = SonyProtocolClient(conn, viewModelScope)
                client = protocol
                protocol.start()
                mirrorState(protocol)
                // Pull the current state up front so the UI isn't blank.
                protocol.refreshAncStatus()
                protocol.refreshBattery()
            } catch (e: LightBluetoothException) {
                _state.value = SonyUiState.Failed(e.message ?: "Could not connect to earbuds")
            }
        }
    }

    /** Collapse the client's individual StateFlows into a single Connected state. */
    private fun mirrorState(protocol: SonyProtocolClient) {
        mirrorJob?.cancel()
        val anc = combine(
            protocol.ancMode,
            protocol.ambientLevel,
            protocol.voicePassthrough,
        ) { mode, level, voice -> Triple(mode, level, voice) }
        val battery = combine(
            protocol.leftBattery,
            protocol.rightBattery,
            protocol.caseBattery,
        ) { left, right, case -> Triple(left, right, case) }
        mirrorJob = viewModelScope.launch {
            combine(anc, battery) { a, b ->
                SonyUiState.Connected(
                    mode = a.first,
                    ambientLevel = a.second,
                    voicePassthrough = a.third,
                    leftBattery = b.first,
                    rightBattery = b.second,
                    caseBattery = b.third,
                )
            }.collect { _state.value = it }
        }
    }

    /** Cycle Off → Noise-Cancel → Ambient → Off. */
    fun cycleMode() {
        val s = _state.value as? SonyUiState.Connected ?: return
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
        if (s.mode != AncMode.AMBIENT) return
        val base = (s.ambientLevel / 5) * 5
        val next = (base + 5).let { if (it > 20) 0 else it }
        viewModelScope.launch {
            runCatching { client?.setAnc(AncMode.AMBIENT, next, s.voicePassthrough) }
        }
    }

    /** Toggle Sony "Focus on Voice" (voice passthrough). */
    fun toggleVoice() {
        val s = _state.value as? SonyUiState.Connected ?: return
        viewModelScope.launch {
            runCatching { client?.setAnc(s.mode, s.ambientLevel, !s.voicePassthrough) }
        }
    }

    /** Retry after a failure / NotPaired. */
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

    companion object {
        /** Sony's proprietary RFCOMM/SPP service UUID (not the standard SPP UUID). */
        val SONY_SERVICE_UUID: UUID =
            UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")
    }
}
