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

    // The repository reads the store once per process and writes it back on
    // every edit. It also preserves an unreadable layout in the app's own
    // files directory, so the user's work survives a corrupt file whichever
    // screen loaded it.
    val repository: LayoutRepository = LayoutRepository(
        DataStoreDocumentStore(appContext),
        recoveryLogDir = appContext.filesDir,
    )
    val appsRepository: InstalledAppsRepository = InstalledAppsRepository(appContext)
    // 48dp is the drawer's tile size; the pixel size follows the density.
    val iconCache: IconCache = IconCache.forPackageManager(
        appContext.packageManager,
        sizePx = (ICON_TILE_DP * appContext.resources.displayMetrics.density).toInt(),
    )
}
