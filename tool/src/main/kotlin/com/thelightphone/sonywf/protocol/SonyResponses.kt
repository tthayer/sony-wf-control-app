package com.thelightphone.sonywf.protocol

/**
 * Flat, fully-adaptive battery snapshot. Any field that is `null` was not
 * reported by the device (or the corresponding side is absent). Merging several
 * battery replies (single / dual / case) into one of these lets the UI render
 * whatever the connected device actually exposes without hardcoding a model.
 */
data class SonyBattery(
    val single: Int? = null, // whole-headset battery (e.g. WH over-ear)
    val left: Int? = null, //   earbud L
    val right: Int? = null, //  earbud R
    val case: Int? = null, //   charging case
) {
    val hasAny: Boolean get() = single != null || left != null || right != null || case != null

    /** Overlay the non-null fields of [other] onto this snapshot. */
    fun mergedWith(other: SonyBattery): SonyBattery = SonyBattery(
        single = other.single ?: single,
        left = other.left ?: left,
        right = other.right ?: right,
        case = other.case ?: case,
    )
}

/**
 * Parsed ANC/ambient status reported by the device. [noiseAdaptive] and
 * [adaptiveSensitivity] are the raw 0x19-layout bytes (null on other layouts),
 * kept so a later set can echo them.
 */
data class AncStatus(
    val mode: AncMode,
    val voicePassthrough: Boolean,
    val ambientLevel: Int,
    val noiseAdaptive: Int? = null,
    val adaptiveSensitivity: Int? = null,
)

/** A decoded, high-level event produced from a [SonyMessage] for a given dialect. */
sealed interface SonyEvent {
    data class Anc(val status: AncStatus) : SonyEvent
    data class Battery(val battery: SonyBattery) : SonyEvent
    data class Firmware(val version: String) : SonyEvent
    data class ModelName(val name: String) : SonyEvent
    data class SupportFunctions(val functions: Set<Int>) : SonyEvent
}

/**
 * Parsers for the reply/notify payloads this tool understands, for BOTH
 * dialects. `payload` is the already-unescaped, checksum-validated payload from
 * a [SonyMessage]; `payload[0]` is the response opcode.
 *
 * Pure: no transport, no coroutines, no `android.*`.
 */
object SonyResponses {
    // ---- Response opcodes (payload[0]) -------------------------------------

    // ANC status: solicited reply (0x67) and unsolicited notify (0x69), both dialects.
    const val ANC_RET = 0x67
    const val ANC_NOTIFY = 0x69

    // Battery, V1: reply 0x11 / notify 0x13.
    const val V1_BATTERY_RET = 0x11
    const val V1_BATTERY_NOTIFY = 0x13

    // Battery, V2: reply 0x23 / notify 0x25.
    const val V2_BATTERY_RET = 0x23
    const val V2_BATTERY_NOTIFY = 0x25

    // Firmware reply (0x05); paired with sub 0x02. The same opcode with sub 0x01
    // is the model-name reply, hence the sub-byte check in both parsers.
    const val FIRMWARE_RET = 0x05

    /** CONNECT_RET_SUPPORT_FUNCTION opcode (V2 only). */
    const val SUPPORT_FUNCTION_RET = 0x07

    /** The Init reply is a Command1 whose payload[0] is this marker. */
    const val INIT_REPLY_MARKER = 0x01

    // ---- Predicates --------------------------------------------------------

    fun isBatteryReply(dialect: SonyDialect, opcode: Int): Boolean = when (dialect) {
        SonyDialect.V1 -> opcode == V1_BATTERY_RET || opcode == V1_BATTERY_NOTIFY
        SonyDialect.V2 -> opcode == V2_BATTERY_RET || opcode == V2_BATTERY_NOTIFY
    }

    fun isAncReply(opcode: Int): Boolean = opcode == ANC_RET || opcode == ANC_NOTIFY

    fun isFirmwareReply(opcode: Int): Boolean = opcode == FIRMWARE_RET

