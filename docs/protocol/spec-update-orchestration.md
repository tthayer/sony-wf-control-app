# Sony Sound Connect (com.sony.songpal.mdr) — Firmware Update Orchestration

Scope: update controllers, preconditions, state machines, and the MDR/Tandem commands
sent around a transfer. Byte-level Airoha RACE framing and Tandem FOTA wire framing are
explicitly **out of scope** here (covered by other analysts) except where a byte value
was trivially visible in an enum and directly answers "what command/value is sent."

All paths verified against jadx-decompiled sources under
`.../scratchpad/sony/jadx/sources`. Obfuscated single-letter classes are cited by
package + filename + line.

---

## 1. SELECTION — which update mechanism a device uses

### Source of truth
The app never hardcodes "this model uses CSR" — it derives an `UpdateCapability` object
from the **Tandem device-capability table** (the capability/`FunctionType` list the
headset returns during initial connection — "tableset1" for older v1-protocol devices,
"tableset2" for v2-protocol devices). The mapping logic lives in:

- `com/sony/songpal/mdr/j2objc/tandem/UpdateCapability.java` (the model/enum itself)
- `com/sony/songpal/mdr/j2objc/devicecapability/tableset1/DeviceCapabilityTableset1Builder.java:240-305` (`b(...)`, old protocol)
- `com/sony/songpal/mdr/j2objc/devicecapability/tableset2/DeviceCapabilityTableset2Builder.java:449-511` (`d(...)`, `e(...)`, `f/g/h/i/j/k/l(...)`, v2 protocol)

### `UpdateCapability.LibraryType` enum (`UpdateCapability.java:42-49`)
```
CSR
MTK_RHO_W_DISCONNECTION        // MTK/Airoha, headset disconnects BT during transfer
MTK_TRANSFER_WO_DISCONNECTION  // MTK/Airoha, transfer happens without dropping the link
TANDEM                         // pure Tandem-protocol FOTA (no Airoha library at all)
USING_MC_APP                   // delegates to a separate "MC" companion app
NOT_SUPPORTED
```

### `UpdateCapability.Target` enum (`UpdateCapability.java:52-56`)
```
FW
VOICE_GUIDANCE
SONY_VOICE_ASSISTANT
```
Each target (firmware, voice-guidance data, Sony Voice Assistant data) gets its own
`UpdateCapability`/`LibraryType`/controller instance — a device can use different update
mechanisms for FW vs. VG vs. SVA data.

### Decision logic (v2 protocol, tableset2)
`DeviceCapabilityTableset2Builder.d(list)` (line 449):
```java
if (f(list) || l(list) || i(list)) return MTK_TRANSFER_WO_DISCONNECTION;
if (k(list)) return TANDEM;
return g(list) ? USING_MC_APP : NOT_SUPPORTED;
```
where (lines 482-511):
- `f()` → `FunctionType` contains `FW_UPDATE_MTK_TRANSFER_WITHOUT_DISCONNECTION[_AUTO_UPDATE]`, `FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE`, or `FW_UPDATE_MTK_TRANSFER_WITH_AC_CONNECTION_CHECK`
- `g()` → contains `FW_UPDATE_USING_MC_APP`
- `i()` → contains `SONY_VOICE_ASSISTANT`
- `k()` → contains `FW_UPDATE_TANDEM`
- `l()` → contains any `VOICE_GUIDANCE_SETTING_MTK_TRANSFER_WITHOUT_DISCONNECTION_*` or `VOICE_GUIDANCE_SETTING_*`

These `FunctionType` byte codes are wire values (`com.sony.songpal.tandemfamily.message.mdr.v2.table1.updt.param.UpdtInquiredType`, file `com/sony/songpal/tandemfamily/message/mdr/v2/table1/updt/param/UpdtInquiredType.java:8-17`):
```
FW_UPDATE_MTK_TRANSFER_WO_DISCONNECTION              = 0x02
FW_UPDATE_MTK_TRANSFER_WO_DISCONNECTION_AUTO_UPDATE  = 0x04
FW_UPDATE_MTK_TRANSFER_WITH_REPAIR_MODE              = 0x05
FW_UPDATE_MTK_TRANSFER_WITH_AC_CONNECTION_CHECK      = 0x06
FW_UPDATE_USING_MC_APP                               = 0x07
FW_UPDATE_TANDEM_PART1..4                            = 0x10,0x11,0x12,0x13
OUT_OF_RANGE                                         = 0xFF
```
So: **the source of truth is a specific bit/byte in the Tandem capability-inquiry reply
for the UPDT feature** (byte-level framing owned by the other analyst; this is the
semantic label of that byte).

### Decision logic (v1 protocol, tableset1)
`DeviceCapabilityTableset1Builder.b(...)` (lines 240-305) instead reads a
`com.sony.songpal.tandemfamily.mdr.param.UpdateMethod` byte straight off the wire:
```
TANDEM_METHOD                       = 0x00
CSR_METHOD                          = 0x20 (BSON.NUMBER_INT const-reuse)   -- see note
CSR_RESUMABLE_METHOD                = ...
CSR_TWS_METHOD / CSR_TWS_RESUMABLE_METHOD
MTK_METHOD                          = 0x20
MTK_RESUMABLE_METHOD                = 0x21
MTK_TWS_RESUMABLE_METHOD            = 0x23
MTK_BACKGROUND_RESUMABLE_METHOD     = 0x25
MTK_TWS_BACKGROUND_RESUMABLE_METHOD = 0x27
```
(`com/sony/songpal/tandemfamily/mdr/param/UpdateMethod.java:8-17`; exact CSR byte values
are jadx-obfuscated via BSON-constant reuse — treat CSR bytes as unconfirmed, MTK bytes
0x20/0x21/0x23/0x25/0x27 are plain literals and confirmed). The top nibble selects
`Module` (`MTK=0x20`, `CSR=0x20`... actually `Module.getModuleOf(byteCode & 0xF0)`,
`MTK=(byte)32`, `CSR=BSON.NUMBER_INT`, `TANDEM=0`, `UNKNOWN=0xF0`), and 3 bit flags in the
low nibble: `FLAG_RESUMABLE=1`, `FLAG_TWS=2`, `FLAG_BACKGROUND_TRANSFER=4`
(`UpdateMethod.java:19-21,69-79`). `DeviceCapabilityTableset1Builder.b()` maps
`Module.MTK` → `LibraryType.MTK_RHO_W_DISCONNECTION` and `Module.CSR` → `LibraryType.CSR`
(lines 268-276).

