package com.thelightphone.sonywf.update.airoha

import com.thelightphone.sonywf.update.FotaFailure
import com.thelightphone.sonywf.update.FotaPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * The single-device (non-TWS) MediaTek/Airoha flash path: stages 1-14 of
 * spec-airoha-mt28xx-single §3.0, driven over an already-open [RaceClient].
 *
 * This class WRITES FLASH. It is deliberately fail-closed:
 *  - nothing is sent after 0x1C08 unless the size guard and the 0x1C04 state
 *    guard both passed (the caller separately gates on chip family and TWS),
 *  - any non-zero status on a stage reply aborts the run and sends cancel
 *    0x1C03 reason 1,
 *  - a sector is programmed only when it is known-erased or its device-side
 *    SHA-256 already matched, never otherwise.
 *
 * One [transfer] per instance.
 *
 * @param clock millisecond source, injected so pacing/watchdogs are testable.
 */
class AirohaFotaSession(
    private val race: RaceClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _phase = MutableStateFlow<FotaPhase>(FotaPhase.Idle)
    val phase: StateFlow<FotaPhase> = _phase.asStateFlow()

    /**
     * True from the moment the 0x1C08 frame is BUILT: `i0(true)` runs before
     * the packet is queued, so FOTA-Start itself already carries byte0 = 0x15
     * (spec §0.1).
     */
    @Volatile
    private var sessionOpen = false

    /**
     * RX filtering starts only after 0x1C08 came back with status 0. Filtering
     * the start reply itself would be a fail-open guess about a frame we have
     * never observed; refusing to hear it would wedge a device that answers
     * with byte0 = 0x05.
     */
    @Volatile
    private var rxFilterOn = false

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var transferStarted = false

    /** True while [transfer] is running: only then may a stage failure cancel. */
    @Volatile
    private var flowActive = false

    private var pacingMs = 0L
    private var commandsPerPacket = AirohaRace.COMMANDS_PER_PACKET_ACTIVE

    // ---- Read-only queries -------------------------------------------------

    /**
     * READ NVKEY 0x1002 (§1.2/§1.3). The reply has NO status byte: `rx[6..7]`
     * is the returned length (LE16) and the name is `rx[8..]`, ISO-8859-1.
     * Null when nothing came back or the length is 0 — unlike the library,
     * which falls through an empty name to "MT2811" and would let the caller
     * flash the wrong chip.
     */
    suspend fun readChipName(): String? {
        val rx = race.request(
            raceId = AirohaRace.READ_NVKEY,
            payload = AirohaRequests.readChipName(),
            flag = RaceFrame.FLAG_NONE,
            timeoutMs = AirohaRace.CHIP_NAME_TIMEOUT_MS,
            retries = AirohaRace.CHIP_NAME_ATTEMPTS - 1,
            acceptTypes = setOf(RaceFrame.TYPE_RSP),
        ) ?: return null
        val len = le16(rx, 6)
        if (len <= 0 || rx.payload.size < 2 + len) return null
        val name = String(rx.payload, 2, len, Charsets.ISO_8859_1)
        return name.ifBlank { null }
    }

    /** 0x1C00 InquiryFota (§3.1). Null on timeout or a non-zero status. */
    suspend fun inquiry(): PartitionInfo? {
        val rx = command(AirohaRace.INQUIRY_FOTA, AirohaRequests.inquiryFota(), RaceFrame.TYPE_RSP) ?: return null
        if (rx.rx(6) != 0) return null
        if (rx.rx(16) < 0) return null
        return PartitionInfo(
            id = rx.rx(7),
            storageType = rx.rx(8),
            addr = le32(rx, 9),
            length = le32(rx, 13),
        )
    }

    /** 0x1C04 QueryState (§2.3): LE16 state at `rx[7..8]`. Null on failure. */
    suspend fun queryState(): Int? {
        val rx = command(AirohaRace.QUERY_STATE, AirohaRequests.queryState(), RaceFrame.TYPE_RSP) ?: return null
        if (rx.rx(6) != 0) return null
        if (rx.rx(8) < 0) return null
        return le16(rx, 7)
    }

    // ---- Transfer ----------------------------------------------------------

    /**
     * Stages 1-14 (§3.0). Returns [FotaPhase.Transferring] at 100 % when the
     * device's final 0x1C04 reported 0x0211, otherwise a terminal phase.
     */
    suspend fun transfer(image: ByteArray, mode: Int): FotaPhase {
        check(!transferStarted) { "AirohaFotaSession.transfer() may only be called once" }
        transferStarted = true

        flowActive = true
        val terminal = try {
            runTransfer(image, mode)
        } finally {
            flowActive = false
        }
        // Terminal path owns the session flag: [cancel] must not clear it while
        // the flow is still sending, or the frames after it would drop to 0x05.
        // A completed transfer keeps it, because the commit that follows rides
        // the same session (§0.1).
        if (terminal !is FotaPhase.Transferring) {
            sessionOpen = false
            rxFilterOn = false
        }
        _phase.value = terminal
        return terminal
    }

    private suspend fun runTransfer(image: ByteArray, mode: Int): FotaPhase {
        if (image.isEmpty()) return FotaPhase.Failed(FotaFailure.OTHER, "empty firmware image")
        if (mode != AirohaRace.MODE_BACKGROUND && mode != AirohaRace.MODE_ACTIVE) {
            return FotaPhase.Failed(FotaFailure.OTHER, "unsupported FOTA mode 0x${hex2(mode)}")
        }
        _phase.value = FotaPhase.Transferring(0)

        // 1. 0x1C00 InquiryFota.
        val partition = inquiry() ?: return abort(FotaFailure.DEVICE_REFUSED, "0x1C00 inquiry failed")

        // Size guard (§3.1): ceil(size/4096)*4096 <= partitionLength - 4096.
        val declared = ((image.size + FlashPlan.SECTOR - 1) / FlashPlan.SECTOR) * FlashPlan.SECTOR
        if (declared > partition.length - FlashPlan.SECTOR) {
            return abort(
                FotaFailure.OTHER,
                "image $declared B exceeds partition ${partition.length} B",
            )
        }

        // State guard: never open a FOTA session from an unknown device state.
        val state = queryState() ?: return abort(FotaFailure.DEVICE_REFUSED, "0x1C04 query state failed")
        if (state !in AirohaRace.RESUMABLE_STATES) {
            return abort(FotaFailure.DEVICE_REFUSED, "device state 0x${hex4(state)} is not resumable")
        }
        if (cancelRequested) return FotaPhase.Cancelled

        // 2. 0x1C08 FOTA Start. The flag flips BEFORE the frame is built.
        sessionOpen = true
        val start = command(
            AirohaRace.FOTA_START,
            AirohaRequests.fotaStart(mode),
            RaceFrame.TYPE_NOTIFY,
        )
        if (start == null) {
            sessionOpen = false
            return abort(FotaFailure.TIMEOUT, "no reply to 0x1C08 FOTA start")
        }
        if (start.rx(6) != 0) {
            sessionOpen = false
            return abort(FotaFailure.DEVICE_REFUSED, "0x1C08 status 0x${hex2(start.rx(6))}")
        }
        rxFilterOn = true

        commandsPerPacket =
            if (mode == AirohaRace.MODE_BACKGROUND) AirohaRace.COMMANDS_PER_PACKET
            else AirohaRace.COMMANDS_PER_PACKET_ACTIVE
        pacingMs = if (mode == AirohaRace.MODE_BACKGROUND) AirohaRace.PACING_BACKGROUND_MS else 0L

        // 3. 0x1C1C Query Transmit Interval. A timeout here is non-fatal (§3.3).
        val interval = command(
            AirohaRace.QUERY_TRANSMIT_INTERVAL,
            AirohaRequests.queryTransmitInterval(),
            RaceFrame.TYPE_NOTIFY,
        )
        if (interval != null) {
            if (interval.rx(6) != 0) {
                return abort(FotaFailure.DEVICE_REFUSED, "0x1C1C status 0x${hex2(interval.rx(6))}")
            }
            val ms = le16(interval, 9)
            if (ms > 0) pacingMs = ms.toLong()
        }

        val sectors = FlashPlan.sectors(image, partition.addr)
        if (sectors.isEmpty()) return abort(FotaFailure.OTHER, "empty flash plan")

        // 4. 0x0433 GetEraseStatus, one command per 512 KB region.
        readEraseStatus(partition, sectors)?.let { return it }

        // 5. 0x0431 Compare. Skipped entirely when sector 0 is already erased
        //    (`Compare_stages`, g8/i.java:171-174).
        if (!sectors[0].erased) compare(partition, sectors)?.let { return it }
        if (cancelRequested) return cancelled()

        // 6. 0x1C0A StartTransaction.
        commandOk(AirohaRace.START_TRANSACTION, AirohaRequests.startTransaction(), RaceFrame.TYPE_RSP)
            ?.let { return it }

        val needErase = sectors.filter { it.needsWrite && !it.erased }
        val needWrite = sectors.filter { it.needsWrite }

        if (needWrite.isNotEmpty()) {
            // 7/8. WriteState 0x0200 + one 0x0404 per sector, low address first.
            //      Both are skipped together when nothing needs erasing.
            if (needErase.isNotEmpty()) {
                writeState(AirohaRace.STATE_ERASING)?.let { return it }
                for (sector in needErase) {
                    if (cancelRequested) return cancelled()
                    eraseSector(partition, sector)?.let { return it }
                }
            }
            // 9/10. WriteState 0x0201 then 0x0210.
            writeState(AirohaRace.STATE_WRITING)?.let { return it }
            writeState(AirohaRace.STATE_VERIFYING)?.let { return it }

            // 11. 0x0402 page writes.
            writePages(partition, needWrite)?.let { return it }
        }
        if (cancelRequested) return cancelled()

        // 12. 0x1C01 CheckIntegrity.
        commandOk(
            AirohaRace.CHECK_INTEGRITY,
            AirohaRequests.checkIntegrity(partition.storageType),
            RaceFrame.TYPE_NOTIFY,
        )?.let { return it }

        // 13. WriteState 0x0211.
        writeState(AirohaRace.STATE_WRITTEN)?.let { return it }

        // 14. Final 0x1C04: the transfer counts as complete iff it is 0x0211.
        val finalState = queryState() ?: return abort(FotaFailure.DEVICE_REFUSED, "final 0x1C04 failed")
        if (finalState != AirohaRace.STATE_WRITTEN) {
            return abort(FotaFailure.DEVICE_REFUSED, "final state 0x${hex4(finalState)}")
        }
        return FotaPhase.Transferring(100)
    }

    /**
     * 0x1C02 Commit (§4.1/§4.2). Success is a 0x5A reply with status 0 OR the
     * socket dropping: the device reboots into the new image, and on that path
     * no reply is ever seen.
     */
    suspend fun commit(): Boolean {
        val rx = race.request(
            raceId = AirohaRace.COMMIT,
            payload = AirohaRequests.commit(),
            flag = txFlag(),
            timeoutMs = AirohaRace.TIMEOUT_MS,
            retries = 0,
            acceptTypes = setOf(RaceFrame.TYPE_CMD),
            replyFlagMask = rxMask(),
        )
        if (rx != null) return rx.rx(6) == 0
        if (!race.connected.value) return true
        return withTimeoutOrNull(AirohaRace.TIMEOUT_MS) { race.connected.first { !it } } != null
    }

    /**
     * 0x1C03 cancel (§5.1). Best effort: the caller is already on a failure or
     * user-cancel path, so a missing reply changes nothing.
     */
    suspend fun cancel(reason: Int) {
        cancelRequested = true
        // Outside a run there is no FOTA state to abandon, so stay silent.
        if (!flowActive && !sessionOpen) return
        race.request(
            raceId = AirohaRace.CANCEL,
            payload = AirohaRequests.cancel(reason),
            flag = txFlag(),
            timeoutMs = AirohaRace.CANCEL_TIMEOUT_MS,
            retries = AirohaRace.ATTEMPTS - 1,
            acceptTypes = setOf(RaceFrame.TYPE_RSP),
            // Teardown: take the reply whatever byte0 it carries.
            replyFlagMask = 0,
        )
        // Only a cancel from outside a run ends the session here; a running
        // [transfer] still has frames to send and clears the flag itself.
        if (!flowActive) {
            sessionOpen = false
            rxFilterOn = false
        }
    }

    // ---- Stages ------------------------------------------------------------

    /** 0x0433 per region; a set bit means "sector already erased" (§3.4). */
    private suspend fun readEraseStatus(partition: PartitionInfo, sectors: List<FlashPlan.Sector>): FotaPhase? {
        val total = sectors.size * FlashPlan.SECTOR
        var addr = partition.addr
        var remaining = total
        var index = 0
        while (remaining > 0) {
            val length = minOf(FlashPlan.REGION, remaining)
            val rx = command(
                AirohaRace.GET_ERASE_STATUS,
                AirohaRequests.getEraseStatus(partition.storageType, addr, length),
                RaceFrame.TYPE_NOTIFY,
            ) ?: return abort(FotaFailure.TIMEOUT, "no reply to 0x0433 at 0x${hex8(addr)}")
            if (rx.rx(6) != 0) {
                return abort(FotaFailure.DEVICE_REFUSED, "0x0433 status 0x${hex2(rx.rx(6))}")
            }
            if (le32(rx, 9) != addr) {
                return abort(FotaFailure.OTHER, "0x0433 echoed 0x${hex8(le32(rx, 9))}, expected 0x${hex8(addr)}")
            }
            // The bitmap is indexed off the region length the DEVICE echoes
            // (`g8/h.java:145`); decoding it against a different length would
            // mark the wrong sectors erased.
            if (le32(rx, 13) != length) {
                return abort(FotaFailure.OTHER, "0x0433 echoed length ${le32(rx, 13)}, expected $length")
            }
            val count = length / FlashPlan.SECTOR
            val bitmapBytes = le16(rx, 17)
            if (bitmapBytes * 8 < count || rx.rx(19 + bitmapBytes - 1) < 0) {
                return abort(FotaFailure.OTHER, "0x0433 bitmap too short ($bitmapBytes B for $count sectors)")
            }
            for (i in 0 until count) {
                val mask = 0x80 shr (i % 8)
                sectors[index + i].erased = (rx.rx(19 + i / 8) and mask) == mask
            }
            index += count
            addr += length
            remaining -= length
        }
        return null
    }

    /**
     * 0x0431 (§3.5). Only the LEADING run of not-yet-erased sectors is
     * compared, exactly as `g8/i.java:39-42` scans: a tail command over the
     * last sector of that run, then group commands of up to 128 sectors over
     * everything before it. A digest match clears [FlashPlan.Sector.needsWrite]
     * so those sectors are neither erased nor written.
     */
    private suspend fun compare(partition: PartitionInfo, sectors: List<FlashPlan.Sector>): FotaPhase? {
        var last = -1
        for (i in sectors.indices) {
            if (sectors[i].erased) break
            last = i
        }
        if (last < 0) return null

        val tail = sectors[last]
        when (val outcome = compareRun(partition, tail.addr, tail.len, tail.content)) {
            is CompareOutcome.Failed -> return outcome.phase
            is CompareOutcome.Match -> tail.needsWrite = false
            CompareOutcome.Differs -> Unit
        }

        val groupSize = FlashPlan.REGION / FlashPlan.SECTOR
        var i = 0
        while (i < last) {
            val end = minOf(i + groupSize, last)
            val group = sectors.subList(i, end)
            val data = ByteArray(group.sumOf { it.len })
            var at = 0
            for (sector in group) {
                sector.content.copyInto(data, at)
                at += sector.len
            }
            when (val outcome = compareRun(partition, group.first().addr, data.size, data)) {
                is CompareOutcome.Failed -> return outcome.phase
                is CompareOutcome.Match -> group.forEach { it.needsWrite = false }
                CompareOutcome.Differs -> Unit
            }
            i = end
        }
        return null
    }

    private sealed interface CompareOutcome {
        data object Match : CompareOutcome
        data object Differs : CompareOutcome
        data class Failed(val phase: FotaPhase) : CompareOutcome
    }

    private suspend fun compareRun(
        partition: PartitionInfo,
        addr: Int,
        length: Int,
        data: ByteArray,
    ): CompareOutcome {
        val rx = command(
            AirohaRace.COMPARE,
            AirohaRequests.compare(partition.storageType, addr, length),
            RaceFrame.TYPE_NOTIFY,
        ) ?: return CompareOutcome.Failed(abort(FotaFailure.TIMEOUT, "no reply to 0x0431 at 0x${hex8(addr)}"))
        if (rx.rx(6) != 0) {
            return CompareOutcome.Failed(abort(FotaFailure.DEVICE_REFUSED, "0x0431 status 0x${hex2(rx.rx(6))}"))
        }
        if (le32(rx, 9) != addr) {
            return CompareOutcome.Failed(
                abort(FotaFailure.OTHER, "0x0431 echoed 0x${hex8(le32(rx, 9))}, expected 0x${hex8(addr)}"),
            )
        }
        if (rx.rx(48) < 0) {
            return CompareOutcome.Failed(abort(FotaFailure.OTHER, "0x0431 reply has no digest"))
        }
        val deviceDigest = ByteArray(32) { rx.rx(17 + it).toByte() }
        return if (deviceDigest.contentEquals(FlashPlan.sha256(data))) {
            CompareOutcome.Match
        } else {
            CompareOutcome.Differs
        }
    }

    private suspend fun writeState(state: Int): FotaPhase? =
        commandOk(AirohaRace.WRITE_STATE, AirohaRequests.writeState(state), RaceFrame.TYPE_RSP)

    /** 0x0404, one sector (§3.8). The reply echoes the address at `rx[12..15]`. */
    private suspend fun eraseSector(partition: PartitionInfo, sector: FlashPlan.Sector): FotaPhase? {
        val rx = command(
            AirohaRace.ERASE,
            AirohaRequests.erase(partition.storageType, sector.addr),
            RaceFrame.TYPE_NOTIFY,
        ) ?: return abort(FotaFailure.TIMEOUT, "no reply to 0x0404 at 0x${hex8(sector.addr)}")
        if (rx.rx(6) != 0) return abort(FotaFailure.DEVICE_REFUSED, "0x0404 status 0x${hex2(rx.rx(6))}")
        if (le32(rx, 12) != sector.addr) {
            return abort(FotaFailure.OTHER, "0x0404 acked 0x${hex8(le32(rx, 12))}, expected 0x${hex8(sector.addr)}")
        }
        sector.erased = true
        return null
    }

    // ---- 0x0402 page writes ------------------------------------------------

    private class WriteCommand(val addr: Int, val frame: ByteArray) {
        var packetIndex = 0
        var lagResends = 0
    }

    /**
     * 0x0402 for every page of every sector still needing programming (§3.9,
     * §6.5). Background mode concatenates up to 3 complete frames into one
     * write, keeps at most 4 packets outstanding, paces writes, and resends a
     * command whose packet index has fallen [AirohaRace.RESEND_LAG] behind the
     * newest. Active mode is the same code with one command per write and no
     * pacing.
     */
    private suspend fun writePages(
        partition: PartitionInfo,
        sectors: List<FlashPlan.Sector>,
    ): FotaPhase? = coroutineScope {
        val queue = ArrayDeque<WriteCommand>()
        for (sector in sectors) {
            // Fail closed: a sector that is neither erased nor verified-equal
            // must never be programmed.
            if (!sector.erased) {
                return@coroutineScope abort(
                    FotaFailure.OTHER,
                    "refusing to write un-erased sector 0x${hex8(sector.addr)}",
                )
            }
            for ((addr, page) in FlashPlan.pages(sector)) {
                val payload = AirohaRequests.writeFlash(partition.storageType, listOf(FlashPlan.record(addr, page)))
                queue.add(WriteCommand(addr, RaceFrame.encode(txFlag(), RaceFrame.TYPE_CMD, AirohaRace.WRITE_FLASH, payload)))
            }
        }
        val totalPages = queue.size
        if (totalPages == 0) return@coroutineScope null

        val acks = Channel<RaceMessage>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch {
            race.messages
                .onSubscription { subscribed.complete(Unit) }
                .collect { message ->
                    if (message.raceId == AirohaRace.WRITE_FLASH &&
                        message.type == RaceFrame.TYPE_RSP &&
                        (message.flag and rxMask()) == rxMask()
                    ) {
                        acks.trySend(message)
                    }
                }
        }
        subscribed.await()

        try {
            val pending = LinkedHashMap<Int, WriteCommand>()
            var written = 0
            var packetIndex = 0
            var lastProgressAt = clock()

            while (queue.isNotEmpty() || pending.isNotEmpty()) {
                if (cancelRequested) return@coroutineScope cancelled()

                packetIndex++
                val batch = ArrayList<WriteCommand>(commandsPerPacket)
                for (command in pending.values.toList()) {
                    if (batch.size >= commandsPerPacket) break
                    if (command.packetIndex + AirohaRace.RESEND_LAG >= packetIndex) continue
                    // A lag resend is pipeline slack, not a device failure, so
                    // it gets its own generous cap; the stall watchdog below is
                    // what actually ends a dead transfer.
                    if (command.lagResends >= AirohaRace.MAX_LAG_RESENDS) {
                        return@coroutineScope abort(
                            FotaFailure.TRANSFER_FAILED,
                            "page 0x${hex8(command.addr)} not acked after ${command.lagResends} resends",
                        )
                    }
                    command.lagResends++
                    command.packetIndex = packetIndex
                    batch.add(command)
                }
                // The window caps outstanding PACKETS (§6.4), and one packet
                // carries [commandsPerPacket] commands.
                while (batch.size < commandsPerPacket &&
                    pending.size < AirohaRace.MAX_OUTSTANDING * commandsPerPacket &&
                    queue.isNotEmpty()
                ) {
                    val command = queue.removeFirst()
                    command.packetIndex = packetIndex
                    pending[command.addr] = command
                    batch.add(command)
                }

                if (batch.isNotEmpty()) {
                    val packet = ByteArray(batch.sumOf { it.frame.size })
                    var at = 0
                    for (command in batch) {
                        command.frame.copyInto(packet, at)
                        at += command.frame.size
                    }
                    if (!race.writeRaw(packet)) {
                        return@coroutineScope abort(FotaFailure.TRANSFER_FAILED, "0x0402 write failed")
                    }
                    // Pacing doubles as the point where acks are collected; in
                    // active mode a plain yield plays that part.
                    if (pacingMs > 0) delay(pacingMs) else yield()
                } else {
                    // Window full, or every command is still inside its resend
                    // lag: wait briefly for an ack instead of spinning. Giving
                    // up is the watchdog's job, not this wait's.
                    val idleMs = if (pacingMs > 0) pacingMs else IDLE_WAIT_MS
                    val ack = withTimeoutOrNull(idleMs) { acks.receive() }
                    if (ack != null) {
                        handleWriteAck(ack, pending)?.let { return@coroutineScope it }
                        lastProgressAt = clock()
                    }
                }

                while (true) {
                    val ack = acks.tryReceive().getOrNull() ?: break
                    handleWriteAck(ack, pending)?.let { return@coroutineScope it }
                    lastProgressAt = clock()
                }

                val acked = totalPages - queue.size - pending.size
                if (acked != written) {
                    written = acked
                    _phase.value = FotaPhase.Transferring((written.toLong() * 100 / totalPages).toInt())
                }
                if (clock() - lastProgressAt > AirohaRace.TIMEOUT_MS) {
                    return@coroutineScope abort(FotaFailure.TIMEOUT, "0x0402 stalled with ${pending.size} in flight")
                }
            }
            _phase.value = FotaPhase.Transferring(100)
            null
        } finally {
            collector.cancel()
            acks.close()
        }
    }

    /**
     * One 0x0402 reply: `rx[8]` acked-page count, addresses from `rx[9]` LE32.
     * ANY non-zero status is a device error here: the 0x80 busy bit is stripped
     * only in adaptive mode, which MT2822/MT2833 never run (§6.2).
     */
    private suspend fun handleWriteAck(rx: RaceMessage, pending: MutableMap<Int, WriteCommand>): FotaPhase? {
        val status = rx.rx(6)
        if (status != 0) {
            return abort(FotaFailure.DEVICE_REFUSED, "0x0402 status 0x${hex2(status)}")
        }
        val count = rx.rx(8)
        if (count <= 0) return null
        for (i in 0 until count) {
            if (rx.rx(9 + i * 4 + 3) < 0) break
            pending.remove(le32(rx, 9 + i * 4))
        }
        return null
    }

    // ---- Plumbing ----------------------------------------------------------

    private fun txFlag(): Int = if (sessionOpen) RaceFrame.FLAG_SESSION else RaceFrame.FLAG_NONE

    private fun rxMask(): Int = if (rxFilterOn) RaceFrame.FLAG_SESSION else 0

    /**
     * One stage command: 9000 ms, 3 sends, the response type the spec lists.
     *
     * A stage whose result is a 0x5D may still be REFUSED by a 0x5B carrying a
     * non-zero status (§6.1: `handleResp` matches on the race id alone). Take
     * that refusal immediately instead of resending for the whole 27 s budget.
     * A 0x5B with status 0 is only an acknowledgement, so it is skipped and the
     * wait for the 0x5D continues inside the same subscription.
     */
    private suspend fun command(raceId: Int, payload: ByteArray, acceptType: Int): RaceMessage? {
        val notifyStage = acceptType == RaceFrame.TYPE_NOTIFY
        return race.request(
            raceId = raceId,
            payload = payload,
            flag = txFlag(),
            timeoutMs = AirohaRace.TIMEOUT_MS,
            retries = AirohaRace.ATTEMPTS - 1,
            acceptTypes = if (notifyStage) NOTIFY_OR_RSP else setOf(acceptType),
            replyFlagMask = rxMask(),
            accept = { if (notifyStage) it.type == RaceFrame.TYPE_NOTIFY || it.rx(6) != 0 else true },
        )
    }

    /** [command] plus the "status must be 0" rule; returns a terminal phase or null. */
    private suspend fun commandOk(raceId: Int, payload: ByteArray, acceptType: Int): FotaPhase? {
        val rx = command(raceId, payload, acceptType)
            ?: return abort(FotaFailure.TIMEOUT, "no reply to 0x${hex4(raceId)}")
        if (rx.rx(6) != 0) {
            return abort(FotaFailure.DEVICE_REFUSED, "0x${hex4(raceId)} status 0x${hex2(rx.rx(6))}")
        }
        return null
    }

    /** Every failure past the point of no return tells the device to stop (§5.1). */
    private suspend fun abort(reason: FotaFailure, detail: String): FotaPhase {
        cancel(AirohaRace.CANCEL_REASON_STAGE_ERROR)
        return FotaPhase.Failed(reason, detail)
    }

    private suspend fun cancelled(): FotaPhase {
        cancel(AirohaRace.CANCEL_REASON_USER)
        return FotaPhase.Cancelled
    }

    private fun le16(rx: RaceMessage, at: Int): Int = (rx.rx(at) and 0xFF) or ((rx.rx(at + 1) and 0xFF) shl 8)

    private fun le32(rx: RaceMessage, at: Int): Int =
        (rx.rx(at) and 0xFF) or
            ((rx.rx(at + 1) and 0xFF) shl 8) or
            ((rx.rx(at + 2) and 0xFF) shl 16) or
            ((rx.rx(at + 3) and 0xFF) shl 24)

    private fun hex2(v: Int) = "%02x".format(v)
    private fun hex4(v: Int) = "%04x".format(v)
    private fun hex8(v: Int) = "%08x".format(v)

    private companion object {
        /** Active mode has no pacing, so idle polls need their own short wait. */
        const val IDLE_WAIT_MS = 20L

        /** A 0x5D stage also has to hear a 0x5B refusal for the same race id. */
        val NOTIFY_OR_RSP = setOf(RaceFrame.TYPE_NOTIFY, RaceFrame.TYPE_RSP)
    }
}
