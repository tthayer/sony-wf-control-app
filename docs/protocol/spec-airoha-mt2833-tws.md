# Airoha MT2833: byte-exact TWS (agent + partner earbud) FOTA spec, SPP/RFCOMM

Target: a Kotlin reimplementation for the **WF-1000XM6** (chip name
`MT2833_Earbuds`), speaking RFCOMM/SPP on UUID `8901dfa8-5c7e-4d8f-9f0c-c2b70683f5f0`,
reproducing what Sony Sound Connect 13.2.2 does when the device reports `tws = ENABLE`.

Source of truth: the **fresh** jadx decompile at
`…/e4df01bb-f485-4540-b962-ab27c4b93309/scratchpad/sony/jadx/sources`. Every
`file:line` below is relative to that directory. That scratchpad is temporary; regenerate
it with `unzip Sony+_+Sound+Connect_13.2.2_APKPure.xapk && jadx -d jadx --show-bad-code *.apk`
(jadx 1.5.6). Class names turned out to be the same
as in the older tree (`d8.b` = `AirohaFotaMgr2833`, `h8/*` = TWS stages), but **many line
numbers moved** (see §10.1).

Baseline and template: `spec-airoha-mt28xx-single.md` (called "SINGLE" below). Everything
it says about the frame layout (§0), the CRC-8 (§7.1), SHA-256 (§7.4), `x8.d` helpers (§7.3),
the stage base class (§6), long-packet assembly (§6.5), and the 0x0402 page record (§3.9)
**applies to the TWS flow unchanged**. This document only lists what is different or extra.

jadx constant substitutions that are new here:

| jadx renders | real value | source |
|---|---|---|
| `DTrees.PREDICT_MASK` | **768** = 0x0300 | `org/opencv/ml/DTrees.java:9` |
| `stage.a.f21558x` | `{00 10 00 00}` = 4096 LE32 | `com/airoha/libfota2833/fota/stage/a.java:38` |

(`Imgcodecs.IMWRITE_GIF_QUALITY` = 0x0402 and `CAP_PROP_XI_IMAGE_DATA_FORMAT_RGB32_ALPHA` =
0x0211 as in SINGLE.)

---

## 0. The answer in brief

* **There is no 0x0D01 relay on MT2833.** The partner is addressed by a **role byte inside
  the payload** (`0` = agent, `1` = partner), by **TWS-specific race ids** (0x1C10–0x1C14,
  0x0432, 0x0CD4, 0x0CD7), or not at all. `grep 3329|3328|Relay` across `z7 a8 b8 c8 d8 e8
  f8 g8 h8 com/airoha/libfota2833 FotaControl2833.java` returns **nothing**. The 0x0D00 /
  0x0D01 machinery (§4) is used only by the AB1562 library (`p7/*`, `i7/*`).
* **One image for both buds.** Sony hands the Airoha library a single `byte[]`
  (`pl/d.java:321`), `startDualFotaExt` is called with the partner file `null`
  (`d8/b.java:69, 74`), and the TWS erase-status stage slices that one stream into **two
  identical sector maps**, one per bud, at the same partition address
  (`h8/m.java:81-84`).
* **The host writes image bytes to the agent only**, in **two passes separated by a role
  handover (RHO)**. Pass 1 erases both buds (one dual-target 0x0432 per sector pair),
  writes the agent, verifies the agent, and asks for a role switch (0x0CD7). After the
  handover the other bud is the agent; pass 2 runs the same stage list again, now
  writing the new agent (the erase is skipped because it was done in pass 1), verifies
  both, and ends with both states at 0x0311. Commit is 0x1C11 (TWS commit, 15 s).
  (§5, §7 show the full trace. Whether the agent also forwards data to the partner
  internally during pass 1 is **UNCONFIRMED**. The state machine below works either way.)

---

## 1. How Sony picks TWS vs single for MT2833

### 1.1 Capability → `isTws`

MDR `UPDT_RET_CAPABILITY` for inquired types 2/4/5/7 is parsed by `eg0.n`
(`eg0/m.java:30-33` → `eg0/n.java:14-24`):

```
payload  31 04 03 00 00 00          (live WF-1000XM6)
[0] 0x31 UPDT_RET_CAPABILITY
[1] 0x04 FW_UPDATE_MTK_TRANSFER_WO_DISCONNECTION_AUTO_UPDATE   (UpdtInquiredType.java:9)
[2] 0x03 length of what follows                                (eg0/n.java:18)
[3] resumable   EnableDisable   n.e()  eg0/n.java:36-38
[4] tws         EnableDisable   n.g()  eg0/n.java:44-46
[5] background  EnableDisable   n.f()  eg0/n.java:40-42
EnableDisable: ENABLE = 0x00, DISABLE = 0x01   (tandemfamily/message/mdr/v2/EnableDisable.java:6-7)
```

`wv/a.java:284-288` → `builder.V(bg = [5]==EN, resumable = [3]==EN, tws = [4]==EN, false, type)`
(`DeviceCapabilityTableset2Builder.java:786-792`) → `new UpdateCapability(lib, H0=resumable,
J0=tws, I0=background, targets, repair = list contains FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE,
K0)` (`DeviceCapabilityTableset2Builder.java:1366-1367`).

`UpdateCapability`: `d()` = background (`UpdateCapability.java:80-82`), `e()` = repair mode
(`:84-86`), `f()` = resumable (`:101-103`), `g()` = tws (`:105-107`).

```java
// pl/d.java:295-300
public boolean z() {
    if (this.f69559l.e()) { return false; }   // repair mode -> never TWS
    return this.f69559l.g();                  // tws
}
// pl/d.java:323
this.f69553f.start(i11, this.f69559l.d(), z(), this.f69559l.f());
```

**XM6: `31 04 03 00 00 00` ⇒ resumable = true, tws = true, background = true, repair = false
(type 0x04 is not REPAIR_MODE) ⇒ `z()` = true.** The single-device path is **not** what Sony
runs on this device.

### 1.2 Chain to the TWS flow

