package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimary,
    onPrimary = Color.White,
    primaryContainer = CobaltContainer,
    onPrimaryContainer = CobaltOnContainer,
    secondary = TealSecondary,
    onSecondary = Color.White,
    secondaryContainer = TealContainer,
    onSecondaryContainer = TealOnContainer,
    tertiary = CrimsonTertiary,
    onTertiary = Color.White,
    tertiaryContainer = CrimsonContainer,
    onTertiaryContainer = CrimsonOnContainer,
    background = PaperBackground,
    onBackground = InkPrimary,
    surface = PaperSurface,
    onSurface = InkPrimary,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = InkSecondary,
    outline = PaperOutline,
    outlineVariant = PaperOutlineSubtle,
    error = CrimsonTertiary,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = SkyCobaltPrimary,
    onPrimary = Color(0xFF0B1E40),
    primaryContainer = SkyCobaltContainer,
    onPrimaryContainer = SkyCobaltOnContainer,
    secondary = MintTealSecondary,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = MintTealContainer,
    onSecondaryContainer = MintTealOnContainer,
    tertiary = CoralRedTertiary,
    onTertiary = Color(0xFF450A0A),
    tertiaryContainer = CoralRedContainer,
    onTertiaryContainer = CoralRedOnContainer,
    background = ObsidianBackground,
    onBackground = SlateTextPrimary,
    surface = ObsidianSurface,
    onSurface = SlateTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = SlateTextSecondary,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineSubtle,
    error = CoralRedTertiary,
    onError = Color(0xFF450A0A)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun LiteOfficeTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
