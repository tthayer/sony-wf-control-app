# Airoha MT2822 / MT2833 / MT2855 — byte-exact SINGLE-DEVICE (non-TWS, SPP/RFCOMM) FOTA spec

Target: a Kotlin reimplementation for **one** headphone (WH-1000XM5 class), speaking
RFCOMM/SPP on UUID `8901dfa8-5c7e-4d8f-9f0c-c2b70683f5f0`.

Source of truth: the jadx decompile of Sony Sound Connect 13.2.2 at
`…/scratchpad/sony/jadx/sources`. All `file:line` citations are relative to that
directory. Companion/overview document: `spec-airoha-fota.md` (read §1–§2 for the
transport and framing; this document supersedes its offsets where they differ —
see §9 "Corrections").

jadx constant substitutions used below (resolved from the same tree):

| jadx renders | real value | source |
|---|---|---|
| `Calib3d.CALIB_FIX_K5` | **4096** | `org/opencv/calib3d/Calib3d.java:38` |
| `Imgcodecs.IMWRITE_GIF_QUALITY` | **1026** = 0x0402 | `org/opencv/imgcodecs/Imgcodecs.java:62` |
| `Imgcodecs.IMWRITE_GIF_TRANSPARENCY` | **1028** = 0x0404 | `org/opencv/imgcodecs/Imgcodecs.java:64` |
| `Videoio.CAP_PROP_XI_IMAGE_DATA_FORMAT_RGB32_ALPHA` | **529** = 0x0211 | `org/opencv/videoio/Videoio.java:298` |
| `BSON.NUMBER_INT` | **16** = 0x10 | `org/bson/BSON.java:29` |
| `BSON.MAXKEY` | **127** = 0x7F | `org/bson/BSON.java:25` |
| `BSON.CODE_W_SCOPE` | **15** = 0x0F | `org/bson/BSON.java:20` |
| `DeviceOrientationRequest.OUTPUT_PERIOD_FAST` | **5000** | `com/google/android/gms/location/DeviceOrientationRequest.java:19` |

---

## 0. Frame template (every message below uses this)

Built by `z7.a.e(byte flag)` for MT2833/MT2855, `q7.a` for MT2822 (identical code):

```java
// z7/a.java:83-91
arrayList.add(Byte.valueOf((byte) (b11 | this.f85227a)));  // f85227a = 5  -> byte0 = flag|0x05
arrayList.add(Byte.valueOf(this.f85228b));                 // byte1 = type
arrayList.add(Byte.valueOf(this.f85230d[0]));              // byte2 = len LOW
arrayList.add(Byte.valueOf(this.f85230d[1]));              // byte3 = len HIGH
arrayList.add(Byte.valueOf(this.f85231e[0]));              // byte4 = raceId LOW
arrayList.add(Byte.valueOf(this.f85231e[1]));              // byte5 = raceId HIGH
```

```
off  size  field                                       endianness
0    1     0x05 | flag     (flag = 0x00 or 0x10)        —
1    1     type: 0x5A cmd / 0x5B rsp / 0x5D notify      —
2    2     length = 2 + payloadLen                      LITTLE
4    2     race id                                      LITTLE
6    n     payload                                      per-message
```

Length maths: `z7/a.java:142-153` — `len = raceIdBytes.length(=2) + payload.length`.
RaceId LE split: `z7/a.java:63` — `{(byte)(i11 & 255), (byte)((i11 >> 8) & 255)}`.
**No CRC/checksum in the frame.**

### 0.1 The `0x15` session flag — exactly when it flips

```java
// z7/a.java:106-108
public byte[] f(boolean z11) { return z11 ? e(BSON.NUMBER_INT) : e((byte) 0); }
```

The boolean is `d8.d.F()` → field `Y`, set by `i0(boolean)` (`d8/d.java:818-820, 976-978`).
`i0(true)` is executed inside `f8.a.i()` **before the packet is queued**:

```java
// f8/a.java:30-33
z7.a aVar = new z7.a((byte) 90, 7176, new byte[]{b11, value});
this.f21560b.i0(true);
this.f21562d.offer(aVar);
```

and the frame bytes are only materialised later in `getData()` →
`aVarPoll.f(this.f21560b.F())` (`com/airoha/libfota2833/fota/stage/a.java:467`).
**Consequence: the 0x1C08 FOTA-Start command itself already goes out with
byte0 = 0x15.** Everything before it (chip-name handshake, 0x0CD6, 0x1C07,
0x1C04, 0x1C00) uses **0x05**, because `i0(false)` is asserted at
`d8/b.java:603` (in `start`) and `d8/b.java:520` (in `querySingleFotaInfo`).

Cleared again on FOTA-Start failure (`f8/a.java:42-44`) and on cancel
(`f8/c.java:41`).

Inbound filter (`d8/d.java:236`):

```java
if (!dVar7.Y || (bArr[0] & BSON.NUMBER_INT) == 16) {   // accept if session not open, or bit set
```

**Byte-exact subtlety worth reproducing or deliberately not reproducing:** on the
*reconnect-for-commit* path the flag is `false` again (`d8/b.java:603, 520`) and
`startCommitProcess()` goes straight to `g8.e` without a new 0x1C08
(`d8/b.java:653-677` → `d8/d.java:1230-1241`), so **0x1C02 is sent with
byte0 = 0x05 there and byte0 = 0x15 when committing inside the same session.**
The device evidently accepts both.

### 0.2 RX indexing

```java
// d8/d.java:200-201
int iG = x8.d.g(bArr[5], bArr[4]);   // raceId = LE16 at rx[4..5]
byte b11 = bArr[1];                  // type   = rx[1]
```

```java
// com/airoha/libfota2833/fota/stage/a.java:288
this.f21567i = bArr[6];              // status = rx[6]  (all FOTA race ids)
```

Payload proper therefore starts at **rx[7]** for every FOTA message.
The one exception in this whole flow is **READ NVKEY 0x0A00**, which has *no*
status byte — see §1.

---

## 1. A — Chip-detection handshake

### 1.1 Flow

`AirohaFotaAdapterSony.onHostInitialized` (`…/AirohaFotaAdapterSony.java:170-190`):

```java
mAirohaCommonMgr = new AirohaCommonMgr(mTargetAddr, mHost, mLinkParam);
mAirohaCommonMgr.addListener(TAG, mAirohaCommonListener);
mAirohaCommonMgr.setMgrStopWhenFail(true);
mAirohaCommonMgr.readChipName();                 // AirohaCommonMgr.java:370
```

### 1.2 Request — READ NVKEY 0x0A00, key 0x1002

```java
// com/airoha/libcommon/stage/CommonStageReadChipName.java:18
this.mNvKeyID = 4098;                            // 0x1002
// :26
a aVarGenReadNvKeyPacket = genReadNvKeyPacket(d.l((short) this.mNvKeyID));
```

```java
// com/airoha/libcommon/stage/CommonStage.java:104-111
protected a genReadNvKeyPacket(byte[] bArr) {
    this.mRaceId = 2560;                         // 0x0A00
    this.mRaceRespType = (byte) 91;              // 0x5B
    a aVar = new a((byte) 0, (byte) 90, this.mRaceId);   // flag 0x00 -> byte0 = 0x05
    byte[] bArrL = d.l((short) 1000);            // 0x03E8 LE
    aVar.l(new byte[]{bArr[0], bArr[1], bArrL[0], bArrL[1]});
    return aVar;
}
```

`x8.d.l(short)` = LE 2-byte (`x8/d.java:104-106`), so key 0x1002 → `02 10`.

```
request payload
off(payload)  off(frame)  size  field                value
0             6           2     nvkey id, LE16       0x1002 -> 02 10
2             8           2     max read length LE16 1000   -> E8 03
```

**Hex dump (exact bytes on the wire):**

```
05 5A 06 00 00 0A 02 10 E8 03
^  ^  ^^^^^ ^^^^^ ^^^^^ ^^^^^
|  |  len=6 0x0A00 key   maxLen
|  type 0x5A
byte0 = 0x00|0x05
```

### 1.3 Response — type **0x5B**

```java
// CommonStageReadChipName.java:34-45
int iF = d.f(bArr[7], bArr[6]);                  // (rx[7]<<8) | rx[6]  ==  LE16 at rx[6..7]
...
if (iF != 0 || (i13 = this.mRetryCount) >= this.mMaxRetry) {
    byte[] bArr2 = new byte[iF];
    System.arraycopy(bArr, 8, bArr2, 0, iF);
    String strH = d.h(d.d(bArr2));
    ...
    this.gAirohaCommonListenerMgr.notifyReadChipName(true, strH);
```

```
response layout (absolute offsets, rx[0] = flag byte)
rx[0]        flag|0x05
rx[1]        0x5B
rx[2..3]     frame length, LE16
rx[4..5]     00 0A   (0x0A00)
rx[6..7]     returned data length, LITTLE-ENDIAN 16-bit    <-- NOT a status byte
rx[8 .. 8+n) chip-name bytes
```

* **There is no status byte for 0x0A00.** `CommonStage.handleResp` does set
  `mStatusCode = bArr[6]` (`CommonStage.java:185`) but the stage overwrites it
  with `0` on success (`CommonStageReadChipName.java:43`). The only success test
  is **`LE16(rx[6..7]) != 0`**.
* String decoding: `d.d(bytes)` → uppercase hex with no separators
  (`x8/d.java:45-53`); `d.h(hexString)` → parses each 2 hex chars back to a char
  (`x8/d.java:67-76`). Net effect = **`String(bytes, ISO-8859-1)`**, i.e. plain
  byte-per-char ASCII of `rx[8 .. 8+len)`.
* Retry: `mMaxRetry = 2` (`CommonStage.java:38`), response timeout
  `TIMEOUT_RACE_CMD_NOT_RSP = 1000 ms` (`com/airoha/libcommon/AirohaCommonMgr.java:216, 429`).
  Flow locker 5000 ms, timer locker 3000 ms (`AirohaCommonMgr.java:218-219`).
  Note the decompiled condition means a **third** empty reply is still reported
  as `notifyReadChipName(true, "")`, which falls through the substring table to
  `MT2811`.
* Pre-poll depth 4 with `DELAY_POLL_TIME = 0` (`CommonStage.java:24-25`), but for
  a single-command stage `prePoolCmdQueue()` polls once (`CommonStage.java:295-306`).

### 1.4 CHIP_TYPE substring table

```java
// AirohaFotaAdapterSony.java:217-227
if (str.contains("1562"))                                          -> CHIP_TYPE.AB1562
else if (str.contains("283") || str.contains("158") || str.contains("157")) -> CHIP_TYPE.MT2833
else if (str.contains("285"))                                      -> CHIP_TYPE.MT2855
else if (str.contains("2822") || str.contains("1568") || str.contains("1565")) -> CHIP_TYPE.MT2822
else                                                               -> CHIP_TYPE.MT2811
```

Enum ordinals (`AirohaFotaAdapterSony.java:61-67`): `MT2811=0, MT2822=1,
AB1562=2, MT2833=3, MT2855=4`. SPP driver selection by ordinal
(`AirohaFotaAdapterSony.java:115-131`): `1→FotaControl2822`, `2→FotaControl1562`,
`3→FotaControl2833`, `4→FotaControl2855`, else `FotaControl2811`.
Failure to read chip name → `AirohaRaceOtaError.INIT_FAIL`
(`AirohaFotaAdapterSony.java:213-215`); response timeout / stop during the
handshake also map to `INIT_FAIL` (`:330-353`).

### 1.5 The SPP socket **is** closed and reopened between handshake and FOTA

Yes — explicitly:

```java
// AirohaFotaAdapterSony.java:228-236   (non-BLE branch)
if (!AirohaFotaAdapterSony.this.mIsBleFOTA || AirohaFotaAdapterSony.this.mTargetDeviceList == null) {
    if (AirohaFotaAdapterSony.this.mAirohaCommonMgr != null) {
        AirohaFotaAdapterSony.this.mAirohaCommonMgr.destroy();
    }
    if (AirohaFotaAdapterSony.this.mAirohaLinker != null) {
        AirohaFotaAdapterSony.this.mAirohaLinker.d(AirohaFotaAdapterSony.this.mTargetAddr);
        return;
    }
    return;
}
```

`n8.a.d(addr)` = disconnect the host (`n8/a.java:104-121` → `host.i()`). The
resulting `onHostDisconnected` callback builds the chip-specific control and
starts FOTA on a **new** socket:

```java
// AirohaFotaAdapterSony.java:108-142
if (airohaFotaAdapterSony4.mAirohaLinker != null) { AirohaFotaAdapterSony.this.mAirohaLinker.i(); }
...
mFotaControl = new FotaControl2833(mCtx, mIsDbgLogEnabled);   // :124
...
AirohaFotaAdapterSony.this.mFotaControl.setSppUUID(...);      // :135
AirohaFotaAdapterSony.this.mFotaControl.setBdAddress(...);    // :136
AirohaFotaAdapterSony.this.mFotaControl.setBinaryFile(...);   // :140
AirohaFotaAdapterSony.this.mFotaControl.start(mLowBatteryThreshold, mIsBackground,
        mIsTwsMode, mIsResumable, mPartialReadFlashLengthKB);  // :142
AirohaFotaAdapterSony.this.gOtaListenerMgr.notifyTransferStartNotification();  // :143
```

