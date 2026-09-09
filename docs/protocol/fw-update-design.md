# Firmware update — design and API contract

Source of truth for wire formats: `spec-tandem-fota.md` (transfer), `spec-firmware-download.md`
(discovery/decrypt), `spec-update-orchestration.md` (gating). This file fixes the Kotlin API so the
protocol, feed, and UI layers can be built independently and compile together.

Scope of v1: **Tandem FOTA** (firmware carried inside the existing MDR RFCOMM link). Devices that
advertise an MTK/Airoha update method get an update *check* and a "use the Sony app" message; the
Airoha RACE transport (`spec-airoha-fota.md`) is out of scope.

All protocol/feed code is pure Kotlin/JVM (no `android.*`), unit-tested on the JVM like the existing
`protocol/` package. Package: `com.thelightphone.sonywf.protocol` (existing) and
`com.thelightphone.sonywf.update` (new).

---

## 1. Frame layer (`protocol/SonyFrame.kt`)

Add:

```kotlin
const val TYPE_LARGE_DATA_MDR = 0x2C   // firmware chunks; ack-required; 5000 ms ack timeout, 2 resends
```

Decoder needs no change (it does not validate `type`).

## 2. Support-function / device-info discovery (v2 dialect only)

Wire (v2 table1, DATA_MDR frames):

| message | payload |
|---|---|
| CONNECT_GET_SUPPORT_FUNCTION | `06 00` |
| CONNECT_RET_SUPPORT_FUNCTION | `07 00 <count> { <functionType> <capabilityCounter> } * count` (payload len = 3 + 2*count) |
| CONNECT_GET_DEVICE_INFO model name | `04 01` |
| CONNECT_RET_DEVICE_INFO model name | `05 01 <len> <ASCII name>` (same shape as the firmware reply `05 02 …`) |

Table-1 function bytes that matter:

```
FW_UPDATE_TANDEM                                        0x30
FW_UPDATE_MTK_TRANSFER_WITHOUT_DISCONNECTION            0x32
FW_UPDATE_MTK_TRANSFER_WITHOUT_DISCONNECTION_AUTO_UPDATE 0x34
FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE                 0x35
FW_UPDATE_MTK_TRANSFER_WITH_AC_CONNECTION_CHECK         0x36
FW_UPDATE_USING_MC_APP                                  0x38
```

`SonyCommands` additions:

```kotlin
const val SUPPORT_FUNCTION_GET = 0x06
const val DEVICE_INFO_MODEL_SUB = 0x01          // FIRMWARE_GET (0x04) + this sub = model name query
fun supportFunctionGet(): ByteArray             // {0x06, 0x00}
fun modelNameGet(): ByteArray                   // {0x04, 0x01}
```

`SonyResponses` additions:

```kotlin
const val SUPPORT_FUNCTION_RET = 0x07
fun parseSupportFunctions(payload: ByteArray): Set<Int>?   // table-1 function bytes (0x00 NO_USE dropped); null if malformed
fun parseModelName(payload: ByteArray): String?             // {0x05,0x01,len,ascii}
```

`SonyEvent` gains `ModelName(name)`, `SupportFunctions(set)`; `parse()` dispatches them.
`parseFirmware` must not match `05 01` (check payload[1] == 0x02).

## 3. Update method selection (`update/FirmwareUpdateMethod.kt`)

```kotlin
enum class FirmwareUpdateMethod { TANDEM, MTK, MC_APP, NONE }

object FirmwareUpdateMethods {
    /** spec-tandem-fota §6.2: MTK wins over TANDEM if any 0x32/0x34/0x35/0x36 present. */
    fun fromSupportFunctions(fns: Set<Int>): FirmwareUpdateMethod
    /** spec-tandem-fota §6.4: UpdtInquiredType byte to use for UPDT_GET_CAPABILITY/GET_PARAM, or null. */
    fun updtInquiredType(fns: Set<Int>): Int?     // 0x32→0x02, 0x34→0x04, 0x35→0x05, 0x36→0x06, 0x38→0x07, 0x30→0x10
}
```

(The Sony app also routes to MTK when voice-guidance / voice-assistant functions are advertised. We
ignore that; if a device advertises 0x30 without MTK types, we treat it as TANDEM.)

## 4. UPDT messages (`update/UpdtMessages.kt`)

Constants (payload[0]):

