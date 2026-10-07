# Notice and credits

**Sloop Go** is a native Android companion app for the M-VAVE FM-1 running the SLOOP firmware.
It is free software, released under the **GNU General Public License v3.0 only** (see [LICENSE](LICENSE)).

This app is **made on the basis of the work of others**. It would not exist without:

## SLOOP firmware — isod89

- Project: <https://github.com/isod89/sloop-fm1> (code: GPL-3.0-only)
- The firmware the app controls, and its editor protocol (SysEx over USB-MIDI, protocol versions 1–9:
  parameters, steps, tracks, locks / nudges / fills, FM6 patches). The request / reply formats in
  `android/app/src/main/kotlin/com/sloop/go/proto/` are ported from `web/EDITOR_PROTOCOL.md` and the reference
  web editor (`web/editor.html`) of that project.
- The FM6 patch model (`proto/Fm6.kt`: DX7 voice unpack / pack, SysEx import and export, the 32 algorithm
  tables) is a port of the web editor's `FM6` object.
- The app's launcher / splash icon is a vector conversion of the SLOOP icon (`assets/logo/sloop-icon.svg` in that repository).
  The SLOOP repository licenses its code under GPL-3.0-only and does not state a separate licence for its logo; see its
  `LICENSING.md`. If you reuse the icon outside this app, check with its author.

## Felucca — Leo Kuroshita (@kurogedelic) / Hügelton Instruments

- Project: <https://github.com/hugelton/Felucca> (code: GPL-3.0-only; its assets such as the icon atlas, panel image
  and Hügelton Sample Pack are all-rights-reserved and are **not** used or included in this app)
- The open firmware for the FM-1 that SLOOP is built on: the engines, the sequencer, the editor
  protocol and its port name ("Felucca"), and in 1.0 the FM6 engine and its patch commands (numbers 68–71).

## msfa / Dexed

The six-operator FM algorithm tables used by the FM6 editor come from the *msfa* engine (Google Inc. and Pascal Gauthier, Apache License 2.0)
via Dexed and Felucca. Apache-2.0 is compatible with GPL-3.0.

## Third-party libraries

Android Jetpack (Compose, Activity, Lifecycle) and Kotlin / kotlinx.coroutines, under the Apache License 2.0.

## Disclaimer

Sloop Go is an independent project. It is not made by, affiliated with, or endorsed by the authors above or by
M-VAVE. "FM-1", "SLOOP", "Felucca" and "DX7" belong to their respective owners. The software is provided
without warranty, as stated in the GPL; use it at your own risk and keep a backup of your FM-1 before writing
to it.
