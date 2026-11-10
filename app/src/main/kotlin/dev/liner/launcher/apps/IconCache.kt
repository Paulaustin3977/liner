package dev.liner.launcher.apps

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Caches app icons rasterised to a bitmap of a fixed size.
 * A failed load returns null rather than throwing, so the caller can render
 * the app label instead.
 */
class IconCache(
    private val packageManager: PackageManager,
    private val sizePx: Int,
) {
    private val cache = LruCache<String, ImageBitmap>(64)

    fun icon(packageName: String): ImageBitmap? {
        val key = "$packageName@$sizePx"
        cache.get(key)?.let { return it }

        val bitmap = try {
            packageManager.getApplicationIcon(packageName).toImageBitmap()
        } catch (_: Throwable) {
            null
        }

        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private fun Drawable.toImageBitmap(): ImageBitmap? {
        val width = if (intrinsicWidth > 0) intrinsicWidth else sizePx
        val height = if (intrinsicHeight > 0) intrinsicHeight else sizePx
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, width, height)
        draw(canvas)
        return bitmap.asImageBitmap()
    }
}
