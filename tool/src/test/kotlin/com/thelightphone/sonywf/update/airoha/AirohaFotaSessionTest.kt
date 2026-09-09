package com.thelightphone.sonywf.update.airoha

import com.thelightphone.sonywf.update.FotaFailure
import com.thelightphone.sonywf.update.FotaPhase
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val ADDR = 0x00BDA000

/** Image bytes that never form an all-0xFF page. */
private fun payload(size: Int): ByteArray = ByteArray(size) { ((it % 251) + 1).toByte() }

class AirohaFotaSessionTest {

    @Test
    fun readChipNameDecodesTheNvkeyReply() = runTest {
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(chipName = "MT2822S"))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        assertEquals("MT2822S", AirohaFotaSession(race) { currentTime }.readChipName())
        // Handshake traffic must stay on byte0 = 0x05.
        assertEquals(0x05, device.writes[0][0].toInt() and 0xFF)
    }

    @Test
    fun readChipNameRefusesAnEmptyName() = runTest {
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(chipName = ""))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        assertEquals(null, AirohaFotaSession(race) { currentTime }.readChipName())
    }

    @Test
    fun inquiryAndQueryStateReadThePartitionAndState() = runTest {
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig())
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()
        val session = AirohaFotaSession(race) { currentTime }

        assertEquals(PartitionInfo(0, 0, ADDR, 0x00962000), session.inquiry())
        assertEquals(0x0101, session.queryState())
    }

    @Test
    fun happyPathOnAFreshDeviceErasesThenWritesEveryPage() = runTest {
        val image = payload(FlashPlan.SECTOR + 512)
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig())
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val session = AirohaFotaSession(race) { currentTime }
        val terminal = session.transfer(image, AirohaRace.MODE_BACKGROUND)

        assertEquals(FotaPhase.Transferring(100), terminal)

        // Stage order (spec §3.0), with 0x0433/0x0431/0x0404/0x0402 repeated.
        assertEquals(
            listOf(
                AirohaRace.INQUIRY_FOTA,
                AirohaRace.QUERY_STATE,
                AirohaRace.FOTA_START,
                AirohaRace.QUERY_TRANSMIT_INTERVAL,
                AirohaRace.GET_ERASE_STATUS,
            ),
            device.commands.take(5),
        )
        assertEquals(
            listOf(AirohaRace.CHECK_INTEGRITY, AirohaRace.WRITE_STATE, AirohaRace.QUERY_STATE),
            device.commands.takeLast(3),
        )

        // Both sectors erased, low address first, then every page written.
        assertEquals(listOf(ADDR, ADDR + FlashPlan.SECTOR), device.erases)
        assertEquals(
            listOf(
                AirohaRace.STATE_ERASING,
                AirohaRace.STATE_WRITING,
                AirohaRace.STATE_VERIFYING,
                AirohaRace.STATE_WRITTEN,
            ),
            device.stateWrites,
        )
        assertEquals(18, device.pageWrites.size) // 16 pages + 2 for the 512 B tail
        assertContentEquals(image, device.read(ADDR, image.size))
        assertTrue(device.badCrcPages.isEmpty())
        assertTrue(device.writesToUnerasedSectors.isEmpty())
        assertTrue(device.flagViolations.isEmpty())
        assertEquals(AirohaRace.STATE_WRITTEN, device.deviceState)
        assertTrue(device.cancels.isEmpty())

        // 0x1C08 onwards every frame carries byte0 = 0x15.
        val startedAt = device.writes.indexOfFirst { RaceDecoder().feed(it).first().raceId == AirohaRace.FOTA_START }
        assertTrue(startedAt > 0)
        assertTrue(device.writes.drop(startedAt).all { (it[0].toInt() and 0xFF) == 0x15 })
        assertTrue(device.writes.take(startedAt).all { (it[0].toInt() and 0xFF) == 0x05 })
    }

    @Test
    fun commitSucceedsWhenTheSocketDrops() = runTest {
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(closeOnCommit = true))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        assertTrue(AirohaFotaSession(race) { currentTime }.commit())
        assertFalse(device.isConnected)
    }

    @Test
    fun commitSucceedsOnAStatusZeroReply() = runTest {
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(closeOnCommit = false, commitStatus = 0))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        assertTrue(AirohaFotaSession(race) { currentTime }.commit())
    }

    @Test
    fun commitFailsOnANonZeroStatus() = runTest {
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(closeOnCommit = false, commitStatus = 3))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        assertFalse(AirohaFotaSession(race) { currentTime }.commit())
    }

    @Test
    fun resumeSkipsTheErasureOfSectorsTheBitmapReportsBlank() = runTest {
        val image = payload(3 * FlashPlan.SECTOR)
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(erasedSectors = 2))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }.transfer(image, AirohaRace.MODE_BACKGROUND)

        assertEquals(FotaPhase.Transferring(100), terminal)
        // Only the third sector needed erasing; sector 0 being blank also means
        // 0x0431 is skipped entirely (`Compare_stages`).
        assertEquals(listOf(ADDR + 2 * FlashPlan.SECTOR), device.erases)
        assertFalse(AirohaRace.COMPARE in device.commands)
        assertEquals(48, device.pageWrites.size)
        assertContentEquals(image, device.read(ADDR, image.size))
        assertTrue(device.writesToUnerasedSectors.isEmpty())
    }

    @Test
    fun aRunWhoseDigestMatchesIsNeitherErasedNorWritten() = runTest {
        val image = payload(FlashPlan.SECTOR)
        // The device already holds exactly this image and reports it un-erased.
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(preload = image))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }.transfer(image, AirohaRace.MODE_BACKGROUND)

        assertEquals(FotaPhase.Transferring(100), terminal)
        assertTrue(AirohaRace.COMPARE in device.commands)
        assertTrue(device.erases.isEmpty())
        assertTrue(device.pageWrites.isEmpty())
        assertFalse(AirohaRace.ERASE in device.commands)
        assertFalse(AirohaRace.WRITE_FLASH in device.commands)
        // Nothing to program: only the final 0x0211 state is written.
        assertEquals(listOf(AirohaRace.STATE_WRITTEN), device.stateWrites)
    }

    @Test
    fun anOversizeImageIsRefusedBeforeFotaStartAndCancels() = runTest {
        // Partition 0x2000 (2 sectors): the guard needs len <= length - 4096.
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(partitionLength = 0x2000))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }
            .transfer(payload(2 * FlashPlan.SECTOR), AirohaRace.MODE_BACKGROUND)

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.OTHER, failed.reason)
        assertFalse(AirohaRace.FOTA_START in device.commands)
        assertTrue(device.erases.isEmpty())
        assertEquals(listOf(AirohaRace.CANCEL_REASON_STAGE_ERROR), device.cancels)
    }

    @Test
    fun anUnexpectedDeviceStateIsRefusedBeforeFotaStart() = runTest {
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(state = 0x0303))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }
            .transfer(payload(256), AirohaRace.MODE_BACKGROUND)

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.DEVICE_REFUSED, failed.reason)
        assertFalse(AirohaRace.FOTA_START in device.commands)
        assertEquals(listOf(AirohaRace.CANCEL_REASON_STAGE_ERROR), device.cancels)
    }

    @Test
    fun aNonZeroFotaStartStatusAbortsAndCancels() = runTest {
        val device = FakeAirohaFotaDevice(
            AirohaDeviceConfig(statusOverrides = mapOf(AirohaRace.FOTA_START to 0x05)),
        )
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }
            .transfer(payload(256), AirohaRace.MODE_BACKGROUND)

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.DEVICE_REFUSED, failed.reason)
        assertEquals(listOf(AirohaRace.CANCEL_REASON_STAGE_ERROR), device.cancels)
        assertTrue(device.erases.isEmpty())
        assertTrue(device.pageWrites.isEmpty())
    }

    @Test
    fun aNonZeroEraseStatusAbortsBeforeAnyPageIsWritten() = runTest {
        val device = FakeAirohaFotaDevice(
            AirohaDeviceConfig(statusOverrides = mapOf(AirohaRace.ERASE to 0x02)),
        )
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }
            .transfer(payload(1024), AirohaRace.MODE_BACKGROUND)

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.DEVICE_REFUSED, failed.reason)
        assertTrue(device.pageWrites.isEmpty())
        assertEquals(listOf(AirohaRace.CANCEL_REASON_STAGE_ERROR), device.cancels)
    }

    @Test
    fun aFinalStateOtherThan0x0211Fails() = runTest {
        // The device acks every 0x1C06 but never leaves 0x0101.
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(honourStateWrites = false))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }
            .transfer(payload(256), AirohaRace.MODE_BACKGROUND)

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.DEVICE_REFUSED, failed.reason)
        assertTrue(failed.detail.contains("0101"))
        assertEquals(listOf(AirohaRace.CANCEL_REASON_STAGE_ERROR), device.cancels)
    }

    @Test
    fun theInterval0x1C1CReportsOverridesThePacing() = runTest {
        val image = payload(1024)
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(intervalMs = 5))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }.transfer(image, AirohaRace.MODE_BACKGROUND)
        assertEquals(FotaPhase.Transferring(100), terminal)
        assertEquals(4, device.pageWrites.size)
    }

    @Test
    fun aMissingIntervalReplyIsNotFatal() = runTest {
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(replyToInterval = false))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }.transfer(payload(256), AirohaRace.MODE_BACKGROUND)
        assertEquals(FotaPhase.Transferring(100), terminal)
    }

    // ---- Long packets ------------------------------------------------------

    @Test
    fun backgroundModeConcatenatesThreeCommandsPerWrite() = runTest {
        val image = payload(4 * FlashPlan.PAGE)
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig())
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        assertEquals(
            FotaPhase.Transferring(100),
            AirohaFotaSession(race) { currentTime }.transfer(image, AirohaRace.MODE_BACKGROUND),
        )

        val writePackets = device.writes.filter { it.size >= 269 && frameRaceId(it) == AirohaRace.WRITE_FLASH }
        assertEquals(listOf(3 * 269, 269), writePackets.map { it.size })
        assertEquals(4, device.pageWrites.size)
    }

    @Test
    fun activeModeSendsOneCommandPerWrite() = runTest {
        val image = payload(4 * FlashPlan.PAGE)
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig())
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        assertEquals(
            FotaPhase.Transferring(100),
            AirohaFotaSession(race) { currentTime }.transfer(image, AirohaRace.MODE_ACTIVE),
        )

        val writePackets = device.writes.filter { frameRaceId(it) == AirohaRace.WRITE_FLASH }
        assertEquals(4, writePackets.size)
        assertTrue(writePackets.all { it.size == 269 })
    }

    @Test
    fun aPageWhoseAckIsLostIsResentOnceItFallsBehind() = runTest {
        val image = payload(8 * FlashPlan.PAGE)
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(dropFirstAckFor = setOf(ADDR)))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        assertEquals(
            FotaPhase.Transferring(100),
            AirohaFotaSession(race) { currentTime }.transfer(image, AirohaRace.MODE_BACKGROUND),
        )

        // 8 distinct pages, and the un-acked first page went out twice.
        assertEquals(9, device.pageWrites.size)
        assertEquals(2, device.pageWrites.count { it.first == ADDR })
        assertEquals(8, device.pageWrites.map { it.first }.distinct().size)
    }

    @Test
    fun theBusyBitRetriesThePageRatherThanFailing() = runTest {
        val image = payload(FlashPlan.PAGE)
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(busyFirstWrite = true))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        assertEquals(
            FotaPhase.Transferring(100),
            AirohaFotaSession(race) { currentTime }.transfer(image, AirohaRace.MODE_BACKGROUND),
        )
        // The busy reply was not an ack: the page was written on the retry.
        assertEquals(1, device.pageWrites.size)
        assertTrue(device.cancels.isEmpty())
    }

    @Test
    fun neverMoreThanFourCommandsAreOutstanding() = runTest {
        val image = payload(8 * FlashPlan.PAGE)
        val device = FakeAirohaFotaDevice(AirohaDeviceConfig(ackWrites = false))
        val race = RaceClient(device.openSocket(), backgroundScope)
        race.start()
        runCurrent()

        val terminal = AirohaFotaSession(race) { currentTime }.transfer(image, AirohaRace.MODE_BACKGROUND)

        val failed = assertIs<FotaPhase.Failed>(terminal)
        assertEquals(FotaFailure.TRANSFER_FAILED, failed.reason)
        // The window never opened past 4 distinct pages, and each was retried
        // at most 3 times before the run was abandoned.
        val distinct = device.pageWrites.map { it.first }.distinct()
        assertEquals(4, distinct.size)
        assertEquals(listOf(ADDR, ADDR + 256, ADDR + 512, ADDR + 768), distinct)
        assertTrue(device.pageWrites.count { it.first == ADDR } <= 4)
        assertEquals(listOf(AirohaRace.CANCEL_REASON_STAGE_ERROR), device.cancels)
    }
}

private fun frameRaceId(bytes: ByteArray): Int = RaceDecoder().feed(bytes).first().raceId
