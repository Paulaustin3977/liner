package dev.liner.launcher.apps

import android.content.Context
import android.content.Intent
import dev.liner.launcher.model.InstalledApp

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

    fun load(): List<InstalledApp> = try {
        val apps = query()
        // An empty result is not a successful result: some OEMs surface
        // restricted package visibility as an empty list rather than an
        // error, and adopting that would blank the drawer and destroy the
        // cache. A device always has at least one launchable activity — its
        // own launcher.
        if (apps.isNotEmpty()) {
            lastSuccessful = apps
        } else if (lastSuccessful.isEmpty()) {
            // Nothing has been remembered yet, so there is nothing to keep:
            // an empty result stays empty.
            lastSuccessful = apps
        }
        // An empty result must not blank the remembered list — the last list
        // we know to be good is a better answer than "no apps".
        apps.ifEmpty { lastSuccessful }
    } catch (_: Throwable) {
        lastSuccessful
    }
}

/**
 * Reads the launchable apps installed on the device.
 *
 * Never throws. A failed query falls back to the last successful list rather
 * than showing the user an empty drawer — the launcher must keep working
 * when the package manager is briefly unavailable.
 */
class InstalledAppsRepository(context: Context) {

    private val packageManager = context.applicationContext.packageManager

    private val provider = AppListProvider { queryInstalledApps() }

    fun loadInstalledApps(): List<InstalledApp> = provider.load()

    private fun queryInstalledApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { resolved ->
                val info = resolved.activityInfo ?: return@mapNotNull null
                InstalledApp(
                    packageName = info.packageName,
                    activityClass = info.name,
                    // A null label must not abort the whole query — fall back
                    // to the package name for that one app.
                    systemLabel = resolved.loadLabel(packageManager)?.toString() ?: info.packageName,
                )
            }
            // One entry per app: an app with two MAIN/LAUNCHER activities must
            // not produce two tiles in the drawer, and the home path keys its
            // entries by package name anyway.
            .distinctBy { it.packageName }
    }
}
