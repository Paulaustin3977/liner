# Liner Launcher — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build "Liner", an Android launcher whose home screen is a customizable, icon-free text list of the user's chosen apps, with a full app drawer, drag-to-reorder editing, and a deep theming system driven by a single serializable layout document.

**Architecture:** A single Gradle module with package-level boundaries. `model` holds the entire layout document as `@Serializable` Kotlin data classes plus every pure operation (edit, reorder, drag-target, style-resolution, filtering) and has zero Android dependencies, so all of it is unit-testable on the JVM without an emulator. `store`, `apps`, `ui`, and `platform` are thin adapters over `model`: the Android layer never owns state, it only renders a `LayoutDocument` and calls the pure edit functions.

**Tech Stack:** Kotlin 2.0.21 · Jetpack Compose (BOM 2024.10.01, Material3) · AGP 8.7.2 · Gradle 8.11.1 · kotlinx-serialization 1.7.3 · DataStore Preferences 1.1.1 · JDK 17 · minSdk 26 / target & compile SDK 35

**Spec:** `docs/superpowers/specs/2026-11-10-liner-launcher-design.md`

---

## Global Constraints

Every task below implicitly includes these. Values are copied verbatim from the spec.

- Package / applicationId: `dev.liner.launcher`
- minSdk `26`, compileSdk `35`, targetSdk `35`, JVM target `17`
- Kotlin `2.0.21`, Compose BOM `2024.10.01`, AGP `8.7.2`, Gradle `8.11.1`
- kotlinx-serialization `1.7.3`, DataStore Preferences `1.1.1`
- `model` package depends on **nothing** except the Kotlin stdlib and kotlinx-serialization. No Android, no Compose. This is enforced by review and is what makes the JVM unit tests possible.
- Home screen renders **no icons by default** (`ThemeTokens.showIcons = false`). Icons are an opt-in toggle.
- The home layout is a single scrolling column. No multi-page canvas, no free 2D positioning.
- Every user-visible customization is a field in `LayoutDocument` or an operation on it. No hidden state.
- Style precedence is fixed: **per-item override > global theme > system default.**
- Layout document is persisted as one JSON blob in DataStore Preferences, key `layout_document`.
- The launcher declares `QUERY_ALL_PACKAGES` under the standard launcher (CATEGORY_HOME) exemption.
- Every write to persistent storage happens through `LayoutRepository`, which is the only component that touches `DocumentStore`.
- The `ui` layer owns three things `model` deliberately cannot:
  - Substituting the real text colour when `ResolvedStyle.colorArgb` is null (the
    "follow system" sentinel), using the current light/dark configuration.
  - Mapping `ThemeTokens.clockFormat == "system"` to
    `DateFormat.is24HourFormat(context)` before calling `ClockFormatter.formatTime`.
  - Feeding the drawer `AppCatalog.drawerApps(...)` output into `AppFilter.filter(...)`,
    because `AppFilter` preserves input order — filtering an unsorted list would
    lose the alphabetical ordering.
- The `ui` layer never reads raw theme *style* tokens; it consumes `StyleResolver`
  output only. `StyleResolver` exposes every styling decision — the item and
  section-header styles, the theme text colour, the section-title case and rule
  flags, and the icon size — so no composable reaches into `ThemeTokens` to
  decide how something looks. Layout structure (alignment, padding, spacing,
  clock size) is read from `ThemeTokens` directly and is not a style decision.
- **Only `material-icons-core` is available** — not `material-icons-extended`. The full extended set adds ~37 MB to the APK for icons this app never uses. Use a core icon (`Icons.Default.Settings` is in core) or a text label instead.
- No crashes on: missing app, failed icon load, failed DataStore write, corrupt JSON, `PackageManager` throwing.
- **A failed save is always reported.** Every screen that writes to the document
  observes the repository's `writeFailed` flag and shows a notice — a silent
  failure would leave the user believing a change was saved when it was not.
- **The layout store is read once per process.** `LayoutRepository.load()` is
  idempotent: a second read would race the writer and could discard an edit
  that has not been persisted yet.
- **Writes to the layout store are coalesced.** `LayoutRepository` funnels
  updates through a conflated channel and waits for the burst to settle before
  writing, so a gesture that fires an update per frame — a dragged slider —
  rewrites the whole document once instead of once per frame. The in-memory
  document still updates on every movement, so the live preview stays live.
  A superseded write is dropped rather than persisted, so nothing may await a
  *specific* write's deferred.
- **Removing a single item from the home screen is always undoable.** The
  trash zone and the item editor's Remove button raise a snackbar with an
  Undo action that puts the item back where it was. Deleting a *section*
  removes its items too and cannot be undone, so it asks for confirmation
  first.
- **Icons are never rasterised on the main thread.** `IconCache` is thread-safe
  (`LruCache` plus a concurrent set), so both the drawer and the home screen
  load icons from a background dispatcher and publish the result into Compose
  state. A drawer with a hundred tiles must not stall the frame.
- Unit tests live in `app/src/test/kotlin/dev/liner/launcher/` and must run headless via `gradle test`.
- Instrumentation tests live in `app/src/androidTest/kotlin/dev/liner/launcher/` and **cannot be executed in this environment** (no emulator). They are shipped unverified and must be labelled as such in the README.

### Build environment (required for every Gradle command)

The sandbox has no system Gradle. Use the installed toolchain:

```bash
cd /workspace
export ANDROID_HOME=/workspace/.toolchain/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=/workspace/.gradle-home
export PATH=$ANDROID_HOME/platform-tools:$PATH
```

**Run every Gradle command as `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" <tasks>`**, where `$GRADLE` is defined once
per shell as:

```bash
GRADLE_BIN=/workspace/.toolchain/gradle-8.11.1/bin/gradle
GRADLE_ARGS=(
  --no-parallel
  --no-watch-fs
  -Pkotlin.compiler.execution.strategy=in-process
  "-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
)
```

This override is **mandatory**. The committed `gradle.properties` asks for
`-Xmx3g`, and this sandbox has 3 GB of RAM and 2 CPUs — the OOM killer
terminates the build. Do not lower `-Xmx3g` in the committed file; it is
correct for a developer's machine and only the sandbox needs the override.

A cold build takes roughly 15 minutes here and the foreground tool timeout is
600 s. Run the first build in the background and poll it. Incremental builds
after that are fast.

`--no-watch-fs` is required: this sandbox's Gradle daemon does not notice
file edits, so without it a build can report "up to date" for code that has
just changed. The flag costs nothing and removes a whole class of confusing
"my change did nothing" failures.

`local.properties` must contain `sdk.dir=/workspace/.toolchain/android-sdk`. It is gitignored; `local.properties.example` is committed.

---

## File Structure

```
/workspace/
├── settings.gradle.kts              # plugin + dependency repos, includes :app
├── build.gradle.kts                 # root: plugins declared with `apply false`
├── gradle.properties                # jvmargs, parallel, caching, androidX
├── gradle/libs.versions.toml        # version catalog (single source of versions)
├── local.properties.example         # sdk.dir placeholder for other machines
├── keystore.properties.example      # release signing placeholders
├── .gitignore
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── kotlin/dev/liner/launcher/
│       │   │   ├── LinerApp.kt                    # Application, holds AppContainer
│       │   │   ├── model/
│       │   │   │   ├── LayoutDocument.kt          # document + version const
│       │   │   │   ├── Section.kt
│       │   │   │   ├── HomeItem.kt
│       │   │   │   ├── ItemStyle.kt
│       │   │   │   ├── ThemeTokens.kt
│       │   │   │   ├── ColorSpec.kt
│       │   │   │   ├── BackgroundSpec.kt
│       │   │   │   ├── ResolvedStyle.kt
│       │   │   │   ├── InstalledApp.kt             # pure data for an installed app
│       │   │   │   ├── DocumentEdits.kt            # pure edit operations
│       │   │   │   ├── StyleResolver.kt            # pure style resolution
│       │   │   │   ├── AppCatalog.kt               # pure merge of doc + installed apps
│       │   │   │   ├── AppFilter.kt                # pure drawer filter
│       │   │   │   ├── DragTargeting.kt            # pure drag-target maths
│       │   │   │   ├── ClockFormatter.kt           # pure clock/date formatting
│       │   │   │   ├── ImportValidator.kt          # pure import validation + summary
│       │   │   │   └── DocumentCodec.kt            # JSON encode/decode + migrate
│       │   │   ├── store/
│       │   │   │   ├── DocumentStore.kt            # interface + in-memory fake
│       │   │   │   ├── DataStoreDocumentStore.kt   # DataStore impl
│       │   │   │   └── LayoutRepository.kt         # single writer, channel-serialised
│       │   │   ├── apps/
│       │   │   │   ├── InstalledAppsRepository.kt   # PackageManager queries
│       │   │   │   └── IconCache.kt                # Drawable → ImageBitmap cache
│       │   │   ├── platform/
│       │   │   │   ├── AppContainer.kt             # manual DI
│       │   │   │   ├── LauncherActivity.kt         # HOME activity
│       │   │   │   ├── SettingsActivity.kt         # settings activity
│       │   │   │   ├── AppLauncher.kt              # launch / open-app helpers
│       │   │   │   ├── DefaultHomeDetector.kt      # is-liner-the-default-home
│       │   │   │   ├── WallpaperProvider.kt        # system wallpaper drawable
│       │   │   │   └── LinerTheme.kt               # Compose theme + token mapping
│       │   │   └── ui/
│       │   │       ├── home/HomeScreen.kt           # home composition
│       │   │       ├── home/HomeViewModel.kt
│       │   │       ├── home/EditModeController.kt   # drag state + gesture wiring
│       │   │       ├── drawer/DrawerSheet.kt
│       │   │       ├── drawer/DrawerViewModel.kt
│       │   │       ├── settings/SettingsScreen.kt   # tab scaffold
│       │   │       ├── settings/AppearanceTab.kt
│       │   │       ├── settings/AppsTab.kt
│       │   │       ├── settings/SectionsTab.kt
│       │   │       ├── settings/ImportExportTab.kt
│       │   │       ├── settings/AdvancedTab.kt
│       │   │       └── common/                       # shared composables
│       │   │           ├── LabelRow.kt
│       │   │           ├── SectionHeaderRow.kt
│       │   │           ├── ColorOptionRow.kt
│       │   │           ├── SliderRow.kt
│       │   │           └── ChoiceRow.kt
│       │   └── res/
│       │       ├── values/{strings.xml,themes.xml,colors.xml}
│       │       ├── values-night/themes.xml
│       │       ├── drawable/ic_launcher_foreground.xml
│       │       ├── mipmap-anydpi-v26/{ic_launcher.xml,ic_launcher_round.xml}
│       │       └── xml/{backup_rules.xml,data_extraction_rules.xml}
│       ├── test/kotlin/dev/liner/launcher/
│       │   ├── model/{DocumentCodecTest,DocumentEditsTest,StyleResolverTest,
│       │   │   │        AppCatalogTest,AppFilterTest,DragTargetingTest,
│       │   │   │        ClockFormatterTest}.kt
│       │   └── store/LayoutRepositoryTest.kt
│       └── androidTest/kotlin/dev/liner/launcher/
│           ├── HomeScreenTest.kt
│           ├── DrawerTest.kt
│           └── DefaultHomeTest.kt
├── docs/superpowers/{specs,plans}/
├── MANUAL_TEST.md
├── README.md
└── .toolchain/          # gitignored SDK/JDK/Gradle install
```

**Boundary rules**

- `model` → nothing but stdlib + kotlinx-serialization.
- `store`, `apps`, `ui`, `platform` → may depend on `model`.
- `ui` → may depend on `model`, `store`, `apps`, `platform`.
- No package depends on `ui` except `platform` (activities compose UI) and `ui` itself.

---

## Task 1: Buildable Project Skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `local.properties.example`, `keystore.properties.example`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/{strings.xml,themes.xml,colors.xml}`, `values-night/themes.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `mipmap-anydpi-v26/{ic_launcher.xml,ic_launcher_round.xml}`
- Create: `app/src/main/res/xml/{backup_rules.xml,data_extraction_rules.xml}`
- Create: `app/src/main/kotlin/dev/liner/launcher/platform/LauncherActivity.kt` (temporary placeholder)
- Create: `app/src/test/kotlin/dev/liner/launcher/SmokeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a buildable `:app` module producing `app/build/outputs/apk/debug/app-debug.apk`. Every later task adds to this module.

- [ ] **Step 1: Write the version catalog**

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.21"
coreKtx = "1.13.1"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.10.01"
datastore = "1.1.1"
serialization = "1.7.3"
junit = "4.13.2"
androidxJunit = "1.2.1"
espresso = "3.6.1"
coroutines = "1.9.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }
androidx-test-espresso = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Write the root Gradle files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Liner"
include(":app")
```

`build.gradle.kts` (root):

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=false
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 3: Write the app module build file**

`app/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.liner.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.liner.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    testOptions {
        unitTests { isReturnDefaultValues = true }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
```

`app/proguard-rules.pro` — empty file with a comment; minification is off.

- [ ] **Step 4: Write the manifest**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />

    <application
        android:name=".LinerApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Liner">

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
            <meta-data
                android:name="android.intent-filter.MAIN"
                android:value="true" />
        </activity>

        <activity
            android:name=".platform.SettingsActivity"
            android:exported="true"
            android:label="@string/settings_title"
            android:theme="@style/Theme.Liner">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

> `LinerApp` and `SettingsActivity` do not exist yet. Create `LinerApp` now (Task 1) and `SettingsActivity` as a stub in Task 1 too — the manifest must resolve at build time.

- [ ] **Step 5: Write the placeholder Application and activities**

`app/src/main/kotlin/dev/liner/launcher/LinerApp.kt`:

```kotlin
package dev.liner.launcher

import android.app.Application

class LinerApp : Application()
```

`app/src/main/kotlin/dev/liner/launcher/platform/LauncherActivity.kt`:

```kotlin
package dev.liner.launcher.platform

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("Liner") }
            }
        }
    }
}
```

`app/src/main/kotlin/dev/liner/launcher/platform/SettingsActivity.kt`:

```kotlin
package dev.liner.launcher.platform

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("Settings") }
            }
        }
    }
}
```

- [ ] **Step 6: Write the resources**

`app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Liner</string>
    <string name="settings_title">Liner Settings</string>
</resources>
```

`app/src/main/res/values/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#101014</color>
</resources>
```

`app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Liner" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@android:color/black</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
</resources>
```

`app/src/main/res/values-night/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Liner" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@android:color/black</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
</resources>
```

`app/src/main/res/xml/backup_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <include domain="file" path="datastore/" />
</full-backup-content>
```

`app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <include domain="file" path="datastore/" />
    </cloud-backup>
    <device-transfer>
        <include domain="file" path="datastore/" />
    </device-transfer>
</data-extraction-rules>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#F5F5F7"
        android:pathData="M30,34h48v4h-48z M30,48h48v4h-48z M30,62h48v4h-48z M30,76h28v4h-28z" />
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` (identical):

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 7: Write the environment example files**

`local.properties.example`:

```properties
# Copy to local.properties and point at your SDK.
sdk.dir=/path/to/Android/sdk
```

`keystore.properties.example`:

```properties
# Copy to keystore.properties to enable signed release builds.
storeFile=liner-release.jks
storePassword=CHANGE_ME
keyAlias=liner
keyPassword=CHANGE_ME
```

- [ ] **Step 8: Write the smoke test**

`app/src/test/kotlin/dev/liner/launcher/SmokeTest.kt`:

```kotlin
package dev.liner.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun `unit test environment works`() {
        assertEquals(4, 2 + 2)
    }
}
```

- [ ] **Step 9: Run the build to verify it compiles**

Run:

```bash
cd /workspace
export ANDROID_HOME=/workspace/.toolchain/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=/workspace/.gradle-home
echo "sdk.dir=$ANDROID_HOME" > local.properties
"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, `app/build/outputs/apk/debug/app-debug.apk` exists, and `app/build/reports/tests/testDebugUnitTest/index.html` reports 1 test, 0 failures.

- [ ] **Step 10: Commit**

```bash
cd /workspace
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/libs.versions.toml \
  local.properties.example keystore.properties.example .gitignore \
  app/build.gradle.kts app/proguard-rules.pro app/src
git commit -m "chore: scaffold buildable Android project"
```

---

## Task 2: Model Types, JSON Codec, and Defaults

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/model/{LayoutDocument,Section,HomeItem,ItemStyle,ThemeTokens,ColorSpec,BackgroundSpec,ResolvedStyle,InstalledApp}.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/model/DocumentCodec.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/DocumentCodecTest.kt`

**Interfaces:**
- Consumes: Task 1's Gradle setup (serialization plugin already applied).
- Produces:
  - `data class LayoutDocument(version: Int = 1, sections: List<Section> = emptyList(), theme: ThemeTokens = ThemeTokens(), hiddenFromDrawer: List<String> = emptyList(), autoPruneUninstalled: Boolean = false, firstRunPromptDismissed: Boolean = false)`
  - `data class Section(id: String, title: String? = null, items: List<HomeItem> = emptyList())`
  - `data class HomeItem(id: String, packageName: String, label: String? = null, style: ItemStyle? = null)`
  - `data class ItemStyle(textSizeSp: Float? = null, color: ColorSpec? = null, fontWeight: String? = null, letterSpacingEm: Float? = null)`
  - `data class ThemeTokens(...)` — full field list as specced in §5 of the spec
  - `sealed interface ColorSpec { data class Solid(argb: Int); data object FollowSystem }`
  - `sealed interface BackgroundSpec { data class Solid(color); data class Gradient(from, to, angleDegrees); data class Wallpaper(dim) }`
  - `data class ResolvedStyle(fontKey: String, sizeSp: Float, weight: Int, letterSpacingEm: Float, colorArgb: Int?)`
  - `data class InstalledApp(packageName: String, activityClass: String, systemLabel: String)`
  - `object DocumentCodec { fun encode(d: LayoutDocument): String; fun decode(raw: String): LayoutDocument? }`

- [ ] **Step 1: Write the failing codec test**

`app/src/test/kotlin/dev/liner/launcher/model/DocumentCodecTest.kt`:

```kotlin
package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCodecTest {

    @Test
    fun `round trips a fully populated document`() {
        val original = LayoutDocument(
            version = 1,
            sections = listOf(
                Section(
                    id = "s1",
                    title = "Daily",
                    items = listOf(
                        HomeItem(
                            id = "i1",
                            packageName = "com.example.mail",
                            label = "Mail",
                            style = ItemStyle(
                                textSizeSp = 28f,
                                color = ColorSpec.Solid(0xFF00FF00.toInt()),
                                fontWeight = "700",
                                letterSpacingEm = 0.05f,
                            ),
                        ),
                        HomeItem(id = "i2", packageName = "com.example.maps"),
                    ),
                ),
                Section(id = "s2", title = null),
            ),
            theme = ThemeTokens(
                background = BackgroundSpec.Gradient(
                    from = ColorSpec.Solid(0xFF102030.toInt()),
                    to = ColorSpec.FollowSystem,
                    angleDegrees = 45,
                ),
                textColor = ColorSpec.Solid(0xFFFFFFFF.toInt()),
                showIcons = true,
                iconSizeDp = 40,
                alignment = "center",
            ),
            hiddenFromDrawer = listOf("com.example.hidden"),
            autoPruneUninstalled = true,
        )

        val decoded = DocumentCodec.decode(DocumentCodec.encode(original))

        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `every background and colour variant survives a round trip`() {
        val backgrounds = listOf(
            BackgroundSpec.Solid(ColorSpec.Solid(0xFF102030.toInt())),
            BackgroundSpec.Gradient(
                from = ColorSpec.Solid(0xFF000000.toInt()),
                to = ColorSpec.FollowSystem,
                angleDegrees = 45,
            ),
            BackgroundSpec.Wallpaper(dim = 0.5f),
        )

        backgrounds.forEach { background ->
            val original = LayoutDocument(theme = ThemeTokens(background = background))

            val decoded = DocumentCodec.decode(DocumentCodec.encode(original))

            assertNotNull(decoded)
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `a hand written payload decodes to the expected document`() {
        val json = """
            {
              "version": 1,
              "sections": [
                {
                  "id": "s1",
                  "title": "Daily",
                  "items": [ { "id": "a", "packageName": "com.mail" } ]
                }
              ],
              "theme": {
                "background": { "type": "wallpaper", "dim": 0.5 },
                "textColor": { "type": "system" }
              }
            }
        """.trimIndent()

        val decoded = DocumentCodec.decode(json)

        assertNotNull(decoded)
        assertEquals(BackgroundSpec.Wallpaper(0.5f), decoded!!.theme.background)
        assertEquals(ColorSpec.FollowSystem, decoded.theme.textColor)
        assertEquals("Daily", decoded.sections[0].title)
        assertEquals("com.mail", decoded.sections[0].items[0].packageName)
    }

    @Test
    fun `ignores unknown fields for forward compatibility`() {
        val json = """{"version":1,"sections":[],"theme":{},"somethingNew":{"a":1}}"""

        val decoded = DocumentCodec.decode(json)

        assertNotNull(decoded)
        assertEquals(1, decoded!!.version)
    }

    @Test
    fun `rejects a document from a newer version`() {
        val json = """{"version":99,"sections":[],"theme":{}}"""

        assertNull(DocumentCodec.decode(json))
    }

    @Test
    fun `rejects malformed json`() {
        assertNull(DocumentCodec.decode("{not json"))
    }

    @Test
    fun `defaults are applied when fields are absent`() {
        val decoded = DocumentCodec.decode("""{"version":1}""")

        assertNotNull(decoded)
        assertTrue(decoded!!.sections.isEmpty())
        assertEquals(20f, decoded.theme.listTextSizeSp, 0.001f)
        assertEquals(false, decoded.theme.showIcons)
        assertEquals(BackgroundSpec.Wallpaper(0.35f), decoded.theme.background)
        assertEquals(ColorSpec.FollowSystem, decoded.theme.textColor)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd /workspace
export ANDROID_HOME=/workspace/.toolchain/android-sdk GRADLE_USER_HOME=/workspace/.gradle-home
"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*DocumentCodecTest*'
```

Expected: compilation failure — `Unresolved reference: DocumentCodec`.

- [ ] **Step 3: Write the model types**

`model/LayoutDocument.kt`:

```kotlin
package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class LayoutDocument(
    val version: Int = CURRENT_VERSION,
    val sections: List<Section> = emptyList(),
    val theme: ThemeTokens = ThemeTokens(),
    val hiddenFromDrawer: List<String> = emptyList(),
    val autoPruneUninstalled: Boolean = false,
    /**
     * Whether the user has dismissed the first-run prompt. The prompt is
     * shown once, on the first launch, when Liner is not the default home
     * app — and it stays dismissed afterwards.
     *
     * It lives in the document rather than in a second store because the app
     * keeps no hidden state: everything the user can see is in the document.
     */
    val firstRunPromptDismissed: Boolean = false,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
```

`model/Section.kt`:

```kotlin
package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class Section(
    val id: String,
    val title: String? = null,
    val items: List<HomeItem> = emptyList(),
)
```

`model/HomeItem.kt`:

```kotlin
package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class HomeItem(
    val id: String,
    val packageName: String,
    val label: String? = null,
    val style: ItemStyle? = null,
)
```

`model/ItemStyle.kt`:

```kotlin
package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class ItemStyle(
    val textSizeSp: Float? = null,
    val color: ColorSpec? = null,
    val fontWeight: String? = null,
    val letterSpacingEm: Float? = null,
)
```

`model/ColorSpec.kt`:

```kotlin
package dev.liner.launcher.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ColorSpec {
    @Serializable
    @SerialName("solid")
    data class Solid(val argb: Int) : ColorSpec

    @Serializable
    @SerialName("system")
    data object FollowSystem : ColorSpec
}
```

`model/BackgroundSpec.kt`:

```kotlin
package dev.liner.launcher.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface BackgroundSpec {
    @Serializable
    @SerialName("solid")
    data class Solid(val color: ColorSpec) : BackgroundSpec

    @Serializable
    @SerialName("gradient")
    data class Gradient(
        val from: ColorSpec,
        val to: ColorSpec,
        val angleDegrees: Int = 90,
    ) : BackgroundSpec

    @Serializable
    @SerialName("wallpaper")
    data class Wallpaper(val dim: Float = 0.35f) : BackgroundSpec
}
```

`model/ThemeTokens.kt`:

```kotlin
package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class ThemeTokens(
    val background: BackgroundSpec = BackgroundSpec.Wallpaper(dim = 0.35f),
    val textColor: ColorSpec = ColorSpec.FollowSystem,
    val listFontFamily: String = "sans-serif",
    val listTextSizeSp: Float = 20f,
    val listFontWeight: String = "400",
    val letterSpacingEm: Float = 0f,
    val itemSpacingDp: Float = 10f,
    val screenPaddingHdp: Float = 24f,
    val screenPaddingVdp: Float = 16f,
    val alignment: String = "start",
    val clockStyle: String = "large",
    val clockFormat: String = "system",
    val dateStyle: String = "medium",
    val sectionTitleSizeSp: Float = 13f,
    val sectionTitleColor: ColorSpec = ColorSpec.FollowSystem,
    val sectionTitleUppercase: Boolean = true,
    val sectionTitleRule: Boolean = true,
    val showIcons: Boolean = false,
    val iconSizeDp: Int = 24,
    val drawerStyle: String = "icons",
)
```

`model/ResolvedStyle.kt`:

```kotlin
package dev.liner.launcher.model

data class ResolvedStyle(
    val fontKey: String,
    val sizeSp: Float,
    val weight: Int,
    val letterSpacingEm: Float,
    /** null means "use the system default text colour". */
    val colorArgb: Int?,
)
```

`model/InstalledApp.kt`:

```kotlin
package dev.liner.launcher.model

data class InstalledApp(
    val packageName: String,
    val activityClass: String,
    val systemLabel: String,
)
```

- [ ] **Step 4: Write the codec**

`model/DocumentCodec.kt`:

```kotlin
package dev.liner.launcher.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object DocumentCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        classDiscriminator = "type"
    }

    fun encode(document: LayoutDocument): String = json.encodeToString(document)

    /**
     * Returns null when the payload is unreadable or newer than this build
     * understands. The caller is responsible for falling back to defaults.
     */
    fun decode(raw: String): LayoutDocument? = try {
        val decoded = json.decodeFromString<LayoutDocument>(raw)
        if (decoded.version > LayoutDocument.CURRENT_VERSION) null else migrate(decoded)
    } catch (_: Exception) {
        // Data errors only: malformed JSON, missing fields, an unknown
        // discriminator. Deliberately not Throwable — an OutOfMemoryError or a
        // bug inside migrate() must surface, not silently reset the user's
        // layout to defaults.
        null
    }

    /** Forward-migrates an older document. Version 1 is the first version, so no-op today. */
    private fun migrate(document: LayoutDocument): LayoutDocument = document
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
cd /workspace
export ANDROID_HOME=/workspace/.toolchain/android-sdk GRADLE_USER_HOME=/workspace/.gradle-home
"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*DocumentCodecTest*'
```

Expected: `BUILD SUCCESSFUL`, 7 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/model \
        app/src/test/kotlin/dev/liner/launcher/model
git commit -m "feat(model): add layout document types and JSON codec"
```

---

## Task 3: Pure Document Edit Operations

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/model/DocumentEdits.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/DocumentEditsTest.kt`

