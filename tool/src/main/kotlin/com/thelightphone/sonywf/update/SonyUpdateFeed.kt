package com.thelightphone.sonywf.update

import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Any refusal to trust or understand the update feed / a downloaded binary. */
class FeedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Decrypted, digest-verified info.xml plus the digest algorithm the feed declared. */
data class InfoDocument(val xml: String, val digest: DigestType)

/** One `<Rule>`; `Type` is parsed by Sony but never consulted, so it is dropped. */
data class UpdateRule(val key: String, val operator: String, val value: String)

/** One `<Distribution>`; [uri] is verbatim from the XML (see [SonyUpdateFeed.select]). */
data class Distribution(
    val installType: String,
    val version: String,
    val uri: String,
    val mac: String,
    val clientVersion: String,
    val size: Long,
)

data class Description(val lang: String, val text: String)

data class ApplyCondition(
    val rules: List<UpdateRule>,
    val distributions: List<Distribution>,
    val descriptions: List<Description>,
    val defaultLang: String?,
)

/** The four values the feed's `Rules` may be matched against (spec §4). */
data class DeviceContext(
    val model: String,
    val firmwareVersion: String,
    val serialNo: String,
    val nation: String,
)

data class AvailableUpdate(
    val version: String,
    val url: String,
    val macHex: String,
    val digest: DigestType,
    val sizeBytes: Long,
    val notes: String?,
)

/**
 * Sony's "AutoMagic" firmware-info feed: URL, header/cipher/digest framing,
 * info.xml schema, and the rule engine that picks a release for a device.
 * See docs/protocol/spec-firmware-download.md; that spec is authoritative.
 *
 * Pure JVM: no `android.*`, no transport. Fetching is delegated to [HttpFetcher].
 */
object SonyUpdateFeed {

    const val HOST = "info.update.sony.net"

    /** spec §3.2: hardcoded AES-128 key for `eaid=ENC0003`. */
    private val AES_KEY = byteArrayOf(
        0x4F, 0xA2.toByte(), 0x79, 0x99.toByte(), 0xFF.toByte(), 0xD0.toByte(), 0x8B.toByte(), 0x1F,
        0xE4.toByte(), 0xD2.toByte(), 0x60, 0xD5.toByte(), 0x7B, 0x6D, 0x3C, 0x17,
    )

    /** spec §3.2: `eaid=ENC0002` uses 24 zero bytes. */
    private val TRIPLE_DES_KEY = ByteArray(24)

    private const val AES_BLOCK = 16
    private const val DES_BLOCK = 8

    /** spec §4: only these keys use dotted-numeric comparison. */
    private val VERSION_KEYS = setOf("FirmwareVersion", "OSVersion", "FW_VERSION")
    private val NUMERIC_VERSION = Regex("^[0-9.]+$")
    private const val VERSION_COMPONENTS = 4

    /** The rule key Sony pulls out as its own app-version gate instead of matching. */
    const val CLIENT_VERSION_KEY = "ClientVersion"

    const val INSTALL_TYPE_BINARY = "binary"

    /** What a device-reported id may contain to be used as a URL path segment. */
    private val PATH_SEGMENT = Regex("^[A-Za-z0-9._-]+$")

    // ---- URL / device identity ---------------------------------------------

    /** @throws FeedException if either id would not be a safe single path segment. */
    fun infoUrl(categoryId: String, serviceId: String): String {
        // The device supplies these, so they must not be able to escape the path.
        requirePathSegment(categoryId, "categoryId")
        requirePathSegment(serviceId, "serviceId")
        return "https://$HOST/$categoryId/$serviceId/info/info.xml"
    }

    private fun requirePathSegment(value: String, what: String) {
        if (!PATH_SEGMENT.matches(value) || value == "." || value == "..") {
            throw FeedException("illegal $what for the info URL: '$value'")
        }
    }

    /**
     * spec §1.4: for three models Sony ignores the serial the device reports and
     * matches `SerialNo` rules against a pinned literal.
     */
    fun effectiveSerial(model: String, serial: String): String = when (model) {
        "WF-1000XM4" -> "1301211"
        "LinkBuds" -> "5630981"
        "LinkBuds S" -> "1300112"
        else -> serial
    }

