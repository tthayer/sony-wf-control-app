package com.thelightphone.sonywf.protocol

/**
 * Table-1 (v2) device settings beyond NC/ASM, as data. Each setting is read
 * with a GET_PARAM, answered by RET_PARAM and pushed by NTFY_PARAM, all with
 * the same `[opcode, sub, value…]` body, and written with a SET_PARAM of the
 * same shape. Wire facts: docs/protocol/spec-mdr-v2-settings-sound-power.md
 * and spec-mdr-v2-settings-system-playback.md, each SET verified on a
 * WF-1000XM6 unless noted.
 *
 * Pure: no transport, no coroutines, no `android.*`.
 */

/**
 * One choice of a [SonySetting]. [value] is the bytes after `[opcode, sub]`;
 * a reply selects this option when its first [matchLen] value bytes equal
 * [value]'s (so "Off" can ignore a trailing room size, for instance).
 */
class SettingOption(
    val label: String,
    val value: ByteArray,
    val matchLen: Int = value.size,
)

class SonySetting(
    /** Stable key for UI state. */
    val key: String,
    val title: String,
    /** Table-1 FunctionType the device must advertise for this to show. */
    val function: Int,
    /** Family GET_PARAM opcode; RET = +1, SET = +2, NTFY = +3. */
    val getOpcode: Int,
    /** InquiredType byte (or general-setting slot). */
    val sub: Int,
    /** Default options; [SonySettingsCatalog.optionsFromCapability] may replace them. */
    val options: List<SettingOption>,
    /** Bytes the SET appends after the option value (e.g. Speak-to-Chat's trailing 0x01). */
    val setSuffix: ByteArray = ByteArray(0),
    /** Capability query whose reply narrows [options]; null when the list is fixed. */
    val capabilityGet: ByteArray? = null,
) {
    val retOpcode: Int get() = getOpcode + 1
    val setOpcode: Int get() = getOpcode + 2
    val ntfyOpcode: Int get() = getOpcode + 3

    fun getPayload(): ByteArray = byteArrayOf(getOpcode.toByte(), sub.toByte())

    fun setPayload(option: SettingOption): ByteArray =
        byteArrayOf(setOpcode.toByte(), sub.toByte()) + option.value + setSuffix

    /** True when [payload] is this setting's RET_PARAM or NTFY_PARAM. */
    fun matches(payload: ByteArray): Boolean {
        if (payload.size < 3) return false
        val op = payload[0].toInt() and 0xFF
        return (op == retOpcode || op == ntfyOpcode) && (payload[1].toInt() and 0xFF) == sub
    }

    /** Index into [options] selected by a RET/NTFY payload, or null if none matches. */
    fun optionIndex(payload: ByteArray, options: List<SettingOption> = this.options): Int? {
        if (!matches(payload)) return null
        val value = payload.copyOfRange(2, payload.size)
        val i = options.indexOfFirst { o ->
            o.matchLen <= value.size && (0 until o.matchLen).all { value[it] == o.value[it] }
        }
        return if (i >= 0) i else null
    }
}

/** Current state of one setting on the connected device. */
data class SettingState(
    val setting: SonySetting,
    val options: List<SettingOption>,
    /** Index into [options]; null while unknown or when the device reports an unlisted value. */
    val selected: Int?,
) {
    val valueLabel: String get() = selected?.let { options[it].label } ?: "—"
}

object SonySettingsCatalog {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // OnOffSettingValue: ON = 0x00, OFF = 0x01.
    private val ON_OFF = listOf(SettingOption("On", b(0x00)), SettingOption("Off", b(0x01)))

    // General-setting slots answer `d7 slot 00 VV`; the value that matters is [3].
    private val GS_ON_OFF = listOf(
        SettingOption("On", b(0x00, 0x00)),
        SettingOption("Off", b(0x00, 0x01)),
    )

    val DSEE = SonySetting(
        key = "dsee", title = "DSEE", function = 0xe2, getOpcode = 0xe6, sub = 0x01,
        options = listOf(SettingOption("Off", b(0x00)), SettingOption("Auto", b(0x01))),
    )

    /** Off keeps whatever room byte follows; SET Off sends room 0x02 (Sony's default). */
    val BGM = SonySetting(
        key = "bgm", title = "Background music", function = 0xeb, getOpcode = 0xe6, sub = 0x09,
        options = listOf(
            SettingOption("Off", b(0x01, 0x02), matchLen = 1),
            SettingOption("My room", b(0x00, 0x00)),
            SettingOption("Living room", b(0x00, 0x01)),
            SettingOption("Cafe", b(0x00, 0x02)),
        ),
    )

