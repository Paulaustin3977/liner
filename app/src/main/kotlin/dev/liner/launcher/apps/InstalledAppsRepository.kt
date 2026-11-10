package dev.liner.launcher.apps

import android.content.Context
import android.content.Intent
import dev.liner.launcher.model.InstalledApp

/**
 * Reads the launchable apps installed on the device.
 *
 * Never throws. A failed query falls back to the last successful list rather
 * than showing the user an empty drawer — the launcher must keep working
 * when the package manager is briefly unavailable.
 */
class InstalledAppsRepository(context: Context) {

    private val packageManager = context.applicationContext.packageManager

    @Volatile
    private var lastSuccessful: List<InstalledApp> = emptyList()

    fun loadInstalledApps(): List<InstalledApp> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { resolved ->
                val info = resolved.activityInfo ?: return@mapNotNull null
                InstalledApp(
                    packageName = info.packageName,
                    activityClass = info.name,
                    systemLabel = resolved.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy { it.packageName to it.activityClass }
        lastSuccessful = apps
        apps
    } catch (_: Throwable) {
        lastSuccessful
    }
}
