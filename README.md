# AirPlay Server for Android TV

An Android TV port of [AirPlayServer for Windows](https://github.com/xenos1337/AirPlayServer).
It turns an Android TV / Google TV device into a receiver for **screen mirroring
and audio from iPhone, iPad and Mac**.

## Install

1. Enable developer options and network debugging (or USB debugging) on the TV.
2. From a computer on the same network:
   ```bash
   adb connect <tv-ip-address>
   adb install app-release.apk
   ```
3. Open **AirPlay Server** from the TV's app list.

## Use

1. Open the app. The home screen shows the receiver name and the TV's IP address.
2. On the Apple device, open **Control Center → Screen Mirroring** (or the
   Screen Mirroring menu on a Mac) and choose the TV.
3. The TV and the Apple device must be on the same network and subnet.

### Remote control

| Button | While mirroring |
|--------|-----------------|
| OK / Enter / Menu / Info | Show or hide stream info (resolution, fps, bitrate, decoder) |
| Back | Shows a hint; press again within 3 s to stop mirroring |

### Settings

| Setting | Notes |
|---------|-------|
| Receiver name | Defaults to the TV's device name. Changing it restarts the receiver when idle. |
| AirPlay resolution | Display size advertised to senders: Match TV, 720p, 1080p (default), 1440p, 4K. 4K needs a TV that can decode 4K H.264. |
| Keep running in the background | The receiver stays discoverable after you leave the app. Otherwise it stops when the app is closed. |
| Start when the TV turns on | Starts the receiver at boot (turns on background mode). |
| Open automatically when a device connects | In background mode, Android needs the *Display over other apps* permission to bring up the mirroring screen. Many TVs hide this setting; grant it with `adb shell appops set com.airplayserver.tv SYSTEM_ALERT_WINDOW allow`. |

## Build

Requirements: JDK 17+, Android SDK (platform 36), NDK `29.0.14206865`, CMake `3.31.6`.

```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew connectedDebugAndroidTest   # decoder tests on a connected device/emulator
```

The release build is signed with the debug key so it can be sideloaded. Set up
your own signing config before distributing it.

APKs include `armeabi-v7a` (most TVs), `arm64-v8a`, `x86` and `x86_64`.

## CI and releases

- **CI** (`.github/workflows/ci.yml`) runs on every push and pull request. It
  builds debug and release APKs, runs the JVM unit tests (`./gradlew testDebugUnitTest`),
  and runs the MediaCodec decoder tests on an Android emulator. APKs are attached
  to each run as artifacts.
- **Release** (`.github/workflows/release.yml`):
  1. Push a tag starting with `v`:
     ```bash
     git tag v1.1.0
     git push origin v1.1.0
     ```
     This builds a universal APK plus one per CPU architecture (with SHA-256
     checksums), sets the app version from the tag, and uploads them as the
     `release-apks-v1.1.0` artifact on the workflow run (kept for 90 days). It
     does not create a GitHub release.
  2. Create a release for the tag on GitHub (**Releases → Draft a new release**).
     The APKs from the tag build are attached automatically. If you create the
     release while the build is still running, they are attached when it
     finishes. If the tag was never built (for example, you created the tag
     from the release page), the release triggers the build.

### Release signing

Android only installs an update over an existing install if both are signed
with the same key. Create a key once and store it as repository secrets
(**Settings → Secrets and variables → Actions**):

```bash
keytool -genkeypair -v -keystore release.jks -alias airplay -keyalg RSA -keysize 4096 -validity 10000
base64 -i release.jks | pbcopy   # paste as RELEASE_KEYSTORE_BASE64
```

| Secret | Value |
|--------|-------|
| `RELEASE_KEYSTORE_BASE64` | The base64-encoded `release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | `airplay` (or the alias you chose) |
| `RELEASE_KEY_PASSWORD` | Key password |

Keep `release.jks` safe and out of the repository. Without these secrets the
release still builds, but each release is signed with a throwaway key and will
not install over a previous one.

## Debugging

```bash
adb logcat -s AirPlayNative AirPlayService NsdAdvertiser VideoDecoder AudioPlayer
```

Debug builds log the full AirPlay protocol exchange.

## Licenses

Repository-authored code from the upstream project is MIT
(`LICENSE-upstream-MIT`). The receiver includes libplist (LGPL-2.1-or-later)
and PlayFair interoperability code (GPL-3.0, `LICENSE-playfair-GPL3.md`).
Because PlayFair is GPL-3.0, distributed builds of this app must comply with
GPL-3.0. See `THIRD_PARTY_NOTICES-upstream.md`.

This is an unofficial implementation. Apple, AirPlay, and related trademarks
belong to Apple Inc.