    /** Low Latency (LE Audio, 0x02) is left out: switching to LE drops this link. Not verified live. */
    val CONNECTION_QUALITY = SonySetting(
        key = "connection", title = "Connection", function = 0xe7, getOpcode = 0xe6, sub = 0x05,
        options = listOf(
            SettingOption("Sound quality", b(0x00)),
            SettingOption("Stable", b(0x01)),
        ),
        setSuffix = b(0x00),
    )

    /** Select = `58 04 id 00` (zero bands); the RET carries the band steps after the id. */
    val EQ = SonySetting(
        key = "eq", title = "Equalizer", function = 0x57, getOpcode = 0x56, sub = 0x04,
        options = listOf(SettingOption("Off", b(0x00, 0x00), matchLen = 1)),
        capabilityGet = b(0x50, 0x04, 0x01), // 3 bytes: Sony rejects `50 04`; 0x01 = English
    )

    /** `27 05 main selectTime`; the options offered come from the `21 05` capability. */
    val AUTO_POWER_OFF = SonySetting(
        key = "autoPowerOff", title = "Auto power off", function = 0x25, getOpcode = 0x26, sub = 0x05,
        options = listOf(
            SettingOption("When removed", b(0x10, 0x00), matchLen = 1),
            SettingOption("Never", b(0x11, 0x00), matchLen = 1),
        ),
        capabilityGet = b(0x20, 0x05),
    )

    /** `27 0b setting active`; SET `28 0b V 00` (00 = leave an active save alone). */
    val POWER_SAVE = SonySetting(
        key = "powerSave", title = "Auto power save", function = 0x2b, getOpcode = 0x26, sub = 0x0b,
        options = listOf(
            SettingOption("On", b(0x00), matchLen = 1),
            SettingOption("Off", b(0x01), matchLen = 1),
        ),
        setSuffix = b(0x00),
    )

    val PAUSE_WHEN_REMOVED = SonySetting(
        key = "pauseWhenRemoved", title = "Pause when removed", function = 0xf1,
        getOpcode = 0xf6, sub = 0x01, options = ON_OFF,
    )

    /** SET `f8 0c V 01`: Sony always sends the second byte as 0x01. */
    val SPEAK_TO_CHAT = SonySetting(
        key = "speakToChat", title = "Speak-to-Chat", function = 0xfc, getOpcode = 0xf6, sub = 0x0c,
        options = listOf(
            SettingOption("On", b(0x00), matchLen = 1),
            SettingOption("Off", b(0x01), matchLen = 1),
        ),
        setSuffix = b(0x01),
    )

    val VOICE_ASSISTANT = SonySetting(
        key = "voiceAssistant", title = "Voice assistant", function = 0xf4, getOpcode = 0xf6, sub = 0x04,
        options = listOf(SettingOption("Phone", b(0x30)), SettingOption("None", b(0xff))),
        capabilityGet = b(0xf0, 0x04),
    )

    val HEAD_GESTURES = SonySetting(
        key = "headGestures", title = "Head gestures", function = 0xff, getOpcode = 0xf6, sub = 0x0f,
        options = ON_OFF,
    )

    /** General-setting slots: the slot's meaning comes from its `d1` capability title. */
    fun generalSetting(slot: Int, capabilityTitle: String): SonySetting? {
        val title = when (capabilityTitle) {
            "SIDETONE_SETTING" -> "Hear own voice on calls"
            "MULTIPOINT_SETTING" -> "Connect to 2 devices"
            else -> return null
        }
        val function = when (slot) {
            0xd1 -> 0xd1
            0xd2 -> 0xd2
            0xd3 -> 0xd3
            0xd4 -> 0xd4
            else -> return null
        }
        return SonySetting(
            key = "gs_${"%02x".format(slot)}", title = title, function = function,
            getOpcode = 0xd6, sub = slot, options = GS_ON_OFF,
        )
    }

    /** General-setting slots to ask `d0 slot 01` (title) for, in table order. */
    val GENERAL_SETTING_SLOTS = intArrayOf(0xd1, 0xd2, 0xd3, 0xd4)

