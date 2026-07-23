package com.thelightphone.sonywf.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

class SonyFrameTest {

    // ---- Reference vectors -------------------------------------------------

    @Test
    fun initFrameMatchesReferenceVector() {
        // Init: Command1 (0x0c), seq 0, payload [0x00, 0x00].
        val frame = SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, byteArrayOf(0x00, 0x00))
        assertEquals("3e 0c 00 00 00 00 02 00 00 0e 3c", frame.toHex())
    }

    @Test
    fun ackReplyToSeq1MatchesReferenceVector() {
        // Ack replying to a received seq 1: our seq = (1 - 1) = 0, empty payload.
        val frame = SonyFrame.encode(SonyFrame.TYPE_ACK, 1 - 1, ByteArray(0))
        assertEquals("3e 01 00 00 00 00 00 01 3c", frame.toHex())
    }

    // ---- Checksum ----------------------------------------------------------

    @Test
    fun checksumIsSumOfTypeSeqLengthAndPayloadMod256() {
        val type = SonyFrame.TYPE_COMMAND1
        val seq = 0x05
        val payload = byteArrayOf(0x22, 0x01)
        val frame = SonyFrame.encode(type, seq, payload)

        // Manually: type + seq + len(4 bytes) + payload, mod 256.
        val expected = (type + seq + 0 + 0 + 0 + payload.size + 0x22 + 0x01) and 0xFF
        // Checksum sits just before the trailer (no escaping needed for these bytes).
        val checksumByte = frame[frame.size - 2].toInt() and 0xFF
        assertEquals(expected, checksumByte)
        assertEquals(SonyFrame.TRAILER, frame.last().toInt() and 0xFF)
        assertEquals(SonyFrame.HEADER, frame.first().toInt() and 0xFF)
    }

    // ---- Escaping ----------------------------------------------------------

    @Test
    fun escapingProducesEscapeSequencesAndNoRawSpecialBytesInBody() {
        // Payload that contains every special byte.
        val payload = byteArrayOf(0x3e, 0x3c, 0x3d, 0x00, 0x11)
        val frame = SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, payload)

        // Body is frame[1 .. size-2] (between header and trailer).
        val body = frame.copyOfRange(1, frame.size - 1)
        var i = 0
        while (i < body.size) {
            val b = body[i].toInt() and 0xFF
            // No raw special byte may appear unescaped in the body.
            assertTrue(b != SonyFrame.HEADER, "raw HEADER leaked into body at $i")
            assertTrue(b != SonyFrame.TRAILER, "raw TRAILER leaked into body at $i")
            if (b == SonyFrame.ESCAPE) {
                // Escaped byte must have bit 4 cleared.
                val next = body[i + 1].toInt() and 0xFF
                assertEquals(0, next and 0x10, "escaped byte must have bit 4 cleared")
                i += 2
            } else {
                i++
            }
        }
    }

    @Test
    fun escapingRoundTripsThroughDecoder() {
        val payload = byteArrayOf(0x3e, 0x3c, 0x3d, 0x00, 0x3d, 0x3e, 0x42)
        val frame = SonyFrame.encode(SonyFrame.TYPE_COMMAND2, 7, payload)

        val messages = SonyFrameDecoder().feed(frame)
        assertEquals(1, messages.size)
        assertEquals(SonyFrame.TYPE_COMMAND2, messages[0].type)
        assertEquals(7, messages[0].seq)
        assertContentEquals(payload, messages[0].payload)
    }

    // ---- Streaming decoder -------------------------------------------------

    @Test
    fun decoderReassemblesFrameSplitAcrossTwoFeeds() {
        val frame = SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, byteArrayOf(0x66, 0x17))
        val decoder = SonyFrameDecoder()

        val cut = frame.size / 2
        val first = decoder.feed(frame.copyOfRange(0, cut))
        assertTrue(first.isEmpty(), "partial frame should yield nothing yet")

        val second = decoder.feed(frame.copyOfRange(cut, frame.size))
        assertEquals(1, second.size)
        assertContentEquals(byteArrayOf(0x66, 0x17), second[0].payload)
    }

    @Test
    fun decoderHandlesSplitInMiddleOfEscapeSequence() {
        // Payload with a special byte guarantees an escape sequence in the wire form.
        val payload = byteArrayOf(0x3e, 0x01)
        val frame = SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, payload)

        // Find an ESCAPE byte and split right after it (between 0x3d and the escaped byte).
        val escIdx = frame.indexOfFirst { (it.toInt() and 0xFF) == SonyFrame.ESCAPE }
        assertTrue(escIdx >= 0, "expected an escape byte in the wire form")

        val decoder = SonyFrameDecoder()
        assertTrue(decoder.feed(frame.copyOfRange(0, escIdx + 1)).isEmpty())
        val out = decoder.feed(frame.copyOfRange(escIdx + 1, frame.size))
        assertEquals(1, out.size)
        assertContentEquals(payload, out[0].payload)
    }

    @Test
    fun decoderHandlesTwoFramesInOneChunk() {
        val a = SonyFrame.encode(SonyFrame.TYPE_ACK, 0, ByteArray(0))
        val b = SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 1, byteArrayOf(0x22, 0x0a))
        val chunk = a + b

        val out = SonyFrameDecoder().feed(chunk)
        assertEquals(2, out.size)
        assertEquals(SonyFrame.TYPE_ACK, out[0].type)
        assertEquals(SonyFrame.TYPE_COMMAND1, out[1].type)
        assertContentEquals(byteArrayOf(0x22, 0x0a), out[1].payload)
    }

    @Test
    fun decoderSkipsLeadingNoiseBeforeHeader() {
        val frame = SonyFrame.encode(SonyFrame.TYPE_ACK, 0, ByteArray(0))
        val chunk = byteArrayOf(0x00, 0x11, 0x22) + frame
        val out = SonyFrameDecoder().feed(chunk)
        assertEquals(1, out.size)
        assertEquals(SonyFrame.TYPE_ACK, out[0].type)
    }

    @Test
    fun decoderDropsFrameWithBadChecksum() {
        val frame = SonyFrame.encode(SonyFrame.TYPE_COMMAND1, 0, byteArrayOf(0x66, 0x17)).copyOf()
        // Corrupt the checksum byte (just before trailer). None of these bytes
        // are special, so no escaping shifts positions.
        frame[frame.size - 2] = (frame[frame.size - 2] + 1).toByte()

        val decoder = SonyFrameDecoder()
        val out = decoder.feed(frame)
        assertTrue(out.isEmpty(), "bad checksum frame must be dropped")
        assertEquals(1, decoder.droppedFrames)
    }

    @Test
    fun decoderResyncsOnStrayHeaderMidFrame() {
        // Truncated frame (header + a couple bytes, no trailer) followed by a good frame.
        val good = SonyFrame.encode(SonyFrame.TYPE_ACK, 1, ByteArray(0))
        val garbage = byteArrayOf(SonyFrame.HEADER.toByte(), 0x0c, 0x00)
        val out = SonyFrameDecoder().feed(garbage + good)
        assertEquals(1, out.size)
        assertEquals(SonyFrame.TYPE_ACK, out[0].type)
        assertEquals(1, out[0].seq)
    }
}
