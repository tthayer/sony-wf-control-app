package com.thelightphone.sonywf.update.airoha

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Byte helper that keeps values > 0x7F legal. */
private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

class RaceFrameTest {

    // ---- encode ------------------------------------------------------------

    @Test
    fun encodesTheReadNvKeyChipNameCommandByteExactly() {
        // spec-airoha-fota §2.4: READ NVKEY 0x0A00, NVKEY 0x1002, max read 0x03E8.
        val frame = RaceFrame.encode(0, RaceFrame.TYPE_CMD, 0x0A00, b(0x02, 0x10, 0xE8, 0x03))
        assertContentEquals(b(0x05, 0x5A, 0x06, 0x00, 0x00, 0x0A, 0x02, 0x10, 0xE8, 0x03), frame)
    }

    @Test
    fun lengthCountsTheRaceIdAndIsLittleEndian() {
        val frame = RaceFrame.encode(0, RaceFrame.TYPE_CMD, 0x1C04, ByteArray(0))
        // len = 2 (race id only) -> 02 00; race id LE -> 04 1C.
        assertContentEquals(b(0x05, 0x5A, 0x02, 0x00, 0x04, 0x1C), frame)
    }

    @Test
    fun sessionFlagIsOrEdIntoByteZero() {
        val frame = RaceFrame.encode(RaceFrame.FLAG_SESSION, RaceFrame.TYPE_CMD, 0x1C08, b(0x01, 0x00))
        assertEquals(0x15, frame[0].toInt() and 0xFF)
    }

    @Test
    fun longPayloadLengthSpansBothLengthBytes() {
        val frame = RaceFrame.encode(0, RaceFrame.TYPE_CMD, 0x0402, ByteArray(300))
        assertEquals(302, (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8))
        assertEquals(306, frame.size)
    }

    // ---- decode ------------------------------------------------------------

    @Test
    fun decodesASingleResponseFrame() {
        val rx = b(0x05, 0x5B, 0x05, 0x00, 0x04, 0x1C, 0x00, 0x01, 0x01)
        val out = RaceDecoder().feed(rx)
        assertEquals(1, out.size)
        assertEquals(RaceFrame.FLAG_NONE, out[0].flag)
        assertEquals(RaceFrame.TYPE_RSP, out[0].type)
        assertEquals(0x1C04, out[0].raceId)
        assertContentEquals(b(0x00, 0x01, 0x01), out[0].payload)
        assertContentEquals(rx, out[0].frame)
    }

    @Test
    fun acceptsTheSessionFlagByteZero() {
        val out = RaceDecoder().feed(b(0x15, 0x5D, 0x03, 0x00, 0x03, 0x1C, 0x00))
        assertEquals(1, out.size)
        assertEquals(RaceFrame.FLAG_SESSION, out[0].flag)
        assertEquals(RaceFrame.TYPE_NOTIFY, out[0].type)
        assertEquals(0x1C03, out[0].raceId)
    }

    @Test
    fun reassemblesAFrameSplitAcrossEveryByteBoundary() {
        val frame = RaceFrame.encode(0, RaceFrame.TYPE_RSP, 0x0CD6, b(0x00, 0x00, 0x5A))
        for (cut in 1 until frame.size) {
            val decoder = RaceDecoder()
            val first = decoder.feed(frame.copyOfRange(0, cut))
            val second = decoder.feed(frame.copyOfRange(cut, frame.size))
            assertEquals(0, first.size, "cut=$cut leaked a partial frame")
            assertEquals(1, second.size, "cut=$cut lost the frame")
            assertContentEquals(b(0x00, 0x00, 0x5A), second[0].payload)
        }
    }

