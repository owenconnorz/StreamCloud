package com.streamcloud.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamcloud.app.ui.icons.AppIcons as Icons
import com.streamcloud.app.ui.viewmodel.MobileSearchFilters
import com.streamcloud.app.ui.viewmodel.MobileSearchGenreMode
import com.streamcloud.app.ui.viewmodel.MobileSearchMediaType
import com.streamcloud.app.ui.viewmodel.MobileSearchSort
import com.streamcloud.app.ui.viewmodel.mobileSearchGenres
import java.util.Calendar
import java.util.Locale

internal enum class MobileSearchFilterPanel(val title: String) {
    Genre("Genres"),
    Sort("Sort"),
    Rating("Rating"),
    Year("Year"),
    AgeRating("Age rating"),
    Language("Language"),
}

@Composable
internal fun MobileSearchFilterBar(
    filters: MobileSearchFilters,
    onFiltersChanged: (MobileSearchFilters) -> Unit,
    onPanelSelected: (MobileSearchFilterPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val genreLabel = when (filters.genres.size) {
        0 -> "Genre"
        1 -> mobileSearchGenres.firstOrNull { it.id in filters.genres }?.label ?: "Genre"
        else -> "Genre · ${filters.genres.size}"
    }
    val ratingLabel = if (filters.minRating > 0.0 || filters.maxRating < 10.0) {
        "${displayRating(filters.minRating)}–${displayRating(filters.maxRating)}"
    } else {
        "Rating"
    }
    val yearLabel = when {
        filters.exactYear != null -> filters.exactYear.toString()
        filters.yearStart != null -> "${filters.yearStart}s"
        filters.yearEnd != null -> "Before ${filters.yearEnd}"
        else -> "Year"
    }
    val ageLabel = filters.ageRating ?: "Age rating"
    val languageLabel = filters.language?.let(::languageName) ?: "Language"

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "search-type-tabs") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MobileSearchMediaType.values().forEach { type ->
                    FilterChip(
                        selected = filters.mediaType == type,
                        onClick = { onFiltersChanged(filters.copy(mediaType = type)) },
                        label = { Text(type.label, maxLines = 1) },
                    )
                }
            }
        }
        item(key = "search-genre-filter") {
            MobileFilterChip(genreLabel, filters.genres.isNotEmpty()) {
                onPanelSelected(MobileSearchFilterPanel.Genre)
            }
        }
        item(key = "search-sort-filter") {
            MobileFilterChip(
                filters.sort.label,
                filters.sort != MobileSearchSort.Popular,
            ) { onPanelSelected(MobileSearchFilterPanel.Sort) }
        }
        item(key = "search-rating-filter") {
            MobileFilterChip(
                ratingLabel,
                filters.minRating > 0.0 || filters.maxRating < 10.0 ||
                    filters.minimumVotes > 0,
            ) { onPanelSelected(MobileSearchFilterPanel.Rating) }
        }
        item(key = "search-year-filter") {
            MobileFilterChip(
                yearLabel,
                filters.yearStart != null || filters.yearEnd != null ||
                    filters.exactYear != null,
            ) { onPanelSelected(MobileSearchFilterPanel.Year) }
        }
        item(key = "search-age-filter") {
            MobileFilterChip(ageLabel, filters.ageRating != null) {
                onPanelSelected(MobileSearchFilterPanel.AgeRating)
            }
        }
        item(key = "search-language-filter") {
            MobileFilterChip(languageLabel, filters.language != null) {
                onPanelSelected(MobileSearchFilterPanel.Language)
            }
        }
        item(key = "search-hide-watched-filter") {
            MobileFilterChip("Hide watched", filters.hideWatched) {
                onFiltersChanged(filters.copy(hideWatched = !filters.hideWatched))
            }
        }
    }
}

