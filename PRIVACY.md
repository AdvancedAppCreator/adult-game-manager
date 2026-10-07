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

On Android 11 and later, direct access to user-selected game folders may require
the **All files access** special access (`MANAGE_EXTERNAL_STORAGE`). The app
opens Android's system settings for the user to grant it; the app cannot grant
it to itself. On older Android versions, the app declares the legacy read
permission through Android 12L and the legacy write permission through Android
10. Storage Access Framework pickers are also used where supported.

## App/package inspection

The app declares broad package visibility (`QUERY_ALL_PACKAGES`) so it can scan
locally installed packages, show installed games, compare them with public
catalog entries, and discover compatible JoiPlay, Winlator, and Kirikiroid
components.

The optional **Usage access** special access (`PACKAGE_USAGE_STATS`) is used to
show last-used and per-package storage information. Without it, those fields
are unavailable; the installed-app inventory still works.

`REQUEST_INSTALL_PACKAGES` supports Android-confirmed installation of APKs the
user selected, including an update APK. `REQUEST_DELETE_PACKAGES` supports
user-initiated uninstall flows that Android confirms. AGM does not silently
install or remove packages.

## Network and integration permissions

`INTERNET` and network-state access support public catalog/update checks,
user-opened web content, and optional diagnostics upload when a local
configuration enables it. Custom migration and Kirikiroid launch permissions
declared by the app are integration contracts with companion apps; they do not
grant Android platform storage, package, or network privileges.

Support email and donation links open only after the user selects them. They
are public destinations from the bundled `app_config.json`; AGM does not send
contact details or payment information itself.

## No game-downloader behavior

Adult Game Manager does not bypass file hosts, scrape private download links, or automatically download games. It is a local tracker and local file/save helper.
