# Liner v1.0

The first release of **Liner** — an Android launcher whose home screen is a
list of words instead of icons.

## What it is

Pick the apps you care about, arrange them into sections you name, and tune
every pixel of the result: fonts, sizes, colours, spacing, background,
alignment. Swipe up from the bottom edge for a drawer with every app on the
device.

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

## Installing

1. Download `liner-release.apk` from this release.
2. Copy it to your phone and tap it. Allow "install from unknown sources".
3. Open Liner, then follow the prompt to make it your default launcher.

The debug build installs alongside the release as `dev.liner.launcher.debug`.

## Before you trust it with your home screen

**Please read the known limitations in the README first.** Two of them matter:

- **The instrumentation tests were never executed.** The build environment had
  no emulator or device, so they are shipped as source and unverified. Nothing
  in this release should be read as "tested on a device".
- **`MANUAL_TEST.md` is the only end-to-end verification that exists.** It is
  a step-by-step script for a human to run on a real phone. Run it before
  Liner takes over somebody's home screen.

The unit tests — 239 of them — do run headless and are green on every build:

```bash
./gradlew test
```

## A note on the signing key

The APK in this release is signed with a **throwaway demo keystore** whose
passwords are public. The keystore itself is not in the source tree and is not
in this release. It protects nothing — anyone could sign an update that a
device would accept as the same app. **A real release must be signed with a
private keystore you generate, keep and back up yourself.** The README shows
how.

## Licence

MIT. See [LICENSE](https://github.com/Paulaustin3977/liner/blob/main/LICENSE).
