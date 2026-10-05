# 0006. Build baseline and pinned versions

**Status:** accepted · **Date:** 2026-10-05

## Decision

| | |
|---|---|
| Language | Kotlin 2.3.x (the line Gradle 9.x embeds for build scripts) |
| Build | Gradle 9.6.1 wrapper with pinned distribution checksum; AGP 9.4.1 (built-in Kotlin) |
| JDK | 21 recommended, 17 to 24 supported; bytecode and API level restricted to Java 17 |
| Android | `minSdk` 26, `compileSdk` 36, `targetSdk` 36 |
| UI | Jetpack Compose with Material 3, Compose BOM 2026.06.01 |
| Tests | JUnit 5 + `kotlin.test`; coroutines test for async code |
| Repositories | Google and Maven Central only, no JitPack, no other repositories (F-Droid friendly, small supply chain) |
| CI actions | Pinned to commit SHAs, updated by Dependabot |

AndroidX libraries are pinned to releases that build against `compileSdk` 36; newer ones need 37.

## Consequences

* `minSdk` 26 (Android 8.0) covers well over 95% of devices and gives `java.time`, adaptive icons and
  modern USB host behaviour without desugaring.
* Pure-JVM library modules cannot accidentally use APIs newer than Java 17, so they also run on Android.
* Bumping `compileSdk` to 37 later is a deliberate step that unlocks the newest AndroidX releases.
* Details and troubleshooting: [docs/DEVELOPMENT.md](../DEVELOPMENT.md).
