package dev.liner.launcher.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The condition that decides whether the first-run prompt is shown.
 *
 * It is pulled out of the composable so it can be checked without a device:
 * the prompt needs three facts — has the document been read, has the user
 * dismissed the prompt, and is Liner the default home app — and only the
 * first of them belongs to the caller.
 */
class FirstRunPromptTest {

    @Test
    fun `the prompt shows when it was not dismissed and Liner is not the default home app`() {
        assertTrue(shouldShowFirstRunPrompt(dismissed = false, isDefaultHome = false))
    }

    @Test
    fun `a dismissed prompt stays away`() {
        assertFalse(shouldShowFirstRunPrompt(dismissed = true, isDefaultHome = false))
    }

    @Test
    fun `the prompt stays away once Liner is the default home app`() {
        // Even when the user never dismissed it: the prompt must not come
        // back once Liner *is* the default home app.
        assertFalse(shouldShowFirstRunPrompt(dismissed = false, isDefaultHome = true))
    }

    @Test
    fun `the prompt waits for the answer instead of guessing`() {
        // The default-home check is a binder round trip: while it is pending
        // the answer is unknown, and a prompt that flashed in and out would
        // be worse than no prompt.
        assertFalse(shouldShowFirstRunPrompt(dismissed = false, isDefaultHome = null))
        assertFalse(shouldShowFirstRunPrompt(dismissed = true, isDefaultHome = null))
    }
}
