package com.thelightphone.sonywf.update

import com.thelightphone.sdk.bluetooth.LightSerialConnection
import com.thelightphone.sonywf.protocol.SonyFrame
import com.thelightphone.sonywf.protocol.SonyFrameDecoder
import com.thelightphone.sonywf.protocol.SonyMessage
import com.thelightphone.sonywf.protocol.SonyProtocolClient
import com.thelightphone.sonywf.update.airoha.AirohaDeviceConfig
import com.thelightphone.sonywf.update.airoha.AirohaRace
import com.thelightphone.sonywf.update.airoha.FakeAirohaFotaDevice
import com.thelightphone.sonywf.update.airoha.FlashPlan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

private fun str(s: String): ByteArray = byteArrayOf(s.length.toByte()) + s.toByteArray(Charsets.US_ASCII)

/** Bytes that never form an all-0xFF page. */
private fun payload(size: Int): ByteArray = ByteArray(size) { ((it % 251) + 1).toByte() }

/**
 * Minimal MDR device: V2 init, the discovery queries an MTK device answers,
 * and an Ack for everything. It records the `UPDT_SET_STATUS` payloads.
 */
private class FakeMdrConnection(
    private val firmwareVersion: String = "1.0.0",
    private val supportFunctions: Set<Int> = setOf(0x34),
) : LightSerialConnection {
    val setStatusPayloads = mutableListOf<ByteArray>()

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    override var isConnected: Boolean = true
        private set

    private val decoder = SonyFrameDecoder()
    private var deviceSeq = 1

    override suspend fun write(bytes: ByteArray) {
        if (!isConnected) return
        for (message in decoder.feed(bytes)) respond(message)
    }

    override fun close() {
        isConnected = false
        channel.close()
    }

    private fun send(type: Int, seq: Int, payload: ByteArray) {
        channel.trySend(SonyFrame.encode(type, seq, payload))
    }

    private fun sendCommand1(payload: ByteArray) {
        send(SonyFrame.TYPE_COMMAND1, deviceSeq, payload)
        deviceSeq = 1 - deviceSeq
    }

    private fun respond(message: SonyMessage) {
        if (message.type != SonyFrame.TYPE_COMMAND1 && message.type != SonyFrame.TYPE_COMMAND2) return
        send(SonyFrame.TYPE_ACK, (1 - message.seq) and 0xFF, ByteArray(0))

        val p = message.payload
        val op = if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
        val sub = if (p.size >= 2) p[1].toInt() and 0xFF else -1
        when {
            op == 0x00 && sub == 0x00 -> sendCommand1(ByteArray(8).also { it[0] = 0x01 })
            op == 0x22 -> sendCommand1(b(0x23, 0x09, 70, 0x00, 80, 0x00))
            op == 0x66 -> sendCommand1(b(0x67, 0x15, 0x01, 0x01, 0x00, 0x00, 0x00))
            op == 0x04 && sub == 0x02 -> sendCommand1(b(0x05, 0x02) + str(firmwareVersion))
            op == 0x04 && sub == 0x01 -> sendCommand1(b(0x05, 0x01) + str("WH-1000XM5"))
            op == 0x06 -> {
                var out = b(0x07, 0x00, supportFunctions.size)
                for (fn in supportFunctions) out += b(fn, 0x00)
                sendCommand1(out)
            }
            // MTK inquired type 0x04: three EnableDisable features, all ENABLE.
            op == 0x30 -> sendCommand1(b(0x31, 0x04, 0x03, 0x00, 0x01, 0x00))
            op == 0x36 -> sendCommand1(
                b(0x37, 0x04) + str("HP") + str("SVC") + str("US") + str("English") + str("SN1") +
                    b(30, 20) + str("uid"),
            )
            op == UpdtMessages.UPDT_SET_STATUS -> setStatusPayloads.add(p.copyOf())
        }
    }
}

class MtkUpdateControllerTest {

    @Test
    fun chipFamilyFollowsTheLibrarySubstringOrder() {
        assertEquals(AirohaChip.MT2822, MtkUpdateController.chipFamily("MT2822S"))
        assertEquals(AirohaChip.MT2822, MtkUpdateController.chipFamily("AB1568"))
        assertEquals(AirohaChip.MT2833, MtkUpdateController.chipFamily("MT2833"))
        assertEquals(AirohaChip.MT2833, MtkUpdateController.chipFamily("AB1585"))
        assertEquals(AirohaChip.MT2855, MtkUpdateController.chipFamily("MT2855"))
        assertEquals(AirohaChip.AB1562, MtkUpdateController.chipFamily("AB1562"))
        // The library falls back to MT2811; we refuse instead.
        assertEquals(AirohaChip.UNKNOWN, MtkUpdateController.chipFamily("MT2811"))
        assertEquals(AirohaChip.UNKNOWN, MtkUpdateController.chipFamily(null))
    }

