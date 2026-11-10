# Liner — Design Spec

**Date:** 2026-11-10
**Status:** Approved by user (design sections 1–6)
**Package:** `dev.liner.launcher`
**Artifact:** Installable Android APK + source + README

---

## 1. Summary

**Liner** is a replacement home screen for Android. The home screen is a single
scrolling column of text: a clock and date header, then the user's chosen apps
rendered as plain labels — no icons, no folders, no grid. The user picks which
apps appear, in what order, grouped under optional section headers they name.

Swiping up from the bottom edge opens a drawer containing every launchable app
on the device, so anything not on the home list remains reachable.

Customization is the core of the product, not an afterthought. The entire
screen is described by a single serializable **layout document**; the settings
UI edits that document. Appearance (fonts, colors, spacing, background,
alignment) and structure (sections, ordering, per-item renames and style
overrides) are equally editable, and the document can be exported and
imported as JSON.

---

## 2. Goals

| # | Goal |
|---|------|
| G1 | Home screen shows a curated, icon-free, text list of the user's apps |
| G2 | Full launcher behaviour: can be set as the default Home app, and provides a drawer with every installed app |
| G3 | Every visual property of the home screen is user-adjustable |
| G4 | The *structure* of the screen is user-editable — sections, ordering, renames, per-item style overrides |
| G5 | Layout is data, so the customization surface can grow without a rewrite |
| G6 | Survives app uninstall/rename, config corruption, and process death without losing the user's setup |

## 3. Non-goals

- **No widgets.** The home screen shows clock, date, and app labels only.
- **No folders, no icon grid.** The drawer is the icon-bearing surface.
- **No cloud sync / accounts.** Import and export are manual file/clipboard operations.
- **No search-first launch flow.** The drawer has a simple filter field, not a fuzzy type-to-launch engine.
- **No multi-page canvas.** One scrolling column only.
- **No onboarding tour.** A single first-run prompt to set Liner as default home.

---

## 4. Architecture

### 4.1 Build configuration

| Setting | Value |
|---|---|
| Language | Kotlin 2.0.x |
| UI | Jetpack Compose (BOM 2024.10.x) |
| Min SDK | 26 (Android 8.0) |
| Compile / Target SDK | 35 |
| AGP | 8.7.x |
| Gradle | 8.11.1 |
| Serialization | kotlinx-serialization-json 1.7.x |
| Persistence | androidx.datastore:datastore-preferences 1.1.x |
| JVM target | 17 |

### 4.2 Module layout

Single Gradle module (`:app`). Boundaries are enforced by package structure,
not by Gradle modules — one module keeps the build simple while the package
layout keeps responsibilities separated.

```
dev.liner.launcher
├─ model/       Pure Kotlin: LayoutDocument, Section, HomeItem, ThemeTokens,
│               InstalledApp, ResolvedStyle, plus the pure edit operations
│               (reorder, move, rename, remove) and the pure drag-target
│               maths. ZERO Android dependencies → unit-testable on the JVM.
├─ store/       LayoutRepository: DataStore<LayoutDocument> backed by a single
│               JSON string. Exposes StateFlow<LayoutDocument>.
├─ apps/        InstalledAppsRepository: PackageManager queries → InstalledApp
│               list + icon loading + label resolution.
├─ ui/
│  ├─ home/     HomeScreen, HomeViewModel, LayoutRenderer, EditModeController.
│  ├─ drawer/   DrawerSheet, DrawerViewModel, filter logic.
│  ├─ settings/ SettingsActivity screens: Appearance, Apps, Sections,
│  │            ImportExport, Advanced.
│  └─ common/   Shared composables (Label, SectionHeader, ColorPickerRow…).
├─ platform/    LauncherActivity, LauncherTheme, gestures, launch intents,
│               default-home detection.
```

### 4.3 Dependency rule

`model` depends on nothing but the Kotlin stdlib and kotlinx-serialization.
`store`, `apps`, and `ui` may depend on `model`. `model` may never depend on
anything else. This is what makes the interesting logic testable without an
emulator, and it is checked by the unit tests living in `model`'s own package.

---

## 5. Data model

All types are `@Serializable` and live in `dev.liner.launcher.model`.

