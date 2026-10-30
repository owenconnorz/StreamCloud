package com.streamcloud.app.ui.viewmodel

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.api.TmdbTitleLogo
import com.streamcloud.app.data.collections.HomeCollection
import com.streamcloud.app.data.collections.HomeCollections
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.nuvio.matchesLocalProgress
import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioHomeRow
import com.streamcloud.app.data.stremio.StremioMetaPreview
import com.streamcloud.app.data.stremio.StremioRepository
import com.streamcloud.app.data.stremio.adultStremioHomeRows
import com.streamcloud.app.data.stremio.isAdultStremioLabel
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.isEpisodeBased
import com.streamcloud.app.data.plugins.PinnedCsSection
import com.streamcloud.app.data.plugins.PluginRuntime
import retrofit2.HttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SOURCE_BUILTIN = "builtin"
// Don't let a stalled TMDB shelf keep the cold-start home loading for half a minute.
// Rows publish independently, so a slow collection can time out while others remain visible.
private const val HOME_COLLECTION_TIMEOUT_MS = 12_000L
// Cached shelves remain visible while stale data refreshes; a longer freshness window avoids
// needlessly refetching the full home on typical app reopen intervals.
private const val HOME_COLLECTION_STALE_AFTER_MS = 30 * 60 * 1000L
private const val GENRE_ARTWORK_STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000L
private const val SECONDARY_HOME_STARTUP_WAIT_MS = 8_000L
private const val SECONDARY_HOME_QUIET_PERIOD_MS = 2_000L
private val TV_HOME_GENRE_COLLECTION_IDS = listOf("action", "comedy", "drama", "horror", "scifi")
private const val HOME_PERFORMANCE_TAG = "MoviesHomePerf"

internal fun isPersistedHomeCollectionCacheFresh(
    savedAtMillis: Long,
    nowMillis: Long,
    staleAfterMillis: Long,
): Boolean {
    val ageMillis = nowMillis - savedAtMillis
    return savedAtMillis > 0L && ageMillis >= 0L && ageMillis < staleAfterMillis
}

internal fun staleHomeCollectionIds(
    collectionIds: List<String>,
    cachedCollectionIds: Set<String>,
    savedAtByCollectionId: Map<String, Long>,
    legacySavedAtMillis: Long,
    nowMillis: Long,
    staleAfterMillis: Long,
): Set<String> = collectionIds.filterTo(linkedSetOf()) { id ->
    id !in cachedCollectionIds ||
        !isPersistedHomeCollectionCacheFresh(
            savedAtMillis = savedAtByCollectionId[id] ?: legacySavedAtMillis,
            nowMillis = nowMillis,
            staleAfterMillis = staleAfterMillis,
        )
}

internal fun shouldShowMoviesHomeLoading(hasVisibleRows: Boolean): Boolean =
    !hasVisibleRows

data class HeroBannerItem(
    val imageUrl: String,
    val title: String,
    val year: String = "",
    val rating: String = "",
    val overview: String = "",
    val tmdbId: Long? = null,
    val mediaType: String = "movie",
    val stremioMeta: StremioMetaPreview? = null,
)

data class CsPluginRow(
    val pluginInternalName: String,
    val pluginDisplayName: String,
    val sectionName: String,
    val items: List<SearchResponse>,
)

data class CsSearchResult(
    val pluginName: String,
    val pluginInternalName: String,
    val filePath: String,
    val item: SearchResponse,
)

data class StremioSearchResult(
    val addonName: String,
    val addonId: String,
    val item: StremioMetaPreview,
)

data class CollectionRow(
    val id: String,
    val title: String,
    val emoji: String,
    val items: List<TmdbMovie>,
)

data class PinnedCollectionRow(
    val collectionId: Long,
    val collectionName: String,
    val folders: List<CollectionFolderEntity>,
    val viewMode: String = "rows",
)

data class MoviesState(
    val trending: List<TmdbMovie> = emptyList(),
    val popular: List<TmdbMovie> = emptyList(),
    val topRated: List<TmdbMovie> = emptyList(),
    val nowPlaying: List<TmdbMovie> = emptyList(),
    val collections: List<CollectionRow> = emptyList(),
    val genreArtwork: Map<String, String> = emptyMap(),
    val heroBanner: List<HeroBannerItem> = emptyList(),
    val continueWatching: List<WatchProgressEntity> = emptyList(),
    val searchResults: List<TmdbMovie> = emptyList(),
    val tvSearchResults: List<TmdbMovie> = emptyList(),
    val searchCorrection: String? = null,
    val csSearchResults: List<CsSearchResult> = emptyList(),
    val stremioSearchResults: List<StremioSearchResult> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val moviesLoading: Boolean = false,
    val seriesLoading: Boolean = false,
    val moviePagination: TmdbSearchPagination = TmdbSearchPagination(),
    val tvPagination: TmdbSearchPagination = TmdbSearchPagination(),
    val csLoading: Boolean = false,
    val stremioLoading: Boolean = false,
    val installedPlugins: List<InstalledPlugin> = emptyList(),
    val installedStremioAddons: List<InstalledStremioAddon> = emptyList(),
    val stremioRows: List<StremioHomeRow> = emptyList(),
    val stremioAdultCatalogs: List<StremioHomeRow> = emptyList(),
    val stremioTmdbIds: Map<String, Long> = emptyMap(),
    val csHomeTmdbMovies: Map<String, TmdbMovie> = emptyMap(),
    val tmdbTitleLogos: Map<String, String> = emptyMap(),
    val watchlist: List<WatchlistEntity> = emptyList(),
    val csPluginRows: List<CsPluginRow> = emptyList(),
    val pinnedCollections: List<PinnedCollectionRow> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val showHeroSection: Boolean = true,
    val hideCatalogUnderline: Boolean = false,
    val hideUnreleasedContent: Boolean = false,
)

private data class HomeCollectionFetchResult(
    val row: CollectionRow?,
    val failure: String?,
)

private data class HomeCollectionConfig(
    val ids: List<String>,
    val hideUnreleased: Boolean,
)

private fun watchProgressIdentity(
    tmdbId: Long,
    mediaType: String,
    seasonNumber: Int,
    episodeNumber: Int,
): String = "$tmdbId:$mediaType:$seasonNumber:$episodeNumber"

private fun watchProgressIdentity(entry: WatchProgressEntity): String =
    watchProgressIdentity(
        entry.tmdbId,
        entry.mediaType,
        entry.seasonNumber,
        entry.episodeNumber,
    )

internal fun bestCloudStreamHomeTmdbMatch(
    results: List<TmdbMovie>,
    title: String,
    year: Int?,
): TmdbMovie? {
    val normalizedTitle = normalizeHomeMatchTitle(title)
    val titleWithoutYear = year
        ?.let { normalizedTitle.removeSuffix(it.toString()).trim() }
        ?.takeIf { it.isNotBlank() }
        ?: normalizedTitle
    if (normalizedTitle.isBlank()) return null

    var bestMatch: TmdbMovie? = null
    var bestScore = Int.MIN_VALUE
    results.forEach { candidate ->
        val candidateTitle = normalizeHomeMatchTitle(candidate.displayTitle)
        val titleScore = when {
            candidateTitle == normalizedTitle || candidateTitle == titleWithoutYear -> 100
            candidateTitle.contains(normalizedTitle) ||
                normalizedTitle.contains(candidateTitle) -> 55
            candidateTitle.contains(titleWithoutYear) ||
                titleWithoutYear.contains(candidateTitle) -> 45
            else -> 0
        }
        if (titleScore == 0) return@forEach

        val candidateYear = (candidate.releaseDate ?: candidate.firstAirDate)
            ?.substringBefore('-')
            ?.toIntOrNull()
        val yearScore = when {
            year == null || candidateYear == null -> 0
            candidateYear == year -> 35
            else -> -35
        }
        val score = titleScore + yearScore
        if (score > bestScore) {
            bestScore = score
            bestMatch = candidate
        }
    }
    return bestMatch
}

