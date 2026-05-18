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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.streamcloud.app.data.ServiceLocator

private val AioColors = darkColorScheme(
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

// ── Preset palette accent-color definitions ─────────────────────────────────
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

    // ── Base color scheme ────────────────────────────────────────────────────
    // Priority (Metrolist parity):
    //  1. Monet / Android 12+ dynamic wallpaper colors   (dynamicEnabled + S+)
    //  2. Music-driven Metrolist-style full tonal scheme  (track playing with artwork)
    //  3. User-selected preset palette                    (static)
    val colors = when {
        // 1. Monet — let Android handle everything; don't layer album art on top
        dynamicEnabled && supportsDynamic -> {
            if (useDark) dynamicDarkColorScheme(context)
            else         dynamicLightColorScheme(context)
        }

        // 2. Album-art driven scheme (dark mode only, matching Metrolist behaviour)
        useDark && hasArtwork -> {
            // Metrolist seeds the full dark scheme from the palette swatches:
            //  • primary / tertiary        → vibrant swatch (the "pop" color)
            //  • secondary / container     → muted swatch (softer complement)
            //  • surface / background      → very dark tint toward the accent
            val accent   = albumArtAccent
            val muted    = albumArtSecond

            // Subtle background tint: blend 6 % of the accent into the base dark bg
            val tintedBg      = lerp(Bg,          accent, 0.06f)
            val tintedSurface = lerp(BgElevated,  accent, 0.08f)
            val tintedVariant = lerp(BgSurface,   accent, 0.10f)

            AioColors.copy(
                primary              = accent,
                onPrimary            = Color.White,
                primaryContainer     = lerp(Color.Black, accent, 0.35f),
                onPrimaryContainer   = accent.copy(alpha = 0.9f),
                secondary            = muted,
                onSecondary          = Color.White,
                secondaryContainer   = lerp(Color.Black, muted, 0.30f),
                onSecondaryContainer = muted.copy(alpha = 0.9f),
                tertiary             = lerp(accent, muted, 0.4f),
                onTertiary           = Color.White,
                background           = tintedBg,
                onBackground         = TextPrimary,
                surface              = tintedSurface,
                onSurface            = TextPrimary,
                surfaceVariant       = tintedVariant,
                onSurfaceVariant     = TextSecondary,
            )
        }

        // 3. Static preset palette (or light mode)
        !useDark -> AioLightColors
        else -> {
            val p = palettes[colorPaletteId] ?: palettes["default"]!!
            val scheme = AioColors.copy(
                primary          = p.primary,
                primaryContainer = p.primaryContainer,
                secondary        = p.secondary,
            )
            if (themeMode == "black") scheme.copy(
                background     = Color.Black,
                surface        = Color(0xFF0D0D0D),
                surfaceVariant = Color(0xFF121212),
            ) else scheme
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                window.statusBarColor     = colors.background.toArgbInt()
                window.navigationBarColor = colors.background.toArgbInt()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
            }
        }
    }
    ProvideUiFormFactor(formFactor) {
        MaterialTheme(colorScheme = colors, typography = AioTypography, content = content)
    }
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)
