package com.thelightphone.sonywf.update.airoha

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.security.MessageDigest

/** How the emulated MT2822 answers each stage. */
internal class AirohaDeviceConfig(
    val chipName: String? = "MT2822S",
    val partitionAddr: Int = 0x00BDA000,
    val partitionLength: Int = 0x00962000,
    val storageType: Int = 0,
    val state: Int = AirohaRace.STATE_IDLE,
    /** Sectors (from the partition base) the 0x0433 bitmap reports as already erased. */
    val erasedSectors: Int = 0,
    /** Bytes the device already holds at the partition base, for the 0x0431 digests. */
    val preload: ByteArray? = null,
    /** LE16 at rx[9..10] of the 0x1C1C reply; 0 keeps the client's own pacing. */
    val intervalMs: Int = 0,
    val replyToInterval: Boolean = true,
    /** raceId -> status byte, to force a refusal. */
    val statusOverrides: Map<Int, Int> = emptyMap(),
    /** Drop the FIRST ack for these page addresses, to force a retransmit. */
    val dropFirstAckFor: Set<Int> = emptySet(),
    /** Answer the first 0x0402 with the busy bit set. */
    val busyFirstWrite: Boolean = false,
    /** Never ack a page write, to exercise the outstanding-window cap. */
    val ackWrites: Boolean = true,
    /** When false 0x1C06 is acked but the state does not move, so the final 0x1C04 disagrees. */
    val honourStateWrites: Boolean = true,
    /** Commit reboots the device: the socket dies with no reply. */
    val closeOnCommit: Boolean = true,
    val commitStatus: Int = 0,
    /** Fired when 0x1C02 lands, to model the reboot killing the MDR link too. */
    val onCommit: () -> Unit = {},
)

/**
 * A [LightSerialConnection] speaking the MT28xx single-device FOTA protocol
 * (spec-airoha-mt28xx-single). It decodes every RACE frame the client writes,
 * tracks erase/write state, computes the real SHA-256 for 0x0431, answers
 * 0x0433 with a bitmap, and enforces the 0x15 session flag after FOTA start.
 */
internal class FakeAirohaFotaDevice(private val config: AirohaDeviceConfig = AirohaDeviceConfig()) {

    /** Raw writes, so long-packet assembly can be inspected. */
    val writes = mutableListOf<ByteArray>()

    /** Every command race id in arrival order. */
    val commands = mutableListOf<Int>()

    /** Sector addresses erased, in order. */
    val erases = mutableListOf<Int>()

    /** `(address, 256 bytes)` of every accepted page write, in order. */
    val pageWrites = mutableListOf<Pair<Int, ByteArray>>()

    /** 0x1C06 states written, in order. */
    val stateWrites = mutableListOf<Int>()

    /** 0x1C03 reasons received. */
    val cancels = mutableListOf<Int>()

    /** Frames that arrived without byte0 = 0x15 after FOTA start. */
    val flagViolations = mutableListOf<Int>()

    /** Page writes aimed at a sector the device does not consider erased. */
    val writesToUnerasedSectors = mutableListOf<Int>()

    /** Page records whose CRC-8 did not match the data. */
    val badCrcPages = mutableListOf<Int>()

    var deviceState: Int = config.state
        private set

    private val flash = HashMap<Int, Byte>()
    private val erased = HashSet<Int>()
    private val ackDropped = HashSet<Int>()
    private var writeCommands = 0
    private var sessionOpen = false

    /** The socket currently open; the Sony app closes and reopens one (spec §1.5). */
    private var socket: Socket? = null

    /** Sockets opened so far, in order. */
    val sockets = mutableListOf<Socket>()

    val isConnected: Boolean get() = socket?.isConnected == true

    init {
        repeat(config.erasedSectors) { erased.add(config.partitionAddr + it * FlashPlan.SECTOR) }
        config.preload?.forEachIndexed { i, byte -> flash[config.partitionAddr + i] = byte }
    }

    /** Open a fresh RFCOMM socket to this device; the flash state survives. */
    fun openSocket(): Socket {
        val opened = Socket()
        socket = opened
        sockets.add(opened)
        return opened
    }

