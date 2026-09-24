package com.thelightphone.sonywf.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

/** Replies are the live WF-1000XM6 (fw 1.6.0) bytes from 2026-09-24. */
class SonySettingsTest {

    @Test
    fun onOffSettingsDecodeRetAndNotifyAndBuildSets() {
        val s = SonySettingsCatalog.PAUSE_WHEN_REMOVED
        assertEquals(0, s.optionIndex(bytes(0xf7, 0x01, 0x00))) // On
        assertEquals(1, s.optionIndex(bytes(0xf9, 0x01, 0x01))) // notify: Off
        assertNull(s.optionIndex(bytes(0xf7, 0x0c, 0x00))) // other sub
        assertContentEquals(bytes(0xf8, 0x01, 0x01), s.setPayload(s.options[1]))
        assertContentEquals(bytes(0xf6, 0x01), s.getPayload())
    }

    @Test
    fun speakToChatAppendsTheFixedSecondByte() {
        val s = SonySettingsCatalog.SPEAK_TO_CHAT
        assertEquals(1, s.optionIndex(bytes(0xf7, 0x0c, 0x01, 0x01))) // Off
        assertContentEquals(bytes(0xf8, 0x0c, 0x00, 0x01), s.setPayload(s.options[0]))
    }

    @Test
    fun bgmOffIgnoresRoomAndRoomsMatchExactly() {
        val s = SonySettingsCatalog.BGM
        assertEquals("Off", s.options[s.optionIndex(bytes(0xe7, 0x09, 0x01, 0x02))!!].label)
        assertEquals("Cafe", s.options[s.optionIndex(bytes(0xe9, 0x09, 0x00, 0x02))!!].label)
        assertContentEquals(bytes(0xe8, 0x09, 0x00, 0x00), s.setPayload(s.options[1]))
    }

    @Test
    fun powerSaveAndConnectionAppendControlByte() {
        val ps = SonySettingsCatalog.POWER_SAVE
        assertEquals("Off", ps.options[ps.optionIndex(bytes(0x27, 0x0b, 0x01, 0x01))!!].label)
        assertContentEquals(bytes(0x28, 0x0b, 0x00, 0x00), ps.setPayload(ps.options[0]))
        val cq = SonySettingsCatalog.CONNECTION_QUALITY
        assertEquals("Stable", cq.options[cq.optionIndex(bytes(0xe7, 0x05, 0x01))!!].label)
        assertContentEquals(bytes(0xe8, 0x05, 0x00, 0x00), cq.setPayload(cq.options[0]))
    }

    @Test
    fun eqOptionsComeFromTheCapabilityAndSelectWithZeroBands() {
        val s = SonySettingsCatalog.EQ
        val cap = bytes(
            0x51, 0x04, 0x0a, 0x0d, 0x09,
            0x00, 0x00, 0x30, 0x00, 0x31, 0x00, 0x32, 0x00, 0x33, 0x00, 0x20, 0x00, 0xa0, 0x00, 0xa1, 0x00, 0xa2, 0x00,
        )
        val options = SonySettingsCatalog.optionsFromCapability(s, cap)!!
        assertEquals(
            listOf("Off", "Heavy", "Clear", "Hard", "Soft", "Gaming", "Manual", "Custom 1", "Custom 2"),
            options.map { it.label },
        )
        // RET carries the id then the band steps; only the id selects.
        assertEquals(1, s.optionIndex(bytes(0x57, 0x04, 0x30, 0x0a, 0x0a, 0x0a, 0x0a, 0x05, 0x05, 0x06, 0x06, 0x06, 0x06, 0x06, 0x06), options))
        assertContentEquals(bytes(0x58, 0x04, 0x30, 0x00), s.setPayload(options[1]))
        // A truncated capability is rejected rather than half-read.
        assertNull(SonySettingsCatalog.optionsFromCapability(s, cap.copyOf(cap.size - 1)))
    }

    @Test
    fun autoPowerOffAndVoiceAssistantOptionsFromCapability() {
        val apo = SonySettingsCatalog.optionsFromCapability(
            SonySettingsCatalog.AUTO_POWER_OFF, bytes(0x21, 0x05, 0x02, 0x10, 0x11),
        )!!
        assertEquals(listOf("When removed", "Never"), apo.map { it.label })
        assertContentEquals(bytes(0x28, 0x05, 0x11, 0x00), SonySettingsCatalog.AUTO_POWER_OFF.setPayload(apo[1]))
        val va = SonySettingsCatalog.optionsFromCapability(
            SonySettingsCatalog.VOICE_ASSISTANT, bytes(0xf1, 0x04, 0x03, 0x04, 0x34, 0x30, 0x31, 0xff),
        )!!
        assertEquals(listOf("Sony", "Phone", "Google", "None"), va.map { it.label })
        assertEquals(1, SonySettingsCatalog.VOICE_ASSISTANT.optionIndex(bytes(0xf7, 0x04, 0x30), va))
    }

    @Test
    fun generalSettingSlotsResolveByCapabilityTitle() {
        val cap = bytes(0xd1, 0xd2, 0x00, 0x01, 0x12) + "MULTIPOINT_SETTING".toByteArray() +
            bytes(0x05) + "SUMRY".toByteArray()
        assertEquals("MULTIPOINT_SETTING", SonySettingsCatalog.generalSettingTitle(cap))
        val s = SonySettingsCatalog.generalSetting(0xd2, "MULTIPOINT_SETTING")!!
        assertEquals(0, s.optionIndex(bytes(0xd7, 0xd2, 0x00, 0x00))) // On
        assertContentEquals(bytes(0xd8, 0xd2, 0x00, 0x01), s.setPayload(s.options[1]))
        assertNull(SonySettingsCatalog.generalSetting(0xd4, "TWS_ONE_SIDE_USE_NCASM_SETTING"))
    }

    @Test
    fun playbackParsers() {
        assertEquals(31, SonyPlayback.parseCapability(bytes(0xa1, 0x01, 0x1f, 0x10)))
        assertEquals(24, SonyPlayback.parseVolume(bytes(0xa7, 0x20, 0x18)))
        assertEquals(23, SonyPlayback.parseVolume(bytes(0xa9, 0x20, 0x17)))
        assertNull(SonyPlayback.parseVolume(bytes(0xa7, 0x21, 0x06))) // call volume
        assertTrue(SonyPlayback.parsePlaying(bytes(0xa3, 0x01, 0x00, 0x01, 0x00))!!)
        assertFalse(SonyPlayback.parsePlaying(bytes(0xa5, 0x01, 0x00, 0x02, 0x00))!!)
        assertEquals("", SonyPlayback.parseTrack(bytes(0xa7, 0x01, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00)))
        val playing = bytes(0xa9, 0x01, 0x02, 0x02) + "Hi".toByteArray() + bytes(0x02, 0x01) + "A".toByteArray() +
            bytes(0x02, 0x03) + "Art".toByteArray() + bytes(0x01, 0x00)
        assertEquals("Hi · Art", SonyPlayback.parseTrack(playing))
        assertContentEquals(bytes(0xa4, 0x01, 0x00, 0x07), SonyPlayback.control(SonyPlayback.PLAY))
        assertContentEquals(bytes(0xa8, 0x20, 0x17), SonyPlayback.volumeSet(23))
    }
}