    // ---- Header framing, cipher, digest chain ------------------------------

    /**
     * spec §3: the raw body is `key:value` header lines, a blank line, then the
     * (usually encrypted) info.xml. Decrypts per `eaid`, then checks the
     * two-stage digest chain of §3.3. Throws [FeedException] on any mismatch.
     */
    fun decodeInfo(raw: ByteArray, categoryId: String, serviceId: String): InfoDocument {
        val (headerLines, body) = splitHeaderBody(raw)
        val header = parseHeader(headerLines)
        val eaid = header["eaid"] ?: throw FeedException("info header has no eaid")
        val daid = header["daid"] ?: throw FeedException("info header has no daid")
        val digest = DigestType.fromDaid(daid) ?: throw FeedException("unknown daid $daid")
        val plain = decrypt(eaid, body)
        verifyDigest(plain, digest, header["digest"] ?: "", categoryId, serviceId)
        return InfoDocument(plain.toString(Charsets.UTF_8), digest)
    }

    /** Splits at the first empty line. Tolerates CRLF as well as bare LF. */
    private fun splitHeaderBody(raw: ByteArray): Pair<List<String>, ByteArray> {
        val lines = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < raw.size) {
            if (raw[i] == LF) {
                var end = i
                if (end > start && raw[end - 1] == CR) end--
                val line = String(raw, start, end - start, Charsets.UTF_8)
                if (line.isEmpty()) return lines to raw.copyOfRange(i + 1, raw.size)
                lines.add(line)
                start = i + 1
            }
            i++
        }
        throw FeedException("info feed has no blank line between header and body")
    }

    /** Sony splits on ':' but a value may legally contain one, so only the first counts. */
    private fun parseHeader(lines: List<String>): Map<String, String> {
        if (lines.isEmpty()) throw FeedException("info feed has an empty header")
        val map = HashMap<String, String>(lines.size)
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw FeedException("malformed info header line")
            map[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return map
    }

    private fun decrypt(eaid: String, body: ByteArray): ByteArray = when (eaid) {
        "ENC0001" -> body
        "ENC0002" -> decipher(body, "DESede", "DESede/ECB/NoPadding", TRIPLE_DES_KEY, DES_BLOCK)
        "ENC0003" -> decipher(body, "AES", "AES/ECB/NoPadding", AES_KEY, AES_BLOCK)
        else -> throw FeedException("unknown eaid $eaid")
    }

    /**
     * Sony asks for BouncyCastle's non-standard `ZeroBytePadding`; that name is
     * absent from a plain JCE provider, so decrypt raw and strip the zeros here.
     */
    private fun decipher(
        body: ByteArray,
        keyAlgorithm: String,
        transformation: String,
        key: ByteArray,
        blockSize: Int,
    ): ByteArray {
        if (body.isEmpty() || body.size % blockSize != 0) {
            throw FeedException("encrypted info body is not a multiple of $blockSize bytes")
        }
        val plain = try {
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, keyAlgorithm))
            cipher.doFinal(body)
        } catch (e: GeneralSecurityException) {
            throw FeedException("info body decryption failed: ${e.message}", e)
        }
        var end = plain.size
        while (end > 0 && plain[end - 1] == ZERO) end--
        return if (end == plain.size) plain else plain.copyOfRange(0, end)
    }

    /** spec §3.3: `digest == hex(h(hex(h(body)) + ServiceID + CategoryID))`. */
    private fun verifyDigest(
        body: ByteArray,
        digest: DigestType,
        expected: String,
        categoryId: String,
        serviceId: String,
    ) {
        if (digest == DigestType.NONE) {
            if (expected.isNotEmpty()) throw FeedException("HAS0001 requires an empty digest header")
            return
        }
        val algorithm = digest.jcaName ?: throw FeedException("no JCA name for $digest")
        val inner = hex(MessageDigest.getInstance(algorithm).digest(body))
        val outer = MessageDigest.getInstance(algorithm).digest(
            inner.toByteArray(Charsets.UTF_8) +
                serviceId.toByteArray(Charsets.UTF_8) +
                categoryId.toByteArray(Charsets.UTF_8),
        )
        if (!hex(outer).equals(expected, ignoreCase = true)) {
            throw FeedException("info digest mismatch")
        }
    }

    // ---- info.xml ----------------------------------------------------------

    /**
     * spec §4. The root element name is not constrained, so `ApplyCondition`
     * elements are located anywhere in the document.
     */
    fun parseInfo(xml: String): List<ApplyCondition> {
        // Parser feature support differs per platform, so refuse DTDs up front.
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) throw FeedException("info.xml parse failed: DTD not allowed")
        val document = try {
            hardenedBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw FeedException("info.xml parse failed: ${e.message}", e)
        }
        val conditions = document.getElementsByTagName("ApplyCondition")
        val out = ArrayList<ApplyCondition>(conditions.length)
        for (i in 0 until conditions.length) {
            val condition = conditions.item(i) as? Element ?: continue
            out.add(
                ApplyCondition(
                    rules = descendants(condition, "Rule").map {
                        UpdateRule(
                            key = it.getAttribute("Key"),
                            operator = it.getAttribute("Operator"),
                            value = it.getAttribute("Value"),
                        )
                    },
                    distributions = descendants(condition, "Distribution").map {
                        Distribution(
                            installType = it.getAttribute("InstallType"),
                            version = it.getAttribute("Version"),
                            uri = it.getAttribute("URI"),
                            mac = it.getAttribute("MAC"),
                            clientVersion = it.getAttribute("ClientVersion"),
                            size = it.getAttribute("Size").trim().toLongOrNull() ?: 0L,
                        )
                    },
                    descriptions = descendants(condition, "Description").map {
                        // textContent folds CDATA sections in for us.
                        Description(it.getAttribute("Lang"), it.textContent.orEmpty().trim())
                    },
                    defaultLang = descendants(condition, "Descriptions")
                        .firstOrNull { it.hasAttribute("DefaultLang") }
                        ?.getAttribute("DefaultLang"),
                ),
            )
        }
        return out
    }

    /** The feed is remote and attacker-reachable on a MITM, so no DTDs or external entities. */
    private fun hardenedBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureQuietly(
            factory,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            false,
        )
        runCatching { factory.setAttribute(ACCESS_EXTERNAL_DTD, "") }
        runCatching { factory.setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
        // Android's DocumentBuilderFactory throws UnsupportedOperationException here.
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }
        return factory.newDocumentBuilder()
    }

    private fun setFeatureQuietly(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        // Feature support varies between the JVM and Android parsers.
        runCatching { factory.setFeature(name, value) }
    }

    private fun descendants(root: Element, name: String): List<Element> {
        val nodes = root.getElementsByTagName(name)
        val out = ArrayList<Element>(nodes.length)
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        return out
    }

    // ---- Rule engine / selection -------------------------------------------

    /**
     * First [ApplyCondition] whose rules all pass and which offers a `binary`
     * [Distribution]. `ClientVersion` rules are skipped (spec §4: Sony uses that
     * key as its own min-app-version gate, which does not apply to this client).
     */
    fun select(
        conditions: List<ApplyCondition>,
        ctx: DeviceContext,
        digest: DigestType,
        lang: String = "English",
    ): AvailableUpdate? {
        val values = mapOf(
            "Model" to ctx.model,
            "FirmwareVersion" to ctx.firmwareVersion,
            "SerialNo" to ctx.serialNo,
            "Nation" to ctx.nation,
        )
        for (condition in conditions) {
            val passes = condition.rules.all {
                it.key == CLIENT_VERSION_KEY || ruleMatches(it, values)
            }
            if (!passes) continue
            val binary = condition.distributions
                .firstOrNull { it.installType == INSTALL_TYPE_BINARY } ?: continue
            return AvailableUpdate(
                version = binary.version,
                url = forceHttps(binary.uri),
                macHex = binary.mac,
                digest = digest,
                sizeBytes = binary.size,
                notes = pickDescription(condition, lang),
            )
        }
        return null
    }

    private fun pickDescription(condition: ApplyCondition, lang: String): String? {
        val byLang = condition.descriptions.firstOrNull { it.lang == lang }
        val byDefault = condition.defaultLang
            ?.let { d -> condition.descriptions.firstOrNull { it.lang == d } }
        return (byLang ?: byDefault ?: condition.descriptions.firstOrNull())
            ?.text
            ?.takeIf { it.isNotEmpty() }
    }

    /** spec §1.2: Sony rewrites an `http://` distribution URI to `https://`. */
    fun forceHttps(uri: String): String =
        if (uri.startsWith("http://")) "https://" + uri.removePrefix("http://") else uri

    /**
     * spec §4 operators. A key absent from [ctx] fails every operator except
     * `NotExist` (fail closed: we would rather skip a release than mis-target one).
     * An unknown operator fails too.
     */
    fun ruleMatches(rule: UpdateRule, ctx: Map<String, String>): Boolean {
        val actual = ctx[rule.key]
        when (rule.operator) {
            "Exist" -> return !actual.isNullOrEmpty()
            "NotExist" -> return actual.isNullOrEmpty()
        }
        if (actual == null) return false
        val expected = rule.value
        return when (rule.operator) {
            "Equal" -> compare(rule.key, actual, expected) == 0
            "NotEqual" -> compare(rule.key, actual, expected) != 0
            "LessThan" -> compare(rule.key, actual, expected) < 0
            "LessThanEqual" -> compare(rule.key, actual, expected) <= 0
            "GreaterThan" -> compare(rule.key, actual, expected) > 0
            "GreaterThanEqual" -> compare(rule.key, actual, expected) >= 0
            "StartWith" -> actual.startsWith(expected)
            "NotStartWith" -> !actual.startsWith(expected)
            "EndWith" -> actual.endsWith(expected)
            "NotEndWith" -> !actual.endsWith(expected)
            "Include" -> actual.contains(expected)
            "Exclude" -> !actual.contains(expected)
            else -> false
        }
    }

    private fun compare(key: String, actual: String, expected: String): Int =
        if (key in VERSION_KEYS &&
            NUMERIC_VERSION.matches(actual) &&
            NUMERIC_VERSION.matches(expected)
        ) {
            compareDottedVersions(actual, expected)
        } else {
            actual.compareTo(expected)
        }

    /** Up to four integer components; a missing or unparsable component is 0. */
    private fun compareDottedVersions(a: String, b: String): Int {
        val left = versionComponents(a)
        val right = versionComponents(b)
        for (i in 0 until VERSION_COMPONENTS) {
            val diff = left[i].compareTo(right[i])
            if (diff != 0) return diff
        }
        return 0
    }

    private fun versionComponents(value: String): IntArray {
        val out = IntArray(VERSION_COMPONENTS)
        val parts = value.split('.')
        for (i in 0 until minOf(VERSION_COMPONENTS, parts.size)) {
            out[i] = parts[i].toIntOrNull() ?: 0
        }
        return out
    }

    // ---- Downloaded binary -------------------------------------------------

    /**
     * spec §5: exact size, then the `MAC` digest over the raw bytes. An empty
     * `MAC` (or `daid=HAS0001`) means Sony checks size only.
     */
    fun verifyBinary(
        bytes: ByteArray,
        expectedSize: Long,
        macHex: String,
        digest: DigestType,
    ): Boolean {
        if (bytes.size.toLong() != expectedSize) return false
        if (macHex.isBlank()) return true
        val algorithm = digest.jcaName ?: return true
        val actual = hex(MessageDigest.getInstance(algorithm).digest(bytes))
        return actual.equals(macHex.trim(), ignoreCase = true)
    }

    /** spec §5: the transfer filename is `hex(basename.hashCode())`, not the basename. */
    fun fileNameFor(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        return Integer.toHexString(path.substringAfterLast('/').hashCode())
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    // javax.xml.XMLConstants does not carry these on Android, so spell them out.
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA =
        "http://javax.xml.XMLConstants/property/accessExternalSchema"
    private const val HEX = "0123456789abcdef"
    private const val LF: Byte = 0x0A
    private const val CR: Byte = 0x0D
    private const val ZERO: Byte = 0x00
}
