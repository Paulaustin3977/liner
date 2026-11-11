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
    fun `a home row reads unavailable when its app is gone`() {
        // A package name is not a label: the row has to say that the app is
        // no longer there.
        val entry = AppCatalog.homeEntries(document, installed)[1]

        assertEquals("unavailable", AppCatalog.homeRowLabel(entry, appsLoaded = true))
    }

    @Test
    fun `a home row keeps the package name until the app query has answered`() {
        // Before the query answers, an app that is not in the list may still
        // be installed: a row that says "unavailable" and then changes its
        // mind is worse than one that shows a package name for a moment.
        val entry = AppCatalog.homeEntries(document, installed)[1]

        assertEquals("com.missing", AppCatalog.homeRowLabel(entry, appsLoaded = false))
    }

    @Test
    fun `a home row keeps the user's rename when the app is gone`() {
        val doc = document.copy(
            sections = listOf(
                Section(
                    id = "s1",
                    items = listOf(HomeItem(id = "a", packageName = "com.missing", label = "Music")),
                ),
            ),
        )

        val entry = AppCatalog.homeEntries(doc, installed)[0]

        assertEquals("Music", AppCatalog.homeRowLabel(entry, appsLoaded = true))
    }

    @Test
    fun `a home row of an installed app reads the app's own label`() {
        val entry = AppCatalog.homeEntries(document, installed)[0]

        assertEquals("Mail", AppCatalog.homeRowLabel(entry, appsLoaded = true))
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
