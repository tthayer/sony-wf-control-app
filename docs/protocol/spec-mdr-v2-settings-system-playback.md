# MDR v2 table-1 wire specs: WF-1000XM6 system, playback, LEA and multipoint settings

Source: jadx decompile of Sony Sound Connect 13.2.2. Paths are relative to the jadx `sources/` root. `T1/` = `com/sony/songpal/tandemfamily/message/mdr/v2/table1/`, `KMP/` = `com/sony/songpal/mdr/kmp/`. This is read-only research: no device was touched. Anything not proven by code is marked **UNCONFIRMED**. Function codes and XM6 support come from `spec-mdr-v2-functions.md`.

## 0. Conventions

- Every payload below is the MDR payload of a table-1 Command1 frame (data type `0x0c`, `T1/c.java:1004`). `payload[0]` is the opcode and `payload[1]` is the family InquiredType. Opcodes are in `T1/Command.java`: SYSTEM `f0`–`fd` (:146-159), PLAY `a0`–`a9` (:107-116), SAR_AUTO_PLAY `b0`–`b9` (:117-125), GENERAL_SETTING `d0`–`d9` (:128-136), LEA `40`–`4d` (:47-59).
- Family opcode pattern: `+0` GET_CAPABILITY, `+1` RET_CAPABILITY, `+2` GET_STATUS, `+3` RET_STATUS, `+4` SET_STATUS, `+5` NTFY_STATUS, `+6` GET_PARAM, `+7` RET_PARAM, `+8` SET_PARAM, `+9` NTFY_PARAM, `+a..+d` GET/RET/SET/NTFY_EXT_PARAM (SYSTEM and LEA only).
- **The value enums are inverted: 0 means yes.**
  - `EnableDisable`: `00`=ENABLE, `01`=DISABLE (`v2/EnableDisable.java:6-7`).
  - `OnOffSettingValue`: `00`=ON, `01`=OFF (`v2/OnOffSettingValue.java:6-7`).
  - KMP mirrors: `ProtocolEnableDisable` and `ProtocolOnOff` (`KMP/tandem/command/v2/*.java:10`).
  - `GsSettingValue`: `00`=ON, `01`=OFF (`T1/generalsetting/param/GsSettingValue.java`).
- STATUS vs PARAM. RET_STATUS/NTFY_STATUS carry `EnableDisable`. This says whether the setting can currently be changed (Sony greys the control out when it is DISABLE). RET_PARAM/SET_PARAM/NTFY_PARAM carry the user value.
- Sony's parsers reject anything whose length is not exact. The "Validation" lines list those checks. A Kotlin port should apply the same checks.
- SET acks. Sony never waits for a reply to a SET. It fires the SET (`le0.e.k(...)`) and updates its model only when an NTFY_* arrives. None of the setters below proves that the XM6 answers a SET with an NTFY, so **every "notify after SET" is UNCONFIRMED**. Beyond the link-layer ACK, treat the NTFY as the only confirmation.
- Parse routing quirk. For SYSTEM inquired bytes `0x01` and `0x0b`, Sony's generic parser hands GET/RET/SET/NTFY_STATUS, RET_PARAM and NTFY_PARAM to a raw holder (`T1/b.java` set `{1, 11}`, `T1/a.java:30`, `T1/c.java:946-960`). The KMP codecs in `nb0/*` parse them instead (section 1).

---

## 1. 0xf1 PLAYBACK_CONTROL_BY_WEARING_REMOVING_HEADPHONE_ON_OFF ("Pause when headphones are removed")

InquiredType `SystemInquiredType.PLAYBACK_CONTROL_BY_WEARING = 0x01` (`T1/system/param/SystemInquiredType.java:9`). XM6 counter 0x3a.

Sony's live code path is KMP: `KMP/feature/tandem/repository/v2/system/controlbywearing/V2ControlByWearingRepository.java`, using codecs in `nb0/`.

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_STATUS | `f2 01` | — | `nb0/b0.java:29-30` (`d(242); d(1)`), sent at `V2ControlByWearingRepository.java:231-233`, which waits for `0xf3/0x01` |
| RET_STATUS | `f3 01 EE` | len == 3. EE = EnableDisable (00 enable, 01 disable) | `nb0/z1.java:22-41` |
| GET_PARAM | `f6 01` | — | `nb0/t.java:29-30`, sent at `V2ControlByWearingRepository.java:258-262`, which waits for `0xf7/0x01` |
| RET_PARAM | `f7 01 VV` | len == 3. VV = ProtocolOnOff (00 ON = pause on removal, 01 OFF) | `nb0/r1.java:22-41` |
| SET_PARAM | `f8 01 VV` | VV: 00 ON, 01 OFF | `nb0/n2.java:53-57` (`Imgcodecs.IMWRITE_PNG_ALL_FILTERS` = 248 = 0xf8, `org/opencv/imgcodecs/Imgcodecs.java:92`), sent at `V2ControlByWearingRepository$setParam$2.java:45` |
| NTFY_STATUS | `f5 01 EE` | len == 3 | `nb0/v0.java:22-41`, observed at `…$startObservingNotifications$1.java:58` (opcode 245) |
| NTFY_PARAM | `f9 01 VV` | len == 3 | `nb0/l0.java:22-41`, observed at `…$startObservingNotifications$2.java:64` (opcode 249) |

