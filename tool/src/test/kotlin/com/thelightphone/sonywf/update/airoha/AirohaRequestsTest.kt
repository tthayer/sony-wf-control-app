package com.thelightphone.sonywf.update.airoha

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

/** Frame as it goes on the wire, so the spec's hex dumps can be compared verbatim. */
private fun frame(flag: Int, raceId: Int, payload: ByteArray): ByteArray =
    RaceFrame.encode(flag, RaceFrame.TYPE_CMD, raceId, payload)

private const val PRE = RaceFrame.FLAG_NONE //    byte0 0x05, before FOTA start
private const val SESSION = RaceFrame.FLAG_SESSION // byte0 0x15, after FOTA start

/**
 * Byte-exact request builders, compared against the hex dumps in
 * spec-airoha-mt28xx-single §1-§5.
 */
class AirohaRequestsTest {

    @Test
    fun readChipNameMatchesSpec() {
        assertContentEquals(
            b(0x05, 0x5A, 0x06, 0x00, 0x00, 0x0A, 0x02, 0x10, 0xE8, 0x03),
            frame(PRE, AirohaRace.READ_NVKEY, AirohaRequests.readChipName()),
        )
    }

    @Test
    fun inquiryFotaMatchesSpec() {
        assertContentEquals(
            b(0x05, 0x5A, 0x03, 0x00, 0x00, 0x1C, 0x00),
            frame(PRE, AirohaRace.INQUIRY_FOTA, AirohaRequests.inquiryFota()),
        )
    }

    @Test
    fun queryStateHasNoPayload() {
        assertContentEquals(
            b(0x05, 0x5A, 0x02, 0x00, 0x04, 0x1C),
            frame(PRE, AirohaRace.QUERY_STATE, AirohaRequests.queryState()),
        )
    }

    @Test
    fun fotaStartCarriesTheSessionFlagAndTheModeByte() {
        assertContentEquals(
            b(0x15, 0x5A, 0x04, 0x00, 0x08, 0x1C, 0x01, 0x00),
            frame(SESSION, AirohaRace.FOTA_START, AirohaRequests.fotaStart(AirohaRace.MODE_BACKGROUND)),
        )
        assertContentEquals(
            b(0x15, 0x5A, 0x04, 0x00, 0x08, 0x1C, 0x01, 0x01),
            frame(SESSION, AirohaRace.FOTA_START, AirohaRequests.fotaStart(AirohaRace.MODE_ACTIVE)),
        )
    }

    @Test
    fun queryTransmitIntervalMatchesSpec() {
        assertContentEquals(
            b(0x15, 0x5A, 0x04, 0x00, 0x1C, 0x1C, 0x01, 0x00),
            frame(SESSION, AirohaRace.QUERY_TRANSMIT_INTERVAL, AirohaRequests.queryTransmitInterval()),
        )
    }

    @Test
    fun getEraseStatusIsStorageTypeThenRoleThenAddressThenLength() {
        assertContentEquals(
            b(0x15, 0x5A, 0x0C, 0x00, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x08, 0x00),
            frame(SESSION, AirohaRace.GET_ERASE_STATUS, AirohaRequests.getEraseStatus(0, 0x00200000, 512 * 1024)),
        )
    }

    @Test
    fun compareHasTheSameShapeAsGetEraseStatus() {
        assertContentEquals(
            b(0x15, 0x5A, 0x0C, 0x00, 0x31, 0x04, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x08, 0x00),
            frame(SESSION, AirohaRace.COMPARE, AirohaRequests.compare(0, 0x00200000, 512 * 1024)),
        )
    }

    @Test
    fun startTransactionHasNoPayload() {
        assertContentEquals(
            b(0x15, 0x5A, 0x02, 0x00, 0x0A, 0x1C),
            frame(SESSION, AirohaRace.START_TRANSACTION, AirohaRequests.startTransaction()),
        )
    }

    @Test
    fun writeStateIsLittleEndian() {
        assertContentEquals(
            b(0x15, 0x5A, 0x04, 0x00, 0x06, 0x1C, 0x00, 0x02),
            frame(SESSION, AirohaRace.WRITE_STATE, AirohaRequests.writeState(AirohaRace.STATE_ERASING)),
        )
        assertContentEquals(
            b(0x15, 0x5A, 0x04, 0x00, 0x06, 0x1C, 0x01, 0x02),
            frame(SESSION, AirohaRace.WRITE_STATE, AirohaRequests.writeState(AirohaRace.STATE_WRITING)),
        )
        assertContentEquals(
            b(0x15, 0x5A, 0x04, 0x00, 0x06, 0x1C, 0x10, 0x02),
            frame(SESSION, AirohaRace.WRITE_STATE, AirohaRequests.writeState(AirohaRace.STATE_VERIFYING)),
        )
        assertContentEquals(
            b(0x15, 0x5A, 0x04, 0x00, 0x06, 0x1C, 0x11, 0x02),
            frame(SESSION, AirohaRace.WRITE_STATE, AirohaRequests.writeState(AirohaRace.STATE_WRITTEN)),
        )
    }

