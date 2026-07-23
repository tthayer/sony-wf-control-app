package com.thelightphone.sonywf.protocol

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte helper that keeps values > 0x7F legal. */
private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

/** Decode every client write into the messages the device would see. */
private fun decodeWrites(writes: List<ByteArray>): List<SonyMessage> =
    writes.flatMap { SonyFrameDecoder().feed(it) }

/** Find the ANC SET (opcode 0x68) payload the client emitted, or null. */
private fun ancSetPayload(writes: List<ByteArray>): ByteArray? =
    decodeWrites(writes)
        .firstOrNull { it.type == SonyFrame.TYPE_COMMAND1 && it.payload.isNotEmpty() && (it.payload[0].toInt() and 0xFF) == 0x68 }
        ?.payload

/**
 * Immutable description of a fake Sony device. The emulator ACKs every command
 * (with seq = 1 - clientSeq, as a real device does) and replies to the ones it
 * "supports".
 */
private class EmulatedDevice(
    val initReplyLen: Int, //                       4 -> V1, 8 -> V2
    val batteryReplies: Map<Int, ByteArray>, //     type byte -> full battery reply payload
    val ancSupportedSub: Int?, //                   sub-byte the device answers ANC GET on
    val ancReplyPayload: ByteArray?, //             0x67 payload sent for the supported sub
    val firmwarePayload: ByteArray? = null,
)

/**
 * A [LightSerialConnection] that emulates an [EmulatedDevice]: it decodes each
 * client write and pushes the corresponding ACK / reply frames onto `incoming`.
 * Backed by an unbounded channel so delivery is deterministic under the test
 * scheduler.
 */
private class FakeSonyConnection(private val device: EmulatedDevice) : LightSerialConnection {
    val writes = mutableListOf<ByteArray>()
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    override var isConnected: Boolean = true
        private set

    private val decoder = SonyFrameDecoder()
    private var deviceSeq = 1

    override suspend fun write(bytes: ByteArray) {
        writes.add(bytes.copyOf())
        for (msg in decoder.feed(bytes)) respond(msg)
    }

    override fun close() {
        isConnected = false
        channel.close()
    }

    /** Push a raw inbound frame directly (used to emulate unsolicited notifies). */
    fun deliver(bytes: ByteArray) {
        channel.trySend(bytes)
    }

    private fun send(type: Int, seq: Int, payload: ByteArray) {
        channel.trySend(SonyFrame.encode(type, seq, payload))
    }

    /** Device Command1 with a toggling sequence number; the client auto-Acks it. */
    private fun sendCommand1(payload: ByteArray) {
        send(SonyFrame.TYPE_COMMAND1, deviceSeq, payload)
        deviceSeq = 1 - deviceSeq
    }

    private fun respond(msg: SonyMessage) {
        // Ignore the client's own ACKs; only react to commands.
        if (msg.type != SonyFrame.TYPE_COMMAND1 && msg.type != SonyFrame.TYPE_COMMAND2) return
        val p = msg.payload
        // Every command is ACKed with seq = (1 - clientSeq).
        send(SonyFrame.TYPE_ACK, (1 - msg.seq) and 0xFF, ByteArray(0))

        val op = if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
        when {
            // INIT: [0x00, 0x00] -> device sends its Init reply (payload[0]==0x01).
            op == 0x00 && p.size >= 2 && (p[1].toInt() and 0xFF) == 0x00 -> {
                sendCommand1(ByteArray(device.initReplyLen).also { it[0] = 0x01 })
            }
            // Battery GET (V1 0x10 / V2 0x22).
            op == SonyCommands.V1_BATTERY_GET || op == SonyCommands.V2_BATTERY_GET -> {
                val type = if (p.size >= 2) p[1].toInt() and 0xFF else -1
                device.batteryReplies[type]?.let { sendCommand1(it) }
            }
            // ANC GET (0x66): reply only for the sub-byte we support.
            op == SonyCommands.ANC_GET -> {
                val sub = if (p.size >= 2) p[1].toInt() and 0xFF else -1
                if (sub == device.ancSupportedSub && device.ancReplyPayload != null) {
                    sendCommand1(device.ancReplyPayload)
                }
            }
            // Firmware GET (0x04).
            op == SonyCommands.FIRMWARE_GET -> {
                device.firmwarePayload?.let { sendCommand1(it) }
            }
            // ANC SET (0x68) and anything else: ACK only, no reply.
            else -> Unit
        }
    }
}

/** A connection that records writes but never answers — models a non-Sony device. */
private class SilentConnection : LightSerialConnection {
    val writes = mutableListOf<ByteArray>()
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    override var isConnected: Boolean = true
        private set

    override suspend fun write(bytes: ByteArray) {
        writes.add(bytes.copyOf())
    }

    override fun close() {
        isConnected = false
        channel.close()
    }
}

class SonyProtocolClientTest {

