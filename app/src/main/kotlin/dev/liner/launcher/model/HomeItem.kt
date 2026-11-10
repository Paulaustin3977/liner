package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class HomeItem(
    val id: String,
    val packageName: String,
    val label: String? = null,
    val style: ItemStyle? = null,
)
