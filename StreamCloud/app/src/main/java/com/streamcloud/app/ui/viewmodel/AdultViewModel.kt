package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.EpornerApi
import com.streamcloud.app.data.api.RedditAdultRepository
import com.streamcloud.app.data.api.RedtubeRepository
import com.streamcloud.app.data.network.Net
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

    val subreddit: String = "nsfw",

    val nextAfter: String? = null,
    val loadingMore: Boolean = false,

    val redtubePage: Int = 1,
    val redtubeQuery: String = "",
    val redtubeCanLoadMore: Boolean = true,
)

class AdultViewModel : ViewModel() {
    private val _state = MutableStateFlow(AdultState())
    val state: StateFlow<AdultState> = _state.asStateFlow()

    private val eporner: EpornerApi =
        Net.retrofit("https://www.eporner.com/").create(EpornerApi::class.java)

    private var searchJob: Job? = null

    init { search("popular") }


    fun setSource(source: AdultSource) {
        if (_state.value.source == source) return
        _state.update { it.copy(source = source, items = emptyList(), error = null, nextAfter = null, redtubePage = 1, redtubeCanLoadMore = true) }
        when (source) {
            AdultSource.Eporner -> search("popular")
            AdultSource.Reddit  -> loadReddit(_state.value.subreddit)
            AdultSource.Redtube -> searchRedtube("")
        }
    }


    fun search(query: String) {
        when (_state.value.source) {
            AdultSource.Eporner -> searchEporner(query)
            AdultSource.Reddit  -> {
                val sub = query.ifBlank { "nsfw" }.removePrefix("r/").trim()
                _state.update { it.copy(subreddit = sub) }
                loadReddit(sub)
            }
            AdultSource.Redtube -> searchRedtube(query)
        }
    }


    fun setSubreddit(sub: String) {
        val clean = sub.removePrefix("r/").trim().ifBlank { "nsfw" }
        _state.update { it.copy(subreddit = clean) }
        loadReddit(clean)
    }


    fun loadMore() {
        val s = _state.value
        if (s.loadingMore) return
        when (s.source) {
            AdultSource.Reddit  -> {
                if (s.nextAfter == null) return
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
            AdultSource.Redtube -> {
                if (!s.redtubeCanLoadMore) return
                viewModelScope.launch {
                    _state.update { it.copy(loadingMore = true) }
                    try {
                        val nextPage = s.redtubePage + 1
                        val more = RedtubeRepository.search(s.redtubeQuery, page = nextPage)
                        _state.update {
                            it.copy(
                                items = it.items + more,
                                redtubePage = nextPage,
                                redtubeCanLoadMore = more.isNotEmpty(),
                                loadingMore = false,
                            )
                        }
                    } catch (e: Exception) {
                        _state.update { it.copy(loadingMore = false, error = "Redtube page failed: ${e.message}") }
                    }
                }
            }
            else -> {}
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
                        views  = if (v.views > 0) formatViews(v.views) else null,
                        rating = v.rate?.takeIf { it.isNotBlank() },
                        tags   = v.keywords?.takeIf { it.isNotBlank() },
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

    private fun searchRedtube(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(if (query.isBlank()) 0L else 300L)
            _state.update { it.copy(loading = true, error = null, redtubeQuery = query, redtubePage = 1, redtubeCanLoadMore = true) }
            try {
                val items = RedtubeRepository.search(query = query, page = 1)
                _state.update { it.copy(items = items, loading = false, redtubeCanLoadMore = items.isNotEmpty()) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Redtube failed: ${e.message}") }
            }
        }
    }


    suspend fun resolveStreamUrl(videoId: String, fallbackEmbed: String): String {


        val normalizedEmbed = when {
            fallbackEmbed.startsWith("//") -> "https:$fallbackEmbed"
            fallbackEmbed.startsWith("/")  -> "https://www.eporner.com$fallbackEmbed"
            else -> fallbackEmbed
        }
        if (videoId.startsWith("direct://")) {
            val direct = videoId.removePrefix("direct://")
            return direct.ifBlank { normalizedEmbed }
        }
        _state.update { it.copy(resolvingId = videoId, error = null) }
        return try {
            val resp = eporner.details(id = videoId)
            resp.videos.firstOrNull()?.bestMp4() ?: normalizedEmbed
        } catch (e: Exception) {
            _state.update { it.copy(error = "Stream resolve failed: ${e.message}") }
            normalizedEmbed
        } finally {
            _state.update { it.copy(resolvingId = null) }
        }
    }

    private fun formatViews(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000     -> "%.1fK".format(n / 1_000.0)
        else           -> n.toString()
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
