# Airoha (MediaTek) FOTA protocol as embedded in Sony "Sound Connect" 13.2.2

Reverse-engineered from the jadx decompile at
`…/scratchpad/sony/jadx/sources`. All `file:line` citations below are relative to
that directory unless an absolute path is given. Library version string logged by
the adapter is **`Ver:1.5.7.2025052218`**
(`com/airoha/project/sony/AirohaFotaAdapterSony.java:706`).

Obfuscated-package cheat sheet (established by following imports):

| pkg | role |
|---|---|
| `n8.a` | `AirohaLinker` (connection factory / registry) |
| `q8.*` | `AirohaHost`, host-state listener (`q8.e`), TX scheduler (`q8.g`) |
| `r8.b` / `r8.c` / `r8.a` | link params: base / SPP / GATT |
| `x8.d` | byte/hex helpers (`d.l(short)` = LE 2-byte, `d.f(hi,lo)` = BE short, …) |
| `f7.a` | RACE packet (libcommon + AB1562) |
| `z7.a` | RACE packet (MT2833/MT2855) |
| `q7.a` | RACE packet (AB1568/MT2822) |
| `o6.a` | RACE packet (MT2811 / legacy `com.airoha.android.lib`) |
| `i7.d` | relay-packet (un)wrapper |
| `j7.*` / `k7.*` | AB1562 FOTA manager + stages |
| `u7.*` / `v7.*` | AB1568/MT2822 FOTA manager + stages |
| `d8.*` / `h8.*` | MT2833 FOTA manager (+ LE-audio variant) + 14 stages |
| `i8.*` | MT2855 FOTA manager |
| `s6.*` / `t6.*` / `z6.a` | MT2811 legacy manager + `AirohaSppController` |
| `pl.d`, `nu.o`, `mu.d` | Sony app glue: FOTA driver, update controller, binary downloader |

**jadx constant substitutions** — jadx replaced plain integer literals with
unrelated library constants. Every one used below, resolved from this same tree:

| jadx renders | real value | source |
|---|---|---|
| `Calib3d.CALIB_FIX_K5` | **4096** | `org/opencv/calib3d/Calib3d.java:38` |
| `Imgcodecs.IMWRITE_GIF_QUALITY` | **1026** = 0x0402 | `org/opencv/imgcodecs/Imgcodecs.java:62` |
| `Imgcodecs.IMWRITE_GIF_TRANSPARENCY` | **1028** = 0x0404 | `org/opencv/imgcodecs/Imgcodecs.java:64` |
| `Videoio.CAP_XIAPI` | **1100** | `org/opencv/videoio/Videoio.java:383` |
| `Videoio.CAP_PROP_XI_LENS_FEATURE_SELECTOR` | **517** | `org/opencv/videoio/Videoio.java:309` |
| `Videoio.CAP_PROP_XI_IMAGE_DATA_FORMAT_RGB32_ALPHA` | **529** = 0x0211 | `org/opencv/videoio/Videoio.java:298` |
| `Videoio.CAP_QT` | **500** | `org/opencv/videoio/Videoio.java:375` |
| `DTrees.PREDICT_MASK` | **768** = 0x0300 | `org/opencv/ml/DTrees.java:9` |
| `BSON.NUMBER_INT` | **16** = 0x10 | `org/bson/BSON.java:29` |
| `BSON.CODE_W_SCOPE` | **15** = 0x0F | `org/bson/BSON.java` |
| `DeviceOrientationRequest.OUTPUT_PERIOD_FAST` | **5000** | `com/google/android/gms/location/DeviceOrientationRequest.java:19` |

---

## 1. Transport

### 1.1 It is a *separate* connection from Sony MDR/Tandem

The Airoha FOTA library never reuses the MDR/Tandem SPP socket. Sony's MDR
RFCOMM UUID `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` appears only in the Tandem
transport (`ie0/i.java:43`, `jp/co/sony/hes/autoplay/core/utils/b.java:33`) and
never inside `com/airoha/**`. The Airoha layer always opens its own socket /
GATT connection to the same BD address
(`com/airoha/project/sony/AirohaFotaAdapterSony.java:721-742` builds a fresh
`r8.c`/`r8.a` link param and calls `mAirohaLinker.c(...)` / `.a(...)`).

### 1.2 RFCOMM/SPP UUIDs (the normal, non-LE path)

```java
// com/airoha/project/sony/AirohaFotaAdapterSony.java:54-57
public static final UUID SONY_SPP_UUID          = UUID.fromString("8901dfa8-5c7e-4d8f-9f0c-c2b70683f5f0");
public static final UUID SONY_GATT_SERVICE_UUID = UUID.fromString("dc405470-a351-4a59-97d8-2e2e3b207fbb");
public static final UUID SONY_GATT_TX_UUID      = UUID.fromString("bfd869fa-a3f2-4c2f-bcff-3eb1ec80cead");
public static final UUID SONY_GATT_RX_UUID      = UUID.fromString("2a6b6575-faf6-418c-923f-ccd63a56d955");
```

These are the defaults installed in the constructor
(`AirohaFotaAdapterSony.java:75-78`) and the Sony app **never overrides them** —
`pl/d.java` only calls `setBdAddress`, `setBinaryFile`, `start`,
`startCommitProcess` (`pl/d.java:329-345`); `setSppUUID`/`setGattUUID` have no
caller in the app. So a third-party client should hard-code
`8901dfa8-5c7e-4d8f-9f0c-c2b70683f5f0`.

The legacy MT2811 SPP controller has the same UUID plus a "generic Airoha"
fallback selected by a global flag:

```java
// z6/a.java:23,62,133-141
private static final byte[] f85205n = {0,0,0,0,0,0,0,0, 0,-103,-86,-69,-52,-35,-18,-1}; // 00000000-0000-0000-0099-aabbccddeeff
private UUID f85218m = UUID.fromString("8901dfa8-5c7e-4d8f-9f0c-c2b70683f5f0");
private BluetoothSocket b(BluetoothDevice dev) {
    UUID u = d(c());                       // generic Airoha UUID
    if (n6.a.f65672a) { u = this.f85218m; } // "isForSony" -> Sony UUID
    return dev.createRfcommSocketToServiceRecord(u);   // NOTE: *secure* RFCOMM
}
```

`n6/a.java:8` initialises `f65672a = true`, and `s6/c.java:177,186` re-assert
`true` (only `s6/c.java:328` sets it false), so the Sony UUID is always used.
The socket is a **secure** `createRfcommSocketToServiceRecord` (not the
`createInsecure…` variant).

### 1.3 Declared "MTU" per link type

`r8.b` clamps to a minimum of 23:

```java
// r8/b.java:18-26
public b(String addr, LinkTypeEnum t, int mtu) { ... if (mtu < 23) this.f72321c = 23; else this.f72321c = mtu; }
```

* SPP link param: `super(str, LinkTypeEnum.SPP, Videoio.CAP_XIAPI)` →
  **1100** (`r8/c.java:15`; `org/opencv/videoio/Videoio.java:383`
  `CAP_XIAPI = 1100`). jadx mis-resolved the literal to an OpenCV constant; the
  real value is 1100 bytes.
* GATT link param: `super(str, LinkTypeEnum.GATT, Videoio.CAP_PROP_XI_LENS_FEATURE_SELECTOR)`
  → **517** (`r8/a.java:27`; `org/opencv/videoio/Videoio.java:309`
  `CAP_PROP_XI_LENS_FEATURE_SELECTOR = 517`) — i.e. the maximum ATT MTU.

### 1.4 BLE GATT variant

`TargetDevice` always uses `LinkTypeEnum.GATT` and wraps the three UUIDs into an
`r8.a` GATT link param (`com/airoha/project/sony/TargetDevice.java:12-16,
36-40`). `r8.a` exposes `h()` = service, `i()` = TX, `f()` = RX
(`r8/a.java:36-50`), and `FotaControl2833MultiLink.setDeviceList` passes them in
that order to `setGattUUID(service, tx, rx)`
(`com/airoha/project/sony/FotaControl2833MultiLink.java:252-253`).

BLE FOTA is gated to newer chips only:

```java
// com/airoha/project/sony/AirohaFotaAdapterSony.java:239-247
if (chip_type == CHIP_TYPE.MT2822 || chip_type == CHIP_TYPE.MT2833 || chip_type == CHIP_TYPE.MT2855) {
    mAirohaCommonMgr.getDeviceRole();
} else {
    gLogger.e(TAG, "BLE FOTA is only supported on MT2822 and MT2833");
    gOtaListenerMgr.notifyFailed(AirohaRaceOtaError.INIT_FAIL);
}
```

### 1.5 liblinker physical-layer detail

Layer map: `n8/a.java` = `AirohaLinker` (one host per BD addr in a
`HashMap<String, q8.a>`, `n8/a.java:70-78`; `a(r8.a,…)` = GATT connect,
`c(r8.c,…)` = SPP connect, `n8/a.java:96-102`); `q8/b.java` = `GeneralHost`;
`q8/g.java` = TX scheduler; `u8/a.java` = **liblinker SPP physical layer**
(log tag `"AirohaSPP"`); `com/airoha/liblinker/physical/gatt/c.java` = GATT
physical layer (tag `"AirohaGATT"`); `v8/b.java` = state machine;
`com/airoha/liblinker/transport/a.java` = the H4 transport (deframer), used for
**both** SPP and GATT (`q8/b.java:151`).

**SPP.** Secure socket, UUID taken verbatim from the link param, no reflection
fallback, no insecure variant anywhere in liblinker:

```java
// u8/a.java:269-276
private BluetoothSocket M(BluetoothDevice dev) {
    log("createRfcomm: " + this.f79518c.e().toString());
    try { return dev.createRfcommSocketToServiceRecord(this.f79518c.e()); }
    catch (Exception unused) { return null; }
}
```

Read buffer 2000 bytes, one `read()` per loop iteration, bytes pushed up raw
(`u8/a.java:375-384`, read thread `u8/a.java:193-218`). Write is a single
`OutputStream.write(frame)` with **no length prefix, no escaping, no flush and
no per-write sleep** (`u8/a.java:565-578`). Connect posts
`onSppConnected` after 100 ms and `onSppInitialized` after another 100 ms
(`u8/a.java:278-299`, `352-372`); error codes `2002`/`2003` (open), `2004`
(init). A "connectable" pre-check polls the **A2DP** profile every
`PERIOD_MS_TO_CHECK_PROFILE = 6000` ms up to `MAX_COUNT_OF_PROFILE_CHECK = 40`
times, then sleeps `DELAY_MS_TO_NOTIFY_CONNECTABLE = 2000` ms before firing
`onSppReadyToReconnect` (`u8/a.java:70-80, 120-174`); after 40 failures →
error `2002`.

**GATT.** `connectGatt(ctx, autoConnect=false, cb, TRANSPORT_LE=2)`
(`…/gatt/c.java:632`), CONNECT task timeout 10000 ms (`…/gatt/c.java:953-955`).
Characteristic roles (matches §1.4): service = `r8.a.h()`, **TX (written) =
`r8.a.i()`**, **RX (notified) = `r8.a.f()`** (`…/gatt/c.java:499-510`); RX must
have `PROPERTY_NOTIFY` else error `3010` (`…/gatt/c.java:522-531`).
Notifications are enabled by writing `ENABLE_NOTIFICATION_VALUE` (`01 00`) to
CCCD `00002902-0000-1000-8000-00805F9B34FB` (`p8/a.java:10`,
`…/gatt/c.java:715-725`).

`writeCharacteristic` is called **without ever calling `setWriteType`** for data
(`…/gatt/c.java:738-744`); the only `setWriteType(2)` (`WRITE_TYPE_DEFAULT`)
calls are temporary, around descriptor writes (`…/gatt/c.java:723, 761`). The
task queue holds exactly one write in flight and waits for
`onCharacteristicWrite` before the next (`…/gatt/c.java:214-217`) — i.e.
write-with-response semantics. A Kotlin port should set
`WRITE_TYPE_DEFAULT` explicitly and preserve the one-in-flight serialisation.

MTU: `requestMtu(r8.a.c())` = **517**, clamped to `[23,517]`
(`…/gatt/c.java:911-920, 697`; `…/gatt/GattTask.java:78-89`). Max payload is
**`mtu − 3`**, and it only ever decreases:

```java
// q8/b.java:53-60   (onMtuChanged -> v8/b.java:150-153 -> here)
public void f(int i11) { int i12 = i11 - 3; if (i12 < b.this.f70978j) b.this.f70978j = i12; }
```

`f70978j` starts at `linkParam.getMtu()` (`q8/b.java:138`) → 517 for GATT,
1100 for SPP. Connection priority: `requestConnectionPriority(r8.a.e())`, default
`1` = `CONNECTION_PRIORITY_HIGH` (`r8/a.java:28,58-60`, `…/gatt/c.java:689`);
Sony's GATT FOTA sets it explicitly via `aVar.k(1)`
(`com/airoha/project/sony/FotaControl2855Gatt.java:373-374`).

