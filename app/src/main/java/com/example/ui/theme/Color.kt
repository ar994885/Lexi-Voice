package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Light Palette - Warm Editorial Literary Studio
val AmberPrimaryLight = Color(0xFFC96528)
val AmberOnPrimaryLight = Color(0xFFFFFFFF)
val AmberContainerLight = Color(0xFFFFDCC7)
val AmberOnContainerLight = Color(0xFF3E1800)

val TealSecondaryLight = Color(0xFF2A6F68)
val TealOnSecondaryLight = Color(0xFFFFFFFF)
val TealContainerLight = Color(0xFFB4EBE4)
val TealOnContainerLight = Color(0xFF00201D)

val GoldTertiaryLight = Color(0xFF8C6214)
val GoldContainerLight = Color(0xFFFFDFAA)

val BackgroundLight = Color(0xFFFAF6F0)
val SurfaceLight = Color(0xFFFFFDF9)
val SurfaceVariantLight = Color(0xFFEDE6DA)
val OnBackgroundLight = Color(0xFF171A21)
val OnSurfaceVariantLight = Color(0xFF565C66)
val OutlineLight = Color(0xFFD6CEC2)

// Dark Palette - Midnight Studio
val AmberPrimaryDark = Color(0xFFF2A164)
val AmberOnPrimaryDark = Color(0xFF4C2000)
val AmberContainerDark = Color(0xFF6E330B)
val AmberOnContainerDark = Color(0xFFFFDCC7)

val TealSecondaryDark = Color(0xFF69C5BA)
val TealOnSecondaryDark = Color(0xFF003732)
val TealContainerDark = Color(0xFF0D504A)
val TealOnContainerDark = Color(0xFFB4EBE4)

val GoldTertiaryDark = Color(0xFFE9B872)
val GoldContainerDark = Color(0xFF5D4107)

val BackgroundDark = Color(0xFF10141B)
val SurfaceDark = Color(0xFF171C26)
val SurfaceVariantDark = Color(0xFF222936)
val OnBackgroundDark = Color(0xFFF0ECE3)
val OnSurfaceVariantDark = Color(0xFFA7AFB9)
val OutlineDark = Color(0xFF313A4A)

// Reader Themes
enum class ReaderThemeMode(
    val label: String,
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val highlightBg: Color,
    val highlightText: Color,
    val chromeBg: Color
) {
    LIGHT(
        label = "Light",
        background = Color(0xFFFCFBF7),
        text = Color(0xFF1B1E23),
        secondaryText = Color(0xFF606670),
        highlightBg = Color(0xFFFFDF9E),
        highlightText = Color(0xFF1B1E23),
        chromeBg = Color(0xFFF3EFE6)
    ),
    SEPIA(
        label = "Sepia",
        background = Color(0xFFF4EBD9),
        text = Color(0xFF3B2E22),
        secondaryText = Color(0xFF705D4B),
        highlightBg = Color(0xFFECC072),
        highlightText = Color(0xFF2A1E13),
        chromeBg = Color(0xFFE9DEC8)
    ),
    DARK(
        label = "Dark",
        background = Color(0xFF12151B),
        text = Color(0xFFE3DFD6),
        secondaryText = Color(0xFF939AA6),
        highlightBg = Color(0xFF614418),
        highlightText = Color(0xFFFFF4DE),
        chromeBg = Color(0xFF1A1F28)
    )
}
