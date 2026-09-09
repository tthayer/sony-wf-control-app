package com.thelightphone.sonywf.update.airoha

/**
 * CRC-8 exactly as Airoha's `x8/a.java` computes it for the 0x0402 page record
 * (spec-airoha-mt28xx-single §7.1).
 *
 * Parameters: width 8, poly 0x31 MSB-first, refin false, xorout 0x00, and a
 * FULL 8-bit reversal of the accumulator as the finalisation step (`refout`).
 * The init value is the constructor argument in the Java; FOTA always uses 0.
 *
 * The lookup table is generated rather than transcribed: the spec quotes both
 * the 256 table bytes and the MSB-first generator they come from, and
 * generating removes any transcription risk. `Crc8Test` pins the result with
 * the spec's vectors.
 */
object Crc8 {
    private const val POLY = 0x31

    private val TABLE = IntArray(256) { index ->
        var c = index
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor POLY) and 0xFF else (c shl 1) and 0xFF }
        c
    }

    /** Nibble-reverse pairs from `x8/a.java:16`; the two halves compose a byte reversal. */
    private val NIBBLE_REVERSE = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)

    fun of(data: ByteArray, init: Int = 0): Int {
        var c = init and 0xFF
        for (byte in data) c = TABLE[c xor (byte.toInt() and 0xFF)]
        return reverse8(c)
    }

    private fun reverse8(v: Int): Int =
        (NIBBLE_REVERSE[v shr 4] or (NIBBLE_REVERSE[v and 0x0F] shl 4)) and 0xFF
}
