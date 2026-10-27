package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.SettingsRepository
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.EpornerApi
import com.streamcloud.app.data.api.EpornerCategory
import com.streamcloud.app.data.api.RedditAdultRepository
import com.streamcloud.app.data.api.RedditAuthRequiredException
import com.streamcloud.app.data.api.RedditRateLimitException
import com.streamcloud.app.data.api.RedGifsRepository
import com.streamcloud.app.data.api.PornhubRepository
import com.streamcloud.app.data.api.PornhubCategory
import com.streamcloud.app.data.api.PornhubHomeSection
import com.streamcloud.app.data.api.PornMzRepository
import com.streamcloud.app.data.api.XHamsterRepository
import com.streamcloud.app.data.api.XHamsterCategory
import com.streamcloud.app.data.api.XHamsterOrientation
import com.streamcloud.app.data.api.XFreeRepository
import com.streamcloud.app.data.library.AdultHistoryEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.network.Net
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
    val pornhubCategories: List<PornhubCategory> = emptyList(),
    val loadingPornhubCategories: Boolean = false,
    val xhamsterCategories: List<XHamsterCategory> = emptyList(),
    val loadingXHamsterCategories: Boolean = false,
    val xhamsterCategoryError: String? = null,
    val xhamsterPopularCategories: List<XHamsterCategory> = emptyList(),
    val loadingXHamsterPopularCategories: Boolean = false,
    val xhamsterPopularCategoryError: String? = null,
    val selectedXHamsterCategory: XHamsterCategory? = null,
    val xhamsterOrientation: XHamsterOrientation = XHamsterOrientation.STRAIGHT,
    val pornhubHomeSections: List<PornhubHomeSection> = emptyList(),
    val pornhubHome: Boolean = false,
    /** True when Reddit returns a 401/403 — the user must log in. */
    val redditNeedsAuth: Boolean = false,
    /** Currently-browsed subreddit (without r/ prefix). */
    val currentSubreddit: String = "nsfw",
    /** Signed-in Reddit username, or empty if not logged in. */
    val redditUsername: String = "",
    /** Currently-browsed RedGifs tag (e.g. "trending", "amateur"). */
    val currentRedGifsTag: String = "trending",
    /** True after the 18+ age gate has been confirmed by the user. */
    val ageGateConfirmed: Boolean = false,
    /** True when adult tab lock is enabled in settings. */
    val adultLockEnabled: Boolean = false,
    /** True once correct PIN has been entered this session. */
    val lockUnlocked: Boolean = false,
    /** The configured safe-mode PIN (empty = none). */
    val safeModePin: String = "",
)

private val SORT_ORDERS = listOf("most-popular", "newest", "top-rated", "most-viewed", "longest")

