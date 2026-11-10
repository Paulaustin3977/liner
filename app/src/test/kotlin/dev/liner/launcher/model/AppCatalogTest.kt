package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogTest {

    private val document = LayoutDocument(
        sections = listOf(
            Section(
                id = "s1",
                title = "Daily",
                items = listOf(
                    HomeItem(id = "a", packageName = "com.mail", label = "Mail"),
                    HomeItem(id = "b", packageName = "com.missing"),
                ),
            ),
        ),
        hiddenFromDrawer = listOf("com.secret"),
    )

    private val installed = mapOf(
        "com.mail" to InstalledApp("com.mail", "com.mail.Main", "Gmail"),
    )

    @Test
    fun `home entries use the custom label when there is one`() {
        val entries = AppCatalog.homeEntries(document, installed)

        assertEquals("Mail", entries[0].displayLabel)
    }

    @Test
    fun `home entries fall back to the system label`() {
        val doc = document.copy(
            sections = listOf(
                Section(id = "s1", items = listOf(HomeItem(id = "a", packageName = "com.mail"))),
            ),
        )

        assertEquals("Gmail", AppCatalog.homeEntries(doc, installed)[0].displayLabel)
    }

    @Test
    fun `home entries fall back to the package name when the app is gone`() {
        val entries = AppCatalog.homeEntries(document, installed)

        assertEquals("com.missing", entries[1].displayLabel)
        assertFalse(entries[1].available)
    }

    @Test
    fun `home entries mark installed apps as available`() {
        assertTrue(AppCatalog.homeEntries(document, installed)[0].available)
    }

    @Test
    fun `drawer apps exclude hidden packages and sort by label`() {
        val apps = listOf(
            InstalledApp("com.secret", "com.secret.Main", "Secret"),
            InstalledApp("com.zoo", "com.zoo.Main", "Zoo"),
            InstalledApp("com.mail", "com.mail.Main", "Alpha"),
        )

        val result = AppCatalog.drawerApps(document, apps)

        assertEquals(listOf("com.mail", "com.zoo"), result.map { it.packageName })
    }

    @Test
    fun `drawer apps are sorted case insensitively by label`() {
        val apps = listOf(
            InstalledApp("com.b", "com.b.Main", "beta"),
            InstalledApp("com.a", "com.a.Main", "Alpha"),
        )

        val result = AppCatalog.drawerApps(LayoutDocument(), apps)

        assertEquals(listOf("com.a", "com.b"), result.map { it.packageName })
    }

    @Test
    fun `custom labels maps package names to their renamed labels`() {
        val labels = AppCatalog.customLabels(document)

        assertEquals("Mail", labels["com.mail"])
    }
}
