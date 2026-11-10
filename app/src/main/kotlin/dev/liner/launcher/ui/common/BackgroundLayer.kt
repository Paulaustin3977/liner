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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.ColorSpec
import kotlin.math.cos
import kotlin.math.sin

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
                        // The brush is painted over the measured size, so the
                        // gradient covers the whole screen whatever its angle.
                        .drawBehind {
                            val (start, end) = gradientEndpoints(spec.angleDegrees)
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(from, to),
                                    start = Offset(start.x * size.width, start.y * size.height),
                                    end = Offset(end.x * size.width, end.y * size.height),
                                ),
                            )
                        },
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

/**
 * The gradient line for [angleDegrees], in normalised screen coordinates:
 * (0,0) is the top-left of the screen and (1,1) the bottom-right.
 *
 * The angle is measured clockwise from vertical, so 0 runs top-to-bottom
 * (`from` at the top, `to` at the bottom), 90 runs left-to-right and 180
 * runs bottom-to-top. The line always passes through the centre of the
 * screen, so the two ends are half a screen apart in each direction.
 */
internal fun gradientEndpoints(angleDegrees: Int): Pair<Offset, Offset> {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val dx = sin(radians).toFloat()
    val dy = cos(radians).toFloat()
    return Offset(0.5f - dx * 0.5f, 0.5f - dy * 0.5f) to
        Offset(0.5f + dx * 0.5f, 0.5f + dy * 0.5f)
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
