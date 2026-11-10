package dev.liner.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.model.AppCatalog
import dev.liner.launcher.model.AppFilter
import dev.liner.launcher.model.LayoutDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The drawer on a device: the app list it shows, the filter that narrows it
 * and the icons it draws.
 *
 * **These tests have never been executed.** The build environment has no
 * emulator and no connected device, so the sources are shipped unverified:
 * they compile, and they are expected to be run on a device. See MANUAL_TEST.md
 * for the human-run script that covers the same ground.
 */
@RunWith(AndroidJUnit4::class)
class DrawerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theDrawerListsEveryInstalledApp() {
        val installed = InstalledAppsRepository(context).loadInstalledApps().apps

        val visible = AppCatalog.drawerApps(LayoutDocument(), installed)

        assertEquals(installed.size, visible.size)
    }

    @Test
    fun filteringNarrowsTheList() {
        val installed = InstalledAppsRepository(context).loadInstalledApps().apps

        val result = AppFilter.filter(installed, "zzzz-no-such-app")

        assertTrue(result.isEmpty())
    }

    @Test
    fun iconsLoadWithoutThrowing() {
        val installed = InstalledAppsRepository(context).loadInstalledApps().apps
        val cache = IconCache.forPackageManager(context.packageManager, 96)

        // Must not throw, whatever the icons look like.
        installed.forEach { cache.icon(it.packageName) }

        // The app under test is installed, so its own icon must load — an
        // icon that never loads is the "no icon at all" failure.
        assertNotNull(cache.icon(context.packageName))
    }
}