`FotaControl2833.setBdAddress` builds a fresh `r8.c` SPP link param
(`FotaControl2833.java:329-338`) and `d8.b.Z0` opens the connection
(`d8/b.java:641-650`: `this.f43939d.c((r8.c) this.f43908p0, map)`).

**A Kotlin client can skip the reconnect entirely** — nothing in the protocol
requires it; it is an artefact of the library using two managers.

### 1.6 The "device type" read (F2 B0) is **not** on the SPP path

```java
// com/airoha/libcommon/stage/CommonStageGetDeviceType.java:20-23
byte[] bArr = {-80, -14};                       // B0 F2  -> nvkey 0xF2B0
byte[] bArrL = d.l((short) 1000);               // E8 03
a aVar = new a((byte) 90, this.mRaceId /*2560*/, new byte[]{bArr[0], bArr[1], bArrL[0], bArrL[1]});
```

Frame would be `05 5A 06 00 00 0A B0 F2 E8 03`; response type 0x5B, requires
`bArr.length >= 9`, device type at **rx[8]** (`CommonStageGetDeviceType.java:30-35`).
But `AirohaCommonMgr.getDeviceType()` (`AirohaCommonMgr.java:356`) has **no
caller anywhere under `com/airoha/project`**, and
`AirohaFotaAdapterSony.onNotifyReadDeviceType` is an empty method
(`AirohaFotaAdapterSony.java:326-327`). **Do not implement it.**

The BLE-only second step is `getDeviceRole()` (race `0x0CC4`), reached only when
`mIsBleFOTA && mTargetDeviceList != null` (`AirohaFotaAdapterSony.java:238-245`).
Not on the SPP path.

---

## 2. B — Pre-flow queries for a single device

`d8.b.querySingleFotaInfo()`:

```java
// d8/b.java:516-525
public void R0() {
    this.F = false;
    this.f43935b.d("AirohaFotaMgr2833", "function = querySingleFotaInfo()");
    V();
    i0(false);                                  // frame flag back to 0x05
    this.f43960p.offer(new h(this, (byte) 0));  // h8.h  GetBattery,  role 0
    this.f43960p.offer(new h8.g(this, (byte) 0));//h8.g  GetVersion,  role 0
    this.f43960p.offer(new g8.b(this));         // g8.b  QueryState
    o0();
}
```

Reached from `queryAfterConnected()` (`d8/b.java:534-558`) once the socket is up;
`onTransferStartNotification` is fired here too (`:553-557`).

### 2.1 0x0CD6 GetBattery `{role}`

Builder `c8.i` = `super((byte) 90, 3286, bArr)` (`c8/i.java:7`);
payload built at `h8/h.java:24` = `new c8.i(new byte[]{this.f48312y})`, role = 0.
Response type **0x5D** (`h8/h.java:17` `this.f21569k = (byte) 93`).

```
request payload
off(payload) off(frame) size field
0            6          1    role (0 = agent/only bud)
```

```
05 5A 03 00 D6 0C 00
```

```
response (type 0x5D)
rx[6]   status, success = 0x00        (h8/h.java:32)
rx[7]   role echo (agentOrClient)     (h8/h.java:40)
rx[8]   battery level 0..100          (h8/h.java:41,44 -> b13 & 255)
```

Side effect: `mgr.d0(true)` = "flash operation allowed" (`h8/h.java:42`,
`d8/d.java:949-951`). **This is the only thing the battery reply gates on
MT2833/2855/2822 — the app-supplied threshold is written to
`e8.b.f44847f`/`e8.a.f44840g`/`mgr.R` (`d8/b.java:590-592`) and never read.**
Enforcement is left to the device (it answers 0x1C08 with a non-zero status or
sends cancel reason 4).

### 2.2 0x1C07 GetVersion `{role}`

Builder `c8.b` = `super((byte) 90, 7175, bArr)` (`c8/b.java:7`), payload
`{role}` (`h8/g.java:20`). Response type **0x5D** (`h8/g.java:13`; also
explicitly rejected if `i12 != 93`, `h8/g.java:28-31`).

```
05 5A 03 00 07 1C 00
```

```
response (type 0x5D)
rx[6]           status, success = 0x00                  (h8/g.java:32)
rx[7]           role                                    (h8/g.java:40)
rx[8]           version string length (0 => reject)     (h8/g.java:41-44)
rx[9 .. 9+len)  version bytes                           (h8/g.java:45-46)
```

Decoded with `x8.d.j(bytes)` (`d8/d.java:937-939`): **keep only bytes `>= 0x20`,
append as chars** — i.e. an ASCII filter, not a plain `String(bytes)`.

```java
// x8/d.java:87-95
public static String j(byte[] bArr) {
    StringBuilder sb2 = new StringBuilder();
    for (byte b11 : bArr) { if (b11 >= 32) { sb2.append((char) b11); } }
    return sb2.toString();
}
```

Role 0 → `mgr.a0()` (agent version), role 1 → `mgr.c0()` (`h8/g.java:47-51`).

### 2.3 0x1C04 QueryState (no payload)

Builder `b8.b` = `super((byte) 90, 7172, null)` (`b8/b.java:7`).
Response type = the **default 0x5B** (`com/airoha/libfota2833/fota/stage/a.java:177,179`
`this.f21569k = (byte) 91`; `g8/b.java` does not override it).

```
05 5A 02 00 04 1C
```

```
response (type 0x5B)
rx[6]  status, success = 0x00     (g8/b.java:21)
rx[7]  state LOW  byte
rx[8]  state HIGH byte
```

```java
// g8/b.java:29
this.f21560b.h0(new byte[]{bArr[7], bArr[8]});
// d8/d.java:965-970
public void h0(byte[] bArr) {
    ...
    this.f43949i = ((bArr[1] & 255) << 8) | (bArr[0] & 255);   // state = LE16 at rx[7..8]
    z();
}
```

**Which values mean what** (`d8/b.java:691-697` → `RunnableC0411b`, `:110-159`):

| state | dec | branch | meaning |
|---|---|---|---|
| **0x0211** | 529 | `d8/b.java:112-119` | image already written → fire `onTransferCompleted()` and start the ping keep-alive. **Resume at commit.** |
| **0x0101** | 257 | `d8/b.java:137-139` | *only checked while `mIsDoingCommit`* → `onDeviceRebooted()` (commit succeeded) |
| anything else | — | `d8/b.java:145-158` | set `mActingSingleAction = StartFota` and run the full transfer `u0()/v0()` |

The handler runs on the main looper **1000 ms after** the 0x1C04 reply
(`d8/b.java:696`: `postDelayed(new RunnableC0411b(this.f43949i), 1000L)`).

Other states the library writes but never resumes from: `0x0200` erase,
`0x0201` write, `0x0210` verify (see §3.7).

---

## 3. C — Main transfer flow

### 3.0 Stage order (authoritative)

```java
// d8/d.java:1131-1173   startResumableEraseProgramFotaV2StorageExt()
V();
this.f43960p.offer(new g8.a(this));                       // 1  0x1C00
this.f43960p.offer(new f8.a(this, this.f43955l));         // 2  0x1C08   (f43955l = isDual = false)
this.f43960p.offer(new f8.d(this, (byte) 0));             // 3  0x1C1C
h hVar   = new h(this);                                   //    0x0433
i iVar   = new i(this);                                   //    0x0431
j jVar   = new j(this, 512);                              //    0x1C06 state 0x0200
g8.f fVar= new g8.f(this);                                //    0x0404
j jVar2  = new j(this, 513);                              //    0x1C06 state 0x0201
j jVar3  = new j(this, 528);                              //    0x1C06 state 0x0210
g8.g gVar= new g8.g(this);                                //    0x0402
g8.d dVar= new g8.d(this, (byte) 0);                      //    0x1C01 role 0
j jVar4  = new j(this, Videoio.CAP_PROP_XI_IMAGE_DATA_FORMAT_RGB32_ALPHA); // 0x1C06 state 0x0211
g8.b bVar= new g8.b(this);                                //    0x1C04
...
this.f43960p.offer(hVar);   // 4
this.f43960p.offer(iVar);   // 5
this.f43960p.offer(new g8.c(this));   // 6  0x1C0A
this.f43960p.offer(jVar);   // 7
this.f43960p.offer(fVar);   // 8
this.f43960p.offer(jVar2);  // 9
this.f43960p.offer(jVar3);  // 10
this.f43960p.offer(gVar);   // 11
this.f43960p.offer(dVar);   // 12
this.f43960p.offer(jVar4);  // 13
this.f43960p.offer(bVar);   // 14
o0();
```

| # | race id | stage class | resp type | timeout |
|---|---|---|---|---|
| 1 | **0x1C00** InquiryFota | `g8/a.java:13` | 0x5B (default) | 9000 ms |
| 2 | **0x1C08** FOTA Start | `f8/a.java:12,14` | **0x5D** | 9000 ms |
| 3 | **0x1C1C** Query Transmit Interval | `f8/d.java:13,14` | **0x5D** | 9000 ms, **timeout non-fatal** |
| 4 | **0x0433** GetEraseStatus | `g8/h.java:36,37` | **0x5D** | 9000 ms |
| 5 | **0x0431** Compare (SHA-256) | `g8/i.java:31,32` | **0x5D** | 9000 ms |
| 6 | **0x1C0A** StartTransaction | `g8/c.java:8` | 0x5B (default) | 9000 ms |
| 7 | **0x1C06** WriteState 0x0200 | `g8/j.java:12` | 0x5B (default) | 9000 ms |
| 8 | **0x0404** Erase | `g8/f.java:20,21` | **0x5D** | 9000 ms |
| 9 | **0x1C06** WriteState 0x0201 | `g8/j.java:12` | 0x5B | 9000 ms |
| 10 | **0x1C06** WriteState 0x0210 | `g8/j.java:12` | 0x5B | 9000 ms |
| 11 | **0x0402** WriteFlash | `g8/g.java:18` | **0x5B (default — not overridden)** | 9000 ms |
| 12 | **0x1C01** CheckIntegrity | `g8/d.java:19,21` | **0x5D** | 9000 ms |
| 13 | **0x1C06** WriteState 0x0211 | `g8/j.java:12` | 0x5B | 9000 ms |
| 14 | **0x1C04** QueryState | `g8/b.java:8` | 0x5B (default) | 9000 ms |

Default timeout & retry come from the stage base:
`com/airoha/libfota2833/fota/stage/a.java:86` `protected int f21576r = 9000;`
`:177,179` `this.f21569k = (byte) 91;`
Per-packet retry limit **3** (`z7/a.java:126-128` `return this.f21535i >= 3;`).

Skip graph registered in `p0()`:

| stage that reports | SKIP_TYPE | stages dropped | cite |
|---|---|---|---|
| 0x0433 | `Compare_stages` | 0x0431 | `d8/d.java:1147` |
| 0x0433 | `CompareErase_stages` | 0x0431, WriteState 0x0200, 0x0404 | `:1148-1151` |
| 0x0431 | `Erase_stages` | WriteState 0x0200, 0x0404 | `:1152-1154` |
| 0x0431 | `All_stages` | WriteState 0x0200, 0x0404, WriteState 0x0201, WriteState 0x0210, 0x0402 | `:1155-1160` |

Queue is rebuilt by removing exactly those instances (`d8/d.java:893-907`).

---

### 3.1 `0x1C00` InquiryFota

```java
// g8/a.java:20-22
b8.a aVar = new b8.a(new byte[]{0});      // b8/a.java:7  super((byte)90, 7168, bArr)
```

```
request payload
0 / rx-off 6 : 1 byte : partition id = 0x00
```

```
05 5A 03 00 00 1C 00
```

Response type 0x5B, status rx[6] (must be 0, `g8/a.java:29-31`):

```
rx[6]      status = 0x00
rx[7]      partition ID                        g8/a.java:37
rx[8]      storageType   -> mgr.g0()           g8/a.java:39-40
rx[9..12]  partition address, LE32 -> mgr.f0() g8/a.java:42-45
rx[13..16] partition length,  LE32 -> mgr.e0() g8/a.java:47-50
```

`x8.d.e(byte[4])` is LE32→int (`x8/d.java:55-57`).

Size guard (`g8/a.java:52-78`): `ceil(binSize/4096)*4096` must be
`<= partitionLength - 4096`, else `AirohaFotaErrorEnum.FOTA_BIN_FILE_SIZE_TOO_LARGE`.

---

### 3.2 `0x1C08` FOTA Start — and the exact mode byte Sony sends

```java
// f8/a.java:19-33
if (this.f45854y) {                                       // isDual
    value = (byte) this.f21560b.p().f44839f.getValue();    // e8.a  (dual settings)
    b11 = 3;
} else {
    value = (byte) this.f21560b.t().f44846e.getValue();    // e8.b  (single settings)
    b11 = 1;
}
z7.a aVar = new z7.a((byte) 90, 7176, new byte[]{b11, value});
this.f21560b.i0(true);
```

```
request payload
off(payload) off(frame) size field
0            6          1    target: 0x01 = single, 0x03 = dual/TWS
1            7          1    FotaModeId
```

`FotaModeId` (`com/airoha/libbase/RaceCommand/constant/FotaModeId.java:6-8`):
`Background = 0`, `Active = 1`, `Adaptive = 2`.

Mode selection (`d8/b.java:605-634`):

