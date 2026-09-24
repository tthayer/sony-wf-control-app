# MDR v2 (table 1) wire spec: WF-1000XM6 sound and power settings

Source: Sony Sound Connect 13.2.2 decompile (jadx). All paths below are relative to
`jadx/sources/`, and `T1/` means `com/sony/songpal/tandemfamily/message/mdr/v2/table1/`.
Function codes and class locations come from `spec-mdr-v2-functions.md`.

This is read-only research. No bytes here have been sent to a device, except the NCASM
`0x19` GET/reply that the brief already confirmed live. Anything marked **UNCONFIRMED** needs
checking on a device before you rely on it.

## Conventions

- Every payload is sent as a Command1 frame (type `0x0c`). `payload[0]` is the opcode and
  `payload[1]` is the InquiredType. Bytes are hex.
- `N` is a count byte and `…` is repeated data. Sony reads every numeric field as unsigned
  (`com.sony.songpal.util.e.m(b) = b & 0xff`).
- Opcodes by family (`T1/Command.java:25-145`):

| family | GET_CAP | RET_CAP | GET_STATUS | RET_STATUS | SET_STATUS | NTFY_STATUS | GET_PARAM | RET_PARAM | SET_PARAM | NTFY_PARAM | GET_EXT | RET_EXT |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| power | 20 | 21 | 22 | 23 | 24 | 25 | 26 | 27 | 28 | 29 | – | – |
| eqebb | 50 | 51 | 52 | 53 | – | 55 | 56 | 57 | 58 | 59 | 5a | 5b |
| ncasm | 60 | 61 | 62 | 63 | 64 | 65 | 66 | 67 | 68 | 69 | – | – |
| sense | 70 | 71 | **none** | – | 74 | 75 | **none** | – | 78 | 79 | 7a | 7b |
| audio | e0 | e1 | e2 | e3 | – | e5 | e6 | e7 | e8 | e9 | – | – |

- These enums recur. Watch the polarity: two of them encode ON as `00`.
  - `EnableDisable` (`v2/EnableDisable.java:6-8`): ENABLE=`00`, DISABLE=`01`.
  - `OnOffSettingValue` (`v2/OnOffSettingValue.java:6-8`): **ON=`00`, OFF=`01`**.
  - `NcAsmOnOffValue` (`T1/ncasm/param/NcAsmOnOffValue.java:6-8`): OFF=`00`, ON=`01`.
  - `NoiseAdaptiveMode` (`T1/ncasm/param/NoiseAdaptiveMode.java:6-9`): ON=`00`, OFF=`01`,
    PAUSED=`02`. This enum is used only in the sense/ASC entry, never in NCASM `0x19`.
  - `ValueChangeStatus` (`T1/ncasm/param/ValueChangeStatus.java`): UNDER_CHANGING=`00`, CHANGED=`01`.
  - `RequestResult` (`v2/RequestResult.java:6-7`): ACCEPTED=`00`, DECLINED=`01`.
- Sony decides which features to show from the support-function list (`wv/e.java`), then runs
  the capability GETs listed per setting below.
- Sony's parsers reject malformed replies silently. There is no NAK, so if a request is
  unsupported the device just never replies.

---

## 1. 0x6d: NC/ASM noise adaptation (NCASM inquired type `0x19`)

Inquired type `MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS_NA = 0x19`
(`T1/ncasm/param/NcAsmInquiredType.java:17`). Noise adaptation has no command of its own. It
is bytes `[7]` and `[8]` of the normal 9-byte NCASM `0x19` param, and Sony sets it with the
same `68 19` SET. Confirmed: `c40/e.java:116-117` builds `pf0.d1` (the `68 19` SET) with
`noiseAdaptiveOnOffValue.getNcAsmOnOffValueTableSet2()` and `noiseAdaptiveSensitivity`.

### Layout (GET reply 67, SET 68, notify 69: all the same 9 bytes)
Parse, build and validate code: `rf0/g.java:14-44`. SET wrapper: `pf0/z0.java:115-120` and
`pf0/d1.java:32-34`. RET_PARAM: `pf0/m0.java` → `rf0/g.f`. NTFY_PARAM: `pf0/h.java` → `rf0/g.f`.

