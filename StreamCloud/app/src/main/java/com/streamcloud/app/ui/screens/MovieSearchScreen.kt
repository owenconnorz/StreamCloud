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
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.ui.components.WatchedPosterBadge
import com.streamcloud.app.ui.theme.LegacyMoviesThemeWrapper
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
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
    val moviesThemeName by sl.settings.moviesTheme.collectAsState(initial = "modern_blue")
    val activeProfile by sl.profiles.activeProfile.collectAsState(initial = null)
    val nuvioUserId by sl.settings.nuvioUserId.collectAsState(initial = "")
    val storageScopeKey = "$nuvioUserId:${activeProfile?.id ?: "default"}"
    val vm: MoviesViewModel = viewModel(
        key = "movie-search-$storageScopeKey",
        factory = MoviesViewModel.factory(context),
    )
    val state by vm.state.collectAsState()
    val watchedItems by remember(storageScopeKey) {
        LibraryDb.get(context.applicationContext).watchedMovies().all()
    }.collectAsState(initial = emptyList())
    val watchedTmdbIds = remember(watchedItems) {
        watchedItems
            .filter { it.tmdbId > 0L && (it.mediaType == "movie" || it.mediaType == "tv") }
            .mapTo(mutableSetOf()) { it.tmdbId }
    }
    // Keep this independent of profile-load timing so returning from a detail
    // route cannot reset a query when the active-profile key changes.
    var query by rememberSaveable { mutableStateOf("") }
    var submittedTvQuery by rememberSaveable { mutableStateOf("") }
    var focusResultsAfterSearch by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val firstKeyboardKeyFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }
    val firstHistoryFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val firstResultKey = when {
        state.tvSearchResults.isNotEmpty() -> "series-${state.tvSearchResults.first().id}"
        state.searchResults.isNotEmpty() -> "movies-${state.searchResults.first().id}"
        state.csSearchResults.isNotEmpty() -> "cloudstream-${state.csSearchResults.size}"
        state.stremioSearchResults.isNotEmpty() -> "stremio-${state.stremioSearchResults.size}"
        else -> null
    }
    val searchIsLoading =
        state.moviesLoading || state.seriesLoading || state.csLoading || state.stremioLoading

    // TV starts on the custom key grid; touch-first layouts start in the text field.
    LaunchedEffect(isTv) {
        val target = if (isTv) firstKeyboardKeyFocusRequester else focusRequester
        repeat(10) {
            if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                return@LaunchedEffect
            }
            delay(100)
        }
    }

    // Move into results only after a result card exists. If the search is empty,
    // restore the keyboard focus rather than leaving the remote with no highlight.
    LaunchedEffect(focusResultsAfterSearch, firstResultKey, searchIsLoading) {
        if (isTv && focusResultsAfterSearch) {
            delay(100)
            when {
                firstResultKey != null -> {
                    runCatching { firstResultFocusRequester.requestFocus() }
                    focusResultsAfterSearch = false
                }
                !searchIsLoading -> {
                    runCatching { firstKeyboardKeyFocusRequester.requestFocus() }
                    focusResultsAfterSearch = false
                }
            }
        }
    }

    LaunchedEffect(query, vm, isTv) {
        if (isTv) return@LaunchedEffect
        if (query.length >= 2) {
            // Clear previous results immediately so screen feels instant
            vm.search(query)
        } else {
            vm.search("")
        }
    }

    val submitSearch: (String) -> Unit = { rawQuery ->
        val submittedQuery = rawQuery.trim()
        query = submittedQuery
        if (submittedQuery.length >= 2) {
            // Keyboard Search and the TV remote Back key use the same path:
            // refresh every provider, save the query, then move focus to the
            // first result once the result rail has been composed.
            vm.search(submittedQuery, forceRefresh = true)
            vm.saveToHistory(submittedQuery)
            if (isTv) submittedTvQuery = submittedQuery
            focusResultsAfterSearch = true
        } else if (isTv) {
            submittedTvQuery = ""
            vm.search("")
        }
        if (!isTv) focusManager.clearFocus(force = true)
    }

    val appendTvSearchText: (String) -> Unit = { text ->
        query += text
        focusResultsAfterSearch = false
        if (isTv && submittedTvQuery.isNotEmpty()) {
            submittedTvQuery = ""
            vm.search("")
        }
    }
    val deleteTvSearchCharacter: () -> Unit = {
        if (query.isNotEmpty()) query = query.dropLast(1)
        focusResultsAfterSearch = false
        if (isTv && submittedTvQuery.isNotEmpty()) {
            submittedTvQuery = ""
            vm.search("")
        }
    }
    val clearSearch: () -> Unit = {
        query = ""
        submittedTvQuery = ""
        focusResultsAfterSearch = false
        if (isTv) vm.search("")
    }

    // Back always leaves search on TV. Do not turn the remote's Back key into
    // another search submission when the text field or results own focus.
    BackHandler(enabled = isTv) {
        onBack()
    }

    val searchField: @Composable (Modifier) -> Unit = { fieldModifier ->
        TextField(
            value = query,
            onValueChange = {
                query = it
                focusResultsAfterSearch = false
            },
            modifier = fieldModifier
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (isTv && event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.Back, Key.Escape -> false
                            Key.DirectionDown -> runCatching {
                                firstKeyboardKeyFocusRequester.requestFocus()
                            }.getOrDefault(false)
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .tvFocusBorder(
                    RoundedCornerShape(28.dp),
                    color = if (isTv) Color(0xFF13BCEB) else Color.White,
                ),
            placeholder = {
                Text(
                    "Search movies, series & add-ons",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
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
                    IconButton(onClick = clearSearch) {
                        Icon(Icons.Default.Close, "Clear")
                    }
                }
            },
            readOnly = isTv,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { submitSearch(query) },
            ),
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = if (isTv) Color(0xFF17232D) else MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = if (isTv) Color(0xFF111922) else MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = if (isTv) Color(0xFF13BCEB) else MaterialTheme.colorScheme.primary,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
        )
    }

    LegacyMoviesThemeWrapper(moviesThemeName) {
    Scaffold(
        topBar = {
            if (!isTv) {
                TopAppBar(
                    title = {
                        searchField(Modifier.fillMaxWidth())
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
            }
        },
        containerColor = if (isTv) Color(0xFF080C12) else MaterialTheme.colorScheme.background,
    ) { padding ->
        val displayedQuery = if (isTv) submittedTvQuery else query
        if (isTv) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                TvSearchSidebar(
                    query = query,
                    searchField = searchField,
                    firstKeyboardKeyFocusRequester = firstKeyboardKeyFocusRequester,
                    searchFieldFocusRequester = focusRequester,
                    onCharacter = appendTvSearchText,
                    onBackspace = deleteTvSearchCharacter,
                    onClear = clearSearch,
                    onSearch = {
                        if (query.trim().length >= 2) {
                            submitSearch(query)
                        } else {
                            runCatching { firstKeyboardKeyFocusRequester.requestFocus() }
                        }
                    },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    if (displayedQuery.length < 2) {
                        RecentSearches(
                            history = state.searchHistory,
                            padding = PaddingValues(),
                            firstItemFocusRequester = firstHistoryFocusRequester,
                            onSelect = { submitSearch(it) },
                            onRemove = { vm.removeFromSearchHistory(it) },
                            onClearAll = { vm.clearSearchHistory() },
                        )
                    } else {
                        CombinedResultsList(
                            state = state,
                            query = displayedQuery,
                            padding = PaddingValues(),
                            watchedTmdbIds = watchedTmdbIds,
                            firstResultFocusRequester = firstResultFocusRequester,
                            keyboardFocusRequester = firstKeyboardKeyFocusRequester,
                            onMovieClick = onMovieClick,
                            onTvClick = onTvClick,
                            onLoadMoreMovies = vm::loadMoreMovies,
                            onLoadMoreTv = vm::loadMoreTv,
                            onOpenCsItem = onOpenCsItem,
                            onOpenStremio = onOpenStremio,
                        )
                    }
                }
            }
        } else {
            if (displayedQuery.length < 2) {
                RecentSearches(
                    history = state.searchHistory,
                    padding = padding,
                    firstItemFocusRequester = firstHistoryFocusRequester,
                    onSelect = { submitSearch(it) },
                    onRemove = { vm.removeFromSearchHistory(it) },
                    onClearAll = { vm.clearSearchHistory() },
                )
            } else {
                CombinedResultsList(
                    state = state,
                    query = displayedQuery,
                    padding = padding,
                    watchedTmdbIds = watchedTmdbIds,
                    firstResultFocusRequester = firstResultFocusRequester,
                    keyboardFocusRequester = firstKeyboardKeyFocusRequester,
                    onMovieClick = onMovieClick,
                    onTvClick = onTvClick,
                    onLoadMoreMovies = vm::loadMoreMovies,
                    onLoadMoreTv = vm::loadMoreTv,
                    onOpenCsItem = onOpenCsItem,
                    onOpenStremio = onOpenStremio,
                )
            }
        }
    }
    } // MoviesThemeWrapper
}