@Composable
private fun MobileFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileSearchFilterSheet(
    panel: MobileSearchFilterPanel,
    filters: MobileSearchFilters,
    onFiltersChanged: (MobileSearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    panel.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                )
                TextButton(
                    onClick = {
                        onFiltersChanged(MobileSearchFilters(mediaType = filters.mediaType))
                    },
                ) {
                    Text("Reset")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close filters")
                }
            }
            when (panel) {
                MobileSearchFilterPanel.Genre -> GenreFilterContent(filters, onFiltersChanged)
                MobileSearchFilterPanel.Sort -> SortFilterContent(filters, onFiltersChanged)
                MobileSearchFilterPanel.Rating -> RatingFilterContent(filters, onFiltersChanged)
                MobileSearchFilterPanel.Year -> YearFilterContent(filters, onFiltersChanged)
                MobileSearchFilterPanel.AgeRating -> AgeRatingFilterContent(filters, onFiltersChanged)
                MobileSearchFilterPanel.Language -> LanguageFilterContent(filters, onFiltersChanged)
            }
        }
    }
}

@Composable
private fun GenreFilterContent(
    filters: MobileSearchFilters,
    onFiltersChanged: (MobileSearchFilters) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MobileFilterChip(
            "AND",
            filters.genreMode == MobileSearchGenreMode.And,
        ) { onFiltersChanged(filters.copy(genreMode = MobileSearchGenreMode.And)) }
        MobileFilterChip(
            "OR",
            filters.genreMode == MobileSearchGenreMode.Or,
        ) { onFiltersChanged(filters.copy(genreMode = MobileSearchGenreMode.Or)) }
    }
    Spacer(Modifier.height(8.dp))
    MobileFilterOptionGrid(
        options = mobileSearchGenres.map { it.id to it.label },
        selected = filters.genres,
        enabled = { id ->
            filters.mediaType != MobileSearchMediaType.TvShows ||
                mobileSearchGenres.firstOrNull { it.id == id }?.tvId != null
        },
        onClick = { id ->
            val selected = if (id in filters.genres) filters.genres - id else filters.genres + id
            onFiltersChanged(filters.copy(genres = selected))
        },
    )
    if (filters.mediaType == MobileSearchMediaType.TvShows) {
        FilterHint("Some movie genres are not available for TV.")
    }
    FilterHint("AND requires every selected genre; OR matches any selected genre.")
}

@Composable
private fun SortFilterContent(
    filters: MobileSearchFilters,
    onFiltersChanged: (MobileSearchFilters) -> Unit,
) {
    MobileFilterOptionGrid(
        options = MobileSearchSort.values().mapIndexed { index, sort -> index to sort.label },
        selected = setOf(MobileSearchSort.values().indexOf(filters.sort)),
        columns = 3,
        onClick = { id ->
            MobileSearchSort.values()
                .getOrNull(id)
                ?.let { onFiltersChanged(filters.copy(sort = it)) }
        },
    )
    FilterHint("The result list updates when you choose a sort order.")
}

