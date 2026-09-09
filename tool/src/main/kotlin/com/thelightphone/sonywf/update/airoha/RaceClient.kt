package com.thelightphone.sonywf.update.airoha

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Request/response driver for the Airoha RACE transport
 * (docs/protocol/spec-airoha-fota.md §2.3).
 *
 * Wraps a [LightSerialConnection] opened on the Airoha SPP UUID (a SEPARATE
 * socket from the Sony MDR/Tandem link, spec §1.1) and:
 *  - deframes inbound bytes into [messages],
 *  - matches a reply to its command by `raceId` + type,
 *  - resends the identical frame on silence, up to a retry budget.
 *
 * The linker performs zero retries of its own (spec §1.6), so retrying here is
 * the whole retry story.
 *
 * @param connection live byte transport on the Airoha SPP socket.
 * @param scope owns the inbound collector; cancelling it (or [stop]) tears the
 *   driver down.
 */
class RaceClient(
    private val connection: LightSerialConnection,
    private val scope: CoroutineScope,
) {
    private val _connected = MutableStateFlow(false)
    /** True from [start] until the inbound stream completes or [stop] runs. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _messages = MutableSharedFlow<RaceMessage>(
        replay = 0,
        // Notifications can burst; dropping the oldest keeps the inbound loop
        // non-blocking rather than losing the newest frame.
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Every decoded inbound frame, in arrival order. */
    val messages: SharedFlow<RaceMessage> = _messages.asSharedFlow()

    private val decoder = RaceDecoder()
    private val sendMutex = Mutex() // one outstanding request at a time
    private var receiveJob: Job? = null

    /** Start the inbound collector. Idempotent. */
    fun start() {
        if (receiveJob != null) return
        _connected.value = true
        receiveJob = scope.launch {
            try {
                connection.incoming.collect { chunk ->
                    for (message in decoder.feed(chunk)) _messages.tryEmit(message)
                }
            } finally {
                _connected.value = false
            }
        }
    }

    /**
     * Send a RACE command and await a matching reply.
     *
     * A reply matches when `raceId` is equal and the type is in [acceptTypes] —
     * exactly the stage-layer rule (spec §2.3, `CommonStage.java:171`). Some
     * queries answer with a notify (0x5D) rather than a response (0x5B), hence
     * both are accepted by default.
     *
     * On timeout the IDENTICAL frame is resent up to [retries] times. Returns
     * null when nothing matched. Serialised by a [Mutex] so a stray reply
     * cannot be attributed to the wrong request.
     */
    suspend fun request(
        raceId: Int,
        payload: ByteArray,
        flag: Int = RaceFrame.FLAG_NONE,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        retries: Int = DEFAULT_RETRIES,
        acceptTypes: Set<Int> = setOf(RaceFrame.TYPE_RSP, RaceFrame.TYPE_NOTIFY),
    ): RaceMessage? = sendMutex.withLock {
        // Encode once so every resend is byte-identical.
        val frame = RaceFrame.encode(flag, RaceFrame.TYPE_CMD, raceId, payload)
        var attempt = 0
        while (attempt <= retries) {
            val reply = attempt(frame, raceId, acceptTypes, timeoutMs)
            if (reply != null) return@withLock reply
            attempt++
        }
        null
    }

    /** Cancel the inbound collector and close the transport. Idempotent. */
    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        _connected.value = false
        connection.close()
    }

    /**
     * One write + await cycle. The reply collector is attached UNDISPATCHED so
     * it is subscribed to [messages] before the write goes out; a device that
     * answers instantly cannot then be missed.
     */
    private suspend fun attempt(
        frame: ByteArray,
        raceId: Int,
        acceptTypes: Set<Int>,
        timeoutMs: Long,
    ): RaceMessage? = coroutineScope {
        // UNDISPATCHED runs the body until its first real suspension, which is
        // inside SharedFlow.collect AFTER the subscriber slot is registered.
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeoutMs) {
                messages.first { it.raceId == raceId && it.type in acceptTypes }
            }
        }
        val written = try {
            connection.write(frame)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A dead link is a failed attempt, not an exception for callers:
            // this pass is diagnostic and must always produce a report.
            false
        }
        if (!written) {
            awaiting.cancel()
            return@coroutineScope null
        }
        awaiting.await()
    }

    companion object {
        /** Spec §2.3: `TIMEOUT_RACE_CMD_NOT_RSP = 1000`. */
        const val DEFAULT_TIMEOUT_MS = 1000L

        /** Spec §2.3: `mMaxRetry = 2`. */
        const val DEFAULT_RETRIES = 2
    }
}
