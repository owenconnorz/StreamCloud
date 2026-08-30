package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import coil.compose.AsyncImage
import com.streamcloud.app.audio.PlaybackBus
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.audio.DjNarrator
import com.streamcloud.app.audio.DjVoicePreset
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.ytmusic.HomeSection
import com.streamcloud.app.data.ytmusic.MoodChip
import com.streamcloud.app.ui.viewmodel.DjSession
import com.streamcloud.app.ui.viewmodel.DjViewModel
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvDpadRepeatThrottle
import com.streamcloud.app.ui.theme.tvFocusGroup
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.viewmodel.MusicViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

internal fun musicMediaIdsMatch(first: String?, second: String?): Boolean {
    if (first.isNullOrBlank() || second.isNullOrBlank()) return false
    if (first == second) return true
    return musicVideoId(first) != null && musicVideoId(first) == musicVideoId(second)
}

private fun musicVideoId(value: String): String? {
    val trimmed = value.trim()
    Uri.parse(trimmed).getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let { return it }
    Regex("""(?:[?&]v=|youtu\.be/|/(?:shorts|embed)/)([A-Za-z0-9_-]{11})""")
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    return trimmed.takeIf { it.matches(Regex("""[A-Za-z0-9_-]{11}""")) }
}

private val SUGGESTIONS = listOf(
    "Top hits 2026", "Lo-fi beats", "Chill", "Workout",
    "Throwback", "K-pop", "Hip hop", "Jazz", "EDM", "Acoustic"
)

private val DEFAULT_MUSIC_QUICK_CHIPS = listOf(
    "Podcast", "Relax", "Workout", "Focus", "Sleep", "Party", "Chill",
).map { MoodChip(label = it, params = null) }

internal fun buildMusicQuickChips(remoteChips: List<MoodChip>): List<MoodChip> =
    (remoteChips + DEFAULT_MUSIC_QUICK_CHIPS)
        .filter { it.label.isNotBlank() }
        .distinctBy { it.label.trim().lowercase() }
        .take(8)

internal fun buildCombinedMusicSuggestions(quickChips: List<MoodChip>): List<String> =
    (quickChips.map { it.label } + SUGGESTIONS)
        .filter { it.isNotBlank() }
        .distinctBy { it.trim().lowercase() }

private const val DJ_ANNOUNCEMENT_INTERVAL = 2

private data class PendingDjAnnouncement(
    val session: DjSession,
    val announcement: String,
)

private fun YtTrack.matchesDjMediaId(mediaId: String): Boolean {
    if (url == mediaId) return true
    val trackVideoId = url.substringAfter("v=", "").substringBefore("&")
    val mediaVideoId = mediaId.substringAfter("v=", "").substringBefore("&")
    return trackVideoId.length == 11 && trackVideoId == mediaVideoId
}

