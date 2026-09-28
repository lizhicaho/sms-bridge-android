# Third-party notices

Project-authored code is MIT licensed. Third-party code and tools retain their own licenses.

| Component | Version | Use | License / source |
| --- | --- | --- | --- |
| Kotlin stdlib | 2.1.20 | Application runtime | [Apache-2.0](https://github.com/JetBrains/kotlin/tree/v2.1.20/license) |
| JetBrains annotations | 13.0 | Kotlin dependency / annotations | [Apache-2.0](https://github.com/JetBrains/java-annotations) |
| Gradle wrapper | 8.13 | Build bootstrap | [Apache-2.0](https://github.com/gradle/gradle/tree/v8.13.0) |
| Android Gradle Plugin | 8.9.2 | Build only | [Apache-2.0](https://android.googlesource.com/platform/tools/base/) |
| JUnit | 4.13.2 | Tests only | [EPL-1.0](https://junit.org/junit4/license.html) |
| Robolectric | 4.14.1 | Tests only | [MIT](https://github.com/robolectric/robolectric/blob/robolectric-4.14.1/LICENSE) |

Kotlin: Copyright 2010–2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
JetBrains annotations: Copyright JetBrains s.r.o.
Gradle wrapper: Copyright Gradle, Inc.

See licenses/ for Apache-2.0 license texts. Gradle wrapper scripts retain their original headers. Android SDK/emulator binaries and system images are not redistributed in this repository.

Tests and build tools have additional transitive dependencies resolved by Gradle; the table describes direct declared dependencies and the packaged Kotlin runtime, not a full transitive SBOM. Inspect dependency trees with ./gradlew :app:dependencies.
