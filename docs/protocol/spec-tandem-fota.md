# Sony "Tandem FOTA" firmware-update protocol (MDR/Tandem transport)

Reverse-engineered from jadx decompilation of **Sony Sound Connect 13.2.2** (`com.sony.songpal.mdr`).

All paths below are relative to:
`/private/tmp/claude-501/-Users-tonythayer-osborne-projects-sony-wf-control-app/8a8f0681-7933-4de4-9375-6162a26bbda2/scratchpad/sony/jadx/sources/`

Line numbers refer to the decompiled `.java` files **as printed by jadx, with `/* JADX INFO */` comment
lines removed** (the way they were read); they are accurate to ±5 lines in the raw file.

---

## 0. Scope and naming

"Tandem FOTA" is the path where the firmware image is carried **inside the MDR/Tandem serial
protocol itself** over the SPP/RFCOMM channel
(`UUID 956C7B26-D49A-4BA8-B03F-B17D393CB6E2`, `ie0/i.java:43`). No separate Airoha/MTK or
GAIA/CSR channel and no re-pairing is involved.

Protocol messages live in obfuscated package **`eg0/`**
(`com.sony.songpal.tandemfamily.message.mdr.v2.table1.updt.*` message classes; only the
`param/` enums kept their names).

State machine / orchestration lives in
`com/sony/songpal/mdr/j2objc/feature/fwupdate/tandem/core/` (+ helpers in `kx/`, `q20/`, `s20/`).

---

## 1. Frame layer

### 1.1 Frame format (unchanged from the normal MDR framing)

`ne0/b.java:57-84` (`e(byte dataType, byte[] payload, byte seq)`):

```
0x3E | escape( dataType | seq | len[4] BE | payload | checksum ) | 0x3C
```

* `dataType` — `DataType.byteCode()`
* `seq` — 1-bit sequence, alternating `0x00` / `0x01` (`ie0/c.java:18,99-101`)
* `len` — 4-byte big-endian payload length (`com.sony.songpal.util.e.i()`, `com/sony/songpal/util/e.java:60-62`)
* `checksum` — low byte of a sum over `dataType|seq|len|payload` (`zg0/f`), `ne0/b.java:64-66`
* byte-stuffing (`zg0/b.java:8-30`): `0x3E → 0x3D 0x2E`, `0x3C → 0x3D 0x2C`,
  `0x3D → 0x3D 0x2D`; un-escape is `b |= 0x10` (`zg0/b.java:42`)
* The finished frame is split into `ie0.f.j0()`-sized writes — **2048 bytes for the SPP session**
  (`ah0/a.java:159-161`), via `zg0.a.a()` (`ne0/b.java:74-83`). This is transport chunking only, not
  a protocol fragmentation header: there is **no** per-fragment header, sequence, or offset at this
  level. The Tandem frame is one logical unit.

### 1.2 DataType values (`com/sony/songpal/tandemfamily/message/DataType.java:5-25`)

| name | byte | ackRequired |
|---|---|---|
| `DATA` | 0x00 | true |
| `ACK` | 0x01 | false |
| `DATA_MDR` | 0x0C | true |
| `DATA_COMMON` | 0x0D | true |
| `DATA_MDR_NO2` | 0x0E | true |
| `SHOT_MDR` | 0x1C | false |
| **`LARGE_DATA_SSH`** | 0x27 | true |
| **`LARGE_DATA_MDR`** | **0x2C** | **true** |
| `LARGE_DATA_COMMON` | 0x2D | true |

**Firmware chunks are sent with DataType `LARGE_DATA_MDR` = 0x2C**, not 0x2D
(`eg0/c0.java:61-64`: `public DataType b() { return DataType.LARGE_DATA_MDR; }`).
Every other UPDT message uses the table1 default `DATA_MDR` = 0x0C
(`com/sony/songpal/tandemfamily/message/mdr/v2/table1/c.java:996-999`).

### 1.3 ACK / flow control / retries

`le0/e.java:345-353`:

```java
public void k(oe0.b bVar) {
    DataType dataTypeB = bVar.b();
    this.f61934a.n(bVar.b(), bVar.d(),
        dataTypeB == DataType.LARGE_DATA_MDR ? 5000L : 750L,   // ack timeout ms
        dataTypeB == DataType.LARGE_DATA_MDR ? 2 : 10);        // resend count
}
```

* Send is **strictly synchronous / stop-and-wait**: `ie0/c.java:129-155` sends the frame, then blocks
  until the peer's `ACK` frame with the matching sequence number arrives. On timeout it resends the
  identical frame (same seq); after the retry budget is exhausted it declares the link dead
  (`T0()` → disconnect) and throws `IOException`.
* Therefore: **window size 1**, one firmware chunk in flight, ACK per chunk.
  For `LARGE_DATA_MDR`: 5000 ms ack timeout, 2 resends. For everything else: 750 ms, 10 resends.
* ACK frames are 9 bytes, precomputed (`ne0/b.java:22-26`):
  * seq 0: `3E 01 00 00 00 00 00 01 3C`
  * seq 1: `3E 01 01 00 00 00 00 02 3C`

---

## 2. Command opcodes (payload[0])

From `com/sony/songpal/tandemfamily/message/mdr/v2/table1/Command.java:35-46`:

| Command | opcode | direction | message class |
|---|---|---|---|
| `UPDT_GET_CAPABILITY` | **0x30** | app → dev | `eg0/a.java` |
| `UPDT_RET_CAPABILITY` | **0x31** | dev → app | `eg0/m.java` → `eg0/p.java` (Tandem) |
| `UPDT_GET_STATUS` | **0x32** | app → dev | `eg0/c.java` |
| `UPDT_RET_STATUS` | **0x33** | dev → app | `eg0/u.java` → `eg0/v.java` (Tandem) |
| `UPDT_SET_STATUS` | **0x34** | app → dev | `eg0/b0.java` (MTK only, not Tandem) |
| `UPDT_NTFY_STATUS` | **0x35** | dev → app | `eg0/k.java` → `eg0/l.java` (Tandem) |
| `UPDT_GET_PARAM` | **0x36** | app → dev | `eg0/b.java` |
| `UPDT_RET_PARAM` | **0x37** | dev → app | `eg0/q.java` → `eg0/r.java` |
| `UPDT_SET_PARAM` | **0x38** | app → dev | `eg0/w.java` → `y/a0/z` (Tandem) |
| `UPDT_NTFY_PARAM` | **0x39** | dev → app | `eg0/e.java` → `g/i/h` (Tandem) |
| `UPDT_TRANSFER_DATA` | **0x3E** | app → dev | `eg0/c0.java` (LARGE_DATA_MDR) |
| `UPDT_NTFY_MESSAGE` | **0x3F** | dev → app | `eg0/d.java` |

Note the coincidence: opcode `0x3E` equals the frame start byte; that is fine because the payload is
byte-stuffed (`0x3E → 0x3D 0x2E`).

### 2.1 `UpdtInquiredType` — payload[1] sub-addressing

`com/sony/songpal/tandemfamily/message/mdr/v2/table1/updt/param/UpdtInquiredType.java:7-17`
(`BSON.NUMBER_INT`=0x10, `BSON.TIMESTAMP`=0x11, `BSON.NUMBER_LONG`=0x12):

| value | byte |
|---|---|
| `FW_UPDATE_MTK_TRANSFER_WO_DISCONNECTION` | 0x02 |
| `FW_UPDATE_MTK_TRANSFER_WO_DISCONNECTION_AUTO_UPDATE` | 0x04 |
| `FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE` | 0x05 |
| `FW_UPDATE_MTK_TRANSFER_WITH_AC_CONNECTION_CHECK` | 0x06 |
| `FW_UPDATE_USING_MC_APP` | 0x07 |
| **`FW_UPDATE_TANDEM_PART1`** | **0x10** |
| **`FW_UPDATE_TANDEM_PART2`** | **0x11** |
| **`FW_UPDATE_TANDEM_PART3`** | **0x12** |
| **`FW_UPDATE_TANDEM_PART4`** | **0x13** |
| `OUT_OF_RANGE` | 0xFF |