    internal inner class Socket : LightSerialConnection {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)
        private val decoder = RaceDecoder()
        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
        override var isConnected: Boolean = true
            private set

        override suspend fun write(bytes: ByteArray) {
            if (!isConnected) return
            writes.add(bytes.copyOf())
            for (message in decoder.feed(bytes)) handle(message)
        }

        override fun close() {
            isConnected = false
            channel.close()
        }

        fun deliver(frame: ByteArray) {
            if (isConnected) channel.trySend(frame)
        }
    }

    /** Bytes the device holds for [length] from [addr], 0xFF where never written. */
    fun read(addr: Int, length: Int): ByteArray =
        ByteArray(length) { flash[addr + it] ?: 0xFF.toByte() }

    // ---- Protocol ----------------------------------------------------------

    private fun reply(type: Int, raceId: Int, payload: ByteArray) {
        val flag = if (sessionOpen) RaceFrame.FLAG_SESSION else RaceFrame.FLAG_NONE
        socket?.deliver(RaceFrame.encode(flag, type, raceId, payload))
    }

    private fun status(raceId: Int): Int = config.statusOverrides[raceId] ?: 0

    private fun handle(message: RaceMessage) {
        if (sessionOpen && message.flag != RaceFrame.FLAG_SESSION) flagViolations.add(message.raceId)
        commands.add(message.raceId)
        val payload = message.payload
        when (message.raceId) {
            AirohaRace.READ_NVKEY -> {
                val name = (config.chipName ?: "").toByteArray(Charsets.ISO_8859_1)
                reply(
                    RaceFrame.TYPE_RSP,
                    message.raceId,
                    byteArrayOf((name.size and 0xFF).toByte(), ((name.size ushr 8) and 0xFF).toByte()) + name,
                )
            }

            AirohaRace.INQUIRY_FOTA -> reply(
                RaceFrame.TYPE_RSP,
                message.raceId,
                byteArrayOf(status(message.raceId).toByte(), 0x00, config.storageType.toByte()) +
                    le32(config.partitionAddr) + le32(config.partitionLength),
            )

            AirohaRace.QUERY_STATE -> reply(
                RaceFrame.TYPE_RSP,
                message.raceId,
                byteArrayOf(
                    status(message.raceId).toByte(),
                    (deviceState and 0xFF).toByte(),
                    ((deviceState ushr 8) and 0xFF).toByte(),
                ),
            )

            AirohaRace.FOTA_START -> {
                val st = status(message.raceId)
                reply(RaceFrame.TYPE_NOTIFY, message.raceId, byteArrayOf(st.toByte()))
                if (st == 0) sessionOpen = true
            }

            AirohaRace.QUERY_TRANSMIT_INTERVAL -> {
                if (!config.replyToInterval) return
                reply(
                    RaceFrame.TYPE_NOTIFY,
                    message.raceId,
                    byteArrayOf(
                        status(message.raceId).toByte(),
                        0x01,
                        AirohaRace.ROLE_SINGLE.toByte(),
                        (config.intervalMs and 0xFF).toByte(),
                        ((config.intervalMs ushr 8) and 0xFF).toByte(),
                    ),
                )
            }

            AirohaRace.GET_ERASE_STATUS -> {
                val addr = le32At(payload, 2)
                val length = le32At(payload, 6)
                val count = length / FlashPlan.SECTOR
                val bitmap = ByteArray((count + 7) / 8)
                for (i in 0 until count) {
                    if ((addr + i * FlashPlan.SECTOR) in erased) {
                        bitmap[i / 8] = (bitmap[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
                    }
                }
                reply(
                    RaceFrame.TYPE_NOTIFY,
                    message.raceId,
                    byteArrayOf(status(message.raceId).toByte(), 0x01, AirohaRace.ROLE_SINGLE.toByte()) +
                        le32(addr) + le32(length) +
                        byteArrayOf((bitmap.size and 0xFF).toByte(), ((bitmap.size ushr 8) and 0xFF).toByte()) +
                        bitmap,
                )
            }

            AirohaRace.COMPARE -> {
                val addr = le32At(payload, 2)
                val length = le32At(payload, 6)
                val digest = MessageDigest.getInstance("SHA-256").digest(read(addr, length))
                reply(
                    RaceFrame.TYPE_NOTIFY,
                    message.raceId,
                    byteArrayOf(status(message.raceId).toByte(), 0x01, AirohaRace.ROLE_SINGLE.toByte()) +
                        le32(addr) + le32(length) + digest,
                )
            }

            AirohaRace.START_TRANSACTION ->
                reply(RaceFrame.TYPE_RSP, message.raceId, byteArrayOf(status(message.raceId).toByte()))

            AirohaRace.WRITE_STATE -> {
                val state = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                stateWrites.add(state)
                if (status(message.raceId) == 0 && config.honourStateWrites) deviceState = state
                reply(RaceFrame.TYPE_RSP, message.raceId, byteArrayOf(status(message.raceId).toByte()))
            }

            AirohaRace.ERASE -> {
                val addr = le32At(payload, 5)
                val st = status(message.raceId)
                if (st == 0) {
                    erases.add(addr)
                    erased.add(addr)
                    for (i in 0 until FlashPlan.SECTOR) flash.remove(addr + i)
                }
                reply(
                    RaceFrame.TYPE_NOTIFY,
                    message.raceId,
                    byteArrayOf(st.toByte(), config.storageType.toByte()) + le32(FlashPlan.SECTOR) + le32(addr),
                )
            }

            AirohaRace.WRITE_FLASH -> handleWrite(message)

            AirohaRace.CHECK_INTEGRITY -> reply(
                RaceFrame.TYPE_NOTIFY,
                message.raceId,
                byteArrayOf(
                    status(message.raceId).toByte(),
                    0x01,
                    AirohaRace.ROLE_SINGLE.toByte(),
                    config.storageType.toByte(),
                ),
            )

            AirohaRace.COMMIT -> {
                config.onCommit()
                if (config.closeOnCommit) {
                    socket?.close()
                } else {
                    reply(RaceFrame.TYPE_CMD, message.raceId, byteArrayOf(config.commitStatus.toByte()))
                }
            }

            AirohaRace.CANCEL -> {
                cancels.add(payload[2].toInt() and 0xFF)
                // The reply still carries the session flag; the host clears it
                // only after the reply lands (`f8/c.java:41`).
                reply(RaceFrame.TYPE_RSP, message.raceId, byteArrayOf(0x00))
                sessionOpen = false
            }
        }
    }

    private fun handleWrite(message: RaceMessage) {
        val payload = message.payload
        val count = payload[1].toInt() and 0xFF
        val busy = config.busyFirstWrite && writeCommands == 0
        writeCommands++

        val acked = ArrayList<Int>(count)
        for (i in 0 until count) {
            val at = 2 + i * FlashPlan.RECORD
            val crc = payload[at].toInt() and 0xFF
            val addr = le32At(payload, at + 1)
            val data = payload.copyOfRange(at + 5, at + FlashPlan.RECORD)
            if (Crc8.of(data) != crc) badCrcPages.add(addr)
            val sector = addr - (addr - config.partitionAddr) % FlashPlan.SECTOR
            if (sector !in erased) writesToUnerasedSectors.add(addr)
            if (!busy) {
                pageWrites.add(addr to data)
                data.forEachIndexed { j, byte -> flash[addr + j] = byte }
            }
            if (addr in config.dropFirstAckFor && ackDropped.add(addr)) continue
            acked.add(addr)
        }
        if (!config.ackWrites) return
        if (acked.isEmpty() && !busy) return

        val addresses = if (busy) (0 until count).map { le32At(payload, 2 + it * FlashPlan.RECORD + 1) } else acked
        var out = byteArrayOf(
            (if (busy) AirohaRace.BUSY_BIT else 0).toByte(),
            config.storageType.toByte(),
            addresses.size.toByte(),
        )
        for (addr in addresses) out += le32(addr)
        reply(RaceFrame.TYPE_RSP, message.raceId, out)
    }

    private fun le32(value: Int): ByteArray {
        val out = ByteArray(4)
        FlashPlan.putLe32(out, 0, value)
        return out
    }

    private fun le32At(payload: ByteArray, at: Int): Int = FlashPlan.le32(payload, at)
}
