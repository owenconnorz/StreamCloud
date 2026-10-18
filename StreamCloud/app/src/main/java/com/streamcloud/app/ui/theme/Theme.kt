package com.streamcloud.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.streamcloud.app.data.ServiceLocator

internal val AioColors = darkColorScheme(
    primary = Salmon,
    onPrimary = TextPrimary,
    primaryContainer = SalmonDark,
    onPrimaryContainer = TextPrimary,
    secondary = Teal,
    onSecondary = Bg,
    tertiary = Rose,
    onTertiary = TextPrimary,
    background = Bg,
    onBackground = TextPrimary,
    surface = BgElevated,
    onSurface = TextPrimary,
    surfaceContainerHigh = Color(0xFF2D2019),
    surfaceVariant = BgSurface,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    outlineVariant = Outline,
    error = Color(0xFFEF4444),
    onError = TextPrimary,
)

internal fun shouldUseAlbumArtDynamicTheme(
    dynamicColorEnabled: Boolean,
    dynamicMiniPlayerThemeEnabled: Boolean,
    hasArtwork: Boolean,
): Boolean = hasArtwork && (dynamicColorEnabled || dynamicMiniPlayerThemeEnabled)

internal fun shouldUseSystemDynamicTheme(
    dynamicColorEnabled: Boolean,
    supportsDynamic: Boolean,
): Boolean = dynamicColorEnabled && supportsDynamic

private val AioTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 38.sp, lineHeight = 46.sp, letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp,
    ),
)

@Composable
fun StreamCloudTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val dynamicEnabled  by sl.settings.dynamicColor.collectAsState(initial = false)
    val dynamicMiniTheme by sl.settings.dynamicMiniPlayerTheme.collectAsState(initial = true)
    val albumArtAccent  by AlbumArtThemeBus.accent.collectAsState()
    val albumArtSecond  by AlbumArtThemeBus.accentSecondary.collectAsState()
    val hasArtwork      by AlbumArtThemeBus.hasArtwork.collectAsState()
    val themeMode       by sl.settings.theme.collectAsState(initial = "dark")
    val colorPaletteId  by sl.settings.colorPalette.collectAsState(initial = "modern_blue")
    val moviesThemeName by sl.settings.moviesTheme.collectAsState(initial = "modern_blue")
    val appPaletteId = normalizeAppColorPaletteId(colorPaletteId)
    val isSystemDark = isSystemInDarkTheme()

    // Always auto-detect — no manual override allowed.
    val formFactor = remember(context) { detectFormFactor(context) }

    val useDark = when (themeMode) {
        "light"  -> false
        "system" -> isSystemDark
        else     -> true
    }

    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S






    val useAlbumArtDynamicTheme = shouldUseAlbumArtDynamicTheme(
        dynamicColorEnabled = dynamicEnabled,
        dynamicMiniPlayerThemeEnabled = dynamicMiniTheme,
        hasArtwork = hasArtwork,
    )
    val useSystemDynamicTheme = shouldUseSystemDynamicTheme(
        dynamicColorEnabled = dynamicEnabled,
        supportsDynamic = supportsDynamic,
    )

    val colors = when {

        // Album-art extracted palette — highest priority when dynamic theme is ON and artwork exists
        useAlbumArtDynamicTheme -> {
            val accent    = albumArtAccent   // vivid, already HSL-boosted by AlbumArtThemeBus
            val secondary = albumArtSecond   // same hue, darker/more muted

            // Strong tint on backgrounds so the whole UI reflects the album palette
            val tintedBg      = lerp(Color(0xFF0A0A0A), accent, 0.18f)
            val tintedSurface = lerp(Color(0xFF141414), accent, 0.22f)
            val tintedVariant = lerp(Color(0xFF1E1E1E), accent, 0.28f)

            // onPrimary: use black for light accent, white for dark accent
            val onPrimary = if (accent.luminance() > 0.4f) Color.Black else Color.White

            AioColors.copy(
                primary              = accent,
                onPrimary            = onPrimary,
                primaryContainer     = lerp(Color.Black, accent, 0.35f),
                onPrimaryContainer   = accent,
                secondary            = secondary,
                onSecondary          = onPrimary,
                secondaryContainer   = lerp(Color.Black, secondary, 0.40f),
                onSecondaryContainer = secondary,
                tertiary             = lerp(accent, secondary, 0.45f),
                onTertiary           = onPrimary,
                background           = tintedBg,
                onBackground         = TextPrimary,
                surface              = tintedSurface,
                onSurface            = TextPrimary,
                surfaceContainerHigh = lerp(Color(0xFF1E1E1E), accent, 0.22f),
                surfaceVariant       = tintedVariant,
                onSurfaceVariant     = TextSecondary,
                outline              = lerp(Outline, accent, 0.25f),
                outlineVariant       = lerp(Outline, accent, 0.15f),
            )
        }

        // Android 12+ Material You wallpaper-based dynamic colours (fallback when no artwork)
        useSystemDynamicTheme -> {
            if (useDark) dynamicDarkColorScheme(context)
            else         dynamicLightColorScheme(context)
        }

        !useDark -> appColorScheme(appPaletteId, light = true)
        else -> {
            val paletteLight = appPaletteId == "light_clean" && themeMode != "black"
            val scheme = appColorScheme(appPaletteId, light = paletteLight)
            if (themeMode == "black") scheme.copy(
                background           = Color.Black,
                surface              = Color(0xFF0D0D0D),
                surfaceVariant       = Color(0xFF121212),
                surfaceContainerHigh = Color(0xFF1A1A1A),
            ) else scheme
        }
    }
    // TV controller focus follows the same Color Theme used by the movie
    // navigation pill, so the selected theme stays visually consistent across
    // content, settings, navigation, and updater surfaces.
    val tvFocusColor = AllMoviesThemes
        .firstOrNull { it.id == normalizeAppColorPaletteId(moviesThemeName) }
        ?.primary
        ?: palettes[appPaletteId]?.primary ?: colors.primary
    val usesLightBars = when {
        useAlbumArtDynamicTheme -> false
        useSystemDynamicTheme -> !useDark
        themeMode == "black" -> false
        else -> !useDark || appPaletteId == "light_clean"
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars     = usesLightBars
                controller.isAppearanceLightNavigationBars = usesLightBars
            }
        }
    }
    ProvideUiFormFactor(formFactor) {
        CompositionLocalProvider(LocalTvFocusColor provides tvFocusColor) {
            MaterialTheme(colorScheme = colors, typography = AioTypography, content = content)
        }
    }
}

/** Keep legacy route wrappers, but inherit the app-wide palette instead of resetting it. */
@Composable
fun StaticAppTheme(content: @Composable () -> Unit) {
    content()
}