The four `FW_UPDATE_TANDEM_PARTn` sub-addresses partition the Tandem FOTA feature:

* **PART1 (0x10)** — capability, status, param, and the `FW_UPDATE_COMPLETED` message.
* **PART2 (0x11)** — the "simple" commands: `ENTER_FW_UPDATE_MODE`, `EXIT_FW_UPDATE_MODE`,
  `FINISH_TRANSFER`, `CANCEL_TRANSFER` (and their results).
* **PART3 (0x12)** — `START_TRANSFER` (and result) and `UPDT_TRANSFER_DATA`.
* **PART4 (0x13)** — `EXECUTE_FW_UPDATE` (and result).

Which inquired types each opcode accepts is whitelisted per message class, e.g.
`eg0/w.java:15` (SET_PARAM accepts `0x04, 0x11, 0x12, 0x13`),
`eg0/e.java:11` (NTFY_PARAM likewise),
`eg0/k.java:13` / `eg0/u.java:13` (NTFY/RET_STATUS Tandem = `0x10` only).

### 2.2 `TandemFotaCommand` — payload[2] for PART2/3/4

`…/updt/param/TandemFotaCommand.java:42-48`:

| command | byte | allowed results |
|---|---|---|
| `ENTER_FW_UPDATE_MODE` | **0x01** | OK, OTHER, ILLEGAL_STATE, ILLEGAL_ARGS, NEED_POWER_CABLE…, TEMP_TOO_HIGH |
| `EXIT_FW_UPDATE_MODE` | **0x02** | OK, OTHER, ILLEGAL_STATE, ILLEGAL_ARGS |
| `START_TRANSFER` | **0x03** | OK, OTHER, ILLEGAL_STATE, ILLEGAL_ARGS, NO_NEED_OF_DATA_TRANSFER |
| `FINISH_TRANSFER` | **0x04** | OK, OTHER, ILLEGAL_STATE, ILLEGAL_ARGS |
| `CANCEL_TRANSFER` | **0x05** | OK, OTHER, ILLEGAL_STATE, ILLEGAL_ARGS |
| `EXECUTE_FW_UPDATE` | **0x06** | OK, OTHER, ILLEGAL_STATE, ILLEGAL_ARGS, FIRMWARE_TRANSFER_INCOMPLETED |
| `OUT_OF_RANGE` | 0xFF | – |

### 2.3 `TandemFotaResult`

`…/updt/param/TandemFotaResult.java:9-17`:

| result | byte |
|---|---|
| `OK` | 0x00 |
| `ERROR_OTHER_THAN_SPECIFIC_ERROR` | 0x01 |
| `ERROR_ILLEGAL_STATE` | 0x02 |
| `ERROR_ILLEGAL_ARGUMENTS` | 0x03 |
| `ERROR_NO_NEED_OF_DATA_TRANSFER` | 0x04 |
| `ERROR_FIRMWARE_TRANSFER_INCOMPLETED` | 0x05 |
| `ERROR_NEED_POWER_CABLE_CONNECTED_AND_ENOUGH_BATTERY` | 0x06 |
| `ERROR_TEMPERATURE_IS_TOO_HIGH` | 0x07 |
| `OUT_OF_RANGE` | 0xFF |

### 2.4 `TandemFotaStatus`

`…/updt/param/TandemFotaStatus.java:6-11`:

| status | byte | meaning |
|---|---|---|
| `INVALID` | 0x00 | not in FW-update mode |
| `IDLE` | 0x01 | in FW-update mode, no transfer running |
| `NOT_READY` | 0x02 | device refuses / aborted |
| `DATA_RECEIVING` | 0x03 | accepting `UPDT_TRANSFER_DATA` |
| `UPDATING` | 0x04 | installing |
| `OUT_OF_RANGE` | 0xFF | |

### 2.5 `MacType`, `Topology`, `MessageType`

`…/updt/param/MacType.java:6-9` — `NONE=0x00`, `MD5=0x01`, `SHA1=0x02`, `OUT_OF_RANGE=0xFF`.
`…/updt/param/Topology.java:6-8` — `SINGLE_SPEAKER=0x00`, `TWS=0x01`, `OUT_OF_RANGE=0xFF`.
`…/updt/param/MessageType.java:6-8` — `NO_USE=0x00`, `FW_UPDATE_COMPLETED=0x01`, `OUT_OF_RANGE=0xFF`.

---

## 3. Exact payload layouts

`EnableDisable` is the standard v2 enum (`DISABLE=0x00` / `ENABLE=0x01`).
`u8` = 1 byte, `u16be`/`u32be` = big-endian. `str{n}` = 1-byte length prefix followed by that many
ASCII bytes (`zg0/e.java:6-19`, cap `n`).

### 3.1 `UPDT_GET_CAPABILITY` (0x30) — app → device

`eg0/a.java:31-39`

```
[0] 0x30
[1] UpdtInquiredType = 0x10   (FW_UPDATE_TANDEM_PART1)
```
len 2.

### 3.2 `UPDT_RET_CAPABILITY` (0x31), PART1 — device → app

`eg0/p.java:12-53`, cross-checked against the KMP parser `ob0/i2.java:16-120`
(`aVar.k(7)` = exact length 7, `numOfFeature` must be 4):

```
[0] 0x31
[1] 0x10
[2] u8  numOfFeature            (must be 4)
[3] u8  resumable               EnableDisable   -> can resume an interrupted transfer at an offset
[4] u8  topology                Topology        0=single, 1=TWS
[5] u8  supportBackgroundTransfer  EnableDisable
[6] u8  requiredAcConnectionCheck  EnableDisable
```
len 7. Accessors: `p.f()`=resumable, `p.h()`=topology, `p.g()`=backgroundTransfer, `p.e()`=acCheck
(`eg0/p.java:39-53`); model class `ob0/h2.java:24-34`.

### 3.3 `UPDT_GET_STATUS` (0x32) — app → device

`eg0/c.java:22-25`; KMP builder `ob0/j0.java:23-29` writes exactly `{0x32, 0x10}`.

```
[0] 0x32
[1] 0x10
```
len 2.

### 3.4 `UPDT_RET_STATUS` (0x33), PART1 — device → app

`eg0/v.java:13-16`; KMP parser `ob0/m2.java:15-45` (`aVar.k(3)`, `aVar.j(16)`).

```
[0] 0x33
[1] 0x10
[2] u8 TandemFotaStatus
```
len 3.

### 3.5 `UPDT_NTFY_STATUS` (0x35), PART1 — device → app (unsolicited)

`eg0/l.java:13-16` — identical shape to RET_STATUS:

```
[0] 0x35
[1] 0x10
[2] u8 TandemFotaStatus
```
len 3. This is **the** primary progress/handshake signal: nearly every step waits for both a result
notification *and* a status notification.

### 3.6 `UPDT_GET_PARAM` (0x36) / `UPDT_RET_PARAM` (0x37)

`eg0/b.java:31-45` (request `{0x36, type}`).
Reply `eg0/q.java` + `eg0/r.java` — a chain of length-prefixed strings then two battery thresholds:

```
[0] 0x37
[1] UpdtInquiredType
[2] str{128} categoryId
    str{128} serviceId
    str{128} nationCode
    str{128} language
    str{128} serialNumber
    u8 batteryPowerThreshold            (0..100)   r.p()
    u8 batteryPowerThresholdInterrupt   (0..100)   r.q()
    str{128} uniqueId                              r.r()
```
Offsets are computed by walking the length prefixes (`eg0/q.java:74-88`,
`eg0/r.java:32-46`; model `ob0/j2.java`). Each prefix must be `>0 && <=128` for the first two and
`>=0 && <=128` afterwards (`eg0/q.java:19-36`).

**PART1 is in the accepted set** for RET_PARAM (`eg0/q.java:11`), so these two battery thresholds are
the "is the battery high enough" precondition source for Tandem FOTA as well.

