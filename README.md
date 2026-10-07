# Adult Game Manager

Adult Game Manager is a local-first Android companion app for tracking installed APK games, AGM-managed extracted games, and public catalog updates.

## What it does

- Lists locally installed Android apps and AGM-managed extracted games.
- Runs managed games through enabled JoiPlay, Winlator, and Kirikiroid engines without using their libraries as AGM's source of truth.
- Matches local games to public catalog entries.
- Tracks known version/update status.
- Offers system, light, standard dark, and true-black OLED themes.
- Analyzes supported Unity 2019–2022 LTS texture metadata without reading streamed payloads or modifying game files, and can configure Winlator's container-private texture-limit overlay when advertised.
- Provides local Ren'Py and RPGM save discovery/editing helpers with backups.
- Provides local install/extract helpers for files the user already has.
- Installs a user-selected Ren'Py patch archive (ZIP/RAR/7Z) into an AGM-managed game only when the
  patch and exactly one installed game prove the same identity — from the patch's own options.rpy,
  or, when it ships none, from a large exact match of the Character definitions and labels its
  bounded Ren'Py source declares. Version compatibility is proven separately, and an unproven
  version is refused rather than guessed. Every replaced file is backed up with a verified rollback
  point.
- Installs several picked files in one run, deciding up front — before anything is written — which
  of them replace an installed game, which install as new, and which are skipped.
- Lets a running extraction, update or patch install be minimized to a compact progress card so the
  rest of the app stays usable. The work keeps running inside AGM; it is not a background service
  and does not survive the app being killed.

Adult Game Manager does not require an F95 login, does not download games automatically, does not bypass file hosts, and does not include analytics.

## Build

See [BUILDING.md](BUILDING.md) for prerequisites, build commands, dependency
inventory commands, signing boundaries, and artifact provenance.

GitHub Actions builds are unsigned source-validation artifacts. They are not
official releases. Official public APKs are signed and published through
GitHub Releases; separately configured Azure update feeds are private or
development distribution channels and do not establish public release
provenance.

## Privacy and permissions

Adult Game Manager is designed to work locally on the device.

- No analytics are included.
- No site login is required.
- Log upload is optional and user-initiated.
- Storage-related permissions are used only for user-selected local file, folder, install, backup, and save-tool flows.
- Broad package visibility is used to inventory installed games and discover compatible launcher/runtime apps.
- Usage access and all-files access are Android special accesses requested through system settings for optional library metadata and direct local-file workflows.

See [PRIVACY.md](PRIVACY.md) for more detail.

## Security

See [SECURITY.md](SECURITY.md) for supported versions and private vulnerability
reporting.

## License

Adult Game Manager project code is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).

Third-party components keep their original licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