| step | code | cite |
|---|---|---|
| adapter `start(thr, bg=true, tws=true, resumable)` | stores `mIsTwsMode = z12` | `AirohaFotaAdapterSony.java:549-586` (576) |
| chip name contains "283" ⇒ `CHIP_TYPE.MT2833` | | `AirohaFotaAdapterSony.java:213-216` |
| SPP socket closed and reopened; `FotaControl2833` built | | `AirohaFotaAdapterSony.java:224-232, 115-124` |
| `mFotaControl.start(thr, bg, tws, resumable, 512)` | | `AirohaFotaAdapterSony.java:142` |
| `mAirohaFotaMgr2833.Z0(i11, z11, z12, false, i12)` (4th arg **forced false**) | | `FotaControl2833.java:204-207` |
| `f43955l = z12` (isDual) | | `d8/b.java:597` |
| bg ⇒ `FotaModeId.Background` in **both** `e8.b` and `e8.a`, long-packet ON, 3 cmds/packet, 200 ms | | `d8/b.java:605-622` |
| `T()` queryAfterConnected: `f43955l` ⇒ `"fota_step = TWS Query"` ⇒ `Q0()` | | `d8/b.java:534-558` (543-545) |
| `Q0()` queryDualFotaInfo, ends with `h8.i` 0x1C12 | | `d8/b.java:502-514` |
| `h8.i.o()` → `mgr.Y(agentState, partnerState)` → `A()` | | `h8/i.java:35`, `d8/d.java:852-859` |
| `A()` handleTwsQueriedStates → `O0(DualActionEnum.X)` | | `d8/b.java:260-290` |
| `O0` posts runnable `a` **1000 ms later** on the main looper, only if `f43959o` (flash allowed) | | `d8/b.java:453-470` |
| runnable `a`: `StartFota` ⇒ `l0(path, null, e8.a, 512K)` or `m0(bytes, null, e8.a, 512K)` | | `d8/b.java:64-79` |
| `l0/m0` → set pacing `e8.a.f44841h`(200), window `e8.a.f44835b`(4), region 524288 → `y0()` | | `d8/d.java:973-1009` |
| `y0()` "startTwsResumableEraseFotaV2StorageExt" | the 19-stage TWS queue | `d8/d.java:1265-1345` |

The action enum is **`com.airoha.libfota2833.fota.actionEnum.DualActionEnum`**
`{RoleSwitch, StartFota, StartNvKeyUpdate, TwsCommit, UNKNOWN}` (`DualActionEnum.java:5-11`).
`com.airoha.libfota1562.constant.FotaDualActionEnum` (`FotaDualActionEnum.java:5-13`) is
AB1562-only and is **not** used on this path.

### 1.3 `A()` decision table (`d8/b.java:260-290`)

States are LE16 (`d8/d.java:856-857`); initial value 0xFFFF (`d8/d.java:105, 108`).
`I` = current acting DualAction (reset to UNKNOWN by `Z0`, `d8/b.java:594`).

| agent state | partner state | `I` | action | cite |
|---|---|---|---|---|
| ≠ 0x0311 | any | any | `StartFota` (ignored if `I` is already StartFota: `d8/b.java:459-461`) | `:267-269` |
| 0x0311 | 0x0311 | StartFota | **`TwsCommit`** = "transfer complete" | `:271-276` |
| 0x0311 | 0x0311 | not StartFota | `StartFota` (re-verify pass) | `:277-279` |
| 0x0311 | ≠ 0x0311 | not StartFota | `StartFota` | `:282-285` |
| 0x0311 | ≠ 0x0311 | StartFota | `H=true`; **`RoleSwitch`** | `:286-289` |

`TwsCommit` does **not** commit. The runnable reports `onProgressChanged(100, PARTNER)`,
`onTransferCompleted()`, and starts the 9 s ping (`d8/b.java:80-92`). The actual commit is
the separate host call in §7.

---

## 2. Image mapping

* Sony: `mu.d` downloader → `onSuccess(byte[])` → `u0(bArr,…)` → `f66863j.b(bArr, i11, …)`
  (`nu/o.java:204-210, 936-960`) → `pl/d.b()` → `setBinaryFile(bArr)` (`pl/d.java:310-323`).
  **One opaque byte array.** Nothing splits it into L/R.
* Library: `m0(bArr, null, …)` (`d8/b.java:74`) → `f43962r = ByteArrayInputStream(bArr)`
  (`d8/d.java:1007`). The `bArr2`/partner argument is ignored (`d8/d.java:997-1009`).
* `h8/m.i()` reads the stream in 4096-byte sectors (0xFF-padded tail) and puts **the same
  sector** into both maps, keyed on the LE32 address hex (`h8/m.java:67-90`):

```java
// h8/m.java:81-84
byte[] bArrK = x8.d.k(iS);                      // iS starts at mgr.s() = agent partition address from 0x1C14
String strC = x8.d.c(bArrK);
this.f21560b.f43934a0.put(strC, new stage.a.b(bArrK, bArr, i12));   // "right" map
this.f21560b.f43936b0.put(strC, new stage.a.b(bArrK, bArr, i12));   // "left"  map
```

* Which map belongs to the agent: `k()` = `L` = "agent is the right bud" from 0x0CD4
  (`h8/f.java:33-35`, `d8/d.java:861-868, 950-952`). Agent map = `k() ? a0 : b0`;
  partner map = the other (`h8/m.java:44`, `h8/n.java:203-209`, `h8/k.java:32-33`,
  `h8/l.java:27`). For a Kotlin client with one image, **both maps are identical at
  construction**. Only the per-sector flags differ: `erased` (from 0x0433 per role) and
  `needsProgram` (from 0x0431 per role).
* Size guard (`h8/d.java:60-86`): `ceil4096(imageSize) > partitionLength − 4096` ⇒
  `FOTA_BIN_FILE_SIZE_TOO_LARGE` (checked twice, "Right"/"Left", same numbers).

---

## 3. Query phase (runs on every (re)connect, including after RHO)

`Q0()` (`d8/b.java:502-514`): `V(); i0(false);` (flag byte0 = **0x05**), then:

| # | stage | race id | request (exact) | resp type | rx parse | cite |
|---|---|---|---|---|---|---|
| Q1 | `h8.f` `00_CheckAgentChannel` | **0x0CD4** | `05 5A 02 00 D4 0C` | **0x5B** (explicit check) | rx[6] status = 0; **rx[7] == 0x01 ⇒ agent is RIGHT** → `mgr.Z()` | `h8/f.java:12-36`, `c8/a.java:7` |
| Q2 | `h8.h` `00_TwsGetBattery` role 0 | 0x0CD6 | `05 5A 03 00 D6 0C 00` | 0x5D | rx[6]=0, rx[7] role, rx[8] level | `h8/h.java:17-45`, `c8/i.java:7` |
| Q3 | `h8.h` role 1 | 0x0CD6 | `05 5A 03 00 D6 0C 01` | 0x5D | same | `d8/b.java:509` |
| Q4 | `h8.g` `00_GetVersion` role 0 | 0x1C07 | `05 5A 03 00 07 1C 00` | 0x5D (explicit) | rx[6]=0, rx[7] role, rx[8] len (0 ⇒ reject), rx[9..] ASCII | `h8/g.java:18-52`, `c8/b.java:7` |
| Q5 | `h8.g` role 1 | 0x1C07 | `05 5A 03 00 07 1C 01` | 0x5D | same; role 1 → `mgr.c0()` | `h8/g.java:49-50` |
| Q6 | `h8.i` `00_TwsQueryState` | **0x1C12** | `05 5A 02 00 12 1C` | **0x5D** (explicit) | rx[6]=0; **rx[7..8] agent state LE16; rx[9..10] partner state LE16** → `Y()` → `A()` | `h8/i.java:13-36`, `c8/e.java:7`, `d8/d.java:852-859` |