    // (a) v2 WH-1000XM5-style: single battery on 0x00, ANC only on sub 0x15 (7-byte).
    @Test
    fun v2SingleBatteryDeviceWithStandardAnc() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 8, // V2
            batteryReplies = mapOf(SonyCommands.V2_BATTERY_TYPE_SINGLE to b(0x23, 0x00, 64, 0x00)),
            ancSupportedSub = SonyCommands.V2_ANC_SUB_STANDARD, // 0x15
            ancReplyPayload = b(0x67, 0x15, 0x01, 0x01, 0x00, 0x00, 0x00), // 7-byte, ANC mode
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)

        client.start()
        advanceUntilIdle()

        assertEquals(SonyDialect.V2, client.dialect.value)
        assertTrue(client.connected.value)
        assertTrue(client.ancSupported.value)
        assertEquals(64, client.battery.value.single)
        assertNull(client.battery.value.left)
        assertNull(client.battery.value.right)
        assertNull(client.battery.value.case)

        // setAnc must use the discovered V2 standard (7-byte, sub 0x15) layout.
        client.setAnc(AncMode.AMBIENT, level = 15, voicePassthrough = false)
        advanceUntilIdle()
        assertContentEquals(b(0x68, 0x15, 0x01, 0x01, 0x01, 0x00, 0x0f), ancSetPayload(conn.writes))
        assertEquals(AncMode.AMBIENT, client.ancMode.value)

        client.stop()
    }

    // (b) v2 WF earbuds: dual on 0x09 + case on 0x0a, ANC on sub 0x17 (8-byte wind).
    @Test
    fun v2DualEarbudsWithWindAnc() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 8, // V2
            batteryReplies = mapOf(
                SonyCommands.V2_BATTERY_TYPE_DUAL to b(0x23, 0x09, 70, 0x00, 80, 0x00),
                SonyCommands.V2_BATTERY_TYPE_CASE to b(0x23, 0x0a, 50, 0x01),
            ),
            ancSupportedSub = SonyCommands.V2_ANC_SUB_WIND, // 0x17
            ancReplyPayload = b(0x67, 0x17, 0x01, 0x01, 0x01, 0x02, 0x00, 0x0c), // 8-byte, ambient
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)

        client.start()
        advanceUntilIdle()

        assertEquals(SonyDialect.V2, client.dialect.value)
        assertTrue(client.ancSupported.value)
        assertEquals(70, client.battery.value.left)
        assertEquals(80, client.battery.value.right)
        assertEquals(50, client.battery.value.case)
        assertNull(client.battery.value.single)

        // setAnc must use the discovered V2 wind (8-byte, sub 0x17) layout.
        client.setAnc(AncMode.AMBIENT, level = 10, voicePassthrough = false)
        advanceUntilIdle()
        assertContentEquals(b(0x68, 0x17, 0x01, 0x01, 0x01, 0x02, 0x00, 0x0a), ancSetPayload(conn.writes))

        client.stop()
    }

    // (c) v1 device: INIT reply len 4, battery opcode 0x10, ANC sub 0x02 (non-wind).
    @Test
    fun v1DeviceUsesV1Layout() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 4, // V1
            batteryReplies = mapOf(
                SonyCommands.V1_BATTERY_TYPE_DUAL to b(0x11, 0x01, 60, 0x00, 65, 0x00),
                SonyCommands.V1_BATTERY_TYPE_CASE to b(0x11, 0x02, 45, 0x00),
            ),
            ancSupportedSub = SonyCommands.V1_ANC_SUB, // 0x02
            ancReplyPayload = b(0x67, 0x02, 0x11, 0x00, 0x00, 0x01, 0x00, 0x0f), // non-wind AMBIENT
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)

        client.start()
        advanceUntilIdle()

        assertEquals(SonyDialect.V1, client.dialect.value)
        assertTrue(client.ancSupported.value)
        assertEquals(60, client.battery.value.left)
        assertEquals(65, client.battery.value.right)
        assertEquals(45, client.battery.value.case)

        // Battery GET must have used the V1 opcode 0x10 (not the V2 0x22).
        val batteryGets = decodeWrites(conn.writes)
            .filter { it.type == SonyFrame.TYPE_COMMAND1 && it.payload.isNotEmpty() && (it.payload[0].toInt() and 0xFF) == 0x10 }
        assertTrue(batteryGets.isNotEmpty(), "expected V1 (0x10) battery GETs")

        // setAnc must use the V1 (8-byte, sub 0x02) layout with the inverted byte-4:
        // ANC -> modeByte 1.
        client.setAnc(AncMode.ANC, level = 0, voicePassthrough = false)
        advanceUntilIdle()
        assertContentEquals(b(0x68, 0x02, 0x11, 0x00, 0x01, 0x01, 0x00, 0x00), ancSetPayload(conn.writes))

        client.stop()
    }

    // (d) start() throws when no Init reply ever arrives (non-Sony device).
    @Test
    fun startThrowsWhenNoInitReply() = runTest {
        val conn = SilentConnection()
        val client = SonyProtocolClient(conn, backgroundScope)

        assertFailsWith<IllegalStateException> { client.start() }

        // Init should have been retried up to 3 times, all identical.
        assertEquals(3, conn.writes.size)
        val expectedInit = SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, SonyCommands.INIT_PAYLOAD)
        assertTrue(conn.writes.all { it.contentEquals(expectedInit) })

        client.stop()
    }

    // Unsolicited notifies keep state live and are auto-Acked.
    @Test
    fun unsolicitedNotifiesUpdateStateAndAutoAck() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 8,
            batteryReplies = mapOf(SonyCommands.V2_BATTERY_TYPE_SINGLE to b(0x23, 0x00, 64, 0x00)),
            ancSupportedSub = SonyCommands.V2_ANC_SUB_STANDARD,
            ancReplyPayload = b(0x67, 0x15, 0x01, 0x01, 0x00, 0x00, 0x00),
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        advanceUntilIdle()
        val writesBefore = conn.writes.size

        // Push an unsolicited battery notify (0x25) case=30 and an ANC notify (0x69) AMBIENT.
        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 1, b(0x25, 0x0a, 30, 0x00)))
        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, b(0x69, 0x15, 0x01, 0x01, 0x01, 0x00, 0x08)))
        advanceUntilIdle()

        assertEquals(30, client.battery.value.case)
        assertEquals(AncMode.AMBIENT, client.ancMode.value)
        assertEquals(8, client.ambientLevel.value)
        // Both notifies were auto-Acked (two extra writes).
        assertEquals(writesBefore + 2, conn.writes.size)

        client.stop()
    }
}
