package com.thelightphone.sonywf.update

/**
 * Builders and parsers for the `UPDT_*` payloads of the Tandem FOTA transport
 * (spec-tandem-fota §2/§3). Builders return the payload only; the caller picks
 * the frame type (Command1 for everything except `UPDT_TRANSFER_DATA`, which
 * goes out as `SonyFrame.TYPE_LARGE_DATA_MDR`).
 *
 * Pure: no transport, no coroutines, no `android.*`.
 */
object UpdtMessages {
    // ---- Opcodes (payload[0]) ---------------------------------------------

    const val UPDT_GET_CAPABILITY = 0x30
    const val UPDT_RET_CAPABILITY = 0x31
    const val UPDT_GET_STATUS = 0x32
    const val UPDT_RET_STATUS = 0x33
    const val UPDT_NTFY_STATUS = 0x35
    const val UPDT_GET_PARAM = 0x36
    const val UPDT_RET_PARAM = 0x37
    const val UPDT_SET_PARAM = 0x38
    const val UPDT_NTFY_PARAM = 0x39
    const val UPDT_TRANSFER_DATA = 0x3E
    const val UPDT_NTFY_MESSAGE = 0x3F

    // ---- UpdtInquiredType (payload[1]) ------------------------------------

    const val PART1 = 0x10
    const val PART2 = 0x11
    const val PART3 = 0x12
    const val PART4 = 0x13

    // ---- TandemFotaCommand (payload[2] for PART2/3/4) ---------------------

    const val CMD_ENTER = 0x01
    const val CMD_EXIT = 0x02
    const val CMD_START_TRANSFER = 0x03
    const val CMD_FINISH = 0x04
    const val CMD_CANCEL = 0x05
    const val CMD_EXECUTE = 0x06

    // ---- MessageType (UPDT_NTFY_MESSAGE payload[2]) -----------------------

    const val MSG_FW_UPDATE_COMPLETED = 0x01

    /** `str{n}` cap for the fwVersion / fileName fields (§3.8: the safe choice is 32). */
    const val MAX_STRING_LEN = 32

    /** Fixed number of capability features the device must report (§3.2). */
    private const val CAPABILITY_FEATURE_COUNT = 4

    /** `str{128}` cap for the RET_PARAM string chain (§3.6). */
    private const val PARAM_STRING_LEN = 128

    // ---- Builders ---------------------------------------------------------

    /** `{0x30, inq}` (§3.1). */
    fun getCapability(inq: Int): ByteArray =
        byteArrayOf(UPDT_GET_CAPABILITY.toByte(), inq.toByte())

    /** `{0x32, inq}` (§3.3). */
    fun getStatus(inq: Int): ByteArray =
        byteArrayOf(UPDT_GET_STATUS.toByte(), inq.toByte())

    /** `{0x36, inq}` (§3.6). */
    fun getParam(inq: Int): ByteArray =
        byteArrayOf(UPDT_GET_PARAM.toByte(), inq.toByte())

    /** `{0x38, 0x11, command}` — ENTER / EXIT / FINISH / CANCEL (§3.7). */
    fun setSimple(command: Int): ByteArray =
        byteArrayOf(UPDT_SET_PARAM.toByte(), PART2.toByte(), command.toByte())

