# ShareToLocate

ShareToLocate is a private, peer-to-peer location-sharing application for Android. It allows trusted contacts to exchange their real-time location directly through the Tox network, without requiring a central location server or exposing a public IP address during contact pairing.

The project is based on the concept and core workflow of zoff99's ToLoShare. ShareToLocate is a modern, independent reimplementation written for current Android versions; it does not copy the original application's source code.

## Features

- Real-time location sharing with verified contacts over Tox.
- OpenStreetMap-based map with markers for the local user and contacts.
- Map tracking modes for free navigation, the local position, or a selected contact.
- Persistent foreground location service with a visible notification and stop action.
- QR-based contact pairing without exposing the user's public IP address.
- Mutual short-code verification in `1234-5678` format.
- Persistent Tox identity and verified contact list.
- Location smoothing controls.
- Optional keep-screen-awake behavior.
- Optional screenshot protection for sensitive map data.
- Material 3 interface optimized for dark mode.
- Android 13-16 permission handling.
- Per-contact “Ring my phone” authorization and peer-to-peer attention requests.
- Configurable ring behavior: silent notification, notification with sound, or sound only.
- Automatic respect for Android Do Not Disturb and silent modes.
- Changeable application lock PIN.
- One-tap standard attention requests, with an optional custom-message menu and visible resend countdown.
- Per-contact marker colors, saved locally and rendered on the map.
- Optional phone contact links with quick Call and WhatsApp actions in attention notifications.
- Collapsible contact cards and settings categories to keep the interface focused.

## Changes and Improvements over ToLoShare

This version keeps the original idea of decentralized location sharing while replacing and extending the Android implementation. The main changes are:

- Complete Android UI rewrite using Kotlin and Jetpack Compose.
- Material 3 design with a modern dark-mode-focused interface.
- Updated Android project targeting Android 16 / API level 36.
- OpenStreetMap integration through osmdroid.
- Reworked map interaction and selectable tracking modes.
- QR-assisted contact linking.
- Mutual verification codes to reduce the risk of pairing with the wrong contact.
- Persistent foreground service designed for modern Android location restrictions.
- Explicit service notification with a user-accessible stop action.
- Updated runtime permission flow for location, background location, notifications, and foreground services.
- Encrypted local preferences for sensitive identity data.
- Disabled Android backups for private application data.
- Privacy controls for screenshots and keeping the display active.
- Release configuration for Android App Bundles, code shrinking, resource shrinking, and Play App Signing upload keys.
- Per-contact controls for remote attention alerts, available only during active location sharing.

## Privacy and Security

Location information is sent only to connected and verified contacts through Tox. ShareToLocate does not require a centralized application account or location-storage backend.

Users must compare and confirm the same short verification code during the first pairing. The application also disables backups of its private data.

Because ShareToLocate supports continued sharing while the application is not visible, it requests background location access. A Google Play release must include a clear in-app disclosure, a publicly accessible privacy policy, and the required Play Console location declaration.

See [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md) for the current privacy policy draft.

## Technology

- Kotlin
- Jetpack Compose
- Material 3
- Tox / ToxAndroidRefImpl
- OpenStreetMap and osmdroid
- Google Play Services Location
- Android DataStore and Security Crypto
- ZXing and Google Code Scanner

## Requirements

- JDK 17
- Android SDK 36
- Android 8.0 (API level 26) or newer

## Download

Download the latest signed APK from [GitHub Releases](https://github.com/CctrGy/ShareToLocate/releases/latest/download/app.apk).

Android may ask for permission to install applications from the browser or file manager used to open the APK.

## Build

To build a debug APK on Windows:

```powershell
.\gradlew.bat assembleDebug
```

The APK is generated under:

```text
app/build/outputs/apk/debug/
```

To build a release Android App Bundle:

1. Copy `release-keystore.properties.example` to `release-keystore.properties`.
2. Set the upload keystore path, alias, and passwords in the copied file.
3. Run:

```powershell
.\gradlew.bat bundleRelease
```

The signed bundle is generated under:

```text
app/build/outputs/bundle/release/
```

Never commit the release keystore or `release-keystore.properties` to version control.

## Google Play

Before publishing, review [docs/PLAY_STORE_CHECKLIST.md](docs/PLAY_STORE_CHECKLIST.md). The release requires a signed Android App Bundle, a hosted privacy policy, store listing assets, Data safety declarations, and approval for background location usage.

## Attribution

ShareToLocate was inspired by and is based on the original ToLoShare project by zoff99. The original project established the idea of sharing location through Tox; this repository provides a separate modern Android implementation with the changes listed above.

## License

Copyright (C) 2026 ShareToLocate contributors.

ShareToLocate is free software licensed under the **GNU General Public License v3.0 only** (`GPL-3.0-only`). You may use, study, copy, modify, and redistribute it under the terms of that license. Distributed modified versions must remain under GPL-3.0-only and their corresponding source code must be made available.

See [LICENSE](LICENSE) for the complete license text.

ToLoShare and ToxAndroidRefImpl are also distributed under GPL-3.0-only. Their respective copyrights remain with their original authors and contributors.

## Disclaimer

Location-sharing software handles sensitive personal data. Review the source, permissions, privacy policy, and release configuration before distributing or relying on the application.

## Support the project

If ShareToLocate is useful to you, you can follow and support its development at
[CctrGy Donations](https://cctrgy.github.io/donations.html).
