package com.thelightphone.sonywf.update

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import com.thelightphone.sonywf.protocol.SonyFrame
import com.thelightphone.sonywf.protocol.SonyProtocolClient
import com.thelightphone.sonywf.update.airoha.AirohaFotaSession
import com.thelightphone.sonywf.update.airoha.AirohaRace
import com.thelightphone.sonywf.update.airoha.RaceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Airoha chip families, as the substring table in `AirohaFotaAdapterSony.java:217-227` names them. */
enum class AirohaChip { MT2811, MT2822, AB1562, MT2833, MT2855, UNKNOWN }

/**
 * Drives an MTK/Airoha firmware update: the MDR link stays up and carries the
 * update-mode switch, while the image is flashed over a SEPARATE Airoha RACE
 * socket (design §10.3, spec-airoha-mt28xx-single).
 *
 * Refuses, before anything is written, anything this client has not been
 * verified against: chips other than MT2822/MT2833, TWS devices, and a device
 * whose capability reply never arrived.
 *
 * One [run] per instance.
 *
 * @param openAiroha opens a secure RFCOMM socket to `AirohaDiagnostics.SPP_UUID`.
 * @param reconnect redials the MDR link after the reboot, or null if not yet up.
 */
class MtkUpdateController(
    private val client: SonyProtocolClient,
    private val openAiroha: suspend () -> LightSerialConnection,
    private val reconnect: suspend () -> SonyProtocolClient?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _phase = MutableStateFlow<FotaPhase>(FotaPhase.Idle)
    val phase: StateFlow<FotaPhase> = _phase.asStateFlow()

    @Volatile
    private var runStarted = false

    @Volatile
    private var cancelRequested = false

    /** True between UPDT_SET_STATUS ENABLE and the matching DISABLE. */
    @Volatile
    private var updateModeOn = false

    /** True once the first flash-writing stage could have run. */
    @Volatile
    private var transferBegan = false

    private var inquiredType = -1
    private var race: RaceClient? = null
    private var session: AirohaFotaSession? = null
    private var mirror: Job? = null

    suspend fun run(image: FirmwareImage, inquiredType: Int, capability: UpdateCapability?): FotaPhase {
        check(!runStarted) { "MtkUpdateController.run() may only be called once" }
        runStarted = true
        this.inquiredType = inquiredType

        var terminal: FotaPhase = FotaPhase.Failed(FotaFailure.OTHER, "update did not run")
        try {
            terminal = sequence(image, capability)
        } finally {
            mirror?.cancel()
            // Fail closed: a run that did not complete must not leave the device
            // in a FOTA session or in MTK update mode. Cleanup is uncancellable
            // and its results are ignored; the MDR link may already be down.
            if (terminal is FotaPhase.Failed || terminal is FotaPhase.Cancelled) {
                withContext(NonCancellable) { teardown() }
            }
            race?.stop()
        }
        _phase.value = terminal
        return terminal
    }

    /** Best-effort return to a known state. Safe to call twice. */
    private suspend fun teardown() {
        // Only a transfer that started can have left FOTA state to abandon; the
        // session itself stays silent when it never opened one.
        if (transferBegan && race?.connected?.value == true) {
            session?.cancel(AirohaRace.CANCEL_REASON_STAGE_ERROR)
        }
        if (updateModeOn) {
            updateModeOn = false
            setUpdateStatus(false)
        }
    }

    /**
     * User cancel: tell the Airoha side to abandon the FOTA (reason 1 = stage
     * error, what the library sends for a host-side abort of a running stage),
     * then drop the device out of MTK update mode.
     */
    suspend fun cancel() {
        cancelRequested = true
        when (_phase.value) {
            is FotaPhase.Executing, is FotaPhase.Installing, is FotaPhase.Completed,
            is FotaPhase.Cancelled, is FotaPhase.Failed,
            -> return
            else -> Unit
        }
        session?.cancel(AirohaRace.CANCEL_REASON_STAGE_ERROR)
        if (updateModeOn) {
            updateModeOn = false
            setUpdateStatus(false)
        }
        _phase.value = FotaPhase.Cancelled
    }

    // ---- Sequence ----------------------------------------------------------

    private suspend fun sequence(image: FirmwareImage, capability: UpdateCapability?): FotaPhase {
        if (image.bytes.isEmpty()) return FotaPhase.Failed(FotaFailure.OTHER, "empty firmware image")

        // Fail closed: without a capability reply we know neither the topology
        // nor the transfer mode, and this path writes flash.
        if (capability == null) {
            return FotaPhase.Failed(FotaFailure.OTHER, "unsupported: no update capability reported")
        }
        if (capability.tws) {
            return FotaPhase.Failed(FotaFailure.OTHER, "unsupported: TWS device")
        }

        _phase.value = FotaPhase.EnteringMode
        if (!setUpdateStatus(true)) {
            return FotaPhase.Failed(FotaFailure.DEVICE_REFUSED, "UPDT_SET_STATUS enable not acked")
        }
        updateModeOn = true
        if (cancelRequested) return FotaPhase.Cancelled

        // Chip handshake on its own socket, exactly as the Sony app does: read
        // the name, then close and reopen before FOTA (spec §1.5).
        val probe = openRace() ?: return FotaPhase.Failed(FotaFailure.DISCONNECTED, "airoha socket unavailable")
        val chipName = AirohaFotaSession(probe, clock).readChipName()
        probe.stop()
        race = null
        val chip = chipFamily(chipName)
        if (chip != AirohaChip.MT2822 && chip != AirohaChip.MT2833) {
            return FotaPhase.Failed(FotaFailure.OTHER, "unsupported chip: ${chipName ?: "unknown"} ($chip)")
        }
        if (cancelRequested) return FotaPhase.Cancelled

        val link = openRace() ?: return FotaPhase.Failed(FotaFailure.DISCONNECTED, "airoha socket unavailable")
        val fota = AirohaFotaSession(link, clock)
        session = fota
        mirror = scope.launch {
            fota.phase.collect { if (it !is FotaPhase.Idle) _phase.value = it }
        }

        // Background transfer is a device-reported capability; Active otherwise.
        // MT2822/MT2833 never use Adaptive (spec §3.2).
        val mode = if (capability.backgroundTransfer) AirohaRace.MODE_BACKGROUND else AirohaRace.MODE_ACTIVE
        transferBegan = true
        val transferred = fota.transfer(image.bytes, mode)
        mirror?.cancel()
        if (transferred !is FotaPhase.Transferring) return transferred
        if (cancelRequested) return FotaPhase.Cancelled

        // Sony re-asserts update mode before committing (`nu/o.java:894-905`).
        if (!setUpdateStatus(true)) {
            return FotaPhase.Failed(FotaFailure.DEVICE_REFUSED, "UPDT_SET_STATUS enable not acked before commit")
        }
        _phase.value = FotaPhase.Executing
        if (!fota.commit()) return FotaPhase.Failed(FotaFailure.OTHER, "commit refused")

        return awaitInstall(image)
    }

    /**
     * The device reboots into the new image: the MDR link drops, then comes
     * back. The verdict is the firmware version the redialled link reports
     * (`nu/o.java` drives INSTALL_COMPLETED off the Tandem side, spec §4.4).
     */
    private suspend fun awaitInstall(image: FirmwareImage): FotaPhase {
        _phase.value = FotaPhase.Installing(0, 0)
        val startedAt = clock()
        val deadline = startedAt + INSTALL_TIMEOUT_MS
        // Seeing this version again means the device has not rebooted yet, not a failure.
        val previousVersion = client.firmwareVersion.value

        // The link normally dies within seconds; if it does not, start polling
        // anyway rather than burning the whole budget on the wait.
        withTimeoutOrNull(LINK_DROP_WAIT_MS) { client.connected.first { !it } }

        while (clock() < deadline) {
            delay(RECONNECT_DELAY_MS)
            val percent = ((clock() - startedAt) * 100 / INSTALL_TIMEOUT_MS).toInt().coerceIn(0, 95)
            _phase.value = FotaPhase.Installing(percent, 0)
            val fresh = redial() ?: continue
            val version = fresh.firmwareVersion.value
            when {
                version == image.version -> return FotaPhase.Completed
                version == null || version == previousVersion -> continue
                else -> return FotaPhase.Failed(FotaFailure.OTHER, "version mismatch: reported $version")
            }
        }
        return FotaPhase.Failed(FotaFailure.TIMEOUT, "install not confirmed")
    }

    // ---- Plumbing ----------------------------------------------------------

    /** `{0x34, inq, EnableDisable}` on Command1, Ack-reliable. */
    private suspend fun setUpdateStatus(enable: Boolean): Boolean = client.sendReliable(
        SonyFrame.TYPE_COMMAND1,
        UpdtMessages.setStatus(inquiredType, enable),
        SonyProtocolClient.CONTROL_ACK_TIMEOUT_MS,
        SonyProtocolClient.CONTROL_RESENDS,
    )

    private suspend fun openRace(): RaceClient? {
        val connection = try {
            openAiroha()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val opened = RaceClient(connection, scope)
        opened.start()
        race = opened
        return opened
    }

    private suspend fun redial(): SonyProtocolClient? = try {
        reconnect()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Reboot + reconnect budget (design §10.3). */
        const val INSTALL_TIMEOUT_MS = 480_000L
        const val RECONNECT_DELAY_MS = 3000L

        /** How long to wait for the reboot to drop the MDR link before polling. */
        const val LINK_DROP_WAIT_MS = 60_000L

        /**
         * `AirohaFotaAdapterSony.java:217-227`, in the library's order — "283"
         * is tested before "2822", so the order here is load-bearing.
         */
        fun chipFamily(name: String?): AirohaChip {
            if (name == null) return AirohaChip.UNKNOWN
            return when {
                name.contains("1562") -> AirohaChip.AB1562
                name.contains("283") || name.contains("158") || name.contains("157") -> AirohaChip.MT2833
                name.contains("285") -> AirohaChip.MT2855
                name.contains("2822") || name.contains("1568") || name.contains("1565") -> AirohaChip.MT2822
                // The library falls through to MT2811 here; we refuse instead,
                // because an unrecognised chip must never be flashed.
                else -> AirohaChip.UNKNOWN
            }
        }
    }
}
