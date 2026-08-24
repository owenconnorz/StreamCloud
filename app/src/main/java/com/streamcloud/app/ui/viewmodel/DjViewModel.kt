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
import com.streamcloud.app.data.ytmusic.HomeSection
import com.streamcloud.app.data.ytmusic.YtMusicHomeRepository
import com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class DjSession(
    val request: String,
    val narration: String,
    val tracks: List<YtTrack>,
    val isPersonalized: Boolean = false,
    val sourceDescription: String = "",
)

data class DjUiState(
    val loading: Boolean = false,
    val session: DjSession? = null,
    val error: String? = null,
)

private const val MAX_DJ_SEEDS = 10
private const val MAX_DJ_DISCOVERY_QUERIES = 10
private const val MAX_DJ_ONLINE_COLLECTIONS = 4
private const val DJ_SOURCE_TIMEOUT_MS = 12_000L
private const val DJ_SEARCH_CONCURRENCY = 3
private const val DJ_COLLECTION_CONCURRENCY = 2

private data class DjOnlineSignals(
    val likedTracks: List<YtTrack> = emptyList(),
    val collectionTracks: List<YtTrack> = emptyList(),
    val homeTracks: List<YtTrack> = emptyList(),
    val discoveryQueries: List<String> = emptyList(),
)

internal fun djTrackKey(track: YtTrack): String {
    val videoId = track.url
        .substringAfter("v=", missingDelimiterValue = "")
        .substringBefore("&")
        .takeIf { it.length == 11 }
    return videoId ?: track.url.trim().lowercase()
}

internal fun distinctDjTracks(tracks: Iterable<YtTrack>): List<YtTrack> =
    tracks.filter { it.url.isNotBlank() }.distinctBy(::djTrackKey)

internal fun buildDjSeedTracks(
    localTracks: List<YtTrack>,
    onlineLikedTracks: List<YtTrack>,
    onlineCollectionTracks: List<YtTrack>,
    onlineHomeTracks: List<YtTrack>,
): List<YtTrack> = distinctDjTracks(
    localTracks.take(4) +
        onlineLikedTracks.take(3) +
        onlineCollectionTracks.take(2) +
        onlineHomeTracks.take(1) +
        localTracks.drop(4) +
        onlineLikedTracks.drop(3) +
        onlineCollectionTracks.drop(2) +
        onlineHomeTracks.drop(1),
).take(MAX_DJ_SEEDS)

internal fun buildDjDiscoveryQueries(
    seedTracks: List<YtTrack>,
    searchHistory: List<String>,
    onlineQueries: List<String> = emptyList(),
): List<String> {
    val localQueries = buildList {
    seedTracks.take(5).forEach { seed ->
        if (seed.uploader.isNotBlank()) add("${seed.uploader} similar songs")
        if (seed.title.isNotBlank()) add("${seed.title} ${seed.uploader}")
    }
    addAll(searchHistory.take(3))
    }
    // Reserve more than half of the request budget for online-library and home-feed
    // sources so a long local history cannot silently crowd out fresh discovery.
    return (onlineQueries.take(6) + localQueries)
        .map(String::trim)
        .filter { it.length >= 2 }
        .distinctBy(String::lowercase)
        .take(MAX_DJ_DISCOVERY_QUERIES)
}

