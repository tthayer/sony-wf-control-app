package com.thelightphone.sonywf.update

import com.thelightphone.sonywf.protocol.SonyFrame
import com.thelightphone.sonywf.protocol.SonyProtocolClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Coarse progress of a Tandem FOTA run, mirrored into the UI. */
sealed interface FotaPhase {
    data object Idle : FotaPhase
    data object EnteringMode : FotaPhase
    data class Transferring(val percent: Int) : FotaPhase
    data object Finishing : FotaPhase
    data object Executing : FotaPhase
    data class Installing(val percent: Int, val requiredTimeSec: Int) : FotaPhase
    data object Completed : FotaPhase
    data object Cancelled : FotaPhase
    data class Failed(val reason: FotaFailure, val detail: String) : FotaPhase
}

enum class FotaFailure {
    NEED_CHARGE,
    BATTERY_HOT,
    DEVICE_REFUSED,
    TIMEOUT,
    TRANSFER_FAILED,
    CANCELLED_BY_DEVICE,
    DISCONNECTED,
    OTHER,
}

/**
 * Drives one Tandem FOTA transfer + install over an established
 * [SonyProtocolClient] (spec-tandem-fota §4.1 / §7).
 *
 * Every wait consumes [SonyProtocolClient.notifications], which is a hot
 * SharedFlow that outlives the transport: that is what lets the install phase
 * survive the device dropping the RFCOMM link while it reboots.
 *
 * One [run] per instance.
 *
 * @param reconnect redials the device and returns a started client, or null if
 *   it is not back yet. Supplied only for the install phase, where the device
 *   reboots and the RFCOMM link dies (spec-tandem-fota §4.7). Null means "just
 *   keep waiting on the old link".
 * @param clock millisecond source, injected so install progress is testable.
 */
