package com.aioweb.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aioweb.app.data.api.AdultItem
import com.aioweb.app.data.api.AdultSource
import com.aioweb.app.data.api.EpornerApi
import com.aioweb.app.data.api.RedditAdultRepository
import com.aioweb.app.data.network.Net
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdultState(
    val items: List<AdultItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val resolvingId: String? = null,
    val source: AdultSource = AdultSource.Eporner,
    /** Reddit-only: the subreddit currently being browsed. */
    val subreddit: String = "nsfw",
    /** Reddit pagination cursor. */
    val nextAfter: String? = null,
    val loadingMore: Boolean = false,
)

class AdultViewModel : ViewModel() {
    private val _state = MutableStateFlow(AdultState())
    val state: StateFlow<AdultState> = _state.asStateFlow()

    private val eporner: EpornerApi =
        Net.retrofit("https://www.eporner.com/").create(EpornerApi::class.java)

    private var searchJob: Job? = null

    init { search("popular") }

    /** Switch between Eporner and Reddit. Resets the grid + reloads with sane defaults. */
    fun setSource(source: AdultSource) {
        if (_state.value.source == source) return
        _state.update { it.copy(source = source, items = emptyList(), error = null, nextAfter = null) }
        when (source) {
            AdultSource.Eporner -> search("popular")
            AdultSource.Reddit -> loadReddit(_state.value.subreddit)
        }
    }

    /** Drives the search bar across both sources. */
    fun search(query: String) {
        when (_state.value.source) {
            AdultSource.Eporner -> searchEporner(query)
            AdultSource.Reddit -> {
                val sub = query.ifBlank { "nsfw" }.removePrefix("r/").trim()
                _state.update { it.copy(subreddit = sub) }
                loadReddit(sub)
            }
        }
    }

    /** Pick a preset / custom subreddit chip. */
    fun setSubreddit(sub: String) {
        val clean = sub.removePrefix("r/").trim().ifBlank { "nsfw" }
        _state.update { it.copy(subreddit = clean) }
        loadReddit(clean)
    }

    /** Endless scroll for Reddit (Eporner search is single-shot for now). */
    fun loadMore() {
        val s = _state.value
        if (s.source != AdultSource.Reddit || s.loadingMore || s.nextAfter == null) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val (more, after) = RedditAdultRepository.fetch(s.subreddit, after = s.nextAfter)
                _state.update {
                    it.copy(
                        items = it.items + more,
                        nextAfter = after,
                        loadingMore = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, error = "Reddit page failed: ${e.message}") }
            }
        }
    }

    private fun searchEporner(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(loading = true, error = null) }
            try {
                val q = if (query.isBlank()) "popular" else query
                val r = eporner.search(query = q, perPage = 30)
                val items = r.videos.map { v ->
                    AdultItem(
                        id = v.id,
                        title = v.title,
                        thumbnail = v.defaultThumb?.src,
                        previewImage = v.defaultThumb?.src,
                        durationLabel = v.lengthMin,
                        streamUrl = null,
                        source = AdultSource.Eporner,
                        epornerId = v.id,
                        embedUrl = v.embed,
                    )
                }
                _state.update { it.copy(items = items, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Failed: ${e.message}") }
            }
        }
    }

    private fun loadReddit(subreddit: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val (items, after) = RedditAdultRepository.fetch(subreddit)
                _state.update { it.copy(items = items, nextAfter = after, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Reddit failed: ${e.message}") }
            }
        }
    }

    /**
     * Resolves a direct MP4 URL for an Eporner video. Returns the embed URL if no
     * direct MP4 is exposed — the player falls back to WebView for embed pages.
     *
     * Reddit short-circuit: ids prefixed with `direct://` already carry the
     * resolved stream URL inline (set by [AdultScreen.routeId]).
     */
    suspend fun resolveStreamUrl(videoId: String, fallbackEmbed: String): String {
        if (videoId.startsWith("direct://")) {
            val direct = videoId.removePrefix("direct://")
            return direct.ifBlank { fallbackEmbed }
        }
        _state.update { it.copy(resolvingId = videoId, error = null) }
        return try {
            val resp = eporner.details(id = videoId)
            resp.videos.firstOrNull()?.bestMp4() ?: fallbackEmbed
        } catch (e: Exception) {
            _state.update { it.copy(error = "Stream resolve failed: ${e.message}") }
            fallbackEmbed
        } finally {
            _state.update { it.copy(resolvingId = null) }
        }
    }

    companion object {
        fun factory(@Suppress("UNUSED_PARAMETER") context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AdultViewModel() as T
            }
        }
    }
}