### BLE "Blanc" mechanism (fourth path, out-of-band)
A completely separate BLE-GATT FOTA transport exists for BLE-capable accessories:
`ServiceUuid.BLANC_FOTA_SERVICE` with characteristics `BLANC_FOTA_DATA_SINK`,
`BLANC_FOTA_READY_TRANSFER`, `BLANC_FOTA_DATA_SIZE`
(`com/sony/songpal/ble/client/CharacteristicUuid.java:112-114,284-290`), plus a parallel
`SSS_FOTA_*` triplet. `BlancFotaDataSize.DataSize` enum
(`com/sony/songpal/ble/client/characteristic/BlancFotaDataSize.java:16-21`) declares
chunk sizes 4KB/40KB/400KB/1MB/2MB. This is not wired into `UpdateCapability`/
`LibraryType` at all — it's a distinct BLE-only code path (likely used by non-headphone
BLE accessories); not explored further (out of scope for headphone FOTA).

### `CsrUpdateState` (CSR/GAIA path, for completeness)
`com/sony/songpal/mdr/application/update/csr/CsrUpdateState.java:5-12`:
```
INIT, IDLE, IN_DOWNLOAD, IN_SENDING, IN_INSTALLING, UPDATE_COMPLETED, FINALIZING
```
Each carries `(isCancelable, isRunning, isOtherFunctionOperable)` booleans. Driven by
`com/sony/songpal/mdr/application/update/csr/CsrUpdateController.java` (not read in
depth — GAIA/CSR is legacy silicon, low priority for a modern third-party client).

---

## 2. PRECONDITIONS

### Condition-code enums
Two parallel condition-code enums exist — one for the MTK/Airoha path, one for the pure
Tandem-FOTA path:

`MtkUpdateConditionErrorCode` (`com/sony/songpal/mdr/j2objc/application/update/mtk/MtkUpdateConditionErrorCode.java:6-26`):
```
CONFIRM_MDR_BATTERY, CONFIRM_MOBILE_BATTERY, CONFIRM_LEFT_CONNECTION,
CONFIRM_RIGHT_CONNECTION, CONFIRM_NETWORK_CONNECTION, DOWNLOAD_FAILED, DATA_ERROR,
TRANSFER_ERROR, INSTALL_ERROR, UPDATE_COMPLETION, UPDATE_RECOMMENDATION,
ABORT_CONFIRMATION, UPDATE_CONFIRM_COMPLETION, CONFIRM_AC_POWER_SUPPLY_CONNECTION,
CONFIRM_GROUPING_CONDITION, CONFIRM_PARTY_CONNECT_CONDITION, NEED_APP_UPDATE,
CONFIRM_GATT_OFF_IN_UPDATING, NEED_DISCONNECT_OTHER_CONNECTING_DEVICE,
CONFIRM_PARTY_CHAIN_CONDITION, NO_PROBLEM
```
`TandemUpdateConditionErrorCode` (`.../update/tandem/TandemUpdateConditionErrorCode.java:6-18`):
```
DEVICE_BATTERY_LOW, MOBILE_BATTERY_LOW, NETWORK_UNAVAILABLE,
GATT_DISCONNECTION_REQUIRED, MULTIPOINT_DISCONNECTION_REQUIRED, APP_UPDATE_REQUIRED,
LEFT_CONNECTION_REQUIRED, RIGHT_CONNECTION_REQUIRED,
CONFIRM_AC_POWER_SUPPLY_CONNECTION, CONFIRM_GROUPING_CONDITION,
CONFIRM_PARTY_CONNECT_CONDITION, CONFIRM_PARTY_CHAIN_CONDITION, NO_PROBLEM
```
`MtkFwUpdateStatusInfo` (`com/sony/songpal/mdr/application/update/mtk/MtkFwUpdateStatusInfo.java:24-78`)
1:1-maps every `MtkUpdateConditionErrorCode` to a dialog id, a message string resource,
and an `actionlog.param.Dialog` enum value — this is the exact table a third-party UI
should mirror for user-facing copy (dialogs: `FW_MDR_BATTERY_POWER`,
`FW_MOBILE_BATTERY_POWER`, `FW_MDR_L_CONNECTION_ERROR`, `FW_MDR_R_CONNECTION_ERROR`,
`FW_NETWORK_ERROR`, `FW_DOWNLOAD_ERROR`, `FW_DATA_ERROR`, `FW_TRANSFER_ERROR`,
`FW_INSTALL_ERROR`, `FW_UPDATE_COMPLETION`, `FW_UPDATE_RECOMMENDATION`,
`FW_ABORT_CONFIRMATION`, `FW_UPDATE_CONFIRM_COMPLETION`, `FW_STEREO_PAIR`,
`FW_PARTY_CONNECT`, `FW_PARTY_CHAIN`, `APP_UPDATE_CONFIRMATION_BEFORE_FW_UPDATE`,
`FOTA_IN_GATT_ON_CONFIRMATION`).

