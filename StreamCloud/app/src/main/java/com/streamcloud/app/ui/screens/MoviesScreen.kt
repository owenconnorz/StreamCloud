@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.streamcloud.app.ui.screens

import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons as MaterialIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.streamcloud.app.ui.theme.tvFocusBorder
import kotlinx.coroutines.launch
import com.streamcloud.app.ui.theme.tvFocusGroup
import com.streamcloud.app.ui.theme.tvDpadRepeatThrottle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.MoviesThemeWrapper
import com.streamcloud.app.ui.theme.UiFormFactor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import coil.compose.AsyncImage
import com.lagradost.cloudstream3.SearchResponse
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.collections.HomeCollections
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.stremio.adultStremioHomeRows
import com.streamcloud.app.data.stremio.displayCatalogTitle
import com.streamcloud.app.data.stremio.StremioMetaPreview
import com.streamcloud.app.data.SettingsRepository
import com.streamcloud.app.ui.components.MovieArtwork
import com.streamcloud.app.ui.components.tvHighResolutionTmdbArtworkUrl
import com.streamcloud.app.ui.components.TmdbTrailerPreview
import com.streamcloud.app.ui.components.WatchedPosterBadge
import com.streamcloud.app.ui.viewmodel.CsPluginRow
import com.streamcloud.app.ui.viewmodel.HeroBannerItem
import com.streamcloud.app.ui.viewmodel.MoviesViewModel
import com.streamcloud.app.ui.viewmodel.PinnedCollectionRow

private data class PosterSheetItem(
    val tmdbId: Long?,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,
)

private data class TvGenreShortcut(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val imageUrl: String?,
    val fallbackImageUrl: String? = null,
    val tint: Color,
    val onClick: () -> Unit,
)

private data class TvGenreDefinition(
    val title: String,
    val collectionId: String,
    val tmdbGenreId: Int,
    val icon: ImageVector,
    val tint: Color,
)

private const val TV_IDLE_RECOMMENDATION_DELAY_MS = 2 * 60_000L
private const val TV_IDLE_RECOMMENDATION_ROTATE_MS = 25_000L

private fun TmdbMovie.watchedMediaType(): String = if (title != null) "movie" else "tv"

internal fun csHomeTmdbKey(
    pluginInternalName: String,
    sectionName: String,
    itemUrl: String,
): String = "cs:$pluginInternalName:$sectionName:$itemUrl"

