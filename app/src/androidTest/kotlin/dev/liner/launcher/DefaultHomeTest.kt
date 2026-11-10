package dev.liner.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.liner.launcher.platform.DefaultHomeDetector
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The default-home detection on a device: the role query and the request
 * that asks the system to make Liner the home app.
 *
 * **These tests have never been executed.** The build environment has no
 * emulator and no connected device, so the sources are shipped unverified:
 * they compile, and they are expected to be run on a device. See MANUAL_TEST.md
 * for the human-run script that covers the same ground.
 */
@RunWith(AndroidJUnit4::class)
class DefaultHomeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun detectingTheDefaultHomeNeverThrows() {
        // Whether Liner is the default depends on how the test app was
        // installed; the call must simply not crash. The answer must also be
        // stable — the role cannot change between two calls in a row.
        val first = DefaultHomeDetector.isDefaultHome(context)
        val second = DefaultHomeDetector.isDefaultHome(context)

        assertEquals("the default-home answer must be stable", first, second)
    }

    @Test
    fun requestingTheDefaultHomeNeverThrows() {
        // Side effect: on a device this opens the system's role request, or
        // the home-app settings screen. The call must not crash.
        DefaultHomeDetector.requestDefaultHome(context)
    }
}