```java
if (z11) {                                       // isBackground
    if (z13) {                                   // 4th boolean == "isAdaptive"
        this.X = true;                           // enables the busy-bit auto switch
        FotaModeId fotaModeId = FotaModeId.Adaptive;   ...
    } else {
        FotaModeId fotaModeId2 = FotaModeId.Background; ...
    }
    this.f43958n.f44841h = 200;  this.f43957m.f44848g = 200;
    m(true);      // long-packet mode ON
    j0(3);        // 3 commands per long packet
    k0(200);      // 200 ms inter-packet pacing
} else {
    FotaModeId fotaModeId3 = FotaModeId.Active;  ...  (interval 0)
    m(false); j0(0); k0(0);
}
```

**Trace of what Sony actually passes on SPP:**

```java
// pl/d.java:335
this.f69553f.start(i11, this.f69559l.d(), z(), this.f69559l.f());
```
* `i11` = low-battery threshold, from `nu/o.java:876` / `:922` / `:978`
  `this.f66873t.b()` → `az/a.java:162-164` → `q20.c.b()` → the device-reported
  "interrupt battery level" from the Tandem FW-update capability
  (`r20/c.java:56-58`, `s20/c.java:60-62`, `s20/f.java:54-56`).
  **Never transmitted on the MT28xx wire.**
* `f69559l.d()` = `UpdateCapability` field "Background Transfer"
  (`com/sony/songpal/mdr/j2objc/tandem/UpdateCapability.java:90-92, 128`),
  populated from the Tandem capability inquiry
  (`DeviceCapabilityTableset2Builder.java:1387` `this.X0 == EnableDisable.ENABLE`,
  or `:1367` `this.I0` set at `:786-793`). **Device-reported, not a constant.**
* `z()` = isTws = `!capability.repairMode && capability.tws`
  (`pl/d.java:307-312`; `UpdateCapability.java:94-96, 115-117`) → **false** for a
  single WH-1000XM5.
* `f69559l.f()` = "Resumable" (`UpdateCapability.java:111-113`) — **discarded**;
  every wrapper hard-codes the 4th manager argument:

| wrapper | call | 4th arg |
|---|---|---|
| `FotaControl2833.java:358` | `mAirohaFotaMgr2833.Y0(i11, z11, z12, false)` | **false** |
| `FotaControl2833.java:364` | `mAirohaFotaMgr2833.Z0(i11, z11, z12, false, i12)` | **false** |
| `FotaControl2822.java:344` | `mAirohaFotaMgr1568.d1(i11, z11, z12, false)` | **false** |
| `FotaControl2822.java:349` | `mAirohaFotaMgr1568.e1(i11, z11, z12, false, i12)` | **false** |
| `FotaControl2855.java:357` | `mAirohaFotaMgr2855.w1(i11, z11, z12, true)` | **true** |
| `FotaControl2855.java:363` | `mAirohaFotaMgr2855.x1(i11, z11, z12, true, i12)` | **true** |

The adapter always calls the 5-arg form with `mPartialReadFlashLengthKB`
(`AirohaFotaAdapterSony.java:142`), default **512** (`:81`).

**Therefore, on SPP:**

| chip | isBackground = true | isBackground = false |
|---|---|---|
| **MT2833** | mode byte **0x00** (Background) | 0x01 (Active) |
| **MT2822** | mode byte **0x00** (Background) | 0x01 (Active) |
| MT2855 | mode byte **0x02** (Adaptive) | 0x01 (Active) |

**Hex dumps (single device, MT2833/MT2822, Background):**

```
15 5A 04 00 08 1C 01 00
                  ^^ ^^ target=single, mode=Background
```

Active: `15 5A 04 00 08 1C 01 01`; MT2855 Adaptive: `15 5A 04 00 08 1C 01 02`.

*(GATT/LE-audio only — not our path: `f8/b.java:32` ORs `0x10` into the mode
byte: `new z7.a((byte) 90, 7176, new byte[]{b11, (byte)(value | BSON.NUMBER_INT)})`.)*

Response type **0x5D**:

```
rx[6]  status; 0x00 = accepted (f8/a.java:42-48)
```

On non-zero status the stage calls `i0(false)` (flag back to 0x05) and does *not*
mark the packet successful → the manager's retry timer fires → after 3 packet
retries → `AirohaFotaErrorEnum.CMD_RETRY_FAIL` (`d8/d.java:713-721`).

On success `mgr.O()` is called (`f8/a.java:47`) — a no-op in `d8.d`
(`d8/d.java:853-854`).

---

### 3.3 `0x1C1C` Query Transmit Interval

```java
// f8/d.java:21
z7.a aVar = new z7.a((byte) 90, this.f21568j /*7196*/, new byte[]{1, this.f45858y});
```
Constructed with role `(byte) 0` (`d8/d.java:1136`).

```
request payload
0 / rx-off 6 : 1 : count = 0x01
1 / rx-off 7 : 1 : role  = 0x00
```

```
15 5A 04 00 1C 1C 01 00
```

Response type **0x5D** (explicitly rejected otherwise, `f8/d.java:28-31`):

```java
// f8/d.java:32-40
if (b11 != 0) { return false; }
...
this.f21560b.S(bArr[8], x8.d.f(bArr[10], bArr[9]));
```

```
rx[6]      status = 0x00
rx[7]      count (unused)
rx[8]      role
rx[9..10]  interval in ms, LITTLE-ENDIAN 16-bit
```

`x8.d.f(b11, b12) = (b11<<8)|b12` (`x8/d.java:59-61`) and it is called as
`f(rx[10], rx[9])`, so the **field is LE16 at rx[9..10]**.

How it is applied:

```java
// d8/d.java:882-887
public void S(byte b11, short s11) {
    ...
    if (G()) { com.airoha.libfota2833.fota.stage.a.p(s11); }   // only in long-packet mode
}
```

`a.p(int)` sets the static `f21556v` (`stage/a.java:194-196`), which is the sleep
the long-packet pacer uses between packets (`d8/d.java:534-535`
`SystemClock.sleep((long) com.airoha.libfota2833.fota.stage.a.j())`). It
**replaces** the 200 ms installed by `k0(200)` (`d8/d.java:1021-1023`).

**Timeout here is non-fatal — the stage is skipped:**

```java
// d8/d.java:586-599   (RetryTask)
if (iAirohaFotaStage2 != null && iAirohaFotaStage2.e() == 7196) {
    ... "state = RACE_FOTA_QUERY_TRANSMIT_INTERVAL timeout; skip it!"
    d.this.B0(); d.this.z0();
    dVar3.f43961q = dVar3.f43960p.poll();
    if (iAirohaFotaStage3 != null) { iAirohaFotaStage3.start(); return; }
}
```

---

### 3.4 `0x0433` GetEraseStatus

**Region slicing.** The bin is first cut into 4096-byte sectors starting at the
device-reported partition address, tail padded with 0xFF:

```java
// g8/h.java:60-82
int iS = this.f21560b.s();                       // partition address from 0x1C00
InputStream inputStreamQ = this.f21560b.q();
this.f21560b.Z = new LinkedHashMap<>();
byte[] bArr = new byte[Calib3d.CALIB_FIX_K5];    // 4096
Arrays.fill(bArr, (byte) -1);                    // 0xFF pad
while (true) {
    int i12 = inputStreamQ.read(bArr);
    if (i12 == -1) break;
    i11 += Calib3d.CALIB_FIX_K5;                 // declared total = ceil(size/4096)*4096
    byte[] bArrK = x8.d.k(iS);
    this.f21560b.Z.put(x8.d.c(bArrK), new com.airoha.libfota2833.fota.stage.a.b(bArrK, bArr, i12));
    iS += Calib3d.CALIB_FIX_K5;
}
```

Then one 0x0433 command per **region** of `f21557w` bytes:

```java
// g8/h.java:83-103
int i13 = com.airoha.libfota2833.fota.stage.a.f21557w;   // = partialReadFlashLength in BYTES
int i14 = i11 / i13;             // number of full regions
int i15 = i11 % i13;             // remainder
byte[] bArrK2 = x8.d.k(i13);
byte[] bArrK3 = x8.d.k(i15);
int iS2 = this.f21560b.s();
for (int i16 = 0; i16 < i14; i16++) {
    byte[] bArrK4 = x8.d.k(iS2);
    a8.b bVar = new a8.b((byte) 0, this.f21560b.u(), bArrK4, bArrK2);
    ...
    iS2 += com.airoha.libfota2833.fota.stage.a.f21557w;
}
if (i15 > 0) {
    byte[] bArrK5 = x8.d.k(iS2);
    a8.b bVar2 = new a8.b((byte) 0, this.f21560b.u(), bArrK5, bArrK3);
    ...
}
```

`f21557w` defaults to **262144** (`stage/a.java:35`) but is overwritten per run by
`com.airoha.libfota2833.fota.stage.a.q(i11)` (`d8/d.java:1253, 1271`) with
`f43904s0 = partialReadFlashLengthKB * 1024` (`d8/b.java:596`).
Sony passes 512 KB (`AirohaFotaAdapterSony.java:81`), and `d8/b.java:34` also
defaults to `524288`. **So the region size is 512 KB = 524288 B = 128 sectors.**

**Payload field ORDER (note: storageType first, role second):**

```java
// a8/b.java:19-32
public b(byte b11, byte b12, byte[] bArr, byte[] bArr2) {
    super((byte) 90, 1075);
    ...
    byte[] bArr3 = new byte[10];
    bArr3[0] = b12;                                  // <- storageType   (2nd ctor arg)
    bArr3[1] = b11;                                  // <- role          (1st ctor arg)
    System.arraycopy(bArr,  0, bArr3, 2, 4);         // address, LE32
    System.arraycopy(this.f214p, 0, bArr3, 6, 4);    // length,  LE32
    super.o(bArr3);
    l(bArr);                                         // locker/ack key = the 4 address bytes
}
```
Called `new a8.b((byte) 0, this.f21560b.u(), addrLE32, lenLE32)` — so `b11` = role
= 0 and `b12` = `mgr.u()` = storageType from the 0x1C00 reply.

```
request payload (10 bytes)
off(payload) off(frame) size field                endianness
0            6          1    storageType
1            7          1    role (0)
2            8          4    region start address LITTLE
6            12         4    region byte length   LITTLE
```

**Hex dump** (storageType 0, partition address 0x00200000, region 512 KB):

```
15 5A 0C 00 33 04 00 00 00 00 20 00 00 00 08 00
^  ^  ^^^^^ ^^^^^ ^^ ^^ ^^^^^^^^^^^ ^^^^^^^^^^^
|  |  len=12 0x0433 st role addr LE   len LE (0x00080000)
```

Response type **0x5D** (explicitly rejected otherwise, `g8/h.java:136-139`):

```java
// g8/h.java:140-171
if (b11 != 0) { return false; }
byte b12 = bArr[7];
byte b13 = bArr[8];                                   // role
byte[] bArr2 = new byte[4];  System.arraycopy(bArr,  9, bArr2, 0, 4);   // address
byte[] bArr3 = new byte[4];  System.arraycopy(bArr, 13, bArr3, 0, 4);   // length
int iE = x8.d.e(bArr3) / Calib3d.CALIB_FIX_K5;                          // totalBitNum
byte[] bArr4 = new byte[2]; System.arraycopy(bArr, 17, bArr4, 0, 2);
int iG = x8.d.g(bArr4[1], bArr4[0]);                                    // bitmap byte length
byte[] bArr5 = new byte[iG]; System.arraycopy(bArr, 19, bArr5, 0, iG);  // bitmap
```

```
response layout (absolute offsets)
rx[6]         status, success = 0x00
rx[7]         recipient/count (read, unused)
rx[8]         role  -> part of the ack key
rx[9..12]     region start address, LE32  -> ack key (matched as hex(addr)+hex(role))
rx[13..16]    region byte length,   LE32  -> totalBitNum = len / 4096
rx[17..18]    erase-bitmap byte length, LITTLE-ENDIAN 16-bit
rx[19 .. 19+n) erase bitmap
```

`x8.d.g(b11, b12) = (b11<<8)+b12` (`x8/d.java:63-65`) called as
`g(bArr4[1], bArr4[0])` → **LE16 at rx[17..18]**.

**Bit order and meaning:**

```java
// g8/h.java:44-52
for (int i13 = 0; i13 < i11; i13++) {
    int i14 = 128 >> (i13 % 8);
    boolean z11 = (bArr[i13 / 8] & i14) == i14;
    ((com.airoha.libfota2833.fota.stage.a.b) arrayList.get(i13)).f21585f = z11;
    if (z11) { i12++; }
}
```

Sector *i* (counting from the first sector of the whole partition, since bitmaps
from successive regions are concatenated into `this.E` at `g8/h.java:172`) is at
`bitmap[i / 8]`, mask `0x80 >> (i % 8)` — **MSB-first within each byte**.
A **set bit means that 4096-byte sector is already erased**, stored as
`b.f21585f`, and `g8/f.java:31` skips it: `if (bVar.f21584e && !bVar.f21585f)`.

**SKIP decisions** (`g8/h.java:113-131`):

```java
s((byte) 0, this.D, this.E.toByteArray());
ArrayList arrayList = new ArrayList(this.f21560b.Z.values());
if (this.C == this.f21560b.Z.size()) {                       // every sector erased
    this.f21572n = IAirohaFotaStage.SKIP_TYPE.CompareErase_stages;
    return true;
}
if (!((com.airoha.libfota2833.fota.stage.a.b) arrayList.get(0)).f21585f) { return true; }
this.f21572n = IAirohaFotaStage.SKIP_TYPE.Compare_stages;    // sector 0 erased => nothing to compare
return true;
```

