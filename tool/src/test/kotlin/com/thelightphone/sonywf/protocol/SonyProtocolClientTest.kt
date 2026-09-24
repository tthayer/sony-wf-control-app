package com.thelightphone.sonywf.protocol

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import com.thelightphone.sonywf.update.FirmwareUpdateMethod
import com.thelightphone.sonywf.update.UpdateCapability
import com.thelightphone.sonywf.update.UpdateParams
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    val firmwarePayload: ByteArray? = null, //      0x05 0x02 reply
    val modelNamePayload: ByteArray? = null, //     0x05 0x01 reply
    val supportFunctionPayload: ByteArray? = null, // 0x07 reply
    val capabilityPayload: ByteArray? = null, //    0x31 reply
    val paramPayload: ByteArray? = null, //         0x37 reply
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
            // Device info GET (0x04): sub 0x02 = version, sub 0x01 = model name.
            op == SonyCommands.FIRMWARE_GET -> {
                val sub = if (p.size >= 2) p[1].toInt() and 0xFF else -1
                when (sub) {
                    SonyCommands.FIRMWARE_SUB -> device.firmwarePayload?.let { sendCommand1(it) }
                    SonyCommands.DEVICE_INFO_MODEL_SUB -> device.modelNamePayload?.let { sendCommand1(it) }
                    else -> Unit
                }
            }
            // Support-function GET (0x06).
            op == SonyCommands.SUPPORT_FUNCTION_GET -> {
                device.supportFunctionPayload?.let { sendCommand1(it) }
            }
            // UPDT_GET_CAPABILITY (0x30) / UPDT_GET_PARAM (0x36).
            op == 0x30 -> device.capabilityPayload?.let { sendCommand1(it) }
            op == 0x36 -> device.paramPayload?.let { sendCommand1(it) }
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

/**
 * Answers the V2 Init handshake while [autoAck] is on, then hands Ack timing to
 * the test so out-of-order / duplicate Acks can be injected.
 */
private class ManualAckConnection : LightSerialConnection {
    val writes = mutableListOf<ByteArray>()

    /** While false, writes are recorded but nothing is Acked or answered. */
    var autoAck: Boolean = true

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    override var isConnected: Boolean = true
        private set

    private val decoder = SonyFrameDecoder()

    override suspend fun write(bytes: ByteArray) {
        writes.add(bytes.copyOf())
        if (!autoAck) return
        for (msg in decoder.feed(bytes)) {
            if (msg.type == SonyFrame.TYPE_ACK) continue // our own auto-Ack
            ack((1 - msg.seq) and 0xFF)
            val p = msg.payload
            if (p.size >= 2 && p[0].toInt() == 0x00 && p[1].toInt() == 0x00) {
                channel.trySend(
                    SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 1, ByteArray(8).also { it[0] = 0x01 }),
                )
            }
        }
    }

    fun ack(seq: Int) {
        channel.trySend(SonyFrame.encode(SonyFrame.TYPE_ACK, seq, ByteArray(0)))
    }

    /** Seq of the last frame the client wrote. */
    fun lastSeq(): Int = decodeWrites(writes).last().seq

    override fun close() {
        isConnected = false
        channel.close()
    }
}

/**
 * Answers the V2 Init handshake and ACKs everything, except that the next
 * [dropNextAcks] commands get no ACK — a flaky link, so the resend path of
 * [SonyProtocolClient.sendReliable] can be observed.
 */
private class FlakyAckConnection : LightSerialConnection {
    val writes = mutableListOf<ByteArray>()

    /** Number of upcoming commands whose ACK is withheld. */
    var dropNextAcks: Int = 0

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    override var isConnected: Boolean = true
        private set

    private val decoder = SonyFrameDecoder()

    override suspend fun write(bytes: ByteArray) {
        writes.add(bytes.copyOf())
        for (msg in decoder.feed(bytes)) {
            if (msg.type == SonyFrame.TYPE_ACK) continue // our own auto-Ack
            if (dropNextAcks > 0) {
                dropNextAcks--
                continue
            }
            channel.trySend(SonyFrame.encode(SonyFrame.TYPE_ACK, (1 - msg.seq) and 0xFF, ByteArray(0)))
            val p = msg.payload
            if (p.size >= 2 && p[0].toInt() == 0x00 && p[1].toInt() == 0x00) {
                channel.trySend(
                    SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 1, ByteArray(8).also { it[0] = 0x01 }),
                )
            }
        }
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

