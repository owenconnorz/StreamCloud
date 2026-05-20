package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.collections.HomeCollection
import com.streamcloud.app.data.collections.HomeCollections
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioHomeRow
import com.streamcloud.app.data.stremio.StremioRepository
import com.streamcloud.app.data.util.PageCache
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * Compatibility token still referenced by the player's source-picker code.
 * The Movies tab itself no longer renders a "Built-in" chip.
 */
const val SOURCE_BUILTIN = "builtin"

@Serializable
data class CollectionRow(
    val id: String,
    val title: String,
    val emoji: String,
    val items: List<TmdbMovie>,
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
    /** All CloudStream `.cs3` plugins — rendered as a chip strip → opens its own page. */
    val installedPlugins: List<InstalledPlugin> = emptyList(),
    /** All Stremio HTTP addons currently installed. Catalogs flatten into [stremioRows]. */
    val installedStremioAddons: List<InstalledStremioAddon> = emptyList(),
    /** Inline NuvioMobile-style addon rows, one per addon catalog (movie/series/etc). */
    val stremioRows: List<StremioHomeRow> = emptyList(),
    val watchlist: List<WatchlistEntity> = emptyList(),
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

    init {
        viewModelScope.launch {
            pluginRepo.installed.collect { list ->
                _state.update { it.copy(installedPlugins = list) }
            }
        }
        viewModelScope.launch {
            // Whenever the addon list changes, re-aggregate the home so freshly
            // installed Stremio addons surface immediately.
            stremioRepo.addons.collect { list ->
                _state.update { it.copy(installedStremioAddons = list) }
                refreshStremioRows(list)
            }
        }
        // Auto-reload home rows whenever the user toggles a collection in Settings.
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
    }

    fun loadDiscover() {
        viewModelScope.launch {
            // ── Step 1: serve cached data instantly (no spinner if cache hit) ──────
            val cachedJson = PageCache.getStale(appContext, PageCache.KEY_TMDB_COLLECTIONS)
            if (cachedJson != null) {
                runCatching {
                    val rows = com.streamcloud.app.data.network.Net.json
                        .decodeFromString(ListSerializer(CollectionRow.serializer()), cachedJson)
                    applyCollectionRows(rows, loading = false)
                }
            }

            // ── Step 2: skip network if cache is fresh ────────────────────────────
            val isFresh = PageCache.getFresh(appContext, PageCache.KEY_TMDB_COLLECTIONS, PageCache.TTL_TMDB_MS) != null
            if (isFresh && cachedJson != null) return@launch

            // Only show spinner when there is no cached content at all.
            if (cachedJson == null) _state.update { it.copy(loading = true, error = null) }

            // ── Step 3: fetch fresh data in background ────────────────────────────
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

                // ── Step 4: persist to cache ──────────────────────────────────────
                runCatching {
                    val json = com.streamcloud.app.data.network.Net.json
                        .encodeToString(ListSerializer(CollectionRow.serializer()), rows)
                    PageCache.put(appContext, PageCache.KEY_TMDB_COLLECTIONS, json)
                }

                applyCollectionRows(rows, loading = false)
                refreshStremioRows(_state.value.installedStremioAddons)
            } catch (e: Exception) {
                if (cachedJson != null) {
                    // Silent background failure — user already sees cached content.
                    _state.update { it.copy(loading = false) }
                } else {
                    _state.update { it.copy(error = "Failed to load: ${e.message}", loading = false) }
                }
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
                heroBanner  = (byId["trending"]?.items
                    ?: byId["now_playing"]?.items
                    ?: rows.firstOrNull()?.items
                    ?: emptyList()).take(7),
                loading = loading,
            )
        }
    }

    /**
     * Aggregate every installed Stremio addon's non-search catalogs into a flat
     * row list (NuvioMobile parity). Each addon × catalog becomes one row,
     * fanned out in parallel + capped at 18 items per row.
     *
     * Stale-while-revalidate: cached rows are shown immediately; fresh rows
     * replace them when the network fetch completes (or silently on error).
     */
    private fun refreshStremioRows(addons: List<InstalledStremioAddon>) {
        if (addons.isEmpty()) {
            _state.update { it.copy(stremioRows = emptyList()) }
            PageCache.clear(appContext, PageCache.KEY_STREMIO_ROWS)
            return
        }
        viewModelScope.launch {
            // ── Step 1: show cached Stremio rows immediately ──────────────────
            val cachedJson = PageCache.getStale(appContext, PageCache.KEY_STREMIO_ROWS)
            if (cachedJson != null) {
                runCatching {
                    val rows = com.streamcloud.app.data.network.Net.json
                        .decodeFromString(ListSerializer(StremioHomeRow.serializer()), cachedJson)
                    _state.update { it.copy(stremioRows = rows) }
                }
            }

            // ── Step 2: skip network if cache is fresh ────────────────────────
            val isFresh = PageCache.getFresh(appContext, PageCache.KEY_STREMIO_ROWS, PageCache.TTL_STREMIO_MS) != null
            if (isFresh && cachedJson != null) return@launch

            // ── Step 3: fetch fresh catalog rows in background ────────────────
            val rows = addons.map { addon ->
                async { runCatching { stremioRepo.fetchAllHomeCatalogs(addon) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()

            // ── Step 4: persist and update UI ─────────────────────────────────
            runCatching {
                val json = com.streamcloud.app.data.network.Net.json
                    .encodeToString(ListSerializer(StremioHomeRow.serializer()), rows)
                PageCache.put(appContext, PageCache.KEY_STREMIO_ROWS, json)
            }
            _state.update { it.copy(stremioRows = rows) }
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

    /**
     * Resolve a Stremio meta to a TMDB id (or surface a friendly notice when
     * we can't). Stremio addons key items by IMDB id (`tt…`); we hand that
     * straight to TMDB's `/find` endpoint with `external_source=imdb_id`.
     *
     * If the Stremio addon happens to provide a non-IMDB id (some custom
     * addons do), we fall back to a TMDB title search and pick the closest
     * year match.
     */
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
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            try {
                val res = sl.tmdb.search(sl.tmdbApiKey, query).results
                _state.update { it.copy(searchResults = res, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Search failed: ${e.message}") }
            }
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
