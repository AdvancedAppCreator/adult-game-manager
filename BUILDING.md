# Building and artifact provenance

## Requirements

- JDK 17
- Android SDK platform 34 and build tools 34.0.0
- CMake 3.22.1 and an Android NDK capable of building the configured native code

Use the checked-in wrapper. Its Gradle 8.7 distribution is protected by
`distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`.

On Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest lintDebug
```

On macOS or Linux, mark the checked-in wrapper executable after obtaining a
source archive, then use it with the same tasks:

```sh
chmod +x ./gradlew
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

## Release builds and signing

`assembleRelease` builds the release variant. Signing material is never stored
in this repository. A maintainer can point `AGM_KEYSTORE_PROPERTIES` to an
external properties file, or place a local ignored
`app/keystore.properties` file. If neither exists, Gradle produces an unsigned
release APK.

The public GitHub Actions workflow intentionally receives no signing material
and uploads an artifact named `adult-game-manager-unsigned-apk`. This artifact
is for source-build inspection and testing; it is not an official release.

Official public APKs are signed outside public CI and published through GitHub
Releases. Verify an official APK against the release's published checksum and
expected Android signing certificate before installing it. An APK obtained
from an independently configured Azure update feed is a private/development
update, not a GitHub official release, even when built from the same source.

## Dependency inventory

The direct dependency declarations are in `build.gradle.kts` and
`app/build.gradle.kts`. To inspect the resolved graphs without changing them:

```powershell
.\gradlew.bat app:dependencies
.\gradlew.bat app:buildEnvironment
```

The repository currently does not check in Gradle dependency locks or
verification metadata. Android builds resolve several plugin, platform/BOM,
native, test, and variant-specific graphs; incomplete generated metadata would
give a misleading assurance or break valid variants. Add those controls only
as a separately reviewed change that resolves and validates every supported
CI/build configuration.

## Public application configuration

`app/src/main/assets/app_config.json` is the public baseline configuration
packaged in the APK. It contains public catalog/update endpoints, support
contact, and donation destinations. User-imported `app_config.json` fields
override this baseline. Keep credentials, SAS tokens, and private diagnostics
endpoints out of the bundled file.