```
UPDT_GET_CAPABILITY 0x30  UPDT_RET_CAPABILITY 0x31
UPDT_GET_STATUS     0x32  UPDT_RET_STATUS     0x33   UPDT_NTFY_STATUS 0x35
UPDT_GET_PARAM      0x36  UPDT_RET_PARAM      0x37
UPDT_SET_PARAM      0x38  UPDT_NTFY_PARAM     0x39
UPDT_TRANSFER_DATA  0x3E  UPDT_NTFY_MESSAGE   0x3F
```

Inquired types: `PART1 0x10, PART2 0x11, PART3 0x12, PART4 0x13`.
Commands: `ENTER 0x01, EXIT 0x02, START_TRANSFER 0x03, FINISH 0x04, CANCEL 0x05, EXECUTE 0x06`.
Results: `OK 0x00, OTHER 0x01, ILLEGAL_STATE 0x02, ILLEGAL_ARGS 0x03, NO_NEED_OF_DATA_TRANSFER 0x04,
TRANSFER_INCOMPLETE 0x05, NEED_POWER_AND_BATTERY 0x06, TEMP_TOO_HIGH 0x07`.
Status: `INVALID 0x00, IDLE 0x01, NOT_READY 0x02, DATA_RECEIVING 0x03, UPDATING 0x04`.
MacType: `NONE 0x00, MD5 0x01, SHA1 0x02`.

```kotlin
enum class DigestType(val macType: Int, val jcaName: String?) { NONE(0x00, null), MD5(0x01, "MD5"), SHA1(0x02, "SHA-1") }

data class UpdateCapability(val resumable: Boolean, val tws: Boolean, val backgroundTransfer: Boolean, val acCheck: Boolean)

data class UpdateParams(
    val categoryId: String, val serviceId: String, val nationCode: String, val language: String,
    val serialNumber: String, val batteryThreshold: Int, val batteryThresholdInterrupt: Int, val uniqueId: String,
)

enum class FotaStatus(val code: Int) { INVALID(0), IDLE(1), NOT_READY(2), DATA_RECEIVING(3), UPDATING(4) }
enum class FotaResult(val code: Int) { OK(0), OTHER(1), ILLEGAL_STATE(2), ILLEGAL_ARGS(3), NO_NEED_OF_DATA_TRANSFER(4), TRANSFER_INCOMPLETE(5), NEED_POWER_AND_BATTERY(6), TEMP_TOO_HIGH(7), UNKNOWN(-1) }

sealed interface UpdtNotify {
    data class Status(val status: FotaStatus) : UpdtNotify                              // 0x33 / 0x35 PART1
    data class SimpleResult(val command: Int, val result: FotaResult) : UpdtNotify        // 0x39 PART2 (len 4)
    data class StartTransferResult(val result: FotaResult, val maxPacketSize: Int, val offset: Int) : UpdtNotify // 0x39 PART3 (len 12)
    data class ExecuteResult(val result: FotaResult, val requiredTimeSec: Int) : UpdtNotify // 0x39 PART4 (len 6)
    data object Completed : UpdtNotify                                                    // 0x3F PART1 msgType 0x01
}

object UpdtMessages {
    // builders (payload only; caller picks the frame type)
    fun getCapability(inq: Int): ByteArray           // {0x30, inq}
    fun getStatus(inq: Int): ByteArray               // {0x32, inq}
    fun getParam(inq: Int): ByteArray                // {0x36, inq}
    fun setSimple(command: Int): ByteArray           // {0x38, 0x11, command}
    fun startTransfer(fwVersion: String, fileName: String, digest: DigestType, macHex: String): ByteArray
        // {0x38,0x12,0x03, len,fwVersion, 0x00 /*fileIndex*/, 0x01 /*numFiles*/, len,fileName, macType, macLen, macAscii}
        // fwVersion/fileName truncated to 32 bytes ASCII; macHex must be 32 (MD5) / 40 (SHA1) chars, empty for NONE
    fun execute(fwVersion: String, fileNames: List<String>): ByteArray
        // {0x38,0x13,0x06, len,fwVersion, n, {len,name}*n}
    fun transferData(offset: Int, data: ByteArray): ByteArray
        // {0x3E,0x12, u32be offset, u32be data.size, data}

    // parsers (return null when the payload is not that message / malformed)
    fun parseCapability(payload: ByteArray): UpdateCapability?     // 0x31, len 7, payload[2]==4
    fun parseParam(payload: ByteArray): UpdateParams?              // 0x37, chained str{128} x5, u8, u8, str{128}
    fun parseNotify(payload: ByteArray): UpdtNotify?               // 0x33/0x35/0x39/0x3F as above
}
```

