package com.aioweb.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.aioweb.app.data.library.LibraryDb
import com.aioweb.app.data.library.TrackDao
import com.aioweb.app.data.library.TrackEntity
import com.aioweb.app.data.lyrics.LrcEntry
import com.aioweb.app.data.lyrics.LyricsRepository
import com.aioweb.app.data.newpipe.MusicSearchSections
import com.aioweb.app.data.newpipe.NewPipeRepository
import com.aioweb.app.data.newpipe.YtAlbum
import com.aioweb.app.data.newpipe.YtArtist
import com.aioweb.app.data.newpipe.YtTrack
import com.aioweb.app.data.ytmusic.YtMusicHomeFeed
import com.aioweb.app.data.ytmusic.YtMusicHomeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Active search mode — drives the chip row in MusicScreen. */
enum class SearchMode { All, Songs, Videos, Albums, Artists }

data class MusicState(
    val tracks: List<YtTrack> = emptyList(),
    val homeFeed: List<YtTrack> = emptyList(),
    val loading: Boolean = false,
    val homeLoading: Boolean = false,
    val error: String? = null,
    val nowPlayingUrl: String? = null,
    val nowPlayingTrack: YtTrack? = null,
    val resolvingUrl: String? = null,

    // Lyrics
    val lyrics: LrcEntry? = null,
    val lyricsLoading: Boolean = false,

    // Sleep timer
    val sleepTimerEndTs: Long? = null,
    val sleepTimerRemainingMs: Long = 0,

    // Repeat / Shuffle (mirrored from MediaController)
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,

    // Library
    val recent: List<TrackEntity> = emptyList(),
    val liked: List<TrackEntity> = emptyList(),
    val mostPlayed: List<TrackEntity> = emptyList(),
    val isCurrentLiked: Boolean = false,

    // ── Metrolist-style sectioned search ─────────────────────────────────────
    val searchQuery: String = "",
    val searchMode: SearchMode = SearchMode.All,
    val sections: MusicSearchSections = MusicSearchSections(),
    val albumResults: List<YtAlbum> = emptyList(),
    val artistResults: List<YtArtist> = emptyList(),

    // ── Metrolist-style home feed (YouTube Music personalised sections) ──────
    val ytHome: YtMusicHomeFeed = YtMusicHomeFeed(),
    val ytHomeLoading: Boolean = false,
)

class MusicViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private val dao: TrackDao = LibraryDb.get(context).tracks()
    private var sleepJob: Job? = null

    init {
        loadHomeFeed()
        loadYtHome()
        viewModelScope.launch {
            dao.recent().collect { list -> _state.update { it.copy(recent = list) } }
        }
        viewModelScope.launch {
            dao.liked().collect { list -> _state.update { it.copy(liked = list) } }
        }
        viewModelScope.launch {
            dao.mostPlayed().collect { list -> _state.update { it.copy(mostPlayed = list) } }
        }
        // Reload the YT Music home feed whenever the login cookie changes (sign in / out).
        viewModelScope.launch {
            com.aioweb.app.data.ServiceLocator.get(appContext).settings.ytMusicCookie
                .collect { _ -> loadYtHome() }
        }
    }

    fun loadYtHome() {
        viewModelScope.launch {
            _state.update { it.copy(ytHomeLoading = true) }
            val cookie = com.aioweb.app.data.ServiceLocator.get(appContext)
                .settings.ytMusicCookie.first()
            val feed = YtMusicHomeRepository.load(cookie)
            _state.update { it.copy(ytHome = feed, ytHomeLoading = false) }
        }
    }

    fun loadHomeFeed() {
        viewModelScope.launch {
            _state.update { it.copy(homeLoading = true) }
            try {
                val feed = NewPipeRepository.homeFeed()
                _state.update { it.copy(homeFeed = feed, homeLoading = false) }
            } catch (_: Exception) {
                _state.update { it.copy(homeLoading = false) }
            }
        }
    }

    fun setSearchMode(mode: SearchMode) {
        if (_state.value.searchMode == mode) return
        _state.update { it.copy(searchMode = mode) }
        val q = _state.value.searchQuery
        if (q.length >= 2) search(q)
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
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Search failed: ${e.message}") }
            }
        }
    }

    fun play(track: YtTrack, onResolved: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(resolvingUrl = track.url, error = null) }
            try {
                // Prefer offline copy when available (Metrolist parity).
                val cached = dao.byUrl(track.url)?.localPath?.takeIf {
                    java.io.File(it).exists()
                }
                val audio = cached ?: NewPipeRepository.resolveAudioStream(track.url)
                _state.update {
                    it.copy(
                        nowPlayingUrl = track.url,
                        nowPlayingTrack = track,
                        resolvingUrl = null,
                    )
                }
                onResolved(audio)
                // Library: persist + bump play count
                val ts = System.currentTimeMillis()
                dao.upsert(
                    TrackEntity(
                        url = track.url, title = track.title, artist = track.uploader,
                        durationSec = track.durationSec, thumbnail = track.thumbnail,
                        localPath = cached,
                    )
                )
                dao.bumpPlayed(track.url, ts)
                fetchLyrics(track)
                refreshLikedFlag(track.url)
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

    private fun fetchLyrics(track: YtTrack) {
        _state.update { it.copy(lyricsLoading = true, lyrics = null) }
        viewModelScope.launch {
            val lrc = runCatching {
                LyricsRepository.fetch(track.title, track.uploader, track.durationSec)
            }.getOrNull()
            _state.update { it.copy(lyrics = lrc, lyricsLoading = false) }
        }
    }

    // ---- Like / Unlike --------------------------------------------------------------------
    fun toggleLikeCurrent() {
        val url = _state.value.nowPlayingUrl ?: return
        viewModelScope.launch {
            val currentlyLiked = _state.value.isCurrentLiked
            dao.setLikedAt(url, if (currentlyLiked) null else System.currentTimeMillis())
            _state.update { it.copy(isCurrentLiked = !currentlyLiked) }
        }
    }

    private fun refreshLikedFlag(url: String) {
        viewModelScope.launch {
            dao.isLiked(url).collect { liked ->
                _state.update { it.copy(isCurrentLiked = liked == true) }
            }
        }
    }

    // ---- Sleep timer ---------------------------------------------------------------------
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

    // ---- Repeat / Shuffle mirroring (called by Composable when controller events fire) ---
    fun setRepeatMode(mode: Int) { _state.update { it.copy(repeatMode = mode) } }
    fun setShuffle(enabled: Boolean) { _state.update { it.copy(shuffleEnabled = enabled) } }

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MusicViewModel(context.applicationContext) as T
            }
        }
    }
}