```kotlin
@Serializable
data class LayoutDocument(
    val version: Int = 1,
    val sections: List<Section> = emptyList(),
    val theme: ThemeTokens = ThemeTokens(),
    val hiddenFromDrawer: List<String> = emptyList(),  // package names
    val autoPruneUninstalled: Boolean = false,
)

@Serializable
data class Section(
    val id: String,                 // stable UUID, survives reordering
    val title: String?,             // null → untitled grouping, no header row
    val items: List<HomeItem> = emptyList(),
)

@Serializable
data class HomeItem(
    val id: String,                 // stable UUID
    val packageName: String,        // target app
    val label: String?,             // custom rename; null → app's own label
    val style: ItemStyle? = null,   // per-item override; null → global theme
)

@Serializable
data class ItemStyle(
    val textSizeSp: Float? = null,
    val color: ColorSpec? = null,
    val fontWeight: String? = null, // "100".."900"
    val letterSpacingEm: Float? = null,
)

@Serializable
data class ThemeTokens(
    val background: BackgroundSpec = BackgroundSpec.Wallpaper(dim = 0.35f),
    val textColor: ColorSpec = ColorSpec.FollowSystem,
    val listFontFamily: String = "sans-serif",     // FontFamily-compatible key
    val listTextSizeSp: Float = 20f,
    val listFontWeight: String = "400",
    val letterSpacingEm: Float = 0f,
    val itemSpacingDp: Float = 10f,
    val screenPaddingHdp: Float = 24f,
    val screenPaddingVdp: Float = 16f,
    val alignment: String = "start",                // start | center | end
    val clockStyle: String = "large",               // hidden | large | small
    val clockFormat: String = "system",             // system | 12h | 24h
    val dateStyle: String = "medium",               // none | short | medium | long
    val sectionTitleSizeSp: Float = 13f,
    val sectionTitleColor: ColorSpec = ColorSpec.FollowSystem,
    val sectionTitleUppercase: Boolean = true,
    val sectionTitleRule: Boolean = true,
    val showIcons: Boolean = false,
    val iconSizeDp: Int = 24,
    val drawerStyle: String = "icons",               // icons | text
)

@Serializable
sealed interface ColorSpec {
    @Serializable @SerialName("solid") data class Solid(val argb: Int) : ColorSpec
    @Serializable @SerialName("system") data object FollowSystem : ColorSpec
}

@Serializable
sealed interface BackgroundSpec {
    @Serializable @SerialName("solid") data class Solid(val color: ColorSpec) : BackgroundSpec
    @Serializable @SerialName("gradient") data class Gradient(
        val from: ColorSpec, val to: ColorSpec, val angleDegrees: Int = 90,
    ) : BackgroundSpec
    @Serializable @SerialName("wallpaper") data class Wallpaper(val dim: Float = 0.35f) : BackgroundSpec
}
```

### 5.1 Edit operations (pure, in `model`)

All mutation happens through pure functions on `LayoutDocument`; the repository
applies the returned document to DataStore. Keeping these pure is what makes
the reorder/move/section logic testable without Android.

```kotlin
fun LayoutDocument.moveItem(itemId: String, toSectionId: String, toIndex: Int): LayoutDocument
fun LayoutDocument.removeItem(itemId: String): LayoutDocument
fun LayoutDocument.renameItem(itemId: String, label: String?): LayoutDocument
fun LayoutDocument.setItemStyle(itemId: String, style: ItemStyle?): LayoutDocument
fun LayoutDocument.addItem(item: HomeItem, sectionId: String, index: Int = Int.MAX_VALUE): LayoutDocument
fun LayoutDocument.reorderHomeItems(flatOrder: List<String>): LayoutDocument
fun LayoutDocument.addSection(id: String, title: String?): LayoutDocument
fun LayoutDocument.renameSection(sectionId: String, title: String?): LayoutDocument
fun LayoutDocument.removeSection(sectionId: String): LayoutDocument
fun LayoutDocument.moveSection(sectionId: String, toIndex: Int): LayoutDocument
fun LayoutDocument.setHiddenFromDrawer(packageName: String, hidden: Boolean): LayoutDocument
fun LayoutDocument.setAutoPruneUninstalled(enabled: Boolean): LayoutDocument
fun LayoutDocument.updateTheme(transform: (ThemeTokens) -> ThemeTokens): LayoutDocument
```

Two semantics worth stating explicitly, because they are the ones most
likely to be got wrong:

- **`moveItem`** removes the item from wherever it is, then inserts it at
  `toIndex` *within the target section's post-removal item list*. `toIndex`
  is clamped to the valid range; an unknown `toSectionId` is a no-op.
- **`reorderHomeItems`** takes the desired flat order of home item ids and
  reorders items *within their existing sections* to match it. It never
  moves an item between sections — that is `moveItem`'s job. This is how the
  Settings → Apps tab reorders: it presents the home items flattened
  across sections, and a reorder there produces a new flat order.

