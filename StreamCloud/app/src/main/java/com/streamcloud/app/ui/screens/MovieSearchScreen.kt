package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.ui.theme.MoviesThemeWrapper
import com.streamcloud.app.ui.viewmodel.CsSearchResult
import com.streamcloud.app.ui.viewmodel.MoviesViewModel
import com.streamcloud.app.ui.viewmodel.StremioSearchResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieSearchScreen(
    onBack: () -> Unit,
    onMovieClick: (Long) -> Unit,
    onTvClick: (Long) -> Unit = {},
    onOpenCsItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit = { _, _, _, _ -> },
    onOpenStremio: (addonId: String, type: String, metaId: String, title: String, poster: String?) -> Unit = { _, _, _, _, _ -> },
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val moviesThemeName by sl.settings.moviesTheme.collectAsState(initial = "violet")
    val vm: MoviesViewModel = viewModel(factory = MoviesViewModel.factory(context))
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    LaunchedEffect(query) {
        if (query.length >= 2) {
            // Clear previous results immediately so screen feels instant
            vm.search(query)
        } else {
            vm.search("")
        }
    }

    MoviesThemeWrapper(moviesThemeName) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("Search movies, series, addons…") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, "Clear")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { vm.saveToHistory(query) },
                        ),
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (query.length < 2) {
            RecentSearches(
                history = state.searchHistory,
                padding = padding,
                onSelect = { query = it },
                onRemove = { vm.removeFromSearchHistory(it) },
                onClearAll = { vm.clearSearchHistory() },
            )
        } else {
            CombinedResultsList(
                state = state,
                query = query,
                padding = padding,
                onMovieClick = onMovieClick,
                onTvClick = onTvClick,
                onOpenCsItem = onOpenCsItem,
                onOpenStremio = onOpenStremio,
            )
        }
    }
    } // MoviesThemeWrapper
}

// ─────────────────────────────────────────────────────────────────────────────
// Recent searches
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentSearches(
    history: List<String>,
    padding: PaddingValues,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    if (history.isEmpty()) {
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Type to search across Movies, Series,\nCloudStream & Stremio addons",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = 32.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "history-header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                TextButton(onClick = onClearAll) {
                    Text("Clear all", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        items(history.size, key = { "history-$it" }) { idx ->
            val item = history[idx]
            ListItem(
                headlineContent = {
                    Text(
                        item,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { onRemove(item) }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                modifier = Modifier.clickable { onSelect(item) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Results list
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CombinedResultsList(
    state: com.streamcloud.app.ui.viewmodel.MoviesState,
    query: String,
    padding: PaddingValues,
    onMovieClick: (Long) -> Unit,
    onTvClick: (Long) -> Unit,
    onOpenCsItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit,
    onOpenStremio: (addonId: String, type: String, metaId: String, title: String, poster: String?) -> Unit,
) {
    val csGrouped = remember(state.csSearchResults) { state.csSearchResults.groupBy { it.pluginName } }
    val stremioGrouped = remember(state.stremioSearchResults) { state.stremioSearchResults.groupBy { it.addonName } }
    val anyLoading = state.moviesLoading || state.seriesLoading || state.csLoading || state.stremioLoading
    val hasAny = state.searchResults.isNotEmpty() || state.tvSearchResults.isNotEmpty() ||
        state.csSearchResults.isNotEmpty() || state.stremioSearchResults.isNotEmpty()

    if (!hasAny && !anyLoading) {
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No results for \"$query\"",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = 32.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        // ── Series (TMDB TV) ──────────────────────────────────────────────
        if (state.tvSearchResults.isNotEmpty() || state.seriesLoading) {
            item(key = "series-header") {
                SectionHeader(
                    title = "Series",
                    loading = state.seriesLoading,
                    topPadding = 0.dp,
                )
            }
            state.tvSearchResults.forEachIndexed { idx, movie ->
                item(key = "tv-$idx") {
                    LandscapeCardItem(
                        imageUrl = movie.backdropUrl ?: movie.posterUrl,
                        title = movie.displayTitle,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onTvClick(movie.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ── Movies (TMDB) ─────────────────────────────────────────────────
        if (state.searchResults.isNotEmpty() || state.moviesLoading) {
            item(key = "movies-header") {
                SectionHeader(
                    title = "Movies",
                    loading = state.moviesLoading,
                    topPadding = if (state.tvSearchResults.isNotEmpty() || state.seriesLoading) 20.dp else 0.dp,
                )
            }
            state.searchResults.forEachIndexed { idx, movie ->
                item(key = "movie-$idx") {
                    LandscapeCardItem(
                        imageUrl = movie.backdropUrl ?: movie.posterUrl,
                        title = movie.displayTitle,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onMovieClick(movie.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ── CloudStream ───────────────────────────────────────────────────
        if (csGrouped.isNotEmpty() || state.csLoading) {
            val hasPrevSection = state.searchResults.isNotEmpty() || state.tvSearchResults.isNotEmpty() ||
                state.moviesLoading || state.seriesLoading
            item(key = "cs-header") {
                SectionHeader(
                    title = "CloudStream",
                    loading = state.csLoading,
                    topPadding = if (hasPrevSection) 20.dp else 0.dp,
                )
            }
            csGrouped.forEach { (pluginName, results) ->
                item(key = "cs-plugin-$pluginName") {
                    Text(
                        pluginName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    )
                }
                results.forEachIndexed { idx, r ->
                    item(key = "cs-$pluginName-$idx") {
                        LandscapeCardItem(
                            imageUrl = r.item.posterUrl,
                            title = r.item.name,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenCsItem(r.pluginInternalName, r.item.url, r.item.name, r.item.posterUrl) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // ── Stremio ───────────────────────────────────────────────────────
        if (stremioGrouped.isNotEmpty() || state.stremioLoading) {
            val hasPrevSection = state.searchResults.isNotEmpty() || state.tvSearchResults.isNotEmpty() ||
                csGrouped.isNotEmpty() || state.moviesLoading || state.seriesLoading || state.csLoading
            item(key = "stremio-header") {
                SectionHeader(
                    title = "Stremio",
                    loading = state.stremioLoading,
                    topPadding = if (hasPrevSection) 20.dp else 0.dp,
                )
            }
            stremioGrouped.forEach { (addonName, results) ->
                item(key = "stremio-addon-$addonName") {
                    Text(
                        addonName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    )
                }
                results.forEachIndexed { idx, r ->
                    item(key = "stremio-$addonName-$idx") {
                        LandscapeCardItem(
                            imageUrl = r.item.poster,
                            title = r.item.name,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenStremio(r.addonId, r.item.type, r.item.id, r.item.name, r.item.poster) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared card components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LandscapeCardItem(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                        ),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                    ),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, loading: Boolean, topPadding: Dp = 0.dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