Also sets the write-progress denominators:
`mgr.f43942e0 = sectorCount`, `mgr.f43944f0 = sectorCount * 16` (`g8/h.java:106-109`).

---

### 3.5 `0x0431` Compare (SHA-256)

Payload builder is the same shape as 0x0433:

```java
// a8/a.java:19-32
public a(byte b11, byte b12, byte[] bArr, byte[] bArr2) {
    super((byte) 90, 1073, null);
    ...
    byte[] bArr3 = new byte[10];
    bArr3[0] = b12;                                  // storageType
    bArr3[1] = b11;                                  // role
    System.arraycopy(bArr,  0, bArr3, 2, 4);         // address, LE32
    System.arraycopy(this.f209o, 0, bArr3, 6, 4);    // length,  LE32
    super.o(bArr3);
    l(bArr);
}
```
Called `new a8.a(role, this.f21560b.u(), addrLE32, lenLE32)` at `g8/i.java:53, 94`.

```
request payload (10 bytes)  — identical field order to 0x0433
0 / 6  : 1 : storageType
1 / 7  : 1 : role (0)
2 / 8  : 4 : start address, LITTLE
6 / 12 : 4 : byte length,   LITTLE
```

```
15 5A 0C 00 31 04 00 00 00 00 20 00 00 00 08 00
```

Response type **0x5D** (rejected otherwise, `g8/i.java:209-212`):

```java
// g8/i.java:218-235
byte b12 = bArr[7];
byte b13 = bArr[8];
byte[] bArr2 = new byte[4]; System.arraycopy(bArr,  9, bArr2, 0, 4);
...
System.arraycopy(bArr, 13, new byte[4], 0, 4);      // length: read and discarded
byte[] bArr3 = new byte[32];
System.arraycopy(bArr, 17, bArr3, 0, 32);           // <-- SHA-256
```

```
response layout (absolute offsets)
rx[6]      status, success = 0x00
rx[7]      count (unused)
rx[8]      role  -> part of the ack key
rx[9..12]  address, LE32 -> ack key hex(addr)+hex(role)
rx[13..16] length, LE32  (parsed into a throwaway array — ignore)
rx[17..48] SHA-256 digest, 32 bytes
```

**What is hashed, and which commands are issued** (`g8/i.java:36-100`,
entered only if sector 0 is *not* already erased, `g8/i.java:171-174`):

Let `sectors[0..n)` be the ascending-address sector list from 0x0433, and
`k` = the largest index such that `sectors[0..k]` are all un-erased
(`for (i12 = 0; i12 < size && !arrayList.get(i12).f21585f; i12++) i11 = i12;`,
`g8/i.java:39-42`) — i.e. the scan stops at the first already-erased sector.

1. **Tail command** (`g8/i.java:43-56`), issued when `k >= 0`:
   * address = `sectors[k].f21580a`
   * length  = `x8.d.k(sectors[k].f21581b)` — the **actual bytes read** for that
     sector (≤ 4096), not 4096.
   * expected digest = `sectors[k].f21583d` = `x8.e.a(sectors[k].f21582c)`,
     the SHA-256 of that single sector's data buffer
     (`stage/a.java:161, 170`: `this.f21582c = new byte[i11]; this.f21583d = e.a(this.f21582c);`).
   * stored in map `E`, device reply lands in `G`.
2. **Group commands** (`g8/i.java:57-98`), issued when `k > 0`, over
   `sectors[0..k)` chunked into groups of `f21557w / 4096` = **128** sectors:
   * `bArrA = x8.c.a(bArrA, bVar2.f21582c)` — the group digest is
     `x8.e.a()` over the **byte-wise concatenation of the group's un-erased
     sector buffers** (`x8/c.java:6-11` is a plain array concat).
   * address = first sector of the group; length = `Σ f21581b` over the group's
     sectors (`g8/i.java:87-92`).
   * stored in map `D`, device reply lands in `F`.

**How compare results decide what gets written** (`g8/i.java:102-157`):

```java
if (arrayList2.size() == 0) { return IAirohaFotaStage.SKIP_TYPE.Erase_stages; }   // no tail cmd
...
for (tail entries) {
    if (Arrays.equals(this.E.get(eVar4), this.G.get(eVar4))) { this.B.get(eVar4).f21584e = false; }
    else { z12 = false; }
}
if (z12 && arrayList.size() == 0) { return IAirohaFotaStage.SKIP_TYPE.Erase_stages; }
for (group entries) {
    if (Arrays.equals(this.D.get(eVar5), this.F.get(eVar5))) {
        for (b bVar : this.A.get(eVar5)) { bVar.f21584e = false; }     // whole group already correct
    } else { z11 = false; }
}
if (z11 && z12) {
    return Arrays.equals(this.C.get(str).f21580a, this.B.get(eVar).f21580a)
        ? IAirohaFotaStage.SKIP_TYPE.All_stages
        : IAirohaFotaStage.SKIP_TYPE.Erase_stages;
}
return IAirohaFotaStage.SKIP_TYPE.None;
```

* `f21584e` = "**needs programming**". Erase uses `f21584e && !f21585f`
  (`g8/f.java:31`), Write uses `f21584e` (`g8/g.java:28`) — so a sector whose
  digest matched is **neither erased nor written**.
* `C[role]` is the **last** sector overall (`g8/i.java:38`), `B[role]` is the tail
  sector at index `k`. `All_stages` therefore means "everything matches AND the
  tail sector is the final sector" → skip WriteState 0x0200, 0x0404,
  WriteState 0x0201, WriteState 0x0210 and 0x0402.
* Then (`g8/i.java:191-204`) the *client/partner* skip type is hard-coded to
  `All_stages` for single-device, and the stage reports `All_stages` only when the
  agent side also says `All_stages`, else `Erase_stages`, else `None`.

---

### 3.6 `0x1C0A` StartTransaction

`b8.c` = `super((byte) 90, 7178, null)` (`b8/c.java:7`). No payload.
Response type 0x5B (default), status rx[6] must be 0 (`g8/c.java:21`).

```
15 5A 02 00 0A 1C
```

---

### 3.7 `0x1C06` WriteState `{state LE16}`

```java
// g8/j.java:19-20
int i11 = this.f46783y;
b8.e eVar = new b8.e(new byte[]{(byte) (i11 & 255), (byte) ((i11 >> 8) & 255)});
```
`b8.e` = `super((byte) 90, 7174, bArr)` (`b8/e.java:7`).

```
request payload
0 / 6 : 1 : state LOW  byte
1 / 7 : 1 : state HIGH byte      (i.e. LE16)
```

Sequence values for a single device (`d8/d.java:1139, 1141, 1142, 1145`):

| order | value | dec | dump |
|---|---|---|---|
| after 0x1C0A, before erase | **0x0200** | 512 | `15 5A 04 00 06 1C 00 02` |
| after erase | **0x0201** | 513 | `15 5A 04 00 06 1C 01 02` |
| before write | **0x0210** | 528 | `15 5A 04 00 06 1C 10 02` |
| after CheckIntegrity | **0x0211** | 529 | `15 5A 04 00 06 1C 11 02` |

Response 0x5B, status rx[6] must be 0 (`g8/j.java:27`). No response fields parsed.

---

### 3.8 `0x0404` Erase — one command per 4096-byte sector

```java
// g8/f.java:28-52
for (com.airoha.libfota2833.fota.stage.a.b bVar : this.f21560b.Z.values()) {
    if (bVar.f21584e && !bVar.f21585f) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(this.f21560b.u());              // storageType
        byteArrayOutputStream.write(x8.d.k(Calib3d.CALIB_FIX_K5));  // 4096, LE32
        byteArrayOutputStream.write(bVar.f21580a);                  // sector address, LE32
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        z7.a aVar = new z7.a((byte) 90, Imgcodecs.IMWRITE_GIF_TRANSPARENCY);  // 0x0404
        aVar.l(bVar.f21580a);                                       // ack key
        aVar.o(byteArray);
        this.f21562d.offer(aVar);
        this.f21563e.put(x8.d.c(bVar.f21580a), aVar);
    }
}
```

```
request payload (9 bytes) — field ORDER: storageType, LENGTH, ADDRESS
off(payload) off(frame) size field                 value / endianness
0            6          1    storageType           from 0x1C00 rx[8]
1            7          4    erase length = 4096   LITTLE  -> 00 10 00 00
5            11         4    sector flash address  LITTLE
```

**Ordering:** `this.f21560b.Z` is a `LinkedHashMap` filled in **ascending address
order** (`g8/h.java:76`), and `g8/f.java:30` iterates `Z.values()` — so erase
commands go out **low address first**. (Contrast AB1562, which reverses; MT28xx
does not.)

**Hex dump** (storageType 0, sector 0x00200000):

```
15 5A 0B 00 04 04 00 00 10 00 00 00 00 20 00
^  ^  ^^^^^ ^^^^^ ^^ ^^^^^^^^^^^ ^^^^^^^^^^^
|  |  len=11 0x0404 st len=4096LE addr LE
```

Response type **0x5D** (`g8/f.java:21`):

```java
// g8/f.java:80-94
if (b11 != 0) { return false; }
byte b12 = bArr[7];
Arrays.copyOfRange(bArr, 8, 12);                                   // erase length, discarded
z7.a aVar = this.f21563e.get(x8.d.c(Arrays.copyOfRange(bArr, 12, 16)));   // <-- ack key
```

```
response layout
rx[6]      status, success = 0x00
rx[7]      storageType (read, unused)
rx[8..11]  erase length, LE32 (read and thrown away)
rx[12..15] flash address, LE32   <-- the ACK KEY that matches the pending command
```

Pending commands are keyed on `x8.d.c(addrBytes)` = the space-separated
uppercase hex of the 4 address bytes in **stored (LSB-first) order**
(`x8/d.java:34-43`) — e.g. address 0x00200000 → key `"00 00 20 00"`.
The stage completes when every queued packet is marked
(`g8/f.java:54-64`).

---

### 3.9 `0x0402` WriteFlash

```java
// g8/g.java:25-67
for (com.airoha.libfota2833.fota.stage.a.b bVar : this.f21560b.Z.values()) {
    if (bVar.f21584e) {
        int iE = x8.d.e(bVar.f21580a);            // sector base address
        int i11 = bVar.f21581b + iE;              // sector end (real read length)
        int i12 = 0;
        while (iE < i11) {
            LinkedList linkedList = new LinkedList();
            ...
            int i13 = iE + 256;
            int i14 = i13 > i11 ? i11 - iE : 256;
            byte[] bArr2 = new byte[256];
            Arrays.fill(bArr2, (byte) -1);                       // 0xFF pad
            System.arraycopy(bVar.f21582c, i12, bArr2, 0, i14);
            if (!x8.b.a(bArr2)) {                                // skip all-0xFF pages
                x8.a aVar = new x8.a((byte) 0);
                aVar.update(bArr2);
                byte value = (byte) aVar.getValue();             // CRC-8
                ...
                byte[] bArrK = x8.d.k(iE);                       // page address, LE32
                linkedList.add(new b8.f(value, bArrK, bArr2));
            }
            i12 += 256;
            b8.f[] fVarArr = (b8.f[]) linkedList.toArray(new b8.f[linkedList.size()]);
            if (fVarArr.length != 0) {
                b8.d dVar = new b8.d(this.f21560b.u(), (byte) fVarArr.length, fVarArr);
                dVar.l(fVarArr[0].f18207b);                      // ack key = first page address
                this.f21562d.offer(dVar);
                this.f21563e.put(x8.d.c(fVarArr[0].f18207b), dVar);
            }
            iE = i13;
        }
    }
}
```

**One page per command on MT2833/MT2822** — the `LinkedList` is re-created inside
the per-page loop, so `fVarArr.length` is always 1.

```java
// b8/d.java:18-31
public d(byte b11, byte b12, f[] fVarArr) {
    super((byte) 90, Imgcodecs.IMWRITE_GIF_QUALITY);      // 0x0402
    ...
    byte[] bArr = new byte[(b12 * 261) + 2];
    bArr[0] = b11;                                        // storageType
    bArr[1] = b12;                                        // page count
    for (int i11 = 0; i11 < this.f18204o; i11++) {
        System.arraycopy(this.f18205p[i11].a(), 0, bArr, (i11 * 261) + 2, 261);
    }
    o(bArr);
    l(this.f18205p[0].f18207b);                           // ack key
}
```

```java
// b8/f.java:22-30   (the 261-byte page record)
public byte[] a() {
    byte[] bArr = new byte[261];
    bArr[0] = this.f18206a;                                        // CRC-8
    System.arraycopy(this.f18207b, 0, bArr, 1, this.f18207b.length); // address, 4 bytes LE32
    System.arraycopy(this.f18208c, 0, bArr, 5, this.f18208c.length); // 256 data bytes
    return bArr;
}
```

```
request payload
off(payload) off(frame) size field
0            6          1    storageType
1            7          1    page count (1 on MT2833/MT2822; 2 on MT2855)
2            8          261  record[0]
263          269        261  record[1]   (MT2855 only)

record layout (261 bytes)
0   1    CRC-8 over the 256 data bytes (see §7.1), init 0x00
1   4    page flash address, LITTLE-ENDIAN 32-bit
5   256  page data, 0xFF-padded
```