The caller supplies the UUID for `addSection` and `addItem`, keeping `model`
free of nondeterministic generation and therefore deterministic under test.

### 5.2 Resolved appearance

`model` also exposes `resolveItemStyle(theme, itemStyle)` and
`resolveSectionHeaderStyle(theme)`. Each returns a plain `ResolvedStyle`
data class whose fields are framework-free — `fontKey: String`,
`sizeSp: Float`, `weight: Int`, `letterSpacingEm: Float`, `colorArgb: Int?` —
which the `ui` layer maps onto Compose `TextStyle` and `Color`. `model` stays
free of Compose types, so the dependency rule in §4.3 holds.

The UI never inspects raw theme tokens — it only consumes resolved styles.
This keeps override precedence in one testable place:
**per-item override > global theme > system default**.

One exception to "concrete ARGB": `ColorSpec.FollowSystem` cannot be resolved
to a fixed colour without knowing the current configuration, so `model`
resolves it to a sentinel and the `ui` layer substitutes the real colour using
`isSystemInDarkTheme()`. Everything else resolves fully inside `model`.

---

## 6. Screens and interactions

### 6.1 Home screen (`LauncherActivity`)

Composition, top to bottom:

1. **Clock + date header.** The clock is hidden entirely when
   `clockStyle = hidden`. When visible: `large` renders the time at 64sp,
   weight 300, with the date beneath at 18sp; `small` renders the time at
   24sp, weight 400, with the date beneath at 14sp. Clock color follows
   `textColor`. Date format follows `dateStyle`: `none` hides it, `short`
   is `EEE d MMM`, `medium` is `EEEE, d MMMM`, `long` is `EEEE, d MMMM yyyy`.
2. **Sections and items**, in a `LazyColumn`.
3. **Bottom gesture zone.** A transparent strip across the bottom ~12% of the
   screen that captures an upward drag to open the drawer.

Interactions:

| Gesture | Result |
|---|---|
| Tap item | Launch the app |
| Tap item (app uninstalled) | Toast "App not installed" |
| Long-press item | Enter edit mode for that item |
| Long-press (edit mode) | Drag to reorder within its section |
| Drag item to top edge (edit mode) | Move into the section immediately above it in document order; if it is already in the first section, it moves to the **top** of that section, since there is no section above to move into |
| Drag item to trash zone (edit mode) | Remove from home (undoable via Snackbar) |
| Tap item (edit mode) | Open rename sheet |
| Long-press section header | Sheet: rename section / delete section |
| Swipe up from bottom zone | Open drawer |
| Back | Close drawer; otherwise leave the app |

**Edit mode** is entered by long-press and exited by tapping empty space or
pressing back. While active, items lift slightly (scale 1.02), a trash zone
appears at the bottom of the screen, and the clock/date dim.

#### Drag model

The drag does **not** reorder the list live under the finger. Instead:

1. The dragged item stays in its slot but renders faded, and a horizontal
   insertion indicator line animates to the slot the item would land in.
2. The target is computed by pure function `DragTargeting.computeTarget(dragY,
   bounds, draggedId, sectionOrder)`, where `bounds` is the list of on-screen
   item rectangles in root coordinates — its order does not matter, the
   computation sorts by vertical position.
3. On release the reorder is committed in one `moveItem` call.

This keeps the gesture maths out of the rendering layer, makes it unit-
testable on the JVM, and avoids the classic live-reorder problem where the
item jumps under the finger as the list reflows. The computation lives in
`model` as `DragTargeting` and returns either `InSection(sectionId, index)`
or `PreviousSection(sectionId)` — the latter when the drag is dragged above
its own section's first item and a preceding section exists.

### 6.2 Drawer

A full-screen sheet listing every launchable app on the device.

- **Filter field** at the top. Plain case-insensitive substring match on the
  app label, matched against the custom rename too when the item is on the
  home list. A text field at the top; results update per keystroke.
- Default style is a grid of app icons (this is the one icon-bearing surface):
  4 columns, adapting down to 3 on narrow screens, icon size 48dp with the
  label beneath at 12sp. `drawerStyle = text` switches it to a single-column
  text list at 18sp, for consistency with home.
- The sheet uses the same background as the home screen, with a scrim over it
  so the list stays legible, and the app's own label (not the home rename) —
  the drawer lists the real apps, the home screen is the personalised one.
  Back closes it.
- Tapping an app launches it and dismisses the drawer.
- The drawer's own Settings entry is always present at the bottom.

