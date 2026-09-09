package com.thelightphone.sonywf.update

/**
 * Shared value types for the firmware-update feature. Pure Kotlin; see
 * docs/protocol/fw-update-design.md for the contract and
 * docs/protocol/spec-tandem-fota.md for the wire formats.
 */

/** Digest used for firmware MACs; [macType] is the UPDT wire byte, [jcaName] the JCA algorithm. */
enum class DigestType(val macType: Int, val jcaName: String?) {
    NONE(0x00, null),
    MD5(0x01, "MD5"),
    SHA1(0x02, "SHA-1");

    companion object {
        /** From the feed header `daid` (HAS0001/HAS0002/HAS0003); null if unknown. */
        fun fromDaid(daid: String): DigestType? = when (daid.trim()) {
            "HAS0001" -> NONE
            "HAS0002" -> MD5
            "HAS0003" -> SHA1
            else -> null
        }
    }
}

/** How a connected device accepts firmware (spec-tandem-fota §6). */
enum class FirmwareUpdateMethod { TANDEM, MTK, MC_APP, NONE }

/** UPDT_RET_CAPABILITY (PART1) fields. */
data class UpdateCapability(
    val resumable: Boolean,
    val tws: Boolean,
    val backgroundTransfer: Boolean,
    val acCheck: Boolean,
)

/** UPDT_RET_PARAM fields: feed identifiers plus battery gates (percent, strict `>`). */
data class UpdateParams(
    val categoryId: String,
    val serviceId: String,
    val nationCode: String,
    val language: String,
    val serialNumber: String,
    val batteryThreshold: Int,
    val batteryThresholdInterrupt: Int,
    val uniqueId: String,
)

enum class FotaStatus(val code: Int) {
    INVALID(0x00), IDLE(0x01), NOT_READY(0x02), DATA_RECEIVING(0x03), UPDATING(0x04);

    companion object {
        fun fromCode(code: Int): FotaStatus? = entries.firstOrNull { it.code == code }
    }
}

enum class FotaResult(val code: Int) {
    OK(0x00),
    OTHER(0x01),
    ILLEGAL_STATE(0x02),
    ILLEGAL_ARGS(0x03),
    NO_NEED_OF_DATA_TRANSFER(0x04),
    TRANSFER_INCOMPLETE(0x05),
    NEED_POWER_AND_BATTERY(0x06),
    TEMP_TOO_HIGH(0x07),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): FotaResult = entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
    }
}

/** Parsed device→app UPDT notification (0x33 / 0x35 / 0x39 / 0x3F). */
sealed interface UpdtNotify {
    data class Status(val status: FotaStatus) : UpdtNotify
    data class SimpleResult(val command: Int, val result: FotaResult) : UpdtNotify
    data class StartTransferResult(val result: FotaResult, val maxPacketSize: Int, val offset: Int) : UpdtNotify
    data class ExecuteResult(val result: FotaResult, val requiredTimeSec: Int) : UpdtNotify
    data object Completed : UpdtNotify
}

/** A verified firmware binary ready for transfer. [macHex] is the ASCII-hex digest from the feed. */
class FirmwareImage(
    val bytes: ByteArray,
    val version: String,
    val fileName: String,
    val digest: DigestType,
    val macHex: String,
)