- Capability query: none. Sony sends no GET_CAPABILITY for 0x01, and `ag0/a.java:78-88` does not accept it.
- Validation: the length must be exactly 3, `payload[1]` must be 0x01, and the value must be 0 or 1. Anything else throws "Unknown …" (`nb0/r1.java`).
- Sony's UI:
  - title "Pause when headphones are removed" (`AutoPlaybackControl_Title`)
  - description "When this function is turned on, music will pause when you take off the headphones. Playback resumes once you put the headphones back on." (`AutoPlaybackControl_Description`)
  - shown by `com/sony/songpal/mdr/feature/controlbywearing/ControlByWearingFragment.java` as an on/off switch, disabled when the status is DISABLE (`isEnable` in `V2ControlByWearingRepository.java:97-112`)
- Notify after SET: UNCONFIRMED.

## 2. 0xfc SMART_TALKING_MODE_TYPE2 (Speak-to-Chat)

InquiredType `SMART_TALKING_MODE_TYPE2 = 0x0c` (`SystemInquiredType.java:20`). XM6 counter 0x0d. Parsed by `ag0/*`. The model lives in `n50/a.java` (reader) and `n50/b.java` (setter).

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_CAPABILITY | `f0 0c` | — | `ag0/a.java:94-96`; init `wv/e.java:1334-1335` → `z0()` `wv/e.java:1019-1021` |
| RET_CAPABILITY | `f1 0c PP T1 T2 T3` | len == 6. PP = SmartTalkingModePreviewType (00 NOT_SUPPORT, 01 SUPPORT). T1/T2/T3 = unsigned "time until the mode closes" for FAST/MID/SLOW, shown as "Approx %d s". Units in seconds are UNCONFIRMED. | `ag0/o0.java:15-40`, `wv/a.java:185-186`, `DeviceCapabilityTableset2Builder.java:733-738` (a zero T is treated as capability missing: `:1030`), used at `n50/b.java:88-89` and `sy/s.java:158` |
| GET_STATUS | `f2 0c` | — | `ag0/d.java:34-36`, `u70/o1.java:901-902` |
| RET_STATUS | `f3 0c EE SS` | len == 4. EE = EnableDisable. SS = SmartTalkingModeEffectStatus (00 NOT_ACTIVE, 01 ACTIVE = chat mode engaged right now) | `ag0/n1.java:16-33`, `ag0/h1.java:115-117` |
| GET_PARAM | `f6 0c` | — | `ag0/c.java`, `u70/o1.java:893-894` |
| RET_PARAM | `f7 0c VV XX` | len == 4. VV = OnOffSettingValue STC on/off (00 ON). XX = second OnOffSettingValue, meaning UNCONFIRMED (Sony keeps it as `m50.b.d()` and never displays it) | `ag0/e1.java:15-37`, `n50/a.java:63` |
| SET_PARAM | `f8 0c VV XX` | Sony always sends XX = `01` (OFF): `a(onOff, false, …)` at `feature/smarttalkingmode/g0.java:259`, `l.java:214`, `sy/v.java:138` | `ag0/e2.java:36-47`, `n50/b.java:71-73` |
| NTFY_STATUS | `f5 0c EE SS` | len == 4 | `ag0/f0.java:16-37`, `n50/a.java:74-80` |
| NTFY_PARAM | `f9 0c VV XX` | len == 4 | `ag0/v.java:15-37`, `n50/a.java:82-90` |
| GET_EXT_PARAM | `fa 0c` | — | `ag0/b.java:53-60`, `u70/o1.java:885-886` |
| RET_EXT_PARAM | `fb 0c SN TT` | len == 4. SN = DetectSensitivity (00 AUTO, 01 HIGH, 02 LOW). TT = ModeOutTime (00 FAST, 01 MID, 02 SLOW, 03 NONE) | `ag0/v0.java`, `bg0/d.java:11-24`, enums `T1/system/param/DetectSensitivity.java`, `ModeOutTime.java` |
| SET_EXT_PARAM | `fc 0c SN TT` | — | `ag0/v1.java:32-40`, `bg0/d.java:26-33`, `n50/b.java:67-69` |
| NTFY_EXT_PARAM | `fd 0c SN TT` | len == 4 | `ag0/j.java`, `n50/a.java:92-100` |