    /** Fixed settings, in display order. General-setting slots are appended at runtime. */
    val ALL: List<SonySetting> = listOf(
        EQ, DSEE, BGM, SPEAK_TO_CHAT, PAUSE_WHEN_REMOVED, HEAD_GESTURES,
        VOICE_ASSISTANT, AUTO_POWER_OFF, POWER_SAVE, CONNECTION_QUALITY,
    )

    // ---- Capability replies ------------------------------------------------

    private val EQ_PRESET_LABELS = mapOf(
        0x00 to "Off", 0x01 to "Rock", 0x02 to "Pop", 0x03 to "Jazz", 0x04 to "Dance", 0x05 to "EDM",
        0x06 to "R&B/Hip Hop", 0x07 to "Acoustic",
        0x10 to "Bright", 0x11 to "Excited", 0x12 to "Mellow", 0x13 to "Relaxed", 0x14 to "Vocal",
        0x15 to "Treble boost", 0x16 to "Bass boost", 0x17 to "Speech",
        0x20 to "Gaming", 0x21 to "FPS 1", 0x22 to "FPS 2", 0x23 to "FPS 3",
        0x30 to "Heavy", 0x31 to "Clear", 0x32 to "Hard", 0x33 to "Soft",
        0xa0 to "Manual", 0xa1 to "Custom 1", 0xa2 to "Custom 2", 0xa3 to "Custom 3",
        0xa4 to "Custom 4", 0xa5 to "Custom 5",
    )

    private val AUTO_POWER_OFF_LABELS = mapOf(
        0x00 to "After 5 min", 0x01 to "After 30 min", 0x02 to "After 1 hour", 0x03 to "After 3 hours",
        0x04 to "After 15 min", 0x10 to "When removed", 0x11 to "Never",
    )

    private val VOICE_ASSISTANT_LABELS = mapOf(
        0x30 to "Phone", 0x31 to "Google", 0x32 to "Alexa", 0x34 to "Sony", 0xff to "None",
    )

    /**
     * Options derived from a capability reply for [setting], or null when the
     * reply is not that setting's capability or is malformed (callers then keep
     * the defaults).
     */
    fun optionsFromCapability(setting: SonySetting, payload: ByteArray): List<SettingOption>? {
        fun u(i: Int) = payload[i].toInt() and 0xFF
        val cap = setting.capabilityGet ?: return null
        if (payload.size < 3 || u(0) != (cap[0].toInt() and 0xFF) + 1 || u(1) != setting.sub) return null
        return when (setting.key) {
            // `51 04 bands steps count {id len name[len]}×count`
            EQ.key -> {
                if (payload.size < 5) return null
                val count = u(4)
                var pos = 5
                val out = ArrayList<SettingOption>(count)
                repeat(count) {
                    if (pos + 2 > payload.size) return null
                    val id = u(pos)
                    val len = u(pos + 1)
                    if (pos + 2 + len > payload.size) return null
                    val name = String(payload, pos + 2, len, Charsets.UTF_8)
                    val label = name.ifEmpty { EQ_PRESET_LABELS[id] ?: "Preset ${"%02x".format(id)}" }
                    out.add(SettingOption(label, byteArrayOf(id.toByte(), 0x00), matchLen = 1))
                    pos += 2 + len
                }
                if (pos != payload.size) null else out
            }
            // `21 05 N elem×N`: a timed element is sent as both main and select time.
            AUTO_POWER_OFF.key -> {
                val n = u(2)
                if (payload.size != 3 + n) return null
                (0 until n).map { i ->
                    val id = u(3 + i)
                    val selectTime = if (id <= 0x04) id else 0x00
                    SettingOption(
                        AUTO_POWER_OFF_LABELS[id] ?: "Mode ${"%02x".format(id)}",
                        byteArrayOf(id.toByte(), selectTime.toByte()),
                        matchLen = 1,
                    )
                }
            }
            // `f1 04 keyType N va×N`
            VOICE_ASSISTANT.key -> {
                if (payload.size < 4) return null
                val n = u(3)
                if (payload.size != 4 + n) return null
                (0 until n).map { i ->
                    val id = u(4 + i)
                    SettingOption(VOICE_ASSISTANT_LABELS[id] ?: "Assistant ${"%02x".format(id)}", byteArrayOf(id.toByte()))
                }
            }
            else -> null
        }
    }