Length field = `2 + (2 + 261*pageCount)`. For pageCount 1: `265 = 0x0109`, total
frame **269 bytes**. For MT2855 pageCount 2: `527 = 0x020F`, frame 531 bytes.

**Hex dump** (storageType 0, page at 0x00200000, 1 page):

```
15 5A 09 01 02 04 00 01 <crc8> 00 00 20 00 <256 data bytes>
^  ^  ^^^^^ ^^^^^ ^^ ^^ ^^^^^^ ^^^^^^^^^^^
|  |  len=  0x0402 st cnt crc   page addr LE
|  |  0x0109
```

Response type — **the default 0x5B**; `g8/g.java` does *not* set `f21569k`
(compare `g8/f.java:21`, `g8/h.java:37` which do). Parsing:

```java
// g8/g.java:106-127
if (b11 != 0) { return false; }
byte b12 = bArr[7];
int i13 = bArr[8];
int i14 = i13 * 4;
byte[] bArr2 = new byte[i14];
System.arraycopy(bArr, 9, bArr2, 0, i14);
for (int i15 = 0; i15 < i13; i15++) {
    byte[] bArr3 = new byte[4];
    System.arraycopy(bArr2, i15 * 4, bArr3, 0, 4);
    z7.a aVar = this.f21563e.get(x8.d.c(bArr3));
    if (aVar != null) { if (aVar.j()) { return false; } aVar.n(); this.f21571m++; }
}
```

```
response layout
rx[6]                  status, success = 0x00
rx[7]                  storageType (read, unused)
rx[8]                  count of acked pages
rx[9 + 4*i .. +4)      acked page address i, LE32   (i in [0, count))
```

Pending pages are matched by `x8.d.c(addr4)` — the space-separated uppercase hex
of the 4 stored (LSB-first) address bytes — against the map keyed on
`fVarArr[0].f18207b` (`g8/g.java:57`). So **a client must key its in-flight table
by the raw 4 address bytes as sent**, and a single reply may ack several pages.
Stage completion: every queued packet marked (`g8/g.java:69-79`).

**All-0xFF page test** (`x8/b.java:6-13`):

```java
public static boolean a(byte[] bArr) {
    for (byte b11 : bArr) { if (b11 != -1) { return false; } }
    return true;
}
```

Such pages are never transmitted, and they are not counted in the write total.

---

### 3.10 `0x1C01` CheckIntegrity `{01, role, storageType}`

```java
// g8/d.java:28-33
z7.a aVar = new z7.a((byte) 90, 7169);
ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
byteArrayOutputStream.write(1);
byteArrayOutputStream.write(this.f46775y);        // role, (byte) 0 from d8/d.java:1144
byteArrayOutputStream.write(this.f21560b.u());    // storageType
aVar.o(byteArrayOutputStream.toByteArray());
```

```
request payload
0 / 6 : 1 : recipient count = 0x01
1 / 7 : 1 : role = 0x00
2 / 8 : 1 : storageType
```

```
15 5A 05 00 01 1C 01 00 00
```

Response type **0x5D** (`g8/d.java:21`):

```java
// g8/d.java:52-71
this.f21561c.d(..., String.format("variable = recipientCount: %02X", Byte.valueOf(bArr[7])));
byte b12 = bArr[8];
this.f21561c.d(..., String.format("variable = recipient: %02X", Byte.valueOf(b12)));
this.f21561c.d(..., String.format("variable = storageType: %02X", Byte.valueOf(bArr[9])));
if (b11 == 0) { aVar.n(); ... this.f21571m++; }
else if (b12 == 1) { aVar.n(); ... this.f21571m++; }   // partner failure tolerated
else { ... this.f21574p = true; }                       // agent failure -> error
```

```
response layout
rx[6]  status, success = 0x00
rx[7]  recipient count
rx[8]  recipient / role
rx[9]  storageType
```

Note `isCompleted()` is overridden to "any response received"
(`g8/d.java:38-41` returns `f46776z`, set at `:47`).

### 3.11 Final `0x1C04` and the "transfer completed" condition

Same message as §2.3. The expected state is **0x0211 (529)**:

```java
// d8/b.java:110-119  (RunnableC0411b, posted 1000 ms after the reply)
if (this.f43913a == 529) {
    for (d8.a aVar : b.this.f43907o0) { if (aVar != null) { aVar.onTransferCompleted(); } }
    b.this.b1();            // start the 9 s ping keep-alive
    return;
}
```

So `onTransferCompleted` fires **iff** the final 0x1C04 returns state exactly
`0x0211`. It is then translated to the app by `FotaControl2833.java:220-235`:

```java
public void onTransferCompleted() {
    FotaControl2833.this.gLogger.d("", "fota_step = Transfer Complete");
    if (AirohaFotaAdapterSony.gIsCommiting) {
        new Thread(new Runnable() { public void run() {
            SystemClock.sleep(1000L);
            FotaControl2833.this.startCommitProcess();
        }}).start();
    } else {
        FotaControl2833.this.gOtaListenerMgr.notifyTransferCompleted();
    }
}
```

---

## 4. D — Commit

### 4.1 The 0x1C02 message

```java
// g8/e.java:8-21
public e(d8.d dVar) {
    super(dVar);
    this.f21568j = 7170;
    this.f21569k = (byte) 90;                   // <-- expected response type 0x5A
    this.f21559a = "05_Commit";
}
public void i() {
    ...
    z7.a aVar = new z7.a((byte) 90, this.f21568j, new byte[]{0});
```

```
request payload
0 / 6 : 1 : 0x00
```

Dumps — both forms occur (see §0.1):

```
15 5A 03 00 02 1C 00     # committing inside the same session (flag still set)
05 5A 03 00 02 1C 00     # after the reconnect-for-commit path (i0(false) was re-asserted)
```

Response type **0x5A** (`g8/e.java:11`):

```java
// g8/e.java:24-35
if (b11 != 0) {
    this.f21574p = true;
    this.f21575q = AirohaFotaErrorEnum.COMMIT_FAIL;
    return false;
}
```

```
rx[6]  status, success = 0x00; non-zero -> COMMIT_FAIL
```

**Timeout: the default 9000 ms** — `g8/e.java` does *not* override `f21576r`.
The 15000 ms value belongs to the **TWS** commit (`h8/b.java:11`) and to RHO
(`h8/a.java:11`) only. Commit is retried up to 3 times via the commit counter:

```java
// d8/d.java:600-612  (RetryTask)
if (dVar4.E) {                                 // mIsDoingCommit
    ... "variable = mCounterForRhoOrCommit: " + d.this.Q
    if (dVar6.Q > 3) { dVar6.E = false; dVar6.N(AirohaFotaErrorEnum.COMMIT_FAIL); d.this.X((byte) 2); return; }
}
```
`Q` is incremented once per `startSingleCommit()` (`d8/d.java:1240`).

### 4.2 The reboot wait — it is a *disconnect* event, not a timer

```java
// d8/d.java:1230-1241
public void t0() {
    ... "function = startSingleCommit()"
    this.F = false;
    this.E = true;                              // mIsDoingCommit
    V();
    this.f43960p.offer(new g8.e(this));
    ...
    this.Q++;
}
```

```java
// d8/d.java:1300-1321  handleHostDisconnectedEvent()
A0(); B0(); z0();
if (this.f43960p != null) { this.f43960p.clear(); this.f43961q = null; }
...
if (this.E) {                                   // we were committing -> reboot happened
    I();                                        // d8/b.java:323-329 -> onCompleted()
    if (this.G) { this.f43941e.t(); return; }    // reconnect
    else { this.H = false; return; }
}
```

`d8.b.I()` → `d8.a.onCompleted()` → `FotaControl2833.java:117-123`
`gOtaListenerMgr.notifyCompleted()`. So **success is signalled by the socket
dropping while `mIsDoingCommit`**, not by a 15 s timer. The 15 s figure in the
overview applies to `h8/b` (TWS). `d8.b.N0()` (`onDeviceRebooted`) is only
reached from the state-based path (state 0x0101 while committing,
`d8/b.java:137-139`), and `FotaControl2833`'s `onDeviceRebooted` is **empty**
(`FotaControl2833.java:126-127`).

**Recommendation for Kotlin:** send 0x1C02, accept a 0x5A reply with status 0,
then wait for the RFCOMM socket to close (allow ~15 s) and treat that as commit
accepted.

### 4.3 What the Sony app does before commit

```java
// nu/o.java:894-905   startInstall()
public void s0() {
    ... "startInstall"
    if (!this.f66879z && !this.f66873t.c(true)) { ... return; }   // put device in update mode
    this.f66859f = 0;
    i0(MtkUpdateState.INSTALLING);
    ((nu.d) com.sony.songpal.util.p.b(this.f66864k)).n();
    this.f66863j.c();                                             // -> pl.d.c()
}
```

```java
// pl/d.java:339-346
public void c() {
    AirohaFotaAdapterSony airohaFotaAdapterSony = this.f69553f;
    if (airohaFotaAdapterSony == null) { return; }
    airohaFotaAdapterSony.unregisterAirohaOtaListener(this.f69556i);   // <-- listener removed first
    this.f69553f.startCommitProcess();
}
```

Note the listener is **unregistered before** commit, so the app deliberately does
not consume `onCompleted`. Commit is fully host-triggered — there is no automatic
commit after transfer.

`startCommitProcess()` reconnects if needed:

```java
// AirohaFotaAdapterSony.java:868-907
if (chip_type != CHIP_TYPE.MT2822 && chip_type != CHIP_TYPE.MT2833 && chip_type != CHIP_TYPE.MT2855) {
    airohaFotaAdapterSony.mFotaControl.startCommitProcess(); return;
}
if (airohaFotaAdapterSony.mFotaControl != null) {
    if (!mIsBleFOTA) {
        a airohaLinker = mFotaControl.getAirohaLinker();
        if (airohaLinker != null && airohaLinker.h(mTargetAddr)) {   // still connected?
            mFotaControl.startCommitProcess(); return;
        }
    } ...
}
AirohaFotaAdapterSony.gIsCommiting = true;
airohaFotaAdapterSony3.start(mLowBatteryThreshold, mIsBackground, mIsTwsMode, mIsResumable);
```

i.e. if the socket is gone it **re-runs the entire `start()` handshake**
(chip-name read included, §1), the query phase returns state 0x0211, and
`onTransferCompleted` with `gIsCommiting == true` sleeps 1000 ms then commits
(`FotaControl2833.java:222-231`).

Also, `a1()` stops the ping timer and clears the locker before committing:

```java
// d8/b.java:653-677
A0();                       // stopPingTimerTask
B0(); z0();
this.f43941e.x("AirohaFOTA");
this.Q = 0;
if (this.f43955l) { x0(); } else { this.J = SingleActionEnum.Commit; t0(); }
```

### 4.4 After commit

The Airoha layer only reports `onCompleted` (§4.2). Sony's `pl/d` `onCompleted`
just logs (`pl/d.java:117-119`); the actual "installed" verdict comes from the
Tandem side reconnecting and comparing FW versions
(`nu/o.java:642-647` gates `getRunningInstaller()` on
`MtkUpdateState.INSTALLING`; `MtkUpdateState.INSTALL_COMPLETED` is driven from
`nu.d`, outside the Airoha library — see §10 open questions).

---

## 5. E — Cancel, RHO notify, ping

### 5.1 `0x1C03` Cancel (host → device)

Two producers, same bytes:

```java
// f8/c.java:15-30   (the stage, used by the normal cancel path)
public c(d8.d dVar, boolean z11, byte b11) {
    super(dVar);
    this.f21568j = 7171;
    this.f45856y = z11;      // isDual
    this.f45857z = b11;      // reason
    this.f21576r = 3000;     // <-- 3000 ms timeout, not 9000
    this.f21559a = "FotaStage_06_Cancel";
}
public void i() {
    ...
    z7.a aVar = new z7.a((byte) 90, 7171, new byte[]{7, this.f45856y ? (byte) 3 : (byte) 1, this.f45857z});
```

```java
// d8/d.java:728-733   (the "fire and forget" path, bypasses the TX scheduler)
public void X(byte b11) {
    byte b12 = this.f43955l ? (byte) 3 : (byte) 1;
    this.D = false; this.E = false;
    this.f43941e.u(new z7.a((byte) 90, 7171, new byte[]{7, b12, b11}).f(this.Y));
}
```

```
request payload
0 / 6 : 1 : 0x07  (constant)
1 / 7 : 1 : 0x01 single / 0x03 dual
2 / 8 : 1 : reason
```

```
15 5A 05 00 03 1C 07 01 00
```

Response type = default **0x5B**, status rx[6] must be 0
(`f8/c.java:34`); on success `i0(false)` (flag back to 0x05) and
`AirohaFotaErrorEnum.USER_CANCELED` is reported (`f8/c.java:41-43`).

**Reason values as emitted by this library:**

| reason | emitted from |
|---|---|
| `0` user cancel | `d8/d.java:506` `d.this.s0((byte) 0)` |
| `1` stage error | `d8/d.java:279` `d.this.X((byte) 1)` |
| `2` retry exhausted / commit fail / RHO fail | `d8/d.java:718, 609, 624` `X((byte) 2)` |

