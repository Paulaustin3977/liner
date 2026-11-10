package dev.liner.launcher.platform

import android.app.WallpaperManager
import android.content.Context
import android.graphics.drawable.Drawable

object WallpaperProvider {
    /** Returns the system wallpaper, or null when it cannot be read. Never throws. */
    fun load(context: Context): Drawable? {
        val manager = WallpaperManager.getInstance(context)
        // A null result means "no wallpaper set" — the system wallpaper may
        // still be there, so try the system flag before giving up.
        return try {
            manager.getDrawable() ?: manager.getDrawable(WallpaperManager.FLAG_SYSTEM)
        } catch (_: Throwable) {
            try {
                manager.getDrawable(WallpaperManager.FLAG_SYSTEM)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
