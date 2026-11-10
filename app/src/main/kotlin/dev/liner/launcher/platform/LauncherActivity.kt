package dev.liner.launcher.platform

import android.os.Bundle
import android.widget.Toast
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
                    onLaunch = { entry -> launchEntry(entry.item.packageName) },
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
}
