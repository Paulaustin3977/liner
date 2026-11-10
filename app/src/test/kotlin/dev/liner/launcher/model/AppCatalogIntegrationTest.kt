package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the pure catalog against a realistic mixed set of installed and
 * uninstalled apps, mirroring what the repository returns on a real device.
 */
class AppCatalogIntegrationTest {

    @Test
    fun `home shows custom labels, live labels and missing packages`() {
        val document = LayoutDocument(
            sections = listOf(
                Section(
                    id = "s1",
                    title = "Daily",
                    items = listOf(
                        HomeItem(id = "a", packageName = "com.mail", label = "Inbox"),
                        HomeItem(id = "b", packageName = "com.maps"),
                        HomeItem(id = "c", packageName = "com.gone"),
                    ),
                ),
            ),
        )
        val installed = mapOf(
            "com.mail" to InstalledApp("com.mail", "com.mail.Main", "Gmail"),
            "com.maps" to InstalledApp("com.maps", "com.maps.Main", "Maps"),
        )

        val entries = AppCatalog.homeEntries(document, installed)

        assertEquals(listOf("Inbox", "Maps", "com.gone"), entries.map { it.displayLabel })
        assertEquals(listOf(true, true, false), entries.map { it.available })
    }

    @Test
    fun `drawer shows every installed app except the hidden ones`() {
        val document = LayoutDocument(hiddenFromDrawer = listOf("com.maps"))
        val installed = listOf(
            InstalledApp("com.mail", "com.mail.Main", "Gmail"),
            InstalledApp("com.maps", "com.maps.Main", "Maps"),
            InstalledApp("com.notes", "com.notes.Main", "Notes"),
        )

        val result = AppCatalog.drawerApps(document, installed)

        assertEquals(listOf("com.mail", "com.notes"), result.map { it.packageName })
    }
}