| idx | field | values |
|---|---|---|
| 0 | opcode | `67` RET_PARAM / `68` SET_PARAM / `69` NTFY_PARAM |
| 1 | inquired type | `19` |
| 2 | ValueChangeStatus | `00` under changing (slider drag), `01` changed |
| 3 | NC/ASM total effect (NcAsmOnOffValue) | `00` off, `01` on |
| 4 | NcAsmMode | `00` NC, `01` ASM (ambient). Sony validates against NcNcssAsmMode (`00`/`01`/`02`), `rf0/g.java:35` |
| 5 | AmbientSoundMode | `00` normal, `01` voice (focus on voice) |
| 6 | ambient level | unsigned. Range comes from the capability (see below) |
| 7 | **noise adaptive on/off (NcAsmOnOffValue)** | **`00` OFF, `01` ON** (`rf0/g.java:26-28`, `NoiseAdaptiveOnOffValue.java:8-9`) |
| 8 | **noise adaptive sensitivity** | `00` STANDARD, `01` HIGH, `02` LOW (`T1/ncasm/param/NoiseAdaptiveSensitivity.java:6-8`) |

Validation (`rf0/g.java:35`): length must be exactly 9, `[1]` must be `19`, and bytes 4, 5, 7
and 8 must be in range. The base check (`pf0/z0.java:78`, `pf0/d.java:77`) also requires `[2]`
to be a valid ValueChangeStatus and `[3]` a valid NcAsmOnOffValue.

- GET: `66 19` (`pf0/b.java:21`, `u70/o1.java:1017`). The reply is `67 19 …` as above. The
  live XM6 value `67 19 01 01 00 00 14 00 00` decodes as: changed, effect on, NC, normal,
  level 20, **NA off**, sensitivity standard.
- SET: `68 19 <vcs> <eff> <mode> <amb> <lvl> <na> <sens>`. Sony sends
  `vcs=00 eff=01` (UNDER_CHANGE) while a slider moves and `vcs=01 eff=01` (CHANGED) when the
  user commits (`NcAsmSendStatus.java:39-47`). To turn noise adaptation on and leave everything
  else alone, echo bytes 3 to 6 from the last GET or notify and set `[7]=01`.
- Ack: the device sends a `69 19 …` notify with the same 9-byte layout (Sony's
  `pf0/h.java` handler). Live-confirmed per the brief.
- Status: `62 19` → `63 19 <EnableDisable>`, which is 3 bytes (`pf0/c.java:20`,
  `pf0/w0.java:20`). Notify `65 19 <EnableDisable>` (`pf0/s.java`).
- Capability: `60 19` (`pf0/a.java:20`, `wv/e.java:1253`, `:909`) →
  `61 19 N {asmMode min max step}×N`, so length = 3 + 4·N (`pf0/y.java:19`,
  `rf0/b.java:10-41`). Each entry gives the ambient-level range per AmbientSoundMode
  (`ze0/b.java`: min 0..254, max 1..255, step 1..255). There are no noise-adaptation fields in
  the capability.
- UI (`ModeNcAsmNcDualModeSwitchSeamlessNaThirdLayerDetailView.java:196,261,272,353-356`):
  - The switch is titled "Auto Ambient Sound" (`ASM_AutoAMB`), with the description "Automatically
    adjusts the volume of ambient sound filtered in, depending on the level of noise."
  - "Noise Detection Sensitivity" (`ASM_AutoAMB_Sensitivity_Title`) offers Standard, "H
    Sensitivity" and "L Sensitivity".
  - The manual ambient-level slider is enabled only while NA is OFF (`:261`).

> **Existing-code discrepancy (fix before implementing).** `SonyCommands.kt` declares
> `NOISE_ADAPTIVE_OFF = 0x01` ("ON is 0x00"), and that constant is the default for
> `ancSet(noiseAdaptive = …)`. That polarity belongs to `NoiseAdaptiveMode` (sense entry,
> §2). For NCASM `0x19`, Sony uses `NcAsmOnOffValue`, where `01` means **ON**. Sending the
> default would therefore switch Auto Ambient Sound **on**. (The live reply `…00 00` = NA off.)

