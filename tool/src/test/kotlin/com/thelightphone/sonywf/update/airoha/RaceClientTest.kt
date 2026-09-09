package com.thelightphone.sonywf.update.airoha

import com.thelightphone.sdk.bluetooth.LightBluetoothException
import com.thelightphone.sdk.bluetooth.LightSerialConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

/**
 * A [LightSerialConnection] whose replies are driven by [respond]. Backed by an
 * unbounded channel so delivery is deterministic under the test scheduler.
 */
private open class FakeRaceConnection : LightSerialConnection {
    val writes = mutableListOf<ByteArray>()
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    private var open: Boolean = true
    override val isConnected: Boolean get() = open

    private val decoder = RaceDecoder()

    override suspend fun write(bytes: ByteArray) {
        writes.add(bytes.copyOf())
        for (msg in decoder.feed(bytes)) respond(msg)
    }

    override fun close() {
        open = false
        channel.close()
    }

    /** Push a raw inbound chunk (arbitrary framing / splitting). */
    fun deliver(chunk: ByteArray) {
        channel.trySend(chunk)
    }

    /** Default device: silent. */
    open fun respond(cmd: RaceMessage) = Unit
}

class RaceClientTest {

    @Test
    fun requestReturnsTheReplyThatMatchesTheRaceId() = runTest {
        val conn = object : FakeRaceConnection() {
            override fun respond(cmd: RaceMessage) {
                // A different race id first: it must NOT satisfy the request.
                deliver(RaceFrame.encode(0, RaceFrame.TYPE_RSP, 0x0CD6, b(0x00, 0x00, 0x5A)))
                deliver(RaceFrame.encode(0, RaceFrame.TYPE_RSP, cmd.raceId, b(0x00, 0x01, 0x01)))
            }
        }
        val client = RaceClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val reply = client.request(0x1C04, ByteArray(0))
        assertNotNull(reply)
        assertEquals(0x1C04, reply.raceId)
        assertEquals(RaceFrame.TYPE_RSP, reply.type)
        assertContentEquals(b(0x00, 0x01, 0x01), reply.payload)

        assertEquals(1, conn.writes.size)
        assertContentEquals(b(0x05, 0x5A, 0x02, 0x00, 0x04, 0x1C), conn.writes[0])
        client.stop()
    }

    @Test
    fun requestAcceptsANotifyReply() = runTest {
        val conn = object : FakeRaceConnection() {
            override fun respond(cmd: RaceMessage) {
                deliver(RaceFrame.encode(0, RaceFrame.TYPE_NOTIFY, cmd.raceId, b(0x00)))
            }
        }
        val client = RaceClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val reply = client.request(0x1C00, b(0x00))
        assertNotNull(reply)
        assertEquals(RaceFrame.TYPE_NOTIFY, reply.type)
        client.stop()
    }

    @Test
    fun requestIgnoresAnEchoOfItsOwnCommandType() = runTest {
        val conn = object : FakeRaceConnection() {
            override fun respond(cmd: RaceMessage) {
                // Same race id but type 0x5A: not in acceptTypes, so it is noise.
                deliver(RaceFrame.encode(0, RaceFrame.TYPE_CMD, cmd.raceId, b(0x00)))
            }
        }
        val client = RaceClient(conn, backgroundScope)
        client.start()
        runCurrent()

        assertNull(client.request(0x1C04, ByteArray(0), retries = 0))
        client.stop()
    }

    @Test
    fun requestResendsTheIdenticalFrameOnSilenceAndFailsAfterTheBudget() = runTest {
        val conn = FakeRaceConnection()
        val client = RaceClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val reply = client.request(0x0A00, b(0x02, 0x10, 0xE8, 0x03), retries = 2)
        assertNull(reply)

        // 1 initial write + 2 resends, all byte-identical.
        assertEquals(3, conn.writes.size)
        val expected = b(0x05, 0x5A, 0x06, 0x00, 0x00, 0x0A, 0x02, 0x10, 0xE8, 0x03)
        assertTrue(conn.writes.all { it.contentEquals(expected) })
        client.stop()
    }

    @Test
    fun requestSucceedsOnAResendAfterEarlySilence() = runTest {
        val conn = object : FakeRaceConnection() {
            var dropped = 0
            override fun respond(cmd: RaceMessage) {
                if (dropped < 2) {
                    dropped++
                    return
                }
                deliver(RaceFrame.encode(0, RaceFrame.TYPE_RSP, cmd.raceId, b(0x00, 0x64)))
            }
        }
        val client = RaceClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val reply = client.request(0x0CD6, b(0x00), retries = 2)
        assertNotNull(reply)
        assertEquals(3, conn.writes.size)
        client.stop()
    }

    @Test
    fun requestReturnsNullWhenTheWriteFails() = runTest {
        val conn = object : FakeRaceConnection() {
            override suspend fun write(bytes: ByteArray) {
                throw LightBluetoothException("link down")
            }
        }
        val client = RaceClient(conn, backgroundScope)
        client.start()
        runCurrent()

        assertNull(client.request(0x1C04, ByteArray(0), retries = 1))
        client.stop()
    }

    @Test
    fun messagesRelaysEveryDecodedFrameIncludingSplitChunks() = runTest {
        val conn = FakeRaceConnection()
        val client = RaceClient(conn, backgroundScope)
        client.start()
        runCurrent()

        val seen = mutableListOf<RaceMessage>()
        val job = backgroundScope.launch { client.messages.collect { seen.add(it) } }
        runCurrent()

        val frame = RaceFrame.encode(0x10, RaceFrame.TYPE_NOTIFY, 0x1C03, b(0x00, 0x07))
        conn.deliver(frame.copyOfRange(0, 3))
        conn.deliver(frame.copyOfRange(3, frame.size))
        runCurrent()

        assertEquals(1, seen.size)
        assertEquals(0x1C03, seen[0].raceId)
        assertEquals(RaceFrame.FLAG_SESSION, seen[0].flag)

        job.cancel()
        client.stop()
    }

    @Test
    fun connectedFlipsFalseWhenTheStreamCompletes() = runTest {
        val conn = FakeRaceConnection()
        val client = RaceClient(conn, backgroundScope)
        client.start()
        runCurrent()
        assertTrue(client.connected.value)

        conn.close()
        // runCurrent, not advanceUntilIdle: the collector lives in backgroundScope,
        // which advanceUntilIdle does not wait for.
        runCurrent()
        assertFalse(client.connected.value)
    }
}
