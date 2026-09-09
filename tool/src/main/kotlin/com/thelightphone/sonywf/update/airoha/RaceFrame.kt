package com.thelightphone.sonywf.update.airoha

/**
 * Airoha (MediaTek) RACE wire framing, as used by the FOTA transport on the
 * separate secure RFCOMM socket. See docs/protocol/spec-airoha-fota.md §2.
 *
 * This file is intentionally PURE: Kotlin/JVM stdlib only (no `android.*`, no
 * coroutines), so the framing is trivially unit-testable.
 *
 * ```
 *   byte 0     : 0x05 OR-ed with a flag byte (0x00 normal, 0x10 session)
 *   byte 1     : type (0x5A cmd, 0x5B rsp, 0x5D notify)
 *   bytes 2..3 : length, LITTLE-endian = 2 + payload.size (the race id counts)
 *   bytes 4..5 : race id, LITTLE-endian
 *   bytes 6..  : payload
 * ```
 * There is NO checksum: the device's own reader accepts a frame on byte0/type
 * validity alone (spec §2), so this decoder does the same.
 */
object RaceFrame {
    const val FLAG_NONE = 0x00
    const val FLAG_SESSION = 0x10

    const val TYPE_CMD = 0x5A
    const val TYPE_RSP = 0x5B
    const val TYPE_NOTIFY = 0x5D

    /** Start-of-frame / channel byte, OR-ed with the flag to make byte 0. */
    const val SOF = 0x05

    /** Header size: byte0 + type + LE16 len + LE16 race id. */
    const val HEADER_SIZE = 6

    /**
     * Largest declared length the decoder will believe. Frames are at most one
     * SPP write unit (1100 bytes, spec §1.3); anything past 4096 is a desync,
     * not a giant frame, so we resynchronise instead of stalling forever.
     */
    const val MAX_LENGTH = 4096

    fun encode(flag: Int, type: Int, raceId: Int, payload: ByteArray): ByteArray {
        val len = 2 + payload.size // race id counts toward the declared length
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = ((flag or SOF) and 0xFF).toByte()
        out[1] = (type and 0xFF).toByte()
        out[2] = (len and 0xFF).toByte()
        out[3] = ((len ushr 8) and 0xFF).toByte()
        out[4] = (raceId and 0xFF).toByte()
        out[5] = ((raceId ushr 8) and 0xFF).toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** True for the two byte-0 values the device accepts (spec §2, `z6/a.java:255-270`). */
    fun isFrameStart(b: Int): Boolean = b == SOF || b == (SOF or FLAG_SESSION)

    fun isKnownType(b: Int): Boolean = b == TYPE_CMD || b == TYPE_RSP || b == TYPE_NOTIFY
}

/**
 * A decoded RACE frame.
 *
 * [equals]/[hashCode] treat [payload] by content (like `SonyMessage`) so value
 * comparisons work in tests.
 */
class RaceMessage(
    val flag: Int,
    val type: Int,
    val raceId: Int,
    val payload: ByteArray,
) {
    /**
     * The frame exactly as it appeared on the wire. Diagnostics log complete raw
     * hex, and the Airoha response layouts are documented as `rx[n]` offsets
     * into the whole frame rather than into the payload.
     */
    val frame: ByteArray
        get() = RaceFrame.encode(flag, type, raceId, payload)

    /** Complete lowercase hex of [frame]; never truncated. */
    val hex: String
        get() = frame.joinToString(" ") { "%02x".format(it) }

    /** `rx[index]` as an unsigned int, or -1 when the frame is shorter than that. */
    fun rx(index: Int): Int {
        if (index < RaceFrame.HEADER_SIZE) {
            val header = frame
            return if (index < 0 || index >= header.size) -1 else header[index].toInt() and 0xFF
        }
        val p = index - RaceFrame.HEADER_SIZE
        return if (p >= payload.size) -1 else payload[p].toInt() and 0xFF
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RaceMessage) return false
        return flag == other.flag &&
            type == other.type &&
            raceId == other.raceId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = flag
        result = 31 * result + type
        result = 31 * result + raceId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RaceMessage(flag=0x${"%02x".format(flag)}, type=0x${"%02x".format(type)}, " +
            "raceId=0x${"%04x".format(raceId)}, payload=${payload.joinToString(" ") { "%02x".format(it) }})"
}

/**
 * Streaming RACE deframer. Chunk boundaries are NOT frame boundaries, so [feed]
 * buffers whatever it cannot yet complete and reprocesses it next call.
 *
 * Resynchronisation: any byte that is not a valid byte-0, or that is a valid
 * byte-0 not followed by a known type, or a header whose declared length is
 * out of range, is dropped and the scan continues one byte later.
 *
 * Not thread-safe: feed from a single consumer.
 */
class RaceDecoder {
    private var buffer = ByteArray(0)

    /** Bytes discarded as noise / failed resyncs; useful when debugging a link. */
    var droppedBytes: Int = 0
        private set

    fun feed(chunk: ByteArray): List<RaceMessage> {
        buffer = if (buffer.isEmpty()) chunk.copyOf() else buffer + chunk

        val out = ArrayList<RaceMessage>()
        val n = buffer.size
        var pos = 0

        loop@ while (pos < n) {
            if (!RaceFrame.isFrameStart(buffer[pos].toInt() and 0xFF)) {
                pos++
                droppedBytes++
                continue
            }
            // Need the type byte before we can tell a real header from noise.
            if (pos + 1 >= n) break@loop
            if (!RaceFrame.isKnownType(buffer[pos + 1].toInt() and 0xFF)) {
                pos++
                droppedBytes++
                continue
            }
            if (pos + RaceFrame.HEADER_SIZE > n) break@loop

            val len = (buffer[pos + 2].toInt() and 0xFF) or ((buffer[pos + 3].toInt() and 0xFF) shl 8)
            if (len < 2 || len > RaceFrame.MAX_LENGTH) {
                pos++
                droppedBytes++
                continue
            }
            // len counts the race id, so the frame ends len bytes after byte 3.
            val end = pos + 4 + len
            if (end > n) break@loop

            val raceId = (buffer[pos + 4].toInt() and 0xFF) or ((buffer[pos + 5].toInt() and 0xFF) shl 8)
            out.add(
                RaceMessage(
                    flag = (buffer[pos].toInt() and 0xFF) and 0xF0,
                    type = buffer[pos + 1].toInt() and 0xFF,
                    raceId = raceId,
                    payload = buffer.copyOfRange(pos + RaceFrame.HEADER_SIZE, end),
                ),
            )
            pos = end
        }

        buffer = if (pos >= n) ByteArray(0) else buffer.copyOfRange(pos, n)
        return out
    }
}
