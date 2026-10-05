# Development guide

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 to 24, **21 recommended** | Gradle 9 + AGP 9. JDK 25 or newer may be too new for the toolchain |
| Android SDK | platform **36**, build-tools 35 or 36 | Platform 37 is *not* needed (see below) |
| Gradle | wrapper (9.6.1) | `./gradlew`, the distribution checksum is pinned |

Point the build at your toolchain. Put your machine's paths in `scripts/env.local.sh` (git-ignored; fish
users: `scripts/env.local.fish`) and source the script:

```bash
# scripts/env.local.sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=$HOME/Android/Sdk
export GRADLE_USER_HOME=/big/disk/gradle-home      # optional, see below

# then, in each shell
source scripts/env.sh          # fish: source scripts/env.fish
```

and create `local.properties` (also git-ignored) pointing at your SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

`GRADLE_USER_HOME` is optional. Android builds download a few GB of dependencies there, so if your home
disk is small, point it at a bigger one. Deleting the directory reclaims the space.

## Everyday commands

```bash
./gradlew build                       # compile, unit tests, lint, assemble debug and release
./gradlew test                        # unit tests only (fast, pure JVM)
./gradlew :protocol:ipp:test          # one module
./gradlew :app:installDebug           # install on a connected device or emulator
```

CI runs `./gradlew build -Pplaten.warningsAsErrors=true`. Run the same before you push.

## Trying the app without a printer

`FakeIppPrinter` is a real HTTP/IPP server. Run it on your computer:

```bash
./gradlew :testing:fake-printer:run --args="inkjet 6310"     # raster-only inkjet, shaped like a DeskJet 3700
./gradlew :testing:fake-printer:run --args="laser 6311"      # PDF-capable laser with duplex and trays
```

From the Android emulator the host machine is `10.0.2.2`, so add the printer by address as
`ipp://10.0.2.2:6310/ipp/print`. Received jobs are written to the current directory so you can open
the PWG Raster or PDF the app produced.

## Layout

See [ARCHITECTURE.md](ARCHITECTURE.md) for the module map and the dependency rules. In short:
`protocol/*` is pure Kotlin and easiest to work on; `platform/*` and `app` need the Android SDK.

## Conventions

* Kotlin, official code style (`.editorconfig` is the source of truth), 120 columns.
* Library modules use **explicit API mode**: every public declaration has an explicit visibility and type.
* Tests: JUnit 5 with `kotlin.test`. Parsers get byte-exact tests *and* malformed-input tests.
* Test data that looks like a real device dump must be marked **synthetic** unless it really is one,
  and real dumps must have serial numbers, host names, MAC and IP addresses removed.
* No new network destinations except a printer the user chose. No analytics, ads or accounts. Ever.

## Pinned versions, and why

Versions live in `gradle/libs.versions.toml`. Some are deliberately *not* the latest:

* **AndroidX** (`core-ktx` 1.18, `lifecycle` 2.10, Compose BOM 2026.06.01): newer releases require
  `compileSdk` 37. We compile against 36 so contributors do not need a preview-era SDK platform. Bump them
  together with `compileSdk` when platform 37 is a normal download.
* **Gradle 9.6.1** is the oldest version AGP 9.4 accepts, and therefore the one it is tested with.
* **Kotlin 2.3.x** matches the Kotlin that Gradle 9.x embeds for build scripts, which keeps the
  convention plugins in `build-logic` simple.

## Troubleshooting

* **"Minimum supported Gradle version is ..."**: the wrapper is pinned; use `./gradlew`, not a system `gradle`.
* **"requires libraries and applications that depend on it to compile against version 37"**: a dependency
  was bumped past what `compileSdk` 36 allows. Revert it or move everything to 37.
* **Build fails in a path with spaces**: Gradle and AGP are fine with them, but native (NDK/CMake) builds
  and some shell scripts are not. Platen has no native code today; if that changes, keep the checkout
  path free of spaces.
* **Gradle runs out of disk**: the cache lives in `GRADLE_USER_HOME` (default `~/.gradle`); delete it to reclaim space, or move it with `GRADLE_USER_HOME`.
