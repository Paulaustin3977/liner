package dev.liner.launcher.model

import kotlinx.serialization.Serializable

@Serializable
data class ThemeTokens(
    val background: BackgroundSpec = BackgroundSpec.Wallpaper(dim = 0.35f),
    val textColor: ColorSpec = ColorSpec.FollowSystem,
    val listFontFamily: String = "sans-serif",
    val listTextSizeSp: Float = 20f,
    val listFontWeight: String = "400",
    val letterSpacingEm: Float = 0f,
    val itemSpacingDp: Float = 10f,
    val screenPaddingHdp: Float = 24f,
    val screenPaddingVdp: Float = 16f,
    val alignment: String = "start",
    val clockStyle: String = "large",
    val clockFormat: String = "system",
    val dateStyle: String = "medium",
    val sectionTitleSizeSp: Float = 13f,
    val sectionTitleColor: ColorSpec = ColorSpec.FollowSystem,
    val sectionTitleUppercase: Boolean = true,
    val sectionTitleRule: Boolean = true,
    val showIcons: Boolean = false,
    val iconSizeDp: Int = 24,
    val drawerStyle: String = "icons",
)