    fun isSupportFunctionReply(opcode: Int): Boolean = opcode == SUPPORT_FUNCTION_RET

    // ---- Init / dialect detection ------------------------------------------

    /**
     * Detect the dialect from an Init reply payload. A valid Init reply has
     * `payload[0] == 0x01`; the payload length then names the dialect: 4 -> V1,
     * 8 -> V2. Returns null if this is not a recognisable Init reply.
     */
    fun parseInitReplyDialect(payload: ByteArray): SonyDialect? {
        if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != INIT_REPLY_MARKER) return null
        return when (payload.size) {
            4 -> SonyDialect.V1
            8 -> SonyDialect.V2
            else -> null
        }
    }

    // ---- Battery -----------------------------------------------------------

    /**
     * The kind of a battery reply, derived from its type byte (`payload[1]`).
     * Public so callers (e.g. the client) can distinguish a DUAL reply from a
     * SINGLE/CASE reply even when both per-bud levels are `0` (and therefore
     * dropped to null by [parseBattery]). This is the model-agnostic signal that
     * a device has a charging case: only devices that report per-bud (DUAL)
     * batteries have one. Note DUAL2 (V2 0x01) maps to [DUAL].
     */
    enum class SonyBatteryKind { SINGLE, DUAL, CASE }

    private fun batteryKind(dialect: SonyDialect, type: Int): SonyBatteryKind? = when (dialect) {
        SonyDialect.V1 -> when (type) {
            SonyCommands.V1_BATTERY_TYPE_SINGLE -> SonyBatteryKind.SINGLE
            SonyCommands.V1_BATTERY_TYPE_DUAL -> SonyBatteryKind.DUAL
            SonyCommands.V1_BATTERY_TYPE_CASE -> SonyBatteryKind.CASE
            else -> null
        }
        SonyDialect.V2 -> when (type) {
            SonyCommands.V2_BATTERY_TYPE_SINGLE -> SonyBatteryKind.SINGLE // 0x00
            SonyCommands.V2_BATTERY_TYPE_DUAL2 -> SonyBatteryKind.DUAL //    0x01
            SonyCommands.V2_BATTERY_TYPE_DUAL -> SonyBatteryKind.DUAL //     0x09
            SonyCommands.V2_BATTERY_TYPE_CASE -> SonyBatteryKind.CASE //     0x0a
            else -> null
        }
    }

    /**
     * Return the [SonyBatteryKind] of a battery reply/notify payload from its
     * type byte (`payload[1]`), using the same mapping as [parseBattery]
     * (DUAL2 -> DUAL). Returns null if the payload is too short (< 2) or the type
     * is unknown for the dialect.
     */
    fun batteryReplyKind(dialect: SonyDialect, payload: ByteArray): SonyBatteryKind? {
        if (payload.size < 2) return null
        return batteryKind(dialect, payload[1].toInt() and 0xFF)
    }

    /**
     * Parse a battery reply/notify payload into the fields it reports. `payload[1]`
     * is the type byte, interpreted per [dialect]:
     *  - SINGLE: `single = payload[2]`.
     *  - CASE:   `case = payload[2]`.
     *  - DUAL:   `left = payload[2]`, `right = payload[4]`; a side whose level is
     *    `0` is treated as ABSENT and left null.
     *
     * Returns null if the payload is too short or the type is unknown for the
     * dialect. Fields not described by this reply stay null so the result can be
     * merged into a running [SonyBattery] via [SonyBattery.mergedWith].
     */
    fun parseBattery(dialect: SonyDialect, payload: ByteArray): SonyBattery? {
        if (payload.size < 2) return null
        val type = payload[1].toInt() and 0xFF
        return when (batteryKind(dialect, type)) {
            SonyBatteryKind.SINGLE -> {
                if (payload.size < 3) return null
                SonyBattery(single = payload[2].toInt() and 0xFF)
            }
            SonyBatteryKind.CASE -> {
                if (payload.size < 3) return null
                SonyBattery(case = payload[2].toInt() and 0xFF)
            }
            SonyBatteryKind.DUAL -> {
                if (payload.size < 5) return null
                val left = payload[2].toInt() and 0xFF
                val right = payload[4].toInt() and 0xFF
                SonyBattery(
                    left = if (left == 0) null else left,
                    right = if (right == 0) null else right,
                )
            }
            null -> null
        }
    }

    // ---- ANC ---------------------------------------------------------------

    /**
     * Parse an ANC status reply/notify payload for the given dialect. Returns
     * null if the payload does not match the dialect's expected layout.
     *
     * WIND is folded into [AncMode.AMBIENT] since the UI is a 3-mode model.
     */
    fun parseAnc(dialect: SonyDialect, payload: ByteArray): AncStatus? = when (dialect) {
        SonyDialect.V2 -> parseAncV2(payload)
        SonyDialect.V1 -> parseAncV1(payload)
    }

    private fun parseAncV2(payload: ByteArray): AncStatus? {
        if (payload.size == 9) return parseAncV2Adaptive(payload)
        if (payload.size != 7 && payload.size != 8) return null
        val sub = payload[1].toInt() and 0xFF
        if (sub != SonyCommands.V2_ANC_SUB_STANDARD && sub != SonyCommands.V2_ANC_SUB_WIND) return null
        val includesWind = sub == SonyCommands.V2_ANC_SUB_WIND && payload.size > 7
        val enable = payload[3].toInt() and 0xFF
        val ambientOrNc = payload[4].toInt() and 0xFF
        val windByte = if (includesWind) payload[5].toInt() and 0xFF else -1
        val mode = when {
            enable == 0 -> AncMode.OFF
            includesWind && (windByte == 0x03 || windByte == 0x05) -> AncMode.AMBIENT // WIND -> AMBIENT
            ambientOrNc == 0 -> AncMode.ANC // 0 = NoiseCancel on V2
            else -> AncMode.AMBIENT //         1 = Ambient on V2
        }
        val voice = (payload[if (includesWind) 6 else 5].toInt() and 0xFF) == 1
        val level = payload[if (includesWind) 7 else 6].toInt() and 0xFF
        return AncStatus(mode, voice, level)
    }

    /**
     * Sub 0x19 (`rf0/g.java`): `[2]` value-change status, `[3]` NC/ASM on,
     * `[4]` 0 NC / 1 ASM, `[5]` 0 normal / 1 voice, `[6]` level, `[7]` noise
     * adaptive / Auto Ambient Sound (0 off / 1 on), `[8]` sensitivity (0..2). Range checks match
     * Sony's validator `rf0/g.f`.
     */
    private fun parseAncV2Adaptive(payload: ByteArray): AncStatus? {
        fun u(i: Int) = payload[i].toInt() and 0xFF
        if (u(1) != SonyCommands.V2_ANC_SUB_ADAPTIVE) return null
        if (u(3) > 1 || u(4) > 1 || u(5) > 1 || u(7) > 1 || u(8) > 2) return null
        val mode = when {
            u(3) == 0 -> AncMode.OFF
            u(4) == 0 -> AncMode.ANC
            else -> AncMode.AMBIENT
        }
        return AncStatus(mode, u(5) == 1, u(6), noiseAdaptive = u(7), adaptiveSensitivity = u(8))
    }

    private fun parseAncV1(payload: ByteArray): AncStatus? {
        if (payload.size < 8) return null
        val enable = payload[2].toInt() and 0xFF
        val windSel = payload[3].toInt() and 0xFF
        val modeByte = payload[4].toInt() and 0xFF
        val mode = when {
            enable == 0 -> AncMode.OFF
            windSel == 0x02 -> when (modeByte) { // wind layout
                0 -> AncMode.AMBIENT
                1 -> AncMode.AMBIENT // WIND -> AMBIENT
                else -> AncMode.ANC //  2 -> NoiseCancel
            }
            else -> when (modeByte) { // non-wind layout (INVERTED vs V2)
                0 -> AncMode.AMBIENT
                else -> AncMode.ANC // 1 -> NoiseCancel
            }
        }
        val voice = (payload[6].toInt() and 0xFF) == 1
        val level = payload[7].toInt() and 0xFF
        return AncStatus(mode, voice, level)
    }

    // ---- Firmware ----------------------------------------------------------

    /**
     * Parse a firmware reply `{0x05, 0x02, len, ASCII...}` into the version
     * string, or null if the payload is malformed. Deliberately rejects the
     * model-name reply `{0x05, 0x01, ...}`, which shares the opcode.
     */
    fun parseFirmware(payload: ByteArray): String? =
        parseDeviceInfoString(payload, SonyCommands.FIRMWARE_SUB)

    /**
     * Parse a model-name reply `{0x05, 0x01, len, ASCII...}`, or null if the
     * payload is malformed (or is the firmware reply `05 02 ...`).
     */
    fun parseModelName(payload: ByteArray): String? =
        parseDeviceInfoString(payload, SonyCommands.DEVICE_INFO_MODEL_SUB)

    /** `{0x05, sub, len, ASCII...}` — the shared CONNECT_RET_DEVICE_INFO shape. */
    private fun parseDeviceInfoString(payload: ByteArray, sub: Int): String? {
        if (payload.size < 3) return null
        if ((payload[0].toInt() and 0xFF) != FIRMWARE_RET) return null
        if ((payload[1].toInt() and 0xFF) != sub) return null
        val len = payload[2].toInt() and 0xFF
        val end = minOf(3 + len, payload.size)
        if (end <= 3) return null
        val bytes = payload.copyOfRange(3, end)
        return String(bytes, Charsets.US_ASCII)
    }

    // ---- Support functions -------------------------------------------------

    /**
     * Parse `{0x07, table, count, {functionType, capabilityCounter} * count}`
     * into the set of advertised table-1 function bytes. `0x00` (NO_USE) is
     * dropped. Returns null unless the declared count exactly accounts for the
     * payload. The table byte is not checked: it echoes the request and no
     * other table is ever queried here.
     */
    fun parseSupportFunctions(payload: ByteArray): Set<Int>? {
        if (payload.size < 3) return null
        if ((payload[0].toInt() and 0xFF) != SUPPORT_FUNCTION_RET) return null
        val count = payload[2].toInt() and 0xFF
        if (payload.size != 3 + 2 * count) return null
        val out = LinkedHashSet<Int>(count)
        for (i in 0 until count) {
            val fn = payload[3 + 2 * i].toInt() and 0xFF
            if (fn != 0x00) out.add(fn)
        }
        return out
    }

    // ---- Dispatcher --------------------------------------------------------

    /**
     * Dispatch a device message to the matching parser for [dialect], or null if
     * unrecognised. The Init reply is intentionally NOT a [SonyEvent] here — the
     * client handles dialect detection separately.
     */
    fun parse(dialect: SonyDialect, message: SonyMessage): SonyEvent? {
        val payload = message.payload
        if (payload.isEmpty()) return null
        val opcode = payload[0].toInt() and 0xFF
        return when {
            isAncReply(opcode) -> parseAnc(dialect, payload)?.let { SonyEvent.Anc(it) }
            isBatteryReply(dialect, opcode) -> parseBattery(dialect, payload)?.let { SonyEvent.Battery(it) }
            // 0x05 carries both the version (sub 0x02) and the model name (sub 0x01).
            isFirmwareReply(opcode) ->
                parseFirmware(payload)?.let { SonyEvent.Firmware(it) }
                    ?: parseModelName(payload)?.let { SonyEvent.ModelName(it) }
            isSupportFunctionReply(opcode) -> parseSupportFunctions(payload)?.let { SonyEvent.SupportFunctions(it) }
            else -> null
        }
    }
}
