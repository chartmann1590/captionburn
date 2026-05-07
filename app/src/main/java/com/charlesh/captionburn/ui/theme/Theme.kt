package com.charlesh.captionburn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Purple40,
    onPrimary = Ink50,
    primaryContainer = Purple80,
    onPrimaryContainer = Ink900,
    secondary = Coral40,
    onSecondary = Ink50,
    secondaryContainer = Coral80,
    onSecondaryContainer = Ink900,
    tertiary = Mint40,
    onTertiary = Ink50,
    tertiaryContainer = Mint80,
    onTertiaryContainer = Ink900,
    background = Ink50,
    onBackground = Ink900,
    surface = Ink50,
    onSurface = Ink900,
    surfaceVariant = Ink200,
    onSurfaceVariant = Ink500,
    outline = Ink500,
)

private val DarkColors = darkColorScheme(
    primary = Purple80,
    onPrimary = Ink900,
    primaryContainer = Purple40,
    onPrimaryContainer = Ink50,
    secondary = Coral80,
    onSecondary = Ink900,
    secondaryContainer = Coral40,
    onSecondaryContainer = Ink50,
    tertiary = Mint80,
    onTertiary = Ink900,
    tertiaryContainer = Mint40,
    onTertiaryContainer = Ink50,
    background = Ink900,
    onBackground = Ink50,
    surface = Ink900,
    onSurface = Ink50,
    surfaceVariant = Ink700,
    onSurfaceVariant = Ink200,
    outline = Ink200,
)

@Composable
fun CaptionBurnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
