# Adult Game Manager

Adult Game Manager is a local-first Android companion app for tracking installed APK games, AGM-managed extracted games, and public catalog updates.

[Download the latest signed APK](https://github.com/AdvancedAppCreator/adult-game-manager/releases/latest)
| [Read the help site](https://advancedappcreator.github.io/adult-game-manager-releases/)
| [Install with Obtainium](https://advancedappcreator.github.io/adult-game-manager-releases/install-obtainium/)
| [Get support](https://github.com/AdvancedAppCreator/adult-game-manager-releases/issues)

AGM is designed for people who keep Android games, JoiPlay-compatible
Ren'Py/RPG Maker games, Windows games for Winlator, or KiriKiri games on the
same device and want one searchable library instead of separate manual lists.

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

## Launcher ecosystem

AGM remains the source of truth for its managed library while compatible
runtime apps execute games:

- [JoiPlay](https://joiplay.org/) for supported Ren'Py, RPG Maker, HTML, and
  related games.
- [Winlator Secure](https://github.com/AdvancedAppCreator/winlator-app), an
  unofficial community fork for supported Windows games and AGM integration.
- [Kirikiroid2 Community Fork](https://github.com/AdvancedAppCreator/kirikiroid2)
  for supported KiriKiri/Kirikiri Z games and AGM integration.

See the [launcher setup guide](https://advancedappcreator.github.io/adult-game-manager-releases/launcher-setup/)
for project boundaries and setup order.

## Why use AGM?

| Without a shared manager | With AGM |
| --- | --- |
| Check Android and each launcher separately | Search one local library |
| Revisit source pages to compare versions | Compare matched games with public catalog metadata |
| Remember which runtime owns each game | Launch compatible games from their AGM entry |
| Replace archives manually | Review a planned multi-file install or upgrade |
| Find engine-specific saves by hand | Use supported backup-aware Ren'Py and RPG Maker tools |

See the complete [feature comparison](https://advancedappcreator.github.io/adult-game-manager-releases/why-agm/).

## Screenshots

| Library | Catalog | Main menu |
| --- | --- | --- |
| ![AGM library](https://raw.githubusercontent.com/AdvancedAppCreator/adult-game-manager-releases/main/docs/screenshots/main-screen.png) | ![AGM catalog](https://raw.githubusercontent.com/AdvancedAppCreator/adult-game-manager-releases/main/docs/screenshots/catalog-main.png) | ![AGM main menu](https://raw.githubusercontent.com/AdvancedAppCreator/adult-game-manager-releases/main/docs/screenshots/main-menu.png) |

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
