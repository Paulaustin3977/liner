package dev.liner.launcher.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ColorSpec {
    @Serializable
    @SerialName("solid")
    data class Solid(val argb: Int) : ColorSpec

    @Serializable
    @SerialName("system")
    data object FollowSystem : ColorSpec
}
