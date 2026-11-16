# Play Console: Data safety

- **Does your app collect or share any of the required user data types?** No.
  - The app has no account, ads, analytics, crash reporting or tracking SDKs.
  - What it downloads (the hub's list, firmware packages, the makers' pictures, the releases of repositories the person
    adds) are plain HTTPS requests for content; nothing about the person is sent, stored or logged by the developer.
  - Everything the app keeps (downloaded firmwares, emulator saves, settings) stays in its own storage on the device.
- **Is all of the user data collected by your app encrypted in transit?** (not asked when nothing is collected) Every
  connection is HTTPS.
- **Do you provide a way for users to request that their data is deleted?** Nothing is collected; uninstalling removes
  everything the app kept on the device.
- **Privacy policy URL:** https://orianbsilva.github.io/fm1-pocket/privacy.html