private fun YtmSong.toDjTrack(): YtTrack = YtTrack(
    title = title,
    uploader = artist,
    durationSec = durationSeconds ?: 0L,
    url = YtPlayback.watchUrl(videoId),
    thumbnail = thumbnail,
    isVideo = isVideo,
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
    private var personalizedMixJob: Job? = null

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
        personalizedMixJob?.cancel()
        _state.update { it.copy(loading = true, error = null, session = null) }
        personalizedMixJob = viewModelScope.launch {
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
                val onlineSignals = loadOnlineSignals(
                    serviceLocator.settings.ytMusicCookie.first().trim(),
                )

                val localSeeds = buildList {
                    addAll(liked)
                    addAll(mostPlayed)
                    addAll(recent)
                }.distinctBy { it.url }
                val localSeedTracks = localSeeds.map { entity ->
                    YtTrack(
                        title = entity.title,
                        uploader = entity.artist,
                        durationSec = entity.durationSec,
                        url = entity.url,
                        thumbnail = entity.thumbnail,
                    )
                }
                // Give each source a dependable place in the mix before using the remainder
                // to enrich it. This stops a large local history from starving online tastes.
                val seedTracks = buildDjSeedTracks(
                    localTracks = localSeedTracks,
                    onlineLikedTracks = onlineSignals.likedTracks,
                    onlineCollectionTracks = onlineSignals.collectionTracks,
                    onlineHomeTracks = onlineSignals.homeTracks,
                )
                val discoveryQueries = buildDjDiscoveryQueries(
                    seedTracks = seedTracks,
                    searchHistory = searchHistory,
                    onlineQueries = onlineSignals.discoveryQueries,
                )

                if (seedTracks.isEmpty() && discoveryQueries.isEmpty()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "Play or like a few songs, or connect YouTube Music, then the DJ can make a mix around your taste.",
                        )
                    }
                    onComplete(null)
                    return@launch
                }

                val searchGate = Semaphore(DJ_SEARCH_CONCURRENCY)
                val discoveredTracks = coroutineScope {
                    discoveryQueries.map { query ->
                        async {
                            searchGate.withPermit {
                                tryDjSource { NewPipeRepository.searchSongs(query) }
                                    .orEmpty()
                            }
                                .take(8)
                        }
                    }.awaitAll().flatten()
                }
                // Keep a few recognisable favourites, then favour fresh results before
                // filling remaining places from the wider source set.
                val tracks = distinctDjTracks(
                    seedTracks.take(5) +
                        distinctDjTracks(discoveredTracks).filterNot { discovered ->
                            seedTracks.any { seed -> djTrackKey(seed) == djTrackKey(discovered) }
                        } +
                        seedTracks.drop(5),
                )
                    .take(15)
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
                    if (tracks.any { it.matchesAny(onlineSignals.likedTracks) }) {
                        add("YouTube Music likes")
                    }
                    if (tracks.any { it.matchesAny(onlineSignals.collectionTracks) }) {
                        add("online playlists and albums")
                    }
                    if (tracks.any { it.matchesAny(onlineSignals.homeTracks) }) {
                        add("your YouTube Music home feed")
                    }
                }
                val sourceDescription = if (signalParts.isEmpty()) {
                    "Song searches are used only to find this mix."
                } else {
                    "Built from ${signalParts.joinToString(", ")}. Your local listening stays on this device; online sources are read directly by the app and only derived searches find fresh tracks."
                }
                // Personalized track metadata is private listening data. Keep this narration local
                // instead of sending a seed track to the optional remote AI backend.
                val narration = buildFallbackNarration("your listening", tracks)
                val session = DjSession(
                    request = "For you",
                    narration = narration,
                    tracks = tracks,
                    isPersonalized = true,
                    sourceDescription = sourceDescription,
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

    private fun YtTrack.matchesAny(otherTracks: List<YtTrack>): Boolean =
        otherTracks.any { djTrackKey(it) == djTrackKey(this) }

    private suspend fun <T> tryDjSource(block: suspend () -> T): T? = try {
        // A source-specific timeout is a normal partial-result case. External ViewModel
        // cancellation still propagates through the catch block below.
        withTimeoutOrNull(DJ_SOURCE_TIMEOUT_MS) { block() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /**
     * Reads only the sources already available to the app through the signed-in YouTube Music
     * session. Results are deliberately capped: this is a taste sampler, not a full library sync
     * on every DJ request. Nothing from these sources is sent to the narration backend.
     */
    private suspend fun loadOnlineSignals(cookie: String): DjOnlineSignals {
        if (cookie.isBlank()) return DjOnlineSignals()

        val (library, homeFeed) = coroutineScope {
            val libraryJob = async {
                tryDjSource { YtMusicLibraryRepository.sync(cookie) }
            }
            val homeJob = async {
                tryDjSource { YtMusicHomeRepository.load(cookie) }
            }
            libraryJob.await() to homeJob.await()
        }

        val collections = library
            ?.let { it.playlists + it.albums }
            .orEmpty()
            .filterNot { it.isVideo }
            .distinctBy { it.id }
            .take(MAX_DJ_ONLINE_COLLECTIONS)
        val collectionGate = Semaphore(DJ_COLLECTION_CONCURRENCY)
        val collectionSongs = coroutineScope {
            collections.map { collection ->
                async {
                    collectionGate.withPermit {
                        tryDjSource {
                        YtMusicLibraryRepository.playlistTracks(
                            cookie = cookie,
                            playlistId = collection.id,
                            externalThumb = collection.thumbnail,
                        ).take(10)
                        }.orEmpty()
                    }
                }
            }.awaitAll().flatten()
        }
        val homeSongs = homeFeed?.sections.orEmpty()
            .filterIsInstance<HomeSection.SongRail>()
            .flatMap { it.items.take(5) }
        val homePlaylistTitles = homeFeed?.sections.orEmpty()
            .filterIsInstance<HomeSection.PlaylistRail>()
            .flatMap { it.items.take(3) }
            .map { it.title }
            .orEmpty()
        val artistQueries = library?.artists.orEmpty().take(2).map { artist ->
            "${artist.name} popular songs"
        }
        val collectionQueries = collections.take(2).map { collection ->
            "${collection.title} similar music"
        }
        val homeQueries = homePlaylistTitles.take(2).map { title ->
            "$title music"
        }
        val onlineQueries = buildList {
            val queryGroups = listOf(artistQueries, collectionQueries, homeQueries)
            repeat(2) { index ->
                queryGroups.forEach { group ->
                    group.getOrNull(index)?.let(::add)
                }
            }
        }
        return DjOnlineSignals(
            likedTracks = distinctDjTracks(library?.likedSongs.orEmpty().map(YtmSong::toDjTrack)),
            collectionTracks = distinctDjTracks(collectionSongs.map(YtmSong::toDjTrack)),
            homeTracks = distinctDjTracks(homeSongs.map(YtmSong::toDjTrack)),
            discoveryQueries = onlineQueries,
        )
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
        return "Here is a $request mix to get you started. Opening with ${first.title} by ${first.uploader}."
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