**When it is sent.** `cancel()` → `d8/d.java:972-974` → `j(isDual)`:

```java
// d8/d.java:980-1011
if (this.E) { ... return; }                  // never cancel while committing
...
if (this.f43961q != null) { this.f43961q.stop(); }
if (queue != null) { queue.clear(); }
this.f43955l = z11;
... "state = mTimerSendCancelCmd delay 2000ms"
Timer timer2 = new Timer();  this.f43968x = timer2;  timer2.schedule(new e(), 2000L);
```

i.e. the current stage is stopped, the queue cleared, and **2000 ms later** the
cancel command is enqueued (`d8/d.java:494-507` → `s0((byte) 0)` →
`d8/d.java:1218-1224`). `d8.b.i()` additionally stops the ping timer first
(`d8/b.java:684-688`).

### 5.2 `0x1C03` device-initiated cancel (device → host), type **0x5A**

```java
// d8/d.java:645-674
public boolean D(int i11, byte[] bArr, int i12) {
    if (i12 != 90 || i11 != 7171) { return false; }
    byte b11 = bArr[6];   // sender
    byte b12 = bArr[7];   // recipient
    byte b13 = bArr[8];   // reason
    ...
    this.f43941e.u(new z7.a((byte) 91, 7171, new byte[]{0}).f(this.Y));
    if (iAirohaFotaStage != null) { iAirohaFotaStage.stop(); }
    this.f43960p.clear();
    this.f43941e.u(new z7.a((byte) 93, 7171, new byte[]{b11, b12, b13}).f(this.Y));
    if (b13 == 0)      N(AirohaFotaErrorEnum.DEVICE_CANCELLED);
    else if (b13 == 1) N(AirohaFotaErrorEnum.Device_Cancelled_FOTA_FAIL);
    else if (b13 == 2) N(AirohaFotaErrorEnum.Device_Cancelled_FOTA_TIMEOUT);
    else if (b13 == 3) N(AirohaFotaErrorEnum.Device_Cancelled_PartnerLoss);
    else if (b13 != 4) N(AirohaFotaErrorEnum.FotaCanceled_ByDevice_UnKnownReason);
    else               N(AirohaFotaErrorEnum.Device_Cancelled_FOTA_NOT_ALLOWED);
    return true;
}
```

```
notify layout (type 0x5A, raceId 0x1C03)
rx[6]  sender
rx[7]  recipient
rx[8]  reason: 0 device-cancelled, 1 FOTA_FAIL, 2 FOTA_TIMEOUT,
               3 partner loss, 4 FOTA_NOT_ALLOWED (e.g. battery), else unknown
```

The library replies with **two raw frames sent directly via `host.u()`**
(bypassing the TX scheduler, `q8/b.java:243`):

```
<flag>|0x05  5B  03 00  03 1C  00                       # ack
<flag>|0x05  5D  05 00  03 1C  <sender> <recipient> <reason>   # echo
```

Reproduce this if you want the device to stop retransmitting the notify.

### 5.3 `0x0900` RHO-done notify (module 20)

```java
// d8/d.java:678-694
public boolean E(int i11, byte[] bArr, int i12) {
    if (i12 == 90 && i11 == 2304) {                    // type 0x5A, raceId 0x0900
        if (x8.d.f(bArr[7], bArr[6]) != 20) { ... return false; }   // module id, LE16 at rx[6..7]
        byte b11 = bArr[8];      // result
        byte b12 = bArr[9];      // agentChannel
        ...
        if (b11 == 0) { Q(); return true; }            // onRhoCompleted
        R();                                           // RHO_FAIL
    }
    return false;
}
```

```
notify layout (type 0x5A, raceId 0x0900)
rx[6..7]  module id, LITTLE-ENDIAN 16-bit; must be 20 (0x0014) else ignore
rx[8]     result: 0 = OK
rx[9]     agent channel
```

**Single device: do *not* silently ignore it.** In this library a successful RHO
notify while `mIsDoingRoleSwitch == false` (always the case for single-device)
takes the "unexpected RHO" branch and **aborts** the FOTA:

```java
// d8/d.java:203-233
if (d.this.E(iG, bArr, b11)) {
    ... "state = RHO Done"
    if (dVar3.D) { ... continue FOTA ... }
    dVar3.f43935b.e(dVar3.f43933a, "error = unexpected RHO; stop FOTA!");
    dVar4.N(AirohaFotaErrorEnum.UNEXPECTED_RHO);
    ...
    if (!dVar6.C) { dVar6.j(dVar6.f43955l); }        // -> cancel 0x1C03
}
```

A single headphone should never emit it. If it does, treat it as a fatal abort
and send cancel reason 2, matching the library.

### 5.4 `0x1C1B` Ping — **yes, it is used for a single device**

```java
// d8/b.java:239-258   startPingTimerTask()
A0();
this.H = true;
h8.c.s();                                    // reset the no-response counter
this.f43910r0 = new c();
Timer timer = new Timer();
this.f43909q0 = timer;
timer.scheduleAtFixedRate(this.f43910r0, 9000L, 9000L);
```

```java
// d8/b.java:210-236   sendPingReq()
if (!this.f43941e.n()) { A0(); return; }
int iT = h8.c.t();                            // ++counter
if (iT > 3) { ... A0(); N(AirohaFotaErrorEnum.PING_FAIL); return; }
if (iAirohaFotaStage != null) { iAirohaFotaStage.stop(); }
V();
if (this.f43955l) { this.f43960p.offer(new h8.c(this, (byte) 1)); }
else              { this.f43960p.offer(new h8.c(this, (byte) 0)); }   // <-- single: role 0
o0();
```

It is started from `RunnableC0411b` at `d8/b.java:118` — i.e. **immediately after
the final 0x1C04 returns 0x0211 and `onTransferCompleted` fires** — and stopped
by `startCommitProcess()` (`d8/b.java:658 A0()`) and by `cancel()`
(`d8/b.java:686`).

```java
// h8/c.java:16-42
public c(d8.d dVar, byte b11) {
    super(dVar);
    this.f21568j = 7195;
    this.f48309y = b11;
    this.f21569k = (byte) 93;                 // response type 0x5D
    this.f21559a = "FotaStageTwsPing";
}
public void i() {
    ...
    z7.a aVar = new z7.a((byte) 90, 7195);
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    byteArrayOutputStream.write(1);
    byteArrayOutputStream.write(this.f48309y);
    aVar.o(byteArrayOutputStream.toByteArray());
```

```
request payload
0 / 6 : 1 : count = 0x01
1 / 7 : 1 : role  = 0x00 (single)
```

```
15 5A 04 00 1B 1C 01 00
```

Response type **0x5D**, status rx[6] must be 0; on OK the counter is reset
(`h8/c.java:46-49`). No other response field is parsed.

**Cadence: fixed rate 9000 ms, first send 9000 ms after transfer completion;
more than 3 consecutive unanswered pings → `PING_FAIL`.** Each ping *stops the
current stage* and rebuilds the queue, so it must never overlap real traffic —
which is why it only runs in the idle window between transfer and commit.

---

## 6. F — Stage base class (`com/airoha/libfota2833/fota/stage/a.java`)

### 6.1 Response → stage matching

Two-level. The manager first asks the *current* stage:

```java
// com/airoha/libfota2833/fota/stage/a.java:216-219
public boolean c(int i11, int i12) {
    return i11 == this.f21568j && i12 == this.f21569k;    // raceId AND respType
}
```

```java
// d8/d.java:249-250
} else if (iAirohaFotaStage.c(iG, b11)) {
    if (!d.this.f43961q.handleResp(iG, bArr, b11)) { ... "may receive duplicate response; just skip it" }
```

then `handleResp` re-checks only the **race id**:

```java
// com/airoha/libfota2833/fota/stage/a.java:284-288
public boolean handleResp(int i11, byte[] bArr, int i12) {
    if (i11 != this.f21568j) { return false; }
    this.f21567i = bArr[6];
```

Several stages additionally assert the type inside `o()` (`g8/h.java:136`,
`g8/i.java:209`, `f8/d.java:28`, `h8/g.java:28`) — belt and braces.

There is **no stage for the "wrong" race id**: any frame whose race id does not
match the current stage (and is not 0x0900 or a 0x1C03/0x5A notify) is dropped.

### 6.2 Status byte semantics and the 0x80 busy bit

```java
// com/airoha/libfota2833/fota/stage/a.java:294-314
if (this.f21560b.C()) {                                    // adaptive mode enabled
    if ((bArr[6] & 128) == 128) {
        if (!this.f21560b.G()) {
            ... "state = device is busy; switch to background mode"
            this.f21560b.m(true);
        }
        byte b11 = (byte) (bArr[6] & BSON.MAXKEY);         // & 0x7F  -> strip the busy bit
        this.f21567i = b11;
        bArr[6] = b11;
    } else if (this.f21560b.G()) {
        ... "state = device is not busy; switch to active mode"
        this.f21560b.m(false);
        this.f21560b.z0();
        this.f21560b.r0();
    }
}
if (this.f21567i == 0) { this.f21566h = true; } else { this.f21566h = false; }
```

* `status = rx[6]`; **success is exactly `0x00`** (after the busy bit is stripped).
* `status & 0x80` = "device busy". Only interpreted when `C()` returns true,
  i.e. `mgr.X` — set only by the *adaptive* branch of `start` (`d8/b.java:607`).
  **On MT2833/MT2822 SPP this branch is never taken, so a status with the high
  bit set is simply a non-zero (failure) status there.** On MT2855 it toggles
  long-packet/background mode at runtime.
* `BSON.MAXKEY = 127` (`org/bson/BSON.java:25`).

### 6.3 Per-command timeout, retries

* Timeout: `protected int f21576r = 9000;`
  (`com/airoha/libfota2833/fota/stage/a.java:86`), exposed as `d()` (`:222-224`)
  and armed by `d8/d.java:1205-1212` → `q0(stage.d())` → a `Timer` with a
  `RetryTask` (`d8/d.java:1183-1199`). Overridden only by
  `f8/c.java:20` (cancel, **3000**), `h8/b.java:11` (TWS commit, 15000) and
  `h8/a.java:11` (RHO, 15000).
* Retry-when-queue-empty delay: `timer.schedule(new g(), this.f43970z)`
  (`d8/d.java:744-746`).
* **Per-packet retry limit 3**:
  ```java
  // z7/a.java:126-128
  public boolean k() { return this.f21535i >= 3; }
  ```
  checked in `isRetryUpToLimit()`:
  ```java
  // com/airoha/libfota2833/fota/stage/a.java:354-370
  this.f21562d.clear();
  for (z7.a aVar : this.f21563e.values()) {
      if (aVar.k()) { ... "state = retry reach upper limit" ; return true; }
      if (!aVar.j()) {
          if (aVar.b() > 0) { aVar.h(); ... }      // h() = retryCount++
          this.f21562d.offer(aVar);
      }
  }
  return false;
  ```
  On exhaustion: `AirohaFotaErrorEnum.CMD_RETRY_FAIL` + cancel reason 2
  (`d8/d.java:713-721`).
* Commit / RHO have their own counter `Q`, limit `> 3` (`d8/d.java:606, 620`).

### 6.4 Pre-poll window 4 — where it actually applies

```java
// com/airoha/libfota2833/fota/stage/a.java:29
private static int f21555u = 4;                       // getter m(), setter r()
```
set per run from `e8.b.f44843b = 4` (`e8/b.java:14`) via `a.r(...)`
(`d8/d.java:1252, 1270`).

```java
// com/airoha/libfota2833/fota/stage/a.java:505-525
public void prePoolCmdQueue() {
    if (this.f21562d.size() != 0) {
        this.f21560b.f43938c0.clear();
        this.f21565g = 0;
        dVar.f43940d0 = 0;
        if (dVar.G()) { this.f21560b.v().v(this); return; }                       // long-packet: 1 poll
        if (this.f21562d.size() < 2 || this.f21577s != TxSchedulePriority.Low) {
            this.f21560b.v().v(this); return;                                     // <-- always taken
        }
        for (int i11 = 0; i11 < m(); i11++) { this.f21560b.v().v(this); }         // dead code here
    }
}
```

**No MT28xx stage sets `f21577s` to `Low`** (grep for `f21577s` across
`g8/ f8/ h8/ l8/ m8/ k8/` returns nothing; the base sets `Middle` at
`stage/a.java:89`). So `prePoolCmdQueue` always issues exactly **one** poll.
The window of 4 is instead maintained by the *active-mode* sender re-priming
itself:

```java
// com/airoha/libfota2833/fota/stage/a.java:471-474
this.f21561c.d(this.f21559a, "variable = mWaitingRespCount: " + this.f21565g);
if (this.f21565g < m()) { new Thread(new RunnableC0189a()).start(); }
```
and `RunnableC0189a.run()` re-checks `f21565g < m()` under the manager lock
(5000 ms `tryLock`) before calling `pollCmdQueue()`
(`stage/a.java:110-131`). **Net effect: up to 4 commands in flight in Active
mode.**

In long-packet mode the window is the packet size instead:

```java
// com/airoha/libfota2833/fota/stage/a.java:492-502
public void pollCmdQueue() {
    if (this.f21562d.size() != 0) {
        this.f21560b.v().x("AirohaFOTA");
        if (!this.f21560b.G() || (iX = this.f21560b.x() - this.f21565g) > 0) {
            this.f21560b.v().v(this); return;
        }
        ... "state = skip; cmd_count: " + iX
```
`x()` = `mgr.B` = commands-per-packet (`d8/d.java:1283-1285, 1013-1015`).

