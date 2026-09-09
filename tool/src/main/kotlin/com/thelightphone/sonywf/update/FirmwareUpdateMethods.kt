package com.thelightphone.sonywf.update

/**
 * Maps a V2 device's CONNECT support-function table onto the firmware-update
 * transport it speaks (spec-tandem-fota §6). The [FirmwareUpdateMethod] enum
 * itself lives in `UpdateTypes.kt`.
 *
 * Pure: no transport, no coroutines, no `android.*`.
 */
object FirmwareUpdateMethods {
    // Table-1 FunctionType bytes (spec-tandem-fota §6.1).
    const val FN_FW_UPDATE_TANDEM = 0x30
    const val FN_FW_UPDATE_MTK_WO_DISCONNECTION = 0x32
    const val FN_FW_UPDATE_MTK_WO_DISCONNECTION_AUTO_UPDATE = 0x34
    const val FN_FW_UPDATE_MTK_WITH_REPAIR_MODE = 0x35
    const val FN_FW_UPDATE_MTK_WITH_AC_CONNECTION_CHECK = 0x36
    const val FN_FW_UPDATE_USING_MC_APP = 0x38

    /** The four MTK/Airoha transfer variants, any of which outranks TANDEM. */
    private val MTK_FUNCTIONS = intArrayOf(
        FN_FW_UPDATE_MTK_WO_DISCONNECTION,
        FN_FW_UPDATE_MTK_WO_DISCONNECTION_AUTO_UPDATE,
        FN_FW_UPDATE_MTK_WITH_REPAIR_MODE,
        FN_FW_UPDATE_MTK_WITH_AC_CONNECTION_CHECK,
    )

    /**
     * spec-tandem-fota §6.2: MTK wins over TANDEM if any of 0x32/0x34/0x35/0x36
     * is present; TANDEM (0x30) next; MC_APP (0x38) last.
     *
     * The Sony app also routes to MTK when voice-guidance / voice-assistant
     * functions are advertised (§6.2, flagged there as possibly an app bug); we
     * ignore that and treat a lone 0x30 as TANDEM.
     */
    fun fromSupportFunctions(fns: Set<Int>): FirmwareUpdateMethod = when {
        MTK_FUNCTIONS.any { it in fns } -> FirmwareUpdateMethod.MTK
        FN_FW_UPDATE_TANDEM in fns -> FirmwareUpdateMethod.TANDEM
        FN_FW_UPDATE_USING_MC_APP in fns -> FirmwareUpdateMethod.MC_APP
        else -> FirmwareUpdateMethod.NONE
    }

    /**
     * spec-tandem-fota §6.4: the single `UpdtInquiredType` byte to address
     * `UPDT_GET_CAPABILITY` / `UPDT_GET_PARAM` with, or null when the device
     * advertises no firmware-update function at all. Priority order is the
     * app's `wv/e.java:814-852` if-else chain, verbatim.
     */
    fun updtInquiredType(fns: Set<Int>): Int? = when {
        FN_FW_UPDATE_MTK_WO_DISCONNECTION in fns -> 0x02
        FN_FW_UPDATE_MTK_WO_DISCONNECTION_AUTO_UPDATE in fns -> 0x04
        FN_FW_UPDATE_MTK_WITH_REPAIR_MODE in fns -> 0x05
        FN_FW_UPDATE_MTK_WITH_AC_CONNECTION_CHECK in fns -> 0x06
        FN_FW_UPDATE_USING_MC_APP in fns -> 0x07
        FN_FW_UPDATE_TANDEM in fns -> 0x10
        else -> null
    }
}
