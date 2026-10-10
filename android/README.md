# Sloop Go — native Android app for the M-Vave FM-1 / SLOOP

A fully native (Kotlin + Jetpack Compose) controller for the FM-1 running the open-source
SLOOP firmware. It speaks the SLOOP editor protocol directly over USB-MIDI — no WebView, no
JavaScript bridge — so parameter changes reach the device immediately and the sequencer is a
real touch UI.

Connect the phone to the FM-1 / SLOOP (Felucca) with a USB-C ↔ USB-C cable (the phone must
support USB host / OTG), open the app, go to the **Device** tab and tap **Connect**. If Android
launches the app from the USB attach prompt, it also tries to connect automatically.

## Architecture

```
com.sloop.go
├─ proto/            SLOOP protocol, ported byte-for-byte from web/EDITOR_PROTOCOL.md of isod89/sloop-fm1
│  ├─ Protocol.kt    CMD numbers, framing (F0 7D 46 4C … F7), v14/string codecs, Reader
│  ├─ Requests.kt    request builders + replyMatches
│  ├─ Replies.kt     reply/push parsers + data classes
│  └─ Fm6.kt         FM6 (DX7) patch model, SysEx import/export
├─ midi/
│  └─ MidiEngine.kt  MidiManager wrapper: enumerate, open ports, SysEx reassembly, USB perm
├─ device/
│  ├─ DeviceController.kt  one-request-in-flight queue, coalesced instant SET, WATCH/PING,
│  │                       push handling (CHANGED/RELOAD/STEP_CHANGED/TRACK_CHANGED)
│  └─ DeviceState.kt       immutable StateFlow snapshot the UI renders
└─ ui/               Compose: Theme, corner-menu App, Sound / Sequencer / Mixer / Device screens
```

Edits enter a RAM FIFO (up to 16,384 entries) and appear locally at once. Discrete note/step
edits remain in order; only intermediate knob/mixer values for the same control are replaced by
the latest. Each edit waits for its SysEx reply before the next is sent, as required by firmware.
A failed request pauses the queue; Device → Retry resumes it. Unsent edits are explicitly
reported and discarded on disconnect. App-process death also loses queued edits without recovery
(the queue is RAM-only, not persistent).

### Current scope

- **Sound**: engine/kit selector + collapsible parameter groups (ENV, LFO, engine EDIT, VOICE, FX,
  SCALE, ARP, PATTERN, GLOBAL, MASTER, DRUMS). Round touch knobs: drag vertically, move the finger
  sideways for finer adjustment, double-tap for the default; toggle and enum controls remain.
- **Sequencer**: fixed-screen layout: a compact control panel on top (menu, preset, Now/Late, SEND,
  Clear, Fit) and a self-contained grid block below with its own scroll and pinch zoom, step numbers
  pinned to the block's top edge. Drum grid (16 lanes) and piano-roll span the whole selected track
  LEN horizontally (no pages). Tap to edit; long-press a drum cell and drag to paint. Piano-roll:
  tap to add/remove a note, drag the handle at the right end of a note bar to lengthen it
  (via TIE steps), long-press a note and drag to move it, drag empty space to scroll.
  A chord shares one duration, since TIE holds the entire synth track's previous step. Each synth
  step supports up to four notes. Clear asks for confirmation; per-step accent/slide/tie options,
  length +/- controls and keyboard are in the piano-roll's step options. The sequencer shows the
  selected track, engine and preset name; startup uses the original SLOOP icon as an animated splash.
  **Now** queues each sequencer change immediately. **Late** keeps a RAM-only draft for each track;
  **SEND** queues the selected track's entire 64-step state, including rests, in order. You can
  continue editing while the queue drains. Switching back to Now with unsent drafts requires an
  explicit choice to send all drafts or discard them. Disconnect also discards unsent drafts.
- **Mixer**: per-track level, mute, and selecting the track to edit.
- **Device**: pick/connect/disconnect a MIDI device, firmware info.

Also: **FM6 patches** (list/load/store/erase, DX7 SysEx import/export from phone storage, full six-operator editor) and, for
SLOOP 2.4, per-step nudge / fill / parameter locks in the step editor.

Not yet ported: projects, user-preset bank, sample upload, backup/restore, live playhead.
The protocol for them is documented in `web/EDITOR_PROTOCOL.md` of [isod89/sloop-fm1](https://github.com/isod89/sloop-fm1).

## Build requirements

- **JDK 17+** (JDK 21 works).
- **Android SDK** with `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`.
- No Android Studio required — the committed Gradle wrapper fetches Gradle 8.9 on first run.

### From a clean machine (Linux)

```bash
sudo apt install -y openjdk-17-jdk        # or any JDK >= 17

mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -fsSL -o cmdtools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q cmdtools.zip && mv cmdline-tools latest && rm cmdtools.zip
export ANDROID_SDK_ROOT=~/android-sdk
yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Point Gradle at the SDK with `android/local.properties` (not committed):

```properties
sdk.dir=/home/<you>/android-sdk
```

## Build & install

```bash
cd android
./gradlew assembleDebug
~/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Or copy it to the phone and tap it
(allow "install from unknown sources"). Current `applicationId` is `com.sloop.go`, `versionCode`
is 29 and `versionName` is 1.7.3. For each major update increment `versionCode` and update
`versionName` in `android/app/build.gradle`, and update the APK link in the root `README.md`
to the new release (`releases/download/<tag>/sloop_go_<version>.apk`); keep the same package
and signing key to install updates over existing installations with `adb install -r`.

> `./gradlew assembleRelease` is signed with the debug key for convenience. Replace the signing
> config before any real distribution.

## Notes

- USB-MIDI is reached through the system `MidiManager`, which usually needs no raw USB
  permission; the app also requests raw USB permission for the FM-1 (VID 0x1209 / PID 0x0001)
  as a best-effort for the most reliable access, and auto-launches on device attach.
- The device's MIDI port is named "Felucca"; the app auto-selects it, but any MIDI device is
  listed and selectable on the Device tab.
```
