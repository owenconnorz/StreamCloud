package com.streamcloud.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat

@Composable
internal fun SpotifyTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val appTypography = MaterialTheme.typography
    val colors = darkColorScheme(
        primary = SpotifyGreen,
        onPrimary = Color.Black,
        primaryContainer = SpotifyGreen,
        onPrimaryContainer = Color.Black,
        secondary = SpotifyGreen,
        onSecondary = Color.Black,
        background = SpotifyBlack,
        onBackground = Color.White,
        surface = SpotifyBlack,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF242424),
        onSurfaceVariant = Color(0xFFB3B3B3),
        outline = Color(0xFF535353),
        outlineVariant = Color(0xFF3A3A3A),
        error = Color(0xFFF15E6C),
        onError = Color.White,
    )
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        if (window == null || view.isInEditMode) {
            onDispose {}
        } else {
            val insetsController = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = insetsController.isAppearanceLightStatusBars
            val previousLightNavigationBars = insetsController.isAppearanceLightNavigationBars
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
            onDispose {
                insetsController.isAppearanceLightStatusBars = previousLightStatusBars
                insetsController.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = appTypography,
        content = content,
    )
}
