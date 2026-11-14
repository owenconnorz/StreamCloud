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
        label = "Cinematic Blue",
        background = Color(0xFF071522),
        surface = Color(0xFF0B1E2C),
        surfaceVariant = Color(0xFF123247),
        primary = Color(0xFF13A9D8),
        primaryContainer = Color(0xFF0B5878),
        secondary = Color(0xFF62D5F2),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFB7CDD8),
        onPrimary = Color(0xFF06202B),
        onPrimaryContainer = Color.White,
        swatches = listOf(
            Color(0xFF071522),
            Color(0xFF0B1E2C),
            Color(0xFF123247),
            Color(0xFF13A9D8),
            Color.White,
            Color(0xFFB7CDD8),
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
        secondary = Color(0xFFA855F7),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFD4C6F1),
        onPrimary = Color.White,
        onPrimaryContainer = Color.White,
        swatches = listOf(
            Color(0xFF2A1F4B),
            Color(0xFF3B2A6B),
            Color(0xFF5D3FD3),
            Color(0xFFA855F7),
            Color.White,
            Color(0xFFD4C6F1),
        ),
    ),
    AppColorPalette(
        id = "sunset_orange",
        label = "Sunset Orange",
        background = Color(0xFF3D1E0F),
        surface = Color(0xFF6B3118),
        surfaceVariant = Color(0xFFF97316),
        primary = Color(0xFFFDBA74),
        primaryContainer = Color(0xFFF97316),
        secondary = Color(0xFFFDBA74),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFF3D7B8),
        onPrimary = Color(0xFF3D1E0F),
        onPrimaryContainer = Color.White,
        swatches = listOf(
            Color(0xFF3D1E0F),
            Color(0xFF6B3118),
            Color(0xFFF97316),
            Color(0xFFFDBA74),
            Color.White,
            Color(0xFFF3D7B8),
        ),
    ),
    AppColorPalette(
        id = "forest_green",
        label = "Forest Green",
        background = Color(0xFF0F2F2A),
        surface = Color(0xFF1B4D43),
        surfaceVariant = Color(0xFF2DD4BF),
        primary = Color(0xFF86EFAC),
        primaryContainer = Color(0xFF2DD4BF),
        secondary = Color(0xFF86EFAC),
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFCFE8D9),
        onPrimary = Color(0xFF0F2F2A),
        onPrimaryContainer = Color.White,
        swatches = listOf(
            Color(0xFF0F2F2A),
            Color(0xFF1B4D43),
            Color(0xFF2DD4BF),
            Color(0xFF86EFAC),
            Color.White,
            Color(0xFFCFE8D9),
        ),
    ),
    AppColorPalette(
        id = "light_clean",
        label = "Light Clean",
        background = Color(0xFFE9EDF2),
        surface = Color(0xFFD6E4F5),
        surfaceVariant = Color(0xFF93C5FD),
        primary = Color(0xFF3B82F6),
        primaryContainer = Color(0xFF93C5FD),
        secondary = Color(0xFF3B82F6),
        onBackground = Color(0xFF1E293B),
        onSurface = Color(0xFF1E293B),
        onSurfaceVariant = Color(0xFF64748B),
        onPrimary = Color(0xFF1E293B),
        onPrimaryContainer = Color(0xFF1E293B),
        swatches = listOf(
            Color(0xFFE9EDF2),
            Color(0xFFD6E4F5),
            Color(0xFF93C5FD),
            Color(0xFF3B82F6),
            Color(0xFF1E293B),
            Color(0xFF64748B),
        ),
    ),
    AppColorPalette(
        id = "monochrome",
        label = "Black & White",
        background = Color(0xFF08090B),
        surface = Color(0xFF101113),
        surfaceVariant = Color(0xFF292B2F),
        primary = Color(0xFFF2F2F2),
        primaryContainer = Color(0xFFE4E4E4),
        secondary = Color(0xFFC5C6C8),
        onBackground = Color(0xFFF1F1F1),
        onSurface = Color(0xFFE8E8E9),
        onSurfaceVariant = Color(0xFFB8B9BC),
        onPrimary = Color(0xFF111214),
        onPrimaryContainer = Color(0xFF111214),
        swatches = listOf(
            Color(0xFF08090B),
            Color(0xFF101113),
            Color(0xFF292B2F),
            Color(0xFF77797D),
            Color(0xFFCACBCD),
            Color(0xFFF2F2F2),
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
    "monochrome", "black_white", "black-and-white", "grayscale", "greyscale" -> "monochrome"
    else -> "modern_blue"
}

internal fun appColorScheme(paletteId: String, light: Boolean): ColorScheme {
    return buildAppColorScheme(palettes[normalizeAppColorPaletteId(paletteId)]!!, light)
}

internal fun legacyAppColorScheme(paletteId: String, light: Boolean): ColorScheme {
    val current = palettes[normalizeAppColorPaletteId(paletteId)]!!
    val legacy = when (current.id) {
        "modern_blue" -> current.copy(
            primaryContainer = Color(0xFF0B5878),
            onSurfaceVariant = Color(0xFFB7CDD8),
        )
        "purple_aurora" -> current.copy(
            secondary = Color(0xFF5D3FD3),
            onSurfaceVariant = Color(0xFFE5DDF5),
        )
        "sunset_orange" -> current.copy(
            surfaceVariant = Color(0xFF6B3118),
            primary = Color(0xFFF97316),
            primaryContainer = Color(0xFFFDBA74),
            onSurfaceVariant = Color(0xFFFFE0C2),
            onPrimaryContainer = Color(0xFF3D1E0F),
        )
        "forest_green" -> current.copy(
            surfaceVariant = Color(0xFF1B4D43),
            primary = Color(0xFF2DD4BF),
            primaryContainer = Color(0xFF86EFAC),
            onSurfaceVariant = Color(0xFFD3F2E3),
            onPrimaryContainer = Color(0xFF0F2F2A),
        )
        "light_clean" -> current.copy(
            surfaceVariant = Color(0xFFD6E4F5),
            secondary = Color(0xFF93C5FD),
            onSurfaceVariant = Color(0xFF334155),
            onPrimary = Color.White,
        )
        else -> current
    }
    return buildAppColorScheme(
        palette = if (light) legacy.copy(onPrimary = Color.White) else legacy,
        light = light,
    )
}

private fun buildAppColorScheme(palette: AppColorPalette, light: Boolean): ColorScheme {
    val isMonochrome = palette.id == "monochrome"
    val background = if (light) {
        when {
            palette.id == "light_clean" -> palette.background
            isMonochrome -> Color(0xFFF6F6F6)
            palette.id == "modern_blue" -> Color(0xFFF0F7FA)
            else -> Color(0xFFF4F6FA)
        }
    } else {
        palette.background
    }
    val surface = if (light) {
        if (palette.id == "light_clean") palette.surface else Color.White
    } else {
        palette.surface
    }
    val surfaceVariant = if (light) {
        when {
            palette.id == "light_clean" -> palette.surfaceVariant
            isMonochrome -> Color(0xFFE4E4E4)
            palette.id == "modern_blue" -> Color(0xFFDDEEF4)
            else -> Color(0xFFE5EBF3)
        }
    } else {
        palette.surfaceVariant
    }
    val primary = when {
        light && isMonochrome -> Color(0xFF151515)
        light && palette.id == "modern_blue" -> Color(0xFF08789D)
        else -> palette.primary
    }
    val onPrimary = when {
        light && (isMonochrome || palette.id == "modern_blue") -> Color.White
        else -> palette.onPrimary
    }
    val onBackground = if (light) {
        if (isMonochrome) Color(0xFF171717) else Color(0xFF1E293B)
    } else {
        palette.onBackground
    }
    val onSurface = if (light) {
        if (isMonochrome) Color(0xFF171717) else Color(0xFF1E293B)
    } else {
        palette.onSurface
    }
    val onSurfaceVariant = if (light && isMonochrome) {
        Color(0xFF595A5D)
    } else if (light && palette.id != "light_clean") {
        Color(0xFF475569)
    } else {
        palette.onSurfaceVariant
    }
    val onPrimaryContainer = if (light && isMonochrome) Color(0xFF171717)
    else if (light) Color(0xFF163444)
    else palette.onPrimaryContainer
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
        onPrimaryContainer = onPrimaryContainer,
    )
    return if (light) {
        lightColorScheme(
            primary = primary,
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
            primary = primary,
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
