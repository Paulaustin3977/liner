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

    val repository: LayoutRepository = LayoutRepository(DataStoreDocumentStore(appContext))
    val appsRepository: InstalledAppsRepository = InstalledAppsRepository(appContext)
    // 48dp is the drawer's tile size; the pixel size follows the density.
    val iconCache: IconCache = IconCache.forPackageManager(
        appContext.packageManager,
        sizePx = (ICON_TILE_DP * appContext.resources.displayMetrics.density).toInt(),
    )
}
