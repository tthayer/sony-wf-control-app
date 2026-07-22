package com.thelightphone.sonywf.protocol

/** Which battery a [BatteryStatus] describes. */
enum class BatteryTarget { HEADPHONES, CASE }

/** Parsed ANC/ambient status reported by the buds. */
data class AncStatus(
    val mode: AncMode,
    val voicePassthrough: Boolean,
    val ambientLevel: Int,
)

/**
 * Parsed battery status.
 *
 * For [BatteryTarget.CASE] only [level] is populated ([left]/[right] are null).
 * For [BatteryTarget.HEADPHONES] only [left]/[right] are populated ([level] is
 * null).
 */
data class BatteryStatus(
    val target: BatteryTarget,
    val level: Int? = null,
    val left: Int? = null,
    val right: Int? = null,
)

/** A decoded, high-level event produced from a [SonyMessage]. */
sealed interface SonyEvent {
    data class Anc(val status: AncStatus) : SonyEvent
    data class Battery(val status: BatteryStatus) : SonyEvent
}

/**
 * Parsers for the reply/notify payloads this tool understands.
 *
 * Pure: `payload` is the already-unescaped, checksum-validated payload from a
 * [SonyMessage]. `payload[0]` is the response type id.
 */
object SonyResponses {
    // ANC status: solicited reply (0x67) and unsolicited notify (0x69).
    const val ANC_STATUS_RET = 0x67
    const val ANC_STATUS_NOTIFY = 0x69

    // Battery: solicited reply (0x23) and unsolicited notify (0x25).
    const val BATTERY_RET = 0x23
    const val BATTERY_NOTIFY = 0x25

    // Battery sub-target byte (payload[1]).
    const val BATTERY_SUB_HEADPHONES = 0x01
    const val BATTERY_SUB_HEADPHONES_ALT = 0x09
    const val BATTERY_SUB_CASE = 0x0a

    /**
     * Parse an ANC-status payload:
     * `mode = if payload[3]==0 OFF else if payload[4]==0 ANC else AMBIENT`,
     * `voicePassthrough = payload[5]==1`, `ambientLevel = payload[6]`.
     */
    fun parseAncStatus(payload: ByteArray): AncStatus? {
        if (payload.size < 7) return null
        val ncOn = payload[3].toInt() and 0xFF
        val ambientOn = payload[4].toInt() and 0xFF
        val mode = when {
            ncOn == 0 -> AncMode.OFF
            ambientOn == 0 -> AncMode.ANC
            else -> AncMode.AMBIENT
        }
        val voicePassthrough = (payload[5].toInt() and 0xFF) == 1
        val ambientLevel = payload[6].toInt() and 0xFF
        return AncStatus(mode, voicePassthrough, ambientLevel)
    }

    /**
     * Parse a battery payload. `payload[1]` selects the target:
     *  - CASE (0x0a): `level = payload[2]`.
     *  - HEADPHONES (0x01 / 0x09): `left = payload[2]`, `right = payload[4]`.
     */
    fun parseBattery(payload: ByteArray): BatteryStatus? {
        if (payload.size < 2) return null
        return when (payload[1].toInt() and 0xFF) {
            BATTERY_SUB_CASE -> {
                if (payload.size < 3) return null
                BatteryStatus(BatteryTarget.CASE, level = payload[2].toInt() and 0xFF)
            }
            BATTERY_SUB_HEADPHONES, BATTERY_SUB_HEADPHONES_ALT -> {
                if (payload.size < 5) return null
                BatteryStatus(
                    BatteryTarget.HEADPHONES,
                    left = payload[2].toInt() and 0xFF,
                    right = payload[4].toInt() and 0xFF,
                )
            }
            else -> null
        }
    }

    /** Dispatch a message to the matching parser, or null if unrecognised. */
    fun parse(message: SonyMessage): SonyEvent? {
        val payload = message.payload
        if (payload.isEmpty()) return null
        return when (payload[0].toInt() and 0xFF) {
            ANC_STATUS_RET, ANC_STATUS_NOTIFY -> parseAncStatus(payload)?.let { SonyEvent.Anc(it) }
            BATTERY_RET, BATTERY_NOTIFY -> parseBattery(payload)?.let { SonyEvent.Battery(it) }
            else -> null
        }
    }
}