### Battery thresholds — exact rule, values are device-supplied (not hardcoded %)
`MdrUpdateStatusChecker` (`com/sony/songpal/mdr/j2objc/application/update/MdrUpdateStatusChecker.java`):
- Single-battery device: **battery % must be `>` threshold** (strict, not `>=`) — line 70/84/90: `bVar.k().b() > this.f37961d`.
- TWS (dual-battery) device: **both L and R battery % must each be `>` threshold** — line 74-75: `iB > i11 && iB2 > i11`.
- `e()`/`f()`/`g()` are three variants of this check (general "has enough battery",
  and split L-only/R-only checks), all using the same strict `>` comparator.
- **The threshold itself is not a fixed constant.** It comes from the Tandem
  capability table entry for FW-update (class `com/sony/songpal/mdr/j2objc/devicecapability/tableset2/u.java:11-43`):
  - `f38724a` = TxPower (e.g. builder passes literal `-60` at
    `DeviceCapabilityTableset2Builder.java:1367`, but this is a BLE tx-power hint, not battery)
  - `f38725b` ("Battery Power Threshold") → accessor `a()` — pre-flight battery gate (`CONFIRM_MDR_BATTERY`)
  - `f38726c` ("Battery Power Threshold for Interrupt") → accessor `b()` — the threshold that, if the
    battery drops below it **during an active TRANSFERRING phase**, aborts the update
    (`nu/o.java:463-468` `C()`: `if (state==TRANSFERRING) { J(); i0(ABORT_BATTERY_LOW); }`,
    wired to live battery-level callbacks in `nu/o.java:552-575` `k0(int threshold)`,
    `b0()` for single-battery, `c0()` for L/R battery — `c0()` additionally requires
    **both L and R report themselves as still connected** before evaluating the level,
    `nu/o.java:513-530`).
  - There is also a distinct `BatteryLevelThreshold` value class
    (`com/sony/songpal/mdr/j2objc/devicecapability/tableset2/k.java`) carrying an int
    0-100 plus the list of `FunctionType`s it gates — same "device tells the app the
    number" pattern, used elsewhere in the capability table (not just FW update).
  - **Practical implication for a third-party client:** don't hardcode a battery %;
    read the threshold the headset itself reports in its capability/UPDT reply, and
    apply strict `>` on both buds for TWS.
- `MtkFotaError.BATTERY_LOW` (`.../mtk/MtkFotaError.java:8`) is the corresponding
  device-reported abort reason surfaced by the Airoha library callback
  (`nu/o.java:301-304`) → `MtkUpdateState.ABORT_BATTERY_LOW`.
- `EnterFwUpdateMode` (Tandem-FOTA path) can also fail pre-flight with
  `TandemFotaResult.ERROR_NEED_POWER_CABLE_CONNECTED_AND_ENOUGH_BATTERY` (byte `0x06`)
  or `ERROR_TEMPERATURE_IS_TOO_HIGH` (byte `0x07`) — see §3.
- `CONFIRM_MOBILE_BATTERY` / `TandemUpdateConditionErrorCode.MOBILE_BATTERY_LOW` exist as
  condition codes but the phone-battery-percent check itself was **not located** in the
  files read (likely gated in an Activity via Android's `BatteryManager`, outside the
  explored controller classes) — flagged as an unknown below.

### Connection-type / topology requirements
- `MdrUpdateStatusChecker.ConnectionState` (`MdrUpdateStatusChecker.java:28-33`):
  `TWS_R_NOT_CONNECTED`, `TWS_L_NOT_CONNECTED`, `CONNECTED`, `UNKNOWN`. For TWS, **both
  buds must independently report connected** before an update can proceed —
  `CONFIRM_LEFT_CONNECTION` / `CONFIRM_RIGHT_CONNECTION` map straight to this.
- `CONFIRM_GATT_OFF_IN_UPDATING` / Tandem's `GATT_DISCONNECTION_REQUIRED`: BLE (GATT)
  must be turned off / disconnected before/while updating over Classic BT — the app
  prompts the user with dialog `FOTA_IN_GATT_ON_CONFIRMATION` to disable it first.
- Tandem's `MULTIPOINT_DISCONNECTION_REQUIRED` / Mtk's
  `NEED_DISCONNECT_OTHER_CONNECTING_DEVICE`: any second (multipoint) host device must be
  disconnected first.
- `CONFIRM_GROUPING_CONDITION` / `CONFIRM_PARTY_CONNECT_CONDITION` /
  `CONFIRM_PARTY_CHAIN_CONDITION`: stereo-pair/"party" (multi-speaker link) grouping must
  be undone before updating (dialogs `FW_STEREO_PAIR`, `FW_PARTY_CONNECT`,
  `FW_PARTY_CHAIN`, all sharing string `Fwupdate_UnGroup_Message[2]`).
- `CONFIRM_AC_POWER_SUPPLY_CONNECTION` / Tandem's own copy: some models (per
  `UpdateCapability.h()` "NeedsAcConnectionCheck" flag, set from
  `FW_UPDATE_MTK_TRANSFER_WITH_AC_CONNECTION_CHECK`, `UpdtInquiredType` byte `0x06`)
  require external AC power connected before update, not just battery.
- `az.b.i()` (`az/a.java:196-198`) distinguishes the two MTK sub-variants at runtime:
  `LibraryType.MTK_RHO_W_DISCONNECTION` → the transfer will disconnect Classic BT
  (device reboots into a bootloader-like mode mid-transfer); otherwise
  `MTK_TRANSFER_WO_DISCONNECTION` keeps the link up throughout.

