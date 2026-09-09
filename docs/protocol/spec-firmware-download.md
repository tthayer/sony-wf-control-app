# Sony "Sound Connect" / MDR app — Firmware discovery & download ("AutoMagic" feed)

Decompiled from com.sony.songpal.mdr 13.2.2 (jadx sources). All paths below are
relative to the sources root
`.../scratchpad/sony/jadx/sources`. Obfuscated single-letter class names are
called out with their package so they can be re-located.

The subsystem is internally called **"AutoMagic"** (package `com.sony.songpal.automagic`,
helper interfaces in package `ah`, orchestration in package `mu`, model-specific
capability plumbing in packages `q20` / `r20`).

---

## 1. URL construction

### 1.1 Info-feed URL

`com/sony/songpal/automagic/e.java:46-54` (class `e`, private method `a`):

```java
private static URL a(String str, String str2) throws InternalException {
    String str3 = String.format(Locale.getDefault(),
            "https://%s/%s/%s/info/%s",
            "info.update.sony.net", str, str2, "info.xml");
    ...
    return new URL(str3);
}
```

Template:

```
https://info.update.sony.net/{CategoryID}/{ServiceID}/info/info.xml
```

- `{CategoryID}` and `{ServiceID}` are opaque path segments, not literal
  strings like "HP002"/"MDRID" (those greps in the task brief did not match
  anything in this build — see "Unknowns" below).
- No query string, no additional path elements. Plain HTTPS GET (see §2).

### 1.2 Firmware / EULA / notice binary URL

The binary URL is **not** constructed by the client — it comes verbatim out
of the decrypted `info.xml` body, as the `URI` attribute of a `<Distribution>`
element (see §4). The one piece of client-side logic applied to it:

`com/sony/songpal/automagic/d.java:44-64` (inner class `d.a`, constructor):

```java
a(String str, String str2, String str3, String str4, int i11) {
    this.f29953b = str;                  // Version
    this.f29952a = (str2 != null) ? a(str2) : "";   // URI, upgraded http->https
    this.f29954c = str3;                 // MAC (expected digest, hex)
    this.f29955d = str4;                 // ClientVersion (min app version gate)
    this.f29956e = i11;                  // Size (bytes)
}
private static String a(String str) {
    if (!str.startsWith("http://")) return str;
    return str.replaceFirst("http://", "https://");   // force TLS
}
```

So any `http://` URI found in info.xml is rewritten to `https://` before use;
otherwise it is used as-is. The binary itself is fetched with the same
`HttpsDownloader` (plain HTTPS GET, no auth).

### 1.3 Where CategoryID / ServiceID / model-identifying fields come from

**They are read live from the headphones over the Tandem BLE/SPP protocol —
there is no static per-model URL table** (aside from one narrow override,
see §1.4). Chain of custody:

1. `r20/a.java` (`class a extends q20.b`) is the Tandem "table1" feature that
   queries the device for 5 `UpdateInquiredType` fields on `a()` (constructor
   registers them at lines 84-88):
   `CATEGORY_ID`, `SERVICE_ID`, `NATION_CODE`, `LANGUAGE`, `SERIAL_NUMBER`.
2. Wire format — Tandem Table1 protocol
   (`com/sony/songpal/tandemfamily/message/mdr/v1/table1/param/UpdateInquiredType.java`):

   ```
   NO_USE = 0x00
   FW_UPDATE_MODE = 0x01
   CATEGORY_ID = 0x02
   SERVICE_ID = 0x03
   NATION_CODE = 0x04
   LANGUAGE = 0x05
   SERIAL_NUMBER = 0x06
   BLE_TX_POWER = 0x07
   BATTERY_POWER_THRESHOLD = 0x08
   UPDATE_METHOD = 0x09
   BATTERY_POWER_THRESHOLD_FOR_INTERRUPTIONG_FW_UPDATE = 0x0A
   UNIQUE_ID_FOR_DEVICE_BINDING = 0x2A   // BSON.REGEX == 0x0B *decoded as 11 by jadx, verify*
   OUT_OF_RANGE = -1
   ```

