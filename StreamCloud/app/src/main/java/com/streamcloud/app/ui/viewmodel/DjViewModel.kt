package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.ChatRequest
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.newpipe.YtTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val INITIAL_TRACK_COUNT = 24
private const val EXTENSION_TRACK_COUNT = 12

data class DjSession(
    val request: String,
    val narration: String,
    val tracks: List<YtTrack>,
    val isPersonalized: Boolean = false,
    val sourceDescription: String = "",
)

data class DjUiState(
    val loading: Boolean = false,
    val extending: Boolean = false,
    val session: DjSession? = null,
    val error: String? = null,
)

/**
 * The DJ always chooses from canonical StreamCloud search results. The AI service contributes
 * narration only, which prevents arbitrary model text from controlling playback or introducing
 * untrusted stream URLs.
 */
class DjViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val serviceLocator = ServiceLocator.get(appContext)
    private val trackDao = LibraryDb.get(appContext).tracks()

    private val _state = MutableStateFlow(DjUiState())
    val state: StateFlow<DjUiState> = _state.asStateFlow()

    fun buildMix(request: String) {
        val normalized = request.trim()
        if (normalized.length < 2) {
            _state.update { it.copy(error = "Tell the DJ what you want to hear.") }
            return
        }

        _state.update { it.copy(loading = true, error = null, session = null) }
        viewModelScope.launch {
            try {
                val tracks = NewPipeRepository.searchSongs(normalized)
                    .distinctBy { it.url }
                    .take(12)
                if (tracks.isEmpty()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "I couldn't find a playable mix for \"$normalized\". Try another mood, artist, or genre.",
                        )
                    }
                    return@launch
                }

                val narration = createNarration(normalized, tracks)
                _state.update {
                    it.copy(
                        loading = false,
                        session = DjSession(
                            request = normalized,
                            narration = narration,
                            tracks = tracks,
                            sourceDescription = "Built from your StreamCloud search.",
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "The DJ couldn't build that mix: ${error.message ?: "please try again."}",
                    )
                }
            }
        }
    }

    fun buildPersonalizedMix(onComplete: (DjSession?) -> Unit = {}) {
        _state.update { it.copy(loading = true, error = null, session = null) }
        viewModelScope.launch {
            try {
                val liked = trackDao.liked().first()
                val listeningHistoryEnabled = serviceLocator.settings.listenHistoryEnabled.first() &&
                    !serviceLocator.settings.pauseListenHistory.first()
                val recent = if (listeningHistoryEnabled) trackDao.recent().first() else emptyList()
                val mostPlayed = if (listeningHistoryEnabled) {
                    trackDao.mostPlayed().first().filter { it.playCount > 0 }
                } else {
                    emptyList()
                }
                val searchHistory = serviceLocator.settings.musicSearchHistory.first()

                val seeds = buildList {
                    addAll(liked)
                    addAll(mostPlayed)
                    addAll(recent)
                }.distinctBy { it.url }.take(6)
                val seedTracks = seeds.map { entity ->
                    YtTrack(
                        title = entity.title,
                        uploader = entity.artist,
                        durationSec = entity.durationSec,
                        url = entity.url,
                        thumbnail = entity.thumbnail,
                    )
                }
                val discoveryQueries = buildList {
                    seeds.take(3).forEach { seed ->
                        if (seed.artist.isNotBlank()) add("${seed.artist} similar songs")
                        if (seed.title.isNotBlank()) add("${seed.title} ${seed.artist}")
                    }
                    addAll(searchHistory.take(2))
                }.map(String::trim).filter { it.length >= 2 }.distinct().take(7)

                if (seeds.isEmpty() && discoveryQueries.isEmpty()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "Play or like a few songs first, then the DJ can make a mix around your taste.",
                        )
                    }
                    onComplete(null)
                    return@launch
                }

                val discoveredTracks = coroutineScope {
                    discoveryQueries.map { query ->
                        async {
                            runCatching { NewPipeRepository.searchSongs(query) }
                                .getOrDefault(emptyList())
                                .take(8)
                        }
                    }.awaitAll().flatten()
                }
                val tracks = buildDjRadioQueue(
                    familiar = seedTracks,
                    discoveries = discoveredTracks
                        .filter { it.url.isNotBlank() }
                        .distinctBy { it.url }
                        .filterNot { candidate ->
                            seedTracks.any { seed -> seed.url == candidate.url }
                        },
                    limit = INITIAL_TRACK_COUNT,
                )
                if (tracks.isEmpty()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "I couldn't find playable songs around your listening yet. Try again or use a mood below.",
                        )
                    }
                    onComplete(null)
                    return@launch
                }

                val signalParts = buildList {
                    if (liked.isNotEmpty()) add("${liked.size} liked")
                    if (listeningHistoryEnabled && mostPlayed.isNotEmpty()) add("your most-played")
                    if (listeningHistoryEnabled && recent.isNotEmpty()) add("recent listening")
                    if (searchHistory.isNotEmpty()) add("recent searches")
                }
                val sourceDescription = if (signalParts.isEmpty()) {
                    "Your preferences stay on this device. Song searches are used only to find this mix."
                } else {
                    "Built from ${signalParts.joinToString(", ")}. Your preferences stay on this device; song searches find fresh similar tracks."
                }
                // Personalized track metadata is private listening data. Keep this narration local
                // instead of sending a seed track to the optional remote AI backend.
                val narration = buildFallbackNarration("your listening", tracks)
                val session = DjSession(
                    request = "For you",
                    narration = narration,
                    tracks = tracks,
                    isPersonalized = true,
                    sourceDescription = "$sourceDescription The DJ will keep finding the next chapter while you listen.",
                )
                _state.update {
                    it.copy(loading = false, session = session)
                }
                onComplete(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "The personalized DJ couldn't build a mix: ${error.message ?: "please try again."}",
                    )
                }
                onComplete(null)
            }
        }
    }

    /**
     * Extends an active personalized radio before its queue runs out. Fresh searches are built
     * around the current chapter and local taste signals so the DJ feels continuous instead of
     * looping a fixed playlist.
     */
    fun extendPersonalizedMix(
        session: DjSession,
        onComplete: (DjSession?) -> Unit = {},
    ) {
        if (!session.isPersonalized || _state.value.extending) return
        _state.update { it.copy(extending = true, error = null) }
        viewModelScope.launch {
            try {
                val existingUrls = session.tracks.map(YtTrack::url).toSet()
                val liked = trackDao.liked().first()
                val listeningHistoryEnabled = serviceLocator.settings.listenHistoryEnabled.first() &&
                    !serviceLocator.settings.pauseListenHistory.first()
                val recent = if (listeningHistoryEnabled) trackDao.recent().first() else emptyList()
                val signalTracks = buildList {
                    addAll(session.tracks.takeLast(5))
                    addAll(
                        liked.map { entity ->
                            YtTrack(
                                title = entity.title,
                                uploader = entity.artist,
                                durationSec = entity.durationSec,
                                url = entity.url,
                                thumbnail = entity.thumbnail,
                            )
                        },
                    )
                    if (listeningHistoryEnabled) {
                        addAll(
                            recent.map { entity ->
                                YtTrack(
                                    title = entity.title,
                                    uploader = entity.artist,
                                    durationSec = entity.durationSec,
                                    url = entity.url,
                                    thumbnail = entity.thumbnail,
                                )
                            },
                        )
                    }
                }.filter { it.url.isNotBlank() }.distinctBy(YtTrack::url).take(8)

                val queries = buildList {
                    signalTracks.take(4).forEach { track ->
                        if (track.uploader.isNotBlank()) add("${track.uploader} similar music")
                        if (track.title.isNotBlank() && track.uploader.isNotBlank()) {
                            add("${track.title} ${track.uploader} mix")
                        }
                    }
                    add("more ${session.request} music")
                }.map(String::trim).filter { it.length >= 2 }.distinct().take(7)

                val discoveries = coroutineScope {
                    queries.map { query ->
                        async {
                            runCatching { NewPipeRepository.searchSongs(query) }
                                .getOrDefault(emptyList())
                                .take(8)
                        }
                    }.awaitAll().flatten()
                }
                    .filter { it.url.isNotBlank() && it.url !in existingUrls }
                    .distinctBy(YtTrack::url)

                val additions = buildDjRadioQueue(
                    familiar = signalTracks.filterNot { it.url in existingUrls },
                    discoveries = discoveries,
                    limit = EXTENSION_TRACK_COUNT,
                ).filterNot { it.url in existingUrls }

                val extended = additions.takeIf { it.isNotEmpty() }?.let {
                    session.copy(
                        tracks = session.tracks + it,
                        narration = buildFallbackNarration(
                            "the next chapter of your ${session.request}",
                            it,
                        ),
                        sourceDescription = "The DJ is blending your listening with fresh discoveries.",
                    )
                }
                _state.update { it.copy(extending = false, session = extended ?: session) }
                onComplete(extended)
            } catch (error: CancellationException) {
                _state.update { it.copy(extending = false) }
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        extending = false,
                        error = "The DJ could not find the next chapter: ${error.message ?: "try again later."}",
                    )
                }
                onComplete(null)
            }
        }
    }

    fun clearSession() {
        _state.update { it.copy(session = null, error = null) }
    }

    private suspend fun createNarration(request: String, tracks: List<YtTrack>): String {
        val fallback = buildFallbackNarration(request, tracks)
        return try {
            val provider = serviceLocator.settings.aiProvider.first()
            val model = serviceLocator.settings.aiModel.first()
            val response = serviceLocator.backend().chat(
                ChatRequest(
                    message = "Write one short, original DJ introduction (maximum 35 words) for a listener who requested \"$request\". " +
                        "The first song is \"${tracks.first().title}\" by ${tracks.first().uploader}. " +
                        "Do not imitate a real person or character, mention Spotify, include a URL or link, or give playback instructions or commands. " +
                        "Do not make factual claims you cannot know.",
                    provider = provider,
                    model = model,
                    systemMessage = "You are StreamCloud DJ. Write concise, warm, original narration only; never provide URLs, links, or playback commands.",
                ),
            )
            response.response
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.length in 8..300 && it.isSafeDjNarration() }
                ?: fallback
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fallback
        }
    }

    private fun buildFallbackNarration(request: String, tracks: List<YtTrack>): String {
        val first = tracks.first()
        return "Here is a $request mix. Opening with ${first.title} by ${first.uploader}."
    }

    private fun buildDjRadioQueue(
        familiar: List<YtTrack>,
        discoveries: List<YtTrack>,
        limit: Int,
    ): List<YtTrack> {
        val result = mutableListOf<YtTrack>()
        val seen = mutableSetOf<String>()
        var familiarIndex = 0
        var discoveryIndex = 0

        fun addNext(source: List<YtTrack>, index: Int): Int {
            var next = index
            while (next < source.size && result.size < limit) {
                val track = source[next++]
                if (track.url.isNotBlank() && seen.add(track.url)) result += track
            }
            return next
        }

        while (result.size < limit && (familiarIndex < familiar.size || discoveryIndex < discoveries.size)) {
            if (familiarIndex < familiar.size) {
                familiarIndex = addNext(familiar, familiarIndex)
            }
            repeat(2) {
                if (discoveryIndex < discoveries.size) {
                    discoveryIndex = addNext(discoveries, discoveryIndex)
                }
            }
        }
        return result.take(limit)
    }

    private fun String.isSafeDjNarration(): Boolean {
        val unsafeContent = Regex(
            """(?i)(https?://|www\.|\b(?:play|pause|skip|open|tap|click)\s+(?:this\s+)?(?:url|link|track|song|playlist|mix)\b)""",
        )
        return !unsafeContent.containsMatchIn(this)
    }

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return DjViewModel(context.applicationContext) as T
            }
        }
    }
}