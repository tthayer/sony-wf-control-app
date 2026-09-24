package com.thelightphone.sonywf.protocol

/** Noise-cancelling mode exposed to the UI (3-mode model). */
enum class AncMode { OFF, ANC, AMBIENT }

/**
 * The two mutually-incompatible Sony wire dialects this tool speaks. The dialect
 * is discovered at connect time from the length of the Init reply payload
 * (see [SonyResponses.parseInitReplyDialect]) and then selects the correct
 * opcodes and buffer layouts for every subsequent command.
 */
enum class SonyDialect { V1, V2 }

/**
 * Builders for the Sony command payloads this tool supports, for BOTH protocol
 * dialects. Each function returns the raw payload bytes (payload[0] is the Sony
 * command id); wrap them with [SonyFrame.encode] to produce a wire frame.
 *
 * Pure: no transport, no coroutines, no `android.*`. EQ/codec commands are out
 * of scope and intentionally omitted. Every builder is a deterministic
 * transform over its arguments so it can be unit-tested directly.
 */
object SonyCommands {
    // ---- Init --------------------------------------------------------------

    /** Init handshake payload: Command1 with `{0x00, 0x00}`. */
    val INIT_PAYLOAD = byteArrayOf(0x00, 0x00)

    // ---- Battery opcodes (payload[0]) --------------------------------------

    /** V1 battery status request opcode. */
    const val V1_BATTERY_GET = 0x10

    /** V2 battery status request opcode. */
    const val V2_BATTERY_GET = 0x22

    // ---- Battery type bytes (payload[1]) -----------------------------------
    // V1 dialect.
    const val V1_BATTERY_TYPE_SINGLE = 0x00
    const val V1_BATTERY_TYPE_DUAL = 0x01
    const val V1_BATTERY_TYPE_CASE = 0x02

    // V2 dialect.
    const val V2_BATTERY_TYPE_SINGLE = 0x00
    const val V2_BATTERY_TYPE_DUAL = 0x09
    const val V2_BATTERY_TYPE_DUAL2 = 0x01
    const val V2_BATTERY_TYPE_CASE = 0x0a

    // ---- ANC opcodes / sub-bytes -------------------------------------------

    /** ANC set opcode (both dialects). */
    const val ANC_SET = 0x68

    /** ANC status get opcode (both dialects). */
    const val ANC_GET = 0x66

    /** V1 ANC sub-byte (payload[1]). */
    const val V1_ANC_SUB = 0x02

    /** V2 ANC sub-byte for the plain (non-wind) layout, 7-byte set buffer. */
    const val V2_ANC_SUB_STANDARD = 0x15

    /** V2 ANC sub-byte for wind-capable / ASC2 models, 8-byte set buffer. */
    const val V2_ANC_SUB_WIND = 0x17

    /**
     * V2 ANC sub-byte `MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA`
     * (WF-1000XM6), 9-byte buffer with noise-adaptive fields (`rf0/g.java`).
     * The XM6 answers a 0x17 GET with an all-zero payload, so this must be
     * probed first.
     */
    const val V2_ANC_SUB_ADAPTIVE = 0x19

    /**
     * Auto Ambient Sound (noise adaptive) OFF for the 0x19 layout. Byte [7] is
     * an NcAsmOnOffValue (OFF 0x00, ON 0x01; `rf0/g.java:26`), not the
     * NoiseAdaptiveMode enum (ON 0x00) used by Adaptive Sound Control.
     */
    const val NOISE_ADAPTIVE_OFF = 0x00
    const val NOISE_ADAPTIVE_ON = 0x01

    // ---- Firmware ----------------------------------------------------------

    const val FIRMWARE_GET = 0x04
    const val FIRMWARE_SUB = 0x02

    // ---- Device info / support functions (V2 dialect only) -----------------

    /** CONNECT_GET_SUPPORT_FUNCTION opcode. */
    const val SUPPORT_FUNCTION_GET = 0x06

    /** `FIRMWARE_GET` sub-byte that asks for the model name instead of the version. */
    const val DEVICE_INFO_MODEL_SUB = 0x01

    /**
     * Battery types to probe, in order, for the given dialect. Devices silently
     * ignore unsupported types (there is no NAK in this protocol), so callers
     * simply send each in turn and merge whatever replies arrive.
     */
    fun batteryTypesFor(dialect: SonyDialect): IntArray = when (dialect) {
        SonyDialect.V2 -> intArrayOf(
            V2_BATTERY_TYPE_SINGLE, // 0x00
            V2_BATTERY_TYPE_DUAL, //   0x09
            V2_BATTERY_TYPE_DUAL2, //  0x01
            V2_BATTERY_TYPE_CASE, //   0x0a
        )
        SonyDialect.V1 -> intArrayOf(
            V1_BATTERY_TYPE_SINGLE, // 0x00
            V1_BATTERY_TYPE_DUAL, //   0x01
            V1_BATTERY_TYPE_CASE, //   0x02
        )
    }

    /** `[opcode, type]` — request battery status for [type] in the given dialect. */
    fun batteryGet(dialect: SonyDialect, type: Int): ByteArray {
        val opcode = when (dialect) {
            SonyDialect.V1 -> V1_BATTERY_GET
            SonyDialect.V2 -> V2_BATTERY_GET
        }
        return byteArrayOf(opcode.toByte(), type.toByte())
    }