### 6.3 Settings (`SettingsActivity`)

A separate activity (with `LAUNCHER` category so it appears in the drawer),
opened from the drawer or from the home screen's overflow. It accepts an
intent extra `tab` (`appearance` | `apps` | `sections` | `importexport` |
`advanced`) so the home screen's empty state can deep-link straight to the
Apps tab.

Tabs:

**Appearance**
- Background: solid / gradient (two stops + angle) / wallpaper + dim scrim (0–90%)
  - Gradient angle: measured clockwise from vertical, so `0` runs top-to-bottom
    (`from` at the top, `to` at the bottom), `90` runs left-to-right, `180`
    runs bottom-to-top.
- Text color: solid picker, or follow light/dark
- List font family: `sans-serif`, `serif`, `monospace`, `cursive` — only
  genuinely distinct families. The platform's light/medium/condensed faces
  cannot be referenced from Compose without bundling font files as resources,
  so offering them would present choices that render identically.
- List text size 12–48sp; weight 100–900; letter spacing −0.05 to 0.2em
- Row spacing 0–32dp; horizontal/vertical screen padding 0–48dp
- Alignment: start / center / end
- Clock: hidden / large / small; format system / 12h / 24h
- Date: none / short / medium / long
- Section header: size 8–24sp, color, uppercase on/off, hairline rule on/off
- Icons: off (default) / on; size 16–64dp
- Reset appearance to defaults

**Apps**
- All installed apps, searchable, each with a checkbox
- Checked = appears on home
- Reorder the checked apps here (same reorder affordance as home)
- Per-app: hide from drawer; move to a specific section
- Refresh button (re-queries PackageManager)

**Sections**
- Create / rename / reorder / delete sections
- Move items between sections
- Section title is optional

**Import / Export**
- Export: write the layout document as JSON to a file via the share sheet,
  and/or copy to clipboard. The exported file is named
  `liner-layout-<yyyyMMdd-HHmmss>.json` and contains the document at the top
  level (no wrapper object), so it stays directly editable by hand.
- Import: from file picker or clipboard. Validates first, shows a summary of
  what changed (apps added/removed, sections changed, theme replaced), then
  commits. A document whose `version` is newer than the app supports is
  rejected with an explanatory message; an older version is migrated forward.

**Advanced**
- Auto-prune uninstalled apps (off by default)
- Reset everything to defaults
- "Liner is not your default launcher" warning banner + "Set as default" button

### 6.4 First run

On first launch, if Liner is not the default home app, show a one-time prompt
with a button that fires
`Intent(Settings.ACTION_HOME_SETTINGS)`. Dismissible. The prompt does not
reappear once Liner is the default home.

---

## 7. Customization surface — summary

Every field above maps to exactly one `ThemeTokens` field or one edit
operation on `LayoutDocument`. There is no hidden state: if it is visible in
the UI, it is in the document; if it is in the document, it is visible in the
UI. This invariant is what makes "totally customizable" a property of the
design rather than a promise.

---

## 8. Permissions and manifest

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
    tools:ignore="QueryAllPackagesPermission" />
```

Declared under the standard launcher exemption: the app registers for
`ACTION_MAIN` / `CATEGORY_HOME` and requires full package visibility to
enumerate and launch installed apps. This is the same justification every
launcher on the Play Store uses.

Launcher activity declaration:

```xml
<activity
    android:name=".platform.LauncherActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:stateNotNeeded="true"
    android:excludeFromRecents="true"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize|uiMode|density|locale|layoutDirection"
    android:theme="@style/Theme.Liner">
  <intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.HOME" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.LAUNCHER" />
  </intent-filter>
  <meta-data android:name="android.intent-filter.MAIN" android:value="true" />
