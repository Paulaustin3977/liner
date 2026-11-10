package dev.liner.launcher.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface BackgroundSpec {
    @Serializable
    @SerialName("solid")
    data class Solid(val color: ColorSpec) : BackgroundSpec

    @Serializable
    @SerialName("gradient")
    data class Gradient(
        val from: ColorSpec,
        val to: ColorSpec,
        val angleDegrees: Int = 90,
    ) : BackgroundSpec

    @Serializable
    @SerialName("wallpaper")
    data class Wallpaper(val dim: Float = 0.35f) : BackgroundSpec
}
