# sony-wf-control-app

A standalone [Light Phone III](https://www.thelightphone.com/) tool that
controls **Sony WF-1000XM5** earbuds over Bluetooth — noise-cancelling /
ambient mode, ambient level, focus-on-voice, and battery status — directly from
the phone's minimal UI. **No web interface**: it talks to the earbuds natively
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

### SDK extension (submodule branch)

Upstream Light SDK has no Bluetooth support, so this repo pins its submodule to
`tthayer/light-sdk@feat/sony-wf-app`, which adds:

1. `LightBluetoothSerial` + `LightSerialConnection` (the RFCOMM broker), exposed
   on `SealedLightContext.bluetoothSerial`.
2. `BLUETOOTH_CONNECT` in the plugin's allowed-permission set (+ the implied
   `android.hardware.bluetooth` feature).
3. A LightOS-server grant so the dangerous runtime `BLUETOOTH_CONNECT`
   permission is actually granted on-device (mirrors how `CAMERA` is handled).

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
