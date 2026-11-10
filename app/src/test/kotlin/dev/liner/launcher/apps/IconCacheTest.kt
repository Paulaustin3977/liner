package dev.liner.launcher.apps

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class IconCacheTest {

    @Test
    fun `a genuinely missing package is remembered and not asked for again`() {
        var calls = 0
        val cache = IconCache(
            loadIcon = { packageName ->
                calls++
                throw PackageManager.NameNotFoundException()
            },
            sizePx = 96,
        )

        assertNull(cache.icon("com.gone"))
        assertNull(cache.icon("com.gone"))

        assertEquals(1, calls)
    }

    @Test
    fun `a transient failure is not remembered so the next call retries`() {
        var calls = 0
        val cache = IconCache(
            loadIcon = { packageName ->
                calls++
                throw IllegalStateException("transient")
            },
            sizePx = 96,
        )

        assertNull(cache.icon("com.flaky"))
        assertNull(cache.icon("com.flaky"))

        assertEquals(2, calls)
    }

    @Test
    fun `an error is not swallowed`() {
        val cache = IconCache(
            loadIcon = { packageName -> throw OutOfMemoryError("no room") },
            sizePx = 96,
        )

        try {
            cache.icon("com.oom")
            fail("expected the error to propagate")
        } catch (_: OutOfMemoryError) {
            // expected — an Error must not be swallowed into a silent fallback
        }
    }
}
