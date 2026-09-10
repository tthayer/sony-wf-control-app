package com.thelightphone.sonywf

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.bluetooth.LightBluetoothException
import com.thelightphone.sdk.bluetooth.LightBluetoothSerial
import com.thelightphone.sdk.bluetooth.LightSerialConnection
import com.thelightphone.sonywf.protocol.AncMode
import com.thelightphone.sonywf.protocol.SonyBattery
import com.thelightphone.sonywf.protocol.SonyProtocolClient
import com.thelightphone.sonywf.update.AvailableUpdate
import com.thelightphone.sonywf.update.FirmwareImage
import com.thelightphone.sonywf.update.FirmwareUpdateChecker
import com.thelightphone.sonywf.update.FirmwareUpdateMethod
import com.thelightphone.sonywf.update.FirmwareUpdateMethods
import com.thelightphone.sonywf.update.FotaFailure
import com.thelightphone.sonywf.update.FotaPhase
import com.thelightphone.sonywf.update.MtkUpdateController
import com.thelightphone.sonywf.update.TandemFotaSession
import com.thelightphone.sonywf.update.UpdateCheck
import com.thelightphone.sonywf.update.UpdateParams
import com.thelightphone.sonywf.update.airoha.AirohaDiagnostics
import com.thelightphone.sonywf.update.airoha.RaceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
     * [firmwareVersion] is null until the device answers the firmware query.
     * [updateMethod] is null until discovered; the screen keys the update copy
     * and the DIAG affordance off it, since MTK and Tandem installs differ.
     */
    data class Connected(
        val model: String,
        val ancSupported: Boolean,
        val mode: AncMode,
        val ambientLevel: Int,
        val voicePassthrough: Boolean,
        val battery: SonyBattery,
        val firmwareVersion: String?,
        val updateMethod: FirmwareUpdateMethod?,
        val update: FirmwareUpdateUi,
    ) : SonyUiState

    /** The connection dropped or errored. */
    data class Failed(val message: String) : SonyUiState
}

/** The four inputs the update check needs; see docs/protocol/fw-update-design.md §8. */
private data class UpdateInputs(
    val method: FirmwareUpdateMethod,
    val params: UpdateParams?,
    val firmwareVersion: String?,
    val model: String,
)

