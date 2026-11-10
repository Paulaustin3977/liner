package dev.liner.launcher.platform

import android.content.Context
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.store.DataStoreDocumentStore
import dev.liner.launcher.store.LayoutRepository

/** Manual dependency container. Small enough that a DI framework would be overhead. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val repository: LayoutRepository = LayoutRepository(DataStoreDocumentStore(appContext))
    val appsRepository: InstalledAppsRepository = InstalledAppsRepository(appContext)
    val iconCache: IconCache = IconCache(appContext.packageManager, sizePx = 96)
}