    // (b2) WF-1000XM6: ANC on sub 0x19 (9-byte, noise adaptive); reads the real mode.
    @Test
    fun v2AdaptiveAncReadsCurrentModeAndEchoesAdaptiveOnSet() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 8, // V2
            batteryReplies = mapOf(
                SonyCommands.V2_BATTERY_TYPE_DUAL to b(0x23, 0x09, 97, 0x00, 99, 0x00),
            ),
            ancSupportedSub = SonyCommands.V2_ANC_SUB_ADAPTIVE, // 0x19
            // Live XM6 reply: NC on, noise cancelling, level 20, adaptive ON, sensitivity HIGH.
            ancReplyPayload = b(0x67, 0x19, 0x01, 0x01, 0x00, 0x00, 0x14, 0x00, 0x01),
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)

        client.start()
        advanceUntilIdle()

        assertTrue(client.ancSupported.value)
        assertEquals(AncMode.ANC, client.ancMode.value)
        assertEquals(20, client.ambientLevel.value)

        client.setAnc(AncMode.AMBIENT, level = 20, voicePassthrough = false)
        advanceUntilIdle()
        assertContentEquals(b(0x68, 0x19, 0x01, 0x01, 0x01, 0x00, 0x14, 0x00, 0x01), ancSetPayload(conn.writes))

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
        // EARBUDS device: it reports a DUAL battery during discovery, so the
        // case gate (dualSeen) opens and the case notify below is surfaced.
        val device = EmulatedDevice(
            initReplyLen = 8,
            batteryReplies = mapOf(SonyCommands.V2_BATTERY_TYPE_DUAL to b(0x23, 0x09, 55, 0x00, 60, 0x00)),
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
        // The inbound collector and the auto-Ack writes run in `backgroundScope`.
        // With no foreground coroutine pending, `advanceUntilIdle()` returns without
        // running background-only work, so the delivered notifies would never be
        // drained; `runCurrent()` runs the background tasks due at this instant.
        runCurrent()

        assertEquals(30, client.battery.value.case)
        assertEquals(AncMode.AMBIENT, client.ancMode.value)
        assertEquals(8, client.ambientLevel.value)
        // Both notifies were auto-Acked (two extra writes).
        assertEquals(writesBefore + 2, conn.writes.size)

        client.stop()
    }

    // Over-ear device (single battery, NO dual): a spurious CASE reply must be
    // suppressed because the device never reported a per-bud (dual) battery.
    @Test
    fun overEarCaseReplyIsSuppressedWithoutDual() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 8, // V2
            batteryReplies = mapOf(
                SonyCommands.V2_BATTERY_TYPE_SINGLE to b(0x23, 0x00, 64, 0x00),
                // Spurious case echo from an over-ear device that has no case.
                SonyCommands.V2_BATTERY_TYPE_CASE to b(0x23, 0x0a, 30, 0x00),
            ),
            ancSupportedSub = SonyCommands.V2_ANC_SUB_STANDARD,
            ancReplyPayload = b(0x67, 0x15, 0x01, 0x01, 0x00, 0x00, 0x00),
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)

        client.start()
        advanceUntilIdle()

        assertEquals(64, client.battery.value.single)
        assertNull(client.battery.value.case) // no dual reported -> case gated off
        assertNull(client.battery.value.left)
        assertNull(client.battery.value.right)

        client.stop()
    }

    // Earbuds device (dual + case): once dual is reported the case surfaces.
    @Test
    fun earbudsCaseSurfacesWithDual() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 8, // V2
            batteryReplies = mapOf(
                SonyCommands.V2_BATTERY_TYPE_DUAL to b(0x23, 0x09, 70, 0x00, 80, 0x00),
                SonyCommands.V2_BATTERY_TYPE_CASE to b(0x23, 0x0a, 50, 0x01),
            ),
            ancSupportedSub = SonyCommands.V2_ANC_SUB_STANDARD,
            ancReplyPayload = b(0x67, 0x15, 0x01, 0x01, 0x00, 0x00, 0x00),
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)

        client.start()
        advanceUntilIdle()

        assertEquals(70, client.battery.value.left)
        assertEquals(80, client.battery.value.right)
        assertEquals(50, client.battery.value.case)
        assertNull(client.battery.value.single)

        client.stop()
    }

    // ---- Firmware-update discovery (V2 only) -------------------------------

    @Test
    fun v2StartDiscoversUpdateSupport() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 8, // V2
            batteryReplies = mapOf(SonyCommands.V2_BATTERY_TYPE_SINGLE to b(0x23, 0x00, 64, 0x00)),
            ancSupportedSub = SonyCommands.V2_ANC_SUB_STANDARD,
            ancReplyPayload = b(0x67, 0x15, 0x01, 0x01, 0x00, 0x00, 0x00),
            firmwarePayload = b(0x05, 0x02, 0x05) + "3.0.1".toByteArray(Charsets.US_ASCII),
            modelNamePayload = b(0x05, 0x01, 0x0a) + "WF-1000XM5".toByteArray(Charsets.US_ASCII),
            supportFunctionPayload = b(0x07, 0x00, 0x02, 0x30, 0x01, 0x11, 0x00),
            capabilityPayload = b(0x31, 0x10, 0x04, 0x01, 0x00, 0x01, 0x00),
            paramPayload = b(0x37, 0x10, 0x02, 0x48, 0x50, 0x03, 0x53, 0x56, 0x43, 0x02, 0x55, 0x53) +
                b(0x02, 0x45, 0x4e, 0x03, 0x53, 0x4e, 0x31, 30, 20, 0x03, 0x75, 0x69, 0x64),
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)

        client.start()
        advanceUntilIdle()

        // The four discovery payloads went out (spec §7 steps 2-4).
        val payloads = decodeWrites(conn.writes).map { it.payload }
        fun sent(vararg v: Int) = payloads.any { it.contentEquals(b(*v)) }
        assertTrue(sent(0x04, 0x01), "model-name GET 04 01")
        assertTrue(sent(0x06, 0x00), "support-function GET 06 00")
        assertTrue(sent(0x30, 0x10), "UPDT_GET_CAPABILITY 30 10")
        assertTrue(sent(0x36, 0x10), "UPDT_GET_PARAM 36 10")

        assertEquals("3.0.1", client.firmwareVersion.value)
        assertEquals("WF-1000XM5", client.modelName.value)
        assertEquals(setOf(0x30, 0x11), client.supportFunctions.value)
        assertEquals(FirmwareUpdateMethod.TANDEM, client.updateMethod.value)
        assertEquals(
            // EnableDisable ENABLE is 0x00: 01 00 01 00 = resumable off, single,
            // background off, AC check on.
            UpdateCapability(resumable = false, tws = false, backgroundTransfer = false, acCheck = true),
            client.updateCapability.value,
        )
        assertEquals(
            UpdateParams("HP", "SVC", "US", "EN", "SN1", 30, 20, "uid"),
            client.updateParams.value,
        )

        client.stop()
    }

    @Test
    fun v2StartSurvivesMissingUpdateReplies() = runTest {
        // Device answers nothing beyond the basics: the update flows stay null
        // and start() still succeeds.
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

        assertTrue(client.connected.value)
        assertNull(client.modelName.value)
        assertNull(client.supportFunctions.value)
        assertNull(client.updateMethod.value)
        assertNull(client.updateCapability.value)
        assertNull(client.updateParams.value)

        client.stop()
    }

    @Test
    fun v1StartReportsNoUpdateSupport() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 4, // V1
            batteryReplies = mapOf(SonyCommands.V1_BATTERY_TYPE_SINGLE to b(0x11, 0x00, 64, 0x00)),
            ancSupportedSub = SonyCommands.V1_ANC_SUB,
            ancReplyPayload = b(0x67, 0x02, 0x11, 0x00, 0x00, 0x01, 0x00, 0x0f),
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)

        client.start()
        advanceUntilIdle()

        assertEquals(FirmwareUpdateMethod.NONE, client.updateMethod.value)
        assertNull(client.supportFunctions.value)
        // No V2-only discovery frames on a V1 device.
        val payloads = decodeWrites(conn.writes).map { it.payload }
        assertTrue(payloads.none { it.contentEquals(b(0x06, 0x00)) })
        assertTrue(payloads.none { it.contentEquals(b(0x30, 0x10)) })

        client.stop()
    }

    // ---- notifications SharedFlow -----------------------------------------

    @Test
    fun notificationsEmitEveryInboundCommandMessage() = runTest {
        val device = EmulatedDevice(
            initReplyLen = 8,
            batteryReplies = emptyMap(),
            ancSupportedSub = null,
            ancReplyPayload = null,
        )
        val conn = FakeSonyConnection(device)
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        advanceUntilIdle()

        val seen = mutableListOf<SonyMessage>()
        val job = backgroundScope.launch { client.notifications.collect { seen.add(it) } }
        runCurrent()

        // A UPDT status notify is opaque to SonyResponses but must still be relayed.
        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 1, b(0x35, 0x10, 0x01)))
        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, b(0x39, 0x11, 0x01, 0x00)))
        runCurrent()

        assertEquals(2, seen.size)
        assertContentEquals(b(0x35, 0x10, 0x01), seen[0].payload)
        assertContentEquals(b(0x39, 0x11, 0x01, 0x00), seen[1].payload)

        job.cancel()
        client.stop()
    }

    // ---- sendReliable ------------------------------------------------------

    @Test
    fun sendReliableResendsTheIdenticalFrameAndFailsAfterBudget() = runTest {
        val conn = SilentConnection()
        val client = SonyProtocolClient(conn, backgroundScope)

        val ok = client.sendReliable(SonyFrame.TYPE_COMMAND1, b(0x38, 0x11, 0x01), 2000L, maxResends = 3)
        assertFalse(ok)

        // 1 initial write + 3 resends, all byte-identical (same seq, per spec §1.3).
        assertEquals(4, conn.writes.size)
        val expected = SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, b(0x38, 0x11, 0x01))
        assertTrue(conn.writes.all { it.contentEquals(expected) })

        client.stop()
    }

    @Test
    fun sendReliableSucceedsOnAResendAndKeepsTheSameSeq() = runTest {
        val conn = FlakyAckConnection()
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start() // starts the inbound collector that consumes ACKs
        advanceUntilIdle()

        conn.writes.clear()
        conn.dropNextAcks = 2
        val ok = client.sendReliable(SonyFrame.TYPE_LARGE_DATA_MDR, b(0x3E, 0x12), 5000L, maxResends = 2)
        assertTrue(ok)

        // 1 initial write + 2 resends, all byte-identical: same seq on every try.
        assertEquals(3, conn.writes.size)
        assertTrue(conn.writes.all { it.contentEquals(conn.writes[0]) })

        client.stop()
    }

    @Test
    fun staleDuplicateAckDoesNotCompleteTheNextFrame() = runTest {
        val conn = ManualAckConnection()
        val client = SonyProtocolClient(conn, backgroundScope)
        client.start()
        advanceUntilIdle()

        conn.autoAck = false
        conn.writes.clear()

        // Frame A: the first Ack is withheld, so the client resends the same
        // frame; the device then Acks the resend (and, later, duplicates it).
        val sendA = async { client.sendReliable(SonyFrame.TYPE_COMMAND1, b(0x38, 0x11, 0x01), 2000L, maxResends = 1) }
        runCurrent()
        val seqA = conn.lastSeq()
        advanceTimeBy(2100) // first attempt times out -> resend
        assertEquals(2, conn.writes.size)
        conn.ack((1 - seqA) and 0xFF)
        runCurrent()
        assertTrue(sendA.await())

        // Frame B uses the toggled seq and expects the opposite Ack seq.
        conn.writes.clear()
        val sendB = async { client.sendReliable(SonyFrame.TYPE_COMMAND1, b(0x38, 0x11, 0x04), 2000L, maxResends = 0) }
        runCurrent()
        val seqB = conn.lastSeq()
        assertEquals((1 - seqA) and 0xFF, seqB)

        // The device's duplicate Ack for frame A must be ignored.
        conn.ack((1 - seqA) and 0xFF)
        runCurrent()
        assertTrue(sendB.isActive, "a stale Ack must not complete the next frame")

        conn.ack((1 - seqB) and 0xFF)
        runCurrent()
        assertTrue(sendB.await())

        // Seq stayed consistent: frame C is back on frame A's seq.
        conn.writes.clear()
        val sendC = async { client.sendReliable(SonyFrame.TYPE_COMMAND1, b(0x38, 0x11, 0x02), 2000L, maxResends = 0) }
        runCurrent()
        assertEquals(seqA, conn.lastSeq())
        sendC.cancel()

        client.stop()
    }

    @Test
    fun sendReliableWithNoResendsWritesOnce() = runTest {
        val conn = SilentConnection()
        val client = SonyProtocolClient(conn, backgroundScope)

        assertFalse(client.sendReliable(SonyFrame.TYPE_COMMAND1, b(0x66, 0x15), 2000L, maxResends = 0))
        assertEquals(1, conn.writes.size)

        client.stop()
    }
}