## 5. Protocol client changes (`protocol/SonyProtocolClient.kt`)

New exposed state:

```kotlin
val modelName: StateFlow<String?>
val supportFunctions: StateFlow<Set<Int>?>              // null until discovered (always null on V1)
val updateMethod: StateFlow<FirmwareUpdateMethod?>       // null until known; NONE on V1
val updateCapability: StateFlow<UpdateCapability?>
val updateParams: StateFlow<UpdateParams?>
val notifications: SharedFlow<SonyMessage>               // every inbound Command1/Command2 message, incl. UPDT_* (replay 0, extraBufferCapacity 256)
```

`start()` for V2, after the existing discovery: `modelNameGet` (query, reply 0x05 with sub 0x01),
`supportFunctionGet` (query 0x07), then if `updtInquiredType != null`: `getCapability(inq)` (query 0x31)
and `getParam(inq)` (query 0x37); set `updateMethod`. Any missing reply leaves the flow null and
does not fail `start()`. Query reply timeout for these may be raised to 2000 ms.

New send primitive (public):

```kotlin
/** Write a frame with the current seq and wait for its Ack; on timeout resend the same frame up to
 *  [maxResends] times. Returns false if no Ack ever arrived. Serialised by sendMutex. */
suspend fun sendReliable(type: Int, payload: ByteArray, ackTimeoutMs: Long, maxResends: Int): Boolean
```

Existing `sendCommand` becomes `sendReliable(type, payload, ACK_TIMEOUT_MS, 0)` semantics-preserving.
`handleMessage` must `tryEmit` every Command1/Command2 message to `notifications` before auto-acking.

Constants: `CONTROL_ACK_TIMEOUT_MS = 2000`, `CONTROL_RESENDS = 3`, `LARGE_ACK_TIMEOUT_MS = 5000`,
`LARGE_RESENDS = 2`.

## 6. Tandem FOTA session (`update/TandemFotaSession.kt`)

```kotlin
data class FirmwareImage(val bytes: ByteArray, val version: String, val fileName: String, val digest: DigestType, val macHex: String)

sealed interface FotaPhase {
    data object Idle : FotaPhase
    data object EnteringMode : FotaPhase
    data class Transferring(val percent: Int) : FotaPhase
    data object Finishing : FotaPhase
    data object Executing : FotaPhase
    data class Installing(val percent: Int, val requiredTimeSec: Int) : FotaPhase
    data object Completed : FotaPhase
    data object Cancelled : FotaPhase
    data class Failed(val reason: FotaFailure, val detail: String) : FotaPhase
}
enum class FotaFailure { NEED_CHARGE, BATTERY_HOT, DEVICE_REFUSED, TIMEOUT, TRANSFER_FAILED, CANCELLED_BY_DEVICE, DISCONNECTED, OTHER }

class TandemFotaSession(private val client: SonyProtocolClient, private val clock: () -> Long = System::currentTimeMillis) {
    val phase: StateFlow<FotaPhase>
    /** Runs the full sequence (spec-tandem-fota §4.1/§7). Returns the terminal phase. */
    suspend fun run(image: FirmwareImage): FotaPhase
    /** User cancel: CANCEL_TRANSFER (if transferring) then EXIT_FW_UPDATE_MODE. Safe to call any time before Executing. */
    suspend fun cancel()
}
```

Sequence inside `run` (all waits consume `client.notifications` through `UpdtMessages.parseNotify`):