- Capability query needed first: `f0 0c`. It gives the three time values the UI labels need.
- Validation: exact lengths as listed. Every byte must be a known enum value (OUT_OF_RANGE is rejected). SET_EXT rejects OUT_OF_RANGE (`bg0/d.java:27`).
- Sony's UI:
  - "Speak-to-Chat" (`SmartTalkingMode_Title`) on/off.
  - "Speak-to-Chat Setting" (`SmartTalkingMode_Setting_Title`) has two sections.
  - "Voice Detect Sensitivity" (`…_Sensitivity_Title`):
    - Automatic ("Automatically adjusts the sensitivity based on the ambient sound.")
    - H Sensitivity ("Select this when it doesn't react to voices well.")
    - L Sensitivity ("Select this when it reacts even if you haven't spoken.")
  - "Time until the mode closes" (`…_ModeOutTime_Title`):
    - Short/Fast (FAST)
    - Standard (MID)
    - Long/Slow (SLOW)
    - "Does not close automatically" (NONE)
  - The FAST/MID/SLOW labels are built with T1/T2/T3 (`sy/s.java:154-181`). Captions: `AR_Custom_LongStay_WaitTime_Short_Caption` "Short", `…_ModeOutTime_Option2` "Standard", `…Long_Caption` "Long", `…Option4` "Does not close automatically" (`feature/smarttalkingmode/c.java:116-293`).
  - The first time STC is enabled, the "Turned on Speak-to-Chat" dialog appears (`SmartTalkingModeTurnOnConfirmDialogFragment.java:60`).
- Notify after SET: UNCONFIRMED.

## 3. 0xf4 VOICE_ASSISTANT_SETTINGS

InquiredType `VOICE_ASSISTANT_SETTINGS = 0x04` (`SystemInquiredType.java:12`). XM6 counter 0x25. The model is `d70/a.java` (reader) and `d70/b.java` (setter).

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_CAPABILITY | `f0 04` | — | init `wv/e.java:1343` → `B0()` `wv/e.java:314-315` |
| RET_CAPABILITY | `f1 04 KT NN A1..ANN` | len == 4+NN. KT = VoiceAssistantKeyType (00 FIXED_BUTTON, 01 TOUCH_SENSOR_CONTROL_PANEL, 02 ASSIGNABLE_BUTTON, 03 ASSIGNABLE_SENSOR). Ai = selectable VoiceAssistant values | `ag0/p0.java:23-51` |
| GET_STATUS | `f2 04` | — | `d70/a.java:47-49` |
| RET_STATUS | `f3 04 EE` | len == 3 | `ag0/l1.java:75` |
| GET_PARAM | `f6 04` | — | `d70/a.java:53` |
| RET_PARAM | `f7 04 VA` | len == 3 | `ag0/f1.java`, `bg0/h.java:9-14` |
| SET_PARAM | `f8 04 VA` | — | `ag0/f2.java:35-38`, `d70/b.java:70` |
| NTFY_STATUS | `f5 04 EE` | len == 3 | `ag0/c0.java:70`, `d70/a.java:75-84` |
| NTFY_PARAM | `f9 04 VA` | len == 3 | `ag0/w.java`, `d70/a.java:87-98` |

VoiceAssistant values (`T1/system/param/VoiceAssistant.java`):

