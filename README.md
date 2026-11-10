# Liner

An Android launcher whose home screen is a list of words instead of icons.

Pick the apps you care about, arrange them into sections you name, and tune
every pixel of the result — fonts, sizes, colours, spacing, background,
alignment. Swipe up from the bottom edge for a drawer with every app on the
device.

- Package: `dev.liner.launcher`. The debug build installs as
  `dev.liner.launcher.debug`, so it can sit next to a release install.
- Requires Android 8.0 (API 26) or newer. Built against Android 15 (API 35).

## Install

1. Download `liner-release.apk`.
2. Copy it to your phone and tap it. Allow "install from unknown sources".
3. Open Liner, then follow the prompt to make it your default launcher.

The first launch shows a one-time prompt offering to make Liner your home
app. It goes away for good once you dismiss it or once Liner *is* the default
home app. If you dismiss it, you can still make Liner your default launcher
later from Settings → Advanced.

## Build from source

```bash
cp local.properties.example local.properties   # then set sdk.dir
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK with platform 35 and build-tools 35.0.0.
The first build downloads the Gradle distribution and the dependencies from
the network; after they are cached the build works offline.

`./gradlew` is the Gradle wrapper, and it is the supported way to build: it
pins the Gradle version, so a build does not depend on what is installed on
your machine.

### Building a signed release

```bash
keytool -genkeypair -v -keystore my-release.jks -alias liner \
  -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties   # then fill it in
./gradlew assembleRelease
```

`keystore.properties` is read at configuration time and must contain all
four keys:

```properties
storeFile=my-release.jks
storePassword=…
keyAlias=liner
keyPassword=…
```

A missing key fails the build with a message naming the key. Without a
`keystore.properties` the release variant still builds, but it is **not
signed** and cannot be installed. The debug build needs no keystore at all.

> **The keystore that signed the APK in this delivery is a throwaway demo
> keystore.** It was generated for the build, its passwords are public, and
> neither the keystore nor its properties are in the source tree — they are
> gitignored, and the source zip leaves them out. A key whose passwords
> anybody can read protects nothing: anyone could sign an update that a
> device would accept as the same app. **A real release must be signed with
> a private keystore that you generate, keep and back up yourself** — and
> never share it.

## What's in the box

| File | What it is |
|---|---|
| `liner-release.apk` | Signed release build, ready to install |
| `liner-debug.apk` | Debug build |
| `liner-source.zip` | The full Gradle project |
| `README.md` | This file |
| `MANUAL_TEST.md` | The manual test script |

The source zip is the project as it is in version control: the Gradle
wrapper, the sources, the tests and the documentation. It does not contain
the built APKs, the keystore or any local build output.

## Features

- A home screen that is one scrolling column of text — no icons, no grid
- Sections with names you choose, and per-item renames
- Drag to reorder, with a live insertion indicator
- A drawer of every installed app, with a filter field
- Full theming: background (solid / gradient / wallpaper), text colour, font
  family, size, weight, letter spacing, row spacing, padding, alignment,
  clock and date styles, section header styling, optional icons
- Per-item style overrides that beat the global theme
- A one-time prompt on the first launch, offering to make Liner your home app
- Import and export your layout as JSON
- Handles uninstalled apps, corrupt saves and failed writes without crashing

## Testing

```bash
./gradlew test
```

The unit tests run headless — 211 of them, covering the model layer, the
document codec, the edit operations, the drag maths, the style resolver, the
repository and the settings logic.

The instrumentation tests under `app/src/androidTest` **were compiled but
not executed** — the build environment had no emulator or device. Run them
on a device with `./gradlew connectedDebugAndroidTest`.

`MANUAL_TEST.md` is a step-by-step script for checking the app on a real
phone. It is the only end-to-end verification of Liner that exists, so run it
before Liner takes over somebody's home screen.

## Known limitations

- **The instrumentation tests were never executed.** They were written and
  they compile, but no emulator or device was available to run them on, so
  they are shipped unverified. Nothing in this release should be read as
  "tested on a device".
- The launcher's own behaviour on a real device — gestures, the drawer, the
  drag-to-reorder, the first-run prompt — is verified only by
  `MANUAL_TEST.md`, which is a script for a human to run. The instrumentation
  sources cover the drag gesture and the drawer swipe as well, but they have
  never run, so they are not a second opinion.
- Icon rendering uses `loadIcon` and rasterises to a bitmap; adaptive icons
  are rendered by the system, so their appearance may differ slightly
  between OEM implementations.
- The release APK is signed with the throwaway demo keystore described
  above. It is fine for a demo; it is not fine for a real release.
- Liner keeps no hidden state: everything you can see is in the layout
  document, which is why the document can be exported and imported as JSON.

## Permissions

`QUERY_ALL_PACKAGES` is declared because a launcher must enumerate installed
apps in order to launch them. This is the standard exemption granted to
launchers and is covered by the Play Store policy for apps that register for
`CATEGORY_HOME`.

## Documentation

- [`MANUAL_TEST.md`](MANUAL_TEST.md) — the manual test script
- [`docs/superpowers/specs/2026-11-10-liner-launcher-design.md`](docs/superpowers/specs/2026-11-10-liner-launcher-design.md)
  — the design spec the app was built from
- [`docs/superpowers/plans/2026-11-10-liner-launcher.md`](docs/superpowers/plans/2026-11-10-liner-launcher.md)
  — the build plan

## License

MIT.