@Composable
private fun TvSearchSidebar(
    query: String,
    searchField: @Composable (Modifier) -> Unit,
    firstKeyboardKeyFocusRequester: FocusRequester,
    searchFieldFocusRequester: FocusRequester,
    onCharacter: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    val keyboardRows = remember {
        listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM123", "45678", "90-.'")
            .map { row -> row.map { it.toString() } + List(5 - row.length) { "" } }
    }
    Column(
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(Color(0xFF0D151E))
            .padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        searchField(Modifier.fillMaxWidth().height(62.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            "ON-SCREEN KEYBOARD",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF9EABB8),
        )
        Spacer(Modifier.height(8.dp))
        keyboardRows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEachIndexed { columnIndex, key ->
                    if (key.isBlank()) {
                        Spacer(Modifier.weight(1f).height(38.dp))
                    } else {
                        val firstKey = rowIndex == 0 && columnIndex == 0
                        val keyModifier = Modifier
                            .weight(1f)
                            .then(
                                if (rowIndex == 0) {
                                    Modifier.onPreviewKeyEvent { event ->
                                        if (
                                            event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionUp
                                        ) {
                                            runCatching { searchFieldFocusRequester.requestFocus() }
                                                .getOrDefault(false)
                                        } else {
                                            false
                                        }
                                    }
                                } else Modifier
                            )
                        TvSearchKey(
                            label = key,
                            modifier = keyModifier,
                            focusRequester = if (firstKey) firstKeyboardKeyFocusRequester else null,
                            onClick = { onCharacter(key) },
                        )
                    }
                }
            }
            if (rowIndex != keyboardRows.lastIndex) Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TvSearchKey(
                label = "SPACE",
                modifier = Modifier.weight(2f),
                onClick = { onCharacter(" ") },
            )
            TvSearchKey(
                label = "⌫",
                modifier = Modifier.weight(1f),
                onClick = onBackspace,
            )
            TvSearchKey(
                label = "CLEAR",
                modifier = Modifier.weight(1.25f),
                onClick = onClear,
            )
        }
        Spacer(Modifier.height(8.dp))
        TvSearchKey(
            label = if (query.length >= 2) "SEARCH" else "SEARCH (2+ LETTERS)",
            modifier = Modifier.fillMaxWidth(),
            height = 44.dp,
            isPrimary = true,
            onClick = onSearch,
        )
    }
}