| Byte | Value |
|---|---|
| `30` | VOICE_RECOGNITION (phone's voice assist) |
| `31` | GOOGLE_ASSISTANT |
| `32` | AMAZON_ALEXA |
| `33` | TENCENT_XIAOWEI |
| `34` | SONY_VOICE_ASSISTANT |
| `3f` | VOICE_ASSISTANT_ENABLED_IN_OTHER_DEVICE |
| `ff` | NO_FUNCTION |

`fe` is OUT_OF_RANGE, and a message carrying it is rejected.

- Capability query needed first: `f0 04`. SET only a value that appeared in the capability list. Sony's UI offers only those values; Sony's builder does not enforce this.
- Validation: RET/SET/NTFY_PARAM must be exactly 3 bytes with a known VA (`bg0/h.java:13`). Every capability entry must be a known VA (`ag0/p0.java:28-32`).
- Sony quirk: when an app-side flag is set, Sony displays GOOGLE_ASSISTANT as NO_FUNCTION (`d70/a.java:60-62`, `:92-94`). The meaning of that flag is UNCONFIRMED (probably "Google Assistant unavailable on this phone").
- Sony's UI labels (string names; which screen shows them for the XM6 is UNCONFIRMED):

| Value | Label | String name |
|---|---|---|
| VOICE_RECOGNITION | "Voice Assist Function of Mobile Device" / "Voice Assist Function" | `Assignable_Key_Elem_VoiceAssistant_Title`, `…VoiceRecog_Title` |
| GOOGLE_ASSISTANT | "Digital assistant" | `Assignable_Key_Elem_GoogleAssistant_Title` |
| AMAZON_ALEXA | "Amazon Alexa" | — |
| NO_FUNCTION | "Do not use" | `Assignable_Key_Elem_VoiceAssistant_unused_Title` |

- LE Audio: `LEA_Assignable_Key_Setting_Edit_Info_FT_*_LEAudio` says Voice Assistant needs "Classic Audio only" (see section 8).
- Notify after SET: UNCONFIRMED.

## 4. 0xff HEAD_GESTURE_ON_OFF_TRAINING

InquiredTypes `HEAD_GESTURE_ON_OFF = 0x0f` and `HEAD_GESTURE_TRAINING = 0x10` (`SystemInquiredType.java:23-24`). XM6 counter 0x2f. The model is `x20/a.java` (reader) and `x20/b.java` (setter). KMP codecs exist too and give the same bytes (`nb0/f0,d2,v,t1,p2,p0,z0` for 0x0f).

On/off:

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_STATUS | `f2 0f` | — | `x20/a.java:41-43` |
| RET_STATUS | `f3 0f EE` | len == 3 | `ag0/l1.java:75`; KMP `nb0/d2.java:22-24` |
| GET_PARAM | `f6 0f` | — | `x20/a.java:47` |
| RET_PARAM | `f7 0f VV` | len == 3. VV = OnOff (00 ON) | `ag0/b1.java:47`; KMP `nb0/t1.java` |
| SET_PARAM | `f8 0f VV` | — | `ag0/a2.java:65-68`, `x20/b.java:55-56`; KMP `nb0/p2.java` |
| NTFY_STATUS | `f5 0f EE` | len == 3 | `ag0/c0.java:70`, `x20/a.java:63` |
| NTFY_PARAM | `f9 0f VV` | len == 3 | `ag0/p.java:47`, `x20/a.java:75-78` |

Training (optional; drives the "try nodding" screen):

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_STATUS | `f2 10` | reply `f3 10 EE` | `ag0/l1.java`; KMP `nb0/h0.java`, `nb0/f2.java` |
| SET_STATUS | `f4 10 OP` | OP = TrainingModeOperation (00 START, 01 FINISH) | `ag0/k2.java:16-27`, `ag0/h2.java:108` |
| NTFY_STATUS | `f5 10 MS EE` | len == 4. MS = HeadGestureTrainingModeStatus (00 IN_TEST_MODE, 01 OUT_OF_TEST_MODE) | `ag0/e0.java:16-31` |
| NTFY_PARAM | `f9 10 GA` | len == 3. GA = HeadGestureAction (00 NOD, 01 SWING = shake) | `ag0/s.java:15-24` |

- Capability query: none (`ag0/a.java` does not accept 0x0f or 0x10).
- Validation: lengths are exact and the enums must be known.
- Sony's UI:
  - "Head Gesture" (`Headgesture_Title`) on/off
  - help text: "You can respond by nodding or shaking your head. Incoming Call: Nod to accept. Shake head to reject. Scene-based Listening…: Yes/Cancel" (`Headgesture_Ex_AutoPlay_*`, `Info_Description_Headgesture_AutoPlay_Experience`)
  - training screens in `com/sony/songpal/mdr/feature/headgesture/view/HeadGestureTraining*`
- Notify after SET: UNCONFIRMED.

## 5. 0xf3 ASSIGNABLE_SETTING (touch sensor function per side)

InquiredType `ASSIGNABLE_SETTINGS = 0x03` (`SystemInquiredType.java:11`). XM6 counter 0x28. It is not the `_WITH_LIMITATION` 0x0e variant: FunctionType 0xfe is absent on the XM6.

Model: capability `DeviceCapabilityTableset2.d`, reader `o00/e.java`, setter `o00/h.java`.

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_CAPABILITY | `f0 03` | — | init `wv/e.java:1337-1338` → `z0()` |
| RET_CAPABILITY | `f1 03 NK {KEY}×NK` | see capability structure below. NK ≥ 1. Stream must be consumed exactly | `ag0/j0.java:22-40`, `cg0/c.java:17-30`, `cg0/d.java:19-38`, `cg0/a.java:18-28`, `cg0/b.java:21-44` |
| GET_STATUS | `f2 03` | — | `u70/o1.java:1115-1116` |
| RET_STATUS | `f3 03 N E1..EN` | one EnableDisable per key. N ≥ 1 and N == remaining bytes | `ag0/i1.java:18-35`, `cg0/h.java:41-47` |
| GET_PARAM | `f6 03` | — | `u70/o1.java:1092-1094` |
| RET_PARAM | `f7 03 N P1..PN` | Pi = current Preset for key i, in the capability key order. N ≥ 1 and N == remaining bytes | `ag0/y0.java:18-35`, `cg0/g.java:38-45` |
| SET_PARAM | `f8 03 N P1..PN` | Sony always sends all N keys: it pre-fills the list with the map size and places each preset at its key index (`o00/h.java:286-299`) | `ag0/x1.java:37-47`, `cg0/g.java:47-52` |
| NTFY_STATUS | `f5 03 N E1..EN` | — | `ag0/z.java:18`, `o00/e.java:250` |
| NTFY_PARAM | `f9 03 N P1..PN` | — | `ag0/m.java:18`, `o00/e.java:267` |
| GET/RET/SET/NTFY_EXT_PARAM | `fa 03` / `fb 03 N {preset nA {action function}×nA}` … | Custom per-gesture mapping (`bg0/a.java:13-48`, `ag0/s0,r1,f`). Not needed for preset-only support. Layout of `fb` is UNCONFIRMED beyond the parser shape | `ag0/r0.java:62-74` |

Capability structure (`f1 03 NK {KEY}×NK`):

```
KEY    = key type defaultPreset nP {PRESET}×nP          (nP ≥ 1)
PRESET = preset nA nB {action function}×nA {action function nF function×nF}×nB
         (nA and nB are not both 0)
```

The `{action function}` pairs list what the gestures do under that preset.

Enums (`T1/system/param/`):

**Key**

| Byte | Value |
|---|---|
| `00` | LEFT_SIDE |
| `01` | RIGHT_SIDE |
| `02` | CUSTOM |
| `03` | C |
| `04` | NC_AMB_KEY |
| `05` | NC_AMBIENT_KEY |

**Type**

| Byte | Value |
|---|---|
| `00` | TOUCH_SENSOR |
| `01` | BUTTON |
| `02` | FACE_TAP |

**Preset** (label in Sony's UI, from `com/sony/songpal/mdr/view/assignablesettingsdetail/PresetType.java:69-100`)

| Byte | Value | Label |
|---|---|---|
| `00` | AMBIENT_SOUND_CONTROL | "Ambient Sound Control" (`ASM_Title`) |
| `10` | VOLUME_CONTROL | "Volume Control" |
| `20` | PLAYBACK_CONTROL | "Playback Control" |
| `21` | TRACK_CONTROL | "Select Song" |
| `22` | PLAYBACK_CONTROL_VOICE_ASSISTANT_LIMITATION | — |
| `30` | VOICE_RECOGNITION | "Voice Assist Function" |
| `31` | GOOGLE_ASSIST | "Digital assistant" |
| `32` | AMAZON_ALEXA | "Amazon Alexa" |
| `33` | TENCENT_XIAOWEI | — |
| `34` | MS | — |
| `35` | AMBIENT_SOUND_CONTROL_QUICK_ACCESS | "Ambient Sound Control/Quick Access" |
| `36` | QUICK_ACCESS | "Quick Access" |
| `37` | TENCENT_XIAOWEI_Q_MSC | — |
| `38` | TEAMS | — |
| `39` | GOOGLE_ASSISTANT_…_CLASSIC_CONNECTION_CAUTION | — |
| `40` | AMAZON_ALEXA_…_CAUTION | — |
| `41` | TENCENT_XIAOWEI_…_CAUTION | — |
| `42` | QUICK_ACCESS_…_CAUTION | — |
| `43` | AMBIENT_SOUND_CONTROL_QUICK_ACCESS_…_CAUTION | — |
| `44` | TENCENT_XIAOWEI_Q_MSC_…_CAUTION | — |
| `45` | AMBIENT_SOUND_CONTROL_MIC | "Ambient Sound Control/Microphone" |
| `46` | LISTENING_MODE_QUICK_ACCESS | "Listening mode/Quick Access" |
| `47` | AMBIENT_SOUND_CONTROL_LISTENING_MODE | — |
| `70` | CHAT_MIX | "Game/Chat Balance Control" |
| `71` | CUSTOM1 | "Custom" |
| `72` | CUSTOM2 | "Custom" |
| `ff` | NO_FUNCTION | "Not Assigned" |

**Action**

| Byte | Value |
|---|---|
| `00` | SINGLE_TAP |
| `01` | DOUBLE_TAP |
| `02` | TRIPLE_TAP |
| `03` | REPEAT_TAP |
| `10` | SINGLE_TAP_AND_HOLD |
| `11` | DOUBLE_TAP_AND_HOLD |
| `21` | LONG_PRESS_THEN_ACTIVATE |
| `22` | LONG_PRESS_DURING_ACTIVATE |

**Function**: 00 NO_FUNCTION … 20 PLAY_PAUSE, 21 NEXT_TRACK, 22 PREV_TRACK, 23 VOLUME_UP, 24 VOLUME_DOWN, 30 VOICE_RECOGNITION … (full list in `Function.java`).

- Capability query needed first: `f0 03`. It gives the key order, which the SET list must follow, and the allowed presets per key.
- Validation rules:
  - Sony's parser: N is 1–255, every Preset is known (OUT_OF_RANGE `fe` is rejected), and there are no trailing bytes.
  - Sony's UI:
    - offers only the presets listed in the capability for each key
    - error "You cannot assign the Voice Assist Function to both the left and right." (`Assignable_Key_Error_Dual_VoiceAssistant`)
    - confirm dialog "Changes the function of the touch sensor" (`Assignable_Key_Changing_Title_Confirm_Touch`)
  - The body string "Reconnects to the headphones. Change?" also exists. Whether the XM6 reconnects after a preset change is UNCONFIRMED.
- UI labels: "Left" and "Right" tabs, with "You can change the function assigned to the touch sensor on the left/right." (`Assignable_Key_Setting_Edit_*`).
- Notify after SET: UNCONFIRMED.

## 6. 0xb8 INTEGRATED_AUTO_PLAY (sarautoplay)

InquiredType `SARAutoPlayInquiredType.INTEGRATED_AUTO_PLAY = 0x02` (`T1/sarautoplay/param/SARAutoPlayInquiredType.java:10`). XM6 counter 0x21.

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_CAPABILITY | `b0 02` | — | init `wv/e.java:1295-1296` → `m0()` `wv/e.java:879-880` |
| RET_CAPABILITY | `b1 02 UH UL` | len == 4. `AutoPlayUniqueId = (UH<<8)+UL` | `wf0/m.java:18-33`, `wv/a.java:536-537` |

- **There is no on/off message.** No wf0 STATUS or PARAM class accepts inquired 0x02. Only `wf0/a.java`, `wf0/j.java` and `wf0/m.java` reference it (grep). Sony stores the unique ID but reads it only in `toString`/`equals` (`j2objc/devicecapability/tableset2/f.java:61-69`).
- The feature counts as "Auto Play supported": `DeviceCapabilityTableset2.java:1783`, `l() = AUTO_PLAY || INTEGRATED_AUTO_PLAY`. The on/off is an app-side setting.
- **UNCONFIRMED:** whether any headset-side toggle exists for it. It is not in table 1. The per-service AUTO_PLAY (0x01) messages are sent only when FunctionType AUTO_PLAY (0xb1) is present (`wv/e.java:1292-1293`), and the XM6 does not report 0xb1.
- For the Kotlin app: only the capability read is possible. Do not invent a SET.

## 7. 0xa1 PLAYBACK_CONTROLLER_WITH_CALL_VOLUME_ADJUSTMENT

InquiredTypes (`T1/playback/param/PlayInquiredType.java`):

| Byte | Value |
|---|---|
| `01` | PLAYBACK_CONTROL_WITH_CALL_VOLUME_ADJUSTMENT |
| `20` | MUSIC_VOLUME |
| `21` | CALL_VOLUME |

The `_WITH_MUTE` variants 0x30/0x31 are used only by 0xa3-type devices. XM6 counter 0x14. Model: reader `j40/f.java`, setter `j40/i.java`.

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_CAPABILITY | `a0 01` | — | init `wv/e.java:1277-1278` → `s0()` `wv/e.java:943-944` |
| RET_CAPABILITY | `a1 01 MS CS` | len == 4. MS = music volume steps (≥1), CS = call volume steps (≥1) | `tf0/p.java:63-78`, `wv/a.java:149-150` |
| GET_STATUS | `a2 01` | only types 01–03 and 40 are valid for GET_STATUS | `tf0/c.java:74`, `u70/o1.java:1287-1288`, `j40/f.java:74` |
| RET_STATUS | `a3 01 EE PS MC` | len == 5. EE = EnableDisable. PS = PlaybackStatus (00 UNSETTLED, 01 PLAY, 02 PAUSE, 03 STOP). MC = MusicCallStatus (00 MUSIC, 01 CALL) | `tf0/a0.java:65-86`, `tf0/w.java:73-74` |
| SET_STATUS (transport command) | `a4 01 00 PC` | EE must be 00 (else rejected). PC ∈ {01 PAUSE, 02 TRACK_UP = next, 03 TRACK_DOWN = previous, 07 PLAY}. All other PlaybackControl values are rejected | `tf0/f0.java:87-112`, `tf0/f0.java:60-72`, `j40/i.java:93-97` |
| NTFY_STATUS | `a5 01 EE PS MC` | len == 5 | `tf0/m.java:65-86`, `j40/f.java:115-117` |
| GET_PARAM (metadata) | `a6 01` | — | `tf0/b.java:65`, `u70/o1.java:452-453` |
| RET_PARAM (metadata) | `a7 01 {NS LEN UTF8×LEN}×4` | Exactly 4 entries in order Track, Album, Artist, Genre. NS = PlaybackNameStatus (00 UNSETTLED, 01 NOTHING, 02 SETTLED). LEN ≤ 128. Total length must match | `tf0/t.java:62-108`, `uf0/a.java:13-38`, `T1/e.java:20-24`; order from `j40/f.java:91` and `playbackcontroller/n.java:109` ("Track/Album/Artist/Genre") |
| NTFY_PARAM (metadata) | `a9 01 {…}×4` | same layout | `tf0/f.java:62-108`, `j40/f.java:137-141` |
| GET_PARAM (volume) | `a6 20` (music) / `a6 21` (call) | — | `u70/o1.java:1304-1305`, `j40/f.java:82-86` |
| RET_PARAM (volume) | `a7 20 VV` / `a7 21 VV` | len == 3. VV unsigned | `tf0/u.java:63-74` |
| SET_PARAM (volume) | `a8 20 VV` / `a8 21 VV` | Builder accepts 0–255 (`tf0/d0.java:76-79`). The UI range is 0..steps from the capability, and whether the maximum is steps or steps−1 is UNCONFIRMED | `tf0/d0.java:64-84`, `j40/i.java:77-81`, `:108-112` |
| NTFY_PARAM (volume) | `a9 20 VV` / `a9 21 VV` | len == 3 | `tf0/g.java:63-74`, `j40/f.java:124-128` |

- **Volume up/down**: there is no relative volume command. Sony's UI sets an absolute value with SET_PARAM `a8 20 VV`. For up/down, read or track VV and send VV±1, clamped to 0..MS.
- Capability query needed first: `a0 01` (volume step counts).
- Validation: exact lengths. RET_PARAM metadata must hold exactly 4 well-formed strings. RET_CAPABILITY steps must be ≥1.
- Sony's UI:
  - "Now Playing" (`PlaybackController_Title`) card "Playback Control"
  - fallback text "Unknown Song" / "Unknown Album" / "Unknown Artist" when the name status is not SETTLED
  - play/pause, previous and next buttons
  - music volume slider
  - call volume slider (`SettingItem$AudioVolume.CALL_VOLUME`)
- Notify after SET: UNCONFIRMED. Transport commands should cause an NTFY_STATUS `a5 01` when the playback state changes (UNCONFIRMED).

## 8. 0x42 CLASSIC_ONLY_LE_CLASSIC_SETTING (lea) — LE Audio connection setting

InquiredType `LEAInquiredType.CLASSIC_ONLY_LE_CLASSIC_SETTING = 0x0c` (`T1/lea/param/LEAInquiredType.java:12`). XM6 counter 0x3c. Model: reader `d30/a.java`, setter `d30/b.java`.

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_STATUS | `42 0c` | — | `kf0/d.java:92`, `d30/a.java:38-40` |
| RET_STATUS | `43 0c EE` | len == 3 | `kf0/e0.java`, `mf0/b.java:84-89`, `kf0/d0.java:129` |
| GET_PARAM | `46 0c` | — | `kf0/c.java:92`, `d30/a.java:44` |
| RET_PARAM | `47 0c VV` | len == 3. VV = OnOff: 00 ON = "LE Audio Priority", 01 OFF = "Classic Audio only (conventional connection method)" | `kf0/a0.java`, `mf0/a.java:9-14`, `d30/a.java:49`, `d30/b.java:52-55` (`enableLE ? ON : OFF`) |
| SET_PARAM | `48 0c VV CT` | len == 4. CT = ChangeType (00 SETTING_AND_CONNECTION_METHOD_CHANGE, 01 SETTING_CHANGE). Sony's UI always passes supportedLE = true, so CT = 00 (`feature/leaudio/connection/c0.java:239,285,295,544,554` send ON; `:364,370,376` send OFF) | `kf0/m0.java:21-38`, `d30/b.java:55` |
| NTFY_STATUS | `45 0c EE` | len == 3 | `kf0/l.java`, `kf0/k.java:129`, `d30/a.java:58-67` |
| NTFY_PARAM | `49 0c VV` | len == 3 | `kf0/i.java`, `kf0/g.java:118`, `d30/a.java:70-77` |

- Capability query: none. Sony's init sends no LEA GET_CAPABILITY for 0x0c.
- Validation: exact lengths and known enums.
- Sony's UI:
  - title "LE Audio connection setting for headphones" (`LEA_Title_Capability_Change_Settings`). On phones without LE support the title is "Headphone connection setting" (`…_withUnsupportedDevices`).
  - option "LE Audio Priority" (`LEA_Capability_LEAudio_Classic`)
  - option "Classic Audio only (conventional connection method)" (`LEA_Capability_only_Classic`)
  - confirm dialog "Reconnects to the headphones. If changed to this setting, headphones will only be able to connect with Classic Audio." (`LEA_Description_CapabilityChange_to_ClassicOnly`)
  - warning "The [Connect to 2 devices simultaneously] function is not available for devices connected via LE Audio." (`LEA_Description_CapabilityChange_to_LEAudio_Classic_Notuse_2DMP`)
- **The headset reconnects after this SET.** Sony's confirmation text says so. Expect the link to drop.
- Notify after SET: UNCONFIRMED.
- Related but separate: 0xe7 CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO (audio family) is not covered here.

## 9. Multipoint ("Connect to 2 devices simultaneously") and connected-device list

- The XM6 table-1 list has **no dedicated multipoint function.** Sony models multipoint as a **GENERAL_SETTING slot** titled `MULTIPOINT_SETTING`:
  - `j2objc/cap/GsTitleTitle.java:15`
  - UI "Connect to 2 devices simultaneously": `feature/generalsetting/resources/GsTitleTitleResourceMap.java:18`, `MultiPoint_Title`
- The XM6 reports GS slots 0xd1, 0xd2 and 0xd4. **Which slot is multipoint is UNCONFIRMED.** It must be read from each slot's capability title.

GENERAL_SETTING (InquiredType = slot byte `d1`/`d2`/`d4`, `T1/generalsetting/param/GsInquiredType.java`):

| Op | Payload | Layout / values | Cite |
|---|---|---|---|
| GET_CAPABILITY | `d0 SL LG` | LG = DisplayLanguage (`01` ENGLISH; `T1/common/param/DisplayLanguage.java`) | `if0/a.java:22-33`; init `wv/e.java:1355-1360` → `l0()` `wv/e.java:866-867` |
| RET_CAPABILITY | `d1 SL ST FM TL title×TL SLn summary×SLn` | ST = GsSettingType (00 BOOLEAN, 01 LIST). FM = GsStringFormat (00 RAW_NAME, 01 ENUM_NAME). Title/summary are length-prefixed UTF-8. With ENUM_NAME, the title is an enum name such as `MULTIPOINT_SETTING` (`GsTitleTitle.fromTitle`). The boolean form must have total len == TL+6+SLn | `if0/h.java:36-66`, `if0/i.java:18-22`, `jf0/a.java:13-50`. The LIST layout (`if0/j.java`) is not covered here: UNCONFIRMED |
| GET_STATUS | `d2 SL` | — | `if0/c.java:20` |
| RET_STATUS | `d3 SL EE` | len == 3 | `if0/n.java:20` |
| GET_PARAM | `d6 SL` | — | `if0/b.java:21` |
| RET_PARAM (boolean) | `d7 SL 00 VV` | len == 4. VV = GsSettingValue (00 ON, 01 OFF) | `if0/l.java:19` |
| SET_PARAM (boolean) | `d8 SL 00 VV` | — | `if0/p.java:21-35` |
| NTFY_STATUS | `d5 SL EE` | — | `if0/g.java:20` |
| NTFY_PARAM (boolean) | `d9 SL 00 VV` | — | `if0/e.java:19` |

- Alerts `DISCONNECT_CAUSED_BY_CHANGING_MULTIPOINT` (0x07) and `…_LDAC_DISABLE` (0x06) exist (`T1/alert/param/AlertMessageType.java:13-14`). A multipoint change can disconnect a device or disable LDAC (UNCONFIRMED on the XM6).
- **Connected-device list: not in table 1.** It lives in table 2 PERIPHERAL: 0x30 PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT and 0x32/0x33 …WITH_BLUETOOTH_CLASS_OF_DEVICE (`lg0/*`, frame type 0x0e). Table 2 has not been probed on the XM6, so its support and wire layout are **UNCONFIRMED**. The first step is the table-2 support-function probe `06 00` on frame type 0x0e (`spec-mdr-v2-functions.md` §6).

---

## Probe list (read-only, in a safe order)

All probes are table 1 (frame type 0x0c) unless noted. Each is a GET that Sony's own startup already sends, or a status/param GET. None changes state. Send one at a time and wait for the matching `+1` reply before sending the next. Stop if a reply fails validation.

1. Capabilities:
   - `f0 0c` (STC)
   - `f0 03` (assignable)
   - `f0 04` (voice assistant)
   - `a0 01` (playback)
   - `b0 02` (integrated auto play)
   - `d0 d1 01`, `d0 d2 01`, `d0 d4 01` (GS slots, English titles; look for `MULTIPOINT_SETTING`)
2. Pause when removed: `f2 01`, `f6 01`
3. Speak-to-Chat: `f2 0c`, `f6 0c`, `fa 0c`
4. Voice assistant: `f2 04`, `f6 04`
5. Head gesture: `f2 0f`, `f6 0f`, `f2 10`
6. Touch assignment: `f2 03`, `f6 03`
   - Optional custom mapping: `fa 03`
7. Playback: `a2 01`, `a6 01`, `a6 20`, `a6 21`
8. LE Audio setting: `42 0c`, `46 0c`
9. GS status/param per slot: `d2 d1`, `d6 d1`, `d2 d2`, `d6 d2`, `d2 d4`, `d6 d4`
10. Optional, table 2 (frame type 0x0e): `06 00` (support functions, to decide on the device-list work)

Expected reply opcodes: `f1`/`f3`/`f7`/`fb` (system), `a1`/`a3`/`a7` (play), `b1` (sarautoplay), `d1`/`d3`/`d7` (GS), `43`/`47` (LEA), `07` (table-2 support function).

## UNCONFIRMED summary

1. Whether the XM6 sends an NTFY_* after each SET. Sony never waits for one.
2. The meaning of the second STC param byte (`f7 0c VV XX`). Sony always sends XX = 01.
3. The unit of the STC capability T1/T2/T3 (seconds per the "Approx %d s" labels).
4. Which GS slot (d1/d2/d4) is multipoint. The GS LIST-type layout.
5. No headset-side on/off exists for INTEGRATED_AUTO_PLAY. The toggle is app-side (location not traced).
6. Whether the volume maximum is `steps` or `steps−1`.
7. The connected-device list (table 2 PERIPHERAL): support and layout are unprobed.
8. Whether a touch-preset SET or a LE Audio SET forces a reconnect on the XM6 (the LE one very likely does).
9. The meaning of the app-side flag that hides GOOGLE_ASSISTANT (`d70/a.java:60`).