All timeouts 9000 ms (stage base, `stage/a.java:86`), 3 packet retries (`z7/a.java:122-124`).

Live XM6 cross-check (probed read-only): `0x0CD6 {00}` → `5B 00` ack, then `5D 00 00 63`
(status 0, role 0, **99 %**). `0x1C07 {00}` → `5B 00`, then `5D 00 00 06 "v1.0.0"`. The
immediate 0x5B acks are **ignored**, because the stage matches on `(raceId, type)` and wants
0x5D (`stage/a.java:216-219`, `d8/d.java:249`).

Live XM6 (fw 1.6.0, 2026-09-24, flag 0x05, no 0x1C08 sent), confirming Q1-Q6:

| # | tx | rx | decoded |
|---|---|---|---|
| Q1 | `05 5A 02 00 D4 0C` | `05 5B 04 00 D4 0C 00 01` | status 0, rx[7] = 0x01 ⇒ agent is the **right** bud |
| Q2 | `05 5A 03 00 D6 0C 00` | `5B …00` then `05 5D 05 00 D6 0C 00 00 61` | role 0, 97 % |
| Q3 | `05 5A 03 00 D6 0C 01` | `5B …00` then `05 5D 05 00 D6 0C 00 01 63` | role 1 (partner), 99 % |
| Q4 | `05 5A 03 00 07 1C 00` | `5B …00` then `05 5D 0B 00 07 1C 00 00 06 "v1.0.0"` | role 0 |
| Q5 | `05 5A 03 00 07 1C 01` | `5B …00` then `05 5D 0B 00 07 1C 00 01 06 "v1.0.0"` | role 1 |
| Q6 | `05 5A 02 00 12 1C` | `5B …00` then `05 5D 07 00 12 1C 00 FF FF FF FF` | agent 0xFFFF, partner 0xFFFF (idle) |

Every query gets the immediate `5B 00` ack first, so the 0x5D-only matching is required. The RACE
version string (`v1.0.0`) is not the Sony firmware version (MDR reports `1.6.0`).

Battery: the reply's only effects are `d0(true)` (flash allowed) and a log line
(`h8/h.java:42-44`, `d8/d.java:781-783`). **No threshold is applied by the library for
either bud.** The threshold `i11` is stored write-only (`d8/b.java:590-592`). Sony gates
before calling the library: **both** L and R battery must be `>` threshold
(`MdrUpdateStatusChecker.java:65-76`). The device enforces its own limit through
0x1C03 reason 4 (§8.2).

---

## 4. The 0x0D01 relay wrapper (AB1562 only; **not used by MT2833 TWS**)

Documented because you asked for it and because the live 0x0D00 probe answered.

### 4.1 0x0D00 GetAvailableDst

Request: no payload (`i7/b.java:5-8`): `05 5A 02 00 00 0D`.
Response parsing (`p7/c.java:25-55`): **no status byte**. Starting at rx[6], pairs
`{type, id}` are read until the frame ends (`for (i=6; i < len-1; i+=2)`), and the **first
pair with `type == 0x05`** becomes the partner destination (`p7/c.java:36-44`). If there is none,
the result is `PARTNER_NOT_FOUND`.

