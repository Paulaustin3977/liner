package dev.liner.launcher.model

object StyleResolver {
    /**
     * Precedence: per item override > global theme > system default.
     * A null [ResolvedStyle.colorArgb] means "use the system default text colour".
     */
    fun resolveItemStyle(theme: ThemeTokens, override: ItemStyle?): ResolvedStyle {
        val colorSpec = override?.color ?: theme.textColor
        return ResolvedStyle(
            fontKey = theme.listFontFamily,
            sizeSp = override?.textSizeSp ?: theme.listTextSizeSp,
            weight = (override?.fontWeight ?: theme.listFontWeight).toIntOrNull() ?: 400,
            letterSpacingEm = override?.letterSpacingEm ?: theme.letterSpacingEm,
            colorArgb = (colorSpec as? ColorSpec.Solid)?.argb,
        )
    }

    fun resolveSectionHeaderStyle(theme: ThemeTokens): ResolvedStyle = ResolvedStyle(
        fontKey = theme.listFontFamily,
        sizeSp = theme.sectionTitleSizeSp,
        weight = 600,
        letterSpacingEm = 0.12f,
        colorArgb = (theme.sectionTitleColor as? ColorSpec.Solid)?.argb,
    )
}
