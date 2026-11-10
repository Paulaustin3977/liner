# Liner

Liner is an Android launcher whose home screen is a list of your apps — text
only, no icons — that you arrange the way you want them: in sections, in
your own order, under your own names, in your own typeface and colours.

* Package: `dev.liner.launcher` (the debug build installs as
  `dev.liner.launcher.debug`, so it can sit next to a release install)
* Minimum Android version: 8.0 (API 26); compiled and targeted against
  Android 15 (API 35)

## Build and install

The project is a plain Gradle build — there is no wrapper checked in yet, so
use a local Gradle 8.11.1 (or generate the wrapper with `gradle wrapper`).

```bash
gradle :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
gradle :app:test                 # the JVM unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build is signed only when a `keystore.properties` file is present
(see `keystore.properties.example`); without it, the release variant builds
unsigned.

## Testing status — read this before trusting Liner with your home screen

| Suite | Where | Status |
|---|---|---|
| JVM unit tests | `app/src/test/` | **Verified.** 202 tests, green on every build. They cover the model layer, the document codec, the edit operations, the drag maths, the style resolver, the repository and the settings logic. |
| Instrumentation tests | `app/src/androidTest/` | **NOT VERIFIED — shipped unverified.** |

The instrumentation tests (`HomeScreenTest`, `DrawerTest`, `DefaultHomeTest`)
cover the parts that need a real Android runtime: the HOME registration, the
package query, the document round trip, the repository's persistence, the
drawer list, the filter and icon loading.

**They have never been executed.** The environment that built Liner had no
emulator and no connected device, so the sources were compiled
(`assembleDebugAndroidTest` succeeds) and shipped as they are. Run them on a
device before relying on them:

```bash
gradle :app:connectedDebugAndroidTest
```

**The manual test script in [MANUAL_TEST.md](MANUAL_TEST.md) is the only
end-to-end verification of Liner that exists.** It is a numbered, step-by-step
script with an expected result for every step, and it should be run on a real
device before Liner is used as somebody's home screen.

## Documentation

- [MANUAL_TEST.md](MANUAL_TEST.md) — the manual test script
- [`docs/superpowers/specs/2026-11-10-liner-launcher-design.md`](docs/superpowers/specs/2026-11-10-liner-launcher-design.md)
  — the design spec
- [`docs/superpowers/plans/2026-11-10-liner-launcher.md`](docs/superpowers/plans/2026-11-10-liner-launcher.md)
  — the build plan
