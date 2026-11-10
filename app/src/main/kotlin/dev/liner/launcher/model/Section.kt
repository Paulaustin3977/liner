package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class Section(
    val id: String,
    val title: String? = null,
    val items: List<HomeItem> = emptyList(),
)
