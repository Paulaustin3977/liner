package dev.liner.launcher.apps

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Caches app icons rasterised to a bitmap.
 *
 * The bitmap keeps the drawable's intrinsic resolution, falling back to
 * [sizePx] only when the drawable reports no size of its own — that keeps
 * adaptive icons crisp when the UI scales them down. A failed load returns
 * null rather than throwing, so the caller can render the app label instead.
 *
 * The loader is injected rather than taken as a PackageManager, so the
 * caching and stickiness policy can be tested on the JVM without a device.
 */
class IconCache(
    private val loadIcon: (String) -> ImageBitmap?,
    private val sizePx: Int,
) {
    // Bounded by bytes, not entry count: these are ARGB_8888 bitmaps at the
    // drawable's intrinsic resolution, so a few hundred KB each on a modern
    // device. A count-based ceiling of 64 would be tens of megabytes.
    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    // Packages that are genuinely gone. Without this, every recomposition of
    // a tile whose icon is missing re-enters the loader and re-throws.
    private val unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun icon(packageName: String): ImageBitmap? {
        val key = "$packageName@$sizePx"
        cache.get(key)?.let { return it }
        if (packageName in unavailable) return null

        val bitmap = try {
            loadIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            // The package is genuinely gone. Remember that, so the tile stops
            // asking for an icon that will never load.
            unavailable += packageName
            null
        } catch (_: Exception) {
            // A transient failure — deliberately NOT remembered, so the next
            // attempt retries rather than leaving the tile iconless for the
            // rest of the session. Errors are deliberately not caught here.
            null
        }

        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    companion object {
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

        /**
         * The production loader: rasterises the package's application icon.
         */
        fun forPackageManager(packageManager: PackageManager, sizePx: Int): IconCache =
            IconCache(
                loadIcon = { packageName ->
                    packageManager.getApplicationIcon(packageName).toImageBitmap(sizePx)
                },
                sizePx = sizePx,
            )
    }
}

/** Rasterises a drawable, falling back to [fallbackSize] when it reports no size. */
private fun Drawable.toImageBitmap(fallbackSize: Int): ImageBitmap? {
    val width = if (intrinsicWidth > 0) intrinsicWidth else fallbackSize
    val height = if (intrinsicHeight > 0) intrinsicHeight else fallbackSize
    if (width <= 0 || height <= 0) return null

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, width, height)
    draw(canvas)
    return bitmap.asImageBitmap()
}
