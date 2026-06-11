# Third-party notices

Adult Game Manager project code is licensed under the Apache License, Version 2.0, except for bundled third-party components and dependencies that retain their own licenses.

## Bundled native code

### UnRAR

The source under `app/src/main/cpp/unrar/` is the UnRAR source package and is governed by its own license in `app/src/main/cpp/unrar/license.txt`.

Important UnRAR license note: UnRAR source may be used to handle RAR archives, but it may not be used to recreate the RAR compression algorithm.

### UnRAR acknowledgements

Additional acknowledgements for code used by UnRAR are in `app/src/main/cpp/unrar/acknow.txt`, including public-domain and BSD-licensed components referenced there.

## Android/Kotlin dependencies

Runtime and build dependencies are declared in:

- `settings.gradle.kts`
- `build.gradle.kts`
- `app/build.gradle.kts`

Those dependencies retain their respective upstream licenses.