1. `EnteringMode`: `sendReliable(COMMAND1, setSimple(ENTER))`; wait ≤ 20 s for **both** `SimpleResult(ENTER, OK)` and `Status(IDLE)`. Result 0x06 → `Failed(NEED_CHARGE)`, 0x07 → `Failed(BATTERY_HOT)`, other non-OK → `DEVICE_REFUSED`; timeout → `TIMEOUT`.
2. `Transferring(0)`: `sendReliable(COMMAND1, startTransfer(...))`; wait ≤ 150 s for `StartTransferResult(OK, maxPacketSize, offset)` **and** `Status(DATA_RECEIVING)`. `NO_NEED_OF_DATA_TRANSFER` → skip to step 4. `Status(NOT_READY)` → `CANCELLED_BY_DEVICE`.
3. Loop from `offset` in steps of `maxPacketSize`: `sendReliable(TYPE_LARGE_DATA_MDR, transferData(off, chunk), 5000, 2)`; false → `Failed(TRANSFER_FAILED)`. Update `Transferring(off*100/size)`. Abort with `CANCELLED_BY_DEVICE` if any `Status` other than `DATA_RECEIVING` arrives mid-loop; abort with `Cancelled` if `cancel()` was requested.
4. `Finishing`: `setSimple(FINISH)`; wait ≤ 20 s for `SimpleResult(FINISH, OK)` **and** `Status(IDLE)`.
5. `Executing`: `sendReliable(COMMAND1, execute(version, listOf(fileName)))`; wait ≤ 20 s for `ExecuteResult(OK, requiredTime)` **and** `Status(UPDATING)`. `TRANSFER_INCOMPLETE` → `DEVICE_REFUSED`.
6. `Installing`: wait for `Completed` up to `2 × requiredTime` s (min 60 s); fake percent = elapsed/requiredTime capped at 95. The link may drop here; a closed connection during Installing is **not** a failure — keep waiting on `notifications` until the deadline, then report `Completed` if a `Completed` arrived, else `Failed(TIMEOUT)` with detail "install not confirmed".
7. `Status(INVALID)` at any point before Executing → `Failed(CANCELLED_BY_DEVICE)`.

`cancel()`: if phase is Transferring → `setSimple(CANCEL)` wait ≤ 20 s for `SimpleResult(CANCEL, OK)` + `Status(IDLE)`; then `setSimple(EXIT)` wait ≤ 20 s for `SimpleResult(EXIT, OK)` + `Status(INVALID|NOT_READY)`. Sets phase `Cancelled`.

Only one `run` per session instance.

## 7. Feed: discovery, decrypt, select, download (`update/SonyUpdateFeed.kt`, `update/FirmwareUpdateChecker.kt`)

```kotlin
fun interface HttpFetcher { suspend fun get(url: String, onProgress: ((received: Long, total: Long) -> Unit)? = null): ByteArray }
class HttpsUrlFetcher(connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 60_000) : HttpFetcher   // java.net.HttpsURLConnection, GET, no headers, expects 200

data class InfoDocument(val xml: String, val digest: DigestType)

data class UpdateRule(val key: String, val operator: String, val value: String)
data class Distribution(val installType: String, val version: String, val uri: String, val mac: String, val clientVersion: String, val size: Long)
data class Description(val lang: String, val text: String)
data class ApplyCondition(val rules: List<UpdateRule>, val distributions: List<Distribution>, val descriptions: List<Description>, val defaultLang: String?)

data class DeviceContext(val model: String, val firmwareVersion: String, val serialNo: String, val nation: String)

data class AvailableUpdate(val version: String, val url: String, val macHex: String, val digest: DigestType, val sizeBytes: Long, val notes: String?)

object SonyUpdateFeed {
    const val HOST = "info.update.sony.net"
    fun infoUrl(categoryId: String, serviceId: String): String   // https://HOST/{cat}/{svc}/info/info.xml
    fun effectiveSerial(model: String, serial: String): String    // WF-1000XM4→"1301211", LinkBuds→"5630981", LinkBuds S→"1300112"
    /** Header (key:value lines, blank line) + body; ENC0003 AES-128-ECB key 4FA27999FFD08B1FE4D260D57B6D3C17, ENC0002 3DES-ECB zero key, ENC0001 plain;
     *  zero-byte padding stripped; digest check hex(h(hex(h(body)) + serviceId + categoryId)) == header.digest (HAS0002 MD5 / HAS0003 SHA1 / HAS0001 requires ""). Throws FeedException on any mismatch. */
    fun decodeInfo(raw: ByteArray, categoryId: String, serviceId: String): InfoDocument
    fun parseInfo(xml: String): List<ApplyCondition>              // javax.xml.parsers (available on Android + JVM)
    /** First ApplyCondition whose rules all pass (ClientVersion rules skipped), with a binary Distribution. Description: lang match, else DefaultLang, else first. */
    fun select(conditions: List<ApplyCondition>, ctx: DeviceContext, digest: DigestType, lang: String = "English"): AvailableUpdate?
    fun ruleMatches(rule: UpdateRule, ctx: Map<String, String>): Boolean  // operators per spec §4; numeric dotted compare for FirmwareVersion/OSVersion/FW_VERSION when both sides match ^[0-9.]+$
    fun verifyBinary(bytes: ByteArray, expectedSize: Long, macHex: String, digest: DigestType): Boolean  // size exact; MAC skipped when macHex blank
    fun fileNameFor(url: String): String   // Integer.toHexString(lastPathSegment.hashCode())
}

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val update: AvailableUpdate) : UpdateCheck
    data class Error(val message: String) : UpdateCheck
}

class FirmwareUpdateChecker(private val fetcher: HttpFetcher = HttpsUrlFetcher()) {
    suspend fun check(params: UpdateParams, model: String, firmwareVersion: String): UpdateCheck
    suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): FirmwareImage   // verifies size+MAC, throws FeedException
}
```

