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
import dev.liner.launcher.model.InstalledApp
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
                    onLaunchApp = { app -> launchApp(app) },
                    onOpenSettings = { tab -> openSettings(tab) },
                    onRecoverLayout = { payload -> writeRecoveryLog(payload) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The installed apps can change while Liner is in the background — an
        // app the user uninstalls must come back greyed out, not stale.
        viewModel.refreshApps()
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
        // The app is listed but may still fail to start: its launch
        // component can be disabled, it can have been updated since the
        // query, or the system can refuse the start. A tap that does
        // nothing at all is worse than a tap that says so, so a failed start
        // is reported — the same way the drawer reports it.
        launchApp(app)
    }

    /**
     * Starts an app the user picked in the drawer. The drawer only lists
     * installed apps, but the list can go stale between the query and the
     * tap, so a failed start is reported rather than swallowed.
     */
    private fun launchApp(app: InstalledApp) {
        if (!AppLauncher(this).launch(app)) {
            Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
        }
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