class SonyWfViewModel(
    private val bluetooth: LightBluetoothSerial,
    private val checker: FirmwareUpdateChecker = FirmwareUpdateChecker(),
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<SonyUiState>(SonyUiState.Connecting)
    val state: StateFlow<SonyUiState> = _state.asStateFlow()

    private val _update = MutableStateFlow<FirmwareUpdateUi>(FirmwareUpdateUi.Unknown)

    private var connection: LightSerialConnection? = null
    private var client: SonyProtocolClient? = null
    private var connectJob: Job? = null
    private var mirrorJob: Job? = null
    private var checkJob: Job? = null
    private var updateJob: Job? = null
    private var diagJob: Job? = null

    /** Bluetooth name of the connected device; the feed model falls back to it. */
    private var deviceName: String = DEFAULT_DEVICE_NAME

    /** Address + service UUID that actually connected, so the install can redial. */
    private var deviceAddress: String? = null
    private var serviceUuid: UUID? = null

    /** True while the screen is hidden / the app is paused. */
    private var hidden: Boolean = false

    /** Last check result, kept so CANCEL / BACK can restore the Available line. */
    private var pendingUpdate: AvailableUpdate? = null
    private var pendingInstallable: Boolean = false
    private var fotaSession: TandemFotaSession? = null
    private var mtkController: MtkUpdateController? = null

    /** Update state the DIAG report covered up, so OK can put it straight back. */
    private var preDiagnostics: FirmwareUpdateUi? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        hidden = false
        connect()
    }

    // Tearing the link down mid-update would brick the transfer, so hold it open;
    // [publishTerminal] releases it once the update finishes.
    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        hidden = true
        if (!updateInProgress()) teardown()
    }

    override fun onAppPause() {
        hidden = true
        if (!updateInProgress()) teardown()
    }

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
                        deviceName = device.name ?: DEFAULT_DEVICE_NAME
                        deviceAddress = device.address
                        serviceUuid = uuid
                        mirrorState(protocol, deviceName)
                        startUpdateCheck(protocol)
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
        // Two-stage combine: kotlinx `combine` only has typed overloads up to five flows.
        val core = combine(
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
                firmwareVersion = null,
                updateMethod = null,
                update = FirmwareUpdateUi.Unknown,
            )
        }
        mirrorJob = viewModelScope.launch {
            combine(
                core,
                protocol.firmwareVersion,
                protocol.updateMethod,
                _update,
            ) { base, firmware, method, update ->
                base.copy(firmwareVersion = firmware, updateMethod = method, update = update)
            }.collect { _state.value = it }
        }
    }

    /**
     * Once the device has reported an update method (plus params, firmware and a
     * model name), run one feed check. Runs once per connection; re-armed only by
     * [dismissUpdateResult] after a completed install.
     */
    private fun startUpdateCheck(protocol: SonyProtocolClient) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            val inputs = withTimeoutOrNull(UPDATE_INPUTS_TIMEOUT_MS) {
                combine(
                    protocol.updateMethod,
                    protocol.updateParams,
                    protocol.firmwareVersion,
                    protocol.modelName,
                ) { method, params, firmware, modelName ->
                    method?.let { UpdateInputs(it, params, firmware, modelName ?: deviceName) }
                }.first {
                    it != null &&
                        (it.method == FirmwareUpdateMethod.NONE || (it.params != null && it.firmwareVersion != null))
                }
            }
            if (inputs == null) {
                // Nothing usable arrived; say nothing rather than guessing.
                _update.value = FirmwareUpdateUi.Unknown
                return@launch
            }
            if (inputs.method == FirmwareUpdateMethod.NONE) {
                _update.value = FirmwareUpdateUi.Unsupported("No update support")
                return@launch
            }
            val params = inputs.params
            val firmware = inputs.firmwareVersion
            if (params == null || firmware == null) {
                _update.value = FirmwareUpdateUi.Unknown
                return@launch
            }
            _update.value = FirmwareUpdateUi.Checking
            val result = try {
                checker.check(params, inputs.model, firmware)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateCheck.Error(e.message ?: "Update check failed")
            }
            _update.value = when (result) {
                is UpdateCheck.UpToDate -> FirmwareUpdateUi.UpToDate
                is UpdateCheck.Error -> FirmwareUpdateUi.Failed(result.message)
                is UpdateCheck.Available -> {
                    // Tandem over MDR and MTK over Airoha RACE can both be driven
                    // from here; MC_APP has no transport of ours, so it stays check-only.
                    val installable = inputs.method == FirmwareUpdateMethod.TANDEM ||
                        inputs.method == FirmwareUpdateMethod.MTK
                    pendingUpdate = result.update
                    pendingInstallable = installable
                    FirmwareUpdateUi.Available(result.update.version, result.update.sizeBytes, installable)
                }
            }
        }
    }

    /** UPDATE: ask for confirmation before touching the firmware. */
    fun startUpdate() {
        val available = _update.value as? FirmwareUpdateUi.Available ?: return
        if (!available.installable) return
        _update.value = FirmwareUpdateUi.Confirming(available.version)
    }

    /** START: battery gate, then download and transfer. */
    fun confirmUpdate() {
        if (_update.value !is FirmwareUpdateUi.Confirming) return
        if (updateJob?.isActive == true) return
        val connected = _state.value as? SonyUiState.Connected ?: return
        val protocol = client ?: return
        val available = pendingUpdate ?: run {
            _update.value = FirmwareUpdateUi.Failed("Update details missing")
            return
        }
        val threshold = protocol.updateParams.value?.batteryThreshold ?: 0
        if (threshold > 0) {
            val levels = knownBatteryLevels(connected.battery)
            if (levels.isEmpty()) {
                // Flashing on an unknown charge is how a device gets bricked.
                _update.value = FirmwareUpdateUi.Failed("Battery level unknown")
                return
            }
            if (levels.any { it <= threshold }) {
                _update.value = FirmwareUpdateUi.Failed("Charge above $threshold% first")
                return
            }
        }
        updateJob = viewModelScope.launch {
            _update.value = FirmwareUpdateUi.Downloading(0)
            val image = try {
                checker.download(available) { percent ->
                    _update.value = FirmwareUpdateUi.Downloading(percent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publishTerminal(FirmwareUpdateUi.Failed(e.message ?: "Download failed"))
                return@launch
            }
            val terminal = when (protocol.updateMethod.value) {
                FirmwareUpdateMethod.TANDEM -> runTandemUpdate(protocol, image, available)
                FirmwareUpdateMethod.MTK -> runMtkUpdate(protocol, image, available)
                else -> FotaPhase.Failed(FotaFailure.OTHER, "no install path for this device")
            }
            publishTerminal(phaseToUi(terminal, available))
        }
    }

    /** Firmware inside the MDR link (spec-tandem-fota). */
    private suspend fun runTandemUpdate(
        protocol: SonyProtocolClient,
        image: FirmwareImage,
        available: AvailableUpdate,
    ): FotaPhase = coroutineScope {
        val session = TandemFotaSession(protocol, reconnect = { reconnectForUpdate() })
        fotaSession = session
        val phaseMirror = launch {
            session.phase.collect { _update.value = phaseToUi(it, available) }
        }
        try {
            session.run(image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FotaPhase.Failed(FotaFailure.OTHER, e.message ?: "Update failed")
        } finally {
            phaseMirror.cancel()
            fotaSession = null
        }
    }

    /**
     * Firmware over the Airoha RACE socket (docs/protocol/fw-update-design.md §10):
     * a second, separate RFCOMM link, while the MDR link stays up to arm and
     * disarm the update mode.
     */
    private suspend fun runMtkUpdate(
        protocol: SonyProtocolClient,
        image: FirmwareImage,
        available: AvailableUpdate,
    ): FotaPhase = coroutineScope {
        val inquiredType =
            FirmwareUpdateMethods.updtInquiredType(protocol.supportFunctions.value ?: emptySet())
                ?: return@coroutineScope FotaPhase.Failed(
                    FotaFailure.OTHER,
                    "no update inquired type",
                )
        val address = deviceAddress
            ?: return@coroutineScope FotaPhase.Failed(FotaFailure.OTHER, "device address unknown")
        val controller = MtkUpdateController(
            protocol,
            openAiroha = { bluetooth.connect(address, UUID.fromString(AirohaDiagnostics.SPP_UUID)) },
            reconnect = { reconnectForUpdate() },
            scope = viewModelScope,
            // A flash runs for ~25 min on real hardware; logcat is the only
            // record of what the device actually answered.
            trace = { Log.i("SonyWfFota", it) },
        )
        mtkController = controller
        val phaseMirror = launch {
            controller.phase.collect { _update.value = phaseToUi(it, available) }
        }
        try {
            controller.run(image, inquiredType, protocol.updateCapability.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FotaPhase.Failed(FotaFailure.OTHER, e.message ?: "Update failed")
        } finally {
            phaseMirror.cancel()
            mtkController = null
        }
    }

    /**
     * Publish a terminal update state. The link is only held open past
     * [onScreenHide] / [onAppPause] for the update's sake, so release it now.
     */
    private fun publishTerminal(ui: FirmwareUpdateUi) {
        _update.value = ui
        if (hidden) teardown()
    }

    /**
     * Redial the device mid-install (spec-tandem-fota §4.7): the reboot kills the
     * RFCOMM socket, so the old client is discarded and a fresh one started on the
     * same address + service UUID. Returns null while the device is still away.
     */
    private suspend fun reconnectForUpdate(): SonyProtocolClient? {
        val address = deviceAddress ?: return null
        val uuid = serviceUuid ?: return null

        client?.let { runCatching { it.stop() } }
        connection?.let { runCatching { it.close() } }
        client = null
        connection = null

        val conn = try {
            bluetooth.connect(address, uuid)
        } catch (e: LightBluetoothException) {
            null
        } ?: return null
        val protocol = SonyProtocolClient(conn, viewModelScope)
        try {
            protocol.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { protocol.stop() }
            runCatching { conn.close() }
            return null
        }
        connection = conn
        client = protocol
        mirrorState(protocol, deviceName)
        return protocol
    }

    /**
     * DIAG: one read-only Airoha RACE probe (docs/protocol/spec-airoha-fota.md
     * §2, §3.7) on a SEPARATE secure RFCOMM socket, so nothing here touches the
     * live Sony MDR link. Offered on Airoha chips (MTK, which the MTK install
     * path uses anyway) and where we cannot flash from here at all: a check-only
     * [FirmwareUpdateUi.Available] or [FirmwareUpdateUi.Unsupported].
     *
     * Every line is logged as well as shown: the report is the point of the run,
     * and logcat survives the screen going away.
     */
    fun runAirohaDiagnostics() {
        // A second socket mid-update would fight the transfer for the radio.
        if (updateInProgress()) return
        val method = client?.updateMethod?.value
        val current = _update.value
        val eligible = when (current) {
            is FirmwareUpdateUi.Available ->
                !current.installable || method == FirmwareUpdateMethod.MTK
            is FirmwareUpdateUi.Unsupported -> true
            else -> false
        }
        if (!eligible) return
        if (diagJob?.isActive == true) return
        val address = deviceAddress ?: return

        preDiagnostics = current
        diagJob = viewModelScope.launch {
            _update.value = FirmwareUpdateUi.Diagnostics(listOf("probing…"))
            var conn: LightSerialConnection? = null
            var race: RaceClient? = null
            val lines = try {
                val opened = bluetooth.connect(address, UUID.fromString(AirohaDiagnostics.SPP_UUID))
                conn = opened
                val client = RaceClient(opened, viewModelScope)
                race = client
                client.start()
                AirohaDiagnostics.run(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listOf("error: ${e.message ?: e.toString()}")
            } finally {
                race?.stop() // also closes the socket
                runCatching { conn?.close() }
            }
            for (line in lines) Log.i(DIAG_TAG, line)
            _update.value = FirmwareUpdateUi.Diagnostics(lines)
        }
    }

    /** OK on the DIAG report: restore what it covered, or re-run the check. */
    fun dismissDiagnostics() {
        if (_update.value !is FirmwareUpdateUi.Diagnostics) return
        diagJob?.cancel(); diagJob = null
        val back = preDiagnostics
        preDiagnostics = null
        if (back != null && back !is FirmwareUpdateUi.Diagnostics) {
            _update.value = back
        } else {
            recheck()
        }
    }

    /** CANCEL / BACK: abandon the confirmation, the download, or the transfer. */
    fun cancelUpdate() {
        when (_update.value) {
            is FirmwareUpdateUi.Confirming -> _update.value = availableUi()
            // Cancelling the job aborts the in-flight HTTP read (HttpsUrlFetcher
            // checks the job between reads).
            is FirmwareUpdateUi.Downloading -> {
                updateJob?.cancel(); updateJob = null
                publishTerminal(availableUi())
            }
            // The device must be told, otherwise it stays in FW-update mode.
            is FirmwareUpdateUi.Transferring -> {
                val session = fotaSession
                val mtk = mtkController
                if (session == null && mtk == null) return
                viewModelScope.launch {
                    runCatching { session?.cancel() }
                    runCatching { mtk?.cancel() }
                }
            }
            else -> Unit
        }
    }

    /** OK on a finished or failed update. */
    fun dismissUpdateResult() {
        when (_update.value) {
            is FirmwareUpdateUi.Failed -> {
                val back = availableUi()
                if (back is FirmwareUpdateUi.Available) {
                    _update.value = back
                } else {
                    recheck()
                }
            }
            // The installed version changed, so the cached result is stale.
            is FirmwareUpdateUi.Completed -> {
                pendingUpdate = null
                pendingInstallable = false
                recheck()
            }
            else -> Unit
        }
    }

    private fun recheck() {
        val protocol = client
        if (protocol == null) {
            _update.value = FirmwareUpdateUi.Unknown
            return
        }
        _update.value = FirmwareUpdateUi.Unknown
        startUpdateCheck(protocol)
    }

    private fun availableUi(): FirmwareUpdateUi {
        val update = pendingUpdate ?: return FirmwareUpdateUi.Unknown
        return FirmwareUpdateUi.Available(update.version, update.sizeBytes, pendingInstallable)
    }

    /** The bud/headset levels the device actually reports; the case is irrelevant. */
    private fun knownBatteryLevels(battery: SonyBattery): List<Int> =
        listOfNotNull(battery.single, battery.left, battery.right)

    private fun updateInProgress(): Boolean = when (_update.value) {
        is FirmwareUpdateUi.Downloading,
        is FirmwareUpdateUi.Transferring,
        is FirmwareUpdateUi.Installing,
        -> true
        else -> false
    }

    private fun phaseToUi(phase: FotaPhase, update: AvailableUpdate): FirmwareUpdateUi = when (phase) {
        FotaPhase.Idle, FotaPhase.EnteringMode -> FirmwareUpdateUi.Transferring(0)
        is FotaPhase.Transferring -> FirmwareUpdateUi.Transferring(phase.percent)
        FotaPhase.Finishing -> FirmwareUpdateUi.Transferring(100)
        FotaPhase.Executing -> FirmwareUpdateUi.Installing(0)
        is FotaPhase.Installing -> FirmwareUpdateUi.Installing(phase.percent)
        FotaPhase.Completed -> FirmwareUpdateUi.Completed(update.version)
        FotaPhase.Cancelled -> FirmwareUpdateUi.Available(update.version, update.sizeBytes, pendingInstallable)
        is FotaPhase.Failed -> FirmwareUpdateUi.Failed(failureMessage(phase))
    }

    private fun failureMessage(failed: FotaPhase.Failed): String = when (failed.reason) {
        FotaFailure.NEED_CHARGE -> "Charge the headphones, then try again"
        FotaFailure.BATTERY_HOT -> "Headphones too warm; try again later"
        FotaFailure.DEVICE_REFUSED -> "Headphones refused the update"
        FotaFailure.TIMEOUT -> "Update timed out"
        FotaFailure.TRANSFER_FAILED -> "Transfer failed"
        FotaFailure.CANCELLED_BY_DEVICE -> "Headphones cancelled the update"
        FotaFailure.DISCONNECTED -> "Headphones disconnected"
        FotaFailure.OTHER -> failed.detail.ifBlank { "Update failed" }
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
        checkJob?.cancel(); checkJob = null
        updateJob?.cancel(); updateJob = null
        diagJob?.cancel(); diagJob = null
        fotaSession = null
        mtkController = null
        preDiagnostics = null
        pendingUpdate = null
        pendingInstallable = false
        _update.value = FirmwareUpdateUi.Unknown
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

        private const val DEFAULT_DEVICE_NAME = "Sony headphones"

        /** start() populates these before returning; this only guards a silent device. */
        private const val UPDATE_INPUTS_TIMEOUT_MS = 5_000L

        /** Logcat tag for the read-only Airoha probe report. */
        private const val DIAG_TAG = "SonyWfDiag"
    }
}