An update is "available" when a passing condition yields a binary Distribution **and** its Version
differs from the current firmware version (the feed's own rules normally encode this; the extra
check avoids re-offering the installed version). Any network failure → `Error`.

## 8. ViewModel / UI

`SonyUiState.Connected` gains:

```kotlin
val firmwareVersion: String?,
val update: FirmwareUpdateUi,
```

```kotlin
sealed interface FirmwareUpdateUi {
    data object Unknown : FirmwareUpdateUi            // no method info yet / V1
    data object Checking : FirmwareUpdateUi
    data object UpToDate : FirmwareUpdateUi
    data class Unsupported(val reason: String) : FirmwareUpdateUi    // e.g. "Update via Sony app" for MTK
    data class Available(val version: String, val sizeBytes: Long, val installable: Boolean) : FirmwareUpdateUi
    data class Confirming(val version: String) : FirmwareUpdateUi
    data class Downloading(val percent: Int) : FirmwareUpdateUi
    data class Transferring(val percent: Int) : FirmwareUpdateUi
    data class Installing(val percent: Int) : FirmwareUpdateUi
    data class Completed(val version: String) : FirmwareUpdateUi
    data class Failed(val message: String) : FirmwareUpdateUi
}
```

ViewModel behaviour:

- After connect, once `updateMethod`, `updateParams`, `firmwareVersion` and a model name are known:
  method NONE → `Unsupported("No update support")`; params null → `Unknown`; else run
  `checker.check(...)` (`Checking` → result). MTK/MC_APP with an available update →
  `Available(installable=false)` and the screen says "Install with Sony Sound Connect".
- Model for the feed = `client.modelName` ?: Bluetooth device name.
- `startUpdate()`: `Available(installable)` → `Confirming`. `confirmUpdate()`: battery gate
  (every non-null of single/left/right must be **>** `batteryThreshold` when threshold > 0, else
  `Failed("Charge above N% first")`), then Downloading → `TandemFotaSession.run` mirroring
  `phase` into Transferring/Installing/Completed/Failed. `cancelUpdate()` during Downloading or
  Transferring.
- `onAppPause()` / `onScreenHide()` must **not** tear down while `update` is Downloading,
  Transferring or Installing.
- Bottom bar: existing buttons; plus `UPDATE` when `Available(installable=true)`;
  `START`/`BACK` when Confirming; `CANCEL` while Downloading/Transferring; `RETRY`/`OK` on
  Failed/Completed. Body shows model, firmware version, battery, and one update status line
  ("Firmware 3.0.1 available", "Transferring 42%", "Installing… keep the app open").
- `tool/lighttool.toml`: add `android.permission.INTERNET` (allowed by the SDK policy).

## 9. Tests (JVM, `tool/src/test/...`)

- `UpdtMessagesTest`: byte-exact builders (§4 layouts), parser round-trips, malformed rejects.
- `SonyResponsesTest` additions: support-function and model-name parsing; firmware parser ignores `05 01`.
- `FirmwareUpdateMethodTest`: selection matrix.
- `TandemFotaSessionTest`: extend the existing `FakeSonyConnection`/`EmulatedDevice` pattern with a
  FOTA-capable device (answers ENTER/START/FINISH/EXECUTE with the paired result + status notifies,
  records received chunks, emits Completed). Assert: full happy path reassembles the image, chunk
  sizes equal maxPacketSize, resume offset honoured, NOT_READY mid-transfer → CANCELLED_BY_DEVICE,
  large-data frames use type 0x2C.
- `SonyUpdateFeedTest`: fixture built in-test (AES-ECB encrypt + zero pad with the spec key, digest
  chain), decode + parse + select; rule operators; version compare; verifyBinary; effectiveSerial.
- `SonyProtocolClientTest` additions: V2 start() issues 06 00 / 04 01 / 30 10 / 36 10 and populates
  the new flows; `sendReliable` resends on missing Ack and returns false after budget.
