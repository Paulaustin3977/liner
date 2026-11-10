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
