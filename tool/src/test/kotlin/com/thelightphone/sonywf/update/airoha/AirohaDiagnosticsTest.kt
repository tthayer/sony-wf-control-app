package com.thelightphone.sonywf.update.airoha

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

/**
 * A fake Airoha device answering exactly the five read-only queries, using the
 * response layouts documented in spec-airoha-fota.md §2.4 / §3.7.
 */
private class FakeAirohaDevice(private val answer: Boolean = true) : LightSerialConnection {
    val commands = mutableListOf<RaceMessage>()
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    override var isConnected: Boolean = true
        private set

    private val decoder = RaceDecoder()

    override suspend fun write(bytes: ByteArray) {
        for (cmd in decoder.feed(bytes)) {
            commands.add(cmd)
            if (!answer) continue
            replyFor(cmd)?.let { channel.trySend(RaceFrame.encode(0, RaceFrame.TYPE_RSP, cmd.raceId, it)) }
        }
    }

    override fun close() {
        isConnected = false
        channel.close()
    }

    private fun replyFor(cmd: RaceMessage): ByteArray? = when (cmd.raceId) {
        // libcommon READ NVKEY: rx[6..7] returned length, data from rx[8].
        AirohaDiagnostics.RACE_READ_NVKEY -> b(0x06, 0x00, 0x4D, 0x54, 0x32, 0x38, 0x33, 0x33)
        // status, then the 16-bit state LE at rx[7..8].
        AirohaDiagnostics.RACE_QUERY_STATE -> b(0x00, 0x01, 0x01)
        // status, role, len, ASCII version.
        AirohaDiagnostics.RACE_GET_VERSION -> b(0x00, 0x00, 0x06, 0x56, 0x31, 0x2E, 0x32, 0x2E, 0x33)
        // status, role, percent.
        AirohaDiagnostics.RACE_GET_BATTERY -> b(0x00, 0x00, 0x64)
        // status, partitionId, storageType, addr LE32, len LE32.
        AirohaDiagnostics.RACE_INQUIRY_FOTA ->
            b(0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x08, 0x00)
        else -> null
    }
}

class AirohaDiagnosticsTest {

    @Test
    fun sppUuidIsSonysAirohaFotaService() {
        assertEquals("8901dfa8-5c7e-4d8f-9f0c-c2b70683f5f0", AirohaDiagnostics.SPP_UUID)
    }

    @Test
    fun runSendsExactlyTheFiveReadOnlyQueriesInOrder() = runTest {
        val device = FakeAirohaDevice()
        val client = RaceClient(device, backgroundScope)
        client.start()
        runCurrent()

        AirohaDiagnostics.run(client)

        assertEquals(
            listOf(0x0A00, 0x1C04, 0x1C07, 0x0CD6, 0x1C00),
            device.commands.map { it.raceId },
        )
        // Every command is a plain 0x05-flagged command: no session flag is ever
        // set, because nothing here opens a FOTA session.
        assertTrue(device.commands.all { it.type == RaceFrame.TYPE_CMD && it.flag == RaceFrame.FLAG_NONE })
        assertContentEquals(b(0x02, 0x10, 0xE8, 0x03), device.commands[0].payload)
        assertContentEquals(ByteArray(0), device.commands[1].payload)
        assertContentEquals(b(0x00), device.commands[2].payload)
        assertContentEquals(b(0x00), device.commands[3].payload)
        assertContentEquals(b(0x00), device.commands[4].payload)

        client.stop()
    }

    @Test
    fun runReportsTxHexRxHexAndAParsedLinePerQuery() = runTest {
        val client = RaceClient(FakeAirohaDevice(), backgroundScope)
        client.start()
        runCurrent()

        val lines = AirohaDiagnostics.run(client)
        assertEquals(15, lines.size) // 5 queries x (tx, rx, parsed)

        assertEquals("chipname tx: 05 5a 06 00 00 0a 02 10 e8 03", lines[0])
        assertEquals("chipname rx: 05 5b 0a 00 00 0a 06 00 4d 54 32 38 33 33", lines[1])
        assertTrue(lines[2].contains("rx[6..7] len=6"), lines[2])
        assertTrue(lines[2].contains("rx[8..] name='MT2833'"), lines[2])

        assertEquals("querystate tx: 05 5a 02 00 04 1c", lines[3])
        assertEquals("querystate: status=0x00 state=0x0101", lines[5])

        assertEquals("getversion: status=0x00 version='V1.2.3'", lines[8])
        assertEquals("getbattery: status=0x00 role=0 battery=100%", lines[11])
        assertEquals(
            "inquiryfota: status=0x00 partition=0 storage=0 addr=0x00100000 len=0x00080000 (524288 bytes)",
            lines[14],
        )

        client.stop()
    }

    @Test
    fun runReportsNoReplyForASilentDeviceWithoutFailing() = runTest {
        val device = FakeAirohaDevice(answer = false)
        val client = RaceClient(device, backgroundScope)
        client.start()
        runCurrent()

        val lines = AirohaDiagnostics.run(client)
        // tx + "no reply" for each of the five queries; no parsed line.
        assertEquals(10, lines.size)
        assertTrue(lines.filter { it.contains("rx:") }.all { it.endsWith("no reply") })
        // 5 queries x (1 initial + 2 resends).
        assertEquals(15, device.commands.size)

        client.stop()
    }
}
