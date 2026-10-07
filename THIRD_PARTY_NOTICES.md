# Third-party notices

Adult Game Manager project code is licensed under the Apache License, Version 2.0, except for bundled third-party components and dependencies that retain their own licenses.

## Bundled native code

### UnRAR

The source under `app/src/main/cpp/unrar/` is the UnRAR source package and is governed by its own license in `app/src/main/cpp/unrar/license.txt`.

The checked-in `version.hpp` identifies this snapshot as UnRAR 7.11
(20 March 2025). Its complete bundled license and acknowledgements are retained
next to the source. The repository does not contain an upstream archive hash or
an import manifest, so it does not claim independently reproducible
file-by-file provenance for this snapshot.

Important UnRAR license note: UnRAR source may be used to handle RAR archives, but it may not be used to recreate the RAR compression algorithm.

### UnRAR acknowledgements

Additional acknowledgements for code used by UnRAR are in `app/src/main/cpp/unrar/acknow.txt`, including public-domain and BSD-licensed components referenced there.

## Android/Kotlin dependencies

Runtime and build dependencies are declared in:

- `settings.gradle.kts`
- `build.gradle.kts`
- `app/build.gradle.kts`

Those dependencies retain their respective upstream licenses.

For the exact resolved graph used by a local checkout, run the Gradle dependency
inventory commands in [BUILDING.md](BUILDING.md). The declarations and that
generated graph are dependency inventories, not a comprehensive SBOM. No
generated SBOM is currently checked into this repository.