### 6.5 Long-packet assembly — **exact concatenation of raw frames**

```java
// com/airoha/libfota2833/fota/stage/a.java:248
byte[] bArrK = this.f21560b.G() ? k(this.f21560b.x() - this.f21565g) : l();
```

```java
// com/airoha/libfota2833/fota/stage/a.java:378-444   (abridged)
byte[] k(int i11) {
    ArrayList arrayList = new ArrayList();
    dVar.f43940d0 = dVar.f43940d0 + 1;                       // packet index
    if (dVar.f43938c0.size() != 0) {                         // pending (unacked) commands
        for (z7.a aVar : this.f21560b.f43938c0.values()) {
            if (!aVar.j()) {
                if (aVar.b() + 3 < this.f21560b.f43940d0) {  // <-- windowed retransmit, +3
                    ... "state = re-send cmd with addr: " ...
                    aVar.p(this.f21560b.f43940d0);
                    length = aVar.f(this.f21560b.F()).length;
                    arrayList.add(aVar);
                    if (this.f21565g > 0) { this.f21565g = this.f21565g - 1; }
                } else { concurrentHashMap.put(aVar.a(), aVar); }
            }
        }
        this.f21560b.f43938c0 = concurrentHashMap;
    }
    for (int i13 = 0; i13 < i11; i13++) {                    // then up to i11 fresh commands
        z7.a aVarPoll = this.f21562d.poll();
        if (aVarPoll != null) {
            aVarPoll.p(this.f21560b.f43940d0);
            arrayList.add(aVarPoll);
            length = aVarPoll.f(this.f21560b.F()).length;
            if (!this.f21560b.f43938c0.containsKey(aVarPoll.a())) {
                this.f21560b.f43938c0.put(aVarPoll.a(), aVarPoll);
            }
        }
    }
    if (arrayList.size() <= 0) { return null; }
    ... "state = cmd Count in one packet: " + arrayList.size()
    this.f21565g = this.f21565g + arrayList.size();
    byte[] bArr = new byte[arrayList.size() * length];
    for (int i14 = 0; i14 < arrayList.size(); i14++) {
        z7.a aVar2 = (z7.a) arrayList.get(i14);
        System.arraycopy(aVar2.f(this.f21560b.F()), 0, bArr, i14 * length, length);
        ...
    }
    if (zI) { this.f21560b.n0(); }                           // start the pacing timer
    return bArr;
}
```

**Answer to "exact concatenation = raw frames back-to-back?" — yes.**
`aVar2.f(flag)` returns the *complete* RACE frame (byte0 = `flag|0x05`, type,
LE16 len, LE16 raceId, payload), and they are copied back-to-back with no
separator, no outer header and no total-length field.

Caveat to reproduce or fix: `bArr` is sized `count * length` where `length` is
the length of the **last** frame appended. This is only correct because all
commands in one 0x0402/0x0404/0x0433 packet have the same length. (`k8/a.java`
is MT2855's rewrite that tracks per-command lengths, because adaptive write
commands can differ.)

**Commands per packet:** `j0(3)` for MT2833 (`d8/b.java:621`) and MT2822
(`u7/b.java:596`), `j0(4)` for MT2855 (`i8/a.java:640`); `j0(0)` in Active mode.

**A 3-command 0x0402 long packet is therefore 3 × 269 = 807 bytes**, which
exceeds nothing on SPP (link "MTU" 1100, `r8/c.java:15`) and is written as one
`OutputStream.write` (`u8/a.java:565-578`) after the transport splits at
`maxPayload` (`com/airoha/liblinker/transport/a.java:45-62`).

### 6.6 Pacing 200 ms

```java
// com/airoha/libfota2833/fota/stage/a.java:32
private static int f21556v = 0;       // getter j(), setter p()
```
Set to `e8.b.f44848g` per run (`d8/d.java:1251, 1269`), which `start` sets to
**200** in background/adaptive mode and **0** in active mode
(`d8/b.java:618-619, 627, 630`), then `k0(200)`/`k0(0)`
(`d8/b.java:622, 633` → `d8/d.java:1021-1023`). Overridable by the device via
0x1C1C (§3.3).

The pacer:

```java
// d8/d.java:528-554   (LongPacketTimer thread, started by n0())
SystemClock.sleep((long) com.airoha.libfota2833.fota.stage.a.j());
...
int iF = d.this.B - d.this.f43961q.f();                 // cmdsPerPacket - waitingRespCount
if (d.this.f43961q.isCmdQueueEmpty() || iF <= 0) { d.this.z0(); d.this.r0(); }   // arm resp timeout
else { d.this.f43961q.pollCmdQueue(); }                                          // send next packet
```

### 6.7 How a stage decides completion

Default: every packet in `f21563e` has been marked
(`z7.a.n()` sets `f85234h`, `j()` reads it):

```java
// com/airoha/libfota2833/fota/stage/a.java:338-346
public boolean isCompleted() {
    Iterator<z7.a> it = this.f21563e.values().iterator();
    while (it.hasNext()) { if (!it.next().j()) { return false; } }
    return true;
}
```

Overrides: `g8/f.java:54-64` and `g8/g.java:69-79` (same logic + a log line),
`g8/h.java:112-132` and `g8/i.java:182-205` (same + compute the SKIP_TYPE),
`g8/d.java:38-41` (any response counts).

The manager acts on it at `d8/d.java:286-298`: on completion it logs
`"state = Completed: <class>"`, reads `stage.b()` for the SKIP_TYPE, rebuilds the
queue if needed and starts the next stage. Progress is reported *before* the
completion check via `K(role, stage, completedTaskCount, totalTaskCount)`
(`d8/d.java:282-285`; implementation `d8/b.java:345+`).

---

## 7. G — Helpers

### 7.1 CRC-8 (`x8/a.java`) — full algorithm

```java
// x8/a.java:13   (256-entry table, f82725b)
{0, 49, 98, 83, 196, 245, 166, 151, 185, 136, 219, 234, 125, 76, 31, 46,
 67, 114, 33, 16, 135, 182, 229, 212, 250, 203, 152, 169, 62, 15, 92, 109,
 134, 183, 228, 213, 66, 115, 32, 17, 63, 14, 93, 108, 251, 202, 153, 168,
 197, 244, 167, 150, 1, 48, 99, 82, 124, 77, 30, 47, 184, 137, 218, 235,
 61, 12, 95, 110, 249, 200, 155, 170, 132, 181, 230, 215, 64, 113, 34, 19,
 126, 79, 28, 45, 186, 139, 216, 233, 199, 246, 165, 148, 3, 50, 97, 80,
 187, 138, 217, 232, 127, 78, 29, 44, 2, 51, 96, 81, 198, 247, 164, 149,
 248, 201, 154, 171, 60, 13, 94, 111, 65, 112, 35, 18, 133, 180, 231, 214,
 122, 75, 24, 41, 190, 143, 220, 237, 195, 242, 161, 144, 7, 54, 101, 84,
 57, 8, 91, 106, 253, 204, 159, 174, 128, 177, 226, 211, 68, 117, 38, 23,
 252, 205, 158, 175, 56, 9, 90, 107, 69, 116, 39, 22, 129, 176, 227, 210,
 191, 142, 221, 236, 123, 74, 25, 40, 6, 55, 100, 85, 194, 243, 160, 145,
 71, 118, 37, 20, 131, 178, 225, 208, 254, 207, 156, 173, 58, 11, 88, 105,
 4, 53, 102, 87, 192, 241, 162, 147, 189, 140, 223, 238, 121, 72, 27, 42,
 193, 240, 163, 146, 5, 52, 103, 86, 120, 73, 26, 43, 188, 141, 222, 239,
 130, 179, 224, 209, 70, 119, 36, 21, 59, 10, 89, 104, 255, 206, 157, 172}
```

```java
// x8/a.java:16   (nibble-reverse table, f82726c)
{0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15}
```

```java
// x8/a.java:21-25, 48-63
public a(byte b11) { short s11 = b11; this.f82724a = s11; this.f82727d = s11; }   // init

public void update(byte[] bArr, int i11, int i12) {
    while (true) {
        int i13 = i12 - 1;
        if (i12 <= 0) {
            short[] sArr = this.f82726c;
            short s11 = this.f82727d;
            this.f82727d = (byte) (sArr[s11 >> 4] | (sArr[s11 & 15] << 4));   // FINALISE
            return;
        }
        short s12 = (short) (this.f82727d & 255);
        this.f82727d = s12;
        this.f82727d = this.f82725b[s12 ^ ((short) (bArr[i11] & 255))];       // crc = T[crc ^ b]
        i11++;
        i12 = i13;
    }
}
public long getValue() { return this.f82727d & 255; }                          // x8/a.java:28-30
```

**Derived parameters (verified programmatically against the table):**

| parameter | value |
|---|---|
| width | 8 |
| polynomial | **0x31** (`x²⁵ …` — i.e. `x⁸+x⁵+x⁴+x⁰` reflected form 0x8C) |
| table generation | MSB-first: `for 8 bits: crc = (crc&0x80) ? ((crc<<1)^0x31) : (crc<<1)` starting from the index |
| refin | **false** |
| init | **the constructor argument**; FOTA always uses `new x8.a((byte) 0)` (`g8/g.java:42`) → **0x00** |
| xorout | 0x00 |
| refout | **true** — a full 8-bit reversal is applied at the end of every `update()` call |

The finalisation `nibRev[c>>4] | (nibRev[c&15]<<4)` is exactly `reverse_bits8(c)`
(verified for all 256 values).

Reference Kotlin:

```kotlin
private val T = shortArrayOf(/* the 256 values above */)
private fun rev8(v: Int): Int {
    val n = intArrayOf(0,8,4,12,2,10,6,14,1,9,5,13,3,11,7,15)
    return (n[v shr 4] or (n[v and 15] shl 4)) and 0xFF
}
fun crc8(data: ByteArray, init: Int = 0): Int {
    var c = init and 0xFF
    for (b in data) c = T[c xor (b.toInt() and 0xFF)].toInt() and 0xFF
    return rev8(c)
}
```

**Test vectors (computed from the exact algorithm above):**

| input | crc8 |
|---|---|
| `ByteArray(0)` (empty) | `0x00` |
| 256 × `0x00` | `0x00` |
| 256 × `0xFF` | `0xB4` |
| bytes `0x00..0xFF` in order | `0xA9` |
| `byteArrayOf(0x01)` | `0x8C` |
| ASCII `"123456789"` | `0x45` |

**Gotcha:** the finalisation mutates the internal state, so this class is *not*
incremental — calling `update()` twice yields a wrong value. The FOTA code always
does exactly one `update(256 bytes)` then `getValue()` (`g8/g.java:42-44`).
Note also that `bArr[0] = value` is stored, then the *same* `value` is passed to
`b8.f` (`g8/g.java:45, 49`) — so byte 0 of the record is the CRC of the 256 data
bytes only.

### 7.2 `x8/b.java` — all-0xFF test

```java
// x8/b.java:6-13
public static boolean a(byte[] bArr) {
    for (byte b11 : bArr) { if (b11 != -1) { return false; } }
    return true;
}
```
`true` ⇒ the 256-byte page is skipped entirely (`g8/g.java:41`).

### 7.3 `x8/d.java` — endianness helpers, exactly as used

```java
// x8/d.java:55-65
public static int   e(byte[] bArr) { return ((bArr[3]&255)<<24) | (bArr[0]&255) | ((bArr[1]&255)<<8) | ((bArr[2]&255)<<16); }
public static short f(byte b11, byte b12) { return (short) (((b11 & 255) << 8) | (b12 & 255)); }
public static int   g(byte b11, byte b12) { return ((b11 & 255) << 8) + (b12 & 255); }
// x8/d.java:97-110
public static byte[] k(int i11) { ByteBuffer b = ByteBuffer.allocate(4); b.order(ByteOrder.LITTLE_ENDIAN); b.putInt(i11); return b.array(); }
public static byte[] l(short s11) { return new byte[]{(byte)(s11 & 255), (byte)((s11 >> 8) & 255)}; }
public static byte[] m(short s11) { return new byte[]{(byte)((s11 >> 8) & 255), (byte)(s11 & 255)}; }
```

| helper | signature | meaning | how it's used |
|---|---|---|---|
| `e(byte[4])` | bytes → int | **decode LE32** | partition addr/len, sector addr |
| `k(int)` | int → bytes | **encode LE32** | addresses, lengths, `k(4096)` = `00 10 00 00` |
| `l(short)` | short → bytes | **encode LE16** | nvkey id, max read length |
| `m(short)` | short → bytes | encode BE16 | only inside `n()` for logging |
| `f(hi, lo)` | two bytes → short | `(hi<<8)|lo` — **BE combine of the two args**, but every call site passes `(rx[n+1], rx[n])`, so the *field* is **LE16** | chip-name length `f(rx[7],rx[6])`, 0x1C1C interval `f(rx[10],rx[9])`, 0x0900 module `f(rx[7],rx[6])` |
| `g(hi, lo)` | two bytes → int | same combine as `f` but returns `int` | race id `g(rx[5],rx[4])`, bitmap length `g(b[1],b[0])` |

