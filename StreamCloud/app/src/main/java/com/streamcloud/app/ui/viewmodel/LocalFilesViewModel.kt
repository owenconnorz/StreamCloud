package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.local.LocalAudioItem
import com.streamcloud.app.data.local.LocalImageItem
import com.streamcloud.app.data.local.LocalMediaRepository
import com.streamcloud.app.data.local.LocalMediaSection
import com.streamcloud.app.data.local.LocalVideoItem
import kotlinx.coroutines.launch

data class PagedLocalMediaState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val loadedOnce: Boolean = false,
)

data class LocalFilesState(
    val music: PagedLocalMediaState<LocalAudioItem> = PagedLocalMediaState(),
    val videos: PagedLocalMediaState<LocalVideoItem> = PagedLocalMediaState(),
    val images: PagedLocalMediaState<LocalImageItem> = PagedLocalMediaState(),
)

class LocalFilesViewModel(context: Context) : ViewModel() {
    private val repository = LocalMediaRepository(context)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(LocalFilesState())
    val state: kotlinx.coroutines.flow.StateFlow<LocalFilesState> = _state

    fun ensureLoaded(section: LocalMediaSection) {
        when (section) {
            LocalMediaSection.Music -> if (!_state.value.music.loadedOnce && !_state.value.music.isLoading) refresh(section)
            LocalMediaSection.Videos -> if (!_state.value.videos.loadedOnce && !_state.value.videos.isLoading) refresh(section)
            LocalMediaSection.Images -> if (!_state.value.images.loadedOnce && !_state.value.images.isLoading) refresh(section)
        }
    }

    fun refresh(section: LocalMediaSection) {
        when (section) {
            LocalMediaSection.Music -> loadMusic(reset = true)
            LocalMediaSection.Videos -> loadVideos(reset = true)
            LocalMediaSection.Images -> loadImages(reset = true)
        }
    }

    fun loadMore(section: LocalMediaSection) {
        when (section) {
            LocalMediaSection.Music -> loadMusic(reset = false)
            LocalMediaSection.Videos -> loadVideos(reset = false)
            LocalMediaSection.Images -> loadImages(reset = false)
        }
    }

    private fun loadMusic(reset: Boolean) {
        val current = _state.value.music
        if (current.isLoading || current.isLoadingMore || (!reset && !current.hasMore)) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                music = current.copy(
                    isLoading = reset,
                    isLoadingMore = !reset,
                    error = null,
                ),
            )
            runCatching {
                repository.loadAudioPage(offset = if (reset) 0 else current.items.size, limit = PAGE_SIZE)
            }.onSuccess { page ->
                val merged = if (reset) page.items else (current.items + page.items).distinctBy { it.id }
                _state.value = _state.value.copy(
                    music = current.copy(
                        items = merged,
                        isLoading = false,
                        isLoadingMore = false,
                        hasMore = page.hasMore,
                        error = null,
                        loadedOnce = true,
                    ),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    music = current.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = error.message ?: "Couldn't load local music.",
                        loadedOnce = current.loadedOnce,
                    ),
                )
            }
        }
    }

    private fun loadVideos(reset: Boolean) {
        val current = _state.value.videos
        if (current.isLoading || current.isLoadingMore || (!reset && !current.hasMore)) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                videos = current.copy(
                    isLoading = reset,
                    isLoadingMore = !reset,
                    error = null,
                ),
            )
            runCatching {
                repository.loadVideoPage(offset = if (reset) 0 else current.items.size, limit = PAGE_SIZE)
            }.onSuccess { page ->
                val merged = if (reset) page.items else (current.items + page.items).distinctBy { it.id }
                _state.value = _state.value.copy(
                    videos = current.copy(
                        items = merged,
                        isLoading = false,
                        isLoadingMore = false,
                        hasMore = page.hasMore,
                        error = null,
                        loadedOnce = true,
                    ),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    videos = current.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = error.message ?: "Couldn't load local videos.",
                        loadedOnce = current.loadedOnce,
                    ),
                )
            }
        }
    }

    private fun loadImages(reset: Boolean) {
        val current = _state.value.images
        if (current.isLoading || current.isLoadingMore || (!reset && !current.hasMore)) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                images = current.copy(
                    isLoading = reset,
                    isLoadingMore = !reset,
                    error = null,
                ),
            )
            runCatching {
                repository.loadImagePage(offset = if (reset) 0 else current.items.size, limit = PAGE_SIZE)
            }.onSuccess { page ->
                val merged = if (reset) page.items else (current.items + page.items).distinctBy { it.id }
                _state.value = _state.value.copy(
                    images = current.copy(
                        items = merged,
                        isLoading = false,
                        isLoadingMore = false,
                        hasMore = page.hasMore,
                        error = null,
                        loadedOnce = true,
                    ),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    images = current.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = error.message ?: "Couldn't load local images.",
                        loadedOnce = current.loadedOnce,
                    ),
                )
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 60

        fun factory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LocalFilesViewModel(context) as T
            }
        }
    }
}