Live XM6 reply `05 5B 04 00 00 0D 05 06`: length 4 = raceId(2) + one pair ⇒ **`{type=0x05,
id=0x06}`**. The partner dst is **`05 06`**. (Type 0x05 is the peer/partner channel. Id 0x06 is
the device's own link id and is carried verbatim. Its meaning is **UNCONFIRMED** and not needed.)

### 4.2 0x0D01 Relay request

```java
// i7/c.java:6-13
super((byte) 90, 3329);                             // 0x0D01, type 0x5A
byte[] bArrC = aVar2.c();                           // FULL inner frame, flag 0x05 (f7/a.java:84-86)
byte[] bArr = new byte[bArrC.length + 2];
System.arraycopy(aVar.a(), 0, bArr, 0, 2);          // {dstType, dstId}  (i7/a.java:13-15)
System.arraycopy(bArrC, 0, bArr, 2, bArrC.length);
```

```
off  size  field
0    1     0x05                        outer flag (AB1562 never sets 0x10)
1    1     0x5A
2    2     LE16 len = 2 + 2 + innerFrameLen
4    2     01 0D
6    1     dst type  (0x05 from 0x0D00)
7    1     dst id    (0x06 on XM6)
8    n     inner RACE frame, complete: 05 5A <len> <id> <payload>
```

e.g. relayed GetBattery role 0: `05 5A 0B 00 01 0D 05 06 05 5A 03 00 D6 0C 00`.

### 4.3 Relayed response

Outer type expected 0x5D (`p7/d.java:13-14`). Match (`com/airoha/libfota1562/stage/a.java:336-346`):
frame length ≥ 9, outer raceId (LE16 rx[4..5]) == 0x0D01, then `inner = rx[8..end]`
(`i7/d.java:19-21`), `inner[1]` = inner type, LE16 `inner[4..5]` = inner race id, and both must
equal the stage's expected inner type/id (`f21514y`, `f21513x`). Status = `inner[6]`
(`i7/d.java:27-29`, `a.java:285-291`). For inner race 0x0900/0x0901 the status is `inner[8]`
(`i7/d.java:23-25`). **5B vs 5D inner is `inner[1]`**. The outer 0x5B immediate ack
(`05 5B 03 00 01 0D <st>`, 7 bytes) fails the `length ≥ 9` test and is dropped. Outer
rx[6..7] (presumably echoed dst) is never read.

**MT2833 TWS: do not implement.** No MT2833 class builds or parses 0x0D01.

---

## 5. Authoritative TWS stage list (`y0()`, `d8/d.java:1265-1345`)

### 5.1 Queue (offer order, `d8/d.java:1328-1343`)

Flag column: byte0 for that frame. `i0(true)` runs inside `f8.a.i()` before the frame is
built (`f8/a.java:30-31`), so 0x1C08 and everything after it use 0x15 (SINGLE §0.1).
Timeouts are 9000 ms unless stated (`stage/a.java:86`).

| # | var | stage (name) | race id | request payload | resp type | rx parse | flag |
|---|---|---|---|---|---|---|---|
| 1 | — | `h8.d` FotaStageTwsQueryPartition | **0x1C14** | `00` | **0x5D** | §5.2 | 0x05 |
| 2 | — | `f8.a(dual=true)` FotaStage_00_Start | 0x1C08 | **`03 00`** (dual, Background) | 0x5D | rx[6]=0 | **0x15** |
| 3 | — | `f8.d(0)` FotaStage_07 | 0x1C1C | `01 00` | 0x5D | rx[8] role, LE16 rx[9..10] interval; timeout **non-fatal** | 0x15 |
| 4 | mVar | `h8.m` 23_TwsGetEraseStatusExt | 0x0433 | per region: role 0 **and** role 1 | 0x5D | §5.3 | 0x15 |
| 5 | nVar | `h8.n` 24_TwsCompareExt | 0x0431 | per role, per group/tail | 0x5D | §5.4 | 0x15 |
| 6 | cVar | `g8.c` 01_StartTranscation | 0x1C0A | — | 0x5B | rx[6]=0 | 0x15 |
| 7 | jVar | `h8.j` 01_TwsStartTranscation | **0x1C10** | — | **0x5D** | rx[6]=0 | 0x15 |
| 8 | jVar2 | `g8.j(0x0300)` WriteState | 0x1C06 | `00 03` | 0x5B | rx[6]=0 | 0x15 |
| 9 | eVar | `h8.e(0x0300)` TwsWriteState | **0x1C13** | `00 03 00 03` | **0x5D** | rx[6]=0 | 0x15 |
| 10 | kVar | `h8.k` 21_Erase | **0x0432** | 18-byte dual descriptor | **0x5D** | §6.1 | 0x15 |
| 11 | jVar3 | `g8.j(0x0301)` | 0x1C06 | `01 03` | 0x5B | | 0x15 |
| 12 | eVar2 | `h8.e(0x0301)` | 0x1C13 | `01 03 01 03` | 0x5D | | 0x15 |
| 13 | jVar4 | `g8.j(0x0310)` | 0x1C06 | `10 03` | 0x5B | | 0x15 |
| 14 | lVar | `h8.l` 22_TwsWrite | 0x0402 | agent map only (SINGLE §3.9 layout) | **0x5B** (not overridden) | §6.2 | 0x15 |
| 15 | dVar | `g8.d(0)` 04_CheckIntegrity | 0x1C01 | `01 00 <st>` | 0x5D | §5.5 | 0x15 |
| 16 | jVar5 | `g8.j(0x0311)` | 0x1C06 | `11 03` | 0x5B | | 0x15 |
| 17 | dVar2 | `g8.d(1)` 04_CheckIntegrity | 0x1C01 | `01 01 <st>` | 0x5D | §5.5 | 0x15 |
| 18 | eVar3 | `h8.e(0x0311)` | 0x1C13 | `11 03 11 03` | 0x5D | | 0x15 |
| 19 | iVar | `h8.i` 00_TwsQueryState | 0x1C12 | — | 0x5D | LE16 rx[7..8] agent, rx[9..10] partner → `A()` | 0x15 |

Citations for each row: race ids `c8/d.java:7` (0x1C14), `f8/a.java:12,14,23-31`,
`f8/d.java:13-14,21`, `h8/m.java:37-38`, `h8/n.java:36-37`, `g8/c.java:8`, `c8/f.java:7` +
`h8/j.java:8`, `g8/j.java:12,20` + `b8/e.java:7`, `c8/g.java:7` + `h8/e.java:13,20`,
`h8/k.java:17-18` + `c8/j.java:26`, `h8/l.java:18`, `g8/d.java:19,21,28-33`,
`c8/e.java:7` + `h8/i.java:8-9`. Roles/values from `d8/d.java:1268-1286`.

**Exact hex (in-session flag 0x15; `<st>` = storageType from 0x1C14 rx[8]):**

```
1  05 5A 03 00 14 1C 00
2  15 5A 04 00 08 1C 03 00
3  15 5A 04 00 1C 1C 01 00
4  15 5A 0C 00 33 04 <st> 00 <addr LE32> <len LE32>       role 0
   15 5A 0C 00 33 04 <st> 01 <addr LE32> <len LE32>       role 1   (same addr/len)
5  15 5A 0C 00 31 04 <st> <role> <addr LE32> <len LE32>
6  15 5A 02 00 0A 1C
7  15 5A 02 00 10 1C
8  15 5A 04 00 06 1C 00 03
9  15 5A 06 00 13 1C 00 03 00 03
10 15 5A 14 00 32 04 <st> 00 10 00 00 <agentAddr> <st> 00 10 00 00 <partnerAddr>
11 15 5A 04 00 06 1C 01 03
12 15 5A 06 00 13 1C 01 03 01 03
13 15 5A 04 00 06 1C 10 03
14 15 5A 09 01 02 04 <st> 01 <crc8> <pageAddr LE32> <256 B>
15 15 5A 05 00 01 1C 01 00 <st>
16 15 5A 04 00 06 1C 11 03
17 15 5A 05 00 01 1C 01 01 <st>
18 15 5A 06 00 13 1C 11 03 11 03
19 15 5A 02 00 12 1C
```

`h8.e` payload is `{s&FF, s>>8, s&FF, s>>8}`, meaning agent state then partner state, always equal
(`h8/e.java:20`).

### 5.2 0x1C14 TwsQueryPartition response (`h8/d.java:26-93`)

```
rx[6]       status = 0
rx[7]       read, unused                  (UNCONFIRMED: partition id / count)
rx[8]       storageType  -> mgr.g0()      ("agent storageType", h8/d.java:42-44)
rx[9..12]   partition address LE32 -> mgr.f0()   used for BOTH maps
rx[13..16]  partition length  LE32 -> mgr.e0()   size guard
rx[17]      read, unused                  (UNCONFIRMED: probably partner storageType)
rx[18..21]  copied to a throwaway array   (UNCONFIRMED: probably partner address)
rx[22..25]  copied to a throwaway array   (UNCONFIRMED: probably partner length)
```

* Frame must be ≥ 26 bytes. A shorter reply throws inside `o()`. The exception is caught by
  the RX handler (`d8/d.java:351-352`), so the packet is never marked and the 9 s retry path runs.
* `storageType != 1 && address == 0` ⇒ error (`h8/d.java:54-57`). The reported enum is whatever
  `f21575q` holds, which is `CMD_RETRY_FAIL` after the first `getData()` (`stage/a.java:249`).
* **Live XM6 (2026-09-24):** `05 5D 16 00 14 1C 00 00 00 00 80 92 00 00 70 5B 00 00 00 80 92 00
  00 70 5B 00` (26 bytes, after a `5B 00` ack): status 0, rx[7] 0x00, storageType 0, address
  0x00928000, length 0x005B7000, rx[17] 0x00, rx[18..21] 0x00928000, rx[22..25] 0x005B7000.
  The partner fields match the agent's, consistent with the "probably partner" reading, and the
  agent fields equal the single-device 0x1C00 reply. rx[7]/rx[17] are 0 on this device; their
  meaning is still UNCONFIRMED (unused by the app either way).

### 5.3 0x0433 per role (`h8/m.java`)

Request builder = SINGLE §3.4 (`a8/b.java:19-31`: `{storageType, role, addrLE32, lenLE32}`).
For each 512 KB region (`f21557w` = 524288, set by `l0/m0` → `stage.a.q(i11)`,
`d8/d.java:981/1003`), **two commands are queued back-to-back: role 0, then role 1**, at the
same address and length (`h8/m.java:97-119`). Pending key = `hex(addr) + hex(role)`
(`h8/m.java:99, 103`).

Response parse = SINGLE offsets (`h8/m.java:161-203`): rx[6] status, rx[8] role,
rx[9..12] address, rx[13..16] length (`/4096` = bit count), LE16 rx[17..18] bitmap byte
length, rx[19..] bitmap. Matching uses `hex(rx[9..12]) + hex(rx[8])`. Bitmaps are concatenated
per role (`G` role 0, `H` role 1) and applied MSB-first to the role's map
(`h8/m.java:42-62`). A set bit means already erased.

Skip decision (`h8/m.java:130-150`; default `None`, `stage/a.java:74`):

| condition | SKIP_TYPE |
|---|---|
| every sector erased on **both** roles | `CompareErase_stages` |
| else sector 0 erased on **both** maps | `Compare_stages` |
| else | `None` |

### 5.4 0x0431 per role (`h8/n.java`)

Algorithm = SINGLE §3.5, run independently per role (`s(role, list)`, `h8/n.java:41-118`), and
only for a role whose sector 0 is **not** erased (`h8/n.java:210-215`). Role 0 list = agent map,
role 1 = partner map. Request `{storageType, role, addrLE32, lenLE32}` (`a8/a.java:19-31`).
Pending key `hex(addr)+hex(role)`. Response: rx[8] role, rx[9..12] addr, SHA-256 rx[17..48]
(`h8/n.java:266-299`).

Per-role verdict `t(role)` (`h8/n.java:120-186`): identical rules to SINGLE (no tail command
⇒ `Erase_stages`; all digests equal and tail == last sector ⇒ `All_stages`; all equal
otherwise ⇒ `Erase_stages`; else `None`). **A role that was not compared at all (sector 0
already erased) yields `Erase_stages`** (no tail entry, `h8/n.java:132-135`). Matching sectors
get `needsProgram = false`.

Combined (`h8/n.java:230-263`). `agent = t(0)`, `client = t(1)`; `mgr.b0(client)` stores
the client verdict for later (`d8/d.java:874-876`):

| agent | client | stage SKIP_TYPE |
|---|---|---|
| `All_stages` | any | `All_stages` |
| `Erase_stages` | `All_stages` or `Erase_stages` | `Erase_stages` |
| other | `All_stages` or `Erase_stages` | `Client_Erase_stages` |
| other | other | **`Sinlge_StateUpdate_stages`** (the default) |

If `client == All_stages`, progress is attributed to `PARTNER` from here on
(`h8/n.java:255-260`).

### 5.5 0x1C01 per role and its skip (`g8/d.java:44-73`)

Response = SINGLE §3.10 (rx[6] status, rx[7] count, rx[8] recipient, rx[9] storageType).
`isCompleted()` = "any reply" (`g8/d.java:38-41`). TWS additions:

| stage | reply | SKIP_TYPE set | effect |
|---|---|---|---|
| role 0 | status 0 and `mgr.o()` (client verdict) ≠ All_stages | `WritePartnerStateCheckIntegrity_stages` | drops #17 and #18 |
| role 0 | status 0 and client == All_stages | `None` | #16, #17, #18 all run |
| any | status ≠ 0, recipient byte rx[8] == 1 | WritePartner… | tolerated (packet marked) |
| any | status ≠ 0, rx[8] ≠ 1 | WritePartner… + **error** | abort, cancel reason 1 |

(`d8/d.java:1287-1290`: `dVar` and `dVar2` both register #17/#18 under that skip type.)

### 5.6 Skip graph (registered in `y0()`, `d8/d.java:1287-1327`)

Removed stages per reported SKIP_TYPE (numbers from §5.1):

| reporter | SKIP_TYPE | removed |
|---|---|---|
| 4 (0x0433) | Compare_stages | 5, 6, 8, 11 |
| 4 | CompareErase_stages | 5, 7, 8, 9, 10, 11, 12 |
| 5 (0x0431) | Erase_stages | 7, 8, 9, 10, 11, 12 |
| 5 | Client_Erase_stages | 7, 9, 12 |
| 5 | All_stages | 7, 8, 9, 10, 11, 12, 13, 14 |
| 5 | **Sinlge_StateUpdate_stages** | **6, 8, 11** |
| 15 / 17 (0x1C01) | WritePartnerStateCheckIntegrity_stages | 17, 18 |

The manager applies these via `U()` (`d8/d.java:826-840`, switch at `:299-323`). Case
`Sinlge_StateUpdate_stages` is applied only if its list is non-null (`:317-321`).
`All_stages` from a stage that registered nothing would abort with "Interrupted: all
partitions are the same" (`:300-306`), but #5 registers a list, so this does not happen here.

**Read this carefully:** with a fresh image on both buds, #5 reports the default
`Sinlge_StateUpdate_stages`. That **removes the single-device 0x1C0A and the single 0x1C06
0x0300/0x0301 writes**, so pass 1 uses 0x1C10 plus the TWS 0x1C13 state writes. With
`Erase_stages`/`Client_Erase_stages` the reverse happens: 0x1C0A and the single 0x1C06 writes
stay, and 0x1C10 plus the TWS 0x0300/0x0301 writes go.

---

## 6. Erase and write

### 6.1 0x0432 dual-target erase (`h8/k.java`, `c8/j.java`)

Builder (`c8/j.java:25-43`):

```
payload (18 B)      frame len field = 0x0014, frame = 24 B
off  size  field
0    1     agent storageType   (mgr.u())
1    4     agent erase length  LE32 = 00 10 00 00 (4096) or 00 00 00 00
5    4     agent sector addr   LE32              or 00 00 00 00
9    1     partner storageType (mgr.u())
10   4     partner erase length LE32 = 00 10 00 00 or 00 00 00 00
14   4     partner sector addr LE32              or 00 00 00 00
```

`l(agentAddr)` (ack/locker key), `m(partnerAddr)` (`c8/j.java:41-42`).

Pairing (`h8/k.java:25-89`). jadx rendered this loop badly (as an infinite `while(true)`), so
this is a reconstruction. What the code clearly does: two iterators, over the agent map and over the partner
map (`:32-33`, ascending address). Each command takes the **next agent sector with
`needsProgram && !erased`** and the **next partner sector with the same predicate**
(`:47-53, 57-64`). A side that has run out is sent as zero length and zero address (`bArr4`, `:34, 68-75`).
Commands continue until both iterators are exhausted. Pending key
`hex(agentAddr) + hex(partnerAddr)` (`:78, 86`). **UNCONFIRMED:** exact behaviour when only one
side has a sector (the rendered branches at `:67-86` look like they could double-queue the
same pair). Implement "one command per pair, zero-fill the absent side".

Response (`h8/k.java:116-138`), type **0x5D**, rx[6] must be 0:

```
rx[6]       status
rx[7]       agent storageType     (logged)
rx[8..11]   agent length          (logged)
rx[12..15]  agent address   LE32  <- key part 1
rx[16]      UNCONFIRMED (by symmetry: partner storageType)
rx[17..20]  UNCONFIRMED (by symmetry: partner length)
rx[21..24]  partner address LE32  <- key part 2
```

Ordering: low address first (LinkedHashMap filled ascending, `h8/m.java:74-85`).
Progress denominator `f43946g0` = number of commands (`h8/k.java:37-40`).

### 6.2 0x0402 write: agent only, never relayed (`h8/l.java`)

```java
// h8/l.java:27
for (stage.a.b bVar : (this.f21560b.k() ? this.f21560b.f43934a0 : this.f21560b.f43936b0).values()) {
    if (bVar.f21584e) { … 256-byte pages, skip all-0xFF, CRC-8, b8.d(storageType, 1, page) … }
```

* Only the **agent** map, only sectors with `needsProgram`. **There is no role byte and no
  partner write command.** Byte layout, CRC-8, 0xFF skip, one page per command, and ack parsing
  (rx[8] count, rx[9+4i] addresses, key `hex(addr)`) are identical to SINGLE §3.9
  (`h8/l.java:28-66, 105-129`).
* Response type: **default 0x5B** (`h8/l.java` never sets `f21569k`; base `stage/a.java:177-179`).
* Pacing/window: identical to SINGLE §6.4–6.6, because the manager state is shared. Background ⇒
  `A=true` (long-packet), `B=3` commands per packet, 200 ms (`d8/b.java:620-622`), overridable
  by 0x1C1C (`d8/d.java:815-820`), windowed re-send `b()+3 < packetIndex`
  (`stage/a.java:386-400`). This covers **every** stage in the TWS flow (0x0433 role pairs, 0x0432,
  0x0402 …), because `getData()` uses `k()` whenever `G()` (`stage/a.java:248`).
* Since nothing is relayed, "outstanding window rules for relayed writes" do not exist.
  The partner receives its image in pass 2, as the agent (§7).

---

## 7. The two-pass flow, RHO, commit

### 7.1 Pass 1 (connected bud = X, both buds on old firmware)

Query → states e.g. `FFFF/FFFF` → `A()` → `StartFota` → `y0()`:

| # | sent? | why |
|---|---|---|
| 1 0x1C14, 2 0x1C08 `03 00`, 3 0x1C1C | yes | |
| 4 0x0433 ×2/region | yes | typically `None` |
| 5 0x0431 per role | yes | both roles `None` ⇒ `Sinlge_StateUpdate_stages` |
| 6 0x1C0A, 8 0x1C06 0300, 11 0x1C06 0301 | **no** | Sinlge_StateUpdate |
| 7 0x1C10, 9 0x1C13 0300/0300 | yes | |
| 10 0x0432 | yes | erases agent **and** partner sectors |
| 12 0x1C13 0301/0301, 13 0x1C06 0310 | yes | |
| 14 0x0402 | yes | **agent (X) sectors only** |
| 15 0x1C01 role 0 | yes | client verdict ≠ All ⇒ WritePartner… skip |
| 16 0x1C06 0311 | yes | agent state 0x0311 |
| 17, 18 | **no** | skipped |
| 19 0x1C12 | yes | expected agent 0x0311, partner ≠ 0x0311 (likely 0x0301) |

`A()`: agent 0x0311, partner ≠ 0x0311, `I == StartFota` ⇒ **RoleSwitch** (`d8/b.java:286-289`).

### 7.2 RHO (`d8/d.java:958-971`, `h8/a.java`)

`l()`: `D = true` (doing RHO), `M = L` (remember agentIsRight), `Q++`. If `Q > 3` ⇒
`RHO_FAIL`, else queue `h8.a`:

```
15 5A 02 00 D7 0C          0x0CD7 RoleSwitch, no payload (c8/h.java:7)
resp type 0x5D, rx[6] must be 0, else RHO_FAIL   (h8/a.java:10, 25-30)
timeout 15000 ms           (h8/a.java:11)
```

Completion comes by **either** of two paths. Both are implemented, and which one XM6 uses is
**UNCONFIRMED** (Sony's library type for inquired type 0x04 is
`MTK_TRANSFER_WO_DISCONNECTION`, `DeviceCapabilityTableset2Builder.java:449-452`, which
suggests the link survives):

1. **Link survives:** a 0x0900 notify, type 0x5A, `LE16 rx[6..7] == 0x0014`, rx[8] result
   (0 = OK), rx[9] agentChannel (`d8/d.java:617-633`). It is checked **before** the 0x15 RX
   filter (`d8/d.java:203` vs `:236`). With `D` set: `D=false`, `w0()` → sleep 100 ms, clear
   queue, `T()` → the §3 query again (`d8/d.java:203-215, 376-390, 716-718`). Result ≠ 0 ⇒
   `R()` → `RHO_FAIL` + cancel (`d8/d.java:797-813`).
2. **Link drops:** `y()` with `D` ⇒ `P()` (`onRhoNotification`; Sony sets `f69555h` so it
   ignores the MDR disconnect, `pl/d.java:158-166, 249-252`) and `f43941e.t()` reconnects to
   the **same BD address** (`d8/d.java:1255-1257`). `onHostInitialized` with `D` ⇒ `Q()`,
   `w0()` → query (`d8/d.java:1397-1418`).

Also: 0x0CD4 `Z()` during RHO, if agentIsRight flipped relative to `M`, sets `D=false` and
`Q()` (`d8/d.java:861-868`). RHO response timeout: `Q ≤ 3` ⇒ `w0()` re-query, else `RHO_FAIL`
+ cancel reason 2 (`d8/d.java:557-569`).

`i0(false)` in `Q0` (`d8/b.java:506`) resets byte0 to 0x05 for the post-RHO query.

### 7.3 Pass 2 (connected bud now Y = old partner)

Query: agent (Y) state ≠ 0x0311 ⇒ `O0(StartFota)` (allowed because `I` is `RoleSwitch`) ⇒ `y0()`
again, **re-sending 0x1C14 and 0x1C08 `03 00`**.

Expected with a pass-1 dual erase: #4 agent (Y) sectors all erased, partner (X) not ⇒ `None`.
#5 agent sector 0 erased ⇒ `t(0) = Erase_stages`, and partner (X) matches everywhere ⇒
`t(1) = All_stages` ⇒ **`Erase_stages`**, progress role → PARTNER.

| # | sent? |
|---|---|
| 6 0x1C0A | **yes** |
| 7, 8, 9, 10, 11, 12 | no (Erase_stages) |
| 13 0x1C06 0310, 14 0x0402 | yes, **Y's sectors** |
| 15 0x1C01 role 0 | yes; client == All ⇒ `None` |
| 16 0x1C06 0311, 17 0x1C01 role 1, 18 0x1C13 0311/0311 | yes |
| 19 0x1C12 | expect 0x0311 / 0x0311 |

`A()`: both 0x0311, `I == StartFota` ⇒ `TwsCommit` action ⇒ `onProgressChanged(100, PARTNER)`,
`onTransferCompleted()`, ping every 9 s (`d8/b.java:80-92, 239-258`). Ping in TWS mode:
**`15 5A 04 00 1B 1C 01 01`** (role 1 when `f43955l`, `d8/b.java:230-231`; `h8/c.java:33-41`),
resp 0x5D, more than 3 misses ⇒ `PING_FAIL` (`d8/b.java:218-224`).

If Y's sectors had in fact been written by the agent during pass 1 (the internal relay
hypothesis), #5 would give `All_stages` for Y and pass 2 would shrink to
0x1C0A, 0x1C01×2, 0x1C06 0311, 0x1C13 0311, 0x1C12. **Implement the full skip graph and
you are correct in both cases.**

### 7.4 States

| value | written by | meaning |
|---|---|---|
| 0x0300 | 0x1C06 (single) / 0x1C13 (both) | erase started |
| 0x0301 | 0x1C06 / 0x1C13 | erase done |
| 0x0310 | 0x1C06 | write started (agent) |
| 0x0311 | 0x1C06 (agent), 0x1C13 (both) | image verified, commit-ready |
| 0x0101 | device | post-commit "done" (checked only on the commit path, `d8/b.java:477-481`) |

Success before commit = **0x1C12 reports 0x0311 for both** while acting `StartFota`.

### 7.5 Commit (`d8/b.java:653-677`, `d8/d.java:1220-1231`, `h8/b.java`)

Host-triggered: Sony unregisters its listener and then calls `startCommitProcess()`
(`pl/d.java:327-334`). `a1()`: stop ping and timers, `Q = 0`, `f43955l` ⇒ `x0()`:

```
15 5A 02 00 11 1C          0x1C11 TwsCommit, no payload (c8/c.java:7)
resp type 0x5A, rx[6] ≠ 0 ⇒ COMMIT_FAIL    (h8/b.java:10, 25-30)
timeout 15000 ms           (h8/b.java:11)
retry: RetryTask, Q > 3 ⇒ COMMIT_FAIL + cancel reason 2  (d8/d.java:545-556)
```

Byte0 is 0x15 in both commit routes (same session, or reconnect, since the reconnect route
re-runs 0x1C08; see below). **Success = SPP disconnect while `E` (committing)** ⇒ `I()` →
`onCompleted()` (`d8/d.java:1245-1253`). `G` is never set true in `d8` (only cleared at
`d8/b.java:266, 602, 695`), so the library does **not** reconnect. The "installed" verdict
comes from Sony's Tandem/MDR side after the buds reboot (SINGLE §4.4). The `0x0101/0x0101 ⇒
onDeviceRebooted` branch (`d8/b.java:462-481`) only runs if a TWS query happens while
committing. **Recommendation:** accept 0x5A status 0, wait up to ~15 s for the socket to
drop, then reconnect on MDR and compare versions.

Reconnect-for-commit (`AirohaFotaAdapterSony.java:503-542`): if the SPP link is gone,
`gIsCommiting = true` and the whole `start()` runs again. `Z0` resets `I = UNKNOWN`, so `A()`
with 0x0311/0x0311 takes the "not StartFota" row ⇒ **a full verify pass** (0x1C14, 0x1C08,
compare ⇒ `All_stages` ⇒ 0x1C0A, 0x1C01×2, 0x1C06/0x1C13 0x0311, 0x1C12) ⇒ `TwsCommit` ⇒
`onTransferCompleted` ⇒ 1000 ms sleep ⇒ commit (`FotaControl2833.java:345-360`).

Progress mapping (Sony, TWS): agent `p/2`, partner `p/2 + 50` (`pl/d.java:143-149`).

---

## 8. Cancel and failure

### 8.1 Host cancel 0x1C03

`f8.c(dual=true, reason)` and `X(reason)` both send `{0x07, 0x03, reason}`
(`f8/c.java:27`, `d8/d.java:667-672`):

```
15 5A 05 00 03 1C 07 03 <reason>      reason 0 user, 1 stage error, 2 retry/commit/RHO fail
```

The 0x03 means "dual" (single device uses 0x01). **UNCONFIRMED:** whether 0x03 is a bitmask
(agent|partner) and what the constant 0x07 encodes. Resp 0x5B, rx[6] = 0, **3000 ms** timeout
(`f8/c.java:20`). The user cancel is delayed 2000 ms and never sent while committing
(`d8/d.java:913-944`).

### 8.2 Device cancel 0x1C03 (type 0x5A), including partner loss

Same as SINGLE §5.2 (`d8/d.java:584-614`): rx[6] sender, rx[7] recipient, rx[8] reason.
The library answers `<f> 5B 03 00 03 1C 00` and `<f> 5D 05 00 03 1C <s> <r> <reason>`.
Reason **3 ⇒ `Device_Cancelled_PartnerLoss`** (`d8/d.java:606-607`) →
`FotaCanceled_ByDevice_PartnerLoss` (`FotaControl2833.java:260-261`) → Sony
`MtkFotaError.PARTNER_LOSS` (`pl/d.java:99-101`). Sony then picks L vs R loss from the MDR
connection status (spec-update-orchestration.md). Reason 4 = NOT_ALLOWED (battery etc.).
The sender/recipient values for a TWS pair are **UNCONFIRMED**.

### 8.3 Other failures

* Unexpected 0x0900 while not doing RHO ⇒ `UNEXPECTED_RHO` + cancel (`d8/d.java:216-232`).
* Link drop outside commit/RHO while running ⇒ `ABNORMALLY_DISCONNECTED` (`d8/d.java:1258-1262`,
  `d8/b.java:402-405`).
* Stage error ⇒ `N(err)` + cancel reason 1 (`d8/d.java:272-280`). Packet retries exhausted (3)
  ⇒ `CMD_RETRY_FAIL` + reason 2 (`d8/d.java:652-660`).
* Sony side: during TWS RHO the MDR-disconnect watchdog is suppressed only after
  `onRhoNotification` (`pl/d.java:249-252`). That is fired only on the link-drop RHO path.

---

## 9. Differences from the single MT2833 flow (besides the partner)

| aspect | single (`p0`) | TWS (`y0`) |
|---|---|---|
| query | 0x0CD6(0), 0x1C07(0), 0x1C04 | 0x0CD4, 0x0CD6(0,1), 0x1C07(0,1), **0x1C12** |
| partition | 0x1C00 `{00}`, 0x5B | **0x1C14** `{00}`, **0x5D**, 26-byte reply |
| FOTA start | `01 <mode>` | **`03 <mode>`**; mode from `e8.a` (same Background 0x00) |
| transaction | 0x1C0A | 0x1C10 (or 0x1C0A, per skip graph) |
| state write | 0x1C06, 0x02xx | 0x1C06 and/or **0x1C13 (4 bytes)**, **0x03xx** |
| erase | 0x0404 per sector (9 B) | **0x0432** per sector pair (18 B) |
| final state | 0x1C04 == 0x0211 | 0x1C12 == 0x0311/0x0311 |
| extra | — | RHO 0x0CD7 + second pass |
| commit | 0x1C02 `{00}`, 9 s | **0x1C11** no payload, **15 s** |
| ping role | 0 | **1** |
| cancel byte 1 | 0x01 | **0x03** |

Session flag 0x15: same rule as SINGLE (set at 0x1C08, cleared by every query phase, so
also after RHO). There are no relayed frames, so there is nothing flag-specific to relayed
frames. 0x0900 bypasses the RX flag filter. Busy bit 0x80: inert (adaptive forced false,
`FotaControl2833.java:206, 222`).

---

## 10. Corrections

### 10.1 Line-number drift (new decompile vs. citations in SINGLE / spec-airoha-fota.md)

`d8/b.java`, `stage/a.java`, `f8/*`, `g8/*`, `h8/*` are unchanged. Moved:

| symbol | old cite | new |
|---|---|---|
| `p0()` single queue | `d8/d.java:1131-1173` | `1064-1106` |
| `t0()` startSingleCommit | `1230-1241` | `1163-1174` |
| `x0()` startTwsCommit | `1287-1298` | `1220-1231` |
| `y()` host disconnected | `1300-1321` | `1233-1263` |
| `y0()` TWS queue | `1332-1412` | `1265-1345` |
| `l()` doRoleSwitch | `1025-1038` | `958-971` |
| RetryTask 0x1C1C skip / commit / RHO | `586-625` | `530-569` |
| `D()` device cancel / `E()` 0x0900 | `645-674` / `678-694` | `584-614` / `617-633` |
| `W()` / `X()` | `713-721` / `728-733` | `652-660` / `667-672` |
| `S()` / `h0()` / `j()` | `882-887` / `965-970` / `980-1011` | `815-820` / `898-903` / `913-944` |
| `z7.a.e/f/k` | `z7/a.java:83-91/106-108/126-128` | `79-100 / 102-104 / 122-124` |
| FotaControl2833 `Y0`/`Z0` calls | `:358 / :364` | `:222 / :206` |
| FotaControl2833 `onTransferCompleted` / `onCompleted` | `:220-235 / :117-123` | `:345-360 / :246-252` |
| adapter `startCommitProcess` / chip table | `:868-907 / :217-227` | `:503-542 / :213-223` |
| `pl/d` `start` / `z()` / `c()` | `:335 / :307-312 / :339-346` | `:323 / :295-300 / :327-334` |

### 10.2 Content corrections

1. **spec-airoha-fota.md §3.9 and spec-update-orchestration.md "TWS partner bud handling"**
   say the agent relays the image to the partner internally. The code does not show that.
   The host writes only the agent (`h8/l.java:27`), never verifies or state-marks the partner in
   pass 1 unless it already matched (`g8/d.java:56-62`), and so **always** triggers
   RHO plus a second pass that writes the other bud as the new agent (`d8/b.java:286-289`).
   Internal forwarding is **UNCONFIRMED** and at most an optimisation.
2. **spec-airoha-fota.md §3.9** "If the partner is current but the agent is not (or vice
   versa), the library performs an RHO" is wrong. RHO happens **only** when the agent is
   0x0311, the partner is not, and the acting action was StartFota. An agent that is not
   0x0311 always gets StartFota (§1.3).
3. **spec-airoha-fota.md §3.8** lists all 19 stages as if all are sent. In the normal case
   0x1C0A and the single 0x1C06 0x0300/0x0301 are **removed** by
   `Sinlge_StateUpdate_stages` (`h8/n.java:243`, `d8/d.java:1324-1327`), and #17/#18 are removed
   in pass 1 by `WritePartnerStateCheckIntegrity_stages`. The skip graph (§5.6) is mandatory.
4. **spec-airoha-fota.md §2.1/§2.2** imply the relay wrapper is how the FOTA libraries address
   the partner. It is AB1562 (`libfota1562`, `p7/*`) only. MT28xx has no 0x0D01.
5. **spec-airoha-fota.md §3.8** "0x0900 module `BE16(rx[7], rx[6])`": the field is **LE16
   at rx[6..7]** (the `x8.d.f(hi, lo)` argument-order issue, SINGLE §9.5).
6. **fw-update-design.md** scope ("single device only, `tws == false`") excludes the
   WF-1000XM6: it reports `tws = ENABLE` (§1.1). Sony runs `y0()` on it, not `p0()`. The
   current Kotlin `AirohaFotaSession` (single path: 0x1C00/0x0404/0x1C04/0x1C02) is **not**
   what Sony does on this device. Whether the single path would even work on a TWS pair
   (it would flash only the connected bud) is **UNCONFIRMED and risky**.
7. SINGLE §2.1 note stands for TWS: the battery reply gates nothing in the library. Sony's
   own gate is both-buds `>` threshold (`MdrUpdateStatusChecker.java:72-75`).

---

## 11. Open questions / what would confirm them

1. **0x1C14 reply layout on XM6** (rx[7], rx[17], rx[18..25]). Probe `05 5A 03 00 14 1C 00`
   (read-only query).
2. **0x0CD4 / 0x1C12 replies on XM6.** Probe both (read-only). 0x1C12 tells you the resume
   state, which 0x1C04 = 0xFFFF did not.
3. **RHO transport behaviour on XM6**: 0x0900 notify over a surviving link, or SPP drop +
   reconnect to the same address. Needs an HCI capture of a Sony-app update, or a careful
   first live run.
4. **Whether pass 1 also updates the partner** (internal forwarding). Visible in the pass-2
   0x0431 role-0 digests of a real run.
5. **0x0432 response rx[16..20]** and the exact pairing when only one side has sectors left
   (the jadx rendering of `h8/k.i()` is damaged). Needs smali or a capture.
6. **0x1C03 bytes 0x07 / 0x03** semantics, and sender/recipient values in device-initiated
   cancels for a pair.
7. **Post-commit reboot timing** for both buds and whether 0x0101/0x0101 is ever observable
   over SPP.