---

## 2. 0x71: ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION (sense `0x01`)

`SenseInquiredType.ADAPTIVE_CONTROL_WITH_PARAMETER_NOTIFICATION = 0x01`
(`T1/sense/param/SenseInquiredType.java:7`). This is Sony's **Adaptive Sound Control**
(`AR_Title`). The phone detects activity or place and pushes the settings for it. **The
device offers no GET_STATUS or GET_PARAM**: the Command enum lacks them (`T1/Command.java:81-88`),
so the on/off state is held on the phone.

- Capability: `70 01` (`xf0/a.java:40`, `xf0/b.java:40`: length must be 2) →
  `71 01 N func×N`, length = N+3, N ≥ 1 (`xf0/k.java:20-29`). Each `func` is a
  SenseApplicableFunction (`T1/sense/param/SenseApplicableFunction.java:8-18`):
  - `00`–`07` are the older NC/ASM layouts.
  - `08` is NCASM-with-NA (the 0x19 equivalent).
  - `10` is EQ_PRESET_ID.
  - `20` is SMART_TALKING_MODE (Speak-to-Chat).
- SET_STATUS (editing brackets): `74 01 <ctl>` with ctl `00` START_SETTING and `01`
  END_SETTING, 3 bytes (`xf0/r.java:21,33-37`; sent from `h50/c.java:205-209`). Notify
  `75 01 <ctl>`, 3 bytes (`xf0/i.java:20`).
- SET_PARAM: `78 01 <onoff:OnOffSettingValue> N {entry}×N`, with 1 ≤ N ≤ 255
  (`xf0/p.java:49-66`; built in `h50/c.java:162-203`). Each entry starts with its function
  byte (`zf0/u.java:66-92`):
  - `08` NC/ASM+NA (8 bytes, `zf0/c.java:78-80`): `08 <vcs> <eff> <NcNcssAsmMode> <amb> <lvl>
    <NoiseAdaptiveMode> <sens>`. Sony sends `vcs=00` (UNDER_CHANGING) (`h50/c.java:127-131`).
    **NoiseAdaptiveMode here is ON=00/OFF=01/PAUSED=02.** Sony substitutes PAUSED for OFF when
    its flag is set (`h50/c.java:128-130`).
  - `10` EQ preset (2 bytes): `10 <EqPresetId>` (`zf0/a.java:32-34`).
  - `20` Smart Talking (2 bytes): `20 <OnOffSettingValue>` (`zf0/s.java:32-34`).
- NTFY_PARAM: `79 01 N {RequestResult entry}×N`. Each entry is prefixed with ACCEPTED `00` or
  DECLINED `01`, and the bytes must be consumed exactly (`xf0/g.java:21-33`, `zf0/v.java:21-28`).
  The app applies the accepted params to its NC/EQ state (`h50/a.java:86+`).
- GET_EXT_INFO: `7a 01 10` (EQ_PRESET_ID, 3 bytes: `xf0/d.java:44-63`, used by
  `u70/o1.java:834`) → `7b 01 10 <presetId> N step×N`, length = N+5 (`xf0/n.java:13-24`). This
  returns the band steps of the preset ASC will apply.
- UNCONFIRMED:
  - What the SET_PARAM `[2]` on/off byte means (it looks like "ASC enabled").
  - Whether the XM6 actually runs ASC autonomously. The feature name suggests the device
    reports back what it applied, but that isn't shown.
  - Sony's START/END order around a SET.
  - Recommendation: don't implement SETs until a trace is captured. Only the capability and
    ext-info GETs are safe to probe.

---

## 3. 0xe2: UPSCALING_AUTO_OFF (DSEE), audio `0x01`

`AudioInquiredType.UPSCALING = 0x01` (`T1/audio/param/AudioInquiredType.java:9`).

