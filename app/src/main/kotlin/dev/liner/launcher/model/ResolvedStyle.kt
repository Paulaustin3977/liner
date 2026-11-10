package dev.liner.launcher.model

data class ResolvedStyle(
    val fontKey: String,
    val sizeSp: Float,
    val weight: Int,
    val letterSpacingEm: Float,
    /** null means "use the system default text colour". */
    val colorArgb: Int?,
)