@Composable
internal fun Modifier.tvOkPress(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    var okKeyDown by remember { mutableStateOf(false) }
    var longPressDetected by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    if (!isTv) return this

    return onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val isOkKey = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
            native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A
        if (!isOkKey) return@onPreviewKeyEvent false

        when (native.action) {
            AndroidKeyEvent.ACTION_DOWN -> {
                val isLongPress = native.repeatCount > 0 || native.isLongPress()
                if (!isLongPress) {
                    okKeyDown = true
                    longPressDetected = false
                } else {
                    okKeyDown = true
                    longPressDetected = true
                }
                true
            }
            AndroidKeyEvent.ACTION_UP -> {
                if (!okKeyDown) {
                    false
                } else {
                    val wasLongPress = longPressDetected ||
                        native.repeatCount > 0 ||
                        native.isLongPress()
                    okKeyDown = false
                    longPressDetected = false
                    // Open the menu only after the key-up is consumed. Opening it on
                    // a repeated key-down can detach this card before the remote sends
                    // key-up, allowing Compose's clickable to interpret that release
                    // as a second, ordinary click.
                    if (wasLongPress) currentOnLongPress() else currentOnClick()
                    true
                }
            }
            else -> false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesScreen(
    initialFocusRequester: FocusRequester? = null,
    initialFocusEnabled: Boolean = true,
    allowTvIdleScreen: Boolean = true,
    // Always attached to the current hero Play button so the TV nav D-pad Down
    // can jump here even when initialFocusRequester targets something else.
    tvNavHeroFocus: FocusRequester? = null,
    tvNavSafeFocusRequester: FocusRequester? = null,
    tvNavSafeFocusReceived: Boolean = false,
    // Incremented when the TV nav gains focus; immediately reveals the hero
    // before a later Down handoff requests its Play button.
    navScrollToTopVersion: Int = 0,
    // Scroll Movies to the banner before retrying a TV-nav focus request.
    tvNavDownFocusHandoffVersion: Int = 0,
    onTvTopBarVisibilityChanged: (Boolean) -> Unit = {},
    onFirstMovieFocusedChanged: (Boolean) -> Unit = {},
    adultContentEnabled: Boolean = false,
    onOpenAdultContent: () -> Unit = {},
    onMovieClick: (Long) -> Unit,
    onTvClick: (Long) -> Unit = {},
    onOpenCloudStreamPlugin: (internalName: String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onPluginsClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    onOpenCatalog: (source: String, title: String, subtitle: String) -> Unit = { _, _, _ -> },
    onOpenStremio: (addonId: String, type: String, metaId: String, title: String, poster: String?) -> Unit =
        { _, _, _, _, _ -> },
    onOpenCsItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit =
        { _, _, _, _ -> },
    onOpenSourcePage: ((tmdbId: Long, mediaType: String, season: Int?, episode: Int?) -> Unit)? = null,
    onViewAllCsSection: (pluginInternalName: String, sectionName: String, pluginDisplayName: String) -> Unit =
        { _, _, _ -> },
    onOpenCollectionFolder: (Long) -> Unit = {},
    onOpenCollectionTabbed: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val sl = remember(context) { com.streamcloud.app.data.ServiceLocator.get(context) }
    val profileRepo = sl.profiles
    val activeProfile by profileRepo.activeProfile.collectAsState(initial = null)
    val nuvioUserId by sl.settings.nuvioUserId.collectAsState(initial = "")
    val profileKey = activeProfile?.id ?: "default"
    val storageScopeKey = "$nuvioUserId:$profileKey"
    val vm: MoviesViewModel = viewModel(
        key = "movies-$storageScopeKey",
        factory = MoviesViewModel.factory(context),
    )
    val state by vm.state.collectAsState()
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val lifecycleOwner = LocalLifecycleOwner.current
    val adultGenreScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshDiscoverIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val watchedItems by remember(storageScopeKey) {
        LibraryDb.get(context.applicationContext).watchedMovies().all()
    }.collectAsState(initial = emptyList())
    val watchedTmdbIds = remember(watchedItems) {
        watchedItems
            .filter { it.tmdbId > 0L && (it.mediaType == "movie" || it.mediaType == "tv") }
            .mapTo(mutableSetOf()) { it.tmdbId }
    }
    val settingsRepo = remember { SettingsRepository(context) }
    val appearancePalette by settingsRepo.colorPalette.collectAsState(initial = "modern_blue")
    val posterStyle by settingsRepo.posterStyle.collectAsState(initial = "portrait")
    val homeCardStyle by settingsRepo.homeCardStyle.collectAsState(initial = "expanding")
    val adultAgeGateConfirmed by settingsRepo.ageGateConfirmed.collectAsState(initial = false)
    val adultStremioRows = remember(
        state.stremioRows,
        state.stremioAdultCatalogs,
        adultContentEnabled,
    ) {
        if (!adultContentEnabled) {
            emptyList()
        } else {
            (
                state.stremioAdultCatalogs +
                    adultStremioHomeRows(state.stremioRows)
                ).distinctBy { it.rowKey }
        }
    }
    var showAdultCatalogAgeGate by remember { mutableStateOf(false) }
    var showNoAdultStremioCatalogs by remember { mutableStateOf(false) }
    val openAdultStremioCatalog = {
        if (adultStremioRows.isNotEmpty()) {
            val encodedRows = adultStremioRows.joinToString(".") { row ->
                Base64.encodeToString(
                    "${if (row.isAdultCatalog) 1 else 0}|${row.rowKey}".toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
            }
            onOpenCatalog(
                "stremio-multi:$encodedRows",
                "18+ · Adult Stremio catalogs",
                "${adultStremioRows.map { it.addonName }.distinct().size} enabled add-ons",
            )
        }
    }
    val requestAdultStremioCatalog = {
        if (adultStremioRows.isNotEmpty()) {
            if (adultAgeGateConfirmed) {
                openAdultStremioCatalog()
            } else {
                showAdultCatalogAgeGate = true
            }
        } else {
            showNoAdultStremioCatalogs = true
        }
    }
    fun openHeroSources(tmdbId: Long, mediaType: String) {
        val openSources = onOpenSourcePage
        if (openSources != null) {
            openSources(tmdbId, mediaType, null, null)
        } else if (mediaType == "tv") {
            onTvClick(tmdbId)
        } else {
            onMovieClick(tmdbId)
        }
    }
    val openHeroDetails: (HeroBannerItem) -> Unit = { item ->
        when {
            item.tmdbId != null && (
                item.mediaType.equals("tv", ignoreCase = true) ||
                    item.mediaType.equals("series", ignoreCase = true)
                ) ->
                onTvClick(item.tmdbId)
            item.tmdbId != null -> onMovieClick(item.tmdbId)
            item.stremioMeta != null -> vm.openStremioMeta(item.stremioMeta) { tmdbId, _ ->
                if (tmdbId != null) {
                    if (
                        item.stremioMeta.type.equals("series", ignoreCase = true) ||
                        item.stremioMeta.type.equals("tv", ignoreCase = true)
                    ) {
                        onTvClick(tmdbId)
                    } else {
                        onMovieClick(tmdbId)
                    }
                }
            }
            else -> Unit
        }
    }
    val playHeroNow: (HeroBannerItem) -> Unit = { item ->
        val mediaType = if (
            item.mediaType.equals("tv", ignoreCase = true) ||
            item.mediaType.equals("series", ignoreCase = true) ||
            item.stremioMeta?.type.equals("series", ignoreCase = true) ||
            item.stremioMeta?.type.equals("tv", ignoreCase = true)
        ) {
            "tv"
        } else {
            "movie"
        }
        when {
            item.tmdbId != null -> openHeroSources(item.tmdbId, mediaType)
            item.stremioMeta != null -> vm.openStremioMeta(item.stremioMeta) { tmdbId, _ ->
                if (tmdbId != null) openHeroSources(tmdbId, mediaType)
                else openHeroDetails(item)
            }
            else -> openHeroDetails(item)
        }
    }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val tvIdleRecommendations = remember(
        state.trending,
        state.popular,
        state.topRated,
        state.nowPlaying,
        state.collections,
    ) {
        (
            state.trending +
                state.popular +
                state.topRated +
                state.nowPlaying +
                state.collections.flatMap { it.items }
            )
            .asSequence()
            .filter { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() }
            .distinctBy { "${it.watchedMediaType()}:${it.id}" }
            .take(16)
            .toList()
    }
    var tvIdleScreenVisible by remember { mutableStateOf(false) }
    var tvIdleRecommendationIndex by remember { mutableIntStateOf(0) }
    var lastTvInteractionAt by remember {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    val idleScreenView = LocalView.current
    DisposableEffect(idleScreenView, tvIdleScreenVisible) {
        idleScreenView.setKeepScreenOn(tvIdleScreenVisible)
        onDispose { idleScreenView.setKeepScreenOn(false) }
    }
    var cwSheetEntry by remember { mutableStateOf<WatchProgressEntity?>(null) }
    var focusedCwEntry by remember { mutableStateOf<WatchProgressEntity?>(null) }
    val continueWatchingBackdropCache = remember {
        androidx.compose.runtime.mutableStateMapOf<String, String>()
    }
    fun cwIdentity(entry: WatchProgressEntity) =
        "${entry.tmdbId}_${entry.mediaType}_${entry.seasonNumber}_${entry.episodeNumber}"
    val visibleContinueWatching = remember(isTv, state.continueWatching, focusedCwEntry) {
        val focusedEntry = focusedCwEntry
        if (isTv && focusedEntry != null && state.continueWatching.none { cwIdentity(it) == cwIdentity(focusedEntry) }) {
            state.continueWatching + focusedEntry
        } else state.continueWatching
    }
    var pendingCwRemoval by remember { mutableStateOf<WatchProgressEntity?>(null) }
    LaunchedEffect(cwSheetEntry, pendingCwRemoval, tvNavSafeFocusRequester, tvNavSafeFocusReceived) {
        val entry = pendingCwRemoval ?: return@LaunchedEffect
        if (cwSheetEntry != null) return@LaunchedEffect

        if (tvNavSafeFocusRequester == null) {
            vm.deleteWatchProgress(entry.tmdbId, entry.mediaType, entry.seasonNumber, entry.episodeNumber)
            pendingCwRemoval = null
            return@LaunchedEffect
        }

        // A frame after requestFocus() does not prove that focus moved. Keep the
        // card in the lazy row until the TV navigation confirms it owns focus.
        if (!tvNavSafeFocusReceived) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { tvNavSafeFocusRequester.requestFocus() }
            return@LaunchedEffect
        }

        androidx.compose.runtime.withFrameNanos { }
        vm.deleteWatchProgress(entry.tmdbId, entry.mediaType, entry.seasonNumber, entry.episodeNumber)
        pendingCwRemoval = null
    }
    val openCwEntry: (WatchProgressEntity) -> Unit = { entry ->
        val sr = entry.sourceRoute
        if (sr != null && sr.startsWith("cs:")) {
            val parts = sr.removePrefix("cs:").split("|||", limit = 4)
            val plugin = parts.getOrElse(0) { "" }
            val url    = parts.getOrElse(1) { "" }
            val name   = parts.getOrElse(2) { entry.title }
            val poster = parts.getOrElse(3) { "" }.takeIf { it.isNotBlank() }
            if (plugin.isNotBlank() && url.isNotBlank()) {
                onOpenCsItem(plugin, url, name, poster)
            } else {
                if (entry.mediaType == "tv") onTvClick(entry.tmdbId) else onMovieClick(entry.tmdbId)
            }
        } else if (sr != null && sr.startsWith("sources:") && onOpenSourcePage != null) {
            val parts = sr.removePrefix("sources:").split("|||", limit = 3)
            val type = parts.getOrElse(0) { entry.mediaType }
            val season = parts.getOrElse(1) { "" }.toIntOrNull()
            val episode = parts.getOrElse(2) { "" }.toIntOrNull()
            onOpenSourcePage(entry.tmdbId, type, season, episode)
        } else {
            if (entry.mediaType == "tv") onTvClick(entry.tmdbId) else onMovieClick(entry.tmdbId)
        }
    }
    var posterSheet by remember { mutableStateOf<PosterSheetItem?>(null) }
    var watchlistPickerEntry by remember { mutableStateOf<WatchlistEntity?>(null) }
    val cwSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val posterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tvIdleScreenEligible = isTv &&
        allowTvIdleScreen &&
        initialFocusEnabled &&
        query.isBlank() &&
        !searchExpanded &&
        cwSheetEntry == null &&
        posterSheet == null &&
        watchlistPickerEntry == null
    LaunchedEffect(lifecycleOwner, tvIdleScreenEligible, tvIdleRecommendations.size) {
        if (!tvIdleScreenEligible || tvIdleRecommendations.isEmpty()) {
            tvIdleScreenVisible = false
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(500L)
                if (
                    !tvIdleScreenVisible &&
                    SystemClock.elapsedRealtime() - lastTvInteractionAt >=
                    TV_IDLE_RECOMMENDATION_DELAY_MS
                ) {
                    tvIdleRecommendationIndex = 0
                    tvIdleScreenVisible = true
                }
            }
        }
    }
    LaunchedEffect(tvIdleScreenVisible, tvIdleRecommendations.size) {
        if (!tvIdleScreenVisible || tvIdleRecommendations.size < 2) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(TV_IDLE_RECOMMENDATION_ROTATE_MS)
            tvIdleRecommendationIndex =
                (tvIdleRecommendationIndex + 1) % tvIdleRecommendations.size
        }
    }
    LaunchedEffect(lifecycleOwner, nuvioUserId, isTv, searchExpanded) {
        if (isTv || nuvioUserId.isBlank() || searchExpanded) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(120_000L)
                com.streamcloud.app.data.nuvio.NuvioAutoSync.requestProgress(context.applicationContext)
            }
        }
    }
    val moviesListState = rememberLazyListState()
    val tvHeroDetailsVisible by remember(moviesListState, query) {
        derivedStateOf {
            query.isBlank() &&
                moviesListState.firstVisibleItemIndex == 0 &&
                moviesListState.firstVisibleItemScrollOffset <= 16
        }
    }
        val topBarVisibilityChanged by rememberUpdatedState(onTvTopBarVisibilityChanged)
        LaunchedEffect(isTv) {
            if (isTv) topBarVisibilityChanged(true)
        }
        var tvNavHeroHasFocus by remember { mutableStateOf(false) }
        // When the TV nav bar regains focus, scroll back to the top so the hero is fully visible.
    // When TV focus returns to the nav bar, jump to the top immediately. An
    // animated scroll can still be running when the next Down handoff starts.
    LaunchedEffect(navScrollToTopVersion) {
        if (navScrollToTopVersion > 0) moviesListState.scrollToItem(0)
    }
    // A Down key can arrive before the previous scroll settles, leaving the hero
    // focus target detached. Reset synchronously and retry the exact target after
    // the lazy item has had time to reattach.
    LaunchedEffect(isTv, tvNavDownFocusHandoffVersion) {
        if (!isTv || tvNavDownFocusHandoffVersion <= 0) return@LaunchedEffect
        moviesListState.scrollToItem(0)
        if (query.isNotBlank()) return@LaunchedEffect
        repeat(16) {
            if (tvNavHeroHasFocus) return@LaunchedEffect
            kotlinx.coroutines.delay(100L)
            runCatching { tvNavHeroFocus?.requestFocus() }
        }
    }
    val firstCollectionRowId = state.collections
        .firstOrNull { it.items.isNotEmpty() }
        ?.id
    val firstStremioRowKey = state.stremioRows
        .firstOrNull { it.items.isNotEmpty() }
        ?.rowKey
    val homeCollectionRows = remember(isTv, state.collections) {
        if (isTv) {
            state.collections.sortedBy { if (it.id == "popular") 0 else 1 }
        } else {
            state.collections
        }
    }
    val tvGenreArtworkPool = remember(
        state.collections,
        state.trending,
        state.popular,
        state.topRated,
        state.nowPlaying,
    ) {
        (
            state.collections.flatMap { it.items } +
                state.trending +
                state.popular +
                state.topRated +
                state.nowPlaying
            )
            .distinctBy { it.id }
            .filter { !it.backdropUrl.isNullOrBlank() }
    }
    val adultGenreArtwork = remember(adultStremioRows) {
        adultStremioRows.asSequence()
            .flatMap { it.items.asSequence() }
            .flatMap { sequenceOf(it.background, it.poster) }
            .filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(2)
            .toList()
    }
    val homeGenres = run {
        val definitions = listOf(
            TvGenreDefinition("Action", "action", 28, Icons.Default.Whatshot, Color(0xFFEA8C2B)),
            TvGenreDefinition("Comedy", "comedy", 35, Icons.Default.Star, Color(0xFF5BA5E8)),
            TvGenreDefinition("Drama", "drama", 18, Icons.Default.Theaters, Color(0xFF5C86D6)),
            TvGenreDefinition("Horror", "horror", 27, Icons.Default.Warning, Color(0xFF596C9B)),
            TvGenreDefinition("Sci-Fi", "scifi", 878, Icons.Default.AutoAwesome, Color(0xFF7158D6)),
        )
        val genreShortcuts = definitions.map { definition ->
            val (title, collectionId, genreId, icon, tint) = definition
            val categoryItem = state.collections
                .firstOrNull { it.id == collectionId }
                ?.items
                ?.firstOrNull {
                    !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank()
                }
            val matchingItem = tvGenreArtworkPool.firstOrNull {
                genreId in it.genreIds && !it.backdropUrl.isNullOrBlank()
            }
            val artworkCandidates = listOfNotNull(
                state.genreArtwork[collectionId]?.takeIf(String::isNotBlank),
                categoryItem?.backdropUrl?.takeIf(String::isNotBlank)
                    ?: categoryItem?.posterUrl?.takeIf(String::isNotBlank),
                matchingItem?.backdropUrl?.takeIf(String::isNotBlank)
                    ?: matchingItem?.posterUrl?.takeIf(String::isNotBlank),
            ).distinct()
            TvGenreShortcut(
                id = collectionId,
                title = title,
                icon = icon,
                imageUrl = artworkCandidates.firstOrNull(),
                fallbackImageUrl = artworkCandidates.getOrNull(1),
                tint = tint,
                onClick = {
                    val collection = HomeCollections.byId(collectionId)
                    onOpenCatalog(
                        "tmdb:$collectionId",
                        collection?.title ?: title,
                        collection?.subtitle.orEmpty(),
                    )
                },
            )
        }
        if (adultContentEnabled) {
            genreShortcuts + TvGenreShortcut(
                id = "adult",
                title = "18+",
                icon = Icons.Default.Warning,
                imageUrl = adultGenreArtwork.firstOrNull(),
                fallbackImageUrl = adultGenreArtwork.getOrNull(1),
                tint = Color(0xFFB53962),
                onClick = requestAdultStremioCatalog,
            )
        } else {
            genreShortcuts
        }
    }
    val hasTvHero = isTv &&
        query.isBlank() &&
        state.showHeroSection &&
        state.heroBanner.isNotEmpty()
    val tvHomeRowsViewportHeight = run {
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val continueCardWidth = (screenWidth * 0.21f).coerceIn(176.dp, 272.dp)
        val genreCardWidth = calculateTvGenreCardWidth(screenWidth, homeGenres.size)
        val continueRowHeight = 30.dp + continueCardWidth * 0.54f + 8.dp
        val genreRowHeight = 30.dp + genreCardWidth * 9f / 16f + 8.dp
        val followingShelfHeight = 120.dp
        (
            if (visibleContinueWatching.isNotEmpty()) {
                continueRowHeight + genreRowHeight
            } else {
                genreRowHeight + followingShelfHeight
            }
        ).coerceIn(220.dp, 380.dp)
    }
    val hasBrowseContent = state.continueWatching.isNotEmpty() ||
        firstCollectionRowId != null ||
        firstStremioRowKey != null ||
        (state.showHeroSection && state.heroBanner.isNotEmpty()) ||
        state.pinnedCollections.any { it.folders.isNotEmpty() } ||
        state.csPluginRows.any { it.items.isNotEmpty() }
    val startupFocusTarget = when {
        state.error != null || !hasBrowseContent -> "recovery"
        isTv && visibleContinueWatching.isEmpty() -> "genre"
        state.continueWatching.isNotEmpty() -> "continue"
        isTv -> "genre"
        firstCollectionRowId != null -> "collection"
        firstStremioRowKey != null -> "stremio"
        !isTv && !state.loading && state.showHeroSection && state.heroBanner.isNotEmpty() -> "hero"
        else -> null
    }
    var startupFocusRequested by remember { mutableStateOf(false) }
    var recoveryHeldStartupFocus by remember { mutableStateOf(false) }

    LaunchedEffect(isTv, initialFocusEnabled, startupFocusTarget) {
        if (startupFocusTarget != "recovery" && recoveryHeldStartupFocus) {
            // The loading/error retry card can disappear as content arrives. Give the first
            // durable card or hero a fresh focus handoff instead of leaving the remote stranded.
            startupFocusRequested = false
            recoveryHeldStartupFocus = false
        }
        if (
            !isTv ||
            !initialFocusEnabled ||
            startupFocusRequested ||
            startupFocusTarget == null ||
            initialFocusRequester == null
        ) {
            return@LaunchedEffect
        }
        // The LazyColumn item the focus requester is attached to may not be
        // laid out yet when this effect first runs. Retry every 200 ms until
        // the node is attached and requestFocus() succeeds (up to ~2 s).
        repeat(10) {
            kotlinx.coroutines.delay(200L)
            val ok = runCatching {
                initialFocusRequester.requestFocus()
                true
            }.getOrDefault(false)
            if (ok) {
                startupFocusRequested = true
                recoveryHeldStartupFocus = startupFocusTarget == "recovery"
                return@LaunchedEffect
            }
        }
    }

    var activeHeroBanner by remember { mutableStateOf<HeroBannerItem?>(null) }
    LaunchedEffect(state.heroBanner) {
        if (activeHeroBanner !in state.heroBanner) {
            activeHeroBanner = state.heroBanner.firstOrNull()
        }
    }

    MoviesThemeWrapper(appearancePalette) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                if (isTv) Color(0xFF07080D) else MaterialTheme.colorScheme.background,
            )
            .onPreviewKeyEvent { event ->
                if (isTv && event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                    lastTvInteractionAt = SystemClock.elapsedRealtime()
                }
                false
            }
            .pointerInput(isTv) {
                if (!isTv) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val pointerEvent = awaitPointerEvent(PointerEventPass.Initial)
                        if (pointerEvent.changes.any { it.pressed && !it.previousPressed }) {
                            lastTvInteractionAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
            }
            // Keep vertical D-pad movement in focus traversal instead of
            // letting the parent list scroll independently.
            .tvDpadRepeatThrottle(handleInitialPresses = true),
    ) {
        if (showAdultCatalogAgeGate) {
            AlertDialog(
                onDismissRequest = { showAdultCatalogAgeGate = false },
                title = { Text("18+ content") },
                text = {
                    Text("This Stremio catalog may contain explicit adult content. Continue only if you are at least 18 years old.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAdultCatalogAgeGate = false
                            adultGenreScope.launch {
                                settingsRepo.setAgeGateConfirmed(true)
                                openAdultStremioCatalog()
                            }
                        },
                    ) {
                        Text("I'm 18 or older")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAdultCatalogAgeGate = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
        if (showNoAdultStremioCatalogs) {
            AlertDialog(
                onDismissRequest = { showNoAdultStremioCatalogs = false },
                title = { Text("No adult Stremio catalogs") },
                text = {
                    Text("Enable an adult Stremio catalog in Add-ons, then return here to browse 18+ titles.")
                },
                confirmButton = {
                    TextButton(onClick = { showNoAdultStremioCatalogs = false }) {
                        Text("OK")
                    }
                },
            )
        }
        if (hasTvHero) {
            TvHeroBackdrop(
                items = state.heroBanner,
                rowsViewportHeight = tvHomeRowsViewportHeight,
                initialFocusRequester = if (startupFocusTarget == "hero") {
                    initialFocusRequester
                } else {
                    null
                },
                navFocusRequester = tvNavHeroFocus,
                onCurrentItemChanged = { activeHeroBanner = it },
                onPlayNow = playHeroNow,
                onMoreInfo = openHeroDetails,
                detailsVisible = tvHeroDetailsVisible,
                onActionsFocusChanged = { focused ->
                    tvNavHeroHasFocus = focused
                    onFirstMovieFocusedChanged(focused)
                },
            )
        }
        val moviesListModifier = if (isTv && query.isBlank()) {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(tvHomeRowsViewportHeight)
        } else {
            Modifier.fillMaxSize()
        }
        LazyColumn(
            moviesListModifier,
            state = moviesListState,
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (query.isNotBlank()) {
                item { Spacer(Modifier.statusBarsPadding().height(56.dp)) }
                item { SectionTitle("Search results") }
                item {
                    PosterGrid(
                        movies = state.searchResults,
                        watchedTmdbIds = watchedTmdbIds,
                        posterStyle = posterStyle,
                        onClick = { movie ->
                            if (movie.title != null) onMovieClick(movie.id) else onTvClick(movie.id)
                        },
                        onLongPress = { m ->
                            posterSheet = PosterSheetItem(
                                m.id,
                                m.displayTitle,
                                m.posterUrl,
                                m.watchedMediaType(),
                            )
                        },
                    )
                }
            } else {
                if (!isTv && state.heroBanner.isNotEmpty() && state.showHeroSection) {
                    item(key = "hero_pager") {
                        HeroPager(
                            items = state.heroBanner,
                            titleLogoUrlFor = { item ->
                                val resolvedId = item.tmdbId ?: item.stremioMeta?.let { meta ->
                                    state.stremioTmdbIds["stremio:${meta.type}:${meta.id}"]
                                }
                                val mediaType = if (
                                    item.mediaType.equals("tv", ignoreCase = true) ||
                                    item.stremioMeta?.type.equals("series", ignoreCase = true)
                                ) "tv" else "movie"
                                resolvedId?.let { state.tmdbTitleLogos["$mediaType:$it"] }
                            },
                            titleLogoRequestKeyFor = { item ->
                                item.tmdbId?.toString() ?: item.stremioMeta?.let { meta ->
                                    state.stremioTmdbIds["stremio:${meta.type}:${meta.id}"]
                                        ?.toString()
                                }
                            },
                            onRequestTitleLogoFor = { item ->
                                val resolvedId = item.tmdbId ?: item.stremioMeta?.let { meta ->
                                    state.stremioTmdbIds["stremio:${meta.type}:${meta.id}"]
                                }
                                if (resolvedId != null) {
                                    val mediaType = if (
                                        item.mediaType.equals("tv", ignoreCase = true) ||
                                        item.stremioMeta?.type.equals("series", ignoreCase = true)
                                    ) "tv" else "movie"
                                    vm.requestTitleLogo(resolvedId, mediaType)
                                } else {
                                    item.stremioMeta?.let(vm::requestStremioTmdbId)
                                }
                            },
                            onCurrentItemChanged = { activeHeroBanner = it },
                            initialFocusRequester = if (startupFocusTarget == "hero") {
                                initialFocusRequester
                            } else {
                                null
                            },
                                                        onInitialItemFocusChanged = { focused ->
                                 tvNavHeroHasFocus = focused
                                 onFirstMovieFocusedChanged(focused)
                             },
                            onClick = { item ->
                                openHeroDetails(item)
                            },
                            onPlayNow = playHeroNow,
                        )
                    }
                } else if (!isTv) {
                    // On TV the transparent top nav bar overlays the content — give enough
                    // clearance so the first row isn't hidden behind it.
                    item { Spacer(Modifier.statusBarsPadding().height(56.dp)) }
                }

                state.notice?.let {
                    item { NoticeBanner(it, onDismiss = vm::clearNotice) }
                }
                state.error?.let {
                    item {
                        MoviesRecoveryCard(
                            title = if (state.collections.isEmpty()) {
                                "Movies couldn't load"
                            } else {
                                "Some movie sections couldn't load"
                            },
                            detail = it,
                            action = "Retry",
                            onAction = vm::loadDiscover,
                            initialFocusRequester = if (startupFocusTarget == "recovery") {
                                initialFocusRequester
                            } else {
                                null
                            },
                            navFocusRequester = tvNavHeroFocus,
                        )
                    }
                }
                if (state.loading && state.collections.isEmpty() && state.error == null) {
                    item {
                        MoviesRecoveryCard(
                            title = "Loading movies…",
                            detail = "Fetching your movie collections. You can retry if this takes too long.",
                            action = "Reload",
                            onAction = vm::loadDiscover,
                            initialFocusRequester = if (startupFocusTarget == "recovery") {
                                initialFocusRequester
                            } else {
                                null
                            },
                            navFocusRequester = tvNavHeroFocus,
                            showProgress = true,
                        )
                    }
                    item(key = "movies_loading_placeholder") {
                        MoviesLoadingPlaceholder()
                    }
                }
                if (visibleContinueWatching.isNotEmpty()) {
                    item(key = "continue_watching") {
                        Column {
                            if (isTv) {
                                TvHomeSectionTitle("Continue Watching")
                            } else {
                                SectionTitle("Continue Watching")
                            }
                            val continueRowState = rememberLazyListState()
                            var focusedContinueIndex by remember { mutableIntStateOf(-1) }
                            KeepTvFocusedItemAtStart(
                                listState = continueRowState,
                                focusedIndex = focusedContinueIndex,
                                isTv = isTv,
                            )
                            LazyRow(
                                state = continueRowState,
                                modifier = Modifier.tvFocusGroup(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(
                                    visibleContinueWatching,
                                    key = { _, entry -> "cw_${cwIdentity(entry)}" },
                                ) { index, entry ->
                                    val backdropCacheKey = "${entry.mediaType}:${entry.tmdbId}"
                                    val focusModifier = Modifier
                                        .then(
                                            if (
                                                isTv &&
                                                !hasTvHero &&
                                                index == 0 &&
                                                startupFocusTarget != "recovery" &&
                                                tvNavHeroFocus != null
                                            ) {
                                                Modifier.focusRequester(tvNavHeroFocus)
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .then(
                                            if (
                                                index == 0 &&
                                                startupFocusTarget == "continue" &&
                                                initialFocusRequester != null
                                            ) {
                                                Modifier.focusRequester(initialFocusRequester)
                                            } else {
                                                Modifier
                                            },
                                        )
                                    ContinueWatchingCard(
                                        entry = entry,
                                        isTv = isTv,
                                        tmdbApi = sl.tmdb,
                                        tmdbApiKey = sl.tmdbApiKey,
                                        cachedBackdropUrl = continueWatchingBackdropCache[backdropCacheKey],
                                        onBackdropResolved = { url ->
                                            continueWatchingBackdropCache[backdropCacheKey] = url
                                        },
                                        onFocusChanged = { hasFocus ->
                                            if (isTv && index == 0) {tvNavHeroHasFocus = hasFocus

                                                onFirstMovieFocusedChanged(hasFocus)
                                            }
                                            if (hasFocus) {
                                                focusedContinueIndex = index
                                                focusedCwEntry = entry
                                            } else if (
                                                focusedCwEntry?.let { cwIdentity(it) == cwIdentity(entry) } == true
                                            ) {
                                                focusedCwEntry = null
                                            }
                                        },
                                        modifier = focusModifier,
                                        onClick = { openCwEntry(entry) },
                                        onLongPress = { cwSheetEntry = entry },
                                    )
                                }
                            }
                            if (isTv) Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                if (homeGenres.isNotEmpty()) {
                    item(key = "home_genres") {
                        HomeGenresRow(
                            genres = homeGenres,
                            isTv = isTv,
                            initialFocusRequester = if (startupFocusTarget == "genre") {
                                initialFocusRequester
                            } else {
                                null
                            },
                            navFocusRequester = if (
                                visibleContinueWatching.isEmpty() &&
                                !hasTvHero &&
                                startupFocusTarget == "genre"
                            ) {
                                tvNavHeroFocus
                            } else {
                                null
                            },
                            onFirstItemFocusChanged = { focused ->

                                tvNavHeroHasFocus = focused

                                onFirstMovieFocusedChanged(focused)

                            },
                        )
                    }
                }
                state.pinnedCollections.forEach { pinnedRow ->
                    item(key = "pinned_t_${pinnedRow.collectionId}") {
                        if (pinnedRow.viewMode == "tabbed_grid") {
                            SectionTitleWithViewAll(
                                title = pinnedRow.collectionName,
                                onViewAll = { onOpenCollectionTabbed(pinnedRow.collectionId) },
                            )
                        } else {
                            SectionTitle(pinnedRow.collectionName)
                        }
                    }
                    if (pinnedRow.folders.isNotEmpty()) {
                        item(key = "pinned_${pinnedRow.collectionId}") {
                            LazyRow(
                                modifier = Modifier.tvFocusGroup(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(pinnedRow.folders, key = { "pf_${it.id}" }) { folder ->
                                    CollectionFolderTile(
                                        folder = folder,
                                        onClick = {
                                            runCatching {
                                                when (folder.providerType) {
                                                    "cloudstream" -> {
                                                        val entries = folder.linkedCategoryId.split("\n").filter { it.isNotBlank() }
                                                        if (entries.size == 1) {
                                                            val p = entries[0].split("|||")
                                                            val iname = p.getOrNull(0) ?: ""
                                                            val sname = p.getOrNull(1) ?: ""
                                                            val dname = p.getOrNull(2)?.ifBlank { null } ?: iname
                                                            when {
                                                                sname.isNotBlank() -> onViewAllCsSection(iname, sname, dname)
                                                                iname.isNotBlank() -> onOpenCloudStreamPlugin(iname)
                                                            }
                                                        } else if (entries.size > 1) {
                                                            onOpenCollectionFolder(folder.id)
                                                        }
                                                    }
                                                    "stremio" -> {
                                                        val entries = folder.linkedCategoryId.split("\n").filter { it.isNotBlank() }
                                                        if (entries.size == 1) {
                                                            val p = entries[0].split("|||")
                                                            val addonId = p.getOrNull(0) ?: ""
                                                            val cType = p.getOrNull(1) ?: ""
                                                            val cId = p.getOrNull(2) ?: ""
                                                            val cName = p.getOrNull(3)?.ifBlank { null } ?: folder.name
                                                            onOpenCatalog("stremio:$addonId:$cType:$cId", folder.name, cName)
                                                        } else if (entries.size > 1) {
                                                            onOpenCollectionFolder(folder.id)
                                                        }
                                                    }
                                                    else -> {
                                                        val catId = folder.linkedCategoryId.trim()
                                                        if (catId.isNotBlank()) {
                                                            if (catId.contains(":")) {
                                                                // Custom TMDB source — load in folder detail screen
                                                                onOpenCollectionFolder(folder.id)
                                                            } else {
                                                                val cat = HomeCollections.byId(catId)
                                                                onOpenCatalog("tmdb:$catId", folder.name, cat?.subtitle.orEmpty())
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                homeCollectionRows.forEachIndexed { _, row ->
                    item(key = "col_t_${row.id}") {
                        SectionTitleWithViewAll(
                            title = row.title,
                            onViewAll = {
                                onOpenCatalog(
                                    "tmdb:${row.id}",
                                    row.title,
                                    com.streamcloud.app.data.collections.HomeCollections
                                        .byId(row.id)?.subtitle.orEmpty(),
                                )
                            },
                        )
                    }
                    item(key = "col_${row.id}") {




                        val collectionRowState = rememberLazyListState()
                        var focusedPosterIndex by remember { mutableIntStateOf(-1) }
                        KeepTvFocusedItemAtStart(
                            listState = collectionRowState,
                            focusedIndex = focusedPosterIndex,
                            isTv = isTv && homeCardStyle != "portrait",
                        )
                        LazyRow(
                            state = collectionRowState,
                            modifier = Modifier
                                .tvFocusGroup()
                                .then(if (isTv) Modifier else Modifier.animateContentSize(animationSpec = tween(durationMillis = 260))),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(
                                row.items,
                                key = { _, movie -> "${row.id}_${movie.id}" },
                            ) { index, m ->
                                val mediaType = if (m.title != null) "movie" else "tv"
                                MidPoster(
                                    m = m,
                                    posterStyle = homeCardStyle,
                                    landscapeCardWidth = 220.dp,
                                    isWatched = m.id in watchedTmdbIds,
                                    isTv = isTv,
                                    titleLogoUrl = state.tmdbTitleLogos["$mediaType:${m.id}"],
                                    onRequestTitleLogo = { vm.requestTitleLogo(m.id, mediaType) },
                                    onFocusChanged = { hasFocus ->
                                        if (hasFocus) focusedPosterIndex = index
                                    },
                                    modifier = if (
                                        row.id == firstCollectionRowId &&
                                        index == 0 &&
                                        startupFocusTarget == "collection" &&
                                        initialFocusRequester != null
                                    ) {
                                        Modifier
                                            .focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                    onClick = {
                                        if (m.title != null) onMovieClick(m.id) else onTvClick(m.id)
                                    },
                                    onLongPress = {
                                        posterSheet = PosterSheetItem(
                                            m.id,
                                            m.displayTitle,
                                            m.posterUrl,
                                            m.watchedMediaType(),
                                        )
                                    },
                                )
                            }
                            if (isTv) {
                                item(key = "${row.id}_viewall") {
                                    ViewAllCard(
                                        posterStyle = homeCardStyle,
                                        onClick = {
                                            onOpenCatalog(
                                                "tmdb:${row.id}",
                                                row.title,
                                                HomeCollections.byId(row.id)?.subtitle.orEmpty(),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                state.stremioRows.forEach { row ->
                    item(key = "stremio_t_${row.rowKey}") {
                        AddonSectionTitleWithViewAll(
                            addon = row.addonName,
                            catalog = row.displayCatalogTitle(),
                            onViewAll = {
                                onOpenCatalog(
                                    "stremio:${row.addonId}:${row.type}:${row.catalogId}",
                                    row.displayCatalogTitle(),
                                    row.addonName,
                                )
                            },
                        )
                    }
                    item(key = "stremio_${row.rowKey}") {
                        val stremioRowState = rememberLazyListState()
                        var focusedPosterIndex by remember { mutableIntStateOf(-1) }
                        KeepTvFocusedItemAtStart(
                            listState = stremioRowState,
                            focusedIndex = focusedPosterIndex,
                            isTv = isTv && homeCardStyle != "portrait",
                        )
                        LazyRow(
                            state = stremioRowState,
                            modifier = Modifier.tvFocusGroup(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(
                                row.items,
                                key = { _, meta -> "${row.rowKey}_${meta.id}" },
                            ) { index, meta ->
                                val stremioKey = "stremio:${meta.type}:${meta.id}"
                                val mediaType = if (
                                    meta.type.equals("series", ignoreCase = true) ||
                                    meta.type.equals("tv", ignoreCase = true)
                                ) "tv" else "movie"
                                val resolvedTmdbId = state.stremioTmdbIds[stremioKey]
                                StremioPoster(
                                    meta = meta,
                                    posterStyle = homeCardStyle,
                                    resolvedTmdbId = resolvedTmdbId,
                                    onRequestTmdbId = { vm.requestStremioTmdbId(meta) },
                                    titleLogoUrl = resolvedTmdbId?.let {
                                        state.tmdbTitleLogos["$mediaType:$it"]
                                    },
                                    onRequestTitleLogo = vm::requestTitleLogo,
                                    onFocusChanged = { hasFocus ->
                                        if (hasFocus) focusedPosterIndex = index
                                    },
                                    modifier = if (
                                        row.rowKey == firstStremioRowKey &&
                                        index == 0 &&
                                        startupFocusTarget == "stremio" &&
                                        initialFocusRequester != null
                                    ) {
                                        Modifier
                                            .focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                    onLongPress = {
                                        posterSheet = PosterSheetItem(
                                            tmdbId = null,
                                            title = meta.name,
                                            posterUrl = meta.poster,
                                            mediaType = row.type,
                                        )
                                    },
                                ) {
                                    if (meta.id.startsWith("tt", ignoreCase = true)) {



                                        vm.openStremioMeta(meta) { tmdbId, _ ->
                                            if (tmdbId != null) {
                                                if (meta.type == "series") onTvClick(tmdbId)
                                                else onMovieClick(tmdbId)
                                            }
                                        }
                                    } else {



                                        onOpenStremio(
                                            row.addonId, row.type, meta.id,
                                            meta.name, meta.poster,
                                        )
                                    }
                                }
                            }
                            if (isTv) {
                                item(key = "${row.rowKey}_viewall") {
                                    ViewAllCard(
                                        posterStyle = homeCardStyle,
                                        onClick = {
                                            onOpenCatalog(
                                                "stremio:${row.addonId}:${row.type}:${row.catalogId}",
                                                row.displayCatalogTitle(),
                                                row.addonName,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                state.csPluginRows.forEach { row ->
                    item(key = "cshome_t_${row.pluginInternalName}_${row.sectionName}") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.sectionName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    row.pluginDisplayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    onViewAllCsSection(
                                        row.pluginInternalName,
                                        row.sectionName,
                                        row.pluginDisplayName,
                                    )
                                },
                            ) {
                                Text(
                                    "View all",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    item(key = "cshome_${row.pluginInternalName}_${row.sectionName}") {
                        val csLandscape = homeCardStyle == "landscape"
                        val csAspect = if (csLandscape) 16f / 9f else 2f / 3f
                        val csRowState = rememberLazyListState()
                        var focusedPosterIndex by remember { mutableIntStateOf(-1) }
                        KeepTvFocusedItemAtStart(
                            listState = csRowState,
                            focusedIndex = focusedPosterIndex,
                            isTv = isTv && homeCardStyle != "portrait",
                        )
                        LazyRow(
                            state = csRowState,
                            modifier = Modifier.tvFocusGroup(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(
                                row.items,
                                key = { _, item ->
                                    "${row.pluginInternalName}_${row.sectionName}_${item.url}"
                                },
                            ) { index, sr ->
                                val tmdbKey = csHomeTmdbKey(
                                    row.pluginInternalName,
                                    row.sectionName,
                                    sr.url,
                                )
                                val csTmdbMovie = state.csHomeTmdbMovies[tmdbKey]
                                val csMediaType = if (csTmdbMovie?.title != null) "movie" else "tv"
                                val csCardLandscape = homeCardStyle == "landscape"
                                val csCardWidth = if (csCardLandscape) 200.dp else 120.dp
                                CsHomePosterCard(
                                    item = sr,
                                    defaultWidth = csCardWidth,
                                    homeCardStyle = homeCardStyle,
                                    isTv = isTv,
                                    tmdbPrefetchDelayMillis = index * 120L,
                                    tmdbMovie = csTmdbMovie,
                                    onRequestTmdbMovie = {
                                        vm.requestCsHomeTmdbMovie(tmdbKey, sr)
                                    },
                                    titleLogoUrl = csTmdbMovie?.let {
                                        state.tmdbTitleLogos["$csMediaType:${it.id}"]
                                    },
                                    onRequestTitleLogo = vm::requestTitleLogo,
                                    onFocusChanged = { hasFocus ->
                                        if (hasFocus) focusedPosterIndex = index
                                    },
                                    onClick = {
                                        onOpenCsItem(
                                            row.pluginInternalName,
                                            sr.url,
                                            sr.name,
                                            sr.posterUrl,
                                        )
                                    },
                                )
                            }
                            if (isTv) {
                                item(key = "${row.pluginInternalName}_${row.sectionName}_viewall") {
                                    ViewAllCard(
                                        posterStyle = homeCardStyle,
                                        onClick = {
                                            onViewAllCsSection(
                                                row.pluginInternalName,
                                                row.sectionName,
                                                row.pluginDisplayName,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (!hasBrowseContent && !state.loading && state.error == null) {
                    item {
                        MoviesRecoveryCard(
                            title = "No movie collections yet",
                            detail = "Choose Home collections in Settings or install a Stremio addon, then reload.",
                            action = "Reload movies",
                            onAction = vm::loadDiscover,
                            initialFocusRequester = if (startupFocusTarget == "recovery") {
                                initialFocusRequester
                            } else {
                                null
                            },
                            navFocusRequester = tvNavHeroFocus,
                        )
                    }
                }
            }
        }

        val cwEntry = cwSheetEntry
        if (cwEntry != null) {
            val goToDetails = {
                cwSheetEntry = null
                if (cwEntry.mediaType == "tv") onTvClick(cwEntry.tmdbId)
                else onMovieClick(cwEntry.tmdbId)
            }
            val playManually = {
                cwSheetEntry = null
                openCwEntry(cwEntry)
            }
            val startFromBeginning = {
                cwSheetEntry = null
                vm.resetWatchProgress(
                    cwEntry.tmdbId,
                    cwEntry.mediaType,
                    cwEntry.seasonNumber,
                    cwEntry.episodeNumber,
                )
                openCwEntry(cwEntry)
            }
            val removeFromContinueWatching = {
                pendingCwRemoval = cwEntry
                cwSheetEntry = null
            }
            if (isTv) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { cwSheetEntry = null },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false,
                    ),
                ) {
                    CwOptionsDialog(
                        entry = cwEntry,
                        onGoToDetails = goToDetails,
                        onPlayManually = playManually,
                        onStartFromBeginning = startFromBeginning,
                        onRemove = removeFromContinueWatching,
                    )
                }
            } else {
                ModalBottomSheet(
                    onDismissRequest = { cwSheetEntry = null },
                    sheetState = cwSheetState,
                ) {
                    CwOptionsSheet(
                        entry = cwEntry,
                        onGoToDetails = goToDetails,
                        onPlayManually = playManually,
                        onStartFromBeginning = startFromBeginning,
                        onRemove = removeFromContinueWatching,
                    )
                }
            }
        }

        val ps = posterSheet
        if (ps != null) {
            ModalBottomSheet(
                onDismissRequest = { posterSheet = null },
                sheetState = posterSheetState,
            ) {
                PosterOptionsSheet(
                    item = ps,
                    isInLibrary = ps.tmdbId != null && state.watchlist.any { it.tmdbId == ps.tmdbId },
                    onAddToLibrary = {
                        posterSheet = null
                        ps.tmdbId?.let {
                            watchlistPickerEntry = WatchlistEntity(
                                tmdbId = it,
                                title = ps.title,
                                posterUrl = ps.posterUrl,
                                mediaType = ps.mediaType,
                            )
                        }
                    },
                    onMarkAsWatched = {
                        posterSheet = null
                        ps.tmdbId?.let { vm.markAsWatched(it, ps.title, ps.posterUrl, ps.mediaType) }
                    },
                )
            }
        }

        watchlistPickerEntry?.let { entry ->
            MovieWatchlistPickerDialog(
                entry = entry,
                onDismiss = { watchlistPickerEntry = null },
            )
        }

        // TV uses a compact Home location marker instead of the StreamCloud wordmark.
        if (isTv && query.isBlank()) {
            TvHomeLocationIndicator()
        } else if (!isTv) {
            MoviesHeader(
                onProfileClick = onProfileClick,
                onOpenCollections = onOpenCollections,
                onSearchClick = onSearchClick,
                onPluginsClick = onPluginsClick,
                hasPlugins = state.installedPlugins.isNotEmpty(),
            )
        }
        if (tvIdleScreenVisible && tvIdleRecommendations.isNotEmpty()) {
            val idleIndex = tvIdleRecommendationIndex % tvIdleRecommendations.size
            val idleItem = tvIdleRecommendations.getOrNull(idleIndex)
            if (idleItem != null) {
                TvIdleRecommendationDialog(
                    item = idleItem,
                    index = idleIndex,
                    count = tvIdleRecommendations.size,
                    onOpen = {
                        tvIdleScreenVisible = false
                        lastTvInteractionAt = SystemClock.elapsedRealtime()
                        if (idleItem.title != null) onMovieClick(idleItem.id)
                        else onTvClick(idleItem.id)
                    },
                    onWake = {
                        tvIdleScreenVisible = false
                        lastTvInteractionAt = SystemClock.elapsedRealtime()
                    },
                )
            }
        }
    }
    } // MoviesThemeWrapper
}

@Composable
private fun TvHomeLocationIndicator() {
    Row(
        modifier = Modifier.padding(start = 24.dp, top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MaterialIcons.Filled.Home,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = "Home",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MoviesHeader(
    onProfileClick: () -> Unit,
    onOpenCollections: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onPluginsClick: () -> Unit = {},
    hasPlugins: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, top = 8.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "StreamCloud",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )

        val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
        if (!isTv) {
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color.White,
                )
            }
        }

        if (hasPlugins) {
            IconButton(
                onClick = onPluginsClick,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = "Switch Plugin",
                    tint = Color.White,
                )
            }
        }

    }
}

@Composable
private fun TvHomeSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

internal fun calculateTvGenreCardWidth(screenWidth: Dp, genreCount: Int): Dp {
    val itemCount = genreCount.coerceAtLeast(1)
    val horizontalGaps = 10.dp * (itemCount - 1).toFloat()
    val availableWidth = (screenWidth - 48.dp - horizontalGaps).coerceAtLeast(0.dp)
    return (availableWidth / itemCount.toFloat()).coerceIn(72.dp, 220.dp)
}

internal fun calculateMobileGenreCardWidth(screenWidth: Dp): Dp =
    (screenWidth * 0.42f).coerceIn(144.dp, 176.dp)

@Composable
private fun HomeGenresRow(
    genres: List<TvGenreShortcut>,
    isTv: Boolean,
    initialFocusRequester: FocusRequester? = null,
    navFocusRequester: FocusRequester? = null,
    onFirstItemFocusChanged: (Boolean) -> Unit = {},
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardWidth = if (isTv) {
        calculateTvGenreCardWidth(screenWidth, genres.size)
    } else {
        calculateMobileGenreCardWidth(screenWidth)
    }
    val compactCards = cardWidth < 136.dp
    val labelHorizontalPadding = if (compactCards) 7.dp else 10.dp
    val labelVerticalPadding = if (compactCards) 6.dp else 8.dp
    val iconSize = if (compactCards) 16.dp else 19.dp
    val labelSpacing = if (compactCards) 5.dp else 7.dp
    val labelFontSize = if (compactCards) 12.sp else 14.sp
    Column {
        if (isTv) {
            TvHomeSectionTitle("Genres")
        } else {
            SectionTitle("Genres")
        }
        LazyRow(
            modifier = Modifier.tvFocusGroup(),
            contentPadding = PaddingValues(horizontal = if (isTv) 24.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(genres, key = { _, genre -> genre.id }) { index, genre ->
                val shape = RoundedCornerShape(12.dp)
                val focusModifier = Modifier
                    .then(
                        if (index == 0 && navFocusRequester != null) {
                            Modifier.focusRequester(navFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (index == 0 && initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .onFocusChanged {
                        if (index == 0) {
                            onFirstItemFocusChanged(it.isFocused || it.hasFocus)
                        }
                    }
                Box(
                    modifier = focusModifier
                        .width(cardWidth)
                        .height(cardWidth * 9f / 16f)
                        .clip(shape)
                        .background(
                            Brush.linearGradient(
                                listOf(genre.tint, genre.tint.copy(alpha = 0.52f), Color(0xFF171823)),
                            ),
                        )
                        .tvFocusBorder(shape)
                        .clickable(onClick = genre.onClick),
                ) {
                    if (!genre.imageUrl.isNullOrBlank()) {
                        MovieArtwork(
                            primaryUrl = genre.imageUrl,
                            fallbackUrl = genre.fallbackImageUrl,
                            contentDescription = "${genre.title} genre artwork",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.04f),
                                        Color.Black.copy(alpha = 0.18f),
                                        Color.Black.copy(alpha = 0.78f),
                                    ),
                                ),
                            ),
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(
                                horizontal = labelHorizontalPadding,
                                vertical = labelVerticalPadding,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(labelSpacing),
                    ) {
                        Icon(
                            genre.icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(iconSize),
                        )
                        Text(
                            genre.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = labelFontSize),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TvHeroBackdrop(
    items: List<HeroBannerItem>,
    rowsViewportHeight: androidx.compose.ui.unit.Dp,
    detailsVisible: Boolean,
    initialFocusRequester: FocusRequester? = null,
    navFocusRequester: FocusRequester? = null,
    onCurrentItemChanged: (HeroBannerItem) -> Unit,
    onPlayNow: (HeroBannerItem) -> Unit,
    onMoreInfo: (HeroBannerItem) -> Unit,
    onActionsFocusChanged: (Boolean) -> Unit = {},
) {
    var currentPage by remember(items.size) { mutableIntStateOf(0) }
    var actionsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(currentPage, items) {
        items.getOrNull(currentPage)?.let(onCurrentItemChanged)
    }
    LaunchedEffect(items.size, actionsFocused) {
        if (items.size <= 1 || actionsFocused) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(15_000L)
            currentPage = (currentPage + 1) % items.size
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07080D)),
    ) {
        Crossfade(
            targetState = currentPage,
            modifier = Modifier.fillMaxSize(),
            label = "tvPinnedHeroBackdrop",
        ) { page ->
            val item = items.getOrNull(page) ?: return@Crossfade
            AsyncImage(
                model = tvHighResolutionTmdbArtworkUrl(item.imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.28f),
                            0.16f to Color.Transparent,
                            0.62f to Color.Transparent,
                            0.82f to Color.Black.copy(alpha = 0.18f),
                            1f to Color(0xFF07080D).copy(alpha = 0.78f),
                        ),
                    ),
                ),
        )
        val hero = items.getOrNull(currentPage)
        if (hero != null) {
            val meta = listOfNotNull(
                if (hero.mediaType.equals("tv", ignoreCase = true)) "Series" else "Movie",
                hero.year.takeIf(String::isNotBlank),
                hero.rating.takeIf(String::isNotBlank),
            ).joinToString("  •  ")
            AnimatedVisibility(
                visible = detailsVisible || actionsFocused,
                modifier = Modifier.align(Alignment.BottomStart),
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(140)),
            ) {
                Column(
                    modifier = Modifier
                        .padding(
                            start = 24.dp,
                            end = LocalConfiguration.current.screenWidthDp.dp * 0.48f,
                            bottom = rowsViewportHeight + 12.dp,
                        )
                        .widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        hero.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        meta,
                        color = Color.White.copy(alpha = 0.84f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    hero.overview.trim().takeIf(String::isNotBlank)?.let { overview ->
                        Text(
                            overview,
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .tvFocusGroup()
                            .onFocusChanged {
                                actionsFocused = it.hasFocus
                                onActionsFocusChanged(it.hasFocus)
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .then(
                                    if (initialFocusRequester != null) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    if (navFocusRequester != null) {
                                        Modifier.focusRequester(navFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clip(RoundedCornerShape(50))
                                .background(Color.White)
                                .tvFocusBorder(RoundedCornerShape(50))
                                .tvOkPress({ onPlayNow(hero) }, onLongPress = {})
                                .clickable { onPlayNow(hero) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(19.dp),
                            )
                            Text(
                                "Play Now",
                                color = Color.Black,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.2f))
                                .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(50))
                                .tvFocusBorder(RoundedCornerShape(50))
                                .tvOkPress({ onMoreInfo(hero) }, onLongPress = {})
                                .clickable { onMoreInfo(hero) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "More Info",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroPager(
    items: List<HeroBannerItem>,
    initialFocusRequester: FocusRequester? = null,
    navFocusRequester: FocusRequester? = null,
    onInitialItemFocusChanged: (Boolean) -> Unit = {},
    titleLogoUrlFor: (HeroBannerItem) -> String? = { null },
    titleLogoRequestKeyFor: (HeroBannerItem) -> String? = { null },
    onRequestTitleLogoFor: (HeroBannerItem) -> Unit = {},
    onCurrentItemChanged: (HeroBannerItem) -> Unit = {},
    onClick: (HeroBannerItem) -> Unit,
    onPlayNow: (HeroBannerItem) -> Unit = onClick,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tvHeroHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f)
        .coerceIn(270.dp, 420.dp)

    if (isTv) {
        // HorizontalPager intercepts every D-pad left/right at the input level and permanently
        // traps the remote. On TV, auto-cycle with a crossfade instead — only the
        // "View Details" button is focusable, so left/right/up/down all move freely.
        var currentPage by remember { mutableStateOf(0) }
        var buttonHasFocus by remember { mutableStateOf(false) }
        LaunchedEffect(currentPage, items) {
            items.getOrNull(currentPage)?.let(onCurrentItemChanged)
        }
        LaunchedEffect(items.size, buttonHasFocus) {
            if (items.size <= 1 || buttonHasFocus) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(15_000L)
                currentPage = (currentPage + 1) % items.size
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(tvHeroHeight),
        ) {
            Crossfade(
                targetState = currentPage,
                modifier = Modifier.fillMaxSize(),
                label = "tvHeroBanner",
            ) { page ->
                val item = items.getOrNull(page) ?: return@Crossfade
                HeroBannerSlide(
                    item = item,
                    onClick = { onClick(item) },
                    onPlayClick = { onPlayNow(item) },
                    titleLogoUrl = titleLogoUrlFor(item),
                    titleLogoRequestKey = titleLogoRequestKeyFor(item),
                    onRequestTitleLogo = { onRequestTitleLogoFor(item) },
                    isCurrentPage = page == currentPage,
                    buttonFocusRequester = if (page == 0) initialFocusRequester else null,
                    navFocusRequester = if (page == currentPage) navFocusRequester else null,
                    // Keep the hero on its static artwork instead of loading a TV trailer.
                    showTrailerPreview = false,
                    onButtonFocusChanged = { focused ->
                        buttonHasFocus = focused
                        onInitialItemFocusChanged(focused)
                    },
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 14.dp),
            ) {
                items.forEachIndexed { i, _ ->
                    val active = i == currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .height(6.dp)
                            .width(if (active) 22.dp else 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (active) Color.White else Color.White.copy(alpha = 0.55f),
                            ),
                    )
                }
            }
        }
    } else {
        // Mobile / tablet: keep the swipeable horizontal pager.
        val pagerState = rememberPagerState(pageCount = { items.size })
        var pagerHasFocus by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.currentPage, items) {
            items.getOrNull(pagerState.currentPage)?.let(onCurrentItemChanged)
        }
        LaunchedEffect(items.size, pagerHasFocus) {
            if (items.size <= 1 || pagerHasFocus) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(6_000)
                val next = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(next)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 18.dp,
                        shape = RoundedCornerShape(18.dp),
                        clip = false,
                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                    )
                    .height(520.dp + statusBarHeight),
                pageSpacing = 0.dp,
            ) { page ->
                val item = items[page]
                HeroBannerSlide(
                    item = item,
                    onClick = { onClick(item) },
                    onPlayClick = { onPlayNow(item) },
                    titleLogoUrl = titleLogoUrlFor(item),
                    titleLogoRequestKey = titleLogoRequestKeyFor(item),
                    onRequestTitleLogo = { onRequestTitleLogoFor(item) },
                    isCurrentPage = page == pagerState.currentPage,
                    onFocusChange = { pagerHasFocus = it },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                items.forEachIndexed { i, _ ->
                    val active = i == pagerState.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .height(6.dp)
                            .width(if (active) 22.dp else 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MoviesRecoveryCard(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    navFocusRequester: FocusRequester? = null,
    showProgress: Boolean = false,
) {
    var actionModifier: Modifier = Modifier
    if (initialFocusRequester != null) {
        actionModifier = actionModifier.focusRequester(initialFocusRequester)
    }
    if (navFocusRequester != null) {
        actionModifier = actionModifier.focusRequester(navFocusRequester)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onAction,
            modifier = actionModifier
                .padding(top = 18.dp)
                .tvFocusBorder(RoundedCornerShape(24.dp)),
        ) {
            Text(action)
        }
    }
}

@Composable
private fun MoviesLoadingPlaceholder() {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val posterWidth = if (isTv) 152.dp else 124.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 24.dp),
    ) {
        Text(
            text = "Preparing recommendations",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false,
        ) {
            items(6) {
                Box(
                    modifier = Modifier
                        .width(posterWidth)
                        .height(posterWidth * 1.5f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
                )
            }
        }
    }
}@Composable
private fun HeroBannerSlide(
    item: HeroBannerItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayClick: () -> Unit = onClick,
    titleLogoUrl: String? = null,
    titleLogoRequestKey: String? = null,
    onRequestTitleLogo: () -> Unit = {},
    isCurrentPage: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    // TV only: startup focus requester (conditional on startupFocusTarget == "hero").
    buttonFocusRequester: FocusRequester? = null,
    // TV only: always-active nav requester so D-pad Down from the top bar lands here.
    navFocusRequester: FocusRequester? = null,
    showTrailerPreview: Boolean = false,
    onButtonFocusChanged: (Boolean) -> Unit = {},
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    LaunchedEffect(
        item.tmdbId,
        item.stremioMeta?.id,
        titleLogoRequestKey,
        isCurrentPage,
        titleLogoUrl,
    ) {
        if (isCurrentPage && titleLogoUrl.isNullOrBlank()) {
            onRequestTitleLogo()
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // On TV the outer box is a visual container only; the button below is the
            // sole focus target so D-pad navigates freely in all directions.
            .then(if (!isTv) Modifier.tvFocusBorder(RoundedCornerShape(14.dp)) else Modifier)
            .then(if (isTv) Modifier.onFocusChanged { onFocusChange(it.hasFocus) } else Modifier)
            .then(if (!isTv) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        AsyncImage(
            model = if (isTv) tvHighResolutionTmdbArtworkUrl(item.imageUrl) else item.imageUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isTv && showTrailerPreview) {
            val previewMovie = remember(item.tmdbId, item.mediaType, item.title) {
                item.tmdbId?.let { id ->
                    if (item.mediaType == "tv") {
                        TmdbMovie(id = id, name = item.title)
                    } else {
                        TmdbMovie(id = id, title = item.title)
                    }
                }
            }
            if (previewMovie != null) {
                TmdbTrailerPreview(
                    movie = previewMovie,
                    modifier = Modifier.fillMaxSize(),
                    startDelayMs = 2_000L,
                )
            }
        }
        val meta = listOfNotNull(
            if (item.mediaType == "tv") "Series" else "Movie",
            item.year.takeIf { it.isNotBlank() },
            item.rating.takeIf { it.isNotBlank() },
        ).joinToString("  •  ")

        if (isTv) {
            // TV: full-bleed editorial hero with details and actions on the left.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.92f),
                            Color.Black.copy(alpha = 0.62f),
                            Color.Black.copy(alpha = 0.10f),
                            Color.Transparent,
                        )
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.30f),
                            0.20f to Color.Transparent,
                            0.62f to Color.Transparent,
                            0.84f to Color.Black.copy(alpha = 0.22f),
                            1f to Color.Black.copy(alpha = 0.90f),
                        ),
                    )
                )
            )
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(
                        start = 40.dp,
                        end = LocalConfiguration.current.screenWidthDp.dp * 0.48f,
                        top = 30.dp,
                    ),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    if (item.mediaType == "tv") "SERIES" else "FEATURED MOVIE",
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.sp,
                        letterSpacing = 3.sp,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                HeroBannerTitle(
                    title = item.title,
                    logoUrl = titleLogoUrl,
                    isTv = true,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    meta,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                )
                val overview = item.overview.trim()
                if (overview.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        overview,
                        color = Color.White.copy(alpha = 0.90f),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Netflix-style: Play (white, primary) + More Info (semi-transparent) side by side
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Play — white filled, ▶ icon, primary action
                    Row(
                        (if (buttonFocusRequester != null)
                            Modifier.focusRequester(buttonFocusRequester)
                        else Modifier)
                            .let { if (navFocusRequester != null) it.focusRequester(navFocusRequester) else it }
                            .onFocusChanged { onButtonFocusChanged(it.isFocused) }
                            .tvFocusBorder(RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                            .clickable(onClick = onPlayClick)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            "Play Now",
                            color = Color.Black,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    // More Info — semi-transparent, secondary action
                    Row(
                        Modifier
                            .tvFocusBorder(RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.22f))
                            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(50))
                            .clickable(onClick = onClick)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            "More Info",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
        } else {
            // Mobile/tablet: vertical gradient + centred layout
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.65f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.92f),
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    )
                )
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HeroBannerTitle(
                    title = item.title,
                    logoUrl = titleLogoUrl,
                    isTv = false,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    meta,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 26.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "View Details",
                        color = Color.Black,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroBannerTitle(
    title: String,
    logoUrl: String?,
    isTv: Boolean,
) {
    if (!logoUrl.isNullOrBlank()) {
        AsyncImage(
            model = if (isTv) tvHighResolutionTmdbArtworkUrl(logoUrl) else logoUrl,
            contentDescription = "$title logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(if (isTv) 0.56f else 0.72f)
                .height(if (isTv) 68.dp else 76.dp),
        )
    } else {
        Text(
            title,
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Black,
                fontSize = if (isTv) 38.sp else 36.sp,
                lineHeight = if (isTv) 42.sp else 40.sp,
            ),
            color = Color.White,
            textAlign = if (isTv) {
                androidx.compose.ui.text.style.TextAlign.Start
            } else {
                androidx.compose.ui.text.style.TextAlign.Center
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoviesSearchField(query: String, loading: Boolean, onQueryChange: (String) -> Unit) {
    androidx.compose.material3.TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp)),
        placeholder = { Text("Search movies, series, anime") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
            if (loading) CircularProgressIndicator(
                Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}


@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(12.dp),
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close, "Dismiss",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    Text(
        text,
        style = if (isTv) {
            MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
        } else {
            MaterialTheme.typography.headlineSmall
        },
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(
            horizontal = if (isTv) 24.dp else 20.dp,
            vertical = if (isTv) 7.dp else 12.dp,
        ),
    )
}

/** Card appended at the tail of a category LazyRow on TV so the D-pad can select "View all". */
@Composable
private fun ViewAllCard(
    posterStyle: String = "portrait",
    onClick: () -> Unit,
) {
    val useLandscape = posterStyle == "landscape"
    val width = if (useLandscape) 220.dp else 140.dp
    val ratio = if (useLandscape) 16f / 9f else 2f / 3f
    Column(
        modifier = Modifier
            .width(width)
            .tvFocusBorder(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "View all",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SectionTitleWithViewAll(title: String, onViewAll: () -> Unit) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTv) 24.dp else 20.dp,
                vertical = if (isTv) 7.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = if (isTv) {
                MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
            } else {
                MaterialTheme.typography.headlineSmall
            },
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "View all →",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onViewAll)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AddonSectionTitle(addon: String, catalog: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            catalog,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AddonSectionTitleWithViewAll(
    addon: String,
    catalog: String,
    onViewAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                catalog,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "View all →",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onViewAll)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingCard(
    entry: WatchProgressEntity,
    isTv: Boolean,
    tmdbApi: com.streamcloud.app.data.api.TmdbApi,
    tmdbApiKey: String,
    cachedBackdropUrl: String?,
    onBackdropResolved: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val cardWidth = if (isTv) {
        (LocalConfiguration.current.screenWidthDp.dp * 0.21f).coerceIn(176.dp, 272.dp)
    } else {
        320.dp
    }
    val cardHeight = if (isTv) cardWidth * 0.54f else 124.dp
    val pct = if (entry.durationMs > 0L)
        (entry.positionMs.toFloat() / entry.durationMs.toFloat()).coerceIn(0f, 1f)
    else 0f
    val watchedPercent = if (entry.durationMs > 0L) {
        (pct * 100f + 0.5f).toInt().coerceAtLeast(1)
    } else {
        0
    }
    val landscapeImageUrl by androidx.compose.runtime.produceState(
        initialValue = cachedBackdropUrl ?: entry.posterUrl,
        entry.tmdbId,
        entry.mediaType,
        entry.posterUrl,
        tmdbApiKey,
        cachedBackdropUrl,
    ) {
        if (cachedBackdropUrl != null) {
            value = cachedBackdropUrl
            return@produceState
        }
        if (entry.tmdbId <= 0L || tmdbApiKey.isBlank()) {
            return@produceState
        }
        val details = runCatching {
            if (entry.mediaType == "tv") {
                tmdbApi.tvDetails(entry.tmdbId, tmdbApiKey)
            } else {
                tmdbApi.details(entry.tmdbId, tmdbApiKey)
            }
        }.getOrNull()
        val resolvedBackdrop = details?.backdropUrl?.takeIf { it.isNotBlank() }
        if (resolvedBackdrop != null) {
            value = resolvedBackdrop
            onBackdropResolved(resolvedBackdrop)
        } else {
            value = entry.posterUrl
        }
    }
    val cardModifier = modifier
        .onFocusChanged { onFocusChanged(it.isFocused || it.hasFocus) }
        .width(cardWidth)
        .height(cardHeight)
        .tvOkPress(onClick, onLongPress)
        .tvFocusBorder(RoundedCornerShape(14.dp))
        .clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surface)
        .combinedClickable(onClick = onClick, onLongClick = onLongPress)

    Box(cardModifier) {
        AsyncImage(
            model = if (isTv) tvHighResolutionTmdbArtworkUrl(landscapeImageUrl) else landscapeImageUrl,
            contentDescription = entry.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.88f),
                            Color.Black.copy(alpha = 0.62f),
                            Color.Black.copy(alpha = 0.16f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Text(
                entry.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = if (isTv) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isTv) continueWatchingLandscapeSubtitle(entry) else watchProgressDisplayLabel(entry),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (!isTv) {
                Text(
                    "$watchedPercent% watched",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.26f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(pct.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun KeepTvFocusedItemAtStart(
    listState: LazyListState,
    focusedIndex: Int,
    isTv: Boolean,
) {
    LaunchedEffect(isTv, focusedIndex) {
        if (!isTv) return@LaunchedEffect
        if (focusedIndex !in 0 until listState.layoutInfo.totalItemsCount) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == focusedIndex }) {
            listState.scrollToItem(focusedIndex)
        }

        // Focused posters animate between portrait and landscape widths. Aligning
        // against the pre-animation width can overscroll the row and clip the
        // selected card at the leading edge. Wait until layout width settles.
        var previousSize: Int? = null
        var stableFrames = 0
        for (frame in 0 until 28) {
            androidx.compose.runtime.withFrameNanos { }
            val current = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == focusedIndex }
            if (current == null) {
                stableFrames = 0
                previousSize = null
                continue
            }
            if (current.size == previousSize) {
                stableFrames += 1
            } else {
                previousSize = current.size
                stableFrames = 0
            }
            if (stableFrames >= 3) break
        }

        val layoutInfo = listState.layoutInfo
        val focusedItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
            ?: return@LaunchedEffect
        val scrollByPx = focusedItem.offset - layoutInfo.beforeContentPadding
        if (scrollByPx != 0) {
            listState.animateScrollBy(
                value = scrollByPx.toFloat(),
                animationSpec = tween(durationMillis = 160),
            )
        }
    }
}

private fun continueWatchingLandscapeSubtitle(entry: WatchProgressEntity): String {
    val episodeLabel = if (
        entry.mediaType == "tv" &&
        entry.seasonNumber > 0 &&
        entry.episodeNumber > 0
    ) {
        "S${entry.seasonNumber} E${entry.episodeNumber}"
    } else if (entry.mediaType == "tv") {
        "Series"
    } else {
        "Movie"
    }
    val remainingMinutes = if (entry.durationMs > 0L) {
        ((entry.durationMs - entry.positionMs).coerceAtLeast(0L) + 59_999L) / 60_000L
    } else {
        0L
    }
    val remainingLabel = when {
        remainingMinutes <= 0L -> null
        remainingMinutes >= 60L ->
            "${remainingMinutes / 60L}h ${remainingMinutes % 60L}m left"
        else -> "${remainingMinutes}m left"
    }
    return listOfNotNull(episodeLabel, remainingLabel).joinToString(" · ")
}

@Composable
private fun LandscapeCardTitle(title: String, logoUrl: String? = null) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(58.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                    ),
                ),
        )
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = "$title logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 7.dp)
                    .fillMaxWidth(0.78f)
                    .height(44.dp),
            )
        } else {
            Text(
                title,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CsHomePosterCard(
    item: SearchResponse,
    defaultWidth: Dp,
    isTv: Boolean,
    tmdbPrefetchDelayMillis: Long,
    homeCardStyle: String,
    tmdbMovie: TmdbMovie?,
    onRequestTmdbMovie: () -> Unit,
    titleLogoUrl: String?,
    onRequestTitleLogo: (Long, String) -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    var isFocused by remember(item.url) { mutableStateOf(false) }
    val isExpanded = isTv && homeCardStyle == "expanding" && isFocused
    val useLandscape = homeCardStyle == "landscape"
    val imageRatio = if (useLandscape) 16f / 9f else 2f / 3f
    val expandedWidth = if (homeCardStyle == "expanding" || useLandscape) 320.dp else 160.dp
    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) expandedWidth else defaultWidth,
        animationSpec = tween(durationMillis = 260),
        label = "cloudstream-home-card-width",
    )
    val animatedImageHeight by animateDpAsState(
        targetValue = if (isExpanded) 180.dp else defaultWidth / imageRatio,
        animationSpec = tween(durationMillis = 260),
        label = "cloudstream-home-card-image-height",
    )

    LaunchedEffect(isTv, isFocused, item.url, tmdbMovie?.id, tmdbPrefetchDelayMillis) {
        if (!isTv || tmdbMovie != null) return@LaunchedEffect
        val delayMillis = if (isFocused) 0L else tmdbPrefetchDelayMillis
        if (delayMillis > 0L) kotlinx.coroutines.delay(delayMillis)
        onRequestTmdbMovie()
    }

    LaunchedEffect(isTv, isFocused, tmdbMovie?.id, titleLogoUrl) {
        if (!isTv || tmdbMovie == null || titleLogoUrl != null) return@LaunchedEffect
        if (!isFocused) kotlinx.coroutines.delay(250L)
        onRequestTitleLogo(tmdbMovie.id, if (tmdbMovie.title != null) "movie" else "tv")
    }

    val posterShape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(animatedWidth)
            .tvOkPress(onClick, onLongPress = {})
            .onFocusChanged {
                isFocused = it.isFocused || it.hasFocus
                onFocusChanged(isFocused)
            }
            .then(if (isTv) Modifier else Modifier.animateContentSize(animationSpec = tween(durationMillis = 260)))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedImageHeight)
                .clip(posterShape)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val artworkUrl = if (isTv && (useLandscape || isExpanded)) {
                tmdbMovie?.backdropUrl?.takeIf { it.isNotBlank() } ?: item.posterUrl
            } else {
                item.posterUrl
            }
            AsyncImage(
                model = if (isTv) tvHighResolutionTmdbArtworkUrl(artworkUrl) else artworkUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isExpanded || isTv) {
                LandscapeCardTitle(tmdbMovie?.displayTitle ?: item.name, titleLogoUrl)
            }
            if (isTv && isFocused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(4.dp, Color(0xFF62D8FF), posterShape),
                )
            }
        }

        if (isExpanded) {
            val releaseInfo = tmdbMovie?.displayReleaseInfo()
            val overview = tmdbMovie?.overview?.trim().orEmpty()
            if (releaseInfo != null || overview.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    releaseInfo?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                    overview.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MidPoster(
    m: TmdbMovie,
    posterStyle: String = "portrait",
    landscapeCardWidth: Dp = 220.dp,
    isWatched: Boolean = false,
    isTv: Boolean = false,
    titleLogoUrl: String? = null,
    onRequestTitleLogo: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val useLandscape = posterStyle == "landscape" ||
        (posterStyle == "auto" && m.backdropUrl != null)
    val imageUrl = if (useLandscape) m.backdropUrl ?: m.posterUrl else m.posterUrl
    val ratio = if (useLandscape) 16f / 9f else 2f / 3f
    val width = if (useLandscape) landscapeCardWidth else 140.dp
    val mediaType = if (m.title != null) "movie" else "tv"
    val showTitleOnThumbnail = useLandscape
    var isFocused by remember(m.id) { mutableStateOf(false) }
    val isExpanded = isTv && posterStyle == "expanding" && isFocused
    LaunchedEffect(useLandscape, isExpanded, m.id, mediaType, titleLogoUrl) {
        if ((!useLandscape && !isExpanded) || titleLogoUrl != null) return@LaunchedEffect
        onRequestTitleLogo()
    }
    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) 320.dp else width,
        animationSpec = tween(durationMillis = 260),
        label = "movie-card-width",
    )
    val animatedImageHeight by animateDpAsState(
        targetValue = if (isExpanded) 180.dp else width / ratio,
        animationSpec = tween(durationMillis = 260),
        label = "movie-card-image-height",
    )
    Column(
        modifier = modifier
            .tvOkPress(onClick, onLongPress)
            .width(animatedWidth)
            .onFocusChanged {
                isFocused = it.isFocused || it.hasFocus
                onFocusChanged(isFocused)
            }
            .then(if (isTv) Modifier else Modifier.animateContentSize(animationSpec = tween(durationMillis = 260)))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(animatedImageHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .then(
                    if (isTv && isFocused) {
                        Modifier.border(
                            4.dp,
                            Color(0xFF62D8FF),
                            RoundedCornerShape(12.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            val artworkUrl = if (isExpanded) m.backdropUrl ?: imageUrl else imageUrl
            AsyncImage(
                model = if (isTv) tvHighResolutionTmdbArtworkUrl(artworkUrl) else artworkUrl,
                contentDescription = m.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isExpanded || showTitleOnThumbnail) {
                LandscapeCardTitle(m.displayTitle, titleLogoUrl)
            }
            if (isWatched) {
                WatchedPosterBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
        }
        if (isExpanded) {
            val releaseInfo = m.displayReleaseInfo()
            val overview = m.overview?.trim().orEmpty()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        listOfNotNull(
                            releaseInfo?.takeIf { it.isNotBlank() },
                            if (m.title != null) "Movie" else "Series",
                        ).joinToString("  •  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                    if (m.voteAverage > 0.0) {
                        Text(
                            "★ ${"%.1f".format(m.voteAverage)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFD166),
                        )
                    }
                }
                if (overview.isNotBlank()) {
                    Text(
                        overview,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else if (!useLandscape && isTv) {
            Spacer(Modifier.height(6.dp))
            Text(
                m.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StremioPoster(
    meta: StremioMetaPreview,
    posterStyle: String = "portrait",
    resolvedTmdbId: Long?,
    onRequestTmdbId: () -> Unit,
    titleLogoUrl: String? = null,
    onRequestTitleLogo: (Long, String) -> Unit = { _, _ -> },
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onClick: () -> Unit,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val useLandscape = posterStyle == "landscape" ||
        (posterStyle == "auto" &&
            (!meta.background.isNullOrBlank() ||
                meta.posterShape.equals("landscape", ignoreCase = true)))
    var isFocused by remember(meta.type, meta.id) { mutableStateOf(false) }
    val isExpanded = isTv && posterStyle == "expanding" && isFocused
    val posterShape = RoundedCornerShape(12.dp)
    val ratio = if (useLandscape) 16f / 9f else 2f / 3f
    val width = if (useLandscape) 220.dp else 140.dp
    val expandedWidth = 320.dp
    val isSeries = meta.type.equals("series", ignoreCase = true) ||
        meta.type.equals("tv", ignoreCase = true)
    val mediaType = if (isSeries) "tv" else "movie"
    val showTitleOnThumbnail = useLandscape
    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) expandedWidth else width,
        animationSpec = tween(durationMillis = 260),
        label = "stremio-card-width",
    )
    val animatedImageHeight by animateDpAsState(
        targetValue = if (isExpanded) 180.dp else width / ratio,
        animationSpec = tween(durationMillis = 260),
        label = "stremio-card-image-height",
    )
    LaunchedEffect(
        isTv,
        isFocused,
        meta.id,
        meta.type,
        resolvedTmdbId,
        titleLogoUrl,
        showTitleOnThumbnail,
        isExpanded,
    ) {
        val shouldResolveTitle = showTitleOnThumbnail || isExpanded
        if (!shouldResolveTitle) return@LaunchedEffect
        if (isTv) {
            // Wait for a stable focus before resolving external catalog IDs and logos.
            kotlinx.coroutines.delay(300L)
        }
        if (resolvedTmdbId == null) onRequestTmdbId()
        else if (titleLogoUrl == null) onRequestTitleLogo(resolvedTmdbId, mediaType)
    }
    val primaryArtwork = if (useLandscape || isExpanded) {
        meta.background?.takeIf { it.isNotBlank() } ?: meta.poster
    } else {
        meta.poster
    }
    val fallbackArtwork = if (useLandscape || isExpanded) meta.poster else null
    Column(
        modifier = modifier
            .tvOkPress(onClick, onLongPress)
            .width(animatedWidth)
            .onFocusChanged {
                isFocused = it.isFocused || it.hasFocus
                onFocusChanged(isFocused)
            }
            .then(if (isTv) Modifier else Modifier.animateContentSize(animationSpec = tween(durationMillis = 260)))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(animatedImageHeight)
                .clip(posterShape)
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (isTv && isFocused) Modifier.border(4.dp, Color(0xFF62D8FF), posterShape)
                    else Modifier,
                ),
        ) {
            MovieArtwork(
                primaryUrl = primaryArtwork,
                fallbackUrl = fallbackArtwork,
                contentDescription = meta.name,
                modifier = Modifier.fillMaxSize(),
            )
            if (isExpanded || showTitleOnThumbnail) {
                LandscapeCardTitle(meta.name, titleLogoUrl)
            }
        }
        if (
            isExpanded &&
            (!meta.releaseInfo.isNullOrBlank() || !meta.description.isNullOrBlank())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!meta.releaseInfo.isNullOrBlank()) {
                    Text(
                        meta.releaseInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!meta.description.isNullOrBlank()) {
                    Text(
                        meta.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else if (!useLandscape) {
            if (isTv) {
                Spacer(Modifier.height(6.dp))
                Text(
                    meta.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!meta.releaseInfo.isNullOrBlank()) {
                if (!isTv) Spacer(Modifier.height(6.dp))
                Text(
                    meta.releaseInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterGrid(
    movies: List<TmdbMovie>,
    watchedTmdbIds: Set<Long>,
    posterStyle: String = "portrait",
    onClick: (TmdbMovie) -> Unit,
    onLongPress: (TmdbMovie) -> Unit = {},
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val chunkSize = if (posterStyle == "landscape") 2 else 3
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .tvFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        movies.chunked(chunkSize).forEach { row ->
            Row(
                modifier = Modifier.tvFocusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { m ->
                    val useLandscape = posterStyle == "landscape" || (posterStyle == "auto" && m.backdropUrl != null)
                    val imageUrl = if (useLandscape) m.backdropUrl ?: m.posterUrl else m.posterUrl
                    val ratio = if (useLandscape) 16f / 9f else 2f / 3f
                    Column(
                        Modifier
                            .weight(1f)
                            .tvOkPress(
                                onClick = { onClick(m) },
                                onLongPress = { onLongPress(m) },
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .tvFocusBorder(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = { onClick(m) },
                                onLongClick = { onLongPress(m) },
                            )
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
                            AsyncImage(
                                model = if (isTv) tvHighResolutionTmdbArtworkUrl(imageUrl) else imageUrl,
                                contentDescription = m.displayTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                            )
                            if (m.id in watchedTmdbIds) {
                                WatchedPosterBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            m.displayTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(chunkSize - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun watchProgressDisplayLabel(entry: WatchProgressEntity): String {
    if (entry.mediaType != "tv") return "Movie"
    return if (entry.seasonNumber > 0 && entry.episodeNumber > 0) {
        "Series · S${entry.seasonNumber.toString().padStart(2, '0')} E${entry.episodeNumber.toString().padStart(2, '0')}"
    } else {
        "Series"
    }
}

@Composable
private fun CwOptionsSheet(
    entry: WatchProgressEntity,
    onGoToDetails: () -> Unit,
    onPlayManually: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onRemove: () -> Unit,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = if (isTv) tvHighResolutionTmdbArtworkUrl(entry.posterUrl) else entry.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 60.dp, height = 86.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    watchProgressDisplayLabel(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        QuickActionRow(Icons.Default.Info, "Go to details", onGoToDetails)
        QuickActionRow(Icons.Default.PlayArrow, "Play manually", onPlayManually)
        QuickActionRow(Icons.Default.Replay, "Start from beginning", onStartFromBeginning)
        QuickActionRow(Icons.Default.Delete, "Remove", onRemove)
    }
}

@Composable
private fun CwOptionsDialog(
    entry: WatchProgressEntity,
    onGoToDetails: () -> Unit,
    onPlayManually: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onRemove: () -> Unit,
) {
    val firstActionFocus = remember { FocusRequester() }
    LaunchedEffect(firstActionFocus) {
        repeat(10) {
            kotlinx.coroutines.delay(120)
            if (runCatching { firstActionFocus.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.78f)
            .widthIn(max = 560.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            Text(
                "Choose what you want to do with this item.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            CwDialogAction(
                icon = Icons.Default.Info,
                label = "Go to details",
                focusRequester = firstActionFocus,
                onClick = onGoToDetails,
            )
            CwDialogAction(Icons.Default.PlayArrow, "Play manually", onClick = onPlayManually)
            CwDialogAction(Icons.Default.Replay, "Start from beginning", onClick = onStartFromBeginning)
            CwDialogAction(Icons.Default.Delete, "Remove", onClick = onRemove)
        }
    }
}

@Composable
private fun CwDialogAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) colors.primary
                else colors.surfaceVariant.copy(alpha = 0.42f),
            )
            .tvFocusBorder(shape, showOutline = false)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            icon,
            null,
            tint = if (focused) colors.onPrimary else colors.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) colors.onPrimary else colors.onSurface,
        )
    }
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun PosterOptionsSheet(
    item: PosterSheetItem,
    isInLibrary: Boolean,
    onAddToLibrary: () -> Unit,
    onMarkAsWatched: () -> Unit,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = if (isTv) tvHighResolutionTmdbArtworkUrl(item.posterUrl) else item.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 60.dp, height = 86.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when (item.mediaType) {
                        "tv", "series" -> "Series"
                        else -> "Movie"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        QuickActionRow(
            icon = Icons.Default.Bookmark,
            label = if (isInLibrary) "Manage watchlists" else "Choose watchlists",
            onClick = onAddToLibrary,
        )
        QuickActionRow(
            icon = Icons.Default.CheckCircle,
            label = "Mark as watched",
            onClick = onMarkAsWatched,
        )
    }
}

@Composable
private fun CollectionFolderTile(folder: CollectionFolderEntity, onClick: () -> Unit) {
    val (width, ratio) = when (folder.tileShape) {
        "poster" -> 120.dp to (2f / 3f)
        "square" -> 160.dp to 1f
        else     -> 200.dp to (16f / 9f)
    }
    Box(
        Modifier
            .width(width)
            .aspectRatio(ratio)
            .tvFocusBorder(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        if (folder.coverUrl.isNotBlank()) {
            AsyncImage(
                model = folder.coverUrl,
                contentDescription = folder.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!folder.hideTitle) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))),
                )
            )
            Text(
                folder.name,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun QuickActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
