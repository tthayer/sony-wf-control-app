package com.thelightphone.sonywf.protocol

/** Noise-cancelling mode exposed to the UI. */
enum class AncMode { OFF, ANC, AMBIENT }

/**
 * Builders for the Sony command payloads this tool supports. Each function
 * returns the raw payload bytes (payload[0] is the Sony command id); wrap them
 * with [SonyFrame.encode] to produce a wire frame.
 *
 * Pure: no transport, no coroutines, no `android.*`. EQ/codec commands are out
 * of scope and intentionally omitted.
 */
object SonyCommands {
    // ANC / ambient sound control.
    const val ANC_SET = 0x68
    const val ANC_STATUS_GET = 0x66
    const val SUPPORTS_AMBIENT_SOUND_CONTROL_2 = 0x17

    // Battery.
    const val GET_BATTERY_STATUS = 0x22
    const val BATTERY_TARGET_HEADPHONES = 0x01
    const val BATTERY_TARGET_CASE = 0x0a

    /**
     * Build the 7-byte `AncSet` payload:
     * `[0x68, 0x17, drag, ncOn, ambientOn, voicePassthrough, ambientLevel]`.
     *
     * Mode encoding:
     *  - [AncMode.OFF]     -> ncOn=0, ambientOn=0
     *  - [AncMode.ANC]     -> ncOn=1, ambientOn=0
     *  - [AncMode.AMBIENT] -> ncOn=1, ambientOn=1
     *
     * @param liveDrag `false` (the default) emits `drag=1`, a committed change.
     *   `true` emits `drag=0`, used only while a slider is being dragged live.
     * @param level ambient level, only meaningful in [AncMode.AMBIENT];
     *   coerced into `0..20`.
     */
    fun ancSet(
        mode: AncMode,
        level: Int,
        voicePassthrough: Boolean,
        liveDrag: Boolean = false,
    ): ByteArray {
        val ncOn: Int
        val ambientOn: Int
        when (mode) {
            AncMode.OFF -> { ncOn = 0; ambientOn = 0 }
            AncMode.ANC -> { ncOn = 1; ambientOn = 0 }
            AncMode.AMBIENT -> { ncOn = 1; ambientOn = 1 }
        }
        val drag = if (liveDrag) 0 else 1
        val voice = if (voicePassthrough) 1 else 0
        val clampedLevel = level.coerceIn(0, 20)
        return byteArrayOf(
            ANC_SET.toByte(),
            SUPPORTS_AMBIENT_SOUND_CONTROL_2.toByte(),
            drag.toByte(),
            ncOn.toByte(),
            ambientOn.toByte(),
            voice.toByte(),
            clampedLevel.toByte(),
        )
    }

    /** `[0x66, 0x17]` — request the current ANC/ambient status. */
    fun getAncStatus(): ByteArray =
        byteArrayOf(ANC_STATUS_GET.toByte(), SUPPORTS_AMBIENT_SOUND_CONTROL_2.toByte())

    /**
     * `[0x22, target]` — request battery for [BATTERY_TARGET_HEADPHONES] (0x01)
     * or [BATTERY_TARGET_CASE] (0x0a).
     */
    fun getBattery(target: Int): ByteArray =
        byteArrayOf(GET_BATTERY_STATUS.toByte(), target.toByte())
}