3. Request: `qe0/j0.java` — Command **`UPDT_GET_PARAM`**, payload = `[opcode][UpdateInquiredType byte]`.
4. Response: `qe0/x2.java` — Command **`UPDT_RET_PARAM`**, payload =
   `[opcode][UpdateInquiredType byte][value bytes...]`. For the 6 string-typed
   inquiry types (Category/Service/Nation/Language/Serial/UniqueIdForBinding)
   the value is parsed by `se0/z0.java`:

   `se0/z0.java:66-69`:
   ```java
   private void e(byte[] bArr) {
       int iMin = Math.min(com.sony.songpal.util.e.m(bArr[0]), 128);
       this.f74077b = iMin > 0 ? com.sony.songpal.util.z.b(bArr, 1, iMin) : "";
   }
   ```
   i.e. `byte[0]` = unsigned length (capped at 128), followed by that many
   UTF-8 bytes (`com/sony/songpal/util/z.java:12`: `new String(bArr, i11, i12, "UTF-8")`).

5. Fields collected into `q20.a` (`q20/a.java`), whose `toString()`
   (lines 85-86) literally documents the semantics:
   ```
   Category ID / Service ID / Nation Code / Language / Serial Number /
   Update Mode / Auto Update
   ```
   Getters: `a()`=CategoryID, `e()`=ServiceID, `c()`=NationCode,
   `b()`=Language, `d()`=SerialNumber.

