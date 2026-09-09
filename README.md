# sony-wf-control-app

A standalone [Light Phone III](https://www.thelightphone.com/) tool that
controls **Sony headphones and earbuds** over Bluetooth — noise-cancelling /
ambient mode, ambient level, focus-on-voice, and battery status — directly from
the phone's minimal UI.

It is model-agnostic: it auto-detects the protocol dialect (v1 or v2) from the
handshake and discovers each device's capabilities at runtime (single vs L/R +
case battery, ANC variant / wind-noise support), so it adapts to whatever Sony
device is paired rather than hardcoding a model list. Verified end-to-end on
**WF-1000XM6** (earbuds) and **WH-1000XM5** (over-ear); other Sony models that
speak the same serial protocol (WF-1000XM4/XM5, WH-1000XM3/XM4, LinkBuds /
LinkBuds S, WF-C500/C700N, …) are supported by the same adaptive logic. The
connected device's name is shown as the title, and controls it doesn't support
(e.g. ANC on LinkBuds) are hidden. **No web interface**: it talks to the earbuds natively
over an RFCOMM (SPP-style) serial link, the same "serial control" mechanism
reverse-engineered by
[`usering-around/sony-wf1000xm5-controller`](https://github.com/usering-around/sony-wf1000xm5-controller)
and documented by [Gadgetbridge](https://gadgetbridge.org/).

It is a thin, self-contained repo built against the **Light SDK** (as a git
submodule), laid out to drop straight into Light's tool build / review pipeline.

## Layout

```
sony-wf-control-app/
├── light-sdk/            # git submodule → tthayer/light-sdk @ feat/sony-wf-app
├── tool/                 # the ONLY dev-owned module
│   ├── lighttool.toml    # tool id, label, version, declared permissions
│   ├── build.gradle.kts  # deps (project(":sdk:client") + light.sdk plugin)
│   └── src/
│       ├── main/kotlin/com/thelightphone/sonywf/
│       │   ├── SonyWfHomeScreen.kt   # @InitialScreen — the UI
│       │   ├── SonyWfViewModel.kt    # state + connection orchestration
│       │   └── protocol/             # pure-Kotlin Sony protocol (framing,
│       │       │                     #   ACK handshake, commands, parsers)
│       │       └── *.kt
│       └── test/kotlin/…             # protocol unit tests
├── settings.gradle.kts   # grafts the submodule's SDK projects into this build
├── build.gradle.kts      # thin root: plugin classpath + ext build knobs
├── gradle.properties
└── gradlew, gradle/       # wrapper (matches the pinned SDK)
```

## How it talks to the earbuds

The Light SDK sandboxes tool code — app modules cannot touch `android.bluetooth`
directly (no `Context`, no `getSystemService`). So Bluetooth access is brokered
by the SDK, exactly like the YubiKey tool brokers USB/NFC through
`LightSecurityKey`:

- **`LightBluetoothSerial`** (in the SDK, obtained as
  `lightContext.bluetoothSerial`) owns the `BluetoothAdapter`, enumerates
  already-paired devices, opens the RFCOMM socket to the Sony service UUID
  (`956C7B26-D49A-4BA8-B03F-B17D393CB6E2`), and exposes a protocol-agnostic
  `LightSerialConnection` (suspend `write`, a `Flow<ByteArray>` of inbound
  bytes, `close`).
- **`tool/src/main/kotlin/.../protocol/`** is pure Kotlin: it frames the Sony
  message protocol (header `0x3e` / trailer `0x3c`, escaping, big-endian length,
  checksum), runs the strict send-one-wait-for-ACK handshake, and parses ANC and
  battery notifications. It has no Android dependencies (only the
  `LightSerialConnection` interface), so it is unit-tested on the JVM.

> **Pairing:** pair the earbuds once in the system Bluetooth settings. This tool
> does not scan/discover, so it declares only `BLUETOOTH_CONNECT`.

### Known limitation: the `BLUETOOTH_CONNECT` runtime grant

Verified end-to-end on a real Light Phone III (model TLP301) against WF-1000XM6:
RFCOMM connect, the Init/ACK handshake, ANC + battery read, and ANC writes all
work. **But** the shipped LightOS server (`com.lightos`) currently refuses to
grant `BLUETOOTH_CONNECT` — its permission screen shows **"Not allowed"**
(`LightSdkPermissionActivity: … BLUETOOTH_CONNECT is not grantable by this
server`). The SDK-side grant plumbing here is correct (it launches the request
via `startActivityForResult` so the caller is identified, matching the CAMERA
path), and `LightSdkServer.androidPermissionAllowed` allows it — but that only
affects the SDK/emulator build, not the prebuilt on-device `com.lightos`.

So on a production device the permission must be granted one of two ways:

1. **Light adds `BLUETOOTH_CONNECT` to the production server's grantable set**
   (the real fix for end users — requires a LightOS change).
2. **Grant it manually over adb** (for development / personal use):
   ```bash
   adb shell pm grant com.thelightphone.sonycontrol android.permission.BLUETOOTH_CONNECT
   ```
   After that the tool connects and works fully.

### SDK extension (submodule branch)

Upstream Light SDK has no Bluetooth support, so this repo pins its submodule to
`tthayer/light-sdk@feat/sony-wf-app`, which adds:

1. `LightBluetoothSerial` + `LightSerialConnection` (the RFCOMM broker), exposed
   on `SealedLightContext.bluetoothSerial`.
2. `BLUETOOTH_CONNECT` in the plugin's allowed-permission set (+ the implied
   `android.hardware.bluetooth` feature).
3. A LightOS-server grant so the dangerous runtime `BLUETOOTH_CONNECT`
   permission is actually granted on-device (mirrors how `CAMERA` is handled).

## Firmware updates

The tool checks Sony's firmware feed (`info.update.sony.net`, hence the
`INTERNET` permission) once per connection, using the category/service IDs the
headphones report themselves.

