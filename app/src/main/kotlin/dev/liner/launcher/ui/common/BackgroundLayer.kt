package dev.liner.launcher.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.liner.launcher.model.BackgroundSpec
import dev.liner.launcher.model.ColorSpec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Paints the screen background behind the home list.
 *
 * [systemBackgroundColor] is the background of the current light/dark
 * configuration and is the fallback for a "follow system" background colour.
 * A background must never fall back to the *text* colour: that would paint
 * the label colour itself as the background and leave the labels unreadable.
 *
 * [systemTextColor] is the label colour of the same configuration. The
 * background never paints it — the screen resolves the chrome colour from
 * the theme through the resolver — but it belongs to this layer's contract,
 * so a caller can pass the pair it already has.
 */
@Composable
fun BackgroundLayer(
    spec: BackgroundSpec,
    wallpaper: Bitmap?,
    systemTextColor: Color,
    systemBackgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (spec) {
            is BackgroundSpec.Solid -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(spec.color.toColor(fallback = systemBackgroundColor)),
                )
            }

            is BackgroundSpec.Gradient -> {
                val from = spec.from.toColor(fallback = systemBackgroundColor)
                val to = spec.to.toColor(fallback = systemBackgroundColor)
                Box(
                    Modifier
                        .fillMaxSize()
                        // The brush is painted over the measured size, so the
                        // gradient covers the whole screen whatever its angle.
                        .drawBehind {
                            val (start, end) = gradientEndpoints(spec.angleDegrees, size.width, size.height)
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(from, to),
                                    start = start,
                                    end = end,
                                ),
                            )
                        },
                )
            }

            is BackgroundSpec.Wallpaper -> {
                if (wallpaper != null) {
                    Image(
                        bitmap = wallpaper.asImageBitmap(),
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
 * The endpoints of a linear gradient whose direction is [angleDegrees]
 * measured clockwise from vertical: 0 runs top-to-bottom, 90 runs
 * left-to-right, 180 runs bottom-to-top.
 *
 * The line is scaled isotropically — its length is the CSS "magic corner"
 * value `|w·sinθ| + |h·cosθ|` — so the on-screen direction is exactly the
 * requested angle on any aspect ratio. Scaling the two axes independently
 * would skew every non-cardinal angle.
 *
 * The endpoints are in pixels, because that is what
 * [Brush.linearGradient] expects.
 */
internal fun gradientEndpoints(
    angleDegrees: Int,
    width: Float,
    height: Float,
): Pair<Offset, Offset> {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val dx = sin(radians).toFloat()
    val dy = cos(radians).toFloat()
    val length = abs(width * dx) + abs(height * dy)
    val halfX = dx * length / 2f
    val halfY = dy * length / 2f
    val cx = width / 2f
    val cy = height / 2f
    return Offset(cx - halfX, cy - halfY) to Offset(cx + halfX, cy + halfY)
}

/**
 * Maps a [ColorSpec] to a concrete colour, resolving "follow system" to
 * [fallback]. A background must fall back to the *background* colour, not
 * the text colour — falling back to the text colour would paint a
 * near-white label colour as the background and make the text unreadable.
 */
fun ColorSpec.toColor(fallback: Color): Color = when (this) {
    is ColorSpec.Solid -> Color(argb)
    ColorSpec.FollowSystem -> fallback
}
