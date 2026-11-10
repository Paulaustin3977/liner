package dev.liner.launcher.ui.settings

import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.model.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reset of the advanced tab.
 *
 * The reset throws the layout away, so it is pulled out of the composable
 * and checked here: what it empties, and what it must leave alone.
 */
class AdvancedTabTest {

    private val document = LayoutDocument(
        sections = listOf(
            Section(id = "s1", title = "Mine", items = listOf(HomeItem(id = "a", packageName = "p.a"))),
        ),
        hiddenFromDrawer = listOf("p.a"),
        autoPruneUninstalled = true,
    )

    @Test
    fun `the reset empties the layout`() {
        val reset = resetToDefaults(document)

        assertTrue(reset.sections.isEmpty())
        assertTrue(reset.hiddenFromDrawer.isEmpty())
        assertFalse(reset.autoPruneUninstalled)
    }

    @Test
    fun `the reset puts the theme back to its defaults`() {
        val themed = document.copy(theme = ThemeTokens().copy(listTextSizeSp = 30f))

        assertEquals(ThemeTokens(), resetToDefaults(themed).theme)
    }

    @Test
    fun `the reset keeps the version of the document`() {
        assertEquals(document.version, resetToDefaults(document).version)
    }
}