### Network requirements
- Firmware **binary download** requires network connectivity, checked generically (not
  Wi-Fi-specific at the controller level) via `new v70.i().c()` — used identically in
  both the MTK controller (`nu/o.java:194-195`) and `AutoMagicDownloadTask` (line 202) to
  distinguish `ABORT_NETWORK_CONNECTION`/`NETWORK_UNAVAILABLE` from a generic download
  failure.
- A user-facing preference, `AutoDownloadSetting` (`ALWAYS` vs `ONLY_WIFI`), gates
  **automatic/background** firmware checks only
  (`com/sony/songpal/mdr/application/update/mtk/firmware/MtkFwUpdateSettingsPreference.java:21-24`,
  default `ALWAYS`); a manually-triggered update is not shown gated by this flag in the
  files read.
- `NEED_APP_UPDATE` / Tandem's `APP_UPDATE_REQUIRED`: if the headset's capability table
  indicates a firmware format newer than this app version understands, the app blocks
  and asks the user to update the Sound Connect app itself first (dialog
  `APP_UPDATE_CONFIRMATION_BEFORE_FW_UPDATE`).

### `MtkFotaError` (device-reported abort reasons during Airoha transfer)
`com/sony/songpal/mdr/j2objc/application/update/mtk/MtkFotaError.java:6-10`:
```
PARTNER_LOSS, DISCONNECTED, BATTERY_LOW, FotaCanceled_ByDevice_UnKnownReason, OTHER
```
Mapped in `nu/o.java:284-310` (`onFailed`):
- `PARTNER_LOSS` → `ABORT_PARTNER_R_LOSS` if `connectionstatus.b().a().b()` (right-bud-lost bit) else `ABORT_PARTNER_L_LOSS`; if no live connection-status object, falls back to `ABORT_TRANSFER_FAILED`.
- `BATTERY_LOW` → `ABORT_BATTERY_LOW`
- `FotaCanceled_ByDevice_UnKnownReason` → `ABORT_BY_DEVICE_UNKNOWN_REASON`
- `DISCONNECTED`, `OTHER` → `ABORT_TRANSFER_FAILED`

---

## 3. MDR/TANDEM PROTOCOL INTERACTIONS AROUND THE TRANSFER

Two structurally different command sets exist depending on which `LibraryType` was
selected in §1. **Only the `TANDEM` library type uses the "UPDT" Tandem-FOTA command set
below with confirmed wire byte values; the MTK/Airoha types use a different, generic
Tandem "general setting" toggle plus the separate Airoha RACE binary protocol (owned by
the other analyst).**

### 3a. Pure Tandem-FOTA path (`LibraryType.TANDEM`) — confirmed byte-level command set

Driver: `com/sony/songpal/mdr/j2objc/feature/fwupdate/tandem/core/m.java` (693 lines),
composed of helper classes `b` (enter/exit), `q` (transfer loop), `i` (install/execute)
in the same package, wrapping thin 20-second-timeout command objects in package `kx`
(`kx/b.java`=CancelTransfer, `kx/g.java`=ExitFwUpdateMode, `kx/i.java`=FinishTransfer,
plus `EnterFwUpdateMode.java`, `StartTransfer.java`, `Transfer.java`).

`TandemFotaCommand` byte codes (`com/sony/songpal/tandemfamily/message/mdr/v2/table1/updt/param/TandemFotaCommand.java:41-46`, confirmed literals):
```
ENTER_FW_UPDATE_MODE = 0x01     supported results: OK, ERROR_OTHER_THAN_SPECIFIC_ERROR,
                                  ERROR_ILLEGAL_STATE, ERROR_ILLEGAL_ARGUMENTS,
                                  ERROR_NEED_POWER_CABLE_CONNECTED_AND_ENOUGH_BATTERY,
                                  ERROR_TEMPERATURE_IS_TOO_HIGH
EXIT_FW_UPDATE_MODE  = 0x02     OK, ERROR_OTHER_THAN_SPECIFIC_ERROR, ERROR_ILLEGAL_STATE,
                                  ERROR_ILLEGAL_ARGUMENTS
START_TRANSFER       = 0x03     + ERROR_NO_NEED_OF_DATA_TRANSFER
FINISH_TRANSFER      = 0x04
CANCEL_TRANSFER      = 0x05
EXECUTE_FW_UPDATE    = 0x06     + ERROR_FIRMWARE_TRANSFER_INCOMPLETED
OUT_OF_RANGE         = 0xFF
```
`TandemFotaResult` byte codes (`.../updt/param/TandemFotaResult.java:9-17`):
```
OK=0x00, ERROR_OTHER_THAN_SPECIFIC_ERROR=0x01, ERROR_ILLEGAL_STATE=0x02,
ERROR_ILLEGAL_ARGUMENTS=0x03, ERROR_NO_NEED_OF_DATA_TRANSFER=0x04,
ERROR_FIRMWARE_TRANSFER_INCOMPLETED=0x05,
ERROR_NEED_POWER_CABLE_CONNECTED_AND_ENOUGH_BATTERY=0x06,
ERROR_TEMPERATURE_IS_TOO_HIGH=0x07, OUT_OF_RANGE=0xFF
```
`TandemFotaStatus` (async notification, byte codes `.../updt/param/TandemFotaStatus.java:6-11`):
```
INVALID=0x00, IDLE=0x01, NOT_READY=0x02, DATA_RECEIVING=0x03, UPDATING=0x04, OUT_OF_RANGE=0xFF
```
Other UPDT params seen: `MessageType{NO_USE=0,FW_UPDATE_COMPLETED=1}`,
`MacType{NONE=0,MD5=1,SHA1=2}`, `Topology{SINGLE_SPEAKER=0,TWS=1}`.