    /**
     * `START_TRANSFER` (§3.8). The app always declares itself file 0 of 1.
     * [macHex] is the ASCII-hex digest string from the feed, NOT raw digest
     * bytes: 32 chars for MD5, 40 for SHA1, empty for NONE.
     *
     * @throws IllegalArgumentException if [macHex] does not match [digest], or
     *   if either string is empty.
     */
    fun startTransfer(fwVersion: String, fileName: String, digest: DigestType, macHex: String): ByteArray {
        val version = asciiCapped(fwVersion)
        val name = asciiCapped(fileName)
        require(version.isNotEmpty()) { "fwVersion must be non-empty" }
        require(name.isNotEmpty()) { "fileName must be non-empty" }
        val expectedMacLen = when (digest) {
            DigestType.NONE -> 0
            DigestType.MD5 -> 32
            DigestType.SHA1 -> 40
        }
        require(macHex.length == expectedMacLen) {
            "macHex length ${macHex.length} != $expectedMacLen for $digest"
        }

        val out = ArrayList<Byte>(8 + version.size + name.size + expectedMacLen)
        out.add(UPDT_SET_PARAM.toByte())
        out.add(PART3.toByte())
        out.add(CMD_START_TRANSFER.toByte())
        out.add(version.size.toByte())
        for (byte in version) out.add(byte)
        out.add(0x00) // fileIndex
        out.add(0x01) // numFiles
        out.add(name.size.toByte())
        for (byte in name) out.add(byte)
        out.add(digest.macType.toByte())
        out.add(expectedMacLen.toByte())
        for (byte in macHex.toByteArray(Charsets.US_ASCII)) out.add(byte)
        return out.toByteArray()
    }

    /**
     * `EXECUTE_FW_UPDATE` (§3.11) — same shape as [startTransfer] minus the
     * fileIndex and the MAC, and with the full file list.
     */
    fun execute(fwVersion: String, fileNames: List<String>): ByteArray {
        val version = asciiCapped(fwVersion)
        require(version.isNotEmpty()) { "fwVersion must be non-empty" }
        val names = fileNames.map { asciiCapped(it) }
        require(names.isNotEmpty()) { "fileNames must be non-empty" }
        require(names.none { it.isEmpty() }) { "file names must be non-empty" }

        val out = ArrayList<Byte>(5 + version.size + names.sumOf { it.size + 1 })
        out.add(UPDT_SET_PARAM.toByte())
        out.add(PART4.toByte())
        out.add(CMD_EXECUTE.toByte())
        out.add(version.size.toByte())
        for (byte in version) out.add(byte)
        out.add(names.size.toByte())
        for (name in names) {
            out.add(name.size.toByte())
            for (byte in name) out.add(byte)
        }
        return out.toByteArray()
    }

    /** `{0x3E, 0x12, u32be offset, u32be len, data}` (§3.13). */
    fun transferData(offset: Int, data: ByteArray): ByteArray {
        val out = ByteArray(10 + data.size)
        out[0] = UPDT_TRANSFER_DATA.toByte()
        out[1] = PART3.toByte()
        putU32(out, 2, offset)
        putU32(out, 6, data.size)
        data.copyInto(out, 10)
        return out
    }

    // ---- Parsers ----------------------------------------------------------

    /**
     * `UPDT_RET_CAPABILITY` PART1 (§3.2): exact length 7, `numOfFeature == 4`.
     * The PART1 sub-address is required — the MTK inquired types answer 0x31
     * with a different layout.
     */
    fun parseCapability(payload: ByteArray): UpdateCapability? {
        if (payload.size != 7) return null
        if (u8(payload, 0) != UPDT_RET_CAPABILITY) return null
        if (u8(payload, 1) != PART1) return null
        if (u8(payload, 2) != CAPABILITY_FEATURE_COUNT) return null
        return UpdateCapability(
            resumable = u8(payload, 3) == 0x01,
            tws = u8(payload, 4) == 0x01,
            backgroundTransfer = u8(payload, 5) == 0x01,
            acCheck = u8(payload, 6) == 0x01,
        )
    }

