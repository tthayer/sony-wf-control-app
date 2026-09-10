package com.thelightphone.sonywf.update.airoha

/**
 * RACE ids, device states, mode bytes and timing constants for the MT2822 /
 * MT2833 single-device FOTA flow (spec-airoha-mt28xx-single §3.0).
 */
object AirohaRace {
    // ---- Race ids ---------------------------------------------------------

    /** libcommon READ NVKEY; used only for the chip-name handshake (§1.2). */
    const val READ_NVKEY = 0x0A00
    const val NVKEY_CHIP_NAME = 0x1002

    /** Max read length libcommon always asks for: 0x03E8 = 1000. */
    const val NVKEY_READ_LEN = 0x03E8

    const val WRITE_FLASH = 0x0402
    const val ERASE = 0x0404
    const val COMPARE = 0x0431
    const val GET_ERASE_STATUS = 0x0433
    const val GET_BATTERY = 0x0CD6
    const val INQUIRY_FOTA = 0x1C00
    const val CHECK_INTEGRITY = 0x1C01
    const val COMMIT = 0x1C02
    const val CANCEL = 0x1C03
    const val QUERY_STATE = 0x1C04
    const val WRITE_STATE = 0x1C06
    const val GET_VERSION = 0x1C07
    const val FOTA_START = 0x1C08
    const val START_TRANSACTION = 0x1C0A
    const val PING = 0x1C1B
    const val QUERY_TRANSMIT_INTERVAL = 0x1C1C

    // ---- Device states (0x1C04 / 0x1C06 LE16) -----------------------------

    /** Idle; the only pre-transfer state the library treats as "start fresh" (§2.3). */
    const val STATE_IDLE = 0x0101
    const val STATE_ERASING = 0x0200
    const val STATE_WRITING = 0x0201
    const val STATE_VERIFYING = 0x0210

    /** Image fully written; the final 0x1C04 must return exactly this (§3.11). */
    const val STATE_WRITTEN = 0x0211

    /** States a transfer may legally start from: idle, or a resumable 0x02xx. */
    val RESUMABLE_STATES = setOf(STATE_IDLE, STATE_ERASING, STATE_WRITING, STATE_VERIFYING, STATE_WRITTEN)

    // ---- 0x1C08 target / mode bytes (§3.2) --------------------------------

    const val TARGET_SINGLE = 0x01
    const val TARGET_DUAL = 0x03

    const val MODE_BACKGROUND = 0x00
    const val MODE_ACTIVE = 0x01

    /** MT2855 only; this client refuses that chip, so it is never sent. */
    const val MODE_ADAPTIVE = 0x02

    // ---- Roles / misc -----------------------------------------------------

    /** Single (non-TWS) device: role 0 everywhere (§2). */
    const val ROLE_SINGLE = 0x00

    /** Constant first byte of a host-initiated 0x1C03 cancel (§5.1). */
    const val CANCEL_PREFIX = 0x07

    const val CANCEL_REASON_USER = 0x00
    const val CANCEL_REASON_STAGE_ERROR = 0x01
    const val CANCEL_REASON_RETRY_EXHAUSTED = 0x02

    /**
     * `status & 0x80` = "device busy" (§6.2). Only ADAPTIVE mode ever strips it;
     * MT2822/MT2833 never run adaptive, so on this path a status with the high
     * bit set is simply a non-zero (failure) status. Kept for the diagnostics
     * decoder and for tests that emulate the byte.
     */
    const val BUSY_BIT = 0x80

    // ---- Timing (§6.3) ----------------------------------------------------

    /** Every FOTA stage command: 9000 ms, at most 3 sends. */
    const val TIMEOUT_MS = 9000L
    const val ATTEMPTS = 3

    /** The cancel stage overrides the timeout to 3000 ms (`f8/c.java:20`). */
    const val CANCEL_TIMEOUT_MS = 3000L

    /**
     * How long to wait for the reboot to drop the RACE socket after a commit was
     * accepted. The library waits 15 s for that "disconnected event" (`u7/d.java`
     * `w0()`); a real MT2822S can take longer, and the drop is informational
     * only, so this is generous and never fatal.
     */
    const val COMMIT_DISCONNECT_WAIT_MS = 20_000L

    /** libcommon chip-name read: 1000 ms, `mMaxRetry = 2` => 3 sends (§1.3). */
    const val CHIP_NAME_TIMEOUT_MS = 1000L
    const val CHIP_NAME_ATTEMPTS = 3

    /** Long-packet pacing installed by `k0(200)` in background mode (§6.6). */
    const val PACING_BACKGROUND_MS = 200L

    /** Commands concatenated into one write in background mode (§6.5). */
    const val COMMANDS_PER_PACKET = 3

    /** Active mode sends one command per write. */
    const val COMMANDS_PER_PACKET_ACTIVE = 1

    /** Active-mode window, and the cap on outstanding long-packet commands (§6.4). */
    const val MAX_OUTSTANDING = 4

