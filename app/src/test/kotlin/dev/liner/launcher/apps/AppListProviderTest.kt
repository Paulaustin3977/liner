package dev.liner.launcher.apps

import dev.liner.launcher.model.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListProviderTest {

    private val apps = listOf(
        InstalledApp("com.mail", "com.mail.Main", "Gmail"),
        InstalledApp("com.maps", "com.maps.Main", "Maps"),
    )

    @Test
    fun `a successful query is returned`() {
        val provider = AppListProvider { apps }

        assertEquals(apps, provider.load().apps)
    }

    @Test
    fun `a failed query falls back to the last successful list`() {
        var shouldFail = false
        val provider = AppListProvider {
            if (shouldFail) throw IllegalStateException("package manager unavailable") else apps
        }

        assertEquals(apps, provider.load().apps)

        shouldFail = true
        assertEquals(apps, provider.load().apps)
    }

    @Test
    fun `a failed query before any success returns an empty list`() {
        val provider = AppListProvider {
            throw IllegalStateException("package manager unavailable")
        }

        assertTrue(provider.load().apps.isEmpty())
    }

    @Test
    fun `an empty result does not blank the remembered list`() {
        var empty = false
        val provider = AppListProvider { if (empty) emptyList() else apps }

        provider.load()
        empty = true

        assertEquals(apps, provider.load().apps)
    }

    @Test
    fun `a query that answers is not a fallback`() {
        val provider = AppListProvider { apps }

        val load = provider.load()

        assertFalse(load.wasFallback)
    }

    @Test
    fun `a failed query is a fallback`() {
        val provider = AppListProvider {
            throw IllegalStateException("package manager unavailable")
        }

        val load = provider.load()

        assertTrue(load.wasFallback)
    }

    @Test
    fun `an empty result is a fallback`() {
        val provider = AppListProvider { emptyList() }

        val load = provider.load()

        assertTrue(load.wasFallback)
    }

    @Test
    fun `a later failed query is a fallback after an earlier success`() {
        var shouldFail = false
        val provider = AppListProvider {
            if (shouldFail) throw IllegalStateException("package manager unavailable") else apps
        }

        val first = provider.load()
        assertFalse(first.wasFallback)

        shouldFail = true
        val second = provider.load()

        assertTrue(second.wasFallback)
    }

    @Test
    fun `a later empty result is a fallback after an earlier success`() {
        var empty = false
        val provider = AppListProvider { if (empty) emptyList() else apps }

        val first = provider.load()
        assertFalse(first.wasFallback)

        empty = true
        val second = provider.load()

        assertTrue(second.wasFallback)
    }

    @Test
    fun `the list and the trust flag travel together`() {
        // The repository is shared between the home and the settings screen.
        // Reading the list in one call and the flag in another could pick up
        // a load the other screen performed in between — in the dangerous
        // direction. One load carries both.
        var empty = false
        val provider = AppListProvider { if (empty) emptyList() else apps }

        provider.load()
        empty = true

        val load = provider.load()

        assertEquals(apps, load.apps)
        assertTrue(load.wasFallback)
    }
}