    @Test
    fun happyPathFlashesCommitsAndConfirmsTheNewVersion() = runTest {
        val mdr = FakeMdrConnection(firmwareVersion = "1.0.0")
        val client = SonyProtocolClient(mdr, backgroundScope)
        client.start()
        runCurrent()

        assertEquals(FirmwareUpdateMethod.MTK, client.updateMethod.value)
        val capability = client.updateCapability.value
        assertEquals(
            UpdateCapability(resumable = true, tws = false, backgroundTransfer = true, acCheck = false),
            capability,
        )

        // Committing reboots the headphone: both links die.
        val airoha = FakeAirohaFotaDevice(AirohaDeviceConfig(onCommit = { mdr.close() }))
        val rebooted = FakeMdrConnection(firmwareVersion = "2.0.0")
        val freshClient = SonyProtocolClient(rebooted, backgroundScope)

        val controller = MtkUpdateController(
            client = client,
            openAiroha = { airoha.openSocket() },
            reconnect = {
                // The reboot finished: the device answers again on a new link.
                freshClient.start()
                freshClient
            },
            scope = backgroundScope,
            clock = { currentTime },
        )

        val image = FirmwareImage(
            bytes = payload(2 * FlashPlan.PAGE),
            version = "2.0.0",
            fileName = "fw.bin",
            digest = DigestType.NONE,
            macHex = "",
        )
        val terminal = controller.run(image, inquiredType = 0x04, capability = capability)

        assertEquals(FotaPhase.Completed, terminal)
        assertEquals(FotaPhase.Completed, controller.phase.value)

        // UPDT_SET_STATUS {0x34, inq, ENABLE} went out before the transfer and
        // again before the commit. ENABLE is 0x00 on the wire.
        assertEquals(2, mdr.setStatusPayloads.size)
        for (sent in mdr.setStatusPayloads) {
            assertEquals(listOf(0x34, 0x04, 0x00), sent.map { it.toInt() and 0xFF })
        }

        // The image landed and the device was committed.
        assertEquals(2, airoha.pageWrites.size)
        assertTrue(airoha.writesToUnerasedSectors.isEmpty())
        assertTrue(airoha.badCrcPages.isEmpty())
        assertTrue(AirohaRace.COMMIT in airoha.commands)
        assertTrue(airoha.cancels.isEmpty())
        advanceUntilIdle()
    }

