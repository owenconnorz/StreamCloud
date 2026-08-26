package com.streamcloud.app.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.FollowedArtistDao
import com.streamcloud.app.data.library.FollowedArtistEntity
import com.streamcloud.app.data.library.TrackDao
import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.lyrics.LrcEntry
import com.streamcloud.app.data.lyrics.LyricsRepository
import com.streamcloud.app.data.newpipe.MusicSearchSections
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.newpipe.YtAlbum
import com.streamcloud.app.data.newpipe.YtArtist
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.data.ytmusic.YtMusicHomeFeed
import com.streamcloud.app.data.ytmusic.YtMusicHomeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SearchMode { All, Songs, Videos, Albums, Artists }

data class MusicState(
    val tracks: List<YtTrack> = emptyList(),
    val homeFeed: List<YtTrack> = emptyList(),
    val loading: Boolean = false,
    val homeLoading: Boolean = false,
    val homeFeedFailure: String? = null,
    val error: String? = null,
    val nowPlayingUrl: String? = null,
    val nowPlayingTrack: YtTrack? = null,
    val resolvingUrl: String? = null,


    val lyrics: LrcEntry? = null,
    val lyricsLoading: Boolean = false,


    val sleepTimerEndTs: Long? = null,
    val sleepTimerRemainingMs: Long = 0,


    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,


    val recent: List<TrackEntity> = emptyList(),
    val liked: List<TrackEntity> = emptyList(),
    val mostPlayed: List<TrackEntity> = emptyList(),
    val isCurrentLiked: Boolean = false,


    val searchQuery: String = "",
    val searchMode: SearchMode = SearchMode.All,
    val sections: MusicSearchSections = MusicSearchSections(),
    val albumResults: List<YtAlbum> = emptyList(),
    val artistResults: List<YtArtist> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val suggestionsLoading: Boolean = false,


    val ytHome: YtMusicHomeFeed = YtMusicHomeFeed(),
    val ytHomeLoading: Boolean = false,

    val followedArtists: List<FollowedArtistEntity> = emptyList(),
)

class MusicViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private val dao: TrackDao = LibraryDb.get(context).tracks()
    private val followedArtistsDao: FollowedArtistDao = LibraryDb.get(context).followedArtists()
    private val settings = com.streamcloud.app.data.ServiceLocator.get(context).settings
    private var sleepJob: Job? = null
    private var ytHomeJob: Job? = null
    private var homeFeedJob: Job? = null

    private fun primeTracks(tracks: Iterable<YtTrack>) {
        com.streamcloud.app.data.ytmusic.YtMusicStreamResolver.prime(
            tracks
                .asSequence()
                .map { it.videoId() }
                .filter(String::isNotBlank)
                .toList(),
        )
    }

    private fun primeLibraryTracks(tracks: Iterable<TrackEntity>) {
        primeTracks(
            tracks.map {
                YtTrack(
                    title = it.title,
                    uploader = it.artist,
                    durationSec = it.durationSec,
                    url = it.url,
                    thumbnail = it.thumbnail,
                )
            },
        )
    }

    init {
        // Metrolist keeps a long-lived player connection. Establish StreamCloud's controller as
        // soon as the music surface exists so a first tap does not also have to create the
        // MediaLibraryService, ExoPlayer, session, and controller connection.
        viewModelScope.launch {
            runCatching { MusicController.get(appContext) }
                .onFailure { Log.w("MusicViewModel", "Music controller preconnect failed", it) }
        }
        loadHomeFeed()
        loadYtHome()
        viewModelScope.launch {
            dao.recent().collect {
                list ->
                _state.update { it.copy(recent = list) }
                primeLibraryTracks(list)
            }
        }
        viewModelScope.launch {
            dao.liked().collect {
                list ->
                _state.update { it.copy(liked = list) }
                primeLibraryTracks(list)
            }
        }
        viewModelScope.launch {
            dao.mostPlayed().collect {
                list ->
                _state.update { it.copy(mostPlayed = list) }
                primeLibraryTracks(list)
            }
        }
        viewModelScope.launch {
            followedArtistsDao.all().collect { list -> _state.update { it.copy(followedArtists = list) } }
        }

        viewModelScope.launch {
            com.streamcloud.app.data.ServiceLocator.get(appContext).settings.ytMusicCookie
                .collect { _ -> loadYtHome() }
        }
    }

    fun loadYtHome() {
        ytHomeJob?.cancel()
        ytHomeJob = viewModelScope.launch {
            _state.update { it.copy(ytHomeLoading = true) }
            val feed = try {
                val cookie = com.streamcloud.app.data.ServiceLocator.get(appContext)
                    .settings.ytMusicCookie.first()
                YtMusicHomeRepository.load(cookie)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w("MusicViewModel", "loadYtHome failed: ${error.message}", error)
                YtMusicHomeFeed(
                    failureReason = error.message ?: "Couldn't connect to YouTube Music.",
                )
            }
            if (!isActive) return@launch
            _state.update { current ->
                // Do not erase a feed the user can still browse when a background refresh fails.
                val keepExistingFeed = feed.sections.isEmpty() &&
                    feed.failureReason != null &&
                    current.ytHome.sections.isNotEmpty()
                current.copy(
                    ytHome = if (keepExistingFeed) current.ytHome else feed,
                    ytHomeLoading = false,
                )
            }
            // Resolve the first visible songs while the user browses, not after the tap.
            // The resolver shares its cache with the playback service, so this turns common
            // home-feed starts into a cache hit without delaying the screen render.
            com.streamcloud.app.data.ytmusic.YtMusicStreamResolver.prime(
                feed.sections
                    .asSequence()
                    .filterIsInstance<com.streamcloud.app.data.ytmusic.HomeSection.SongRail>()
                    .flatMap { it.items.asSequence() }
                    .map { it.videoId }
                    .toList(),
            )
        }
    }

    fun loadHomeFeed() {
        homeFeedJob?.cancel()
        homeFeedJob = viewModelScope.launch {
            _state.update { it.copy(homeLoading = true, homeFeedFailure = null) }
            try {
                val feed = NewPipeRepository.homeFeed()
                if (!isActive) return@launch
                _state.update {
                    it.copy(
                        homeFeed = feed,
                        homeLoading = false,
                        homeFeedFailure = if (feed.isEmpty()) {
                            "No songs were returned by the YouTube fallback."
                        } else {
                            null
                        },
                    )
                }
                primeTracks(feed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MusicViewModel", "loadHomeFeed failed: ${e.message}", e)
                if (!isActive) return@launch
                _state.update {
                    it.copy(
                        homeLoading = false,
                        homeFeedFailure = e.message ?: "Couldn't load the YouTube fallback.",
                    )
                }
            }
        }
    }

    /** Re-runs both independent home sources for touch refresh and TV retry actions. */
    fun reloadHome() {
        loadYtHome()
        loadHomeFeed()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun setSearchMode(mode: SearchMode) {
        if (_state.value.searchMode == mode) return
        _state.update { it.copy(searchMode = mode) }
        val q = _state.value.searchQuery
        if (q.length >= 2) search(q)
    }

    private var suggestionsJob: Job? = null

    fun fetchSuggestions(query: String) {
        suggestionsJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(suggestions = emptyList(), suggestionsLoading = false) }
            return
        }
        suggestionsJob = viewModelScope.launch {
            _state.update { it.copy(suggestionsLoading = true) }
            val list = runCatching { NewPipeRepository.searchSuggestions(query) }.getOrDefault(emptyList())
            _state.update { it.copy(suggestions = list, suggestionsLoading = false) }
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, searchQuery = query) }
            try {
                when (_state.value.searchMode) {
                    SearchMode.All -> {
                        val sections = NewPipeRepository.searchAll(query)
                        _state.update {
                            it.copy(
                                sections = sections,
                                tracks = sections.songs,
                                albumResults = sections.albums,
                                artistResults = sections.artists,
                                loading = false,
                            )
                        }
                    }
                    SearchMode.Songs -> {
                        val songs = NewPipeRepository.searchSongs(query)
                        _state.update {
                            it.copy(
                                tracks = songs,
                                sections = MusicSearchSections(songs = songs),
                                albumResults = emptyList(),
                                artistResults = emptyList(),
                                loading = false,
                            )
                        }
                    }
                    SearchMode.Videos -> {
                        val videos = NewPipeRepository.searchVideos(query)
                        _state.update {
                            it.copy(
                                tracks = videos,
                                sections = MusicSearchSections(videos = videos),
                                albumResults = emptyList(),
                                artistResults = emptyList(),
                                loading = false,
                            )
                        }
                    }
                    SearchMode.Albums -> {
                        val albums = NewPipeRepository.searchAlbums(query)
                        _state.update {
                            it.copy(
                                tracks = emptyList(),
                                sections = MusicSearchSections(albums = albums),
                                albumResults = albums,
                                artistResults = emptyList(),
                                loading = false,
                            )
                        }
                    }
                    SearchMode.Artists -> {
                        val artists = NewPipeRepository.searchArtists(query)
                        _state.update {
                            it.copy(
                                tracks = emptyList(),
                                sections = MusicSearchSections(artists = artists),
                                albumResults = emptyList(),
                                artistResults = artists,
                                loading = false,
                            )
                        }
                    }
                }
                // Search rows are immediately tappable. Start their stream resolution as soon as
                // results render so a user tap consumes a warm cache entry instead of starting a
                // player-client request from scratch.
                primeTracks(_state.value.tracks)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Search failed: ${e.message}") }
            }
        }
    }

    fun play(track: YtTrack, onResolved: (String) -> Unit = {}) {
        play(listOf(track), 0, onResolved)
    }

    /**
     * Starts [tracks] as the active playback queue at [startIndex].
     *
     * Keeping the source list here means the player can move both backward and forward from
     * history, search, artist, home, and library rows instead of replacing the queue with a
     * single media item.
     */
    fun play(
        tracks: List<YtTrack>,
        startIndex: Int,
        onResolved: (String) -> Unit = {},
    ) {
        val selected = tracks.getOrNull(startIndex) ?: return
        val queue = tracks
        val queueStartIndex = startIndex

        viewModelScope.launch {
            _state.update { it.copy(resolvingUrl = selected.url, error = null) }
            try {
                val selectedSong = selected.toYtmSong()
                // Start network resolution before the legacy-local-file Room lookup below.
                // The playback service consumes this same in-flight request/cache entry.
                com.streamcloud.app.data.ytmusic.YtMusicStreamResolver
                    .primeForPlayback(selectedSong.videoId)
                val cached = dao.byUrl(selected.url)?.localPath?.takeIf {
                    java.io.File(it).exists()
                }

                _state.update {
                    it.copy(
                        nowPlayingUrl = selected.url,
                        nowPlayingTrack = selected,
                        resolvingUrl = null,
                    )
                }

                if (cached != null && queue.size == 1) {
                    onResolved(cached)
                } else {
                    val songs = queue.map { it.toYtmSong() }
                    if (songs.size == 1) {
                        com.streamcloud.app.data.ytmusic.YtPlayback.playSong(
                            appContext, selectedSong, withAutoRadio = false,
                        )
                    } else {
                        com.streamcloud.app.data.ytmusic.YtPlayback.playPlaylist(
                            appContext, songs, queueStartIndex,
                        )
                    }
                }

                val ts = System.currentTimeMillis()
                dao.upsert(
                    TrackEntity(
                        url = selected.url, title = selected.title, artist = selected.uploader,
                        durationSec = selected.durationSec, thumbnail = selected.thumbnail,
                        localPath = cached,
                    )
                )
                dao.bumpPlayed(selected.url, ts)
                fetchLyrics(selected)
                refreshLikedFlag(selected.url)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        resolvingUrl = null,
                        error = "Playback failed: ${e.message ?: e::class.simpleName}",
                    )
                }
            }
        }
    }

    private fun YtTrack.videoId(): String =
        url
            .substringAfter("v=", missingDelimiterValue = "")
            .substringBefore("&")
            .ifBlank { url.substringAfterLast("/") }

    private fun YtTrack.toYtmSong(): com.streamcloud.app.data.ytmusic.YtmSong {
        return com.streamcloud.app.data.ytmusic.YtmSong(
            videoId = videoId(),
            title = title,
            artist = uploader,
            album = null,
            thumbnail = thumbnail,
            durationSeconds = durationSec,
            isVideo = isVideo,
        )
    }

    private fun fetchLyrics(track: YtTrack) {
        _state.update { it.copy(lyricsLoading = true, lyrics = null) }
        viewModelScope.launch {
            val lrc = runCatching {
                LyricsRepository.fetch(track.title, track.uploader, track.durationSec)
            }.getOrNull()
            _state.update { it.copy(lyrics = lrc, lyricsLoading = false) }
        }
    }


    fun toggleLikeCurrent() {
        val url = _state.value.nowPlayingUrl ?: return
        val currentlyLiked = _state.value.isCurrentLiked
        viewModelScope.launch {
            dao.setLikedAt(url, if (currentlyLiked) null else System.currentTimeMillis())
            _state.update { it.copy(isCurrentLiked = !currentlyLiked) }
            val videoId = url.substringAfter("v=").substringBefore("&")
                .takeIf { it.isNotBlank() } ?: return@launch
            val cookie = settings.ytMusicCookie.first()
            if (currentlyLiked) com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository.unlikeSong(cookie, videoId)
            else com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository.likeSong(cookie, videoId)
        }
    }

    private fun refreshLikedFlag(url: String) {
        viewModelScope.launch {
            dao.isLiked(url).collect { liked ->
                _state.update { it.copy(isCurrentLiked = liked == true) }
            }
        }
    }


    fun startSleepTimer(minutes: Int, onElapsed: () -> Unit) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepTimerEndTs = null, sleepTimerRemainingMs = 0) }
            return
        }
        val endTs = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepTimerEndTs = endTs, sleepTimerRemainingMs = endTs - System.currentTimeMillis()) }
        sleepJob = viewModelScope.launch {
            while (true) {
                val remaining = endTs - System.currentTimeMillis()
                if (remaining <= 0) {
                    _state.update { it.copy(sleepTimerEndTs = null, sleepTimerRemainingMs = 0) }
                    onElapsed()
                    return@launch
                }
                _state.update { it.copy(sleepTimerRemainingMs = remaining) }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        _state.update { it.copy(sleepTimerEndTs = null, sleepTimerRemainingMs = 0) }
    }


    fun setRepeatMode(mode: Int) { _state.update { it.copy(repeatMode = mode) } }
    fun setShuffle(enabled: Boolean) { _state.update { it.copy(shuffleEnabled = enabled) } }

    // ── Follow artists ────────────────────────────────────────────────────────

    fun followArtist(channelId: String, name: String, thumbnail: String?, subscriberLabel: String?) {
        viewModelScope.launch {
            followedArtistsDao.follow(
                FollowedArtistEntity(
                    channelId       = channelId,
                    name            = name,
                    thumbnail       = thumbnail,
                    subscriberLabel = subscriberLabel,
                )
            )
        }
    }

    fun unfollowArtist(channelId: String) {
        viewModelScope.launch { followedArtistsDao.unfollow(channelId) }
    }

    /** Returns a [kotlinx.coroutines.flow.Flow] that emits whether [channelId] is followed. */
    fun isArtistFollowed(channelId: String) = followedArtistsDao.isFollowed(channelId)

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MusicViewModel(context.applicationContext) as T
            }
        }
    }
}