**Sequence actually driven by the app** (`m.java`):
1. **Before transfer:** `EnterFwUpdateMode` (`EnterFwUpdateMode.java:101-146`) — sends
   `UpdateRequestType.ENTER_FW_UPDATE_MODE`, waits (20 s) for *both* an
   `ENTER_FW_UPDATE_MODE`/`OK` reply **and** a subsequent async `TandemFotaStatus.IDLE`
   notification before declaring success. `NEED_CHARGE` /`BATTERY_HOT` results map to
   `FwUpdateCallbacks$ResultCode.AUDIO_DEVICE_NEED_CHARGE` /
   `AUDIO_DEVICE_BATTERY_HOT` and abort back to `FwUpdateState.INIT` (`m.java:414-438`).
2. **Per file in the firmware package**, for each `ix.c` file entry: `StartTransfer`
   (`StartTransfer.java`) — sends `START_TRANSFER` with `(fwVersion, fileIndex,
   fileCount, fileName, MacType, macBytes)`, 150 s timeout
   (`f39036a = Imgproc.COLOR_BGR2YUV_YVYU` = **150**, a jadx constant-substitution
   artifact — value confirmed against `org/opencv/imgproc/Imgproc.java:87`). Waits for
   `TandemFotaStatus.DATA_RECEIVING`; a reply of `ERROR_NO_NEED_OF_DATA_TRANSFER` means
   the device already has this file's data and the app treats it as already-transferred
   (skip straight to next file / `onCompleted`) — this doubles as the **resume**
   mechanism. On success the reply also carries `(maxPacketSize, offset)` — `offset` is
   where `Transfer` should resume writing from (`q.java:223-228`, `Transfer.java:76-136`
   `d()`: `skip(offset)` then streams `maxPacketSize`-byte chunks).
3. Then `Transfer` (`Transfer.java`) streams the raw firmware chunks (no separate Tandem
   command enum — raw `q20.f.a(offset, bytes)` data-send calls, byte-level framing out of
   scope); progress % = `(offset*100)/fileSize`, reported live.
4. Then `FinishTransfer` (`kx/i.java`, 20 s timeout) — sends `FINISH_TRANSFER`, confirms
   `OK`.
5. After all files: `EXECUTE_FW_UPDATE` (`com/sony/songpal/mdr/j2objc/feature/fwupdate/tandem/core/i.java:82-118`,
   via helper class `c`) — sends the fw version + full file-name list; reply carries
   `requiredTime` (seconds) which the app uses purely to drive a **simulated** progress
   bar (see §4/§5) and an install-timeout window.
6. **On cancel** (`m.java:606-634`, `o(kx.k)`): if `TRANSFERRING`, sends `CANCEL_TRANSFER`
   then `EXIT_FW_UPDATE_MODE`; if only `FIRMWARE_DOWNLOADING` (i.e. update mode was never
   entered / already exited), sends `EXIT_FW_UPDATE_MODE` directly.
7. **On device-initiated abort** (`m.java:367-397` `C(UpdateStatus)`): an unsolicited
   `IDLE`/`NOT_READY` status pushed by the headset while the app thinks it's
   downloading/transferring triggers `cancelFromAudioDevice()` (`m.java:489-495`), which
   sends `CANCEL_TRANSFER` then a bare "exit" callback (state → `ERROR_OCCURRED`,
   reason `CANCELED_FROM_AUDIO_DEVICE`).
8. **After success:** completion is signalled by an async
   `MessageType.FW_UPDATE_COMPLETED` notification while state is `INSTALLING`
   (`m.java:463-470` `K()`) → state → `INSTALL_COMPLETED`. No explicit
   "verify new version" read-back or `EXIT_FW_UPDATE_MODE` call was observed on the
   success path in `m.java` itself (unlike the MTK/Airoha path below, which explicitly
   compares firmware version strings after reconnect) — flagged as an unknown/gap.

### 3b. MTK/Airoha path (`LibraryType.MTK_TRANSFER_WO_DISCONNECTION` / `MTK_RHO_W_DISCONNECTION`)

Controller: `nu/o.java` (1011 lines, implements `nu.d.b`). This does **not** use the UPDT
Tandem-FOTA command set from §3a at all — before handing binary data to the Airoha RACE
library (`nu.a`, interface only — the actual implementation is the other analyst's
territory), it toggles a **general Tandem setting** (a boolean, addressed by whatever
`GsInquiredType`/`UpdtInquiredType` the capability table assigned this device) via
`q20.c.c(boolean)` → impl `s20/c.java:64-77` `c(boolean)`:
```java
// "changeUpdateStatus" — GS SET command
this.f73710c.e0(new b0.b().f(this.f73709b.c(), enable ? EnableDisable.ENABLE : EnableDisable.DISABLE), j.class)
```
i.e. **enable/disable "FW update mode"** is one Tandem general-setting SET/GET pair
(`b0`/boolean-setting family), addressed by the device's own inquired-type constant —
called with `true` before both `startTransfer`/`u0()` (`nu/o.java:953-960`) and before
`startInstall`/`s0()` (`nu/o.java:894-905`, guarded unless `f66879z` "same session"
flag is set), and implicitly released via `dispose()`/`cancel()`.

There is also a **separate** boolean toggle, `setAutoUpdate` (`s20/c.java:104-119`,
`h(boolean)`), sent via an on/off-setting family (`x.b()...OnOffSettingValue.ON/OFF`)
and persisted locally through `mq.d.K0(bool)` — name suggests an "auto-update enabled"
device setting rather than "disable auto power-off" specifically; the literal
"disable auto power-off" toggle instructed by the task brief was **not conclusively
located** — flagged as an unknown below (candidate: this `setAutoUpdate`/`h()` call, or
a plain `AUTO_POWER_OFF` general setting elsewhere in `feature/connectionmode` /
power-management code not explored in this pass).

