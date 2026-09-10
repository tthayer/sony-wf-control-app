package com.thelightphone.sonywf.update

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are built here the way Sony's server does: plaintext info.xml,
 * zero-padded, AES-128-ECB encrypted with the spec key, wrapped in the
 * `key:value` header whose `digest` is the two-stage chain of spec §3.3.
 */
class SonyUpdateFeedTest {

    private val categoryId = "CAT01"
    private val serviceId = "SVC99"

    // ---- Fixture helpers ---------------------------------------------------

    private val aesKey = byteArrayOf(
        0x4F, 0xA2.toByte(), 0x79, 0x99.toByte(), 0xFF.toByte(), 0xD0.toByte(), 0x8B.toByte(), 0x1F,
        0xE4.toByte(), 0xD2.toByte(), 0x60, 0xD5.toByte(), 0x7B, 0x6D, 0x3C, 0x17,
    )

    private fun zeroPad(data: ByteArray, blockSize: Int): ByteArray {
        val remainder = data.size % blockSize
        if (remainder == 0) return data
        return data.copyOf(data.size + (blockSize - remainder))
    }

    private fun aesEncrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        return cipher.doFinal(zeroPad(plain, 16))
    }

    private fun tripleDesEncrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(24), "DESede"))
        return cipher.doFinal(zeroPad(plain, 8))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun digestChain(algorithm: String, body: ByteArray): String {
        val inner = hex(MessageDigest.getInstance(algorithm).digest(body))
        val buffer = inner.toByteArray() + serviceId.toByteArray() + categoryId.toByteArray()
        return hex(MessageDigest.getInstance(algorithm).digest(buffer))
    }

    private fun feed(
        xml: String,
        eaid: String = "ENC0003",
        daid: String = "HAS0003",
        digestOverride: String? = null,
        newline: String = "\n",
    ): ByteArray {
        val plain = xml.toByteArray()
        val body = when (eaid) {
            "ENC0001" -> plain
            "ENC0002" -> tripleDesEncrypt(plain)
            else -> aesEncrypt(plain)
        }
        val algorithm = when (daid) {
            "HAS0002" -> "MD5"
            "HAS0003" -> "SHA-1"
            else -> null
        }
        val digest = digestOverride ?: algorithm?.let { digestChain(it, plain) } ?: ""
        val header = listOf("eaid:$eaid", "daid:$daid", "digest:$digest")
            .joinToString(newline, postfix = newline + newline)
        return header.toByteArray() + body
    }

    private val fullXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <UpdateInfo>
          <ApplyConditions>
            <ApplyCondition>
              <Rules>
                <Rule Type="device" Key="Model" Value="WH-1000XM5" Operator="Equal"/>
              </Rules>
              <Distributions>
                <Distribution InstallType="binary" Version="9.9.9"
                              URI="https://dl.sony.net/other.bin" MAC="aa" Size="1"/>
              </Distributions>
            </ApplyCondition>
            <ApplyCondition>
              <Rules>
                <Rule Type="device" Key="Model" Value="WF-1000XM4" Operator="Equal"/>
                <Rule Type="device" Key="FirmwareVersion" Value="3.0.1" Operator="LessThan"/>
                <Rule Type="app" Key="ClientVersion" Value="99.99.99" Operator="GreaterThanEqual"/>
              </Rules>
              <Distributions>
                <Distribution InstallType="EULA" Version="1.0"
                              URI="https://dl.sony.net/eula.xml" MAC="" Size="10"/>
                <Distribution InstallType="binary" Version="3.0.1"
                              URI="http://dl.sony.net/fw/wf1000xm4_301.bin"
                              MAC="0123456789ABCDEF0123456789abcdef01234567"
                              ClientVersion="8.01.00" Size="4194304"/>
              </Distributions>
              <Descriptions DefaultLang="English">
                <Description Lang="Japanese">Notes JP</Description>
                <Description Lang="English"><![CDATA[Improves stability.]]></Description>
              </Descriptions>
            </ApplyCondition>
          </ApplyConditions>
        </UpdateInfo>
    """.trimIndent()

    private val xm4 = DeviceContext("WF-1000XM4", "3.0.0", "1301211", "US")

    // ---- decodeInfo --------------------------------------------------------

    @Test
    fun `infoUrl uses the category and service path segments`() {
        assertEquals(
            "https://info.update.sony.net/CAT01/SVC99/info/info.xml",
            SonyUpdateFeed.infoUrl(categoryId, serviceId),
        )
    }

    @Test
    fun `infoUrl rejects ids that are not safe path segments`() {
        for (bad in listOf("", ".", "..", "a/b", "../etc", "a?b", "a b", "a%2Fb", "a#b")) {
            assertFailsWith<FeedException>("categoryId '$bad'") {
                SonyUpdateFeed.infoUrl(bad, serviceId)
            }
            assertFailsWith<FeedException>("serviceId '$bad'") {
                SonyUpdateFeed.infoUrl(categoryId, bad)
            }
        }
        // Dots, dashes and underscores are legal inside a segment.
        assertEquals(
            "https://info.update.sony.net/a.b-c_1/x.y/info/info.xml",
            SonyUpdateFeed.infoUrl("a.b-c_1", "x.y"),
        )
    }

    @Test
    fun `decodeInfo decrypts an AES SHA1 feed and strips zero padding`() {
        val info = SonyUpdateFeed.decodeInfo(feed(fullXml), categoryId, serviceId)
        assertEquals(DigestType.SHA1, info.digest)
        assertEquals(fullXml, info.xml)
    }

    @Test
    fun `decodeInfo tolerates CRLF header line endings`() {
        val info = SonyUpdateFeed.decodeInfo(feed(fullXml, newline = "\r\n"), categoryId, serviceId)
        assertEquals(fullXml, info.xml)
    }

    @Test
    fun `decodeInfo accepts a plaintext ENC0001 MD5 feed`() {
        val info = SonyUpdateFeed.decodeInfo(
            feed(fullXml, eaid = "ENC0001", daid = "HAS0002"),
            categoryId,
            serviceId,
        )
        assertEquals(DigestType.MD5, info.digest)
        assertEquals(fullXml, info.xml)
    }

    @Test
    fun `decodeInfo accepts a 3DES ENC0002 feed`() {
        val info = SonyUpdateFeed.decodeInfo(
            feed(fullXml, eaid = "ENC0002"),
            categoryId,
            serviceId,
        )
        assertEquals(fullXml, info.xml)
    }

    @Test
    fun `decodeInfo accepts HAS0001 only with an empty digest`() {
        val ok = SonyUpdateFeed.decodeInfo(
            feed(fullXml, eaid = "ENC0001", daid = "HAS0001"),
            categoryId,
            serviceId,
        )
        assertEquals(DigestType.NONE, ok.digest)
        assertFailsWith<FeedException> {
            SonyUpdateFeed.decodeInfo(
                feed(fullXml, eaid = "ENC0001", daid = "HAS0001", digestOverride = "abcd"),
                categoryId,
                serviceId,
            )
        }
    }

    @Test
    fun `decodeInfo rejects a tampered digest`() {
        val tampered = feed(fullXml, digestOverride = "00".repeat(20))
        assertFailsWith<FeedException> {
            SonyUpdateFeed.decodeInfo(tampered, categoryId, serviceId)
        }
    }

    @Test
    fun `decodeInfo rejects a digest computed for a different serviceId`() {
        assertFailsWith<FeedException> {
            SonyUpdateFeed.decodeInfo(feed(fullXml), categoryId, "OTHER")
        }
    }

    @Test
    fun `decodeInfo digest compare is case insensitive`() {
        val upper = feed(fullXml, digestOverride = digestChain("SHA-1", fullXml.toByteArray()).uppercase())
        assertEquals(fullXml, SonyUpdateFeed.decodeInfo(upper, categoryId, serviceId).xml)
    }

    @Test
    fun `decodeInfo rejects a body that is not a whole number of blocks`() {
        val raw = feed(fullXml)
        assertFailsWith<FeedException> {
            SonyUpdateFeed.decodeInfo(raw.copyOf(raw.size - 1), categoryId, serviceId)
        }
    }

    @Test
    fun `decodeInfo rejects a feed with no blank separator line`() {
        assertFailsWith<FeedException> {
            SonyUpdateFeed.decodeInfo("eaid:ENC0001\ndaid:HAS0001\n".toByteArray(), categoryId, serviceId)
        }
    }

    @Test
    fun `decodeInfo rejects unknown or missing header ids`() {
        assertFailsWith<FeedException> {
            SonyUpdateFeed.decodeInfo(feed(fullXml, eaid = "ENC9999"), categoryId, serviceId)
        }
        assertFailsWith<FeedException> {
            SonyUpdateFeed.decodeInfo(feed(fullXml, daid = "HAS9999"), categoryId, serviceId)
        }
        assertFailsWith<FeedException> {
            SonyUpdateFeed.decodeInfo("daid:HAS0001\n\nbody".toByteArray(), categoryId, serviceId)
        }
    }

    @Test
    fun `decodeInfo rejects a header line without a colon`() {
        assertFailsWith<FeedException> {
            SonyUpdateFeed.decodeInfo("eaid=ENC0001\n\nbody".toByteArray(), categoryId, serviceId)
        }
    }

    @Test
    fun `header value may contain a colon`() {
        val body = "<a/>"
        val raw = ("eaid:ENC0001\ndaid:HAS0001\ndigest:\nnote:a:b\n\n" + body).toByteArray()
        assertEquals(body, SonyUpdateFeed.decodeInfo(raw, categoryId, serviceId).xml)
    }

    // ---- parseInfo ---------------------------------------------------------

    @Test
    fun `parseInfo reads rules distributions and descriptions`() {
        val conditions = SonyUpdateFeed.parseInfo(fullXml)
        assertEquals(2, conditions.size)
        val second = conditions[1]
        assertEquals(3, second.rules.size)
        assertEquals(UpdateRule("Model", "Equal", "WF-1000XM4"), second.rules[0])
        assertEquals(UpdateRule("FirmwareVersion", "LessThan", "3.0.1"), second.rules[1])
        assertEquals(2, second.distributions.size)
        val binary = second.distributions[1]
        assertEquals("binary", binary.installType)
        assertEquals("3.0.1", binary.version)
        assertEquals("http://dl.sony.net/fw/wf1000xm4_301.bin", binary.uri)
        assertEquals("8.01.00", binary.clientVersion)
        assertEquals(4_194_304L, binary.size)
        assertEquals("English", second.defaultLang)
        assertEquals(2, second.descriptions.size)
        assertEquals("Improves stability.", second.descriptions[1].text)
    }

    @Test
    fun `parseInfo does not assume the root element name`() {
        val xml = "<Anything><ApplyConditions><ApplyCondition><Rules/>" +
            "<Distributions><Distribution InstallType=\"binary\" Version=\"1\" URI=\"https://x/a.bin\"" +
            " MAC=\"\" Size=\"7\"/></Distributions></ApplyCondition></ApplyConditions></Anything>"
        val conditions = SonyUpdateFeed.parseInfo(xml)
        assertEquals(1, conditions.size)
        assertEquals(7L, conditions[0].distributions[0].size)
    }

    @Test
    fun `parseInfo defaults a missing or unparsable Size to zero`() {
        val xml = "<R><ApplyCondition><Distributions>" +
            "<Distribution InstallType=\"binary\" Version=\"1\" URI=\"https://x/a.bin\" Size=\"nope\"/>" +
            "</Distributions></ApplyCondition></R>"
        assertEquals(0L, SonyUpdateFeed.parseInfo(xml)[0].distributions[0].size)
    }

    @Test
    fun `parseInfo rejects a doctype declaration`() {
        val xml = "<!DOCTYPE r [<!ENTITY x \"boom\">]><r><ApplyCondition/></r>"
        assertFailsWith<FeedException> { SonyUpdateFeed.parseInfo(xml) }
    }

    @Test
    fun `parseInfo rejects malformed xml`() {
        assertFailsWith<FeedException> { SonyUpdateFeed.parseInfo("<r><ApplyCondition>") }
    }

    // ---- ruleMatches -------------------------------------------------------

    private val ctx = mapOf(
        "Model" to "WF-1000XM4",
        "FirmwareVersion" to "3.10.0",
        "SerialNo" to "1301211",
        "Nation" to "",
    )

    private fun match(key: String, operator: String, value: String) =
        SonyUpdateFeed.ruleMatches(UpdateRule(key, operator, value), ctx)

    @Test
    fun `ruleMatches covers the equality and containment operators`() {
        assertTrue(match("Model", "Equal", "WF-1000XM4"))
        assertFalse(match("Model", "Equal", "WF-1000XM5"))
        assertTrue(match("Model", "NotEqual", "WF-1000XM5"))
        assertFalse(match("Model", "NotEqual", "WF-1000XM4"))
        assertTrue(match("Model", "StartWith", "WF-"))
        assertFalse(match("Model", "StartWith", "WH-"))
        assertTrue(match("Model", "NotStartWith", "WH-"))
        assertTrue(match("Model", "EndWith", "XM4"))
        assertTrue(match("Model", "NotEndWith", "XM5"))
        assertTrue(match("Model", "Include", "1000"))
        assertFalse(match("Model", "Include", "9999"))
        assertTrue(match("Model", "Exclude", "9999"))
        assertFalse(match("Model", "Exclude", "1000"))
    }

    @Test
    fun `ruleMatches treats Exist as present and non-empty`() {
        assertTrue(match("Model", "Exist", ""))
        assertFalse(match("Nation", "Exist", ""))
        assertTrue(match("Nation", "NotExist", ""))
        assertTrue(match("OSVersion", "NotExist", ""))
        assertFalse(match("OSVersion", "Exist", ""))
    }

    @Test
    fun `ruleMatches fails closed for an absent key or unknown operator`() {
        assertFalse(match("OSVersion", "Equal", "1"))
        assertFalse(match("OSVersion", "NotEqual", "1"))
        assertFalse(match("Model", "Matches", "WF-1000XM4"))
    }

    @Test
    fun `ruleMatches compares version keys numerically not lexicographically`() {
        // String compare would call "3.10.0" < "3.9.0"; dotted compare must not.
        assertTrue(match("FirmwareVersion", "GreaterThan", "3.9.0"))
        assertFalse(match("FirmwareVersion", "LessThan", "3.9.0"))
        assertTrue(match("FirmwareVersion", "LessThan", "3.10.1"))
        assertTrue(match("FirmwareVersion", "GreaterThanEqual", "3.10"))
        assertTrue(match("FirmwareVersion", "LessThanEqual", "3.10.0.0"))
        assertTrue(match("FirmwareVersion", "Equal", "3.10.0.0"))
    }

    @Test
    fun `ruleMatches falls back to string compare when a side is not numeric`() {
        val alpha = mapOf("FirmwareVersion" to "3.0.1a")
        assertTrue(SonyUpdateFeed.ruleMatches(UpdateRule("FirmwareVersion", "GreaterThan", "3.0.1"), alpha))
        // Non-version keys never use the dotted compare.
        val serial = mapOf("SerialNo" to "3.10.0")
        assertTrue(SonyUpdateFeed.ruleMatches(UpdateRule("SerialNo", "LessThan", "3.9.0"), serial))
    }

    // ---- select ------------------------------------------------------------

    @Test
    fun `select returns the first passing condition with a binary distribution`() {
        val info = SonyUpdateFeed.decodeInfo(feed(fullXml), categoryId, serviceId)
        val update = SonyUpdateFeed.select(SonyUpdateFeed.parseInfo(info.xml), xm4, info.digest)
        assertNotNull(update)
        assertEquals("3.0.1", update.version)
        // http -> https, and the EULA distribution must not win.
        assertEquals("https://dl.sony.net/fw/wf1000xm4_301.bin", update.url)
        assertEquals("0123456789ABCDEF0123456789abcdef01234567", update.macHex)
        assertEquals(DigestType.SHA1, update.digest)
        assertEquals(4_194_304L, update.sizeBytes)
        assertEquals("Improves stability.", update.notes)
    }

    @Test
    fun `select honours the requested language then DefaultLang then the first entry`() {
        val conditions = SonyUpdateFeed.parseInfo(fullXml)
        assertEquals(
            "Notes JP",
            SonyUpdateFeed.select(conditions, xm4, DigestType.SHA1, lang = "Japanese")?.notes,
        )
        // Unknown language falls back to DefaultLang="English".
        assertEquals(
            "Improves stability.",
            SonyUpdateFeed.select(conditions, xm4, DigestType.SHA1, lang = "Klingon")?.notes,
        )
    }

    @Test
    fun `select falls back to the first description when DefaultLang is absent`() {
        val xml = "<R><ApplyCondition><Descriptions><Description Lang=\"French\">Notes FR</Description>" +
            "</Descriptions><Distributions><Distribution InstallType=\"binary\" Version=\"2\"" +
            " URI=\"https://x/a.bin\" MAC=\"\" Size=\"3\"/></Distributions></ApplyCondition></R>"
        val update = SonyUpdateFeed.select(SonyUpdateFeed.parseInfo(xml), xm4, DigestType.NONE)
        assertEquals("Notes FR", update?.notes)
    }

    @Test
    fun `select skips ClientVersion rules`() {
        // The fixture's second condition has an unsatisfiable ClientVersion rule.
        assertNotNull(SonyUpdateFeed.select(SonyUpdateFeed.parseInfo(fullXml), xm4, DigestType.SHA1))
    }

    @Test
    fun `select returns null when no condition matches`() {
        val other = DeviceContext("WH-CH720N", "1.0.0", "x", "US")
        val conditions = SonyUpdateFeed.parseInfo(fullXml)
        // The first fixture condition targets WH-1000XM5, so nothing passes here.
        assertNull(SonyUpdateFeed.select(conditions, other, DigestType.SHA1))
    }

    @Test
    fun `select skips a passing condition that has no binary distribution`() {
        val xml = "<R><ApplyConditions>" +
            "<ApplyCondition><Rules/><Distributions>" +
            "<Distribution InstallType=\"notice\" Version=\"1\" URI=\"https://x/n.xml\" MAC=\"\" Size=\"1\"/>" +
            "</Distributions></ApplyCondition>" +
            "<ApplyCondition><Rules/><Distributions>" +
            "<Distribution InstallType=\"binary\" Version=\"4.0.0\" URI=\"https://x/b.bin\" MAC=\"\" Size=\"9\"/>" +
            "</Distributions></ApplyCondition>" +
            "</ApplyConditions></R>"
        val update = SonyUpdateFeed.select(SonyUpdateFeed.parseInfo(xml), xm4, DigestType.NONE)
        assertEquals("4.0.0", update?.version)
        assertNull(update?.notes)
    }

    // ---- binary verification / naming --------------------------------------

    @Test
    fun `verifyBinary requires an exact size`() {
        val bytes = ByteArray(16) { it.toByte() }
        assertTrue(SonyUpdateFeed.verifyBinary(bytes, 16, "", DigestType.NONE))
        assertFalse(SonyUpdateFeed.verifyBinary(bytes, 15, "", DigestType.NONE))
        assertFalse(SonyUpdateFeed.verifyBinary(bytes, 17, "", DigestType.NONE))
    }

    @Test
    fun `verifyBinary checks the MAC when present and skips it when blank`() {
        val bytes = "firmware".toByteArray()
        val sha1 = hex(MessageDigest.getInstance("SHA-1").digest(bytes))
        val md5 = hex(MessageDigest.getInstance("MD5").digest(bytes))
        assertTrue(SonyUpdateFeed.verifyBinary(bytes, bytes.size.toLong(), sha1, DigestType.SHA1))
        assertTrue(
            SonyUpdateFeed.verifyBinary(bytes, bytes.size.toLong(), sha1.uppercase(), DigestType.SHA1),
        )
        assertTrue(SonyUpdateFeed.verifyBinary(bytes, bytes.size.toLong(), md5, DigestType.MD5))
        assertFalse(SonyUpdateFeed.verifyBinary(bytes, bytes.size.toLong(), md5, DigestType.SHA1))
        assertTrue(SonyUpdateFeed.verifyBinary(bytes, bytes.size.toLong(), "  ", DigestType.SHA1))
        // No algorithm to check with; size alone stands.
        assertTrue(SonyUpdateFeed.verifyBinary(bytes, bytes.size.toLong(), sha1, DigestType.NONE))
    }

    @Test
    fun `fileNameFor hashes the last path segment`() {
        assertEquals(
            Integer.toHexString("wf1000xm4_301.bin".hashCode()),
            SonyUpdateFeed.fileNameFor("https://dl.sony.net/fw/wf1000xm4_301.bin"),
        )
        assertEquals(
            Integer.toHexString("a.bin".hashCode()),
            SonyUpdateFeed.fileNameFor("https://dl.sony.net/a.bin?v=2#frag"),
        )
    }

    @Test
    fun `effectiveSerial pins the three overridden models`() {
        assertEquals("1301211", SonyUpdateFeed.effectiveSerial("WF-1000XM4", "device-reported"))
        assertEquals("5630981", SonyUpdateFeed.effectiveSerial("LinkBuds", "device-reported"))
        assertEquals("1300112", SonyUpdateFeed.effectiveSerial("LinkBuds S", "device-reported"))
        assertEquals("device-reported", SonyUpdateFeed.effectiveSerial("WH-1000XM5", "device-reported"))
    }

    @Test
    fun `forceHttps upgrades only plain http`() {
        assertEquals("https://x/a.bin", SonyUpdateFeed.forceHttps("http://x/a.bin"))
        assertEquals("https://x/a.bin", SonyUpdateFeed.forceHttps("https://x/a.bin"))
        assertEquals("ftp://x/a.bin", SonyUpdateFeed.forceHttps("ftp://x/a.bin"))
    }

    @Test
    fun `parseInfo rejects documents with a DTD`() {
        val xml = """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/hosts">]><Root><ApplyConditions/></Root>"""
        val e = assertFailsWith<FeedException> { SonyUpdateFeed.parseInfo(xml) }
        assertTrue(e.message!!.contains("DTD"))
    }
}
