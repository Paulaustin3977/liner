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
- The `ui` layer never reads raw theme tokens; it consumes `StyleResolver` output only.
- **Only `material-icons-core` is available** — not `material-icons-extended`. The full extended set adds ~37 MB to the APK for icons this app never uses. Use a core icon (`Icons.Default.Settings` is in core) or a text label instead.
- No crashes on: missing app, failed icon load, failed DataStore write, corrupt JSON, `PackageManager` throwing.
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
  - `data class LayoutDocument(version: Int = 1, sections: List<Section> = emptyList(), theme: ThemeTokens = ThemeTokens(), hiddenFromDrawer: List<String> = emptyList(), autoPruneUninstalled: Boolean = false)`
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
  - `data class HomeEntry(item: HomeItem, displayLabel: String, available: Boolean, sectionId: String, sectionTitle: String?)`
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
    val sectionTitle: String?,
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
                sectionTitle = section.title,
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
    fun `dragging above the first item of a section targets index zero`() {
        val target = DragTargeting.computeTarget(95f, bounds, "a", sectionOrder)

        assertEquals(DragTargetTarget.InSection("s1", 0), target)
    }

    @Test
    fun `dragging past the midpoint of an item moves below it`() {
        val target = DragTargeting.computeTarget(180f, bounds, "a", sectionOrder)

        assertEquals(DragTarget.InSection("s1", 1), target)
    }

    @Test
    fun `the dragged item is excluded from the index computation`() {
        // "b" is being dragged; "a" and "c" are the only other items.
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

    /** Move into [sectionId], which is the section immediately before the dragged item's. */
    data class PreviousSection(val sectionId: String) : DragTarget
}