private fun buildDjFollowUpAnnouncement(
    previousTrack: YtTrack,
    currentTrack: YtTrack,
    announcementNumber: Int,
): String {
    val previous = "${previousTrack.title} by ${previousTrack.uploader}"
    val current = "${currentTrack.title} by ${currentTrack.uploader}"
    return when ((announcementNumber - 1) % 3) {
        0 -> "That was $previous. Coming up next is $current."
        1 -> "You are settling into the mix nicely. Up next, $current."
        else -> "Keeping the mood moving with $current, following $previous."
    }
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun MusicScreen(
    onArtistClick: (url: String, thumbnail: String?) -> Unit = { _, _ -> },
    onOpenPlaylist: (id: String, title: String, thumbnail: String?) -> Unit = { _, _, _ -> },
    onProfileClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSearchWithQuery: (String) -> Unit = {},
    tvNavFocusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val vm: MusicViewModel = viewModel(factory = MusicViewModel.factory(context))
    val state by vm.state.collectAsState()
    val nowPlayingMediaId by PlaybackBus.nowPlayingMediaId.collectAsState()
    val playbackIsPlaying by PlaybackBus.isPlaying.collectAsState()
    var query by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showDj by remember { mutableStateOf(false) }
    var djRequest by remember { mutableStateOf("") }
    var djStarting by remember { mutableStateOf(false) }
    var djQuickMixLoading by remember { mutableStateOf(false) }
    val dlScope = rememberCoroutineScope()
    val settings = remember(context) { ServiceLocator.get(context).settings }
    val djViewModel: DjViewModel = viewModel(factory = DjViewModel.factory(context))
    val djState by djViewModel.state.collectAsState()
    val djVoicePresetName by settings.djVoicePreset.collectAsState(initial = DjVoicePreset.BrightHost.name)
    val djVoicePreset = remember(djVoicePresetName) {
        DjVoicePreset.entries.firstOrNull { it.name == djVoicePresetName } ?: DjVoicePreset.BrightHost
    }
    val djNarrator = remember(context) { DjNarrator(context.applicationContext) }
    val quickChips = remember(state.ytHome.sections) {
        buildMusicQuickChips(
            state.ytHome.sections
                .filterIsInstance<HomeSection.MoodChips>()
                .flatMap { it.chips },
        )
    }
    DisposableEffect(djNarrator) {
        onDispose { djNarrator.close() }
    }



    var player by remember { mutableStateOf<androidx.media3.common.Player?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var activeQueueSignature by remember { mutableStateOf<List<String>?>(null) }
    var activeQueueIndex by remember { mutableIntStateOf(-1) }
    var activeDjSession by remember { mutableStateOf<DjSession?>(null) }
    var djSessionGeneration by remember { mutableIntStateOf(0) }
    var djQueueInstalled by remember { mutableStateOf(false) }
    var djTracksSinceAnnouncement by remember { mutableIntStateOf(0) }
    var djAnnouncementNumber by remember { mutableIntStateOf(0) }
    var lastDjTrack by remember { mutableStateOf<YtTrack?>(null) }
    var djAnnouncementInProgress by remember { mutableStateOf(false) }
    var djPauseCommandPending by remember { mutableStateOf(false) }
    var djResumeAfterAnnouncement by remember { mutableStateOf(false) }
    var pendingDjAnnouncement by remember { mutableStateOf<PendingDjAnnouncement?>(null) }
    val currentDjSession = rememberUpdatedState(activeDjSession)
    val currentDjVoicePreset = rememberUpdatedState(djVoicePreset)

    fun cancelDjAnnouncement() {
        // This also cancels an initial introduction. It is safe when nothing is speaking and
        // prevents a stale TTS completion from replacing playback after the user chooses a track.
        djNarrator.cancel()
        djAnnouncementInProgress = false
        djPauseCommandPending = false
        djResumeAfterAnnouncement = false
        pendingDjAnnouncement = null
    }

    fun endDjSession() {
        cancelDjAnnouncement()
        djStarting = false
        djSessionGeneration += 1
        djQueueInstalled = false
        activeDjSession = null
        djTracksSinceAnnouncement = 0
        lastDjTrack = null
    }

    LaunchedEffect(Unit) {
        try {
            val controller = com.streamcloud.app.audio.MusicController.get(context.applicationContext)
            fun startPendingDjAnnouncement() {
                val pending = pendingDjAnnouncement ?: return
                if (
                    djAnnouncementInProgress ||
                    !controller.playWhenReady ||
                    controller.playbackState != Player.STATE_READY ||
                    currentDjSession.value != pending.session
                ) {
                    return
                }
                pendingDjAnnouncement = null
                val willSpeak = djNarrator.speak(
                    pending.announcement,
                    currentDjVoicePreset.value,
                ) {
                    val shouldResume = djAnnouncementInProgress &&
                        djResumeAfterAnnouncement &&
                        currentDjSession.value == pending.session
                    djAnnouncementInProgress = false
                    djPauseCommandPending = false
                    djResumeAfterAnnouncement = false
                    if (shouldResume) controller.play()
                }
                if (willSpeak) {
                    djAnnouncementInProgress = true
                    djResumeAfterAnnouncement = true
                    djPauseCommandPending = true
                    // Do not let the transition announcement compete with the song.
                    controller.pause()
                }
            }
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    if (!djAnnouncementInProgress) {
                        if (playing) startPendingDjAnnouncement()
                        return
                    }
                    if (!playing && djPauseCommandPending) {
                        djPauseCommandPending = false
                    } else {
                        // A user pause/resume or external interruption takes precedence over
                        // the DJ's temporary pause, so never restart playback afterward.
                        cancelDjAnnouncement()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) startPendingDjAnnouncement()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && !djAnnouncementInProgress) {
                        // Do not defer a queued DJ interruption until after a user pause.
                        pendingDjAnnouncement = null
                    }
                }

                override fun onTimelineChanged(
                    timeline: androidx.media3.common.Timeline,
                    reason: Int,
                ) {
                    val session = currentDjSession.value ?: return
                    if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                    if (controller.mediaItemCount == 0) {
                        if (djQueueInstalled) endDjSession()
                        return
                    }
                    val isExpectedDjQueue = controller.mediaItemCount == session.tracks.size &&
                        (0 until controller.mediaItemCount).all { index ->
                            session.tracks[index].matchesDjMediaId(
                                controller.getMediaItemAt(index).mediaId,
                            )
                        }
                    if (isExpectedDjQueue) {
                        // Starting a mix is asynchronous. Ignore the outgoing queue until the
                        // controller has actually accepted the DJ media items.
                        djQueueInstalled = true
                    } else if (djQueueInstalled) {
                        endDjSession()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val session = currentDjSession.value ?: return
                    if (!djQueueInstalled) return
                    val currentTrack = mediaItem?.mediaId
                        ?.let { mediaId -> session.tracks.firstOrNull { it.matchesDjMediaId(mediaId) } }
                        ?: run {
                            endDjSession()
                            return
                        }

                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        cancelDjAnnouncement()
                        djTracksSinceAnnouncement = 0
                        lastDjTrack = currentTrack
                        return
                    }

                    djTracksSinceAnnouncement += 1
                    val previousTrack = lastDjTrack
                    lastDjTrack = currentTrack
                    if (
                        djTracksSinceAnnouncement < DJ_ANNOUNCEMENT_INTERVAL ||
                        previousTrack == null ||
                        djAnnouncementInProgress ||
                        pendingDjAnnouncement != null
                    ) {
                        return
                    }

                    djTracksSinceAnnouncement = 0
                    djAnnouncementNumber += 1
                    pendingDjAnnouncement = PendingDjAnnouncement(
                        session = session,
                        announcement = buildDjFollowUpAnnouncement(
                        previousTrack = previousTrack,
                        currentTrack = currentTrack,
                        announcementNumber = djAnnouncementNumber,
                        ),
                    )
                    startPendingDjAnnouncement()
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val msg = "Audio playback failed (${error.errorCodeName}): ${error.message}"
                    com.streamcloud.app.data.AppLogger.e("MusicPlayback", msg, error.cause)
                    playerError = msg
                }
                override fun onRepeatModeChanged(repeatMode: Int) { vm.setRepeatMode(repeatMode) }
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    vm.setShuffle(shuffleModeEnabled)
                }
            }
            controller.addListener(listener)

            vm.setRepeatMode(controller.repeatMode)
            vm.setShuffle(controller.shuffleModeEnabled)
            player = controller
            isPlaying = controller.isPlaying
            try {
                awaitCancellation()
            } finally {
                controller.removeListener(listener)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            playerError = "Couldn't connect to media service: ${e.message}"
        }
    }



    val nowPlaying = state.nowPlayingTrack
        ?: state.tracks.firstOrNull { it.url == state.nowPlayingUrl }
        ?: state.homeFeed.firstOrNull { it.url == state.nowPlayingUrl }

    fun playFromQueue(track: YtTrack, source: List<YtTrack>, startIndex: Int) {
        val queue = source.ifEmpty { listOf(track) }
        val queueStartIndex = if (source.isEmpty()) 0 else startIndex.coerceIn(queue.indices)
        val queueSignature = queue.map(YtTrack::url)
        val isCurrentQueueItem = state.nowPlayingUrl == track.url &&
            activeQueueSignature == queueSignature &&
            activeQueueIndex == queueStartIndex

        if (isCurrentQueueItem && (player?.isPlaying == true)) {
            player?.pause()
        } else if (isCurrentQueueItem) {
            player?.play()
        } else {
            if (activeDjSession?.tracks?.map(YtTrack::url) != queueSignature) {
                endDjSession()
            }
            activeQueueSignature = queueSignature
            activeQueueIndex = queueStartIndex
            vm.play(queue, queueStartIndex) { audioUrl ->
                player?.let { playTrack(it, track, audioUrl) }
            }
        }
    }

    fun startDjMix(session: DjSession) {
        if (djStarting) return
        val firstTrack = session.tracks.firstOrNull() ?: return
        val sessionGeneration = djSessionGeneration + 1
        djSessionGeneration = sessionGeneration
        activeDjSession = session
        djQueueInstalled = false
        djTracksSinceAnnouncement = 0
        djAnnouncementNumber = 0
        lastDjTrack = firstTrack
        cancelDjAnnouncement()
        djStarting = true
        val startPlayback = {
            if (
                djStarting &&
                djSessionGeneration == sessionGeneration &&
                activeDjSession == session
            ) {
                djStarting = false
                playFromQueue(firstTrack, session.tracks, 0)
                showDj = false
            }
        }
        val willSpeak = djNarrator.speak(session.narration, djVoicePreset, startPlayback)
        if (willSpeak) {
            // A DJ introduction should occur before, rather than over, the selected music.
            player?.pause()
        } else {
            startPlayback()
        }
    }

    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val hasYtHomeContent = state.ytHome.sections.any { section ->
        when (section) {
            is HomeSection.PlaylistRail -> section.items.isNotEmpty()
            is HomeSection.SongRail -> section.items.isNotEmpty()
            is HomeSection.MoodChips -> false
        }
    }
    val hasRemoteHomeContent = hasYtHomeContent || state.homeFeed.isNotEmpty()
    val homeFailureSummary = buildMusicHomeFailureSummary(
        ytMusicFailure = state.ytHome.failureReason,
        fallbackFailure = state.homeFeedFailure,
    )
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(state.ytHomeLoading, state.homeLoading) {
        if (!state.ytHomeLoading && !state.homeLoading) isRefreshing = false
    }

    MusicRefreshBox(
        isTv = isTv,
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            vm.reloadHome()
        },
        state = pullRefreshState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .tvFocusGroup()
                .tvDpadRepeatThrottle(),
            contentPadding = PaddingValues(bottom = if (nowPlaying != null) 180.dp else 80.dp),
        ) {
            item {
                MusicHeader(
                    onProfileClick = onProfileClick,
                    onHistoryClick = { showHistory = true },
                    onSearchClick = onSearchClick,
                    onTrendingClick = { onSearchWithQuery("Top hits 2026") },
                    djLoading = djQuickMixLoading || djStarting,
                    // When no remote music is available, the recovery action below is the
                    // most useful landing point from the TV nav bar.
                    tvNavFocusRequester = if (isTv && !hasRemoteHomeContent) {
                        null
                    } else {
                        tvNavFocusRequester
                    },
                    onDjClick = {
                        if (!djQuickMixLoading && !djStarting) {
                            showDj = true
                            djQuickMixLoading = true
                            djViewModel.buildPersonalizedMix {
                                djQuickMixLoading = false
                            }
                        }
                    },
                    onDjLongClick = {
                        if (!djQuickMixLoading && !djStarting) showDj = true
                    },
                )
            }



            val combinedError = state.error ?: playerError
            if (combinedError != null) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Search,
                            null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            combinedError,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                playerError = null
                                vm.clearError()
                                if (query.isBlank()) vm.reloadHome() else vm.search(query)
                            },
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }


            if (query.isBlank()) {
                item {
                    SuggestionsRow(
                        quickChips = quickChips,
                        onPick = { query = it; vm.search(it) },
                    )
                }


                if (state.liked.isNotEmpty()) {
                    item { SectionTitle("Liked songs") }
                    itemsIndexed(
                        state.liked.take(5),
                        key = { index, entity -> "lib_liked_${index}_${entity.url}" },
                    ) { index, entity ->
                        LibraryRow(entity, isPlaying = isPlaying && state.nowPlayingUrl == entity.url) {
                            val track = YtTrack(
                                title = entity.title, uploader = entity.artist,
                                durationSec = entity.durationSec,
                                url = entity.url, thumbnail = entity.thumbnail,
                            )
                            playFromQueue(
                                track,
                                state.liked.map {
                                    YtTrack(
                                        title = it.title,
                                        uploader = it.artist,
                                        durationSec = it.durationSec,
                                        url = it.url,
                                        thumbnail = it.thumbnail,
                                    )
                                },
                                index,
                            )
                        }
                    }
                }





                state.ytHome.sections.forEachIndexed { idx, section ->
                    when (section) {
                        is HomeSection.MoodChips -> Unit
                        is HomeSection.PlaylistRail -> {
                            item(key = "yt_prail_title_$idx") { SectionTitle(section.title) }
                            item(key = "yt_prail_$idx") {
                                LazyRow(
                                    modifier = Modifier.tvFocusGroup(),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(section.items) { pl ->
                                        YtHomePlaylistCard(pl) {
                                            onOpenPlaylist(pl.id, pl.title, pl.thumbnail)
                                        }
                                    }
                                }
                            }
                        }
                        is HomeSection.SongRail -> {
                            item(key = "yt_srail_title_$idx") { SectionTitle(section.title) }
                            item(key = "yt_srail_${idx}") {
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val cardWidth = ((maxWidth - 68.dp) / 4).coerceAtLeast(76.dp)
                                    LazyRow(
                                        modifier = Modifier.tvFocusGroup(),
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        itemsIndexed(
                                            section.items,
                                            key = { index, s -> "yt_srail_${idx}_${index}_${s.videoId}" },
                                        ) { index, song ->
                                            YtHomeSongCard(
                                                song = song,
                                                queue = section.items,
                                                startIndex = index,
                                                modifier = Modifier.width(cardWidth),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }



                if (!hasYtHomeContent && state.homeFeed.isNotEmpty()) {
                    item { SectionTitle("Trending today") }
                    item {
                        LazyRow(
                            modifier = Modifier.tvFocusGroup(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(
                                state.homeFeed.take(10),
                                key = { index, track -> "home_${index}_${track.url}" },
                            ) { index, track ->
                                HeroCard(
                                    track = track,
                                    isPlaying = isPlaying && state.nowPlayingUrl == track.url,
                                    onClick = {
                                        playFromQueue(track, state.homeFeed, index)
                                    }
                                )
                            }
                        }
                    }
                    item { SectionTitle("More from YouTube") }
                    itemsIndexed(
                        state.homeFeed.drop(10),
                        key = { index, track -> "homerow_${index}_${track.url}" },
                    ) { index, track ->
                        SongRow(
                            track = track,
                            nowPlayingUrl = state.nowPlayingUrl,
                            isPlaying = isPlaying && state.nowPlayingUrl == track.url,
                            loading = state.resolvingUrl == track.url,
                            onClick = {
                                playFromQueue(track, state.homeFeed, index + 10)
                            }
                        )
                    }
                }
                if (!hasRemoteHomeContent) {
                    item {
                        MusicHomeRecoveryState(
                            loading = state.ytHomeLoading || state.homeLoading,
                            failureSummary = homeFailureSummary,
                            onReload = {
                                isRefreshing = true
                                vm.reloadHome()
                            },
                            focusRequester = if (isTv) tvNavFocusRequester else null,
                        )
                    }
                }
            }


            // ── SimpMusic-style: top results + live suggestions ──
            if (query.isNotBlank()) {
                val sections = state.sections
                val topArtist = sections.artists.firstOrNull()
                val topSongs = sections.songs.take(if (topArtist != null) 2 else 3)
                val topVideos = sections.videos.take(if (topSongs.isNotEmpty()) 2 else 3)

                topArtist?.let { artist ->
                    item(key = "sr_artist_${artist.url}") {
                        SearchResultRow(
                            thumbnail = artist.thumbnail,
                            title = artist.name,
                            subtitle = "Artists",
                            isCircle = true,
                            onClick = { onArtistClick(artist.url, artist.thumbnail) },
                        )
                    }
                }

                itemsIndexed(
                    topSongs,
                    key = { index, track -> "sr_song_${index}_${track.url}" },
                ) { index, track ->
                    SearchResultRow(
                        thumbnail = track.thumbnail,
                        title = track.title,
                        subtitle = track.uploader,
                        isCircle = false,
                        isNowPlaying = musicMediaIdsMatch(track.url, nowPlayingMediaId),
                        isPlaying = playbackIsPlaying,
                        onClick = {
                            playFromQueue(track, sections.songs, index)
                        },
                    )
                }

                itemsIndexed(
                    topVideos,
                    key = { index, track -> "sr_video_${index}_${track.url}" },
                ) { index, track ->
                    SearchResultRow(
                        thumbnail = track.thumbnail,
                        title = track.title,
                        subtitle = track.uploader,
                        isCircle = false,
                        isNowPlaying = musicMediaIdsMatch(track.url, nowPlayingMediaId),
                        isPlaying = playbackIsPlaying,
                        onClick = {
                            playFromQueue(track, sections.videos, index)
                        },
                    )
                }

                if (topArtist == null && topSongs.isEmpty()) {
                    sections.albums.firstOrNull()?.let { album ->
                        item(key = "sr_album_${album.url}") {
                            val uri = Uri.parse(album.url)
                            val id = uri.getQueryParameter("list")
                                ?: uri.lastPathSegment?.takeIf { seg -> seg.isNotBlank() }
                                ?: album.url
                            SearchResultRow(
                                thumbnail = album.thumbnail,
                                title = album.title,
                                subtitle = album.artist,
                                isCircle = false,
                                onClick = { onOpenPlaylist(id, album.title, album.thumbnail) },
                            )
                        }
                    }
                }

                items(state.suggestions, key = { "sug_$it" }) { suggestion ->
                    SuggestionListRow(
                        text = suggestion,
                        onClick = { query = suggestion; vm.search(suggestion) },
                    )
                }
            }
            state.error?.let {
                item {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }





        if (showHistory) {
            HistorySheet(
                recent = state.recent,
                nowPlayingUrl = state.nowPlayingUrl,
                isPlaying = isPlaying,
                onPlay = { entity, index ->
                    val track = YtTrack(
                        title = entity.title, uploader = entity.artist,
                        durationSec = entity.durationSec,
                        url = entity.url, thumbnail = entity.thumbnail,
                    )
                    playFromQueue(
                        track,
                        state.recent.map {
                            YtTrack(
                                title = it.title,
                                uploader = it.artist,
                                durationSec = it.durationSec,
                                url = it.url,
                                thumbnail = it.thumbnail,
                            )
                        },
                        index,
                    )
                },
                onDismiss = { showHistory = false },
            )
        }
        if (showDj) {
            DjSheet(
                request = djRequest,
                onRequestChange = { djRequest = it },
                voicePreset = djVoicePreset,
                onVoicePresetChange = { preset ->
                    dlScope.launch { settings.setDjVoicePreset(preset.name) }
                },
                state = djState,
                startingMix = djStarting,
                onBuildMix = { djViewModel.buildMix(djRequest) },
                onPlayMix = ::startDjMix,
                onDismiss = {
                    if (djStarting) {
                        djStarting = false
                        djNarrator.cancel()
                    }
                    showDj = false
                    djViewModel.clearSession()
                },
            )
        }
    }
}

/** Wraps content in a plain Box on TV (no pull-to-refresh gesture) or PullToRefreshBox on phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicRefreshBox(
    isTv: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    state: PullToRefreshState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (isTv) {
        Box(modifier = modifier, content = content)
    } else {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = state,
            modifier = modifier,
            content = content,
        )
    }
}

internal fun buildMusicHomeFailureSummary(
    ytMusicFailure: String?,
    fallbackFailure: String?,
): String? {
    val failures = buildList {
        ytMusicFailure?.takeIf(String::isNotBlank)?.let {
            add("YouTube Music: $it")
        }
        fallbackFailure?.takeIf(String::isNotBlank)?.let {
            add("YouTube fallback: $it")
        }
    }
    return failures.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n")
}

@Composable
private fun MusicHomeRecoveryState(
    loading: Boolean,
    failureSummary: String?,
    onReload: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val visibleFailure = failureSummary.takeUnless { loading }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(38.dp),
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (loading) "Loading music…" else "Music needs a refresh",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            visibleFailure
                ?: "Reload, search, or sign in to YouTube Music on this TV to keep listening.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (visibleFailure != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onReload,
            modifier = Modifier
                .let { base ->
                    if (focusRequester != null) base.focusRequester(focusRequester) else base
                }
                .tvFocusBorder(RoundedCornerShape(24.dp)),
        ) {
            Text("Reload music")
        }
        if (visibleFailure != null) {
            Text(
                "If this is a separate TV device, sign in to YouTube Music here and try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
private fun buildMusicExoPlayer(context: android.content.Context): ExoPlayer {
    val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
    val mediaSourceFactory =
        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply { playWhenReady = true }
}

private fun playTrack(player: androidx.media3.common.Player, track: YtTrack, audioUrl: String) {
    player.setMediaItem(
        MediaItem.Builder()
            .setUri(audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.uploader)
                    .setArtworkUri(track.thumbnail?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()
    )
    player.prepare()
    player.play()
}

@Composable
private fun MusicHeader(
    onProfileClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onTrendingClick: () -> Unit = {},
    djLoading: Boolean = false,
    onDjClick: () -> Unit = {},
    onDjLongClick: () -> Unit = {},
    tvNavFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, top = 8.dp, end = 4.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Music",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            MusicHeaderAction(
                icon = Icons.Default.Search,
                contentDescription = "Search music",
                focusRequester = tvNavFocusRequester,
                onClick = onSearchClick,
            )
            MusicHeaderAction(
                icon = Icons.Default.AutoAwesome,
                contentDescription = "Play a personalized StreamCloud DJ mix; hold for DJ options",
                loading = djLoading,
                onClick = onDjClick,
                onLongClick = onDjLongClick,
            )
            MusicHeaderAction(
                icon = Icons.Default.History,
                contentDescription = "Recently played",
                onClick = onHistoryClick,
            )
            MusicHeaderAction(
                icon = Icons.Default.TrendingUp,
                contentDescription = "Trending",
                onClick = onTrendingClick,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    loading: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    fun Modifier.actionGesture() = if (onLongClick != null) {
        combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        clickable(onClick = onClick)
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .tvFocusBorder(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .actionGesture(),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
                modifier = Modifier.size(21.dp),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(23.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    recent: List<com.streamcloud.app.data.library.TrackEntity>,
    nowPlayingUrl: String?,
    isPlaying: Boolean,
    onPlay: (com.streamcloud.app.data.library.TrackEntity, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Text(
            "Recently played",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (recent.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing played yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                itemsIndexed(
                    recent,
                    key = { index, entity -> "hist_${index}_${entity.url}" },
                ) { index, entity ->
                    LibraryRow(
                        entity = entity,
                        isPlaying = isPlaying && nowPlayingUrl == entity.url,
                        onClick = { onPlay(entity, index) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSearchField(query: String, loading: Boolean, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search songs, artists, albums") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = when {
            loading -> { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary) } }
            query.isNotEmpty() -> { { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear") } } }
            else -> null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SearchResultRow(
    thumbnail: String?,
    title: String,
    subtitle: String,
    isCircle: Boolean = false,
    isNowPlaying: Boolean = false,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusBorder(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val imgShape = if (isCircle) CircleShape else RoundedCornerShape(8.dp)
        Box {
            com.streamcloud.app.ui.components.MusicThumbnail(
                url = thumbnail,
                size = 54.dp,
                shape = imgShape,
            )
            if (isNowPlaying) {
                com.streamcloud.app.ui.components.PlayingBars(
                    modifier = Modifier.matchParentSize(),
                    paused = !isPlaying,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SuggestionListRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusBorder(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).rotate(-45f),
        )
    }
}

@Composable
private fun SuggestionsRow(
    quickChips: List<MoodChip>,
    onPick: (String) -> Unit,
) {
    Column {
        SectionTitle("Trending searches")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(buildCombinedMusicSuggestions(quickChips)) { s ->
                SuggestionChip(label = s, onClick = { onPick(s) })
            }
        }
    }
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .tvFocusBorder(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun SearchModeChips(
    active: com.streamcloud.app.ui.viewmodel.SearchMode,
    onPick: (com.streamcloud.app.ui.viewmodel.SearchMode) -> Unit,
) {
    val modes = com.streamcloud.app.ui.viewmodel.SearchMode.values().toList()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(modes, key = { it.name }) { mode ->
            FilterChip(
                selected = mode == active,
                onClick = { onPick(mode) },
                label = { Text(mode.name) },
                leadingIcon = if (mode == active) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun TopResultCard(track: YtTrack, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .tvFocusBorder(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.thumbnail,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                track.uploader,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClick) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun AlbumCard(album: com.streamcloud.app.data.newpipe.YtAlbum, onClick: () -> Unit = {}) {
    Column(Modifier.width(160.dp).tvFocusBorder(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        AsyncImage(
            model = album.thumbnail,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            album.title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            album.artist,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistCard(
    artist: com.streamcloud.app.data.newpipe.YtArtist,
    onClick: () -> Unit = {},
) {
    Column(
        Modifier.width(140.dp).tvFocusBorder(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = artist.thumbnail,
            contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(120.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            artist.name,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        artist.subscriberLabel?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroCard(track: YtTrack, isPlaying: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .tvFocusBorder(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = track.thumbnail,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                Modifier
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .align(Alignment.BottomEnd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            track.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.uploader,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SongRow(
    track: YtTrack,
    nowPlayingUrl: String?,
    isPlaying: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val highlighted = nowPlayingUrl == track.url
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .tvFocusBorder(RoundedCornerShape(12.dp))
            .background(if (highlighted) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (track.thumbnail != null) {
                AsyncImage(
                    model = track.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.uploader,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                null,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryRow(
    entity: com.streamcloud.app.data.library.TrackEntity,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .tvFocusBorder(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = entity.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entity.title, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                entity.artist, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun YtHomePlaylistCard(
    pl: com.streamcloud.app.data.ytmusic.YtmPlaylist,
    onClick: () -> Unit = {},
) {
    Column(
        Modifier
            .width(150.dp)
            .tvFocusBorder(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = pl.thumbnail,
            contentDescription = pl.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            pl.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )



        val displaySubtitle = if (pl.cachedTrackCount != null) {
            pl.subtitle
                ?.replace(Regex("\\d+\\s+songs?", RegexOption.IGNORE_CASE), "${pl.cachedTrackCount} songs")
                ?: "${pl.cachedTrackCount} songs"
        } else pl.subtitle
        displaySubtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun YtHomeSongCard(
    song: com.streamcloud.app.data.ytmusic.YtmSong,
    queue: List<com.streamcloud.app.data.ytmusic.YtmSong>,
    startIndex: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onPlay = {
        scope.launch {
            runCatching {
                com.streamcloud.app.data.ytmusic.YtPlayback.playPlaylist(
                    context,
                    queue,
                    startIndex,
                )
            }
        }
        Unit
    }
    Column(
        modifier = Modifier
            .then(modifier)
            .tvFocusBorder(RoundedCornerShape(10.dp))
            .clickable { onPlay() }
            .padding(bottom = 10.dp),
    ) {
        Box {
            AsyncImage(
                model = song.thumbnail,
                contentDescription = song.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            com.streamcloud.app.ui.components.SongRowMenu(
                song = song,
                onPlay = { onPlay() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(36.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

