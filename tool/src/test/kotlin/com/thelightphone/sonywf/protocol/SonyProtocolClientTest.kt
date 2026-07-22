package com.thelightphone.sonywf.protocol

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A test double for [LightSerialConnection]. `write` records the exact bytes
 * handed to the transport; [deliver] pushes an inbound chunk to the client's
 * `incoming` collector. Backed by an unbounded [Channel] so delivery is
 * independent of subscription timing (deterministic under the test scheduler).
 */
private class FakeConnection : LightSerialConnection {
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

    /** Push an inbound chunk (arbitrary bytes) toward the client. */
    fun deliver(bytes: ByteArray) {
        check(channel.trySend(bytes).isSuccess)
    }

    fun writesHex(i: Int): String = writes[i].joinToString(" ") { "%02x".format(it) }
}

class SonyProtocolClientTest {

    @Test
    fun handshakeSendsInitAndSetAncEmitsCorrectBytes() = runTest {
        val conn = FakeConnection()
        val client = SonyProtocolClient(conn, backgroundScope)

        // start(): launches the inbound collector and writes Init.
        val startJob = launch { client.start() }
        runCurrent()
        assertEquals(1, conn.writes.size, "Init should be the first write")
        assertEquals("3e 0c 00 00 00 00 02 00 00 0e 3c", conn.writesHex(0))

        // Device Acks the Init with seq = (1 - 0) = 1; client adopts seq = 1.
        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_ACK, 1, ByteArray(0)))
        runCurrent()
        startJob.join()

        // setAnc must be sent with the current seq (1) and the Ambient payload.
        val setJob = launch { client.setAnc(AncMode.AMBIENT, level = 15, voicePassthrough = false) }
        runCurrent()
        assertEquals(2, conn.writes.size, "setAnc should produce exactly one more write")
        val expected = SonyFrame.encode(
            SonyFrame.TYPE_COMMAND1,
            1,
            SonyCommands.ancSet(AncMode.AMBIENT, 15, false),
        )
        assertContentEquals(expected, conn.writes[1])

        // Device Acks the command (seq = 1 - 1 = 0); setAnc returns.
        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_ACK, 0, ByteArray(0)))
        runCurrent()
        setJob.join()

        // Optimistic state reflects the request.
        assertEquals(AncMode.AMBIENT, client.ancMode.value)
        assertEquals(15, client.ambientLevel.value)
        assertEquals(false, client.voicePassthrough.value)

        client.stop()
    }

    @Test
    fun strictlySequentialSendsWaitForEachAck() = runTest {
        val conn = FakeConnection()
        val client = SonyProtocolClient(conn, backgroundScope)

        val startJob = launch { client.start() }
        runCurrent()
        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_ACK, 1, ByteArray(0)))
        runCurrent()
        startJob.join()

        // refreshBattery issues two commands but must wait for the first Ack
        // before sending the second.
        val job = launch { client.refreshBattery() }
        runCurrent()
        assertEquals(2, conn.writes.size, "only the first battery command may be in flight")
        assertContentEquals(
            SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 1, SonyCommands.getBattery(SonyCommands.BATTERY_TARGET_HEADPHONES)),
            conn.writes[1],
        )

        // Ack the first (seq -> 0); the second command goes out with seq 0.
        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_ACK, 0, ByteArray(0)))
        runCurrent()
        assertEquals(3, conn.writes.size)
        assertContentEquals(
            SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, SonyCommands.getBattery(SonyCommands.BATTERY_TARGET_CASE)),
            conn.writes[2],
        )

        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_ACK, 1, ByteArray(0)))
        runCurrent()
        job.join()

        client.stop()
    }

    @Test
    fun unsolicitedNotifyUpdatesStateAndAutoAcks() = runTest {
        val conn = FakeConnection()
        val client = SonyProtocolClient(conn, backgroundScope)

        val startJob = launch { client.start() }
        runCurrent()
        conn.deliver(SonyFrame.encode(SonyFrame.TYPE_ACK, 1, ByteArray(0)))
        runCurrent()
        startJob.join()
        val writesAfterHandshake = conn.writes.size

        // Unsolicited battery notify (Command1, seq 1): headphones left=70 right=80.
        conn.deliver(
            SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 1, byteArrayOf(0x25, 0x01, 70, 0x00, 80, 0x00)),
        )
        runCurrent()

        assertEquals(70, client.leftBattery.value)
        assertEquals(80, client.rightBattery.value)

        // The client must auto-Ack the notify with seq = (1 - 1) = 0.
        assertEquals(writesAfterHandshake + 1, conn.writes.size, "notify must be auto-acked")
        assertContentEquals(
            SonyFrame.encode(SonyFrame.TYPE_ACK, 0, ByteArray(0)),
            conn.writes.last(),
        )

        // A follow-up ANC notify updates ANC state too.
        conn.deliver(
            SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, byteArrayOf(0x69, 0x17, 0x01, 0x01, 0x00, 0x00, 0x00)),
        )
        runCurrent()
        assertEquals(AncMode.ANC, client.ancMode.value)

        client.stop()
    }

    @Test
    fun initRetriesUpToThreeTimesWhenNoBytesArrive() = runTest {
        val conn = FakeConnection()
        val client = SonyProtocolClient(conn, backgroundScope)

        // No inbound bytes ever arrive: start() should retry Init 3 times total.
        val startJob = launch { client.start() }
        advanceUntilIdle()
        startJob.join()

        assertEquals(3, conn.writes.size, "Init should be retried up to 3 times")
        assertTrue(conn.writes.all { it.contentEquals(SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, byteArrayOf(0x00, 0x00))) })

        client.stop()
    }
}