object DragTargeting {
    /**
     * Works out where the dragged item would land if released at [dragY].
     *
     * [bounds] must be the ordered list of on-screen item rectangles for the
     * current screen, and [sectionOrder] the document's section ids in order.
     * Returns null when the dragged item is not in [bounds].
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

        val siblings = bounds.filter { it.sectionId == dragged.sectionId && it.id != draggedId }

        val firstTop = siblings.minOfOrNull { it.top }
        if (firstTop != null && dragY < firstTop && previousSectionId != null) {
            return DragTarget.PreviousSection(previousSectionId)
        }

        var index = 0
        for (bound in siblings) {
            if (dragY > (bound.top + bound.bottom) / 2f) index++ else break
        }

        return DragTarget.InSection(dragged.sectionId, index)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*DragTargetingTest*'`

Expected: `BUILD SUCCESSFUL`, 6 tests, 0 failures.

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
  - `class LayoutRepository(store: DocumentStore)` with `val document: StateFlow<LayoutDocument>`, `val writeFailed: StateFlow<Boolean>`, `suspend fun load()`, `fun update(transform: (LayoutDocument) -> LayoutDocument)`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/dev/liner/launcher/store/LayoutRepositoryTest.kt`:

```kotlin
package dev.liner.launcher.store

import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.addSection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
```

> `awaitPendingWrite()` is a test-visible suspend function that awaits the
> most recent write. Because writes are consumed from a FIFO channel by a
> single coroutine, awaiting the latest write also implies every earlier
> write has completed. Add it to `LayoutRepository` as
> `internal suspend fun awaitPendingWrite()`.

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The single writer for the layout document.
 *
 * Every mutation goes through [update], which updates the in-memory state
 * immediately and persists it asynchronously. Writes are funnelled through one
 * unbounded channel consumed by a single coroutine, so two rapid edits can
 * never land out of order.
 */
class LayoutRepository(private val store: DocumentStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class PendingWrite(
        val document: LayoutDocument,
        val done: CompletableDeferred<Unit>,
    )

    private val writes = Channel<PendingWrite>(Channel.UNLIMITED)
    private val latestWrite = AtomicReference<CompletableDeferred<Unit>?>(null)

    private val _document = MutableStateFlow(LayoutDocument())
    val document: StateFlow<LayoutDocument> = _document.asStateFlow()

    private val _writeFailed = MutableStateFlow(false)
    val writeFailed: StateFlow<Boolean> = _writeFailed.asStateFlow()

    init {
        scope.launch {
            for (pending in writes) {
                try {
                    store.write(DocumentCodec.encode(pending.document))
                    _writeFailed.value = false
                } catch (_: Throwable) {
                    _writeFailed.value = true
                } finally {
                    pending.done.complete(Unit)
                }
            }
        }
    }

    /** Reads the stored document. Falls back to defaults when it cannot be read. */
    suspend fun load() {
        val raw = try {
            store.read()
        } catch (_: Throwable) {
            null
        }
        _document.value = try {
            raw?.let { DocumentCodec.decode(it) } ?: LayoutDocument()
        } catch (_: Exception) {
            LayoutDocument()
        }
    }

    fun update(transform: (LayoutDocument) -> LayoutDocument) {
        val next = transform(_document.value)
        _document.value = next

        val done = CompletableDeferred<Unit>()
        latestWrite.set(done)
        writes.trySend(PendingWrite(next, done))
    }

    /** Test hook: waits for the most recent write to settle. */
    internal suspend fun awaitPendingWrite() {
        latestWrite.get()?.await()
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

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
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

Expected: `BUILD SUCCESSFUL`, 6 tests, 0 failures.

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

**Interfaces:**
- Consumes: Task 2's `InstalledApp`, Task 4's `AppCatalog`.
- Produces:
  - `class InstalledAppsRepository(context: Context)` with `fun loadInstalledApps(): List<InstalledApp>`
  - `class IconCache(packageManager: PackageManager, sizePx: Int)` with `fun icon(packageName: String): ImageBitmap?`
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

- [ ] **Step 2: Run the test to verify it fails**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*AppCatalogIntegrationTest*'`

Expected: compilation failure — `Unresolved reference: InstalledAppsRepository` is *not* expected here, but the test file references `AppCatalog` which does exist. **If this test passes immediately, that is fine and expected** — it exercises Task 4 code. The genuinely new work in this task is the Android adapter, which has no JVM test. Change Step 2's expectation to: the test passes immediately (2 tests, 0 failures), confirming the catalog contract that the adapter depends on.

- [ ] **Step 3: Implement the installed apps repository**

`apps/InstalledAppsRepository.kt`:

```kotlin
package dev.liner.launcher.apps

import android.content.Context
import android.content.Intent
import dev.liner.launcher.model.InstalledApp

/**
 * Reads the launchable apps installed on the device.
 *
 * Never throws: a failed query returns an empty list so the drawer degrades
 * to "no apps" instead of crashing the launcher.
 */
class InstalledAppsRepository(context: Context) {

    private val packageManager = context.applicationContext.packageManager

    fun loadInstalledApps(): List<InstalledApp> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { resolved ->
                val info = resolved.activityInfo ?: return@mapNotNull null
                InstalledApp(
                    packageName = info.packageName,
                    activityClass = info.name,
                    systemLabel = resolved.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy { it.packageName to it.activityClass }
    } catch (_: Throwable) {
        emptyList()
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
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Caches app icons rasterised to a bitmap of a fixed size.
 * A failed load returns null rather than throwing, so the caller can render
 * the app label instead.
 */
class IconCache(
    private val packageManager: PackageManager,
    private val sizePx: Int,
) {
    private val cache = LruCache<String, ImageBitmap>(64)

    fun icon(packageName: String): ImageBitmap? {
        val key = "$packageName@$sizePx"
        cache.get(key)?.let { return it }

        val bitmap = try {
            packageManager.getApplicationIcon(packageName).toImageBitmap()
        } catch (_: Throwable) {
            null
        }

        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private fun Drawable.toImageBitmap(): ImageBitmap? {
        val width = if (intrinsicWidth > 0) intrinsicWidth else sizePx
        val height = if (intrinsicHeight > 0) intrinsicHeight else sizePx
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, width, height)
        draw(canvas)
        return bitmap.asImageBitmap()
    }
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
    private val appContext = context.applicationContext

    val repository: LayoutRepository = LayoutRepository(DataStoreDocumentStore(appContext))
    val appsRepository: InstalledAppsRepository = InstalledAppsRepository(appContext)
    val iconCache: IconCache = IconCache(appContext.packageManager, sizePx = 96)
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
        assertEquals(FontFamily.SansSerif, fontFamilyFor("sans-serif-light"))
        assertEquals(FontFamily.SansSerif, fontFamilyFor("sans-serif-medium"))
        assertEquals(FontFamily.SansSerif, fontFamilyFor("sans-serif-condensed"))
        assertEquals(FontFamily.Serif, fontFamilyFor("serif"))
        assertEquals(FontFamily.Monospace, fontFamilyFor("monospace"))
        assertEquals(FontFamily.Cursive, fontFamilyFor("cursive"))
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

/** The font families a user can pick from in Settings → Appearance. */
val FONT_KEYS = listOf(
    "sans-serif",
    "sans-serif-light",
    "sans-serif-medium",
    "sans-serif-condensed",
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
            background = Color(0xFF101014),
            surface = Color(0xFF101014),
            onBackground = Color(0xFFF5F5F7),
            onSurface = Color(0xFFF5F5F7),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1A1A1E),
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

import android.content.Context
import android.content.Intent
import dev.liner.launcher.model.InstalledApp

/** Starts an installed app. Returns false when the app cannot be started. */
class AppLauncher(context: Context) {

    private val appContext = context.applicationContext

    fun launch(app: InstalledApp): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(android.content.ComponentName(app.packageName, app.activityClass))
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
import android.os.Build
import android.provider.Settings

object DefaultHomeDetector {

    /** True when Liner is currently the resolved home app. */
    fun isDefaultHome(context: Context): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        resolved.any { it.activityInfo?.packageName == context.packageName }
    } catch (_: Throwable) {
        false
    }

    /** Fires the system flow that lets the user make Liner the default home app. */
    fun requestDefaultHome(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                runCatching {
                    context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                }.onFailure { openHomeSettings(context) }
                return
            }
        }
        openHomeSettings(context)
    }

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
import android.graphics.drawable.Drawable

object WallpaperProvider {
    /** Returns the system wallpaper, or null when it cannot be read. Never throws. */
    fun load(context: Context): Drawable? = try {
        WallpaperManager.getInstance(context).getDrawable()
    } catch (_: Throwable) {
        try {
            WallpaperManager.getInstance(context).getDrawable(WallpaperManager.FLAG_SYSTEM)
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

Expected: `BUILD SUCCESSFUL`, 4 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/platform app/src/test/kotlin/dev/liner/launcher/platform
git commit -m "feat(platform): add app launching, default-home detection, wallpaper and theme"
```

---

## Task 9: Home Screen Rendering

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/home/{HomeScreen,HomeViewModel}.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/common/{LabelRow,SectionHeaderRow}.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/common/BackgroundLayer.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/platform/LauncherActivity.kt`

**Interfaces:**
- Consumes: Task 4's `AppCatalog`/`StyleResolver`/`ClockFormatter`, Task 6's `LayoutRepository`, Task 8's `LinerTheme`.
- Produces:
  - `class HomeViewModel(repository: LayoutRepository, appsRepository: InstalledAppsRepository) : ViewModel` with `val document: StateFlow<LayoutDocument>`, `val installedApps: StateFlow<List<InstalledApp>>`, `val homeEntries: StateFlow<List<HomeEntry>>`, `val writeFailed: StateFlow<Boolean>`, `fun update(transform: (LayoutDocument) -> LayoutDocument)`
  - `@Composable fun HomeScreen(viewModel: HomeViewModel, onLaunch: (InstalledApp) -> Unit, onOpenSettings: (String?) -> Unit)`
  - `@Composable fun BackgroundLayer(spec: BackgroundSpec, wallpaper: Drawable?)`
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

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest --tests '*HomeViewModelStateTest*'`

Expected: `BUILD SUCCESSFUL`, 2 tests, 0 failures. This test pins the contract that the view model will use.

- [ ] **Step 3: Implement the background layer**

`ui/common/BackgroundLayer.kt`:

```kotlin
package dev.liner.launcher.ui.common

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.ColorSpec

@Composable
fun BackgroundLayer(
    spec: BackgroundSpec,
    wallpaper: Drawable?,
    systemTextColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (spec) {
            is BackgroundSpec.Solid -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(spec.color.toColor(systemTextColor)),
                )
            }

            is BackgroundSpec.Gradient -> {
                val from = spec.from.toColor(systemTextColor)
                val to = spec.to.toColor(systemTextColor)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(from, to),
                                start = androidx.compose.ui.geometry.Offset.Zero,
                                end = androidx.compose.ui.geometry.Offset.Infinite,
                            ),
                        ),
                )
            }

            is BackgroundSpec.Wallpaper -> {
                if (wallpaper != null) {
                    Image(
                        bitmap = androidx.compose.ui.graphics.asImageBitmap(
                            wallpaper.toBitmap(width = 1080, height = 1920),
                        ),
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

/** Maps a [ColorSpec] to a concrete colour, resolving "follow system" to [fallback]. */
fun ColorSpec.toColor(fallback: Color): Color = when (this) {
    is ColorSpec.Solid -> Color(argb)
    ColorSpec.FollowSystem -> fallback
}

private fun Drawable.toBitmap(width: Int, height: Int): android.graphics.Bitmap {
    val w = if (intrinsicWidth > 0) intrinsicWidth else width
    val h = if (intrinsicHeight > 0) intrinsicHeight else height
    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bitmap
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: LayoutRepository,
    private val appsRepository: InstalledAppsRepository,
) : ViewModel() {

    val document: StateFlow<LayoutDocument> = repository.document

    val writeFailed: StateFlow<Boolean> = repository.writeFailed

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    val homeEntries: StateFlow<List<HomeEntry>> =
        combine(document, _installedApps) { doc, apps ->
            AppCatalog.homeEntries(doc, apps.associateBy { it.packageName })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { repository.load() }
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = appsRepository.loadInstalledApps()
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

import android.graphics.drawable.Drawable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    wallpaper: Drawable?,
    iconCache: IconCache,
    onLaunch: (HomeEntry) -> Unit,
    onOpenSettings: (String?) -> Unit,
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val entries by viewModel.homeEntries.collectAsStateWithLifecycle()
    val theme = document.theme
    val systemTextColor = MaterialTheme.colorScheme.onBackground

    val horizontalAlignment = when (theme.alignment) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }

    Box(Modifier.fillMaxSize()) {
        BackgroundLayer(spec = theme.background, wallpaper = wallpaper, systemTextColor = systemTextColor)

        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = theme.screenPaddingHdp.dp,
                    vertical = theme.screenPaddingVdp.dp,
                ),
        ) {
            ClockDateHeader(theme)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(theme.itemSpacingDp.dp),
            ) {
                document.sections.forEach { section ->
                    if (section.title != null) {
                        item(key = "header-${section.id}") {
                            SectionHeaderRow(
                                title = section.title,
                                style = StyleResolver.resolveSectionHeaderStyle(theme),
                                uppercase = theme.sectionTitleUppercase,
                                showRule = theme.sectionTitleRule,
                            )
                        }
                    }
                    items(
                        items = entries.filter { it.sectionId == section.id },
                        key = { it.item.id },
                    ) { entry ->
                        // Icons are opt-in: the home screen is icon-free by default.
                        val icon = if (theme.showIcons) {
                            remember(entry.item.id) {
                                iconCache.icon(entry.item.packageName)
                            }
                        } else {
                            null
                        }
                        LabelRow(
                            text = entry.displayLabel,
                            style = StyleResolver.resolveItemStyle(theme, entry.item.style),
                            alignment = horizontalAlignment,
                            icon = icon,
                            iconSizeDp = theme.iconSizeDp,
                            enabled = entry.available,
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
private fun ClockDateHeader(theme: ThemeTokens) {
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
            style = TextStyle(fontSize = clockSize.sp, fontWeight = FontWeight.Light),
        )
        val date = ClockFormatter.formatDate(now, zone, theme.dateStyle)
        if (date != null) {
            Text(text = date, style = TextStyle(fontSize = dateSize.sp))
        }
        Spacer(Modifier.height(16.dp))
    }
}
```

- [ ] **Step 7: Wire the activity**

Replace `platform/LauncherActivity.kt` with:

```kotlin
package dev.liner.launcher.platform

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.liner.launcher.ui.home.HomeScreen
import dev.liner.launcher.ui.home.HomeViewModel

class LauncherActivity : ComponentActivity() {

    private val container by lazy { (application as LinerApp).container }

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(container.repository, container.appsRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val wallpaper = WallpaperProvider.load(this)

        setContent {
            LinerTheme {
                HomeScreen(
                    viewModel = viewModel,
                    wallpaper = wallpaper,
                    iconCache = container.iconCache,
                    onLaunch = { entry -> launchEntry(entry.packageName) },
                    onOpenSettings = { tab -> openSettings(tab) },
                )
            }
        }
    }

    private fun launchEntry(packageName: String) {
        val app = viewModel.installedApps.value.firstOrNull { it.packageName == packageName } ?: return
        AppLauncher(this).launch(app)
    }

    private fun openSettings(tab: String?) {
        startActivity(SettingsActivity.intent(this, tab))
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

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.model.InstalledApp

@Composable
fun DrawerSheet(
    visibleApps: List<InstalledApp>,
    query: String,
    style: String,
    iconCache: IconCache,
    onQueryChange: (String) -> Unit,
    onLaunch: (InstalledApp) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clickable(enabled = false) {},
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text("Filter apps") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (visibleApps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No apps match", fontSize = 16.sp)
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
                            Text(app.systemLabel, fontSize = 18.sp)
                        }
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(4)) {
                    items(visibleApps, key = { it.packageName + it.activityClass }) { app ->
                        Column(
                            Modifier
                                .padding(6.dp)
                                .clickable { onLaunch(app) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val icon = remember(app.packageName, app.activityClass) {
                                iconCache.icon(app.packageName)
                            }
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                            Text(
                                text = app.systemLabel,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp),
                            )
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
                        androidx.compose.material.icons.Icons.Default.Settings,
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
    val drawerViewModel = androidx.compose.runtime.remember { DrawerViewModel(viewModel.document, viewModel.installedApps) }
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

- [ ] **Step 7: Build and verify**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebug :app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/ui app/src/main/kotlin/dev/liner/launcher/platform \
        app/src/test/kotlin/dev/liner/launcher/model
git commit -m "feat(ui): add the app drawer with filter, grid and text modes"
```

---

## Task 11: Edit Mode — Reorder, Rename, Remove, Section Editing

**Files:**
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/home/EditModeController.kt`
- Create: `app/src/main/kotlin/dev/liner/launcher/ui/common/EditSheets.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/home/HomeScreen.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/DragTargetingTest.kt` (already covers the maths)

**Interfaces:**
- Consumes: Task 5's `DragTargeting`/`DragTarget`, Task 3's edit operations.
- Produces:
  - `class EditModeController` with `var active: Boolean`, `var draggingId: String?`, `var dragTarget: DragTarget?`, `val bounds: MutableMap<String, ItemBounds>`, `fun startDrag(id: String)`, `fun updateDrag(dragY: Float, sectionOrder: List<String>)`, `fun endDrag(): DragTarget?`, `fun clear()`
  - `@Composable fun ItemEditorSheet(entry: HomeEntry, onRename: (String?) -> Unit, onMoveToSection: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit)`
  - `@Composable fun SectionEditorSheet(title: String?, onRename: (String?) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit)`

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
    var label by remember(entry.item.id) { mutableStateOf(entry.item.label ?: entry.displayLabel) }

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
                if (sections.size > 1) {
                    Text(
                        text = "Move to section",
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    sections.forEach { (id, title) ->
                        TextButton(onClick = { onMoveToSection(id) }) {
                            Text(title ?: "Untitled")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(label); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onRemove(); onDismiss() }) { Text("Remove") }
        },
    )
}

@Composable
fun SectionEditorSheet(
    title: String?,
    onRename: (String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(title ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Section") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(value); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete") }
        },
    )
}
```

- [ ] **Step 3: Wire edit mode into the home screen**

In `HomeScreen.kt`:

1. Create `val editController = remember { EditModeController() }`.
2. Track the dragging item's absolute Y with a `var dragY by remember { mutableFloatStateOf(0f) }`.
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
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            editController.startDrag(entry.item.id)
            dragY = entryTop + offset.y
        },
        onDrag = { change, dragAmount ->
            change.consume()
            dragY += dragAmount
            editController.updateDrag(dragY, document.sections.map { it.id })
        },
        onDragEnd = {
            when (val target = editController.endDrag()) {
                is DragTarget.InSection -> viewModel.update { doc ->
                    doc.moveItem(entry.item.id, target.sectionId, target.index)
                }
                is DragTarget.PreviousSection -> viewModel.update { doc ->
                    doc.moveItem(entry.item.id, target.sectionId, doc.sectionById(target.sectionId)?.items?.size ?: 0)
                }
                null -> Unit
            }
        },
        onDragCancel = { editController.endDrag() },
    )
}
```

5. A long press on the row (without moving) opens the editor sheet:

```kotlin
.combinedClickable(
    onClick = { if (editController.active) Unit else onLaunch(entry) },
    onLongClick = { editingEntry = entry },
)
```

`combinedClickable` is `androidx.compose.foundation.ExperimentalFoundationApi`; annotate
the composable that uses it with `@OptIn(ExperimentalFoundationApi::class)`.

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
if (overTrash) {
    editController.dragTarget = null
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

`dragX` is tracked alongside `dragY`, updated in `onDrag` as `dragX += dragAmount.x`.

8. Render the insertion indicator. A 2dp line that sits at the slot the item
   would land in:

```kotlin
val target = editController.dragTarget
if (target != null) {
    val indicatorTop = remember(target, editController.bounds) {
        when (target) {
            is DragTarget.InSection -> {
                val siblings = editController.bounds.values
                    .filter { it.sectionId == target.sectionId }
                    .sortedBy { it.top }
                if (target.index < siblings.size) siblings[target.index].top
                else siblings.lastOrNull()?.bottom ?: 0f
            }

            is DragTarget.PreviousSection -> {
                editController.bounds.values
                    .filter { it.sectionId == target.sectionId }
                    .minOfOrNull { it.top } ?: 0f
            }
        }
    }

    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(0, indicatorTop.toInt()) }
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(2.dp)
            .background(Color.White),
    )
}
```

The indicator is drawn inside the `Box` that wraps the list, positioned with
`offset` so it floats over the rows.

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

**Interfaces:**
- Consumes: Task 3's `updateTheme`, Task 8's `FONT_KEYS`.
- Produces:
  - `@Composable fun SettingsScreen(viewModel: SettingsViewModel, initialTab: String?)`
  - `class SettingsViewModel(repository: LayoutRepository, appsRepository: InstalledAppsRepository) : ViewModel` with `val document: StateFlow<LayoutDocument>`, `val installedApps: StateFlow<List<InstalledApp>>`, `fun update(transform: (LayoutDocument) -> LayoutDocument)`
  - `@Composable fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onValueChange: (Float) -> Unit)`
  - `@Composable fun ChoiceRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit)`
  - `@Composable fun ColorOptionRow(label: String, selected: ColorSpec, onSelect: (ColorSpec) -> Unit)`

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
import androidx.compose.ui.unit.dp

@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("$label: ${formatValue(value)}")
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

private fun formatValue(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else String.format("%.2f", value)
```

