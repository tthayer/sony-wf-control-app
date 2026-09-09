package com.thelightphone.sonywf.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FirmwareUpdateMethodsTest {

    @Test
    fun tandemOnlyWhenNoMtkTypeIsAdvertised() {
        assertEquals(FirmwareUpdateMethod.TANDEM, FirmwareUpdateMethods.fromSupportFunctions(setOf(0x30)))
        assertEquals(FirmwareUpdateMethod.TANDEM, FirmwareUpdateMethods.fromSupportFunctions(setOf(0x30, 0x37, 0x11)))
    }

    @Test
    fun anyMtkTypeOutranksTandem() {
        for (mtk in intArrayOf(0x32, 0x34, 0x35, 0x36)) {
            assertEquals(
                FirmwareUpdateMethod.MTK,
                FirmwareUpdateMethods.fromSupportFunctions(setOf(0x30, mtk)),
                "0x${mtk.toString(16)} must win over TANDEM",
            )
            assertEquals(FirmwareUpdateMethod.MTK, FirmwareUpdateMethods.fromSupportFunctions(setOf(mtk)))
        }
    }

    @Test
    fun mcAppOnlyWhenNothingElseIsAdvertised() {
        assertEquals(FirmwareUpdateMethod.MC_APP, FirmwareUpdateMethods.fromSupportFunctions(setOf(0x38)))
        // TANDEM is checked before MC_APP.
        assertEquals(FirmwareUpdateMethod.TANDEM, FirmwareUpdateMethods.fromSupportFunctions(setOf(0x30, 0x38)))
        assertEquals(FirmwareUpdateMethod.MTK, FirmwareUpdateMethods.fromSupportFunctions(setOf(0x35, 0x38)))
    }

    @Test
    fun noneWhenNoUpdateFunctionIsAdvertised() {
        assertEquals(FirmwareUpdateMethod.NONE, FirmwareUpdateMethods.fromSupportFunctions(emptySet()))
        // 0x37 (TANDEM over the common table) is declared but unimplemented (spec §6.1).
        assertEquals(FirmwareUpdateMethod.NONE, FirmwareUpdateMethods.fromSupportFunctions(setOf(0x37, 0x22, 0x01)))
    }

    @Test
    fun inquiredTypeFollowsTheAppsPriorityChain() {
        assertEquals(0x02, FirmwareUpdateMethods.updtInquiredType(setOf(0x32, 0x34, 0x35, 0x36, 0x38, 0x30)))
        assertEquals(0x04, FirmwareUpdateMethods.updtInquiredType(setOf(0x34, 0x35, 0x36, 0x38, 0x30)))
        assertEquals(0x05, FirmwareUpdateMethods.updtInquiredType(setOf(0x35, 0x36, 0x38, 0x30)))
        assertEquals(0x06, FirmwareUpdateMethods.updtInquiredType(setOf(0x36, 0x38, 0x30)))
        assertEquals(0x07, FirmwareUpdateMethods.updtInquiredType(setOf(0x38, 0x30)))
        assertEquals(0x10, FirmwareUpdateMethods.updtInquiredType(setOf(0x30)))
    }

    @Test
    fun inquiredTypeIsNullWithoutAnyUpdateFunction() {
        assertNull(FirmwareUpdateMethods.updtInquiredType(emptySet()))
        assertNull(FirmwareUpdateMethods.updtInquiredType(setOf(0x37, 0x11)))
    }
}
