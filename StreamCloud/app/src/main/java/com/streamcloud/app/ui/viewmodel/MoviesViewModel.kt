package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbMovie
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val SOURCE_BUILTIN = "builtin"

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
)

data class MoviesState(
    val trending: List<TmdbMovie> = emptyList(),
    val popular: List<TmdbMovie> = emptyList(),
    val topRated: List<TmdbMovie> = emptyList(),
    val nowPlaying: List<TmdbMovie> = emptyList(),
    val collections: List<CollectionRow> = emptyList(),
    val heroBanner: List<TmdbMovie> = emptyList(),
    val continueWatching: List<WatchProgressEntity> = emptyList(),
    val searchResults: List<TmdbMovie> = emptyList(),
    val tvSearchResults: List<TmdbMovie> = emptyList(),
    val csSearchResults: List<CsSearchResult> = emptyList(),
    val stremioSearchResults: List<StremioSearchResult> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val moviesLoading: Boolean = false,
    val seriesLoading: Boolean = false,
    val csLoading: Boolean = false,
    val stremioLoading: Boolean = false,
    val installedPlugins: List<InstalledPlugin> = emptyList(),
    val installedStremioAddons: List<InstalledStremioAddon> = emptyList(),
    val stremioRows: List<StremioHomeRow> = emptyList(),
    val watchlist: List<WatchlistEntity> = emptyList(),
    val csPluginRows: List<CsPluginRow> = emptyList(),
    val pinnedCollections: List<PinnedCollectionRow> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class MoviesViewModel(
    private val sl: ServiceLocator,
    private val pluginRepo: PluginRepository,
    private val stremioRepo: StremioRepository,
    private val appContext: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(MoviesState())
    val state: StateFlow<MoviesState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** In-memory cache: query → (movies, tvShows). Cleared when VM is cleared. */
    private val tmdbCache = HashMap<String, Pair<List<TmdbMovie>, List<TmdbMovie>>>()

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
            sl.settings.homeCollectionsCsv.collectLatest { loadDiscover() }
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
            try {
                LibraryDb.get(appContext).userCollections().pinned().collectLatest { pinned ->
                    try {
                        val rows = pinned.map { col ->
                            val folders = LibraryDb.get(appContext).collectionFolders().forCollectionOnce(col.id)
                            PinnedCollectionRow(col.id, col.name, folders)
                        }
                        _state.update { it.copy(pinnedCollections = rows) }
                    } catch (_: Throwable) {}
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
            _state.update { it.copy(loading = true, error = null) }
            try {
                val key = sl.tmdbApiKey
                val csv = sl.settings.homeCollectionsCsv.first()
                val ids = csv?.takeIf { it.isNotBlank() }?.split(',')
                    ?: HomeCollections.ALL.filter { it.defaultEnabled }.map { it.id }
                val collections: List<HomeCollection> = ids.mapNotNull { HomeCollections.byId(it) }

                val rows = collections.map { def ->
                    async {
                        val items = runCatching { def.fetch(sl.tmdb, key) }.getOrDefault(emptyList())
                        if (items.isEmpty()) null
                        else CollectionRow(def.id, def.title, def.emoji, items)
                    }
                }.awaitAll().filterNotNull()

                applyCollectionRows(rows, loading = false)
                refreshStremioRows(_state.value.installedStremioAddons)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to load: ${e.message}", loading = false) }
            }
        }
    }

    private fun applyCollectionRows(rows: List<CollectionRow>, loading: Boolean) {
        val byId = rows.associateBy { it.id }
        _state.update {
            it.copy(
                trending   = byId["trending"]?.items   ?: emptyList(),
                popular    = byId["popular"]?.items    ?: emptyList(),
                topRated   = byId["top_rated"]?.items  ?: emptyList(),
                nowPlaying = byId["now_playing"]?.items ?: emptyList(),
                collections = rows,
                heroBanner  = rows
                    .flatMap { row -> row.items.take(2) }
                    .distinctBy { it.id }
                    .filter { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() }
                    .take(12),
                loading = loading,
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
                async { runCatching { stremioRepo.fetchAllHomeCatalogs(addon) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
            allFetchedStremioRows = rows
            applyStremioFilter()
        }
    }

    private fun applyStremioFilter() {
        viewModelScope.launch {
            val csv = sl.settings.stremioDisabledCatalogsCsv.first()
            val disabled = csv?.takeIf { it.isNotBlank() }?.split(",")?.toSet() ?: emptySet()
            val filtered = if (disabled.isEmpty()) allFetchedStremioRows
                           else allFetchedStremioRows.filter { it.rowKey !in disabled }
            _state.update { it.copy(stremioRows = filtered) }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun toggleWatchlist(tmdbId: Long, title: String, posterUrl: String?, mediaType: String) {
        viewModelScope.launch {
            val db = LibraryDb.get(appContext).watchlist()
            val alreadyIn = _state.value.watchlist.any { it.tmdbId == tmdbId }
            if (alreadyIn) db.remove(tmdbId)
            else db.add(WatchlistEntity(tmdbId = tmdbId, title = title, posterUrl = posterUrl, mediaType = mediaType))
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

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update {
                it.copy(
                    searchResults = emptyList(), tvSearchResults = emptyList(),
                    csSearchResults = emptyList(), stremioSearchResults = emptyList(),
                    moviesLoading = false, seriesLoading = false,
                    csLoading = false, stremioLoading = false,
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(120)
            val q = query.trim()

            // Check cache first — serve instantly if available
            val cached = tmdbCache[q.lowercase()]
            if (cached != null) {
                _state.update {
                    it.copy(
                        searchResults = cached.first,
                        tvSearchResults = cached.second,
                        moviesLoading = false,
                        seriesLoading = false,
                    )
                }
            } else {
                _state.update { it.copy(moviesLoading = true, seriesLoading = true) }
            }
            _state.update { it.copy(csLoading = true, stremioLoading = true, error = null) }

            // ── TMDB movies (only if not cached) ──────────────────────────
            if (cached == null) {
                launch {
                    val movies = runCatching {
                        sl.tmdb.search(sl.tmdbApiKey, q).results
                    }.getOrDefault(emptyList())
                    tmdbCache[q.lowercase()] = Pair(movies, tmdbCache[q.lowercase()]?.second ?: emptyList())
                    _state.update { it.copy(searchResults = movies, moviesLoading = false) }
                }
                // ── TMDB series ───────────────────────────────────────────
                launch {
                    val tv = runCatching {
                        sl.tmdb.searchTv(sl.tmdbApiKey, q).results
                    }.getOrDefault(emptyList())
                    val existing = tmdbCache[q.lowercase()]?.first ?: emptyList()
                    tmdbCache[q.lowercase()] = Pair(existing, tv)
                    _state.update { it.copy(tvSearchResults = tv, seriesLoading = false) }
                }
            }

            // ── CloudStream (per-plugin, streams results as each plugin finishes) ──
            launch {
                val plugins = pluginRepo.installed.first()
                if (plugins.isEmpty()) {
                    _state.update { it.copy(csLoading = false) }
                    return@launch
                }
                plugins.forEach { plugin ->
                    launch {
                        val results = runCatching {
                            PluginRuntime.search(appContext, plugin.filePath, q)
                                .map { CsSearchResult(plugin.name, plugin.internalName, plugin.filePath, it) }
                        }.getOrDefault(emptyList())
                        if (results.isNotEmpty()) {
                            _state.update { s ->
                                s.copy(csSearchResults = s.csSearchResults + results)
                            }
                        }
                    }
                }
                // All plugin launches are child coroutines; mark done after they finish
                // (we don't await here — each plugin updates state independently)
                _state.update { it.copy(csLoading = false) }
            }

            // ── Stremio ───────────────────────────────────────────────────
            launch {
                val addons = stremioRepo.addons.first()
                if (addons.isEmpty()) {
                    _state.update { it.copy(stremioLoading = false) }
                    return@launch
                }
                val results = runCatching {
                    stremioRepo.searchAllAddons(addons, q)
                        .map { (addon, meta) -> StremioSearchResult(addon.name, addon.id, meta) }
                }.getOrDefault(emptyList())
                _state.update { it.copy(stremioSearchResults = results, stremioLoading = false) }
            }
        }
    }

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

    fun deleteWatchProgress(tmdbId: Long) {
        viewModelScope.launch {
            LibraryDb.get(appContext).watchProgress().remove(tmdbId)
        }
    }

    fun resetWatchProgress(tmdbId: Long) {
        viewModelScope.launch {
            val dao = LibraryDb.get(appContext).watchProgress()
            val existing = dao.byId(tmdbId) ?: return@launch
            dao.upsert(existing.copy(positionMs = 0L, updatedAt = System.currentTimeMillis()))
        }
    }

    fun markAsWatched(tmdbId: Long, title: String, posterUrl: String?, mediaType: String) {
        viewModelScope.launch {
            val dao = LibraryDb.get(appContext).watchProgress()
            val existing = dao.byId(tmdbId)
            val duration = existing?.durationMs?.takeIf { it > 0 } ?: 7_200_000L
            dao.upsert(
                WatchProgressEntity(
                    tmdbId = tmdbId,
                    title = title,
                    posterUrl = posterUrl,
                    mediaType = mediaType,
                    positionMs = (duration * 0.97).toLong(),
                    durationMs = duration,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MoviesViewModel(
                    ServiceLocator.get(context),
                    PluginRepository(context.applicationContext),
                    StremioRepository(context.applicationContext),
                    context.applicationContext,
                ) as T
            }
        }
    }
}