class TandemFotaSession(
    client: SonyProtocolClient,
    private val reconnect: (suspend () -> SonyProtocolClient?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The live client; swapped for a fresh one after a reboot-time redial. */
    private var current: SonyProtocolClient = client

    private val _phase = MutableStateFlow<FotaPhase>(FotaPhase.Idle)
    val phase: StateFlow<FotaPhase> = _phase.asStateFlow()

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var runStarted = false

    /** Latches once FW_UPDATE_COMPLETED is seen, whichever step is running. */
    private val completed = MutableStateFlow(false)

    /** True when a send exhausted its Ack budget, to distinguish it from a timeout. */
    @Volatile
    private var sendFailed = false

    /** Scope owning the completion watcher; alive only for the duration of [run]. */
    private var watchScope: CoroutineScope? = null

    private var completionWatcher: Job? = null

    /** Runs the full sequence and returns the terminal phase. */
    suspend fun run(image: FirmwareImage): FotaPhase = coroutineScope {
        check(!runStarted) { "TandemFotaSession.run() may only be called once" }
        runStarted = true

        // A session-wide watcher for FW_UPDATE_COMPLETED. Per-step collectors
        // unsubscribe as soon as their step is satisfied, and `notifications`
        // has no replay buffer, so without this a completion that arrives while
        // a step is finishing would be dropped.
        watchScope = this
        attachCompletionWatcher()

        val terminal = try {
            sequence(image)
        } finally {
            completionWatcher?.cancel()
            completionWatcher = null
            watchScope = null
        }
        _phase.value = terminal
        terminal
    }

    /**
     * User cancel: CANCEL_TRANSFER (only while transferring, per §4.8) then
     * EXIT_FW_UPDATE_MODE. Safe to call any time before [FotaPhase.Executing];
     * once the device is installing there is nothing to cancel.
     */
    suspend fun cancel() {
        cancelRequested = true
        when (_phase.value) {
            is FotaPhase.Executing, is FotaPhase.Installing, is FotaPhase.Completed,
            is FotaPhase.Cancelled, is FotaPhase.Failed,
            -> return
            is FotaPhase.Transferring -> awaitCancelAck()
            else -> Unit
        }
        awaitExitAck()
        _phase.value = FotaPhase.Cancelled
    }

    // ---- Sequence ---------------------------------------------------------

    private suspend fun sequence(image: FirmwareImage): FotaPhase {
        if (image.bytes.isEmpty()) return FotaPhase.Failed(FotaFailure.OTHER, "empty firmware image")

        // 1. ENTER_FW_UPDATE_MODE.
        _phase.value = FotaPhase.EnteringMode
        enterMode()?.let { return it }
        if (cancelRequested) return FotaPhase.Cancelled

        // 2. START_TRANSFER.
        _phase.value = FotaPhase.Transferring(0)
        var noNeedOfTransfer = false
        val start = when (val outcome = startTransfer(image)) {
            is StartOutcome.Failed -> return outcome.phase
            StartOutcome.NoNeedOfTransfer -> {
                noNeedOfTransfer = true
                null
            }
            is StartOutcome.Ready -> outcome.result
        }

        // 3. Chunk loop.
        if (start != null) {
            if (start.maxPacketSize <= 0) {
                return FotaPhase.Failed(FotaFailure.TRANSFER_FAILED, "maxPacketSize ${start.maxPacketSize}")
            }
            transferChunks(image, start.maxPacketSize, start.offset)?.let { return it }
        }

        // 4. FINISH_TRANSFER. Skipped after NO_NEED_OF_DATA_TRANSFER: the device
        // is already IDLE and the Sony app does not send FINISH there (spec §4.3).
        if (!noNeedOfTransfer) {
            _phase.value = FotaPhase.Finishing
            finishTransfer()?.let { return it }
        }
        if (cancelRequested) return FotaPhase.Cancelled

        // 5. EXECUTE_FW_UPDATE.
        _phase.value = FotaPhase.Executing
        val requiredTimeSec = when (val outcome = execute(image)) {
            is ExecuteOutcome.Failed -> return outcome.phase
            is ExecuteOutcome.Ready -> outcome.requiredTimeSec
        }

        // 6. Install.
        return awaitInstall(requiredTimeSec, image)
    }

    /** Returns a terminal phase on failure, or null when the step succeeded. */
    private suspend fun enterMode(): FotaPhase? {
        var resultOk = false
        var idleSeen = false
        val outcome = awaitStep(
            timeoutMs = CONTROL_TIMEOUT_MS,
            trigger = { sendControl(UpdtMessages.setSimple(UpdtMessages.CMD_ENTER)) },
        ) { notify ->
            when {
                notify is UpdtNotify.SimpleResult && notify.command == UpdtMessages.CMD_ENTER ->
                    when (notify.result) {
                        FotaResult.OK -> {
                            resultOk = true
                            if (idleSeen) StepOk else null
                        }
                        FotaResult.NEED_POWER_AND_BATTERY ->
                            fail(FotaFailure.NEED_CHARGE, "device needs power and battery")
                        FotaResult.TEMP_TOO_HIGH ->
                            fail(FotaFailure.BATTERY_HOT, "device temperature too high")
                        else -> fail(FotaFailure.DEVICE_REFUSED, "enter result ${notify.result}")
                    }

                notify is UpdtNotify.Status && notify.status == FotaStatus.IDLE -> {
                    idleSeen = true
                    if (resultOk) StepOk else null
                }

                notify is UpdtNotify.Status && notify.status == FotaStatus.INVALID ->
                    fail(FotaFailure.CANCELLED_BY_DEVICE, "device left update mode")

                else -> null
            }
        }
        return terminalOf(outcome, "enter update mode")
    }

    private sealed interface StartOutcome {
        data class Ready(val result: UpdtNotify.StartTransferResult) : StartOutcome
        data object NoNeedOfTransfer : StartOutcome
        data class Failed(val phase: FotaPhase) : StartOutcome
    }

    private suspend fun startTransfer(image: FirmwareImage): StartOutcome {
        var result: UpdtNotify.StartTransferResult? = null
        var receivingSeen = false
        val outcome = awaitStep(
            timeoutMs = START_TRANSFER_TIMEOUT_MS,
            trigger = {
                sendControl(
                    UpdtMessages.startTransfer(image.version, image.fileName, image.digest, image.macHex),
                )
            },
        ) { notify ->
            when {
                notify is UpdtNotify.StartTransferResult -> when (notify.result) {
                    FotaResult.OK -> {
                        result = notify
                        if (receivingSeen) StepOk else null
                    }
                    // Device already holds this image.
                    FotaResult.NO_NEED_OF_DATA_TRANSFER -> StepSkip
                    else -> fail(FotaFailure.DEVICE_REFUSED, "start transfer result ${notify.result}")
                }

                notify is UpdtNotify.Status && notify.status == FotaStatus.DATA_RECEIVING -> {
                    receivingSeen = true
                    if (result != null) StepOk else null
                }

                notify is UpdtNotify.Status && notify.status == FotaStatus.NOT_READY ->
                    fail(FotaFailure.CANCELLED_BY_DEVICE, "device not ready")

                notify is UpdtNotify.Status && notify.status == FotaStatus.INVALID ->
                    fail(FotaFailure.CANCELLED_BY_DEVICE, "device left update mode")

                else -> null
            }
        }
        return when (outcome) {
            StepSkip -> StartOutcome.NoNeedOfTransfer
            StepOk -> result?.let { StartOutcome.Ready(it) }
                ?: StartOutcome.Failed(FotaPhase.Failed(FotaFailure.OTHER, "missing start transfer result"))
            is StepFail -> StartOutcome.Failed(FotaPhase.Failed(outcome.reason, outcome.detail))
            null -> StartOutcome.Failed(timeoutPhase("start transfer"))
        }
    }

    /**
     * Push the image in `maxPacketSize` slices from [startOffset], watching the
     * status stream for a device-side abort between chunks. Returns a terminal
     * phase on failure, or null when the whole image went out.
     */
    private suspend fun transferChunks(image: FirmwareImage, maxPacketSize: Int, startOffset: Int): FotaPhase? =
        coroutineScope {
            val size = image.bytes.size
            if (startOffset < 0 || startOffset > size) {
                return@coroutineScope FotaPhase.Failed(FotaFailure.TRANSFER_FAILED, "bad resume offset $startOffset")
            }

            val subscribed = CompletableDeferred<Unit>()
            val aborted = MutableStateFlow<FotaStatus?>(null)
            val watcher = launch {
                notifies(subscribed).collect { notify ->
                    if (notify is UpdtNotify.Status && notify.status != FotaStatus.DATA_RECEIVING) {
                        aborted.compareAndSet(null, notify.status)
                    }
                }
            }
            subscribed.await()

            try {
                var offset = startOffset
                while (offset < size) {
                    if (cancelRequested) return@coroutineScope FotaPhase.Cancelled
                    val abortStatus = aborted.value
                    if (abortStatus != null) {
                        return@coroutineScope FotaPhase.Failed(
                            FotaFailure.CANCELLED_BY_DEVICE,
                            "status $abortStatus during transfer",
                        )
                    }
                    val end = minOf(offset + maxPacketSize, size)
                    val chunk = image.bytes.copyOfRange(offset, end)
                    val sent = current.sendReliable(
                        SonyFrame.TYPE_LARGE_DATA_MDR,
                        UpdtMessages.transferData(offset, chunk),
                        SonyProtocolClient.LARGE_ACK_TIMEOUT_MS,
                        SonyProtocolClient.LARGE_RESENDS,
                    )
                    if (!sent) {
                        return@coroutineScope FotaPhase.Failed(
                            FotaFailure.TRANSFER_FAILED,
                            "no ack for chunk at $offset",
                        )
                    }
                    offset = end
                    // Long math: offset * 100 overflows an Int past ~21 MB.
                    _phase.value = FotaPhase.Transferring((offset.toLong() * 100 / size).toInt())
                }
                null
            } finally {
                watcher.cancel()
            }
        }

    private suspend fun finishTransfer(): FotaPhase? {
        var resultOk = false
        var idleSeen = false
        val outcome = awaitStep(
            timeoutMs = CONTROL_TIMEOUT_MS,
            trigger = { sendControl(UpdtMessages.setSimple(UpdtMessages.CMD_FINISH)) },
        ) { notify ->
            when {
                notify is UpdtNotify.SimpleResult && notify.command == UpdtMessages.CMD_FINISH ->
                    if (notify.result == FotaResult.OK) {
                        resultOk = true
                        if (idleSeen) StepOk else null
                    } else {
                        fail(FotaFailure.DEVICE_REFUSED, "finish result ${notify.result}")
                    }

                notify is UpdtNotify.Status && notify.status == FotaStatus.IDLE -> {
                    idleSeen = true
                    if (resultOk) StepOk else null
                }

                notify is UpdtNotify.Status && notify.status == FotaStatus.INVALID ->
                    fail(FotaFailure.CANCELLED_BY_DEVICE, "device left update mode")

                else -> null
            }
        }
        return terminalOf(outcome, "finish transfer")
    }

    private sealed interface ExecuteOutcome {
        data class Ready(val requiredTimeSec: Int) : ExecuteOutcome
        data class Failed(val phase: FotaPhase) : ExecuteOutcome
    }

    private suspend fun execute(image: FirmwareImage): ExecuteOutcome {
        var requiredTimeSec: Int? = null
        var updatingSeen = false
        val outcome = awaitStep(
            timeoutMs = CONTROL_TIMEOUT_MS,
            trigger = { sendControl(UpdtMessages.execute(image.version, listOf(image.fileName))) },
        ) { notify ->
            when {
                notify is UpdtNotify.ExecuteResult -> when (notify.result) {
                    FotaResult.OK -> {
                        requiredTimeSec = notify.requiredTimeSec
                        if (updatingSeen) StepOk else null
                    }
                    FotaResult.TRANSFER_INCOMPLETE ->
                        fail(FotaFailure.DEVICE_REFUSED, "firmware transfer incomplete")
                    else -> fail(FotaFailure.DEVICE_REFUSED, "execute result ${notify.result}")
                }

                notify is UpdtNotify.Status && notify.status == FotaStatus.UPDATING -> {
                    updatingSeen = true
                    if (requiredTimeSec != null) StepOk else null
                }

                else -> null
            }
        }
        return when (outcome) {
            StepOk, StepSkip -> requiredTimeSec?.let { ExecuteOutcome.Ready(it) }
                ?: ExecuteOutcome.Failed(FotaPhase.Failed(FotaFailure.OTHER, "missing execute result"))
            is StepFail -> ExecuteOutcome.Failed(FotaPhase.Failed(outcome.reason, outcome.detail))
            null -> ExecuteOutcome.Failed(timeoutPhase("execute update"))
        }
    }

    /**
     * Wait out the install. The device reboots here and the RFCOMM link dies,
     * which is NOT a failure (spec-tandem-fota §4.7): with a [reconnect] we
     * redial until the device answers again, otherwise we keep waiting on the
     * old link. Either way the wait is bounded by 2 x requiredTime.
     */
    private suspend fun awaitInstall(requiredTimeSec: Int, image: FirmwareImage): FotaPhase = coroutineScope {
        val deadlineMs = maxOf(MIN_INSTALL_TIMEOUT_MS, requiredTimeSec * 2 * 1000L)
        _phase.value = FotaPhase.Installing(0, requiredTimeSec)

        val startedAt = clock()
        val tickMs = maxOf(MIN_INSTALL_TICK_MS, requiredTimeSec * 1000L / 100)
        val ticker = launch {
            while (true) {
                delay(tickMs)
                val elapsed = clock() - startedAt
                // Fake progress: no real signal exists until FW_UPDATE_COMPLETED.
                val percent =
                    if (requiredTimeSec <= 0) 95
                    else (elapsed / (requiredTimeSec * 10L)).toInt().coerceIn(0, 95)
                _phase.value = FotaPhase.Installing(percent, requiredTimeSec)
            }
        }

        val done = try {
            withTimeoutOrNull(deadlineMs) { installLoop(image) }
        } finally {
            ticker.cancel()
        }

        done ?: FotaPhase.Failed(FotaFailure.TIMEOUT, "install not confirmed")
    }

    /**
     * Race the completion notification against the link dying. On a drop with no
     * [reconnect] we simply keep waiting (the caller's timeout bounds it); with
     * one we redial every [RECONNECT_DELAY_MS] and resume watching the fresh
     * session. Returns only on completion; the caller's timeout does the rest.
     */
    private suspend fun installLoop(image: FirmwareImage): FotaPhase {
        while (true) {
            if (awaitCompletionOrDrop()) return FotaPhase.Completed
            val redial = reconnect ?: run {
                completed.first { it }
                return FotaPhase.Completed
            }
            while (true) {
                delay(RECONNECT_DELAY_MS)
                val fresh = redial() ?: continue
                switchTo(fresh)
                // A fresh session may never see FW_UPDATE_COMPLETED: the device
                // sent it (if at all) on the link it dropped. The version it
                // now reports is the authoritative answer.
                if (fresh.firmwareVersion.value == image.version) return FotaPhase.Completed
                break
            }
        }
    }

    /** True when the completion landed, false when the link dropped first. */
    private suspend fun awaitCompletionOrDrop(): Boolean = coroutineScope {
        val done = async { completed.first { it } }
        val dropped = async { current.connected.first { !it } }
        try {
            select {
                done.onAwait { true }
                dropped.onAwait { false }
            }
        } finally {
            done.cancel()
            dropped.cancel()
        }
    }

    /** Point the session at a post-reboot client and re-arm the completion watcher. */
    private suspend fun switchTo(fresh: SonyProtocolClient) {
        current = fresh
        attachCompletionWatcher()
    }

    /**
     * (Re-)subscribe the session-wide FW_UPDATE_COMPLETED watcher to [current].
     * Returns once the subscription is live.
     */
    private suspend fun attachCompletionWatcher() {
        val scope = watchScope ?: return
        completionWatcher?.cancel()
        val subscribed = CompletableDeferred<Unit>()
        completionWatcher = scope.launch { notifies(subscribed).collect { } }
        subscribed.await()
    }

    // ---- Cancel steps -----------------------------------------------------

    private suspend fun awaitCancelAck() {
        var resultOk = false
        var idleSeen = false
        awaitStep(
            timeoutMs = CONTROL_TIMEOUT_MS,
            trigger = { sendControl(UpdtMessages.setSimple(UpdtMessages.CMD_CANCEL)) },
        ) { notify ->
            when {
                notify is UpdtNotify.SimpleResult && notify.command == UpdtMessages.CMD_CANCEL ->
                    if (notify.result == FotaResult.OK) {
                        resultOk = true
                        if (idleSeen) StepOk else null
                    } else {
                        fail(FotaFailure.DEVICE_REFUSED, "cancel result ${notify.result}")
                    }

                notify is UpdtNotify.Status && notify.status == FotaStatus.IDLE -> {
                    idleSeen = true
                    if (resultOk) StepOk else null
                }

                else -> null
            }
        }
    }

    private suspend fun awaitExitAck() {
        var resultOk = false
        var statusSeen = false
        awaitStep(
            timeoutMs = CONTROL_TIMEOUT_MS,
            trigger = { sendControl(UpdtMessages.setSimple(UpdtMessages.CMD_EXIT)) },
        ) { notify ->
            when {
                notify is UpdtNotify.SimpleResult && notify.command == UpdtMessages.CMD_EXIT ->
                    if (notify.result == FotaResult.OK) {
                        resultOk = true
                        if (statusSeen) StepOk else null
                    } else {
                        fail(FotaFailure.DEVICE_REFUSED, "exit result ${notify.result}")
                    }

                notify is UpdtNotify.Status &&
                    (notify.status == FotaStatus.INVALID || notify.status == FotaStatus.NOT_READY) -> {
                    statusSeen = true
                    if (resultOk) StepOk else null
                }

                else -> null
            }
        }
    }

    // ---- Wait plumbing ----------------------------------------------------

    private sealed interface StepOutcome
    private data object StepOk : StepOutcome
    private data object StepSkip : StepOutcome
    private data class StepFail(val reason: FotaFailure, val detail: String) : StepOutcome

    private fun fail(reason: FotaFailure, detail: String): StepOutcome = StepFail(reason, detail)

    /**
     * The parsed notification stream, with [subscribed] completed the moment
     * the collector is attached. Callers MUST await it before triggering the
     * send, otherwise a fast device reply races ahead of the subscription
     * (the flow has no replay buffer).
     */
    private fun notifies(subscribed: CompletableDeferred<Unit>) =
        current.notifications
            .onSubscription { subscribed.complete(Unit) }
            .mapNotNull { UpdtMessages.parseNotify(it.payload) }
            .onEach { if (it is UpdtNotify.Completed) completed.value = true }

    /**
     * Subscribe, [trigger] the send, then feed each notification to [reduce]
     * until it returns non-null. Returns null on timeout or a failed send.
     *
     * The timeout window opens only after [trigger] returns: the send itself
     * carries an Ack timeout plus resends, and spending that budget must not
     * eat into the device's time to answer.
     */
    private suspend fun awaitStep(
        timeoutMs: Long,
        trigger: suspend () -> Boolean,
        reduce: (UpdtNotify) -> StepOutcome?,
    ): StepOutcome? = coroutineScope {
        val subscribed = CompletableDeferred<Unit>()
        val waiter = async { notifies(subscribed).mapNotNull { reduce(it) }.first() }
        subscribed.await()
        if (!trigger()) {
            waiter.cancel()
            return@coroutineScope null
        }
        val outcome = withTimeoutOrNull(timeoutMs) { waiter.await() }
        if (outcome == null) waiter.cancel()
        outcome
    }

    private suspend fun sendControl(payload: ByteArray): Boolean {
        val ok = current.sendReliable(
            SonyFrame.TYPE_COMMAND1,
            payload,
            SonyProtocolClient.CONTROL_ACK_TIMEOUT_MS,
            SonyProtocolClient.CONTROL_RESENDS,
        )
        if (!ok) sendFailed = true
        return ok
    }

    private fun terminalOf(outcome: StepOutcome?, what: String): FotaPhase? = when (outcome) {
        StepOk, StepSkip -> null
        is StepFail -> FotaPhase.Failed(outcome.reason, outcome.detail)
        null -> timeoutPhase(what)
    }

    private fun timeoutPhase(what: String): FotaPhase =
        if (sendFailed) {
            FotaPhase.Failed(FotaFailure.DISCONNECTED, "no ack for $what")
        } else {
            FotaPhase.Failed(FotaFailure.TIMEOUT, "timed out waiting for $what")
        }

    private companion object {
        /** ENTER / EXIT / FINISH / CANCEL / EXECUTE all use 20 s (§4.9). */
        const val CONTROL_TIMEOUT_MS = 20_000L
        const val START_TRANSFER_TIMEOUT_MS = 150_000L
        const val MIN_INSTALL_TIMEOUT_MS = 60_000L
        const val MIN_INSTALL_TICK_MS = 100L

        /** Wait between redial attempts while the device is rebooting (§4.7). */
        const val RECONNECT_DELAY_MS = 3000L
    }
}
