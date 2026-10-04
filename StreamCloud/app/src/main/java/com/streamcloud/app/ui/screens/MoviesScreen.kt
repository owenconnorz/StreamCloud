@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.streamcloud.app.ui.screens

import android.view.KeyEvent as AndroidKeyEvent
import com.streamcloud.app.ads.AdPlacement
import com.streamcloud.app.ads.AdvertisingBanner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.streamcloud.app.ui.theme.LocalTvFocusColor
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup
import com.streamcloud.app.ui.theme.tvDpadRepeatThrottle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.MoviesThemeWrapper
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.rememberBannerPalette
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import coil.compose.AsyncImage
import com.lagradost.cloudstream3.SearchResponse
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.collections.HomeCollections
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.stremio.StremioHomeRow
import com.streamcloud.app.data.stremio.StremioMetaPreview
import com.streamcloud.app.data.SettingsRepository
import com.streamcloud.app.ui.components.MovieArtwork
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

private fun TmdbMovie.watchedMediaType(): String = if (title != null) "movie" else "tv"

internal fun csHomeTmdbKey(
    pluginInternalName: String,
    sectionName: String,
    itemUrl: String,
): String = "cs:$pluginInternalName:$sectionName:$itemUrl"

@Composable
private fun Modifier.tvOkPress(
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
    // Always attached to the current hero Play button so the TV nav D-pad Down
    // can jump here even when initialFocusRequester targets something else.
    tvNavHeroFocus: FocusRequester? = null,
    // Incremented by StreamCloudApp whenever the nav bar regains focus; triggers
    // an animated scroll back to the top so the hero is fully visible again.
    navScrollToTopVersion: Int = 0,
    onFirstMovieFocusedChanged: (Boolean) -> Unit = {},
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
    val lifecycleOwner = LocalLifecycleOwner.current
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
    val moviesThemeName by settingsRepo.moviesTheme.collectAsState(initial = "violet")
    val posterStyle by settingsRepo.posterStyle.collectAsState(initial = "portrait")
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var cwSheetEntry by remember { mutableStateOf<WatchProgressEntity?>(null) }
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
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val moviesListState = rememberLazyListState()
    // When the TV nav bar regains focus, scroll back to the top so the hero is fully visible.
    LaunchedEffect(navScrollToTopVersion) {
        if (navScrollToTopVersion > 0) moviesListState.animateScrollToItem(0)
    }
    val firstCollectionRowId = state.collections
        .firstOrNull { it.items.isNotEmpty() }
        ?.id
    val firstStremioRowKey = state.stremioRows
        .firstOrNull { it.items.isNotEmpty() }
        ?.rowKey
    val hasBrowseContent = state.continueWatching.isNotEmpty() ||
        firstCollectionRowId != null ||
        firstStremioRowKey != null ||
        (state.showHeroSection && state.heroBanner.isNotEmpty()) ||
        state.pinnedCollections.any { it.folders.isNotEmpty() } ||
        state.csPluginRows.any { it.items.isNotEmpty() }
    val startupFocusTarget = when {
        state.error != null || !hasBrowseContent -> "recovery"
        state.continueWatching.isNotEmpty() -> "continue"
        firstCollectionRowId != null -> "collection"
        firstStremioRowKey != null -> "stremio"
        !state.loading && state.showHeroSection && state.heroBanner.isNotEmpty() -> "hero"
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
    MoviesThemeWrapper(moviesThemeName) {
    val heroForAmbientColor = activeHeroBanner?.takeIf { it in state.heroBanner }
        ?: state.heroBanner.firstOrNull()
    val bannerPalette = rememberBannerPalette(
        imageUrl = heroForAmbientColor?.imageUrl?.takeIf { state.showHeroSection },
        fallbackAccent = MaterialTheme.colorScheme.primary,
        fallbackOnAccent = MaterialTheme.colorScheme.onPrimary,
        fallbackBackground = MaterialTheme.colorScheme.background,
        fallbackSurface = MaterialTheme.colorScheme.surface,
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(if (isTv) Color.Black else MaterialTheme.colorScheme.background)
            // Keep vertical D-pad movement in focus traversal instead of
            // letting the parent list scroll independently.
            .tvDpadRepeatThrottle(handleInitialPresses = true),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
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
                if (isTv && state.heroBanner.isNotEmpty() && state.showHeroSection) {
                    item(key = "tv_hero_nav_spacing") {
                        // The transparent TV navigation bar is an overlay. Keep the hero
                        // below the actual nav row instead of placing artwork behind it.
                        Spacer(Modifier.statusBarsPadding().height(64.dp))
                    }
                }
                if (state.heroBanner.isNotEmpty() && state.showHeroSection) {
                    item(key = "hero_pager") {
                        HeroPager(
                            items = state.heroBanner,
                            bannerTintColor = bannerPalette.artworkColor,
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
                            navFocusRequester = tvNavHeroFocus,
                            onInitialItemFocusChanged = onFirstMovieFocusedChanged,
                            onClick = { item ->
                                when {
                                    item.tmdbId != null && item.mediaType == "tv" -> onTvClick(item.tmdbId)
                                    item.tmdbId != null -> onMovieClick(item.tmdbId)
                                    item.stremioMeta != null -> vm.openStremioMeta(item.stremioMeta) { tmdbId, _ ->
                                        if (tmdbId != null) {
                                            if (item.stremioMeta.type == "series") onTvClick(tmdbId)
                                            else onMovieClick(tmdbId)
                                        }
                                    }
                                }
                            },
                        )
                    }
                } else {
                    // On TV the transparent top nav bar overlays the content — give enough
                    // clearance so the first row isn't hidden behind it.
                    item { Spacer(Modifier.statusBarsPadding().height(if (isTv) 90.dp else 56.dp)) }
                }

                if (!isTv) {
                    item(key = "movies_advertisement") {
                        AdvertisingBanner(placement = AdPlacement.Movies)
                    }
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
                }
                if (state.continueWatching.isNotEmpty()) {
                    item(key = "continue_watching_t") { SectionTitle("Continue Watching") }
                    item(key = "continue_watching") {
                        LazyRow(
                            modifier = Modifier.tvFocusGroup(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(
                                state.continueWatching,
                                key = { _, entry ->
                                    "cw_${entry.tmdbId}_${entry.mediaType}_${entry.seasonNumber}_${entry.episodeNumber}"
                                },
                            ) { index, entry ->
                                ContinueWatchingCard(
                                    entry = entry,
                                    modifier = if (
                                        index == 0 &&
                                        startupFocusTarget == "continue" &&
                                        initialFocusRequester != null
                                    ) {
                                        Modifier
                                            .focusRequester(initialFocusRequester)
                                            .onFocusChanged {
                                                onFirstMovieFocusedChanged(it.isFocused || it.hasFocus)
                                            }
                                    } else {
                                        Modifier
                                    },
                                    onClick = { openCwEntry(entry) },
                                    onLongPress = { cwSheetEntry = entry },
                                )
                            }
                        }
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

                state.collections.forEachIndexed { _, row ->
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




                        LazyRow(
                            modifier = Modifier
                                .tvFocusGroup()
                                .animateContentSize(animationSpec = tween(durationMillis = 260)),
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
                                    posterStyle = posterStyle,
                                    isWatched = m.id in watchedTmdbIds,
                                    isTv = isTv,
                                    titleLogoUrl = state.tmdbTitleLogos["$mediaType:${m.id}"],
                                    onRequestTitleLogo = { vm.requestTitleLogo(m.id, mediaType) },
                                    modifier = if (
                                        row.id == firstCollectionRowId &&
                                        index == 0 &&
                                        startupFocusTarget == "collection" &&
                                        initialFocusRequester != null
                                    ) {
                                        Modifier
                                            .focusRequester(initialFocusRequester)
                                            .onFocusChanged {
                                                onFirstMovieFocusedChanged(it.isFocused || it.hasFocus)
                                            }
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
                                        posterStyle = posterStyle,
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
                            catalog = row.catalogName,
                            onViewAll = {
                                onOpenCatalog(
                                    "stremio:${row.addonId}:${row.type}:${row.catalogId}",
                                    row.catalogName,
                                    row.addonName,
                                )
                            },
                        )
                    }
                    item(key = "stremio_${row.rowKey}") {
                        LazyRow(
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
                                    posterStyle = posterStyle,
                                    resolvedTmdbId = resolvedTmdbId,
                                    onRequestTmdbId = { vm.requestStremioTmdbId(meta) },
                                    titleLogoUrl = resolvedTmdbId?.let {
                                        state.tmdbTitleLogos["$mediaType:$it"]
                                    },
                                    onRequestTitleLogo = vm::requestTitleLogo,
                                    modifier = if (
                                        row.rowKey == firstStremioRowKey &&
                                        index == 0 &&
                                        startupFocusTarget == "stremio" &&
                                        initialFocusRequester != null
                                    ) {
                                        Modifier
                                            .focusRequester(initialFocusRequester)
                                            .onFocusChanged {
                                                onFirstMovieFocusedChanged(it.isFocused)
                                            }
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
                                        posterStyle = posterStyle,
                                        onClick = {
                                            onOpenCatalog(
                                                "stremio:${row.addonId}:${row.type}:${row.catalogId}",
                                                row.catalogName,
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
                        val csLandscape = isTv || posterStyle == "landscape"
                        val csCardWidth = if (csLandscape) 200.dp else 120.dp
                        val csAspect = if (csLandscape) 16f / 9f else 2f / 3f
                        LazyRow(
                            modifier = Modifier.tvFocusGroup(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                row.items,
                                key = { "${row.pluginInternalName}_${row.sectionName}_${it.url}" },
                            ) { sr ->
                                val tmdbKey = csHomeTmdbKey(
                                    row.pluginInternalName,
                                    row.sectionName,
                                    sr.url,
                                )
                                val csTmdbMovie = state.csHomeTmdbMovies[tmdbKey]
                                val csMediaType = if (csTmdbMovie?.title != null) "movie" else "tv"
                                CsHomePosterCard(
                                    item = sr,
                                    defaultWidth = csCardWidth,
                                    isTv = isTv,
                                    tmdbMovie = csTmdbMovie,
                                    onRequestTmdbMovie = {
                                        vm.requestCsHomeTmdbMovie(tmdbKey, sr)
                                    },
                                    titleLogoUrl = csTmdbMovie?.let {
                                        state.tmdbTitleLogos["$csMediaType:${it.id}"]
                                    },
                                    onRequestTitleLogo = vm::requestTitleLogo,
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
                                        posterStyle = if (csAspect > 1f) "landscape" else "portrait",
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
            ModalBottomSheet(
                onDismissRequest = { cwSheetEntry = null },
                sheetState = cwSheetState,
            ) {
                CwOptionsSheet(
                    entry = cwEntry,
                    onGoToDetails = {
                        cwSheetEntry = null
                        openCwEntry(cwEntry)
                    },
                    onPlayManually = {
                        cwSheetEntry = null
                        openCwEntry(cwEntry)
                    },
                    onStartFromBeginning = {
                        cwSheetEntry = null
                        vm.resetWatchProgress(
                            cwEntry.tmdbId,
                            cwEntry.mediaType,
                            cwEntry.seasonNumber,
                            cwEntry.episodeNumber,
                        )
                        openCwEntry(cwEntry)
                    },
                    onRemove = {
                        cwSheetEntry = null
                        vm.deleteWatchProgress(
                            cwEntry.tmdbId,
                            cwEntry.mediaType,
                            cwEntry.seasonNumber,
                            cwEntry.episodeNumber,
                        )
                    },
                )
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

        // On TV the TvNetflixTopNav in StreamCloudApp already provides the header
        // (with "StreamCloud" title + tabs). Rendering MoviesHeader here too
        // causes the double "StreamCloud" text visible in the top-left corner.
        if (!isTv) {
            MoviesHeader(
                onProfileClick = onProfileClick,
                onOpenCollections = onOpenCollections,
                onSearchClick = onSearchClick,
                onPluginsClick = onPluginsClick,
                hasPlugins = state.installedPlugins.isNotEmpty(),
            )
        }
    }
    } // MoviesThemeWrapper
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroPager(
    items: List<HeroBannerItem>,
    bannerTintColor: Color,
    initialFocusRequester: FocusRequester? = null,
    navFocusRequester: FocusRequester? = null,
    onInitialItemFocusChanged: (Boolean) -> Unit = {},
    titleLogoUrlFor: (HeroBannerItem) -> String? = { null },
    titleLogoRequestKeyFor: (HeroBannerItem) -> String? = { null },
    onRequestTitleLogoFor: (HeroBannerItem) -> Unit = {},
    onCurrentItemChanged: (HeroBannerItem) -> Unit = {},
    onClick: (HeroBannerItem) -> Unit,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Keep the TV hero inset like Netflix's rounded landscape card.
    val tvHeroInset = 24.dp
    val tvHeroWidth = (LocalConfiguration.current.screenWidthDp.dp - tvHeroInset * 2)
        .coerceAtLeast(320.dp)
    val tvHeroHeight = tvHeroWidth * (7f / 16f)

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
                .padding(horizontal = tvHeroInset)
                .height(tvHeroHeight)
                .clip(RoundedCornerShape(24.dp)),
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
                    bannerTintColor = bannerTintColor,
                    titleLogoUrl = titleLogoUrlFor(item),
                    titleLogoRequestKey = titleLogoRequestKeyFor(item),
                    onRequestTitleLogo = { onRequestTitleLogoFor(item) },
                    isCurrentPage = page == currentPage,
                    // Only page 0 carries the startup focus requester. Crossfade composes
                    // both old and new content during the transition, so attaching the same
                    // requester to every page would cause a duplicate-requester error.
                    buttonFocusRequester = if (page == 0) initialFocusRequester else null,
                    navFocusRequester = if (page == currentPage) navFocusRequester else null,
                    showTrailerPreview = page == currentPage,
                    onButtonFocusChanged = { focused ->
                        buttonHasFocus = focused
                        onInitialItemFocusChanged(focused)
                    },
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 18.dp),
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
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
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
                modifier = Modifier.fillMaxWidth().height(520.dp + statusBarHeight),
                pageSpacing = 0.dp,
            ) { page ->
                val item = items[page]
                HeroBannerSlide(
                    item = item,
                    onClick = { onClick(item) },
                    bannerTintColor = bannerTintColor,
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
private fun HeroBannerSlide(
    item: HeroBannerItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    bannerTintColor: Color? = null,
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
            .then(if (isTv) Modifier.clip(RoundedCornerShape(24.dp)) else Modifier)
            .background(MaterialTheme.colorScheme.surface)
            // On TV the outer box is a visual container only; the button below is the
            // sole focus target so D-pad navigates freely in all directions.
            .then(if (!isTv) Modifier.tvFocusBorder(RoundedCornerShape(14.dp)) else Modifier)
            .then(if (isTv) Modifier.onFocusChanged { onFocusChange(it.hasFocus) } else Modifier)
            .then(if (!isTv) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (bannerTintColor != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to bannerTintColor.copy(alpha = 0.22f),
                                0.48f to bannerTintColor.copy(alpha = 0.13f),
                                1f to bannerTintColor.copy(alpha = 0.04f),
                            ),
                        ),
                    ),
            )
        }
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
            // TV: Netflix-style — left-side + bottom gradient, title/buttons bottom-left
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.25f),
                            Color.Transparent,
                        )
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.38f),
                            0.16f to Color.Black.copy(alpha = 0.10f),
                            0.34f to Color.Transparent,
                            0.62f to Color.Transparent,
                            0.76f to Color.Black.copy(alpha = 0.16f),
                            0.88f to Color.Black.copy(alpha = 0.68f),
                            1f to Color.Black.copy(alpha = 0.97f),
                        ),
                    )
                )
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 48.dp, bottom = 52.dp, end = 260.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                HeroBannerTitle(
                    title = item.title,
                    logoUrl = titleLogoUrl,
                    isTv = true,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    meta,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(22.dp))
                // Netflix-style: Play (white, primary) + More Info (semi-transparent) side by side
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                            .clickable(onClick = onClick)
                            .padding(horizontal = 28.dp, vertical = 13.dp),
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
                            "Play",
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
                            .padding(horizontal = 28.dp, vertical = 13.dp),
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
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 38.dp, vertical = 14.dp),
                ) {
                    Text(
                        "View Details",
                        color = Color(0xFF111111),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
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
            model = logoUrl,
            contentDescription = "$title logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(if (isTv) 0.56f else 0.72f)
                .height(if (isTv) 100.dp else 76.dp),
        )
    } else {
        Text(
            title,
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Black,
                fontSize = if (isTv) 44.sp else 36.sp,
                lineHeight = if (isTv) 48.sp else 40.sp,
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
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
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
        Text(
            "from $addon",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text(
                "from $addon",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val pct = if (entry.durationMs > 0L)
        (entry.positionMs.toFloat() / entry.durationMs.toFloat()).coerceIn(0f, 1f)
    else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .width(320.dp)
            .tvOkPress(onClick, onLongPress)
            .tvFocusBorder(RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(8.dp),
    ) {
        AsyncImage(
            model = entry.posterUrl,
            contentDescription = entry.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 84.dp, height = 116.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                watchProgressDisplayLabel(entry),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(pct.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${(pct * 100).toInt()}% watched",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
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
    tmdbMovie: TmdbMovie?,
    onRequestTmdbMovie: () -> Unit,
    titleLogoUrl: String?,
    onRequestTitleLogo: (Long, String) -> Unit,
    onClick: () -> Unit,
) {
    var isFocused by remember(item.url) { mutableStateOf(false) }
    val isExpanded = isTv && isFocused
    val imageRatio = if (defaultWidth >= 180.dp) 16f / 9f else 2f / 3f
    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) 320.dp else defaultWidth,
        animationSpec = tween(durationMillis = 260),
        label = "cloudstream-home-card-width",
    )
    val animatedImageHeight by animateDpAsState(
        targetValue = if (isExpanded) 180.dp else defaultWidth / imageRatio,
        animationSpec = tween(durationMillis = 260),
        label = "cloudstream-home-card-image-height",
    )

    LaunchedEffect(isTv, isFocused, item.url, tmdbMovie?.id) {
        if (!isTv || !isFocused || tmdbMovie != null) return@LaunchedEffect
        kotlinx.coroutines.delay(350L)
        onRequestTmdbMovie()
    }

    LaunchedEffect(isTv, isFocused, tmdbMovie?.id, titleLogoUrl) {
        if (!isTv || !isFocused || tmdbMovie == null || titleLogoUrl != null) return@LaunchedEffect
        onRequestTitleLogo(tmdbMovie.id, if (tmdbMovie.title != null) "movie" else "tv")
    }

    val posterShape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(animatedWidth)
            .tvOkPress(onClick, onLongPress = {})
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .animateContentSize(animationSpec = tween(durationMillis = 260))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedImageHeight)
                .clip(posterShape)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            AsyncImage(
                model = if (isExpanded || (isTv && defaultWidth >= 180.dp)) {
                    tmdbMovie?.backdropUrl?.takeIf { it.isNotBlank() } ?: item.posterUrl
                } else {
                    item.posterUrl
                },
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isExpanded) {
                LandscapeCardTitle(tmdbMovie?.displayTitle ?: item.name, titleLogoUrl)
            }
            if (isExpanded) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, MaterialTheme.colorScheme.primary, posterShape),
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
    isWatched: Boolean = false,
    isTv: Boolean = false,
    titleLogoUrl: String? = null,
    onRequestTitleLogo: () -> Unit = {},
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val useLandscape = isTv ||
        posterStyle == "landscape" ||
        (posterStyle == "auto" && m.backdropUrl != null)
    val imageUrl = if (useLandscape) m.backdropUrl ?: m.posterUrl else m.posterUrl
    val ratio = if (useLandscape) 16f / 9f else 2f / 3f
    val width = if (useLandscape) 220.dp else 140.dp
    val mediaType = if (m.title != null) "movie" else "tv"
    val showTitleOnThumbnail = true
    var isFocused by remember(m.id) { mutableStateOf(false) }
    val isExpanded = isTv && isFocused
    LaunchedEffect(m.id, mediaType, titleLogoUrl) {
        if (titleLogoUrl == null) onRequestTitleLogo()
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
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .animateContentSize(animationSpec = tween(durationMillis = 260))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(animatedImageHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .then(
                    if (isExpanded) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(12.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            AsyncImage(
                model = if (isExpanded) m.backdropUrl ?: imageUrl else imageUrl,
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
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onClick: () -> Unit,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val useLandscape = isTv ||
        posterStyle == "landscape" ||
        (posterStyle == "auto" &&
            (!meta.background.isNullOrBlank() ||
                meta.posterShape.equals("landscape", ignoreCase = true)))
    var isFocused by remember(meta.type, meta.id) { mutableStateOf(false) }
    val isExpanded = isTv && isFocused
    val focusColor = LocalTvFocusColor.current.takeUnless { it == Color.White }
        ?: MaterialTheme.colorScheme.primary
    val posterShape = RoundedCornerShape(12.dp)
    val ratio = if (useLandscape) 16f / 9f else 2f / 3f
    val width = if (useLandscape) 220.dp else 140.dp
    val isSeries = meta.type.equals("series", ignoreCase = true) ||
        meta.type.equals("tv", ignoreCase = true)
    val mediaType = if (isSeries) "tv" else "movie"
    val showTitleOnThumbnail = true
    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) 320.dp else width,
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
    ) {
        val shouldResolveTitle = showTitleOnThumbnail
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
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .animateContentSize(animationSpec = tween(durationMillis = 260))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(animatedImageHeight)
                .clip(posterShape)
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (isExpanded) Modifier.border(2.dp, focusColor, posterShape)
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
                                model = imageUrl,
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
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = entry.posterUrl,
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
private fun PosterOptionsSheet(
    item: PosterSheetItem,
    isInLibrary: Boolean,
    onAddToLibrary: () -> Unit,
    onMarkAsWatched: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.posterUrl,
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