`ui/common/ChoiceRow.kt`:

```kotlin
package dev.liner.launcher.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.liner.launcher.model.ColorSpec

private val PALETTE = listOf(
    0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF888888.toInt(), 0xFFCCCCCC.toInt(),
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFF6D4C41.toInt(),
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
                        width = if (selected is ColorSpec.Solid && selected.argb == argb) 3.dp else 1.dp,
                        color = Color.White,
                    )
                    .clickable { onSelect(ColorSpec.Solid(argb)) },
            )
        }
        FilterChipText("Follow system", selected is ColorSpec.FollowSystem) {
            onSelect(ColorSpec.FollowSystem)
        }
    }
}

@Composable
private fun FilterChipText(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}
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
            selected = when (theme.background) {
                is BackgroundSpec.Solid -> "solid"
                is BackgroundSpec.Gradient -> "gradient"
                is BackgroundSpec.Wallpaper -> "wallpaper"
            },
            onSelect = { style ->
                onThemeChange { current ->
                    when (style) {
                        "solid" -> current.copy(background = BackgroundSpec.Solid(ColorSpec.Solid(0xFF101014.toInt())))
                        "gradient" -> current.copy(
                            background = BackgroundSpec.Gradient(
                                from = ColorSpec.Solid(0xFF101014.toInt()),
                                to = ColorSpec.Solid(0xFF2A2A32.toInt()),
                            ),
                        )
                        else -> current.copy(background = BackgroundSpec.Wallpaper(0.35f))
                    }
                }
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
                    onSelect = { spec -> onThemeChange { it.copy(background = background.copy(from = spec)) } },
                )
                ColorOptionRow(
                    label = "To",
                    selected = background.to,
                    onSelect = { spec -> onThemeChange { it.copy(background = background.copy(to = spec)) } },
                )
                SliderRow(
                    label = "Angle",
                    value = background.angleDegrees.toFloat(),
                    range = 0f..360f,
                    onValueChange = { angle -> onThemeChange { it.copy(background = background.copy(angleDegrees = angle.toInt())) } },
                )
            }

            is BackgroundSpec.Wallpaper -> SliderRow(
                label = "Dim",
                value = background.dim,
                range = 0f..0.9f,
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
            onValueChange = { size -> onThemeChange { it.copy(listTextSizeSp = size) } },
        )

        ChoiceRow(
            label = "Weight",
            options = listOf("300", "400", "500", "700", "900"),
            selected = theme.listFontWeight,
            onSelect = { weight -> onThemeChange { it.copy(listFontWeight = weight) } },
        )

        SliderRow(
            label = "Letter spacing",
            value = theme.letterSpacingEm,
            range = -0.05f..0.2f,
            onValueChange = { spacing -> onThemeChange { it.copy(letterSpacingEm = spacing) } },
        )

        SliderRow(
            label = "Row spacing",
            value = theme.itemSpacingDp,
            range = 0f..32f,
            onValueChange = { spacing -> onThemeChange { it.copy(itemSpacingDp = spacing) } },
        )

        SliderRow(
            label = "Side padding",
            value = theme.screenPaddingHdp,
            range = 0f..48f,
            onValueChange = { padding -> onThemeChange { it.copy(screenPaddingHdp = padding) } },
        )

        SliderRow(
            label = "Top and bottom padding",
            value = theme.screenPaddingVdp,
            range = 0f..48f,
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
            onValueChange = { size -> onThemeChange { it.copy(iconSizeDp = size.toInt()) } },
        )

        ChoiceRow(
            label = "Drawer style",
            options = listOf("icons", "text"),
            selected = theme.drawerStyle,
            onSelect = { style -> onThemeChange { it.copy(drawerStyle = style) } },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        androidx.compose.material3.TextButton(
            onClick = { onThemeChange { ThemeTokens() } },
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Text("Reset appearance")
        }
    }
}
```

