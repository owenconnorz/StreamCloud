package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.ui.components.MovieArtwork
import com.streamcloud.app.ui.components.WatchedPosterBadge
import com.streamcloud.app.ui.icons.AppIcons as Icons
import com.streamcloud.app.ui.viewmodel.MobileSearchFilters
import com.streamcloud.app.ui.viewmodel.MobileSearchSort
import com.streamcloud.app.ui.viewmodel.MoviesState
import com.streamcloud.app.ui.viewmodel.filterMobileSearchTitles

private data class MobileBrowseTitle(
    val movie: TmdbMovie,
    val isTv: Boolean,
)

@Composable
internal fun MobileSearchBrowseContent(
    state: MoviesState,
    filters: MobileSearchFilters,
    watchedIds: Set<Long>,
    searchHistory: List<String>,
    onMovieClick: (Long) -> Unit,
    onTvClick: (Long) -> Unit,
    onSearchHistorySelected: (String) -> Unit,
    onRemoveSearchHistory: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filters.requiresDiscovery) {
        val shelves = listOf(
            "Trending" to state.trending,
            "Popular This Year" to state.popular,
            "Top Rated" to state.topRated,
            "New Releases" to state.nowPlaying,
        ).filter { it.second.isNotEmpty() }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (searchHistory.isNotEmpty()) {
                item(key = "mobile-search-history-title", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Recent searches",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        TextButton(onClick = onClearSearchHistory) { Text("Clear") }
                    }
                }
                item(key = "mobile-search-history-items", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 4.dp),
                    ) {
                        lazyItems(
                            items = searchHistory,
                            key = { "mobile-search-history-$it" },
                        ) { query ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = false,
                                    onClick = { onSearchHistorySelected(query) },
                                    label = {
                                        Text(
                                            query,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                                IconButton(
                                    onClick = { onRemoveSearchHistory(query) },
                                    modifier = Modifier.padding(start = 2.dp).size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove $query",
                                        modifier = Modifier.fillMaxSize(0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            shelves.forEachIndexed { shelfIndex, (title, movies) ->
                item(
                    key = "mobile-discovery-heading-$shelfIndex",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Text(
                        title,
                        modifier = Modifier.padding(top = if (shelfIndex == 0) 2.dp else 10.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                gridItems(
                    items = movies,
                    key = { "mobile-discovery-$shelfIndex-${it.id}" },
                ) { movie ->
                    MobileSearchPosterCard(
                        movie = movie,
                        isTv = false,
                        isWatched = movie.id in watchedIds,
                        onClick = { onMovieClick(movie.id) },
                    )
                }
            }
            if (shelves.isEmpty()) {
                item(
                    key = "mobile-discovery-loading",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                "Discovery titles are not available yet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        return
    }

    val results = buildList {
        addAll(
            filterMobileSearchTitles(
                items = state.mobileSearchBrowseMovies,
                isTv = false,
                filters = filters,
                watchedIds = watchedIds,
            ).map { MobileBrowseTitle(it, isTv = false) },
        )
        addAll(
            filterMobileSearchTitles(
                items = state.mobileSearchBrowseTv,
                isTv = true,
                filters = filters,
                watchedIds = watchedIds,
            ).map { MobileBrowseTitle(it, isTv = true) },
        )
    }.let { items ->
        when (filters.sort) {
            MobileSearchSort.Popular -> items.sortedByDescending { it.movie.popularity }
            MobileSearchSort.TopRated -> items.sortedWith(
                compareByDescending<MobileBrowseTitle> { it.movie.voteAverage }
                    .thenByDescending { it.movie.voteCount },
            )
            MobileSearchSort.Newest -> items.sortedByDescending {
                (if (it.isTv) it.movie.firstAirDate else it.movie.releaseDate).orEmpty()
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.mobileSearchBrowseError != null && results.isNotEmpty()) {
            item(key = "mobile-search-partial-error", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    state.mobileSearchBrowseError,
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        gridItems(
            items = results,
            key = { "mobile-filtered-${if (it.isTv) "tv" else "movie"}-${it.movie.id}" },
        ) { result ->
            MobileSearchPosterCard(
                movie = result.movie,
                isTv = result.isTv,
                isWatched = result.movie.id in watchedIds,
                onClick = {
                    if (result.isTv) onTvClick(result.movie.id) else onMovieClick(result.movie.id)
                },
            )
        }
        if (results.isEmpty()) {
            item(key = "mobile-search-browse-status", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        state.mobileSearchBrowseLoading -> CircularProgressIndicator()
                        state.mobileSearchBrowseError != null -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                state.mobileSearchBrowseError,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onRetry) { Text("Retry") }
                        }
                        else -> Text(
                            "No titles match these filters.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileSearchPosterCard(
    movie: TmdbMovie,
    isTv: Boolean,
    isWatched: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            MovieArtwork(
                primaryUrl = movie.posterUrl,
                fallbackUrl = movie.backdropUrl,
                contentDescription = movie.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isWatched) {
                WatchedPosterBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            movie.displayTitle,
            modifier = Modifier.padding(horizontal = 1.dp),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val year = (if (isTv) movie.firstAirDate else movie.releaseDate)
            ?.takeIf { it.length >= 4 }
            ?.take(4)
        Text(
            text = buildString {
                append(if (isTv) "TV Show" else "Movie")
                year?.let { append(" · ").append(it) }
            },
            modifier = Modifier.padding(horizontal = 1.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