private fun normalizeHomeMatchTitle(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private fun normalizeStremioRows(rows: Any?): List<StremioHomeRow> =
    (rows as? List<*>)
        .orEmpty()
        .mapNotNull { candidate ->
            val row = candidate as? StremioHomeRow ?: return@mapNotNull null
            val items = (row.items as? List<*>)
                .orEmpty()
                .mapNotNull { it as? StremioMetaPreview }
            row.copy(items = items).takeIf { items.isNotEmpty() }
        }

internal fun orderStremioHomeRows(
    rows: List<StremioHomeRow>,
    disabledCatalogsCsv: String?,
    orderCsv: String?,
): List<StremioHomeRow> {
    val disabledKeys = disabledCatalogsCsv
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        .orEmpty()
    val visibleRows = rows.filterNot { it.rowKey in disabledKeys }
    val orderedKeys = orderCsv
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        .orEmpty()
    if (orderedKeys.isEmpty()) return visibleRows

    val visibleByKey = visibleRows.associateBy { it.rowKey }
    val orderedKeySet = orderedKeys.toSet()
    return orderedKeys.mapNotNull(visibleByKey::get) +
        visibleRows.filterNot { it.rowKey in orderedKeySet }
}

class MoviesViewModel(
    private val sl: ServiceLocator,
    private val pluginRepo: PluginRepository,
    private val stremioRepo: StremioRepository,
    private val appContext: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(MoviesState())
    val state: StateFlow<MoviesState> = _state.asStateFlow()
    private val hiddenWatchProgressKeys = MutableStateFlow<Set<String>>(emptySet())

    private var searchJob: Job? = null
    private var moviePageJob: Job? = null
    private var tvPageJob: Job? = null
    private var searchRequestId = 0L

    // Separate hero-banner caches so TMDB and Stremio items can update independently
    @Volatile private var tmdbHeroItems: List<HeroBannerItem> = emptyList()
    @Volatile private var stremioHeroItems: List<HeroBannerItem> = emptyList()

    private data class TmdbSearchCacheEntry(
        val movies: List<TmdbMovie> = emptyList(),
        val tv: List<TmdbMovie> = emptyList(),
        val moviePagination: TmdbSearchPagination = TmdbSearchPagination(),
        val tvPagination: TmdbSearchPagination = TmdbSearchPagination(),
        val moviesReady: Boolean = false,
        val tvReady: Boolean = false,
    )

    /** In-memory cache of all fetched TMDB pages. Cleared when this VM is cleared. */
    private val tmdbCache = HashMap<String, TmdbSearchCacheEntry>()
    private val requestedStremioTmdbIds = mutableSetOf<String>()
    private val requestedCsHomeTmdbMovies = mutableSetOf<String>()
    private val requestedTitleLogoKeys = mutableSetOf<String>()
    private var discoverJob: Job? = null
    private var genreArtworkJob: Job? = null
    private var activeDiscoverConfig: HomeCollectionConfig? = null
    private var lastSuccessfulDiscoverConfig: HomeCollectionConfig? = null
    private var lastSuccessfulDiscoverAt: Long? = null
    private val homeCollectionRowsCache = mutableMapOf<String, CollectionRow>()
    private val homeCollectionCacheJson = Json { ignoreUnknownKeys = true }
    private val homeCollectionCacheReady = CompletableDeferred<Unit>()
    private val primaryHomeContentReady = CompletableDeferred<Unit>()
    private val homeCollectionFetchSemaphore = Semaphore(4)
    private val titleLogoSemaphore = Semaphore(3)
    private var csHomeRowsJob: Job? = null
    private var stremioHomeRowsJob: Job? = null
    private var stremioAdultDiscoveryJob: Job? = null
    private var allDiscoveredAdultCatalogs: List<StremioHomeRow> = emptyList()
    private var activeStremioAddonIdentity: Set<Pair<String, String>>? = null
    private var lastStremioDisabledCatalogKeys: Set<String>? = null

    private fun homeCollectionCachePreferences() =
        appContext.getSharedPreferences("tmdb_home_collection_cache_v1", Context.MODE_PRIVATE)

    private fun genreArtworkPreferences() =
        appContext.getSharedPreferences("tmdb_home_genre_artwork_v1", Context.MODE_PRIVATE)

    init {
        viewModelScope.launch {
            try {
                val cachedRows = withContext(Dispatchers.IO) {
                    loadPersistedHomeCollectionRows()
                }
                homeCollectionRowsCache.putAll(cachedRows)
            } finally {
                homeCollectionCacheReady.complete(Unit)
            }
        }
        viewModelScope.launch {
            sl.settings.movieSearchHistory.collect { history ->
                _state.update { it.copy(searchHistory = history) }
            }
        }
        viewModelScope.launch {
            pluginRepo.installed.collect { list ->
                _state.update { it.copy(installedPlugins = list) }
            }
        }
        viewModelScope.launch {
            combine(
                stremioRepo.addons,
                sl.settings.stremioDisabledCatalogsCsv,
            ) { addons, disabledCsv ->
                addons to disabledCsv.orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
            }.distinctUntilChanged().collect { (list, disabledKeys) ->
                val addonIdentity = list.map { it.id to it.manifestUrl }.toSet()
                val addonsChanged = activeStremioAddonIdentity != addonIdentity
                val previousDisabledKeys = lastStremioDisabledCatalogKeys
                val newlyEnabledCatalog = previousDisabledKeys
                    ?.minus(disabledKeys)
                    ?.isNotEmpty() == true
                lastStremioDisabledCatalogKeys = disabledKeys
                _state.update {
                    it.copy(
                        installedStremioAddons = list,
                        stremioAdultCatalogs = if (addonsChanged) emptyList()
                        else it.stremioAdultCatalogs,
                    )
                }
                if (addonsChanged || newlyEnabledCatalog || previousDisabledKeys == null) {
                    refreshStremioRows(list, disabledKeys)
                } else {
                    applyStremioFilter()
                }
            }
        }
        viewModelScope.launch {
            combine(stremioRepo.addons, sl.settings.nsfwEnabled) { addons, adultEnabled ->
                addons to adultEnabled
            }.collect { (addons, adultEnabled) ->
                refreshStremioAdultCatalogs(addons, adultEnabled)
            }
        }
        viewModelScope.launch {
            combine(
                sl.settings.homeCollectionsCsv,
                sl.settings.hideUnreleasedContent,
            ) { csv, hideUnreleased ->
                homeCollectionConfig(csv, hideUnreleased)
            }
                .distinctUntilChanged()
                .collect { config ->
                    startDiscoverLoad(config)
                }
        }
        viewModelScope.launch {
            combine(
                sl.settings.nuvioUserId,
                sl.profiles.activeProfile,
            ) { userId, profile ->
                userId to (profile?.id ?: "default")
            }
                .distinctUntilChanged()
                .collectLatest { (userId, profileId) ->
                    hiddenWatchProgressKeys.update { emptySet() }
                    _state.update { it.copy(continueWatching = emptyList()) }
                    val db = LibraryDb.getForProfile(
                        context = appContext,
                        profileId = profileId,
                        userId = userId,
                    )
                    combine(
                        db.watchProgress().continueWatching(),
                        hiddenWatchProgressKeys,
                    ) { rows, hidden ->
                        latestContinueWatchingByTitle(
                            rows.filterNot { watchProgressIdentity(it) in hidden },
                        )
                    }.collect { rows ->
                        _state.update { it.copy(continueWatching = rows) }
                    }
                }
        }
        viewModelScope.launch {
            combine(
                sl.settings.nuvioUserId,
                sl.profiles.activeProfile,
            ) { userId, profile ->
                userId to (profile?.id ?: "default")
            }
                .distinctUntilChanged()
                .collectLatest { (userId, profileId) ->
                    _state.update { it.copy(watchlist = emptyList()) }
                    LibraryDb.getForProfile(
                        context = appContext,
                        profileId = profileId,
                        userId = userId,
                    ).watchlist().all().collect { rows ->
                        _state.update { it.copy(watchlist = rows) }
                    }
                }
        }
        viewModelScope.launch {
            combine(sl.settings.csHomeSections, pluginRepo.installed) { _, _ -> Unit }
                .collectLatest { loadCsPluginRows() }
        }
        viewModelScope.launch {
            sl.settings.stremioCatalogOrderCsv.collectLatest { applyStremioFilter() }
        }
        viewModelScope.launch {
            sl.settings.showHeroSection.collectLatest { v ->
                _state.update { it.copy(showHeroSection = v) }
            }
        }
        viewModelScope.launch {
            sl.settings.hideCatalogUnderline.collectLatest { v ->
                _state.update { it.copy(hideCatalogUnderline = v) }
            }
        }
        viewModelScope.launch {
            sl.settings.hideUnreleasedContent.collectLatest { v ->
                _state.update { it.copy(hideUnreleasedContent = v) }
            }
        }
        viewModelScope.launch {
            try {
                combine(
                    LibraryDb.get(appContext).userCollections().pinned(),
                    LibraryDb.get(appContext).collectionFolders().all(),
                ) { pinned, allFolders ->
                    val byCollection = allFolders.groupBy { it.collectionId }
                    pinned.map { col ->
                        PinnedCollectionRow(col.id, col.name, byCollection[col.id] ?: emptyList(), col.viewMode)
                    }
                }.collectLatest { rows ->
                    _state.update { it.copy(pinnedCollections = rows) }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun loadCsPluginRows() {
        csHomeRowsJob?.cancel()
        csHomeRowsJob = viewModelScope.launch {
            try {
                val pinned = sl.settings.csHomeSections.first()
                if (pinned.isEmpty()) {
                    _state.update { it.copy(csPluginRows = emptyList()) }
                    return@launch
                }
                // CloudStream plugin home pages can be CPU- and network-heavy. Prioritize
                // primary Movies shelves before asking installed plugins for rows.
                waitForPrimaryHomeStartup()
                val currentPinned = sl.settings.csHomeSections.first()
                if (currentPinned.isEmpty()) {
                    _state.update { it.copy(csPluginRows = emptyList()) }
                    return@launch
                }
                val installed = pluginRepo.installed.first()
                val rows = currentPinned.mapNotNull { pin ->
                    val plugin = installed.firstOrNull { it.internalName == pin.pluginInternalName }
                        ?: return@mapNotNull null
                    val items = runCatching {
                        PluginRuntime.homePage(appContext, plugin.filePath, pin.sectionName, 1)
                    }.getOrDefault(emptyList())
                    if (items.isEmpty()) null
                    else CsPluginRow(
                        pluginInternalName = pin.pluginInternalName,
                        pluginDisplayName = pin.pluginDisplayName,
                        sectionName = pin.sectionName,
                        items = items,
                    )
                }
                _state.update { it.copy(csPluginRows = rows) }
            } catch (_: Throwable) {}
        }
    }

    private suspend fun waitForPrimaryHomeStartup() {
        withTimeoutOrNull(SECONDARY_HOME_STARTUP_WAIT_MS) {
            primaryHomeContentReady.await()
        }
        delay(SECONDARY_HOME_QUIET_PERIOD_MS)
    }

    fun loadDiscover() {
        viewModelScope.launch {
            val config = combine(
                sl.settings.homeCollectionsCsv,
                sl.settings.hideUnreleasedContent,
            ) { csv, hideUnreleased ->
                homeCollectionConfig(csv, hideUnreleased)
            }.first()
            startDiscoverLoad(config, force = true)
        }
    }

    fun refreshDiscoverIfStale() {
        viewModelScope.launch {
            val config = combine(
                sl.settings.homeCollectionsCsv,
                sl.settings.hideUnreleasedContent,
            ) { csv, hideUnreleased ->
                homeCollectionConfig(csv, hideUnreleased)
            }.first()
            startDiscoverLoad(config)
        }
    }

    private fun homeCollectionConfig(csv: String?, hideUnreleased: Boolean): HomeCollectionConfig {
        val ids = csv?.takeIf { it.isNotBlank() }
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: HomeCollections.ALL.filter { it.defaultEnabled }.map { it.id }
        return HomeCollectionConfig(ids.distinct(), hideUnreleased)
    }

    private fun startDiscoverLoad(config: HomeCollectionConfig, force: Boolean = false) {
        if (discoverJob?.isActive == true) {
            if (!force && activeDiscoverConfig == config) return
            discoverJob?.cancel()
        }

        activeDiscoverConfig = config
        discoverJob = viewModelScope.launch {
            val loadStartedAt = SystemClock.elapsedRealtime()
            // Keep an already browseable home visible while refreshing its rows.
            _state.update { state ->
                state.copy(
                    loading = shouldShowMoviesHomeLoading(
                        state.collections.any { it.items.isNotEmpty() },
                    ),
                    error = null,
                )
            }
            try {
                homeCollectionCacheReady.await()
                val collections: List<HomeCollection> =
                    config.ids.mapNotNull { HomeCollections.byId(it) }
                if (collections.isEmpty()) {
                    primaryHomeContentReady.complete(Unit)
                    applyCollectionRows(emptyList(), loading = false)
                    _state.update { it.copy(error = null) }
                    return@launch
                }
                val today = java.time.LocalDate.now().toString()
                val rowsById = config.ids
                    .mapNotNull { id ->
                        homeCollectionRowsCache[id]
                            ?.let { row -> displayCollectionRow(row, config, today) }
                            ?.let { row -> id to row }
                    }
                    .toMap()
                    .toMutableMap()
                val cachedRows = config.ids.mapNotNull(rowsById::get)
                val failures = mutableListOf<String>()
                val publishMutex = Mutex()
                var firstFreshShelfLogged = false

                applyCollectionRows(
                    cachedRows,
                    loading = shouldShowMoviesHomeLoading(
                        cachedRows.any { it.items.isNotEmpty() },
                    ),
                )
                if (cachedRows.any { it.items.isNotEmpty() }) {
                    Log.d(
                        HOME_PERFORMANCE_TAG,
                        "Cached home content visible after ${SystemClock.elapsedRealtime() - loadStartedAt}ms",
                    )
                } else {
                    Log.d(HOME_PERFORMANCE_TAG, "No cached home rows; fetching ${collections.size} shelves")
                }
                refreshGenreArtwork()

                val staleCollectionIds = if (force) {
                    collections.mapTo(linkedSetOf()) { it.id }
                } else {
                    staleHomeCollectionIds(config, collections)
                }
                if (!force && staleCollectionIds.isEmpty()) {
                    applyCollectionRows(config.ids.mapNotNull(rowsById::get), loading = false)
                    primaryHomeContentReady.complete(Unit)
                    _state.update { it.copy(error = null) }
                    Log.d(
                        HOME_PERFORMANCE_TAG,
                        "All cached home shelves are fresh; no TMDB refresh needed",
                    )
                    return@launch
                }

                val collectionsToFetch = collections.filter { it.id in staleCollectionIds }
                coroutineScope {
                    collectionsToFetch.map { def ->
                        async {
                            val shelfStartedAt = SystemClock.elapsedRealtime()
                            val result = try {
                                val fetchedItems = withTimeoutOrNull(HOME_COLLECTION_TIMEOUT_MS) {
                                    homeCollectionFetchSemaphore.withPermit {
                                        def.fetch(sl.tmdb, sl.tmdbApiKey)
                                    }
                                }
                                if (fetchedItems == null) {
                                    HomeCollectionFetchResult(
                                        row = null,
                                        failure = "${def.title}: request timed out",
                                    )
                                } else {
                                    HomeCollectionFetchResult(
                                        row = fetchedItems.takeIf { it.isNotEmpty() }
                                            ?.let { CollectionRow(def.id, def.title, def.emoji, it) },
                                        failure = null,
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val reason = when (e) {
                                    is HttpException -> "HTTP ${e.code()}"
                                    is IOException -> "network request failed"
                                    else -> "request failed"
                                }
                                HomeCollectionFetchResult(
                                    row = null,
                                    failure = "${def.title}: $reason",
                                )
                            }

                            Log.d(
                                HOME_PERFORMANCE_TAG,
                                "Shelf ${def.id} finished in " +
                                    "${SystemClock.elapsedRealtime() - shelfStartedAt}ms; " +
                                    "items=${result.row?.items?.size ?: 0}; " +
                                    "failure=${result.failure ?: "none"}",
                            )
                            publishMutex.withLock {
                                result.row?.let { rawRow ->
                                    homeCollectionRowsCache[def.id] = rawRow
                                    val visibleRow = displayCollectionRow(rawRow, config, today)
                                    if (visibleRow == null) {
                                        rowsById.remove(def.id)
                                    } else {
                                        rowsById[def.id] = visibleRow
                                    }
                                }
                                result.failure?.let(failures::add)
                                val visibleRows = config.ids.mapNotNull(rowsById::get)
                                if (
                                    !firstFreshShelfLogged &&
                                    result.row != null &&
                                    rowsById.containsKey(result.row.id)
                                ) {
                                    firstFreshShelfLogged = true
                                    Log.d(
                                        HOME_PERFORMANCE_TAG,
                                        "First fresh shelf visible after ${SystemClock.elapsedRealtime() - loadStartedAt}ms",
                                    )
                                }
                                applyCollectionRows(
                                    visibleRows,
                                    loading = shouldShowMoviesHomeLoading(
                                        visibleRows.any { it.items.isNotEmpty() },
                                    ),
                                )
                            }
                            // Publish the first usable shelf before serializing its cache.
                            // Cache writes still finish before the full refresh is marked done.
                            result.row?.let { persistHomeCollectionRow(it) }
                            result
                        }
                    }.awaitAll()
                }
                primaryHomeContentReady.complete(Unit)

                if (!isActive) return@launch
                val rows = config.ids.mapNotNull(rowsById::get)
                applyCollectionRows(rows, loading = false)
                Log.d(
                    HOME_PERFORMANCE_TAG,
                    "Home refresh finished after ${SystemClock.elapsedRealtime() - loadStartedAt}ms; " +
                        "${rows.count { it.items.isNotEmpty() }}/${collections.size} shelves visible; " +
                        "refreshed=${collectionsToFetch.size}",
                )
                val loadError = when {
                    failures.isNotEmpty() -> failures.joinToString("; ").take(240)
                    rows.isEmpty() && collections.isNotEmpty() ->
                        "TMDB returned no movies for the selected Home collections."
                    else -> null
                }
                _state.update { it.copy(error = loadError) }
                if (failures.isEmpty() && collections.all { it.id in homeCollectionRowsCache }) {
                    lastSuccessfulDiscoverConfig = config
                    lastSuccessfulDiscoverAt = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.IO) {
                        homeCollectionCachePreferences().edit()
                            .putLong(homeCollectionFreshnessKey(config), System.currentTimeMillis())
                            .apply()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launch
                primaryHomeContentReady.complete(Unit)
                _state.update { it.copy(error = "Failed to load: ${e.message}", loading = false) }
            }
        }
    }

    private fun displayCollectionRow(
        row: CollectionRow,
        config: HomeCollectionConfig,
        today: String,
    ): CollectionRow? {
        val visibleItems = if (config.hideUnreleased) {
            row.items.filter { movie ->
                val releaseDate = movie.releaseDate ?: movie.firstAirDate
                !releaseDate.isNullOrBlank() && releaseDate <= today
            }
        } else {
            row.items
        }
        return visibleItems.takeIf { it.isNotEmpty() }?.let { row.copy(items = it) }
    }

    private suspend fun staleHomeCollectionIds(
        config: HomeCollectionConfig,
        collections: List<HomeCollection>,
    ): Set<String> {
        if (lastSuccessfulDiscoverConfig == config) {
            val lastSuccessAt = lastSuccessfulDiscoverAt
            if (
                lastSuccessAt != null &&
                SystemClock.elapsedRealtime() - lastSuccessAt < HOME_COLLECTION_STALE_AFTER_MS
            ) {
                return emptySet()
            }
        }

        val collectionIds = collections.map { it.id }
        val cachedCollectionIds = homeCollectionRowsCache.keys.toSet()
        val now = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            val preferences = homeCollectionCachePreferences()
            val legacySavedAt = preferences.getLong(homeCollectionFreshnessKey(config), 0L)
            val savedAtById = collectionIds.associateWith { id ->
                preferences.getLong(homeCollectionRowFreshnessKey(id), 0L)
                    .takeIf { it > 0L }
                    ?: legacySavedAt
            }
            staleHomeCollectionIds(
                collectionIds = collectionIds,
                cachedCollectionIds = cachedCollectionIds,
                savedAtByCollectionId = savedAtById,
                legacySavedAtMillis = legacySavedAt,
                nowMillis = now,
                staleAfterMillis = HOME_COLLECTION_STALE_AFTER_MS,
            )
        }
    }

    private fun homeCollectionFreshnessKey(config: HomeCollectionConfig): String =
        "freshness:${config.ids.sorted().joinToString(",")}"

    private fun homeCollectionRowFreshnessKey(collectionId: String): String =
        "collection_freshness:$collectionId"

    private fun refreshGenreArtwork() {
        if (genreArtworkJob?.isActive == true) return

        genreArtworkJob = viewModelScope.launch {
            val (cachedArtwork, savedAt) = withContext(Dispatchers.IO) {
                val preferences = genreArtworkPreferences()
                val artwork = preferences.getString("items", null)?.let { serialized ->
                    runCatching {
                        homeCollectionCacheJson.decodeFromString<Map<String, String>>(serialized)
                    }.getOrNull()
                }.orEmpty()
                artwork to preferences.getLong("saved_at", 0L)
            }
            if (cachedArtwork.isNotEmpty()) {
                _state.update { it.copy(genreArtwork = cachedArtwork) }
            }
            if (
                cachedArtwork.keys.containsAll(TV_HOME_GENRE_COLLECTION_IDS) &&
                System.currentTimeMillis() - savedAt in 0..GENRE_ARTWORK_STALE_AFTER_MS
            ) {
                return@launch
            }

            // Artwork is decorative. Do not compete with the first Movies shelves
            // for network, CPU, or storage work during a cold launch.
            waitForPrimaryHomeStartup()
            val fetchedArtwork = coroutineScope {
                TV_HOME_GENRE_COLLECTION_IDS.map { collectionId ->
                    async {
                        val collection = HomeCollections.byId(collectionId)
                            ?: return@async collectionId to null
                        val items = runCatching {
                            withTimeoutOrNull(HOME_COLLECTION_TIMEOUT_MS) {
                                homeCollectionFetchSemaphore.withPermit {
                                    collection.fetch(sl.tmdb, sl.tmdbApiKey)
                                }
                            }.orEmpty()
                        }.getOrNull().orEmpty()
                        val imageUrl = items.firstNotNullOfOrNull { movie ->
                            movie.backdropUrl?.takeIf { it.isNotBlank() }
                                ?: movie.posterUrl?.takeIf { it.isNotBlank() }
                        }
                        collectionId to imageUrl
                    }
                }.awaitAll().mapNotNull { (id, url) -> url?.let { id to it } }.toMap()
            }
            if (fetchedArtwork.isEmpty()) return@launch

            val mergedArtwork = cachedArtwork + fetchedArtwork
            _state.update { it.copy(genreArtwork = mergedArtwork) }
            withContext(Dispatchers.IO) {
                genreArtworkPreferences().edit()
                    .putString("items", homeCollectionCacheJson.encodeToString(mergedArtwork))
                    .putLong("saved_at", System.currentTimeMillis())
                    .apply()
            }
        }
    }

    private fun loadPersistedHomeCollectionRows(): Map<String, CollectionRow> =
        HomeCollections.ALL.mapNotNull { collection ->
            val serialized = homeCollectionCachePreferences().getString(
                "collection:${collection.id}",
                null,
            ) ?: return@mapNotNull null
            val items = runCatching {
                homeCollectionCacheJson.decodeFromString<List<TmdbMovie>>(serialized)
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            collection.id to CollectionRow(
                id = collection.id,
                title = collection.title,
                emoji = collection.emoji,
                items = items,
            )
        }.toMap()

    private suspend fun persistHomeCollectionRow(row: CollectionRow) {
        withContext(Dispatchers.IO) {
            val serialized = homeCollectionCacheJson.encodeToString(row.items)
            homeCollectionCachePreferences().edit()
                .putString("collection:${row.id}", serialized)
                .putLong(homeCollectionRowFreshnessKey(row.id), System.currentTimeMillis())
                .apply()
        }
    }

    private fun applyCollectionRows(rows: List<CollectionRow>, loading: Boolean) {
        if (rows.any { it.items.isNotEmpty() }) {
            primaryHomeContentReady.complete(Unit)
        }
        val byId = rows.associateBy { it.id }
        tmdbHeroItems = rows
            .flatMap { row -> row.items.take(2) }
            .distinctBy { it.id }
            .filter { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() }
            .take(8)
            .map { m ->
                HeroBannerItem(
                    imageUrl  = m.backdropUrl ?: m.posterUrl ?: "",
                    title     = m.displayTitle,
                    year      = (m.releaseDate ?: m.firstAirDate)?.substringBefore('-') ?: "",
                    rating    = if (m.voteAverage > 0) String.format("%.1f ★", m.voteAverage) else "",
                    overview  = m.overview.orEmpty(),
                    tmdbId    = m.id,
                    mediaType = if (m.name != null && m.title == null) "tv" else "movie",
                )
            }
        _state.update {
            it.copy(
                trending    = byId["trending"]?.items    ?: emptyList(),
                popular     = byId["popular"]?.items     ?: emptyList(),
                topRated    = byId["top_rated"]?.items   ?: emptyList(),
                nowPlaying  = byId["now_playing"]?.items ?: emptyList(),
                collections = rows,
                heroBanner  = (tmdbHeroItems + stremioHeroItems).take(12),
                loading     = loading,
            )
        }
    }

    private var allFetchedStremioRows: List<StremioHomeRow> = emptyList()

    private fun refreshStremioRows(
        addons: List<InstalledStremioAddon>,
        disabledCatalogKeys: Set<String>,
    ) {
        stremioHomeRowsJob?.cancel()
        val addonIdentity = addons.map { it.id to it.manifestUrl }.toSet()
        if (activeStremioAddonIdentity != addonIdentity) {
            activeStremioAddonIdentity = addonIdentity
            allFetchedStremioRows = emptyList()
        }
        if (addons.isEmpty()) {
            allFetchedStremioRows = emptyList()
            _state.update {
                it.copy(
                    stremioRows = emptyList(),
                )
            }
            return
        }
        loadStremioHomeRowsAfterPrimary(addons, disabledCatalogKeys)
    }

    private fun refreshStremioAdultCatalogs(
        addons: List<InstalledStremioAddon>,
        adultContentEnabled: Boolean,
    ) {
        stremioAdultDiscoveryJob?.cancel()
        allDiscoveredAdultCatalogs = emptyList()
        if (!adultContentEnabled || addons.isEmpty()) {
            _state.update { it.copy(stremioAdultCatalogs = emptyList()) }
            return
        }

        // Discover clearly adult add-ons first so the 18+ shortcut can appear early.
        // Scanning unrelated manifests is supplementary work; wait until primary
        // Movies shelves are available before starting those requests.
        stremioAdultDiscoveryJob = viewModelScope.launch {
            try {
                // Check clearly adult-named add-ons first, so those references are
                // available even when a large collection of unrelated manifests is slow.
                val (adultNamed, otherAddons) = addons.partition {
                    isAdultStremioLabel(it.name)
                }
                if (adultNamed.isNotEmpty()) {
                    allDiscoveredAdultCatalogs =
                        stremioRepo.fetchAdultCatalogReferences(adultNamed)
                    applyStremioFilter()
                }
                if (otherAddons.isNotEmpty()) {
                    waitForPrimaryHomeStartup()
                    val additionalRefs =
                        stremioRepo.fetchAdultCatalogReferences(otherAddons)
                    allDiscoveredAdultCatalogs =
                        (allDiscoveredAdultCatalogs + additionalRefs).distinctBy { it.rowKey }
                    applyStremioFilter()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Adult-catalog discovery is supplementary; the regular home must
                // continue loading even if a provider manifest is unavailable.
            }
        }
    }

    private fun loadStremioHomeRowsAfterPrimary(
        addons: List<InstalledStremioAddon>,
        disabledCatalogKeys: Set<String>,
    ) {
        stremioHomeRowsJob?.cancel()
        if (addons.isEmpty()) return
        stremioHomeRowsJob = viewModelScope.launch {
            // Add-on catalogs are supplementary home content. Keep their network work
            // out of the first Movies startup window.
            waitForPrimaryHomeStartup()
            val rows = addons.map { addon ->
                async {
                    normalizeStremioRows(
                        runCatching {
                            stremioRepo.fetchAllHomeCatalogs(addon, disabledCatalogKeys)
                        }.getOrNull(),
                    )
                }
            }.awaitAll().flatten()
            allFetchedStremioRows = rows
            applyStremioFilter()
        }
    }

    private fun applyStremioFilter() {
        viewModelScope.launch {
            val csv = sl.settings.stremioDisabledCatalogsCsv.first()
            val fetchedRows = normalizeStremioRows(allFetchedStremioRows)
            val orderCsv = sl.settings.stremioCatalogOrderCsv.first()
            val sorted = orderStremioHomeRows(fetchedRows, csv, orderCsv)
            val adultCatalogs = (
                orderStremioHomeRows(allDiscoveredAdultCatalogs, csv, orderCsv) +
                    adultStremioHomeRows(sorted)
                ).distinctBy { it.rowKey }
            stremioHeroItems = sorted
                .flatMap { row -> row.items.take(2) }
                .distinctBy { it.id }
                .filter { !it.background.isNullOrBlank() || !it.poster.isNullOrBlank() }
                .take(6)
                .map { meta ->
                    HeroBannerItem(
                        imageUrl    = meta.background ?: meta.poster ?: "",
                        title       = meta.name,
                        year        = meta.releaseInfo ?: "",
                        rating      = meta.imdbRating?.let { "$it ★" } ?: "",
                        overview    = meta.description.orEmpty(),
                        stremioMeta = meta,
                        mediaType   = if (meta.type == "series") "tv" else "movie",
                    )
                }
            _state.update {
                it.copy(
                    stremioRows = sorted,
                    stremioAdultCatalogs = adultCatalogs,
                    heroBanner = (tmdbHeroItems + stremioHeroItems).take(12),
                )
            }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    private suspend fun activeLibraryDb(): LibraryDb {
        val userId = sl.settings.nuvioUserId.first().trim()
        val profileId = sl.profiles.currentActiveId()
            ?.takeIf { it.isNotBlank() }
            ?: "default"
        return LibraryDb.getForProfile(
            context = appContext,
            profileId = profileId,
            userId = userId,
        )
    }

    fun toggleWatchlist(tmdbId: Long, title: String, posterUrl: String?, mediaType: String) {
        viewModelScope.launch {
            val userId = sl.settings.nuvioUserId.first().trim()
            val profileId = sl.profiles.currentActiveId()
                ?.takeIf { it.isNotBlank() }
                ?: "default"
            val libraryDb = LibraryDb.getForProfile(
                context = appContext,
                profileId = profileId,
                userId = userId,
            )
            val db = libraryDb.watchlist()
            val alreadyIn = db.byId(tmdbId) != null
            if (alreadyIn) {
                val result = com.streamcloud.app.data.nuvio.NuvioAutoSync
                    .deleteLibraryItemAndSyncNow(
                        context = appContext,
                        db = libraryDb,
                        tmdbId = tmdbId,
                        mediaType = mediaType,
                        userId = userId,
                        localProfileId = profileId,
                    )
                if (result.isFailure && userId.isNotBlank()) {
                    _state.update {
                        it.copy(
                            notice = "Removed locally; Nuvio sync will retry when available.",
                        )
                    }
                }
            } else {
                db.add(WatchlistEntity(tmdbId = tmdbId, title = title, posterUrl = posterUrl, mediaType = mediaType))
                com.streamcloud.app.data.nuvio.NuvioAutoSync.request(appContext)
            }
        }
    }

    fun requestStremioTmdbId(meta: StremioMetaPreview) {
        val key = "stremio:${meta.type}:${meta.id}"
        if (!requestedStremioTmdbIds.add(key)) return

        viewModelScope.launch {
            val mediaType = if (
                meta.type.equals("series", ignoreCase = true) ||
                meta.type.equals("tv", ignoreCase = true)
            ) {
                "tv"
            } else {
                "movie"
            }
            val tmdbId = runCatching {
                resolveStremioTmdbId(meta, mediaType)
            }.getOrNull()
            if (tmdbId != null) {
                _state.update { it.copy(stremioTmdbIds = it.stremioTmdbIds + (key to tmdbId)) }
            }
        }
    }

    fun requestCsHomeTmdbMovie(key: String, item: SearchResponse) {
        if (key.isBlank()) return
        val mediaType = when {
            item.type.isEpisodeBased() -> "tv"
            item.type == TvType.Movie ||
                item.type == TvType.AnimeMovie ||
                item.type == TvType.Documentary -> "movie"
            else -> return
        }
        if (!requestedCsHomeTmdbMovies.add(key)) return

        viewModelScope.launch {
            val match = runCatching {
                val results = if (mediaType == "tv") {
                    sl.tmdb.searchTv(sl.tmdbApiKey, item.name).results
                } else {
                    sl.tmdb.search(sl.tmdbApiKey, item.name).results
                }
                val year = (item as? MovieSearchResponse)?.year
                    ?: (item as? TvSeriesSearchResponse)?.year
                bestCloudStreamHomeTmdbMatch(results, item.name, year)
            }.getOrNull()
            if (match != null) {
                _state.update {
                    it.copy(csHomeTmdbMovies = it.csHomeTmdbMovies + (key to match))
                }
            }
        }
    }

    fun requestTitleLogo(tmdbId: Long, mediaType: String) {
        val normalizedMediaType = if (mediaType.equals("tv", ignoreCase = true)) "tv" else "movie"
        val key = "$normalizedMediaType:$tmdbId"
        if (_state.value.tmdbTitleLogos.containsKey(key) || !requestedTitleLogoKeys.add(key)) return

        viewModelScope.launch {
            val logoUrl = runCatching {
                titleLogoSemaphore.withPermit {
                    sl.tmdb.images(normalizedMediaType, tmdbId, sl.tmdbApiKey)
                        .logos
                        .sortedWith(
                            compareBy<TmdbTitleLogo> {
                                when {
                                    it.language?.equals("en", ignoreCase = true) == true -> 0
                                    it.language == null -> 1
                                    else -> 2
                                }
                            }.thenByDescending { it.voteAverage }
                                .thenByDescending { it.width },
                        )
                        .firstNotNullOfOrNull { it.imageUrl }
                }
            }.getOrNull()

            if (logoUrl != null) {
                _state.update {
                    it.copy(tmdbTitleLogos = it.tmdbTitleLogos + (key to logoUrl))
                }
            }
        }
    }

    private suspend fun resolveStremioTmdbId(
        meta: StremioMetaPreview,
        mediaType: String,
    ): Long? {
        val directTmdbId = Regex("""^tmdb:(?:(?:movie|tv|series):)?(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(meta.id)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        if (directTmdbId != null) return directTmdbId

        if (meta.id.startsWith("tt", ignoreCase = true)) {
            val matches = sl.tmdb.find(meta.id, sl.tmdbApiKey, "imdb_id")
            return if (mediaType == "tv") {
                matches.tvResults.firstOrNull()?.id ?: matches.movieResults.firstOrNull()?.id
            } else {
                matches.movieResults.firstOrNull()?.id ?: matches.tvResults.firstOrNull()?.id
            }
        }

        return if (mediaType == "tv") {
            sl.tmdb.searchTv(sl.tmdbApiKey, meta.name).results.firstOrNull()?.id
        } else {
            sl.tmdb.search(sl.tmdbApiKey, meta.name).results.firstOrNull()?.id
        }
    }

    fun openStremioMeta(
        meta: com.streamcloud.app.data.stremio.StremioMetaPreview,
        callback: (tmdbId: Long?, fallbackTitle: String) -> Unit,
    ) {
        viewModelScope.launch {
            val key = sl.tmdbApiKey
            try {
                val resolved: Long? = when {
                    meta.id.startsWith("tt", ignoreCase = true) -> {
                        val r = sl.tmdb.find(meta.id, key, "imdb_id")
                        r.movieResults.firstOrNull()?.id ?: r.tvResults.firstOrNull()?.id
                    }
                    else -> {
                        sl.tmdb.search(key, meta.name).results.firstOrNull()?.id
                    }
                }
                if (resolved == null) {
                    _state.update {
                        it.copy(
                            notice = "Couldn't match \"${meta.name}\" to TMDB. " +
                                "The Stremio addon doesn't ship a known IMDB id.",
                        )
                    }
                }
                callback(resolved, meta.name)
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Resolve failed: ${e.message}") }
                callback(null, meta.name)
            }
        }
    }

    fun search(query: String, forceRefresh: Boolean = false) {
        searchJob?.cancel()
        moviePageJob?.cancel()
        tvPageJob?.cancel()
        val requestId = ++searchRequestId
        if (query.isBlank()) {
            _state.update {
                it.copy(
                    searchResults = emptyList(), tvSearchResults = emptyList(),
                    searchCorrection = null,
                    csSearchResults = emptyList(), stremioSearchResults = emptyList(),
                    moviesLoading = false, seriesLoading = false,
                    moviePagination = TmdbSearchPagination(),
                    tvPagination = TmdbSearchPagination(),
                    csLoading = false, stremioLoading = false,
                )
            }
            return
        }
        val rawQuery = query.trim()
        val correctedQuery = if (forceRefresh) {
            findClosestSearchTitle(rawQuery, knownSearchTitles())
        } else {
            null
        }
        val q = correctedQuery ?: rawQuery
        val cacheKey = q.lowercase()
        if (forceRefresh) tmdbCache.remove(cacheKey)
        // A submitted search must replace every live type-ahead section. In particular,
        // CloudStream results are appended as plugins finish, so leaving old items here would
        // produce duplicate cards after the keyboard Search action forces a fresh request.
        _state.update {
            it.copy(
                searchResults = emptyList(),
                tvSearchResults = emptyList(),
                searchCorrection = correctedQuery,
                csSearchResults = emptyList(),
                stremioSearchResults = emptyList(),
                moviesLoading = true,
                seriesLoading = true,
                moviePagination = TmdbSearchPagination().reset(q, requestId),
                tvPagination = TmdbSearchPagination().reset(q, requestId),
                csLoading = true,
                stremioLoading = true,
                error = null,
            )
        }
        searchJob = viewModelScope.launch {
            delay(120)
            if (requestId != searchRequestId) return@launch

            // Check cache first — serve instantly if available
            val cached = tmdbCache[cacheKey]
            if (cached != null) {
                if (requestId == searchRequestId) {
                    _state.update {
                        it.copy(
                            searchResults = cached.movies,
                            tvSearchResults = cached.tv,
                            moviesLoading = !cached.moviesReady,
                            seriesLoading = !cached.tvReady,
                            moviePagination = if (cached.moviesReady) {
                                cached.moviePagination.reuse(q, requestId)
                            } else TmdbSearchPagination().reset(q, requestId),
                            tvPagination = if (cached.tvReady) {
                                cached.tvPagination.reuse(q, requestId)
                            } else TmdbSearchPagination().reset(q, requestId),
                        )
                    }
                }
            }

            // ── TMDB movies (only if not cached) ──────────────────────────
            if (cached?.moviesReady != true) {
                launch {
                    val response = try {
                        sl.tmdb.search(sl.tmdbApiKey, q, 1)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (requestId == searchRequestId) {
                            _state.update {
                                it.copy(
                                    moviesLoading = false,
                                    moviePagination = it.moviePagination.fail(
                                        q, requestId, searchError(error),
                                    ),
                                )
                            }
                        }
                        return@launch
                    }
                    if (requestId != searchRequestId) return@launch
                    val pagination = _state.value.moviePagination.complete(
                        q, requestId, response.page, response.totalPages,
                    )
                    val movies = mergeTmdbSearchResults(emptyList(), response.results)
                    updateTmdbCache(cacheKey) {
                        it.copy(
                            movies = movies,
                            moviePagination = pagination,
                            moviesReady = true,
                        )
                    }
                    _state.update {
                        it.copy(
                            searchResults = movies,
                            moviesLoading = false,
                            moviePagination = pagination,
                        )
                    }
                }
            }
            // ── TMDB series ───────────────────────────────────────────
            if (cached?.tvReady != true) {
                launch {
                    val response = try {
                        sl.tmdb.searchTv(sl.tmdbApiKey, q, 1)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (requestId == searchRequestId) {
                            _state.update {
                                it.copy(
                                    seriesLoading = false,
                                    tvPagination = it.tvPagination.fail(
                                        q, requestId, searchError(error),
                                    ),
                                )
                            }
                        }
                        return@launch
                    }
                    if (requestId != searchRequestId) return@launch
                    val pagination = _state.value.tvPagination.complete(
                        q, requestId, response.page, response.totalPages,
                    )
                    val tv = mergeTmdbSearchResults(emptyList(), response.results)
                    updateTmdbCache(cacheKey) {
                        it.copy(tv = tv, tvPagination = pagination, tvReady = true)
                    }
                    _state.update {
                        it.copy(
                            tvSearchResults = tv,
                            seriesLoading = false,
                            tvPagination = pagination,
                        )
                    }
                }
            }

            // ── CloudStream (per-plugin, streams results as each plugin finishes) ──
            launch {
                val plugins = pluginRepo.installed.first()
                if (requestId != searchRequestId) return@launch
                if (plugins.isEmpty()) {
                    _state.update { it.copy(csLoading = false) }
                    return@launch
                }
                val results = coroutineScope {
                    plugins.map { plugin ->
                        async {
                            try {
                            PluginRuntime.search(appContext, plugin.filePath, q)
                                .map { CsSearchResult(plugin.name, plugin.internalName, plugin.filePath, it) }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
                if (requestId != searchRequestId) return@launch
                _state.update { it.copy(csSearchResults = results, csLoading = false) }
            }

            // ── Stremio ───────────────────────────────────────────────────
            launch {
                val addons = stremioRepo.addons.first()
                if (requestId != searchRequestId) return@launch
                if (addons.isEmpty()) {
                    _state.update { it.copy(stremioLoading = false) }
                    return@launch
                }
                val results = try {
                    stremioRepo.searchAllAddons(addons, q)
                        .map { (addon, meta) -> StremioSearchResult(addon.name, addon.id, meta) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
                if (requestId != searchRequestId) return@launch
                _state.update { it.copy(stremioSearchResults = results, stremioLoading = false) }
            }
        }
    }

    private fun knownSearchTitles(): List<String> = buildList {
        addAll(_state.value.searchHistory)
        addAll(_state.value.trending.map { it.displayTitle })
        addAll(_state.value.popular.map { it.displayTitle })
        addAll(_state.value.topRated.map { it.displayTitle })
        addAll(_state.value.nowPlaying.map { it.displayTitle })
        addAll(_state.value.heroBanner.map { it.title })
        addAll(_state.value.collections.flatMap { row -> row.items.map { it.displayTitle } })
        addAll(_state.value.watchlist.map { it.title })
    }.filter { it.isNotBlank() }.distinct()

    fun loadMoreMovies() {
        loadMoreTmdb(isTv = false)
    }

    fun loadMoreTv() {
        loadMoreTmdb(isTv = true)
    }

    private fun loadMoreTmdb(isTv: Boolean) {
        val snapshot = _state.value
        val pagination = if (isTv) snapshot.tvPagination else snapshot.moviePagination
        // An error requires an explicit retry tap; this prevents near-end observers from
        // repeatedly issuing the same failing request.
        if (!pagination.canLoad && pagination.error == null) return
        if (pagination.query.isBlank() || pagination.isLoading || pagination.endReached) return

        val q = pagination.query
        val requestId = pagination.generation
        if (requestId != searchRequestId) return
        val page = pagination.page + 1
        _state.update {
            if (isTv) it.copy(
                seriesLoading = page == 1,
                tvPagination = it.tvPagination.beginLoad(),
            ) else it.copy(
                moviesLoading = page == 1,
                moviePagination = it.moviePagination.beginLoad(),
            )
        }

        val job = viewModelScope.launch {
            val response = try {
                if (isTv) sl.tmdb.searchTv(sl.tmdbApiKey, q, page)
                else sl.tmdb.search(sl.tmdbApiKey, q, page)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestId == searchRequestId) {
                    _state.update {
                        if (isTv) it.copy(
                            seriesLoading = false,
                            tvPagination = it.tvPagination.fail(q, requestId, searchError(error)),
                        ) else it.copy(
                            moviesLoading = false,
                            moviePagination = it.moviePagination.fail(q, requestId, searchError(error)),
                        )
                    }
                }
                return@launch
            }
            if (requestId != searchRequestId) return@launch
            val cacheKey = q.lowercase()
            _state.update { current ->
                if (requestId != searchRequestId) return@update current
                if (isTv) {
                    val items = mergeTmdbSearchResults(current.tvSearchResults, response.results)
                    val next = current.tvPagination.complete(
                        q, requestId, response.page, response.totalPages,
                    )
                    updateTmdbCache(cacheKey) {
                        it.copy(tv = items, tvPagination = next, tvReady = true)
                    }
                    current.copy(tvSearchResults = items, seriesLoading = false, tvPagination = next)
                } else {
                    val items = mergeTmdbSearchResults(current.searchResults, response.results)
                    val next = current.moviePagination.complete(
                        q, requestId, response.page, response.totalPages,
                    )
                    updateTmdbCache(cacheKey) {
                        it.copy(movies = items, moviePagination = next, moviesReady = true)
                    }
                    current.copy(searchResults = items, moviesLoading = false, moviePagination = next)
                }
            }
        }
        if (isTv) tvPageJob = job else moviePageJob = job
    }

    @Synchronized
    private fun updateTmdbCache(
        key: String,
        transform: (TmdbSearchCacheEntry) -> TmdbSearchCacheEntry,
    ) {
        tmdbCache[key] = transform(tmdbCache[key] ?: TmdbSearchCacheEntry())
    }

    private fun searchError(error: Exception): String =
        error.message?.takeIf { it.isNotBlank() } ?: "Unable to load results"

    fun saveToHistory(query: String) {
        val q = query.trim()
        if (q.length >= 2) {
            viewModelScope.launch { runCatching { sl.settings.addMovieSearchHistory(q) } }
        }
    }

    fun removeFromSearchHistory(query: String) {
        viewModelScope.launch { runCatching { sl.settings.removeMovieSearchHistory(query) } }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { runCatching { sl.settings.clearMovieSearchHistory() } }
    }

    fun deleteWatchProgress(
        tmdbId: Long,
        mediaType: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ) {
        val identity = watchProgressIdentity(
            tmdbId,
            mediaType,
            seasonNumber,
            episodeNumber,
        )
        hiddenWatchProgressKeys.update { it + identity }
        _state.update {
            it.copy(
                continueWatching = it.continueWatching.filterNot {
                    watchProgressIdentity(it) == identity
                },
            )
        }
        viewModelScope.launch {
            val dao = activeLibraryDb().watchProgress()
            try {
                // Let NuvioAutoSync serialize row removal with its account-scoped
                // tombstone and any active account sync.
                val result = com.streamcloud.app.data.nuvio.NuvioAutoSync
                    .deleteWatchProgressAndSyncNow(
                        appContext,
                        tmdbId,
                        mediaType,
                        seasonNumber,
                        episodeNumber,
                    )
                if (result.isFailure) {
                    val hasAccessToken = sl.settings.nuvioAccessToken.first().isNotBlank()
                    val userId = sl.settings.nuvioUserId.first().trim()
                    val hasAccountId = userId.isNotBlank()
                    val profileNeedsLink = result.exceptionOrNull()?.message
                        ?.contains("not linked", ignoreCase = true) == true
                    val localProfileId = sl.profiles.currentActiveId()
                        ?.takeIf { it.isNotBlank() }
                        ?: sl.profiles.currentProfiles().firstOrNull()?.id
                            ?.takeIf { it.isNotBlank() }
                        ?: "default"
                    val deleteIntentIsPending = hasAccountId &&
                        com.streamcloud.app.data.nuvio.NuvioAutoSync
                            .pendingWatchProgressDeleteKeys(
                                appContext,
                                userId,
                                localProfileId,
                            )
                            .any {
                                it.matchesLocalProgress(
                                    tmdbId,
                                    mediaType,
                                    seasonNumber,
                                    episodeNumber,
                                )
                            }
                    when {
                        profileNeedsLink && hasAccountId -> {
                            _state.update {
                                it.copy(
                                    notice = "Removed locally. Link this StreamCloud profile to Nuvio to sync the deletion.",
                                )
                            }
                        }
                        hasAccessToken && hasAccountId && deleteIntentIsPending -> {
                            com.streamcloud.app.data.nuvio.NuvioAutoSync.request(appContext)
                            _state.update {
                                it.copy(
                                    notice = "Removed locally; Nuvio sync is queued and will retry when available.",
                                )
                            }
                        }
                        hasAccountId && deleteIntentIsPending -> {
                            _state.update {
                                it.copy(
                                    notice = "Removed locally. Reconnect to Nuvio to sync the deletion.",
                                )
                            }
                        }
                        hasAccountId -> {
                            _state.update {
                                it.copy(
                                    notice = "Nuvio could not save this deletion request. The item may return; please try again.",
                                )
                            }
                        }
                    }
                }
            } finally {
                withTimeoutOrNull(2_000L) {
                    dao.continueWatching().first { rows ->
                        rows.none { watchProgressIdentity(it) == identity }
                    }
                }
                hiddenWatchProgressKeys.update { it - identity }
            }
        }
    }

    fun resetWatchProgress(
        tmdbId: Long,
        mediaType: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ) {
        viewModelScope.launch {
            val dao = activeLibraryDb().watchProgress()
            val existing = dao.byKey(tmdbId, mediaType, seasonNumber, episodeNumber) ?: return@launch
            dao.upsert(existing.copy(positionMs = 0L, updatedAt = System.currentTimeMillis()))
            com.streamcloud.app.data.nuvio.NuvioAutoSync.request(appContext)
        }
    }

    fun markAsWatched(tmdbId: Long, title: String, posterUrl: String?, mediaType: String) {
        viewModelScope.launch {
            if (tmdbId <= 0L || (mediaType != "movie" && mediaType != "tv")) return@launch
            val db = activeLibraryDb()
            db.watchedMovies().mark(
                com.streamcloud.app.data.library.WatchedMovieEntity(
                    tmdbId = tmdbId,
                    title = title,
                    posterUrl = posterUrl,
                    mediaType = mediaType,
                )
            )
            db.watchProgress().getAllEntries()
                .filter { it.tmdbId == tmdbId }
                .forEach { entry ->
                    com.streamcloud.app.data.nuvio.NuvioAutoSync.recordWatchProgressDelete(
                        appContext,
                        entry.tmdbId,
                        entry.mediaType,
                        entry.seasonNumber,
                        entry.episodeNumber,
                    )
                }
            db.watchProgress().remove(tmdbId)
            com.streamcloud.app.data.nuvio.NuvioAutoSync.request(appContext)
        }
    }

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MoviesViewModel(
                    ServiceLocator.get(context),
                    PluginRepository(context.applicationContext),
                    ServiceLocator.get(context).stremio,
                    context.applicationContext,
                ) as T
            }
        }
    }
}
