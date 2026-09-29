package com.streamcloud.app.ui.viewmodel

import android.content.Context
import android.os.SystemClock
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
import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioHomeRow
import com.streamcloud.app.data.stremio.StremioMetaPreview
import com.streamcloud.app.data.stremio.StremioRepository
import com.lagradost.cloudstream3.SearchResponse
import com.streamcloud.app.data.plugins.PinnedCsSection
import com.streamcloud.app.data.plugins.PluginRuntime
import retrofit2.HttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

const val SOURCE_BUILTIN = "builtin"
private const val HOME_COLLECTION_TIMEOUT_MS = 30_000L
private const val HOME_COLLECTION_STALE_AFTER_MS = 15 * 60 * 1000L

data class HeroBannerItem(
    val imageUrl: String,
    val title: String,
    val year: String = "",
    val rating: String = "",
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
    val stremioTmdbIds: Map<String, Long> = emptyMap(),
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

class MoviesViewModel(
    private val sl: ServiceLocator,
    private val pluginRepo: PluginRepository,
    private val stremioRepo: StremioRepository,
    private val appContext: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(MoviesState())
    val state: StateFlow<MoviesState> = _state.asStateFlow()

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
    private val requestedTitleLogoKeys = mutableSetOf<String>()
    private var discoverJob: Job? = null
    private var activeDiscoverConfig: HomeCollectionConfig? = null
    private var lastSuccessfulDiscoverConfig: HomeCollectionConfig? = null
    private var lastSuccessfulDiscoverAt: Long? = null
    private val homeCollectionRowsCache = mutableMapOf<String, CollectionRow>()

    init {
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
            stremioRepo.addons.collect { list ->
                _state.update { it.copy(installedStremioAddons = list) }
                refreshStremioRows(list)
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
                .collect { config -> startDiscoverLoad(config) }
        }
        viewModelScope.launch {
            LibraryDb.get(appContext).watchProgress().continueWatching().collect { rows ->
                _state.update { it.copy(continueWatching = rows) }
            }
        }
        viewModelScope.launch {
            LibraryDb.get(appContext).watchlist().all().collect { rows ->
                _state.update { it.copy(watchlist = rows) }
            }
        }
        viewModelScope.launch {
            combine(sl.settings.csHomeSections, pluginRepo.installed) { _, _ -> Unit }
                .collectLatest { loadCsPluginRows() }
        }
        viewModelScope.launch {
            sl.settings.stremioDisabledCatalogsCsv.collectLatest { applyStremioFilter() }
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
        viewModelScope.launch {
            try {
                val pinned = sl.settings.csHomeSections.first()
                if (pinned.isEmpty()) {
                    _state.update { it.copy(csPluginRows = emptyList()) }
                    return@launch
                }
                val installed = pluginRepo.installed.first()
                val rows = pinned.mapNotNull { pin ->
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
        } else if (!force && isDiscoverConfigFresh(config)) {
            return
        }

        activeDiscoverConfig = config
        discoverJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val collections: List<HomeCollection> =
                    config.ids.mapNotNull { HomeCollections.byId(it) }
                val today = java.time.LocalDate.now().toString()
                val rowsById = config.ids
                    .mapNotNull { id ->
                        homeCollectionRowsCache[id]
                            ?.let { row -> displayCollectionRow(row, config, today) }
                            ?.let { row -> id to row }
                    }
                    .toMap()
                    .toMutableMap()
                val failures = mutableListOf<String>()
                val publishMutex = Mutex()

                applyCollectionRows(
                    config.ids.mapNotNull(rowsById::get),
                    loading = true,
                )

                coroutineScope {
                    collections.map { def ->
                        async {
                            val result = try {
                                val fetchedItems = withTimeoutOrNull(HOME_COLLECTION_TIMEOUT_MS) {
                                    def.fetch(sl.tmdb, sl.tmdbApiKey)
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
                                applyCollectionRows(
                                    config.ids.mapNotNull(rowsById::get),
                                    loading = true,
                                )
                            }
                            result
                        }
                    }.awaitAll()
                }

                if (!isActive) return@launch
                val rows = config.ids.mapNotNull(rowsById::get)
                applyCollectionRows(rows, loading = false)
                val loadError = when {
                    failures.isNotEmpty() -> failures.joinToString("; ").take(240)
                    rows.isEmpty() && collections.isNotEmpty() ->
                        "TMDB returned no movies for the selected Home collections."
                    else -> null
                }
                _state.update { it.copy(error = loadError) }
                if (failures.isEmpty()) {
                    lastSuccessfulDiscoverConfig = config
                    lastSuccessfulDiscoverAt = SystemClock.elapsedRealtime()
                }
                refreshStremioRows(_state.value.installedStremioAddons)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launch
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

    private fun isDiscoverConfigFresh(config: HomeCollectionConfig): Boolean {
        if (lastSuccessfulDiscoverConfig != config) return false
        val lastSuccessAt = lastSuccessfulDiscoverAt ?: return false
        return SystemClock.elapsedRealtime() - lastSuccessAt < HOME_COLLECTION_STALE_AFTER_MS
    }

    private fun applyCollectionRows(rows: List<CollectionRow>, loading: Boolean) {
        val byId = rows.associateBy { it.id }
        tmdbHeroItems = rows
            .flatMap { row -> row.items.take(2) }
            .distinctBy { it.id }
            .filter { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() }
            .shuffled()
            .take(8)
            .map { m ->
                HeroBannerItem(
                    imageUrl  = m.backdropUrl ?: m.posterUrl ?: "",
                    title     = m.displayTitle,
                    year      = (m.releaseDate ?: m.firstAirDate)?.substringBefore('-') ?: "",
                    rating    = if (m.voteAverage > 0) String.format("%.1f ★", m.voteAverage) else "",
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

    private fun refreshStremioRows(addons: List<InstalledStremioAddon>) {
        if (addons.isEmpty()) {
            allFetchedStremioRows = emptyList()
            _state.update { it.copy(stremioRows = emptyList()) }
            return
        }
        viewModelScope.launch {
            val rows = addons.map { addon ->
                async {
                    normalizeStremioRows(
                        runCatching { stremioRepo.fetchAllHomeCatalogs(addon) }.getOrNull(),
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
            val disabled = csv?.takeIf { it.isNotBlank() }?.split(",")?.toSet() ?: emptySet()
            val fetchedRows = normalizeStremioRows(allFetchedStremioRows)
            val filtered = if (disabled.isEmpty()) fetchedRows
                           else fetchedRows.filter { it.rowKey !in disabled }
            // Apply saved catalog order
            val orderCsv    = sl.settings.stremioCatalogOrderCsv.first()
            val orderedKeys = orderCsv?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() } ?: emptyList()
            val sorted = if (orderedKeys.isEmpty()) filtered else {
                val byKey = filtered.associateBy { it.rowKey }
                orderedKeys.mapNotNull { byKey[it] } + filtered.filter { it.rowKey !in orderedKeys.toSet() }
            }
            stremioHeroItems = sorted
                .flatMap { row -> row.items.take(2) }
                .distinctBy { it.id }
                .filter { !it.background.isNullOrBlank() || !it.poster.isNullOrBlank() }
                .shuffled()
                .take(6)
                .map { meta ->
                    HeroBannerItem(
                        imageUrl    = meta.background ?: meta.poster ?: "",
                        title       = meta.name,
                        year        = meta.releaseInfo ?: "",
                        rating      = meta.imdbRating?.let { "$it ★" } ?: "",
                        stremioMeta = meta,
                        mediaType   = if (meta.type == "series") "tv" else "movie",
                    )
                }
            _state.update { it.copy(stremioRows = sorted, heroBanner = (tmdbHeroItems + stremioHeroItems).take(12)) }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun toggleWatchlist(tmdbId: Long, title: String, posterUrl: String?, mediaType: String) {
        viewModelScope.launch {
            val db = LibraryDb.get(appContext).watchlist()
            val alreadyIn = _state.value.watchlist.any { it.tmdbId == tmdbId }
            if (alreadyIn) {
                db.remove(tmdbId)
                com.streamcloud.app.data.nuvio.NuvioAutoSync.requestLibraryDelete(
                    appContext,
                    tmdbId,
                    mediaType,
                )
            } else {
                db.add(WatchlistEntity(tmdbId = tmdbId, title = title, posterUrl = posterUrl, mediaType = mediaType))
            }
            com.streamcloud.app.data.nuvio.NuvioAutoSync.request(appContext)
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

    fun requestTitleLogo(tmdbId: Long, mediaType: String) {
        val normalizedMediaType = if (mediaType.equals("tv", ignoreCase = true)) "tv" else "movie"
        val key = "$normalizedMediaType:$tmdbId"
        if (_state.value.tmdbTitleLogos.containsKey(key) || !requestedTitleLogoKeys.add(key)) return

        viewModelScope.launch {
            val logoUrl = runCatching {
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
        viewModelScope.launch {
            com.streamcloud.app.data.nuvio.NuvioAutoSync.recordWatchProgressDelete(
                appContext,
                tmdbId,
                mediaType,
                seasonNumber,
                episodeNumber,
            )
            LibraryDb.get(appContext).watchProgress().removeKey(
                tmdbId,
                mediaType,
                seasonNumber,
                episodeNumber,
            )
            com.streamcloud.app.data.nuvio.NuvioAutoSync.request(appContext)
        }
    }

    fun resetWatchProgress(
        tmdbId: Long,
        mediaType: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ) {
        viewModelScope.launch {
            val dao = LibraryDb.get(appContext).watchProgress()
            val existing = dao.byKey(tmdbId, mediaType, seasonNumber, episodeNumber) ?: return@launch
            dao.upsert(existing.copy(positionMs = 0L, updatedAt = System.currentTimeMillis()))
            com.streamcloud.app.data.nuvio.NuvioAutoSync.request(appContext)
        }
    }

    fun markAsWatched(tmdbId: Long, title: String, posterUrl: String?, mediaType: String) {
        viewModelScope.launch {
            if (tmdbId <= 0L || (mediaType != "movie" && mediaType != "tv")) return@launch
            val db = LibraryDb.get(appContext)
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
