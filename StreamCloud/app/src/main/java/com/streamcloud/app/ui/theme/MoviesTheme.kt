package com.streamcloud.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MoviesThemePalette(
    val name: String,
    val displayName: String,
    val primary: Color,
    val primaryVariant: Color,
    val background: Color,
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val isLight: Boolean = false,
    val gradientColors: List<Color>? = null,
)

val AllMoviesThemes: List<MoviesThemePalette> = listOf(
    MoviesThemePalette(
        name = "white", displayName = "White",
        primary = Color(0xFF222222), primaryVariant = Color(0xFF888888),
        background = Color(0xFFFFFFFF), backgroundElevated = Color(0xFFF5F5F5),
        backgroundCard = Color(0xFFEEEEEE), isLight = true,
    ),
    MoviesThemePalette(
        name = "crimson", displayName = "Crimson",
        primary = Color(0xFFDC143C), primaryVariant = Color(0xFF8B0000),
        background = Color(0xFF0D0005), backgroundElevated = Color(0xFF1A000A),
        backgroundCard = Color(0xFF220010),
    ),
    MoviesThemePalette(
        name = "ocean", displayName = "Ocean",
        primary = Color(0xFF1E90FF), primaryVariant = Color(0xFF0050A0),
        background = Color(0xFF00050D), backgroundElevated = Color(0xFF000D1A),
        backgroundCard = Color(0xFF001222),
    ),
    MoviesThemePalette(
        name = "violet", displayName = "Violet",
        primary = Color(0xFF7C5CFF), primaryVariant = Color(0xFF4A3A99),
        background = Color(0xFF07050F), backgroundElevated = Color(0xFF12101E),
        backgroundCard = Color(0xFF1C1826),
    ),
    MoviesThemePalette(
        name = "emerald", displayName = "Emerald",
        primary = Color(0xFF2ECC71), primaryVariant = Color(0xFF1A7A44),
        background = Color(0xFF000D05), backgroundElevated = Color(0xFF001A0A),
        backgroundCard = Color(0xFF002210),
    ),
    MoviesThemePalette(
        name = "amber", displayName = "Amber",
        primary = Color(0xFFFFC107), primaryVariant = Color(0xFFB8860B),
        background = Color(0xFF0D0900), backgroundElevated = Color(0xFF1A1200),
        backgroundCard = Color(0xFF221800),
    ),
    MoviesThemePalette(
        name = "rose", displayName = "Rose",
        primary = Color(0xFFFF6B9D), primaryVariant = Color(0xFFA03060),
        background = Color(0xFF0D0008), backgroundElevated = Color(0xFF1A0012),
        backgroundCard = Color(0xFF22001A),
    ),
    MoviesThemePalette(
        name = "messenger", displayName = "Messenger",
        primary = Color(0xFF0084FF), primaryVariant = Color(0xFFAA00FF),
        background = Color(0xFF00050D), backgroundElevated = Color(0xFF000D1A),
        backgroundCard = Color(0xFF001230),
        gradientColors = listOf(Color(0xFF0084FF), Color(0xFFAA00FF)),
    ),
    MoviesThemePalette(
        name = "amethyst", displayName = "Amethyst",
        primary = Color(0xFF9B59B6), primaryVariant = Color(0xFF6C3483),
        background = Color(0xFF08050D), backgroundElevated = Color(0xFF110A1A),
        backgroundCard = Color(0xFF190F24),
        gradientColors = listOf(Color(0xFF9B59B6), Color(0xFF6C3483)),
    ),
    MoviesThemePalette(
        name = "blossom", displayName = "Blossom",
        primary = Color(0xFFFF85A1), primaryVariant = Color(0xFFFF4D79),
        background = Color(0xFF0D0508), backgroundElevated = Color(0xFF1A0A12),
        backgroundCard = Color(0xFF220F18),
        gradientColors = listOf(Color(0xFFFF85A1), Color(0xFFFF4D79)),
    ),
    MoviesThemePalette(
        name = "lagoon", displayName = "Lagoon",
        primary = Color(0xFF00BCD4), primaryVariant = Color(0xFF006064),
        background = Color(0xFF00080D), backgroundElevated = Color(0xFF00101A),
        backgroundCard = Color(0xFF001822),
        gradientColors = listOf(Color(0xFF00BCD4), Color(0xFF006064)),
    ),
    MoviesThemePalette(
        name = "sunset", displayName = "Sunset",
        primary = Color(0xFFFF6B35), primaryVariant = Color(0xFFFF1493),
        background = Color(0xFF0D0500), backgroundElevated = Color(0xFF1A0A00),
        backgroundCard = Color(0xFF220F00),
        gradientColors = listOf(Color(0xFFFF6B35), Color(0xFFFF1493)),
    ),
    MoviesThemePalette(
        name = "custom", displayName = "Custom",
        primary = Color(0xFFFF0080), primaryVariant = Color(0xFF7700FF),
        background = Color(0xFF07000D), backgroundElevated = Color(0xFF0F001A),
        backgroundCard = Color(0xFF180024),
        gradientColors = listOf(Color(0xFFFF0080), Color(0xFF7700FF), Color(0xFF0084FF)),
    ),
)

