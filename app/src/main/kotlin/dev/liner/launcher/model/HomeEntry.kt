package dev.liner.launcher.model

data class HomeEntry(
    val item: HomeItem,
    val displayLabel: String,
    val available: Boolean,
    val sectionId: String,
    val sectionTitle: String?,
)
