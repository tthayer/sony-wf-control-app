package com.thelightphone.sonywf.update.airoha

/**
 * READ-ONLY Airoha RACE probe, run once against a real device to confirm the
 * frame format and the chip family before any flashing code is written.
 *
 * Every query here is a pure read. Nothing in this file erases, writes, locks,
 * commits, resets, or opens a FOTA session, and NOTHING ELSE may be sent: in
 * particular 0x1C08 (FotaStart) is deliberately absent, so the 0x15 session
 * flag never flips (spec §2.1) and the device stays in its normal state.
 *
 * Race IDs and response layouts: docs/protocol/spec-airoha-fota.md §2.4, §3.6,
 * §3.7. Offsets are quoted as `rx[n]` into the WHOLE frame.
 */
object AirohaDiagnostics {
    /** Airoha FOTA SPP service UUID, secure RFCOMM (spec §1.2). */
    const val SPP_UUID = "8901dfa8-5c7e-4d8f-9f0c-c2b70683f5f0"

    /** READ NVKEY, generic (spec §2.4). Payload `keyLo keyHi 0xE8 0x03`. */
    const val RACE_READ_NVKEY = 0x0A00

    /** NVKEY 4098 = 0x1002 holds the chip name (`CommonStageReadChipName:18`). */
    const val NVKEY_CHIP_NAME = 0x1002

    /** 0x03E8 = 1000, the max-read length libcommon always asks for. */
    private const val NVKEY_READ_LEN = 0x03E8

    const val RACE_QUERY_STATE = 0x1C04
    const val RACE_GET_VERSION = 0x1C07
    const val RACE_GET_BATTERY = 0x0CD6
    const val RACE_INQUIRY_FOTA = 0x1C00

    /** Single-device role byte used by the MT2833 pre-flow queries (spec §3.7). */
    private const val ROLE_SINGLE: Byte = 0x00

    /**
     * Run the five read-only queries in order and return a flat report: for each
     * query a `tx:` line, an `rx:` line (complete raw hex, never truncated) and,
     * when a reply arrived, a best-effort parsed line.
     */
    suspend fun run(client: RaceClient): List<String> {
        val lines = ArrayList<String>()

        probe(
            client, lines,
            name = "chipname",
            raceId = RACE_READ_NVKEY,
            payload = byteArrayOf(
                (NVKEY_CHIP_NAME and 0xFF).toByte(),
                ((NVKEY_CHIP_NAME ushr 8) and 0xFF).toByte(),
                (NVKEY_READ_LEN and 0xFF).toByte(),
                ((NVKEY_READ_LEN ushr 8) and 0xFF).toByte(),
            ),
            parse = ::parseChipName,
        )
        probe(client, lines, "querystate", RACE_QUERY_STATE, ByteArray(0), ::parseState)
        probe(client, lines, "getversion", RACE_GET_VERSION, byteArrayOf(ROLE_SINGLE), ::parseVersion)
        probe(client, lines, "getbattery", RACE_GET_BATTERY, byteArrayOf(ROLE_SINGLE), ::parseBattery)
        probe(client, lines, "inquiryfota", RACE_INQUIRY_FOTA, byteArrayOf(0x00), ::parsePartition)

        return lines
    }

    private suspend fun probe(
        client: RaceClient,
        lines: MutableList<String>,
        name: String,
        raceId: Int,
        payload: ByteArray,
        parse: (RaceMessage) -> String,
    ) {
        val tx = RaceFrame.encode(RaceFrame.FLAG_NONE, RaceFrame.TYPE_CMD, raceId, payload)
        lines.add("$name tx: ${hex(tx)}")
        val rx = client.request(raceId, payload)
        if (rx == null) {
            lines.add("$name rx: no reply")
            return
        }
        lines.add("$name rx: ${rx.hex}")
        lines.add("$name: ${runCatching { parse(rx) }.getOrElse { "unparsed (${it.message})" }}")
    }

    // ---- Parsers -----------------------------------------------------------

    /**
     * libcommon READ NVKEY reply: returned length at `rx[6..7]`, data from
     * `rx[8]` (`CommonStageReadChipName.java:31-42`). Note the length is read
     * `d.f(rx[7], rx[6])` = `(rx[7] << 8) | rx[6]`, i.e. LITTLE-endian over the
     * pair despite `d.f`'s big-endian name. The declared length is reported
     * alongside a printable-ASCII scan so the layout can be verified either way.
     */
    private fun parseChipName(rx: RaceMessage): String {
        val declared = ((rx.rx(7) and 0xFF) shl 8) or (rx.rx(6) and 0xFF)
        val data = rx.payload.drop(2).toByteArray() // rx[8..]
        val byLength = if (declared in 1..data.size) ascii(data.copyOfRange(0, declared)) else null
        return buildString {
            append("rx[6..7] len=$declared")
            if (byLength != null) append(", rx[8..] name='$byLength'")
            append(", ascii='${printableRun(rx.payload)}'")
        }
    }

    /** 0x1C04 QueryState, single device: 16-bit state at `rx[7..8]`, LE (spec §3.7). */
    private fun parseState(rx: RaceMessage): String {
        val state = (rx.rx(7) and 0xFF) or ((rx.rx(8) and 0xFF) shl 8)
        return "status=0x${"%02x".format(rx.rx(6))} state=0x${"%04x".format(state)}"
    }

    /** 0x1C07 GetVersion: the version is an ASCII run somewhere after the role byte. */
    private fun parseVersion(rx: RaceMessage): String =
        "status=0x${"%02x".format(rx.rx(6))} version='${printableRun(rx.payload)}'"

    /** 0x0CD6 GetBattery: `rx[7]` role, `rx[8]` percent (spec §3.7). */
    private fun parseBattery(rx: RaceMessage): String =
        "status=0x${"%02x".format(rx.rx(6))} role=${rx.rx(7)} battery=${rx.rx(8)}%"

    /**
     * 0x1C00 InquiryFota: `rx[7]` partition id, `rx[8]` storage type,
     * `rx[9..12]` address LE32, `rx[13..16]` length LE32 (spec §3.7,
     * `g8/a.java:31-72`).
     */
    private fun parsePartition(rx: RaceMessage): String {
        val addr = le32(rx, 9)
        val len = le32(rx, 13)
        return "status=0x${"%02x".format(rx.rx(6))} partition=${rx.rx(7)} storage=${rx.rx(8)} " +
            "addr=0x${"%08x".format(addr)} len=0x${"%08x".format(len)} ($len bytes)"
    }

    private fun le32(rx: RaceMessage, at: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) {
            val b = rx.rx(at + i)
            if (b < 0) return -1
            v = (v shl 8) or b.toLong()
        }
        return v
    }

    // ---- Helpers -----------------------------------------------------------

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    private fun ascii(bytes: ByteArray): String =
        bytes.map { val c = it.toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else '.' }
            .joinToString("")
            .trim()

    /** Longest run of printable ASCII in [bytes]; empty when there is none. */
    private fun printableRun(bytes: ByteArray): String {
        var bestStart = 0
        var bestLen = 0
        var start = 0
        var len = 0
        for (i in bytes.indices) {
            val c = bytes[i].toInt() and 0xFF
            if (c in 0x20..0x7E) {
                if (len == 0) start = i
                len++
                if (len > bestLen) {
                    bestLen = len
                    bestStart = start
                }
            } else {
                len = 0
            }
        }
        if (bestLen == 0) return ""
        return String(CharArray(bestLen) { (bytes[bestStart + it].toInt() and 0xFF).toChar() }).trim()
    }
}