- Capability: `e0 01` (`cf0/a.java:20`; startup `wv/e.java:1301-1302`, `:626-627`) →
  `e1 01 <UpscalingType>`, 3 bytes (`cf0/e0.java:19,32`). UpscalingType
  (`UpscalingType.java:6-9`) picks the label:
  - `00` DSEE HX ("DSEE HX")
  - `01` DSEE ("DSEE")
  - `02` DSEE_HX_AI ("DSEE Extreme")
  - `03` DSEE_ULTIMATE ("DSEE Ultimate")
- Status: `e2 01` (`cf0/c.java:20`, `u70/o1.java:449`) → `e3 01 <EnableDisable>`, 3 bytes
  (`cf0/s0.java:17-25`). `00` means the control is available and `01` means it is greyed out.
  Notify `e5 01 <EnableDisable>` (`cf0/l.java:17-25`).
- Param GET: `e6 01` (`cf0/b.java:21`) → `e7 01 <UpscalingTypeAutoOff>`, 3 bytes
  (`cf0/o0.java:19`). Values: `00` OFF, `01` AUTO (`UpscalingTypeAutoOff.java:6-7`).
- SET: `e8 01 <00|01>`, length 3 (`cf0/i1.java:19-34`; sent from `r60/b.java:93-95`).
- Ack: notify `e9 01 <UpscalingTypeAutoOff>`, 3 bytes (`cf0/w.java:19`; handled at
  `r60/a.java:165`).
- UI: the title comes from the capability type. Options are "Auto" and "Off"
  (`DSEEHX_Param_Auto`/`_Off`, and `DSEEHX_AI_Param_*`).
- Related: inquired type `0x0b` (UPSCALING_AUTO_OFF_WITH_STATUS_DISABLE_REASON) uses the same
  param layout plus disable reasons, but it is a separate function code (0xed). Don't mix them.

---

## 4. 0xeb: BGM_MODE_SMALL_MIDDLE_LARGE_AND_ERRORCODE, audio `0x09`

`AudioInquiredType.BGM_MODE_AND_ERRORCODE = 0x09` (`AudioInquiredType.java:17`).

- Capability: `e0 09` (`wv/e.java:1310-1311`, `:652-653`) →
  `e1 09 N exFunc×N`, length = N+3 (`cf0/y.java:23-68`). This is the list of
  AudioExclusiveFunctionType values that the BGM effect disables
  (`AudioExclusiveFunctionType.java:8-23`):
  - `00` EQ, `01` DSEE, `02` head tracker, `03` LE Audio, `04` immersive audio, `05` SAR opt.
  - `06` Google Assistant, `07` spatial audio, `08` BGM, `09` upmix cinema, `0a` Sony VA.
  - `0b` Tencent, `0c` voice contents, `0d` sound-leak reduction, `0e` Bravia 3D, `0f` upmix series.
- Status: `e2 09` (`u70/o1.java:471`) → `e3 09 <EnableDisable> N err×N`
  (`cf0/r0.java:17-65`). `err` is a StatusErrorCodeType (`StatusErrorCodeType.java:8-21`):
  - `00` calling, `01` demo, `02` LE connected, `03` LE music playing, `04` tandem over GATT.
  - `05` VUI Google, `06` VUI Sony, `07` VUI Tencent.
  - `08` upmix cinema, `09` upmix game, `0a` upmix music.
  - `0b` listening mode not standard, `fe` other.

  Sony's length check is loose (`len ≥ N+3`, `cf0/r0.java:25`), but it reads the error codes
  at `[4..]`. Notify `e5 09 …` has the same layout (`cf0/k.java:17-66`).
- Param GET: `e6 09` (`u70/o1.java:432`) → `e7 09 <OnOffSettingValue> <RoomSize>`, 4 bytes
  (`cf0/h0.java:22-51`). Watch the polarity: **ON=`00`, OFF=`01`**. RoomSize is `00` SMALL,
  `01` MIDDLE, `02` LARGE (`RoomSize.java:6-8`).
- SET: `e8 09 <onoff> <room>`, 4 bytes (`cf0/a1.java:23-42`; sent from `h10/d.java:64`).
- Ack: notify `e9 09 <onoff> <room>`, 4 bytes (`cf0/d.java:22-51`).
- UI: "Background Music Effect" (`BGM_Title`), a toggle plus "Distance setting:" with three
  options (`cn/h.java:235-240`):
  - SMALL = "My room"
  - MIDDLE = "Living room"
  - LARGE = "Cafe"

  The setting can't be adjusted over LE Audio (`BGM_Info_Msg_LEA`).