### 3.7 `UPDT_SET_PARAM` (0x38), PART2 — the simple commands

`eg0/y.java:33-41` (via `eg0/w.java:82-86`):

```
[0] 0x38
[1] 0x11                       (FW_UPDATE_TANDEM_PART2)
[2] u8 TandemFotaCommand       one of 0x01 ENTER, 0x02 EXIT, 0x04 FINISH, 0x05 CANCEL
```
len 3. Allowed command set: `eg0/y.java:12`; KMP `ob0/y2.java:38` asserts
`command ∈ {1,2,4,5}`.

### 3.8 `UPDT_SET_PARAM` (0x38), PART3 — `START_TRANSFER`

Builder `eg0/a0.java:79-124` (`h(fwVersion, fileIndex, fileNames, macType, mac)`):

```java
ByteArrayOutputStream out = super.f(FW_UPDATE_TANDEM_PART3);   // writes 0x38, 0x12
out.write(START_TRANSFER.byteCode());                          // 0x03
fg0.a.c(out, fwVersion);                 // str{32}, must be non-empty and <=32 chars
out.write(e.k(fileIndex));               // u8, 0..31, must be < fileNames.size()
fg0.a.d(out, fileNames);                 // u8 count (1..31) then count x str{32}
out.write(macType.byteCode());           // 0x00/0x01/0x02
// NONE: out.write(0)                      -> mac length 0
// MD5 : out.write(32); out.write(mac)     -> mac length must be exactly 32
// SHA1: out.write(40); out.write(mac)     -> mac length must be exactly 40
```

So on the wire:

```
[0]  0x38
[1]  0x12
[2]  0x03                      (START_TRANSFER)
[3]  u8 fwVersionLen           (1..32)
[4..] fwVersion ASCII
     u8 fileIndex              (0..31)
     u8 numFiles               (1..31)
     numFiles x { u8 nameLen (1..32), name ASCII }
     u8 macType                (0x00 NONE / 0x01 MD5 / 0x02 SHA1)
     u8 macLen                 (0 / 32 / 40)
     macLen bytes mac
```

**The MAC is the ASCII-hex digest string, not raw digest bytes.** It comes from
`ix.c.f51979e` (a `String`) via `str.getBytes()` (`…/tandem/core/q.java:140`), hence 32 chars for MD5
and 40 for SHA1. Validator `eg0/a0.java:52-67`; string helpers `fg0/a.java:19-41`.

The KMP parser `ob0/c3.java:19-103` agrees on the layout but is looser: `fwVersion` length ≤ 128,
`numFiles` 1..255, `mac` length 0..40.

### 3.9 `UPDT_NTFY_PARAM` (0x39), PART3 — `START_TRANSFER` result

`eg0/i.java:8-45` (fixed length **12**):

```
[0]  0x39
[1]  0x12
[2]  0x03                      (START_TRANSFER)
[3]  u8  TandemFotaResult
[4..7]  u32be maxPacketSize    must be > 0     i.h()
[8..11] u32be offset           must be >= 0    i.i()
```
len 12. Model `ob0/y0.java:70` — `UpdtNtfyTandemStartTransferResult(command, result, maxPacketSize, offset)`.

* `maxPacketSize` = **the chunk size the app must use for `UPDT_TRANSFER_DATA` payload data**
  (device-dictated; there is no app-side proposal — see §4.4).
* `offset` = byte offset the device wants the transfer to resume from (0 for a fresh transfer;
  non-zero when `resumable` capability is set and a previous transfer was interrupted).

### 3.10 `UPDT_NTFY_PARAM` (0x39), PART2 — simple command result

`eg0/g.java:10-50` (fixed length **4**):

```
[0] 0x39
[1] 0x11
[2] u8 TandemFotaCommand       one of 0x01, 0x02, 0x04, 0x05
[3] u8 TandemFotaResult        must be in that command's allowed set
```
len 4. Model `ob0/u0.java:54`.

### 3.11 `UPDT_SET_PARAM` (0x38), PART4 — `EXECUTE_FW_UPDATE`

`eg0/z.java:36-46` (`h(fwVersion, fileNames)`):

