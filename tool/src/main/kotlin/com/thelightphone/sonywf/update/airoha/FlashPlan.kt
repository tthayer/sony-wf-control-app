package com.thelightphone.sonywf.update.airoha

import java.security.MessageDigest

/** 0x1C00 InquiryFota reply fields (spec-airoha-mt28xx-single §3.1). */
data class PartitionInfo(
    val id: Int,
    val storageType: Int,
    val addr: Int,
    val length: Int,
)

/**
 * Splits a firmware image the way the Airoha stages do: 4096-byte sectors from
 * the device-reported partition address, 256-byte pages inside each sector, and
 * the 261-byte page record 0x0402 carries
 * (spec-airoha-mt28xx-single §3.4, §3.9).
 *
 * Pure: arithmetic plus SHA-256/CRC-8, no transport.
 */
object FlashPlan {
    const val SECTOR = 4096
    const val PAGE = 256

    /** 0x0433 / 0x0431 region size: `partialReadFlashLengthKB = 512` (§3.4). */
    const val REGION = 512 * 1024

    /** crc8 | addr LE32 | 256 data bytes (`b8/f.java:22-30`). */
    const val RECORD = 261

    /**
     * One 4096-byte flash sector.
     *
     * [data] is always [SECTOR] bytes, 0xFF-padded, so page slicing is uniform.
     * [len] is the number of image bytes that actually landed in it: the device
     * is told that length by 0x0431 and the sector digest covers exactly those
     * bytes (`stage/a.java:161-170` allocates `new byte[len]`).
     *
     * [erased] mirrors the 0x0433 bitmap ("this sector is already blank");
     * [needsWrite] is `f21584e` ("still needs programming"), cleared when a
     * 0x0431 digest matches.
     */
    class Sector(
        val addr: Int,
        val data: ByteArray,
        val len: Int = SECTOR,
        var erased: Boolean = false,
        var needsWrite: Boolean = true,
    ) {
        /** The bytes the device is asked to compare / that were read from the image. */
        val content: ByteArray get() = if (len == data.size) data else data.copyOf(len)
    }

    /** Ascending-address sectors covering [image], tail padded with 0xFF (§3.4). */
    fun sectors(image: ByteArray, partitionAddr: Int): List<Sector> {
        val out = ArrayList<Sector>((image.size + SECTOR - 1) / SECTOR)
        var offset = 0
        while (offset < image.size) {
            val len = minOf(SECTOR, image.size - offset)
            val data = ByteArray(SECTOR) { 0xFF.toByte() }
            image.copyInto(data, 0, offset, offset + len)
            out.add(Sector(partitionAddr + offset, data, len))
            offset += SECTOR
        }
        return out
    }

    /**
     * `(address, 256 bytes)` for every page of [sector] that is not entirely
     * 0xFF. All-0xFF pages are never transmitted and never counted (§3.9,
     * `x8/b.java`). Pages past [Sector.len] do not exist for the device either.
     */
    fun pages(sector: Sector): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>(SECTOR / PAGE)
        var offset = 0
        while (offset < sector.len) {
            val page = ByteArray(PAGE) { 0xFF.toByte() }
            val take = minOf(PAGE, sector.len - offset)
            sector.data.copyInto(page, 0, offset, offset + take)
            if (!isBlank(page)) out.add((sector.addr + offset) to page)
            offset += PAGE
        }
        return out
    }

    /** The 261-byte 0x0402 record: CRC-8 of the page, address LE32, page (§3.9). */
    fun record(addr: Int, page: ByteArray): ByteArray {
        require(page.size == PAGE) { "page must be $PAGE bytes" }
        val out = ByteArray(RECORD)
        out[0] = Crc8.of(page).toByte()
        putLe32(out, 1, addr)
        page.copyInto(out, 5)
        return out
    }

    /** True when every byte is 0xFF (`x8/b.java:6-13`). */
    fun isBlank(page: ByteArray): Boolean = page.all { it == 0xFF.toByte() }

    /** Plain single-shot SHA-256, as `x8/e.java` computes the compare digests. */
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun putLe32(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value ushr 8) and 0xFF).toByte()
        out[at + 2] = ((value ushr 16) and 0xFF).toByte()
        out[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun le32(out: ByteArray, at: Int): Int =
        (out[at].toInt() and 0xFF) or
            ((out[at + 1].toInt() and 0xFF) shl 8) or
            ((out[at + 2].toInt() and 0xFF) shl 16) or
            ((out[at + 3].toInt() and 0xFF) shl 24)
}