**Every `f`/`g` call site in this flow reads a LITTLE-ENDIAN field.** The `hi, lo`
argument order is the only thing that is "big-endian".

Formatting helpers used as map keys / logs:
`a(bytes)` = reverse-order spaced hex (`x8/d.java:15-24`);
`c(bytes)` = forward-order spaced hex, uppercase (`:34-43`) — **this is the ack-key
format**, e.g. LE32 address 0x00200000 → `"00 00 20 00"`;
`d(bytes)` = forward hex, no separator (`:45-53`);
`h(str)` = hex-pairs → chars (`:67-76`);
`j(bytes)` = ASCII filter, keep `>= 0x20` (`:87-95`);
`n(short)` = `d(m(s))` = big-endian hex of a short, for `race_id = 0x...` logs (`:112-114`).

### 7.4 `x8/e.java` — SHA-256

```java
// x8/e.java:9-18
public static byte[] a(byte[] bArr) {
    try {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        messageDigest.reset();
        return messageDigest.digest(bArr);
    } catch (NoSuchAlgorithmException e11) { e11.printStackTrace(); return null; }
}
```
Plain single-shot SHA-256, 32-byte output, no salt/prefix.
Used for the per-sector digest (`stage/a.java:170`) and the per-group digest
(`g8/i.java:78`). Concatenation helper: `x8/c.java:6-11`.

---

## 8. H — What changes on the wire between MT2822 / MT2833 / MT2855

Only the following differ for the **single-device SPP** flow. Everything else —
race ids, payload field order, response offsets, 4096/256/261 chunking, CRC-8,
SHA-256, the bitmap bit order, the SKIP graph, the 9000 ms timeout, the 3-retry
packet limit, the 4-deep active window, the `0x15` flag rule — is byte-identical.
Verified by reading the MT2822 (`libfota1568`) equivalents:
`r7/a.java:19-32` ≡ `a8/a.java`, `r7/b.java:19-32` ≡ `a8/b.java`,
`s7/d.java:18-31` ≡ `b8/d.java`, `s7/f.java:22-30` ≡ `b8/f.java`,
`x7/f.java:25-47` ≡ `g8/f.java`, `x7/h.java:134-185` ≡ `g8/h.java` (same
rx[8]/rx[9..12]/rx[13..16]/rx[17..18]/rx[19] offsets),
`com/airoha/libfota1568/fota/stage/a.java:29,32,35,86,89` ≡
`com/airoha/libfota2833/fota/stage/a.java:29,32,35,86,89`.

| | MT2822 (`libfota1568`) | MT2833 (`d8.b`) | MT2855 (`i8.a`) |
|---|---|---|---|
| **0x1C08 mode byte** (Sony SPP) | **0x00** Background (0x01 Active) — `FotaControl2822.java:344,349` forces the adaptive flag `false`; `u7/b.java:580-609` | **0x00** Background (0x01 Active) — `FotaControl2833.java:358,364` force `false`; `d8/b.java:605-634` | **0x02** Adaptive (0x01 Active) — `FotaControl2855.java:357,363` force `true`; `i8/a.java:599-655` |
| **pages per 0x0402** | 1 (`x7/g.java` mirrors `g8/g.java:33-53`) | **1** (`g8/g.java:33-53`) | **2** (`k8/a.java:17` `public static int f59278y = 2;`) → payload 2+522 = 524, len field `0x020E`, frame 530 B |
| **commands per long packet** | 3 (`u7/b.java:596` `o0(3)`) | **3** (`d8/b.java:621` `j0(3)`) | **4** (`i8/a.java:640` `j0(4)`) |
| **busy-bit (`status & 0x80`) handling** | inert — `mgr.Y`/`C()` false because the adaptive flag is forced false | **inert** for the same reason | **live** — flips background/active mode at runtime (`stage/a.java:294-309`) |
| pacing / interval | 200 ms bg, 0 active (`u7/b.java:593-597, 605-608`) | 200 ms / 0 (`d8/b.java:618-622, 627-633`) | same |
| erase/compare region | 512 KB (`u7/b.java:32` 524288, `:571` `i12*1024`; honours the caller) | 512 KB (`d8/b.java:34, 596`) | 512 KB |
| write-adaptive stage classes | `x7/g`, `x7/h` | `g8/g`, `g8/h` | `l8/a`, `l8/b` (substituted at `i8/c.java:59-65`) |
| RACE packet class | `q7.a`; flag via `g(boolean)` (`q7/a.java:110-112`), RX filter `u7/d.java:242` | `z7.a`; `f(boolean)` (`z7/a.java:106-108`), RX filter `d8/d.java:236` | `z7.a` (shared) |
| 0x1C1C stage | `com/airoha/libfota1568/fota/stage/b.java:18-19, 43` — payload `{1, role}`, resp 0x5D, `f(rx[10], rx[9])` | `f8/d.java` | `f8/d.java` |
| single query phase | `0x0CD6` → `0x1C07` → `0x1C04` (`u7/b.java:495-504`: `y7.h(0)`, `y7.g(0)`, `x7.b`) | identical (`d8/b.java:516-525`) | identical |
| single transfer order | identical 14 stages (`u7/d.java:1393-1435`, race ids `x7/a.java:12` 0x1C00, `w7/a.java:12` 0x1C08, stage `b` 0x1C1C, `x7/h.java:37` 0x0433, `x7/i` 0x0431, `x7/c.java:8` 0x1C0A, `x7/j` 0x1C06, `x7/f.java:19` 0x0404, `x7/g.java:17` 0x0402, `x7/d.java:19` 0x1C01, `x7/b.java:8` 0x1C04) | `d8/d.java:1131-1173` | `i8/c.java:103-113` (adaptive stage swap only) |
| commit | 0x1C02, `x7/e.java:10,16`, payload `{0x00}`, resp type 0x5A | `g8/e.java` | `g8/e.java` |

**Nothing else changes.** In particular there is *no* difference in payload field
order, no extra field, and no different response offset between the three chips
for any single-device message.

---

## 9. Corrections to `spec-airoha-fota.md`

Offsets / facts I had to correct against the code:

1. **0x1C01 CheckIntegrity response type is `0x5D`, not `0x5B`**
   (`g8/d.java:21` `this.f21569k = (byte) 93;`). Same for 0x1C08 (`f8/a.java:14`),
   0x1C1C (`f8/d.java:14`), 0x0433 (`g8/h.java:37`), 0x0431 (`g8/i.java:32`),
   0x0404 (`g8/f.java:21`), 0x0CD6 (`h8/h.java:17`), 0x1C07 (`h8/g.java:13`),
   0x1C1B (`h8/c.java:20`). The overview's blanket "resp 0x5B" is wrong for these.
   **0x0402 WriteFlash *is* 0x5B** (`g8/g.java` never sets `f21569k`), and
   **0x1C02 Commit is 0x5A** (`g8/e.java:11`).
2. **0x0433 bitmap length is LE16 at rx[17..18], bitmap at rx[19]** — the overview
   §3.7 said `{storageType, role, addr, len}` without offsets and §3.3 (AB1562)
   used rx[18..19]/rx[20]. For MT28xx it is 17/19 (`g8/h.java:162, 167`).
3. **0x0431 SHA-256 is at rx[17..48]** (`g8/i.java:235`), and the length field at
   rx[13..16] is parsed into a throwaway array (`g8/i.java:233`) — the overview
   implied it was used.
4. **Chip-name response has no status byte.** `rx[6..7]` is the returned data
   length as a **little-endian** 16-bit value; the overview described it as
   "`[6]=status?`, `[6..7]` length read big-endian". Success = length ≠ 0
   (`CommonStageReadChipName.java:34-45`).
5. **0x1C1C interval is LE16 at rx[9..10]** — the overview wrote
   "`BE16(rx[10], rx[9])`", which describes the argument order of `x8.d.f`, not
   the field's endianness (`f8/d.java:39`, `x8/d.java:59-61`).
6. **Single-device commit timeout is 9000 ms, not 15000.** `g8/e.java` does not
   override `f21576r`; 15000 belongs to `h8/b.java:11` (TWS commit) and
   `h8/a.java:11` (RHO). "Commit success" is signalled by the socket dropping
   while `mIsDoingCommit` → `d8/d.java:1312-1313` → `d8/b.java:323-329`
   `onCompleted()`.
7. **The `0x15` flag is already set on the 0x1C08 frame itself** — `i0(true)` runs
   in `f8/a.i()` before the packet is queued and the bytes are built lazily
   (`f8/a.java:30-33`, `stage/a.java:467`). The overview said "every *subsequent*
   frame".
8. **Erase commands go low-address-first on MT28xx** (`g8/f.java:30` over a
   `LinkedHashMap` filled ascending at `g8/h.java:76`). The high-address-first
   ordering is AB1562-only (`o7/n.java:34,65`).
9. **MT2822's single query phase is 0x0CD6 → 0x1C07 → 0x1C04**, not
   "0x0433 → 0x1C07 → 0x1C04" as §3.11 of the overview states
   (`u7/b.java:495-504` uses `y7.h` = `t7/i` = 0x0CD6).
10. **The pre-poll window of 4 is not delivered by `prePoolCmdQueue`** — that
    path requires `TxSchedulePriority.Low`, which no MT28xx stage sets. The
    4-deep window comes from `l()`'s self-priming thread
    (`stage/a.java:471-474`).
11. **The battery reply's only effect is `d0(true)` ("flash ops allowed")**
    (`h8/h.java:42`); the threshold really is write-only, as the overview
    suspected, and its origin is the device-reported Tandem "interrupt battery
    level" (`nu/o.java:876` → `az/a.java:162` → `q20.c.b()`).
12. **Device-initiated 0x1C03 is answered with two raw frames**
    (`05|15 5B 03 00 03 1C 00` and `05|15 5D 05 00 03 1C <s><r><reason>`) sent via
    `host.u()` (`d8/d.java:654, 660`) — not mentioned in the overview.
13. **`f21557w` defaults to 262144 (256 KB)**, not 512 KB
    (`stage/a.java:35`); the 512 KB figure comes from the Sony adapter's
    `mPartialReadFlashLengthKB = 512` (`AirohaFotaAdapterSony.java:81`) and
    `d8/b.java:34, 596`. A client that omits the setting would use 256 KB.
14. **The `0x0900` RHO notify must not be ignored on single-device** — it takes
    the "unexpected RHO → stop FOTA" branch (`d8/d.java:216-232`).
15. **The device-type read (`F2 B0`) is dead code on the Sony path** —
    `AirohaCommonMgr.getDeviceType()` (`AirohaCommonMgr.java:356`) has no caller
    under `com/airoha/project`.

---

## 10. Open questions

1. **Which `CHIP_TYPE` a WH-1000XM5 actually reports.** The substring table
   (`AirohaFotaAdapterSony.java:217-227`) routes `"283"`, `"158"`, `"157"` to
   MT2833 and `"285"` to MT2855. Without a capture of the 0x0A00/0x1002 reply I
   cannot say which of the two a WH-1000XM5 lands on — and the difference is
   real (mode byte 0x00 vs 0x02, 1 vs 2 pages per 0x0402, live vs inert busy
   bit). **Read the chip name first and branch on it.**
2. **Whether `isBackground` is true for a WH-1000XM5.** It is the Tandem
   capability's "Background Transfer" bit
   (`DeviceCapabilityTableset2Builder.java:1387`, `:1367`), read over the MDR
   link, not over the Airoha link. If false the mode byte is `0x01` (Active),
   long-packet mode is off, pacing is 0, and `j0(0)` means `pollCmdQueue`
   always uses the single-command path.
3. **Whether the device requires byte0 = 0x15 after FOTA start.** The library
   sets it and filters RX on it, but the reconnect-for-commit path demonstrably
   sends 0x1C02 with `0x05` (§0.1/§4.1) and works, so the requirement is at best
   partial.
4. **The real 0x1C1C interval value on Sony hardware**, and whether the reply's
   `rx[7]` is a count or something else (parsed into an unused local at
   `f8/d.java` via `bArr[8]` being read as role).
5. **`rx[7]` on 0x0433 / 0x0431 / 0x0404 / 0x1C02 / 0x1C1C.** Read into a dead
   local everywhere (`g8/h.java:143`, `g8/i.java:218`, `g8/f.java:70,84`); by
   analogy with 0x1C01 (`recipientCount`) it is probably a count, but nothing in
   the code confirms it.
6. **The 4 bytes at 0x0404 response rx[8..11]** — `Arrays.copyOfRange(bArr, 8, 12)`
   is computed and discarded (`g8/f.java:71, 85`). Almost certainly the echoed
   erase length, matching the request layout, but unconfirmed.
7. **Whether the device tolerates a client that skips the socket
   close/reopen** between chip-name and FOTA (§1.5). Nothing in the protocol
   suggests it matters, but it is untested.
8. **What drives `MtkUpdateState.INSTALL_COMPLETED`** on the Sony side after
   commit. `nu/o.java:351` references it and `pl/d.java:117` ignores
   `onCompleted`, so the verdict comes from `nu.d` / the Tandem reconnect +
   version comparison, which I did not trace to the end.
9. **Whether a single 0x0402 reply can ack pages from *different* long packets.**
   The code handles `count > 1` generically (`g8/g.java:115-127`) and the
   pending map is global to the stage, so it appears yes — but with 1 page per
   command and 3 commands per packet, the observed `count` on MT2833 is unknown.