GATT task queue: default per-task timeout **5000 ms**, retry limit **2** (3
attempts) (`…/gatt/GattTask.java:22,25`; `…/gatt/c.java:851-876`); timeout →
error `3020` (`v8/b.java:132-135`). Other codes: `3001` connect/scan fail,
`3002` not connected, `3003` already connected, `3010` chars/notify missing,
`3011` `discoverServices()` false, `3021` exec exception.
BLE-scan reconnect timeout `TIMEOUT_MS_OF_BLE_SCAN = 240000`
(`…/gatt/c.java:75`).

**Frame fragmentation is done by the transport, not the physical layer.**
`GeneralHost.send()` splits the RACE frame into `maxPayload`-sized pieces and
hands each to one physical write (`q8/b.java:249-257`):

```java
// com/airoha/liblinker/transport/a.java:45-62
public boolean j(byte[] bArr, int i11) {
    int length = bArr.length, i12 = length / i11, i13 = length % i11, i14 = 0, i15 = 0;
    while (i14 < i12) { int i16 = i15 + i11; b(Arrays.copyOfRange(bArr, i15, i16)); i14++; i15 = i16; }
    if (i13 == 0) return true;
    b(Arrays.copyOfRange(bArr, i15, i13 + i15)); return true;
}
```

No per-chunk header/sequence byte is added. So: over SPP a frame ≤1100 bytes is
one write; over GATT it is `ceil(len / (mtu-3))` writes. Reassembly on RX is
purely the byte-accumulator deframer (§2.5).

### 1.6 TX scheduler (`q8/g.java`)

* Three queues by `TxSchedulePriority { High, Middle, Low, None }`
  (`com/airoha/liblinker/constant/TxSchedulePriority.java:5-10`); `None` is
  silently dropped (`q8/g.java:265-286`).
* Weighted aging: weights High=10, Middle=5, Low=1 (`q8/g.java:46-61`), reset on
  dequeue, `wHigh += 5` / `wMid++` / `wLow++` bumps (`q8/g.java:210-229`),
  selection at `q8/g.java:147-171`.
* **Per-packet TX timeout 20000 ms**, armed for High/Middle only — Low priority
  is fire-and-forget (`q8/g.java:43, 172-186, 196-201`).
* **The linker performs zero retries**: on timeout it clears the in-flight slot,
  notifies `onHostScheduleTimeout` and pumps the next item
  (`q8/g.java:120-131`, `q8/a.java:66-69`). All retry logic lives in the
  RACE/FOTA stage layer.
* Ack/unlock is by *locker key*: a reply whose locker key matches the in-flight
  packet clears the timer (`q8/g.java:246-263`); enqueuing a **High** item with
  the same locker key pre-empts the in-flight one (`q8/g.java:267-279`).
* Inter-packet delay **1 ms** (`SystemClock.sleep(1L)`, `q8/g.java:95-99`) — the
  only sleep in the TX path. Connect retry sleeps 500 ms, retry limit 2
  (`v8/b.java:330, 413, 471`).

---

## 2. RACE packet framing

Identical in all four packet classes (`f7.a`, `z7.a`, `q7.a`, `o6.a`). Frame
built by `f7.a.d(byte)` / `z7.a.e(byte)` / `q7.a.f(byte)`:

```java
// z7/a.java:83-91  (identical shape in f7/a.java:97-104, q7/a.java:87-94)
arrayList.add((byte)(b11 | this.f85227a));   // f85227a = 0x05  -> byte0 = flag | 0x05
arrayList.add(this.f85228b);                 // byte1 = race TYPE
arrayList.add(this.f85230d[0]);              // byte2 = length LOW
arrayList.add(this.f85230d[1]);              // byte3 = length HIGH
arrayList.add(this.f85231e[0]);              // byte4 = race id LOW
arrayList.add(this.f85231e[1]);              // byte5 = race id HIGH
// then payload bytes verbatim
```

```
offset  size  field
0       1     0x05  (SOF / channel).  OR-ed with a "flag" byte, see below.
1       1     type: 0x5A = command (host->device)
                    0x5B = response (device->host)
                    0x5D = notification / indication (device->host)
2       2     length, LITTLE-ENDIAN = 2 + payloadLen  (the race id counts toward it)
4       2     race id, LITTLE-ENDIAN
6       n     payload
```

Length maths (`z7/a.java:142-153`, `f7/a.java:148-159`):
`len = raceIdBytes.length (=2) + payload.length`.

**No checksum / CRC anywhere in the frame.** The SPP reader accepts a frame on
header/type validity alone:

```java
// z6/a.java:255-270  (AirohaSppController.readPacket)
byte b11 = (byte) in.read();
if (b11 == 5 || b11 == 21) {                       // 0x05 or 0x15
    byte b12 = (byte) in.read();
    if (b12 == 90 || b12 == 91 || b12 == 93) {     // 0x5A / 0x5B / 0x5D
        bArr[2] = (byte) in.read();
        byte b13 = (byte) in.read();
        int iB = d.b(b13, bArr[2]);                // 16-bit LE length
        in.read(bArr2, 0, iB);                     // read exactly `len` bytes
```

### 2.1 The flag byte in byte 0

`byte0 = flag | 0x05`. Two values matter:

