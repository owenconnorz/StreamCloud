package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.EpornerApi
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
    val loadingMore: Boolean = false,
)

class AdultViewModel : ViewModel() {
    private val _state = MutableStateFlow(AdultState())
    val state: StateFlow<AdultState> = _state.asStateFlow()

    private val eporner: EpornerApi =
        Net.retrofit("https://www.eporner.com/").create(EpornerApi::class.java)

    private var searchJob: Job? = null

    init { search("popular") }

    fun setSource(@Suppress("UNUSED_PARAMETER") source: AdultSource) {
        // Only Eporner is supported; no-op for other values.
    }

    fun search(query: String) { searchEporner(query) }

    fun loadMore() {
        // Eporner returns a single page of results; load-more is a no-op.
    }

    private fun searchEporner(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(if (query == "popular") 0L else 300L)
            _state.update { it.copy(loading = true, error = null) }
            try {
                val q = if (query.isBlank()) "popular" else query
                val r = eporner.search(query = q, perPage = 30)
                val items = r.videos.map { v ->
                    AdultItem(
                        id            = v.id,
                        title         = v.title,
                        thumbnail     = v.defaultThumb?.src,
                        previewImage  = v.defaultThumb?.src,
                        durationLabel = v.lengthMin,
                        streamUrl     = null,
                        source        = AdultSource.Eporner,
                        epornerId     = v.id,
                        embedUrl      = v.embed,
                        views         = if (v.views > 0) formatViews(v.views) else null,
                        rating        = v.rate?.takeIf { it.isNotBlank() },
                        tags          = v.keywords?.takeIf { it.isNotBlank() },
                    )
                }
                _state.update { it.copy(items = items, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Failed: ${e.message}") }
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
        fun factory(@Suppress("UNUSED_PARAMETER") context: Context) =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AdultViewModel() as T
                }
            }
    }
}