---

## 5. 0xe7: CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO, audio `0x05`

`AudioInquiredType.CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO = 0x05` (`AudioInquiredType.java:13`).

- Capability: `e0 05` (`wv/e.java:1316-1317`, `:727-728`) →
  `e1 05 N prior×N M ldac×M`, with N ≥ 2 and total length = N+M+4 (`cf0/z.java:22-82`).
  - PriorMode (`PriorMode.java:6-8`): `00` SOUND_QUALITY_PRIOR, `01` CONNECTION_QUALITY_PRIOR,
    `02` LOW_LATENCY_PRIOR_BETA.
  - LDACExclusiveFeature: `00` GATT_CONNECTABLE (`LDACExclusiveFeature.java:6`).
- Status: `e2 05` (`v10/c.java:86-88`) → `e3 05 <EnableDisable a> <EnableDisable b>`, 4 bytes
  (`cf0/u0.java`, `df0/a.java:9-21`). `a = [2]` means the setting is enabled. `b = [3]` is a
  second flag (`t10/a.d()`), meaning **UNCONFIRMED**. Notify `e5 05 …` has the same layout
  (`cf0/n.java`; handled at `v10/c.java:115-127`).
- Param GET: `e6 05` (`v10/c.java:94`, `u70/o1.java:561`) → `e7 05 <PriorMode>`, 3 bytes
  (`cf0/i0.java:21`).
- SET: `e8 05 <PriorMode> <EnableDisable>`, **4 bytes** (`cf0/c1.java:21-36`; sent from
  `v10/e.java:94-97`). Sony always sends `[3] = 00` (ENABLE): the call is `cVar.b(value, true)`
  at `feature/connectionmode/j.java:263`. The meaning of `[3]` is **UNCONFIRMED**.
- Ack: notify `e9 05 <PriorMode> <SwitchingStream>`, 4 bytes (`cf0/u.java:20`). SwitchingStream
  is `00` none, `01` switching to LE Audio, `02` switching to Classic (`SwitchingStream.java:6-8`).
  Sony shows a stream-switch guide on `01` and `02` (`v10/c.java:130-141`). Expect the link to
  reconnect when the mode crosses Classic↔LE.
- UI: "Bluetooth Connection Quality" (`ConnectionModeItem.java:11-13`):
  - `00` "Prioritize Sound Quality"
  - `01` "Prioritize Stable Connection"
  - `02` "Low Latency (LE Audio)", marked beta. It is filtered out when LE isn't allowed
    (`v10/e.java:57-61,84-90`).

---

## 6. 0x57 / 0x56: EQ (eqebb)

`EqEbbInquiredType` (`T1/eqebb/param/EqEbbInquiredType.java:10,13`) has two relevant values:
`PRESET_EQ_AND_ERRORCODE = 0x04` and `TURN_KEY_EQ = 0x32`.

### 6a. 0x57: PRESET_EQ_AND_ERRORCODE (`0x04`)

- **Capability GET needs 3 bytes:** `50 04 <DisplayLanguage>` (`gf0/b.java:22,33-36`;
  startup `wv/e.java:1223`, `:801-802`). Sending the 2-byte form `50 04` fails Sony's own
  builder (`gf0/a.java:94-112`). DisplayLanguage (`T1/common/param/DisplayLanguage.java:8-24`):
  `00` undefined, `01` English, `02` French, `03` German, … `0b` Japanese, … `10` Turkish.
  Probe with `01`. The language byte appears to control the preset-name strings; that effect
  is UNCONFIRMED.
- Capability reply `51 04 <bandCount> <levelSteps> <presetCount> {id len name[len]}×presetCount`
  (`gf0/z.java:16-73`; field roles from `wv/a.java:244-247` and
  `devicecapability/tableset2/s.java:31-56`):
  - `[2]` is the band count and `[3]` is the level steps (0..255).
  - Presets start at `[5]`, and each is `<EqPresetId> <nameLen> <UTF-8 name>` with nameLen ≤ 128
    (`hf0/e.java`, `T1/e.java:22-24`). Validation requires the entries to fill the payload
    exactly and match presetCount.
  - This preset list is the source of truth for which presets the XM6 offers.
