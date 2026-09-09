package com.thelightphone.sonywf.update.airoha

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val ADDR = 0x00BDA000

class FlashPlanTest {

    @Test
    fun sectorsSplitAtFourKAndPadTheTailWith0xFF() {
        val image = ByteArray(FlashPlan.SECTOR + 100) { 0x5A }
        val sectors = FlashPlan.sectors(image, ADDR)

        assertEquals(2, sectors.size)
        assertEquals(ADDR, sectors[0].addr)
        assertEquals(ADDR + FlashPlan.SECTOR, sectors[1].addr)
        assertEquals(FlashPlan.SECTOR, sectors[0].len)
        assertEquals(100, sectors[1].len)

        // The buffer is always a full sector, 0xFF past the real bytes.
        assertEquals(FlashPlan.SECTOR, sectors[1].data.size)
        assertTrue(sectors[1].data.copyOfRange(0, 100).all { it == 0x5A.toByte() })
        assertTrue(sectors[1].data.copyOfRange(100, FlashPlan.SECTOR).all { it == 0xFF.toByte() })

        // `content` is what 0x0431 hashes: exactly the bytes that were read.
        assertEquals(100, sectors[1].content.size)
    }

    @Test
    fun sectorsDefaultToNeedsWriteAndNotErased() {
        val sector = FlashPlan.sectors(ByteArray(16), ADDR).single()
        assertTrue(sector.needsWrite)
        assertTrue(!sector.erased)
    }

    @Test
    fun sectorsOfAnEmptyImageIsEmpty() {
        assertEquals(0, FlashPlan.sectors(ByteArray(0), ADDR).size)
    }

    @Test
    fun pagesSplitAtTwoFiftySixAndDropAll0xFFPages() {
        val data = ByteArray(FlashPlan.SECTOR) { 0xFF.toByte() }
        // Page 0 all 0xFF (dropped), page 1 has one real byte, page 2 dropped,
        // page 3 has a real byte.
        data[FlashPlan.PAGE] = 0x00
        data[3 * FlashPlan.PAGE + 5] = 0x11
        val sector = FlashPlan.Sector(ADDR, data, len = FlashPlan.SECTOR)

        val pages = FlashPlan.pages(sector)
        assertEquals(listOf(ADDR + 256, ADDR + 768), pages.map { it.first })
        assertEquals(0x00, pages[0].second[0].toInt() and 0xFF)
        assertTrue(pages[0].second.copyOfRange(1, 256).all { it == 0xFF.toByte() })
    }

    @Test
    fun pagesStopAtTheRealReadLengthAndPadTheLastPage() {
        val image = ByteArray(300) { 0x7E }
        val sector = FlashPlan.sectors(image, ADDR).single()

        val pages = FlashPlan.pages(sector)
        assertEquals(2, pages.size)
        assertEquals(ADDR + 256, pages[1].first)
        assertEquals(256, pages[1].second.size)
        assertTrue(pages[1].second.copyOfRange(0, 44).all { it == 0x7E.toByte() })
        assertTrue(pages[1].second.copyOfRange(44, 256).all { it == 0xFF.toByte() })
    }

    @Test
    fun recordIsCrcThenAddressLe32ThenTheData() {
        val page = ByteArray(FlashPlan.PAGE)
        val record = FlashPlan.record(ADDR, page)

        assertEquals(FlashPlan.RECORD, record.size)
        assertEquals(0x00, record[0].toInt() and 0xFF) // crc8 of 256 zero bytes
        assertContentEquals(byteArrayOf(0x00, 0xA0.toByte(), 0xBD.toByte(), 0x00), record.copyOfRange(1, 5))
        assertTrue(record.copyOfRange(5, FlashPlan.RECORD).all { it == 0.toByte() })
    }

    @Test
    fun recordCarriesTheCrcOfTheDataBytesOnly() {
        val page = ByteArray(FlashPlan.PAGE) { 0xFF.toByte() }
        assertEquals(0xB4, FlashPlan.record(0x10, page)[0].toInt() and 0xFF)
    }

    @Test
    fun recordRejectsAWrongSizedPage() {
        assertFailsWith<IllegalArgumentException> { FlashPlan.record(ADDR, ByteArray(255)) }
    }

    @Test
    fun sha256MatchesTheJdkDigest() {
        val data = ByteArray(1000) { it.toByte() }
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), FlashPlan.sha256(data))
    }

    @Test
    fun le32RoundTrips() {
        val out = ByteArray(4)
        FlashPlan.putLe32(out, 0, ADDR)
        assertContentEquals(byteArrayOf(0x00, 0xA0.toByte(), 0xBD.toByte(), 0x00), out)
        assertEquals(ADDR, FlashPlan.le32(out, 0))
    }
}
