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

        assertEquals(apps, provider.load())
    }

    @Test
    fun `a failed query falls back to the last successful list`() {
        var shouldFail = false
        val provider = AppListProvider {
            if (shouldFail) throw IllegalStateException("package manager unavailable") else apps
        }

        assertEquals(apps, provider.load())

        shouldFail = true
        assertEquals(apps, provider.load())
    }

    @Test
    fun `a failed query before any success returns an empty list`() {
        val provider = AppListProvider {
            throw IllegalStateException("package manager unavailable")
        }

        assertTrue(provider.load().isEmpty())
    }

    @Test
    fun `an empty result does not blank the remembered list`() {
        var empty = false
        val provider = AppListProvider { if (empty) emptyList() else apps }

        provider.load()
        empty = true

        assertEquals(apps, provider.load())
    }

    @Test
    fun `a query that answers is not a fallback`() {
        val provider = AppListProvider { apps }

        provider.load()

        assertFalse(provider.lastLoadWasFallback)
    }

    @Test
    fun `a failed query is a fallback`() {
        val provider = AppListProvider {
            throw IllegalStateException("package manager unavailable")
        }

        provider.load()

        assertTrue(provider.lastLoadWasFallback)
    }

    @Test
    fun `an empty result is a fallback`() {
        val provider = AppListProvider { emptyList() }

        provider.load()

        assertTrue(provider.lastLoadWasFallback)
    }

    @Test
    fun `a later failed query is a fallback after an earlier success`() {
        var shouldFail = false
        val provider = AppListProvider {
            if (shouldFail) throw IllegalStateException("package manager unavailable") else apps
        }

        provider.load()
        assertFalse(provider.lastLoadWasFallback)

        shouldFail = true
        provider.load()

        assertTrue(provider.lastLoadWasFallback)
    }

    @Test
    fun `a later empty result is a fallback after an earlier success`() {
        var empty = false
        val provider = AppListProvider { if (empty) emptyList() else apps }

        provider.load()
        assertFalse(provider.lastLoadWasFallback)

        empty = true
        provider.load()

        assertTrue(provider.lastLoadWasFallback)
    }
}
