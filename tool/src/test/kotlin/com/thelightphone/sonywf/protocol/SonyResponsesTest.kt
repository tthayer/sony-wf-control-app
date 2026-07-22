package com.thelightphone.sonywf.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonyCommandsAndResponsesTest {

    // ---- AncMode -> payload mapping ---------------------------------------

    @Test
    fun ancSetOffEncodesNcOffAmbientOff() {
        val p = SonyCommands.ancSet(AncMode.OFF, level = 10, voicePassthrough = false)
        // [0x68, 0x17, drag=1, ncOn=0, ambientOn=0, voice=0, level=10]
        assertContentEquals(byteArrayOf(0x68, 0x17, 0x01, 0x00, 0x00, 0x00, 0x0a), p)
    }

    @Test
    fun ancSetAncEncodesNcOnAmbientOff() {
        val p = SonyCommands.ancSet(AncMode.ANC, level = 0, voicePassthrough = false)
        assertContentEquals(byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x00, 0x00, 0x00), p)
    }

    @Test
    fun ancSetAmbientEncodesNcOnAmbientOnWithVoiceAndLevel() {
        val p = SonyCommands.ancSet(AncMode.AMBIENT, level = 15, voicePassthrough = true)
        assertContentEquals(byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x01, 0x01, 0x0f), p)
    }

    @Test
    fun ancSetClampsLevelToRange() {
        val high = SonyCommands.ancSet(AncMode.AMBIENT, level = 99, voicePassthrough = false)
        assertEquals(20, high[6].toInt() and 0xFF)
        val low = SonyCommands.ancSet(AncMode.AMBIENT, level = -5, voicePassthrough = false)
        assertEquals(0, low[6].toInt() and 0xFF)
    }

    @Test
    fun getAncStatusPayload() {
        assertContentEquals(byteArrayOf(0x66, 0x17), SonyCommands.getAncStatus())
    }

    @Test
    fun getBatteryPayloads() {
        assertContentEquals(byteArrayOf(0x22, 0x01), SonyCommands.getBattery(SonyCommands.BATTERY_TARGET_HEADPHONES))
        assertContentEquals(byteArrayOf(0x22, 0x0a), SonyCommands.getBattery(SonyCommands.BATTERY_TARGET_CASE))
    }

    // ---- Response parsing --------------------------------------------------

    @Test
    fun parseAncStatusAmbient() {
        // [type=0x67, 0x17, ?, ncOn=1, ambientOn=1, voice=0, level=15]
        val status = SonyResponses.parseAncStatus(byteArrayOf(0x67, 0x17, 0x01, 0x01, 0x01, 0x00, 0x0f))
        assertEquals(AncStatus(AncMode.AMBIENT, voicePassthrough = false, ambientLevel = 15), status)
    }

    @Test
    fun parseAncStatusOffAndAncAndVoice() {
        val off = SonyResponses.parseAncStatus(byteArrayOf(0x67, 0x17, 0x01, 0x00, 0x00, 0x00, 0x00))
        assertEquals(AncMode.OFF, off!!.mode)

        val anc = SonyResponses.parseAncStatus(byteArrayOf(0x67, 0x17, 0x01, 0x01, 0x00, 0x01, 0x00))
        assertEquals(AncMode.ANC, anc!!.mode)
        assertTrue(anc.voicePassthrough)
    }

    @Test
    fun parseBatteryHeadphones() {
        // [type=0x23, sub=0x01, left=70, _, right=80, _]
        val battery = SonyResponses.parseBattery(byteArrayOf(0x23, 0x01, 70, 0x00, 80, 0x00))
        assertEquals(BatteryStatus(BatteryTarget.HEADPHONES, left = 70, right = 80), battery)
    }

    @Test
    fun parseBatteryCase() {
        val battery = SonyResponses.parseBattery(byteArrayOf(0x23, 0x0a, 90))
        assertEquals(BatteryStatus(BatteryTarget.CASE, level = 90), battery)
    }

    @Test
    fun parseDispatchesAncNotifyAndBatteryNotify() {
        val ancNotify = SonyMessage(SonyFrame.TYPE_COMMAND1, 0, byteArrayOf(0x69, 0x17, 0x01, 0x01, 0x01, 0x00, 0x0f))
        val ancEvent = SonyResponses.parse(ancNotify)
        assertTrue(ancEvent is SonyEvent.Anc)
        assertEquals(AncMode.AMBIENT, (ancEvent as SonyEvent.Anc).status.mode)

        val batteryNotify = SonyMessage(SonyFrame.TYPE_COMMAND1, 1, byteArrayOf(0x25, 0x0a, 55))
        val batteryEvent = SonyResponses.parse(batteryNotify)
        assertTrue(batteryEvent is SonyEvent.Battery)
        assertEquals(55, (batteryEvent as SonyEvent.Battery).status.level)
    }

    @Test
    fun parseReturnsNullForUnknownPayload() {
        assertNull(SonyResponses.parse(SonyMessage(SonyFrame.TYPE_COMMAND1, 0, byteArrayOf(0x99, 0x00))))
        assertNull(SonyResponses.parse(SonyMessage(SonyFrame.TYPE_COMMAND1, 0, ByteArray(0))))
    }
}