    /** Retransmit a command whose packet index is this far behind the newest (§6.5). */
    const val RESEND_LAG = 3

    /**
     * Cap on lag-driven retransmits of ONE page. A lag resend means the ack has
     * not caught up with the pipeline yet, not that the device is failing, so
     * the cap is deliberately generous: the real failure detector is the
     * [TIMEOUT_MS] no-ack stall watchdog.
     */
    const val MAX_LAG_RESENDS = 20
}

/**
 * Byte-exact payload builders for every stage of the single-device flow. These
 * return PAYLOADS; [RaceFrame.encode] adds byte0/type/length/race id.
 */
object AirohaRequests {
    /** READ NVKEY 0x1002, max length 1000 (§1.2): `02 10 E8 03`. */
    fun readChipName(): ByteArray = byteArrayOf(
        (AirohaRace.NVKEY_CHIP_NAME and 0xFF).toByte(),
        ((AirohaRace.NVKEY_CHIP_NAME ushr 8) and 0xFF).toByte(),
        (AirohaRace.NVKEY_READ_LEN and 0xFF).toByte(),
        ((AirohaRace.NVKEY_READ_LEN ushr 8) and 0xFF).toByte(),
    )

    /** 0x1C00 `{partition id}` (§3.1). */
    fun inquiryFota(partitionId: Int = 0x00): ByteArray = byteArrayOf(partitionId.toByte())

    /** 0x1C04, no payload (§2.3). */
    fun queryState(): ByteArray = ByteArray(0)

    /** 0x1C08 `{target, mode}` (§3.2). */
    fun fotaStart(mode: Int, target: Int = AirohaRace.TARGET_SINGLE): ByteArray =
        byteArrayOf(target.toByte(), mode.toByte())

    /** 0x1C1C `{count, role}` (§3.3). */
    fun queryTransmitInterval(role: Int = AirohaRace.ROLE_SINGLE): ByteArray =
        byteArrayOf(0x01, role.toByte())

    /** 0x0433 `{storageType, role, addr LE32, len LE32}` (§3.4). */
    fun getEraseStatus(storageType: Int, addr: Int, length: Int, role: Int = AirohaRace.ROLE_SINGLE): ByteArray =
        region(storageType, role, addr, length)

    /** 0x0431 — identical field order to 0x0433 (§3.5). */
    fun compare(storageType: Int, addr: Int, length: Int, role: Int = AirohaRace.ROLE_SINGLE): ByteArray =
        region(storageType, role, addr, length)

    /** 0x1C0A, no payload (§3.6). */
    fun startTransaction(): ByteArray = ByteArray(0)

    /** 0x1C06 `{state LE16}` (§3.7). */
    fun writeState(state: Int): ByteArray =
        byteArrayOf((state and 0xFF).toByte(), ((state ushr 8) and 0xFF).toByte())

    /** 0x0404 `{storageType, len LE32 = 4096, addr LE32}` — length BEFORE address (§3.8). */
    fun erase(storageType: Int, addr: Int): ByteArray {
        val out = ByteArray(9)
        out[0] = storageType.toByte()
        FlashPlan.putLe32(out, 1, FlashPlan.SECTOR)
        FlashPlan.putLe32(out, 5, addr)
        return out
    }

    /** 0x0402 `{storageType, pageCount, record * pageCount}` (§3.9). */
    fun writeFlash(storageType: Int, records: List<ByteArray>): ByteArray {
        require(records.isNotEmpty()) { "0x0402 needs at least one record" }
        require(records.all { it.size == FlashPlan.RECORD }) { "record must be ${FlashPlan.RECORD} bytes" }
        val out = ByteArray(2 + records.size * FlashPlan.RECORD)
        out[0] = storageType.toByte()
        out[1] = records.size.toByte()
        records.forEachIndexed { i, record -> record.copyInto(out, 2 + i * FlashPlan.RECORD) }
        return out
    }

    /** 0x1C01 `{count, role, storageType}` (§3.10). */
    fun checkIntegrity(storageType: Int, role: Int = AirohaRace.ROLE_SINGLE): ByteArray =
        byteArrayOf(0x01, role.toByte(), storageType.toByte())

    /** 0x1C02 `{00}` (§4.1). */
    fun commit(): ByteArray = byteArrayOf(0x00)

    /** 0x1C03 `{07, target, reason}` (§5.1). */
    fun cancel(reason: Int, target: Int = AirohaRace.TARGET_SINGLE): ByteArray =
        byteArrayOf(AirohaRace.CANCEL_PREFIX.toByte(), target.toByte(), reason.toByte())

    private fun region(storageType: Int, role: Int, addr: Int, length: Int): ByteArray {
        val out = ByteArray(10)
        out[0] = storageType.toByte()
        out[1] = role.toByte()
        FlashPlan.putLe32(out, 2, addr)
        FlashPlan.putLe32(out, 6, length)
        return out
    }
}