@Composable
private fun TvSearchKey(
    label: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 38.dp,
    focusRequester: FocusRequester? = null,
    isPrimary: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(height)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clip(shape)
            .background(if (isPrimary) Color(0xFF079FC5) else Color(0xFF1A2732))
            .tvFocusBorder(shape, borderWidth = 3.dp, color = Color(0xFF13BCEB))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recent searches
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentSearches(
    history: List<String>,
    padding: PaddingValues,
    firstItemFocusRequester: FocusRequester,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    if (history.isEmpty()) {
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (isTv) {
                Column(
                    modifier = Modifier.padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Find something to watch",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Search movies, series, CloudStream, and Stremio add-ons.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF9EABB8),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    "Type to search across Movies, Series,\nCloudStream & Stremio addons",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    val tvContentWidth = Modifier
        .fillMaxWidth(0.92f)
        .widthIn(max = 1360.dp)

    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = 32.dp,
        ),
        modifier = Modifier.fillMaxSize().tvFocusGroup(),
        horizontalAlignment = if (isTv) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        item(key = "history-header") {
            Row(
                Modifier
                    .then(if (isTv) tvContentWidth else Modifier.fillMaxWidth())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Recent searches",
                    style = (if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                        .copy(fontWeight = FontWeight.Bold),
                    color = if (isTv) Color.White else MaterialTheme.colorScheme.onBackground,
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
                modifier = Modifier
                    .then(
                        if (isTv) {
                            tvContentWidth
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    )
                    .then(
                        if (idx == 0) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .tvFocusBorder(RoundedCornerShape(12.dp))
                    .clickable { onSelect(item) },
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
    watchedTmdbIds: Set<Long>,
    firstResultFocusRequester: FocusRequester,
    keyboardFocusRequester: FocusRequester,
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
                style = if (isTv) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                color = if (isTv) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
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
        modifier = Modifier.fillMaxSize().tvFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 38.dp else 28.dp),
    ) {
        state.searchCorrection?.let { correctedQuery ->
            item(key = "search-correction") {
                Text(
                    "Showing results for \"$correctedQuery\"",
                    modifier = Modifier.padding(horizontal = if (isTv) 40.dp else 16.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                            imageUrl = movie.posterUrl,
                            fallbackImageUrl = movie.backdropUrl,
                            title = movie.displayTitle,
                            isWatched = movie.id in watchedTmdbIds,
                            focusRequester = if (
                                isTv && firstResultSection == "series" && movie == state.tvSearchResults.firstOrNull()
                            ) firstResultFocusRequester else null,
                            returnToKeyboardFocusRequester = if (
                                isTv && firstResultSection == "series" && movie == state.tvSearchResults.firstOrNull()
                            ) keyboardFocusRequester else null,
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
                            imageUrl = movie.posterUrl,
                            fallbackImageUrl = movie.backdropUrl,
                            title = movie.displayTitle,
                            isWatched = movie.id in watchedTmdbIds,
                            focusRequester = if (
                                isTv && firstResultSection == "movies" && movie == state.searchResults.firstOrNull()
                            ) firstResultFocusRequester else null,
                            returnToKeyboardFocusRequester = if (
                                isTv && firstResultSection == "movies" && movie == state.searchResults.firstOrNull()
                            ) keyboardFocusRequester else null,
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
                            returnToKeyboardFocusRequester = if (
                                isTv && firstResultSection == "cloudstream" && r == results.firstOrNull()
                            ) keyboardFocusRequester else null,
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
                            returnToKeyboardFocusRequester = if (
                                isTv && firstResultSection == "stremio" && r == results.firstOrNull()
                            ) keyboardFocusRequester else null,
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
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isTv) 40.dp else 16.dp)
                .padding(bottom = if (isTv) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                header,
                style = (if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                    .copy(fontWeight = FontWeight.Bold),
                color = if (isTv) Color.White else MaterialTheme.colorScheme.onBackground,
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(if (isTv) 22.dp else 16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // LazyRow gives proper TV D-pad focus traversal and auto bring-into-view,
        // unlike a regular Row+horizontalScroll which intercepts directional events.
        LazyRow(
            modifier = Modifier.fillMaxWidth().tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 10.dp),
            contentPadding = PaddingValues(horizontal = if (isTv) 40.dp else 6.dp),
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
    isWatched: Boolean = false,
    focusRequester: FocusRequester? = null,
    returnToKeyboardFocusRequester: FocusRequester? = null,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val cardWidth = if (isTv) 210.dp else 132.dp
    Column(modifier = Modifier.width(cardWidth)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onPreviewKeyEvent { event ->
                    if (
                        isTv &&
                        returnToKeyboardFocusRequester != null &&
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionLeft
                    ) {
                        runCatching { returnToKeyboardFocusRequester.requestFocus() }
                            .getOrDefault(false)
                    } else {
                        false
                    }
                }
                .tvFocusBorder(
                    RoundedCornerShape(10.dp),
                    borderWidth = if (isTv) 5.dp else 3.dp,
                    color = if (isTv) Color(0xFF13BCEB) else Color.White,
                )
                .clickable(onClick = onClick),
        ) {
            MovieArtwork(
                primaryUrl = imageUrl,
                fallbackUrl = fallbackImageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isWatched) {
                WatchedPosterBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = title,
            style = if (isTv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
            color = if (isTv) Color.White else MaterialTheme.colorScheme.onBackground,
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