- EqPresetId (`EqPresetId.java:8-66`) and the Sony label for each (`view/EqResourceMap.java:17-75`):

  | Id | Name | Sony label |
  |---|---|---|
  | `00` | OFF | "Off" |
  | `01` | ROCK | |
  | `02` | POP | |
  | `03` | JAZZ | |
  | `04` | DANCE | |
  | `05` | EDM | |
  | `06` | R&B/Hip Hop | |
  | `07` | ACOUSTIC | |
  | `10` | BRIGHT | "Bright" |
  | `11` | EXCITED | "Excited" |
  | `12` | MELLOW | "Mellow" |
  | `13` | RELAXED | "Relaxed" |
  | `14` | VOCAL | "Vocal" |
  | `15` | TREBLE | "Treble Boost" |
  | `16` | BASS | "Bass Boost" |
  | `17` | SPEECH | "Speech" |
  | `20` | GAMING | |
  | `21`–`23` | FPS 1–3 | |
  | `30` | HEAVY | |
  | `31` | CLEAR | |
  | `32` | HARD | |
  | `33` | SOFT | |
  | `a0` | CUSTOM | "Manual" |
  | `a1`–`a5` | USER_SETTING1–5 | "Custom" |
  | `b0`–`bb` | ARTIST_COLLAB1–12 | |
  | `ff` | UNSPECIFIED | |

  The XM6's actual subset is UNCONFIRMED; take it from the capability reply.
- Status: `52 04` (`gf0/e.java:20`, `u70/o1.java:718,762`) →
  `53 04 <EnableDisable> N err×N` (`gf0/p0.java:21-63`). `err` is a PresetEqErrorCodeType:
  `00` calling, `01` demo mode, `02` listening mode, `fe` other (`PresetEqErrorCodeType.java:6-9`).
  Sony's length check is loose (`len ≥ N+3`), but it reads the errors at `[4..]`. Notify
  `55 04 …` has the same layout (`gf0/t.java`).
- **Read the current preset:** `56 04` (`gf0/d.java:20`, `u70/o1.java:797,811`) →
  `57 04 <presetId> N step×N` (`gf0/j0.java:14-45`, `hf0/c.java:13-57`). Validation: the
  length must be exactly N+4.
- **Select a preset:** `58 04 <presetId> 00`, i.e. zero bands (`m20/h.java:137-150`).
- **Custom bands:** `58 04 <presetId> N step0 … step(N-1)` (`m20/h.java:213-224`,
  `hf0/c.java:24-38`). Each step is a wire value from 0 to levelSteps-1, which Sony checks
  against 0..255. The UI shows `level = step - (levelSteps-1)/2`, so 21 steps give -10..+10
  (`m20/h.java:180-187,227-230`). Sony uses the CUSTOM/USER_SETTING preset ids for custom
  curves. N should equal the band count from the capability, though Sony doesn't enforce
  that on the SET.
- Ack: notify `59 04 <presetId> N step×N` (`gf0/m.java:14-50`). This is the same body as
  RET_PARAM and is also sent when the preset changes on the device.
- Band labels: `5a 04` (`gf0/c.java:20`, `u70/o1.java:625`) →
  `5b 04 N {type hi lo}×N`, length = 3 + 3·N (`gf0/e0.java:37-57`, `hf0/b.java:14-41`).
  - `type` values (`EqBandInformationType.java:8-11`): `00` none, `01` Hz, `02` kHz,
    `10` specific.
  - The value is the big-endian `hi<<8|lo`. For type `10`, value `1` means CLEAR_BASS
    (`SpecificInformationType.java:6`).
  - Sony formats values under 1000 as-is and larger ones as `x.y k` (`EqResourceMap.java:162-164`).
- UI: "Equalizer" (`EQ_Preset_Title`) with a preset list and band sliders.

### 6b. 0x56: TURN_KEY_EQ (`0x32`), QR-code artist EQ