fun moviesThemeByName(name: String): MoviesThemePalette =
    AllMoviesThemes.firstOrNull { it.name == name }
        ?: AllMoviesThemes.first { it.name == "violet" }

fun moviesColorScheme(name: String): ColorScheme {
    val t = moviesThemeByName(name)
    return if (t.isLight) {
        lightColorScheme(
            primary          = t.primary,
            onPrimary        = Color.White,
            primaryContainer = t.backgroundCard,
            secondary        = t.primaryVariant,
            onSecondary      = Color.White,
            background       = t.background,
            onBackground     = Color(0xFF1A1A1A),
            surface          = t.backgroundElevated,
            onSurface        = Color(0xFF1A1A1A),
            onSurfaceVariant = Color(0xFF555555),
            surfaceVariant   = t.backgroundCard,
            tertiary         = t.primary,
        )
    } else {
        darkColorScheme(
            primary          = t.primary,
            onPrimary        = Color.White,
            primaryContainer = t.backgroundCard,
            secondary        = t.primaryVariant,
            onSecondary      = Color.White,
            background       = t.background,
            onBackground     = Color.White,
            surface          = t.backgroundElevated,
            onSurface        = Color.White,
            onSurfaceVariant = Color(0xFFAAAAAA),
            surfaceVariant   = t.backgroundCard,
            tertiary         = t.primary,
            error            = Color(0xFFCF6679),
        )
    }
}

@Composable
fun MoviesThemeWrapper(themeName: String, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = moviesColorScheme(themeName),
        typography  = MaterialTheme.typography,
        content     = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoviesThemePicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val classicThemes  = AllMoviesThemes.take(7)
    val enhancedThemes = AllMoviesThemes.drop(7)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "Classic themes",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            classicThemes.forEach { theme ->
                ThemeDot(theme = theme, isSelected = selected == theme.name, onSelect = onSelect)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Enhanced themes",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            enhancedThemes.forEach { theme ->
                ThemeDot(theme = theme, isSelected = selected == theme.name, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun ThemeDot(
    theme: MoviesThemePalette,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
) {
    val gradients = theme.gradientColors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable { onSelect(theme.name) }
            .padding(4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(
                    if (gradients != null)
                        Modifier.background(
                            Brush.linearGradient(gradients),
                            CircleShape,
                        )
                    else
                        Modifier.background(theme.primary, CircleShape)
                )
                .then(
                    if (isSelected)
                        Modifier.border(2.dp, Color.White, CircleShape)
                    else
                        Modifier
                ),
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            theme.displayName,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
