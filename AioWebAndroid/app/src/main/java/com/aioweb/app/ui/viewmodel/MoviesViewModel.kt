package com.aioweb.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aioweb.app.data.ServiceLocator
import com.aioweb.app.data.api.TmdbMovie
import com.aioweb.app.data.collections.HomeCollection
import com.aioweb.app.data.collections.HomeCollections
import com.aioweb.app.data.library.LibraryDb
import com.aioweb.app.data.library.WatchProgressEntity
import com.aioweb.app.data.plugins.InstalledPlugin
import com.aioweb.app.data.plugins.PluginRepository
import com.aioweb.app.data.stremio.InstalledStremioAddon
import com.aioweb.app.data.stremio.StremioHomeRow
import com.aioweb.app.data.stremio.StremioRepository
import kotlinx.coroutines.Job
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

/**
 * Compatibility token still referenced by the player's source-picker code.
 * The Movies tab itself no longer renders a "Built-in" chip.
 */
const val SOURCE_BUILTIN = "builtin"

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
        // Continue-watching row is fed by the player whenever the user pauses/exits.
        viewModelScope.launch {
            LibraryDb.get(appContext).watchProgress().continueWatching().collect { rows ->
                _state.update { it.copy(continueWatching = rows) }
            }
        }
    }

    fun loadDiscover() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val key = sl.tmdbApiKey

                // Resolve which collections to render based on user settings — fall back to defaults.
                val csv = sl.settings.homeCollectionsCsv.first()
                val ids = csv?.takeIf { it.isNotBlank() }?.split(',')
                    ?: HomeCollections.ALL.filter { it.defaultEnabled }.map { it.id }
                val collections: List<HomeCollection> = ids.mapNotNull { HomeCollections.byId(it) }

                // Fan out — fetch all enabled rows in parallel.
                val rows = collections.map { def ->
                    async {
                        val items = runCatching { def.fetch(sl.tmdb, key) }.getOrDefault(emptyList())
                        if (items.isEmpty()) null
                        else CollectionRow(def.id, def.title, def.emoji, items)
                    }
                }.awaitAll().filterNotNull()

                // Compatibility shim: keep populated trending/popular/topRated/nowPlaying for any
                // older UI paths still pulling from them directly.
                val byId = rows.associateBy { it.id }
                _state.update {
                    it.copy(
                        trending = byId["trending"]?.items ?: emptyList(),
                        popular = byId["popular"]?.items ?: emptyList(),
                        topRated = byId["top_rated"]?.items ?: emptyList(),
                        nowPlaying = byId["now_playing"]?.items ?: emptyList(),
                        collections = rows,
                        heroBanner = (byId["trending"]?.items
                            ?: byId["now_playing"]?.items
                            ?: rows.firstOrNull()?.items
                            ?: emptyList()).take(7),
                        loading = false,
                    )
                }
                // Kick off Stremio aggregation in the background — TMDB rows show
                // first; addon rows trickle in below as each addon resolves.
                refreshStremioRows(_state.value.installedStremioAddons)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to load: ${e.message}", loading = false) }
            }
        }
    }

    /**
     * Aggregate every installed Stremio addon's non-search catalogs into a flat
     * row list (NuvioMobile parity). Each addon × catalog becomes one row,
     * fanned out in parallel + capped at 18 items per row.
     */
    private fun refreshStremioRows(addons: List<InstalledStremioAddon>) {
        if (addons.isEmpty()) {
            _state.update { it.copy(stremioRows = emptyList()) }
            return
        }
        viewModelScope.launch {
            val rows = addons.map { addon ->
                async { runCatching { stremioRepo.fetchAllHomeCatalogs(addon) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
            _state.update { it.copy(stremioRows = rows) }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
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
