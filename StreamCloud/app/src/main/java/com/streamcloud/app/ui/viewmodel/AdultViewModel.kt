package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.EpornerApi
import com.streamcloud.app.data.api.EpornerCategory
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
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val categories: List<EpornerCategory> = emptyList(),
    val loadingCategories: Boolean = false,
    val selectedCategory: EpornerCategory? = null,
    val categorySearch: String = "",
)

private val SORT_ORDERS = listOf("most-popular", "newest", "top-rated", "most-viewed", "longest")

class AdultViewModel : ViewModel() {
    private val _state = MutableStateFlow(AdultState())
    val state: StateFlow<AdultState> = _state.asStateFlow()

    private val eporner: EpornerApi =
        Net.retrofit("https://www.eporner.com/").create(EpornerApi::class.java)

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var currentQuery: String = ""
    private var currentOrder: String = "most-popular"

    init {
        fetchPage(query = "", page = 1, order = "most-popular", replaceItems = true, isInitial = true)
        loadCategories()
    }

    fun setSource(@Suppress("UNUSED_PARAMETER") source: AdultSource) {
        // Only Eporner is supported; no-op for other values.
    }

    fun search(query: String) {
        val q = query.trim()
        currentQuery = q
        currentOrder = "most-popular"
        loadMoreJob?.cancel()
        loadMoreJob = null
        fetchPage(query = q, page = 1, order = currentOrder, replaceItems = true, isInitial = false)
    }

    /** Reload the current feed with a fresh randomised sort order for varied content. */
    fun refresh() {
        val newOrder = SORT_ORDERS.filterNot { it == currentOrder }.random()
        currentOrder = newOrder
        loadMoreJob?.cancel()
        loadMoreJob = null
        fetchPage(query = currentQuery, page = 1, order = currentOrder, replaceItems = true, isInitial = false)
    }

    /** Append the next page. Safe to call repeatedly; ignores calls while already loading. */
    fun loadMore() {
        if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return
        if (loadMoreJob?.isActive == true) return
        val nextPage = _state.value.currentPage + 1
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val r = eporner.search(
                    query    = currentQuery,
                    perPage  = 30,
                    page     = nextPage,
                    order    = currentOrder,
                )
                val newItems = r.videos.mapToAdultItems()
                val existingIds = _state.value.items.map { it.id }.toHashSet()
                val deduped = newItems.filterNot { existingIds.contains(it.id) }
                val hasMore = r.videos.isNotEmpty() &&
                        (nextPage * r.per_page < r.total_count || r.total_count == 0 && r.videos.size == r.per_page)
                _state.update {
                    it.copy(
                        items       = it.items + deduped,
                        loadingMore = false,
                        hasMore     = hasMore,
                        currentPage = nextPage,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, error = "Failed to load more: ${e.message}") }
            }
        }
    }

    fun selectCategory(category: EpornerCategory?) {
        _state.update { it.copy(selectedCategory = category, categorySearch = "") }
        val q = category?.title?.trim() ?: ""
        currentQuery = q
        currentOrder = "most-popular"
        loadMoreJob?.cancel()
        loadMoreJob = null
        fetchPage(query = q, page = 1, order = currentOrder, replaceItems = true, isInitial = false)
    }

    fun setCategorySearch(q: String) {
        _state.update { it.copy(categorySearch = q) }
    }

    fun loadCategories() {
        viewModelScope.launch {
            _state.update { it.copy(loadingCategories = true) }
            try {
                val resp = eporner.categories(perPage = 100)
                _state.update { it.copy(categories = resp.categories, loadingCategories = false) }
            } catch (_: Exception) {
                _state.update { it.copy(loadingCategories = false) }
            }
        }
    }

    // ---- private helpers ----

    private fun fetchPage(
        query: String,
        page: Int,
        order: String,
        replaceItems: Boolean,
        isInitial: Boolean,
    ) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!isInitial) delay(if (query.isBlank()) 0L else 300L)
            _state.update { it.copy(loading = true, error = null) }
            try {
                val r = eporner.search(query = query, perPage = 30, page = page, order = order)
                val newItems = r.videos.mapToAdultItems()
                val hasMore = r.videos.isNotEmpty() &&
                        (page * r.per_page < r.total_count || r.total_count == 0 && r.videos.size == r.per_page)
                if (replaceItems) {
                    _state.update {
                        it.copy(
                            items       = newItems,
                            loading     = false,
                            hasMore     = hasMore,
                            currentPage = page,
                        )
                    }
                } else {
                    val existingIds = _state.value.items.map { it.id }.toHashSet()
                    val deduped = newItems.filterNot { existingIds.contains(it.id) }
                    _state.update {
                        it.copy(
                            items       = it.items + deduped,
                            loading     = false,
                            hasMore     = hasMore,
                            currentPage = page,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Failed: ${e.message}") }
            }
        }
    }

    private fun List<com.streamcloud.app.data.api.EpornerVideo>.mapToAdultItems(): List<AdultItem> =
        map { v ->
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
