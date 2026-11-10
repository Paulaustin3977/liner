package dev.liner.launcher.platform

import android.app.WallpaperManager
import android.content.Context
import android.graphics.drawable.Drawable

object WallpaperProvider {
    /** Returns the system wallpaper, or null when it cannot be read. Never throws. */
    fun load(context: Context): Drawable? = try {
        WallpaperManager.getInstance(context).getDrawable()
    } catch (_: Throwable) {
        // Some devices refuse the current wallpaper but still expose the
        // system one, so fall back before giving up on a background entirely.
        try {
            WallpaperManager.getInstance(context).getDrawable(WallpaperManager.FLAG_SYSTEM)
        } catch (_: Throwable) {
            null
        }
    }
}
