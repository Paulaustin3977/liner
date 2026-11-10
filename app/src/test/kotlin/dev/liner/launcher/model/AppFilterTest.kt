package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFilterTest {

    private val apps = listOf(
        InstalledApp("com.mail", "com.mail.Main", "Gmail"),
        InstalledApp("com.map", "com.map.Main", "Maps"),
        InstalledApp("com.mus", "com.mus.Main", "Spotify"),
    )

    @Test
    fun `an empty query returns everything`() {
        assertEquals(3, AppFilter.filter(apps, "  ").size)
    }

    @Test
    fun `matching is case insensitive and substring based`() {
        assertEquals(listOf("com.mail"), AppFilter.filter(apps, "gma").map { it.packageName })
        assertEquals(listOf("com.map"), AppFilter.filter(apps, "APS").map { it.packageName })
    }

    @Test
    fun `a custom rename is also searched`() {
        val result = AppFilter.filter(apps, "post", customLabels = mapOf("com.mus" to "Post"))

        assertEquals(listOf("com.mus"), result.map { it.packageName })
    }

    @Test
    fun `a query with no matches returns an empty list`() {
        assertTrue(AppFilter.filter(apps, "zzzz").isEmpty())
    }
}
