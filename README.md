# AirTV — AirPlay receiver for Google TV / Android TV

Mirror your iPhone or iPad screen (with audio) to a Google TV or Android TV device over
AirPlay. Open the app on the TV, pick the TV in the iOS Screen Mirroring menu, done — no
pairing code, no account, no cloud.

![idle screen](docs/idle-screen.png)

## Install on your Google TV

You need `adb` once, over the network. On the TV: **Settings → System → About → tap
"Android TV OS build" 7×** to enable developer options, then **Settings → System →
Developer options → USB debugging / Network debugging → on**. Note the TV's IP address
(Settings → Network).

```bash
adb connect <tv-ip>:5555          # accept the prompt shown on the TV
adb install -r AirTV.apk
```

Then launch **AirTV** from the Android TV home screen (it appears in the apps row).

To build the APK yourself:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # or your SDK path
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

The release APK is signed with the debug key — fine for sideloading, not for Play Store
distribution.

## Using it

1. Open AirTV on the TV. It shows `Ready. Choose "<TV name>" …` plus its IP and display mode.
2. On the iPhone/iPad: swipe down from the top-right corner → **Screen Mirroring** → pick
   your TV.
3. The TV switches to your device's screen, with audio.

Both devices must be on the same network, and the network must allow multicast (mDNS) and
direct device-to-device traffic. Guest/AP-isolated Wi-Fi networks will not work.

The receiver keeps running while the app is in the background, so audio continues if you
switch away; press **Back** on the TV remote to exit and stop it.

## About resolution

The receiver advertises your panel's real mode — verified at **3840×2160 @ 60 Hz** on a 4K
display — and the video surface is created at that size, so nothing is downscaled by the
compositor. The decode path handles H.264 and H.265 up to whatever your TV's decoder
supports, which is 4K on current Google TV hardware.

What the stream actually arrives at is **iOS's decision, not the receiver's**: for screen
mirroring iOS picks the encode resolution itself and in practice caps it at 1080p
(sometimes lower, and it drops resolution when the network is congested). No receiver
implementation can raise that cap. So: 4K-capable pipeline, 1080p-ish real-world mirroring
of an iPhone screen. This app does not implement the separate AirPlay-video (HLS) path that
apps like YouTube use to hand off a 4K stream directly.

## Audio

| Sender | Codec | Supported |
| --- | --- | --- |
| Screen mirroring (iPhone/iPad) | AAC-ELD 44.1 kHz stereo | yes |
| Some senders / AirPlay audio | AAC-LC | yes |
| AirPlay audio (Music app) | ALAC | no — Android has no guaranteed ALAC decoder |

## How it works

```
iPhone ──mDNS/Bonjour──▶ _airplay._tcp + _raop._tcp   (embedded mDNS responder)
       ──RTSP──────────▶ pair-setup / fp-setup / SETUP (FairPlay, Ed25519/Curve25519)
       ──RTP───────────▶ H.264 access units + AAC-ELD frames (AES encrypted)
                             │
                    airplay_jni.c (JNI bridge)
                             │
              VideoPipeline ─┴─ AudioPipeline
              MediaCodec →      MediaCodec →
              SurfaceView       AudioTrack
```

- `app/src/main/cpp/airplay/` — the AirPlay protocol server, vendored from
  [UxPlay](https://github.com/FDH2/UxPlay) (`lib/`, plus its bundled `playfair`, `llhttp`
  and `mdnsd`). This is the mature, actively maintained implementation of the FairPlay
  handshake and RTP/mirror protocol; reimplementing it would mean reproducing
  reverse-engineered FairPlay key tables.
- `app/src/main/cpp/airplay_jni.c` — bridges the library's `raop_callbacks_t` to Kotlin,
  handing up Annex-B video and AAC audio via direct `ByteBuffer`s, and normalising the
  remote NTP clock to presentation timestamps.
- `app/src/main/cpp/prebuilt/` — static `libcrypto` (OpenSSL 3.0.16) and `libplist` 2.6.0
  cross-compiled for `arm64-v8a`, `armeabi-v7a` and `x86_64`. Refresh with
  `tools/build-native-deps.sh`.
- `app/src/main/java/com/airtv/receiver/` — Kotlin: MediaCodec pipelines, the foreground
  service that owns the server and the multicast lock, and the full-screen activity.

## Tests

```bash
./gradlew test                      # 29 JVM unit tests
./gradlew connectedAndroidTest      # 16 instrumented tests (needs a device/emulator)
```

The instrumented tests are the interesting ones: they boot the real native server and
speak RTSP to it over a socket, send real multicast DNS queries and assert the responder
answers for both services, and push a genuine H.264 stream (produced by the platform
encoder) through the decode pipeline onto a real surface.

Not covered automatically: a real iPhone completing the FairPlay handshake. That needs an
actual device on the network.

## Licence

GPLv3 — see `LICENSE`. The vendored UxPlay library is LGPL-2.1+ and its bundled `playfair`
component is GPLv3, which makes the combined work GPLv3.
