package com.streamcloud.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal data class AppColorPalette(
    val id: String,
    val label: String,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onPrimary: Color,
    val onPrimaryContainer: Color,
    val swatches: List<Color>,
)

internal val AllAppColorPalettes = listOf(
    AppColorPalette(
        id = "modern_blue",
        label = "Modern Blue",
        background = Color(0xFF202B3B),
        surface = Color(0xFF29384D),
        surfaceVariant = Color(0xFF34445A),
        primary = Color(0xFF4F8EF7),
        primaryContainer = Color(0xFF4F8EF7),
        secondary = Color(0xFF4F8EF7),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFD3DDEB),
        onPrimary = Color.White,
        onPrimaryContainer = Color.White,
        swatches = listOf(
            Color(0xFF202B3B),
            Color(0xFF29384D),
            Color(0xFF34445A),
            Color(0xFF4F8EF7),
            Color.White,
        ),
    ),
    AppColorPalette(
        id = "purple_aurora",
        label = "Purple Aurora",
        background = Color(0xFF2A1F4B),
        surface = Color(0xFF3B2A6B),
        surfaceVariant = Color(0xFF5D3FD3),
        primary = Color(0xFFA855F7),
        primaryContainer = Color(0xFF5D3FD3),
        secondary = Color(0xFF5D3FD3),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFE5DDF5),
        onPrimary = Color.White,
        onPrimaryContainer = Color.White,
        swatches = listOf(
            Color(0xFF2A1F4B),
            Color(0xFF3B2A6B),
            Color(0xFF5D3FD3),
            Color(0xFFA855F7),
            Color.White,
        ),
    ),
    AppColorPalette(
        id = "sunset_orange",
        label = "Sunset Orange",
        background = Color(0xFF3D1E0F),
        surface = Color(0xFF6B3118),
        surfaceVariant = Color(0xFF6B3118),
        primary = Color(0xFFF97316),
        primaryContainer = Color(0xFFFDBA74),
        secondary = Color(0xFFFDBA74),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFFFE0C2),
        onPrimary = Color(0xFF3D1E0F),
        onPrimaryContainer = Color(0xFF3D1E0F),
        swatches = listOf(
            Color(0xFF3D1E0F),
            Color(0xFF6B3118),
            Color(0xFFF97316),
            Color(0xFFFDBA74),
            Color.White,
        ),
    ),
    AppColorPalette(
        id = "forest_green",
        label = "Forest Green",
        background = Color(0xFF0F2F2A),
        surface = Color(0xFF1B4D43),
        surfaceVariant = Color(0xFF1B4D43),
        primary = Color(0xFF2DD4BF),
        primaryContainer = Color(0xFF86EFAC),
        secondary = Color(0xFF86EFAC),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFD3F2E3),
        onPrimary = Color(0xFF0F2F2A),
        onPrimaryContainer = Color(0xFF0F2F2A),
        swatches = listOf(
            Color(0xFF0F2F2A),
            Color(0xFF1B4D43),
            Color(0xFF2DD4BF),
            Color(0xFF86EFAC),
            Color.White,
        ),
    ),
    AppColorPalette(
        id = "light_clean",
        label = "Light Clean",
        background = Color(0xFFE9EDF2),
        surface = Color(0xFFD6E4F5),
        surfaceVariant = Color(0xFFD6E4F5),
        primary = Color(0xFF3B82F6),
        primaryContainer = Color(0xFF93C5FD),
        secondary = Color(0xFF93C5FD),
        onBackground = Color(0xFF1E293B),
        onSurface = Color(0xFF1E293B),
        onSurfaceVariant = Color(0xFF334155),
        onPrimary = Color.White,
        onPrimaryContainer = Color(0xFF1E293B),
        swatches = listOf(
            Color(0xFFE9EDF2),
            Color(0xFFD6E4F5),
            Color(0xFF93C5FD),
            Color(0xFF3B82F6),
            Color(0xFF1E293B),
        ),
    ),
)

internal val palettes: Map<String, AppColorPalette> =
    AllAppColorPalettes.associateBy(AppColorPalette::id)

