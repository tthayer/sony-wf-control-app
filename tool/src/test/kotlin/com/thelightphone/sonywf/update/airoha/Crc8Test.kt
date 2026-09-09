package com.thelightphone.sonywf.update.airoha

import kotlin.test.Test
import kotlin.test.assertEquals

/** Vectors from spec-airoha-mt28xx-single §7.1, computed from `x8/a.java`. */
class Crc8Test {

    @Test
    fun emptyInputIsZero() {
        assertEquals(0x00, Crc8.of(ByteArray(0)))
    }

    @Test
    fun specVectors() {
        assertEquals(0x00, Crc8.of(ByteArray(256)))
        assertEquals(0xB4, Crc8.of(ByteArray(256) { 0xFF.toByte() }))
        assertEquals(0xA9, Crc8.of(ByteArray(256) { it.toByte() }))
        assertEquals(0x8C, Crc8.of(byteArrayOf(0x01)))
        assertEquals(0x45, Crc8.of("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun initValueIsHonoured() {
        // The Java class takes the init in its constructor; FOTA always uses 0,
        // so a non-zero init must NOT silently behave like zero.
        assertEquals(0x8C, Crc8.of(byteArrayOf(0x01), init = 0x00))
        assertEquals(Crc8.of(byteArrayOf(0x00), init = 0x01), Crc8.of(byteArrayOf(0x01), init = 0x00))
    }

    @Test
    fun eachCallStartsFromTheInitAgain() {
        // The Java accumulator is mutated by the finalisation, so it is not
        // incremental; this API is a pure function and must repeat exactly.
        val page = ByteArray(256) { (it * 7).toByte() }
        assertEquals(Crc8.of(page), Crc8.of(page))
    }
}
