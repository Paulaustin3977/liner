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
        // Throwable, not Exception: tapping a row must never take the launcher
        // down. A stale entry — an app uninstalled since the list was built —
        // surfaces as a failed start, which the caller reports, not as a crash.
        false
    }
}