internal fun normalizeAppColorPaletteId(id: String): String = when (id.lowercase()) {
    "modern_blue", "modern-blue", "ocean", "blue", "indigo", "default" -> "modern_blue"
    "purple_aurora", "purple-aurora", "violet", "amethyst", "rose", "messenger", "custom" -> "purple_aurora"
    "sunset_orange", "sunset-orange", "sunset", "warm", "coral", "amber", "blossom", "crimson" -> "sunset_orange"
    "forest_green", "forest-green", "emerald", "lagoon", "white" -> "forest_green"
    "light_clean", "light-clean", "light" -> "light_clean"
    else -> "modern_blue"
}

internal fun appColorScheme(paletteId: String, light: Boolean): ColorScheme {
    val palette = palettes[normalizeAppColorPaletteId(paletteId)]!!
    val background = if (light) {
        if (palette.id == "light_clean") palette.background else Color(0xFFF4F6FA)
    } else {
        palette.background
    }
    val surface = if (light) {
        if (palette.id == "light_clean") palette.surface else Color.White
    } else {
        palette.surface
    }
    val surfaceVariant = if (light) {
        if (palette.id == "light_clean") palette.surfaceVariant else Color(0xFFE5EBF3)
    } else {
        palette.surfaceVariant
    }
    val onBackground = if (light) Color(0xFF1E293B) else palette.onBackground
    val onSurface = if (light) Color(0xFF1E293B) else palette.onSurface
    val onSurfaceVariant = if (light && palette.id != "light_clean") {
        Color(0xFF475569)
    } else {
        palette.onSurfaceVariant
    }
    val onPrimary = if (light) Color.White else palette.onPrimary
    val onSecondary = if (palette.secondary.luminance() > 0.45f) {
        Color(0xFF1E293B)
    } else {
        Color.White
    }

    val arguments = PaletteSchemeValues(
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        onBackground = onBackground,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        onPrimary = onPrimary,
        primaryContainer = if (light && palette.id != "light_clean") surfaceVariant
        else palette.primaryContainer,
        onPrimaryContainer = if (light) Color(0xFF1E293B) else palette.onPrimaryContainer,
    )
    return if (light) {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = arguments.onPrimary,
            primaryContainer = arguments.primaryContainer,
            onPrimaryContainer = arguments.onPrimaryContainer,
            secondary = palette.secondary,
            onSecondary = onSecondary,
            secondaryContainer = arguments.primaryContainer,
            onSecondaryContainer = arguments.onPrimaryContainer,
            tertiary = palette.secondary,
            onTertiary = onBackground,
            background = arguments.background,
            onBackground = arguments.onBackground,
            surface = arguments.surface,
            onSurface = arguments.onSurface,
            surfaceContainerHigh = arguments.surfaceVariant,
            surfaceVariant = arguments.surfaceVariant,
            onSurfaceVariant = arguments.onSurfaceVariant,
            outline = palette.secondary,
            outlineVariant = arguments.surfaceVariant,
            error = Color(0xFFB3261E),
            onError = Color.White,
        )
    } else {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = arguments.onPrimary,
            primaryContainer = arguments.primaryContainer,
            onPrimaryContainer = arguments.onPrimaryContainer,
            secondary = palette.secondary,
            onSecondary = onSecondary,
            secondaryContainer = arguments.primaryContainer,
            onSecondaryContainer = arguments.onPrimaryContainer,
            tertiary = palette.secondary,
            onTertiary = palette.onPrimary,
            background = arguments.background,
            onBackground = arguments.onBackground,
            surface = arguments.surface,
            onSurface = arguments.onSurface,
            surfaceContainerHigh = arguments.surfaceVariant,
            surfaceVariant = arguments.surfaceVariant,
            onSurfaceVariant = arguments.onSurfaceVariant,
            outline = palette.secondary,
            outlineVariant = arguments.surfaceVariant,
            error = Color(0xFFEF4444),
            onError = Color.White,
        )
    }
}

private data class PaletteSchemeValues(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)
