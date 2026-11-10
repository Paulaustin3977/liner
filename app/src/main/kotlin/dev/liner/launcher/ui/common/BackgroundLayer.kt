package dev.liner.launcher.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.ColorSpec

/**
 * Paints the screen background behind the home list.
 *
 * [systemTextColor] is the colour of the current light/dark configuration. It
 * is the fallback for a "follow system" colour, so a background that follows
 * the system stays legible against the system's text colour.
 */
@Composable
fun BackgroundLayer(
    spec: BackgroundSpec,
    wallpaper: Drawable?,
    systemTextColor: Color,
    modifier: Modifier = Modifier,
) {
    // Rasterising the wallpaper costs a full-size bitmap, so it is done once
    // per wallpaper instead of on every recomposition.
    val wallpaperBitmap = remember(wallpaper) {
        wallpaper?.toBitmap(width = 1080, height = 1920)?.asImageBitmap()
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (spec) {
            is BackgroundSpec.Solid -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(spec.color.toColor(systemTextColor)),
                )
            }

            is BackgroundSpec.Gradient -> {
                val from = spec.from.toColor(systemTextColor)
                val to = spec.to.toColor(systemTextColor)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(from, to),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            ),
                        ),
                )
            }

            is BackgroundSpec.Wallpaper -> {
                if (wallpaperBitmap != null) {
                    Image(
                        bitmap = wallpaperBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = spec.dim.coerceIn(0f, 0.9f))),
                )
            }
        }
    }
}

/** Maps a [ColorSpec] to a concrete colour, resolving "follow system" to [fallback]. */
fun ColorSpec.toColor(fallback: Color): Color = when (this) {
    is ColorSpec.Solid -> Color(argb)
    ColorSpec.FollowSystem -> fallback
}

private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    val w = if (intrinsicWidth > 0) intrinsicWidth else width
    val h = if (intrinsicHeight > 0) intrinsicHeight else height
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bitmap
}