* `0x00` → byte0 = **0x05**. Setup/handshake and the whole AB1562 flow.
* `0x10` (`org.bson.BSON.NUMBER_INT` is jadx's rendering of the literal 16) →
  byte0 = **0x15**. Selected by a boolean:
  `z7.a.f(boolean) { return z11 ? e(BSON.NUMBER_INT) : e((byte)0); }`
  (`z7/a.java:106-108`, same in `q7/a.java:110-112`).

For the **MT2833/MT2855 family this is a session flag, and it does flip**: the
boolean is `d8.d.F()` = field `Y` (`d8/d.java:818-820`), initialised `false`
(`d8/d.java:150`) and set by `i0(boolean)` (`d8/d.java:975-977`), which the
FOTA-Start stage calls on enqueue:

```java
// f8/a.java:30-32   (FotaStage_00_Start)
z7.a aVar = new z7.a((byte) 90, 7176, new byte[]{b11, value});
this.f21560b.i0(true);      // -> every subsequent frame has byte0 = 0x15
this.f21562d.offer(aVar);
```

It is cleared again if FOTA Start fails (`f8/a.java:42-44`) and on cancel
(`f8/c.java:30`). Inbound frames are filtered with the same rule
(`d8/d.java:236`: `if (!Y || (bArr[0] & 0x10) == 0x10)`), so once the session is
open the device also answers with `0x15`. A reimplementation **must** flip the
flag after a successful `0x1C08`.

For **AB1562** the flag never flips: `f7.a.f45836p` is a static `false`
(`f7/a.java:13`) with no setter, so `e()` == `f()` == `d(0)` and byte0 is always
`0x05` (`f7/a.java:120-126`).
* `AgentPartnerParam` values `0x40` (AGENT) / `0x20` (PARTNER)
  (`com/airoha/project/sony/AgentPartnerParam.java:5-7`) are *not* OR-ed into
  byte 0 by the FOTA libs — grepping `getValue()`/`0x40`/`0x20` across
  `com/airoha/libfota*` and `h8/` yields only `h8/l.java:44` (a payload byte).
  Agent/partner selection is done with distinct race IDs and with the relay
  wrapper instead (§2.2).

### 2.2 Relay (agent → partner) wrapper

> **AB1562 only.** The MT2822/MT2833/MT2855 libraries never send 0x0D00/0x0D01; MT2833 TWS
> addresses the partner by a role byte or a TWS race id (`spec-airoha-mt2833-tws.md` §4).

`libcommon` and the FOTA stages can wrap a command so the agent bud forwards it
to the partner:

```java
// com/airoha/libcommon/stage/CommonStage.java:47-49 (field initialisers)
protected boolean mIsRelay      = false;
protected int     mRelayRaceId  = 3329;      // 0x0D01
protected byte    mRelayRaceRespType = 93;   // 0x5D
```

The relayed reply is unwrapped by stripping the outer 8-byte header:

```java
// i7/d.java:19-29
public static byte[] c(byte[] b) { return Arrays.copyOfRange(b, 8, b.length); } // inner frame
public static byte   b(byte[] b) { return b[1]; }                               // inner type
public static int    a(byte[] b) { return x8.d.g(b[5], b[4]); }                 // inner race id (LE)
public static byte   d(int id, byte[] b) { return (id == 2304 || id == 2305) ? b[8] : b[6]; } // status byte
public static byte   e(byte[] b) { return b[6]; }
```

So a relayed response looks like
`05 5D <len> 01 0D | 05 5B <innerLen> <innerIdLo> <innerIdHi> <innerPayload…>`
— i.e. the inner RACE frame starts at offset 8 of the outer payload.

### 2.3 Response matching, retries, timeouts (common layer)

* A stage considers a reply its own when `raceId == expectedRaceId &&
  type == expectedRespType` (`com/airoha/libcommon/stage/CommonStage.java:171`,
  `com/airoha/libfota2833/fota/stage/a.java:218`).
* Default response timeout **1000 ms** (`TIMEOUT_RACE_CMD_NOT_RSP = 1000`,
  `com/airoha/libcommon/AirohaCommonMgr.java:94,216`), flow locker 5000 ms,
  timer locker 3000 ms (same lines).
* Default per-stage retry limit **2** (`mMaxRetry = 2`,
  `com/airoha/libcommon/stage/CommonStage.java:38`); per-packet retry limit
  **3** (`f7/a.java:136-138`, `z7/a.java:126-128`, `q7/a.java:130-132`:
  `retryCount >= 3`).
* Command-queue pre-poll depth **4** (`PRE_POLL_SIZE = 4`,
  `com/airoha/libcommon/stage/CommonStage.java:24`) with `DELAY_POLL_TIME = 0`.
* TX priority is `TxSchedulePriority.High` for common stages
  (`CommonStage.java:45`).

### 2.4 Non-FOTA RACE IDs used during setup

| race id | hex | meaning | citation |
|---|---|---|---|
| 2560 | **0x0A00** | READ NVKEY (generic) | `CommonStage.java:104-110` |
| 2560 + NVKEY 4098 (**0x1002**) | | read **chip name** | `CommonStageReadChipName.java:18` |
| 2560 + payload `F2 B0` + `E8 03` | | read **device type** | `CommonStageGetDeviceType.java:20-23` |
| 3268 | **0x0CC4** | get **device role** (agent/partner) | `CommonStageGetDeviceRole.java:17-21` |
| 3329 | **0x0D01** | relay-to-partner wrapper | `CommonStage.java:48` |

`READ NVKEY` payload layout (`CommonStage.java:104-110`):

```java
protected f7.a genReadNvKeyPacket(byte[] nvKeyIdLE) {
    this.mRaceId = 2560;                       // 0x0A00
    f7.a p = new f7.a((byte)0, (byte)90, 2560);// flag 0x00, type 0x5A
    byte[] len = d.l((short) 1000);            // 0x03E8 little-endian = max read len
    p.l(new byte[]{ nvKeyIdLE[0], nvKeyIdLE[1], len[0], len[1] });
    return p;
}
```
→ payload = `keyIdLo keyIdHi 0xE8 0x03`. Response: `[6]=status?`,
`[6..7]` = returned length (read big-endian via `d.f(bArr[7], bArr[6])`),
data at offset 8 (`CommonStageReadChipName.java:31-42`).

The chip-name string is then matched to pick the FOTA implementation (§6).

---

## 3. FOTA stage sequences

Additional obfuscated packages for AB1562: `o7/*` = concrete single/agent-direct
stages, `p7/*` = TWS relay subclasses of the `o7` ones, `j7/c.java` =
`Airoha1562FotaMgr`, `j7/f.java` = `AirohaFotaMgrEx1562` (state machine),
`g7/a.java` = ComparePartition packet builder, `k7/a.java` = WriteFlash packet
builder, `k7/b.java` = WriteNV packet builder, `m7/*` = FOTA settings +
page record, `n7/*` = NVR text parser, `x8/a.java` = 8-bit table checksum,
`x8/b.java` = "is all 0xFF" test, `x8/e.java` = SHA-256.

Response indexing convention shared by all families:
`raceType = rx[1]`, `raceId = LE16(rx[4], rx[5])`, `status = rx[6]`
(`j7/c.java:246-247`, `com/airoha/libfota1562/stage/a.java:293`).
Status `0x00` **and** `0xD0`/`0xD1` all count as success;
`0xD0`/`0xD1` additionally mean "device busy, pace yourself" and switch the
manager into delayed-send mode (`com/airoha/libfota1562/stage/a.java:299,410`;
`j7/c.java:329-333, 545-552`).

### 3.1 AB1562 — race ID table

`role` byte is **0xFF** in every request this app builds
(`j7/c.java:519,588,613,633,662,781`); `storageType` = `0` Fota, `1` FileSystem
(`com/airoha/libfota1562/constant/PartitionType.java:6-7`).

| hex | dec | stage | meaning |
|---|---|---|---|
| **0x0A00** | 2560 | `o7/b.java:17` | READ NVKEY (used as GetAudioChannel) |
| **0x0A01** | 2561 | `o7/v.java:14`, `k7/b.java:7` | WriteNV |
| **0x0A03** | 2563 | `o7/s.java:13` | ReclaimNvkey |
| **0x0402** | 1026 | `o7/o.java:25` | **WriteFlash** |
| **0x0404** | 1028 | `o7/n.java:26` | **ErasePartition** |
| **0x0430** | 1072 | `o7/h.java:15` | Lock / Unlock partition |
| **0x0431** | 1073 | `o7/q.java:34`, `o7/r.java:22`, `g7/a.java:20` | ComparePartition (SHA-256) |
| **0x0433** | 1075 | `o7/p.java:32` | GetEraseStatus |
| **0x0CD6** | 3286 | `o7/c.java:18`, `p7/z.java:7` | **GetBattery** |
| **0x0D00** | 3328 | `p7/c.java:13`, `i7/b.java:7` | GetAvailableDst (locate partner) |
| **0x0D01** | 3329 | `i7/c.java:7`, all `p7/*` | **Relay to partner** |
| **0x0E01** | 3585 | `o7/u.java:9`, `h7/b.java:7` | SuspendDsp |
| **0x0E02** | 3586 | `o7/t.java:11`, `h7/a.java:7` | ResumeDsp |
| **0x1C00** | 7168 | `o7/f.java:19` | QueryPartitionInfo |
| **0x1C01** | 7169 | `o7/j.java:12` | CheckIntegrityStorage |
| **0x1C02** | 7170 | `o7/k.java:17` | **Commit** |
| **0x1C03** | 7171 | `o7/m.java:16` | Stop / Cancel |
| **0x1C04** | 7172 | `o7/g.java:16` | QueryState |
| **0x1C05** | 7173 | `o7/l.java:9` | DetachReset |
| **0x1C06** | 7174 | `o7/w.java:15` | WriteState |
| **0x1C07** | 7175 | `o7/e.java:15` | GetVersion |
| **0x1C08** | 7176 | `o7/a.java:14` | FotaStart |
| **0x1C09** | 7177 | `o7/d.java:15` | GetFwInfo |
| **0x1C0A** | 7178 | `o7/i.java:12` | StartTranscation |

### 3.2 AB1562 — single-device sequence (`FotaSingleActionEnum.StartFota`)

Entry `j7/c.java:1129-1148` (`k0()`) → `j7/c.java:702-732` (`s0(path, AGENT=0)`).
Queue order (`j()` at `j7/c.java:556-562`, `m(false)` at `j7/c.java:597-620`,
tail at `j7/c.java:713-717`):

1. `o7/c` `00_GetBattery` — **0x0CD6**
2. `o7/u` `SuspendDsp` — **0x0E01** — *skipped in background mode* (`j7/c.java:558-562`)
3. `o7/f` `00_QueryPartitionInfo` — **0x1C00**
4. `o7/a` `00_FotaStart` — **0x1C08**
5. `o7/i` `01_StartTranscation` — **0x1C0A**
6. `o7/p` `13_GetEraseStatus` — **0x0433**
7. `o7/q` `14_ComparePartition` — **0x0431**
8. `o7/h` `01_Lock_Unlock` (unlock) — **0x0430**
9. `o7/n` `11_EraseFlashPartition` — **0x0404**
10. `o7/o` `12_WriteFlash` — **0x0402**
11. `o7/j` `04_CheckIntegrityStorage` — **0x1C01**
12. `o7/w` `WriteState(0x0211)` — **0x1C06** (`j7/c.java:714`)
13. `o7/g` `00_QueryState` — **0x1C04**

Commit is a **separate, host-triggered** queue. `handleQueriedStates` sees state
`0x0211` and reports `FotaSingleActionEnum.Commit` (`j7/f.java:287-296`); the host
then calls `startCommitProcess()` → `j7/f.java:582-606` → `j7/c.java:1110-1127`:

1. `o7/c` GetBattery — **0x0CD6**
2. `o7/j` CheckIntegrityStorage — **0x1C01**
3. `o7/k` `05_Commit` — **0x1C02**

Reset path (`j7/c.java:1150-1167`): GetBattery → StartTranscation (0x1C0A) →
DetachReset (0x1C05).

`RestoreNewFileSystem` (`j7/c.java:676-700`) uses the FS chain `l()`
(`j7/c.java:573-595`): QueryPartitionInfo(storageType=1) → FotaStart →
GetEraseStatus → ComparePartition → Lock_Unlock → Erase → WriteFlash →
`o7/r` `15_ComparePartitionFS` (0x0431), then `WriteState(0x0222)` + QueryState.
**No StartTranscation in the FS chain.**

### 3.3 AB1562 — request payload layouts

**FotaStart 0x1C08** (`o7/a.java:20-32`) — payload `[01][FF]` = `[roleCount][role]`:

```java
byteArrayOutputStream.write(this.N.length);   // 0x01
byteArrayOutputStream.write(this.N);          // roles: {0xFF}
f7.a aVar = new f7.a((byte) 90, 7176);
aVar.l(byteArray);
```

**GetVersion 0x1C07** — payload `[01][FF]` (`o7/e.java:22-32`). Response:
`rx[7]==1`, `rx[8]`=role, `rx[9]`=len, `rx[10 .. 10+len)` = ASCII version
(`o7/e.java:38-43`).

**GetFwInfo 0x1C09** — payload `[01][FF]` (`o7/d.java:22-32`). Response
`rx[9]`=len, `rx[10..]`=blob; when blob > 20 bytes: `[0..5]` header (two LE16 at
offsets 2 and 4), `[6..8]` build date `{yy+2000, mm, dd}`, `[9..28]` and
`[29..48]` two 20-byte strings (`j7/c.java:876-894`).

**QueryState 0x1C04** — payload `[01][FF]` (`o7/g.java:45-53`). Response
(`p7/a0.java:13-25`): `rx[7]`=count, then 3 bytes each
`{role, state_lo, state_hi}`; state = `LE16` (`j7/c.java:868`). Known states:

| state | meaning |
|---|---|
| `0x0101` (257) | idle / ready |
| `0x0102` (258) | FOTA failed |
| `0x0211` (529) | agent FOTA image written, awaiting commit |
| `0x0222` (546) | agent filesystem written |
| `0x0311` (785) | TWS FOTA image written |
| `0x0322` (802) | TWS filesystem written |
| `0xFFFF` | no state |

(`j7/f.java:266-319, 328-382`.)

**QueryPartitionInfo 0x1C00** (`o7/f.java:58-70`) — payload
`[count]` then `{role, storageType}` per entry (`p7/y.java:18-20`). Response
(`p7/b0.java:22-37`): `rx[7]`=count, then **11 bytes each**:

```
role(1) | partitionType(1) | storageType(1) | address(4 LE) | length(4 LE)
```

Stored in the static partition table `com.airoha.libfota1562.stage.a.K`
(`o7/f.java:73-74`) that every later stage reads. Side effects: FS bin larger
than the partition → `FILESYSTEM_SIZE_FAIL` (`o7/f.java:45-52`); two entries with
equal addresses → `setNeedToUpdateFileSystem(true)` (`o7/f.java:75-83`).

**StartTranscation 0x1C0A** — `[count][role]*` (`o7/i.java:18-24`).

**GetEraseStatus 0x0433** (`o7/p.java:74-97`):

```java
byteArrayOutputStream.write(a.K.length);            // count
byteArrayOutputStream.write(b0VarArr[i13].f69321a); // role
byteArrayOutputStream.write(a.K[i13].f69323c);      // storageType
byteArrayOutputStream.write(a.K[i13].f69324d);      // partition address (4 LE)
byteArrayOutputStream.write(bArrK2);                // total byte length (4 LE)
```

Response (`o7/p.java:108-135`): `rx[7]`=count, `rx[8]`=role, `rx[9]`=storageType,
`rx[10..13]`=addr, `rx[14..17]`=len, `rx[18..19]`=bitmap byte-length (LE16),
`rx[20..]`=erase bitmap. Bit *i* = `bitmap[i/8] & (0x80 >> (i%8))`; a set bit
means that 4 KB block is **already erased**. All blocks erased →
`SKIP_TYPE.CompareErase_stages` (`o7/p.java:140-142`).

**ComparePartition 0x0431** (`g7/a.java:19-33`):

```java
byte[] bArr3 = new byte[11];
bArr3[0] = 1;                                    // count
bArr3[1] = b11;                                  // role
bArr3[2] = b12;                                  // storageType
System.arraycopy(bArr, 0, bArr3, 3, 4);          // start address (4 LE)
System.arraycopy(this.f46770r, 0, bArr3, 7, 4);  // byte length (4 LE)
super.l(bArr3);
```

Response (`o7/q.java:153-166`): `rx[7]`=count, `rx[8]`=role, `rx[9]`=storageType,
`rx[10..13]`=addr, `rx[14..17]`=len, **`rx[18..49]` = 32-byte SHA-256** of that
flash range.

**Lock/Unlock 0x0430** (`o7/h.java:20-35`) — `[count]` then
`{role, storageType, lockFlag}`; the FOTA flows always pass `false` → `0`
(unlock) (`j7/c.java:577, 601`).

**ErasePartition 0x0404** (`o7/n.java:37-58`) — one command per not-yet-erased
4 KB block:

```java
byteArrayOutputStream.write(a.K.length);              // count
byteArrayOutputStream.write(b0VarArr[i11].f69321a);   // role
byteArrayOutputStream.write(a.K[i11].f69323c);        // storageType
byteArrayOutputStream.write(c0187a.f21515a);          // block address (4 LE)
byteArrayOutputStream.write(x8.d.k(c0187a.f21516b));  // block length (4 LE)
f7.a aVar = new f7.a((byte) 90, 1028);                // 0x0404
```

Blocks are issued **high address first** (list reversed, `o7/n.java:34,65`).
Response `rx[10..13]` = the block address, used as the ack key (`o7/n.java:85`).

**WriteFlash 0x0402** (`k7/a.java:19-32`):

```java
byte[] bArr = new byte[(b12 * 261) + 2];
bArr[0] = b11;   // storageType
bArr[1] = b12;   // page count (always 1 — o7/o.java:62)
System.arraycopy(this.f59180s[i11].a(), 0, bArr, (i11 * 261) + 2, 261);
```

Each 261-byte record (`m7/c.java:22-30`) is
`checksum(1) | address(4 LE) | data(256)`. Response (`o7/o.java:85-93`):
`rx[8]`=count, then `count*4` bytes of acked addresses from `rx[9]`.

**CheckIntegrityStorage 0x1C01** — `[count]` then `{role, storageType}`
(`o7/j.java:18-31`).
**WriteState 0x1C06** — `[count]` then `{role, state_lo, state_hi}`
(`o7/w.java:22-39`).
**Commit 0x1C02** — `[count][role]*` (`o7/k.java:23-36`); sets
`gIsDoingCommit = true`; response status `0x15` (21) → `BATTERY_LOW`
(`o7/k.java:57-60`); after success the stage waits **15 s** for the reboot
disconnect (`o7/k.java:41-44`).
**DetachReset 0x1C05** — **no payload** (`o7/l.java:14`), sets `gIsDoingCommit`.
**Stop/Cancel 0x1C03** (`o7/m.java:25-42`) — `[07][roleCount]{role, reason}*`;
reason `1` = stage error, `2` = retry exhausted (`j7/c.java:262, 456`).
**READ NVKEY 0x0A00** — payload `[keyLo][keyHi][E8][03]`; called with key
`0xF2B5` for the audio-channel query, response channel byte at `rx[9]`, value
`2` ⇒ the agent is the **right** bud (`o7/b.java:23,30-32`; `j7/c.java:856-862`).
**WriteNV 0x0A01** — `[keyLo][keyHi][data…]` (`k7/b.java:6-12`); used with key
`0x3A00`, data `{0x00}` (`j7/c.java:775, 807`).
**ReclaimNvkey 0x0A03** — payload = LE16 length (`o7/s.java:20-23`). Careful:
its handler treats status **≠ 0** as success (`o7/s.java:30-35`).
**SuspendDsp 0x0E01 / ResumeDsp 0x0E02** — no payload (`h7/b.java:7`,
`h7/a.java:7`). **GetAvaDst 0x0D00** — no payload (`i7/b.java:7`).

### 3.4 AB1562 — TWS/dual sequence (`FotaDualActionEnum.StartFota`)

Entry `j7/c.java:1007-1026` (`f0()`) → `j7/c.java:643-666`
(`p0(agentPath, partnerPath)`). **The two buds are given separate .bin files**
(`Z(agentPath, 0)` then `Z(partnerPath, 1)`, `j7/c.java:650, 652`).
Order (`k()` `j7/c.java:564-571`, `j()` `:556-562`, `m(true)` `:597-620`,
`o()` `:1220-1244`, tail `:654-664`):

| # | stage | outer id | inner id |
|---|---|---|---|
| 1 | `p7/c` `00_GetAvaDst` | **0x0D00** | — |
| 2 | `p7/d` `00_GetBatteryRelay` | 0x0D01 | 0x0CD6 |
| 3 | `p7/v` `SuspendDspRelay` (skipped in background) | 0x0D01 | 0x0E01 |
| 4 | `o7/c` `00_GetBattery` (agent) | 0x0CD6 | — |
| 5 | `o7/u` `SuspendDsp` (skipped in background) | 0x0E01 | — |
| 6 | `o7/f` `00_QueryPartitionInfo` | 0x1C00 | — |
| 7 | `o7/a` `00_FotaStart` | 0x1C08 | — |
| 8 | `o7/i` `01_StartTranscation` | 0x1C0A | — |
| 9 | `o7/p` `13_GetEraseStatus` (agent bin) | 0x0433 | — |
| 10 | `o7/q` `14_ComparePartition` | 0x0431 | — |
| 11 | `o7/h` `01_Lock_Unlock` | 0x0430 | — |
| 12 | `o7/n` `11_EraseFlashPartition` | 0x0404 | — |
| 13 | `o7/o` `12_WriteFlash` (agent) | 0x0402 | — |
| 14 | `p7/c` `00_GetAvaDst` (again) | 0x0D00 | — |
| 15 | `p7/g` `00_QueryPartitionInfoRelay` | 0x0D01 | 0x1C00 |
| 16 | `p7/a` `00_FotaStartRelay` | 0x0D01 | 0x1C08 |
| 17 | `p7/j` `01_StartTranscationRelay` | 0x0D01 | 0x1C0A |
| 18 | `p7/q` `23_GetEraseStatusRelay` (**partner bin**) | 0x0D01 | 0x0433 |
| 19 | `p7/r` `24_ComparePartitionRelay` | 0x0D01 | 0x0431 |
| 20 | `p7/i` `01_Lock_UnlockRelay` | 0x0D01 | 0x0430 |
| 21 | `p7/o` `21_EraseFlashPartitionRelay` | 0x0D01 | 0x0404 |
| 22 | `p7/p` `22_WriteFlashRelay` (partner) | 0x0D01 | 0x0402 |
| 23 | `o7/j` `04_CheckIntegrityStorage` | 0x1C01 | — |
| 24 | `o7/w` `WriteState(0x0311)` | 0x1C06 | — |
| 25 | `p7/k` `04_CheckIntegrityStorageRelay` | 0x0D01 | 0x1C01 |
| 26 | `p7/x` `WriteStateRelay(0x0311)` | 0x0D01 | 0x1C06 |
| 27 | `o7/g` `00_QueryState` | 0x1C04 | — |
| 28 | `p7/h` `00_QueryStateRelay` | 0x0D01 | 0x1C04 |

**TWS commit** (`j7/c.java:1169-1180`) — **partner first, then agent**:
GetBatteryRelay → GetBattery → CheckIntegrityRelay → CheckIntegrity →
`p7/l` `05_CommitRelay` (0x0D01/0x1C02) → `o7/k` `05_Commit` (0x1C02).

**TWS reset** (`j7/c.java:1207-1218`): GetBatteryRelay → GetBattery →
StartTranscationRelay → StartTranscation → `p7/m` DetachResetRelay →
`o7/l` DetachReset.

**TWS RestoreNewFileSystem** (`j7/c.java:622-637`): `j()` + `k()` + `l()` (agent
FS chain) + `n()` (partner FS relay chain, `j7/c.java:1182-1205`) +
`WriteStateRelay(0x0322)` + `WriteState(0x0322)` + QueryState + QueryStateRelay.
Here a **single** FS bin serves both buds (`Z(str, 2)` at `j7/c.java:625`).

Cancel is sent to both buds: StopRelay → Stop → ResumeDsp relay + local
(`j7/c.java:515-534`).

### 3.5 AB1562 — how the partner is addressed (relay)

The frame flag byte is **not** used for addressing (always `0x05`). Partner
targeting is a relay wrapper on race id **0x0D01**:

```java
// i7/c.java:6-13
public c(a aVar, f7.a aVar2) {
    super((byte) 90, 3329);
    byte[] bArrC = aVar2.c();                      // full inner frame, incl. its 0x05 header
    byte[] bArr = new byte[bArrC.length + 2];
    System.arraycopy(aVar.a(), 0, bArr, 0, 2);     // 2-byte relay destination
    System.arraycopy(bArrC, 0, bArr, 2, bArrC.length);
    l(bArr);
}
```

So `0x0D01` payload = `[dstByte0][dstByte1] || <complete inner RACE frame>`.
The 2-byte destination comes from **GetAvaDst 0x0D00**: the response is scanned
in 2-byte pairs from offset 6 and the pair whose first byte == **5** is taken as
the partner (`p7/c.java:28-44`); absent → `FotaErrorEnum.PARTNER_NOT_FOUND`
(`p7/c.java:47`). It is stored via `setRelayDst` (`p7/c.java:51`,
`j7/c.java:900-902`) and read back at
`com/airoha/libfota1562/stage/a.java:231`.

Relay responses are demuxed by matching outer id `0x0D01` then reading the inner
frame from offset 8 (§2.2). Each `p7` stage declares the expected inner race id
and inner type and sets `mIsRelay = true`; the exception is `p7/m.java:13`
(DetachResetRelay) which expects no reply.

Both buds are fully flashed; progress is accounted per role via
`AgentPartnerEnum` index 0/1 (`com/airoha/libbase/constant/AgentPartnerEnum.java:6-9`;
static per-role counters `C/D/E/F` in
`com/airoha/libfota1562/stage/a.java:44-47`). There is **no role-switch (RHO)
command emitted** in this build — `FotaStageEnum.RoleSwitch`,
`TwsActiveFotaPreparation`, `GetLeLinkStatus`, `GetRofsVersion`,
`CheckAgentChannel` are declared
(`com/airoha/libfota1562/constant/FotaStageEnum.java:17,27,32,35,37`) but
unreferenced. Cross-bud synchronisation is done purely with
`WriteState(0x0311/0x0322)` on both buds plus `QueryState` on both, and
`handleTwsQueriedStates` proceeds only when both states match
(`j7/f.java:328-382`).

### 3.6 MT2833 / MT2855 — package map and race IDs

Stage packages (all extending `com.airoha.libfota2833.fota.stage.a`):
`g8/*` single-device, `h8/*` TWS, `f8/*` start/cancel/interval,
`l8/*` + `m8/*` "adaptive" replacements used by MT2855, `k8/a.java` adaptive
long-packet base, `a8/*` `b8/*` `c8/*` payload builders.
Managers: `d8/d.java` = common base, `d8/b.java` = MT2833 BR/EDR,
`d8/c.java` = MT2833 LE-audio/GATT, `i8/a.java`+`i8/c.java` = MT2855,
`i8/b.java` = MT2855 LEA. Settings: `e8/*`, `j8/*`.

Response offsets: `raceId = LE16(rx[4], rx[5])`, `raceType = rx[1]`,
`status = rx[6]`, payload from `rx[7]` (`d8/d.java:200-201`,
`com/airoha/libfota2833/fota/stage/a.java:288`). If `status & 0x80` is set the
library strips the bit and treats it as "device busy" → switch to
background/long-packet mode; otherwise switch back to active
(`com/airoha/libfota2833/fota/stage/a.java:294-309`).

| hex | dec | stage class | meaning |
|---|---|---|---|
| **0x0402** | 1026 | `g8/g.java:14`, `h8/l.java:21`, `l8/a`, `m8/a` | WriteFlash |
| **0x0404** | 1028 | `g8/f.java:17` | Erase (single) |
| **0x0431** | 1073 | `g8/i.java:24`, `h8/n.java:38` | ComparePartition (SHA-256) |
| **0x0432** | 1074 | `h8/k.java:21` | **Erase, dual-target** (TWS) |
| **0x0433** | 1075 | `g8/h.java:29`, `h8/m.java:39`, `l8/b`, `m8/b` | GetEraseStatus |
| **0x0900** | 2304 | notify handler `d8/d.java:678-694` | RHO-done notification (module byte == 20) |
| **0x0CD4** | 3284 | `h8/f.java:8`, `c8/a.java:7` | CheckAgentChannel (which bud is right) |
| **0x0CD6** | 3286 | `h8/h.java:18`, `c8/i.java:7` | GetBattery |
| **0x0CD7** | 3287 | `h8/a.java:12`, `c8/h.java:7` | **RoleSwitch / RHO** |
| **0x1C00** | 7168 | `g8/a.java:10` | InquiryFota / QueryPartitionInfo |
| **0x1C01** | 7169 | `g8/d.java:13` | CheckIntegrity |
| **0x1C02** | 7170 | `g8/e.java:8` | Commit (single) |
| **0x1C03** | 7171 | `f8/c.java:12` | Cancel (also a device→host notify) |
| **0x1C04** | 7172 | `g8/b.java:6` | QueryState (single) |
| **0x1C06** | 7174 | `g8/j.java:8` | WriteState (single) |
| **0x1C07** | 7175 | `h8/g.java:14`, `c8/b.java:7` | GetVersion |
| **0x1C08** | 7176 | `f8/a.java:9`, `f8/b.java` | **FOTA Start** (carries the mode byte) |
| **0x1C0A** | 7178 | `g8/c.java:6` | StartTranscation (single) |
| **0x1C10** | 7184 | `h8/j.java:9`, `c8/f.java:7` | TwsStartTranscation |
| **0x1C11** | 7185 | `h8/b.java:12`, `c8/c.java:7` | TwsCommit |
| **0x1C12** | 7186 | `h8/i.java:10`, `c8/e.java:7` | TwsQueryState |
| **0x1C13** | 7187 | `h8/e.java:14` | TwsWriteState |
| **0x1C14** | 7188 | `h8/d.java:13`, `c8/d.java:7` | TwsQueryPartition |
| **0x1C1B** | 7195 | `h8/c.java:21` | Ping / keep-alive |
| **0x1C1C** | 7196 | `f8/d.java:9` | Query Transmit Interval |

FOTA state values (16-bit, LE in payload): `0x0101` done/rebootable;
`0x0200 / 0x0201 / 0x0210 / 0x0211` single erase / write / verify /
commit-ready; `0x0300 / 0x0301 / 0x0310 / 0x0311` the TWS equivalents.

### 3.7 MT2833 single-device sequence

Pre-flow query (`d8/b.java:516-525`): `h8/h`(role 0) GetBattery →
`h8/g`(role 0) GetVersion → `g8/b` QueryState.

Main flow `d8/d.java:1131-1173` (`p0()`,
"startResumableEraseProgramFotaV2StorageExt"):

1. `g8/a` `00_InquiryFota` — **0x1C00** — payload `{0x00}` (partition id).
   Response: `[7]` partitionID, `[8]` storageType, `[9..12]` partition address
   (LE32), `[13..16]` partition length (LE32) (`g8/a.java:31-72`).
2. `f8/a` `FotaStage_00_Start` — **0x1C08** — payload `{0x01, fotaModeId}`.
   Sets the `0x15` frame flag (§2.1).
3. `f8/d` `FotaStage_07` — **0x1C1C** — payload `{0x01, 0x00}`. Response
   `[8]`=role, interval = `BE16(rx[10], rx[9])` → sets the long-packet pacing
   delay (`f8/d.java:31`, `d8/d.java:882-887`). A timeout here is **non-fatal**;
   the stage is skipped (`d8/d.java:586-599`).
4. `g8/h` `13_GetEraseStatusExt` — **0x0433** — one command per region
   (`a8/b.java`): `{storageType, role, addr[4] LE, len[4] LE}`.
5. `g8/i` `14_CompareExt` — **0x0431** — `{storageType, role, addr[4], len[4]}`
   (`a8/a.java:26-31`). Response `[8]` role, `[9..12]` addr, **`[17..48]` =
   32-byte SHA-256** compared against the locally computed digest.
6. `g8/c` `01_StartTranscation` — **0x1C0A** — no payload.
7. `g8/j` `FotaStage_WriteState(0x0200)` — **0x1C06** — payload
   `{state & 0xFF, state >> 8}`.
8. `g8/f` `11_Erase` — **0x0404** — `{storageType, len[4]=00 10 00 00 (4096 LE),
   addr[4]}`. Response `[12..15]` = the flash address (ack key).
9. `g8/j` `WriteState(0x0201)` — 0x1C06.
10. `g8/j` `WriteState(0x0210)` — 0x1C06.
11. `g8/g` `12_Write` — **0x0402** — `{storageType, pageCount, page…}` where
    each page record is 261 bytes `{crc8, addr[4] LE, data[256]}`
    (`b8/f.java:22-30`). Response `[8]` = count, then `count × 4` acked
    addresses.
12. `g8/d` `04_CheckIntegrity` — **0x1C01** — `{0x01, role, storageType}`
    (`g8/d.java:18-23`).
13. `g8/j` `WriteState(0x0211)` — 0x1C06.
14. `g8/b` `00_QueryState` — **0x1C04** — no payload. Response `{rx[7], rx[8]}`
    = the 16-bit state.

**Commit** is a separate call (`d8/d.java:1230-1241`): `g8/e` `05_Commit`,
**0x1C02**, payload `{0x00}`, response type `0x5A`.
**Cancel** (`d8/d.java:1218-1224`): `f8/c` **0x1C03**, payload
`{0x07, isDual ? 3 : 1, reason}` (`f8/c.java:17`).

### 3.8 MT2833/MT2855 TWS sequence

> **Superseded for MT2833 TWS** by [`spec-airoha-mt2833-tws.md`](spec-airoha-mt2833-tws.md) (2026-09-24, fresh decompile + live WF-1000XM6 probe); see its §10.2. Not all 19 stages below are sent: a skip graph drops several on every normal run
(new spec §5–§6).

Pre-flow query (`d8/b.java:502-514`): `h8/f` CheckAgentChannel →
`h8/h`(0) → `h8/h`(1) GetBattery → `h8/g`(0) → `h8/g`(1) GetVersion →
`h8/i` TwsQueryState.

| stage | class | race id | payload |
|---|---|---|---|
| CheckAgentChannel | `h8/f.java:8` | **0x0CD4** | none; resp type `0x5B`, `[7]==1` ⇒ agent is the right bud |
| GetBattery | `h8/h.java:18` | 0x0CD6 | `{role}`; resp `[7]` role, `[8]` battery % |
| GetVersion | `h8/g.java:14` | 0x1C07 | `{role}`; resp `[7]` role, `[8]` len, `[9..]` ASCII |
| TwsQueryState | `h8/i.java:10` | 0x1C12 | none; resp `{[7],[8]}` agent state, `{[9],[10]}` partner state |

Main flow `d8/d.java:1332-1412` (`y0()`):

1. `h8/d` `FotaStageTwsQueryPartition` — **0x1C14** — payload `{0x00}`.
   Response `[8]` storageType, `[9..12]` addr, `[13..16]` len, `[17]`, plus two
   further 4-byte fields at 18/22 that the library discards.
2. `f8/a` FOTA Start — **0x1C08** — payload `{0x03, fotaModeId}` (0x03 = dual).
3. `f8/d` Query Transmit Interval — 0x1C1C.
4. `h8/m` `23_TwsGetEraseStatusExt` — **0x0433** — two `a8/b` commands per
   region, role 0 **and** role 1; fills the two per-bud sector maps.
5. `h8/n` `24_TwsCompareExt` — **0x0431** — per role/region; SHA-256 at
   `[17..48]`.
6. `g8/c` StartTranscation — 0x1C0A.
7. `h8/j` `01_TwsStartTranscation` — **0x1C10** — no payload.
8. `g8/j` WriteState(**0x0300**) — 0x1C06.
9. `h8/e` `FotaStageTwsWriteState`(0x0300) — **0x1C13** — payload
   `{s&0xFF, s>>8, s&0xFF, s>>8}` (the same state written for agent *and*
   partner in one command, `h8/e.java:20`).
10. `h8/k` `21_Erase` — **0x0432** — the dual-target 18-byte descriptor (below).
11. `g8/j` WriteState(0x0301) — 0x1C06.
12. `h8/e` TwsWriteState(0x0301) — 0x1C13.
13. `g8/j` WriteState(0x0310) — 0x1C06.
14. `h8/l` `22_TwsWrite` — **0x0402** — `{storageType, pageCount, page(261)…}`
    (`b8/d.java`); **no role field**.
15. `g8/d` CheckIntegrity role 0 — 0x1C01 — `{0x01, 0x00, storageType}`.
16. `g8/j` WriteState(**0x0311**) — 0x1C06.
17. `g8/d` CheckIntegrity role 1 — 0x1C01 — `{0x01, 0x01, storageType}`.
18. `h8/e` TwsWriteState(0x0311) — 0x1C13.
19. `h8/i` TwsQueryState — 0x1C12.

**TWS commit** (`d8/d.java:1287-1298`): `h8/b` `TwsCommit`, **0x1C11**, no
payload, 15 s timeout.
**RHO / role switch** (`d8/d.java:1025-1038`): `h8/a`, **0x0CD7**, no payload,
15 s timeout. Completion arrives as a notify on race **0x0900** with module
**LE16 rx[6..7]** `== 0x0014` (the code's `BE16(rx[7], rx[6])`), result `[8]`, agentChannel `[9]`
(`d8/d.java:678-694`).
**Keep-alive ping** (`d8/b.java:211-236`): `h8/c`, **0x1C1B**, payload
`{0x01, isTws ? 1 : 0}`.
**Device-initiated cancel** arrives as race **0x1C03** type `0x5A` with
`[6]` sender, `[7]` recipient, `[8]` reason; the library answers with a `0x5B`
and a `0x5D` frame (`d8/d.java:645-674`). Reason 0..4 →
DEVICE_CANCELLED / FOTA_FAIL / TIMEOUT / PartnerLoss / NOT_ALLOWED.

### 3.9 MT2833/MT2855 — how the partner is addressed

> **Superseded for MT2833 TWS** by [`spec-airoha-mt2833-tws.md`](spec-airoha-mt2833-tws.md) (2026-09-24, fresh decompile + live WF-1000XM6 probe); see its §10.2. In particular the agent does **not** relay the image: the host writes the
connected bud only, requests a role switch (0x0CD7) only when the agent is at 0x0311, the partner
is not and the action is StartFota, then writes the other bud in a second pass (new spec §7).

**No relay race id and no flag-byte addressing** — unlike AB1562. The partner is
selected by a **role byte inside the payload** (`0` = agent, `1` =
partner/client), and the agent bud relays the actual image internally.

* Which physical bud is "right" comes from `00_CheckAgentChannel` (0x0CD4) →
  `mgr.Z(agentIsRight)`; `mgr.k()` then decides which of the two sector maps
  (`f43934a0` = right, `f43936b0` = left) belongs to the agent
  (`h8/k.java:32-33`, `h8/l.java:27`, `h8/m.java:44`).
* Erase is one **dual-target** command (`c8/j.java:33-40`):

```java
byte[] bArr5 = new byte[18];
bArr5[0] = b11;                            // agent storage type
System.arraycopy(bArr,  0, bArr5, 1,  4);  // agent erase length (LE)
System.arraycopy(bArr2, 0, bArr5, 5,  4);  // agent flash address
bArr5[9] = b12;                            // partner storage type
System.arraycopy(bArr3, 0, bArr5, 10, 4);  // partner erase length
System.arraycopy(bArr4, 0, bArr5, 14, 4);  // partner flash address
```

  A side with no remaining sector gets length `{0,0,0,0}`.
* **Only the agent receives the binary.** `22_TwsWrite` iterates only the
  agent-side sector map (`h8/l.java:27`) and 0x0402 has no role field. The
  partner is updated by earbud-to-earbud relay; the app only *verifies* it via
  `04_CheckIntegrity` role 1 and the role-1 erase-status/SHA queries.
* If the partner is current but the agent is not (or vice versa), the library
  performs an **RHO (0x0CD7)** so the connected bud becomes the one that needs
  data (`d8/b.java:282-289`, `d8/d.java:1025-1038`).
* Progress is attributed with `AgentPartnerEnum`
  (`AGENT=0, PARTNER=1, BOTH=2, UNKNOWN=255`); when the client can skip
  everything, the reported role is flipped to `PARTNER` (`h8/n.java:255-259`).

### 3.10 MT2833 vs MT2855 — real differences

Class map: MT2833 = `d8.b extends d8.d` (BR/EDR) and `d8.c extends d8.d`
(LEA/GATT, TAG `AirohaFotaMgr2833LEA`); MT2855 =
`i8.a extends i8.c extends d8.d` plus `i8.b` (LEA/GATT). Everything in
`h8/`, `g8/`, `f8/`, `a8/`, `b8/`, `c8/`, `z7/`, `x8/` is shared. Race IDs and
payload formats are **identical**. The differences are:

1. **Adaptive stage substitution.** MT2855 swaps in `l8/b`
   `13_GetEraseStatusExtAdaptive` for `g8/h`, `l8/a` `12_WriteAdaptive` for
   `g8/g`, `m8/b` for `h8/m`, `m8/a` for `h8/l` (`i8/c.java:59-65, 103-113`
   vs `d8/d.java:1137-1148, 1338-1348`).
2. **Pages per write command**: MT2855 packs `k8.a.f59278y = 2` pages (522
   payload bytes) per 0x0402 and scales the progress denominator accordingly
   (`k8/a.java:11`, `l8/a.java:52-66`, `l8/b.java:110-116`); MT2833 sends 1
   page per command (`h8/l.java:33-57`, `g8/g.java:25-50`).
3. **Long-packet command count**: MT2855 `j0(4)` (`i8/a.java:641`), MT2833
   `j0(3)` (`d8/b.java:621`).
4. **Adaptive mode is requested on MT2855 only.**
   `FotaControl2855.start(...)` hard-codes the adaptive flag `true`
   (`com/airoha/project/sony/FotaControl2855.java:357,363`) while
   `FotaControl2833.start(...)` hard-codes `false`
   (`com/airoha/project/sony/FotaControl2833.java:358,364`). So MT2855 sends
   `FotaModeId.Adaptive` (2) and enables the busy-bit auto mode switch; MT2833
   sends `Background` (0).
5. `j8/a`,`j8/b` add a page-count settings field that is written but never read;
   the effective page count is the static `k8.a.f59278y`.
6. `k8/a` reimplements the long-packet assembler with per-command length
   tracking, because adaptive write commands vary in length; the MT2833 version
   assumes all commands in a packet are the same length
   (`com/airoha/libfota2833/fota/stage/a.java:429-432`).
7. The GATT/LEA managers (`d8.c`, `i8.b`) use `f8/b`
   `FotaStage_00_StartLEA`, which **ORs `0x10` into the mode byte**:
   `new z7.a((byte)90, 7176, new byte[]{b11, (byte)(value | 0x10)})`
   (`f8/b.java:24`) — only when the LE-audio role is AGENT; otherwise they just
   set the `0x15` frame flag locally. Their single-device pipeline ends with
   `WriteState` = 0x0311 when TWS else 0x0211 (`d8/c.java:704-738`).

### 3.11 AB1568 / MT2822 (`libfota1568`) — same protocol as MT2833

Packages: `q7/a.java` RACE packet, `r7/*` compare+erase-status builders,
`s7/*` single-device payload builders, `t7/*` TWS payload builders,
`w7/*` start/cancel stages, `x7/*` single stages, `y7/*` TWS stages,
`u7/d.java` manager base, `u7/b.java` SPP manager, `u7/c.java` LE-audio/GATT
manager, `v7/*` settings, `com/airoha/libfota1568/fota/stage/{a,b}.java` stage
base + transmit-interval stage.

**The race ID set is identical to MT2833/MT2855** — verified independently from
the payload-builder constructors: `0x1C00` (`s7/a.java:7`), `0x1C04`
(`s7/b.java:7`), `0x1C0A` (`s7/c.java:7`), `0x0402` (`s7/d.java:19`), `0x1C06`
(`s7/e.java:7`), `0x0CD4` (`t7/a.java:7`), `0x1C07` (`t7/b.java:7`), `0x1C11`
(`t7/c.java:7`), `0x1C14` (`t7/d.java:7`), `0x1C12` (`t7/e.java:7`), `0x1C10`
(`t7/f.java:7`), `0x1C13` (`t7/g.java:7`), `0x0CD7` (`t7/h.java:7`), `0x0CD6`
(`t7/i.java:7`), `0x0432` (`t7/j.java:26`), `0x0431` (`r7/a.java:20`), `0x0433`
(`r7/b.java:20`), `0x1C01` (`x7/d.java:26`), `0x1C02` (`x7/e.java:10`), `0x1C03`
(`w7/c.java:17`), `0x1C08` (`w7/a.java:28`), `0x0404` (`x7/f.java:19`),
`0x1C1B` (`y7/c.java:18`), `0x1C1C`
(`com/airoha/libfota1568/fota/stage/b.java:18`), plus the `0x0900` RHO-done
notify (`u7/d.java:716-724`).

The `0x15` session flag works exactly as in §2.1: `q7.a.g(boolean)`
(`q7/a.java:110-112`), set by `w7/a.java:29` (`n0(true)`) and enforced on RX at
`u7/d.java:242`.

Single-device query phase (`u7/b.java:496-503`): GetEraseStatus 0x0433 →
GetVersion 0x1C07 (role 0) → QueryState 0x1C04.
Single-device transfer (`u7/d.java:1393-1434`): `0x1C00` → `0x1C08` →
`0x1C1C` → `0x0433` → `0x0431` → `0x1C0A` → `WriteState 0x0200` → `0x0404` →
`WriteState 0x0201` → `WriteState 0x0210` → `0x0402` → `0x1C01` →
`WriteState 0x0211` → `0x1C04`. Commit `0x1C02` (`u7/d.java:1527`, resp type
`0x5A`).

TWS query phase (`u7/b.java:482-492`): `0x0CD4` → `0x0433`(role 0) →
`0x0433`(role 1) → `0x1C07`(0) → `0x1C07`(1) → `0x1C12`.
TWS transfer (`u7/d.java:880-959`): `0x1C14` → `0x1C08`(dual) → `0x1C1C` →
`0x0433` → `0x0431` → `0x1C0A` → `0x1C10` → `WriteState 0x0300` →
`TwsWriteState 0x0300` → `0x0432` → `WriteState 0x0301` →
`TwsWriteState 0x0301` → `WriteState 0x0310` → `0x0402` → `0x1C01`(role 0) →
`WriteState 0x0311` → `0x1C01`(role 1) → `TwsWriteState 0x0311` → `0x1C12`.
TWS commit `0x1C11` (`u7/d.java:871`); RHO `0x0CD7` (`u7/d.java:1258`), max 3
attempts (`u7/d.java:1255-1257`).

Payload layouts, chunking (4096 B sectors / 256 B pages / 261 B records / CRC-8
+ SHA-256), the erase bitmap bit order, the SKIP_TYPE resume graph and the
progress formula are **identical to §3.7-§4.6** — see `x7/g.java:30-45`,
`x7/h.java:64-76`, `s7/f.java:22-30`, `s7/d.java:23-29`, `u7/b.java:404-421`.
Differences worth noting:

* Mode selection is richer (`u7/b.java:577-608`):
  `isBackground && isResumable` → **Adaptive** with the busy-bit auto-switch
  enabled; `isBackground` alone → **Background**; else **Active**. Sony's
  `FotaControl2822` forces the 4th flag `false`
  (`com/airoha/project/sony/FotaControl2822.java:343-345`), so MT2822 runs plain
  Background, never Adaptive.
* `FotaControl2822` **honours** the caller's partial-read-flash KB
  (`FotaControl2822.java:348-349`); the default is 512 KB
  (`u7/b.java:32, 562-571`).
* The battery stage `y7/h` (0x0CD6) exists but is **not enqueued** by any default
  queue, and the app-supplied threshold (`u7/b.java:565-567`) has no reader —
  same situation as MT2833 (§4.8).
* Error enum is the 32-value
  `com/airoha/libfota1568/fota/AirohaFotaErrorEnum.java:5-38`, including
  `FOTA_BIN_FILE_SIZE_TOO_LARGE` and `UNEXPECTED_RHO`.

### 3.12 MT2811 (`com/airoha/android/lib/fota`) — legacy variant

Packages: `o6/a.java` RACE packet, `p6/*` compare/erase-status builders,
`q6/*` single builders, `r6/*` TWS builders, `v6/*` start+cancel stages,
`w6/*` single stages, `x6/*` TWS stages, `s6/b.java` + `s6/c.java` manager,
`t6/*` `u6/*` settings, `e7/*` byte+CRC8+SHA helpers, `z6/a.java`
`AirohaSppController` (§1.2).

Race IDs are the same numeric map, with two deltas:

* **`0x1C19` (7193) `TwsActiveFota`** exists only here — payload
  `{agentOrClient}` (`r6/b.java:7`,
  `com/airoha/android/lib/fota/stage/b.java:17-20`).
* **No `0x0CD6` battery command at all**, and no `0x0900` RHO-done sniffing
  (RHO completion arrives through a transport callback `c7.c.a(byte)`,
  `s6/c.java:120-131`).

**The `0x10` flag bit is unconditional here** — every frame is `0x15`:

```java
// o6/a.java:106-108
public byte[] f() { return e(BSON.NUMBER_INT); }   // BSON.NUMBER_INT = 0x10
```

There is no `g(boolean)` and no RX flag filter, unlike every other family.

**`0x1C08` carries no mode byte** — it is a single byte, `3` for TWS else `1`:

```java
// v6/a.java:19
o6.a aVar = new o6.a((byte) 90, 7176, new byte[]{this.F ? (byte) 3 : (byte) 1});
```

`FotaModeId` is never referenced in this family; background vs active only sets
booleans and pacing (`s6/c.java:492-520`).

Single query phase (`s6/c.java:392-400`): **`0x1C08` first** (FOTA Start happens
in the *query* phase here, not the transfer phase) → `0x1C1C` → `0x1C00` →
`0x1C04`.
Single transfer (`s6/b.java:919-958`): `0x0433` → `0x0431` → `0x1C0A` →
`WriteState 0x0200` → `0x0404` → `0x0201` → `0x0210` → `0x0402` → `0x1C01` →
`0x0211` → `0x1C04`. Commit `0x1C02` (`s6/b.java:983`).
TWS query phase (`s6/c.java:370-380`): `0x1C08`(TWS) → `0x1C1C` → `0x0CD4` →
`0x1C14` → `0x1C07`(0) → `0x1C07`(1) → `0x1C12`.
TWS transfer (`s6/b.java:1047-1124`): `0x0433` → `0x0431` → `0x1C0A` →
`0x1C10` → `0x0300` → `TwsWriteState 0x0300` → `0x0432` → `0x0301` →
`TwsWriteState 0x0301` → `0x0310` → `0x0402` → `0x1C01`(0) → `0x0311` →
`0x1C01`(1) → `TwsWriteState 0x0311` → `0x1C12`. TWS commit `0x1C11`
(`s6/b.java:1035`); RHO `0x0CD7` (`s6/b.java:975`).

Framing, CRC-8 (`e7/a.java`), SHA-256 (`e7/e.java`), 4096/256/261 chunking
(`w6/g.java:30-49`, `q6/f.java:22-30`, `q6/d.java:23-29`), erase payload
(`w6/f.java:28-38`: `[storageType][0x00001000 LE][addr LE]`), bitmap bit order
and the SKIP_TYPE graph (`s6/b.java:933-946`, `:1065-1107`, pruned by
`s6/b.java:755-769`) all match the other families. Real behavioural differences
to reproduce:

* **`partialReadFlashLength` = 2 MB**, and `FotaControl2811.start(…, i12)`
  **ignores the caller's value**, always passing 2048 KB
  (`com/airoha/project/sony/FotaControl2811.java:291-292`; `s6/c.java:27`,
  `:487-489`).
* Unacked-command re-send window is **`+1`** instead of `+3`
  (`com/airoha/android/lib/fota/stage/a.java:182`).
* Long-packet pacing is an **inline `SystemClock.sleep` on the TX path**
  (`.../stage/a.java:222-225, 405-408`) rather than a separate timer thread.
* `completedTaskCount` is incremented inside `handleResp` for every OK response
  (`.../stage/a.java:321-323`), which the other families do not do — so progress
  denominators are **not** interchangeable.
* No bin-size-vs-partition guard (`w6/a.java` has no equivalent of
  `x7/a.java:51-76`), and no `FOTA_BIN_FILE_SIZE_TOO_LARGE` error.
* No RHO/commit retry cap; cancel delay 1000 ms (vs 2000 ms) (`s6/b.java:671`);
  the cancel stage keeps the default 9000 ms timeout.
* Block tables and progress counters are **`static` on the stage base class**
  (`com/airoha/android/lib/fota/stage/a.java:50-63`) — a multi-instance hazard.
* Error enum is the 15-value
  `com/airoha/android/lib/fota/AirohaRaceOtaError.java:5-21`; cancel-reason
  mapping still matches 1:1 (`s6/b.java:522-535`).

### 3.13 Summary: what is shared across all four families

Safe to implement once: the RACE frame builder/deframer, the CRC-8 algorithm,
SHA-256 usage, 4096-byte read granularity, the 256-byte page / 261-byte record
program format, the `0x0402 / 0x0404 / 0x0431 / 0x0432 / 0x0433` payload and
response layouts, the FOTA state values
`0x0101 / 0x0200 / 0x0201 / 0x0210 / 0x0211 / 0x0300 / 0x0301 / 0x0310 / 0x0311`,
the SKIP_TYPE resume graph, the erase-bitmap bit order, and the shape of the
progress formula.

Genuinely per-family: AB1562's relay-based TWS (0x0D01 + 0x0D00) and its
distinct race IDs for start/commit; MT2811's unconditional `0x15` flag and
mode-less `0x1C08`; MT2855's 2-pages-per-command adaptive write.

---

## 4. Data chunking, flow control and progress

The scheme is the same in every family; only the constants differ.

### 4.1 Constants

| quantity | AB1562 | MT2833 | MT2855 |
|---|---|---|---|
| erase / sector granularity | **4096 B** (`o7/p.java:53,62,66,117`) | **4096 B** (`h8/m.java:71-85`, `g8/h.java:54-66`) | 4096 B |
| program page | **256 B** (`o7/o.java:40-44`) | **256 B** (`h8/l.java:29-59`) | 256 B |
| wire page record | **261 B** (`m7/c.java:22-30`) | **261 B** (`b8/f.java:22-30`) | 261 B |
| pages per 0x0402 command | **1** (`o7/o.java:62`) | **1** (`h8/l.java:33-57`) | **2** (`k8/a.java:11`) |
| erase-status / compare region | (whole partition) | **512 KB** = 524288 B → 128 sectors (`d8/b.java:34,585,596`) | same |
| pre-poll window | **4** (`com/airoha/libfota1562/stage/a.java:26`, `m7/a.java:17`) | **4** (`com/airoha/libfota2833/fota/stage/a.java:29`, `e8/a.java:6`) | 4 |
| commands per long packet | n/a | **3** (`d8/b.java:621`) | **4** (`i8/a.java:641`) |
| inter-command pacing | **200 ms** after a busy status (`m7/a.java:29`) | **200 ms** background / **0** active (`d8/b.java:622,633`) | same |

AB1568/MT2822 matches the MT2833 column throughout; MT2811 matches it except
for a 2 MB compare region and a `+1` retransmit window — see §3.11 and §3.12.

The 4096 constant is rendered by jadx as `Calib3d.CALIB_FIX_K5`
(`org/opencv/calib3d/Calib3d.java:38` = 4096); the erase-length payload constant
is pre-baked as `{0x00, 0x10, 0x00, 0x00}`
(`com/airoha/libfota2833/fota/stage/a.java:38`).

### 4.2 How the .bin is sliced

Two levels. **Sector level** — read the file in 4096-byte chunks, pad the tail
with `0xFF`, and assign each chunk an absolute flash address starting at the
**device-reported** partition address:

```java
// o7/p.java:49-72  (AB1562; h8/m.java:71-85 is the MT2833 equivalent)
int iE = x8.d.e(a.K[0].f69324d);              // partition start address from 0x1C00
byte[] bArr = new byte[4096];
Arrays.fill(bArr, (byte) -1);                 // pad with 0xFF
int i12 = inputStream.read(bArr);
i11 += 4096;                                  // declared total = ceil(size/4096)*4096
linkedList.add(new a.C0187a(x8.d.k(iE), bArr, i12));
iE += 4096;
```

**Page level** — each sector is cut into 256-byte pages, 0xFF-padded, and pages
that are entirely `0xFF` are **skipped and never transmitted**
(`x8/b.java:6-13`, `o7/o.java:47`, `h8/l.java:41`):

```java
// h8/l.java:29-59  (identical in g8/g.java and o7/o.java)
int iE = x8.d.e(bVar.f21580a);        // sector base address (LE32)
int i11 = bVar.f21581b + iE;          // sector end
while (iE < i11) {
    int i13 = iE + 256;
    int i14 = i13 > i11 ? i11 - iE : 256;
    byte[] bArr2 = new byte[256]; Arrays.fill(bArr2, (byte) -1);
    System.arraycopy(bVar.f21582c, i12, bArr2, 0, i14);
    if (!x8.b.a(bArr2)) { /* crc8 + addr + 256 B -> one 0x0402 record */ }
    i12 += 256; iE = i13;
}
```

Ordering quirks to reproduce: AB1562 issues erase commands **high address
first** (list reversed at `o7/n.java:34,65`, reversed again on a successful
erase-status response at `o7/p.java:150`) and pushes pages onto a `Stack`, so
**within a sector pages go highest-address-first** (`o7/o.java:35,60-63`).

### 4.3 Flow control

Windowed, not strict stop-and-wait:

* Up to `prePollSize` (4) commands are pushed before the stage waits
  (`com/airoha/libfota1562/stage/a.java:388-403`,
  `com/airoha/libfota2833/fota/stage/a.java:506-520`).
* Each write response echoes a **list of completed page addresses**
  (`count` at `rx[8]`, then `count × 4` bytes from `rx[9]`), matched against a
  pending map keyed by the 4-byte address (`o7/o.java:85-108`,
  `h8/l.java:110-127`). A stage completes only when every pending packet is
  `Success` (`o7/o.java:71-80`).
* In background/long-packet mode several equal-length commands are concatenated
  into **one transport packet**; the count is
  `commandsPerPacket − waitingRespCount`
  (`com/airoha/libfota2833/fota/stage/a.java:411-432`, `k8/a.java:43-120`).
* **Windowed retransmit**: a command whose packet index is 3 or more behind the
  current index is re-sent —
  `if (aVar.b() + 3 < mgr.f43940d0)` (`com/airoha/libfota2833/fota/stage/a.java:391`,
  `k8/a.java:62`).
* A `0xD0`/`0xD1` (AB1562) or `status & 0x80` (MT2833/2855) reply means "device
  busy": the next send is deferred by the pacing delay instead of going out
  immediately (`j7/c.java:545-552, 995-1005`;
  `com/airoha/libfota2833/fota/stage/a.java:294-309`).

### 4.4 Progress computation

Both families use the same two-segment weighting.

AB1562 (`j7/f.java:459-484` split, `386-437` value):
`C[role]` = sector count, `D[role] = C[role] × 16` = total pages
(`o7/p.java:87-90`), `E[role]` = erase commands actually issued
(`o7/n.java:60-62`), `F[role]` = write commands issued (`o7/o.java:116-118`).

```
eraseWeight p = (int)((E / (D + E)) * 100)
Erase stage                     -> base 0,  span p
WriteFlash stage                -> base p,  span 99 - p
CheckIntegrity / ComparePartitionFS -> base 99, span 1
anything else                   -> base -1  => no progress event
progress = base + ((completed + (N - stageTotal)) / N) * span
           where N = E[role] for erase, D[role] for write
```

If the filesystem partition also needs updating the value is halved and offset
by 50 (`j7/f.java:410-415, 429-434`).

MT2833/2855 (`d8/b.java:345-438`): identical shape, with
`eraseWeight = eraseCount/(writeTotal + eraseCount) × 100` applied only when
`eraseCmdCount > 20`; write denominator `f43944f0 = sectorCount × 16`
(`h8/m.java:125`, `g8/h.java:98`), divided by pages-per-command and rounded up
for MT2855 adaptive (`l8/b.java:110-116`). Partner progress is forced to 100 on
TWS commit (`d8/b.java:83`).

The Sony wrapper additionally halves and offsets for TWS:
`mCurrentProgress = raw / 2`, `+ 50` for PARTNER
(`com/airoha/project/sony/FotaControl1562.java:157-168`); the
`AirohaRaceOtaListener` still receives the raw per-role value plus the role.

### 4.5 Integrity / checksums

There is **no checksum in the RACE frame** (§2). Integrity is enforced at three
levels inside the payloads:

1. **Per 256-byte page: CRC-8**, table-driven with a nibble-reverse
   finalisation, init 0 (`x8/a.java`: 256-entry table at `:16`, nibble table
   `{0,8,4,12,…}` at `:19`, final
   `f82727d = (byte)(sArr[v>>4] | (sArr[v&15]<<4))` at `:60`). It is byte 0 of
   each 261-byte record; it is never sent as its own command.

```java
// o7/o.java:48-51
x8.a aVar = new x8.a((byte) 0);
aVar.update(bArr2);
byte value = (byte) aVar.getValue();
bArr[0] = value;
```

2. **Per region: SHA-256** (`x8/e.java:9-18`, `MessageDigest("SHA-256")`),
   computed locally and compared with the device's digest returned by
   **0x0431** at response offset `[17..48]` (MT2833: `h8/n.java:283-284`,
   `g8/i.java:220-221`) or `[18..49]` (AB1562: `o7/q.java:153-166`). Matching
   regions are marked "no need to program". AB1562's `o7/r.java`
   (`15_ComparePartitionFS`) hashes the whole FS bin and raises
   `FotaErrorEnum.ERROR_SHA256` on mismatch (`o7/r.java:41-52, 78-81`).
3. **Device-side verification: 0x1C01** `CheckIntegrity`.

No CRC16 is used anywhere in the FOTA data path.

### 4.6 Resume

Resumability is *structural*, driven by what the device reports — there is no
app-side progress journal:

* **0x0433 GetEraseStatus** returns an erase bitmap; already-erased sectors are
  excluded from the erase list (`o7/p.java:128-135`, `g8/h.java:109-119`,
  `h8/m.java:138-148`).
* **0x0431 ComparePartition** SHA-256 match marks sectors as already-correct and
  excludes them from the write list (`o7/q.java:194-212`, `g8/i.java:118-141`,
  `h8/n.java:151-181`).
* Whole stages are dropped via `SKIP_TYPE`
  (`com/airoha/libfota2833/fota/stage/IAirohaFotaStage.java:5-15`:
  `All_stages, Compare_stages, Erase_stages, Program_stages,
  CompareErase_stages, Client_Erase_stages, Sinlge_StateUpdate_stages,
  WritePartnerStateCheckIntegrity_stages, None`); registered per stage and
  applied by rebuilding the queue (`d8/d.java:1147-1160, 1354-1394, 893-907`;
  `j7/c.java:580-587, 831-841`).
* After a reconnect the manager re-reads the persisted FOTA state
  (0x1C04 / 0x1C12) and resumes at start / commit / role-switch
  (`d8/b.java:260-290, 112-158`; `j7/f.java:260-383`).

### 4.7 Timeouts and retries, per family

| | AB1562 | MT2833 / MT2855 |
|---|---|---|
| default per-command timeout | **6000 ms** (`com/airoha/libfota1562/stage/a.java:98,249`) | **9000 ms** (`com/airoha/libfota2833/fota/stage/a.java:86`) |
| retry-task delay when queue empty | — | 9000 ms (`d8/d.java:129,744-746`) |
| per-packet retry limit | **3** (`f7/a.java:136-138`) | **3** (`z7/a.java:126-128`) |
| commit / reboot wait | 15000 ms (`o7/k.java:15,43`) | 15000 ms (`h8/b.java:11`) |
| role-switch (RHO) timeout | n/a (no RHO emitted) | 15000 ms (`h8/a.java:11`) |
| cancel timeout | — | 3000 ms (`f8/c.java:11`) |
| GetAvaDst timeout | 3000 ms (`p7/c.java:14`) | n/a |
| post-reconnect delay | 2000 ms (`j7/c.java:220`) | 1000 ms for dual actions (`d8/b.java:465`) |
| keep-alive ping | none | every **9000 ms**, >3 unanswered → `PING_FAIL` (`d8/b.java:218-224,250`) |
| lock acquisition | 5000 ms (`j7/c.java:242,412,1078`) | 5000 ms / 3000 ms (`d8/d.java:792`) |

Retry exhaustion notifies the error and then sends the cancel command with
reason `2` (`j7/c.java:453-457`, `d8/d.java:713-721`). AB1562 exempts the
Cancel and Erase stages from retry (`j7/c.java:447,460-463`). On MT2833/2855,
RHO and Commit have their own counter (`> 3` → `RHO_FAIL` / `COMMIT_FAIL`,
`d8/d.java:606-611, 620-624, 1032-1033`).

### 4.8 Battery threshold and FOTA mode

**Battery** is read with **0x0CD6**, payload `{role}` for MT2833/2855
(`h8/h.java:24`, `c8/i.java:7`) or `{0x00}` for AB1562 (`o7/c.java:25`,
`p7/z.java:6-8`); the level is `rx[8]`, 0-100.

* **AB1562 enforces the threshold locally.** Default **20 %**
  (`m7/a.java:26`, `m7/b.java:26`):

```java
// o7/c.java:36-45
if (i13 < this.f21491b.w().f64334f) {          // f64334f = threshold
    FotaErrorEnum fotaErrorEnum = FotaErrorEnum.BATTERY_LOW;
    this.f21491b.Y(false);                     // block further flash operations
    ...
} else { this.f21491b.Y(true); }
```

  Commit can also fail with device status `0x15` (21) → `BATTERY_LOW`
  (`o7/k.java:57-60`).
* **MT2833/MT2855 do not enforce it locally in this build.** The value lands in
  `settings.f44840g` / `f44847f` (default **70**, `e8/a.java:11`,
  `e8/b.java:10`) and `mgr.R` (default 50, `d8/d.java:143`), but those fields are
  only ever written, never read (`d8/b.java:590-592`, `i8/a.java:601-603`).
  Enforcement is left to the device, which refuses FOTA start or sends cancel
  reason 4 (`FOTA_NOT_ALLOWED`).

**FOTA mode is not a command** — it is the second byte of the FOTA-Start
payload (**0x1C08**), taking
`FotaModeId { Background = 0, Active = 1, Adaptive = 2 }`
(`com/airoha/libbase/RaceCommand/constant/FotaModeId.java:3-5`, payload built at
`f8/a.java:16-23`). Mode selection in
`start(batteryThreshold, isBackground, isTws, isAdaptive, kbRegion)`
(`d8/b.java:605-634`):

| mode | interval | long packet | commands/packet | pacing |
|---|---|---|---|---|
| Background | 200 ms | on | 3 (2833) / 4 (2855) | 200 ms |
| Active | 0 | off | 0 | 0 |

The device can override the pacing via **0x1C1C** (`d8/d.java:882-887`), and in
adaptive mode the `status & 0x80` busy bit flips the mode at runtime
(`com/airoha/libfota2833/fota/stage/a.java:294-309`).

For AB1562, "background" is a plain boolean with two effects (`j7/c.java:904-908`):
DSP suspend/resume (0x0E01/0x0E02) is **skipped** so audio keeps playing
(`j7/c.java:558-562, 568-571, 524-529`), and commit **waits for an explicit host
trigger** instead of running automatically (`j7/f.java:496-499, 518-521`).

---

## 5. Input format

**The library takes the raw firmware image, unmodified and unencrypted.**

* The Sony app hands over the exact bytes it downloaded:
  `pl/d.java:333` → `AirohaFotaAdapterSony.setBinaryFile(byte[])`
  (`com/airoha/project/sony/AirohaFotaAdapterSony.java:794-800`), which forwards
  it to the chip-specific control (`FotaControl2833.java:341-343` →
  `mAirohaFotaMgr2833.V0(bArr)`). `setFilePath(String)` is the alternative and is
  not used by this app.
* Upstream, `mu/d.java` downloads the file over HTTP, checks the **declared size**
  and an **MD5 or SHA-1 digest** from the metadata (`mu/d.java:402-437`) and
  caches the plain bytes; `j()` returns them verbatim (`mu/d.java:462-464`).
  `nu/o.java:877, 923, 979` pass `dVar.j()` straight into
  `nu.a.b(byte[], batteryLevel, listener)` → `pl/d.java:322-335`.
  **No decryption or transformation** happens on the app side.
* **No decryption in the Airoha library either.** No `Cipher`, no AES, no key
  material anywhere in `f7`,`g7`,`h7`,`i7`,`k7`,`m7`,`n7`,`o7`,`p7`,`x8`,`j7`,
  `z7`,`a8`,`b8`,`c8`,`d8`,`g8`,`h8`,`i8`,`k8`,`l8`,`m8` or
  `com/airoha/libfota*`. The only crypto primitives are SHA-256 (`x8/e.java`)
  and the CRC-8 table (`x8/a.java`), both used for verification only.
* **No partition table or header is parsed out of the .bin.** There is no magic
  number check. The image is an opaque stream: only its length is used
  (`j7/c.java:938-956`) and it is sliced into 4096-byte sectors. The partition
  **address, length and storage type come from the device** via
  `0x1C00` / `0x1C14`.
* Size guard: `ceil(fileSize / 4096) * 4096` must be
  `<= partitionLength − 4096`, else `FOTA_BIN_FILE_SIZE_TOO_LARGE`
  (`g8/a.java:54-72`, `h8/d.java:61-86`); AB1562 raises
  `FILESYSTEM_SIZE_FAIL` for an oversized FS image (`o7/f.java:45-52`).
* The one genuine *parser* in the library is unrelated to FOTA images: an NVR
  key/value **text** format (`&`-prefixed lines, `key=value`, hex nvkey id from
  `substring(3)`, hex value) used only by `UpdateNvr`
  (`n7/a.java:39-57`, `n7/b.java:20-26`, `j7/c.java:1303-1340`).
* **TWS caveat:** AB1562's dual flow expects **two separate .bin files**, one per
  bud (`j7/c.java:650,652`), whereas MT2833/2855 take a single image and let the
  agent relay it (§3.9). The Sony app only ever supplies one buffer, so the
  MT2833/2855 path is the one exercised for modern TWS models.

---

## 6. Sony glue

### 6.1 `SonyFOTAControl` — the interface every chip driver implements

```java
// com/airoha/project/sony/SonyFOTAControl.java:8-34
public interface SonyFOTAControl {
    void cancel(); void close();
    n8.a getAirohaLinker(); boolean isConnected();
    void registerAirohaOtaListener(AirohaRaceOtaListener l);
    void setBdAddress(String bda);
    void setBinaryFile(byte[] bin);
    void setFilePath(String path);
    void setSppUUID(UUID uuid);
    void start(int lowBatteryThreshold, boolean isBackground, boolean isTwsMode, boolean isResumable);
    void start(int lowBatteryThreshold, boolean isBackground, boolean isTwsMode, boolean isResumable, int partialReadFlashLengthKB);
    void startCommitProcess();
    void unregisterAirohaOtaListener(AirohaRaceOtaListener l);
}
```

Implementations: `FotaControl1562`, `FotaControl2811`, `FotaControl2822`,
`FotaControl2833`, `FotaControl2855` (SPP) plus `…2822Gatt`, `…2833Gatt`,
`…2855Gatt` and `…2822MultiLink`, `…2833MultiLink`, `…2855MultiLink`.

### 6.2 Chip detection and driver selection

The adapter always runs a two-step handshake first — read chip name, then (BLE
only) read device role — over a `AirohaCommonMgr` on the *same* link, then tears
that manager down and reconnects for FOTA:

```java
// com/airoha/project/sony/AirohaFotaAdapterSony.java:170-185 (onHostInitialized)
mAirohaCommonMgr = new AirohaCommonMgr(mTargetAddr, mHost, mLinkParam);
mAirohaCommonMgr.addListener(TAG, mAirohaCommonListener);
mAirohaCommonMgr.setMgrStopWhenFail(true);
mAirohaCommonMgr.readChipName();          // NVKEY 0x1002 via race 0x0A00
```

The chip name string decides `CHIP_TYPE`
(`AirohaFotaAdapterSony.java:216-228`; enum at `:61-67`):

| substring in chip name | CHIP_TYPE | ordinal |
|---|---|---|
| `"1562"` | `AB1562` | 2 |
| `"283"`, `"158"`, `"157"` | `MT2833` | 3 |
| `"285"` | `MT2855` | 4 |
| `"2822"`, `"1568"`, `"1565"` | `MT2822` | 1 |
| anything else (fallback) | `MT2811` | 0 |

Driver instantiation by ordinal — SPP path
(`AirohaFotaAdapterSony.java:115-131`):
`1 → FotaControl2822`, `2 → FotaControl1562`, `3 → FotaControl2833`,
`4 → FotaControl2855`, else `FotaControl2811`.
BLE path (`AirohaFotaAdapterSony.java:296-311`) only supports
`1 → FotaControl2822MultiLink`, `3 → FotaControl2833MultiLink`,
`4 → FotaControl2855MultiLink`, each seeded with `setDeviceList(...)`.

Failure to read the chip name → `AirohaRaceOtaError.INIT_FAIL`
(`AirohaFotaAdapterSony.java:214`); a response timeout or stop during the
handshake maps to the same (`:330-356`).

### 6.3 What the Sony wrappers override

* **`isResumable` is overridden, not passed through.** Despite the interface
  taking the flag, each wrapper hard-codes it:

| wrapper | call | forced value |
|---|---|---|
| `FotaControl2833.java:356-358` | `mAirohaFotaMgr2833.Y0(threshold, bg, tws, false)` | `false` |
| `FotaControl2822.java:343-345` | `mAirohaFotaMgr1568.d1(threshold, bg, tws, false)` | `false` |
| `FotaControl2833Gatt.java:403-408` | `mAirohaFotaMgr2833LEA.W0(threshold, bg, tws, false)` | `false` |
| `FotaControl2855.java:355-357` | `mAirohaFotaMgr2855.w1(threshold, bg, tws, true)` | **`true`** (= adaptive) |
| `FotaControl1562.java:303-307` | `mAirohaFotaMgrEx1562.O0(linkParam, threshold, bg, tws, resumable)` | passed through |
| `FotaControl2811.java:286-292` | `mAirohaRaceOtaMgrS.S0(...)`; the 5-arg form forces `2048` KB | passed through |

  So the 4th boolean means "adaptive" on the 2833/2855 managers, and Sony uses
  it to select adaptive mode for MT2855 only (§3.10 item 4).
* **Partial-read-flash length** defaults to **512 KB**
  (`AirohaFotaAdapterSony.java:81`) and is what produces the 512 KB
  erase-status/compare region (§4.1). `FotaControl2811` overrides it to 2048 KB.
* **UUIDs**: each wrapper defaults `mSppUUID = AirohaFotaAdapterSony.SONY_SPP_UUID`
  (`FotaControl2833.java:252`) and builds its own `r8.c` link param in
  `setBdAddress` (`FotaControl2833.java:329-339`). The GATT wrappers build an
  `r8.a` and set connection priority HIGH (`FotaControl2855Gatt.java:372-374`).
* No Sony-specific race IDs are introduced anywhere — the wrappers only
  configure, translate errors and aggregate progress.

### 6.4 Listener / callback state machine

`AirohaRaceOtaListener` (`com/airoha/project/sony/AirohaRaceOtaListener.java`):

```java
void onTransferStartNotification();
void onProgressChanged(int percent, AgentPartnerParam role);
void onTransferCompleted();
void onRhoNotification();
void onRhoCompleted();
void onCompleted();
void onFailed(AirohaRaceOtaError err);
```

Dispatched by the singleton `AirohaRaceOtaListenerMgr`
(`AirohaRaceOtaListenerMgr.java:55-115`). Note `setBinaryFile`/`setFilePath`
call `clearListener()` (`AirohaFotaAdapterSony.java:798, 806`), so listeners must
be registered **after** the binary is set — which is exactly the order in
`pl/d.java:333-334`.

Nominal order for a single device:
`onTransferStartNotification` (fired by the adapter right after
`mFotaControl.start(...)`, `AirohaFotaAdapterSony.java:143-144`) →
`onProgressChanged(…)` × N → `onTransferCompleted` → *(host calls
`startCommitProcess()`)* → `onCompleted`.
For TWS on MT2833 the MultiLink wrapper waits for `onTransferCompleted` from
**both** buds, forces progress 100 for each role, then calls `startDualQuery()`
on the agent (`FotaControl2833MultiLink.java:91-120`) and only raises
`notifyCompleted()` once `mFotaStateUpdateCount == mBdaControlMap.size()`
(`:34-54`). `startCommitProcess()` commits the **partner first, then the agent**
(`FotaControl2833MultiLink.java:301-308`).

`AgentPartnerParam` (the value reported to the listener) is a *different*
encoding from the on-wire role byte:
`AGENT = 0x40`, `PARTNER = 0x20`, `UNKNOWN = 0xFF`
(`com/airoha/project/sony/AgentPartnerParam.java:5-7`), parsed from the
`0x0CC4` response byte (`AirohaFotaAdapterSony.java:255-257`). The on-wire role
byte in FOTA payloads is `AgentPartnerEnum`: `AGENT = 0`, `PARTNER = 1`,
`BOTH = 2`, `UNKNOWN = 255`
(`com/airoha/libbase/constant/AgentPartnerEnum.java:6-9`).

Errors (`com/airoha/project/sony/AirohaRaceOtaError.java`, 21 values):
`FotaCanceled_ByDevice_PartnerLoss, BATTERY_LEVEL_LOW, DISCONNECTED, OTHER,
FOTA_FAIL, FOTA_TIMEOUT, PING_FAIL, RHO_FAIL, COMMIT_FAIL, CMD_RESP_TIMEOUT,
CMD_RETRY_FAIL, FOTA_START_FAIL, FOTA_CANCEL, FOTA_NOT_ALLOWED,
FotaCanceled_ByDevice_UnKnownReason, FOTA_CANCELED_BY_USER, UNEXPECTED_RHO,
INIT_FAIL, HOST_ERROR, EXCEPTION, UNKNOWN`. Host errors `2002` and `3001` are
folded into `DISCONNECTED`, everything else into `HOST_ERROR`
(`AirohaFotaAdapterSony.java:158-167`). The app then narrows these to
`MtkFotaError.{PARTNER_LOSS, BATTERY_LOW, DISCONNECTED,
FotaCanceled_ByDevice_UnKnownReason, OTHER}` (`pl/d.java:96-110`).

### 6.5 Reconnect-for-commit behaviour

`startCommitProcess()` on MT2822/2833/2855 first checks the link is still up;
if the socket is gone it sets a global `gIsCommiting = true` and **re-runs the
whole `start(...)` handshake** (chip-name read included) purely to reconnect,
then commits (`AirohaFotaAdapterSony.java:868-907`). A Kotlin client must be
prepared to reconnect and re-handshake between transfer and commit.

---

## 7. Known gaps and cautions

* `h8/k.java:54-87` (TWS erase, 0x0432) is visibly mangled by jadx — a
  `while(true)` with a duplicated `c8.j` construction and a `bArr3`
  use-before-assign. The intent is one 0x0432 per (agent sector, partner sector)
  pair with `{0,0,0,0}` length for whichever side is exhausted, but the exact
  pairing order should be re-checked against smali for byte-exact
  reproduction.
* `a8/a.java:26-31` and `a8/b.java` put `storageType` at payload[0] and `role`
  at payload[1], the reverse of the `{count, role, storageType}` convention used
  by `04_CheckIntegrity`. The response echo (`[8]` = role) is consistent with the
  code as written, but this asymmetry is worth confirming on real traffic.
* `o7/s.java:30-35` (ReclaimNvkey) treats status **≠ 0** as success — reproduce
  or fix deliberately.
* `j7/f.java:406` uses integer division for the generic progress branch, and
  `j7/f.java:409` has a decompiled condition that is effectively always true;
  treat it as "report progress during StartFota/RestoreNewFileSystem".
* The AB1562 flow keeps its partition table, sector list, per-role counters,
  in-flight count and `gIsDoingCommit` in **mutable statics**
  (`com/airoha/libfota1562/stage/a.java:26-53`); a Kotlin port should make these
  per-session state.
* `writeCharacteristic` never sets a write type (§1.5) — set
  `WRITE_TYPE_DEFAULT` explicitly and keep one write in flight per
  `onCharacteristicWrite`.
* GATT max payload (`mtu − 3`) is monotonically non-increasing; a later, larger
  MTU never raises it back (`q8/b.java:53-60`).

### 7.1 Things I could not determine from the decompile

1. **Exactly how the partner bud gets its image on MT2833/2855/2822.** The code
   proves only the agent is written over the air (0x0402 has no role field and
   iterates only the agent sector map, `h8/l.java:27`), and that RHO (0x0CD7)
   exists and is invoked when the two buds disagree
   (`d8/b.java:282-289`). Whether the partner is filled by an internal
   earbud-to-earbud relay, by a second RHO'd pass over the same connection, or
   both depending on state, is not decidable from the static code — it needs a
   real capture.
2. **The exact pairing order of the TWS dual-erase (0x0432)** — `h8/k.java:54-87`
   is mangled by jadx (see above). Byte-exact reproduction needs smali.
3. **Whether the device actually rejects `0x05` after FOTA start.** The library
   flips to `0x15` and filters RX on the bit, but nothing proves the device
   requires it.
4. **Real negotiated GATT MTU / ATT write size on Sony hardware.** The library
   requests 517 and derives `mtu − 3`; the actual value is device-dependent and
   unobservable here.
5. **Whether Sony devices enforce the battery threshold**, and with which
   value. MT2833/2855/2822/2811 never transmit it; the device is assumed to
   refuse via cancel reason 4. The AB1562 default is 20 %, the newer settings
   default to 70 %, and the app passes its own number
   (`nu/o.java:877` `this.f66873t.b()`) whose origin I did not trace.
6. **What `0x1C1C`'s negotiated interval actually is on real devices**, and
   whether `0x1C19` (`TwsActiveFota`, MT2811 only) is ever reached in practice.
7. **The two discarded 4-byte fields** in the `0x1C14` TwsQueryPartition
   response (offsets 18 and 22) — the library parses and drops them
   (`h8/d.java`, `y7/d.java:40-52`).
8. **Any server-side wrapper on the firmware image.** I verified the app applies
   no transformation and the library applies none, but I did not inspect an
   actual Sony `.bin` to confirm the on-disk file is a bare flash image rather
   than a container the *device* unpacks.
9. **`AirohaFotaAdapterSony`'s `setBdAddress(agent, partner)` BLE path** is
   fully present but the Sony app only reaches it when `f69552e` ("is LE") is
   true (`pl/d.java:328-331`); which models set that flag was out of scope.
