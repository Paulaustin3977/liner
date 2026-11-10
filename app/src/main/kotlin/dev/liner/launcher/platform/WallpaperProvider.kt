package dev.liner.launcher.platform

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

object WallpaperProvider {
    /** Returns the system wallpaper, or null when it cannot be read. Never throws. */
    fun load(context: Context): Drawable? = try {
        val manager = WallpaperManager.getInstance(context)
        // A null result means "no wallpaper set" — the system wallpaper may
        // still be there, so try the system flag before giving up.
        manager.getDrawable() ?: manager.getDrawable(WallpaperManager.FLAG_SYSTEM)
    } catch (_: Throwable) {
        // The wallpaper service can be unavailable; the caller falls back to
        // a plain background rather than the launcher failing to start.
        try {
            WallpaperManager.getInstance(context).getDrawable(WallpaperManager.FLAG_SYSTEM)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The system wallpaper as a bitmap no larger than [maxWidth] x [maxHeight].
     *
     * The raw drawable is often several thousand pixels on a side;
     * rasterising it at full size costs tens of megabytes and can fail with
     * an [OutOfMemoryError] — which is an [Error], not an [Exception], and
     * would otherwise escape every `catch (e: Exception)` guard. The
     * drawable is therefore scaled down to the display size before it is
     * rasterised, and every failure, including an OOM, is swallowed.
     */
    fun loadBitmap(context: Context, maxWidth: Int, maxHeight: Int): Bitmap? {
        val drawable = load(context) ?: return null
        return try {
            val intrinsicWidth = drawable.intrinsicWidth
            val intrinsicHeight = drawable.intrinsicHeight
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return null
            val scale = minOf(
                maxWidth.toFloat() / intrinsicWidth,
                maxHeight.toFloat() / intrinsicHeight,
                1f,
            )
            val width = maxOf(1, (intrinsicWidth * scale).toInt())
            val height = maxOf(1, (intrinsicHeight * scale).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }
}
