package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.livetv.LiveTvChannel
import com.streamcloud.app.data.livetv.LiveTvRepository
import com.streamcloud.app.data.livetv.LiveTvSource
import com.streamcloud.app.data.livetv.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class LiveTvViewModel(private val context: Context) : ViewModel() {

    private val repo = LiveTvRepository(context)

    data class State(
        val sources: List<LiveTvSource>  = emptyList(),
        val channels: List<LiveTvChannel> = emptyList(),
        val loading: Boolean              = false,
        val error: String?                = null,
        val selectedGroup: String?        = null,
        val searchQuery: String           = "",
        val selectedChannel: LiveTvChannel? = null,
    ) {
        val groups: List<String>
            get() = channels.map { it.group }.distinct().filter { it.isNotBlank() }.sorted()

        val filteredChannels: List<LiveTvChannel>
            get() {
                var list = channels
                if (selectedGroup != null) list = list.filter { it.group == selectedGroup }
                if (searchQuery.isNotBlank())
                    list = list.filter { it.name.contains(searchQuery, ignoreCase = true) }
                return list
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { loadSources() }

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

    fun selectGroup(g: String?)         = _state.update { it.copy(selectedGroup = g) }
    fun setSearch(q: String)            = _state.update { it.copy(searchQuery = q) }
    fun selectChannel(ch: LiveTvChannel?) = _state.update { it.copy(selectedChannel = ch) }

    fun newSourceId() = UUID.randomUUID().toString()

    companion object {
        fun factory(ctx: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T = LiveTvViewModel(ctx) as T
        }
    }
}
