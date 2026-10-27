# Third-party notices

Original project code and documentation: Copyright (C) 2026 小狗便利店投屏 contributors, licensed under GPL-3.0-only. See LICENSE. Third-party components below retain their upstream licenses; the project's GPL notice does not replace those licenses.

## Runtime components

| Component | Version / scope | Copyright and license | Upstream |
|---|---|---|---|
| AndroidX Media3 | 1.9.4, including ExoPlayer, HLS, DASH, UI and their Media3 dependencies | The Android Open Source Project; Apache-2.0 | https://github.com/androidx/media |
| Other AndroidX libraries | Transitive annotation, collection, core, lifecycle, arch, concurrent, versionedparcelable, exifinterface, recyclerview and customview modules; exact versions resolved by Gradle | The Android Open Source Project; Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library | 2.0.20, transitive runtime dependency | JetBrains s.r.o. and Kotlin Programming Language contributors; Apache-2.0 | https://github.com/JetBrains/kotlin |
| JetBrains Java annotations | 13.0, transitive dependency | JetBrains s.r.o.; Apache-2.0 | https://github.com/JetBrains/java-annotations |
| Guava | 33.3.1-android | The Guava Authors; Apache-2.0 | https://github.com/google/guava |
| failureaccess | 1.0.2, Guava dependency | The Guava Authors; Apache-2.0 | https://github.com/google/guava/tree/master/futures/failureaccess |
| listenablefuture | 9999.0-empty-to-avoid-conflict-with-guava, empty compatibility artifact | The Guava Authors; Apache-2.0 | https://github.com/google/guava |
| desugar_jdk_libs | 2.1.5, Android core-library desugaring | Upstream OpenJDK / Android contributors; GPL-2.0 with the Classpath Exception as specified upstream | https://github.com/google/desugar_jdk_libs |

Copies of Apache-2.0, the original desugar JDK license, and GPL-3.0 are included in app/src/main/assets/licenses/. Preserve all upstream notices when redistributing dependencies. The desugar license file includes the Classpath Exception.

To inspect the resolved runtime dependency tree, run:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

## Build wrapper

Gradle Wrapper scripts (gradlew, gradlew.bat) and gradle/wrapper/gradle-wrapper.jar are from Gradle 8.13, licensed under Apache-2.0. Their original notices are retained. Upstream: https://github.com/gradle/gradle/tree/v8.13.0 . Apache-2.0 text is in app/src/main/assets/licenses/Apache-2.0.txt.

This source distribution does not contain an Android SDK, JDK, Gradle distribution or commercial casting SDK. SDK and build dependencies are obtained separately by the developer.

## Media and protocol

The README screenshot was captured from this application's Android emulator. Automated core tests use synthetic data and example.invalid URLs, not real platform access tokens. No third-party programme content or live-stream lists are included.

DLNA / UPnP describe interoperability protocols here, not certification or sponsorship. Mentions of Sony, Z视介, 小红书 and 抖音 document limited user-reported compatibility; this project is not affiliated with those products.

## Optional mirror experiment module

The optional `mirror` Android library, integrated into the combined application, reuses AirTV / UxPlay-derived code and links OpenSSL 3.0.22 and libplist 2.6.0 rebuilt from the bundled pinned upstream sources. Its codecs run only in foreground AirPlay mode; the DLNA mode retains its existing player. Source provenance, modifications, component licenses and source archives are documented in [mirror/AIRPLAY-NOTICES.md](mirror/AIRPLAY-NOTICES.md).

## Independent upstream comparison experiment

The optional `reference` application is a separate-install GPL-3.0 comparison build based on jqssun/android-airplay-server v0.0.36. It has no dependency on `mirror`, uses Media3 1.11.1, and builds its UxPlay, FFmpeg, libplist, OpenSSL and Oboe components from bundled pinned source archives. This is not a main-app release. Full versions, original license notices, modifications and rebuild instructions: [reference/NOTICES.md](reference/NOTICES.md), [reference/README.md](reference/README.md), and `reference/vendor/sources.json`.
