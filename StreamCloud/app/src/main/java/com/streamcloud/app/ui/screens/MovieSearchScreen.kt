package com.streamcloud.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.ui.components.MovieArtwork
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.ui.components.WatchedPosterBadge
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.normalizeAppColorPaletteId
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup
import com.streamcloud.app.ui.viewmodel.CsSearchResult
import com.streamcloud.app.ui.viewmodel.MoviesViewModel
import com.streamcloud.app.ui.viewmodel.StremioSearchResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieSearchScreen(
    tvNavDownFocusHandoffVersion: Int = 0,
    onBack: () -> Unit,
    onMovieClick: (Long) -> Unit,
    onTvClick: (Long) -> Unit = {},
    onOpenCsItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit = { _, _, _, _ -> },
    onOpenStremio: (addonId: String, type: String, metaId: String, title: String, poster: String?) -> Unit = { _, _, _, _, _ -> },
    onOpenAi: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val activeProfile by sl.profiles.activeProfile.collectAsState(initial = null)
    val nuvioUserId by sl.settings.nuvioUserId.collectAsState(initial = "")
    val appearancePalette by sl.settings.colorPalette.collectAsState(initial = "modern_blue")
    val monochromePaletteSelected =
        normalizeAppColorPaletteId(appearancePalette) == "monochrome"
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
    var selectedType by rememberSaveable { mutableStateOf(TvSearchType.All) }
    var selectedLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGenres by remember { mutableStateOf(emptySet<Int>()) }
    var showFilterPanel by remember { mutableStateOf(false) }
    var selectedFilterTab by remember { mutableStateOf(TvSearchFilterTab.Genre) }
    var focusResultsAfterSearch by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val firstKeyboardKeyFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }
    val firstHistoryFocusRequester = remember { FocusRequester() }
    val filterButtonFocusRequester = remember { FocusRequester() }
    val filterPanelFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val filteredState = remember(
        state,
        isTv,
        selectedType,
        selectedLanguage,
        selectedGenres,
    ) {
        if (!isTv) {
            state
        } else {
            state.copy(
                moviesLoading = state.moviesLoading && selectedType != TvSearchType.Series,
                seriesLoading = state.seriesLoading && selectedType != TvSearchType.Movies,
                searchResults = if (selectedType == TvSearchType.Series) {
                    emptyList()
                } else {
                    state.searchResults.filter {
                        it.matchesTvSearchFilters(selectedLanguage, selectedGenres)
                    }
                },
                tvSearchResults = if (selectedType == TvSearchType.Movies) {
                    emptyList()
                } else {
                    state.tvSearchResults.filter {
                        it.matchesTvSearchFilters(selectedLanguage, selectedGenres)
                    }
                },
                csSearchResults = state.csSearchResults.filter { result ->
                    result.item.matchesTvSearchType(selectedType)
                },
                stremioSearchResults = state.stremioSearchResults.filter { result ->
                    result.matchesTvSearchType(selectedType)
                },
            )
        }
    }
    val firstResultKey = when {
        filteredState.tvSearchResults.isNotEmpty() -> "series-${filteredState.tvSearchResults.first().id}"
        filteredState.searchResults.isNotEmpty() -> "movies-${filteredState.searchResults.first().id}"
        filteredState.csSearchResults.isNotEmpty() -> "cloudstream-${filteredState.csSearchResults.size}"
        filteredState.stremioSearchResults.isNotEmpty() -> "stremio-${filteredState.stremioSearchResults.size}"
        else -> null
    }
    val searchIsLoading =
        state.moviesLoading || state.seriesLoading || state.csLoading || state.stremioLoading
    val tvSuggestions = remember(
        query,
        filteredState.tvSearchResults,
        filteredState.searchResults,
        filteredState.csSearchResults,
        filteredState.stremioSearchResults,
    ) {
        if (query.trim().length < 2) {
            emptyList()
        } else {
            buildList {
                addAll(filteredState.tvSearchResults.map { it.displayTitle })
                addAll(filteredState.searchResults.map { it.displayTitle })
                addAll(filteredState.csSearchResults.map { it.item.name })
                addAll(filteredState.stremioSearchResults.map { it.item.name })
            }
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(10)
                .toList()
        }
    }

    // TV starts on the custom key grid; touch-first layouts start in the text field.
    LaunchedEffect(isTv) {
        val target = if (isTv) firstKeyboardKeyFocusRequester else focusRequester
        repeat(10) {
            if (runCatching { target.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
            delay(100)
        }
    }
    LaunchedEffect(isTv, tvNavDownFocusHandoffVersion) {
        if (!isTv || tvNavDownFocusHandoffVersion <= 0) return@LaunchedEffect
        repeat(12) {
            if (runCatching { firstKeyboardKeyFocusRequester.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
            delay(60L)
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
        val normalizedQuery = query.trim()
        if (normalizedQuery.length >= 2) {
            // TV keyboards emit one character per remote press. Debounce those
            // updates, but fetch as the query is typed instead of waiting for a
            // separate Search/Enter action.
            if (isTv) delay(280)
            vm.search(normalizedQuery)
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
            focusResultsAfterSearch = true
        }
        if (submittedQuery.length < 2) vm.search("")
        if (!isTv) focusManager.clearFocus(force = true)
    }

    val appendTvSearchText: (String) -> Unit = { text ->
        query += text
        focusResultsAfterSearch = false
    }
    val deleteTvSearchCharacter: () -> Unit = {
        if (query.isNotEmpty()) query = query.dropLast(1)
        focusResultsAfterSearch = false
    }
    val clearSearch: () -> Unit = {
        query = ""
        focusResultsAfterSearch = false
        if (isTv) vm.search("")
    }

    // Back always leaves search on TV. Do not turn the remote's Back key into
    // another search submission when the text field or results own focus.
    BackHandler(enabled = isTv) {
        if (showFilterPanel) {
            showFilterPanel = false
            runCatching { filterButtonFocusRequester.requestFocus() }
        } else {
            onBack()
        }
    }

    val voiceSearchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognizedQuery = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (recognizedQuery.isNotBlank()) submitSearch(recognizedQuery)
        }
    }
    fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Search movies and series")
        }
        runCatching { voiceSearchLauncher.launch(intent) }
            .onFailure {
                Toast.makeText(context, "Voice search is unavailable on this device", Toast.LENGTH_SHORT).show()
            }
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
                .then(
                    if (isTv) {
                        Modifier
                            .height(56.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.72f),
                                RoundedCornerShape(12.dp),
                            )
                    } else {
                        Modifier
                    },
                )
                .onPreviewKeyEvent { event ->
                    if (isTv && event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.Back, Key.Escape -> false
                            Key.DirectionDown -> runCatching {
                                firstKeyboardKeyFocusRequester.requestFocus()
                            }.isSuccess
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .tvFocusBorder(
                    RoundedCornerShape(if (isTv) 12.dp else 28.dp),
                    color = MaterialTheme.colorScheme.primary,
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
                if (isTv) {
                    IconButton(onClick = ::startVoiceSearch) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice search")
                    }
                } else {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            shape = RoundedCornerShape(if (isTv) 12.dp else 28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = if (isTv) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = if (isTv) Color.Transparent else MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }

    val baseColors = MaterialTheme.colorScheme
    val screenColors = if (isTv && !monochromePaletteSelected) {
        baseColors.copy(
            primary = Color(0xFFB64BE8),
            onPrimary = Color.White,
            background = Color(0xFF0C0711),
            onBackground = Color(0xFFF4ECF7),
            surface = Color(0xFF120B18),
            onSurface = Color(0xFFF4ECF7),
            surfaceVariant = Color(0xFF21132A),
            onSurfaceVariant = Color(0xFFC4B8CA),
            surfaceContainerHigh = Color(0xFF291735),
            outline = Color(0xFF715A7D),
        )
    } else {
        baseColors
    }

    MaterialTheme(colorScheme = screenColors) {
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
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            val displayedQuery = query.trim()
            if (isTv) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    val keyboardPanelWidth = (maxWidth * 0.36f).coerceIn(250.dp, 390.dp)
                    val keyHeight = ((maxHeight - 250.dp) / 6f).coerceIn(28.dp, 38.dp)
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(Modifier.weight(1f)) {
                                    searchField(Modifier.fillMaxWidth())
                                }
                                IconButton(
                                    onClick = { showFilterPanel = !showFilterPanel },
                                    modifier = Modifier
                                        .size(54.dp)
                                        .focusRequester(filterButtonFocusRequester)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .tvFocusBorder(RoundedCornerShape(10.dp)),
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = "Filters")
                                }
                                IconButton(
                                    onClick = { onOpenAi(displayedQuery) },
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                        .tvFocusBorder(RoundedCornerShape(10.dp)),
                                ) {
                                    Text(
                                        "AI",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    )
                                }
                            }
                            TvSearchActiveFilters(
                                selectedType = selectedType,
                                selectedLanguage = selectedLanguage,
                                selectedGenres = selectedGenres,
                                onClearType = { selectedType = TvSearchType.All },
                                onClearLanguage = { selectedLanguage = null },
                                onClearGenre = { id ->
                                    selectedGenres = selectedGenres - id
                                },
                            )
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                TvSearchSidebar(
                                    panelWidth = keyboardPanelWidth,
                                    keyHeight = keyHeight,
                                    firstKeyboardKeyFocusRequester = firstKeyboardKeyFocusRequester,
                                    onCharacter = appendTvSearchText,
                                    onBackspace = deleteTvSearchCharacter,
                                    onClear = clearSearch,
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                ) {
                                    TvSearchSuggestions(
                                        query = displayedQuery,
                                        suggestions = tvSuggestions,
                                        isLoading = searchIsLoading,
                                        onSelect = { submitSearch(it) },
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
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
                                            TvCombinedResultsGrid(
                                                state = filteredState,
                                                query = displayedQuery,
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
                            }
                        }
                        if (showFilterPanel) {
                            LaunchedEffect(showFilterPanel) {
                                delay(80)
                                runCatching { filterPanelFocusRequester.requestFocus() }
                            }
                            TvSearchFilterPanel(
                                selectedTab = selectedFilterTab,
                                onTabSelected = { selectedFilterTab = it },
                                selectedType = selectedType,
                                onTypeSelected = { selectedType = it },
                                selectedLanguage = selectedLanguage,
                                onLanguageSelected = { selectedLanguage = it },
                                selectedGenres = selectedGenres,
                                onGenreToggle = { id ->
                                    selectedGenres = if (id in selectedGenres) {
                                        selectedGenres - id
                                    } else {
                                        selectedGenres + id
                                    }
                                },
                                firstFocusRequester = filterPanelFocusRequester,
                                onClose = {
                                    showFilterPanel = false
                                    runCatching { filterButtonFocusRequester.requestFocus() }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 64.dp, end = 16.dp),
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
    }
}

private enum class TvSearchType(val label: String) {
    All("All"),
    Movies("Movie"),
    Series("TV Show"),
}

private enum class TvSearchFilterTab(val label: String) {
    Genre("Genre"),
    Type("Type"),
    Language("Language"),
}

private data class TvSearchGenre(
    val label: String,
    val id: Int,
    val tmdbIds: Set<Int> = setOf(id),
)

private val tvSearchGenres = listOf(
    TvSearchGenre("Action", 28, setOf(28, 10759)),
    TvSearchGenre("Adventure", 12, setOf(12, 10759)),
    TvSearchGenre("Animation", 16),
    TvSearchGenre("Comedy", 35),
    TvSearchGenre("Crime", 80),
    TvSearchGenre("Documentary", 99),
    TvSearchGenre("Drama", 18),
    TvSearchGenre("Family", 10751),
    TvSearchGenre("Fantasy", 14, setOf(14, 10765)),
    TvSearchGenre("History", 36),
    TvSearchGenre("Horror", 27),
    TvSearchGenre("Kids", 10762),
    TvSearchGenre("Mystery", 9648),
    TvSearchGenre("News", 10763),
    TvSearchGenre("Reality", 10764),
    TvSearchGenre("Romance", 10749),
    TvSearchGenre("Sci-Fi", 878, setOf(878, 10765)),
    TvSearchGenre("Thriller", 53),
    TvSearchGenre("War", 10752, setOf(10752, 10768)),
)

private val tvSearchLanguages = listOf(
    "en" to "English",
    "hi" to "Hindi",
    "es" to "Spanish",
    "fr" to "French",
    "ja" to "Japanese",
    "ko" to "Korean",
    "de" to "German",
    "it" to "Italian",
)

private fun TmdbMovie.matchesTvSearchFilters(
    language: String?,
    genres: Set<Int>,
): Boolean {
    if (!language.isNullOrBlank() && !originalLanguage.equals(language, ignoreCase = true)) {
        return false
    }
    if (genres.isEmpty()) return true
    val tmdbGenres = tvSearchGenres
        .filter { it.id in genres }
        .flatMapTo(mutableSetOf()) { it.tmdbIds }
    return genreIds.any(tmdbGenres::contains)
}

private fun SearchResponse.matchesTvSearchType(type: TvSearchType): Boolean {
    val isMovie = this.type in setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary)
    return when (type) {
        TvSearchType.All -> true
        TvSearchType.Movies -> isMovie
        TvSearchType.Series -> !isMovie
    }
}

private fun StremioSearchResult.matchesTvSearchType(type: TvSearchType): Boolean =
    when (type) {
        TvSearchType.All -> true
        TvSearchType.Movies -> item.type.equals("movie", ignoreCase = true)
        TvSearchType.Series -> item.type.equals("series", ignoreCase = true) ||
            item.type.equals("tv", ignoreCase = true)
    }

@Composable
private fun TvSearchActiveFilters(
    selectedType: TvSearchType,
    selectedLanguage: String?,
    selectedGenres: Set<Int>,
    onClearType: () -> Unit,
    onClearLanguage: () -> Unit,
    onClearGenre: (Int) -> Unit,
) {
    val hasFilters = selectedType != TvSearchType.All ||
        selectedLanguage != null ||
        selectedGenres.isNotEmpty()
    if (!hasFilters) {
        Spacer(Modifier.height(10.dp))
        return
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectedType != TvSearchType.All) {
            item(key = "filter-type") {
                AssistChip(
                    onClick = onClearType,
                    label = { Text(selectedType.label) },
                    trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                )
            }
        }
        selectedLanguage?.let { code ->
            item(key = "filter-language-$code") {
                AssistChip(
                    onClick = onClearLanguage,
                    label = {
                        Text(tvSearchLanguages.firstOrNull { it.first == code }?.second ?: code)
                    },
                    trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                )
            }
        }
        items(
            items = tvSearchGenres.filter { it.id in selectedGenres },
            key = { "filter-genre-${it.id}" },
        ) { genre ->
            AssistChip(
                onClick = { onClearGenre(genre.id) },
                label = { Text(genre.label) },
                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
            )
        }
    }
}

@Composable
private fun TvSearchFilterPanel(
    selectedTab: TvSearchFilterTab,
    onTabSelected: (TvSearchFilterTab) -> Unit,
    selectedType: TvSearchType,
    onTypeSelected: (TvSearchType) -> Unit,
    selectedLanguage: String?,
    onLanguageSelected: (String?) -> Unit,
    selectedGenres: Set<Int>,
    onGenreToggle: (Int) -> Unit,
    firstFocusRequester: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .widthIn(min = 380.dp, max = 560.dp)
            .heightIn(max = 470.dp)
            .tvFocusGroup(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Filter results",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close filters")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvSearchFilterTab.values().forEachIndexed { index, tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstFocusRequester)
                        } else {
                            Modifier
                        },
                        label = {
                            Text(
                                tab.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            when (selectedTab) {
                TvSearchFilterTab.Genre -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.heightIn(max = 330.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        gridItems(tvSearchGenres, key = { it.id }) { genre ->
                            FilterChip(
                                selected = genre.id in selectedGenres,
                                onClick = { onGenreToggle(genre.id) },
                                label = {
                                    Text(
                                        genre.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }

                TvSearchFilterTab.Type -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TvSearchType.values().forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { onTypeSelected(type) },
                                label = { Text(type.label) },
                            )
                        }
                    }
                }

                TvSearchFilterTab.Language -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.heightIn(max = 330.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item(key = "language-any") {
                            FilterChip(
                                selected = selectedLanguage == null,
                                onClick = { onLanguageSelected(null) },
                                label = { Text("Any") },
                            )
                        }
                        gridItems(tvSearchLanguages, key = { it.first }) { (code, label) ->
                            FilterChip(
                                selected = selectedLanguage == code,
                                onClick = {
                                    onLanguageSelected(if (selectedLanguage == code) null else code)
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSearchSidebar(
    panelWidth: Dp,
    keyHeight: Dp,
    firstKeyboardKeyFocusRequester: FocusRequester,
    onCharacter: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    val keyboardRows = remember {
        listOf("abcdefghi", "jklmnopqr", "stuvwxyz0", "123456789")
            .map { row -> row.map { it.toString() } }
    }
    Column(
        modifier = Modifier
            .width(panelWidth)
            .fillMaxHeight()
            .padding(start = 8.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "ON-SCREEN KEYBOARD",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        keyboardRows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                row.forEachIndexed { columnIndex, key ->
                    val firstKey = rowIndex == 0 && columnIndex == 0
                    TvSearchKey(
                        label = key,
                        modifier = Modifier.weight(1f),
                        height = keyHeight,
                        focusRequester = if (firstKey) firstKeyboardKeyFocusRequester else null,
                        onClick = { onCharacter(key) },
                    )
                }
            }
            if (rowIndex != keyboardRows.lastIndex) Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            TvSearchKey(
                label = "⌫",
                modifier = Modifier.weight(1.15f),
                height = keyHeight,
                onClick = onBackspace,
            )
            TvSearchKey(
                label = "Space",
                modifier = Modifier.weight(2.8f),
                height = keyHeight,
                onClick = { onCharacter(" ") },
            )
            TvSearchKey(
                label = "Clear",
                modifier = Modifier.weight(1.3f),
                height = keyHeight,
                onClick = onClear,
            )
        }
    }
}

@Composable
private fun TvSearchSuggestions(
    query: String,
    suggestions: List<String>,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .tvFocusGroup(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (query.isNotBlank()) {
            item(key = "typed-query-suggestion") {
                TvSearchSuggestionChip(
                    text = query,
                    selected = true,
                    onClick = { onSelect(query) },
                )
            }
        }
        items(
            items = suggestions.filterNot { it.equals(query, ignoreCase = true) }.take(8),
            key = { "tv-suggestion-${it.lowercase()}" },
        ) { suggestion ->
            TvSearchSuggestionChip(
                text = suggestion,
                selected = false,
                onClick = { onSelect(suggestion) },
            )
        }
        if (query.length >= 2 && suggestions.isEmpty() && isLoading) {
            item(key = "tv-suggestions-loading") {
                Text(
                    "Searching…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TvSearchSuggestionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = text,
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else Color.Transparent,
            )
            .tvFocusBorder(
                shape,
                borderWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                showOutline = false,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TvSearchKey(
    label: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 38.dp,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(height)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clip(shape)
            .background(
                if (focused) MaterialTheme.colorScheme.primary
                else Color.Transparent,
            )
            .onFocusChanged { focused = it.isFocused }
            .tvFocusBorder(
                shape,
                borderWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                showOutline = false,
                showFocusFill = false,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
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
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Search movies, series, CloudStream, and Stremio add-ons.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun TvCombinedResultsGrid(
    state: com.streamcloud.app.ui.viewmodel.MoviesState,
    query: String,
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
    val csGrouped = remember(state.csSearchResults) {
        state.csSearchResults.groupBy { it.pluginName }
    }
    val stremioGrouped = remember(state.stremioSearchResults) {
        state.stremioSearchResults.groupBy { it.addonName }
    }
    val anyLoading = state.moviesLoading || state.seriesLoading || state.csLoading || state.stremioLoading
    val hasAny = state.searchResults.isNotEmpty() ||
        state.tvSearchResults.isNotEmpty() ||
        state.csSearchResults.isNotEmpty() ||
        state.stremioSearchResults.isNotEmpty() ||
        state.moviePagination.error != null ||
        state.tvPagination.error != null
    val firstResultSection = when {
        state.tvSearchResults.isNotEmpty() -> "series"
        state.searchResults.isNotEmpty() -> "movies"
        csGrouped.isNotEmpty() -> "cloudstream"
        stremioGrouped.isNotEmpty() -> "stremio"
        else -> null
    }

    if (!hasAny && !anyLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            Text(
                "No results for \"$query\"",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().tvFocusGroup(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.searchCorrection?.let { correctedQuery ->
            item(
                key = "search-correction",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Text(
                    "Showing results for \"$correctedQuery\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        if (
            state.tvSearchResults.isNotEmpty() ||
            state.seriesLoading ||
            state.tvPagination.error != null
        ) {
            item(key = "series-header", span = { GridItemSpan(maxLineSpan) }) {
                TvResultsGridHeader(
                    if (firstResultSection == "series") "Top results" else "TMDB · Series",
                    state.seriesLoading,
                )
            }
            gridItems(
                items = state.tvSearchResults,
                key = { "series-${it.id}" },
            ) { movie ->
                val isFirstResult = firstResultSection == "series" &&
                    movie == state.tvSearchResults.firstOrNull()
                NuvioCard(
                    imageUrl = movie.posterUrl,
                    fallbackImageUrl = movie.backdropUrl,
                    title = movie.displayTitle,
                    isWatched = movie.id in watchedTmdbIds,
                    fillAvailableWidth = true,
                    focusRequester = if (isFirstResult) firstResultFocusRequester else null,
                    returnToKeyboardFocusRequester = if (isFirstResult) {
                        keyboardFocusRequester
                    } else {
                        null
                    },
                    onClick = { onTvClick(movie.id) },
                )
            }
            if (!state.seriesLoading && !state.tvPagination.endReached) {
                item(key = "series-pagination") {
                    TvGridPaginationCard(
                        loading = state.tvPagination.isLoading,
                        error = state.tvPagination.error,
                        onClick = onLoadMoreTv,
                    )
                }
            }
        }

        if (
            state.searchResults.isNotEmpty() ||
            state.moviesLoading ||
            state.moviePagination.error != null
        ) {
            item(key = "movies-header", span = { GridItemSpan(maxLineSpan) }) {
                TvResultsGridHeader(
                    if (firstResultSection == "movies") "Top results" else "TMDB · Movies",
                    state.moviesLoading,
                )
            }
            gridItems(
                items = state.searchResults,
                key = { "movie-${it.id}" },
            ) { movie ->
                val isFirstResult = firstResultSection == "movies" &&
                    movie == state.searchResults.firstOrNull()
                NuvioCard(
                    imageUrl = movie.posterUrl,
                    fallbackImageUrl = movie.backdropUrl,
                    title = movie.displayTitle,
                    isWatched = movie.id in watchedTmdbIds,
                    fillAvailableWidth = true,
                    focusRequester = if (isFirstResult) firstResultFocusRequester else null,
                    returnToKeyboardFocusRequester = if (isFirstResult) {
                        keyboardFocusRequester
                    } else {
                        null
                    },
                    onClick = { onMovieClick(movie.id) },
                )
            }
            if (!state.moviesLoading && !state.moviePagination.endReached) {
                item(key = "movie-pagination") {
                    TvGridPaginationCard(
                        loading = state.moviePagination.isLoading,
                        error = state.moviePagination.error,
                        onClick = onLoadMoreMovies,
                    )
                }
            }
        }

        csGrouped.forEach { (pluginName, results) ->
            item(
                key = "cs-$pluginName-header",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                TvResultsGridHeader("$pluginName · Search", loading = false)
            }
            gridItems(
                items = results,
                key = { "cs-${pluginName}-${it.item.url}" },
            ) { result ->
                val isFirstResult = firstResultSection == "cloudstream" &&
                    result == csGrouped.values.firstOrNull()?.firstOrNull()
                NuvioCard(
                    imageUrl = result.item.posterUrl,
                    title = result.item.name,
                    fillAvailableWidth = true,
                    focusRequester = if (isFirstResult) firstResultFocusRequester else null,
                    returnToKeyboardFocusRequester = if (isFirstResult) {
                        keyboardFocusRequester
                    } else {
                        null
                    },
                    onClick = {
                        onOpenCsItem(
                            result.pluginInternalName,
                            result.item.url,
                            result.item.name,
                            result.item.posterUrl,
                        )
                    },
                )
            }
        }
        if (state.csLoading && csGrouped.isEmpty()) {
            item(key = "cs-loading", span = { GridItemSpan(maxLineSpan) }) {
                TvResultsGridHeader("CloudStream · Search", loading = true)
            }
        }

        stremioGrouped.forEach { (addonName, results) ->
            item(
                key = "stremio-$addonName-header",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                TvResultsGridHeader("$addonName · Search", loading = false)
            }
            gridItems(
                items = results,
                key = { "stremio-${addonName}-${it.item.id}" },
            ) { result ->
                val isFirstResult = firstResultSection == "stremio" &&
                    result == stremioGrouped.values.firstOrNull()?.firstOrNull()
                NuvioCard(
                    imageUrl = result.item.poster,
                    title = result.item.name,
                    fillAvailableWidth = true,
                    focusRequester = if (isFirstResult) firstResultFocusRequester else null,
                    returnToKeyboardFocusRequester = if (isFirstResult) {
                        keyboardFocusRequester
                    } else {
                        null
                    },
                    onClick = {
                        onOpenStremio(
                            result.addonId,
                            result.item.type,
                            result.item.id,
                            result.item.name,
                            result.item.poster,
                        )
                    },
                )
            }
        }
        if (state.stremioLoading && stremioGrouped.isEmpty()) {
            item(key = "stremio-loading", span = { GridItemSpan(maxLineSpan) }) {
                TvResultsGridHeader("Stremio · Search", loading = true)
            }
        }
    }
}

@Composable
private fun TvResultsGridHeader(title: String, loading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TvGridPaginationCard(
    loading: Boolean,
    error: String?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .tvFocusBorder(shape, borderWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                .clickable(enabled = !loading, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    if (error == null) "LOAD MORE" else "RETRY",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (!error.isNullOrBlank()) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (!isTv) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            state.searchCorrection?.let { correctedQuery ->
                item(key = "search-correction", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Showing results for \"$correctedQuery\"",
                        modifier = Modifier.padding(bottom = 2.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.tvSearchResults.isNotEmpty() || state.seriesLoading ||
                state.tvPagination.error != null
            ) {
                item(key = "mobile-series-heading", span = { GridItemSpan(maxLineSpan) }) {
                    NuvioGridSectionHeader("TV Series", state.seriesLoading)
                }
                gridItems(
                    items = state.tvSearchResults,
                    key = { "tv-${it.id}" },
                ) { movie ->
                    NuvioCard(
                        imageUrl = movie.posterUrl,
                        fallbackImageUrl = movie.backdropUrl,
                        title = movie.displayTitle,
                        isWatched = movie.id in watchedTmdbIds,
                        fillAvailableWidth = true,
                        onClick = { onTvClick(movie.id) },
                    )
                }
                if (!state.seriesLoading && !state.tvPagination.endReached) {
                    item(key = "mobile-tv-pagination", span = { GridItemSpan(maxLineSpan) }) {
                        PaginationCard(
                            loading = state.tvPagination.isLoading,
                            error = state.tvPagination.error,
                            onClick = onLoadMoreTv,
                        )
                    }
                }
            }

            if (state.searchResults.isNotEmpty() || state.moviesLoading ||
                state.moviePagination.error != null
            ) {
                item(key = "mobile-movies-heading", span = { GridItemSpan(maxLineSpan) }) {
                    NuvioGridSectionHeader("Movies", state.moviesLoading)
                }
                gridItems(
                    items = state.searchResults,
                    key = { "movie-${it.id}" },
                ) { movie ->
                    NuvioCard(
                        imageUrl = movie.posterUrl,
                        fallbackImageUrl = movie.backdropUrl,
                        title = movie.displayTitle,
                        isWatched = movie.id in watchedTmdbIds,
                        fillAvailableWidth = true,
                        onClick = { onMovieClick(movie.id) },
                    )
                }
                if (!state.moviesLoading && !state.moviePagination.endReached) {
                    item(key = "mobile-movie-pagination", span = { GridItemSpan(maxLineSpan) }) {
                        PaginationCard(
                            loading = state.moviePagination.isLoading,
                            error = state.moviePagination.error,
                            onClick = onLoadMoreMovies,
                        )
                    }
                }
            }

            csGrouped.forEach { (pluginName, results) ->
                item(
                    key = "mobile-cs-heading-$pluginName",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    NuvioGridSectionHeader("$pluginName · Search", loading = false)
                }
                gridItems(
                    items = results,
                    key = { "cs-$pluginName-${it.item.url}" },
                ) { result ->
                    NuvioCard(
                        imageUrl = result.item.posterUrl,
                        title = result.item.name,
                        fillAvailableWidth = true,
                        onClick = {
                            onOpenCsItem(
                                result.pluginInternalName,
                                result.item.url,
                                result.item.name,
                                result.item.posterUrl,
                            )
                        },
                    )
                }
            }
            if (state.csLoading && csGrouped.isEmpty()) {
                item(key = "mobile-cs-loading", span = { GridItemSpan(maxLineSpan) }) {
                    NuvioGridSectionHeader("CloudStream · Search", loading = true)
                }
            }

            stremioGrouped.forEach { (addonName, results) ->
                item(
                    key = "mobile-stremio-heading-$addonName",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    NuvioGridSectionHeader("$addonName · Search", loading = false)
                }
                gridItems(
                    items = results,
                    key = { "stremio-$addonName-${it.item.id}" },
                ) { result ->
                    NuvioCard(
                        imageUrl = result.item.poster,
                        title = result.item.name,
                        fillAvailableWidth = true,
                        onClick = {
                            onOpenStremio(
                                result.addonId,
                                result.item.type,
                                result.item.id,
                                result.item.name,
                                result.item.poster,
                            )
                        },
                    )
                }
            }
            if (state.stremioLoading && stremioGrouped.isEmpty()) {
                item(key = "mobile-stremio-loading", span = { GridItemSpan(maxLineSpan) }) {
                    NuvioGridSectionHeader("Stremio · Search", loading = true)
                }
            }
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

@Composable
private fun NuvioGridSectionHeader(
    title: String,
    loading: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
                color = MaterialTheme.colorScheme.onBackground,
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
    fillAvailableWidth: Boolean = false,
    focusRequester: FocusRequester? = null,
    returnToKeyboardFocusRequester: FocusRequester? = null,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val cardWidth = if (isTv) 224.dp else 154.dp
    Column(
        modifier = if (fillAvailableWidth) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.width(cardWidth)
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (isTv && fillAvailableWidth) 16f / 10f else 2f / 3f)
                .clip(RoundedCornerShape(14.dp))
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
                            .isSuccess
                    } else {
                        false
                    }
                }
                .tvFocusBorder(
                    RoundedCornerShape(14.dp),
                    borderWidth = if (isTv) 5.dp else 3.dp,
                    color = MaterialTheme.colorScheme.primary,
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
