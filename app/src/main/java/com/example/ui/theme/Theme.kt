package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val FeiraLightColorScheme = lightColorScheme(
    primary = GraphitePrimary,
    onPrimary = Color.White,
    primaryContainer = SurfaceVariantLight,
    onPrimaryContainer = GraphitePrimary,
    secondary = EmeraldSavings,
    onSecondary = Color.White,
    secondaryContainer = EmeraldSavingsContainer,
    onSecondaryContainer = EmeraldSavingsOnContainer,
    tertiary = CoralAlert,
    onTertiary = Color.White,
    tertiaryContainer = CoralAlertContainer,
    onTertiaryContainer = CoralAlertOnContainer,
    background = BgLight,
    onBackground = GraphitePrimary,
    surface = SurfaceLight,
    onSurface = GraphitePrimary,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = SlateSecondary,
    outline = OutlineLight,
    outlineVariant = OutlineLight
)

private val FeiraDarkColorScheme = darkColorScheme(
    primary = OnDark,
    onPrimary = GraphitePrimary,
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = OnDark,
    secondary = EmeraldDark,
    onSecondary = GraphitePrimary,
    secondaryContainer = EmeraldDarkContainer,
    onSecondaryContainer = EmeraldDark,
    tertiary = CoralDark,
    onTertiary = GraphitePrimary,
    tertiaryContainer = CoralDarkContainer,
    onTertiaryContainer = CoralDark,
    background = BgDark,
    onBackground = OnDark,
    surface = SurfaceDark,
    onSurface = OnDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = SlateDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark
)

val FeiraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun FeiraJustaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) FeiraDarkColorScheme else FeiraLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = FeiraTypography,
        shapes = FeiraShapes,
        content = content
    )
}
