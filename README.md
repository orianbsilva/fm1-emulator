# ObsLoader

[Português](#português)

An **FM-1 emulator** for Android: the firmware itself running on the phone, its screen, knobs, buttons and keys under
your fingers, played from a MIDI keyboard, with a controller's knobs turning the panel's (MIDI learn). And the
firmwares of [The Firmware Hub](https://thefirmwarehub.com)'s list: their browser versions, and their packages
installed on a real FM-1 over USB.

- **Play (the emulator):** [ObsSynth FM-1](https://github.com/orianbsilva/obssynth-fm1)'s C code built for the phone
  (the NDK, its own simulator core, `app/src/main/cpp`), sound by AAudio at the firmware's 44.1 kHz with low latency.
  Drag a knob up or down; several fingers on the keys, sliding from one to the next; the button and key lights as the
  firmware sets them; upright or sideways. Every MIDI device the phone has plays it (notes, wheels, pressure, CC as
  over USB); a CC can be learnt by a panel knob. Its presets and settings (its flash) are kept between runs.

- **Firmwares:** the hub's list (read at most once a day), search and filters, each card with its maker's text,
  licence, source at the release's tag, flash chip and identity. Download one, or all of them for offline use: a
  package is kept only if its sha256 is the one the hub checked, and a firmware that leaves the list leaves the phone.
- **FM-1:** finds it on USB, says what it runs (or that an update was not finished), installs a package from the
  phone or a `.fwsc` file, finishes an interrupted update, and goes back to M-VAVE's V15 (from M-VAVE's own
  repository). It refuses a package without the update loader that is not on the hub's list, and a firmware that
  knows only the first FM-1s' flash chip on an FM-1 you said has the newer one.
- **On the phone:** many firmwares kept at once; the one on the FM-1 first, the last installed next, one touch
  switches.
- **MIDI keyboard:** a keyboard on the phone (USB through an OTG hub, or Bluetooth) plays the FM-1 through the phone,
  and plays the simulators: the app gives its pages Web MIDI over Android's MIDI (the WebView's own sees no device),
  so the makers' browser versions and editors reach the keyboard and the FM-1 too.
- **Browser versions:** ObsSynth FM-1's page built in (offline), and the makers' own pages.

The installer follows Felucca's (Leo Kuroshita, Hügelton Instruments; `tools/fm1_install.py` as ObsSynth FM-1
carries it): the unit tests check the Kotlin port against its bytes.

## Install

The APK is on the [releases](https://github.com/orianbsilva/fm1-emulator/releases): `ObsLoader-VERSION.apk`, signed with the project's key (each version installs over the last).
Android 8 or newer with USB host (OTG) and MIDI. Connect the FM-1 with a USB **data** cable and an OTG adapter.

## Build

```
./gradlew :app:testDebugUnitTest :app:assembleDebug     # Android SDK with platform 37, NDK 29, CMake 4.1; JDK 17+
```

## The list and the firmwares

Firmware list: The Firmware Hub – [thefirmwarehub.com](https://thefirmwarehub.com), under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), shown unchanged (filtered and sorted on the phone), by its
[rules](https://thefirmwarehub.com/kb/#catalog). Each card's text and picture belong to its maker. Each firmware
belongs to its maker, under its own licence; packages come from the makers' releases, never from the hub.
Not made by or affiliated with M-VAVE or The Firmware Hub; M-VAVE and FM-1 are trademarks of their owners.
Unofficial firmware, free and without warranty: you install it at your own risk.

## License

GPL-3.0 ([LICENSE](LICENSE)). The emulator's core and the built-in page are ObsSynth FM-1's (GPL-3.0, on Felucca and
Hortator), with their sources and credits in [obssynth-fm1](https://github.com/orianbsilva/obssynth-fm1) and
`app/src/main/cpp/cores/obssynth-fm1`.

## Português

Um **emulador do FM-1** para Android: o próprio firmware rodando no celular, com a tela, os knobs, os botões e as
teclas na ponta dos dedos, tocado por um teclado MIDI e com os knobs de um controlador girando os do painel (MIDI
learn); os presets ficam guardados. Firmwares para o **M-VAVE FM-1** no celular Android: a lista do [The Firmware Hub](https://thefirmwarehub.com), cada
pacote da release do seu autor no GitHub, instalado por USB-MIDI com um cabo OTG; e os simuladores, para experimentar
um firmware antes de ele ir para o FM-1. Baixa um ou todos (para usar offline), confere o sha256 com o do hub, instala,
termina uma atualização interrompida e volta ao V15 da M-VAVE. Guarda vários firmwares e troca entre eles com um toque; um teclado MIDI (USB por hub OTG, ou Bluetooth) toca o FM-1 pelo celular e toca os simuladores. O simulador do ObsSynth FM-1 vem embutido e toca
offline. O APK está nas [releases](https://github.com/orianbsilva/fm1-emulator/releases).