    /**
     * `UPDT_RET_PARAM` (§3.6): five `str{128}` fields, two battery thresholds,
     * then a sixth `str{128}`. The inquired type is NOT checked — the reply
     * shape is identical for every accepted sub-address.
     */
    fun parseParam(payload: ByteArray): UpdateParams? {
        if (payload.size < 3) return null
        if (u8(payload, 0) != UPDT_RET_PARAM) return null

        var pos = 2
        val strings = ArrayList<String>(5)
        repeat(5) {
            val read = readString(payload, pos) ?: return null
            strings.add(read.first)
            pos = read.second
        }
        if (pos + 2 > payload.size) return null
        val threshold = u8(payload, pos)
        val thresholdInterrupt = u8(payload, pos + 1)
        pos += 2
        val uniqueId = readString(payload, pos) ?: return null
        // Trailing bytes would mean we mis-walked the length prefixes.
        if (uniqueId.second != payload.size) return null

        return UpdateParams(
            categoryId = strings[0],
            serviceId = strings[1],
            nationCode = strings[2],
            language = strings[3],
            serialNumber = strings[4],
            batteryThreshold = threshold,
            batteryThresholdInterrupt = thresholdInterrupt,
            uniqueId = uniqueId.first,
        )
    }

    /**
     * Parse a device→app notification: `UPDT_RET_STATUS` (0x33),
     * `UPDT_NTFY_STATUS` (0x35), `UPDT_NTFY_PARAM` (0x39) for PART2/3/4, or
     * `UPDT_NTFY_MESSAGE` (0x3F). Null when the payload is not one of those.
     */
    fun parseNotify(payload: ByteArray): UpdtNotify? {
        if (payload.size < 3) return null
        return when (u8(payload, 0)) {
            UPDT_RET_STATUS, UPDT_NTFY_STATUS -> {
                if (payload.size != 3 || u8(payload, 1) != PART1) return null
                FotaStatus.fromCode(u8(payload, 2))?.let { UpdtNotify.Status(it) }
            }

            UPDT_NTFY_PARAM -> when (u8(payload, 1)) {
                PART2 -> {
                    if (payload.size != 4) return null
                    UpdtNotify.SimpleResult(u8(payload, 2), FotaResult.fromCode(u8(payload, 3)))
                }
                PART3 -> {
                    // maxPacketSize is not range-checked here: a non-OK result
                    // (e.g. NO_NEED_OF_DATA_TRANSFER) may legitimately carry 0.
                    if (payload.size != 12 || u8(payload, 2) != CMD_START_TRANSFER) return null
                    UpdtNotify.StartTransferResult(
                        result = FotaResult.fromCode(u8(payload, 3)),
                        maxPacketSize = u32(payload, 4),
                        offset = u32(payload, 8),
                    )
                }
                PART4 -> {
                    if (payload.size != 6 || u8(payload, 2) != CMD_EXECUTE) return null
                    UpdtNotify.ExecuteResult(
                        result = FotaResult.fromCode(u8(payload, 3)),
                        requiredTimeSec = (u8(payload, 4) shl 8) or u8(payload, 5),
                    )
                }
                else -> null
            }

            // Lenient on the trailing dataLength field (the app never reads the
            // data): missing the completion signal would wedge the install.
            UPDT_NTFY_MESSAGE ->
                if (u8(payload, 1) == PART1 && u8(payload, 2) == MSG_FW_UPDATE_COMPLETED) {
                    UpdtNotify.Completed
                } else {
                    null
                }

            else -> null
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun u32(b: ByteArray, i: Int): Int =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)

    private fun putU32(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v ushr 24) and 0xFF).toByte()
        b[i + 1] = ((v ushr 16) and 0xFF).toByte()
        b[i + 2] = ((v ushr 8) and 0xFF).toByte()
        b[i + 3] = (v and 0xFF).toByte()
    }

    /** ASCII bytes of [s], truncated to [MAX_STRING_LEN]. */
    private fun asciiCapped(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        return if (bytes.size <= MAX_STRING_LEN) bytes else bytes.copyOf(MAX_STRING_LEN)
    }

    /** Read one `str{128}` at [pos]; returns the value and the next offset. */
    private fun readString(b: ByteArray, pos: Int): Pair<String, Int>? {
        if (pos >= b.size) return null
        val len = u8(b, pos)
        if (len > PARAM_STRING_LEN) return null
        val end = pos + 1 + len
        if (end > b.size) return null
        return String(b, pos + 1, len, Charsets.US_ASCII) to end
    }
}
