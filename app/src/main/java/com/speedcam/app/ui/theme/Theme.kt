package com.speedcam.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme =
    darkColorScheme(
        primary = BrandPrimaryDark,
        onPrimary = DarkBackground,
        primaryContainer = Color(0xFF075985),
        onPrimaryContainer = Color(0xFFBAE6FD),
        secondary = BrandSecondaryDark,
        onSecondary = DarkBackground,
        secondaryContainer = DarkSurfaceElevated,
        onSecondaryContainer = DarkTextPrimary,
        tertiary = SpeedWarning,
        onTertiary = DarkBackground,
        background = DarkBackground,
        onBackground = DarkTextPrimary,
        surface = DarkSurface,
        onSurface = DarkTextPrimary,
        surfaceVariant = DarkSurfaceElevated,
        onSurfaceVariant = DarkTextSecondary,
        outline = DarkSurfaceBorder,
        outlineVariant = Color(0xFF1E293B),
        error = SpeedDanger,
        onError = DarkTextPrimary,
        errorContainer = SpeedDangerContainer,
        onErrorContainer = Color(0xFFFECACA),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = BrandPrimaryLight,
        onPrimary = LightSurface,
        primaryContainer = Color(0xFFE0F2FE),
        onPrimaryContainer = Color(0xFF0369A1),
        secondary = BrandSecondaryLight,
        onSecondary = LightSurface,
        secondaryContainer = LightSurfaceElevated,
        onSecondaryContainer = LightTextPrimary,
        tertiary = SpeedWarning,
        onTertiary = LightSurface,
        background = LightBackground,
        onBackground = LightTextPrimary,
        surface = LightSurface,
        onSurface = LightTextPrimary,
        surfaceVariant = LightSurfaceElevated,
        onSurfaceVariant = LightTextSecondary,
        outline = LightSurfaceBorder,
        outlineVariant = Color(0xFFCBD5E1),
        error = SpeedDanger,
        onError = LightSurface,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = SpeedDangerContainer,
    )

@Composable
fun SpeedCamTheme(
    darkTheme: Boolean = true, // Default to dark theme for automotive safety and anti-glare
    dynamicColor: Boolean = false, // Keep consistent brand identity across all Android versions
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            if (Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colorScheme.surface.toArgb()
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SpeedCamTypography,
        shapes = SpeedCamShapes,
        content = content,
    )
}
