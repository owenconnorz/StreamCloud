package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcloud.app.ui.components.MovieArtwork
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.ui.theme.MoviesThemeWrapper
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.tvDpadRepeatThrottle
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup
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
    var focusResultsAfterSearch by remember { mutableStateOf(false) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val firstResultKey = when {
        state.tvSearchResults.isNotEmpty() -> "series-${state.tvSearchResults.first().id}"
        state.searchResults.isNotEmpty() -> "movies-${state.searchResults.first().id}"
        state.csSearchResults.isNotEmpty() -> "cloudstream-${state.csSearchResults.size}"
        state.stremioSearchResults.isNotEmpty() -> "stremio-${state.stremioSearchResults.size}"
        else -> null
    }

    // On TV the text field auto-focus traps D-pad input and prevents navigating
    // down to search results. Skip it so focus starts free.
    LaunchedEffect(Unit) { if (!isTv) runCatching { focusRequester.requestFocus() } }

    // Pressing Search on an Android TV keyboard clears the TextField focus. Once
    // the first result is composed, put focus on the actual card so the next
    // D-pad press is not dependent on spatial navigation across nested LazyRows.
    LaunchedEffect(focusResultsAfterSearch, firstResultKey) {
        if (isTv && focusResultsAfterSearch && firstResultKey != null) {
            delay(100)
            runCatching { firstResultFocusRequester.requestFocus() }
            focusResultsAfterSearch = false
        }
    }

    LaunchedEffect(query) {
        if (query.length >= 2) {
            // Clear previous results immediately so screen feels instant
            vm.search(query)
        } else {
            vm.search("")
        }
    }

    fun submitSearch() {
        val submittedQuery = query.trim()
        if (submittedQuery.length >= 2) {
            // Keyboard Search and the TV remote Back key use the same path:
            // refresh every provider, save the query, then move focus to the
            // first result once the result rail has been composed.
            vm.search(submittedQuery, forceRefresh = true)
            vm.saveToHistory(submittedQuery)
            focusResultsAfterSearch = true
        }
        focusManager.clearFocus(force = true)
    }

    // On TV, Back while the search field owns focus means “close the keyboard
    // and show results”, not “leave the search screen”.
    BackHandler(enabled = isTv && searchFieldFocused) {
        submitSearch()
    }

    MoviesThemeWrapper(moviesThemeName) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = {
                            query = it
                            focusResultsAfterSearch = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (isTv && event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.Back, Key.Escape -> {
                                            submitSearch()
                                            true
                                        }
                                        Key.DirectionDown -> runCatching {
                                            firstResultFocusRequester.requestFocus()
                                            true
                                        }.getOrDefault(false)
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            }
                            .onFocusChanged { searchFieldFocused = it.isFocused }
                            .tvFocusBorder(RoundedCornerShape(28.dp)),
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
                            onSearch = { submitSearch() },
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
                navigationIcon = if (!isTv) {
                    {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                } else null,
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
                firstResultFocusRequester = firstResultFocusRequester,
                onMovieClick = onMovieClick,
                onTvClick = onTvClick,
                onLoadMoreMovies = vm::loadMoreMovies,
                onLoadMoreTv = vm::loadMoreTv,
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

    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val anchorFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isTv) {
        if (isTv) {
            delay(200)
            try { anchorFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = 32.dp,
        ),
        modifier = Modifier.fillMaxSize().tvFocusGroup().tvDpadRepeatThrottle(),
    ) {
        if (isTv) {
            item(key = "tv-focus-anchor") {
                Box(Modifier.size(1.dp).focusRequester(anchorFocusRequester).focusable())
            }
        }
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
// Results list — Nuvio-style: one horizontal scroll row per section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CombinedResultsList(
    state: com.streamcloud.app.ui.viewmodel.MoviesState,
    query: String,
    padding: PaddingValues,
    firstResultFocusRequester: FocusRequester,
    onMovieClick: (Long) -> Unit,
    onTvClick: (Long) -> Unit,
    onLoadMoreMovies: () -> Unit,
    onLoadMoreTv: () -> Unit,
    onOpenCsItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit,
    onOpenStremio: (addonId: String, type: String, metaId: String, title: String, poster: String?) -> Unit,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val csGrouped = remember(state.csSearchResults) { state.csSearchResults.groupBy { it.pluginName } }
    val stremioGrouped = remember(state.stremioSearchResults) { state.stremioSearchResults.groupBy { it.addonName } }
    val anyLoading = state.moviesLoading || state.seriesLoading || state.csLoading || state.stremioLoading
    val hasAny = state.searchResults.isNotEmpty() || state.tvSearchResults.isNotEmpty() ||
        state.csSearchResults.isNotEmpty() || state.stremioSearchResults.isNotEmpty() ||
        state.moviePagination.error != null || state.tvPagination.error != null
    val firstResultSection = when {
        state.tvSearchResults.isNotEmpty() -> "series"
        state.searchResults.isNotEmpty() -> "movies"
        csGrouped.isNotEmpty() -> "cloudstream"
        stremioGrouped.isNotEmpty() -> "stremio"
        else -> null
    }
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
        ),
        // verticalOnly = true so held Left/Right fall through to the nested LazyRows
        // (NuvioSection card rails) instead of being consumed by this outer column.
        modifier = Modifier.fillMaxSize().tvFocusGroup().tvDpadRepeatThrottle(verticalOnly = true),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        // ── Series (TMDB TV) ──────────────────────────────────────────────
        if (state.tvSearchResults.isNotEmpty() || state.seriesLoading ||
            state.tvPagination.error != null
        ) {
            item(key = "series-section") {
                NuvioSection(
                    header = "TMDB \u2022 Series",
                    loading = state.seriesLoading,
                ) {
                    items(state.tvSearchResults, key = { it.id }) { movie ->
                        NuvioCard(
                            imageUrl = movie.backdropUrl,
                            fallbackImageUrl = movie.posterUrl,
                            title = movie.displayTitle,
                            focusRequester = if (
                                isTv && firstResultSection == "series" && movie == state.tvSearchResults.firstOrNull()
                            ) firstResultFocusRequester else null,
                            onClick = { onTvClick(movie.id) },
                        )
                    }
                    if (!state.seriesLoading && !state.tvPagination.endReached) {
                        item(key = "series-pagination") {
                            PaginationCard(
                                loading = state.tvPagination.isLoading,
                                error = state.tvPagination.error,
                                onClick = onLoadMoreTv,
                            )
                        }
                    }
                }
            }
        }

        // ── Movies (TMDB) ─────────────────────────────────────────────────
        if (state.searchResults.isNotEmpty() || state.moviesLoading ||
            state.moviePagination.error != null
        ) {
            item(key = "movies-section") {
                NuvioSection(
                    header = "TMDB \u2022 Movies",
                    loading = state.moviesLoading,
                ) {
                    items(state.searchResults, key = { it.id }) { movie ->
                        NuvioCard(
                            imageUrl = movie.backdropUrl,
                            fallbackImageUrl = movie.posterUrl,
                            title = movie.displayTitle,
                            focusRequester = if (
                                isTv && firstResultSection == "movies" && movie == state.searchResults.firstOrNull()
                            ) firstResultFocusRequester else null,
                            onClick = { onMovieClick(movie.id) },
                        )
                    }
                    if (!state.moviesLoading && !state.moviePagination.endReached) {
                        item(key = "movie-pagination") {
                            PaginationCard(
                                loading = state.moviePagination.isLoading,
                                error = state.moviePagination.error,
                                onClick = onLoadMoreMovies,
                            )
                        }
                    }
                }
            }
        }

        // ── CloudStream — one section per plugin ──────────────────────────
        csGrouped.forEach { (pluginName, results) ->
            item(key = "cs-$pluginName-section") {
                NuvioSection(
                    header = "$pluginName \u2022 Search",
                    loading = false,
                ) {
                    items(results) { r ->
                        NuvioCard(
                            imageUrl = r.item.posterUrl,
                            title = r.item.name,
                            focusRequester = if (
                                isTv && firstResultSection == "cloudstream" && r == results.firstOrNull()
                            ) firstResultFocusRequester else null,
                            onClick = { onOpenCsItem(r.pluginInternalName, r.item.url, r.item.name, r.item.posterUrl) },
                        )
                    }
                }
            }
        }
        if (state.csLoading && csGrouped.isEmpty()) {
            item(key = "cs-loading") {
                NuvioSection(header = "CloudStream \u2022 Search", loading = true) {}
            }
        }

        // ── Stremio — one section per addon ───────────────────────────────
        stremioGrouped.forEach { (addonName, results) ->
            item(key = "stremio-$addonName-section") {
                NuvioSection(
                    header = "$addonName \u2022 Search",
                    loading = false,
                ) {
                    items(results) { r ->
                        NuvioCard(
                            imageUrl = r.item.poster,
                            title = r.item.name,
                            focusRequester = if (
                                isTv && firstResultSection == "stremio" && r == results.firstOrNull()
                            ) firstResultFocusRequester else null,
                            onClick = { onOpenStremio(r.addonId, r.item.type, r.item.id, r.item.name, r.item.poster) },
                        )
                    }
                }
            }
        }
        if (state.stremioLoading && stremioGrouped.isEmpty()) {
            item(key = "stremio-loading") {
                NuvioSection(header = "Stremio \u2022 Search", loading = true) {}
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Nuvio-style section: header + horizontal card row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NuvioSection(
    header: String,
    loading: Boolean,
    cards: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 0.dp)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                header,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // LazyRow gives proper TV D-pad focus traversal and auto bring-into-view,
        // unlike a regular Row+horizontalScroll which intercepts directional events.
        LazyRow(
            modifier = Modifier.fillMaxWidth().tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
            content = cards,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Individual card — 185dp wide, 16:9 image + title label below
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NuvioCard(
    imageUrl: String?,
    title: String,
    onClick: () -> Unit,
    fallbackImageUrl: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val cardWidth = 185.dp
    Column(modifier = Modifier.width(cardWidth)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .tvFocusBorder(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
        ) {
            MovieArtwork(
                primaryUrl = imageUrl,
                fallbackUrl = fallbackImageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun PaginationCard(
    loading: Boolean,
    error: String?,
    onClick: () -> Unit,
) {
    val label = if (error == null) "Load more" else "Retry"
    Column(
        modifier = Modifier.width(185.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .tvFocusBorder(RoundedCornerShape(10.dp))
                .clickable(enabled = !loading, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = error ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}
