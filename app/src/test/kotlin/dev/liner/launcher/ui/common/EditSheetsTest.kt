package dev.liner.launcher.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The logic behind the item and section editors.
 *
 * The sheets themselves are composables, so their decisions are pulled into
 * plain functions that can be checked without a device.
 */
class EditSheetsTest {

    @Test
    fun `an untouched field does not pin the app's name as a custom label`() {
        // The field is seeded with the app's name for an item that has no
        // custom label. Saving it unchanged must leave the label empty, so
        // the row keeps following a later system rename of the app.
        assertNull(
            resolveEditedLabel(
                seededLabel = "WhatsApp",
                editedLabel = "WhatsApp",
                existingLabel = null,
            ),
        )
    }

    @Test
    fun `an untouched field keeps an existing custom label`() {
        assertEquals(
            "Mail",
            resolveEditedLabel(
                seededLabel = "Mail",
                editedLabel = "Mail",
                existingLabel = "Mail",
            ),
        )
    }

    @Test
    fun `a deliberate rename is applied`() {
        assertEquals(
            "Chat",
            resolveEditedLabel(
                seededLabel = "WhatsApp",
                editedLabel = "Chat",
                existingLabel = null,
            ),
        )
    }

    @Test
    fun `a rename is trimmed`() {
        assertEquals(
            "Chat",
            resolveEditedLabel(
                seededLabel = "WhatsApp",
                editedLabel = "  Chat  ",
                existingLabel = null,
            ),
        )
    }

    @Test
    fun `an emptied field resets the item to the app's name`() {
        assertNull(
            resolveEditedLabel(
                seededLabel = "WhatsApp",
                editedLabel = "",
                existingLabel = null,
            ),
        )
        assertNull(
            resolveEditedLabel(
                seededLabel = "Mail",
                editedLabel = "   ",
                existingLabel = "Mail",
            ),
        )
    }

    @Test
    fun `the item's own section is not a move target`() {
        val sections = listOf("s1" to "One", "s2" to "Two", "s3" to null)

        assertEquals(
            listOf("s2" to "Two", "s3" to null),
            moveSectionTargets(sections, currentSectionId = "s1"),
        )
    }

    @Test
    fun `there is no move target when the item is in the only section`() {
        assertEquals(
            emptyList<Pair<String, String?>>(),
            moveSectionTargets(listOf("s1" to "One"), currentSectionId = "s1"),
        )
    }

    @Test
    fun `the delete prompt counts the section's items`() {
        assertEquals("Delete this section and its 1 item?", sectionDeletePrompt(1))
        assertEquals("Delete this section and its 3 items?", sectionDeletePrompt(3))
        assertEquals("Delete this section and its 0 items?", sectionDeletePrompt(0))
    }
}
