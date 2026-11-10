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

    /**
     * True when the most recent [load] fell back to the remembered
     * list rather than returning a fresh query. The fallback covers a failed
     * query and one that came back empty because the OS filtered the package
     * visibility. Neither is evidence that the apps were uninstalled, so a
     * fallback list must never be used to decide that an app is gone.
     */
    @Volatile
    var lastLoadWasFallback: Boolean = false
        private set

    fun load(): List<InstalledApp> = try {
        val apps = query()
        if (apps.isNotEmpty()) {
            lastSuccessful = apps
            lastLoadWasFallback = false
            apps
        } else {
            // An empty result is not a successful result: some OEMs surface
            // restricted package visibility as an empty list rather than an
            // error, and adopting that would blank the drawer and destroy the
            // cache. A device always has at least one launchable activity —
            // its own launcher — so an empty list means the query was
            // filtered, and the remembered list stands in for it.
            lastLoadWasFallback = true
            apps.ifEmpty { lastSuccessful }
        }
    } catch (_: Exception) {
        // Exception, not Throwable, to match the icon path: a bug or an
        // OutOfMemoryError must surface rather than being swallowed into a
        // silent fallback.
        lastLoadWasFallback = true
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

    /**
     * True when the most recent [loadInstalledApps] fell back to the
     * remembered list instead of returning a fresh query. A fallback list
     * must never be used to decide that an app has been uninstalled.
     */
    fun lastLoadWasFallback(): Boolean = provider.lastLoadWasFallback

    private fun queryInstalledApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
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
