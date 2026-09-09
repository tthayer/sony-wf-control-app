package com.thelightphone.sonywf.update

import com.thelightphone.sdk.bluetooth.LightBluetoothException
import com.thelightphone.sdk.bluetooth.LightSerialConnection
import com.thelightphone.sonywf.protocol.SonyDialect
import com.thelightphone.sonywf.protocol.SonyFrame
import com.thelightphone.sonywf.protocol.SonyFrameDecoder
import com.thelightphone.sonywf.protocol.SonyMessage
import com.thelightphone.sonywf.protocol.SonyProtocolClient
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Byte helper that keeps values > 0x7F legal. */
private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

private fun str(s: String): ByteArray =
    byteArrayOf(s.length.toByte()) + s.toByteArray(Charsets.US_ASCII)

private fun u32(v: Int): ByteArray = b((v ushr 24) and 0xFF, (v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF)

/** How the emulated device answers each FOTA step. */
private class FotaDevice(
    val supportFunctions: Set<Int> = setOf(0x30),
    val enterResult: Int = 0x00,
    val startResult: Int = 0x00,
    val maxPacketSize: Int = 128,
    val startOffset: Int = 0,
    val finishResult: Int = 0x00,
    val executeResult: Int = 0x00,
    val requiredTimeSec: Int = 30,
    /** Status pushed instead of DATA_RECEIVING once this many chunks have landed. */
    val abortAfterChunks: Int? = null,
    val abortStatus: Int = 0x02, //   NOT_READY
    val completeOnExecute: Boolean = true,
    /** Virtual milliseconds each firmware chunk takes, so tests can interleave. */
    val chunkDelayMs: Long = 0,
)

/**
 * A [LightSerialConnection] emulating a V2 Tandem-FOTA-capable device: it ACKs
 * every frame (seq = 1 - clientSeq, like the real device), answers the
 * discovery queries, and drives the UPDT handshake with the paired
 * result + status notifications.
 */
private class FakeFotaConnection(private val device: FotaDevice) : LightSerialConnection {
    val writes = mutableListOf<ByteArray>()

    /** `offset to data` of every UPDT_TRANSFER_DATA received, in arrival order. */
    val chunks = mutableListOf<Pair<Int, ByteArray>>()

    /** Frame type of every UPDT_TRANSFER_DATA frame received. */
    val chunkFrameTypes = mutableListOf<Int>()

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    override var isConnected: Boolean = true
        private set

    private val decoder = SonyFrameDecoder()
    private var deviceSeq = 1

    /** Emulate the reboot-time link drop: writes fail, inbound stays alive. */
    fun dropLink() {
        isConnected = false
    }

    override suspend fun write(bytes: ByteArray) {
        if (!isConnected) throw LightBluetoothException("link down")
        writes.add(bytes.copyOf())
        for (msg in decoder.feed(bytes)) {
            if (msg.type == SonyFrame.TYPE_LARGE_DATA_MDR && device.chunkDelayMs > 0) {
                delay(device.chunkDelayMs)
            }
            respond(msg)
        }
    }

    override fun close() {
        isConnected = false
        channel.close()
    }

    fun deliverCompleted() {
        sendCommand1(b(0x3F, 0x10, 0x01, 0x01, 0x00))
    }

    private fun send(type: Int, seq: Int, payload: ByteArray) {
        channel.trySend(SonyFrame.encode(type, seq, payload))
    }

    private fun sendCommand1(payload: ByteArray) {
        send(SonyFrame.TYPE_COMMAND1, deviceSeq, payload)
        deviceSeq = 1 - deviceSeq
    }

    private fun supportFunctionReply(): ByteArray {
        val fns = device.supportFunctions.toList()
        var out = b(0x07, 0x00, fns.size)
        for (fn in fns) out += b(fn, 0x00)
        return out
    }

    private fun paramReply(): ByteArray =
        b(0x37, 0x10) + str("HP") + str("SVC") + str("US") + str("English") + str("SN1") +
            b(30, 20) + str("uid")

    private fun respond(msg: SonyMessage) {
        val p = msg.payload
        if (msg.type == SonyFrame.TYPE_LARGE_DATA_MDR) {
            send(SonyFrame.TYPE_ACK, (1 - msg.seq) and 0xFF, ByteArray(0))
            if (p.size >= 10 && (p[0].toInt() and 0xFF) == 0x3E) {
                val offset = ((p[2].toInt() and 0xFF) shl 24) or ((p[3].toInt() and 0xFF) shl 16) or
                    ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
                chunks.add(offset to p.copyOfRange(10, p.size))
                chunkFrameTypes.add(msg.type)
                if (device.abortAfterChunks == chunks.size) {
                    sendCommand1(b(0x35, 0x10, device.abortStatus))
                }
            }
            return
        }
        if (msg.type != SonyFrame.TYPE_COMMAND1 && msg.type != SonyFrame.TYPE_COMMAND2) return
        send(SonyFrame.TYPE_ACK, (1 - msg.seq) and 0xFF, ByteArray(0))

        val op = if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
        val sub = if (p.size >= 2) p[1].toInt() and 0xFF else -1
        val cmd = if (p.size >= 3) p[2].toInt() and 0xFF else -1
        when {
            op == 0x00 && sub == 0x00 -> sendCommand1(ByteArray(8).also { it[0] = 0x01 }) // V2 Init reply
            op == 0x22 -> sendCommand1(b(0x23, 0x09, 70, 0x00, 80, 0x00))
            op == 0x66 -> sendCommand1(b(0x67, 0x15, 0x01, 0x01, 0x00, 0x00, 0x00))
            op == 0x04 && sub == 0x02 -> sendCommand1(b(0x05, 0x02) + str("2.0.0"))
            op == 0x04 && sub == 0x01 -> sendCommand1(b(0x05, 0x01) + str("WF-1000XM5"))
            op == 0x06 -> sendCommand1(supportFunctionReply())
            op == 0x30 -> sendCommand1(b(0x31, 0x10, 0x04, 0x01, 0x01, 0x00, 0x00))
            op == 0x36 -> sendCommand1(paramReply())

            // ENTER / EXIT / FINISH / CANCEL.
            op == 0x38 && sub == 0x11 -> {
                val result = when (cmd) {
                    0x01 -> device.enterResult
                    0x04 -> device.finishResult
                    else -> 0x00
                }
                sendCommand1(b(0x39, 0x11, cmd, result))
                if (result == 0x00) {
                    val status = if (cmd == 0x02) 0x00 else 0x01 // EXIT -> INVALID, else IDLE
                    sendCommand1(b(0x35, 0x10, status))
                }
            }

            // START_TRANSFER.
            op == 0x38 && sub == 0x12 -> {
                sendCommand1(
                    b(0x39, 0x12, 0x03, device.startResult) +
                        u32(device.maxPacketSize) + u32(device.startOffset),
                )
                if (device.startResult == 0x00) sendCommand1(b(0x35, 0x10, 0x03)) // DATA_RECEIVING
            }

            // EXECUTE_FW_UPDATE.
            op == 0x38 && sub == 0x13 -> {
                sendCommand1(
                    b(0x39, 0x13, 0x06, device.executeResult) +
                        b((device.requiredTimeSec ushr 8) and 0xFF, device.requiredTimeSec and 0xFF),
                )
                if (device.executeResult == 0x00) {
                    sendCommand1(b(0x35, 0x10, 0x04)) // UPDATING
                    if (device.completeOnExecute) deliverCompleted()
                }
            }
        }
    }
}

/** Decode every client write into the frames the device would see. */
private fun decodeWrites(writes: List<ByteArray>): List<SonyMessage> =
    writes.flatMap { SonyFrameDecoder().feed(it) }

private fun payloadsWithOpcode(writes: List<ByteArray>, opcode: Int, sub: Int, cmd: Int): List<ByteArray> =
    decodeWrites(writes)
        .map { it.payload }
        .filter {
            it.size >= 3 && (it[0].toInt() and 0xFF) == opcode &&
                (it[1].toInt() and 0xFF) == sub && (it[2].toInt() and 0xFF) == cmd
        }

private fun image(size: Int, digest: DigestType = DigestType.NONE, mac: String = ""): FirmwareImage =
    FirmwareImage(
        bytes = ByteArray(size) { (it % 251).toByte() },
        version = "3.0.1",
        fileName = "abcd1234",
        digest = digest,
        macHex = mac,
    )

class TandemFotaSessionTest {

    @Test
    fun happyPathTransfersTheWholeImageAndCompletes() = runTest {
        val device = FotaDevice(maxPacketSize = 128)
        val conn = FakeFotaConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        // Discovery populated the update flows.
        assertEquals(SonyDialect.V2, client.dialect.value)
        assertEquals("WF-1000XM5", client.modelName.value)
        assertEquals(setOf(0x30), client.supportFunctions.value)
        assertEquals(FirmwareUpdateMethod.TANDEM, client.updateMethod.value)
        assertEquals(
            UpdateCapability(resumable = true, tws = true, backgroundTransfer = false, acCheck = false),
            client.updateCapability.value,
        )
        assertEquals(30, client.updateParams.value?.batteryThreshold)

        val img = image(500)
        val session = TandemFotaSession(client) { currentTime }
        val terminal = session.run(img)

        assertEquals(FotaPhase.Completed, terminal)
        assertEquals(FotaPhase.Completed, session.phase.value)

        // Every chunk but the last is exactly maxPacketSize; offsets are contiguous.
        assertEquals(listOf(128, 128, 128, 116), conn.chunks.map { it.second.size })
        assertEquals(listOf(0, 128, 256, 384), conn.chunks.map { it.first })
        assertContentEquals(img.bytes, conn.chunks.fold(ByteArray(0)) { acc, c -> acc + c.second })

        // Firmware chunks must go out as LARGE_DATA_MDR (0x2c), not Command1.
        assertTrue(conn.chunkFrameTypes.isNotEmpty())
        assertTrue(conn.chunkFrameTypes.all { it == SonyFrame.TYPE_LARGE_DATA_MDR })
        assertEquals(0x2c, SonyFrame.TYPE_LARGE_DATA_MDR)

        // The handshake frames were sent, in order, on Command1.
        assertEquals(1, payloadsWithOpcode(conn.writes, 0x38, 0x11, 0x01).size) // ENTER
        assertEquals(1, payloadsWithOpcode(conn.writes, 0x38, 0x12, 0x03).size) // START
        assertEquals(1, payloadsWithOpcode(conn.writes, 0x38, 0x11, 0x04).size) // FINISH
        assertEquals(1, payloadsWithOpcode(conn.writes, 0x38, 0x13, 0x06).size) // EXECUTE

        client.stop()
    }

    @Test
    fun resumeOffsetIsHonoured() = runTest {
        val conn = FakeFotaConnection(FotaDevice(maxPacketSize = 100, startOffset = 300))
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val img = image(500)
        assertEquals(FotaPhase.Completed, TandemFotaSession(client) { currentTime }.run(img))

        assertEquals(listOf(300, 400), conn.chunks.map { it.first })
        assertContentEquals(img.bytes.copyOfRange(300, 500), conn.chunks.fold(ByteArray(0)) { a, c -> a + c.second })

        client.stop()
    }

    @Test
    fun startTransferCarriesTheDigestAsAsciiHex() = runTest {
        val conn = FakeFotaConnection(FotaDevice(maxPacketSize = 512))
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val mac = "0123456789abcdef0123456789abcdef"
        TandemFotaSession(client) { currentTime }.run(image(64, DigestType.MD5, mac))

        val start = payloadsWithOpcode(conn.writes, 0x38, 0x12, 0x03).single()
        assertContentEquals(
            b(0x38, 0x12, 0x03) + str("3.0.1") + b(0x00, 0x01) + str("abcd1234") + b(0x01, 32) +
                mac.toByteArray(Charsets.US_ASCII),
            start,
        )

        client.stop()
    }

    @Test
    fun notReadyMidTransferIsCancelledByDevice() = runTest {
        val conn = FakeFotaConnection(FotaDevice(maxPacketSize = 100, abortAfterChunks = 2, abortStatus = 0x02))
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val terminal = TandemFotaSession(client) { currentTime }.run(image(1000))

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.CANCELLED_BY_DEVICE, failed.reason)
        // The loop stopped shortly after the abort instead of pushing all 10 chunks.
        assertTrue(conn.chunks.size < 10, "expected an early abort, got ${conn.chunks.size} chunks")
        // FINISH/EXECUTE must not have been attempted.
        assertEquals(0, payloadsWithOpcode(conn.writes, 0x38, 0x13, 0x06).size)

        client.stop()
    }

    @Test
    fun enterModeNeedChargeAndBatteryHotAreDistinctFailures() = runTest {
        val needCharge = FakeFotaConnection(FotaDevice(enterResult = 0x06))
        val c1 = SonyProtocolClient(needCharge, backgroundScope)
        c1.start()
        runCurrent()
        val f1 = assertIs<FotaPhase.Failed>(TandemFotaSession(c1) { currentTime }.run(image(10)))
        assertEquals(FotaFailure.NEED_CHARGE, f1.reason)
        c1.stop()

        val hot = FakeFotaConnection(FotaDevice(enterResult = 0x07))
        val c2 = SonyProtocolClient(hot, backgroundScope)
        c2.start()
        runCurrent()
        val f2 = assertIs<FotaPhase.Failed>(TandemFotaSession(c2) { currentTime }.run(image(10)))
        assertEquals(FotaFailure.BATTERY_HOT, f2.reason)
        c2.stop()
    }

    @Test
    fun enterModeOtherErrorIsDeviceRefused() = runTest {
        val conn = FakeFotaConnection(FotaDevice(enterResult = 0x02)) // ILLEGAL_STATE
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val failed = assertIs<FotaPhase.Failed>(TandemFotaSession(client) { currentTime }.run(image(10)))
        assertEquals(FotaFailure.DEVICE_REFUSED, failed.reason)
        assertEquals(0, conn.chunks.size)

        client.stop()
    }

    @Test
    fun noNeedOfDataTransferSkipsTheChunkLoop() = runTest {
        val conn = FakeFotaConnection(FotaDevice(startResult = 0x04, maxPacketSize = 0))
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        assertEquals(FotaPhase.Completed, TandemFotaSession(client) { currentTime }.run(image(500)))
        assertEquals(0, conn.chunks.size)
        assertEquals(1, payloadsWithOpcode(conn.writes, 0x38, 0x13, 0x06).size)

        client.stop()
    }

    @Test
    fun executeTransferIncompleteIsDeviceRefused() = runTest {
        val conn = FakeFotaConnection(FotaDevice(maxPacketSize = 256, executeResult = 0x05))
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val failed = assertIs<FotaPhase.Failed>(TandemFotaSession(client) { currentTime }.run(image(300)))
        assertEquals(FotaFailure.DEVICE_REFUSED, failed.reason)

        client.stop()
    }

    @Test
    fun installTimesOutWhenNoCompletionArrives() = runTest {
        val conn = FakeFotaConnection(
            FotaDevice(maxPacketSize = 256, requiredTimeSec = 50, completeOnExecute = false),
        )
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val session = TandemFotaSession(client) { currentTime }
        val run = async { session.run(image(300)) }
        runCurrent()
        assertIs<FotaPhase.Installing>(session.phase.value)

        // Fake progress climbs while waiting, capped at 95 %.
        advanceTimeBy(30_000)
        val mid = assertIs<FotaPhase.Installing>(session.phase.value)
        assertTrue(mid.percent in 55..95, "unexpected install percent ${mid.percent}")
        assertEquals(50, mid.requiredTimeSec)

        // Deadline is 2 x requiredTime = 100 s.
        advanceTimeBy(75_000)
        val failed = assertIs<FotaPhase.Failed>(run.await())
        assertEquals(FotaFailure.TIMEOUT, failed.reason)
        assertEquals("install not confirmed", failed.detail)

        client.stop()
    }

    @Test
    fun linkDropDuringInstallIsToleratedAndCompletionStillLands() = runTest {
        val conn = FakeFotaConnection(
            FotaDevice(maxPacketSize = 256, requiredTimeSec = 40, completeOnExecute = false),
        )
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val session = TandemFotaSession(client) { currentTime }
        val run = async { session.run(image(300)) }
        runCurrent()
        assertIs<FotaPhase.Installing>(session.phase.value)

        // The device reboots: writes now fail, but the session must keep waiting.
        conn.dropLink()
        advanceTimeBy(20_000)
        assertIs<FotaPhase.Installing>(session.phase.value)

        // Completion arrives on the re-established link, inside the deadline.
        conn.deliverCompleted()
        runCurrent()
        assertEquals(FotaPhase.Completed, run.await())
    }

    @Test
    fun cancelDuringTransferSendsCancelThenExit() = runTest {
        // 1000 chunks at 1 virtual ms each, so the transfer is still running
        // when cancel() lands.
        val conn = FakeFotaConnection(FotaDevice(maxPacketSize = 100, chunkDelayMs = 1))
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val session = TandemFotaSession(client) { currentTime }
        val run = async { session.run(image(100_000)) }
        advanceTimeBy(20)
        assertIs<FotaPhase.Transferring>(session.phase.value)

        session.cancel()

        assertEquals(FotaPhase.Cancelled, run.await())
        assertEquals(1, payloadsWithOpcode(conn.writes, 0x38, 0x11, 0x05).size) // CANCEL
        assertEquals(1, payloadsWithOpcode(conn.writes, 0x38, 0x11, 0x02).size) // EXIT
        assertTrue(conn.chunks.size < 1000, "transfer should have stopped early")

        client.stop()
    }

    @Test
    fun runIsSingleUse() = runTest {
        val conn = FakeFotaConnection(FotaDevice(maxPacketSize = 512))
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val session = TandemFotaSession(client) { currentTime }
        session.run(image(64))
        assertFailsWith<IllegalStateException> { session.run(image(64)) }

        client.stop()
    }

    @Test
    fun emptyImageFailsWithoutTouchingTheDevice() = runTest {
        val conn = FakeFotaConnection(FotaDevice())
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val session = TandemFotaSession(client) { currentTime }
        val failed = assertIs<FotaPhase.Failed>(
            session.run(FirmwareImage(ByteArray(0), "1.0", "f", DigestType.NONE, "")),
        )
        assertEquals(FotaFailure.OTHER, failed.reason)
        assertEquals(0, payloadsWithOpcode(conn.writes, 0x38, 0x11, 0x01).size)

        client.stop()
    }
}
