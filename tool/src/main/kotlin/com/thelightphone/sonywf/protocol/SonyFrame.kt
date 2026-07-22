package com.thelightphone.sonywf.protocol

/**
 * Low-level Sony WF-1000XM5 wire framing.
 *
 * This file is intentionally PURE: it depends on nothing but the Kotlin/JVM
 * standard library (no `android.*`, no coroutines, no [com.thelightphone.sdk]
 * transport types). Every function here is a deterministic transform over
 * bytes, which makes the framing and escaping trivially unit-testable.
 *
 * Frame layout, BEFORE escaping:
 * ```
 *   byte 0      : HEADER (0x3e)
 *   byte 1      : message type
 *   byte 2      : sequence number
 *   bytes 3..6  : payload length, 4-byte big-endian u32
 *   bytes 7..   : payload
 *   next byte   : checksum = (sum of bytes[1..end-of-payload]) mod 256
 *   last byte   : TRAILER (0x3c)
 * ```
 * The checksum covers type + seq + the 4 length bytes + payload; it excludes
 * the header, the checksum byte itself, and the trailer.
 *
 * Escaping is applied to every byte BETWEEN the header and trailer (type, seq,
 * length, payload and checksum). A byte equal to [HEADER], [TRAILER] or
 * [ESCAPE] is emitted as [ESCAPE] followed by `(byte AND ESCAPE_MASK)` (which
 * clears bit 4). The header and trailer themselves are never escaped. To
 * decode, a `0x3d` is dropped and the following byte is OR-ed with `0x10`.
 */
object SonyFrame {
    const val HEADER = 0x3e
    const val TRAILER = 0x3c
    const val ESCAPE = 0x3d
    const val ESCAPE_MASK = 0xEF

    // Message types.
    const val TYPE_ACK = 0x01
    const val TYPE_COMMAND1 = 0x0c
    const val TYPE_COMMAND2 = 0x0e

    /**
     * Encode a single frame: build the pre-escape body (type, seq, big-endian
     * length, payload), append the checksum, then escape the whole body and
     * wrap it with the (never-escaped) header and trailer.
     */
    fun encode(type: Int, seq: Int, payload: ByteArray): ByteArray {
        val len = payload.size

        // Body = everything the checksum covers, then the checksum byte itself.
        val body = ArrayList<Int>(6 + len + 1)
        body.add(type and 0xFF)
        body.add(seq and 0xFF)
        body.add((len ushr 24) and 0xFF)
        body.add((len ushr 16) and 0xFF)
        body.add((len ushr 8) and 0xFF)
        body.add(len and 0xFF)
        for (b in payload) body.add(b.toInt() and 0xFF)

        var sum = 0
        for (b in body) sum += b
        body.add(sum and 0xFF) // checksum

        val out = ArrayList<Byte>(body.size + 2)
        out.add(HEADER.toByte())
        for (b in body) {
            if (b == HEADER || b == TRAILER || b == ESCAPE) {
                out.add(ESCAPE.toByte())
                out.add((b and ESCAPE_MASK).toByte())
            } else {
                out.add(b.toByte())
            }
        }
        out.add(TRAILER.toByte())
        return out.toByteArray()
    }
}

/**
 * A fully decoded, checksum-validated Sony message.
 *
 * [equals]/[hashCode] are overridden so value comparisons (and set/list
 * membership) treat [payload] by content rather than by reference — important
 * for tests and for de-duplicating notifications.
 */
class SonyMessage(
    val type: Int,
    val seq: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SonyMessage) return false
        return type == other.type && seq == other.seq && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + seq
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "SonyMessage(type=0x${type.toString(16)}, seq=$seq, payload=${payload.joinToString(" ") { "%02x".format(it) }})"
}

/**
 * A streaming frame decoder. Bytes arrive in arbitrary chunks (chunk
 * boundaries are NOT frame boundaries), so [feed] buffers whatever it cannot
 * yet turn into complete frames and reprocesses it on the next call.
 *
 * Responsibilities:
 *  - reassemble frames split across [feed] calls (including a split in the
 *    middle of an escape sequence),
 *  - unescape the body,
 *  - validate the declared length and checksum, dropping frames that fail,
 *  - resynchronise on a stray [SonyFrame.HEADER] seen mid-frame, and skip
 *    noise bytes before a header.
 *
 * Not thread-safe: feed from a single consumer (e.g. one `incoming` collector).
 */
class SonyFrameDecoder {
    private var buffer = ByteArray(0)

    /** Number of frames dropped for a bad checksum or malformed length. */
    var droppedFrames: Int = 0
        private set

    fun feed(chunk: ByteArray): List<SonyMessage> {
        buffer = if (buffer.isEmpty()) chunk.copyOf() else buffer + chunk

        val out = ArrayList<SonyMessage>()
        val n = buffer.size
        var pos = 0

        loop@ while (pos < n) {
            val b = buffer[pos].toInt() and 0xFF
            if (b != SonyFrame.HEADER) {
                // Noise before a header: discard it and keep scanning.
                pos++
                continue
            }
            when (val r = readFrame(buffer, pos)) {
                is ReadResult.Complete -> {
                    if (r.message != null) out.add(r.message) else droppedFrames++
                    pos = r.nextIndex
                }
                is ReadResult.Resync -> {
                    // A new header appeared before the current frame closed:
                    // the in-flight frame was corrupt/truncated. Drop it and
                    // restart parsing at the new header.
                    pos = r.headerIndex
                }
                ReadResult.NeedMore -> {
                    // Not enough bytes yet; retain from the header onward.
                    break@loop
                }
            }
        }

        buffer = if (pos >= n) ByteArray(0) else buffer.copyOfRange(pos, n)
        return out
    }

    private sealed interface ReadResult {
        class Complete(val message: SonyMessage?, val nextIndex: Int) : ReadResult
        class Resync(val headerIndex: Int) : ReadResult
        data object NeedMore : ReadResult
    }

    /** [start] points at a [SonyFrame.HEADER] byte. */
    private fun readFrame(buf: ByteArray, start: Int): ReadResult {
        val body = ArrayList<Int>()
        var j = start + 1
        val n = buf.size
        while (j < n) {
            when (val b = buf[j].toInt() and 0xFF) {
                SonyFrame.TRAILER -> return ReadResult.Complete(buildMessage(body), j + 1)
                SonyFrame.HEADER -> return ReadResult.Resync(j)
                SonyFrame.ESCAPE -> {
                    if (j + 1 >= n) return ReadResult.NeedMore // escape split across chunks
                    body.add((buf[j + 1].toInt() and 0xFF) or 0x10)
                    j += 2
                }
                else -> {
                    body.add(b)
                    j++
                }
            }
        }
        return ReadResult.NeedMore
    }

    /** Returns the message, or null if the body is malformed / checksum-invalid. */
    private fun buildMessage(body: List<Int>): SonyMessage? {
        // Minimum: type + seq + 4 length bytes + checksum = 7 bytes.
        if (body.size < 7) return null
        val type = body[0]
        val seq = body[1]
        val len = (body[2] shl 24) or (body[3] shl 16) or (body[4] shl 8) or body[5]
        // Declared length must exactly account for the body we unescaped.
        if (len < 0 || body.size != 6 + len + 1) return null

        var sum = 0
        for (k in 0 until body.size - 1) sum += body[k]
        if ((sum and 0xFF) != body[body.size - 1]) return null

        val payload = ByteArray(len) { body[6 + it].toByte() }
        return SonyMessage(type, seq, payload)
    }
}
