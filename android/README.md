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
  step supports up to four notes. Editing follows the track's VOICE: on MONO/LEGATO/UNISON notes
  never overlap — placing replaces the note starting there, moves and resizes stop at neighbours —
  while on POLY pitches land freely and the roll draws true overlaps, including SLOOP 2.7 recorded
  CONT chains (a key released late under the next note keeps sounding; untouched steps stay as
  recorded). A step write cannot carry CONT, so a bar drawn under a later attack is released there
  on the device while the roll keeps the drawn overlap; a bar kept inside a recorded chain crosses
  it unchanged. On POLY each bar in a chord has its own length; adding a pitch at an existing NOTE
  keeps that chord's duration. Editing a note (length, move, delete) drops its hidden continuation,
  so FM-1 then plays exactly the drawn NOTE + TIE length.
  **Q** quantizes note starts (1/2 … 1/32 of a bar, by DIV) of the selection or the whole track,
  keeping lengths. The TIE toolbar switch adds
  continuation markers; dragging previews the same chord boundaries that will be sent.
  Clear asks for confirmation; per-step accent/slide/tie options,
  length +/- controls and keyboard are in the piano-roll's step options. The sequencer shows the
  selected track, engine and preset name; startup uses the original SLOOP icon as an animated splash.
  **Now** queues each sequencer change immediately. **Late** keeps a RAM-only draft for each track;
  **SEND** queues the selected track's entire 64-step state, including rests, in order. You can
  continue editing while the queue drains. Switching back to Now with unsent drafts requires an
  explicit choice to send all drafts or discard them. Disconnect also discards unsent drafts.
- **Sound LFOs**: four LFOs live in the app. Their buttons (each a small scope) sit in the Sound page's header; the
  one in assign mode opens its settings: shape, rate (1/32 to 16 bars, with triplets), phase and curve (soft to
  sharp). They run on the device's BPM, only while it plays, and carry on from the same phase after a pause.
  The LFO button lights every knob: dragging a knob then sets how much (-100..100 %) of that LFO it gets, drawn as a
  bipolar arc; hold a knob and confirm to take the LFO off it. While assigned, the app writes the knob's base value
  plus the LFO to the FM-1 through the normal parameter queue (turning the knob moves its base; a change made on
  the FM-1 becomes the new base). Assignments are kept on the phone.
- **Mixer**: per-track level, mute, and selecting the track to edit.
- **Device**: pick/connect/disconnect a MIDI device, firmware info.

Also: **FM6 patches** (list/load/store/erase, DX7 SysEx import/export from phone storage, full six-operator editor) and, for
SLOOP 2.4, per-step nudge / fill / parameter locks in the step editor. SLOOP 2.6's new parameters (INSERT, LFO 2,
ENV SYNC, ANALOG filter TYPE, global MIDI and TEXTURE) are on the Sound page; they only appear on a 2.6 device. SLOOP 2.7 (protocol v12): GLO > MIDI 3, drum TUNE / DECAY, the FM-1's real
Play / Stop state and playhead (polled through `LIVE`, command 77) and looping sample zones (zone `looped` = 2).
SLOOP 2.8 (protocol still v12, 50 globals): the punch-in FX fade (global 49) is on the Sound page's FX group and only
appears on a 2.8 device. 2.8's legato flag (`SF_LEG`, bit 7 of a step's flags) cannot be read or written over SysEx, so
editing a step of a MONO / LEGATO track retriggers a note that was recorded legato; the sequencer warns about this once.
Filter resonance is a menu setting of the FM-1 (AUDIO > FILTER RESO) and is not in the editor protocol.

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
is 51 and `versionName` is 1.7.25. For each major update increment `versionCode` and update
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