- There is no GET, status or reply for this type. Sony's GET_CAPABILITY dispatcher does accept
  `50 32 <lang>` (`gf0/a.java:59`), but the function table lists no RET_CAPABILITY for it, and
  whether the device answers is UNCONFIRMED.
- SET: `58 32 <len> <blob[len]>`, where length = len+3 (`gf0/a1.java:23-54`). The blob is the
  Base64-decoded payload of an artist-collab QR code, passed through opaquely
  (`xy/f.java:324-335,353-385`, `g60/b.java:20`). The blob's internal layout is
  **UNCONFIRMED** beyond this: byte `[2]` is the preset-name length and bytes `[3..]` are the
  UTF-8 name (`xy/f.java:330-332`).
- Ack: notify `59 32 <TurnKeyEqResult>`, 3 bytes (`gf0/p.java:19`). TurnKeyEqResult
  (`TurnKeyEqResult.java:6-8`): `00` success, `01` not supported, `02` judgement fail.
- Not useful without Sony QR data. Implement it last, if at all.

---

## 7. 0x25: AUTO_POWER_OFF_WITH_WEARING_DETECTION, power `0x05`

`PowerInquiredType.AUTO_POWER_OFF_WEARING_DETECTION = 0x05` (`T1/power/param/PowerInquiredType.java:13`).

- Capability: `20 05` (`vf0/a.java:20`; startup `wv/e.java:1397-1398`, `:550-551`) →
  `21 05 N elem×N`, length = N+3 (`vf0/z.java:19-52`). AutoPowerOffWearingDetectionElements
  (`AutoPowerOffWearingDetectionElements.java:8-15`):
  - `00` 5 min, `01` 30 min, `02` 60 min, `03` 180 min, `04` 15 min.
  - `10` WHEN_REMOVED_FROM_EARS.
  - `11` DISABLE.

  Values `00` to `04` are the "select time" items (`isSelectTime`, `:68-71`).
- Status: `22 05` (`vf0/c.java:18-25`) → `23 05 <EnableDisable>`, 3 bytes (`vf0/j0.java:19`).
  Notify `25 05 <EnableDisable>` (`vf0/k.java:19`).
- Param GET: `26 05` (`vf0/b.java:81`) → `27 05 <mainElem> <selectTimeElem>`, 4 bytes
  (`vf0/e0.java:18-43`).
- SET: `28 05 <mainElem> <selectTimeElem>`, 4 bytes (`vf0/x0.java:19-45`; sent from
  `v00/d.java:86-90`). Neither byte may be `ff`.
  - `mainElem` is the active mode: one timed id (the device uses the timer), `10` or `11`.
  - `selectTimeElem` is the chosen time. Keep sending it even when the mode is `10` or `11`.
  - Example: "Turn off after 30 min" = `28 05 01 01`. "Off when removed" while remembering
    15 min = `28 05 10 04`. The main-mode reading of timed ids is inferred from `v00/d.java:39-45,103-110`.
- Ack: notify `29 05 <mainElem> <selectTimeElem>`, 4 bytes (`vf0/f.java:18-43`).
- UI (`view/AutoPowerOffItem.java:9-15`):
  - "Turn off after a certain time" (5 min, 15 min, 30 min, 1 hour, 3 hours)
  - "Off when headphones are removed"
  - "Do not turn off"

  The time picker appears only if the capability lists at least 2 timed ids (`v00/d.java:70-73`).

---

## 8. 0x23: POWER_OFF, power `0x03`

- There is no GET. `22 03` is rejected by Sony's builder (`vf0/c.java:18-21`), and GET_PARAM
  `26 03` isn't supported (`vf0/b.java:22-50`). There is also no capability.
- SET_STATUS: **`24 03 01`**, exactly 3 bytes with `[2]` equal to `01` USER_POWER_OFF
  (`vf0/c1.java:19-39`, `PowerOffSettingValue.java:6`; sent from `m40/a.java:51`). Value
  `ff` (FACTORY_POWER_OFF) exists in the enum but fails Sony's validation.