    /** `[0x66, subByte]` — request the current ANC/ambient status. */
    fun ancGet(dialect: SonyDialect, subByte: Int): ByteArray =
        byteArrayOf(ANC_GET.toByte(), subByte.toByte())

    /**
     * Build the dialect-specific `AncSet` payload.
     *
     * V2 (sub `0x15` plain / `0x17` wind):
     * ```
     * [0]=0x68 [1]=sub [2]=0x01(committed) [3]=(mode!=OFF?1:0) [4]=(mode==AMBIENT?1:0)
     * (wind only) [5]=0x02  [.]=voice?1:0  [.]=level(0..20)
     * ```
     * Length is 8 when [wind], else 7. (Our 3-mode UI never selects the dedicated
     * wind mode, so the wind byte is the plain-ambient `0x02`.)
     *
     * V1 (sub `0x02`, always 8 bytes):
     * ```
     * [0]=0x68 [1]=0x02 [2]=(mode==OFF?0x00:0x11) [3]=(wind?0x02:0x00)
     * [4]= if wind: (mode==ANC?2:0) else: (mode==ANC?1:0)
     * [5]=0x01 [6]=voice?1:0 [7]=level(0..20)
     * ```
     * Note byte-4 is INVERTED between the dialects: on V2 `0=NoiseCancel/1=Ambient`
     * is folded into `[3]/[4]`, whereas V1 encodes the mode directly in `[4]`.
     *
     * V2 sub `0x19` (9 bytes, `pf0/d1.java`, `rf0/g.java`):
     * ```
     * [0]=0x68 [1]=0x19 [2]=0x01(changed) [3]=(mode!=OFF?1:0) [4]=(mode==AMBIENT?1:0)
     * [5]=voice?1:0 [6]=level [7]=noiseAdaptive [8]=adaptiveSensitivity
     * ```
     * [noiseAdaptive] and [adaptiveSensitivity] are echoed from the last status
     * so a mode change does not reset the device's noise-adaptive setting.
     *
     * @param level ambient level; coerced into `0..20`.
     */
    fun ancSet(
        dialect: SonyDialect,
        subByte: Int,
        wind: Boolean,
        mode: AncMode,
        level: Int,
        voice: Boolean,
        noiseAdaptive: Int = NOISE_ADAPTIVE_OFF,
        adaptiveSensitivity: Int = 0,
    ): ByteArray {
        val voiceByte = if (voice) 1 else 0
        val levelByte = level.coerceIn(0, 20)
        return when (dialect) {
            SonyDialect.V2 -> {
                val ncByte = if (mode == AncMode.OFF) 0 else 1
                val ambientByte = if (mode == AncMode.AMBIENT) 1 else 0
                if (subByte == V2_ANC_SUB_ADAPTIVE) {
                    byteArrayOf(
                        ANC_SET.toByte(),
                        subByte.toByte(),
                        0x01,
                        ncByte.toByte(),
                        ambientByte.toByte(),
                        voiceByte.toByte(),
                        levelByte.toByte(),
                        noiseAdaptive.toByte(),
                        adaptiveSensitivity.toByte(),
                    )
                } else if (wind) {
                    byteArrayOf(
                        ANC_SET.toByte(),
                        subByte.toByte(),
                        0x01,
                        ncByte.toByte(),
                        ambientByte.toByte(),
                        0x02, // dedicated wind mode (0x03) is never selected by the 3-mode UI
                        voiceByte.toByte(),
                        levelByte.toByte(),
                    )
                } else {
                    byteArrayOf(
                        ANC_SET.toByte(),
                        subByte.toByte(),
                        0x01,
                        ncByte.toByte(),
                        ambientByte.toByte(),
                        voiceByte.toByte(),
                        levelByte.toByte(),
                    )
                }
            }
            SonyDialect.V1 -> {
                val enableByte = if (mode == AncMode.OFF) 0x00 else 0x11
                val windByte = if (wind) 0x02 else 0x00
                val modeByte = if (wind) {
                    if (mode == AncMode.ANC) 2 else 0 // AMBIENT -> 0 (dedicated wind mode 1 never selected)
                } else {
                    if (mode == AncMode.ANC) 1 else 0
                }
                byteArrayOf(
                    ANC_SET.toByte(),
                    V1_ANC_SUB.toByte(),
                    enableByte.toByte(),
                    windByte.toByte(),
                    modeByte.toByte(),
                    0x01,
                    voiceByte.toByte(),
                    levelByte.toByte(),
                )
            }
        }
    }

    /** `[0x04, 0x02]` — request the firmware version string (both dialects). */
    fun firmwareGet(): ByteArray =
        byteArrayOf(FIRMWARE_GET.toByte(), FIRMWARE_SUB.toByte())

    /** `[0x06, 0x00]` — request the table-1 support-function list (V2 only). */
    fun supportFunctionGet(): ByteArray =
        byteArrayOf(SUPPORT_FUNCTION_GET.toByte(), 0x00)

    /** `[0x04, 0x01]` — request the model-name string (V2 only). */
    fun modelNameGet(): ByteArray =
        byteArrayOf(FIRMWARE_GET.toByte(), DEVICE_INFO_MODEL_SUB.toByte())
}