    @Test
    fun aDifferentVersionAfterTheRebootFails() = runTest {
        val mdr = FakeMdrConnection(firmwareVersion = "1.0.0")
        val client = SonyProtocolClient(mdr, backgroundScope)
        client.start()
        runCurrent()

        val airoha = FakeAirohaFotaDevice(AirohaDeviceConfig(onCommit = { mdr.close() }))
        val rebooted = FakeMdrConnection(firmwareVersion = "1.5.0") // neither old nor expected
        val freshClient = SonyProtocolClient(rebooted, backgroundScope)

        val controller = MtkUpdateController(
            client = client,
            openAiroha = { airoha.openSocket() },
            reconnect = {
                freshClient.start()
                freshClient
            },
            scope = backgroundScope,
            clock = { currentTime },
        )

        val image = FirmwareImage(payload(256), "2.0.0", "fw.bin", DigestType.NONE, "")
        val terminal = controller.run(image, 0x04, client.updateCapability.value)

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.OTHER, failed.reason)
        assertTrue(failed.detail.contains("version mismatch"))
        advanceUntilIdle()
    }

    @Test
    fun aDeviceStillOnTheOldVersionIsPolledUntilTheDeadline() = runTest {
        val mdr = FakeMdrConnection(firmwareVersion = "1.0.0")
        val client = SonyProtocolClient(mdr, backgroundScope)
        client.start()
        runCurrent()

        val airoha = FakeAirohaFotaDevice(AirohaDeviceConfig(onCommit = { mdr.close() }))
        var redials = 0
        val controller = MtkUpdateController(
            client = client,
            openAiroha = { airoha.openSocket() },
            reconnect = {
                redials++
                // Still the old firmware: the reboot has not happened yet.
                SonyProtocolClient(FakeMdrConnection(firmwareVersion = "1.0.0"), backgroundScope).also { it.start() }
            },
            scope = backgroundScope,
            clock = { currentTime },
        )

        val image = FirmwareImage(payload(256), "2.0.0", "fw.bin", DigestType.NONE, "")
        val terminal = controller.run(image, 0x04, client.updateCapability.value)

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.TIMEOUT, failed.reason)
        assertTrue(redials > 1)
        advanceUntilIdle()
    }

    @Test
    fun anUnsupportedChipIsRefusedBeforeAnythingIsWritten() = runTest {
        val mdr = FakeMdrConnection()
        val client = SonyProtocolClient(mdr, backgroundScope)
        client.start()
        runCurrent()

        val airoha = FakeAirohaFotaDevice(AirohaDeviceConfig(chipName = "MT2855"))
        val controller = MtkUpdateController(
            client = client,
            openAiroha = { airoha.openSocket() },
            reconnect = { null },
            scope = backgroundScope,
            clock = { currentTime },
        )

        val terminal = controller.run(
            FirmwareImage(payload(256), "2.0.0", "fw.bin", DigestType.NONE, ""),
            0x04,
            client.updateCapability.value,
        )

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.OTHER, failed.reason)
        assertTrue(failed.detail.contains("unsupported chip"))
        assertEquals(listOf(AirohaRace.READ_NVKEY), airoha.commands)
        assertTrue(airoha.erases.isEmpty())
        assertTrue(airoha.pageWrites.isEmpty())
        // Update mode was entered to reach the handshake, so it is left again.
        assertEquals(
            listOf(listOf(0x34, 0x04, UpdtMessages.ENABLE), listOf(0x34, 0x04, UpdtMessages.DISABLE)),
            mdr.setStatusPayloads.map { sent -> sent.map { it.toInt() and 0xFF } },
        )
    }

    @Test
    fun aFailedTransferLeavesUpdateModeAgain() = runTest {
        val mdr = FakeMdrConnection()
        val client = SonyProtocolClient(mdr, backgroundScope)
        client.start()
        runCurrent()

        // The device refuses 0x1C08, so the transfer fails after update mode is on.
        val airoha = FakeAirohaFotaDevice(
            AirohaDeviceConfig(statusOverrides = mapOf(AirohaRace.FOTA_START to 0x05)),
        )
        val controller = MtkUpdateController(
            client = client,
            openAiroha = { airoha.openSocket() },
            reconnect = { null },
            scope = backgroundScope,
            clock = { currentTime },
        )

        val terminal = controller.run(
            FirmwareImage(payload(256), "2.0.0", "fw.bin", DigestType.NONE, ""),
            0x04,
            client.updateCapability.value,
        )

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.DEVICE_REFUSED, failed.reason)
        assertTrue(airoha.pageWrites.isEmpty())
        assertEquals(listOf(AirohaRace.CANCEL_REASON_STAGE_ERROR), airoha.cancels)
        assertEquals(
            listOf(listOf(0x34, 0x04, UpdtMessages.ENABLE), listOf(0x34, 0x04, UpdtMessages.DISABLE)),
            mdr.setStatusPayloads.map { sent -> sent.map { it.toInt() and 0xFF } },
        )
    }

    @Test
    fun aTwsDeviceIsRefusedWithoutOpeningTheAirohaSocket() = runTest {
        val mdr = FakeMdrConnection()
        val client = SonyProtocolClient(mdr, backgroundScope)
        client.start()
        runCurrent()

        var opened = 0
        val airoha = FakeAirohaFotaDevice(AirohaDeviceConfig())
        val controller = MtkUpdateController(
            client = client,
            openAiroha = { opened++; airoha.openSocket() },
            reconnect = { null },
            scope = backgroundScope,
            clock = { currentTime },
        )

        val terminal = controller.run(
            FirmwareImage(payload(256), "2.0.0", "fw.bin", DigestType.NONE, ""),
            0x04,
            UpdateCapability(resumable = false, tws = true, backgroundTransfer = false, acCheck = false),
        )

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.OTHER, failed.reason)
        assertTrue(failed.detail.contains("TWS"))
        assertEquals(0, opened)
        assertTrue(mdr.setStatusPayloads.isEmpty())
    }

    @Test
    fun anUnknownCapabilityIsRefused() = runTest {
        val mdr = FakeMdrConnection()
        val client = SonyProtocolClient(mdr, backgroundScope)
        client.start()
        runCurrent()

        val airoha = FakeAirohaFotaDevice(AirohaDeviceConfig())
        val controller = MtkUpdateController(
            client = client,
            openAiroha = { airoha.openSocket() },
            reconnect = { null },
            scope = backgroundScope,
            clock = { currentTime },
        )

        val terminal = controller.run(
            FirmwareImage(payload(256), "2.0.0", "fw.bin", DigestType.NONE, ""),
            0x04,
            capability = null,
        )

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.OTHER, failed.reason)
        assertTrue(airoha.commands.isEmpty())
    }
}
