package com.thelightphone.sonywf.update

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte helper that keeps values > 0x7F legal without littering `.toByte()`. */
private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

/** `str{n}` field: length prefix + ASCII. */
private fun str(s: String): ByteArray =
    byteArrayOf(s.length.toByte()) + s.toByteArray(Charsets.US_ASCII)

class UpdtMessagesTest {

    // ---- Builders (spec-tandem-fota §3) ------------------------------------

    @Test
    fun simpleGettersAreTwoBytes() {
        assertContentEquals(bytes(0x30, 0x10), UpdtMessages.getCapability(UpdtMessages.PART1))
        assertContentEquals(bytes(0x32, 0x10), UpdtMessages.getStatus(UpdtMessages.PART1))
        assertContentEquals(bytes(0x36, 0x10), UpdtMessages.getParam(UpdtMessages.PART1))
        // MTK inquired types go through the same builders.
        assertContentEquals(bytes(0x30, 0x02), UpdtMessages.getCapability(0x02))
    }

    @Test
    fun setSimpleEncodesPart2Commands() {
        assertContentEquals(bytes(0x38, 0x11, 0x01), UpdtMessages.setSimple(UpdtMessages.CMD_ENTER))
        assertContentEquals(bytes(0x38, 0x11, 0x02), UpdtMessages.setSimple(UpdtMessages.CMD_EXIT))
        assertContentEquals(bytes(0x38, 0x11, 0x04), UpdtMessages.setSimple(UpdtMessages.CMD_FINISH))
        assertContentEquals(bytes(0x38, 0x11, 0x05), UpdtMessages.setSimple(UpdtMessages.CMD_CANCEL))
    }

    @Test
    fun startTransferIsByteExactWithMd5() {
        val mac = "0123456789abcdef0123456789abcdef" // 32 hex chars
        val expected = bytes(0x38, 0x12, 0x03) +
            str("3.0.1") +
            bytes(0x00, 0x01) + //  fileIndex 0, numFiles 1
            str("a1b2c3d4") +
            bytes(0x01, 32) + //    MacType.MD5, macLen 32
            mac.toByteArray(Charsets.US_ASCII)
        assertContentEquals(
            expected,
            UpdtMessages.startTransfer("3.0.1", "a1b2c3d4", DigestType.MD5, mac),
        )
    }

    @Test
    fun startTransferSha1MacIs40AsciiChars() {
        val mac = "a".repeat(40)
        val payload = UpdtMessages.startTransfer("1.0", "f", DigestType.SHA1, mac)
        // ...0x02 macType then macLen 40 then the ASCII hex, NOT raw digest bytes.
        assertEquals(0x02, payload[payload.size - 42].toInt() and 0xFF)
        assertEquals(40, payload[payload.size - 41].toInt() and 0xFF)
        assertContentEquals(mac.toByteArray(Charsets.US_ASCII), payload.copyOfRange(payload.size - 40, payload.size))
    }

    @Test
    fun startTransferNoneDigestHasZeroLengthMac() {
        val payload = UpdtMessages.startTransfer("1.0", "f", DigestType.NONE, "")
        assertContentEquals(
            bytes(0x38, 0x12, 0x03) + str("1.0") + bytes(0x00, 0x01) + str("f") + bytes(0x00, 0x00),
            payload,
        )
    }

    @Test
    fun startTransferRejectsWrongMacLength() {
        assertFailsWith<IllegalArgumentException> {
            UpdtMessages.startTransfer("1.0", "f", DigestType.MD5, "abc")
        }
        assertFailsWith<IllegalArgumentException> {
            UpdtMessages.startTransfer("1.0", "f", DigestType.NONE, "deadbeef")
        }
        assertFailsWith<IllegalArgumentException> {
            UpdtMessages.startTransfer("", "f", DigestType.NONE, "")
        }
    }

    @Test
    fun startTransferTruncatesStringsTo32Bytes() {
        val long = "v".repeat(40)
        val payload = UpdtMessages.startTransfer(long, long, DigestType.NONE, "")
        assertEquals(32, payload[3].toInt() and 0xFF)
        assertEquals(32, payload[3 + 1 + 32 + 2].toInt() and 0xFF)
    }

    @Test
    fun executeHasNoFileIndexAndNoMac() {
        assertContentEquals(
            bytes(0x38, 0x13, 0x06) + str("3.0.1") + bytes(0x01) + str("a1b2c3d4"),
            UpdtMessages.execute("3.0.1", listOf("a1b2c3d4")),
        )
        // The full file list goes here, unlike START_TRANSFER (§4.6).
        assertContentEquals(
            bytes(0x38, 0x13, 0x06) + str("1.0") + bytes(0x02) + str("a") + str("bb"),
            UpdtMessages.execute("1.0", listOf("a", "bb")),
        )
    }

