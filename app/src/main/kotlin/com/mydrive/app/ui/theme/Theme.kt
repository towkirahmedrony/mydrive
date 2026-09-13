package com.mydrive.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = Copper,
    onPrimary = Ink,
    primaryContainer = CopperDim,
    onPrimaryContainer = Ivory,
    secondary = Sage,
    onSecondary = Ink,
    secondaryContainer = SageDim,
    onSecondaryContainer = Ivory,
    tertiary = Sky,
    onTertiary = Ink,
    background = Ink,
    onBackground = Ivory,
    surface = InkElevated,
    onSurface = Ivory,
    surfaceVariant = Graphite,
    onSurfaceVariant = IvoryMuted,
    surfaceTint = Copper,
    outline = StrokeStrong,
    outlineVariant = Stroke,
    error = Clay,
    onError = Ivory,
    errorContainer = ClayDim,
    onErrorContainer = Ivory,
    inverseSurface = Ivory,
    inverseOnSurface = Ink,
    scrim = Color.Black
)

private val LightScheme = lightColorScheme(
    primary = CopperDim,
    onPrimary = Ivory,
    primaryContainer = Copper,
    onPrimaryContainer = Ink,
    secondary = SageDim,
    onSecondary = Ivory,
    secondaryContainer = Sage,
    onSecondaryContainer = Ink,
    tertiary = Sky,
    onTertiary = Ink,
    background = Paper,
    onBackground = Charcoal,
    surface = PaperElevated,
    onSurface = Charcoal,
    surfaceVariant = PaperCard,
    onSurfaceVariant = Stone,
    surfaceTint = CopperDim,
    outline = StrokeStrongLight,
    outlineVariant = StrokeLight,
    error = Clay,
    onError = Ivory,
    errorContainer = ClayDim,
    onErrorContainer = Ivory,
    inverseSurface = Ink,
    inverseOnSurface = Ivory,
    scrim = Color.Black
)

@Composable
fun MyDriveTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