**Interfaces:**
- Consumes: Task 2's model types.
- Produces: top-level extension functions on `LayoutDocument` (all in `dev.liner.launcher.model`):
  - `moveItem(itemId: String, toSectionId: String, toIndex: Int): LayoutDocument`
  - `removeItem(itemId: String): LayoutDocument`
  - `renameItem(itemId: String, label: String?): LayoutDocument`
  - `setItemStyle(itemId: String, style: ItemStyle?): LayoutDocument`
  - `addItem(item: HomeItem, sectionId: String, index: Int = Int.MAX_VALUE): LayoutDocument`
  - `reorderHomeItems(flatOrder: List<String>): LayoutDocument`
  - `addSection(id: String, title: String? = null): LayoutDocument`
  - `renameSection(sectionId: String, title: String?): LayoutDocument`
  - `removeSection(sectionId: String): LayoutDocument`
  - `moveSection(sectionId: String, toIndex: Int): LayoutDocument`
  - `setHiddenFromDrawer(packageName: String, hidden: Boolean): LayoutDocument`
  - `setAutoPruneUninstalled(enabled: Boolean): LayoutDocument`
  - `updateTheme(transform: (ThemeTokens) -> ThemeTokens): LayoutDocument`
  - `itemById(itemId: String): HomeItem?`
  - `sectionById(sectionId: String): Section?`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/dev/liner/launcher/model/DocumentEditsTest.kt`:

```kotlin
package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEditsTest {

    private fun doc() = LayoutDocument(
        sections = listOf(
            Section(
                id = "s1",
                title = "One",
                items = listOf(
                    HomeItem(id = "a", packageName = "p.a"),
                    HomeItem(id = "b", packageName = "p.b"),
                    HomeItem(id = "c", packageName = "p.c"),
                ),
            ),
            Section(
                id = "s2",
                title = "Two",
                items = listOf(HomeItem(id = "d", packageName = "p.d")),
            ),
        ),
    )

    @Test
    fun `moveItem reorders within a section`() {
        val result = doc().moveItem("c", toSectionId = "s1", toIndex = 0)

        assertEquals(listOf("c", "a", "b"), result.sections[0].items.map { it.id })
    }

    @Test
    fun `moveItem moves across sections`() {
        val result = doc().moveItem("a", toSectionId = "s2", toIndex = 0)

        assertEquals(listOf("b", "c"), result.sections[0].items.map { it.id })
        assertEquals(listOf("a", "d"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `moveItem clamps an out of range index`() {
        val result = doc().moveItem("a", toSectionId = "s2", toIndex = 99)

        assertEquals(listOf("d", "a"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `moveItem to an unknown section is a no-op`() {
        val before = doc()
        assertEquals(before, before.moveItem("a", toSectionId = "nope", toIndex = 0))
    }

    @Test
    fun `moveItem of an unknown item is a no-op`() {
        val before = doc()
        assertEquals(before, before.moveItem("zzz", toSectionId = "s1", toIndex = 0))
    }

    @Test
    fun `removeItem drops it from its section`() {
        val result = doc().removeItem("b")

        assertEquals(listOf("a", "c"), result.sections[0].items.map { it.id })
    }

    @Test
    fun `renameItem sets a custom label`() {
        assertEquals("Mail", doc().renameItem("a", "Mail").sections[0].items[0].label)
    }

    @Test
    fun `renameItem with null reverts to the system label`() {
        val renamed = doc().renameItem("a", "Mail")
        assertNull(renamed.renameItem("a", null).sections[0].items[0].label)
    }

    @Test
    fun `renameItem with a blank label reverts to the system label`() {
        assertNull(doc().renameItem("a", "   ").sections[0].items[0].label)
    }

    @Test
    fun `setItemStyle attaches and clears an override`() {
        val style = ItemStyle(textSizeSp = 30f)

        val withStyle = doc().setItemStyle("a", style)
        assertEquals(style, withStyle.sections[0].items[0].style)

        assertNull(withStyle.setItemStyle("a", null).sections[0].items[0].style)
    }

    @Test
    fun `addItem appends to the target section`() {
        val result = doc().addItem(HomeItem(id = "e", packageName = "p.e"), sectionId = "s2")

        assertEquals(listOf("d", "e"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `addItem ignores an unknown section`() {
        val before = doc()
        assertEquals(before, before.addItem(HomeItem(id = "e", packageName = "p.e"), sectionId = "nope"))
    }

    @Test
    fun `addItem ignores a duplicate id`() {
        val before = doc()
        assertEquals(before, before.addItem(HomeItem(id = "a", packageName = "p.a"), sectionId = "s2"))
    }

    @Test
    fun `reorderHomeItems reorders within sections without moving between them`() {
        val result = doc().reorderHomeItems(listOf("c", "b", "a", "d"))

        assertEquals(listOf("c", "b", "a"), result.sections[0].items.map { it.id })
        assertEquals(listOf("d"), result.sections[1].items.map { it.id })
    }

    @Test
    fun `reorderHomeItems keeps unlisted items in their previous relative order`() {
        // Ids are deliberately in reverse alphabetical order so that sorting by
        // id and sorting by previous position give different answers.
        val document = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    items = listOf(
                        HomeItem(id = "z", packageName = "p.z"),
                        HomeItem(id = "y", packageName = "p.y"),
                        HomeItem(id = "x", packageName = "p.x"),
                    ),
                ),
            ),
        )

        val result = document.reorderHomeItems(listOf("z"))

        assertEquals(listOf("z", "y", "x"), result.sections[0].items.map { it.id })
    }

    @Test
    fun `reorderHomeItems ignores ids that are not on the home screen`() {
        val result = doc().reorderHomeItems(listOf("c", "b", "a", "unknown"))

        assertEquals(listOf("c", "b", "a"), result.sections[0].items.map { it.id })
    }

    @Test
    fun `addSection appends a new section`() {
        val result = doc().addSection("s3", "Three")

        assertEquals(3, result.sections.size)
        assertEquals("s3", result.sections[2].id)
        assertEquals("Three", result.sections[2].title)
    }

    @Test
    fun `renameSection sets and clears the title`() {
        assertEquals("Uno", doc().renameSection("s1", "Uno").sections[0].title)
        assertNull(doc().renameSection("s1", null).sections[0].title)
        assertNull(doc().renameSection("s1", "  ").sections[0].title)
    }

    @Test
    fun `removeSection deletes it`() {
        val result = doc().removeSection("s1")

        assertEquals(listOf("s2"), result.sections.map { it.id })
    }

    @Test
    fun `moveSection reorders sections`() {
        val result = doc().moveSection("s2", toIndex = 0)

        assertEquals(listOf("s2", "s1"), result.sections.map { it.id })
    }

    @Test
    fun `setHiddenFromDrawer adds and removes`() {
        val hidden = doc().setHiddenFromDrawer("p.a", true)
        assertEquals(listOf("p.a"), hidden.hiddenFromDrawer)

        assertTrue(hidden.setHiddenFromDrawer("p.a", false).hiddenFromDrawer.isEmpty())
    }

    @Test
    fun `setAutoPruneUninstalled toggles the flag`() {
        assertEquals(true, doc().setAutoPruneUninstalled(true).autoPruneUninstalled)
    }

    @Test
    fun `updateTheme transforms the theme`() {
        val result = doc().updateTheme { it.copy(listTextSizeSp = 42f) }

        assertEquals(42f, result.theme.listTextSizeSp, 0.001f)
    }

    @Test
    fun `itemById and sectionById look up by id`() {
        assertEquals("p.a", doc().itemById("a")?.packageName)
        assertEquals("One", doc().sectionById("s1")?.title)
        assertNull(doc().itemById("nope"))
        assertNull(doc().sectionById("nope"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*DocumentEditsTest*'`

Expected: compilation failure — `Unresolved reference: moveItem`.

- [ ] **Step 3: Implement the operations**

`model/DocumentEdits.kt`:

```kotlin
package dev.liner.launcher.model

/**
 * Removes the item from wherever it currently lives, then inserts it at
 * [toIndex] within the target section's post-removal item list.
 * An unknown [toSectionId] or [itemId] leaves the document unchanged.
 */
fun LayoutDocument.moveItem(itemId: String, toSectionId: String, toIndex: Int): LayoutDocument {
    val source = sections.firstOrNull { s -> s.items.any { it.id == itemId } } ?: return this
    if (sections.none { it.id == toSectionId }) return this
    val item = source.items.first { it.id == itemId }

    val withoutItem = sections.map { s ->
        if (s.id == source.id) s.copy(items = s.items.filterNot { it.id == itemId }) else s
    }

    return withoutItem.map { s ->
        if (s.id != toSectionId) {
            s
        } else {
            val safeIndex = toIndex.coerceIn(0, s.items.size)
            s.copy(items = s.items.toMutableList().apply { add(safeIndex, item) })
        }
    }.let { copy(sections = it) }
}

fun LayoutDocument.removeItem(itemId: String): LayoutDocument =
    copy(sections = sections.map { s -> s.copy(items = s.items.filterNot { it.id == itemId }) })

fun LayoutDocument.renameItem(itemId: String, label: String?): LayoutDocument =
    copy(sections = sections.map { s ->
        s.copy(items = s.items.map { i ->
            if (i.id == itemId) i.copy(label = label?.trim()?.takeIf { it.isNotEmpty() }) else i
        })
    })

fun LayoutDocument.setItemStyle(itemId: String, style: ItemStyle?): LayoutDocument =
    copy(sections = sections.map { s ->
        s.copy(items = s.items.map { i -> if (i.id == itemId) i.copy(style = style) else i })
    })

fun LayoutDocument.addItem(item: HomeItem, sectionId: String, index: Int = Int.MAX_VALUE): LayoutDocument {
    if (sections.none { it.id == sectionId }) return this
    if (sections.any { s -> s.items.any { it.id == item.id } }) return this

    return copy(sections = sections.map { s ->
        if (s.id != sectionId) {
            s
        } else {
            val safeIndex = index.coerceIn(0, s.items.size)
            s.copy(items = s.items.toMutableList().apply { add(safeIndex, item) })
        }
    })
}

/**
 * Applies a desired flat order of home item ids. Items keep their section,
 * but their order inside that section follows [flatOrder]. Ids not present in
 * [flatOrder] keep their previous relative position at the end of their
 * section — the tiebreaker is the item's previous index, not its id.
 */
fun LayoutDocument.reorderHomeItems(flatOrder: List<String>): LayoutDocument {
    if (flatOrder.isEmpty()) return this
    val position = flatOrder.withIndex().associate { (index, id) -> id to index }

    return copy(sections = sections.map { s ->
        val previousIndex = s.items.withIndex().associate { (index, item) -> item.id to index }
        val ordered = s.items.sortedWith(
            compareBy(
                { position[it.id] ?: Int.MAX_VALUE },
                { previousIndex[it.id] ?: Int.MAX_VALUE },
            ),
        )
        s.copy(items = ordered)
    })
}

fun LayoutDocument.addSection(id: String, title: String? = null): LayoutDocument =
    copy(sections = sections + Section(id = id, title = title?.trim()?.takeIf { it.isNotEmpty() }))

fun LayoutDocument.renameSection(sectionId: String, title: String?): LayoutDocument =
    copy(sections = sections.map { s ->
        if (s.id == sectionId) s.copy(title = title?.trim()?.takeIf { it.isNotEmpty() }) else s
    })

fun LayoutDocument.removeSection(sectionId: String): LayoutDocument =
    copy(sections = sections.filterNot { it.id == sectionId })

fun LayoutDocument.moveSection(sectionId: String, toIndex: Int): LayoutDocument {
    val from = sections.indexOfFirst { it.id == sectionId }
    if (from < 0) return this
    val mutable = sections.toMutableList()
    val moved = mutable.removeAt(from)
    mutable.add(toIndex.coerceIn(0, mutable.size), moved)
    return copy(sections = mutable)
}

fun LayoutDocument.setHiddenFromDrawer(packageName: String, hidden: Boolean): LayoutDocument =
    if (hidden) {
        if (packageName in hiddenFromDrawer) this
        else copy(hiddenFromDrawer = hiddenFromDrawer + packageName)
    } else {
        copy(hiddenFromDrawer = hiddenFromDrawer.filterNot { it == packageName })
    }

fun LayoutDocument.setAutoPruneUninstalled(enabled: Boolean): LayoutDocument =
    copy(autoPruneUninstalled = enabled)

/**
 * Records that the user has dismissed the first-run prompt, so it does not
 * come back.
 */
fun LayoutDocument.dismissFirstRunPrompt(): LayoutDocument =
    copy(firstRunPromptDismissed = true)

/**
 * Removes every home item whose package is not in [installedPackages].
 *
 * A no-op when auto-pruning is off, and a no-op when nothing is stale.
 *
 * The caller MUST pass a list that really came from the package manager.
 * A query that failed, or one that came back empty because the OS filtered
 * the package visibility, is not evidence that the apps were uninstalled —
 * pruning on it would delete the user's whole home screen.
 */
fun LayoutDocument.pruneUninstalledApps(installedPackages: Set<String>): LayoutDocument {
    if (!autoPruneUninstalled) return this
    val stale = sections.flatMap { it.items }.filter { it.packageName !in installedPackages }
    if (stale.isEmpty()) return this
    return stale.fold(this) { document, item -> document.removeItem(item.id) }
}

fun LayoutDocument.updateTheme(transform: (ThemeTokens) -> ThemeTokens): LayoutDocument =
    copy(theme = transform(theme))

fun LayoutDocument.itemById(itemId: String): HomeItem? =
    sections.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == itemId }

fun LayoutDocument.sectionById(sectionId: String): Section? =
    sections.firstOrNull { it.id == sectionId }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*DocumentEditsTest*'`

Expected: `BUILD SUCCESSFUL`, 24 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/model/DocumentEdits.kt \
        app/src/test/kotlin/dev/liner/launcher/model/DocumentEditsTest.kt
git commit -m "feat(model): add pure document edit operations"
```

---

## Task 4: Style Resolution, App Catalog, Filter, Clock Formatting

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/model/{StyleResolver,AppCatalog,AppFilter,ClockFormatter}.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/model/HomeEntry.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/{StyleResolverTest,AppCatalogTest,AppFilterTest,ClockFormatterTest}.kt`

**Interfaces:**
- Consumes: Task 2's model types.
- Produces:
  - `object StyleResolver { fun resolveItemStyle(theme: ThemeTokens, override: ItemStyle?): ResolvedStyle; fun resolveSectionHeaderStyle(theme: ThemeTokens): ResolvedStyle }`
  - `data class HomeEntry(item: HomeItem, displayLabel: String, available: Boolean, sectionId: String)`
  - `object AppCatalog { fun homeEntries(document: LayoutDocument, installed: Map<String, InstalledApp>): List<HomeEntry>; fun drawerApps(document: LayoutDocument, installed: List<InstalledApp>): List<InstalledApp>; fun customLabels(document: LayoutDocument): Map<String, String> }`
  - `object AppFilter { fun filter(apps: List<InstalledApp>, query: String, customLabels: Map<String, String> = emptyMap()): List<InstalledApp> }`
  - `object ClockFormatter { fun formatTime(epochMillis: Long, zone: ZoneId, use24Hour: Boolean, locale: Locale = Locale.getDefault()): String; fun formatDate(epochMillis: Long, zone: ZoneId, style: String, locale: Locale = Locale.getDefault()): String? }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/dev/liner/launcher/model/StyleResolverTest.kt`:

```kotlin
package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StyleResolverTest {

    private val theme = ThemeTokens(
        textColor = ColorSpec.Solid(0xFF112233.toInt()),
        listFontFamily = "serif",
        listTextSizeSp = 20f,
        listFontWeight = "500",
        letterSpacingEm = 0.1f,
        sectionTitleSizeSp = 13f,
        sectionTitleColor = ColorSpec.Solid(0xFF445566.toInt()),
    )

    @Test
    fun `global theme resolves when there is no override`() {
        val resolved = StyleResolver.resolveItemStyle(theme, null)

        assertEquals("serif", resolved.fontKey)
        assertEquals(20f, resolved.sizeSp, 0.001f)
        assertEquals(500, resolved.weight)
        assertEquals(0.1f, resolved.letterSpacingEm, 0.001f)
        assertEquals(0xFF112233.toInt(), resolved.colorArgb)
    }

    @Test
    fun `per item override wins over the global theme`() {
        val override = ItemStyle(
            textSizeSp = 33f,
            color = ColorSpec.Solid(0xFFAABBCC.toInt()),
            fontWeight = "900",
            letterSpacingEm = 0.25f,
        )

        val resolved = StyleResolver.resolveItemStyle(theme, override)

        assertEquals(33f, resolved.sizeSp, 0.001f)
        assertEquals(0xFFAABBCC.toInt(), resolved.colorArgb)
        assertEquals(900, resolved.weight)
        assertEquals(0.25f, resolved.letterSpacingEm, 0.001f)
    }

    @Test
    fun `a partial override only replaces the fields it sets`() {
        val resolved = StyleResolver.resolveItemStyle(theme, ItemStyle(textSizeSp = 12f))

        assertEquals(12f, resolved.sizeSp, 0.001f)
        assertEquals(500, resolved.weight)
        assertEquals(0xFF112233.toInt(), resolved.colorArgb)
    }

    @Test
    fun `follow system colour resolves to null`() {
        val resolved = StyleResolver.resolveItemStyle(ThemeTokens(), null)

        assertNull(resolved.colorArgb)
    }

    @Test
    fun `an unparseable weight falls back to 400`() {
        val resolved = StyleResolver.resolveItemStyle(ThemeTokens(listFontWeight = "banana"), null)

        assertEquals(400, resolved.weight)
    }

    @Test
    fun `section header style comes from the theme`() {
        val resolved = StyleResolver.resolveSectionHeaderStyle(theme)

        assertEquals(13f, resolved.sizeSp, 0.001f)
        assertEquals(600, resolved.weight)
        assertEquals(0xFF445566.toInt(), resolved.colorArgb)
    }
}
```

`app/src/test/kotlin/dev/liner/launcher/model/AppCatalogTest.kt`:

```kotlin
package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogTest {

    private val document = LayoutDocument(
        sections = listOf(
            Section(
                id = "s1",
                title = "Daily",
                items = listOf(
                    HomeItem(id = "a", packageName = "com.mail", label = "Mail"),
                    HomeItem(id = "b", packageName = "com.missing"),
                ),
            ),
        ),
        hiddenFromDrawer = listOf("com.secret"),
    )

    private val installed = mapOf(
        "com.mail" to InstalledApp("com.mail", "com.mail.Main", "Gmail"),
    )

    @Test
    fun `home entries use the custom label when there is one`() {
        val entries = AppCatalog.homeEntries(document, installed)

        assertEquals("Mail", entries[0].displayLabel)
    }

    @Test
    fun `home entries fall back to the system label`() {
        val doc = document.copy(
            sections = listOf(
                Section(id = "s1", items = listOf(HomeItem(id = "a", packageName = "com.mail"))),
            ),
        )

        assertEquals("Gmail", AppCatalog.homeEntries(doc, installed)[0].displayLabel)
    }

    @Test
    fun `home entries fall back to the package name when the app is gone`() {
        val entries = AppCatalog.homeEntries(document, installed)

        assertEquals("com.missing", entries[1].displayLabel)
        assertFalse(entries[1].available)
    }

    @Test
    fun `home entries mark installed apps as available`() {
        assertTrue(AppCatalog.homeEntries(document, installed)[0].available)
    }

    @Test
    fun `drawer apps exclude hidden packages and sort by label`() {
        val apps = listOf(
            InstalledApp("com.secret", "com.secret.Main", "Secret"),
            InstalledApp("com.zoo", "com.zoo.Main", "Zoo"),
            InstalledApp("com.mail", "com.mail.Main", "Alpha"),
        )

        val result = AppCatalog.drawerApps(document, apps)

        assertEquals(listOf("com.mail", "com.zoo"), result.map { it.packageName })
    }

    @Test
    fun `custom labels maps package names to their renamed labels`() {
        val labels = AppCatalog.customLabels(document)

        assertEquals("Mail", labels["com.mail"])
    }
}
```

`app/src/test/kotlin/dev/liner/launcher/model/AppFilterTest.kt`:

```kotlin
package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFilterTest {

    private val apps = listOf(
        InstalledApp("com.mail", "com.mail.Main", "Gmail"),
        InstalledApp("com.map", "com.map.Main", "Maps"),
        InstalledApp("com.mus", "com.mus.Main", "Spotify"),
    )

    @Test
    fun `an empty query returns everything`() {
        assertEquals(3, AppFilter.filter(apps, "  ").size)
    }

    @Test
    fun `matching is case insensitive and substring based`() {
        assertEquals(listOf("com.mail"), AppFilter.filter(apps, "gma").map { it.packageName })
        assertEquals(listOf("com.map"), AppFilter.filter(apps, "APS").map { it.packageName })
    }

    @Test
    fun `a custom rename is also searched`() {
        val result = AppFilter.filter(apps, "post", customLabels = mapOf("com.mus" to "Post"))

        assertEquals(listOf("com.mus"), result.map { it.packageName })
    }

    @Test
    fun `a query with no matches returns an empty list`() {
        assertTrue(AppFilter.filter(apps, "zzzz").isEmpty())
    }
}
```

`app/src/test/kotlin/dev/liner/launcher/model/ClockFormatterTest.kt`:

```kotlin
package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ClockFormatterTest {

    // 2024-03-14T15:09:26Z — 14 March 2024, 15:09:26 UTC
    private val instant = Instant.parse("2024-03-14T15:09:26Z")
    private val zone = ZoneId.of("UTC")

    @Test
    fun `twenty four hour time omits the am pm marker`() {
        assertEquals("15:09", ClockFormatter.formatTime(instant.toEpochMilli(), zone, use24Hour = true, locale = Locale.UK))
    }

    @Test
    fun `twelve hour time includes the am pm marker`() {
        assertEquals("3:09 PM", ClockFormatter.formatTime(instant.toEpochMilli(), zone, use24Hour = false, locale = Locale.US))
    }

    @Test
    fun `midnight renders as twelve am`() {
        val midnight = Instant.parse("2024-03-14T00:00:00Z").toEpochMilli()

        assertEquals("12:00 AM", ClockFormatter.formatTime(midnight, zone, use24Hour = false, locale = Locale.US))
    }

    @Test
    fun `date style none returns null`() {
        assertNull(ClockFormatter.formatDate(instant.toEpochMilli(), zone, "none", Locale.UK))
    }

    @Test
    fun `date styles render their respective formats`() {
        assertEquals("Thu 14 Mar", ClockFormatter.formatDate(instant.toEpochMilli(), zone, "short", Locale.UK))
        assertEquals("Thursday, 14 March", ClockFormatter.formatDate(instant.toEpochMilli(), zone, "medium", Locale.UK))
        assertEquals("Thursday, 14 March 2024", ClockFormatter.formatDate(instant.toEpochMilli(), zone, "long", Locale.UK))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*StyleResolverTest*' --tests '*AppCatalogTest*' --tests '*AppFilterTest*' --tests '*ClockFormatterTest*'`

Expected: compilation failure — `Unresolved reference: StyleResolver`.

- [ ] **Step 3: Implement style resolution**

`model/StyleResolver.kt`:

```kotlin
package dev.liner.launcher.model

object StyleResolver {
    /**
     * Precedence: per item override > global theme > system default.
     * A null [ResolvedStyle.colorArgb] means "use the system default text colour".
     */
    fun resolveItemStyle(theme: ThemeTokens, override: ItemStyle?): ResolvedStyle {
        val colorSpec = override?.color ?: theme.textColor
        return ResolvedStyle(
            fontKey = theme.listFontFamily,
            sizeSp = override?.textSizeSp ?: theme.listTextSizeSp,
            weight = (override?.fontWeight ?: theme.listFontWeight).toIntOrNull() ?: 400,
            letterSpacingEm = override?.letterSpacingEm ?: theme.letterSpacingEm,
            colorArgb = (colorSpec as? ColorSpec.Solid)?.argb,
        )
    }

    fun resolveSectionHeaderStyle(theme: ThemeTokens): ResolvedStyle = ResolvedStyle(
        fontKey = theme.listFontFamily,
        sizeSp = theme.sectionTitleSizeSp,
        weight = 600,
        letterSpacingEm = 0.12f,
        colorArgb = (theme.sectionTitleColor as? ColorSpec.Solid)?.argb,
    )

    /**
     * The theme's text colour, used by the clock and the chrome. The `ui`
     * layer consumes this instead of reading [ThemeTokens.textColor]
     * directly — the resolver is the only place theme tokens are interpreted.
     * A null result means "follow the system", which the caller resolves
     * against the current light/dark configuration.
     */
    fun resolveTextColor(theme: ThemeTokens): Int? =
        (theme.textColor as? ColorSpec.Solid)?.argb

    fun resolveSectionTitleUppercase(theme: ThemeTokens): Boolean =
        theme.sectionTitleUppercase

    fun resolveSectionTitleRule(theme: ThemeTokens): Boolean =
        theme.sectionTitleRule

    fun resolveIconSizeDp(theme: ThemeTokens): Int = theme.iconSizeDp
}
```

`model/HomeEntry.kt`:

```kotlin
package dev.liner.launcher.model

data class HomeEntry(
    val item: HomeItem,
    val displayLabel: String,
    val available: Boolean,
    val sectionId: String,
)
```

- [ ] **Step 4: Implement the catalog and filter**

`model/AppCatalog.kt`:

```kotlin
package dev.liner.launcher.model

object AppCatalog {
    fun homeEntries(
        document: LayoutDocument,
        installed: Map<String, InstalledApp>,
    ): List<HomeEntry> = document.sections.flatMap { section ->
        section.items.map { item ->
            val app = installed[item.packageName]
            HomeEntry(
                item = item,
                displayLabel = item.label ?: app?.systemLabel ?: item.packageName,
                available = app != null,
                sectionId = section.id,
            )
        }
    }

    fun drawerApps(
        document: LayoutDocument,
        installed: List<InstalledApp>,
    ): List<InstalledApp> {
        val hidden = document.hiddenFromDrawer.toSet()
        return installed
            .filterNot { it.packageName in hidden }
            .sortedWith(compareBy({ it.systemLabel.lowercase() }, { it.packageName }))
    }

    fun customLabels(document: LayoutDocument): Map<String, String> = buildMap {
        document.sections.forEach { section ->
            section.items.forEach { item ->
                item.label?.let { put(item.packageName, it) }
            }
        }
    }
}
```

`model/AppFilter.kt`:

```kotlin
package dev.liner.launcher.model

object AppFilter {
    fun filter(
        apps: List<InstalledApp>,
        query: String,
        customLabels: Map<String, String> = emptyMap(),
    ): List<InstalledApp> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return apps
        return apps.filter { app ->
            app.systemLabel.contains(trimmed, ignoreCase = true) ||
                customLabels[app.packageName]?.contains(trimmed, ignoreCase = true) == true
        }
    }
}
```

- [ ] **Step 5: Implement clock formatting**

`model/ClockFormatter.kt`:

```kotlin
package dev.liner.launcher.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ClockFormatter {
    fun formatTime(
        epochMillis: Long,
        zone: ZoneId,
        use24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val pattern = if (use24Hour) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale).format(time)
    }

    /** Returns null for the "none" style. */
    fun formatDate(
        epochMillis: Long,
        zone: ZoneId,
        style: String,
        locale: Locale = Locale.getDefault(),
    ): String? {
        val pattern = when (style) {
            "short" -> "EEE d MMM"
            "medium" -> "EEEE, d MMMM"
            "long" -> "EEEE, d MMMM yyyy"
            else -> return null
        }
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return DateTimeFormatter.ofPattern(pattern, locale).format(date)
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*StyleResolverTest*' --tests '*AppCatalogTest*' --tests '*AppFilterTest*' --tests '*ClockFormatterTest*'`

Expected: `BUILD SUCCESSFUL`, 21 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/model app/src/test/kotlin/dev/liner/launcher/model
git commit -m "feat(model): add style resolution, app catalog, filter and clock formatting"
```

---

## Task 5: Drag Targeting Maths

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/model/DragTargeting.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/DragTargetingTest.kt`

**Interfaces:**
- Consumes: Task 2's model types.
- Produces:
  - `data class ItemBounds(id: String, sectionId: String, top: Float, bottom: Float)`
  - `sealed interface DragTarget { data class InSection(sectionId: String, index: Int); data class PreviousSection(sectionId: String) }`
  - `object DragTargeting { fun computeTarget(dragY: Float, bounds: List<ItemBounds>, draggedId: String, sectionOrder: List<String>): DragTarget? }`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/dev/liner/launcher/model/DragTargetingTest.kt`:

```kotlin
package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragTargetingTest {

    private val bounds = listOf(
        ItemBounds("a", "s1", top = 100f, bottom = 140f),
        ItemBounds("b", "s1", top = 150f, bottom = 190f),
        ItemBounds("c", "s2", top = 210f, bottom = 250f),
    )

    private val sectionOrder = listOf("s1", "s2")

    @Test
    fun `an unknown dragged id returns null`() {
        assertNull(DragTargeting.computeTarget(120f, bounds, "nope", sectionOrder))
    }

    @Test
    fun `dragging above the first item of the first section targets index zero`() {
        val target = DragTargeting.computeTarget(95f, bounds, "a", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 0), target)
    }

    @Test
    fun `dragging past the midpoint of an item moves below it`() {
        val target = DragTargeting.computeTarget(180f, bounds, "a", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 1), target)
    }

    @Test
    fun `the dragged item is excluded from the index computation`() {
        // At y=170 — the midpoint of "b" — the answer is index 1 within the
        // remaining sibling list [a]. Counting "b" as its own sibling would
        // give index 2, which no longer exists in a two-item section.
        val target = DragTargeting.computeTarget(170f, bounds, "b", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 1), target)
    }

    @Test
    fun `a drop exactly on an item's midpoint lands below it`() {
        // At y=120 — exactly the midpoint of "a" — the item goes after "a",
        // not before it.
        val target = DragTargeting.computeTarget(120f, bounds, "b", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 1), target)
    }

    @Test
    fun `dragging above the first item of a later section targets the previous section`() {
        val target = DragTargeting.computeTarget(200f, bounds, "c", sectionOrder)

        assertEquals(DragTarget.PreviousSection("s1"), target)
    }

    @Test
    fun `the first section cannot target a previous section`() {
        val target = DragTargeting.computeTarget(10f, bounds, "a", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 0), target)
    }
}
```

> The sealed type produced by `computeTarget` is named `DragTarget`. Use that name
> consistently in the test file above.
>
> The implementation above is the corrected version. The first draft measured
> "above the first item" against the *siblings* only, which left a single-item
> section with no first item to compare against — a drag could never escape
> upwards — and it used a strict `>` for the midpoint test, so a drop exactly
> on an item's midpoint landed above it. Both were caught by this task's own
> tests.

- [ ] **Step 2: Run the test to verify it fails**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*DragTargetingTest*'`

Expected: compilation failure — `Unresolved reference: DragTargeting`.

- [ ] **Step 3: Implement the drag targeting**

`model/DragTargeting.kt`:

```kotlin
package dev.liner.launcher.model

/** An on-screen rectangle for one home item, in root coordinates. */
data class ItemBounds(
    val id: String,
    val sectionId: String,
    val top: Float,
    val bottom: Float,
)

sealed interface DragTarget {
    /** Insert into [sectionId] at [index], counted with the dragged item already removed. */
    data class InSection(val sectionId: String, val index: Int) : DragTarget

    /**
     * Move into [sectionId] — the section immediately before the dragged item's —
     * appended at the end of that section's items.
     */
    data class PreviousSection(val sectionId: String) : DragTarget
}

object DragTargeting {
    /**
     * Works out where the dragged item would land if released at [dragY].
     *
     * [bounds] must be the on-screen rectangles of the home items, and
     * [sectionOrder] the document's section ids in order. Every item in
     * [bounds] must belong to a section in [sectionOrder]. The order of
     * [bounds] does not matter: the computation sorts by [ItemBounds.top].
     *
     * Returns null when the dragged item is not in [bounds].
     *
     * The dragged item keeps its slot while the drag runs; this only reports
     * the slot it would land in, so the UI can animate an insertion indicator
     * and commit the reorder on release.
     */
    fun computeTarget(
        dragY: Float,
        bounds: List<ItemBounds>,
        draggedId: String,
        sectionOrder: List<String>,
    ): DragTarget? {
        val dragged = bounds.firstOrNull { it.id == draggedId } ?: return null

        val sectionIndex = sectionOrder.indexOf(dragged.sectionId)
        val previousSectionId = if (sectionIndex > 0) sectionOrder[sectionIndex - 1] else null

        // Sorted by top so the result does not depend on the caller's order.
        val sectionItems = bounds
            .filter { it.sectionId == dragged.sectionId }
            .sortedBy { it.top }

        // The section's first item counts even when it is the dragged item
        // itself: dragging a section's top item above its own top edge means
        // moving it into the section above. sectionItems always contains the
        // dragged item, so it is never empty.
        val firstTop = sectionItems.first().top
        if (dragY < firstTop && previousSectionId != null) {
            return DragTarget.PreviousSection(previousSectionId)
        }

        // The insertion index is counted with the dragged item already removed.
        val siblings = sectionItems.filter { it.id != draggedId }
        var index = 0
        for (bound in siblings) {
            // Landing on the midpoint counts as past it, so the item goes below.
            if (dragY >= (bound.top + bound.bottom) / 2f) index++ else break
        }

        return DragTarget.InSection(dragged.sectionId, index)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*DragTargetingTest*'`

Expected: `BUILD SUCCESSFUL`, 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/model/DragTargeting.kt \
        app/src/test/kotlin/dev/liner/launcher/model/DragTargetingTest.kt
git commit -m "feat(model): add pure drag-target computation"
```

---

## Task 6: Persistence — Store Interface, Repository, DataStore Adapter

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/store/{DocumentStore,DataStoreDocumentStore,LayoutRepository}.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/store/LayoutRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 2's `DocumentCodec` and `LayoutDocument`.
- Produces:
  - `interface DocumentStore { suspend fun read(): String?; suspend fun write(json: String) }`
  - `class InMemoryDocumentStore(initial: String? = null) : DocumentStore` (test double, lives in main so the instrumentation tests can use it too)
  - `class DataStoreDocumentStore(context: Context) : DocumentStore`
  - `class LayoutRepository(store: DocumentStore)` with `val document: StateFlow<LayoutDocument>`, `val writeFailed: StateFlow<Boolean>`, `val loadFailed: StateFlow<Boolean>`, `var recoveredPayload: String?` (private setter), `suspend fun load()` (reads the store once per process), `internal suspend fun reload()` (test hook: re-reads the store), `fun update(transform: (LayoutDocument) -> LayoutDocument)`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/dev/liner/launcher/store/LayoutRepositoryTest.kt`:

```kotlin
package dev.liner.launcher.store

import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.addSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutRepositoryTest {

    @Test
    fun `load reads the stored document`() = runTest {
        val store = InMemoryDocumentStore(DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1")))))

        val repository = LayoutRepository(store)
        repository.load()

        assertEquals(listOf("s1"), repository.document.value.sections.map { it.id })
    }

    @Test
    fun `load falls back to defaults when the stored payload is corrupt`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore("{not json"))
        repository.load()

        assertEquals(LayoutDocument(), repository.document.value)
    }

    @Test
    fun `a corrupt payload is reported rather than silently discarded`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore("{not json"))
        repository.load()

        assertEquals(LayoutDocument(), repository.document.value)
        assertTrue(repository.loadFailed.value)
        assertEquals("{not json", repository.recoveredPayload)
    }

    @Test
    fun `a failed read is reported rather than treated as nothing stored`() = runTest {
        val store = object : DocumentStore {
            override suspend fun read(): String? = throw IllegalStateException("disk unreadable")
            override suspend fun write(json: String) = Unit
        }
        val repository = LayoutRepository(store)

        repository.load()

        assertEquals(LayoutDocument(), repository.document.value)
        assertTrue(repository.loadFailed.value)
    }

    @Test
    fun `an empty store is not a failure`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore(null))
        repository.load()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `a readable payload does not report a failure`() = runTest {
        val stored = DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1"))))
        val repository = LayoutRepository(InMemoryDocumentStore(stored))
        repository.load()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `load falls back to defaults when there is nothing stored`() = runTest {
        val repository = LayoutRepository(InMemoryDocumentStore(null))
        repository.load()

        assertEquals(LayoutDocument(), repository.document.value)
    }

    @Test
    fun `update mutates the in memory document and persists it`() = runTest {
        val store = InMemoryDocumentStore()
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.addSection("s1", "One") }
        repository.awaitPendingWrite()

        assertEquals(listOf("s1"), repository.document.value.sections.map { it.id })
        assertTrue(store.read()!!.contains("\"s1\""))
    }

    @Test
    fun `a failed write is flagged and retried on the next update`() = runTest {
        val store = object : DocumentStore {
            var failNext = true
            val written = mutableListOf<String>()
            override suspend fun read(): String? = null
            override suspend fun write(json: String) {
                if (failNext) { failNext = false; throw IllegalStateException("disk full") }
                written += json
            }
        }
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.addSection("s1") }
        repository.awaitPendingWrite()
        assertTrue(repository.writeFailed.value)

        repository.update { it.addSection("s2") }
        repository.awaitPendingWrite()
        assertFalse(repository.writeFailed.value)
        assertEquals(1, store.written.size)
    }

    @Test
    fun `concurrent updates are serialised so the last write wins`() = runTest {
        val store = InMemoryDocumentStore()
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.addSection("s1") }
        repository.update { it.addSection("s2") }
        repository.awaitPendingWrite()

        assertEquals(listOf("s1", "s2"), repository.document.value.sections.map { it.id })
        assertTrue(store.read()!!.contains("s2"))
    }

    @Test
    fun `a later successful load clears an earlier failure`() = runTest {
        val store = InMemoryDocumentStore("{not json")
        val repository = LayoutRepository(store)
        repository.load()
        assertTrue(repository.loadFailed.value)
        assertEquals("{not json", repository.recoveredPayload)

        store.write(DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s1")))))
        repository.reload()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `a cancelled write is not reported as a write failure`() = runTest {
        val store = object : DocumentStore {
            override suspend fun read(): String? = null
            override suspend fun write(json: String): Unit = throw CancellationException("cancelled")
        }
        val repository = LayoutRepository(store)
        repository.load()

        repository.update { it.addSection("s1") }
        repository.awaitPendingWrite()

        assertFalse(repository.writeFailed.value)
    }

    @Test
    fun `an empty store clears a failure from an earlier failed read`() = runTest {
        val store = object : DocumentStore {
            var failRead = true
            override suspend fun read(): String? {
                if (failRead) { failRead = false; throw IllegalStateException("disk unreadable") }
                return null
            }
            override suspend fun write(json: String) = Unit
        }
        val repository = LayoutRepository(store)

        repository.load()
        assertTrue(repository.loadFailed.value)

        repository.reload()

        assertFalse(repository.loadFailed.value)
        assertNull(repository.recoveredPayload)
    }

    @Test
    fun `a second load does not discard an edit that has not been written back yet`() = runTest {
        // The write is held back, so the store keeps the pre-edit document
        // for as long as the test needs it to.
        val gate = CompletableDeferred<Unit>()
        val stored = DocumentCodec.encode(LayoutDocument(sections = listOf(Section(id = "s0"))))
        val store = object : DocumentStore {
            var content: String? = stored
            override suspend fun read(): String? = content
            override suspend fun write(json: String) {
                gate.await()
                content = json
            }
        }
        val repository = LayoutRepository(store)
        repository.load()

        // The edit is applied in memory and queued for the writer, but the
        // write has not landed yet.
        repository.update { it.addSection("s1", "One") }

        // A second read of the store would replace the in-memory document
        // with the pre-edit one and throw the edit away.
        repository.load()

        assertEquals(listOf("s0", "s1"), repository.document.value.sections.map { it.id })

        gate.complete(Unit)
    }

    @Test
    fun `a burst of updates is persisted once, with the final state`() = runTest {
        val store = CountingDocumentStore()
        val repository = LayoutRepository(store)
        repository.load()

        // A warm-up update pays the class-loading cost, so the measured burst
        // below is not slowed down by the first call on a cold JVM. It is
        // superseded by the burst either way.
        repository.update { it }

        // Dragging a settings slider fires an update per frame. The store
        // only ever needs the settled state of the gesture, so the burst has
        // to collapse into a single write of the final document.
        repeat(40) { index ->
            repository.update { it.addSection("s$index") }
        }

        repository.awaitPendingWrite()

        assertEquals(1, store.writeCount)

        // The live preview still saw every movement: the in-memory document
        // holds all of them, and the one write that did land holds them all
        // too.
        val expected = (0 until 40).map { "s$it" }
        assertEquals(expected, repository.document.value.sections.map { it.id })
        val decoded = store.lastWritten?.let(DocumentCodec::decode)
        assertEquals(expected, decoded?.sections?.map { it.id })
    }
}

/**
 * A [DocumentStore] that counts the writes it receives, so a test can pin
 * how many times the document was persisted.
 */
private class CountingDocumentStore : DocumentStore {

    private val lock = Any()
    private val written = mutableListOf<String>()

    /** How many times the document was written. */
    val writeCount: Int get() = synchronized(lock) { written.size }

    /** The payload of the last write, or null if nothing was written. */
    val lastWritten: String? get() = synchronized(lock) { written.lastOrNull() }

    override suspend fun read(): String? = null

    override suspend fun write(json: String) {
        synchronized(lock) { written += json }
    }
}
```

> `awaitPendingWrite()` is a test-visible suspend function that awaits the
> most recent write. Because writes are consumed from a conflated channel by a
> single coroutine, awaiting the latest write also implies the store holds the
> latest document. It does **not** imply that every earlier write was
> persisted: a write that is superseded while it waits is dropped, and its
> deferred is never completed. Nothing may await a *specific* write's
> deferred — only `latestWrite`, which always points at the newest one. Add it
> to `LayoutRepository` as
> `internal suspend fun awaitPendingWrite()`.
>
> `reload()` is a test-visible suspend function that re-reads the store,
> ignoring the once-per-process guard on `load()`. A test that needs a
> *second* read — the ones that clear an earlier read failure — calls it
> instead of `load()`. Add it to `LayoutRepository` as
> `internal suspend fun reload()`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*LayoutRepositoryTest*'`

Expected: compilation failure — `Unresolved reference: LayoutRepository`.

- [ ] **Step 3: Implement the store interface and the in-memory double**

`store/DocumentStore.kt`:

```kotlin
package dev.liner.launcher.store

interface DocumentStore {
    suspend fun read(): String?
    suspend fun write(json: String)
}

/** An in-memory [DocumentStore]. Used by tests and by the import preview flow. */
class InMemoryDocumentStore(initial: String? = null) : DocumentStore {
    @Volatile
    private var content: String? = initial

    override suspend fun read(): String? = content

    override suspend fun write(json: String) {
        content = json
    }
}
```

- [ ] **Step 4: Implement the repository**

`store/LayoutRepository.kt`:

```kotlin
package dev.liner.launcher.store

import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.LayoutDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The single writer for the layout document.
 *
 * Every mutation goes through [update], which updates the in-memory state
 * immediately and persists it asynchronously. Writes are funnelled through one
 * conflated channel consumed by a single coroutine, so two rapid edits can
 * never land out of order — and a burst of edits (a dragged slider fires one
 * per frame) collapses into a single write of the settled state.
 */
class LayoutRepository(private val store: DocumentStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class PendingWrite(
        val document: LayoutDocument,
        val done: CompletableDeferred<Unit>,
    )

    // Conflated: the store only ever needs the newest document, so an
    // update that arrives while another is pending replaces it.
    private val writes = Channel<PendingWrite>(Channel.CONFLATED)
    private val latestWrite = AtomicReference<CompletableDeferred<Unit>?>(null)

    private val _document = MutableStateFlow(LayoutDocument())
    val document: StateFlow<LayoutDocument> = _document.asStateFlow()

    private val _writeFailed = MutableStateFlow(false)
    val writeFailed: StateFlow<Boolean> = _writeFailed.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)

    private val _documentLoaded = MutableStateFlow(false)

    /**
     * True once the store has been read into memory.
     *
     * The signal belongs to the repository, not to a caller of [load]: the
     * store is read once per process, so a second caller's [load] returns
     * immediately — a caller that waited on its own call would proceed
     * before the document was actually in memory, and a write it triggered
     * would persist the default document over the user's stored layout.
     */
    val documentLoaded: StateFlow<Boolean> = _documentLoaded.asStateFlow()

    /** Whether the store has already been read into memory. */
    private val loaded = AtomicBoolean(false)

    /**
     * Transforms that arrived before the document was read. They are applied
     * to the real document once it is in memory — applying them to the
     * defaults would both lose the edit and, because the result is queued
     * for writing, destroy the stored layout.
     */
    private val pendingTransforms = mutableListOf<(LayoutDocument) -> LayoutDocument>()

    /**
     * Guards the document and the queue of pending transforms.
     *
     * The check that decides whether an edit is applied or queued, the
     * application of an edit and the drain of the queue all happen under it:
     * an edit must never be applied against a document that a queued edit is
     * about to change, or the two would overwrite each other.
     */
    private val lock = Any()

    /** True when the stored layout could not be read and the defaults were used instead. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** The unreadable payload that [load] fell back from, kept for logging. */
    var recoveredPayload: String? = null
        private set

    init {
        scope.launch {
            for (pending in writes) {
                // Let a burst of updates settle before writing. Dragging a
                // settings slider fires an update per frame, and each write
                // rewrites the whole document — so writing every one of them
                // would hammer the disk for no benefit. The store only ever
                // needs the settled state of the gesture.
                delay(WRITE_QUIET_MS)

                // A newer update may have arrived while we waited. The store
                // only ever needs the newest state, so the older one is
                // superseded rather than written out.
                val newest = writes.tryReceive().getOrNull() ?: pending
                writeDocument(newest)
            }
        }
    }

    /**
     * Reads the stored document.
     *
     * This is the recovery boundary at app start, so it catches Throwable
     * rather than Exception: a pathological payload (deeply nested JSON
     * raising StackOverflowError) must not crash the launcher, which the spec
     * explicitly forbids.
     *
     * When the stored payload cannot be decoded the document falls back to
     * defaults, but the failure is *reported* rather than silent: [loadFailed]
     * goes true and the original payload is kept in [recoveredPayload] so the
     * platform layer can log it and the UI can tell the user their layout was
     * reset instead of quietly losing it.
     */
    suspend fun load() {
        // The store is read once per process. A second load would race the
        // writer: an edit that has been applied in memory but not yet
        // written to the store would be discarded by the older stored value.
        // Later screens therefore get the document the repository already
        // holds.
        //
        // The trade-off: a store that cannot be read at start-up stays
        // unreadable for the life of the process. The failure is reported
        // through [loadFailed] and the user can retry by restarting the app.
        if (!loaded.compareAndSet(false, true)) return
        readFromStore()
    }

    /**
     * Test hook: re-reads the store, ignoring the once-per-process guard.
     */
    internal suspend fun reload() {
        readFromStore()
    }

    private suspend fun readFromStore() {
        try {
            var readFailed = false
            val raw = try {
                store.read()
            } catch (_: Throwable) {
                // The read itself failed — that is not the same as "nothing
                // stored", and the user's layout must not be reported as if it
                // had never existed.
                readFailed = true
                null
            }

            if (raw == null) {
                // Nothing stored is not a failure, so the flag is cleared here
                // rather than left over from an earlier failed load.
                recoveredPayload = null
                _loadFailed.value = readFailed
                _document.value = LayoutDocument()
                return
            }

            val decoded = try {
                DocumentCodec.decode(raw)
            } catch (_: Throwable) {
                null
            }

            if (decoded == null) {
                recoveredPayload = raw
                _loadFailed.value = true
                _document.value = LayoutDocument()
            } else {
                recoveredPayload = null
                _loadFailed.value = false
                _document.value = decoded
            }
        } finally {
            // Whether the read succeeded or failed, the document has been
            // dealt with: a failed read leaves the defaults in memory, and
            // the caller must know that too.
            //
            // The queued edits are applied under the same lock that [update]
            // takes, so an edit that arrives while the queue is draining
            // waits for the lock and is applied after the queued ones — the
            // edits keep the order in which the user made them.
            synchronized(lock) {
                _documentLoaded.value = true
                val pending = pendingTransforms.toList()
                pendingTransforms.clear()
                pending.forEach { applyTransform(it) }
            }
        }
    }

    /**
     * Persists one document and reports whether it worked.
     *
     * A superseded write is never persisted at all — its [PendingWrite.done]
     * is simply dropped, so nothing may await a *specific* write's deferred.
     * [awaitPendingWrite] awaits the newest write, which is always the one
     * that is (or will be) written.
     */
    private suspend fun writeDocument(pending: PendingWrite) {
        try {
            store.write(DocumentCodec.encode(pending.document))
            _writeFailed.value = false
        } catch (e: CancellationException) {
            // Never swallow cancellation — it must propagate so the
            // writer shuts down with the rest of its scope.
            throw e
        } catch (_: Throwable) {
            // Throwable here, unlike the model layer's Exception: this
            // is a long-lived writer coroutine, and letting an Error
            // escape would kill it — after which every later edit would
            // silently stop being persisted.
            _writeFailed.value = true
        } finally {
            pending.done.complete(Unit)
        }
    }

    /**
     * Applies [transform] to the in-memory document and persists the result.
     *
     * An edit that arrives before the store has been read is *queued*, not
     * applied: the document in memory is then the default one, and applying
     * the edit to it would lose the edit and — because the result is queued
     * for writing — write the defaults over the user's stored layout. The
     * queued edit is applied to the real document as soon as it is in memory.
     *
     * A transform that changed nothing queues no write: the transform is
     * expected to return the document it was given when there is nothing to
     * change — the identity reference is what says so, so a transform that
     * rebuilds an equal document is still written. That keeps a no-op edit
     * (a prune that finds nothing stale, say) from rewriting the store.
     */
    fun update(transform: (LayoutDocument) -> LayoutDocument) {
        synchronized(lock) {
            if (!_documentLoaded.value) {
                // The store has not been read yet. Queue the transform.
                pendingTransforms += transform
                return
            }
            applyTransform(transform)
        }
    }

    /**
     * Applies [transform] to the in-memory document and persists the result.
     *
     * The caller holds [lock]: the document is read, transformed and written
     * back under it, so that two edits can never be applied against the same
     * document and overwrite each other — and so that a queued edit is never
     * overtaken by an edit that arrives after it.
     */
    private fun applyTransform(transform: (LayoutDocument) -> LayoutDocument) {
        val current = _document.value
        val next = transform(current)
        // A transform that changed nothing must not queue a write: the store
        // would be rewritten for no reason — once per launcher resume, for a
        // no-op prune, for instance.
        if (next === current) return
        _document.value = next

        val done = CompletableDeferred<Unit>()
        latestWrite.set(done)
        writes.trySend(PendingWrite(next, done))
    }

    /** Test hook: waits for the most recent write to settle. */
    internal suspend fun awaitPendingWrite() {
        latestWrite.get()?.await()
    }

    companion object {
        /** How long the writer waits for a burst of updates to settle. */
        private const val WRITE_QUIET_MS = 100L
    }
}
```

- [ ] **Step 5: Implement the DataStore adapter**

`store/DataStoreDocumentStore.kt`:

```kotlin
package dev.liner.launcher.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first

class DataStoreDocumentStore(context: Context) : DocumentStore {

    // Hoisted so the lambda below captures the application context, not
    // whatever context this was constructed with — an Activity would be
    // retained by the DataStore for the life of the process.
    private val appContext: Context = context.applicationContext

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
        produceFile = { appContext.preferencesDataStoreFile(FILE_NAME) },
    )

    override suspend fun read(): String? = dataStore.data.first()[KEY]

    override suspend fun write(json: String) {
        dataStore.edit { preferences -> preferences[KEY] = json }
    }

    private companion object {
        val KEY = stringPreferencesKey("layout_document")
        const val FILE_NAME = "liner_layout"
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*LayoutRepositoryTest*'`

Expected: `BUILD SUCCESSFUL`, 13 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/store app/src/test/kotlin/dev/liner/launcher/store
git commit -m "feat(store): add document store, repository and DataStore adapter"
```

---

## Task 7: Installed Apps Discovery and Icon Cache

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/apps/{InstalledAppsRepository,IconCache}.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/platform/{AppContainer,LinerApp}.kt` — move `LinerApp` here
- Test: `app/src/test/kotlin/dev/liner/launcher/model/AppCatalogIntegrationTest.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/apps/AppListProviderTest.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/apps/IconCacheTest.kt`

**Interfaces:**
- Consumes: Task 2's `InstalledApp`, Task 4's `AppCatalog`.
- Produces:
  - `class AppListProvider(query: () -> List<InstalledApp>)` with `fun load(): AppLoad` — pure, no Android types, so the fallback is JVM-testable
  - `class InstalledAppsRepository(context: Context)` with `fun loadInstalledApps(): AppLoad`
  - `data class AppLoad(val apps: List<InstalledApp>, val wasFallback: Boolean)` — the list and the trust flag travel together, because the repository is shared between the home and the settings screens
  - `class IconCache(loadIcon: (String) -> ImageBitmap?, sizePx: Int)` with `fun icon(packageName: String): ImageBitmap?` and `companion object { fun forPackageManager(packageManager: PackageManager, sizePx: Int): IconCache }`
  - `class AppContainer(context: Context)` with `val repository: LayoutRepository`, `val appsRepository: InstalledAppsRepository`, `val iconCache: IconCache`
  - `class LinerApp : Application` with `val container: AppContainer`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/dev/liner/launcher/model/AppCatalogIntegrationTest.kt`:

```kotlin
package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the pure catalog against a realistic mixed set of installed and
 * uninstalled apps, mirroring what the repository returns on a real device.
 */
class AppCatalogIntegrationTest {

    @Test
    fun `home shows custom labels, live labels and missing packages`() {
        val document = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    title = "Daily",
                    items = listOf(
                        HomeItem(id = "a", packageName = "com.mail", label = "Inbox"),
                        HomeItem(id = "b", packageName = "com.maps"),
                        HomeItem(id = "c", packageName = "com.gone"),
                    ),
                ),
            ),
        )
        val installed = mapOf(
            "com.mail" to InstalledApp("com.mail", "com.mail.Main", "Gmail"),
            "com.maps" to InstalledApp("com.maps", "com.maps.Main", "Maps"),
        )

        val entries = AppCatalog.homeEntries(document, installed)

        assertEquals(listOf("Inbox", "Maps", "com.gone"), entries.map { it.displayLabel })
        assertEquals(listOf(true, true, false), entries.map { it.available })
    }

    @Test
    fun `drawer shows every installed app except the hidden ones`() {
        val document = LayoutDocument(hiddenFromDrawer = listOf("com.maps"))
        val installed = listOf(
            InstalledApp("com.mail", "com.mail.Main", "Gmail"),
            InstalledApp("com.maps", "com.maps.Main", "Maps"),
            InstalledApp("com.notes", "com.notes.Main", "Notes"),
        )

        val result = AppCatalog.drawerApps(document, installed)

        assertEquals(listOf("com.mail", "com.notes"), result.map { it.packageName })
    }
}
```

- [ ] **Step 1b: Write the app list fallback test**

`app/src/test/kotlin/dev/liner/launcher/apps/AppListProviderTest.kt`:

```kotlin
package dev.liner.launcher.apps

import dev.liner.launcher.model.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListProviderTest {

    private val apps = listOf(
        InstalledApp("com.mail", "com.mail.Main", "Gmail"),
        InstalledApp("com.maps", "com.maps.Main", "Maps"),
    )

    @Test
    fun `a successful query is returned`() {
        val provider = AppListProvider { apps }

        assertEquals(apps, provider.load())
    }

    @Test
    fun `a failed query falls back to the last successful list`() {
        var shouldFail = false
        val provider = AppListProvider {
            if (shouldFail) throw IllegalStateException("package manager unavailable") else apps
        }

        assertEquals(apps, provider.load())

        shouldFail = true
        assertEquals(apps, provider.load())
    }

    @Test
    fun `a failed query before any success returns an empty list`() {
        val provider = AppListProvider {
            throw IllegalStateException("package manager unavailable")
        }

        assertTrue(provider.load().isEmpty())
    }

    @Test
    fun `an empty result does not blank the remembered list`() {
        var empty = false
        val provider = AppListProvider { if (empty) emptyList() else apps }

        provider.load()
        empty = true

        assertEquals(apps, provider.load())
    }
}
```

This is the only automated coverage of the spec's "failed query falls back to
the last successful cached app list" requirement, and it runs on the JVM —
the Android `PackageManager` call itself is the only part that needs a device.

- [ ] **Step 1c: Write the icon cache policy test**

`app/src/test/kotlin/dev/liner/launcher/apps/IconCacheTest.kt`:

```kotlin
package dev.liner.launcher.apps

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class IconCacheTest {

    @Test
    fun `a genuinely missing package is remembered and not asked for again`() {
        var calls = 0
        val cache = IconCache(
            loadIcon = { packageName ->
                calls++
                throw PackageManager.NameNotFoundException()
            },
            sizePx = 96,
        )

        assertNull(cache.icon("com.gone"))
        assertNull(cache.icon("com.gone"))

        assertEquals(1, calls)
    }

    @Test
    fun `a transient failure is not remembered so the next call retries`() {
        var calls = 0
        val cache = IconCache(
            loadIcon = { packageName ->
                calls++
                throw IllegalStateException("transient")
            },
            sizePx = 96,
        )

        assertNull(cache.icon("com.flaky"))
        assertNull(cache.icon("com.flaky"))

        assertEquals(2, calls)
    }

    @Test
    fun `an error is not swallowed`() {
        val cache = IconCache(
            loadIcon = { packageName -> throw OutOfMemoryError("no room") },
            sizePx = 96,
        )

        try {
            cache.icon("com.oom")
            fail("expected the error to propagate")
        } catch (_: OutOfMemoryError) {
            // expected — an Error must not be swallowed into a silent fallback
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*AppCatalogIntegrationTest*'`

Expected: **the test passes immediately** (2 tests, 0 failures). This is a contract
test, not a RED-phase test: it exercises `AppCatalog`, which Task 4 already
built and tested, and pins the contract the Android adapter in this task
depends on. The genuinely new code in this task is the Android adapter
(`InstalledAppsRepository`, `IconCache`), which needs a device to exercise —
there is no honest RED phase for it on the JVM, and inventing one would be
theatre rather than verification. The adapter is verified by compiling and
by the instrumentation tests in Task 15.

- [ ] **Step 3: Implement the installed apps repository**

`apps/InstalledAppsRepository.kt`:

```kotlin
package dev.liner.launcher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.liner.launcher.model.InstalledApp

/**
 * The result of an app query: the apps, and whether the list really
 * came from the package manager.
 *
 * The two travel together because the repository is shared: reading the
 * list and the flag in two calls could pick up a load performed in between
 * by the other screen.
 */
data class AppLoad(
    val apps: List<InstalledApp>,
    val wasFallback: Boolean,
)

/**
 * The state machine behind [InstalledAppsRepository]: remembers the last
 * successful list and falls back to it when a query fails.
 *
 * Deliberately free of Android types — it takes a query *function* — so the
 * fallback is unit-testable on the JVM, which is the only way to prove the
 * launcher survives a failing package manager.
 */
class AppListProvider(private val query: () -> List<InstalledApp>) {

    @Volatile
    private var lastSuccessful: List<InstalledApp> = emptyList()

    /**
     * The apps of the most recent query, and whether they really came from
     * the package manager.
     *
     * A fallback list is never evidence that an app was uninstalled: it
     * covers a failed query and one that came back empty because the OS
     * filtered the package visibility. Neither may be used to decide that an
     * app is gone.
     */
    fun load(): AppLoad = try {
        val apps = query()
        if (apps.isNotEmpty()) {
            lastSuccessful = apps
            AppLoad(apps, wasFallback = false)
        } else {
            // An empty result is not a successful result: some OEMs surface
            // restricted package visibility as an empty list rather than an
            // error, and adopting that would blank the drawer and destroy the
            // cache. A device always has at least one launchable activity —
            // its own launcher — so an empty list means the query was
            // filtered, and the remembered list stands in for it.
            AppLoad(lastSuccessful, wasFallback = true)
        }
    } catch (_: Exception) {
        // Exception, not Throwable, to match the icon path: a bug or an
        // OutOfMemoryError must surface rather than being swallowed into a
        // silent fallback.
        AppLoad(lastSuccessful, wasFallback = true)
    }
}

/**
 * Reads the launchable apps installed on the device.
 *
 * Never throws for a data failure: a failed query falls back to the last
 * successful list rather than showing the user an empty drawer — the
 * launcher must keep working when the package manager is briefly
 * unavailable. Errors are deliberately not swallowed.
 */
class InstalledAppsRepository(context: Context) {

    private val packageManager = context.applicationContext.packageManager

    private val provider = AppListProvider { queryInstalledApps() }

    fun loadInstalledApps(): AppLoad = provider.load()

    private fun queryInstalledApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(
            intent,
            // A disabled app is still installed: the user switched it off,
            // they did not remove it. Without this flag the query leaves it
            // out, and the auto-prune would delete the user's home entry for
            // an app that is still on the device.
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
            .mapNotNull { resolved ->
                val info = resolved.activityInfo ?: return@mapNotNull null
                // A label that is null, or that throws because the package was
                // uninstalled between the query and the load, must cost only
                // that one app — not the whole query.
                val label = try {
                    resolved.loadLabel(packageManager)?.toString()
                } catch (_: Exception) {
                    null
                }
                InstalledApp(
                    packageName = info.packageName,
                    activityClass = info.name,
                    systemLabel = label ?: info.packageName,
                )
            }
            // One entry per app: an app with two MAIN/LAUNCHER activities must
            // not produce two tiles in the drawer, and the home path keys its
            // entries by package name anyway.
            .distinctBy { it.packageName }
    }
}
```

- [ ] **Step 4: Implement the icon cache**

`apps/IconCache.kt`:

```kotlin
package dev.liner.launcher.apps

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Caches app icons rasterised to a bitmap.
 *
 * The bitmap keeps the drawable's intrinsic resolution, falling back to
 * [sizePx] only when the drawable reports no size of its own — that keeps
 * adaptive icons crisp when the UI scales them down. A failed load returns
 * null rather than throwing, so the caller can render the app label instead;
 * errors are deliberately not swallowed.
 *
 * The loader is injected rather than taken as a PackageManager, so the
 * caching and stickiness policy can be tested on the JVM without a device.
 */
class IconCache(
    private val loadIcon: (String) -> ImageBitmap?,
    private val sizePx: Int,
) {
    // Bounded by bytes, not entry count: these are ARGB_8888 bitmaps at the
    // drawable's intrinsic resolution, so a few hundred KB each on a modern
    // device. A count-based ceiling of 64 would be tens of megabytes.
    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    // Packages that are genuinely gone. Without this, every recomposition of
    // a tile whose icon is missing re-enters the loader and re-throws.
    private val unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun icon(packageName: String): ImageBitmap? {
        val key = "$packageName@$sizePx"
        cache.get(key)?.let { return it }
        if (packageName in unavailable) return null

        val bitmap = try {
            loadIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            // The package is genuinely gone. Remember that, so the tile stops
            // asking for an icon that will never load.
            unavailable += packageName
            null
        } catch (_: Exception) {
            // A transient failure — deliberately NOT remembered, so the next
            // attempt retries rather than leaving the tile iconless for the
            // rest of the session. Errors are deliberately not caught here.
            null
        }

        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    companion object {
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

        /**
         * The production loader: rasterises the package's application icon.
         */
        fun forPackageManager(packageManager: PackageManager, sizePx: Int): IconCache =
            IconCache(
                loadIcon = { packageName ->
                    packageManager.getApplicationIcon(packageName).toImageBitmap(sizePx)
                },
                sizePx = sizePx,
            )
    }
}

/** Rasterises a drawable, falling back to [fallbackSize] when it reports no size. */
private fun Drawable.toImageBitmap(fallbackSize: Int): ImageBitmap? {
    val width = if (intrinsicWidth > 0) intrinsicWidth else fallbackSize
    val height = if (intrinsicHeight > 0) intrinsicHeight else fallbackSize
    if (width <= 0 || height <= 0) return null

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, width, height)
    draw(canvas)
    return bitmap.asImageBitmap()
}
```

- [ ] **Step 5: Implement the container and application**

`platform/AppContainer.kt`:

```kotlin
package dev.liner.launcher.platform

import android.content.Context
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.store.DataStoreDocumentStore
import dev.liner.launcher.store.LayoutRepository

/** Manual dependency container. Small enough that a DI framework would be overhead. */
class AppContainer(context: Context) {

    private companion object {
        const val ICON_TILE_DP = 48
    }

    private val appContext = context.applicationContext

    val repository: LayoutRepository = LayoutRepository(DataStoreDocumentStore(appContext))
    val appsRepository: InstalledAppsRepository = InstalledAppsRepository(appContext)
    // 48dp is the drawer's tile size; the pixel size follows the density.
    val iconCache: IconCache = IconCache.forPackageManager(
        appContext.packageManager,
        sizePx = (ICON_TILE_DP * appContext.resources.displayMetrics.density).toInt(),
    )
}
```

`LinerApp.kt` — move it from `dev.liner.launcher` to `dev.liner.launcher.platform` and update the manifest `android:name` to `.platform.LinerApp`:

```kotlin
package dev.liner.launcher.platform

import android.app.Application

class LinerApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
```

Update `app/src/main/AndroidManifest.xml`: `android:name=".platform.LinerApp"`.

- [ ] **Step 6: Run the tests and the build**

Run:

```bash
cd /workspace
export ANDROID_HOME=/workspace/.toolchain/android-sdk GRADLE_USER_HOME=/workspace/.gradle-home
"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. The unit test suite passes and the APK builds.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher app/src/test app/src/main/AndroidManifest.xml
git commit -m "feat(apps): add installed app discovery, icon cache and app container"
```

---

## Task 8: Platform Shell — Launching, Default Home Detection, Wallpaper, Theme

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/platform/{AppLauncher,DefaultHomeDetector,WallpaperProvider,LinerTheme}.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/platform/LauncherActivity.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/ClockFormatterTest.kt` (extended)

**Interfaces:**
- Consumes: Task 2's `InstalledApp`, Task 7's `AppContainer`.
- Produces:
  - `class AppLauncher(context: Context)` with `fun launch(app: InstalledApp): Boolean`
  - `object DefaultHomeDetector` with `fun isDefaultHome(context: Context): Boolean` and `fun requestDefaultHome(context: Context)`
  - `object WallpaperProvider` with `fun load(context: Context): Drawable?`
  - `object LinerTheme` with `@Composable fun LinerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`
  - `fun fontFamilyFor(key: String): FontFamily`
  - `fun weightFor(value: Int): FontWeight`

- [ ] **Step 1: Extend the clock formatter test with the theme mapping**

Add to `app/src/test/kotlin/dev/liner/launcher/model/ClockFormatterTest.kt` a new file
`app/src/test/kotlin/dev/liner/launcher/platform/ThemeMappingTest.kt`:

```kotlin
package dev.liner.launcher.platform

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeMappingTest {

    @Test
    fun `known font keys map to the expected families`() {
        assertEquals(FontFamily.SansSerif, fontFamilyFor("sans-serif"))
        assertEquals(FontFamily.Serif, fontFamilyFor("serif"))
        assertEquals(FontFamily.Monospace, fontFamilyFor("monospace"))
        assertEquals(FontFamily.Cursive, fontFamilyFor("cursive"))
    }

    @Test
    fun `every offered font key renders differently`() {
        val families = FONT_KEYS.map { fontFamilyFor(it) }

        assertEquals(
            "every font the picker offers must look different",
            FONT_KEYS.size,
            families.distinct().size,
        )
    }

    @Test
    fun `an unknown font key falls back to sans serif`() {
        assertEquals(FontFamily.SansSerif, fontFamilyFor("comic"))
    }

    @Test
    fun `numeric weights map to font weights`() {
        assertEquals(FontWeight.Light, weightFor(300))
        assertEquals(FontWeight.Normal, weightFor(400))
        assertEquals(FontWeight.Bold, weightFor(700))
        assertEquals(FontWeight.Black, weightFor(900))
    }

    @Test
    fun `an out of range weight falls back to normal`() {
        assertEquals(FontWeight.Normal, weightFor(42))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*ThemeMappingTest*'`

Expected: compilation failure — `Unresolved reference: fontFamilyFor`.

- [ ] **Step 3: Implement the theme mapping**

`platform/LinerTheme.kt`:

```kotlin
package dev.liner.launcher.platform

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The font families a user can pick from in Settings → Appearance.
 *
 * Only genuinely distinct families are offered. The platform's
 * "sans-serif-light", "sans-serif-medium" and "sans-serif-condensed" faces
 * cannot be referenced from Compose without bundling the font files as
 * resources — `android.R.font` does not expose them and Compose's `Font`
 * factory only accepts a resource id — so offering them would present three
 * choices that render identically to plain sans-serif.
 */
val FONT_KEYS = listOf(
    "sans-serif",
    "serif",
    "monospace",
    "cursive",
)

fun fontFamilyFor(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

fun weightFor(value: Int): FontWeight = when (value) {
    100 -> FontWeight.Thin
    200 -> FontWeight.ExtraLight
    300 -> FontWeight.Light
    400 -> FontWeight.Normal
    500 -> FontWeight.Medium
    600 -> FontWeight.SemiBold
    700 -> FontWeight.Bold
    800 -> FontWeight.ExtraBold
    900 -> FontWeight.Black
    else -> FontWeight.Normal
}

@Composable
fun LinerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFE8E8ED),
            onPrimary = Color(0xFF101014),
            background = Color(0xFF101014),
            surface = Color(0xFF101014),
            onBackground = Color(0xFFF5F5F7),
            onSurface = Color(0xFFF5F5F7),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1A1A1E),
            onPrimary = Color(0xFFFAFAFC),
            background = Color(0xFFFAFAFC),
            surface = Color(0xFFFAFAFC),
            onBackground = Color(0xFF1A1A1E),
            onSurface = Color(0xFF1A1A1E),
        )
    }

    MaterialTheme(colorScheme = colors, content = content)
}
```

- [ ] **Step 4: Implement launching, default-home detection and the wallpaper**

`platform/AppLauncher.kt`:

```kotlin
package dev.liner.launcher.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.liner.launcher.model.InstalledApp

/** Starts an installed app. Returns false when the app cannot be started. */
class AppLauncher(context: Context) {

    private val appContext = context.applicationContext

    fun launch(app: InstalledApp): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activityClass))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        appContext.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }
}
```

`platform/DefaultHomeDetector.kt`:

```kotlin
package dev.liner.launcher.platform

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

object DefaultHomeDetector {

    /**
     * True when Liner is currently the default home app.
     *
     * On API 29+ the HOME role is the authoritative answer. Below that, the
     * *resolved* default home activity is the answer — note that
     * `queryIntentActivities` is not, because it returns every activity that
     * handles the intent, and Liner is always among them.
     */
    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = runCatching {
                context.getSystemService(RoleManager::class.java)
            }.getOrNull()
            val held = roleManager?.let {
                runCatching { it.isRoleHeld(RoleManager.ROLE_HOME) }.getOrNull()
            }
            if (held != null) return held
        }

        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName == context.packageName
        } catch (_: Throwable) {
            // The query is a package-manager round trip; if it fails the
            // launcher cannot prove it is the default home app, but it must
            // not crash.
            false
        }
    }

    /** Fires the system flow that lets the user make Liner the default home app. */
    fun requestDefaultHome(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
            if (roleManager != null) {
                // Nothing to do if Liner already holds the role — sending the
                // user to a settings screen with nothing to change is worse
                // than doing nothing.
                if (isRoleHeld(roleManager)) return
                if (isRoleAvailable(roleManager)) {
                    runCatching {
                        context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    }.onFailure { openHomeSettings(context) }
                    return
                }
            }
        }
        openHomeSettings(context)
    }

    /**
     * The role queries are remote calls, so a failure here is treated as
     * "unknown" rather than crashing the launcher.
     */
    private fun isRoleHeld(roleManager: RoleManager): Boolean = runCatching {
        roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    }.getOrDefault(false)

    private fun isRoleAvailable(roleManager: RoleManager): Boolean = runCatching {
        roleManager.isRoleAvailable(RoleManager.ROLE_HOME)
    }.getOrDefault(false)

    private fun openHomeSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
```

`platform/WallpaperProvider.kt`:

```kotlin
package dev.liner.launcher.platform

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

object WallpaperProvider {
    /** Returns the system wallpaper, or null when it cannot be read. Never throws. */
    fun load(context: Context): Drawable? = try {
        val manager = WallpaperManager.getInstance(context)
        // A null result means "no wallpaper set" — the system wallpaper may
        // still be there, so try the system flag before giving up.
        manager.getDrawable() ?: manager.getDrawable(WallpaperManager.FLAG_SYSTEM)
    } catch (_: Throwable) {
        // The wallpaper service can be unavailable; the caller falls back to
        // a plain background rather than the launcher failing to start.
        try {
            WallpaperManager.getInstance(context).getDrawable(WallpaperManager.FLAG_SYSTEM)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The system wallpaper as a bitmap no larger than [maxWidth] x [maxHeight].
     *
     * The raw drawable is often several thousand pixels on a side;
     * rasterising it at full size costs tens of megabytes and can fail with
     * an [OutOfMemoryError] — which is an [Error], not an [Exception], and
     * would otherwise escape every `catch (e: Exception)` guard. The
     * drawable is therefore scaled down to the display size before it is
     * rasterised, and every failure, including an OOM, is swallowed.
     */
    fun loadBitmap(context: Context, maxWidth: Int, maxHeight: Int): Bitmap? {
        val drawable = load(context) ?: return null
        return try {
            val intrinsicWidth = drawable.intrinsicWidth
            val intrinsicHeight = drawable.intrinsicHeight
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return null
            val scale = minOf(
                maxWidth.toFloat() / intrinsicWidth,
                maxHeight.toFloat() / intrinsicHeight,
                1f,
            )
            val width = max(1, (intrinsicWidth * scale).toInt())
            val height = max(1, (intrinsicHeight * scale).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }
}
```

- [ ] **Step 5: Remove the redundant night theme resource**

`app/src/main/res/values-night/themes.xml` is byte-identical to
`app/src/main/res/values/themes.xml`, so the night qualifier is a no-op that
can never change anything. The platform theme is intentionally dark
(`android:Theme.Material.NoActionBar`, `windowLightStatusBar=false`) because
the launcher draws its own background and the home screen is dark by design.

Delete the redundant file:

```bash
rm app/src/main/res/values-night/themes.xml
```

Light and dark handling belongs in `LinerTheme`, which is the only place that
needs to differ between modes.

- [ ] **Step 6: Run the test to verify it passes**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*ThemeMappingTest*'`

Expected: `BUILD SUCCESSFUL`, 5 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/platform app/src/test/kotlin/dev/liner/launcher/platform \
        app/src/main/res/values-night
git commit -m "feat(platform): add app launching, default-home detection, wallpaper and theme"
```

---

## Task 9: Home Screen Rendering

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/home/{HomeScreen,HomeViewModel}.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/common/{LabelRow,SectionHeaderRow}.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/common/BackgroundLayer.kt`
- Create: `app/src/test/kotlin/dev/liner/launcher/ui/common/GradientEndpointsTest.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/platform/LauncherActivity.kt`

**Interfaces:**
- Consumes: Task 4's `AppCatalog`/`StyleResolver`/`ClockFormatter`, Task 6's `LayoutRepository`, Task 8's `LinerTheme`.
- Produces:
  - `class HomeViewModel(repository: LayoutRepository, appsRepository: InstalledAppsRepository) : ViewModel` with `val document: StateFlow<LayoutDocument>`, `val installedApps: StateFlow<List<InstalledApp>>`, `val homeEntries: StateFlow<List<HomeEntry>>`, `val writeFailed: StateFlow<Boolean>`, `val loadFailed: StateFlow<Boolean>`, `val appsLoaded: StateFlow<Boolean>`, `val documentLoaded: StateFlow<Boolean>`, `fun update(transform: (LayoutDocument) -> LayoutDocument)`
  - `@Composable fun HomeScreen(viewModel: HomeViewModel, wallpaper: Bitmap?, iconCache: IconCache, onLaunch: (HomeEntry) -> Unit, onOpenSettings: (String?) -> Unit, onRecoverLayout: (String) -> Unit)`
  - `@Composable fun BackgroundLayer(spec: BackgroundSpec, wallpaper: Bitmap?, systemTextColor: Color, systemBackgroundColor: Color)`
  - `@Composable fun LabelRow(text: String, style: ResolvedStyle, alignment: Alignment.Horizontal, icon: ImageBitmap?, iconSizeDp: Int, enabled: Boolean)`
  - `@Composable fun SectionHeaderRow(title: String, style: ResolvedStyle, uppercase: Boolean, showRule: Boolean)`

- [ ] **Step 1: Write the home view model test**

`app/src/test/kotlin/dev/liner/launcher/model/HomeViewModelStateTest.kt` — keep this in the `model` test source set so it runs headless, using the in-memory store:

```kotlin
package dev.liner.launcher.model

import dev.liner.launcher.store.InMemoryDocumentStore
import dev.liner.launcher.store.LayoutRepository
import org.junit.Assert.assertEquals
import org.junit.Test
class HomeViewModelStateTest {

    @Test
    fun `the catalog reflects the document and the installed apps`() {
        val document = LayoutDocument(
            sections = listOf(
                Section(id = "s1", items = listOf(HomeItem(id = "a", packageName = "com.mail", label = "Inbox"))),
            ),
        )
        val installed = mapOf("com.mail" to InstalledApp("com.mail", "com.mail.Main", "Gmail"))

        val entries = AppCatalog.homeEntries(document, installed)

        assertEquals(1, entries.size)
        assertEquals("Inbox", entries[0].displayLabel)
    }

    @Test
    fun `a stored document round trips through the repository`() {
        val store = InMemoryDocumentStore()
        val repository = LayoutRepository(store)

        repository.load()
        repository.update { it.addSection("s1", "Daily") }
        kotlinx.coroutines.runBlocking { repository.awaitPendingWrite() }

        assertEquals(DocumentCodec.decode(store.read()), repository.document.value)
    }
}
```

- [ ] **Step 2: Run the test**

`app/src/test/kotlin/dev/liner/launcher/ui/common/GradientEndpointsTest.kt` — pins the
angle convention, which is otherwise only visible on a device:

```kotlin
package dev.liner.launcher.ui.common

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientEndpointsTest {

    @Test
    fun `angle zero runs top to bottom`() {
        val (start, end) = gradientEndpoints(angleDegrees = 0, width = 100f, height = 200f)
        assertEquals(start.x, end.x, 0.001f)
        assertTrue("the gradient must start at the top", start.y < end.y)
    }

    @Test
    fun `angle ninety runs left to right`() {
        val (start, end) = gradientEndpoints(angleDegrees = 90, width = 100f, height = 200f)
        assertEquals(start.y, end.y, 0.001f)
        assertTrue("the gradient must start at the left", start.x < end.x)
    }

    @Test
    fun `angle one eighty runs bottom to top`() {
        val (start, end) = gradientEndpoints(angleDegrees = 180, width = 100f, height = 200f)
        assertEquals(start.x, end.x, 0.001f)
        assertTrue("the gradient must start at the bottom", start.y > end.y)
    }

    @Test
    fun `the gradient passes through the centre`() {
        val (start, end) = gradientEndpoints(angleDegrees = 37, width = 100f, height = 200f)
        assertEquals(50f, (start.x + end.x) / 2f, 0.001f)
        assertEquals(100f, (start.y + end.y) / 2f, 0.001f)
    }

    @Test
    fun `the gradient turns clockwise`() {
        // The end point must sit right of and below the start point at an
        // intermediate angle. The cardinal cases above cannot catch a
        // flipped sign, which would mirror every non-cardinal angle.
        val (start, end) = gradientEndpoints(angleDegrees = 45, width = 100f, height = 200f)
        assertTrue("the gradient must turn to the right", end.x > start.x)
        assertTrue("the gradient must turn downwards", end.y > start.y)
    }

    @Test
    fun `the angle is preserved on a wide screen`() {
        // Scaling the two axes independently would skew the angle; the
        // endpoints must keep the requested direction on any aspect ratio.
        val (start, end) = gradientEndpoints(angleDegrees = 45, width = 400f, height = 100f)
        val dx = end.x - start.x
        val dy = end.y - start.y
        assertEquals(1f, dx / dy, 0.01f)
    }
}
```

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*HomeViewModelStateTest*' --tests '*GradientEndpointsTest*'`

Expected: `BUILD SUCCESSFUL`, 2 tests, 0 failures. This test pins the contract that the view model will use.

- [ ] **Step 3: Implement the background layer**

`ui/common/BackgroundLayer.kt`:

```kotlin
package dev.liner.launcher.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.ColorSpec

@Composable
fun BackgroundLayer(
    spec: BackgroundSpec,
    wallpaper: Bitmap?,
    systemTextColor: Color,
    systemBackgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (spec) {
            is BackgroundSpec.Solid -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(spec.color.toColor(fallback = systemBackgroundColor)),
                )
            }

            is BackgroundSpec.Gradient -> {
                val from = spec.from.toColor(fallback = systemBackgroundColor)
                val to = spec.to.toColor(fallback = systemBackgroundColor)
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val (start, end) = gradientEndpoints(spec.angleDegrees, size.width, size.height)
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(from, to),
                                    start = start,
                                    end = end,
                                ),
                            )
                        },
                )
            }

            is BackgroundSpec.Wallpaper -> {
                if (wallpaper != null) {
                    Image(
                        bitmap = wallpaper.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = spec.dim.coerceIn(0f, 0.9f))),
                )
            }
        }
    }
}

/**
 * The endpoints of a linear gradient whose direction is [angleDegrees]
 * measured clockwise from vertical: 0 runs top-to-bottom, 90 runs
 * left-to-right, 180 runs bottom-to-top.
 *
 * The line is scaled isotropically — its length is the CSS "magic corner"
 * value `|w·sinθ| + |h·cosθ|` — so the on-screen direction is exactly the
 * requested angle on any aspect ratio. Scaling the two axes independently
 * would skew every non-cardinal angle.
 *
 * The endpoints are in pixels, because that is what
 * [Brush.linearGradient] expects.
 */
internal fun gradientEndpoints(
    angleDegrees: Int,
    width: Float,
    height: Float,
): Pair<Offset, Offset> {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val dx = sin(radians).toFloat()
    val dy = cos(radians).toFloat()
    val length = abs(width * dx) + abs(height * dy)
    val halfX = dx * length / 2f
    val halfY = dy * length / 2f
    val cx = width / 2f
    val cy = height / 2f
    return Offset(cx - halfX, cy - halfY) to Offset(cx + halfX, cy + halfY)
}

/**
 * Maps a [ColorSpec] to a concrete colour, resolving "follow system" to
 * [fallback]. A background must fall back to the *background* colour, not
 * the text colour — falling back to the text colour would paint a
 * near-white label colour as the background and make the text unreadable.
 */
fun ColorSpec.toColor(fallback: Color): Color = when (this) {
    is ColorSpec.Solid -> Color(argb)
    ColorSpec.FollowSystem -> fallback
}

```

- [ ] **Step 4: Implement the shared rows**

`ui/common/LabelRow.kt`:

```kotlin
package dev.liner.launcher.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liner.launcher.model.ResolvedStyle
import dev.liner.launcher.platform.fontFamilyFor
import dev.liner.launcher.platform.weightFor

@Composable
fun LabelRow(
    text: String,
    style: ResolvedStyle,
    alignment: Alignment.Horizontal,
    icon: ImageBitmap?,
    iconSizeDp: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = style.colorArgb?.let { Color(it) } ?: Color.Unspecified

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, alignment),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSizeDp.dp),
            )
        }
        androidx.compose.material3.Text(
            text = text,
            style = TextStyle(
                fontFamily = fontFamilyFor(style.fontKey),
                fontWeight = weightFor(style.weight),
                fontSize = style.sizeSp.sp,
                letterSpacing = style.letterSpacingEm.sp,
                color = if (enabled) color else color.copy(alpha = 0.4f),
            ),
        )
    }
}
```

`ui/common/SectionHeaderRow.kt`:

```kotlin
package dev.liner.launcher.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liner.launcher.model.ResolvedStyle
import dev.liner.launcher.platform.fontFamilyFor
import dev.liner.launcher.platform.weightFor

@Composable
fun SectionHeaderRow(
    title: String,
    style: ResolvedStyle,
    uppercase: Boolean,
    showRule: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = style.colorArgb?.let { Color(it) } ?: Color.Unspecified

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (uppercase) title.uppercase() else title,
            style = TextStyle(
                fontFamily = fontFamilyFor(style.fontKey),
                fontWeight = weightFor(style.weight),
                fontSize = style.sizeSp.sp,
                letterSpacing = style.letterSpacingEm.sp,
                color = color,
            ),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (showRule) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(color.copy(alpha = 0.25f)),
            ) {}
        }
    }
}
```

- [ ] **Step 5: Implement the view model**

`ui/home/HomeViewModel.kt`:

```kotlin
package dev.liner.launcher.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.HomeEntry
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.store.LayoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: LayoutRepository,
    private val appsRepository: InstalledAppsRepository,
) : ViewModel() {

    val document: StateFlow<LayoutDocument> = repository.document

    val writeFailed: StateFlow<Boolean> = repository.writeFailed

    val loadFailed: StateFlow<Boolean> = repository.loadFailed

    /** The unreadable payload from a failed load, kept so it can be logged. */
    val recoveredPayload: String?
        get() = repository.recoveredPayload

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    val homeEntries: StateFlow<List<HomeEntry>> =
        combine(document, _installedApps) { doc, apps ->
            AppCatalog.homeEntries(doc, apps.associateBy { it.packageName })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether the installed-app list has finished its first query. Until it
     * has, an empty list means "not loaded yet", not "no apps" — the UI must
     * not flash the empty state or grey out every row during that window.
     */
    private val _appsLoaded = MutableStateFlow(false)
    val appsLoaded: StateFlow<Boolean> = _appsLoaded.asStateFlow()

    /**
     * Whether the saved document has finished its first load.
     *
     * The signal is the repository's: the store is read once per process, so
     * this screen's own [LayoutRepository.load] call can return before the
     * document is in memory — when the settings screen claimed the read
     * first.
     */
    val documentLoaded: StateFlow<Boolean> = repository.documentLoaded

    init {
        viewModelScope.launch { repository.load() }
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // The prune may not run before the stored document is in
                // memory: updating before then would persist the DEFAULT
                // document over the user's stored layout. The signal comes
                // from the repository, because a second caller's load()
                // returns immediately without the document being loaded.
                repository.documentLoaded.first { it }

                val load = appsRepository.loadInstalledApps()
                _installedApps.value = load.apps
                // Auto-pruning removes home entries whose app is gone — but
                // only from a query that really answered. A failed or
                // filtered query must never be read as "every app was
                // uninstalled", or the whole home screen would be deleted.
                // A failed READ is not evidence either: the in-memory
                // document is then the default one, and writing it back
                // would destroy the layout still on disk.
                if (!load.wasFallback && !repository.loadFailed.value) {
                    val installed = load.apps.mapTo(HashSet()) { it.packageName }
                    repository.update { doc -> doc.pruneUninstalledApps(installed) }
                }
            } finally {
                // Even a failed query means "we have looked" — without this
                // the UI would wait forever before showing its states.
                _appsLoaded.value = true
            }
        }
    }

    fun update(transform: (LayoutDocument) -> LayoutDocument) = repository.update(transform)

    companion object {
        fun factory(repository: LayoutRepository, appsRepository: InstalledAppsRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository, appsRepository) as T
            }
    }
}
```

- [ ] **Step 6: Implement the home screen**

`ui/home/HomeScreen.kt`:

```kotlin
package dev.liner.launcher.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.model.ClockFormatter
import dev.liner.launcher.model.HomeEntry
import dev.liner.launcher.model.StyleResolver
import dev.liner.launcher.model.ThemeTokens
import dev.liner.launcher.ui.common.BackgroundLayer
import dev.liner.launcher.ui.common.LabelRow
import dev.liner.launcher.ui.common.SectionHeaderRow
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    wallpaper: Bitmap?,
    iconCache: IconCache,
    onLaunch: (HomeEntry) -> Unit,
    onOpenSettings: (String?) -> Unit,
    onRecoverLayout: (String) -> Unit,
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val entries by viewModel.homeEntries.collectAsStateWithLifecycle()
    val loadFailed by viewModel.loadFailed.collectAsStateWithLifecycle()
    val writeFailed by viewModel.writeFailed.collectAsStateWithLifecycle()
    val appsLoaded by viewModel.appsLoaded.collectAsStateWithLifecycle()
    val documentLoaded by viewModel.documentLoaded.collectAsStateWithLifecycle()

    // Until the app query has finished, an empty list means "not loaded yet"
    // rather than "no apps" — showing the empty state then would flash it on
    // every cold start.
    val stillLoading = !appsLoaded || !documentLoaded

    // A failed save must not be silent — the spec requires a notice.
    val writeContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(writeFailed) {
        if (writeFailed) {
            android.widget.Toast.makeText(
                writeContext,
                "Your changes could not be saved. Try again.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    // The stored layout could not be read. Tell the user once, rather than
    // silently showing them a default screen they never configured, and hand
    // the original payload to the platform layer so it can be written to a
    // log file before anything overwrites it.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(loadFailed) {
        if (loadFailed) {
            android.widget.Toast.makeText(
                context,
                "Your saved layout could not be read, so Liner started with defaults.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            viewModel.recoveredPayload?.let(onRecoverLayout)
        }
    }
    val theme = document.theme
    val systemTextColor = MaterialTheme.colorScheme.onBackground
    val systemBackgroundColor = MaterialTheme.colorScheme.background
    // The clock takes the theme's text colour, resolved through the same
    // path as every other style decision.
    val chromeColor = StyleResolver.resolveTextColor(theme)?.let { Color(it) } ?: systemTextColor

    val horizontalAlignment = when (theme.alignment) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }

    Box(Modifier.fillMaxSize()) {
        BackgroundLayer(
            spec = theme.background,
            wallpaper = wallpaper,
            systemTextColor = systemTextColor,
            systemBackgroundColor = systemBackgroundColor,
        )

        if (entries.isEmpty() && !stillLoading) {
            // Nothing chosen yet: point the user at the Apps tab rather than
            // showing a blank screen.
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No apps on your home screen yet", fontSize = 16.sp)
                TextButton(onClick = { onOpenSettings("apps") }) { Text("Choose apps") }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = theme.screenPaddingHdp.dp,
                    vertical = theme.screenPaddingVdp.dp,
                ),
        ) {
            ClockDateHeader(theme, chromeColor)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(theme.itemSpacingDp.dp),
            ) {
                document.sections.forEach { section ->
                    val sectionEntries = entries.filter { it.sectionId == section.id }
                    // An empty section keeps no header — the spec keeps it as an
                    // untitled group, and a blank title is no title at all.
                    val title = section.title?.takeIf { it.isNotBlank() }
                    if (title != null && sectionEntries.isNotEmpty()) {
                        item(key = "header-${section.id}") {
                            SectionHeaderRow(
                                title = title,
                                style = StyleResolver.resolveSectionHeaderStyle(theme),
                                uppercase = StyleResolver.resolveSectionTitleUppercase(theme),
                                showRule = StyleResolver.resolveSectionTitleRule(theme),
                            )
                        }
                    }
                    items(
                        items = sectionEntries,
                        key = { "${section.id}:${it.item.id}" },
                    ) { entry ->
                        // Icons are opt-in: the home screen is icon-free by
                        // default. When they are on, they are rasterised off
                        // the main thread for the same reason as the drawer.
                        val icon by produceState<ImageBitmap?>(
                            initialValue = null,
                            key1 = entry.item.packageName,
                            key2 = theme.showIcons,
                        ) {
                            value = if (theme.showIcons) {
                                withContext(Dispatchers.IO) {
                                    iconCache.icon(entry.item.packageName)
                                }
                            } else {
                                null
                            }
                        }
                        LabelRow(
                            text = entry.displayLabel,
                            style = StyleResolver.resolveItemStyle(theme, entry.item.style),
                            alignment = horizontalAlignment,
                            icon = icon,
                            iconSizeDp = StyleResolver.resolveIconSizeDp(theme),
                            // Until the app query has answered, "not available"
                            // only means "not looked up yet" — greying the row
                            // would report an installed app as missing.
                            enabled = entry.available || !appsLoaded,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLaunch(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockDateHeader(theme: ThemeTokens, textColor: Color) {
    if (theme.clockStyle == "hidden") return

    val context = androidx.compose.ui.platform.LocalContext.current
    val now = remember { System.currentTimeMillis() }
    val zone = remember { ZoneId.systemDefault() }
    val use24Hour = when (theme.clockFormat) {
        "12h" -> false
        "24h" -> true
        else -> android.text.format.DateFormat.is24HourFormat(context)
    }

    val clockSize = if (theme.clockStyle == "large") 64f else 24f
    val dateSize = if (theme.clockStyle == "large") 18f else 14f

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = ClockFormatter.formatTime(now, zone, use24Hour),
            style = TextStyle(
                fontSize = clockSize.sp,
                fontWeight = FontWeight.Light,
                color = textColor,
            ),
        )
        val date = ClockFormatter.formatDate(now, zone, theme.dateStyle)
        if (date != null) {
            Text(
                text = date,
                style = TextStyle(fontSize = dateSize.sp, color = textColor),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
```

- [ ] **Step 7: Wire the activity**

Replace `platform/LauncherActivity.kt` with:

```kotlin
package dev.liner.launcher.platform

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.liner.launcher.ui.home.HomeScreen
import dev.liner.launcher.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherActivity : ComponentActivity() {

    private val container by lazy { (application as LinerApp).container }

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(container.repository, container.appsRepository)
    }

    /**
     * The wallpaper, published once it has been rasterised off the main
     * thread. It is null until then, and the screen falls back to a plain
     * background in the meantime.
     */
    private var wallpaperBitmap by mutableStateOf<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The wallpaper is rasterised off the main thread: a full-size
        // wallpaper is tens of megabytes, and the allocation must not happen
        // before the first frame is drawn.
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = WallpaperProvider.loadBitmap(
                context = applicationContext,
                maxWidth = WALLPAPER_MAX_WIDTH,
                maxHeight = WALLPAPER_MAX_HEIGHT,
            )
            withContext(Dispatchers.Main) {
                wallpaperBitmap = bitmap
            }
        }

        setContent {
            LinerTheme {
                HomeScreen(
                    viewModel = viewModel,
                    wallpaper = wallpaperBitmap,
                    iconCache = container.iconCache,
                    onLaunch = { entry -> launchEntry(entry.item.packageName) },
                    onOpenSettings = { tab -> openSettings(tab) },
                    onRecoverLayout = { payload -> writeRecoveryLog(payload) },
                )
            }
        }
    }

    /**
     * Keeps the unreadable layout on disk before the first edit overwrites it,
     * so a user's work is recoverable by hand. A logging failure must never
     * crash the launcher.
     */
    private fun writeRecoveryLog(payload: String) {
        runCatching {
            java.io.File(filesDir, "liner-recovered-layout.json").writeText(payload)
        }
    }

    private fun launchEntry(packageName: String) {
        val app = viewModel.installedApps.value.firstOrNull { it.packageName == packageName }
        if (app == null) {
            // The app list has not been queried yet, so "not installed" is
            // not something we know yet — saying so would report an app that
            // *is* installed as missing.
            if (!viewModel.appsLoaded.value) return
            // The app is gone. The row is greyed out, and tapping it must not
            // launch anything — it only says so.
            Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
            return
        }
        AppLauncher(this).launch(app)
    }

    private fun openSettings(tab: String?) {
        startActivity(SettingsActivity.intent(this, tab))
    }

    companion object {
        // The wallpaper is only ever drawn as a background, so a screen-sized
        // bitmap is enough — and it is a fraction of the memory a full-size
        // wallpaper would cost.
        private const val WALLPAPER_MAX_WIDTH = 1080
        private const val WALLPAPER_MAX_HEIGHT = 1920
    }
}
```

`platform/SettingsActivity.kt` — add the `intent` helper now (the screen itself is built in Task 12):

```kotlin
package dev.liner.launcher.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("Settings") }
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"

        fun intent(context: Context, tab: String? = null): Intent =
            Intent(context, SettingsActivity::class.java).apply {
                if (tab != null) putExtra(EXTRA_TAB, tab)
            }
    }
}
```

- [ ] **Step 8: Build and verify**

Run:

```bash
cd /workspace
export ANDROID_HOME=/workspace/.toolchain/android-sdk GRADLE_USER_HOME=/workspace/.gradle-home
"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. If the compiler reports unused-import or unresolved-reference errors in `HomeScreen.kt`, fix them directly (commonly `MaterialTheme` needs `androidx.compose.material3.MaterialTheme` imported).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/ui app/src/main/kotlin/dev/liner/launcher/platform \
        app/src/test/kotlin/dev/liner/launcher/model
git commit -m "feat(ui): render the home screen with clock, sections and labels"
```

---

## Task 10: App Drawer

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/drawer/{DrawerSheet,DrawerViewModel}.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/home/HomeScreen.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/platform/LauncherActivity.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/AppFilterTest.kt` (extended with a case for the drawer ordering)

**Interfaces:**
- Consumes: Task 4's `AppFilter`/`AppCatalog`, Task 9's `HomeScreen`.
- Produces:
  - `class DrawerViewModel(document: StateFlow<LayoutDocument>, installedApps: StateFlow<List<InstalledApp>>)` with `val query: StateFlow<String>`, `val visibleApps: StateFlow<List<InstalledApp>>`, `fun setQuery(value: String)`
  - `@Composable fun DrawerSheet(visibleApps: List<InstalledApp>, query: String, onQueryChange: (String) -> Unit, onLaunch: (InstalledApp) -> Unit, onOpenSettings: () -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: Add the drawer ordering test**

Append to `app/src/test/kotlin/dev/liner/launcher/model/AppCatalogTest.kt`:

```kotlin
    @Test
    fun `drawer apps are sorted case insensitively by label`() {
        val apps = listOf(
            InstalledApp("com.b", "com.b.Main", "beta"),
            InstalledApp("com.a", "com.a.Main", "Alpha"),
        )

        val result = AppCatalog.drawerApps(LayoutDocument(), apps)

        assertEquals(listOf("com.a", "com.b"), result.map { it.packageName })
    }
```

- [ ] **Step 2: Run the test**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*AppCatalogTest*'`

Expected: `BUILD SUCCESSFUL`, 7 tests, 0 failures.

- [ ] **Step 3: Implement the drawer view model**

`ui/drawer/DrawerViewModel.kt`:

```kotlin
package dev.liner.launcher.ui.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.AppFilter
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class DrawerViewModel(
    document: StateFlow<LayoutDocument>,
    installedApps: StateFlow<List<InstalledApp>>,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    val query: StateFlow<String> = _query

    val visibleApps: StateFlow<List<InstalledApp>> =
        combine(document, installedApps, _query) { doc, apps, q ->
            val visible = AppCatalog.drawerApps(doc, apps)
            AppFilter.filter(visible, q, AppCatalog.customLabels(doc))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }
}
```

- [ ] **Step 4: Implement the drawer sheet**

`ui/drawer/DrawerSheet.kt`:

```kotlin
package dev.liner.launcher.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.ui.common.BackgroundLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DrawerSheet(
    visibleApps: List<InstalledApp>,
    query: String,
    style: String,
    iconCache: IconCache,
    background: BackgroundSpec,
    wallpaper: Bitmap?,
    appsLoaded: Boolean,
    textColor: Color,
    systemTextColor: Color,
    systemBackgroundColor: Color,
    onQueryChange: (String) -> Unit,
    onLaunch: (InstalledApp) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The sheet covers the whole screen, so there is no "outside" to tap —
    // Back is what closes it.
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        // The drawer shares the home screen's background so a themed launcher
        // stays coherent, with a scrim over it so the list stays legible on a
        // wallpaper or a bright gradient.
        BackgroundLayer(
            spec = background,
            wallpaper = wallpaper,
            systemTextColor = systemTextColor,
            systemBackgroundColor = systemBackgroundColor,
        )
        // The scrim leans towards the theme's background colour, not always
        // towards black: a black scrim over a wallpaper would leave near-black
        // text unreadable in the light theme.
        Box(
            Modifier
                .fillMaxSize()
                .background(systemBackgroundColor.copy(alpha = 0.45f)),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .safeDrawingPadding(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text("Filter apps") },
                modifier = Modifier.fillMaxWidth(),
            )

            // The list takes the space the filter field and the settings row
            // leave; without the weight it would fill the column and push
            // the settings row out of view.
            Box(Modifier.weight(1f)) {
            if (!appsLoaded) {
                // The app query has not finished yet — an empty list here
                // means "not loaded", not "no apps match".
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {}
            } else if (visibleApps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No apps match", fontSize = 16.sp, color = textColor)
                }
            } else if (style == "text") {
                LazyColumn {
                    items(visibleApps, key = { it.packageName + it.activityClass }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLaunch(app) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(app.systemLabel, fontSize = 18.sp, color = textColor)
                        }
                    }
                }
            } else {
                // Four columns, adapting down to three on a narrow screen.
                BoxWithConstraints {
                val columns = if (maxWidth < 360.dp) 3 else 4
                LazyVerticalGrid(columns = GridCells.Fixed(columns)) {
                    items(visibleApps, key = { it.packageName + it.activityClass }) { app ->
                        Column(
                            Modifier
                                .padding(6.dp)
                                .clickable { onLaunch(app) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Rasterising an icon touches the package manager
                            // and allocates a bitmap, so it must not happen on
                            // the main thread — the drawer can hold a hundred
                            // tiles. The load is keyed by package name, so a
                            // scroll cancels it.
                            val icon by produceState<ImageBitmap?>(
                                initialValue = null,
                                key1 = app.packageName,
                            ) {
                                value = withContext(Dispatchers.IO) {
                                    iconCache.icon(app.packageName)
                                }
                            }
                            // A `by` delegate cannot be smart-cast, so the
                            // null case is handled with `let`.
                            icon?.let { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                            Text(
                                text = app.systemLabel,
                                fontSize = 12.sp,
                                color = textColor,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                }
            }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        androidx.compose.material.icons.filled.Settings,
                        contentDescription = "Settings",
                    )
                }
            }
        }
    }
}
```

> `DrawerSheet` needs an `IconCache`. Add the `iconCache: IconCache` parameter to
> the composable signature and pass it in from `HomeScreen`. The icon lookup
> above already does the caching — no separate `iconFor` helper is needed.

- [ ] **Step 5: Wire the drawer into the home screen**

In `HomeScreen.kt`, add drawer state and a bottom gesture zone:

```kotlin
    var drawerOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val appsLoaded by viewModel.appsLoaded.collectAsStateWithLifecycle()
    // The view model belongs to the store, so it survives a configuration
    // change and is cleared with the screen — `remember` would leak its
    // scope.
    val drawerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = DrawerViewModel.factory(viewModel.document, viewModel.installedApps),
    )
    val visibleApps by drawerViewModel.visibleApps.collectAsStateWithLifecycle()
    val query by drawerViewModel.query.collectAsStateWithLifecycle()
```

Add at the end of the root `Box`:

```kotlin
        if (drawerOpen) {
            DrawerSheet(
                visibleApps = visibleApps,
                query = query,
                style = theme.drawerStyle,
                iconCache = iconCache,
                background = theme.background,
                wallpaper = wallpaper,
                appsLoaded = appsLoaded,
                textColor = chromeColor,
                systemTextColor = systemTextColor,
                systemBackgroundColor = systemBackgroundColor,
                onQueryChange = drawerViewModel::setQuery,
                onLaunch = { app -> drawerOpen = false; onLaunchApp(app) },
                onOpenSettings = { drawerOpen = false; onOpenSettings("appearance") },
                onDismiss = { drawerOpen = false },
            )
        }
```

Add a gesture zone at the bottom of the screen:

```kotlin
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {},
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount < -12f) drawerOpen = true
                        },
                    )
                },
        )
```

Import `androidx.compose.foundation.gestures.detectVerticalDragGestures` and
`androidx.compose.ui.input.pointer.pointerInput`.

`HomeScreen` already takes `iconCache: IconCache` (added in Task 9, where the
home rows resolve their own icons when `theme.showIcons` is on). Task 10 only
adds `onLaunchApp: (InstalledApp) -> Unit` for the drawer; `onLaunch:
(HomeEntry) -> Unit` stays for the home rows.

- [ ] **Step 6: Update the activity**

In `LauncherActivity.kt`, pass `iconCache = container.iconCache` and implement
`onLaunchApp`:

```kotlin
    private fun launchApp(app: InstalledApp) {
        AppLauncher(this).launch(app)
    }
```

- [ ] **Step 7: Keep the remembered items across an activity recreation**

The Apps tab remembers the `HomeItem` of every app the user has unchecked, so
that re-checking it restores the label, the style and the section. That map
lives in composable state (`remember`), and `SettingsActivity` declares no
`android:configChanges`, so a rotation recreates the activity and the map is
emptied — the next re-check loses the customisation, which is the defect the
review found in a narrow window.

Hold the map in `SettingsViewModel`, which survives the configuration change,
and pass it to the tab:

- `SettingsViewModel` gains `val removedItems: Map<String, RemovedHomeItem>`
  (a `mutableStateMapOf` or a `MutableStateFlow<Map<...>>`), with a
  `fun rememberRemoved(packageName: String, item: HomeItem, sectionId: String,
  index: Int)` that records it and a `fun forgetRemoved(packageName: String)`
  that drops it;
- `SettingsScreen` passes it down to `AppsTab`;
- `AppsTab` reads and writes the view model's map instead of its own.

`RemovedHomeItem` is a small data class holding the item, its section id and
its index.

- [ ] **Step 8: Build and verify**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebug :app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/ui app/src/main/kotlin/dev/liner/launcher/platform \
        app/src/test/kotlin/dev/liner/launcher/model
git commit -m "feat(ui): add the app drawer with filter, grid and text modes"
```

---

## Task 11: Edit Mode — Reorder, Rename, Remove, Section Editing

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/home/EditModeController.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/ui/home/EditModeControllerTest.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/ui/common/DragIndicatorTest.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/common/EditSheets.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/common/DragIndicator.kt` — the shared insertion-indicator geometry, used by the home screen's drag and by the settings Apps tab's reorder list
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/home/HomeScreen.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/DragTargetingTest.kt` (already covers the maths)

**Interfaces:**
- Consumes: Task 5's `DragTargeting`/`DragTarget`, Task 3's edit operations.
- Produces:
  - `class EditModeController` with `var active: Boolean`, `var draggingId: String?`, `var dragTarget: DragTarget?`, `val bounds: MutableMap<String, ItemBounds>`, `fun startDrag(id: String)`, `fun updateDrag(dragY: Float, sectionOrder: List<String>)`, `fun endDrag(): DragTarget?`, `fun clear()`
  - `@Composable fun ItemEditorSheet(entry: HomeEntry, onRename: (String?) -> Unit, onMoveToSection: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit)`
  - `@Composable fun SectionEditorSheet(title: String?, itemCount: Int, onRename: (String?) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: Implement the edit mode controller**

`ui/home/EditModeController.kt`:

```kotlin
package dev.liner.launcher.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.DragTargeting
import dev.liner.launcher.model.ItemBounds

/**
 * Holds the state of an in-progress drag. The list is not reordered live —
 * the controller only computes where the item would land, and the caller
 * commits that on release.
 */
class EditModeController {

    var active by mutableStateOf(false)
        private set

    var draggingId by mutableStateOf<String?>(null)
        private set

    var dragTarget by mutableStateOf<DragTarget?>(null)
        private set

    val bounds = mutableStateMapOf<String, ItemBounds>()

    fun startDrag(id: String) {
        active = true
        draggingId = id
        dragTarget = null
    }

    fun updateDrag(dragY: Float, sectionOrder: List<String>) {
        val id = draggingId ?: return
        dragTarget = DragTargeting.computeTarget(dragY, bounds.values.toList(), id, sectionOrder)
    }

    /** Returns the computed target and clears the drag state. */
    fun endDrag(): DragTarget? {
        val target = dragTarget
        draggingId = null
        dragTarget = null
        return target
    }

    /**
     * Drops the computed insertion target. Used while the drag is over the
     * trash zone, where the item is removed rather than moved, so there is no
     * slot to show.
     */
    fun clearTarget() {
        dragTarget = null
    }

    /**
     * Leaves edit mode but keeps the recorded bounds: they are filled in by
     * the layout pass that placed the rows, and that pass does not run again
     * just because edit mode ended.
     */
    fun exitEditMode() {
        active = false
        draggingId = null
        dragTarget = null
    }

    fun clear() {
        active = false
        draggingId = null
        dragTarget = null
        bounds.clear()
    }
}
```

- [ ] **Step 2: Implement the editor sheets**

`ui/common/EditSheets.kt`:

```kotlin
package dev.liner.launcher.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.HomeEntry

@Composable
fun ItemEditorSheet(
    entry: HomeEntry,
    sections: List<Pair<String, String?>>,
    onRename: (String?) -> Unit,
    onMoveToSection: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The field is seeded with the app's own name when the item has no
    // custom label, so the seeded value has to be remembered to tell a
    // deliberate rename from an untouched field.
    val seededLabel = entry.item.label ?: entry.displayLabel
    var label by remember(entry.item.id) { mutableStateOf(seededLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.displayLabel) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // The item's own section is not offered: moving an item
                // to the section it is already in would silently push it to
                // the end of that section.
                val targets = sections.filter { it.first != entry.sectionId }
                if (targets.isNotEmpty()) {
                    Text(
                        text = "Move to section",
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    targets.forEach { (id, title) ->
                        TextButton(onClick = { onMoveToSection(id); onDismiss() }) {
                            Text(title ?: "Untitled")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = label.trim()
                    when {
                        // An emptied field resets the item to the app's name.
                        trimmed.isEmpty() -> onRename(null)
                        // An untouched field must not pin the app's name as an
                        // explicit custom label — the row would stop following
                        // a later system rename of the app.
                        trimmed != seededLabel -> onRename(trimmed)
                        else -> onRename(entry.item.label)
                    }
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onRemove(); onDismiss() }) { Text("Remove") }
        },
    )
}

@Composable
fun SectionEditorSheet(
    title: String?,
    itemCount: Int,
    onRename: (String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(title ?: "") }
    // Deleting a section deletes every item in it, and there is no undo for
    // it, so the action asks first.
    var confirmingDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Section") },
        text = {
            if (confirmingDelete) {
                Text(
                    "Delete this section and its $itemCount " +
                        if (itemCount == 1) "item?" else "items?",
                )
            } else {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            if (confirmingDelete) {
                TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete") }
            } else {
                TextButton(onClick = { onRename(value); onDismiss() }) { Text("Save") }
            }
        },
        dismissButton = {
            if (confirmingDelete) {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            } else {
                TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
            }
        },
    )
}
```

- [ ] **Step 3: Wire edit mode into the home screen**

In `HomeScreen.kt`:

1. Create `val editController = remember { EditModeController() }`.
2. Track the dragging item's absolute Y with a `var dragY by remember { mutableFloatStateOf(0f) }`.
   The X is not tracked: the trash zone is hit-tested with the whole pointer
   offset, so a separate X would be dead state.
3. Each `LabelRow` in the list gets a `Modifier.onGloballyPositioned` that records its bounds:

```kotlin
.onGloballyPositioned { coordinates ->
    val position = coordinates.positionInRoot()
    val size = coordinates.size
    editController.bounds[entry.item.id] = ItemBounds(
        id = entry.item.id,
        sectionId = entry.sectionId,
        top = position.y,
        bottom = position.y + size.height,
    )
}
```

4. Add the long-press drag gesture to each row:

```kotlin
.pointerInput(entry.item.id) {
    // The long press is detected here rather than by a combinedClickable: the
    // long press of a clickable consumes the rest of the pointer stream,
    // which would cancel the drag it is meant to start.
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            editController.startDrag(entry.item.id)
            dragY = rowOrigin.y + offset.y
            overTrash = false
        },
        onDrag = { change, _ ->
            change.consume()
            val finger = rowOrigin + change.position
            dragY = finger.y
            overTrash = trashBounds?.contains(finger) == true
            if (overTrash) {
                // The item is dropped, not moved, so there is no slot to show.
                editController.clearTarget()
            } else {
                editController.updateDrag(dragY, sectionOrder)
            }
        },
        onDragEnd = { commitDrop(entry.item.id) },
        onDragCancel = { editController.endDrag() },
    )
}
```

5. The row's click opens the editor sheet while edit mode is active, and
launches the app otherwise:

```kotlin
.clickable {
    if (editController.active) {
        editingEntry = entry
    } else {
        onLaunch(entry)
    }
}
```

The long press itself is detected by `detectDragGesturesAfterLongPress` in the
pointer input above — **not** by a `combinedClickable`. Compose's long press
path calls `consumeUntilUp()`, which eats the rest of the pointer stream and
cancels the very drag the gesture exists to start. A long press that never
moves ends the gesture without a drag, and the row is opened for editing
there.

6. A long press on a section header opens the section editor sheet:

```kotlin
.combinedClickable(
    onClick = { },
    onLongClick = { editingSection = section },
)
```

7. Render the trash zone. It is only visible in edit mode, sits above the
   bottom gesture area, and removes the dragged item when the finger is over it:

```kotlin
if (editController.active) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(16.dp)
            .height(72.dp)
            .background(Color(0xFFB3261E), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("Drop here to remove", color = Color.White)
    }
}
```

Detect the drop on the trash zone inside the drag callback, by comparing the
drag position against the zone's bounds recorded with `onGloballyPositioned`:

```kotlin
var trashBounds by remember { mutableStateOf<Rect?>(null) }

Box(
    Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(16.dp)
        .height(72.dp)
        .onGloballyPositioned { trashBounds = it.boundsInRoot() },
    contentAlignment = Alignment.Center,
) { /* the trash label */ }
```

Then in `onDrag`:

```kotlin
val overTrash = trashBounds?.contains(Offset(dragX, dragY)) == true
// (the finger's full position — the X is not tracked separately)
if (overTrash) {
    editController.clearTarget()
} else {
    editController.updateDrag(dragY, document.sections.map { it.id })
}
```

and in `onDragEnd`:

```kotlin
if (overTrash) {
    viewModel.update { doc -> doc.removeItem(entry.item.id) }
    editController.endDrag()
} else {
    when (val target = editController.endDrag()) {
        is DragTarget.InSection -> viewModel.update { doc ->
            doc.moveItem(entry.item.id, target.sectionId, target.index)
        }
        is DragTarget.PreviousSection -> viewModel.update { doc ->
            doc.moveItem(entry.item.id, target.sectionId, doc.sectionById(target.sectionId)?.items?.size ?: 0)
        }
        null -> Unit
    }
}
```

The trash zone is hit-tested with the finger's full position, so the X does
not need to be tracked on its own.

8. Render the insertion indicator. A 2dp line that sits at the slot the item
   would land in:

```kotlin
val dragTarget = editController.dragTarget
if (dragTarget != null) {
    val indicatorTop = dragIndicatorTop(
        target = dragTarget,
        bounds = editController.bounds.values.toList(),
        draggedId = editController.draggingId,
    )
    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(0, indicatorTop.toInt()) }
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(2.dp)
            // The theme's own text colour, so the line is visible on a light
            // background as well as a dark one.
            .background(chromeColor),
    )
}
```

The indicator is drawn inside the `Box` that wraps the list, positioned with
`offset` so it floats over the rows.

The arithmetic lives in a pure function so it can be tested without a device:

9. Removing an item is **undoable**. The spec asks for a snackbar with an
   Undo action, and a drop on the trash zone is easy to do by accident. Keep
   the removed item and the slot it came from, and put it back if the user
   taps Undo:

```kotlin
val snackbarHostState = remember { SnackbarHostState() }
val scope = rememberCoroutineScope()

fun removeItemWithUndo(itemId: String) {
    val doc = viewModel.document.value
    val section = doc.sections.firstOrNull { s -> s.items.any { it.id == itemId } }
        ?: return
    val index = section.items.indexOfFirst { it.id == itemId }
    val item = section.items[index]
    // The snackbar names the app the way the home screen does: the user's
    // rename, else the app's own label, and only the package name when
    // nothing else is known.
    val label = viewModel.homeEntries.value.firstOrNull { it.item.id == itemId }?.displayLabel
        ?: item.label
        ?: item.packageName
    viewModel.update { it.removeItem(itemId) }
    scope.launch {
        val result = snackbarHostState.showSnackbar(
            message = "Removed $label",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.update { it.addItem(item, section.id, index) }
        }
    }
}
```

Both removal paths use it — the trash-zone drop and the editor's Remove
button:

```kotlin
fun commitDrop(itemId: String) {
    if (overTrash) {
        removeItemWithUndo(itemId)
        editController.endDrag()
        return
    }
    when (val target = editController.endDrag()) {
        is DragTarget.InSection -> viewModel.update { doc ->
            doc.moveItem(itemId, target.sectionId, target.index)
        }
        is DragTarget.PreviousSection -> viewModel.update { doc ->
            doc.moveItem(
                itemId,
                target.sectionId,
                doc.sectionById(target.sectionId)?.items?.size ?: 0,
            )
        }
        null -> Unit
    }
}
```

And host the snackbar in the root `Box`, above the trash zone:

```kotlin
SnackbarHost(
    hostState = snackbarHostState,
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 104.dp),
)
```

10. Wire the editor sheets. They sit on top of everything else:

```kotlin
editingEntry?.let { entry ->
    ItemEditorSheet(
        entry = entry,
        sections = document.sections.map { it.id to it.title },
        onRename = { label ->
            viewModel.update { doc -> doc.renameItem(entry.item.id, label) }
        },
        onMoveToSection = { sectionId ->
            viewModel.update { doc ->
                doc.moveItem(
                    entry.item.id,
                    sectionId,
                    doc.sectionById(sectionId)?.items?.size ?: 0,
                )
            }
        },
        onRemove = { removeItemWithUndo(entry.item.id) },
        onDismiss = { editingEntry = null },
    )
}

editingSection?.let { section ->
    SectionEditorSheet(
        title = section.title,
        itemCount = section.items.size,
        onRename = { title ->
            viewModel.update { doc -> doc.renameSection(section.id, title) }
        },
        onDelete = { viewModel.update { doc -> doc.removeSection(section.id) } },
        onDismiss = { editingSection = null },
    )
}
```

`ui/common/DragIndicator.kt` — the shared insertion-indicator geometry. The home
screen's drag and the settings Apps tab's reorder list both use it, so it lives
in `ui/common` rather than in `ui/home`:

```kotlin
package dev.liner.launcher.ui.common

import dev.liner.launcher.model.DragTarget
import dev.liner.launcher.model.ItemBounds

/**
 * The Y at which the insertion indicator is drawn for [target].
 *
 * The index of a [DragTarget.InSection] counts the dragged item as already
 * removed, so the dragged row must be left out of the sibling list — counting
 * it would put the indicator a row too low. A [DragTarget.PreviousSection]
 * drops the item at the *end* of that section, so its indicator belongs under
 * the section's last row rather than above its first.
 */
internal fun dragIndicatorTop(
    target: DragTarget,
    bounds: List<ItemBounds>,
    draggedId: String?,
): Float = when (target) {
    is DragTarget.InSection -> {
        val siblings = bounds
            .filter { it.sectionId == target.sectionId && it.id != draggedId }
            .sortedBy { it.top }
        if (target.index < siblings.size) {
            siblings[target.index].top
        } else {
            // The item lands at the end of the section: under its last row,
            // or — when it is the section's only row — where it already is.
            siblings.lastOrNull()?.bottom
                ?: bounds.firstOrNull { it.id == draggedId }?.top
                ?: 0f
        }
    }
    is DragTarget.PreviousSection -> {
        val previous = bounds
            .filter { it.sectionId == target.sectionId }
            .sortedBy { it.top }
        previous.lastOrNull()?.bottom
            // The previous section is empty, so there is no row to draw
            // under: the indicator goes where the dragged row came from.
            ?: bounds.firstOrNull { it.id == draggedId }?.top
            ?: 0f
    }
}
```

- [ ] **Step 4: Build and verify**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebug :app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/ui
git commit -m "feat(ui): add edit mode with drag reorder, rename and remove"
```

---

## Task 12: Settings — Appearance Tab

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/settings/{SettingsScreen,AppearanceTab}.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/common/{SliderRow,ChoiceRow,ColorOptionRow}.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/platform/SettingsActivity.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/ui/settings/SettingsAppearanceTest.kt`

**Interfaces:**
- Consumes: Task 3's `updateTheme`, Task 8's `FONT_KEYS`.
- Produces:
  - `@Composable fun SettingsScreen(document: LayoutDocument, installedApps: List<InstalledApp>, initialTab: String?, writeFailed: Boolean, loadFailed: Boolean, onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit)` — the same shape as the tab signatures in Tasks 13 and 14, which take the document and a single `onUpdate` callback
  - `class SettingsViewModel(repository: LayoutRepository, appsRepository: InstalledAppsRepository) : ViewModel` with `val document: StateFlow<LayoutDocument>`, `val installedApps: StateFlow<List<InstalledApp>>`, `val writeFailed: StateFlow<Boolean>`, `val loadFailed: StateFlow<Boolean>`, `fun update(transform: (LayoutDocument) -> LayoutDocument)`
  - `@Composable fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onValueChange: (Float) -> Unit)`
  - `@Composable fun ChoiceRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit)`
  - `@Composable fun ColorOptionRow(label: String, selected: ColorSpec, onSelect: (ColorSpec) -> Unit)`
  - The decisions behind those rows are plain `internal` functions, so a unit
    test can pin them — the project's unit-test source set has no Compose UI
    test dependency and there is no emulator:
    - `internal fun tabIndexFor(key: String?): Int` — the tab a settings key opens
    - `internal fun backgroundStyleLabel(background: BackgroundSpec): String` — the name of a background style
    - `internal fun applyBackgroundStyle(theme: ThemeTokens, style: String): ThemeTokens` — the theme after a style is picked
    - `internal fun ThemeTokens.withGradient(transform: (BackgroundSpec.Gradient) -> BackgroundSpec.Gradient): ThemeTokens` — applies a change to the current gradient, whatever the document holds at the time
    - `internal val FONT_WEIGHTS: List<String>` — every font weight the spec's 100-900 range names; all nine are reachable from the picker
    - `internal fun formatSliderValue(value: Float): String` — how a slider value is written out
    - `internal fun isPaletteColorSelected(selected: ColorSpec, argb: Int): Boolean` — whether a swatch is the picked colour

- [ ] **Step 1: Implement the shared settings rows**

`ui/common/SliderRow.kt`:

```kotlin
package dev.liner.launcher.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A labelled slider.
 *
 * The label carries the current value, so the user reads the number as well
 * as the position of the handle.
 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("$label: ${formatSliderValue(value)}")
        Slider(
            // A value outside the range would make the slider misbehave, so
            // the handle is clamped even though the stored token is not.
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

/**
 * The value as the user reads it: whole numbers without a decimal part,
 * everything else with two decimals.
 */
internal fun formatSliderValue(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else String.format("%.2f", value)
```

`ui/common/ChoiceRow.kt`:

```kotlin
package dev.liner.launcher.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A labelled group of mutually exclusive choices.
 *
 * The chips wrap onto the next line when they do not fit, so a long list of
 * options stays readable on a narrow screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label)
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option) },
            )
        }
    }
}
```

`FlowRow` is in `androidx.compose.foundation.layout` and is `@ExperimentalLayoutApi` — annotate the function with `@OptIn(ExperimentalLayoutApi::class)`.

`ui/common/ColorOptionRow.kt`:

```kotlin
package dev.liner.launcher.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.ColorSpec

/** The colours the user can pick from. */
private val PALETTE = listOf(
    0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF888888.toInt(), 0xFFCCCCCC.toInt(),
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFF6D4C41.toInt(),
)

/**
 * A labelled group of colour choices: the palette's swatches plus a chip
 * that follows the system's own colour.
 *
 * The selected swatch is drawn with a thicker border, so the choice is
 * visible without relying on colour alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorOptionRow(
    label: String,
    selected: ColorSpec,
    onSelect: (ColorSpec) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label)
        PALETTE.forEach { argb ->
            Box(
                Modifier
                    .size(32.dp)
                    .background(Color(argb))
                    .border(
                        width = if (isPaletteColorSelected(selected, argb)) 3.dp else 1.dp,
                        color = Color.White,
                    )
                    .clickable { onSelect(ColorSpec.Solid(argb)) },
            )
        }
        FilterChip(
            selected = selected is ColorSpec.FollowSystem,
            onClick = { onSelect(ColorSpec.FollowSystem) },
            label = { Text("Follow system") },
        )
    }
}

/** Whether [argb] is the colour the user picked. */
internal fun isPaletteColorSelected(selected: ColorSpec, argb: Int): Boolean =
    selected is ColorSpec.Solid && selected.argb == argb
```

- [ ] **Step 2: Implement the appearance tab**

`ui/settings/AppearanceTab.kt`:

```kotlin
package dev.liner.launcher.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.ColorSpec
import dev.liner.launcher.model.ThemeTokens
import dev.liner.launcher.platform.FONT_KEYS
import dev.liner.launcher.ui.common.ChoiceRow
import dev.liner.launcher.ui.common.ColorOptionRow
import dev.liner.launcher.ui.common.SliderRow

/**
 * Every font weight the spec's 100-900 range names.
 *
 * The platform's `weightFor` maps all nine, and `StyleResolver` parses any
 * numeric weight string, so every one of them is a real choice.
 */
internal val FONT_WEIGHTS = listOf(
    "100", "200", "300", "400", "500", "600", "700", "800", "900",
)

/**
 * The appearance tab: every theme token the user can change.
 *
 * The tab writes the tokens straight into the document through
 * [onThemeChange]; it never reads a resolved style back, because a settings
 * screen is where the tokens are *set*, not interpreted.
 */
@Composable
fun AppearanceTab(
    theme: ThemeTokens,
    onThemeChange: ((ThemeTokens) -> ThemeTokens) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Background", modifier = Modifier.padding(top = 8.dp))
        ChoiceRow(
            label = "Style",
            options = listOf("solid", "gradient", "wallpaper"),
            selected = backgroundStyleLabel(theme.background),
            onSelect = { style ->
                onThemeChange { current -> applyBackgroundStyle(current, style) }
            },
        )

        when (val background = theme.background) {
            is BackgroundSpec.Solid -> ColorOptionRow(
                label = "Colour",
                selected = background.color,
                onSelect = { spec -> onThemeChange { it.copy(background = BackgroundSpec.Solid(spec)) } },
            )

            is BackgroundSpec.Gradient -> {
                ColorOptionRow(
                    label = "From",
                    selected = background.from,
                    // The transform derives the background from the CURRENT
                    // document, not from the value captured when the tab was
                    // composed: a stale copy would silently undo whatever
                    // else changed the background in the meantime.
                    onSelect = { spec -> onThemeChange { current -> current.withGradient { it.copy(from = spec) } } },
                )
                ColorOptionRow(
                    label = "To",
                    selected = background.to,
                    onSelect = { spec -> onThemeChange { current -> current.withGradient { it.copy(to = spec) } } },
                )
                SliderRow(
                    label = "Angle",
                    value = background.angleDegrees.toFloat(),
                    range = 0f..360f,
                    steps = 359,
                    // roundToInt, not toInt: the slider's tick value is a
                    // float that can land a hair below the tick, and
                    // truncation would store one degree less than the tick
                    // the user aimed at.
                    onValueChange = { angle -> onThemeChange { current -> current.withGradient { it.copy(angleDegrees = angle.roundToInt()) } } },
                )
            }

            is BackgroundSpec.Wallpaper -> SliderRow(
                label = "Dim",
                value = background.dim,
                range = 0f..0.9f,
                // Five percent steps: the scrim is a percentage, and a
                // tenth of a percent is not a thing a user can see.
                steps = 8,
                onValueChange = { dim -> onThemeChange { it.copy(background = BackgroundSpec.Wallpaper(dim)) } },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        ColorOptionRow(
            label = "Text colour",
            selected = theme.textColor,
            onSelect = { spec -> onThemeChange { it.copy(textColor = spec) } },
        )

        ChoiceRow(
            label = "Font",
            options = FONT_KEYS,
            selected = theme.listFontFamily,
            onSelect = { key -> onThemeChange { it.copy(listFontFamily = key) } },
        )

        SliderRow(
            label = "Text size",
            value = theme.listTextSizeSp,
            range = 12f..48f,
            // The token is a whole number of points, so the slider snaps to
            // it — otherwise the stored value would be 23.4567 while the
            // label read "23.46".
            steps = 35,
            onValueChange = { size -> onThemeChange { it.copy(listTextSizeSp = size) } },
        )

        ChoiceRow(
            label = "Weight",
            // Every weight the spec's 100-900 range names. The platform's
            // font weights are all reachable, and `StyleResolver` parses any
            // numeric weight string.
            options = FONT_WEIGHTS,
            selected = theme.listFontWeight,
            onSelect = { weight -> onThemeChange { it.copy(listFontWeight = weight) } },
        )

        SliderRow(
            label = "Letter spacing",
            value = theme.letterSpacingEm,
            range = -0.05f..0.2f,
            // Two decimal places, which is the precision the token is read
            // back at.
            steps = 24,
            onValueChange = { spacing -> onThemeChange { it.copy(letterSpacingEm = spacing) } },
        )

        SliderRow(
            label = "Row spacing",
            value = theme.itemSpacingDp,
            range = 0f..32f,
            steps = 31,
            onValueChange = { spacing -> onThemeChange { it.copy(itemSpacingDp = spacing) } },
        )

        SliderRow(
            label = "Side padding",
            value = theme.screenPaddingHdp,
            range = 0f..48f,
            steps = 47,
            onValueChange = { padding -> onThemeChange { it.copy(screenPaddingHdp = padding) } },
        )

        SliderRow(
            label = "Top and bottom padding",
            value = theme.screenPaddingVdp,
            range = 0f..48f,
            steps = 47,
            onValueChange = { padding -> onThemeChange { it.copy(screenPaddingVdp = padding) } },
        )

        ChoiceRow(
            label = "Alignment",
            options = listOf("start", "center", "end"),
            selected = theme.alignment,
            onSelect = { alignment -> onThemeChange { it.copy(alignment = alignment) } },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        ChoiceRow(
            label = "Clock",
            options = listOf("hidden", "large", "small"),
            selected = theme.clockStyle,
            onSelect = { style -> onThemeChange { it.copy(clockStyle = style) } },
        )

        ChoiceRow(
            label = "Clock format",
            options = listOf("system", "12h", "24h"),
            selected = theme.clockFormat,
            onSelect = { format -> onThemeChange { it.copy(clockFormat = format) } },
        )

        ChoiceRow(
            label = "Date",
            options = listOf("none", "short", "medium", "long"),
            selected = theme.dateStyle,
            onSelect = { style -> onThemeChange { it.copy(dateStyle = style) } },
        )

        SliderRow(
            label = "Section title size",
            value = theme.sectionTitleSizeSp,
            range = 8f..24f,
            steps = 15,
            onValueChange = { size -> onThemeChange { it.copy(sectionTitleSizeSp = size) } },
        )

        ColorOptionRow(
            label = "Section title colour",
            selected = theme.sectionTitleColor,
            onSelect = { spec -> onThemeChange { it.copy(sectionTitleColor = spec) } },
        )

        ChoiceRow(
            label = "Section title case",
            options = listOf("upper", "as typed"),
            selected = if (theme.sectionTitleUppercase) "upper" else "as typed",
            onSelect = { case -> onThemeChange { it.copy(sectionTitleUppercase = case == "upper") } },
        )

        ChoiceRow(
            label = "Section title rule",
            options = listOf("on", "off"),
            selected = if (theme.sectionTitleRule) "on" else "off",
            onSelect = { value -> onThemeChange { it.copy(sectionTitleRule = value == "on") } },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        ChoiceRow(
            label = "Icons",
            options = listOf("off", "on"),
            selected = if (theme.showIcons) "on" else "off",
            onSelect = { value -> onThemeChange { it.copy(showIcons = value == "on") } },
        )

        SliderRow(
            label = "Icon size",
            value = theme.iconSizeDp.toFloat(),
            range = 16f..64f,
            steps = 47,
            // roundToInt, not toInt — see the angle slider above.
            onValueChange = { size -> onThemeChange { it.copy(iconSizeDp = size.roundToInt()) } },
        )

        ChoiceRow(
            label = "Drawer style",
            options = listOf("icons", "text"),
            selected = theme.drawerStyle,
            onSelect = { style -> onThemeChange { it.copy(drawerStyle = style) } },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        TextButton(
            onClick = { onThemeChange { ThemeTokens() } },
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Text("Reset appearance")
        }
    }
}

/** The name of the background style, as the style chips spell it. */
internal fun backgroundStyleLabel(background: BackgroundSpec): String = when (background) {
    is BackgroundSpec.Solid -> "solid"
    is BackgroundSpec.Gradient -> "gradient"
    is BackgroundSpec.Wallpaper -> "wallpaper"
}

/**
 * Applies [transform] to the theme's gradient, if it has one. A theme
 * whose background is not a gradient is left alone — the gradient controls
 * are only reachable when the background IS a gradient.
 */
internal fun ThemeTokens.withGradient(
    transform: (BackgroundSpec.Gradient) -> BackgroundSpec.Gradient,
): ThemeTokens {
    val current = background
    if (current !is BackgroundSpec.Gradient) return this
    return copy(background = transform(current))
}

/**
 * The theme after the user picks a background style.
 *
 * Each style comes with its own starting colours, so picking one shows a
 * background the user can then adjust. An unknown style falls back to the
 * wallpaper rather than leaving the theme without a background.
 */
internal fun applyBackgroundStyle(theme: ThemeTokens, style: String): ThemeTokens = when (style) {
    "solid" -> theme.copy(background = BackgroundSpec.Solid(ColorSpec.Solid(0xFF101014.toInt())))
    "gradient" -> theme.copy(
        background = BackgroundSpec.Gradient(
            from = ColorSpec.Solid(0xFF101014.toInt()),
            to = ColorSpec.Solid(0xFF2A2A32.toInt()),
        ),
    )
    else -> theme.copy(background = BackgroundSpec.Wallpaper(0.35f))
}
```

- [ ] **Step 3: Implement the settings scaffold and view model**

`ui/settings/SettingsScreen.kt`:

```kotlin
package dev.liner.launcher.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.updateTheme

private val TABS = listOf("Appearance", "Apps", "Sections", "Import / Export", "Advanced")
private val TAB_KEYS = listOf("appearance", "apps", "sections", "importexport", "advanced")

/**
 * The settings screen: the tabs, and the tab the user was sent to.
 *
 * The screen holds no state of its own beyond the selected tab — every
 * change goes back to the caller, which is the only thing that writes to
 * the store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    initialTab: String?,
    writeFailed: Boolean,
    loadFailed: Boolean,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    val startIndex = remember(initialTab) { tabIndexFor(initialTab) }
    var selectedTab by rememberSaveable { mutableIntStateOf(startIndex) }

    val context = LocalContext.current

    // A failed save must not be silent.
    LaunchedEffect(writeFailed) {
        if (writeFailed) {
            Toast.makeText(
                context,
                "Your changes could not be saved. Try again.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // Nor may a failed read: the settings activity can be the process's
    // first activity, and the user is looking at the default theme without
    // knowing why.
    LaunchedEffect(loadFailed) {
        if (loadFailed) {
            Toast.makeText(
                context,
                "Your saved layout could not be read, so Liner started with defaults.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Liner Settings") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                when (TABS[selectedTab]) {
                    "Appearance" -> AppearanceTab(
                        theme = document.theme,
                        onThemeChange = { transform -> onUpdate { it.updateTheme(transform) } },
                    )

                    "Apps" -> AppsTab(
                        document = document,
                        installedApps = installedApps,
                        onUpdate = onUpdate,
                    )

                    "Sections" -> SectionsTab(
                        document = document,
                        onUpdate = onUpdate,
                    )

                    "Import / Export" -> ImportExportTab(
                        document = document,
                        onUpdate = onUpdate,
                    )

                    "Advanced" -> AdvancedTab(
                        document = document,
                        onUpdate = onUpdate,
                    )
                }
            }
        }
    }
}

/**
 * The index of the tab the user was sent to.
 *
 * The keys are the ones the rest of the app opens settings with — the
 * drawer's gear opens "appearance", the home screen's empty state opens
 * "apps". An unknown or missing key falls back to the first tab rather
 * than leaving the screen on nothing.
 */
internal fun tabIndexFor(key: String?): Int {
    val index = key?.let { TAB_KEYS.indexOf(it) } ?: -1
    return index.coerceAtLeast(0)
}
```

`ui/settings/SettingsViewModel.kt`:

```kotlin
package dev.liner.launcher.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.store.LayoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The settings screen's state: the layout document and the installed apps.
 *
 * The view model owns no storage of its own — every change is handed to the
 * repository, which is the only thing that writes.
 */
class SettingsViewModel(
    private val repository: LayoutRepository,
    private val appsRepository: InstalledAppsRepository,
) : ViewModel() {

    val document: StateFlow<LayoutDocument> = repository.document

    /** True when the last save failed; the screen turns it into a notice. */
    val writeFailed: StateFlow<Boolean> = repository.writeFailed

    /**
     * True when the stored layout could not be read. The settings activity is
     * exported with CATEGORY_LAUNCHER, so it can be the process's first
     * activity and the screen that performs the only load — it has to tell
     * the user their layout was reset, the way the home screen does.
     */
    val loadFailed: StateFlow<Boolean> = repository.loadFailed

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    init {
        // The repository reads the store once per process, so this is a no-op
        // when the home screen has already loaded it — and it must never
        // clobber an edit that has not been written back yet.
        viewModelScope.launch { repository.load() }
        // The package manager query is not cheap, so it stays off the main
        // thread.
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = appsRepository.loadInstalledApps().apps
        }
    }

    fun update(transform: (LayoutDocument) -> LayoutDocument) = repository.update(transform)

    companion object {
        fun factory(repository: LayoutRepository, appsRepository: InstalledAppsRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(repository, appsRepository) as T
            }
    }
}
```

- [ ] **Step 4: Wire the settings activity**

Replace `platform/SettingsActivity.kt`:

```kotlin
package dev.liner.launcher.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liner.launcher.ui.settings.SettingsScreen
import dev.liner.launcher.ui.settings.SettingsViewModel

class SettingsActivity : ComponentActivity() {

    private val container by lazy { (application as LinerApp).container }

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.factory(container.repository, container.appsRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getStringExtra(EXTRA_TAB)

        setContent {
            LinerTheme {
                val document by viewModel.document.collectAsStateWithLifecycle()
                val apps by viewModel.installedApps.collectAsStateWithLifecycle()
                val writeFailed by viewModel.writeFailed.collectAsStateWithLifecycle()
                val loadFailed by viewModel.loadFailed.collectAsStateWithLifecycle()

                SettingsScreen(
                    document = document,
                    installedApps = apps,
                    initialTab = initialTab,
                    writeFailed = writeFailed,
                    loadFailed = loadFailed,
                    onUpdate = viewModel::update,
                )
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"

        fun intent(context: Context, tab: String? = null): Intent =
            Intent(context, SettingsActivity::class.java).apply {
                if (tab != null) putExtra(EXTRA_TAB, tab)
            }
    }
}
```

- [ ] **Step 5: Create placeholder tabs so the build compiles**

Create `ui/settings/{AppsTab,SectionsTab,ImportExportTab,AdvancedTab}.kt`, each with a body that renders a single explanatory `Text`. They are fully implemented in Tasks 13 and 14.

```kotlin
package dev.liner.launcher.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument

/** The Apps tab. Fully implemented in Task 13. */
@Composable
fun AppsTab(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    Text("Apps")
}
```

Repeat for the other three with the same signature shape they will keep in Tasks 13–14 (`SectionsTab(document, installedApps, onUpdate)`, `ImportExportTab(document, onUpdate)`, `AdvancedTab(document, onUpdate)`). Each placeholder renders a single `Text` with its tab's name. The sections tab takes the installed apps because it lists the apps of a section by name.

- [ ] **Step 6: Pin the settings decisions with a unit test**

The composables themselves cannot be exercised headlessly — the project's
unit-test source set has no Compose UI test dependency and there is no
emulator — so the decisions they make are pulled into plain `internal`
functions (see the Interfaces list) and checked in
`app/src/test/kotlin/dev/liner/launcher/ui/settings/SettingsAppearanceTest.kt`:

```kotlin
package dev.liner.launcher.ui.settings

import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.ColorSpec
import dev.liner.launcher.model.ThemeTokens
import dev.liner.launcher.platform.weightFor
import dev.liner.launcher.ui.common.formatSliderValue
import dev.liner.launcher.ui.common.isPaletteColorSelected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind the appearance tab and the settings screen.
 *
 * The tab and the screen are composables, so their decisions are pulled into
 * plain functions that can be checked without a device — the project's
 * unit-test source set has no Compose UI test dependency and the sandbox has
 * no emulator.
 */
class SettingsAppearanceTest {

    @Test
    fun `the tabs the rest of the app opens resolve to the right tab`() {
        // The drawer's gear opens "appearance" and the home screen's empty
        // state opens "apps"; both have to land on the tab they name.
        assertEquals(0, tabIndexFor("appearance"))
        assertEquals(1, tabIndexFor("apps"))
        assertEquals(2, tabIndexFor("sections"))
        assertEquals(3, tabIndexFor("importexport"))
        assertEquals(4, tabIndexFor("advanced"))
    }

    @Test
    fun `an unknown tab falls back to the first tab`() {
        assertEquals(0, tabIndexFor(null))
        assertEquals(0, tabIndexFor("nonsense"))
        assertEquals(0, tabIndexFor("Appearance"))
    }

    @Test
    fun `a background style is named the way the style chips spell it`() {
        assertEquals("solid", backgroundStyleLabel(BackgroundSpec.Solid(ColorSpec.FollowSystem)))
        assertEquals(
            "gradient",
            backgroundStyleLabel(
                BackgroundSpec.Gradient(ColorSpec.FollowSystem, ColorSpec.FollowSystem),
            ),
        )
        assertEquals("wallpaper", backgroundStyleLabel(BackgroundSpec.Wallpaper(0.35f)))
    }

    @Test
    fun `picking the solid style gives the theme a solid background`() {
        val theme = applyBackgroundStyle(ThemeTokens(), "solid")

        assertEquals(BackgroundSpec.Solid(ColorSpec.Solid(0xFF101014.toInt())), theme.background)
    }

    @Test
    fun `picking the gradient style gives the theme a gradient background`() {
        val theme = applyBackgroundStyle(ThemeTokens(), "gradient")

        assertEquals(
            BackgroundSpec.Gradient(
                from = ColorSpec.Solid(0xFF101014.toInt()),
                to = ColorSpec.Solid(0xFF2A2A32.toInt()),
            ),
            theme.background,
        )
    }

    @Test
    fun `picking the wallpaper style gives the theme a wallpaper background`() {
        val theme = applyBackgroundStyle(ThemeTokens(), "wallpaper")

        assertEquals(BackgroundSpec.Wallpaper(0.35f), theme.background)
    }

    @Test
    fun `an unknown background style leaves the theme with a background`() {
        val theme = applyBackgroundStyle(ThemeTokens(), "nonsense")

        assertEquals(BackgroundSpec.Wallpaper(0.35f), theme.background)
    }

    @Test
    fun `picking a background style leaves the other tokens alone`() {
        val theme = ThemeTokens(listTextSizeSp = 33f, listFontFamily = "serif")

        val result = applyBackgroundStyle(theme, "solid")

        assertEquals(33f, result.listTextSizeSp, 0.001f)
        assertEquals("serif", result.listFontFamily)
    }

    @Test
    fun `a whole slider value is shown without decimals`() {
        assertEquals("20", formatSliderValue(20f))
        assertEquals("0", formatSliderValue(0f))
        assertEquals("-8", formatSliderValue(-8f))
    }

    @Test
    fun `a fractional slider value is shown with two decimals`() {
        // The decimal separator follows the locale, so the digits are what
        // the assertion pins.
        assertTrue(formatSliderValue(20.5f).matches(Regex("20[.,]50")))
        assertTrue(formatSliderValue(0.05f).matches(Regex("0[.,]05")))
    }

    @Test
    fun `the picked palette colour is the selected one`() {
        val argb = 0xFFE53935.toInt()

        assertTrue(isPaletteColorSelected(ColorSpec.Solid(argb), argb))
        assertFalse(isPaletteColorSelected(ColorSpec.Solid(argb), 0xFF43A047.toInt()))
    }

    @Test
    fun `following the system selects no palette colour`() {
        assertFalse(isPaletteColorSelected(ColorSpec.FollowSystem, 0xFF000000.toInt()))
    }

    @Test
    fun `a gradient change is applied to the background the theme holds now`() {
        // The transform derives the background from the CURRENT document, so
        // a change that landed in between is not undone by a stale copy.
        val theme = ThemeTokens(
            background = BackgroundSpec.Gradient(
                from = ColorSpec.Solid(0xFF101014.toInt()),
                to = ColorSpec.Solid(0xFF2A2A32.toInt()),
                angleDegrees = 90,
            ),
        )

        val result = theme.withGradient { it.copy(angleDegrees = 180) }

        assertEquals(
            BackgroundSpec.Gradient(
                from = ColorSpec.Solid(0xFF101014.toInt()),
                to = ColorSpec.Solid(0xFF2A2A32.toInt()),
                angleDegrees = 180,
            ),
            result.background,
        )
    }

    @Test
    fun `a theme without a gradient is left alone by a gradient change`() {
        val theme = ThemeTokens(background = BackgroundSpec.Solid(ColorSpec.FollowSystem))

        assertEquals(theme, theme.withGradient { it.copy(angleDegrees = 180) })
    }

    @Test
    fun `every weight the spec names is offered`() {
        assertEquals(
            listOf("100", "200", "300", "400", "500", "600", "700", "800", "900"),
            FONT_WEIGHTS,
        )
    }

    @Test
    fun `every offered weight renders differently`() {
        // A weight the platform cannot map would be a dead chip: it would
        // look the same as the default.
        val rendered = FONT_WEIGHTS.map { weightFor(it.toInt()) }

        assertEquals(
            "every weight the picker offers must look different",
            FONT_WEIGHTS.size,
            rendered.distinct().size,
        )
    }
}
```

- [ ] **Step 7: Build and verify**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebug :app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/ui/settings app/src/main/kotlin/dev/liner/launcher/ui/common \
        app/src/main/kotlin/dev/liner/launcher/platform \
        app/src/test/kotlin/dev/liner/launcher/ui/settings
git commit -m "feat(settings): add settings scaffold and the appearance tab"
```

---

## Task 13: Settings — Apps and Sections Tabs

**Files:**
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/settings/AppsTab.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/settings/SectionsTab.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/platform/SettingsActivity.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/common/EditSheets.kt` (the shared section chooser)
- Modify: `app/src/main/kotlin/dev/liner/launcher/model/HomeEntry.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/model/AppCatalog.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/DocumentEditsTest.kt` (extended)
- Test: `app/src/test/kotlin/dev/liner/launcher/ui/settings/AppsTabHomeToggleTest.kt`

**Interfaces:**
- Consumes: Task 3's `addItem`, `removeItem`, `reorderHomeItems`, `addSection`, `renameSection`, `removeSection`, `moveSection`, `setHiddenFromDrawer`; Task 6's `LayoutRepository`.
- Produces: the real `AppsTab` and `SectionsTab` composables with the signatures introduced in Task 12.

- [ ] **Step 1: Add the failing test**

Append to `app/src/test/kotlin/dev/liner/launcher/model/DocumentEditsTest.kt`:

```kotlin
    @Test
    fun `reorderHomeItems keeps items in their own sections`() {
        val document = LayoutDocument(
            sections = listOf(
                Section(id = "s1", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
                Section(id = "s2", items = listOf(HomeItem(id = "b", packageName = "p.b"))),
            ),
        )

        val result = document.reorderHomeItems(listOf("b", "a"))

        assertEquals(listOf("a"), result.sections[0].items.map { it.id })
        assertEquals(listOf("b"), result.sections[1].items.map { it.id })
    }
```

- [ ] **Step 2: Run the test**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*DocumentEditsTest*'`

Expected: `BUILD SUCCESSFUL`, 23 tests, 0 failures.

- [ ] **Step 3: Implement the Apps tab**

`ui/settings/AppsTab.kt`:

```kotlin
package dev.liner.launcher.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.addItem
import dev.liner.launcher.model.removeItem
import dev.liner.launcher.model.reorderHomeItems
import dev.liner.launcher.model.setHiddenFromDrawer
import java.util.UUID

@Composable
fun AppsTab(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val homePackages = remember(document) {
        document.sections.flatMap { it.items }.associateBy { it.packageName }
    }
    val homeOrder = remember(document) {
        document.sections.flatMap { it.items }.map { it.id }
    }

    val visible = remember(installedApps, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) installedApps
        else installedApps.filter { it.systemLabel.contains(trimmed, ignoreCase = true) }
    }

    val defaultSectionId = document.sections.firstOrNull()?.id

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Checked apps appear on your home screen",
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        LazyColumn {
            items(visible, key = { it.packageName }) { app ->
                val isOnHome = homePackages.containsKey(app.packageName)
                val isHidden = app.packageName in document.hiddenFromDrawer

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = isOnHome,
                        onCheckedChange = { checked ->
                            if (checked) {
                                onUpdate { doc ->
                                    // Create a first section on demand, then add the app to it.
                                    val sectionId = doc.sections.firstOrNull()?.id
                                        ?: "default-${UUID.randomUUID()}"
                                    val withSection = if (doc.sections.isEmpty()) {
                                        doc.addSection(sectionId, null)
                                    } else {
                                        doc
                                    }
                                    withSection.addItem(
                                        item = HomeItem(
                                            id = UUID.randomUUID().toString(),
                                            packageName = app.packageName,
                                        ),
                                        sectionId = sectionId,
                                    )
                                }
                            } else {
                                val itemId = homePackages[app.packageName]?.id ?: return@Checkbox
                                onUpdate { doc -> doc.removeItem(itemId) }
                            }
                        },
                    )
                    Text(
                        text = app.systemLabel,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { },
                    )
                    TextButton(
                        onClick = {
                            onUpdate { doc -> doc.setHiddenFromDrawer(app.packageName, !isHidden) }
                        },
                    ) {
                        Text(if (isHidden) "Show" else "Hide")
                    }
                }
            }
        }

        Text(
            text = "Home order: ${homeOrder.size} apps",
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
```

- [ ] **Step 4: Implement the Sections tab**

`ui/settings/SectionsTab.kt`:

```kotlin
package dev.liner.launcher.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.addSection
import dev.liner.launcher.model.moveSection
import dev.liner.launcher.model.removeSection
import dev.liner.launcher.model.renameSection
import java.util.UUID

@Composable
fun SectionsTab(
    document: LayoutDocument,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    var newTitle by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = newTitle,
            onValueChange = { newTitle = it },
            singleLine = true,
            label = { Text("New section title (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                onUpdate { doc -> doc.addSection(UUID.randomUUID().toString(), newTitle) }
                newTitle = ""
            },
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text("Add section")
        }

        document.sections.forEachIndexed { index, section ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(section.title ?: "Untitled")
                    Text(
                        text = "${section.items.size} apps",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = { onUpdate { doc -> doc.moveSection(section.id, index - 1) } },
                    enabled = index > 0,
                ) { Text("Up") }
                TextButton(
                    onClick = { onUpdate { doc -> doc.moveSection(section.id, index + 1) } },
                    enabled = index < document.sections.lastIndex,
                ) { Text("Down") }
                TextButton(
                    onClick = { onUpdate { doc -> doc.removeSection(section.id) } },
                ) { Text("Delete") }
            }
        }
    }
}
```

- [ ] **Step 5: Add the affordances the spec asks for that the snippet above lacks**

The spec's Apps tab is: *"All installed apps, searchable, each with a checkbox;
checked = appears on home; **reorder the checked apps here (same reorder
affordance as home)**; per-app: **hide from drawer**, **move to a specific
section**; **refresh button** (re-queries PackageManager)"*.
And its Sections tab is: *"Create / rename / reorder / delete sections;
**move items between sections**; section title is optional"*.

The snippet above implements the search, the checkbox, the hide-from-drawer
toggle and the section list, but not the four marked affordances. Add them.

**5.1 Reorder the checked apps.**

Above the all-apps list, show a *"Home screen order"* list of the apps that
are on the home screen, **grouped by section exactly as the home screen shows
them**, each reorderable by the same long-press-to-drag affordance the home
screen uses — a dragged row keeps its slot, an insertion indicator shows where
it would land, and one commit happens on release.

The spec asks for the *same reorder affordance as home*, and on the home
screen a drag may cross a section boundary: dropping an item into another
section moves it there. So the list is grouped by section, the drop target is
resolved by `DragTargeting.computeTarget` — exactly as on the home screen —
and the commit is the same `moveItem` call the home screen makes:

```kotlin
when (val target = editController.endDrag()) {
    is DragTarget.InSection -> onUpdate { doc ->
        doc.moveItem(itemId, target.sectionId, target.index)
    }
    is DragTarget.PreviousSection -> onUpdate { doc ->
        doc.moveItem(
            itemId,
            target.sectionId,
            doc.sectionById(target.sectionId)?.items?.size ?: 0,
        )
    }
    null -> Unit
}
```

Reuse the tested primitives rather than reimplementing the maths:

- `EditModeController` (`ui/home/EditModeController.kt`) for the drag state;
- `DragTargeting.computeTarget` for the target;
- `dragIndicatorTop(target, bounds, draggedId)` for the indicator's Y.

`dragIndicatorTop` is `internal` in the `ui.home` package and the settings tab
lives in `ui.settings`, so **move it to a package both can reach** — put it in
`ui/common/` (with `ui/home/HomeScreen.kt` importing it from there) and update
the plan's Task 11 step that introduced it.

`LayoutDocument.reorderHomeItems(flatOrder)` stays in the model — it is a
tested operation — but it is **not** the commit this list makes: it keeps
every item in its own section, so a drag across a section boundary would
silently do nothing.

**5.2 Move an app to a specific section.**

Each checked app in the all-apps list gets a *"Move to section"* control
beside the hide-from-drawer toggle. It opens a chooser of the document's
sections — **excluding the section the item is already in**, the same rule the
home screen's item editor follows — and commits:

```kotlin
onUpdate { doc ->
    doc.moveItem(itemId, sectionId, doc.sectionById(sectionId)?.items?.size ?: 0)
}
```

**5.3 Refresh the app list.**

Add a *Refresh* button next to the search field. It re-queries the package
manager. The query lives in the view model, so:

- `SettingsViewModel` gains `fun refreshApps()` that re-runs the query off the
  main thread (the same way `init` does);
- `SettingsScreen` gains an `onRefresh: () -> Unit` parameter and passes it
  to `AppsTab`;
- `SettingsActivity` passes `viewModel::refreshApps`.

**5.4 Move items between sections.**

In the Sections tab, each section row expands to list its items. Each item has
a *"Move to"* control that opens the same section chooser as 5.2 and commits
the same `moveItem` call. A section with no items shows a short "No apps"
line instead of an empty list.

**5.5 Rename a section.**

Tapping a section row opens the existing `SectionEditorSheet`
(`ui/common/EditSheets.kt`), which renames the section and — with the
confirmation Task 11 added — asks before deleting it and its items. Use it
rather than a second editor.

- [ ] **Step 6: Close the gaps the review found**

**6.1 Unchecking an app must not throw away its customisation.** `toggleHome`
builds a brand-new `HomeItem(id = UUID…, packageName = …)` with no label and no
style, and appends it to the *first* section. So unchecking an app and
checking it again drops the rename the user set, drops the per-item style,
and moves the app out of its section — with no undo and no message.

Keep the removed item and put it back: when the app is unchecked, remember
the `HomeItem` and the section it was in; when it is checked again, re-add
**that** item with `addItem(item, itsSectionId, index)` rather than minting a
new one. If the app was not on the home screen before, add a fresh item to the
first section as the tab does now.

**6.2 Unchecking must remove every copy of the app.** `toggleHome` removes
only the first item with that package. If an app sits on the home screen in
two sections (possible through an imported document), unchecking removes one
copy, the checkbox recomputes straight back to *checked*, and the app stays on
the home screen — the control appears to do nothing. Remove every item with
that package, and derive the checked state from the same "any item with this
package" rule.

**6.3 The section chooser must be scrollable.** `SectionChooserSheet` lists
every other section as a `TextButton` inside the `AlertDialog`'s `text` slot,
which Material 3 does not scroll. With more sections than fit on screen the
lower ones are unreachable — and the chooser is the only way to move an item
between sections. Cap the list's height and give it
`Modifier.verticalScroll(rememberScrollState())`. `ItemEditorSheet` has the
same shape, so fix it there too.

**6.4 The Apps tab must be able to say "still loading".** `SettingsViewModel`
exposes no `appsLoaded` flag, so between opening the tab and the package
manager answering, the "All apps" list is empty with no indication that it
is still loading. The drawer and the home screen both have this flag. Mirror
it: a private `MutableStateFlow<Boolean>` set in a `finally` after the query
(so a failed query still marks "we have looked"), exposed as
`val appsLoaded: StateFlow<Boolean>`. `SettingsActivity` collects it and
passes it down; the Apps tab shows a "Loading…" line while it is false.

**6.5 Delete the code this task made unreachable.** `homeOrderWithMovedItem`
(`model/HomeOrder.kt`) has no production caller since the reorder moved to
`moveItem`, and `HomeEntry.sectionTitle` is written by `AppCatalog.homeEntries`
but read by nobody. Delete `model/HomeOrder.kt`, its test file, and the
`sectionTitle` field (and its constructor argument at every call site).
`reorderHomeItems` stays — it is a tested model operation.

- [ ] **Step 7: Build and verify**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebug :app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/ui/settings app/src/test/kotlin/dev/liner/launcher/model
git commit -m "feat(settings): add apps and sections tabs"
```

---

## Task 14: Settings — Import / Export and Advanced Tabs

**Files:**
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/settings/ImportExportTab.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/settings/AdvancedTab.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/ImportExportTest.kt`

**Interfaces:**
- Consumes: Task 2's `DocumentCodec`, Task 8's `DefaultHomeDetector`.
- Produces:
  - `object ImportValidator { fun validate(raw: String): ImportResult }` where `data class ImportResult(val document: LayoutDocument?, val error: String?, val summary: List<String>)`
  - `@Composable fun ImportExportTab(document: LayoutDocument, onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit)`
  - `@Composable fun AdvancedTab(document: LayoutDocument, onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit)`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/dev/liner/launcher/model/ImportExportTest.kt`:

```kotlin
package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportExportTest {

    private val current = LayoutDocument(
        sections = listOf(Section(id = "s1", title = "Mine", items = listOf(HomeItem(id = "a", packageName = "p.a")))),
    )

    @Test
    fun `a valid document is accepted`() {
        val result = ImportValidator.validate(DocumentCodec.encode(current))

        assertNotNull(result.document)
        assertNull(result.error)
    }

    @Test
    fun `malformed json is rejected with an explanation`() {
        val result = ImportValidator.validate("{not json")

        assertNull(result.document)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("readable"))
    }

    @Test
    fun `a newer document version is rejected with an explanation`() {
        val result = ImportValidator.validate("""{"version":99,"sections":[],"theme":{}}""")

        assertNull(result.document)
        assertTrue(result.error!!.contains("newer"))
    }

    @Test
    fun `the summary lists what changed`() {
        val incoming = LayoutDocument(
            sections = listOf(
                Section(id = "x", title = "New", items = listOf(HomeItem(id = "z", packageName = "p.z"))),
            ),
        )

        val result = ImportValidator.validate(DocumentCodec.encode(incoming))

        assertTrue(result.summary.isNotEmpty())
        assertTrue(result.summary.any { it.contains("p.z") })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*ImportExportTest*'`

Expected: compilation failure — `Unresolved reference: ImportValidator`.

- [ ] **Step 3: Implement the validator**

Create `model/ImportValidator.kt`:

```kotlin
package dev.liner.launcher.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ImportResult(
    val document: LayoutDocument?,
    val error: String?,
    val summary: List<String>,
)

object ImportValidator {
    /**
     * Parses [raw] and describes the difference from [current].
     * Returns a null document with an error message when the payload cannot
     * be used; returns a document plus a human readable summary otherwise.
     */
    fun validate(raw: String, current: LayoutDocument = LayoutDocument()): ImportResult {
        val decoded = decode(raw) ?: return ImportResult(
            document = null,
            error = explain(raw),
            summary = emptyList(),
        )

        return ImportResult(document = decoded, error = null, summary = summarise(current, decoded))
    }

    /**
     * The document a payload describes, or null when it cannot be read.
     *
     * The codec lets Errors through on purpose — a bug in it must not be
     * mistaken for a broken file. This matches that contract and catches
     * Exception only, so an Error inside the decoder surfaces instead of
     * being reported to the user as an unreadable file.
     */
    private fun decode(raw: String): LayoutDocument? = try {
        DocumentCodec.decode(raw)
    } catch (_: Exception) {
        null
    }

    /**
     * The explanation for a payload the codec refused.
     *
     * The codec refuses two different things: a payload it cannot read, and
     * a payload from a newer version of Liner. They need different
     * explanations — one is a broken file, the other is a file this build
     * must not overwrite its own layout with.
     */
    private fun explain(raw: String): String {
        val version = declaredVersion(raw)
        return if (version != null && version > LayoutDocument.CURRENT_VERSION) {
            "That layout was made by a newer version of Liner, so this version cannot read it."
        } else {
            "That file is not readable as a Liner layout."
        }
    }

    /**
     * The version a payload declares, or null when it declares none.
     */
    private fun declaredVersion(raw: String): Int? = try {
        val element = Json.parseToJsonElement(raw)
        (element as? JsonObject)?.get("version")?.jsonPrimitive?.intOrNull
    } catch (_: Exception) {
        null
    }

    /**
     * What the imported layout would change about the current one.
     *
     * The apps are named by their package, because that is what a layout
     * document holds — the name the user gave an app is not part of the
     * document.
     *
     * The sections are compared by their membership and their titles, not by
     * their number alone: the same apps in another section, in another
     * order, or under another title is a change the user has to be told
     * about, because the import rearranges the home screen.
     */
    private fun summarise(current: LayoutDocument, incoming: LayoutDocument): List<String> {
        val changes = mutableListOf<String>()

        val currentPackages = current.sections.flatMap { it.items }.map { it.packageName }.toSet()
        val incomingPackages = incoming.sections.flatMap { it.items }.map { it.packageName }.toSet()

        val added = incomingPackages - currentPackages
        val removed = currentPackages - incomingPackages
        if (added.isNotEmpty()) changes += "Adds apps: ${added.joinToString()}"
        if (removed.isNotEmpty()) changes += "Removes apps: ${removed.joinToString()}"

        changes += sectionChanges(current, incoming)

        if (current.sections.size != incoming.sections.size) {
            changes += "Sections: ${current.sections.size} becomes ${incoming.sections.size}"
        }
        if (current.theme != incoming.theme) changes += "Replaces the theme"

        if (changes.isEmpty()) changes += "No visible changes"
        return changes
    }

    /**
     * The changes between the two documents' sections.
     *
     * A section is recognised by its id, so the same section under another
     * title, with another membership or in another order is a *changed*
     * section — and a section that is only in one of the two documents is an
     * added or a removed one.
     */
    private fun sectionChanges(current: LayoutDocument, incoming: LayoutDocument): List<String> {
        val changes = mutableListOf<String>()
        val currentById = current.sections.associateBy { it.id }

        for (section in incoming.sections) {
            val other = currentById[section.id]
            if (other == null) {
                changes += "Adds section \"${describe(section)}\""
            } else if (section.title != other.title) {
                changes += "Renames section \"${describe(other)}\" to \"${describe(section)}\""
            }
            changes += membershipChanges(other, section)
            changes += orderChanges(other, section)
        }

        for (section in current.sections) {
            if (incoming.sections.none { it.id == section.id }) {
                changes += "Removes section \"${describe(section)}\""
            }
        }

        return changes
    }

    /**
     * The apps that left a section and the ones that joined it.
     *
     * An app that only moved to another section is not added or removed —
     * it is somewhere else now, and that is what the user has to see.
     */
    private fun membershipChanges(before: Section?, after: Section): List<String> {
        val there = before?.items?.map { it.packageName } ?: emptyList()
        val here = after.items.map { it.packageName }
        val lost = there - here.toSet()
        val gained = here - there.toSet()

        return buildList {
            if (lost.isNotEmpty()) add("Section \"${describe(after)}\" loses ${lost.joinToString()}")
            if (gained.isNotEmpty()) add("Section \"${describe(after)}\" gains ${gained.joinToString()}")
        }
    }

    /**
     * Whether the apps a section holds are in another order.
     */
    private fun orderChanges(before: Section?, after: Section): List<String> {
        val there = before?.items?.map { it.packageName } ?: emptyList()
        val here = after.items.map { it.packageName }
        val shared = there.filter { it in here }
        val sharedHere = here.filter { it in there }

        return if (shared != sharedHere) listOf("Reorders section \"${describe(after)}\"") else emptyList()
    }

    /**
     * How a section is named in the summary: its title, or its id when it
     * has no title of its own.
     */
    private fun describe(section: Section): String =
        section.title?.takeIf { it.isNotBlank() } ?: section.id
}
```

> `DocumentCodec` already lives in `model` (created in Task 2), so
> `ImportValidator` uses it directly — the dependency rule is satisfied
> without moving anything.

- [ ] **Step 4: Implement the Import / Export tab**

`ui/settings/ImportExportTab.kt`:

```kotlin
package dev.liner.launcher.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.ImportValidator
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.addSection

@Composable
fun ImportExportTab(
    document: LayoutDocument,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    val context = LocalContext.current

    Column(Modifier.fillMaxWidth()) {
        Text("Export", modifier = Modifier.padding(top = 8.dp))
        TextButton(
            onClick = {
                val json = DocumentCodec.encode(document)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Liner layout", json))
            },
        ) { Text("Copy layout to clipboard") }

        TextButton(
            onClick = { shareLayout(context, document) },
        ) { Text("Share layout as a file") }

        Text("Import", modifier = Modifier.padding(top = 24.dp))
        TextButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val raw = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return@TextButton
                val result = ImportValidator.validate(raw, document)
                if (result.document != null) {
                    onUpdate { importedDocument(result.document) }
                }
            },
        ) { Text("Import from clipboard") }

        Text(
            text = "Importing replaces your current layout. Export first if you want a backup.",
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * The document an import stores.
 *
 * The payload keeps the version it was written with, and a v0 document
 * stored as a v0 document would skip every future migration. It is stored
 * at the version this build understands.
 */
internal fun importedDocument(document: LayoutDocument): LayoutDocument =
    document.copy(version = LayoutDocument.CURRENT_VERSION)

private fun shareLayout(context: Context, document: LayoutDocument) {
    val json = DocumentCodec.encode(document)
    val intent = android.content.Intent(
        android.content.Intent.ACTION_SEND,
    ).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_TEXT, json)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Liner layout")
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, "Share layout")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
```

- [ ] **Step 5: Implement the Advanced tab**

`ui/settings/AdvancedTab.kt`:

```kotlin
package dev.liner.launcher.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.setAutoPruneUninstalled
import dev.liner.launcher.platform.DefaultHomeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdvancedTab(
    document: LayoutDocument,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    val context = LocalContext.current
    // The answer is asked in an effect, off the main thread, and it starts
    // out unknown: null means "not known yet", and the banner waits for it
    // rather than flashing before the answer arrives.
    val isDefault = rememberDefaultHome(context)

    Column(Modifier.fillMaxWidth()) {
        if (isDefault == false) {
            Text(
                text = "Liner is not your default launcher.",
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = { DefaultHomeDetector.requestDefaultHome(context) },
                modifier = Modifier.padding(vertical = 8.dp),
            ) { Text("Set as default launcher") }
        }

        Text(
            text = if (document.autoPruneUninstalled) {
                "Uninstalled apps are removed from your home screen automatically."
            } else {
                "Uninstalled apps stay on your home screen, greyed out."
            },
            modifier = Modifier.padding(top = 16.dp),
        )
        TextButton(
            onClick = { onUpdate { it.setAutoPruneUninstalled(!it.autoPruneUninstalled) } },
        ) {
            Text(if (document.autoPruneUninstalled) "Stop removing uninstalled apps" else "Remove uninstalled apps automatically")
        }

        TextButton(
            onClick = {
                onUpdate { doc ->
                    doc.copy(
                        sections = emptyList(),
                        theme = dev.liner.launcher.model.ThemeTokens(),
                        hiddenFromDrawer = emptyList(),
                        autoPruneUninstalled = false,
                    )
                }
            },
            modifier = Modifier.padding(top = 24.dp),
        ) { Text("Reset everything") }
    }
}

/**
 * Whether Liner is the default home app, or null while the answer is still
 * on its way.
 *
 * The answer needs a round trip to the role manager or the package manager,
 * so it is asked off the main thread and after the composition: the banner
 * must not flash before the answer arrives.
 */
@Composable
private fun rememberDefaultHome(context: Context): Boolean? {
    var isDefaultHome by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Asked once when the tab appears.
    LaunchedEffect(context) {
        isDefaultHome = withContext(Dispatchers.IO) {
            DefaultHomeDetector.isDefaultHome(context)
        }
    }

    // …and again whenever the screen comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    isDefaultHome = withContext(Dispatchers.IO) {
                        DefaultHomeDetector.isDefaultHome(context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return isDefaultHome
}
```

- [ ] **Step 6: Close the gaps the review found**

**6.1 The import summary must report what actually changed.** It compares the
package *sets* and the section *count*, so two documents with the same apps in
a different section, in a different order, or with different section titles
produce an empty change list — the confirm dialog says "No visible changes"
while the import rearranges the home screen. The spec asks for "sections
changed" to be summarised. Compare the section membership and titles, not
just the count.

> The implementation compares the sections by their id. A section that is
> only in one of the two documents is an added or a removed one; a section
> that is in both is a *changed* one when its title, its membership or the
> order of its apps changed. The summary says so in words: "Adds section
> "Work"", "Removes section "Mine"", "Renames section "Mine" to "Yours"",
> "Section "Mine" loses p.a", "Section "Work" gains p.a" and "Reorders
> section "Mine"".

**6.2 An imported document must be stored at the current version.** The commit
is `onUpdate { import.document }`, so a v0 payload is stored with
`version = 0` forever, and a future v1→v2 migration would be skipped for it.
Commit `import.document.copy(version = LayoutDocument.CURRENT_VERSION)`.

> The commit goes through `importedDocument(document)`, which is that copy —
> it is a named function so the rule can be pinned by a test.

**6.3 `ImportValidator` must not swallow `Error`s.** It catches `Throwable`,
so a genuine bug inside the decoder is reported to the user as "That file is
not readable as a Liner layout." `DocumentCodec.decode` deliberately lets
`Error`s through so a bug does not look like a broken file — the validator
must match that contract and catch `Exception` instead. (The comment's
StackOverflowError justification does not hold: kotlinx-serialization-json
switches to a trampolined scope at a nesting depth of 200, so a deeply nested
payload does not overflow the stack.)

> The tab that calls the validator catches the `Error` itself and reports it
> as the unexpected failure it is — the launcher still must not crash, and
> the failure must not be shown as "not readable as a Liner layout".

**6.4 The default-home check must not run a binder call in composition.**
`DefaultHomeDetector.isDefaultHome(context)` does a `RoleManager.isRoleHeld`
(API 29+) or a `PackageManager.resolveActivity` round trip, evaluated inside
`remember { … }` on the main thread the first time the tab is composed.
Resolve it in a `LaunchedEffect` and start from an *unknown* state, so the
banner does not flash before the answer arrives.

> The answer is `Boolean?` — null means "not known yet" — and it is asked off
> the main thread. The banner is shown only when the answer is known *and* it
> is "not the default", so nothing flashes while the answer is on its way.
> The question is asked again on every resume, as before.

**6.5 The prune must not delete a disabled app.** `queryIntentActivities`
excludes disabled components, so an app the user has disabled is missing from
the list and its home entry is deleted when auto-prune is on. Query with
`PackageManager.MATCH_DISABLED_COMPONENTS` so a disabled app is still counted
as installed.

- [ ] **Step 7: Run the tests and build**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`, 4 import tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/model app/src/main/kotlin/dev/liner/launcher/store \
        app/src/main/kotlin/dev/liner/launcher/ui/settings app/src/test/kotlin/dev/liner/launcher/model
git commit -m "feat(settings): add import/export and advanced tabs"
```

---

## Task 15: Instrumentation Tests and Manual Test Script

**Files:**
- Create: `app/src/androidTest/kotlin/dev/liner/launcher/{HomeScreenTest,DrawerTest,DefaultHomeTest}.kt`
- Create: `MANUAL_TEST.md`

**Interfaces:**
- Consumes: everything built so far.
- Produces: instrumentation test sources (unverified here) and the manual test script.

- [ ] **Step 1: Write the home screen instrumentation test**

`app/src/androidTest/kotlin/dev/liner/launcher/HomeScreenTest.kt`:

```kotlin
package dev.liner.launcher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.store.InMemoryDocumentStore
import dev.liner.launcher.store.LayoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device or emulator. Verifies that the pieces that need a real
 * Android runtime behave: package visibility, icon loading and the launcher's
 * own HOME registration.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theLauncherIsRegisteredAsAHomeActivity() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)

        val resolved = context.packageManager.queryIntentActivities(intent, 0)

        assertTrue(
            "Liner must be registered for CATEGORY_HOME to act as a launcher",
            resolved.any { it.activityInfo?.packageName == context.packageName },
        )
    }

    @Test
    fun installedAppsCanBeEnumerated() {
        val apps = dev.liner.launcher.apps.InstalledAppsRepository(context).loadInstalledApps().apps

        // The test app itself is launchable, so the list is never empty.
        assertTrue(apps.isNotEmpty())
        assertTrue(apps.any { it.packageName == context.packageName })
    }

    @Test
    fun theDocumentRoundTripsOnDevice() {
        val document = LayoutDocument(
            sections = listOf(
                Section(id = "s1", title = "Daily", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
            ),
        )

        val decoded = DocumentCodec.decode(DocumentCodec.encode(document))

        assertEquals(document, decoded)
    }

    @Test
    fun theRepositoryPersistsAcrossInstances() = runBlocking {
        val store = InMemoryDocumentStore()
        val first = LayoutRepository(store)
        first.load()
        first.update { it.addSection("s1", "Daily") }
        first.awaitPendingWrite()

        val second = LayoutRepository(store)
        second.load()

        assertEquals(listOf("s1"), second.document.value.sections.map { it.id })
    }
}
```

- [ ] **Step 2: Write the drawer and default-home instrumentation tests**

`app/src/androidTest/kotlin/dev/liner/launcher/DrawerTest.kt`:

```kotlin
package dev.liner.launcher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.AppFilter
import dev.liner.launcher.model.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theDrawerListsEveryInstalledApp() {
        val installed = dev.liner.launcher.apps.InstalledAppsRepository(context).loadInstalledApps().apps

        val visible = AppCatalog.drawerApps(dev.liner.launcher.model.LayoutDocument(), installed)

        assertEquals(installed.size, visible.size)
    }

    @Test
    fun filteringNarrowsTheList() {
        val installed = dev.liner.launcher.apps.InstalledAppsRepository(context).loadInstalledApps().apps

        val result = AppFilter.filter(installed, "zzzz-no-such-app")

        assertTrue(result.isEmpty())
    }

    @Test
    fun iconsLoadWithoutThrowing() {
        val installed = dev.liner.launcher.apps.InstalledAppsRepository(context).loadInstalledApps().apps
        val cache = dev.liner.launcher.apps.IconCache(context.packageManager, 96)

        // Must not throw, whatever the icons look like.
        installed.forEach { cache.icon(it.packageName) }
    }
}
```

`app/src/androidTest/kotlin/dev/liner/launcher/DefaultHomeTest.kt`:

```kotlin
package dev.liner.launcher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.liner.launcher.platform.DefaultHomeDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultHomeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun detectingTheDefaultHomeNeverThrows() {
        // Whether Liner is the default depends on how the test app was
        // installed; the call must simply not crash.
        DefaultHomeDetector.isDefaultHome(context)
    }

    @Test
    fun requestingTheDefaultHomeNeverThrows() {
        DefaultHomeDetector.requestDefaultHome(context)
    }
}
```

- [ ] **Step 3: Write the manual test script**

`MANUAL_TEST.md` — a numbered script covering:

```markdown
# Liner — Manual Test Script

Run through these on a real device after installing `liner-release.apk`.
Tick each box as you go.

## 1. Install and set as default
- [ ] Install the APK and open Liner from the app drawer.
- [ ] The prompt "Liner is not your default launcher" appears. Tap "Set as default launcher".
- [ ] Press HOME. Liner appears.
- [ ] Open Settings → Advanced. The banner is gone.

## 2. Build a home screen
- [ ] Open Settings → Apps, check three apps. They appear on the home screen.
- [ ] Long press a label. It enters edit mode.
- [ ] Drag a label to a new position. It lands where the indicator showed.
- [ ] Tap a label in edit mode. Rename it to "Music". The home screen shows "Music".
- [ ] Drag a label to the trash zone. A snackbar offers undo. It disappears.

## 3. Sections
- [ ] Settings → Sections → Add section, name it "Daily".
- [ ] Move an app into "Daily" from its editor sheet.
- [ ] Long press the "DAILY" header. Rename it. The header updates.

## 4. Theming
- [ ] Settings → Appearance → set the text size to 48sp. The home screen scales.
- [ ] Switch the background to gradient, then to wallpaper.
- [ ] Turn icons on. Icons appear beside the labels.
- [ ] Set alignment to centre. The list centres.

## 5. Drawer
- [ ] Swipe up from the bottom edge. The drawer opens.
- [ ] Type "ma". The list filters to matching apps.
- [ ] Tap an app. It launches and the drawer closes.

## 6. Resilience
- [ ] Uninstall an app that is on the home screen. Its entry stays, greyed out.
- [ ] Tap it. A toast says it is not installed.
- [ ] Force stop Liner and reopen it. The layout is exactly as you left it.
- [ ] Export the layout to the clipboard, then import it. The layout is restored.

## 7. Failure handling
- [ ] Import garbage text from the clipboard. Liner shows an error and keeps your layout.
```

- [ ] **Step 4: Compile the instrumentation tests**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebugAndroidTest`

Expected: `BUILD SUCCESSFUL`. The tests compile but **cannot be executed here** — there is no emulator or device in this environment. Record that fact in the README.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest MANUAL_TEST.md
git commit -m "test: add instrumentation test sources and the manual test script"
```

---

## Task 16: Release Build, Signing, README and Delivery

**Files:**
- Create: `README.md`
- Create: `keystore.properties` (gitignored, generated)
- Create: `liner-release.jks` (gitignored, generated)
- Create: `dist/` output directory (gitignored)

**Interfaces:**
- Consumes: the complete app.
- Produces: `dist/liner-debug.apk`, `dist/liner-release.apk`, `dist/liner-source.zip`, `README.md`.

- [ ] **Step 1: Show the first-run prompt**

The spec's §6.4 is a first-run prompt, not the Advanced tab's banner:

> On first launch, if Liner is not the default home app, show a one-time
> prompt with a button that fires `Intent(Settings.ACTION_HOME_SETTINGS)`.
> Dismissible. The prompt does not reappear once Liner is the default home.

The Advanced tab's banner tells a user who already opened Settings that
Liner is not their default launcher. The first-run prompt is what a user
sees the first time they open Liner, and it goes away for good once they
dismiss it.

**The state lives in the document** — the app keeps no hidden state, so a
second store would be wrong:

```kotlin
@Serializable
data class LayoutDocument(
    ...
    /**
     * Whether the user has dismissed the first-run prompt. The prompt is
     * shown once, on the first launch, when Liner is not the default home
     * app — and it stays dismissed afterwards.
     *
     * It lives in the document rather than in a second store because the app
     * keeps no hidden state: everything the user can see is in the document.
     */
    val firstRunPromptDismissed: Boolean = false,
)
```

and `DocumentEdits.kt` gains:

```kotlin
/**
 * Records that the user has dismissed the first-run prompt, so it does not
 * come back.
 */
fun LayoutDocument.dismissFirstRunPrompt(): LayoutDocument =
    copy(firstRunPromptDismissed = true)
```

**The prompt itself** goes on the home screen, under the clock. It is shown
when all three hold: the document has been read, the user has not dismissed
it, and Liner is genuinely not the default home app.

The default-home check is a binder round trip, so it must NOT run in the
composition — resolve it in a `LaunchedEffect` on `Dispatchers.IO` and start
from an *unknown* state, exactly as the Advanced tab does:

```kotlin
// null means "not known yet"
var isDefaultHome by remember { mutableStateOf<Boolean?>(null) }
LaunchedEffect(Unit) {
    isDefaultHome = withContext(Dispatchers.IO) {
        DefaultHomeDetector.isDefaultHome(context)
    }
}
```

The prompt is a card with a *"Set as default"* button and a dismiss button:

```kotlin
if (showFirstRunPrompt) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Set Liner as your home app", style = MaterialTheme.typography.titleMedium)
            Text(
                "Liner is not your default launcher yet. Choose it to use it as your home screen.",
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { DefaultHomeDetector.requestDefaultHome(context) }) {
                    Text("Set as default")
                }
                TextButton(onClick = { viewModel.update { it.dismissFirstRunPrompt() } }) {
                    Text("Not now")
                }
            }
        }
    }
}
```

`DefaultHomeDetector.requestDefaultHome` fires the HOME role request on API
29+ and `Settings.ACTION_HOME_SETTINGS` below it, so the button is the right
one on every version.

**The prompt must not reappear once Liner IS the default home app**, so the
condition is `isDefaultHome == false` — not merely "not dismissed".

Add a unit test for the edit operation, and pin the condition itself in an
`internal` helper so it can be tested without a device:

```kotlin
internal fun shouldShowFirstRunPrompt(
    dismissed: Boolean,
    isDefaultHome: Boolean?,
): Boolean = !dismissed && isDefaultHome == false
```

- [ ] **Step 2: Generate the release keystore and harden the signing config**

```bash
cd /workspace
mkdir -p dist
keytool -genkeypair -v \
  -keystore liner-release.jks \
  -alias liner \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass linerelease -keypass linerelease \
  -dname "CN=Liner, OU=Launcher, O=Liner, L=, ST=, C=US"
```

Write `keystore.properties`:

```properties
storeFile=liner-release.jks
storePassword=linerelease
keyAlias=liner
keyPassword=linerelease
```

A hand-written `keystore.properties` missing a key currently fails with an
opaque configuration-time NPE. Harden the reads in `app/build.gradle.kts` so
each missing key produces a clear message:

```kotlin
    signingConfigs {
        if (keystorePropsFile.exists()) {
            fun required(key: String): String = keystoreProps.getProperty(key)
                ?: error("keystore.properties is missing '$key'")

            create("release") {
                storeFile = rootProject.file(required("storeFile"))
                storePassword = required("storePassword")
                keyAlias = required("keyAlias")
                keyPassword = required("keyPassword")
            }
        }
    }
```

- [ ] **Step 3: Generate the Gradle wrapper**

The README tells users to run `./gradlew`, so the wrapper must ship with the
source:

```bash
cd /workspace
/workspace/.toolchain/gradle-8.11.1/bin/gradle wrapper --gradle-version 8.11.1
```

Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and
`gradle/wrapper/gradle-wrapper.properties` now exist. The wrapper's
`distributionUrl` points at `services.gradle.org`, so the user needs network
access on first build — that is the normal Gradle wrapper behaviour.

Commit the wrapper files; they are part of the deliverable.

- [ ] **Step 4: Build both APKs**

```bash
cd /workspace
export ANDROID_HOME=/workspace/.toolchain/android-sdk GRADLE_USER_HOME=/workspace/.gradle-home
"$GRADLE_BIN" "${GRADLE_ARGS[@]}" clean :app:assembleDebug :app:assembleRelease :app:test
```

Expected: `BUILD SUCCESSFUL`, all unit tests green.

- [ ] **Step 5: Verify the signatures**

```bash
cd /workspace
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Verifies` for both, with the release certificate shown for the release APK.

- [ ] **Step 6: Copy the artifacts and package the source**

```bash
cd /workspace
cp app/build/outputs/apk/debug/app-debug.apk dist/liner-debug.apk
cp app/build/outputs/apk/release/app-release.apk dist/liner-release.apk
zip -r dist/liner-source.zip . \
  -x '.git/*' '.toolchain/*' '.gradle-home/*' 'build/*' '*/build/*' '.gradle/*' \
  'dist/*' '*.apk' '*.jks' 'keystore.properties' 'local.properties'
```

- [ ] **Step 7: Write the README**

`README.md`:

```markdown
# Liner

An Android launcher whose home screen is a list of words instead of icons.

Pick the apps you care about, arrange them into sections you name, and tune
every pixel of the result — fonts, sizes, colours, spacing, background,
alignment. Swipe up from the bottom edge for a drawer with every app on the
device.

## Install

1. Download `liner-release.apk`.
2. Copy it to your phone and tap it. Allow "install from unknown sources".
3. Open Liner, then follow the prompt to make it your default launcher.

Requires Android 8.0 (API 26) or newer.

## Build from source

```bash
cp local.properties.example local.properties   # then set sdk.dir
cp keystore.properties.example keystore.properties
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK with platform 35 and build-tools 35.0.0.

## What's in the box

| File | What it is |
|---|---|
| `liner-release.apk` | Signed release build, ready to install |
| `liner-debug.apk` | Debug build |
| `liner-source.zip` | The full Gradle project |

## Features

- A home screen that is one scrolling column of text — no icons, no grid
- Sections with names you choose, and per-item renames
- Drag to reorder, with a live insertion indicator
- A drawer of every installed app, with a filter field
- Full theming: background (solid / gradient / wallpaper), text colour, font
  family, size, weight, letter spacing, row spacing, padding, alignment,
  clock and date styles, section header styling, optional icons
- Per-item style overrides that beat the global theme
- Import and export your layout as JSON
- Handles uninstalled apps, corrupt saves and failed writes without crashing

## Testing

```bash
./gradlew test
```

The unit tests run headless. The instrumentation tests under
`app/src/androidTest` **were compiled but not executed** — the build
environment had no emulator or device. Run them on a device with
`./gradlew connectedDebugAndroidTest`.

`MANUAL_TEST.md` is a step-by-step script for checking the app on a real phone.

## Permissions

`QUERY_ALL_PACKAGES` is declared because a launcher must enumerate installed
apps in order to launch them. This is the standard exemption granted to
launchers and is covered by the Play Store policy for apps that register for
`CATEGORY_HOME`.

## License

MIT.
```

- [ ] **Step 6: Verify the deliverables exist**

```bash
cd /workspace
ls -la dist/
```

Expected: `liner-debug.apk`, `liner-release.apk`, `liner-source.zip` all present and non-empty.

- [ ] **Step 7: Commit**

```bash
git add README.md .gitignore
git commit -m "docs: add README and finalise delivery"
```

Note: `keystore.properties`, `liner-release.jks`, `dist/` and `*.apk` stay untracked — they are gitignored.

---

## Self-Review

**Spec coverage**

| Spec requirement | Task |
|---|---|
| Goals G1 (icon-free text list) | 9 |
| Goal G2 (full launcher + drawer) | 8, 9, 10 |
| Goal G3 (every visual property adjustable) | 12 |
| Goal G4 (structure editable) | 11, 13 |
| Goal G5 (layout is data) | 2, 3 |
| Goal G6 (survives failures) | 6, 7, 9, 14 |
| §5 data model | 2 |
| §5.1 edit operations | 3 |
| §5.2 style resolution | 4 |
| §6.1 home screen + gestures | 9, 11 |
| §6.1 drag model | 5, 11 |
| §6.2 drawer | 10 |
| §6.3 settings tabs | 12, 13, 14 |
| §6.4 first run | 12 (Advanced banner), 16 |
| §8 permissions and manifest | 1 |
| §9 error handling | 6, 7, 9, 14 |
| §10.1 unit tests | 2, 3, 4, 5, 6, 14 |
| §10.2 instrumentation tests | 15 |
| §10.3 manual test script | 15 |
| §11 build and delivery | 16 |

**Type consistency** — `moveItem`, `removeItem`, `renameItem`, `setItemStyle`, `addItem`,
`reorderHomeItems`, `addSection`, `renameSection`, `removeSection`, `moveSection`,
`setHiddenFromDrawer`, `setAutoPruneUninstalled`, `updateTheme` are defined once in
Task 3 and used unchanged in Tasks 9, 11, 13, 14. `StyleResolver.resolveItemStyle` /
`resolveSectionHeaderStyle` are defined in Task 4 and consumed in Tasks 9, 12.
`DragTargeting.computeTarget` is defined in Task 5 and consumed in Task 11.
`DocumentCodec` is created in `model` in Task 2, so `ImportValidator` (Task 14) can
use it without breaking the dependency rule and no later file move is needed.

**Defects found and fixed during self-review**

1. *Write ordering in `LayoutRepository`.* The first draft used `Mutex` plus
   `pendingWrite?.cancel()`, which could cancel a write already in flight and
   let an older snapshot land after a newer one. Replaced with an unbounded
   `Channel` consumed by a single coroutine — FIFO by construction, and no
   in-flight write is ever cancelled. `awaitPendingWrite()` awaits the most
   recent write's `CompletableDeferred`; because the channel is FIFO that also
   implies every earlier write completed.
2. *Test races.* Two tests read the store immediately after `update()`, before
   the asynchronous write landed. Both now call `awaitPendingWrite()` first.
3. *Wrong type name.* The Task 5 test referenced `DragTargetingTarget`; the
   sealed type is `DragTarget`. Corrected, and the clarifying note removed.
4. *Wrong package for `DocumentCodec`.* It was specced under `store`, but
   `ImportValidator` in `model` needs it, which would have violated the
   dependency rule and forced a late file move. Moved to `model` in Task 2.
5. *Non-existent API.* The Task 9 `ClockDateHeader` called
   `android.content.ContextHolder.app`, which does not exist. Replaced with
   `LocalContext.current` + `DateFormat.is24HourFormat(context)`, and the
   follow-up correction note removed.
6. *Unused parameter.* `LabelRow` accepted an `alignment` but ignored it, so
   the centre/end alignment setting would have had no visible effect. The row
   now uses `Arrangement.spacedBy(10.dp, alignment)`.
7. *Undefined helper.* The Task 10 drawer referenced a non-existent `iconFor`
   composable. Replaced with a direct cached lookup taking `IconCache`.
8. *Stub instrumentation tests.* Task 15's three test files had empty bodies.
   Replaced with real assertions covering HOME registration, package
   enumeration, document round-trip, repository persistence, drawer listing and
   icon loading — all runnable on a device.
9. *Dead code path.* The Apps tab ignored a check when no section existed.
   It now creates a default section on demand.

**Placeholder scan** — no TBD, TODO, "implement later", or "similar to Task N"
steps remain. Every code step contains the code to write.

**Scope** — one coherent app, one plan. No subsystem needs to be split out.
