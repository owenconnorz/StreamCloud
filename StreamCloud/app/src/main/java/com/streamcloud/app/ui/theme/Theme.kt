package com.streamcloud.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

private val AioLightColors = lightColorScheme(
    primary = Color(0xFFC97B6C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF3A0905),
    secondary = Color(0xFF5D8E8B),
    onSecondary = Color.White,
    tertiary = Color(0xFFB85850),
    onTertiary = Color.White,
    background = Color(0xFFFCF8F7),
    onBackground = Color(0xFF201A19),
    surface = Color(0xFFF5EFED),
    onSurface = Color(0xFF201A19),
    surfaceContainerHigh = Color(0xFFEDE5E3),
    surfaceVariant = Color(0xFFE8E0DE),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFD8C2BF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

internal data class PaletteAccents(
    val primary: Color,
    val primaryContainer: Color,
    val secondary: Color,
)

internal val palettes: Map<String, PaletteAccents> = mapOf(
    "default" to PaletteAccents(Salmon,               SalmonDark,              Teal),
    "warm"    to PaletteAccents(Color(0xFFD4824A),    Color(0xFF8B4513),       Color(0xFFE8B87A)),
    "coral"   to PaletteAccents(Color(0xFFD45858),    Color(0xFF8B3A35),       Color(0xFFE8A0A0)),
    "violet"  to PaletteAccents(Color(0xFF7B54C2),    Color(0xFF3E2070),       Color(0xFFB8A0DC)),
    "blue"    to PaletteAccents(Color(0xFF3B6CAC),    Color(0xFF1E3D6A),       Color(0xFF8AB4E8)),
    "indigo"  to PaletteAccents(Color(0xFF3B3B9C),    Color(0xFF1E1E60),       Color(0xFF8888CC)),
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
    val uiModeStr       by sl.settings.uiMode.collectAsState(initial = "Auto")
    val themeMode       by sl.settings.theme.collectAsState(initial = "dark")
    val colorPaletteId  by sl.settings.colorPalette.collectAsState(initial = "default")
    val isSystemDark = isSystemInDarkTheme()

    val formFactor = remember(uiModeStr, context) {
        UiModeOverride.fromStorage(uiModeStr).resolve(context)
    }

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

        !useDark -> AioLightColors
        else -> {
            val p = palettes[colorPaletteId] ?: palettes["default"]!!
            val scheme = AioColors.copy(
                primary              = p.primary,
                primaryContainer     = p.primaryContainer,
                secondary            = p.secondary,
                background           = lerp(Bg, p.primary, 0.10f),
                surface              = lerp(BgElevated, p.primary, 0.12f),
                surfaceVariant       = lerp(BgSurface, p.primary, 0.15f),
                surfaceContainerHigh = lerp(Color(0xFF2D2019), p.primary, 0.12f),
                outline              = lerp(Outline, p.primary, 0.20f),
                outlineVariant       = lerp(Outline, p.primary, 0.12f),
            )
            if (themeMode == "black") scheme.copy(
                background           = Color.Black,
                surface              = Color(0xFF0D0D0D),
                surfaceVariant       = Color(0xFF121212),
                surfaceContainerHigh = Color(0xFF1A1A1A),
            ) else scheme
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars     = !useDark
                controller.isAppearanceLightNavigationBars = !useDark
            }
        }
    }
    ProvideUiFormFactor(formFactor) {
        MaterialTheme(colorScheme = colors, typography = AioTypography, content = content)
    }
}

/** Static dark colour scheme for Settings and its sub-pages.
 *  Matches the clean black/blue palette used by CollectionsScreen. */
private val SettingsColors = darkColorScheme(
    primary              = Color(0xFF2196F3),
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFF1565C0),
    onPrimaryContainer   = Color.White,
    secondary            = Color(0xFF6AABA8),
    onSecondary          = Color.White,
    background           = Color(0xFF000000),
    onBackground         = Color.White,
    surface              = Color(0xFF121212),
    onSurface            = Color.White,
    surfaceContainerHigh = Color(0xFF252525),
    surfaceVariant       = Color(0xFF1E1E1E),
    onSurfaceVariant     = Color(0xFFAAAAAA),
    outline              = Color(0xFF333333),
    outlineVariant       = Color(0xFF262626),
    error                = Color(0xFFD32F2F),
    onError              = Color.White,
)

/** Wrap content with the static dark/blue colour scheme used by Settings and its categories. */
@Composable
fun StaticAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SettingsColors,
        typography  = AioTypography,
        content     = content,
    )
}