**Sequence:**
1. `obtainUpdateMetaData()`/`J()` (`nu/o.java:527-536`) — checks cached
   `UpdateAvailability`, else triggers `AutoMagicDownloadTask` to fetch update metadata
   over the network.
2. Binary download (network) — on success `startTransfer`/`t0()`/`v0()`
   (`nu/o.java:894-990`) reads the expected FW version/language and downloaded bytes.
3. `u0()` (`nu/o.java:936-961`): registers a live connection-status observer (tracks per-bud
   L/R connect state for `PARTNER_LOSS` disambiguation), calls `q20.c.c(true)`
   ("enable FW update mode" Tandem setting) — if that fails, aborts immediately with
   `ABORT_TRANSFER_FAILED`; else installs the battery-threshold watcher (`k0()`), sets
   state `TRANSFERRING`, and calls `nu.a.b(bytes, batteryThreshold, callback)` — this
   is the handoff into the Airoha RACE library (opaque to this analysis).
4. Airoha callbacks (`nu/a.b` interface, `nu/o.java:245-311` class `e`): `a(progress%)`,
   `b()`=onRhoStart, `c()`=onTransferred → state `TRANSFERRED` (or, if `f66879z`
   "auto-continue" flag set, immediately calls `startInstall()`), `d(MtkFotaError)`=onFailed
   (mapped per §2).
5. `startInstall()`/`s0()` (`nu/o.java:894-905`): re-confirms/sets "FW update mode" via
   `q20.c.c(true)` (unless already in the same continuous session), state → `INSTALLING`,
   calls installer `nu.d.n()` ("startVerification", see below) then `nu.a.c()`
   (tells the Airoha library to actually flash/execute the update).
6. **Post-install verification / reconnect handling** — `nu/d.java` (`startVerification`
   `n()` at line 171-174, `notifyReconnectResult` `f()` at lines 138-156):
   - Starts a completion-check `Timer` ticking every 1200 ms (fake progress, capped at
     95%, `q()` lines 184-193) and a single-shot timeout
     (`f66829h` seconds — **default 240 s**, or **480 s** if the firmware binary is
     `>2 MiB` — `nu/o.java:118-125`: `mu.h.c(aVar.a()) > 2097152 ? g(480) : g(240)`,
     values confirmed via `Videoio.CAP_PROP_XI_CC_MATRIX_01=480` constant-substitution).
   - When the app detects the headset has come back (some external caller — not
     pinpointed in this pass — invokes `notifyReconnectResult(isSppConnected,
     actualFwVersion, actualLanguage)`): if not reconnected within the timeout →
     `INSTALL_TIMEOUT`; else compares `actualFwVersion` (or `actualLanguage` for VG/SVA
     updates) against the version string the app itself was expecting
     (set via `j(expectedFwVersion)` at transfer start, `nu/o.java:939`) — **exact
     string match** → `INSTALL_COMPLETED`, mismatch → `INSTALL_FAILED`. This is the
     literal "verify new version" step.
   - **No explicit `EXIT_FW_UPDATE_MODE`-equivalent call was found on the MTK success
     path** in the files read — the "FW update mode" boolean toggle from step 3 does not
     appear to be explicitly turned back off after success; unknown whether the device
     self-clears it on reboot or whether release happens in code not covered by this
     pass. Cancellation (`nu/o.java:595-601` `D()`) does call `J()`
     (`nu/o.java:482-487`: cancels the Airoha job + resets progress) but likewise does
     not show an explicit "update mode off" call — flagged as an unknown.

---

## 4. STATE MACHINE

Two parallel state enums exist (one per driving class):

**`FwUpdateState`** (Tandem-FOTA controller `m.java`,
`.../fwupdate/tandem/core/FwUpdateState.java:6-14`):
```
INIT → FIRMWARE_DOWNLOADING → TRANSFERRING → TRANSFERRED → INSTALLING
     → INSTALL_COMPLETED | INSTALL_TIMEOUT           (finish states)
     → CANCELLING → ERROR_OCCURRED                   (abort path)
```
`isRunningState()` = downloading/transferring/installing/cancelling;
`isFinishState()` = install-completed/install-timeout; `isAbortState()` = error-occurred.

**`MtkUpdateState`** (MTK/Airoha controller `nu/o.java`,
`.../application/update/mtk/MtkUpdateState.java:6-25`):
```
INIT, DOWNLOADING, TRANSFERRING, TRANSFERRED, INSTALLING,
INSTALL_COMPLETED, INSTALL_FAILED, INSTALL_TIMEOUT,
ABORT_NETWORK_CONNECTION, ABORT_DOWNLOAD_TIMEOUT, ABORT_DOWNLOAD_DATA_ERROR,
ABORT_DOWNLOAD_FAILED, ABORT_USER_OPERATION, ABORT_PARTNER_L_LOSS,
ABORT_PARTNER_R_LOSS, ABORT_BATTERY_LOW, ABORT_DISCONNECTED,
ABORT_BY_DEVICE_UNKNOWN_REASON, ABORT_TRANSFER_FAILED, PAUSE
```
`isRunningState()`=downloading/transferring/installing; `isCancelableState()`=
downloading/transferring/transferred/pause; `isFinishState()`=install-completed/
install-failed/install-timeout; `isAbortState()`= any `ABORT_*` or `ABORT_USER_OPERATION`.

