package dev.liner.launcher

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.addSection
import dev.liner.launcher.store.InMemoryDocumentStore
import dev.liner.launcher.store.LayoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device or emulator. Verifies that the pieces that need a real
 * Android runtime behave: package visibility, icon loading and the launcher's
 * own HOME registration.
 *
 * **These tests have never been executed.** The build environment has no
 * emulator and no connected device, so the sources are shipped unverified:
 * they compile, and they are expected to be run on a device. See MANUAL_TEST.md
 * for the human-run script that covers the same ground.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theLauncherIsRegisteredAsAHomeActivity() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)

        val resolved = context.packageManager.queryIntentActivities(intent, 0)

        assertTrue(
            "Liner must be registered for CATEGORY_HOME to act as a launcher",
            resolved.any { it.activityInfo?.packageName == context.packageName },
        )
    }

    @Test
    fun installedAppsCanBeEnumerated() {
        val apps = InstalledAppsRepository(context).loadInstalledApps().apps

        // The test app itself is launchable, so the list is never empty.
        assertTrue(apps.isNotEmpty())
        assertTrue(apps.any { it.packageName == context.packageName })
    }

    @Test
    fun theDocumentRoundTripsOnDevice() {
        val document = LayoutDocument(
            sections = listOf(
                Section(id = "s1", title = "Daily", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
            ),
        )

        val decoded = DocumentCodec.decode(DocumentCodec.encode(document))

        assertEquals(document, decoded)
    }

    @Test
    fun theRepositoryPersistsAcrossInstances() = runBlocking {
        val store = InMemoryDocumentStore()
        val first = LayoutRepository(store)
        first.load()
        first.update { it.addSection("s1", "Daily") }
        first.awaitPendingWrite()

        val second = LayoutRepository(store)
        second.load()

        assertEquals(listOf("s1"), second.document.value.sections.map { it.id })
    }
}
