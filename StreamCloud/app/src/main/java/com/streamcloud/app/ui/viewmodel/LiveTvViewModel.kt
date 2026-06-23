package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.livetv.LiveTvChannel
import com.streamcloud.app.data.livetv.LiveTvRepository
import com.streamcloud.app.data.livetv.LiveTvSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class LiveTvTab { CHANNELS, FAVORITES, RECENT }

class LiveTvViewModel(private val context: Context) : ViewModel() {

    private val repo = LiveTvRepository(context)

    data class State(
        val sources: List<LiveTvSource>       = emptyList(),
        val channels: List<LiveTvChannel>     = emptyList(),
        val loading: Boolean                  = false,
        val probing: Boolean                  = false,
        val probedCount: Int                  = 0,
        val error: String?                    = null,
        val selectedGroup: String?            = null,
        val searchQuery: String               = "",
        val selectedChannel: LiveTvChannel?   = null,
        val englishOnly: Boolean              = false,
        val hideDeadStreams: Boolean           = false,
        val favorites: Set<String>            = emptySet(),
        val recentlyWatched: List<LiveTvChannel> = emptyList(),
        val activeTab: LiveTvTab              = LiveTvTab.CHANNELS,
    ) {
        val groups: List<String>
            get() = channels.map { it.group }.distinct().filter { it.isNotBlank() }.sorted()

        val filteredChannels: List<LiveTvChannel>
            get() {
                var list = channels
                if (hideDeadStreams)        list = list.filter { it.isAlive != false }
                if (englishOnly)           list = list.filter { isLikelyEnglish(it) }
                if (selectedGroup != null) list = list.filter { it.group == selectedGroup }
                if (searchQuery.isNotBlank())
                    list = list.filter { it.name.contains(searchQuery, ignoreCase = true) }
                return list
            }

        val displayChannels: List<LiveTvChannel>
            get() = when (activeTab) {
                LiveTvTab.CHANNELS  -> filteredChannels
                LiveTvTab.FAVORITES -> filteredChannels.filter { it.id in favorites }
                LiveTvTab.RECENT    -> recentlyWatched
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        _state.update { it.copy(
            englishOnly     = repo.loadEnglishOnly(),
            hideDeadStreams  = repo.loadHideDeadStreams(),
            favorites        = repo.loadFavorites(),
            recentlyWatched  = repo.loadRecents(),
        )}
        loadSources()
    }

    private fun loadSources() {
        val sources = repo.loadSources()
        _state.update { it.copy(sources = sources) }
        if (sources.isNotEmpty()) refreshChannels(sources)
    }

    fun refreshChannels(sources: List<LiveTvSource> = _state.value.sources) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val all = mutableListOf<LiveTvChannel>()
            var lastErr: String? = null
            sources.forEach { src ->
                try { all += repo.fetchChannels(src) }
                catch (e: Exception) { lastErr = "Could not load \"${src.name}\": ${e.message}" }
            }
            _state.update { it.copy(loading = false, channels = all, error = lastErr) }
            if (_state.value.hideDeadStreams) startProbing()
        }
    }

    private fun startProbing() {
        viewModelScope.launch {
            val channels = _state.value.channels
            if (channels.isEmpty()) return@launch
            _state.update { it.copy(probing = true, probedCount = 0) }
            val ioPool = Dispatchers.IO.limitedParallelism(8)
            channels.chunked(8).forEach { batch ->
                batch.map { ch ->
                    async(ioPool) { ch.id to repo.probeStream(ch.url) }
                }.awaitAll().forEach { (id, alive) ->
                    _state.update { s ->
                        s.copy(
                            probedCount = s.probedCount + 1,
                            channels = s.channels.map { if (it.id == id) it.copy(isAlive = alive) else it }
                        )
                    }
                }
            }
            _state.update { it.copy(probing = false) }
        }
    }

    fun addSource(src: LiveTvSource) {
        val next = _state.value.sources + src
        repo.saveSources(next)
        _state.update { it.copy(sources = next) }
        refreshChannels(next)
    }

    fun removeSource(src: LiveTvSource) {
        val next = _state.value.sources.filter { it.id != src.id }
        repo.saveSources(next)
        _state.update { it.copy(
            sources  = next,
            channels = _state.value.channels.filter { it.sourceId != src.id },
        )}
    }

    fun toggleEnglishOnly() {
        val next = !_state.value.englishOnly
        repo.saveEnglishOnly(next)
        _state.update { it.copy(englishOnly = next) }
    }

    fun toggleHideDeadStreams() {
        val next = !_state.value.hideDeadStreams
        repo.saveHideDeadStreams(next)
        _state.update { it.copy(hideDeadStreams = next) }
        if (next && _state.value.channels.any { it.isAlive == null }) startProbing()
    }

    fun toggleFavorite(channelId: String) {
        val next = if (channelId in _state.value.favorites)
            _state.value.favorites - channelId
        else
            _state.value.favorites + channelId
        repo.saveFavorites(next)
        _state.update { it.copy(favorites = next) }
    }

    fun addToRecent(channel: LiveTvChannel) {
        val existing = _state.value.recentlyWatched.filter { it.id != channel.id }
        val next = listOf(channel) + existing
        repo.saveRecents(next)
        _state.update { it.copy(recentlyWatched = next) }
    }

    fun setActiveTab(tab: LiveTvTab)          = _state.update { it.copy(activeTab = tab, searchQuery = "") }
    fun selectGroup(g: String?)               = _state.update { it.copy(selectedGroup = g) }
    fun setSearch(q: String)                  = _state.update { it.copy(searchQuery = q) }
    fun selectChannel(ch: LiveTvChannel?)     = _state.update { it.copy(selectedChannel = ch) }
    fun newSourceId()                         = UUID.randomUUID().toString()

    companion object {
        fun isLikelyEnglish(ch: LiveTvChannel): Boolean {
            if (ch.language.isNotBlank()) {
                val lang = ch.language.trim().lowercase()
                return lang.startsWith("en") || lang.contains("english")
            }
            val combined = "${ch.name} ${ch.group}"
            val total    = combined.length.coerceAtLeast(1)
            val nonLatin = combined.count { it.code > 127 }
            return nonLatin.toFloat() / total < 0.15f
        }

        fun factory(ctx: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T = LiveTvViewModel(ctx) as T
        }
    }
}
