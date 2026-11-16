# FM-1 Pocket

[Português](#português)

An emulator of the **M-VAVE FM-1** for Android: the firmwares themselves, their update packages (`.fwsc`)
unchanged, on an emulated FM-1, played with your fingers, a MIDI keyboard or a controller's knobs. Offline once a
firmware is downloaded. Around it: [The Firmware Hub](https://thefirmwarehub.com)'s list, repositories that are not
on it yet, the makers' browser versions, and installing on a real FM-1 over USB.

**The emulation is [fm1-emu](https://github.com/joakimeriksson/fm1-emu), by Joakim Eriksson (MIT)**: the FM-1's two
JieLi cores (compiled to native code), the chip around them, the screen, keys, knobs, flash, MIDI IN and audio. This
app builds its `fm1-web` library (v0.5.1, pinned in `tools/build_fm1emu.sh`) for Android unchanged; its licence is in
the app (About) and in `app/src/main/assets/licenses/fm1-emu-MIT.txt`.

- **Play:** every firmware on the phone runs on the emulated FM-1: the hub's ones you downloaded, the repositories you
  added, M-VAVE's V15, a `.fwsc` you open, and ObsSynth FM-1 (in the app). Sideways and full screen: the screen on the
  left, the 12 buttons, the 8 knobs (drag up or down) on the right, the 27 keys below (or hidden when a MIDI keyboard
  plays). Sound by AAudio at the firmware's 44.1 kHz. Each firmware version keeps its own flash (its presets and
  settings).
- **MIDI:** every MIDI device the phone has (USB through an OTG hub, Bluetooth, an FM-1's own keys) goes into the
  emulated MIDI IN jack, as a cable would; a controller's CC can turn a panel knob (MIDI learn).
- **Firmwares:** the hub's list (read at most once a day), each card with its maker's text, licence and source; a
  package comes from its maker's GitHub release and is kept only if its sha256 is the hub's.
- **Off the hub:** add a GitHub repository (`owner/repo`): its newest release with a `.fwsc` is downloaded and new
  ones come by themselves. Nobody checked these: they play in the emulator as they are.
- **FM-1 (USB):** what a real FM-1 runs, install a package (Felucca's update protocol), finish an interrupted update,
  back to M-VAVE's V15; a MIDI keyboard through the phone to the FM-1.

## Install

The APK is on the [releases](https://github.com/orianbsilva/fm1-pocket/releases): `Pocket-Synth-Emulator-VERSION.apk`,
signed with the project's key (each version installs over the last). Android 8 or newer, 64-bit (arm64 or x86-64).

## Build

```
tools/build_fm1emu.sh                                   # fm1-emu for Android: Rust (aarch64/x86_64-linux-android), the NDK
./gradlew :app:testDebugUnitTest :app:assembleDebug     # Android SDK with platform 37, NDK 29, CMake 4.1; JDK 17+
```

## Credits

- **fm1-emu**, Joakim Eriksson (MIT): the emulator.
- **The Firmware Hub**, Sven Trogus: the firmware list, under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/),
  shown unchanged (filtered and sorted on the phone), by its [rules](https://thefirmwarehub.com/kb/#catalog).
- **Each firmware's maker**, named on its card: each firmware is theirs, under its own licence; its card's text and
  picture too.
- **Felucca**, Leo Kuroshita, Hügelton Instruments (GPL-3.0): the update protocol the installer follows, and the base
  of most firmwares.
- **ObsSynth FM-1** (GPL-3.0, in the app): on Felucca and on DEADACTIVE's Hortator.

Not made by or affiliated with M-VAVE, The Firmware Hub or fm1-emu's author. M-VAVE and FM-1 are trademarks of their
owners. Unofficial firmware, free and without warranty: you install it at your own risk.

## License

GPL-3.0 ([LICENSE](LICENSE)); fm1-emu is MIT.

## Português

Um **emulador do M-VAVE FM-1** para Android: os próprios firmwares, os pacotes `.fwsc` sem mudanças, num FM-1
emulado, tocados com os dedos, um teclado MIDI ou os knobs de um controlador; offline depois de baixados. A emulação
é o [fm1-emu](https://github.com/joakimeriksson/fm1-emu), de Joakim Eriksson (MIT). Também: a lista do The Firmware
Hub, repositórios que ainda não estão nela, as versões de navegador dos autores e a instalação num FM-1 de verdade por
USB. O APK está nas [releases](https://github.com/orianbsilva/fm1-pocket/releases).