    @Test
    fun decodesTwoFramesInOneChunk() {
        val a = RaceFrame.encode(0, RaceFrame.TYPE_RSP, 0x0A00, b(0x00, 0x04, 0x00, 0x41, 0x42))
        val c = RaceFrame.encode(0x10, RaceFrame.TYPE_NOTIFY, 0x1C00, b(0x00, 0x01))
        val out = RaceDecoder().feed(a + c)
        assertEquals(2, out.size)
        assertEquals(0x0A00, out[0].raceId)
        assertEquals(0x1C00, out[1].raceId)
        assertEquals(RaceFrame.FLAG_SESSION, out[1].flag)
    }

    @Test
    fun skipsLeadingAndTrailingNoise() {
        val frame = RaceFrame.encode(0, RaceFrame.TYPE_RSP, 0x1C07, b(0x00, 0x00))
        val decoder = RaceDecoder()
        val out = decoder.feed(b(0xFF, 0x00, 0x99) + frame + b(0x77))
        assertEquals(1, out.size)
        assertEquals(0x1C07, out[0].raceId)
        assertTrue(decoder.droppedBytes >= 3)
    }

    @Test
    fun resyncsWhenByteZeroIsNotFollowedByAKnownType() {
        // A stray 0x05 followed by junk must not swallow the real frame after it.
        val frame = RaceFrame.encode(0, RaceFrame.TYPE_RSP, 0x1C04, b(0x00, 0x01, 0x01))
        val out = RaceDecoder().feed(b(0x05, 0x11, 0x05, 0x22) + frame)
        assertEquals(1, out.size)
        assertEquals(0x1C04, out[0].raceId)
    }

    @Test
    fun resyncsOnAnAbsurdDeclaredLength() {
        // len = 0xFFFF is a desync, not a 64 KB frame: drop the byte and rescan.
        val frame = RaceFrame.encode(0, RaceFrame.TYPE_RSP, 0x0CD6, b(0x00, 0x00, 0x64))
        val out = RaceDecoder().feed(b(0x05, 0x5B, 0xFF, 0xFF) + frame)
        assertEquals(1, out.size)
        assertEquals(0x0CD6, out[0].raceId)
    }

    @Test
    fun resyncsOnADeclaredLengthBelowTwo() {
        val frame = RaceFrame.encode(0, RaceFrame.TYPE_RSP, 0x1C04, b(0x00))
        val out = RaceDecoder().feed(b(0x05, 0x5B, 0x01, 0x00, 0x00, 0x00) + frame)
        assertEquals(1, out.size)
        assertEquals(0x1C04, out[0].raceId)
    }

    @Test
    fun holdsAnIncompleteTrailingFrameUntilTheRestArrives() {
        val decoder = RaceDecoder()
        val frame = RaceFrame.encode(0, RaceFrame.TYPE_RSP, 0x1C00, ByteArray(20))
        assertEquals(0, decoder.feed(frame.copyOfRange(0, 10)).size)
        assertEquals(0, decoder.feed(frame.copyOfRange(10, 20)).size)
        assertEquals(1, decoder.feed(frame.copyOfRange(20, frame.size)).size)
    }

    // ---- RaceMessage -------------------------------------------------------

    @Test
    fun messageEqualityComparesThePayloadByContent() {
        val a = RaceMessage(0, RaceFrame.TYPE_RSP, 0x0A00, b(0x01, 0x02))
        val c = RaceMessage(0, RaceFrame.TYPE_RSP, 0x0A00, b(0x01, 0x02))
        assertEquals(a, c)
        assertEquals(a.hashCode(), c.hashCode())
    }

    @Test
    fun rxIndexesTheWholeFrameAndReportsMinusOnePastTheEnd() {
        val msg = RaceMessage(0, RaceFrame.TYPE_RSP, 0x1C04, b(0x00, 0x11, 0x02))
        assertEquals(0x05, msg.rx(0))
        assertEquals(0x5B, msg.rx(1))
        assertEquals(0x00, msg.rx(6)) // status
        assertEquals(0x11, msg.rx(7))
        assertEquals(0x02, msg.rx(8))
        assertEquals(-1, msg.rx(9))
        assertEquals("05 5b 05 00 04 1c 00 11 02", msg.hex)
    }
}