    @Test
    fun executeRejectsEmptyFileList() {
        assertFailsWith<IllegalArgumentException> { UpdtMessages.execute("1.0", emptyList()) }
    }

    @Test
    fun transferDataIsPart3WithBigEndianOffsetAndLength() {
        val data = bytes(0xAA, 0xBB, 0xCC)
        assertContentEquals(
            bytes(0x3E, 0x12, 0x00, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x03, 0xAA, 0xBB, 0xCC),
            UpdtMessages.transferData(0x00010203, data),
        )
        // Total payload length == dataLength + 10 (§3.13).
        assertEquals(10 + 1000, UpdtMessages.transferData(0, ByteArray(1000)).size)
    }

    // ---- Capability --------------------------------------------------------

    @Test
    fun parseCapabilityReadsAllFourFlags() {
        assertEquals(
            UpdateCapability(resumable = true, tws = true, backgroundTransfer = false, acCheck = true),
            UpdtMessages.parseCapability(bytes(0x31, 0x10, 0x04, 0x01, 0x01, 0x00, 0x01)),
        )
        assertEquals(
            UpdateCapability(resumable = false, tws = false, backgroundTransfer = false, acCheck = false),
            UpdtMessages.parseCapability(bytes(0x31, 0x10, 0x04, 0x00, 0x00, 0x00, 0x00)),
        )
    }

    @Test
    fun parseCapabilityRejectsMalformed() {
        assertNull(UpdtMessages.parseCapability(bytes(0x31, 0x10, 0x04, 0x01, 0x01, 0x00))) //      len 6
        assertNull(UpdtMessages.parseCapability(bytes(0x31, 0x10, 0x03, 0x01, 0x01, 0x00, 0x01))) // numOfFeature != 4
        assertNull(UpdtMessages.parseCapability(bytes(0x31, 0x02, 0x04, 0x01, 0x01, 0x00, 0x01))) // MTK sub-address
        assertNull(UpdtMessages.parseCapability(bytes(0x37, 0x10, 0x04, 0x01, 0x01, 0x00, 0x01))) // wrong opcode
    }

    // ---- Params ------------------------------------------------------------

    private val paramPayload = bytes(0x37, 0x10) +
        str("HP") + //        categoryId
        str("WF1000XM5") + // serviceId
        str("US") + //        nationCode
        str("English") + //   language
        str("SN12345") + //   serialNumber
        bytes(30, 20) + //    thresholds
        str("uid-9")

    @Test
    fun parseParamWalksTheStringChain() {
        assertEquals(
            UpdateParams(
                categoryId = "HP",
                serviceId = "WF1000XM5",
                nationCode = "US",
                language = "English",
                serialNumber = "SN12345",
                batteryThreshold = 30,
                batteryThresholdInterrupt = 20,
                uniqueId = "uid-9",
            ),
            UpdtMessages.parseParam(paramPayload),
        )
    }

    @Test
    fun parseParamRejectsTruncatedOrTrailingBytes() {
        assertNull(UpdtMessages.parseParam(paramPayload.copyOf(paramPayload.size - 1)))
        assertNull(UpdtMessages.parseParam(paramPayload + bytes(0x00)))
        assertNull(UpdtMessages.parseParam(bytes(0x37, 0x10)))
        assertNull(UpdtMessages.parseParam(bytes(0x31, 0x10, 0x00)))
        // A length prefix over the str{128} cap is malformed.
        assertNull(UpdtMessages.parseParam(bytes(0x37, 0x10, 200) + ByteArray(200)))
    }

    // ---- Notifications -----------------------------------------------------

    @Test
    fun parseNotifyReadsStatusFromRetAndNtfy() {
        assertEquals(
            UpdtNotify.Status(FotaStatus.IDLE),
            UpdtMessages.parseNotify(bytes(0x33, 0x10, 0x01)),
        )
        assertEquals(
            UpdtNotify.Status(FotaStatus.DATA_RECEIVING),
            UpdtMessages.parseNotify(bytes(0x35, 0x10, 0x03)),
        )
        assertEquals(
            UpdtNotify.Status(FotaStatus.INVALID),
            UpdtMessages.parseNotify(bytes(0x35, 0x10, 0x00)),
        )
        assertNull(UpdtMessages.parseNotify(bytes(0x35, 0x10, 0x09))) // out-of-range status
        assertNull(UpdtMessages.parseNotify(bytes(0x35, 0x11, 0x01))) // not PART1
        assertNull(UpdtMessages.parseNotify(bytes(0x35, 0x10, 0x01, 0x00))) // len 4
    }

