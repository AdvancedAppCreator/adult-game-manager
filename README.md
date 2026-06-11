# Adult Game Manager

Adult Game Manager is a local-first Android companion app for tracking installed APK games, imported JoiPlay games, and public catalog updates.

## What it does

- Lists locally installed Android apps and imported JoiPlay games.
- Matches local games to public catalog entries.
- Tracks known version/update status.
- Provides local Ren'Py and RPGM save discovery/editing helpers with backups.
- Provides local install/extract helpers for files the user already has.

Adult Game Manager does not require an F95 login, does not download games automatically, does not bypass file hosts, and does not include analytics.

## Build

Requirements:

- JDK 17
- Android SDK with API 34 and build tools 34.0.0

Build a debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Build a release APK:

```powershell
.\gradlew.bat assembleRelease
```

Release signing uses a local `AGM_KEYSTORE_PROPERTIES` environment variable, or `app\keystore.properties` when present. Signing files are intentionally not part of the source distribution.

Unsigned/open-source build artifacts from GitHub Actions are useful for inspection, but official APK releases are signed separately.

## Privacy and permissions

Adult Game Manager is designed to work locally on the device.

- No analytics are included.
- No site login is required.
- Log upload is optional and user-initiated.
- Storage-related permissions are used only for user-selected local file, folder, install, backup, and save-tool flows.

See [PRIVACY.md](PRIVACY.md) for more detail.

## License

Adult Game Manager project code is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).

Third-party components keep their original licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
