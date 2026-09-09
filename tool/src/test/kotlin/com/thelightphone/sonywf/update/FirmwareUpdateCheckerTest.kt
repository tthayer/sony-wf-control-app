package com.thelightphone.sonywf.update

import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FirmwareUpdateCheckerTest {

    private val categoryId = "CAT01"
    private val serviceId = "SVC99"
    private val infoUrl = SonyUpdateFeed.infoUrl(categoryId, serviceId)
    private val binaryUrl = "https://dl.sony.net/fw/wf1000xm4_301.bin"
    private val firmware = ByteArray(64) { (it * 7).toByte() }
    private val firmwareSha1 = hex(MessageDigest.getInstance("SHA-1").digest(firmware))

    private val params = UpdateParams(
        categoryId = categoryId,
        serviceId = serviceId,
        nationCode = "US",
        language = "English",
        serialNumber = "device-reported",
        batteryThreshold = 50,
        batteryThresholdInterrupt = 20,
        uniqueId = "u1",
    )

    /** Serves canned bodies; [failure], when set, is thrown for every request. */
    private class FakeFetcher(
        private val bodies: Map<String, ByteArray> = emptyMap(),
        private val failure: Exception? = null,
        private val reportContentLength: Boolean = true,
    ) : HttpFetcher {
        val requested = mutableListOf<String>()

        override suspend fun get(
            url: String,
            onProgress: ((received: Long, total: Long) -> Unit)?,
        ): ByteArray {
            requested += url
            failure?.let { throw it }
            val body = bodies[url] ?: throw IOException("HTTP 404 for $url")
            val total = if (reportContentLength) body.size.toLong() else -1L
            onProgress?.invoke(body.size.toLong() / 2, total)
            onProgress?.invoke(body.size.toLong(), total)
            return body
        }
    }

    // ---- Fixture builders (same framing as SonyUpdateFeedTest) -------------

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun aesEncrypt(plain: ByteArray): ByteArray {
        val key = byteArrayOf(
            0x4F, 0xA2.toByte(), 0x79, 0x99.toByte(), 0xFF.toByte(), 0xD0.toByte(), 0x8B.toByte(), 0x1F,
            0xE4.toByte(), 0xD2.toByte(), 0x60, 0xD5.toByte(), 0x7B, 0x6D, 0x3C, 0x17,
        )
        val padding = (16 - plain.size % 16) % 16
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plain.copyOf(plain.size + padding))
    }

    private fun feed(xml: String): ByteArray {
        val plain = xml.toByteArray()
        val inner = hex(MessageDigest.getInstance("SHA-1").digest(plain))
        val outer = hex(
            MessageDigest.getInstance("SHA-1")
                .digest(inner.toByteArray() + serviceId.toByteArray() + categoryId.toByteArray()),
        )
        return "eaid:ENC0003\ndaid:HAS0003\ndigest:$outer\n\n".toByteArray() + aesEncrypt(plain)
    }

    private fun infoXml(version: String, serialRule: String = "1301211"): String =
        "<UpdateInfo><ApplyConditions><ApplyCondition><Rules>" +
            "<Rule Type=\"device\" Key=\"Model\" Value=\"WF-1000XM4\" Operator=\"Equal\"/>" +
            "<Rule Type=\"device\" Key=\"SerialNo\" Value=\"$serialRule\" Operator=\"Equal\"/>" +
            "</Rules><Distributions>" +
            "<Distribution InstallType=\"binary\" Version=\"$version\" URI=\"$binaryUrl\"" +
            " MAC=\"$firmwareSha1\" ClientVersion=\"8.01.00\" Size=\"${firmware.size}\"/>" +
            "</Distributions><Descriptions DefaultLang=\"English\">" +
            "<Description Lang=\"English\">Release notes</Description>" +
            "</Descriptions></ApplyCondition></ApplyConditions></UpdateInfo>"

    // ---- check() -----------------------------------------------------------

    @Test
    fun `check reports Available and fetches the feed url`() = runTest {
        val fetcher = FakeFetcher(mapOf(infoUrl to feed(infoXml("3.0.1"))))
        val result = FirmwareUpdateChecker(fetcher).check(params, "WF-1000XM4", "3.0.0")
        val available = assertIs<UpdateCheck.Available>(result)
        assertEquals("3.0.1", available.update.version)
        assertEquals(binaryUrl, available.update.url)
        assertEquals(DigestType.SHA1, available.update.digest)
        assertEquals(firmware.size.toLong(), available.update.sizeBytes)
        assertEquals("Release notes", available.update.notes)
        assertEquals(listOf(infoUrl), fetcher.requested)
    }

    @Test
    fun `check uses the pinned serial for the overridden models`() = runTest {
        // The rule demands the pinned WF-1000XM4 serial, not what the device reported.
        val fetcher = FakeFetcher(mapOf(infoUrl to feed(infoXml("3.0.1"))))
        assertIs<UpdateCheck.Available>(
            FirmwareUpdateChecker(fetcher).check(params, "WF-1000XM4", "3.0.0"),
        )
        // A model without an override matches only against the reported serial.
        val plain = FakeFetcher(mapOf(infoUrl to feed(infoXml("3.0.1", serialRule = "device-reported"))))
        assertIs<UpdateCheck.UpToDate>(
            FirmwareUpdateChecker(plain).check(params, "WF-1000XM4", "3.0.0"),
        )
    }

    @Test
    fun `check reports UpToDate when the offered version is installed`() = runTest {
        val fetcher = FakeFetcher(mapOf(infoUrl to feed(infoXml("3.0.1"))))
        assertIs<UpdateCheck.UpToDate>(
            FirmwareUpdateChecker(fetcher).check(params, "WF-1000XM4", "3.0.1"),
        )
    }

    @Test
    fun `check reports UpToDate when no condition matches`() = runTest {
        val fetcher = FakeFetcher(mapOf(infoUrl to feed(infoXml("3.0.1"))))
        assertIs<UpdateCheck.UpToDate>(
            FirmwareUpdateChecker(fetcher).check(params, "WH-1000XM5", "1.0.0"),
        )
    }

    @Test
    fun `check reports Error on a network failure`() = runTest {
        val fetcher = FakeFetcher(failure = IOException("connect timed out"))
        val error = assertIs<UpdateCheck.Error>(
            FirmwareUpdateChecker(fetcher).check(params, "WF-1000XM4", "3.0.0"),
        )
        assertEquals("connect timed out", error.message)
    }

    @Test
    fun `check reports Error on a bad digest`() = runTest {
        val raw = feed(infoXml("3.0.1"))
        val corrupted = raw.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()
        val error = assertIs<UpdateCheck.Error>(
            FirmwareUpdateChecker(FakeFetcher(mapOf(infoUrl to corrupted)))
                .check(params, "WF-1000XM4", "3.0.0"),
        )
        assertTrue(error.message.isNotEmpty())
    }

    @Test
    fun `check reports Error when the xml is not parsable`() = runTest {
        val fetcher = FakeFetcher(mapOf(infoUrl to feed("<UpdateInfo>")))
        assertIs<UpdateCheck.Error>(FirmwareUpdateChecker(fetcher).check(params, "WF-1000XM4", "3.0.0"))
    }

    // ---- download() --------------------------------------------------------

    private fun update(
        size: Long = firmware.size.toLong(),
        mac: String = firmwareSha1,
    ) = AvailableUpdate("3.0.1", binaryUrl, mac, DigestType.SHA1, size, "Release notes")

    @Test
    fun `download verifies the binary and names it from the url`() = runTest {
        val fetcher = FakeFetcher(mapOf(binaryUrl to firmware))
        val progress = mutableListOf<Int>()
        val image = FirmwareUpdateChecker(fetcher).download(update()) { progress += it }
        assertTrue(firmware.contentEquals(image.bytes))
        assertEquals("3.0.1", image.version)
        assertEquals(Integer.toHexString("wf1000xm4_301.bin".hashCode()), image.fileName)
        assertEquals(DigestType.SHA1, image.digest)
        assertEquals(firmwareSha1, image.macHex)
        assertEquals(listOf(50, 100), progress)
        assertEquals(listOf(binaryUrl), fetcher.requested)
    }

    @Test
    fun `download falls back to the feed size when Content-Length is missing`() = runTest {
        val fetcher = FakeFetcher(mapOf(binaryUrl to firmware), reportContentLength = false)
        val progress = mutableListOf<Int>()
        FirmwareUpdateChecker(fetcher).download(update()) { progress += it }
        assertEquals(listOf(50, 100), progress)
    }

    @Test
    fun `download rejects a wrong size`() = runTest {
        val fetcher = FakeFetcher(mapOf(binaryUrl to firmware))
        assertFailsWith<FeedException> {
            FirmwareUpdateChecker(fetcher).download(update(size = firmware.size + 1L)) {}
        }
    }

    @Test
    fun `download rejects a wrong MAC`() = runTest {
        val fetcher = FakeFetcher(mapOf(binaryUrl to firmware))
        assertFailsWith<FeedException> {
            FirmwareUpdateChecker(fetcher).download(update(mac = "00".repeat(20))) {}
        }
    }

    @Test
    fun `download propagates a transport failure`() = runTest {
        val fetcher = FakeFetcher(failure = IOException("reset by peer"))
        assertFailsWith<IOException> { FirmwareUpdateChecker(fetcher).download(update()) {} }
    }
}