```
[0] 0x38
[1] 0x13                       (FW_UPDATE_TANDEM_PART4)
[2] 0x06                       (EXECUTE_FW_UPDATE)
[3] u8 fwVersionLen            (<=32 java / <=128 kmp)
[4..] fwVersion ASCII
     u8 numFiles               (1..31)
     numFiles x { u8 nameLen (1..32), name ASCII }
```
Validator `eg0/z.java:17-26`; KMP parser `ob0/a3.java:18-71`, model `ob0/z2.java`.
Note: **no** `fileIndex` and **no** MAC here (that's the only structural difference from START_TRANSFER).

### 3.12 `UPDT_NTFY_PARAM` (0x39), PART4 — `EXECUTE_FW_UPDATE` result

`eg0/h.java:8-43` (fixed length **6**):

```
[0] 0x39
[1] 0x13
[2] 0x06                       (EXECUTE_FW_UPDATE)
[3] u8    TandemFotaResult
[4..5] u16be requiredTime      seconds, 0..65535   h.h()
```
len 6. Model `ob0/w0.java:62` — `UpdtNtfyTandemExecuteFwUpdateResult(command, result, requiredTime)`.

`requiredTime` is the device's estimate of the install duration in seconds; the app uses it both to
fake an install progress bar and to compute the install timeout (§4.7).

### 3.13 `UPDT_TRANSFER_DATA` (0x3E) — the firmware chunk

Builder `eg0/c0.java:37-54`:

```java
ByteArrayOutputStream out = super.d(Command.UPDT_TRANSFER_DATA);  // 0x3E
out.write(UpdtInquiredType.FW_UPDATE_TANDEM_PART3.byteCode());    // 0x12
zg0.a.d(out, offset);        // u32be
zg0.a.d(out, data.length);   // u32be
out.write(data, 0, data.length);
```

On the wire:

```
[0]     0x3E
[1]     0x12                    (FW_UPDATE_TANDEM_PART3)
[2..5]  u32be offset            byte offset of this chunk within the file, >= 0
[6..9]  u32be dataLength        > 0
[10..]  dataLength bytes of raw firmware
```

Total payload length must equal `dataLength + 10` (`eg0/c0.java:19-25`).
**Frame DataType = `LARGE_DATA_MDR` (0x2C)** (`eg0/c0.java:61-64`).
There is **no chunk index, no CRC per chunk, and no per-chunk reply** — flow control is the
frame-level ACK only (§1.3). KMP parser `ob0/g3.java:14-27`, model `ob0/f3.java:41`.

### 3.14 `UPDT_NTFY_MESSAGE` (0x3F) — install completed

`eg0/d.java:10-38`:

```
[0]     0x3F
[1]     0x10                    (FW_UPDATE_TANDEM_PART1)
[2]     u8 MessageType          0x01 = FW_UPDATE_COMPLETED
[3]     u8 dataLength           (>= 1; total payload length == dataLength + 4)
[4..]   dataLength bytes        (content unused by the app)
```
Minimum payload length 5 (`eg0/d.java:20`). Only `MessageType.FW_UPDATE_COMPLETED` is acted on
(`s20/g.java:84-92`). Model `ob0/…` not located under a `Updt*Message` name.

### 3.15 `UPDT_SET_STATUS` (0x34)

`eg0/b0.java:19-41` — `{0x34, UpdtInquiredType, EnableDisable}`, len 3. Its accepted-type list
(`eg0/b0.java:13`) contains only the four MTK types, so **it is not part of the Tandem FOTA path**.

---

## 4. The update sequence

Orchestration: `com/sony/songpal/mdr/j2objc/feature/fwupdate/tandem/core/m.java`
(state machine), `b.java` (enter/exit mode), `q.java` (per-file transfer), `i.java` (install),
`d.java` (single-executor mutex). Public facade: `jx/n2.java`.
Message send: `s20/h.java` (implements `q20/f.java`). Notification demux: `s20/g.java`
(extends `q20/e.java`).

`FwUpdateState` (`…/core/FwUpdateState.java:3-12`):
`INIT, FIRMWARE_DOWNLOADING, TRANSFERRING, TRANSFERRED, INSTALLING, INSTALL_COMPLETED,
INSTALL_TIMEOUT, CANCELLING, ERROR_OCCURRED`.

### 4.0 Preconditions queried

Items 2-3 are issued once during **initial connection capability discovery**, not at update start
(`wv/e.java:814-852`, `j0(List<FunctionType>)`; the results feed `DeviceCapabilityTableset2Builder`).

1. **Feature support** — from the CONNECT support-function table, not from UPDT. See §6.
2. **Capability** — `UPDT_GET_CAPABILITY(0x30, 0x10)` → `UPDT_RET_CAPABILITY`
   giving `resumable`, `topology`, `supportBackgroundTransfer`, `requiredAcConnectionCheck` (§3.2).
   Sent as `new eg0.a.b().f(FW_UPDATE_TANDEM_PART1)`, reply matched on class `eg0.m` +
   inquired type (`wv/e.java:831-837`).
3. **Battery thresholds** — `UPDT_GET_PARAM(0x36, 0x10)` → `UPDT_RET_PARAM` (reply class `eg0.r`,
   `wv/e.java:846-852`) giving `batteryPowerThreshold` / `batteryPowerThresholdInterrupt`, plus
   `categoryId`, `serviceId`, `nationCode`, `language`, `serialNumber`, `uniqueId` — the last set is
   what the app sends to Sony's "AutoMagic" metadata service to discover the update (§5).
4. **Status** — `UPDT_GET_STATUS(0x32, 0x10)` → `UPDT_RET_STATUS`. Expected `INVALID` (0x00) before
   starting. *Note:* the j2objc path does **not** poll this; it relies purely on the unsolicited
   `UPDT_NTFY_STATUS` (0x35) stream. The explicit poll exists only in the KMP layer
   (`V2TandemFotaRepository.java:175-182`). A reimplementation should poll it once at startup to
   learn whether the device is already mid-update.
5. The device also refuses at handshake time via result codes: `ERROR_NEED_POWER_CABLE_CONNECTED_AND_ENOUGH_BATTERY`
   (0x06) and `ERROR_TEMPERATURE_IS_TOO_HIGH` (0x07) on `ENTER_FW_UPDATE_MODE`
   (`…/core/EnterFwUpdateMode.java:63-67`).

There is **no separate "battery level" UPDT query** — the app uses the ordinary MDR battery feature
and the thresholds from `UPDT_RET_PARAM`.

### 4.1 Overall order

```
GET_CAPABILITY / GET_PARAM / GET_STATUS        (preconditions)
  -> ENTER_FW_UPDATE_MODE            (SET_PARAM 0x38 0x11 0x01)
     [download the .bin from Sony over HTTPS while in FW-update mode]
  for each file in the update:
     -> START_TRANSFER               (SET_PARAM 0x38 0x12 0x03 …)
        <- NTFY_PARAM PART3 result + maxPacketSize + offset
        loop: UPDT_TRANSFER_DATA     (0x3E 0x12 offset len data, LARGE_DATA_MDR)
     -> FINISH_TRANSFER              (SET_PARAM 0x38 0x11 0x04)
  -> EXECUTE_FW_UPDATE               (SET_PARAM 0x38 0x13 0x06 …)
     <- NTFY_PARAM PART4 result + requiredTime
     <- NTFY_STATUS UPDATING
     … device installs, may drop the link and reboot …
     <- NTFY_MESSAGE FW_UPDATE_COMPLETED   (or install timeout at 2 x requiredTime)
```

Cancellation at any point: `CANCEL_TRANSFER` then `EXIT_FW_UPDATE_MODE`.

Ordering detail worth reproducing: the firmware download happens **after**
`ENTER_FW_UPDATE_MODE` succeeds — `m.java:106-110` (`onCompleted()` of the enter step calls
`f39116e.B(...)`, the download), and `m.java:539-551` (`R()`, called from the download's success
callback, requires state `FIRMWARE_DOWNLOADING` and starts the transfer). The device therefore sits in
`IDLE` for the whole download.

### 4.2 `ENTER_FW_UPDATE_MODE`

`…/core/EnterFwUpdateMode.java`:

* Sends `SET_PARAM 0x38 0x11 0x01` (`s20/h.java:71-79` → `eg0/y.java`).
* Waits for **both**:
  * `NTFY_PARAM` PART2 with `command == ENTER_FW_UPDATE_MODE && result == OK`
    (`EnterFwUpdateMode.java:55-72`), and
  * `NTFY_STATUS` PART1 with `status == IDLE` (`EnterFwUpdateMode.java:74-85`).
* **Timeout 20 s** (`EnterFwUpdateMode.java:33` `f39025a = 20`, awaited at line 99).
* Error mapping: result `0x06` → `NEED_CHARGE`, result `0x07` → `BATTERY_HOT`, anything else →
  `OTHER_ERROR`; app-level codes `AUDIO_DEVICE_NEED_CHARGE` / `AUDIO_DEVICE_BATTERY_HOT` /
  `AUDIO_DEVICE_CONDITION_FAILED` (`m.java:381-405`).
* **No disconnect, no re-pairing, no new UUID.** The same RFCOMM/SPP Tandem session is used
  throughout; nothing in this path changes the service UUID or drops the link. (Contrast the MTK
  names `FW_UPDATE_MTK_TRANSFER_*_WO_DISCONNECTION` — the "without disconnection" qualifier exists
  because other Sony update transports *do* disconnect.) The only link loss expected is during
  install (§4.7).
* If a status notification other than `IDLE` arrives after `IDLE` had been seen, the step aborts
  (`EnterFwUpdateMode.java:79-83`).

### 4.3 `START_TRANSFER`

`…/core/StartTransfer.java`, invoked per file from `…/core/q.java:209-214`:

```java
new StartTransfer(sender, notifier, /*fwVersion*/ str2, /*fileIndex*/ 0,
                  /*fileNumber*/ 1, /*fileName*/ str, macType, mac)
```

* **The app always sends `fileIndex = 0` and a single-entry file-name list** (`q.java:211`,
  `s20/h.java:85-87`), even though the wire format allows up to 31 files. The multi-file loop in
  `q.java:132-190` iterates over the metadata's binary list and does one full
  START/TRANSFER/FINISH cycle per file, each declaring itself as file 0 of 1.
* MAC type is derived from the metadata `DigestType` (`q.java:100-106`):
  `MD5 → MacType.MD5(0x01)`, `SHA1 → MacType.SHA1(0x02)`, otherwise `MacType.NONE(0x00)`.
* Waits for **both**:
  * `NTFY_PARAM` PART3 with `result == OK` — capturing `maxPacketSize` and `offset`
    (`StartTransfer.java:140-157`), and
  * `NTFY_STATUS` with `status == DATA_RECEIVING` (`StartTransfer.java:124-137`).
* **Timeout 150 s** (`StartTransfer.java:47` — `Imgproc.COLOR_BGR2YUV_YVYU` = 150,
  `org/opencv/imgproc/Imgproc.java:87`; awaited at line 182).
* `result == ERROR_NO_NEED_OF_DATA_TRANSFER (0x04)` → state `NO_NEED_TRANSFER`: the device already
  holds this image; the app skips both the data loop **and** `FINISH_TRANSFER` for that file and
  treats it as complete (`q.java:147-149`).
* `NTFY_STATUS == NOT_READY (0x02)` at this point → `CANCELED_FROM_AUDIO_DEVICE`
  (`StartTransfer.java:128-131`).

### 4.4 Data transfer

`…/core/Transfer.java:62-121`:

```java
int i11 = this.f39058d;            // maxPacketSize from START_TRANSFER reply
byte[] bArr = new byte[i11];
int i12 = this.f39059e;            // offset from START_TRANSFER reply
...
this.f39060f.reset(); this.f39060f.skip(i12);      // seek the source to `offset`
while (true) {
    int i15 = this.f39060f.read(bArr);             // read up to maxPacketSize
    if (i15 == -1 || this.f39061g) break;
    byte[] bArrE = (i15 == i11) ? bArr : e(bArr, i15);   // trim the last short chunk
    if (this.f39056b.a(i12, bArrE)) { i12 += i15; ... }  // send UPDT_TRANSFER_DATA
    else { result = Result.SENDING_FAILED; }
}
```

* **Chunk size = `maxPacketSize` from the START_TRANSFER reply, verbatim.** The app never proposes a
  size and never clamps it; there is no negotiation, the device dictates. (Bounds seen in code:
  `> 0` and it must fit a `u32be`; the practical ceiling is the 2048-byte SPP write chunking of
  `ah0/a.java:159`, which merely splits the frame across writes.)
* `offset` starts at the device-supplied resume offset and advances by the number of bytes actually
  sent. It is the byte offset **within the file**, not a packet counter.
* Progress is reported as `offset * 100 / fileSize`.
* Retries: none at this layer. `sendTransferData` failing (i.e. the frame-level ACK retry budget of
  2 exhausted, or the socket is closed) ends the loop with `SENDING_FAILED` → app result
  `TRANSFER_FAILED` (`q.java:167-170`).
* If a `NTFY_STATUS` other than `DATA_RECEIVING` arrives mid-transfer, the loop is cancelled
  (`Transfer.java:41-46`) → `Result.CANCELED` → `CANCELED_FROM_AUDIO_DEVICE` (`q.java:172-175`).
* Because the frame-level send is blocking stop-and-wait, exactly one chunk is outstanding at a
  time; the loop's `if (send ok)` is the flow-control gate.

### 4.5 `FINISH_TRANSFER`

`kx/i.java` (invoked by `q.java:108-110`, `q.java:182-188`):

* Sends `SET_PARAM 0x38 0x11 0x04`.
* Waits for **both** `NTFY_PARAM` PART2 `FINISH_TRANSFER`/`OK` **and** `NTFY_STATUS == IDLE`.
* **Timeout 20 s** (`kx/i.java:31,83`).
* Failure → app result `UPDATE_START_FAILED` (`q.java:182-185`).

After all files finish, state becomes `TRANSFERRED` (`m.java:145-148`).

### 4.6 `EXECUTE_FW_UPDATE`

`…/core/c.java` (driven by `…/core/i.java:71-107`; only legal from state `TRANSFERRED`,
`m.java:526-537`):

* Sends `SET_PARAM 0x38 0x13 0x06 <fwVersion> <fileNames>` — the file-name list here is the
  **full list of all files** in the update (`i.java:74-85` builds it from every `ix.c.f51977c`),
  unlike START_TRANSFER's single-entry list.
* Waits for **both** `NTFY_PARAM` PART4 with `result == OK` (capturing `requiredTime`)
  **and** `NTFY_STATUS == UPDATING` (`core/c.java:50-76`).
* **Timeout 20 s** (`core/c.java:38,123`).
* `result == ERROR_FIRMWARE_TRANSFER_INCOMPLETED (0x05)` is the "you didn't send me everything" error.
* Empty result → `AUDIO_DEVICE_CONDITION_FAILED` (`i.java:95-99`).

### 4.7 Completion / reboot observation

`…/core/i.java:109-128` and `m.java:427-433`:

* On success the app starts two timers keyed on `requiredTime` (seconds):
  * a fake progress ticker every `requiredTime*1000/100` ms (min 100 ms), capped at 95 %;
  * an **install timeout at `requiredTime * 2` seconds** → state `INSTALL_TIMEOUT`.
* Real completion is signalled by **`UPDT_NTFY_MESSAGE` with `MessageType.FW_UPDATE_COMPLETED`**
  (`s20/g.java:84-92` → `q20.e.a.a()` → `m.java:46-48` → `m.java:427-433`), which moves
  `INSTALLING → INSTALL_COMPLETED`.
* Link loss during install is **tolerated**: `jx/n2.java:294-308` maps
  `FIRMWARE_DOWNLOADING → DISCONNECTED` and `TRANSFERRING → TRANSFER_FAILED`, but has no case for
  `INSTALLING` (state ordinal 6 in `n2.java:167-170`), so a disconnect there is ignored.
  When the device comes back, `n2.F(DeviceState)` / `m.T(DeviceState)` re-bind the sender and
  notification handler to the new session (`n2.java:310-315`, `m.java:557-565`), and the
  `FW_UPDATE_COMPLETED` notification (or a fresh `NTFY_STATUS`) is picked up on the new link.
* A `NTFY_STATUS == NOT_READY` while `INSTALLING` forces the state machine back to `INIT`
  (`m.java:362-365`); `NTFY_STATUS == INVALID` at any time forces `INIT` (`m.java:340-343`) —
  i.e. `INVALID` is how the device says "I left FW-update mode".

### 4.8 Error / cancel paths

* **User cancel** (`m.java:567-595`):
  * from `TRANSFERRING`: send `CANCEL_TRANSFER` (`kx/b.java`, `SET_PARAM 0x38 0x11 0x05`; waits for
    PART2 result `OK` **and** `NTFY_STATUS == IDLE`, timeout 20 s) and then
    `EXIT_FW_UPDATE_MODE`.
  * from `FIRMWARE_DOWNLOADING`: send `EXIT_FW_UPDATE_MODE` directly.
  * otherwise: local state change only.
* **`EXIT_FW_UPDATE_MODE`** (`kx/g.java`, `SET_PARAM 0x38 0x11 0x02`): waits for PART2 result `OK`
  **and** `NTFY_STATUS ∈ {INVALID, NOT_READY}`; timeout 20 s (`kx/g.java:31,61,82`).
* **Device-initiated cancel**: `NTFY_STATUS == IDLE` or `NOT_READY` while
  `FIRMWARE_DOWNLOADING`/`TRANSFERRING` → `cancelFromAudioDevice()` → result
  `CANCELED_FROM_AUDIO_DEVICE` + `EXIT_FW_UPDATE_MODE` (`m.java:336-366`, `m.java:451-457`).
* App-level result codes (`…/core/FwUpdateCallbacks$ResultCode.java:3-19`):
  `LOW_BATTERY_MOBILE, AUDIO_DEVICE_NEED_CHARGE, AUDIO_DEVICE_BATTERY_HOT,
  AUDIO_DEVICE_CONDITION_FAILED, DATA_ERROR, DOWNLOAD_FAILED, DOWNLOAD_TIMEOUT,
  NETWORK_UNAVAILABLE, TIME_OUT, TRANSFER_FAILED, UPDATE_START_FAILED,
  CANCELED_FROM_AUDIO_DEVICE, CANCELED_FROM_USER, DISCONNECTED, OTHER_ERROR, NONE`.
* All steps are serialised through a single-executor mutex `…/core/d.java:28-44`; only one UPDT
  transaction is in flight at a time, and `ERROR_OCCURRED` is a sticky state
  (`m.java:459-474`).

### 4.9 Timeout summary

| step | timeout | source |
|---|---|---|
| `ENTER_FW_UPDATE_MODE` | 20 s | `EnterFwUpdateMode.java:33` |
| `EXIT_FW_UPDATE_MODE` | 20 s | `kx/g.java:31` |
| `START_TRANSFER` | 150 s | `StartTransfer.java:47` |
| per-chunk frame ACK | 5000 ms × (1+2 resends) | `le0/e.java:352` |
| `FINISH_TRANSFER` | 20 s | `kx/i.java:31` |
| `CANCEL_TRANSFER` | 20 s | `kx/b.java:31` |
| `EXECUTE_FW_UPDATE` | 20 s | `core/c.java:38` |
| install | `2 × requiredTime` s | `core/i.java:121-127` |
| HTTPS download | 240 s | `mu/d.java:455-460` |

---

## 5. What bytes are actually transferred

**The raw, unmodified downloaded firmware file.** No decryption, no re-framing, no header stripping.

Chain of custody:

1. `mu/d.java:437-467` (`l()`) downloads the URL from the AutoMagic metadata over HTTPS.
2. `mu/d.java:268-302` (`k()`) accumulates the response body into a `ByteArrayOutputStream`
   in 1024-byte reads → `byte[]`.
3. `mu/d.java:78-92` validates it and hands the **same array** onward:
   * `o(expectedSize, actual)` — exact length match (`mu/d.java:394-397`);
   * `n(expectedMac, digestType, bytes, …)` — MD5 or SHA1 of the bytes hex-compared to the metadata
     MAC (`mu/d.java:373-392`).
   No transformation is applied — `d.this.f65402c = bArr; this.f65409a.c(bArr);`.
4. `AutoMagicDownloadTask.java:183` wraps it verbatim:
   `f39014m.add(new ix.c(bArr, version, fileName, digestType, mac))` — `ix/c.java:8-24`,
   field `f51975a` is the byte array. (Cache path `AutoMagicDownloadTask.java:347` is identical.)
5. `…/core/q.java:216-225` feeds `cVar2.f51975a` into `e.a(bArr)` →
   `…/core/e.java:7-43`, a plain `ByteArrayInputStream` wrapper implementing `kx/j.java`.
6. `Transfer.java:88` reads straight from that stream into the `UPDT_TRANSFER_DATA` payload.

So a third-party client can transfer the bytes it downloaded from Sony's distribution URL unchanged.
The only derived values it must compute are the ones that go in `START_TRANSFER`: the file name, the
FW version string, and the **hex-string** digest (MD5→32 chars / SHA1→40 chars) that Sony's metadata
already supplies.

---

## 6. Device / transport selection

The selection chain is:

```
CONNECT support-function list (List<FunctionType>)
  -> UpdateCapability.LibraryType                 (DeviceCapabilityTableset2Builder.d())
     -> switch in kl/b.java:onDeviceConnected     -> CSR | MTK/Airoha | Tandem | MC-app
```

`UpdtInquiredType` is a *consequence* of the choice, not the selector.

### 6.1 The selecting bits: CONNECT support-function flags

`com/sony/songpal/tandemfamily/message/mdr/v2/FunctionType.java:259-265` — all in `Table.NO_1`
of the support-function reply:

| `FunctionType` | byte |
|---|---|
| **`FW_UPDATE_TANDEM`** | **0x30** (48) |
| `FW_UPDATE_MTK_TRANSFER_WITHOUT_DISCONNECTION` | 0x32 (50) |
| `FW_UPDATE_MTK_TRANSFER_WITHOUT_DISCONNECTION_AUTO_UPDATE` | 0x34 (52) |
| `FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE` | 0x35 (53) |
| `FW_UPDATE_MTK_TRANSFER_WITH_AC_CONNECTION_CHECK` | 0x36 (54) |
| `FW_UPDATE_TANDEM_TRANSFER_USING_COMMON_TABLE` | 0x37 (55) |
| `FW_UPDATE_USING_MC_APP` | 0x38 (56) |

`FW_UPDATE_TANDEM_TRANSFER_USING_COMMON_TABLE` (0x37) is **declared but never read** anywhere else
in the app — no predicate, no `LibraryType`, no message class. Apparently unimplemented in this build.

### 6.2 `FunctionType` list → `UpdateCapability.LibraryType`

`UpdateCapability.LibraryType` (`com/sony/songpal/mdr/j2objc/tandem/UpdateCapability.java:42-49`):

```java
public enum LibraryType {
    CSR,                            // ordinal 0 -> CSR/GAIA (com.csr.gaia / com.csr.vmupgradelibrary)
    MTK_RHO_W_DISCONNECTION,        // ordinal 1 -> Airoha/MTK, legacy "RHO with disconnection"
    MTK_TRANSFER_WO_DISCONNECTION,  // ordinal 2 -> Airoha/MTK
    TANDEM,                         // ordinal 3 -> THIS SPEC
    USING_MC_APP,                   // ordinal 4 -> hand off to Sony Music Center
    NOT_SUPPORTED                   // ordinal 5
}
public enum Target { FW, VOICE_GUIDANCE, SONY_VOICE_ASSISTANT }   // :52-56
```

**Tableset2 (v2 protocol) devices** —
`com/sony/songpal/mdr/j2objc/devicecapability/tableset2/DeviceCapabilityTableset2Builder.java:449-508`:

```java
private static UpdateCapability.LibraryType d(List<FunctionType> list) {
    if (f(list) || l(list) || i(list)) return LibraryType.MTK_TRANSFER_WO_DISCONNECTION;
    if (k(list))                       return LibraryType.TANDEM;
    return g(list) ? LibraryType.USING_MC_APP : LibraryType.NOT_SUPPORTED;
}
// f() :482  any of the four FW_UPDATE_MTK_TRANSFER_* FunctionTypes (0x32/0x34/0x35/0x36)
// g() :486  FW_UPDATE_USING_MC_APP (0x38)
// i() :494  SONY_VOICE_ASSISTANT
// k() :502  FW_UPDATE_TANDEM (0x30)
// l() :506  any VOICE_GUIDANCE_SETTING_* variant
```

So, for a v2 device: **`TANDEM` is selected iff `FW_UPDATE_TANDEM` (0x30) is advertised AND none of
the four MTK FW-update types, no `VOICE_GUIDANCE_SETTING_*`, and no `SONY_VOICE_ASSISTANT` are.**
The `l()`/`i()` terms in the first branch are surprising — they mean a device that supports Tandem
FOTA *and* voice-guidance settings would be routed to the MTK library — but that is what the
decompiled code says; presumably no shipping Tandem-FOTA model advertises those. This is the single
most important thing to double-check against a real device's support-function reply.

The capability object is built at `DeviceCapabilityTableset2Builder.java:1367`, gated on
`f(list) || g(list) || k(list)`:

```java
new UpdateCapability(d(list), this.H0 /*resumable*/, this.J0 /*tws*/, this.I0 /*bgTransfer*/,
                     e(list) /*targets*/,
                     list.contains(FunctionType.FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE),
                     this.K0 /*acConnectionCheck*/)
```

`resumable` / `tws` / `bgTransfer` / `acCheck` come from the `UPDT_RET_CAPABILITY` reply, fed in via
`DeviceCapabilityTableset2Builder.V(...)` (`:786-793`) from `wv/a.java:284-300`
(`r(eg0.p)` at `:299` is the Tandem-PART1 overload, using `pVar.h() == Topology.TWS`).
Repair mode is a *boolean flag* on the capability, not a distinct `LibraryType`.

**Tableset1 (v1 protocol) devices never use Tandem FOTA** —
`…/devicecapability/tableset1/DeviceCapabilityTableset1Builder.java:240-306`:

```java
if (isFwUpdateSupported) {
    if (!z11) {                                   // z11 = DeviceCapabilityTableset1.g3()
        libraryType = LibraryType.CSR;            // protocol version < 0x4000 => always CSR/GAIA
        ...
    } else {
        int i11 = a.f38436b[updateMethod.getModule().ordinal()];
        if (i11 != 1) libraryType = (i11 != 2) ? LibraryType.NOT_SUPPORTED
                                               : LibraryType.MTK_RHO_W_DISCONNECTION;
        else          libraryType = LibraryType.CSR;
    }
}
```

* `g3()` (`…/tableset1/DeviceCapabilityTableset1.java:938-941`) is `R2() >= 16384`, i.e. protocol
  version ≥ 0x4000.
* `Module.TANDEM` falls into the `NOT_SUPPORTED` arm — v1 has no Tandem FOTA.
* v1 `UpdateMethod` byte codes (`com/sony/songpal/tandemfamily/mdr/param/UpdateMethod.java:7-17`):
  `TANDEM_METHOD=0x00`, `CSR_METHOD=0x10`, `CSR_RESUMABLE=0x11`, `CSR_TWS=0x12`,
  `CSR_TWS_RESUMABLE=0x13`, `MTK_METHOD=0x20`, `MTK_RESUMABLE=0x21`, `MTK_TWS_RESUMABLE=0x23`,
  `MTK_BACKGROUND_RESUMABLE=0x25`, `MTK_TWS_BACKGROUND_RESUMABLE=0x27`.
  `getModule() = byteCode() & 0xF0` → `TANDEM(0x00) / CSR(0x10) / MTK(0x20) / UNKNOWN(0xF0)`;
  low nibble bit flags `1=resumable, 2=tws, 4=background` (`UpdateMethod.java:65-79`).

The only per-model hard-coding found anywhere is
`com/sony/songpal/mdr/j2objc/tandem/UpdateCapability.java:13-18`:
`{"WF-1000X", "WF-SP700N"}`, consumed at `DeviceCapabilityTableset1Builder.java:267` to force the
**TWS flag** on those two legacy CSR models. It does not select a transport. No JSON/asset table
mapping model → update method was found (the decompiled `resources/` tree is empty in this
extraction, so an APK asset cannot be fully ruled out).

### 6.3 The concrete branch point

`kl/b.java:202-235`, `onDeviceConnected(DeviceState)` — the single dispatcher
(ordinal table at `kl/b.java:76-103`):

```java
UpdateCapability updateCapabilityX = deviceState.c().h().X();
switch (C0755b.f60024a[updateCapabilityX.b().ordinal()]) {
    case 1: d(deviceState, mdrApplication); break;              // CSR
    case 2: case 3: case 4: e(deviceState, mdrApplication); break; // MTK_RHO_W_DISCONNECTION,
                                                                   // MTK_TRANSFER_WO_DISCONNECTION,
                                                                   // USING_MC_APP
    case 5: f(deviceState, mdrApplication); break;              // TANDEM
    case 6: break;                                              // NOT_SUPPORTED
    default: throw new NoWhenBranchMatchedException();
}
```

| case | LibraryType | handler | implementation |
|---|---|---|---|
| 1 | `CSR` | `kl/b.java:116-119` → `MdrApplication.h1()` = `com.sony.songpal.mdr.application.update.csr.a` | GAIA/VM-upgrade: `com/csr/gaia/`, `com/csr/vmupgradelibrary/` (`GaiaLink.java`, `ResumePoints.java`) |
| 2,3,4 | `MTK_*`, `USING_MC_APP` | `kl/b.java:121-186` → `MdrApplication.N1()` = `pl.j` | Airoha SDK `com/airoha/libfota1562, libfota1568, libfota2833, libbase, libcommon, liblinker, liblogger`; bridge `pl/d.java` |
| 5 | **`TANDEM`** | `kl/b.java:201-215` → `MdrApplication.s2()` = `tn.g0` | **this spec** |
| 6 | `NOT_SUPPORTED` | – | – |

Tandem handler (`kl/b.java:201-215`):

```java
private final void f(DeviceState deviceState, MdrApplication mdrApplication) {
    SpLog.a(TAG, "[Tandem FOTA] FwUpdate Tandem Connected");
    mdrApplication.s2().x(deviceState);
    n2 n2VarW = mdrApplication.s2().w();
    if (n2VarW != null && !n2VarW.y() && n2VarW.x()) {
        n2VarW.G(this.f60023b);   // register availability listener
        n2VarW.D();               // obtainUpdateMetaData
    }
}
```

Controller construction chain (single path, single call site):

* `tn/g0.java:415-417` — the guard:
  `private final boolean z(DeviceState ds) { return ds.c().h().X().b() == UpdateCapability.LibraryType.TANDEM; }`
* `tn/g0.java:521-554` — `x(DeviceState)`:
  `if (z(deviceState)) { … this.f78702d = new n2(deviceState, new ah.d(), new ll.a(), new ah.f()); … }`
  (also re-entered from `g0.onDeviceConnected`, `tn/g0.java:452-459`)
* `jx/n2.java:192-197` — the only `new …tandem.core.m(…)` in the app:

```java
public n2(DeviceState deviceState, ah.c cVar, mu.i iVar, ah.e eVar) {
    ...
    this.f58746a = new com.sony.songpal.mdr.j2objc.feature.fwupdate.tandem.core.m(
                       deviceState, iVar, cVar, eVar, i());
}
```

The transport plumbing it needs — `deviceState.i().y0()` (the `q20.f` sender, `s20/h.java`) and
`deviceState.d().d(q20.e.class)` (the notification hub, `s20/g.java`) — exists only on a tableset2
`DeviceState`.

Secondary `LibraryType` switches exist for UI/metadata only, not transport selection:
`he0/d.java:52-74` (update-card view; `case TANDEM` → `new e(context)` when background transfer is
supported, else `new c(context, TANDEM)`), `he0/c.java:97-105`,
`com/sony/songpal/mdr/vim/MdrApplication.java:2162`,
`…/vim/activity/MdrFgVoiceGuidanceUpdateActivity.java:81`,
`…/vim/activity/MdrFgSVALanguageUpdateActivity.java:62`.

### 6.4 `FunctionType` → `UpdtInquiredType` (which sub-address to address)

`wv/e.java:814-852`, `private void j0(List<FunctionType> list)` — the app picks exactly one
inquired type and then issues `UPDT_GET_CAPABILITY` / `UPDT_GET_PARAM` with it:

```java
if      (list.contains(FW_UPDATE_MTK_TRANSFER_WITHOUT_DISCONNECTION))             type = FW_UPDATE_MTK_TRANSFER_WO_DISCONNECTION;              // 0x02
else if (list.contains(FW_UPDATE_MTK_TRANSFER_WITHOUT_DISCONNECTION_AUTO_UPDATE)) type = FW_UPDATE_MTK_TRANSFER_WO_DISCONNECTION_AUTO_UPDATE;  // 0x04
else if (list.contains(FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE))                  type = FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE;              // 0x05
else if (list.contains(FW_UPDATE_MTK_TRANSFER_WITH_AC_CONNECTION_CHECK))          type = FW_UPDATE_MTK_TRANSFER_WITH_AC_CONNECTION_CHECK;      // 0x06
else if (list.contains(FW_UPDATE_USING_MC_APP))                                   type = FW_UPDATE_USING_MC_APP;                               // 0x07
else if (!list.contains(FW_UPDATE_TANDEM)) return;                 // no FW update at all
else                                                                              type = FW_UPDATE_TANDEM_PART1;                               // 0x10
```

Reply-class discrimination is on `payload[1]`: `eg0/m.java:14` (base list `{0x02,0x04,0x05,0x07}`),
`eg0/n.java:15` (MTK/MC-app), `eg0/o.java:15` (`== 0x06`), `eg0/p.java:13,19` (`== 0x10`, Tandem).

Corroborating structural evidence: `UPDT_SET_STATUS` (0x34) accepts **only** MTK inquired types
(`eg0/b0.java:13`) — the MTK path drives the device with `SET_STATUS`, the Tandem path exclusively
with `SET_PARAM` (0x38).

### 6.5 KMP-side mirror (parallel / possibly not live)

`com/sony/songpal/mdr/kmp/feature/tandem/repository/model/updt/fwupdate/FwUpdateMethod.java:8-14`:

```java
public enum FwUpdateMethod {
    TANDEM,
    MTK_TRANSFER_WITHOUT_DISCONNECTION,
    MTK_TRANSFER_WITHOUT_DISCONNECTION_AUTO_UPDATE,
    MTK_TRANSFER_WITH_REPAIR_MODE,
    MTK_TRANSFER_WITH_AC_CONNECTION_CHECK,
    USING_MC_APP
}
```

No byte codes on the enum; the mapping to `UpdtInquiredType` is
`…/repository/v2/updt/fwupdate/a.java:122-140` (`int c(FwUpdateMethod)`):
`TANDEM → 16 (0x10)`, `MTK_…WITHOUT_DISCONNECTION → 2`, `…AUTO_UPDATE → 4`,
`…WITH_REPAIR_MODE → 5`, `…WITH_AC_CONNECTION_CHECK → 6`, `USING_MC_APP → 7`.

`FwUpdateMethod` is assigned in the KMP factory `ga0/a.java:42-113` by the **runtime class of the
parsed capability reply**, not by byte inspection: `ob0.b2 → MTK_…WITHOUT_DISCONNECTION` (`:44`),
`ob0.p1 → …AUTO_UPDATE` (`:50`), `ob0.v1 → …WITH_REPAIR_MODE` (`:56`),
`ob0.j1 → …WITH_AC_CONNECTION_CHECK` (`:62`), `ob0.d1 → USING_MC_APP` (`:70`);
`ob0.h2` (= `UpdtRetTandemCapability`, §3.2) takes the `:97-113` branch which builds
`V2TandemFotaRepository` and hardcodes `FwUpdateMethod.TANDEM` (`:77`).

`V2TandemFotaRepository` hardcodes the Tandem sub-addresses: `fetchStatus` sends the
`UpdtGetTandemStatus` payload and matches the reply on `(opcode 51 = 0x33, inquiredType 16 = 0x10)`
(`V2TandemFotaRepository.java:175-182`); `startObservingNotifications` subscribes command results on
inquired type `17 = 0x11` (`V2TandemFotaRepository.java:136-141`).

This KMP layer looks like a newer parallel implementation. The **live** wiring in 13.2.2 is the
j2objc path `bx.j` connection callback → `kl/b.java` → `tn/g0.java` → `jx/n2.java` →
`…/tandem/core/m.java`.

---

## 7. Minimal Kotlin reimplementation checklist

1. Speak the existing frame layer; add `LARGE_DATA_MDR = 0x2C` as a data type with
   `ackRequired = true`, ACK timeout 5000 ms, 2 resends.
2. Confirm the device advertises `FunctionType` byte **0x30** (`FW_UPDATE_TANDEM`) in the CONNECT
   support-function table NO_1, and none of `0x32/0x34/0x35/0x36` (MTK). See §6.2 for the app's
   extra `VOICE_GUIDANCE_SETTING_*` / `SONY_VOICE_ASSISTANT` exclusions.
3. `0x30 0x10` → expect `0x31 0x10 04 <resumable> <topology> <bgTransfer> <acCheck>`.
4. `0x36 0x10` → parse `0x37 0x10 …` for battery thresholds + `serviceId`/`nationCode`/`uniqueId`
   etc. for the metadata lookup. `0x32 0x10` → expect `0x33 0x10 00` (`INVALID`).
5. `0x38 0x11 0x01`; wait ≤20 s for `0x39 0x11 0x01 0x00` **and** `0x35 0x10 0x01` (IDLE).
6. Download the `.bin` (HTTPS), verify size + MD5/SHA1 against the metadata.
7. `0x38 0x12 0x03 <len><fwVersion> 0x00 0x01 <len><fileName> <macType> <macLen><asciiHexMac>`;
   wait ≤150 s for `0x39 0x12 0x03 0x00 <u32 maxPacketSize> <u32 offset>` **and**
   `0x35 0x10 0x03` (DATA_RECEIVING).
8. Loop from `offset`: `0x3E 0x12 <u32 offset> <u32 n> <n bytes>` as `LARGE_DATA_MDR`,
   `n = maxPacketSize` (last chunk shorter). Abort on any `0x35 0x10` != `0x03`.
9. `0x38 0x11 0x04`; wait ≤20 s for `0x39 0x11 0x04 0x00` **and** `0x35 0x10 0x01` (IDLE).
10. `0x38 0x13 0x06 <len><fwVersion> <numFiles> <len><name>…`; wait ≤20 s for
    `0x39 0x13 0x06 0x00 <u16 requiredTime>` **and** `0x35 0x10 0x04` (UPDATING).
11. Tolerate a link drop; on reconnect wait for `0x3F 0x10 0x01 <len> <data>`
    (`FW_UPDATE_COMPLETED`), with a hard deadline of `2 × requiredTime` seconds.
12. Abort path: `0x38 0x11 0x05` (CANCEL) then `0x38 0x11 0x02` (EXIT), each waiting for its
    PART2 result and the matching status (`IDLE` for cancel; `INVALID`/`NOT_READY` for exit).

---

## 8. Not determined / open questions

* **`FW_UPDATE_TANDEM_TRANSFER_USING_COMMON_TABLE` (FunctionType 0x37)** — a second Tandem FOTA
  variant carried on the *common* command table (`LARGE_DATA_COMMON` = 0x2D would presumably be its
  data type). Declared in `FunctionType.java:264` and in the `values()` array, but **never read**:
  no predicate, no `LibraryType`, no message classes. Unimplemented in this build; its wire format
  is unknown.
* **The `l()`/`i()` terms in `DeviceCapabilityTableset2Builder.d()`** (`:449-453`) force
  `MTK_TRANSFER_WO_DISCONNECTION` whenever any `VOICE_GUIDANCE_SETTING_*` or `SONY_VOICE_ASSISTANT`
  function is advertised, *before* the `FW_UPDATE_TANDEM` check. Whether real Tandem-FOTA models
  therefore never advertise those functions, or whether this is an app bug, could not be determined
  without a device.
* **Whether the KMP repository layer (`V2TandemFotaRepository`, `FwUpdateMethod`, `ga0/a.java`) is
  live** in 13.2.2 or dead parallel code. The j2objc path is unambiguously wired to the connection
  callbacks; the KMP path's entry point was not traced. The `ob0` capability classes
  `b2/p1/v1/j1/d1` were only inferred (from `ga0/a.java` naming) to correspond to inquired types
  0x02/0x04/0x05/0x06/0x07 — not verified from their parsers.
* **Payload of `UPDT_NTFY_MESSAGE`** beyond `MessageType` and the length byte: the app reads only
  `d()[2]` (`eg0/d.java:36-38`) and ignores the `dataLength` bytes. Contents unknown.
  Also unclear why the validator demands `dataLength >= 1` when the app never reads the data.
* **Practical range of `maxPacketSize`.** No observed value; the app accepts anything `> 0`. The
  `LARGE_DATA_MDR` name plus the 4-byte length field suggests it can exceed the ~2 KB SPP write
  chunk, but this could not be confirmed from the code.
* **Whether `resumable`/`offset` is ever non-zero in practice.** The code handles a non-zero resume
  offset (`Transfer.java:74-84`) but nothing was found that *requests* a resume; the device supplies
  the offset unilaterally in the START_TRANSFER reply.
* **`fwVersion` length limit discrepancy**: the Java builder caps it at 32 bytes
  (`fg0/a.java:19-21`, `eg0/a0.java:82-85`) while the KMP parser allows 128
  (`ob0/c3.java:52-54`). Which the firmware enforces is unknown; 32 is the safe choice.
* **`numFiles` limit discrepancy**: Java 1..31 (`fg0/a.java:31`), KMP 1..255 (`ob0/c3.java:58`).
* **TWS specifics.** `Topology.TWS` (0x01) is reported in the capability reply but no code was found
  that changes the transfer for TWS (e.g. per-earbud transfer or a second `fileIndex`). The app
  always sends `fileIndex = 0, numFiles = 1` per cycle. How a TWS pair distributes the image
  internally is not visible from the app.
* **`ERROR_ILLEGAL_STATE` / `ERROR_ILLEGAL_ARGUMENTS` recovery.** The app treats them as generic
  failures (`OTHER_ERROR`) and does not retry; the device-side conditions that produce them are
  not documented in the code.
* **AutoMagic metadata request format** (the HTTPS API that yields URL/size/MAC/version) was not
  traced — only the fields it produces (`ah/a.java`, `ah/b.java` accessors used in
  `mu/d.java:437-452`).
