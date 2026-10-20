package com.comedyplatform.mictracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark-first theme that follows the system light/dark setting, with the
 * Streamlit red as the accent (spec section 1).
 */
private val DarkColors = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1717),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFFF8A80),
    onSecondary = Color(0xFF3B0907),
    secondaryContainer = Color(0xFF3A2A2C),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = AppColors.Info,
    onTertiary = Color.White,
    background = AppColors.DarkBackground,
    onBackground = AppColors.DarkOnSurface,
    surface = AppColors.DarkBackground,
    onSurface = AppColors.DarkOnSurface,
    surfaceVariant = AppColors.DarkSurfaceVariant,
    onSurfaceVariant = AppColors.DarkOnSurfaceVariant,
    outline = AppColors.DarkOutline,
    outlineVariant = AppColors.DarkSurfaceVariant,
    error = Color(0xFFFF6B6B),
    onError = Color.Black,
    errorContainer = Color(0xFF5C1717),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE03C3C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFFB3261E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4E1),
    onSecondaryContainer = Color(0xFF410002),
    tertiary = AppColors.Info,
    onTertiary = Color.White,
    background = AppColors.LightBackground,
    onBackground = AppColors.LightOnSurface,
    surface = AppColors.LightSurface,
    onSurface = AppColors.LightOnSurface,
    surfaceVariant = AppColors.LightSurfaceVariant,
    onSurfaceVariant = AppColors.LightOnSurfaceVariant,
    outline = AppColors.LightOutline,
    outlineVariant = AppColors.LightSurfaceVariant,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun MicTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