- Ack: none. NTFY_STATUS `25` has no POWER_OFF case (`vf0/i.java:21-73`). Expect the link to drop.
- UI: a confirmation "Turn power of the audio device off?" (`Msg_PowerOff`).
- **Destructive.** Never include it in probes.

---

## 9. 0x2b: BATTERY_SAFE_MODE, power `0x0b` ("Auto Power Save")

`PowerInquiredType.BATTERY_SAFE_MODE = 0x0b` (BSON.REGEX=11, `PowerInquiredType.java:19`).

- Capability: `20 0b` (startup `wv/e.java:1400-1401`) →
  `21 0b <thresholdPct> n1 fn1×n1 n2 fn2×n2`, length = n1+n2+5 (`vf0/a0.java:19-94`):
  - `[2]` is the battery-percent threshold, 0..100.
  - `fn1` are FunctionType table-1 codes and `fn2` are table-2 codes. These are the features
    that power save forces off.
- Status: none. `22 0b` is rejected (`vf0/c.java:20`).
- Param GET: `26 0b` (`vf0/b.java:34`, `u70/o1.java:488`) →
  `27 0b <setting> <effectActive>`, 4 bytes (`vf0/f0.java:18-43`). Both are OnOffSettingValue,
  **ON=`00`, OFF=`01`**. `setting` is the user switch and `effectActive` means power save is
  currently engaged (`g10/a.java:37-47`).
- SET: `28 0b <OnOffSettingValue> <BatterySafeModeEffectStatusControl>`, 4 bytes
  (`vf0/y0.java:20-42`). Control values: `00` NOT_TO_CHANGE, `01` TURN_OFF_THE_EFFECT
  (`BatterySafeModeEffectStatusControl.java:6-7`).
  - Toggle the setting: `28 0b 00 00` (on) or `28 0b 01 00` (off) (`g10/b.java:89-97`).
  - Cancel active power save and keep the setting: `28 0b 00 01` (`g10/b.java:65-75`).
- Ack: notify `29 0b <setting> <effectActive>`, 4 bytes (`vf0/g.java:18-43`; handled at
  `g10/a.java:52-62`).
- UI: "Auto Power Save" (`APS_Title`). The explanation reads: "Reduces power consumption … when
  the battery level reaches %d%% or below. The settings for the following features will be
  automatically changed", where %d is the capability `[2]` and the feature list comes from
  fn1/fn2. There is also a "Power save mode has been canceled" message (`APS_Modeout`).
  The screen mapping is inferred from `zm/c.java`, which uses these strings, so it is lightly
  UNCONFIRMED.

---

## Probe list (read-only, in safe order)

Every entry is a GET, GET_CAPABILITY or GET_EXT, and none changes device state. Send them one
at a time and allow about 500 ms for each reply. A missing reply means unsupported. Never send
anything from opcode families `24`, `28`, `58`, `68`, `74`, `78` or `e8` while probing.

```
# capabilities
60 19          NCASM 0x19 capability (ambient level ranges)
70 01          sense/ASC capability (applicable functions)
e0 01          DSEE capability (UpscalingType)
e0 09          BGM capability (exclusive functions)
e0 05          connection-mode capability (PriorModes + LDAC features)
50 04 01       EQ capability, language=English (band count, steps, preset list)
20 05          auto-power-off capability (timeouts)
20 0b          auto power save capability (threshold %, affected functions)
# status
62 19          NCASM 0x19 status
e2 01          DSEE status
e2 09          BGM status + error codes
e2 05          connection-mode status
52 04          EQ status + error codes
22 05          auto-power-off status
# params (current values)
66 19          NCASM 0x19 param (NA on/off at [7], sensitivity at [8])
e6 01          DSEE param
e6 09          BGM param
e6 05          connection-mode param
56 04          EQ current preset + band steps
5a 04          EQ band frequency labels
26 05          auto-power-off param
26 0b          auto power save param
# optional
7a 01 10       ASC ext info: band steps of the EQ preset ASC would apply
```

Deliberately left out:

- `22 03`, `26 03`, `22 0b`: invalid in Sony's own builders.
- `50 32 <lang>`: turn-key-EQ capability, reply unknown.
- `24 03 01`: power off.