@Composable
private fun RatingFilterContent(
    filters: MobileSearchFilters,
    onFiltersChanged: (MobileSearchFilters) -> Unit,
) {
    Text(
        "${displayRating(filters.minRating)} – ${displayRating(filters.maxRating)}",
        style = MaterialTheme.typography.titleMedium,
    )
    RangeSlider(
        value = filters.minRating.toFloat()..filters.maxRating.toFloat(),
        onValueChange = { range ->
            onFiltersChanged(
                filters.copy(
                    minRating = range.start.toDouble(),
                    maxRating = range.endInclusive.toDouble(),
                ),
            )
        },
        valueRange = 0f..10f,
        steps = 39,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("0", style = MaterialTheme.typography.labelSmall)
        Text("2.5", style = MaterialTheme.typography.labelSmall)
        Text("5", style = MaterialTheme.typography.labelSmall)
        Text("7.5", style = MaterialTheme.typography.labelSmall)
        Text("10", style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(12.dp))
    Text("Minimum votes", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    MobileFilterOptionGrid(
        options = listOf(0 to "Any", 50 to "50+", 100 to "100+", 500 to "500+", 1000 to "1000+"),
        selected = setOf(filters.minimumVotes),
        columns = 3,
        onClick = { onFiltersChanged(filters.copy(minimumVotes = it)) },
    )
    FilterHint("Rating is a whole-point TMDB score; votes help avoid low-sample titles.")
}

@Composable
private fun YearFilterContent(
    filters: MobileSearchFilters,
    onFiltersChanged: (MobileSearchFilters) -> Unit,
) {
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val decades = remember(currentYear) {
        listOf(
            YearChoice("Any", null, null),
            YearChoice("2020s", 2020, currentYear),
            YearChoice("2010s", 2010, 2019),
            YearChoice("2000s", 2000, 2009),
            YearChoice("90s", 1990, 1999),
            YearChoice("80s", 1980, 1989),
            YearChoice("Older", null, 1979),
        )
    }
    val selectedDecade = decades.firstOrNull { choice ->
        if (filters.exactYear != null) {
            filters.exactYear in (choice.start ?: Int.MIN_VALUE)..(choice.end ?: Int.MAX_VALUE)
        } else {
            choice.start == filters.yearStart && choice.end == filters.yearEnd
        }
    }
    Text("Decade", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    MobileFilterOptionGrid(
        options = decades.mapIndexed { index, decade -> index to decade.label },
        selected = setOfNotNull(decades.indexOf(selectedDecade).takeIf { it >= 0 }),
        columns = 4,
        onClick = { index ->
            val choice = decades[index]
            onFiltersChanged(
                filters.copy(
                    yearStart = choice.start,
                    yearEnd = choice.end,
                    exactYear = null,
                ),
            )
        },
    )
    if (selectedDecade != null && selectedDecade.start != null) {
        val end = minOf(selectedDecade.end ?: currentYear, currentYear)
        if (end >= selectedDecade.start) {
            Spacer(Modifier.height(12.dp))
            Text("Exact year", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            MobileFilterOptionGrid(
                options = (end downTo selectedDecade.start).map { it to it.toString() },
                selected = filters.exactYear?.let(::setOf) ?: emptySet(),
                columns = 4,
                onClick = { year ->
                    onFiltersChanged(
                        filters.copy(exactYear = if (filters.exactYear == year) null else year),
                    )
                },
            )
        }
    }
    FilterHint("Choose a decade, then optionally narrow it to one year.")
}

private data class YearChoice(
    val label: String,
    val start: Int?,
    val end: Int?,
)

@Composable
private fun AgeRatingFilterContent(
    filters: MobileSearchFilters,
    onFiltersChanged: (MobileSearchFilters) -> Unit,
) {
    val ratings = listOf(null to "Any", "G" to "G", "PG" to "PG", "PG-13" to "PG-13", "R" to "R", "NC-17" to "NC-17")
    MobileFilterOptionGrid(
        options = ratings.mapIndexed { index, (_, label) -> index to label },
        selected = setOf(ratings.indexOfFirst { it.first == filters.ageRating }),
        columns = 3,
        enabled = { filters.mediaType != MobileSearchMediaType.TvShows },
        onClick = { index -> onFiltersChanged(filters.copy(ageRating = ratings[index].first)) },
    )
    FilterHint(
        if (filters.mediaType == MobileSearchMediaType.TvShows) {
            "TMDB does not provide this movie rating filter for TV series."
        } else {
            "Uses US movie certifications for discovery; title search has no certification data."
        },
    )
}

@Composable
private fun LanguageFilterContent(
    filters: MobileSearchFilters,
    onFiltersChanged: (MobileSearchFilters) -> Unit,
) {
    val languages = listOf(
        null to "Any",
        "en" to "English",
        "ja" to "Japanese",
        "ko" to "Korean",
        "hi" to "Hindi",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
    )
    MobileFilterOptionGrid(
        options = languages.mapIndexed { index, (_, label) -> index to label },
        selected = setOf(languages.indexOfFirst { it.first == filters.language }),
        columns = 3,
        onClick = { index -> onFiltersChanged(filters.copy(language = languages[index].first)) },
    )
    FilterHint("Filters by the original language of a title.")
}

@Composable
private fun MobileFilterOptionGrid(
    options: List<Pair<Int, String>>,
    selected: Set<Int>,
    columns: Int = 3,
    enabled: (Int) -> Boolean = { true },
    onClick: (Int) -> Unit,
) {
    options.chunked(columns).forEach { rowOptions ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            rowOptions.forEach { (id, label) ->
                FilterChip(
                    selected = id in selected,
                    onClick = { onClick(id) },
                    enabled = enabled(id),
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
            repeat(columns - rowOptions.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FilterHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun displayRating(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")

private fun languageName(code: String): String = when (code) {
    "en" -> "English"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "hi" -> "Hindi"
    "es" -> "Spanish"
    "fr" -> "French"
    "de" -> "German"
    else -> code.uppercase(Locale.ROOT)
}