### Timeouts / retries observed
| Step | Timeout | Source |
|---|---|---|
| Tandem `ENTER_FW_UPDATE_MODE` | 20 s | `EnterFwUpdateMode.java:44` |
| Tandem `EXIT_FW_UPDATE_MODE` | 20 s | `kx/g.java:~30` |
| Tandem `FINISH_TRANSFER` | 20 s | `kx/i.java:~30` |
| Tandem `CANCEL_TRANSFER` | 20 s | `kx/b.java:~30` |
| Tandem `START_TRANSFER` (per file) | 150 s | `StartTransfer.java:64` (`Imgproc.COLOR_BGR2YUV_YVYU`=150) |
| MTK install/reconnect wait | 240 s (≤2 MiB fw) / 480 s (>2 MiB fw) | `nu/o.java:118-125`, `nu/d.java:48` default 240, `Videoio.CAP_PROP_XI_CC_MATRIX_01`=480 |
| MTK EXECUTE_FW_UPDATE install-timer | `requiredTime × 2` seconds | `.../fwupdate/tandem/core/i.java:120-138` |

**No automatic retry loop was found anywhere in the controllers** — every command is a
single attempt with a hard timeout; a timeout or error result transitions straight to a
terminal `ABORT_*`/`ERROR_OCCURRED`/`*_FAILED`/`*_TIMEOUT` state. Recovering requires the
user to re-invoke the update flow from the UI (a fresh "Start Update" tap), which re-runs
preconditions from scratch.

### Disconnect mid-transfer
- **MTK/Airoha path:** `nu/o.java:148-159` (`class b implements nu.a.InterfaceC0836a`,
  `onDeviceDisconnected`/`c()`): sets an internal abort flag, calls `J()` (cancel the
  Airoha job + reset the binary downloader), then `i0(MtkUpdateState.ABORT_DISCONNECTED)`.
  This is a hard abort, not a pause — the app does not appear to auto-resume; a fresh
  update attempt starts over from `obtainUpdateMetaData`. Whether the *device* itself
  retains partial flash data for a subsequent resume depends on the `Resumable` flag in
  `UpdateCapability` (from `UpdateMethod.isResumable()`/`FLAG_RESUMABLE=1`) — the app
  exposes this as a capability but no explicit app-level "resume from byte N after
  reconnect" flow was observed in `nu/o.java` (unlike the Tandem-FOTA path's
  `ERROR_NO_NEED_OF_DATA_TRANSFER`/`offset` mechanism, §3a step 2, which *is* an explicit
  resume path).
