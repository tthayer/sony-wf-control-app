package com.thelightphone.sonywf.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte helper that keeps values > 0x7F legal without littering `.toByte()`. */
private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

class SonyCommandsAndResponsesTest {

    // ---- Dialect detection from Init reply ---------------------------------

    @Test
    fun initReplyLength4IsV1AndLength8IsV2() {
        assertEquals(SonyDialect.V1, SonyResponses.parseInitReplyDialect(bytes(0x01, 0, 0, 0)))
        assertEquals(SonyDialect.V2, SonyResponses.parseInitReplyDialect(bytes(0x01, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun initReplyRejectsWrongMarkerOrLength() {
        assertNull(SonyResponses.parseInitReplyDialect(bytes(0x00, 0, 0, 0))) // not a reply marker
        assertNull(SonyResponses.parseInitReplyDialect(bytes(0x01, 0, 0))) //    length 3 -> unknown
        assertNull(SonyResponses.parseInitReplyDialect(ByteArray(0)))
    }

    // ---- Battery GET encoding ----------------------------------------------

    @Test
    fun batteryGetUsesDialectOpcode() {
        assertContentEquals(bytes(0x22, 0x09), SonyCommands.batteryGet(SonyDialect.V2, SonyCommands.V2_BATTERY_TYPE_DUAL))
        assertContentEquals(bytes(0x22, 0x0a), SonyCommands.batteryGet(SonyDialect.V2, SonyCommands.V2_BATTERY_TYPE_CASE))
        assertContentEquals(bytes(0x10, 0x00), SonyCommands.batteryGet(SonyDialect.V1, SonyCommands.V1_BATTERY_TYPE_SINGLE))
        assertContentEquals(bytes(0x10, 0x02), SonyCommands.batteryGet(SonyDialect.V1, SonyCommands.V1_BATTERY_TYPE_CASE))
    }

    @Test
    fun batteryTypesForEachDialect() {
        assertContentEquals(intArrayOf(0x00, 0x09, 0x01, 0x0a).toList(), SonyCommands.batteryTypesFor(SonyDialect.V2).toList())
        assertContentEquals(intArrayOf(0x00, 0x01, 0x02).toList(), SonyCommands.batteryTypesFor(SonyDialect.V1).toList())
    }

    // ---- Battery parsing: V2 ----------------------------------------------

    @Test
    fun parseBatteryV2Single() {
        // [ret=0x23, type=SINGLE(0x00), level=64, charging=0]
        val b = SonyResponses.parseBattery(SonyDialect.V2, bytes(0x23, 0x00, 64, 0x00))
        assertEquals(SonyBattery(single = 64), b)
    }

    @Test
    fun parseBatteryV2DualBothSidesOn0x09() {
        // [ret, type=DUAL(0x09), Llvl=70, Lchg, Rlvl=80, Rchg]
        val b = SonyResponses.parseBattery(SonyDialect.V2, bytes(0x23, 0x09, 70, 0x00, 80, 0x00))
        assertEquals(SonyBattery(left = 70, right = 80), b)
    }

    @Test
    fun parseBatteryV2Dual2On0x01() {
        val b = SonyResponses.parseBattery(SonyDialect.V2, bytes(0x23, 0x01, 55, 0x01, 60, 0x00))
        assertEquals(SonyBattery(left = 55, right = 60), b)
    }

    @Test
    fun parseBatteryV2DualSkipsZeroSide() {
        // Right level 0 => right absent (null).
        val b = SonyResponses.parseBattery(SonyDialect.V2, bytes(0x23, 0x09, 70, 0x00, 0x00, 0x00))
        assertEquals(SonyBattery(left = 70, right = null), b)
    }

    @Test
    fun parseBatteryV2Case() {
        val b = SonyResponses.parseBattery(SonyDialect.V2, bytes(0x23, 0x0a, 90, 0x01))
        assertEquals(SonyBattery(case = 90), b)
    }

    // ---- Battery parsing: V1 ----------------------------------------------

    @Test
    fun parseBatteryV1SingleDualCase() {
        assertEquals(SonyBattery(single = 42), SonyResponses.parseBattery(SonyDialect.V1, bytes(0x11, 0x00, 42, 0x00)))
        assertEquals(SonyBattery(left = 30, right = 40), SonyResponses.parseBattery(SonyDialect.V1, bytes(0x11, 0x01, 30, 0x00, 40, 0x00)))
        assertEquals(SonyBattery(case = 77), SonyResponses.parseBattery(SonyDialect.V1, bytes(0x11, 0x02, 77, 0x00)))
    }

    @Test
    fun parseBatteryUnknownTypeIsNull() {
        assertNull(SonyResponses.parseBattery(SonyDialect.V2, bytes(0x23, 0x7f, 10, 0)))
    }

    @Test
    fun batteryReplyKindMapsTypeByte() {
        // V2: 0x00 SINGLE, 0x09 DUAL, 0x01 DUAL (DUAL2 -> DUAL), 0x0a CASE.
        assertEquals(SonyResponses.SonyBatteryKind.SINGLE, SonyResponses.batteryReplyKind(SonyDialect.V2, bytes(0x23, 0x00, 64, 0x00)))
        assertEquals(SonyResponses.SonyBatteryKind.DUAL, SonyResponses.batteryReplyKind(SonyDialect.V2, bytes(0x23, 0x09, 70, 0x00, 80, 0x00)))
        assertEquals(SonyResponses.SonyBatteryKind.DUAL, SonyResponses.batteryReplyKind(SonyDialect.V2, bytes(0x23, 0x01, 55, 0x01, 60, 0x00)))
        assertEquals(SonyResponses.SonyBatteryKind.CASE, SonyResponses.batteryReplyKind(SonyDialect.V2, bytes(0x23, 0x0a, 90, 0x01)))
        // V1: 0x00 SINGLE, 0x01 DUAL, 0x02 CASE.
        assertEquals(SonyResponses.SonyBatteryKind.SINGLE, SonyResponses.batteryReplyKind(SonyDialect.V1, bytes(0x11, 0x00, 42, 0x00)))
        assertEquals(SonyResponses.SonyBatteryKind.DUAL, SonyResponses.batteryReplyKind(SonyDialect.V1, bytes(0x11, 0x01, 30, 0x00, 40, 0x00)))
        assertEquals(SonyResponses.SonyBatteryKind.CASE, SonyResponses.batteryReplyKind(SonyDialect.V1, bytes(0x11, 0x02, 77, 0x00)))
        // Unknown type and too-short payloads -> null.
        assertNull(SonyResponses.batteryReplyKind(SonyDialect.V2, bytes(0x23, 0x7f, 10, 0)))
        assertNull(SonyResponses.batteryReplyKind(SonyDialect.V2, bytes(0x23)))
    }

    @Test
    fun sonyBatteryMergePrefersNewNonNull() {
        val merged = SonyBattery(left = 70).mergedWith(SonyBattery(right = 80)).mergedWith(SonyBattery(case = 50))
        assertEquals(SonyBattery(left = 70, right = 80, case = 50), merged)
        assertTrue(merged.hasAny)
        assertTrue(!SonyBattery().hasAny)
    }

    // ---- ANC SET encoding: V2 ---------------------------------------------

    @Test
    fun ancSetV2StandardSub15Is7Bytes() {
        // AMBIENT, level 15, no voice, sub 0x15, no wind -> 7 bytes.
        val p = SonyCommands.ancSet(SonyDialect.V2, SonyCommands.V2_ANC_SUB_STANDARD, wind = false, AncMode.AMBIENT, level = 15, voice = false)
        assertContentEquals(bytes(0x68, 0x15, 0x01, 0x01, 0x01, 0x00, 0x0f), p)
    }

    @Test
    fun ancSetV2StandardAncAndOff() {
        val anc = SonyCommands.ancSet(SonyDialect.V2, 0x15, wind = false, AncMode.ANC, level = 0, voice = true)
        assertContentEquals(bytes(0x68, 0x15, 0x01, 0x01, 0x00, 0x01, 0x00), anc)
        val off = SonyCommands.ancSet(SonyDialect.V2, 0x15, wind = false, AncMode.OFF, level = 5, voice = false)
        assertContentEquals(bytes(0x68, 0x15, 0x01, 0x00, 0x00, 0x00, 0x05), off)
    }

    @Test
    fun ancSetV2WindSub17Is8BytesWithWindByte() {
        // AMBIENT, level 15, no voice, sub 0x17, wind -> 8 bytes, wind byte 0x02.
        val p = SonyCommands.ancSet(SonyDialect.V2, SonyCommands.V2_ANC_SUB_WIND, wind = true, AncMode.AMBIENT, level = 15, voice = false)
        assertContentEquals(bytes(0x68, 0x17, 0x01, 0x01, 0x01, 0x02, 0x00, 0x0f), p)
        // ANC over wind layout keeps the wind byte but ambientOn=0.
        val anc = SonyCommands.ancSet(SonyDialect.V2, 0x17, wind = true, AncMode.ANC, level = 3, voice = true)
        assertContentEquals(bytes(0x68, 0x17, 0x01, 0x01, 0x00, 0x02, 0x01, 0x03), anc)
    }

    @Test
    fun ancSetClampsLevel() {
        val hi = SonyCommands.ancSet(SonyDialect.V2, 0x15, wind = false, AncMode.AMBIENT, level = 99, voice = false)
        assertEquals(20, hi[6].toInt() and 0xFF)
        val lo = SonyCommands.ancSet(SonyDialect.V2, 0x15, wind = false, AncMode.AMBIENT, level = -5, voice = false)
        assertEquals(0, lo[6].toInt() and 0xFF)
    }

    // ---- ANC SET encoding: V1 (byte-4 inverted vs V2) ---------------------

    @Test
    fun ancSetV1NonWindLayout() {
        // AMBIENT: enable 0x11, wind 0x00, modeByte 0 (AMBIENT), fixed 0x01, voice, level.
        val amb = SonyCommands.ancSet(SonyDialect.V1, SonyCommands.V1_ANC_SUB, wind = false, AncMode.AMBIENT, level = 15, voice = false)
        assertContentEquals(bytes(0x68, 0x02, 0x11, 0x00, 0x00, 0x01, 0x00, 0x0f), amb)
        // ANC: modeByte 1 (INVERTED vs V2 where NoiseCancel==0).
        val anc = SonyCommands.ancSet(SonyDialect.V1, 0x02, wind = false, AncMode.ANC, level = 0, voice = true)
        assertContentEquals(bytes(0x68, 0x02, 0x11, 0x00, 0x01, 0x01, 0x01, 0x00), anc)
        // OFF: enable 0x00.
        val off = SonyCommands.ancSet(SonyDialect.V1, 0x02, wind = false, AncMode.OFF, level = 7, voice = false)
        assertContentEquals(bytes(0x68, 0x02, 0x00, 0x00, 0x00, 0x01, 0x00, 0x07), off)
    }

    @Test
    fun ancSetV1WindLayout() {
        // wind: byte3=0x02; AMBIENT modeByte 0, ANC modeByte 2.
        val amb = SonyCommands.ancSet(SonyDialect.V1, 0x02, wind = true, AncMode.AMBIENT, level = 10, voice = false)
        assertContentEquals(bytes(0x68, 0x02, 0x11, 0x02, 0x00, 0x01, 0x00, 0x0a), amb)
        val anc = SonyCommands.ancSet(SonyDialect.V1, 0x02, wind = true, AncMode.ANC, level = 0, voice = false)
        assertContentEquals(bytes(0x68, 0x02, 0x11, 0x02, 0x02, 0x01, 0x00, 0x00), anc)
    }

    // ---- ANC GET -----------------------------------------------------------

    @Test
    fun ancGetEncoding() {
        assertContentEquals(bytes(0x66, 0x17), SonyCommands.ancGet(SonyDialect.V2, SonyCommands.V2_ANC_SUB_WIND))
        assertContentEquals(bytes(0x66, 0x15), SonyCommands.ancGet(SonyDialect.V2, SonyCommands.V2_ANC_SUB_STANDARD))
        assertContentEquals(bytes(0x66, 0x02), SonyCommands.ancGet(SonyDialect.V1, SonyCommands.V1_ANC_SUB))
    }

    // ---- ANC parsing: V2 ---------------------------------------------------

    @Test
    fun parseAncV2StandardOffAncAmbient() {
        // sub 0x15, 7 bytes: [ret,0x15,?,enable,ambientOrNc,voice,level]
        val off = SonyResponses.parseAnc(SonyDialect.V2, bytes(0x67, 0x15, 0x01, 0x00, 0x00, 0x00, 0x00))
        assertEquals(AncMode.OFF, off!!.mode)
        // enable=1, byte4=0 => NoiseCancel(ANC) on V2.
        val anc = SonyResponses.parseAnc(SonyDialect.V2, bytes(0x67, 0x15, 0x01, 0x01, 0x00, 0x01, 0x00))
        assertEquals(AncMode.ANC, anc!!.mode)
        assertTrue(anc.voicePassthrough)
        // enable=1, byte4=1 => Ambient on V2, level read from [6].
        val amb = SonyResponses.parseAnc(SonyDialect.V2, bytes(0x67, 0x15, 0x01, 0x01, 0x01, 0x00, 0x0f))
        assertEquals(AncStatus(AncMode.AMBIENT, voicePassthrough = false, ambientLevel = 15), amb)
    }

    @Test
    fun parseAncV2WindLayoutReadsShiftedFields() {
        // sub 0x17, 8 bytes: voice at [6], level at [7].
        val amb = SonyResponses.parseAnc(SonyDialect.V2, bytes(0x67, 0x17, 0x01, 0x01, 0x01, 0x02, 0x01, 0x0c))
        assertEquals(AncStatus(AncMode.AMBIENT, voicePassthrough = true, ambientLevel = 12), amb)
        // Dedicated wind byte 0x03 folds into AMBIENT for the 3-mode UI.
        val wind = SonyResponses.parseAnc(SonyDialect.V2, bytes(0x67, 0x17, 0x01, 0x01, 0x00, 0x03, 0x00, 0x05))
        assertEquals(AncMode.AMBIENT, wind!!.mode)
        // enable=1, ambient byte 0 => NoiseCancel(ANC).
        val anc = SonyResponses.parseAnc(SonyDialect.V2, bytes(0x67, 0x17, 0x01, 0x01, 0x00, 0x02, 0x00, 0x00))
        assertEquals(AncMode.ANC, anc!!.mode)
    }

    @Test
    fun parseAncV2RejectsBadLengthOrSub() {
        assertNull(SonyResponses.parseAnc(SonyDialect.V2, bytes(0x67, 0x15, 0x01))) // too short
        assertNull(SonyResponses.parseAnc(SonyDialect.V2, bytes(0x67, 0x99, 0x01, 0x01, 0x01, 0x00, 0x0f))) // bad sub
    }

    // ---- ANC parsing: V1 (byte-4 inverted vs V2) --------------------------

    @Test
    fun parseAncV1NonWindInvertedByte4() {
        // enable(byte2)=0 => OFF.
        val off = SonyResponses.parseAnc(SonyDialect.V1, bytes(0x67, 0x02, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00))
        assertEquals(AncMode.OFF, off!!.mode)
        // non-wind (byte3=0): byte4=0 => AMBIENT.
        val amb = SonyResponses.parseAnc(SonyDialect.V1, bytes(0x67, 0x02, 0x11, 0x00, 0x00, 0x01, 0x00, 0x0f))
        assertEquals(AncStatus(AncMode.AMBIENT, voicePassthrough = false, ambientLevel = 15), amb)
        // non-wind: byte4=1 => ANC (inverted from V2).
        val anc = SonyResponses.parseAnc(SonyDialect.V1, bytes(0x67, 0x02, 0x11, 0x00, 0x01, 0x01, 0x01, 0x00))
        assertEquals(AncMode.ANC, anc!!.mode)
        assertTrue(anc.voicePassthrough)
    }

    @Test
    fun parseAncV1WindLayout() {
        // wind (byte3=0x02): byte4=2 => ANC, byte4=1 => AMBIENT (WIND folded), byte4=0 => AMBIENT.
        assertEquals(AncMode.ANC, SonyResponses.parseAnc(SonyDialect.V1, bytes(0x67, 0x02, 0x11, 0x02, 0x02, 0x01, 0x00, 0x00))!!.mode)
        assertEquals(AncMode.AMBIENT, SonyResponses.parseAnc(SonyDialect.V1, bytes(0x67, 0x02, 0x11, 0x02, 0x01, 0x01, 0x00, 0x0a))!!.mode)
        assertEquals(AncMode.AMBIENT, SonyResponses.parseAnc(SonyDialect.V1, bytes(0x67, 0x02, 0x11, 0x02, 0x00, 0x01, 0x00, 0x0a))!!.mode)
    }

    // ---- Firmware ----------------------------------------------------------

    @Test
    fun parseFirmwareAscii() {
        // [0x05,0x02,len=3,'1','.','2']
        val fw = SonyResponses.parseFirmware(bytes(0x05, 0x02, 0x03, 0x31, 0x2e, 0x32))
        assertEquals("1.2", fw)
        assertNull(SonyResponses.parseFirmware(bytes(0x05, 0x02))) // too short
        assertNull(SonyResponses.parseFirmware(bytes(0x99, 0x02, 0x01, 0x31))) // wrong opcode
    }

    @Test
    fun firmwareParserIgnoresModelNameReply() {
        // 0x05 carries the model name on sub 0x01 and the version on sub 0x02;
        // the firmware parser must not claim the former.
        assertNull(SonyResponses.parseFirmware(bytes(0x05, 0x01, 0x03, 0x41, 0x42, 0x43)))
        assertEquals("ABC", SonyResponses.parseModelName(bytes(0x05, 0x01, 0x03, 0x41, 0x42, 0x43)))
    }

    // ---- Model name --------------------------------------------------------

    @Test
    fun parseModelNameAscii() {
        assertEquals(
            "WF-1000XM5",
            SonyResponses.parseModelName(bytes(0x05, 0x01, 0x0a) + "WF-1000XM5".toByteArray(Charsets.US_ASCII)),
        )
        assertNull(SonyResponses.parseModelName(bytes(0x05, 0x02, 0x03, 0x31, 0x2e, 0x32))) // firmware reply
        assertNull(SonyResponses.parseModelName(bytes(0x05, 0x01))) //                        too short
        assertNull(SonyResponses.parseModelName(bytes(0x05, 0x01, 0x00))) //                  empty name
    }

    @Test
    fun modelNameGetAndSupportFunctionGetEncoding() {
        assertContentEquals(bytes(0x04, 0x01), SonyCommands.modelNameGet())
        assertContentEquals(bytes(0x06, 0x00), SonyCommands.supportFunctionGet())
    }

    // ---- Support functions -------------------------------------------------

    @Test
    fun parseSupportFunctionsReadsFunctionBytesAndDropsNoUse() {
        // 07 00 count=3 { 0x30,cap } { 0x00,cap } { 0x22,cap }
        assertEquals(
            setOf(0x30, 0x22),
            SonyResponses.parseSupportFunctions(bytes(0x07, 0x00, 0x03, 0x30, 0x01, 0x00, 0x00, 0x22, 0x02)),
        )
        assertEquals(emptySet(), SonyResponses.parseSupportFunctions(bytes(0x07, 0x00, 0x00)))
    }

    @Test
    fun parseSupportFunctionsRejectsLengthMismatch() {
        assertNull(SonyResponses.parseSupportFunctions(bytes(0x07, 0x00, 0x02, 0x30, 0x01))) //       short
        assertNull(SonyResponses.parseSupportFunctions(bytes(0x07, 0x00, 0x01, 0x30, 0x01, 0x00))) // long
        assertNull(SonyResponses.parseSupportFunctions(bytes(0x07, 0x00))) //                         truncated
        assertNull(SonyResponses.parseSupportFunctions(bytes(0x06, 0x00, 0x00))) //                   wrong opcode
    }

    // ---- Dispatcher --------------------------------------------------------

    @Test
    fun dispatcherRoutesByDialect() {
        // V2 battery notify (0x25).
        val batteryEvent = SonyResponses.parse(SonyDialect.V2, SonyMessage(SonyFrame.TYPE_COMMAND1, 1, bytes(0x25, 0x0a, 55, 0x00)))
        assertTrue(batteryEvent is SonyEvent.Battery)
        assertEquals(55, (batteryEvent as SonyEvent.Battery).battery.case)

        // ANC notify (0x69) parsed under V2 standard layout.
        val ancEvent = SonyResponses.parse(SonyDialect.V2, SonyMessage(SonyFrame.TYPE_COMMAND1, 0, bytes(0x69, 0x15, 0x01, 0x01, 0x01, 0x00, 0x0f)))
        assertTrue(ancEvent is SonyEvent.Anc)
        assertEquals(AncMode.AMBIENT, (ancEvent as SonyEvent.Anc).status.mode)

        // A V1 battery notify (0x13) is NOT a V2 battery opcode -> null under V2.
        assertNull(SonyResponses.parse(SonyDialect.V2, SonyMessage(SonyFrame.TYPE_COMMAND1, 0, bytes(0x13, 0x00, 10, 0x00))))
        // ...but IS recognised under V1.
        assertTrue(SonyResponses.parse(SonyDialect.V1, SonyMessage(SonyFrame.TYPE_COMMAND1, 0, bytes(0x13, 0x00, 10, 0x00))) is SonyEvent.Battery)
    }

    @Test
    fun dispatcherRoutesModelNameAndSupportFunctions() {
        val model = SonyResponses.parse(
            SonyDialect.V2,
            SonyMessage(SonyFrame.TYPE_COMMAND1, 0, bytes(0x05, 0x01, 0x02, 0x48, 0x50)),
        )
        assertTrue(model is SonyEvent.ModelName)
        assertEquals("HP", (model as SonyEvent.ModelName).name)

        val fns = SonyResponses.parse(
            SonyDialect.V2,
            SonyMessage(SonyFrame.TYPE_COMMAND1, 0, bytes(0x07, 0x00, 0x01, 0x30, 0x00)),
        )
        assertTrue(fns is SonyEvent.SupportFunctions)
        assertEquals(setOf(0x30), (fns as SonyEvent.SupportFunctions).functions)

        // Still routes the version reply to Firmware.
        assertTrue(
            SonyResponses.parse(
                SonyDialect.V2,
                SonyMessage(SonyFrame.TYPE_COMMAND1, 0, bytes(0x05, 0x02, 0x03, 0x31, 0x2e, 0x32)),
            ) is SonyEvent.Firmware,
        )
    }

    @Test
    fun dispatcherReturnsNullForUnknown() {
        assertNull(SonyResponses.parse(SonyDialect.V2, SonyMessage(SonyFrame.TYPE_COMMAND1, 0, bytes(0x99, 0x00))))
        assertNull(SonyResponses.parse(SonyDialect.V2, SonyMessage(SonyFrame.TYPE_COMMAND1, 0, ByteArray(0))))
    }
}