    @Test
    fun eraseIsStorageTypeThenLengthThenAddress() {
        assertContentEquals(
            b(0x15, 0x5A, 0x0B, 0x00, 0x04, 0x04, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00),
            frame(SESSION, AirohaRace.ERASE, AirohaRequests.erase(0, 0x00200000)),
        )
    }

    @Test
    fun writeFlashCarriesOneRecordOfTwoSixtyOneBytes() {
        val page = ByteArray(FlashPlan.PAGE)
        val payload = AirohaRequests.writeFlash(0, listOf(FlashPlan.record(0x00200000, page)))
        val out = frame(SESSION, AirohaRace.WRITE_FLASH, payload)

        // len = 2 + (2 + 261) = 0x0109; whole frame 269 bytes.
        assertContentEquals(b(0x15, 0x5A, 0x09, 0x01, 0x02, 0x04, 0x00, 0x01), out.copyOfRange(0, 8))
        assertEquals(269, out.size)
        // crc8(zero page) = 0x00, then the page address LE32.
        assertContentEquals(b(0x00, 0x00, 0x00, 0x20, 0x00), out.copyOfRange(8, 13))
    }

    @Test
    fun writeFlashRejectsAMalformedRecord() {
        assertFailsWith<IllegalArgumentException> { AirohaRequests.writeFlash(0, emptyList()) }
        assertFailsWith<IllegalArgumentException> { AirohaRequests.writeFlash(0, listOf(ByteArray(260))) }
    }

    @Test
    fun checkIntegrityMatchesSpec() {
        assertContentEquals(
            b(0x15, 0x5A, 0x05, 0x00, 0x01, 0x1C, 0x01, 0x00, 0x00),
            frame(SESSION, AirohaRace.CHECK_INTEGRITY, AirohaRequests.checkIntegrity(0)),
        )
    }

    @Test
    fun commitMatchesSpec() {
        assertContentEquals(
            b(0x15, 0x5A, 0x03, 0x00, 0x02, 0x1C, 0x00),
            frame(SESSION, AirohaRace.COMMIT, AirohaRequests.commit()),
        )
        // Also legal with byte0 = 0x05 on the reconnect-for-commit path (§0.1).
        assertContentEquals(
            b(0x05, 0x5A, 0x03, 0x00, 0x02, 0x1C, 0x00),
            frame(PRE, AirohaRace.COMMIT, AirohaRequests.commit()),
        )
    }

    @Test
    fun cancelMatchesSpec() {
        assertContentEquals(
            b(0x15, 0x5A, 0x05, 0x00, 0x03, 0x1C, 0x07, 0x01, 0x00),
            frame(SESSION, AirohaRace.CANCEL, AirohaRequests.cancel(AirohaRace.CANCEL_REASON_USER)),
        )
        assertContentEquals(
            b(0x15, 0x5A, 0x05, 0x00, 0x03, 0x1C, 0x07, 0x01, 0x01),
            frame(SESSION, AirohaRace.CANCEL, AirohaRequests.cancel(AirohaRace.CANCEL_REASON_STAGE_ERROR)),
        )
    }

    @Test
    fun theFirstZeroPageRecordAt00BDA000() {
        // The exact bytes that go to a WH-1000XM5 for a page of zeros at the
        // partition base: 00 (crc8) | 00 A0 BD 00 (addr LE32) | 256 x 00.
        val record = FlashPlan.record(0x00BDA000, ByteArray(FlashPlan.PAGE))
        assertContentEquals(b(0x00, 0x00, 0xA0, 0xBD, 0x00), record.copyOfRange(0, 5))
        val out = frame(SESSION, AirohaRace.WRITE_FLASH, AirohaRequests.writeFlash(0, listOf(record)))
        assertContentEquals(
            b(0x15, 0x5A, 0x09, 0x01, 0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0xA0, 0xBD, 0x00),
            out.copyOfRange(0, 13),
        )
    }
}