</activity>
```

`stateNotNeeded="true"` lets the system restore the launcher without saved
instance state, which is what keeps cold starts fast.

---

## 9. Error handling and edge cases

| Case | Behaviour |
|---|---|
| App uninstalled | Entry stays, renders greyed with "unavailable"; tap → toast. Auto-prune optional, off by default. |
| App renamed by developer | Custom rename wins; otherwise the live label is used, re-queried on resume. |
| No apps selected | Empty state with a button that opens the Apps tab. |
| Corrupt / unreadable layout JSON | Fall back to defaults, preserve the bad payload in a log file, one-time toast explaining the reset. |
| DataStore write failure | Continue on in-memory state; retry the write on the next edit; surface a non-blocking notice. |
| `PackageManager` query throws | Fall back to the last successful cached app list. |
| Icon load failure | Render the label with a neutral placeholder. Never crash. |
| Process killed mid-edit | Every edit is persisted immediately; restart restores the last committed state. |
| Another launcher set as default | Settings shows a banner and a "Set as default" action. |
| Filter matches nothing in drawer | Explicit "No apps match" row, not an empty void. |
| Section becomes empty | Kept as an untitled group unless the user deletes it. Empty sections are not auto-removed. |

---

## 10. Testing strategy

### 10.1 Unit tests — runnable and verified in this environment

Plain JVM tests under `app/src/test/`, all in the `model` package so they need
no Android runtime:

- `LayoutDocument` JSON round-trip: serialize → deserialize → equal, including
  every `ColorSpec` and `BackgroundSpec` variant
- Unknown-field forward compatibility: JSON with an unknown key still parses
- `moveItem` within a section, across sections, to invalid targets (no-op)
- `removeItem`, `removeSection`, `renameItem` (including rename to null =
  revert to app label)
- `addSection` / `renameSection` / `moveSection`
- Style precedence: per-item override > global theme > system default
- Drawer filter: case-insensitive substring, matches custom renames, empty
  result set
- Migration from an older document version

These run headless via `gradle test` and are verified before delivery.

### 10.2 Instrumentation tests — written, NOT verified here

`app/src/androidTest/` covers: drag-to-reorder gesture, drawer opening by
swipe, default-home intent resolution, icon rendering.

**No emulator or device is available in the build environment, so these are
shipped unverified.** They are included as source and are expected to be run
on a device by the user. This limitation is stated plainly rather than
implied to pass.

### 10.3 Manual test script

Shipped as `MANUAL_TEST.md`: a numbered, step-by-step script covering install,
set-as-default, first-run prompt, drag-reorder, rename, section CRUD, drawer
filter, theme changes, uninstall handling, import/export round-trip, and
process-death recovery.

---

## 11. Build and delivery

**Deliverables**

1. `liner-debug.apk` — installable directly
2. `liner-release.apk` — signed with a keystore generated in the build
   environment, installable and shareable
3. `liner-source.zip` — full Gradle project
4. `README.md` — install, set-as-default, and feature walkthrough
5. `MANUAL_TEST.md` — the manual test script
6. This spec, committed

**Build steps**

1. `gradle assembleDebug` and `assembleRelease` with a generated signing key
2. `gradle test` — all unit tests green
3. `apksigner verify` both APKs
4. Zip the project (excluding `.toolchain/`, `build/`, `.gradle/`)

**Known limitations, stated up front**

- The APK is compiled and signature-verified, but **never run on a physical
  device** in this environment. On-device gesture feel, scroll physics and
  drag-reorder responsiveness cannot be verified here.
- Instrumentation tests are shipped unverified for the same reason.
- Icon rendering uses `loadIcon` and rasterises to a bitmap; adaptive icons
  are rendered by the system, so their appearance may differ slightly between
  OEM implementations.

---

## 12. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Drag-to-reorder implemented in Compose is fiddly | Medium | Implement in a single focused `DragDropState` unit with the gesture math isolated from rendering; unit-test the reorder maths independently of the gesture layer |
| Package visibility restricted on some OEMs | High | `QUERY_ALL_PACKAGES` + `CATEGORY_HOME` exemption; also handle `SecurityException` by falling back to the cached list |
| Replacing the default launcher breaks other apps' assumptions | Medium | The drawer keeps every app launchable; the launcher also stays in the app drawer itself |
| Users uninstall an app referenced in the document | Medium | Greyed "unavailable" entry rather than a crash; optional auto-prune |
| Compose + `LazyColumn` reorder fights the scroll gesture | Medium | Long-press to arm drag mode explicitly, so a plain vertical scroll never competes with drag |

---

## 13. Decision log

| Decision | Rationale |
|---|---|
| Single scrolling column, not a free 2D canvas | Text at user-chosen sizes collides on a free canvas; a column stays legible at every size |
| Sections as headers in the column flow | Gives grouping without a second layout engine |
| Data-driven layout document over per-setting storage | "Totally customizable" must stay true as features are added; also makes import/export trivial |
| Compose over XML views | Theming is pervasive here; declarative styles map to `ThemeTokens` far more directly than to XML attrs |
| Drawer keeps icons, home does not | Home is the curated text surface; the drawer is the power surface |
| No search-first launch flow | Out of scope; the drawer filter covers the real need |
| Unit tests in `model`, instrumentation tests elsewhere | The environment has no emulator, so the testable logic is kept in the testable place |