- **Tandem-FOTA devices** (the firmware travels over the same RFCOMM link):
  full check → download → transfer → install, driven from the tool. Tap
  **UPDATE**, confirm, and keep the headphones on, near the phone, with the app
  open until it finishes.
- **MTK/Airoha and "MC app" devices**: check only. The tool reports the
  available version and tells you to install it with Sony's Sound Connect app.
- Devices that advertise no update support get no update line.

Specs: [`docs/protocol/fw-update-design.md`](docs/protocol/fw-update-design.md)
(API contract), [`spec-tandem-fota.md`](docs/protocol/spec-tandem-fota.md)
(transfer), [`spec-firmware-download.md`](docs/protocol/spec-firmware-download.md)
(feed discovery/decrypt), [`spec-update-orchestration.md`](docs/protocol/spec-update-orchestration.md)
(gating).

## Building locally

Requires JDK 17 and an Android SDK. Provide a GitHub token with `read:packages`
so the transitive keyboard dependency resolves — either in `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
gpr.user=<your-github-user>
gpr.key=<token with read:packages>
```

or as env vars `GH_PACKAGES_USER` / `GH_PACKAGES_TOKEN`. Then:

```bash
git submodule update --init --recursive
./gradlew :tool:assembleDebug        # → tool/build/outputs/apk/debug/tool-debug.apk
./gradlew :tool:testDebugUnitTest    # run the protocol unit tests
```

The APK is signed with the shared Light dev keystore (from the submodule) for
local sideloading. Light's build service re-signs with its own key.

> Bluetooth needs a real device — the emulator has no BT radio, so
> `LightBluetoothSerial` exposes a **demo mode** (a canned WF-1000XM5) for
> emulator testing.

## Bumping the SDK

```bash
git -C light-sdk fetch origin
git -C light-sdk checkout <commit-or-tag>
git add light-sdk && git commit -m "bump light-sdk to <ref>"
```

## CI / Releases

- **`.github/workflows/build.yml`** compiles the debug APK and runs the protocol
  unit tests on every push (non-`main`) and PR — the compile gate.
- **`.github/workflows/release.yml`** cuts a GitHub Release on every merge to
  `main`: derives the next version from conventional commits (`feat:` → minor,
  `fix:` → patch, `feat!:`/`BREAKING CHANGE` → major, seeded at **v0.1.0**),
  syncs `tool/lighttool.toml`, builds a signed release APK, and attaches it to a
  `vX.Y.Z` release.

## Light's build & review pipeline

Light's containerized builder clones this repo and extracts **only**
`tool/build.gradle.kts`, `tool/lighttool.toml`, and `tool/src/main/**`, then
compiles them against its own pinned, sandboxed copy of the SDK. It generates
`AndroidManifest.xml` from `lighttool.toml`, so this repo intentionally contains
no hand-written manifest and never sets `applicationId`, `versionCode`,
`versionName`, or `namespace`.

## Credit

Protocol details reverse-engineered by
[`usering-around/sony-wf1000xm5-controller`](https://github.com/usering-around/sony-wf1000xm5-controller)
and [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge). This tool
reimplements the serial protocol in Kotlin for the Light Phone.