- [ ] **Step 3: Implement the settings scaffold and view model**

`ui/settings/SettingsScreen.kt`:

```kotlin
package dev.liner.launcher.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liner.launcher.model.InstalledApp
import dev.liner.launcher.model.LayoutDocument

private val TABS = listOf("Appearance", "Apps", "Sections", "Import / Export", "Advanced")
private val TAB_KEYS = listOf("appearance", "apps", "sections", "importexport", "advanced")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    document: LayoutDocument,
    installedApps: List<InstalledApp>,
    initialTab: String?,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    val startIndex = remember(initialTab) { TAB_KEYS.indexOf(initialTab).coerceAtLeast(0) }
    var selectedTab by rememberSaveable { mutableIntStateOf(startIndex) }

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

class SettingsViewModel(
    private val repository: LayoutRepository,
    private val appsRepository: InstalledAppsRepository,
) : ViewModel() {

    val document: StateFlow<LayoutDocument> = repository.document

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    init {
        viewModelScope.launch { repository.load() }
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = appsRepository.loadInstalledApps()
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
        setContent {
            LinerTheme {
                val document by viewModel.document.collectAsStateWithLifecycle()
                val apps by viewModel.installedApps.collectAsStateWithLifecycle()

                SettingsScreen(
                    document = document,
                    installedApps = apps,
                    initialTab = intent.getStringExtra(EXTRA_TAB),
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

@Composable
fun AppsTab(
    document: dev.liner.launcher.model.LayoutDocument,
    installedApps: List<dev.liner.launcher.model.InstalledApp>,
    onUpdate: ((dev.liner.launcher.model.LayoutDocument) -> dev.liner.launcher.model.LayoutDocument) -> Unit,
) {
    Text("Apps")
}
```

