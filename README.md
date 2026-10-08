# Sloop Go

A native Android app (Kotlin + Jetpack Compose) for the **M-VAVE FM-1** running the **SLOOP** firmware.
It talks to the device directly over USB-MIDI through Android's `MidiManager` — no WebView, no JavaScript bridge —
so edits reach the FM-1 immediately.

<table>
  <tr>
    <td><img src="screenshots/1.png" alt="Sound editor" /></td>
    <td><img src="screenshots/2.png" alt="Sequencer" /></td>
    <td><img src="screenshots/3.png" alt="FM6 patches" /></td>
  </tr>
  <tr>
    <td><img src="screenshots/4.png" alt="Samples" /></td>
    <td><img src="screenshots/5.png" alt="Mixer" /></td>
    <td><img src="screenshots/6.png" alt="Settings" /></td>
  </tr>
</table>

> **Made on the basis of the work of others.** Sloop Go is a companion app developed for
> [**SLOOP**](https://github.com/isod89/sloop-fm1) by **isod89**, which in turn builds on
> [**Felucca**](https://github.com/hugelton/Felucca) by **Leo Kuroshita / Hügelton Instruments**.
> The editor protocol, the FM6 patch format and the firmware itself are their work — this app would not
> exist without them. See [NOTICE.md](NOTICE.md).

## Features

- **Sound** — all sound parameters in collapsible blocks with touch knobs, engine / kit and preset pickers, track selector.
- **Sequencer** — drum grid and synth piano-roll with pinch zoom, Live / Store (draft) modes, voice modes, step nudge /
  fill / parameter locks (SLOOP 2.4).
- **FM6 patches** (SLOOP 2.4) — slot library, DX7 SysEx import / export from phone storage, upload to the bank or to a
  track, and a full six-operator editor.
- **Mixer** and **Device** pages, Play / Stop over USB-MIDI.

Requires a phone with USB host (OTG) and a USB-C cable to the FM-1. Written for SLOOP 2.4 (editor protocol v9);
features that need newer firmware are hidden or disabled on older SLOOP.

## Build

```bash
./build-android.sh            # debug APK
./build-android.sh release    # release APK (signed with the debug key unless you configure your own)
```

Needs JDK 17+ and the Android SDK (platform 34). Details and architecture: [android/README.md](android/README.md).

## License

Copyright (C) 2026 Sloop Go contributors.

This program is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License, version 3** as published by the Free Software Foundation. It is distributed in the hope
that it will be useful, but **WITHOUT ANY WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

Writing to a device can overwrite its data: back up your FM-1 first and use this software at your own risk.
