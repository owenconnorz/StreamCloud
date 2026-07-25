package com.streamcloud.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class MovieThemeEntry(
    val id: String,
    val label: String,
    val primary: Color,
    val container: Color,
    val brush: Brush? = null,
)

val AllMoviesThemes: List<MovieThemeEntry> = listOf(
    MovieThemeEntry("white",    "White",    Color(0xFF1B7A3A), Color(0xFF0A3A18)),
    MovieThemeEntry("crimson",  "Crimson",  Color(0xFFDC2626), Color(0xFF8B0A0A)),
    MovieThemeEntry("ocean",    "Ocean",    Color(0xFF2196F3), Color(0xFF0D5A8A)),
    MovieThemeEntry("violet",   "Violet",   Color(0xFF7B54C2), Color(0xFF3E2070)),
    MovieThemeEntry("emerald",  "Emerald",  Color(0xFF22C55E), Color(0xFF166534)),
    MovieThemeEntry("amber",    "Amber",    Color(0xFFF59E0B), Color(0xFF8B5E0A)),
    MovieThemeEntry("rose",     "Rose",     Color(0xFFEC4899), Color(0xFF8B2554)),
    MovieThemeEntry("messenger","Messenger",Color(0xFF5B7AEA), Color(0xFF2D3192),
        Brush.linearGradient(listOf(Color(0xFF5B7AEA), Color(0xFF8B5CF6)))),
    MovieThemeEntry("amethyst", "Amethyst", Color(0xFF9B59B6), Color(0xFF6C3483)),
    MovieThemeEntry("blossom",  "Blossom",  Color(0xFFFF7B54), Color(0xFFCC3C1A),
        Brush.linearGradient(listOf(Color(0xFFFF7B54), Color(0xFFFFB347)))),
    MovieThemeEntry("lagoon",   "Lagoon",   Color(0xFF14B8A6), Color(0xFF0E6655)),
    MovieThemeEntry("sunset",   "Sunset",   Color(0xFFE8622D), Color(0xFF8B2A05),
        Brush.linearGradient(listOf(Color(0xFFE8622D), Color(0xFFF5A623)))),
    MovieThemeEntry("custom",   "Custom",   Color(0xFF9333EA), Color(0xFF5B21B6),
        Brush.sweepGradient(listOf(Color(0xFF6366F1), Color(0xFFEC4899), Color(0xFF8B5CF6)))),
)

private fun buildMoviesColorScheme(primary: Color, container: Color): ColorScheme =
    darkColorScheme(
        primary              = primary,
        onPrimary            = Color.White,
        primaryContainer     = container,
        onPrimaryContainer   = Color.White,
        secondary            = primary,
        onSecondary          = Color.White,
        tertiary             = primary,
        onTertiary           = Color.White,
        background           = Bg,
        onBackground         = TextPrimary,
        surface              = BgElevated,
        onSurface            = TextPrimary,
        surfaceVariant       = BgSurface,
        onSurfaceVariant     = TextSecondary,
        outline              = Outline,
        outlineVariant       = Outline,
        error                = Color(0xFFEF4444),
        onError              = Color.White,
    )

@Composable
fun MoviesThemeWrapper(themeName: String, content: @Composable () -> Unit) {
    val entry = AllMoviesThemes.find { it.id == themeName }
        ?: AllMoviesThemes.find { it.id == "violet" }!!
    val colors = buildMoviesColorScheme(entry.primary, entry.container)
    MaterialTheme(
        colorScheme = colors,
        typography  = MaterialTheme.typography,
        content     = content,
    )
}

@Composable
fun MoviesThemePicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val classicThemes  = AllMoviesThemes.take(7)
    val enhancedThemes = AllMoviesThemes.drop(7)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThemeSwatchRow("Classic themes",  classicThemes,  selected, onSelect)
        ThemeSwatchRow("Enhanced themes", enhancedThemes, selected, onSelect)
    }
}

@Composable
private fun ThemeSwatchRow(
    title: String,
    themes: List<MovieThemeEntry>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            themes.forEach { theme ->
                val isSelected = selected == theme.id
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onSelect(theme.id) },
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .then(
                                if (theme.brush != null)
                                    Modifier.background(theme.brush)
                                else
                                    Modifier.background(theme.primary)
                            )
                            .then(
                                if (isSelected)
                                    Modifier.border(2.5.dp, Color.White, CircleShape)
                                else
                                    Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        theme.label,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onBackground
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