Repeat for the other three with the same signature shape they will keep in Tasks 13–14 (`SectionsTab(document, onUpdate)`, `ImportExportTab(document, onUpdate)`, `AdvancedTab(document, onUpdate)`).

- [ ] **Step 6: Build and verify**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebug :app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/liner/launcher/ui/settings app/src/main/kotlin/dev/liner/launcher/ui/common \
        app/src/main/kotlin/dev/liner/launcher/platform
git commit -m "feat(settings): add settings scaffold and the appearance tab"
```

---

## Task 13: Settings — Apps and Sections Tabs

**Files:**
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/settings/AppsTab.kt`
- Modify: `app/src/main/kotlin/dev/liner/launcher/ui/settings/SectionsTab.kt`
- Test: `app/src/test/kotlin/dev/liner/launcher/model/DocumentEditsTest.kt` (extended)

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

- [ ] **Step 5: Build and verify**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:assembleDebug :app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

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
        val decoded = DocumentCodec.decode(raw) ?: return ImportResult(
            document = null,
            error = "That file is not readable as a Liner layout.",
            summary = emptyList(),
        )

        return ImportResult(document = decoded, error = null, summary = summarise(current, decoded))
    }

    private fun summarise(current: LayoutDocument, incoming: LayoutDocument): List<String> {
        val changes = mutableListOf<String>()

        val currentPackages = current.sections.flatMap { it.items }.map { it.packageName }.toSet()
        val incomingPackages = incoming.sections.flatMap { it.items }.map { it.packageName }.toSet()

        val added = incomingPackages - currentPackages
        val removed = currentPackages - incomingPackages
        if (added.isNotEmpty()) changes += "Adds apps: ${added.joinToString()}"
        if (removed.isNotEmpty()) changes += "Removes apps: ${removed.joinToString()}"
        if (current.sections.size != incoming.sections.size) {
            changes += "Sections: ${current.sections.size} becomes ${incoming.sections.size}"
        }
        if (current.theme != incoming.theme) changes += "Replaces the theme"

        if (changes.isEmpty()) changes += "No visible changes"
        return changes
    }
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
                    onUpdate { result.document }
                }
            },
        ) { Text("Import from clipboard") }

        Text(
            text = "Importing replaces your current layout. Export first if you want a backup.",
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

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

import android.content.Intent
import android.provider.Settings
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
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.setAutoPruneUninstalled
import dev.liner.launcher.platform.DefaultHomeDetector

@Composable
fun AdvancedTab(
    document: LayoutDocument,
    onUpdate: ((LayoutDocument) -> LayoutDocument) -> Unit,
) {
    val context = LocalContext.current
    val isDefault = DefaultHomeDetector.isDefaultHome(context)

    Column(Modifier.fillMaxWidth()) {
        if (!isDefault) {
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
```

- [ ] **Step 6: Run the tests and build**

Run: `"$GRADLE_BIN" "${GRADLE_ARGS[@]}" :app:testDebugUnitTest :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`, 4 import tests pass.

- [ ] **Step 7: Commit**

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
        val apps = dev.liner.launcher.apps.InstalledAppsRepository(context).loadInstalledApps()

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
        val installed = dev.liner.launcher.apps.InstalledAppsRepository(context).loadInstalledApps()

        val visible = AppCatalog.drawerApps(dev.liner.launcher.model.LayoutDocument(), installed)

        assertEquals(installed.size, visible.size)
    }

    @Test
    fun filteringNarrowsTheList() {
        val installed = dev.liner.launcher.apps.InstalledAppsRepository(context).loadInstalledApps()

        val result = AppFilter.filter(installed, "zzzz-no-such-app")

        assertTrue(result.isEmpty())
    }

    @Test
    fun iconsLoadWithoutThrowing() {
        val installed = dev.liner.launcher.apps.InstalledAppsRepository(context).loadInstalledApps()
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

- [ ] **Step 1: Generate the release keystore and harden the signing config**

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

- [ ] **Step 1b: Generate the Gradle wrapper**

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

- [ ] **Step 2: Build both APKs**

```bash
cd /workspace
export ANDROID_HOME=/workspace/.toolchain/android-sdk GRADLE_USER_HOME=/workspace/.gradle-home
"$GRADLE_BIN" "${GRADLE_ARGS[@]}" clean :app:assembleDebug :app:assembleRelease :app:test
```

Expected: `BUILD SUCCESSFUL`, all unit tests green.

- [ ] **Step 3: Verify the signatures**

```bash
cd /workspace
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Verifies` for both, with the release certificate shown for the release APK.

- [ ] **Step 4: Copy the artifacts and package the source**

```bash
cd /workspace
cp app/build/outputs/apk/debug/app-debug.apk dist/liner-debug.apk
cp app/build/outputs/apk/release/app-release.apk dist/liner-release.apk
zip -r dist/liner-source.zip . \
  -x '.git/*' '.toolchain/*' '.gradle-home/*' 'build/*' '*/build/*' '.gradle/*' \
  'dist/*' '*.apk' '*.jks' 'keystore.properties' 'local.properties'
```

- [ ] **Step 5: Write the README**

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
