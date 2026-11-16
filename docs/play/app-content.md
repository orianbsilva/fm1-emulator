# Play Console: App content

- **Privacy policy:** https://orianbsilva.github.io/fm1-pocket/privacy.html
- **Ads:** No, the app contains no ads.
- **App access:** All functionality is available without special access (no login).
- **Content rating (IARC questionnaire):** category *All other app types* (a music tool).
  - Violence, fear, sexuality, language, controlled substances, crude humour, gambling: none.
  - Users interact or exchange content with each other: No.
  - Shares the user's location: No. Digital purchases: No.
  - Unrestricted web access: No. The app opens only the firmware makers' own pages (their browser versions and
    editors) in a view of its own; any other link opens in the phone's browser.
  - Expected rating: Everyone / Livre.
- **Target audience:** 13 and over (not designed for children; no Families programme).
- **News app:** No. **COVID-19 tracing:** No. **Data safety:** see data-safety.md.
- **Government app:** No. **Financial features:** None. **Health:** None.
- **Category:** Music & Audio. **Tags:** music, synthesizer, MIDI.
- **Contact:** website https://orianbsilva.github.io/fm1-pocket/ ; email: the developer account's (shown on the store page).

## Notes for the review (App access / Policy questions)

Pocket Synth Emulator is an emulator of a hardware synthesizer (the M-VAVE FM-1). Like game-console emulators, it runs
programs written for that device's CPU (its firmware, "update packages", .fwsc) inside the emulator; the emulator's
dynamic recompiler (fm1-emu, MIT) translates the device's instructions to run them. These packages are not Android
code: they never call Android APIs and never run outside the emulator. The person chooses each one: from The Firmware
Hub's public list (each downloaded from its maker's GitHub release and checked against the hub's sha256), from a
GitHub repository they add, or from a file they open. Nothing is downloaded without the person's action, and the app
itself (its dex and native libraries) comes only from Google Play.

The app also writes firmware to a real FM-1 over USB-OTG with the device's own update protocol, only when the person
asks and confirms; it refuses a package without the device's update loader that the public list has not checked.