class AdultViewModel(
    private val settings: SettingsRepository,
    private val appContext: android.content.Context,
) : ViewModel() {
    private val _state = MutableStateFlow(AdultState())
    val state: StateFlow<AdultState> = _state.asStateFlow()

    private val eporner: EpornerApi =
        Net.retrofit("https://www.eporner.com/").create(EpornerApi::class.java)

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var currentQuery: String = ""
    private var currentXHamsterCategoryPath: String? = null
    private var currentXHamsterOrientation: XHamsterOrientation = XHamsterOrientation.STRAIGHT
    private var currentOrder: String = "most-popular"
    private var redditAfter: String? = null
    private var observedPornhubSignIn = false
    private var wasPornhubSignedIn = false

    init {
        // Observe the saved Reddit username and reflect it in state
        viewModelScope.launch {
            settings.redditUsername.collectLatest { username ->
                val hadAuthError = _state.value.redditNeedsAuth
                _state.update { it.copy(redditUsername = username) }
                // Auto-retry after a successful login that resolved an auth error
                if (hadAuthError && username.isNotBlank() && _state.value.source == AdultSource.Reddit) {
                    redditAfter = null
                    fetchRedditPage(replace = true)
                }
            }
        }
        viewModelScope.launch {
            settings.ageGateConfirmed.collectLatest { c ->
                _state.update { it.copy(ageGateConfirmed = c) }
                if (
                    c &&
                    _state.value.source == AdultSource.XHamster &&
                    _state.value.items.isEmpty() &&
                    !_state.value.loading
                ) {
                    fetchXHamsterPage(
                        query = currentQuery,
                        page = _state.value.currentPage,
                        replace = true,
                        categoryPath = currentXHamsterCategoryPath,
                        orientation = currentXHamsterOrientation,
                    )
                    loadXHamsterPopularCategories(currentXHamsterOrientation)
                }
                if (
                    c &&
                    _state.value.source == AdultSource.XFree &&
                    _state.value.items.isEmpty() &&
                    !_state.value.loading
                ) {
                    fetchXFreePage(query = currentQuery, page = 1, replace = true)
                }
                if (
                    c &&
                    _state.value.source == AdultSource.PornMz &&
                    _state.value.items.isEmpty() &&
                    !_state.value.loading
                ) {
                    fetchPornMzPage(query = currentQuery, page = 1, replace = true)
                }
            }
        }
        viewModelScope.launch {
            settings.adultLockEnabled.collectLatest { e ->
                _state.update { it.copy(adultLockEnabled = e) }
            }
        }
        viewModelScope.launch {
            settings.safeModePin.collectLatest { p ->
                _state.update { it.copy(safeModePin = p) }
            }
        }
        viewModelScope.launch {
            settings.pornhubSignedIn.collectLatest { signedIn ->
                val completedSignIn = observedPornhubSignIn &&
                    !wasPornhubSignedIn &&
                    signedIn
                wasPornhubSignedIn = signedIn
                observedPornhubSignIn = true
                if (completedSignIn && _state.value.source == AdultSource.Pornhub) {
                    _state.update { it.copy(currentPage = 1, items = emptyList()) }
                    fetchPornhubPage(replace = true)
                }
            }
        }
        viewModelScope.launch {
            val ageGateConfirmed = settings.ageGateConfirmed.first()
            val savedSourceName = settings.adultSource.first()
            val savedSource = runCatching {
                AdultSource.valueOf(savedSourceName)
            }.getOrElse {
                AdultSource.Eporner.also {
                    settings.setAdultSource(AdultSource.Eporner.name)
                }
            }
            currentXHamsterOrientation =
                XHamsterOrientation.fromPreference(settings.xhamsterOrientation.first())
            _state.update {
                it.copy(
                    source = savedSource,
                    pornhubHome = savedSource == AdultSource.Pornhub,
                    xhamsterOrientation = currentXHamsterOrientation,
                )
            }
            when (savedSource) {
                AdultSource.Reddit  -> fetchRedditPage(replace = true)
                AdultSource.RedGifs -> fetchRedGifsPage(replace = true)
                AdultSource.Pornhub -> {
                    fetchPornhubPage(replace = true)
                    loadPornhubCategories()
                }
                AdultSource.XHamster -> {
                    currentQuery = ""
                    currentXHamsterCategoryPath = null
                    if (ageGateConfirmed) {
                        fetchXHamsterPage(
                            query = "",
                            page = 1,
                            replace = true,
                            orientation = currentXHamsterOrientation,
                        )
                        loadXHamsterPopularCategories(currentXHamsterOrientation)
                    }
                }
                AdultSource.XFree -> {
                    currentQuery = ""
                    if (ageGateConfirmed) {
                        fetchXFreePage(query = "", page = 1, replace = true)
                    }
                }
                AdultSource.PornMz -> {
                    currentQuery = ""
                    if (ageGateConfirmed) {
                        fetchPornMzPage(query = "", page = 1, replace = true)
                    }
                }
                else -> {
                    fetchPage(query = "", page = 1, order = "most-popular", replaceItems = true, isInitial = true)
                    loadCategories()
                }
            }
        }
    }

    fun setSource(source: AdultSource) {
        if (_state.value.source == source) return
        currentXHamsterCategoryPath = null
        searchJob?.cancel(); searchJob = null
        loadMoreJob?.cancel(); loadMoreJob = null
        _state.update {
            it.copy(
                source       = source,
                items        = emptyList(),
                pornhubHomeSections = emptyList(),
                pornhubHome  = source == AdultSource.Pornhub,
                loading      = false,
                loadingMore  = false,
                hasMore      = true,
                currentPage  = 1,
                error        = null,
                selectedCategory = null,
                categorySearch   = "",
                selectedXHamsterCategory = null,
            )
        }
        viewModelScope.launch { settings.setAdultSource(source.name) }
        when (source) {
            AdultSource.Eporner -> {
                currentQuery = ""
                currentOrder = "most-popular"
                fetchPage(query = "", page = 1, order = currentOrder, replaceItems = true, isInitial = false)
                loadCategories()
            }
            AdultSource.Reddit -> {
                redditAfter = null
                _state.update { it.copy(currentSubreddit = DEFAULT_REDDIT_SUB) }
                fetchRedditPage(replace = true)
            }
            AdultSource.RedGifs -> {
                _state.update { it.copy(currentRedGifsTag = "trending", currentPage = 1) }
                fetchRedGifsPage(replace = true)
            }
            AdultSource.Pornhub -> {
                currentQuery = ""
                _state.update { it.copy(currentPage = 1) }
                fetchPornhubPage(replace = true)
                loadPornhubCategories()
            }
            AdultSource.XHamster -> {
                currentQuery = ""
                viewModelScope.launch {
                    fetchXHamsterPage(query = "", page = 1, replace = true)
                }
                loadXHamsterPopularCategories()
            }
            AdultSource.XFree -> {
                currentQuery = ""
                viewModelScope.launch {
                    fetchXFreePage(query = "", page = 1, replace = true)
                }
            }
            AdultSource.PornMz -> {
                currentQuery = ""
                viewModelScope.launch {
                    fetchPornMzPage(query = "", page = 1, replace = true)
                }
            }
        }
    }

    fun search(query: String) {
        if (_state.value.source == AdultSource.Pornhub) {
            currentQuery = query.trim()
            _state.update {
                it.copy(
                    pornhubHome = currentQuery.isBlank(),
                    pornhubHomeSections = if (currentQuery.isBlank()) {
                        it.pornhubHomeSections
                    } else {
                        emptyList()
                    },
                )
            }
            searchJob?.cancel()
            loadMoreJob?.cancel()
            searchJob = viewModelScope.launch {
                delay(300L)
                fetchPornhubPage(replace = true)
            }
            return
        }
        if (_state.value.source == AdultSource.XHamster) {
            val requestedQuery = query.trim()
            currentQuery = requestedQuery
            currentXHamsterCategoryPath = null
            searchJob?.cancel()
            loadMoreJob?.cancel()
            loadMoreJob = null
            _state.update {
                it.copy(
                    loading = true,
                    loadingMore = false,
                    currentPage = 1,
                    hasMore = true,
                    error = null,
                    selectedXHamsterCategory = null,
                )
            }
            searchJob = viewModelScope.launch {
                if (requestedQuery.isNotBlank()) delay(300L)
                fetchXHamsterPage(query = requestedQuery, page = 1, replace = true)
            }
            return
        }
        if (_state.value.source == AdultSource.XFree) {
            val requestedQuery = query.trim()
            currentQuery = requestedQuery
            searchJob?.cancel()
            loadMoreJob?.cancel()
            loadMoreJob = null
            _state.update {
                it.copy(
                    loading = true,
                    loadingMore = false,
                    currentPage = 1,
                    hasMore = true,
                    error = null,
                )
            }
            searchJob = viewModelScope.launch {
                if (requestedQuery.isNotBlank()) delay(300L)
                fetchXFreePage(query = requestedQuery, page = 1, replace = true)
            }
            return
        }
        if (_state.value.source == AdultSource.PornMz) {
            val requestedQuery = query.trim()
            currentQuery = requestedQuery
            searchJob?.cancel()
            loadMoreJob?.cancel()
            loadMoreJob = null
            _state.update {
                it.copy(
                    loading = true,
                    loadingMore = false,
                    currentPage = 1,
                    hasMore = true,
                    error = null,
                )
            }
            searchJob = viewModelScope.launch {
                if (requestedQuery.isNotBlank()) delay(300L)
                fetchPornMzPage(query = requestedQuery, page = 1, replace = true)
            }
            return
        }
        if (_state.value.source != AdultSource.Eporner) return
        val q = query.trim()
        currentQuery = q
        currentOrder = "most-popular"
        loadMoreJob?.cancel()
        loadMoreJob = null
        fetchPage(query = q, page = 1, order = currentOrder, replaceItems = true, isInitial = false)
    }

    /** Switch to a different subreddit and reload the Reddit feed. */
    fun setSubreddit(subreddit: String) {
        val clean = subreddit.removePrefix("r/").trim()
        if (clean == _state.value.currentSubreddit && _state.value.items.isNotEmpty()) return
        redditAfter = null
        _state.update {
            it.copy(
                currentSubreddit = clean,
                items    = emptyList(),
                hasMore  = true,
                error    = null,
                redditNeedsAuth = false,
            )
        }
        fetchRedditPage(replace = true)
    }

    /** Clear the auth-required flag (e.g. after the user has logged in). */
    fun clearRedditAuthError() {
        _state.update { it.copy(redditNeedsAuth = false, error = null) }
        if (_state.value.source == AdultSource.Reddit) {
            redditAfter = null
            fetchRedditPage(replace = true)
        }
    }

    fun completeRedditLogin(username: String) {
        viewModelScope.launch {
            settings.setRedditUsername(username)
            redditAfter = null
            _state.update {
                it.copy(
                    redditUsername = username,
                    redditNeedsAuth = false,
                    error = null,
                )
            }
            fetchRedditPage(replace = true)
        }
    }

    /** Reload the current feed with a fresh randomised sort order for varied content. */
    fun refresh() {
        when (_state.value.source) {
            AdultSource.Reddit -> {
                redditAfter = null
                fetchRedditPage(replace = true)
            }
            AdultSource.RedGifs -> {
                _state.update { it.copy(currentPage = 1) }
                fetchRedGifsPage(replace = true)
            }
            AdultSource.Pornhub -> {
                _state.update { it.copy(currentPage = 1) }
                fetchPornhubPage(replace = true)
            }
            AdultSource.XHamster -> {
                searchJob?.cancel()
                loadMoreJob?.cancel()
                loadMoreJob = null
                searchJob = viewModelScope.launch {
                    fetchXHamsterPage(
                        query = currentQuery,
                        page = 1,
                        replace = true,
                        categoryPath = currentXHamsterCategoryPath,
                    )
                }
            }
            AdultSource.XFree -> {
                searchJob?.cancel()
                loadMoreJob?.cancel()
                loadMoreJob = null
                searchJob = viewModelScope.launch {
                    fetchXFreePage(query = currentQuery, page = 1, replace = true)
                }
            }
            AdultSource.PornMz -> {
                searchJob?.cancel()
                loadMoreJob?.cancel()
                loadMoreJob = null
                searchJob = viewModelScope.launch {
                    fetchPornMzPage(query = currentQuery, page = 1, replace = true)
                }
            }
            else -> {
                val newOrder = SORT_ORDERS.filterNot { it == currentOrder }.random()
                currentOrder = newOrder
                loadMoreJob?.cancel()
                loadMoreJob = null
                fetchPage(query = currentQuery, page = 1, order = currentOrder, replaceItems = true, isInitial = false)
            }
        }
    }

    /** Append the next page. Safe to call repeatedly; ignores calls while already loading. */
    fun loadMore() {
        if (_state.value.source == AdultSource.Reddit) {
            if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return
            fetchRedditPage(replace = false)
            return
        }
        if (_state.value.source == AdultSource.RedGifs) {
            if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return
            fetchRedGifsPage(replace = false)
            return
        }
        if (_state.value.source == AdultSource.Pornhub) {
            if (currentQuery.isBlank()) return
            if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return
            fetchPornhubPage(replace = false)
            return
        }
        if (_state.value.source == AdultSource.XHamster) {
            if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return
            if (loadMoreJob?.isActive == true) return
            val nextPage = _state.value.currentPage + 1
            loadMoreJob = viewModelScope.launch {
                fetchXHamsterPage(
                    query = currentQuery,
                    page = nextPage,
                    replace = false,
                    categoryPath = currentXHamsterCategoryPath,
                )
            }
            return
        }
        if (_state.value.source == AdultSource.XFree) {
            if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return
            if (loadMoreJob?.isActive == true) return
            val nextPage = _state.value.currentPage + 1
            loadMoreJob = viewModelScope.launch {
                fetchXFreePage(query = currentQuery, page = nextPage, replace = false)
            }
            return
        }
        if (_state.value.source == AdultSource.PornMz) {
            if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return
            if (loadMoreJob?.isActive == true) return
            val nextPage = _state.value.currentPage + 1
            loadMoreJob = viewModelScope.launch {
                fetchPornMzPage(query = currentQuery, page = nextPage, replace = false)
            }
            return
        }
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

    fun selectPornhubCategory(category: PornhubCategory) {
        searchJob?.cancel()
        searchJob = null
        currentQuery = category.title
        _state.update {
            it.copy(
                currentPage = 1,
                pornhubHome = false,
                pornhubHomeSections = emptyList(),
            )
        }
        fetchPornhubPage(replace = true)
    }

    fun selectXHamsterCategory(category: XHamsterCategory?) {
        currentQuery = ""
        currentXHamsterCategoryPath = category?.path
        searchJob?.cancel()
        loadMoreJob?.cancel()
        loadMoreJob = null
        _state.update {
            it.copy(
                items = emptyList(),
                currentPage = 1,
                hasMore = true,
                loading = false,
                loadingMore = false,
                error = null,
                selectedXHamsterCategory = category,
            )
        }
        searchJob = viewModelScope.launch {
            fetchXHamsterPage(
                query = "",
                page = 1,
                replace = true,
                categoryPath = currentXHamsterCategoryPath,
            )
        }
    }

    fun setXHamsterOrientation(orientation: XHamsterOrientation) {
        if (currentXHamsterOrientation == orientation) return
        currentXHamsterOrientation = orientation
        currentXHamsterCategoryPath = null
        searchJob?.cancel()
        searchJob = null
        loadMoreJob?.cancel()
        loadMoreJob = null
        _state.update {
            it.copy(
                xhamsterOrientation = orientation,
                items = emptyList(),
                selectedXHamsterCategory = null,
                xhamsterCategories = emptyList(),
                loadingXHamsterCategories = false,
                xhamsterCategoryError = null,
                xhamsterPopularCategories = emptyList(),
                loadingXHamsterPopularCategories = false,
                xhamsterPopularCategoryError = null,
                currentPage = 1,
                hasMore = true,
                loading = false,
                loadingMore = false,
                error = null,
            )
        }
        viewModelScope.launch { settings.setXHamsterOrientation(orientation.name) }
        if (_state.value.source != AdultSource.XHamster) return

        viewModelScope.launch {
            fetchXHamsterPage(
                query = currentQuery,
                page = 1,
                replace = true,
                categoryPath = null,
                orientation = orientation,
            )
        }
        loadXHamsterPopularCategories(orientation)
    }

    fun loadPornhubCategories() {
        if (_state.value.loadingPornhubCategories ||
            _state.value.pornhubCategories.isNotEmpty()
        ) return
        viewModelScope.launch {
            _state.update { it.copy(loadingPornhubCategories = true) }
            val categories = runCatching { PornhubRepository.fetchCategories() }
                .getOrDefault(emptyList())
            _state.update {
                it.copy(
                    pornhubCategories = categories,
                    loadingPornhubCategories = false,
                )
            }
        }
    }

    fun loadXHamsterCategories() {
        if (_state.value.loadingXHamsterCategories ||
            _state.value.xhamsterCategories.isNotEmpty()
        ) return
        val orientation = currentXHamsterOrientation
        viewModelScope.launch {
            _state.update {
                it.copy(loadingXHamsterCategories = true, xhamsterCategoryError = null)
            }
            try {
                val categories = XHamsterRepository.fetchCategories(
                    onPartialResults = { partialCategories ->
                        if (currentXHamsterOrientation == orientation) {
                            _state.update {
                                it.copy(xhamsterCategories = partialCategories)
                            }
                        }
                    },
                    orientation = orientation,
                )
                if (_state.value.source != AdultSource.XHamster ||
                    currentXHamsterOrientation != orientation
                ) return@launch
                _state.update {
                    it.copy(
                        xhamsterCategories = categories,
                        loadingXHamsterCategories = false,
                        xhamsterCategoryError = if (categories.isEmpty()) {
                            "XHamster did not return any categories."
                        } else {
                            null
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentXHamsterOrientation != orientation) return@launch
                _state.update {
                    it.copy(
                        loadingXHamsterCategories = false,
                        xhamsterCategoryError = "Could not load XHamster categories: ${e.message}",
                    )
                }
            }
        }
    }

    fun loadXHamsterPopularCategories(
        orientation: XHamsterOrientation = currentXHamsterOrientation,
    ) {
        if (_state.value.loadingXHamsterPopularCategories ||
            (_state.value.xhamsterPopularCategories.isNotEmpty() &&
                _state.value.xhamsterOrientation == orientation)
        ) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loadingXHamsterPopularCategories = true,
                    xhamsterPopularCategoryError = null,
                )
            }
            try {
                val categories = XHamsterRepository.fetchPopularCategories(orientation)
                if (_state.value.source != AdultSource.XHamster ||
                    currentXHamsterOrientation != orientation
                ) return@launch
                _state.update {
                    it.copy(
                        xhamsterPopularCategories = categories,
                        loadingXHamsterPopularCategories = false,
                        xhamsterPopularCategoryError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentXHamsterOrientation != orientation) return@launch
                _state.update {
                    it.copy(
                        loadingXHamsterPopularCategories = false,
                        xhamsterPopularCategoryError =
                            "Could not load XHamster categories: ${e.message}",
                    )
                }
            }
        }
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

    private suspend fun fetchXHamsterPage(
        query: String,
        page: Int,
        replace: Boolean,
        categoryPath: String? = currentXHamsterCategoryPath,
        orientation: XHamsterOrientation = currentXHamsterOrientation,
    ) {
        if (replace) {
            _state.update { it.copy(loading = true, loadingMore = false, error = null) }
        } else {
            _state.update { it.copy(loadingMore = true, error = null) }
        }

        try {
            val result = XHamsterRepository.fetch(
                query = query,
                page = page,
                categoryPath = categoryPath,
                orientation = orientation,
            )
            if (_state.value.source != AdultSource.XHamster ||
                currentQuery != query ||
                currentXHamsterCategoryPath != categoryPath ||
                currentXHamsterOrientation != orientation
            ) return

            val currentItems = if (replace) emptyList() else _state.value.items
            val currentIds = currentItems.mapTo(hashSetOf()) { it.id }
            val incomingItems = result.items.filterNot { it.id in currentIds }
            _state.update {
                it.copy(
                    items = currentItems + incomingItems,
                    loading = false,
                    loadingMore = false,
                    hasMore = result.hasMore,
                    currentPage = page,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value.source != AdultSource.XHamster ||
                currentQuery != query ||
                currentXHamsterCategoryPath != categoryPath ||
                currentXHamsterOrientation != orientation
            ) return
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = "XHamster unavailable: ${e.message}",
                )
            }
        }
    }

    private suspend fun fetchXFreePage(
        query: String,
        page: Int,
        replace: Boolean,
    ) {
        if (replace) {
            _state.update { it.copy(loading = true, loadingMore = false, error = null) }
        } else {
            _state.update { it.copy(loadingMore = true, error = null) }
        }

        try {
            val result = XFreeRepository.fetch(query = query, page = page)
            if (_state.value.source != AdultSource.XFree || currentQuery != query) return

            val currentItems = if (replace) emptyList() else _state.value.items
            val currentIds = currentItems.mapTo(hashSetOf()) { it.id }
            val incomingItems = result.items.filterNot { it.id in currentIds }
            _state.update {
                it.copy(
                    items = currentItems + incomingItems,
                    loading = false,
                    loadingMore = false,
                    hasMore = result.hasMore && (replace || incomingItems.isNotEmpty()),
                    currentPage = page,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value.source != AdultSource.XFree || currentQuery != query) return
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    hasMore = false,
                    error = "XFree unavailable: ${e.message}",
                )
            }
        }
    }

    private suspend fun fetchPornMzPage(
        query: String,
        page: Int,
        replace: Boolean,
    ) {
        if (replace) {
            _state.update { it.copy(loading = true, loadingMore = false, error = null) }
        } else {
            _state.update { it.copy(loadingMore = true, error = null) }
        }

        try {
            val result = PornMzRepository.fetchPage(query = query, page = page)
            if (_state.value.source != AdultSource.PornMz || currentQuery != query) return

            val currentItems = if (replace) emptyList() else _state.value.items
            val currentIds = currentItems.mapTo(hashSetOf()) { it.id }
            val incomingItems = result.items.filterNot { it.id in currentIds }
            _state.update {
                it.copy(
                    items = currentItems + incomingItems,
                    loading = false,
                    loadingMore = false,
                    hasMore = result.hasMore && (replace || incomingItems.isNotEmpty()),
                    currentPage = page,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value.source != AdultSource.PornMz || currentQuery != query) return
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    hasMore = false,
                    error = "PornMZ unavailable: ${e.message}",
                )
            }
        }
    }

    private fun fetchRedditPage(replace: Boolean) {
        if (_state.value.loading || (_state.value.loadingMore && !replace)) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            if (replace) {
                _state.update { it.copy(loading = true, error = null, redditNeedsAuth = false) }
            } else {
                _state.update { it.copy(loadingMore = true) }
            }
            try {
                val (items, after) = RedditAdultRepository.fetch(
                    subreddit = _state.value.currentSubreddit,
                    sort = "hot",
                    after = if (replace) null else redditAfter,
                )
                redditAfter = after
                val hasMore = after != null && items.isNotEmpty()
                if (replace) {
                    _state.update {
                        it.copy(
                            items      = items,
                            loading    = false,
                            hasMore    = hasMore,
                            currentPage = 1,
                        )
                    }
                } else {
                    val existingIds = _state.value.items.map { it.id }.toHashSet()
                    val deduped = items.filterNot { existingIds.contains(it.id) }
                    _state.update {
                        it.copy(
                            items       = it.items + deduped,
                            loadingMore = false,
                            hasMore     = hasMore,
                        )
                    }
                }
            } catch (e: RedditAuthRequiredException) {
                _state.update {
                    it.copy(
                        loading         = false,
                        loadingMore     = false,
                        redditNeedsAuth = true,
                        error           = e.message,
                    )
                }
            } catch (e: RedditRateLimitException) {
                _state.update {
                    it.copy(
                        loading     = false,
                        loadingMore = false,
                        error       = e.message,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading     = false,
                        loadingMore = false,
                        error       = "Reddit unavailable: ${e.message}",
                    )
                }
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

    /** Switch to a different RedGifs tag and reload the feed. */
    fun setRedGifsTag(tag: String) {
        if (tag == _state.value.currentRedGifsTag && _state.value.items.isNotEmpty()) return
        _state.update {
            it.copy(currentRedGifsTag = tag, items = emptyList(), hasMore = true, error = null, currentPage = 1)
        }
        fetchRedGifsPage(replace = true)
    }

    private fun fetchRedGifsPage(replace: Boolean) {
        if (_state.value.loading || (_state.value.loadingMore && !replace)) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            val tag  = _state.value.currentRedGifsTag
            val page = if (replace) 1 else _state.value.currentPage + 1
            if (replace) {
                _state.update { it.copy(loading = true, error = null) }
            } else {
                _state.update { it.copy(loadingMore = true) }
            }
            try {
                val (items, hasMore) = if (tag == "trending") {
                    RedGifsRepository.fetchTrending(page = page)
                } else {
                    RedGifsRepository.fetchTag(tag = tag, page = page)
                }
                if (replace) {
                    _state.update {
                        it.copy(items = items, loading = false, hasMore = hasMore, currentPage = page)
                    }
                } else {
                    val existingIds = _state.value.items.map { it.id }.toHashSet()
                    val deduped = items.filterNot { existingIds.contains(it.id) }
                    _state.update {
                        it.copy(
                            items       = it.items + deduped,
                            loadingMore = false,
                            hasMore     = hasMore,
                            currentPage = page,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, loadingMore = false, error = "RedGifs unavailable: ${e.message}")
                }
            }
        }
    }

    private fun fetchPornhubPage(replace: Boolean) {
        if (!replace && (_state.value.loading || _state.value.loadingMore)) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            val page = if (replace) 1 else _state.value.currentPage + 1
            val requestedQuery = currentQuery
            if (replace) {
                _state.update { it.copy(loading = true, error = null) }
            } else {
                _state.update { it.copy(loadingMore = true) }
            }
            try {
                val homeSections = if (requestedQuery.isBlank()) {
                    PornhubRepository.fetchHome()
                } else {
                    emptyList()
                }
                val result = if (requestedQuery.isBlank()) {
                    null
                } else {
                    PornhubRepository.fetch(requestedQuery, page)
                }
                if (_state.value.source != AdultSource.Pornhub || currentQuery != requestedQuery) {
                    return@launch
                }
                if (replace) {
                    _state.update {
                        it.copy(
                            items = result?.items.orEmpty(),
                            pornhubHomeSections = homeSections,
                            loading = false,
                            hasMore = result?.hasMore ?: false,
                            currentPage = page,
                            error = if (result?.items.orEmpty().isEmpty() &&
                                homeSections.isEmpty()
                            ) {
                                "Pornhub returned no videos."
                            } else {
                                null
                            },
                        )
                    }
                } else {
                    checkNotNull(result)
                    val existingIds = _state.value.items.map { it.id }.toHashSet()
                    val deduped = result.items.filterNot { existingIds.contains(it.id) }
                    _state.update {
                        it.copy(
                            items = it.items + deduped,
                            loadingMore = false,
                            hasMore = result.hasMore && deduped.isNotEmpty(),
                            currentPage = page,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.source != AdultSource.Pornhub || currentQuery != requestedQuery) {
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = e.message?.takeIf(String::isNotBlank)
                            ?: "Pornhub is unavailable right now.",
                    )
                }
            }
        }
    }


    // ── Age gate & lock ────────────────────────────────────────────────────────

    fun confirmAgeGate() {
        viewModelScope.launch { settings.setAgeGateConfirmed(true) }
    }

    /** Checks pin against stored safe-mode PIN; unlocks in-memory state if correct. */
    fun unlockWithPin(enteredPin: String): Boolean {
        val correct = enteredPin.isNotBlank() && enteredPin == _state.value.safeModePin
        if (correct) _state.update { it.copy(lockUnlocked = true) }
        return correct
    }

    // ── Watch history ──────────────────────────────────────────────────────────

    fun recordHistory(item: AdultItem) {
        viewModelScope.launch {
            try {
                LibraryDb.get(appContext).adultHistory().insert(
                    AdultHistoryEntity(
                        id            = "${item.source.name}:${item.id}",
                        title         = item.title,
                        thumbnail     = item.thumbnail,
                        source        = item.source.name,
                        embedUrl      = item.embedUrl ?: item.streamUrl,
                        durationLabel = item.durationLabel,
                        watchedAt     = System.currentTimeMillis(),
                    )
                )
            } catch (_: Exception) {}
        }
    }

    // ── Download ────────────────────────────────────────────────────────────────

    fun downloadVideo(item: AdultItem) {
        viewModelScope.launch {
            try {
                val pornhubPlayback = if (item.source == AdultSource.Pornhub) {
                    PornhubRepository.resolve(
                        item.id,
                        item.embedUrl.orEmpty(),
                        preferProgressive = true,
                    )
                } else {
                    null
                }
                val url: String = when (item.source) {
                    AdultSource.Eporner -> {
                        val fallbackEmbed = normalizeEpornerEmbed(item.embedUrl.orEmpty())
                        resolveStreamUrl(
                            item.epornerId ?: item.id,
                            item.embedUrl.orEmpty(),
                        ).also {
                            require(isSecureDownloadUrl(it) && it != fallbackEmbed) {
                            "Eporner did not provide a downloadable video file for this item."
                            }
                        }
                    }
                    AdultSource.Pornhub -> pornhubPlayback!!.url.also {
                        val uri = runCatching { java.net.URI(it) }.getOrNull()
                        require(
                            isSecureDownloadUrl(it) &&
                                !uri?.path.orEmpty().endsWith(".m3u8", ignoreCase = true) &&
                                !uri?.path.orEmpty().endsWith(".mpd", ignoreCase = true),
                        ) {
                            "Pornhub did not provide a downloadable video file for this item."
                        }
                    }
                    AdultSource.XHamster -> item.streamUrl
                        ?.takeIf(::isDirectVideoDownloadUrl)
                        ?: throw IllegalStateException(
                            "XHamster has not exposed a direct downloadable video file for this item.",
                        )
                    else -> item.streamUrl
                        ?.takeIf(::isDirectVideoDownloadUrl)
                        ?: throw IllegalStateException(
                            "This provider did not provide a direct downloadable video file.",
                        )
                }
                val extension = runCatching {
                    java.net.URI(url).path.substringAfterLast('.', "").lowercase()
                }.getOrNull()?.takeIf { it in setOf("mp4", "m4v", "webm") } ?: "mp4"
                val safeName = item.title.take(60)
                    .replace(Regex("[^\\w \\-]"), "_")
                    .trim() + ".$extension"
                val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                    .setTitle(item.title.take(100))
                    .setDescription("Downloading via StreamCloud…")
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "StreamCloud/$safeName")
                    .setAllowedNetworkTypes(
                        android.app.DownloadManager.Request.NETWORK_WIFI or
                        android.app.DownloadManager.Request.NETWORK_MOBILE,
                    )
                pornhubPlayback?.headers?.forEach(req::addRequestHeader)
                (appContext.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager)
                    .enqueue(req)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Download failed: ${e.message}") }
            }
        }
    }

    private fun isDirectVideoDownloadUrl(rawUrl: String): Boolean {
        val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return false
        if (!isSecureDownloadUrl(rawUrl)) return false
        return uri.path.orEmpty().lowercase().let { path ->
            listOf(".mp4", ".m4v", ".webm").any(path::endsWith)
        }
    }

    private fun isSecureDownloadUrl(rawUrl: String): Boolean {
        val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    private fun normalizeEpornerEmbed(rawUrl: String): String = when {
        rawUrl.startsWith("//") -> "https:$rawUrl"
        rawUrl.startsWith("/") -> "https://www.eporner.com$rawUrl"
        else -> rawUrl
    }

        companion object {
        private const val DEFAULT_REDDIT_SUB = "gonewild"

        fun factory(context: Context) =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AdultViewModel(
                        settings   = SettingsRepository(context.applicationContext),
                        appContext  = context.applicationContext,
                    ) as T
                }
            }
    }
}