    /**
     * General-setting capability `d1 slot 00 01 len title[len] len summary[len]`
     * → the title string (e.g. "MULTIPOINT_SETTING"), or null.
     */
    fun generalSettingTitle(payload: ByteArray): String? {
        if (payload.size < 5 || (payload[0].toInt() and 0xFF) != 0xd1) return null
        val len = payload[4].toInt() and 0xFF
        if (5 + len > payload.size) return null
        return String(payload, 5, len, Charsets.US_ASCII)
    }

    fun generalSettingCapabilityGet(slot: Int): ByteArray = b(0xd0, slot, 0x01)
}

/**
 * Music playback as the device reports it. [volume] is null until read;
 * [volumeMax] is the capability's step count minus one (whether the top is
 * steps or steps-1 is unconfirmed, so stay on the safe side). [track] is
 * "Track · Artist", "" when nothing is reported.
 */
data class PlaybackState(
    val volumeMax: Int,
    val volume: Int? = null,
    val playing: Boolean? = null,
    val track: String? = null,
)

/** Playback control (`a4 01 00 cmd`) and absolute volume (`a8 20 level`). */
object SonyPlayback {
    const val PAUSE = 0x01
    const val NEXT = 0x02
    const val PREVIOUS = 0x03
    const val PLAY = 0x07

    /** PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT. */
    const val FUNCTION = 0xa1

    fun control(command: Int): ByteArray = byteArrayOf(0xa4.toByte(), 0x01, 0x00, command.toByte())

    val CAPABILITY_GET = byteArrayOf(0xa0.toByte(), 0x01)
    val STATUS_GET = byteArrayOf(0xa2.toByte(), 0x01)
    val VOLUME_GET = byteArrayOf(0xa6.toByte(), 0x20)

    fun volumeSet(level: Int): ByteArray = byteArrayOf(0xa8.toByte(), 0x20, level.toByte())

    /** `a1 01 musicSteps callSteps` → music steps, or null. */
    fun parseCapability(payload: ByteArray): Int? =
        if (payload.size == 4 && (payload[0].toInt() and 0xFF) == 0xa1 && payload[1].toInt() == 0x01) {
            payload[2].toInt() and 0xFF
        } else {
            null
        }

    /** `a7 20 level` (RET) or `a9 20 level` (NTFY) → level, or null. */
    fun parseVolume(payload: ByteArray): Int? {
        if (payload.size != 3 || payload[1].toInt() != 0x20) return null
        val op = payload[0].toInt() and 0xFF
        return if (op == 0xa7 || op == 0xa9) payload[2].toInt() and 0xFF else null
    }

    /**
     * `a3 01 enable playState …` (RET) / `a5 01 …` (NTFY) → true when playing,
     * false when paused, null when not a playback status. PlaybackStatus: 00
     * unsettled, 01 playing, 02 paused (spec-mdr-v2-settings-system-playback §7).
     */
    fun parsePlaying(payload: ByteArray): Boolean? {
        if (payload.size < 4 || payload[1].toInt() != 0x01) return null
        val op = payload[0].toInt() and 0xFF
        if (op != 0xa3 && op != 0xa5) return null
        return when (payload[3].toInt() and 0xFF) {
            0x01 -> true
            0x02 -> false
            else -> null
        }
    }

    val METADATA_GET = byteArrayOf(0xa6.toByte(), 0x01)

    /**
     * `a7 01` (RET) / `a9 01` (NTFY) `{nameStatus len utf8[len]}×4` (track,
     * album, artist, genre) → "Track · Artist" from the settled names, "" when
     * nothing is playing, null when not a metadata payload.
     */
    fun parseTrack(payload: ByteArray): String? {
        if (payload.size < 2 || payload[1].toInt() != 0x01) return null
        val op = payload[0].toInt() and 0xFF
        if (op != 0xa7 && op != 0xa9) return null
        val names = ArrayList<String?>(4)
        var pos = 2
        repeat(4) {
            if (pos + 2 > payload.size) return null
            val status = payload[pos].toInt() and 0xFF
            val len = payload[pos + 1].toInt() and 0xFF
            if (pos + 2 + len > payload.size) return null
            names.add(if (status == 0x02 && len > 0) String(payload, pos + 2, len, Charsets.UTF_8) else null)
            pos += 2 + len
        }
        if (pos != payload.size) return null
        return listOfNotNull(names[0], names[2]).joinToString(" · ")
    }

    /** `24 03 01` USER_POWER_OFF; the link drops, no reply. */
    val POWER_OFF = byteArrayOf(0x24, 0x03, 0x01)
    const val POWER_OFF_FUNCTION = 0x23
}