6. `com.sony.songpal.mdr.j2objc.tandem.c` (interface, `com/sony/songpal/mdr/j2objc/tandem/c.java`)
   supplies two more strings from the general device-info feature: `c()`
   (used as the device **model name**, e.g. "WF-1000XM4") and `i()` (used
   downstream as **current firmware version** for the `FirmwareVersion` rule
   key — inferred from usage in `mu/d.java` logs and `com/sony/songpal/mdr/application/update/csr/CsrUpdateController.java:339-340`,
   not from a javadoc'd getter name).

7. Orchestration: `com/sony/songpal/mdr/j2objc/feature/fwupdate/AutoMagicDownloadTask.java:346`:
   ```java
   s().b(this.f39008g.a(), this.f39008g.e(), this.f39003b, this.f39004c,
         this.f39008g.b(), this.f39008g.c(), this.f39008g.d(),
         this.f39005d, this.f39006e, this.f39007f, new a());
   ```
   i.e. `k.b(CategoryID, ServiceID, modelName, fwVersion, Language, NationCode, SerialNumber, digestImpl, cipherImpl, langCodeResolver, callback)`.
   `mu/k.java:22-38` forwards straight to
   `com.sony.songpal.automagic.a.e(str, str2, str3, str4, str5, str6, str7, eVar, cVar, langCode)`
   which builds the info-feed URL from `(str=CategoryID, str2=ServiceID)`
   and filters the parsed info.xml with `(str3=modelName, str4=fwVersion,
   str5=Language, str6=NationCode, str7=SerialNumber)`.

The CSR (older Bluetooth chip) update path,
`com/sony/songpal/mdr/application/update/csr/CsrUpdateController.java:339-361`,
uses the exact same `q20.a`/`mu.k`/`automagic.a.e` call for `Target==FW`
(chip firmware), confirming the feed and crypto are shared infrastructure.
For `Target==VOICE_GUIDANCE`/voice packages it instead pulls CategoryID/
Language from a different capability object (`i70.m`/`i70.l`) and takes
**ServiceID from an Android Intent extra `"KEY_LANGUAGE_SERVICE_ID"`**
rather than from the device (line ~354).

### 1.4 Static per-model override table

The only hardcoded per-model table found is a 3-entry override of the
**SerialNumber** parameter (`str7`) in
`com/sony/songpal/automagic/a.java:63-72` (method `e`, the public
`getUpdateInformation` entry point):

```java
public static ah.a e(String str, String str2, String str3, String str4,
                      String str5, String str6, String str7,
                      ah.e eVar, ah.c cVar, LangCode langCode) {
    if (str3.equals("WF-1000XM4")) {
        str7 = "1301211";
    } else if (str3.equals("LinkBuds")) {
        str7 = "5630981";
    } else if (str3.equals("LinkBuds S")) {
        str7 = "1300112";
    }
    ...
}
```

`str3` is the model name (see §1.3 step 6). This is a **workaround/pinned
serial**, not a category/service-ID table — for these three models the
info.xml `Rules` matching against `SerialNo` uses the fixed literal instead
of whatever the device reported. There is no larger static table mapping
model names → CategoryID/ServiceID anywhere in the searched packages; those
two values are always read from the device live (§1.3).

---

## 2. Request details

- **Method:** plain HTTPS `GET`. `com/sony/songpal/automagic/HttpsDownloader.java:69-90`.
  Opens `(HttpsURLConnection) url.openConnection()`, optionally sets
  `User-Agent` if a non-null UA string was supplied to the constructor
  (`HttpsDownloader(URL, String)`), calls `connect()`, requires HTTP 200,
  reads the full body into a `ByteArrayOutputStream`.
- **User-Agent:** the call sites that build the info-feed URL
  (`com/sony/songpal/automagic/e.java:82`) and the binary URL
  (`com/sony/songpal/automagic/a.java:19`) both pass `null` for the UA
  parameter, i.e. **no custom User-Agent header is sent** for AutoMagic
  requests — default `java.net` UA. (`mu/d.java`'s downloader for the actual
  firmware bytes, class `c` at line 353, uses a separate
  `y70.b`/`y70.a` wrapper around `HttpsURLConnection` — worth checking `y70/*`
  if a header is ever added there; not found in the files read for this task.)
- **Other headers / query params:** none observed. No API key, no auth token,
  no cookies.
- **TLS:** default `javax.net.ssl.HttpsURLConnection` / platform trust store.
  No custom `TrustManager`, `SSLSocketFactory`, `CertificatePinner`, or
  `checkServerTrusted` override was found in `automagic`, `ah`, `mu`, `ix`,
  `q20`, `r20` — i.e. **no certificate/public-key pinning** in this part of
  the app (a third-party client can use a normal TLS stack against
  `info.update.sony.net`).
- **Firmware binary URL location:** yes — it is inside the decrypted/parsed
  `info.xml` body, as `Distribution/@URI` (see §1.2, §4). It is not derivable
  from the outer feed URL alone.

---

## 3. Decryption of the info.xml response

### 3.1 Framing (info.xml over the wire is NOT plain XML)

`com/sony/songpal/automagic/e.java:89-147` (method `d`) parses the raw HTTP
body as: a **text header made of `key:value` lines terminated by `\n` (0x0A)**,
followed by a **blank line** (i.e. the header block ends at a line with
`i12 <= 0`, meaning two consecutive `\n`s), followed by the **binary/encrypted
body** (everything after that point, taken byte-for-byte to EOF).

- The header is **not** 0xFF-delimited as the task brief guessed — it is
  ordinary newline-delimited ASCII text, one `key:value` pair per line,
  parsed by `InformationHeader.f()`:

  `com/sony/songpal/automagic/InformationHeader.java:32-44`:
  ```java
  public static InformationHeader f(List<String> list) {
      HashMap map = new HashMap();
      for (String line : list) {
          List<String> kv = Arrays.asList(line.split(":"));
          if (kv.size() != 2 || kv.get(0).length() <= 0) { map.clear(); break; }
          map.put(kv.get(0), kv.get(1));
      }
      return e(map);
  }
  ```
- Known header keys (`InformationHeader.java:46-74`):
  - `digest` — expected MAC/digest value (hex string), see §3.3.
  - `daid` — digest-algorithm id: `HAS0001`=NONE, `HAS0002`=MD5, `HAS0003`=SHA1, else UNKNOWN.
  - `eaid` — encryption-algorithm id: `ENC0001`=NONE, `ENC0002`=3DES(`TRIPLE_DES`), `ENC0003`=AES, else UNKNOWN.

  (The task brief's `ENC0003`/`HAS0003` guesses are confirmed exactly: `ENC0003`
  = AES, `HAS0003` = SHA-1 — these are the values actually used in production,
  per `d.java`/`c.java`'s enum handling being reachable only for MD5/SHA1/AES/NONE.)

### 3.2 Cipher

`ah/d.java` (class `d implements ah.c`) — **hardcoded key material**:

```java
private static final byte[] f477b = {   // 3DES key, all-zero, 24 bytes
    0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0
};
private static final byte[] f478c = {   // AES key, 16 bytes
    79,-94,121,-103,-1,-48,-117,31,-28,-46,96,-43,123,109,60,23
};

public byte[] a(byte[] bArr) {                       // AES path (ENC0003)
    SecretKeySpec key = new SecretKeySpec(f478c, "AES");
    Cipher cipher = Cipher.getInstance("AES/ECB/ZeroBytePadding");
    cipher.init(Cipher.DECRYPT_MODE /* 2 */, key);
    return cipher.doFinal(bArr);
}

public byte[] b(byte[] bArr) {                        // 3DES path (ENC0002)
    SecretKeySpec key = new SecretKeySpec(f477b, "DESede");
    Cipher cipher = Cipher.getInstance("DESede/ECB/ZeroBytePadding");
    cipher.init(Cipher.DECRYPT_MODE /* 2 */, key);
    return cipher.doFinal(bArr);
}
```

**AES key, hex (16 bytes / AES-128), for `eaid=ENC0003`:**

```
4F A2 79 99 FF D0 8B 1F E4 D2 60 D5 7B 6D 3C 17
```

**3DES key, hex (24 bytes), for `eaid=ENC0002` (legacy/unused fallback):**

```
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

Both are **ECB mode** — no IV. Padding scheme is Android/BouncyCastle's
non-standard `"ZeroBytePadding"` (zero-byte padding, i.e. trailing 0x00 bytes
padded to the block size and stripped on decrypt) — this is *not* PKCS7/PKCS5;
a standard JCE provider needs `Cipher.getInstance("AES/ECB/NoPadding")` plus
manual zero-stripping, or a BouncyCastle provider that supports the literal
`ZeroBytePadding` algorithm name.

`eaid=ENC0001` (NONE) skips decryption entirely (`e.java:56-68`); body is
used raw. `eaid` = anything else → `EncryptionType.UNKNOWN`, and decoding
throws `INVALID_INFORMATION_FILE_HEADER` before even reaching the cipher
(see `e.java` — only `TRIPLE_DES` and `AES` branch into decrypt calls; the
`else` implicitly means NONE/UNKNOWN pass `bArr` through unchanged, but
UNKNOWN is rejected earlier).

### 3.3 Digest / MAC verification

`com/sony/songpal/automagic/c.java` (class `c`) + `ah/f.java` (class
`f implements ah.e`, plain `MessageDigest.getInstance("MD5")` /
`"SHA-1")`.

Verification is a **two-stage hash**, done in
`com/sony/songpal/automagic/e.java:149-172` (method `e`):

1. `strA = c.a(decryptedBody, digestType, digestImpl)` — hex-lowercase digest
   (MD5 or SHA-1, per `daid`) of the **decrypted** body bytes.
   (`c.b()`, `c.java:57-66`, formats each byte as `%02x`.)
2. Build `buf = UTF8(strA) + UTF8(ServiceID) + UTF8(CategoryID)` — i.e. the
   hex digest string, followed by the outer feed URL's second path segment
   (`str2`=ServiceID), followed by the first path segment (`str`=CategoryID).
   (Note the concatenation order in `e.java:160-164`: `strA`, then `str3`
   which at the call site is the *outer* `str2`=ServiceID, then `str2` which
   at the call site is the *outer* `str`=CategoryID — i.e. `digest(body) ||
   ServiceID || CategoryID`, NOT `|| CategoryID || ServiceID`. Double-check
   parameter order carefully if reimplementing — see raw excerpt below.)
3. `strB = digest(buf)` (same algorithm), and compare
   `strB.equals(header["digest"])`. Mismatch → `WRONG_DIGEST`.

Raw excerpt for exact parameter order (`e.java:73` call site and `e.java:149-172`):
```java
e(bArr2, informationHeader.c(), informationHeader.a(), str, str2, eVar);
// signature: e(byte[] bArr, DigestType digestType, String str, String str2, String str3, ah.e eVar)
//   bArr   = decrypted body
//   digestType = header daid
//   str    = header "digest" field (expected value)
//   str2   = outer c()'s `str`  (CategoryID, 1st URL path segment)
//   str3   = outer c()'s `str2` (ServiceID, 2nd URL path segment)
...
String strA = c.a(bArr, digestType, eVar);              // hex(hash(body))
ByteArrayOutputStream out = new ByteArrayOutputStream();
out.write(strA.getBytes("UTF-8"));
out.write(str3.getBytes("UTF-8"));   // ServiceID
out.write(str2.getBytes("UTF-8"));   // CategoryID
if (!c.c(str, out.toByteArray(), digestType, eVar)) throw WRONG_DIGEST;
```
So: `expected_digest == hex(hash( hex(hash(decrypted_body)) + ServiceID + CategoryID ))`,
algorithm = MD5 or SHA-1 selected by `daid`. If `daid=HAS0001` (NONE), the
check instead requires `header["digest"]` to be the empty string.

### 3.4 Gzip

No gzip/deflate handling was found anywhere in the AutoMagic path
(`java.util.zip`/`GZIPInputStream` do not appear in `automagic`, `ah`, `mu`,
`q20`, `r20`). The decrypted body is fed directly to
`new String(bArr, "UTF-8")` and parsed as XML text
(`com/sony/songpal/automagic/g.java:93-107`). So: **no gzip step** in this
app version — the task brief's mention of gzip does not apply here (may be
present in older/other Sony apps, not this build).

### Minimal decode recipe for a third-party client

```
1. GET https://info.update.sony.net/{CategoryID}/{ServiceID}/info/info.xml
2. Split response bytes at first blank line (double 0x0A) into header/body.
3. Parse header lines as "key:value" -> {eaid, daid, digest, ...}.
4. If eaid == ENC0003: AES-128-ECB, key = 4FA27999FFD08B1FE4D260D57B6D3C17,
   ZeroByte-padded, decrypt body.
   If eaid == ENC0002: 3DES-ECB, key = 24 zero bytes, ZeroByte-padded.
   If eaid == ENC0001: body already plaintext.
5. If daid == HAS0003 (SHA-1) or HAS0002 (MD5):
     h1 = hex(hash(decrypted_body))
     h2 = hex(hash(h1_bytes_as_ascii + ServiceID_ascii + CategoryID_ascii))
     assert h2 == header["digest"]
   If daid == HAS0001: assert header["digest"] == ""
6. decrypted_body is UTF-8 XML text -> parse per §4.
```

---

## 4. info.xml schema (post-decryption)

Parsed generically into a tree of elements by
`com/sony/songpal/automagic/g.java` (a minimal XmlPullParser-based DOM, class
`h` = element node with name/attrs/children/text/CDATA-bytes). The
AutoMagic-specific schema is interpreted in `com/sony/songpal/automagic/d.java`.

Top-level structure (element names are literal, discovered from string
constants in `d.java`):

```xml
<Root>                                  <!-- actual root tag name not constrained by parser -->
  <ApplyConditions>
    <ApplyCondition>
      <Rules>
        <Rule Type="..." Key="Model|FirmwareVersion|SerialNo|Nation|OSVersion|FW_VERSION|ClientVersion|..."
              Value="..." Operator="Equal|NotEqual|Exist|NotExist|LessThan|LessThanEqual|
                                     GreaterThan|GreaterThanEqual|StartWith|NotStartWith|
                                     EndWith|NotEndWith|Include|Exclude"/>
        ...
      </Rules>
      <Distributions>
        <Distribution InstallType="binary|EULA|notice"
                       Version="..." URI="http(s)://..." MAC="<hex digest>"
                       ClientVersion="<min app version, e.g. 8.01.00>" Size="<bytes, decimal string>"/>
        ...
      </Distributions>
      <Descriptions DefaultLang="...">
        <Description Lang="...">CDATA release-notes text</Description>
        ...
      </Descriptions>
    </ApplyCondition>
    ...
  </ApplyConditions>
</Root>
```

Semantics (`com/sony/songpal/automagic/d.java`):

- **Rule matching** (`d.b()`, lines 206-246): for each `<ApplyCondition>`,
  every `<Rule>` must pass against a context map built from the caller's
  input — `d.d()` lines 248-255:
  ```java
  map.put("Model", modelName);
  map.put("FirmwareVersion", currentFwVersion);
  map.put("SerialNo", serialNumber);
  map.put("Nation", nationCode);
  ```
  (`Language`/ServiceID/CategoryID are **not** in the rule-matching context —
  they only steer which feed URL is fetched.) The special key
  `"ClientVersion"` is **skipped** during rule matching (`d.java:218-219`) —
  it's pulled out separately as the app's own min-version gate (see below).
  For keys `OSVersion`/`FirmwareVersion`/`FW_VERSION`, comparisons use
  numeric dotted-version compare (`d.j()`/`d.k()`, up to 4 components,
  integer-parsed) when both sides match `^[0-9.]+$`, else falls back to
  lexicographic string compare (`d.l()`). All other keys always use the
  string comparator `d.i()`.
- An `<ApplyCondition>` whose Rules all pass contributes its
  `<Distributions>` (grouped by `InstallType`) and `<Descriptions>` into the
  merged result map. `InstallType="binary"` → firmware/voice binary,
  `"EULA"` → license text file, `"notice"` → release-notes/notice file. Only
  the **first** matching `Distribution` per `InstallType` across all passing
  `ApplyCondition`s is used (`d.b.c()`/`d.b.e()`/`d.b.f()` all take
  `listB.get(0)`).
- `<Distribution>` attributes → `ah.b` fields (`ah/b.java`, `toString()`
  spells them out): `URL` (from `URI`, http→https-fixed), `BinaryVersion`
  (from `Version`), `DigestType` (from the outer `daid`, not per-file),
  `ClientVersion` (used as the **minimum app version required to accept this
  update** — gate logic below), `FileSize` (from `Size`).
  A `DigestID`/`MAC` field is also attached from the `<Distribution>`'s
  `MAC` attribute plus, if present, the header's `daid` string stashed under
  key `"DigestID"` (`d.java:559-561`) — this is the **expected digest of the
  downloaded binary**, checked in §5.
- `<Description>` per-language text: matched by `Lang` attribute against the
  app's current UI language (`LangCode.getCodeForDescription()` — human
  words like `"English"`, `"French"`, `"Japanese"`, see
  `com/sony/songpal/automagic/LangCode.java`), falling back to
  `Descriptions/@DefaultLang`, then to the first `<Description>`
  (`d.b.d()`). EULA text specifically is stored as `<Text>` child CDATA under
  the matching `<EULA>`/locale element and looked up via `Locale`/
  `DefaultLocale` attributes (`com/sony/songpal/automagic/b.java`) — this is
  a **separate mini-XML fetched from the EULA Distribution's own URI**, not
  inline in info.xml (`automagic/a.java:47-53`: `c()` downloads
  `aVarE.f29952a` via `downloadFileAndVerify` then re-parses it as XML and
  extracts EULA text by locale).
- **Update-available decision**: `automagic/a.java:80-84` — if no
  `ApplyCondition` matched (`bVarF == null`), result is
  `INFORMATION_FILE_ERROR`; if a condition matched but yielded no
  `binary` Distribution... (`bVarF.g()` just checks the map is non-null);
  the overall `needUpdate` boolean on `ah.a` is `true` whenever a
  `binary` Distribution was found for a passing `ApplyCondition` (see
  `ah/a.java`, field `f465b`/getter `f()`).

### App min-version gate (separate from the XML `Rules`)

`mu/h.java:52-94` (`f(List<ah.b>, long appVersionCode)`):
- Reads `ClientVersion` off the (first) binary distribution, regex-matches
  a leading `NN.NN.NN` (each 1-2 digits), zero-pads each component to 2
  digits with `DecimalFormat("00")`, concatenates to a 6-digit number
  `minAppVersion`.
- Compares against `appVersion = passedAppVersionCode / 100`.
- Returns `true` ("needs newer app, **skip this update**") when
  `minAppVersion > appVersion`.

---

## 5. Firmware binary handling

`mu/d.java` (class `d`, the download engine) + `ix/c.java` (`FwFileStruct`).

- **Download:** plain HTTPS GET of the `Distribution/@URI` (https-forced,
  §1.2), streamed via `mu.d$c` (an internal downloader wrapping
  `y70.a`/`y70.b`, distinct from `HttpsDownloader` used for info.xml, but
  functionally the same: raw bytes, no decompression).
- **Verification**, `mu/d.java:402-438` (`m()`, `n()`, `o()`):
  1. `o(expectedSize, actualSize)`: exact byte-length match against
     `Distribution/@Size`. Mismatch → `Errors$DistributionFileError.WrongSize`.
  2. `n(expectedMac, digestType, bytes, digestImpl)`: recompute MD5 or SHA-1
     (per the info.xml header's `daid`/`DigestType`) over the **raw
     downloaded bytes** and hex-compare to `Distribution/@MAC`. Mismatch →
     `Errors$DistributionFileError.WrongMAC`. (`s.b(str)` at line 416 is an
     "is-null-or-empty" helper — if `Distribution/@MAC` is empty, the MAC
     check is skipped entirely and only size is verified.)
- **No decryption/transformation of the firmware binary itself** — the AES/
  3DES cipher in `ah.d` is only ever used on the info.xml body, never on the
  downloaded `.bin`. The verified bytes are handed to callers unmodified as
  `ix.c` (`FwFileStruct{mData, mVersion, mFilename, mDigestType, mMac}`,
  `ix/c.java`), where `mFilename` is derived from the URI by
  `AutoMagicDownloadTask.p()` (`.../AutoMagicDownloadTask.java:293-302`):
  last path segment of the URI, then `Integer.toHexString(name.hashCode())`
  — i.e. **the on-disk/transfer filename is a hash of the URL's basename**,
  not the original filename.
- **Storage:** in-memory only inside this task object
  (`mu.d.f65402c` byte[]), with a simple single-slot "cache" keyed by
  `(fwVersion, url, modelName)` (`mu/d.java:466-476`, `k()`) to avoid
  re-downloading if `getUpdateInformation` is re-queried with an identical
  answer. No evidence of writing the `.bin` to app-private storage/disk in
  the files reviewed — it is passed as a `byte[]` up through
  `AutoMagicDownloadTask` → `ix.c`/`ix.d` to whatever consumes
  `DownloadCallback.b(SUCCESS, List<ix.c>, fwVersion)`, presumably the
  Bluetooth transfer library (not traced further — out of scope of the
  `automagic`/`mu`/`ix` packages).
- **Firmware vs. voice-guidance/other targets:**
  `com/sony/songpal/mdr/j2objc/tandem/UpdateCapability.java`:
  - `enum Target { FW, VOICE_GUIDANCE, SONY_VOICE_ASSISTANT }` — this
    classifies *what* is being updated. Chip-firmware updates use `Target.FW`
    (see CSR path switch in `CsrUpdateController.java:341-360`, case 1).
    Voice-guidance/assistant packages (case 2) still go through the exact
    same AutoMagic feed/crypto/verify pipeline, but source their
    CategoryID/Language from a different capability object (`i70.m`) and
    their ServiceID from an **Android Intent extra
    `"KEY_LANGUAGE_SERVICE_ID"`** instead of a live device query — i.e. the
    "which language pack" selection is driven by app UI, not the headset.
  - `enum LibraryType { CSR, MTK_RHO_W_DISCONNECTION,
    MTK_TRANSFER_WO_DISCONNECTION, TANDEM, USING_MC_APP, NOT_SUPPORTED }` —
    this selects which underlying Bluetooth transfer mechanism actually
    pushes the verified bytes to the headset; it is orthogonal to the
    AutoMagic download/verify step documented here.

---

## 6. Version comparison / update-available logic

Two independent version checks gate whether an update is offered:

1. **info.xml `<Rule>`-based gating** (§4) — arbitrary per-release rules,
   commonly against `FirmwareVersion` (current on-device version, read via
   Tandem as `com.sony.songpal.mdr.j2objc.tandem.c.i()`) with operators like
   `GreaterThan`/`LessThan`/`Equal` etc. Numeric dotted-version compare (up
   to 4 integer components, e.g. `"3.1.2"` / `"3.1.2.0"`) is used only when
   *both* sides of the rule are pure `[0-9.]+`; otherwise a plain string
   compare is used (`d.java` methods `j`, `k`, `l`, lines 353-543).
   The value being compared against the rule's `Value` for
   Firmware/OS-version keys is effectively the current device firmware
   string as reported by the Tandem "table1" general-info feature — exact
   format is device-reported (commonly `"N.N.N"`), not independently
   validated by the app beyond the regex above.
2. **App min-version gate** (`mu/h.java`, §4 "App min-version gate") — the
   chosen binary's own `ClientVersion` attribute (format `NN.NN.NN`, 1-2
   digits per component) is compared, zero-padded and concatenated into a
   6-digit integer, against `appVersionCode/100`; if the app is older than
   required, the update is suppressed (`AVAILABLE` flips to effectively
   not-offered by returning `f(...)==true` "skip download").

An update is ultimately surfaced to the UI (`UpdateAvailability.AVAILABLE`)
when: at least one `<ApplyCondition>` matched, it contained an
`InstallType="binary"` Distribution, and `ah.a.f()` (needUpdate) is true —
see `AutoMagicDownloadTask$a.a()`,
`.../AutoMagicDownloadTask.java:109-152`.

---

## Key file/line index

| Concern | File : lines |
|---|---|
| Info-feed URL builder | `com/sony/songpal/automagic/e.java:46-54` |
| Header/body framing + digest orchestration | `com/sony/songpal/automagic/e.java:56-172` |
| Header key parser (`eaid`/`daid`/`digest`) | `com/sony/songpal/automagic/InformationHeader.java:32-75` |
| Cipher impl + hardcoded keys | `ah/d.java` |
| Digest impl (MD5/SHA-1) | `ah/f.java` |
| Digest formatting/compare | `com/sony/songpal/automagic/c.java` |
| Plain-HTTPS GET client | `com/sony/songpal/automagic/HttpsDownloader.java` |
| Minimal XML DOM parser | `com/sony/songpal/automagic/g.java`, `com/sony/songpal/automagic/h.java` |
| info.xml schema interpretation / rule engine | `com/sony/songpal/automagic/d.java` |
| Distribution value object | `ah/b.java`, `ah/a.java` |
| Public AutoMagic entry point + model override table | `com/sony/songpal/automagic/a.java:16-88` |
| Orchestration facade | `mu/k.java`, `mu/i.java` |
| Firmware binary download/verify/cache | `mu/d.java` |
| Firmware bytes container | `ix/c.java`, `ix/d.java` |
| Task/state machine, filename hashing | `com/sony/songpal/mdr/j2objc/feature/fwupdate/AutoMagicDownloadTask.java` |
| App min-app-version gate | `mu/h.java:52-94` |
| Tandem query for Category/Service/Nation/Language/Serial | `r20/a.java` |
| `q20.a` value object (field semantics via toString) | `q20/a.java` |
| Tandem UpdateInquiredType byte codes | `com/sony/songpal/tandemfamily/message/mdr/v1/table1/param/UpdateInquiredType.java` |
| Tandem request/response codec for those fields | `qe0/j0.java`, `qe0/x2.java`, `se0/z0.java` |
| CSR (legacy chip) path using same feed | `com/sony/songpal/mdr/application/update/csr/CsrUpdateController.java:329-361` |
| Target/LibraryType enums | `com/sony/songpal/mdr/j2objc/tandem/UpdateCapability.java` |
| Language code table | `com/sony/songpal/automagic/LangCode.java` |

---

## Unknowns / not resolved in this pass

- The literal values of `CategoryID`/`ServiceID` for any real device (e.g.
  what WF-1000XM4 actually reports) were not observed — they come from a
  live device query (§1.3), not a string constant in the app, so they
  cannot be extracted from static analysis alone. A real device (or a BLE
  capture) is needed to get concrete values. Grepping for `"HP002"` and
  `"MDRID"` (as suggested in the task brief) produced **zero matches**
  anywhere in the decompiled sources — those identifiers are not present in
  this app build, or belong to a different Sony app family/protocol
  generation.
- Whether `mu.d$c`'s downloader (`y70.a`/`y70.b`, used specifically for the
  firmware **binary** GET, as opposed to `HttpsDownloader` used for
  info.xml) sets any extra headers was not confirmed — the `y70` package
  itself was not inspected in this pass.
- `com.sony.songpal.mdr.j2objc.tandem.c`'s `c()`/`i()` getters are inferred
  to be "model name" / "current firmware version" purely from call-site
  usage (logging strings like `targetModelName`, and matching against rule
  key `FirmwareVersion`) — the interface itself carries no javadoc/field
  names, so this should be double-checked against a live device exchange if
  precision matters.
- `UNIQUE_ID_FOR_DEVICE_BINDING`'s byte code is written in source as
  `BSON.REGEX` (an unrelated imported constant reused for its numeric
  value by the obfuscator/R8) — the actual byte value (likely `0x0B`) should
  be double-checked against `org.bson.BSON.REGEX`'s numeric value rather
  than assumed.
- Whether `ClientVersion` on non-`binary` Distributions (EULA/notice) is
  ever populated/consulted was not traced beyond the generic `d.a`
  constructor — likely irrelevant, since `mu.h.f()` only reads
  `listA.get(0)` off the **binary** list.
- The exact wire encoding Tandem uses to request `UPDT_GET_PARAM`/receive
  `UPDT_RET_PARAM` at the transport-frame level (SPP/L2CAP header, checksum,
  Table1 vs Table2 command-set selection) was not traced — only the
  Table1 command payload layout (`qe0/j0.java`, `qe0/x2.java`) was examined,
  which is presumably enough given this app is `sonycontrol`/Tandem-family
  and the user's other project already implements the Tandem transport.
