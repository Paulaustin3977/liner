package dev.liner.launcher

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.liner.launcher.apps.IconCache
import dev.liner.launcher.apps.InstalledAppsRepository
import dev.liner.launcher.model.DocumentCodec
import dev.liner.launcher.model.HomeItem
import dev.liner.launcher.model.LayoutDocument
import dev.liner.launcher.model.Section
import dev.liner.launcher.platform.LinerTheme
import dev.liner.launcher.store.InMemoryDocumentStore
import dev.liner.launcher.store.LayoutRepository
import dev.liner.launcher.ui.home.HomeScreen
import dev.liner.launcher.ui.home.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two gestures the home screen is judged on: the long-press drag that
 * reorders the list, and the swipe up from the bottom that opens the drawer.
 *
 * Both are driven through the real [HomeScreen] — the same composable the
 * launcher shows — so the assertions are about what the screen does, not
 * about a helper that re-implements it.
 *
 * **These tests have never been executed.** The build environment has no
 * emulator and no connected device, so the sources are shipped unverified:
 * they compile, and they are expected to be run on a device. See MANUAL_TEST.md
 * for the human-run script that covers the same ground.
 */
@RunWith(AndroidJUnit4::class)
class HomeInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var repository: LayoutRepository

    /**
     * A layout with three rows in one section. The packages are made up: a row
     * whose app is not installed is still drawn (greyed out), so the test does
     * not depend on what happens to be on the device.
     */
    private fun homeDocument(): LayoutDocument = LayoutDocument(
        sections = listOf(
            Section(
                id = "s1",
                title = "Daily",
                items = listOf(
                    HomeItem(id = "a", packageName = "dev.liner.launcher.fake.alpha", label = "Alpha"),
                    HomeItem(id = "b", packageName = "dev.liner.launcher.fake.bravo", label = "Bravo"),
                    HomeItem(id = "c", packageName = "dev.liner.launcher.fake.charlie", label = "Charlie"),
                ),
            ),
        ),
        // The first-run prompt is dismissed, so the list starts at the top of
        // the screen and nothing else is on it.
        firstRunPromptDismissed = true,
    )

    /** Shows the home screen with [document] as the saved layout. */
    private fun showHomeScreen(document: LayoutDocument) {
        repository = LayoutRepository(InMemoryDocumentStore(DocumentCodec.encode(document)))
        val viewModel = HomeViewModel(repository, InstalledAppsRepository(context))

        rule.setContent {
            LinerTheme {
                HomeScreen(
                    viewModel = viewModel,
                    wallpaper = null,
                    iconCache = IconCache.forPackageManager(context.packageManager, 96),
                    onLaunch = {},
                    onLaunchApp = {},
                    onOpenSettings = {},
                    onRecoverLayout = {},
                )
            }
        }

        // The layout is read from the store off the main thread, so the rows
        // only exist once it has arrived.
        rule.waitUntil(timeoutMillis = 10_000) { repository.documentLoaded.value }
        rule.waitForIdle()
    }

    @Test
    fun aLongPressDragMovesTheRowToTheSlotItWasDroppedOn() {
        showHomeScreen(homeDocument())

        val alpha = rule.onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot
        val bravo = rule.onNodeWithText("Bravo").fetchSemanticsNode().boundsInRoot
        val charlie = rule.onNodeWithText("Charlie").fetchSemanticsNode().boundsInRoot

        // The row is dropped in the gap between Bravo and Charlie. That is
        // below the middle of Bravo's row and above the middle of Charlie's,
        // which is the slot "Bravo, Alpha, Charlie" — the middle of the two
        // rows' halves, so the drop does not sit on a boundary.
        val dropY = (bravo.bottom + charlie.top) / 2f

        rule.onNodeWithText("Alpha").performTouchInput {
            // Hold still first. A long press is what arms the drag, and a
            // finger that moves before the timeout is a scroll, not a drag.
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            // Then drag down to the drop position, in steps, and let go.
            val drop = Offset(center.x, dropY - alpha.top)
            moveTo(Offset(center.x, center.y + (drop.y - center.y) / 3f))
            moveTo(Offset(center.x, center.y + 2f * (drop.y - center.y) / 3f))
            moveTo(drop)
            up()
        }

        rule.waitForIdle()

        assertEquals(
            "the dragged row must land in the slot it was dropped on",
            listOf("Bravo", "Alpha", "Charlie"),
            repository.document.value.sections.single().items.map { it.label },
        )
    }

    @Test
    fun swipingUpFromTheBottomOpensTheDrawer() {
        showHomeScreen(homeDocument())

        // The drawer is a sheet over the home screen: while it is closed, its
        // filter field is not in the tree at all.
        rule.onAllNodesWithText("Filter apps").assertCountEquals(0)

        rule.onRoot().performTouchInput {
            // A flick up from the bottom edge, where the drawer's gesture zone
            // sits. The move is long enough to pass the touch slop in one
            // event, which is what a quick flick looks like.
            val start = Offset(center.x, height - 20f)
            down(start)
            moveTo(Offset(center.x, height - 20f - 250f))
            up()
        }

        rule.waitForIdle()

        rule.onAllNodesWithText("Filter apps").assertCountEquals(1)
    }
}