    @Test
    fun parseNotifyReadsPart2SimpleResults() {
        assertEquals(
            UpdtNotify.SimpleResult(UpdtMessages.CMD_ENTER, FotaResult.OK),
            UpdtMessages.parseNotify(bytes(0x39, 0x11, 0x01, 0x00)),
        )
        assertEquals(
            UpdtNotify.SimpleResult(UpdtMessages.CMD_ENTER, FotaResult.NEED_POWER_AND_BATTERY),
            UpdtMessages.parseNotify(bytes(0x39, 0x11, 0x01, 0x06)),
        )
        assertEquals(
            UpdtNotify.SimpleResult(UpdtMessages.CMD_FINISH, FotaResult.UNKNOWN),
            UpdtMessages.parseNotify(bytes(0x39, 0x11, 0x04, 0x7f)),
        )
        assertNull(UpdtMessages.parseNotify(bytes(0x39, 0x11, 0x01))) // len 3
    }

    @Test
    fun parseNotifyReadsPart3StartTransferResult() {
        assertEquals(
            UpdtNotify.StartTransferResult(FotaResult.OK, maxPacketSize = 512, offset = 0x10000),
            UpdtMessages.parseNotify(
                bytes(0x39, 0x12, 0x03, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x01, 0x00, 0x00),
            ),
        )
        // NO_NEED_OF_DATA_TRANSFER may carry a zero packet size; it must still parse.
        assertEquals(
            UpdtNotify.StartTransferResult(FotaResult.NO_NEED_OF_DATA_TRANSFER, 0, 0),
            UpdtMessages.parseNotify(bytes(0x39, 0x12, 0x03, 0x04, 0, 0, 0, 0, 0, 0, 0, 0)),
        )
        assertNull(UpdtMessages.parseNotify(bytes(0x39, 0x12, 0x03, 0x00, 0, 0, 0, 0, 0, 0, 0))) // len 11
        assertNull(UpdtMessages.parseNotify(bytes(0x39, 0x12, 0x06, 0x00, 0, 0, 0, 0, 0, 0, 0, 0))) // wrong command
    }

    @Test
    fun parseNotifyReadsPart4ExecuteResult() {
        assertEquals(
            UpdtNotify.ExecuteResult(FotaResult.OK, requiredTimeSec = 0x0123),
            UpdtMessages.parseNotify(bytes(0x39, 0x13, 0x06, 0x00, 0x01, 0x23)),
        )
        assertEquals(
            UpdtNotify.ExecuteResult(FotaResult.TRANSFER_INCOMPLETE, 0),
            UpdtMessages.parseNotify(bytes(0x39, 0x13, 0x06, 0x05, 0x00, 0x00)),
        )
        assertEquals(
            65535,
            (UpdtMessages.parseNotify(bytes(0x39, 0x13, 0x06, 0x00, 0xFF, 0xFF)) as UpdtNotify.ExecuteResult)
                .requiredTimeSec,
        )
        assertNull(UpdtMessages.parseNotify(bytes(0x39, 0x13, 0x06, 0x00, 0x01))) // len 5
    }

    @Test
    fun parseNotifyReadsFwUpdateCompleted() {
        assertEquals(
            UpdtNotify.Completed,
            UpdtMessages.parseNotify(bytes(0x3F, 0x10, 0x01, 0x01, 0x00)),
        )
        assertNull(UpdtMessages.parseNotify(bytes(0x3F, 0x10, 0x00, 0x01, 0x00))) // MessageType NO_USE
        assertNull(UpdtMessages.parseNotify(bytes(0x3F, 0x11, 0x01, 0x01, 0x00))) // not PART1
    }

    @Test
    fun parseNotifyRejectsUnrelatedPayloads() {
        assertNull(UpdtMessages.parseNotify(bytes(0x25, 0x0a, 55, 0x00))) // battery notify
        assertNull(UpdtMessages.parseNotify(bytes(0x39, 0x10, 0x01, 0x00))) // NTFY_PARAM PART1
        assertNull(UpdtMessages.parseNotify(bytes(0x39)))
        assertNull(UpdtMessages.parseNotify(ByteArray(0)))
    }

    @Test
    fun digestTypeMacTypeBytesMatchTheSpec() {
        assertEquals(0x00, DigestType.NONE.macType)
        assertEquals(0x01, DigestType.MD5.macType)
        assertEquals(0x02, DigestType.SHA1.macType)
        assertTrue(FotaStatus.fromCode(0x04) == FotaStatus.UPDATING)
        assertEquals(FotaResult.TEMP_TOO_HIGH, FotaResult.fromCode(0x07))
    }
}
