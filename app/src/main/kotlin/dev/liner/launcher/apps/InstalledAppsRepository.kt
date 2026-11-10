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
