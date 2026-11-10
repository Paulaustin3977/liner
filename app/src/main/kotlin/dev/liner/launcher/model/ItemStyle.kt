package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class ItemStyle(
    val textSizeSp: Float? = null,
    val color: ColorSpec? = null,
    val fontWeight: String? = null,
    val letterSpacingEm: Float? = null,
)
