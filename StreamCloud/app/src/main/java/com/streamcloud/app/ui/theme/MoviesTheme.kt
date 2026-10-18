package com.streamcloud.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamcloud.app.ui.icons.AppIcons as Icons
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup

data class MovieThemeEntry(
    val id: String,
    val label: String,
    val primary: Color,
    val container: Color,
    val secondary: Color,
    val onPrimary: Color,
    val onPrimaryContainer: Color,
)

val AllMoviesThemes: List<MovieThemeEntry> = AllAppColorPalettes.map { palette ->
    MovieThemeEntry(
        id = palette.id,
        label = palette.label,
        primary = palette.primary,
        container = palette.primaryContainer,
        secondary = palette.secondary,
        onPrimary = palette.onPrimary,
        onPrimaryContainer = palette.onPrimaryContainer,
    )
}

@Composable
fun MoviesThemeWrapper(themeName: String, content: @Composable () -> Unit) {
    val entry = AllMoviesThemes.find { it.id == normalizeAppColorPaletteId(themeName) }
        ?: AllMoviesThemes.first()
    val baseColors = MaterialTheme.colorScheme
    val colors = baseColors.copy(
        primary = entry.primary,
        onPrimary = entry.onPrimary,
        primaryContainer = entry.container,
        onPrimaryContainer = entry.onPrimaryContainer,
        secondary = entry.secondary,
        onSecondary = entry.onPrimary,
        secondaryContainer = entry.container,
        onSecondaryContainer = entry.onPrimaryContainer,
        tertiary = entry.secondary,
        onTertiary = entry.onPrimary,
    )
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
    val selectedId = normalizeAppColorPaletteId(selected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .tvFocusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AllAppColorPalettes.forEach { palette ->
            val isSelected = selectedId == palette.id
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .width(128.dp)
                    .clip(shape)
                    .background(palette.surface)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) palette.primary else palette.surfaceVariant,
                        shape = shape,
                    )
                    .tvFocusBorder(shape)
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(palette.id) },
                        role = Role.RadioButton,
                    )
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    palette.swatches.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(0.75.dp, palette.onSurface.copy(alpha = 0.3f), CircleShape),
                        )
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = palette.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    palette.label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = palette.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
