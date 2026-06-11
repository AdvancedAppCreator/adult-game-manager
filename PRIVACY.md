# Privacy and permissions

Adult Game Manager is designed as a local-first Android app.

## Network behavior

- The app downloads public catalog/update metadata from the configured public release URLs.
- The app opens user-facing web pages only when the user taps a link or action.
- The app does not require an F95 login.
- The app does not include analytics or background telemetry.
- The app does not automatically download games.

## Logs and diagnostics

- Logs are kept locally unless the user explicitly chooses a save/upload action.
- Public builds do not enable crash/log upload by default.
- Optional upload endpoints can be configured by a user-supplied local config file.

## Storage permissions

Storage access is used for user-selected local workflows:

- importing/exporting AGM backups,
- importing JoiPlay backup metadata,
- scanning local JoiPlay game folders,
- opening local folders in a file manager,
- extracting archives selected by the user,
- installing APKs selected by the user,
- finding and editing local Ren'Py/RPGM save files.

## App/package inspection

The app scans locally installed packages so it can show installed games and compare them against public catalog entries.

## No game-downloader behavior

Adult Game Manager does not bypass file hosts, scrape private download links, or automatically download games. It is a local tracker and local file/save helper.