- **Tandem-FOTA path:** an unsolicited `IDLE`/`NOT_READY` status during an active phase
  is treated as an abort (`m.java:367-397`, `cancelFromAudioDevice`), not a disconnect
  per se (BT connection itself isn't tracked here) — same "hard abort, must restart"
  behavior.

### Reboot detection / reconnect
- MTK/Airoha: handled by `nu/d.java` as described in §3b — a timer-based wait
  (240/480 s) for an external "reconnected" signal (`notifyReconnectResult`), whose
  *caller* was not pinpointed in this pass (candidate: the SPP/RFCOMM connection
  manager's "initial communication started" callback, `MdrRemoteBaseActivity.java:456-465`
  `onInitialCommunicationStarted`, feeding back into the update controller — not
  confirmed). **Whether the headset changes its Bluetooth address / re-pairs on reboot
  was not determined** — no address-comparison logic was found in the files read;
  flagged as an unknown.
- Tandem-FOTA: relies on the async `MessageType.FW_UPDATE_COMPLETED` notification
  arriving over the still-open Tandem session (`m.java:463-470`) — implies this path
  does **not** expect the headset to drop/re-establish the Classic BT link at all during
  install (consistent with `LibraryType.TANDEM` not being one of the "with disconnection"
  variants).

### TWS partner bud handling

> **Corrected (2026-09-24):** for MT2833 TWS the inference below is wrong. The library is handed one
> image but writes only the connected bud, then does a role switch (0x0CD7) and a second pass for the
> other bud; there is no internal relay. See [`spec-airoha-mt2833-tws.md`](spec-airoha-mt2833-tws.md) §7.
Both buds are tracked throughout via a live connection-status observer
(`com.sony.songpal.mdr.j2objc.tandem.features.connectionstatus.b`, exposing
independent L/`a()` and R/`b()` "connected" booleans, `nu/o.java:513-530,543-545`) and,
for battery, either a single-battery observer (`c10.a`) or an L/R-pair observer
(`c10.g`, `nu/o.java:552-575`). The controller (`nu.o`) is a **single instance per
`UpdateCapability.Target`**, not one per bud — there is no separate "flash L, then flash
R" sequencing visible in the app layer. The most consistent reading of the evidence
(single `nu.a.b(bytes, threshold, callback)` call handing the *entire* binary to the
Airoha library once, plus `MtkFotaError.PARTNER_LOSS` distinguishing *which* bud dropped)
is that **both buds are flashed via the Airoha library's own internal relay/agent
mechanism** (one bud — the one connected to the phone — relays data to its partner over
their private link), with the app only observing L/R connectivity and battery for abort
purposes, not driving two separate transfer sessions. This is an inference from the
callback shape, not a directly-read relay implementation (that logic is inside the
Airoha library, out of scope). `FW_INSTALL_TIMEOUT_DIALOG_FOR_TWS` vs.
`_FOR_ONE_UNIT` (`MtkFgFwUpdateFragment.java:667`) confirms the UI at least
distinguishes single-unit vs. TWS-pair install-timeout messaging.

---

## 5. USER-FACING FLOW

Screen/analytics enum values confirm the ordered flow
(`com/sony/songpal/mdr/j2objc/actionlog/param/Screen.java:39-45`):
```
FW_CONFIRMATION → FW_DOWNLOADING → FW_TRANSFERRING → FW_TRANSFERRED
→ FW_UPDATE_IN_PROGRESS → FW_UPDATE_COMPLETION   (or → FW_UPDATE_ABORTED)
```
`MtkFgFwUpdateFragment.we()` (`view/update/mtk/MtkFgFwUpdateFragment.java:600-643`) maps
`MtkUpdateState` → `Screen`:
- `DOWNLOADING` → `Screen.FW_DOWNLOADING`
- `TRANSFERRING` → `Screen.FW_TRANSFERRING`
- `INSTALLING` → `Screen.FW_UPDATE_IN_PROGRESS`
- `INSTALL_COMPLETED` → `Screen.FW_UPDATE_COMPLETION`
- every other state (all `TRANSFERRED`, `ABORT_*`, `INSTALL_FAILED`, `INSTALL_TIMEOUT`) →
  `Screen.UNKNOWN` (i.e. these don't get their own screen — they trigger a dialog
  instead, staying on the current screen; see `ye()` at lines 656-703 for the
  error-dialog mapping: install-error, install-timeout(TWS/single), download/data-error,
  transfer-error dialogs, each keyed off the same `MtkUpdateState` switch).

### Percentage reporting
A single 0-100 progress bar + `"<n>%"` text is reused across phases
(`ze()`, `MtkFgFwUpdateFragment.java:706-734`: `progressBar.setProgress(i11)`,
`percentText.setText(i11 + "%")`) — **percentages are per-phase, not a single blended
0-100% across the whole flow**:
- **Download**: real byte-count progress from the network fetch
  (`AutoMagicDownloadTask.DownloadCallback.a(int)`).
- **Transfer**: real byte-offset progress, `(offset*100)/fileSize` per file
  (`Transfer.java:100,118-121`; Tandem-FOTA path identical logic in `q.java:217-221`).
- **Install**: **simulated**, not read from the device — a timer ticks the displayed
  percentage up by 1 every fixed interval, capped at 95%, then holds at 95% until the
  real completion signal arrives (Tandem-FOTA: `.../core/i.java:120-138`, tick every
  `requiredTime×10 ms`, timeout at `requiredTime×2` s; MTK/Airoha: `nu/d.java:104-124,
  184-193`, tick every 1200 ms, timeout 240/480 s) — then jumps straight to 100%/
  "complete" on `INSTALL_COMPLETED`.

Recommended mirror for a third-party UI: **Info/Confirm → Download (0-100%, real) →
Transfer (0-100%, real, per-file if multi-file) → Install (0-95% simulated ramp, then
"finishing/verifying" indeterminate, then 100%) → Complete**, with the precondition
dialogs of §2 gating entry to "Download", and the error dialogs of §2/§3 able to
interrupt any phase and return the flow to a terminal aborted screen.

---

## Unknowns / gaps (things this pass could not confirm)

1. **Exact battery-percentage numbers.** Thresholds are device-reported (via the Tandem
   capability table, class `tableset2/u.java` and `tableset2/k.java`), not fixed
   constants in the app — no universal "15%"/"20%" was found, and it would be wrong to
   hardcode one.
2. **Mobile/phone battery threshold** (`CONFIRM_MOBILE_BATTERY` /
   `TandemUpdateConditionErrorCode.MOBILE_BATTERY_LOW`) — condition code exists, but the
   actual phone-battery-percent check (presumably via Android `BatteryManager`) was not
   located in the controller classes read.
3. **"Disable auto power-off" command** named in the task brief was not conclusively
   identified — best candidate found is `az.b`/`q20.c`'s `setAutoUpdate`/`h(boolean)`
   general-setting toggle (`s20/c.java:104-119`), but its name suggests "auto-update
   enable" rather than "auto power-off," so this mapping is **unconfirmed**.
4. **Caller of `nu.d.notifyReconnectResult(...)`** (who detects the post-reboot
   reconnect and feeds the result in) was not pinpointed — likely somewhere in the
   connection-manager/Activity layer (`MdrRemoteBaseActivity`, `MdrApplication`) not
   fully traced in this pass.
5. **Whether the headset changes Bluetooth address or re-pairs after an MTK/Airoha
   firmware reboot** — no address-comparison or re-pairing logic was found in the files
   read; this may live in the Airoha RACE library or the RFCOMM connection layer (other
   analyst's territory).
6. **TWS dual-bud flashing mechanism is an inference**, not directly observed — the app
   layer shows one controller instance and one Airoha handoff call per update target,
   with L/R connectivity/battery only *observed*, not separately driven; the actual
   "does bud A relay to bud B" logic is inside the (out-of-scope) Airoha library.
7. **Tandem-FOTA path (`LibraryType.TANDEM`) success path** does not show an explicit
   post-install "read back and verify new firmware version" step or a final
   `EXIT_FW_UPDATE_MODE` call in `m.java` — either it's implicit/elsewhere, or the
   device auto-exits update mode on its own after `FW_UPDATE_COMPLETED`.
8. **CSR/GAIA path** (`CsrUpdateController.java`, `GaiaHandlerFutures.java`, and helper
   classes `csr/{q1,v1,w1,x1,z0,a,b,c}.java`) was only surveyed for its state enum —
   legacy silicon, deprioritized given the task's focus on MTK/Tandem paths; a full
   command-sequence writeup for CSR would need a follow-up pass.
9. **BLE "Blanc"/"SSS" FOTA service** (`BLANC_FOTA_*`, `SSS_FOTA_*` GATT
   characteristics) exists in the codebase but is not wired into `UpdateCapability`; its
   role (which product line uses it, full command sequence) was not investigated beyond
   confirming its existence and chunk-size enum.
