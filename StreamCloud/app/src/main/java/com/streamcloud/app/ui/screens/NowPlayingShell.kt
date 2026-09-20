package com.streamcloud.app.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BedtimeOff
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.ViewGroup
import com.streamcloud.app.data.spotify.SpotifyCanvasRepository
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.streamcloud.app.data.downloads.MusicDownloader
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.lyrics.LyricsRepository
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.newpipe.StreamMeta
import com.streamcloud.app.data.sonos.SonosRepository
import com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository
import com.streamcloud.app.cast.MusicRemoteCast
import com.streamcloud.app.ui.player.MusicActionsSheet
import com.streamcloud.app.ui.player.SonosDevicePickerSheet
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun NowPlayingShell(
    controller: Player,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenArtistSearch: (String) -> Unit = {},
    npScrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sl = remember(context) { com.streamcloud.app.data.ServiceLocator.get(context) }
    val ytCookie by sl.settings.ytMusicCookie.collectAsState(initial = "")


    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var title by remember { mutableStateOf(controller.mediaMetadata.title?.toString().orEmpty()) }
    var artist by remember { mutableStateOf(controller.mediaMetadata.artist?.toString().orEmpty()) }
    var album by remember { mutableStateOf(controller.mediaMetadata.albumTitle?.toString().orEmpty()) }
    var artwork by remember { mutableStateOf(controller.mediaMetadata.artworkUri?.toString()) }
    var mediaId by remember { mutableStateOf(controller.currentMediaItem?.mediaId) }
    var selectedMusicVideo by remember {
        mutableStateOf(
            controller.currentMediaItem?.mediaMetadata?.extras
                ?.getBoolean(com.streamcloud.app.data.ytmusic.YtPlayback.EXTRA_IS_MUSIC_VIDEO, false)
                ?: false,
        )
    }
    var explicitMusicVideoId by remember {
        mutableStateOf(
            controller.currentMediaItem?.mediaMetadata?.extras
                ?.getString(com.streamcloud.app.data.ytmusic.YtPlayback.EXTRA_VIDEO_ID)
                .orEmpty(),
        )
    }
    var isPlaying by remember { mutableStateOf(controller.isPlaying) }
    var shuffleOn by remember { mutableStateOf(controller.shuffleModeEnabled) }
    var repeatMode by remember { mutableStateOf(controller.repeatMode) }

    fun updateSelectedMusicVideo(item: MediaItem?) {
        val extras = item?.mediaMetadata?.extras
        selectedMusicVideo = extras?.getBoolean(
            com.streamcloud.app.data.ytmusic.YtPlayback.EXTRA_IS_MUSIC_VIDEO,
            false,
        ) ?: false
        explicitMusicVideoId = extras
            ?.getString(com.streamcloud.app.data.ytmusic.YtPlayback.EXTRA_VIDEO_ID)
            .orEmpty()
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(md: MediaMetadata) {
                title = md.title?.toString().orEmpty()
                artist = md.artist?.toString().orEmpty()
                album = md.albumTitle?.toString().orEmpty()
                artwork = md.artworkUri?.toString()
                mediaId = controller.currentMediaItem?.mediaId
                updateSelectedMusicVideo(controller.currentMediaItem)
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffleOn = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateSelectedMusicVideo(mediaItem)
                if (mediaItem == null) return

                // When casting, tell the active network destination to load the selected track.
                val cstate = SonosRepository.castState.value
                if (cstate is SonosRepository.CastState.Casting) {
                    // SonosRepository owns the queue observer for the lifetime of the cast.
                    // This screen only keeps phone audio paused while the repository replaces
                    // the speaker URI, so mini-player and notification skips work too.
                    controller.pause()
                }
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            positionMs = controller.currentPosition.coerceAtLeast(0L)
            durationMs = controller.duration.coerceAtLeast(0L)
            delay(500)
        }
    }


    var isLiked by remember(mediaId) { mutableStateOf(false) }
    var isDownloaded by remember(mediaId) { mutableStateOf(false) }
    val downloadMap by MusicDownloader.progressFlow.collectAsState(initial = emptyMap())
    val downloadProgress = mediaId?.let { downloadMap[it] }
    LaunchedEffect(mediaId) {
        val mid = mediaId ?: return@LaunchedEffect
        LibraryDb.get(context.applicationContext).tracks().isLiked(mid).collect { liked ->
            isLiked = liked == true
        }
    }
    LaunchedEffect(mediaId, downloadProgress) {
        val mid = mediaId ?: return@LaunchedEffect
        isDownloaded = MusicDownloader.isDownloaded(context, mid)
    }


    val dominant by rememberDominant(artwork)
    val animDominant by animateColorAsState(
        targetValue = dominant,
        animationSpec = tween(durationMillis = 600),
        label = "np-bg",
    )


    val artworkSwipeX = remember { Animatable(0f) }
    var artworkDragX by remember { mutableStateOf(0f) }


    var lyrics by remember(mediaId) { mutableStateOf<com.streamcloud.app.data.lyrics.LrcEntry?>(null) }
    var lyricsLoading by remember(mediaId) { mutableStateOf(false) }
    LaunchedEffect(mediaId, title, artist) {
        if (mediaId == null || title.isBlank()) return@LaunchedEffect
        lyricsLoading = true
        val durSec = durationMs.takeIf { it > 0L }?.div(1000L) ?: 0L
        lyrics = runCatching { LyricsRepository.fetch(title, artist, durSec) }.getOrNull()
        lyricsLoading = false
    }
    var trackMeta by remember(mediaId) { mutableStateOf<StreamMeta?>(null) }
    LaunchedEffect(mediaId) {
        val mid = mediaId ?: return@LaunchedEffect
        val videoId = if (mid.startsWith("http")) mid.substringAfter("v=", "").substringBefore("&") else mid
        if (videoId.length != 11) return@LaunchedEffect
        trackMeta = runCatching { NewPipeRepository.fetchStreamMeta(videoId) }.getOrNull()
    }


    var sleepEndTs by remember { mutableStateOf<Long?>(null) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    LaunchedEffect(sleepEndTs) {
        val end = sleepEndTs ?: return@LaunchedEffect
        while (System.currentTimeMillis() < end) delay(1000)
        controller.pause()
        sleepEndTs = null
    }


    var showSonos by remember { mutableStateOf(false) }
    val castState by SonosRepository.castState.collectAsState()
    val remoteCastState by MusicRemoteCast.state.collectAsState()
    val isSonosCasting = castState is SonosRepository.CastState.Casting
    val isNetworkCasting = remoteCastState is MusicRemoteCast.State.Casting ||
        remoteCastState is MusicRemoteCast.State.Connecting
    val isCasting = isSonosCasting || isNetworkCasting

    val sonosIsPlaying  by SonosRepository.isSonosPlaying.collectAsState()
    val sonosTrackUpdating by SonosRepository.isSonosTrackUpdating.collectAsState()
    val sonosPosMs      by SonosRepository.sonosPositionMs.collectAsState()
    val sonosDurMs      by SonosRepository.sonosDurationMs.collectAsState()
    val remotePlaybackState by MusicRemoteCast.remoteState.collectAsState()
    val hasRemoteNetworkPlayback = remotePlaybackState != null

    val displayIsPlaying = when {
        isSonosCasting -> sonosIsPlaying
        hasRemoteNetworkPlayback -> remotePlaybackState?.isPlaying ?: false
        else -> isPlaying
    }
    val displayPositionMs = when {
        isSonosCasting -> sonosPosMs
        hasRemoteNetworkPlayback -> remotePlaybackState?.positionMs ?: 0L
        else -> positionMs
    }
    val displayDurationMs = when {
        isSonosCasting -> sonosDurMs
        hasRemoteNetworkPlayback -> remotePlaybackState?.durationMs ?: 0L
        else -> durationMs
    }

    fun skipToNext() {
        if (isCasting) controller.pause()
        controller.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        if (isCasting) controller.pause()
        controller.seekToPreviousMediaItem()
    }



    // A track ID is useful for Canvas and casting, but visual playback must only use a music
    // video explicitly selected from a music-video surface. Most songs also have a YouTube
    // video stream, so treating any track ID as a music video opens the player everywhere.
    val trackVideoId = remember(mediaId, explicitMusicVideoId) {
        mediaVideoId(
            explicitVideoId = explicitMusicVideoId,
            mediaId = mediaId,
        )
    }
    val selectedVideoId = remember(mediaId, explicitMusicVideoId, selectedMusicVideo) {
        selectedMusicVideoId(
            isMusicVideo = selectedMusicVideo,
            explicitVideoId = explicitMusicVideoId,
            mediaId = mediaId,
        )
    }
    val sonosCastWatchUrl = remember(mediaId, trackVideoId) {
        when {
            mediaId?.startsWith("http") == true -> mediaId.orEmpty()
            trackVideoId.isNotBlank() -> "https://music.youtube.com/watch?v=$trackVideoId"
            else -> ""
        }
    }

    val canvasEnabled by sl.settings.canvasEnabled.collectAsState(initial = true)
    val ytMusicCanvasEnabled by sl.settings.ytMusicCanvasEnabled.collectAsState(initial = false)
    val spotifyCookie by sl.settings.spotifyCookie.collectAsState(initial = "")

    var showActions by remember { mutableStateOf(false) }


    // ── Music video detection ─────────────────────────────────────────────────────────────────
    // null = still checking, false = audio-only track, true = has a real music video
    var isMusicVideo    by remember(mediaId) { mutableStateOf<Boolean?>(null) }
    var videoStreamUrl  by remember(mediaId) { mutableStateOf<String?>(null) }
    var videoStreamUserAgent by remember(mediaId) { mutableStateOf<String?>(null) }
    var showVideoPlayer by remember(mediaId) { mutableStateOf(false) }
    var ytMusicCanvasSuppressed by remember(mediaId) { mutableStateOf(false) }
    // Ordinary songs may expose a YouTube video stream too. Canvas stays as their default
    // surface until the listener explicitly chooses to watch the video.
    var manualVideoRequested by remember(mediaId) { mutableStateOf(false) }

    LaunchedEffect(
        selectedVideoId,
        trackVideoId,
        explicitMusicVideoId,
        selectedMusicVideo,
        manualVideoRequested,
        ytMusicCanvasEnabled,
        canvasEnabled,
    ) {
        isMusicVideo   = null
        videoStreamUrl = null
        videoStreamUserAgent = null
        showVideoPlayer = false
        ytMusicCanvasSuppressed = false
        val videoIdToResolve = when {
            selectedMusicVideo -> selectedVideoId
            manualVideoRequested -> trackVideoId
            ytMusicCanvasEnabled &&
                !canvasEnabled &&
                explicitMusicVideoId.isNotBlank() -> trackVideoId
            else -> ""
        }
        if (videoIdToResolve.isBlank()) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            YtPlayerUtils.resolveVideoStream(videoIdToResolve)
        }
        isMusicVideo   = result.isMusicVideo
        videoStreamUrl = result.url
        videoStreamUserAgent = result.userAgent
        showVideoPlayer = result.url != null
    }

    // Keep the in-memory repository cookie in sync with DataStore (survives app restarts)
    LaunchedEffect(spotifyCookie) {
        SpotifyCanvasRepository.setSpotifyCookie(spotifyCookie.takeIf { it.isNotBlank() })
    }

    var canvasUrl by remember(mediaId) { mutableStateOf<String?>(null) }
    LaunchedEffect(mediaId, title, artist, canvasEnabled, spotifyCookie, selectedMusicVideo) {
        canvasUrl = null
        if (
            !canvasEnabled ||
            selectedMusicVideo ||
            title.isBlank() ||
            trackVideoId.isBlank() ||
            spotifyCookie.isBlank()
        ) return@LaunchedEffect
        canvasUrl = runCatching {
            SpotifyCanvasRepository.getCanvasUrl(trackVideoId, title, artist)
        }.getOrNull()
    }
    // Spotify Canvas and YouTube Music video Canvas are mutually exclusive. Spotify keeps
    // priority if an older install somehow has both preferences enabled.
    val activeSpotifyCanvas = if (
        canvasEnabled &&
        !selectedMusicVideo &&
        !showVideoPlayer
    ) canvasUrl else null
    val activeYtMusicCanvas = if (
        ytMusicCanvasEnabled &&
        !canvasEnabled &&
        explicitMusicVideoId.isNotBlank() &&
        !ytMusicCanvasSuppressed &&
        videoStreamUrl != null
    ) videoStreamUrl else null
    val activeCanvas = activeSpotifyCanvas ?: activeYtMusicCanvas

    // When canvas is playing its background is always dark; use white text.
    // Without canvas the gradient bg varies, so derive from the dominant artwork colour.
    val onBg = if (activeCanvas != null) Color.White
               else if (animDominant.luminance() > 0.5f) Color(0xFF111111)
               else Color.White

    // Controls visibility — auto-hides after 3.5 s, tap screen to reveal.
    var controlsVisible by remember { mutableStateOf(true) }
    // Pin state persisted across minimize/app-restart via SharedPreferences
    val playerPrefs = remember { context.getSharedPreferences("sc_player_prefs", android.content.Context.MODE_PRIVATE) }
    var controlsPinned by remember { mutableStateOf(playerPrefs.getBoolean("controls_pinned", false)) }
    var hideKey by remember { mutableStateOf(0) }
    // Auto-hide only when a canvas video is actually playing and controls are not pinned.
    LaunchedEffect(hideKey, activeCanvas, controlsPinned) {
        if (activeCanvas == null || controlsPinned) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(3_500L)
        if (!controlsPinned) controlsVisible = false
    }

    // ── TV: Spotify-style two-column layout ───────────────────────────────────
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    if (isTv) {
        NowPlayingTvLayout(
            artwork = artwork,
            title = title,
            artist = artist,
            isPlaying = displayIsPlaying,
            positionMs = displayPositionMs,
            durationMs = displayDurationMs,
            shuffleOn = shuffleOn,
            repeatMode = repeatMode,
            isLiked = isLiked,
            controller = controller,
            isNetworkCasting = hasRemoteNetworkPlayback,
            castDeviceName = remotePlaybackState?.deviceName,
            onPlayPause = {
                if (hasRemoteNetworkPlayback) MusicRemoteCast.togglePlayPause()
                else if (isPlaying) controller.pause() else controller.play()
            },
            onSeek = { target ->
                if (hasRemoteNetworkPlayback) MusicRemoteCast.seekTo(target)
                else controller.seekTo(target)
            },
            onDisconnect = { MusicRemoteCast.disconnect() },
            onSkipNext = { skipToNext() },
            onSkipPrevious = { skipToPrevious() },
            onClose = onClose,
            onOpenArtistSearch = onOpenArtistSearch,
            npScrollState = npScrollState,
        )
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            // When canvas is active: NO opaque background — SurfaceView / TextureView must show through.
            // An opaque Compose background drawn on top of a SurfaceView completely hides the video.
            .then(
                if (activeCanvas != null) Modifier.background(Color.Black)  // just a black base for areas outside video
                else Modifier
                    .background(Color(0xFF0E0E0E))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                animDominant,
                                animDominant.copy(alpha = 0.55f).compositeOver(Color(0xFF161616)),
                                Color(0xFF0E0E0E),
                            )
                        )
                    )
            )
    ) {

        if (activeCanvas != null) {
            // CanvasVideoLayer uses TextureView (renders inline with Compose, not below it)
            CanvasVideoLayer(
                url = activeCanvas,
                userAgent = videoStreamUserAgent.takeIf { activeYtMusicCanvas != null },
                modifier = Modifier.fillMaxSize(),
            )

            // Gradient: darker at top (top bar) and bottom (controls), transparent in middle
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f   to Color.Black.copy(alpha = 0.50f),
                            0.25f to Color.Black.copy(alpha = 0.10f),
                            0.65f to Color.Black.copy(alpha = 0.10f),
                            1f   to Color.Black.copy(alpha = 0.80f),
                        )
                    )
            )
        }

        // Full-screen tap zone — only active when canvas is playing
        if (activeCanvas != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            controlsVisible = true
                            if (!controlsPinned) hideKey++
                        }
                    }
            )
        }

        // Album artwork / music video player — shown centred when no Spotify Canvas is active
        if (activeCanvas == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, (-npScrollState.value * 0.6f).roundToInt()) }
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 72.dp, bottom = 280.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showVideoPlayer && videoStreamUrl != null) {
                    // ── Music video player ──────────────────────────────────────────────────
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(16f / 9f)
                                .shadow(20.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black),
                        ) {
                            MusicVideoPlayer(
                                url = videoStreamUrl!!,
                                userAgent = videoStreamUserAgent,
                                startPositionMs = controller.currentPosition,
                                controllerPositionMs = displayPositionMs,
                                isPlaying = isPlaying,
                            )
                        }
                        // Close button — top-right corner of the video card
                        Box(
                            Modifier.fillMaxWidth(0.85f).aspectRatio(16f / 9f),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            IconButton(
                                onClick = {
                                    showVideoPlayer = false
                                    if (!selectedMusicVideo) manualVideoRequested = false
                                },
                                modifier = Modifier
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                            ) {
                                Icon(Icons.Default.Close, "Close video", tint = Color.White)
                            }
                        }
                    }
                } else {
                    // ── Album artwork (default) ─────────────────────────────────────────────
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(controller) {
                                while (true) {
                                    var totalX = 0f
                                    val widthPx = size.width.toFloat()
                                    awaitPointerEventScope {
                                        awaitFirstDown(requireUnconsumed = false)
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: break
                                            if (!change.pressed) break
                                            totalX += (change.position - change.previousPosition).x
                                            artworkDragX = totalX
                                            change.consume()
                                        }
                                    }
                                    artworkSwipeX.snapTo(artworkDragX)
                                    artworkDragX = 0f
                                    val threshold = widthPx * 0.28f
                                    when {
                                        totalX < -threshold -> {
                                            artworkSwipeX.animateTo(-widthPx, tween(220))
                                            skipToNext()
                                            artworkSwipeX.snapTo(widthPx)
                                            artworkSwipeX.animateTo(0f, tween(300))
                                        }
                                        totalX > threshold -> {
                                            artworkSwipeX.animateTo(widthPx, tween(220))
                                            skipToPrevious()
                                            artworkSwipeX.snapTo(-widthPx)
                                            artworkSwipeX.animateTo(0f, tween(300))
                                        }
                                        else -> artworkSwipeX.animateTo(0f, spring(dampingRatio = 0.65f))
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(1f)
                                .offset { IntOffset((artworkSwipeX.value + artworkDragX).roundToInt(), 0) }
                                .shadow(20.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.25f)),
                        ) {
                            AsyncImage(
                                model = artwork,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            // Play-circle badge — visible only for confirmed music videos
                            if (isMusicVideo == true) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clickable { showVideoPlayer = true },
                                    contentAlignment = Alignment.BottomEnd,
                                ) {
                                    Icon(
                                        Icons.Default.PlayCircle,
                                        contentDescription = "Watch music video",
                                        tint = Color.White.copy(alpha = 0.88f),
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .size(44.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Controls overlay — fades only when canvas is active and not pinned
        AnimatedVisibility(
            visible = controlsVisible || activeCanvas == null || controlsPinned,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(600)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                // ── Top bar ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NpIconButton(onClick = onClose, tint = onBg) {
                        Icon(Icons.Default.KeyboardArrowDown, "Minimize")
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Now Playing",
                            color = onBg,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            title.ifBlank { "—" },
                            color = onBg,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Balanced spacer — video/pin buttons live in the permanent overlay, not here
                    Spacer(Modifier.size(40.dp))
                }

                // ── Bottom controls (scrollable — scroll down to reveal Lyrics / Artist / Stats) ──
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .align(Alignment.TopStart),
                ) {
                    // Spacer height = everything above the controls region.
                    // The artwork box uses padding(bottom = 280.dp); controls sit in that 280dp zone.
                    val controlsZone = 300.dp
                    val artworkSpace = (maxHeight - controlsZone).coerceAtLeast(80.dp)
                    Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(npScrollState)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Spacer(Modifier.height(artworkSpace))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Album art thumbnail — only shown when Spotify Canvas is playing;
                        // without canvas the full artwork is already visible in the centre.
                        if (activeCanvas != null && !artwork.isNullOrBlank()) {
                            AsyncImage(
                                model = artwork,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                title.ifBlank { "—" },
                                color = onBg,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                            )
                            Text(
                                artist.ifBlank { "Unknown artist" },
                                color = onBg.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                            )
                            if (isNetworkCasting) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.Black.copy(alpha = 0.28f))
                                        .clickable { showSonos = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Icon(
                                        Icons.Default.CastConnected,
                                        contentDescription = null,
                                        tint = Color(0xFF66D9A6),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (remotePlaybackState?.isBuffering == true) {
                                            "Loading on ${remotePlaybackState?.deviceName ?: "TV"}…"
                                        } else {
                                            "Playing on ${remotePlaybackState?.deviceName ?: "TV"}"
                                        },
                                        color = Color(0xFFB8F3D8),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        PillButton(
                            icon = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                            contentDescription = if (isDownloaded) "Downloaded" else "Download",
                            loading = downloadProgress != null,
                            onClick = {
                                val mid = mediaId ?: return@PillButton
                                if (isDownloaded || downloadProgress != null) return@PillButton
                                val videoId = mid
                                    .substringAfter("v=", missingDelimiterValue = "")
                                    .substringBefore('&')
                                    .takeIf { it.isNotBlank() }
                                if (videoId != null) {
                                    com.streamcloud.app.data.ytmusic.YtPlayback.downloadSong(
                                        context,
                                        com.streamcloud.app.data.ytmusic.YtmSong(
                                            videoId = videoId,
                                            title = title,
                                            artist = "",
                                            album = null,
                                            thumbnail = null,
                                            durationSeconds = null,
                                        ),
                                    )
                                } else {
                                    val req = androidx.media3.exoplayer.offline.DownloadRequest
                                        .Builder(mid, android.net.Uri.parse(mid))
                                        .setData(title.toByteArray(Charsets.UTF_8))
                                        .setCustomCacheKey(mid)
                                        .build()
                                    androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                                        context,
                                        com.streamcloud.app.data.downloads.MusicExoDownloadService::class.java,
                                        req,
                                        false,
                                    )
                                }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        PillButton(
                            icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike" else "Like",
                            onClick = {
                                val mid = mediaId ?: return@PillButton
                                val nowLiked = isLiked
                                scope.launch {
                                    val dao = LibraryDb.get(context.applicationContext).tracks()
                                    dao.setLikedAt(mid, if (nowLiked) null else System.currentTimeMillis())
                                    val videoId = mid.substringAfter("v=").substringBefore("&")
                                        .takeIf { it.isNotBlank() } ?: return@launch
                                    if (nowLiked) YtMusicLibraryRepository.unlikeSong(ytCookie, videoId)
                                    else YtMusicLibraryRepository.likeSong(ytCookie, videoId)
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Slider(
                        value = if (displayDurationMs > 0) displayPositionMs / displayDurationMs.toFloat() else 0f,
                        onValueChange = { v ->
                            if (isSonosCasting) {
                                if (displayDurationMs > 0)
                                    SonosRepository.seek((v * displayDurationMs).toLong())
                            } else if (hasRemoteNetworkPlayback && displayDurationMs > 0) {
                                MusicRemoteCast.seekTo((v * displayDurationMs).toLong())
                            } else if (durationMs > 0) {
                                controller.seekTo((v * durationMs).toLong())
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = onBg,
                            activeTrackColor = onBg,
                            inactiveTrackColor = onBg.copy(alpha = 0.3f),
                        ),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            formatTime(displayPositionMs), color = onBg.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            formatTime(displayDurationMs), color = onBg.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DarkCapsule(
                            icon = Icons.Default.SkipPrevious, contentDescription = "Previous",
                            onClick = { skipToPrevious() },
                        )
                        PlayPill(
                            playing = displayIsPlaying,
                            onClick = {
                                if (isSonosCasting) {
                                    if (sonosTrackUpdating || sonosIsPlaying) SonosRepository.pause()
                                    else SonosRepository.resume()
                                } else if (hasRemoteNetworkPlayback) {
                                    MusicRemoteCast.togglePlayPause()
                                } else {
                                    if (isPlaying) controller.pause() else controller.play()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        DarkCapsule(
                            icon = Icons.Default.SkipNext, contentDescription = "Next",
                            onClick = { skipToNext() },
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    BottomToolbar(
                        shuffleOn = shuffleOn,
                        repeatMode = repeatMode,
                        isCasting = isCasting,
                        onQueue = { showQueueSheet = true },
                        onCast = { showSonos = true },
                        onShuffle = { controller.shuffleModeEnabled = !controller.shuffleModeEnabled },
                        onRepeat = {
                            val next = when (controller.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            controller.repeatMode = next
                        },
                        onMore = { showActions = true },
                    )

                    Spacer(Modifier.height(16.dp))
                    LyricsCard(
                        lyrics = lyrics,
                        loading = lyricsLoading,
                        positionMs = positionMs,
                        onTextColor = onBg,
                        cardBg = Color.Black.copy(alpha = 0.35f),
                    )
                    val meta = trackMeta
                    if (meta != null) {
                        Spacer(Modifier.height(12.dp))
                        ArtistCard(
                            artistName = artist,
                            avatarUrl = meta.uploaderAvatarUrl,
                            subscriberCount = meta.uploaderSubscriberCount,
                        )
                        Spacer(Modifier.height(12.dp))
                        YoutubeStatsCard(
                            viewCount = meta.viewCount,
                            likeCount = meta.likeCount,
                            uploadDate = meta.uploadDate,
                            description = meta.description,
                            onTextColor = onBg,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                } // end BoxWithConstraints
            }
        }

        // ── Permanent top-right buttons — never fade with canvas controls ──
        if (activeCanvas != null || videoStreamUrl != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(end = 4.dp, top = 4.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Video / canvas toggle
                    NpIconButton(
                        onClick = {
                            if (activeYtMusicCanvas != null) ytMusicCanvasSuppressed = true
                            else if (activeSpotifyCanvas != null) manualVideoRequested = !manualVideoRequested
                            else showVideoPlayer = !showVideoPlayer
                            controlsVisible = true
                            hideKey++
                        },
                        tint = onBg,
                    ) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = if (activeCanvas != null && manualVideoRequested) "Return to Canvas"
                            else if (showVideoPlayer) "Hide video" else "Watch video",
                        )
                    }
                    // Pin — only relevant when canvas auto-hides controls
                    if (activeCanvas != null) {
                        NpIconButton(
                            onClick = {
                                controlsPinned = !controlsPinned
                                playerPrefs.edit().putBoolean("controls_pinned", controlsPinned).apply()
                                controlsVisible = true
                                hideKey++
                            },
                            tint = onBg,
                        ) {
                            Icon(
                                if (controlsPinned) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = if (controlsPinned) "Unpin controls" else "Pin controls",
                            )
                        }
                    }
                }
            }
        }
    }

    if (showQueueSheet) {
        QueueSheet(controller = controller, onDismiss = { showQueueSheet = false })
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            onDismiss = { showSleepDialog = false },
            onPick = { mins ->
                sleepEndTs = System.currentTimeMillis() + mins * 60_000L
                showSleepDialog = false
            },
        )
    }

    if (showSonos) {
        SonosDevicePickerSheet(
            videoId = trackVideoId,
            title = title,
            watchUrl = sonosCastWatchUrl,
            artist = artist,
            album = album,
            artworkUrl = artwork,
            onDismiss = { showSonos = false },
        )
    }

    if (showActions) {
        MusicActionsSheet(
            controller = controller,
            currentMediaId = mediaId,
            currentTitle = title,
            currentArtist = artist,
            isLiked = isLiked,
            isDownloaded = isDownloaded,
            sleepActive = sleepEndTs != null,
            onSleep = {
                showActions = false
                if (sleepEndTs != null) sleepEndTs = null
                else showSleepDialog = true
            },
            onDismiss = { showActions = false },
            onOpenSettings = onOpenSettings,
            onOpenArtistSearch = onOpenArtistSearch,
        )
    }
}

@Composable
private fun PillButton(
    icon: ImageVector,
    contentDescription: String,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(46.dp)
            .widthIn(min = 70.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.95f))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFF111111),
            )
        } else {
            Icon(icon, contentDescription, tint = Color(0xFF111111), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun DarkCapsule(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 78.dp, height = 56.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun PlayPill(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .clickable(onClick = onClick),
    ) {
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (playing) "Pause" else "Play",
            tint = Color(0xFF111111),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (playing) "Pause" else "Play",
            color = Color(0xFF111111),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            fontSize = 22.sp,
        )
    }
}

@Composable
private fun NpIconButton(
    onClick: () -> Unit,
    tint: Color,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) { content() }
    }
}

@Composable
private fun BottomToolbar(
    shuffleOn: Boolean,
    repeatMode: Int,
    isCasting: Boolean,
    onQueue: () -> Unit,
    onCast: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolbarChip(Icons.Default.QueueMusic, "Queue", false, false, onQueue, Modifier.weight(1f))
        ToolbarChip(Icons.Default.Cast, "Cast to devices", isCasting, false, onCast, Modifier.weight(1f))
        ToolbarChip(Icons.Default.Shuffle, "Shuffle", shuffleOn, false, onShuffle, Modifier.weight(1f))
        ToolbarChip(
            if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOneOn else Icons.Default.Repeat,
            "Repeat", repeatMode != Player.REPEAT_MODE_OFF, false, onRepeat, Modifier.weight(1f),
        )
        ToolbarChip(Icons.Default.MoreVert, "More", false, true, onMore, Modifier.weight(1f))
    }
}

@Composable
private fun ToolbarChip(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    solid: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        solid -> Color.White
        active -> Color.White.copy(alpha = 0.25f)
        else -> Color.Black.copy(alpha = 0.4f)
    }
    val fg = if (solid) Color(0xFF111111) else Color.White
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun rememberDominant(thumbnailUrl: String?): State<Color> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Color(0xFF8A6A48)) }
    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl.isNullOrBlank()) return@LaunchedEffect
        runCatching {
            val loader = ImageLoader(context)
            val req = ImageRequest.Builder(context)
                .data(thumbnailUrl).allowHardware(false).size(160).build()
            val res = loader.execute(req)
            val drawable = (res as? SuccessResult)?.drawable as? BitmapDrawable
            val bitmap: Bitmap? = drawable?.bitmap
            if (bitmap != null) {
                Palette.from(bitmap).generate { p ->
                    val swatch = p?.dominantSwatch ?: p?.vibrantSwatch ?: p?.mutedSwatch
                    if (swatch != null) state.value = Color(swatch.rgb)
                }
            }
        }
    }
    return state
}

@Composable
private fun LyricsCard(
    lyrics: com.streamcloud.app.data.lyrics.LrcEntry?,
    loading: Boolean,
    positionMs: Long,
    onTextColor: Color,
    cardBg: Color,
) {
    val hasSynced = lyrics?.syncedLyrics?.isNotBlank() == true
    val hasAny = hasSynced || lyrics?.plainLyrics?.isNotBlank() == true
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .padding(16.dp),
    ) {
        Column {
            Text(
                "Lyrics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onTextColor,
            )
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = onTextColor)
                }
                hasSynced -> {
                    val parsed = remember(lyrics) { LyricsRepository.parseLrc(lyrics?.syncedLyrics) }
                    val activeIdx = remember(positionMs, parsed) {
                        parsed.indexOfLast { it.first <= positionMs }.coerceAtLeast(0)
                    }
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    androidx.compose.runtime.LaunchedEffect(activeIdx) {
                        // Scroll so the active line is the 2nd visible item — one previous line stays above for context
                        listState.animateScrollToItem((activeIdx - 1).coerceAtLeast(0))
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(parsed.size) { i ->
                            val (_, line) = parsed[i]
                            Text(
                                line,
                                color = if (i == activeIdx) onTextColor else onTextColor.copy(alpha = 0.4f),
                                style = if (i == activeIdx)
                                    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                else
                                    MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                lyrics?.plainLyrics?.isNotBlank() == true -> Text(
                    lyrics.plainLyrics,
                    color = onTextColor.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Text(
                    "No lyrics found.",
                    color = onTextColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (!loading && hasAny) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (hasSynced) "Line Synced" else "Plain text",
                    color = onTextColor.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Lyrics provided by LRCLIB",
                    color = onTextColor.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ArtistCard(
    artistName: String,
    avatarUrl: String?,
    subscriberCount: Long,
) {
    val subLabel = when {
        subscriberCount >= 1_000_000 -> "%.1f".format(subscriberCount / 1_000_000.0)
            .trimEnd('0').trimEnd('.') + "M subscribers"
        subscriberCount >= 1_000 -> "%.1f".format(subscriberCount / 1_000.0)
            .trimEnd('0').trimEnd('.') + "K subscribers"
        subscriberCount > 0 -> "$subscriberCount subscribers"
        else -> ""
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.4f)),
    ) {
        Column {
            Text(
                "Artists",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (!avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = artistName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                artistName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (subLabel.isNotBlank()) {
                Text(
                    subLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun YoutubeStatsCard(
    viewCount: Long,
    likeCount: Long,
    uploadDate: String?,
    description: String,
    onTextColor: Color,
) {
    val viewLabel = when {
        viewCount >= 1_000_000 -> "%.1f".format(viewCount / 1_000_000.0)
            .trimEnd('0').trimEnd('.') + "M views"
        viewCount >= 1_000 -> "%.1f".format(viewCount / 1_000.0)
            .trimEnd('0').trimEnd('.') + "K views"
        viewCount > 0 -> "$viewCount views"
        else -> null
    }
    val likeLabel = when {
        likeCount >= 1_000_000 -> "%.1f".format(likeCount / 1_000_000.0)
            .trimEnd('0').trimEnd('.') + "M likes"
        likeCount >= 1_000 -> "%.1f".format(likeCount / 1_000.0)
            .trimEnd('0').trimEnd('.') + "K likes"
        likeCount > 0 -> "$likeCount likes"
        else -> null
    }
    // Format "2025-02-06T02:04:48-08:00" → "6 Feb 2025"
    val formattedDate = remember(uploadDate) {
        if (uploadDate.isNullOrBlank()) null
        else try {
            val ymd = uploadDate.take(10).split("-")
            val months = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
            "${ymd[2].trimStart('0').ifEmpty { "0" }} ${months[ymd[1].toInt() - 1]} ${ymd[0]}"
        } catch (_: Exception) { uploadDate }
    }
    // Strip HTML tags and decode entities from the description
    val cleanDesc = remember(description) {
        description
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .trim()
    }
    val hasContent = formattedDate != null || viewLabel != null || likeLabel != null || cleanDesc.isNotBlank()
    if (!hasContent) return
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (formattedDate != null) {
                Text(
                    formattedDate,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = onTextColor.copy(alpha = 0.7f),
                )
            }
            if (viewLabel != null) {
                Text(
                    viewLabel,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = onTextColor,
                )
            }
            if (likeLabel != null) {
                Text(
                    likeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onTextColor.copy(alpha = 0.75f),
                )
            }
            if (cleanDesc.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "About",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = onTextColor,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    cleanDesc.take(600),
                    style = MaterialTheme.typography.bodySmall,
                    color = onTextColor.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun SleepTimerDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(5, 10, 15, 30, 45, 60, 90).forEach { mins ->
                    TextButton(
                        onClick = { onPick(mins) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("$mins minutes", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// TV: Spotify-style two-column now-playing layout
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(UnstableApi::class)
@Composable
private fun NowPlayingTvLayout(
    artwork: String?,
    title: String,
    artist: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleOn: Boolean,
    repeatMode: Int,
    isLiked: Boolean,
    controller: Player,
    isNetworkCasting: Boolean,
    castDeviceName: String?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onDisconnect: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onClose: () -> Unit,
    onOpenArtistSearch: (String) -> Unit,
    npScrollState: androidx.compose.foundation.ScrollState,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF11171D)),
    ) {
        AsyncImage(
            model = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.42f
                    scaleX = 1.18f
                    scaleY = 1.18f
                },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xF211171D),
                            Color(0xB811171D),
                            Color(0x4011171D),
                        ),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x9911171D),
                            Color.Transparent,
                            Color(0xE611171D),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(npScrollState)
                .padding(horizontal = 64.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "StreamCloud",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                TvPlayerTopLabel("Home")
                TvPlayerTopLabel("Search")
                TvPlayerTopLabel("Your Library")
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .tvFocusBorder(CircleShape),
                ) {
                    Icon(Icons.Default.Close, "Close player", tint = Color.White.copy(alpha = 0.82f))
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                AsyncImage(
                    model = artwork,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(154.dp)
                        .shadow(30.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF273039)),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isNetworkCasting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF16382B))
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Icon(Icons.Default.CastConnected, null, tint = Color(0xFF66D9A6), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Playing on ${castDeviceName ?: "TV"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFB8F3D8),
                            )
                        }
                    }
                    Text(
                        title.ifBlank { "—" },
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist.ifBlank { "Unknown artist" },
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = progress,
                    onValueChange = { value ->
                        if (durationMs > 0L) onSeek((value * durationMs).toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(positionMs), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelLarge)
                    Text(formatTime(durationMs), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelLarge)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvPlayerIconButton(Icons.Default.SkipPrevious, "Previous", onSkipPrevious)
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .tvFocusBorder(CircleShape)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        tint = Color(0xFF11171D),
                        modifier = Modifier.size(42.dp),
                    )
                }
                TvPlayerIconButton(Icons.Default.SkipNext, "Next", onSkipNext)
                Spacer(Modifier.weight(1f))
                TvPlayerIconButton(
                    if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (isLiked) "Unlike" else "Like",
                    {},
                    active = isLiked,
                )
                TvPlayerIconButton(Icons.Default.Shuffle, "Shuffle", { controller.shuffleModeEnabled = !shuffleOn }, active = shuffleOn)
                TvPlayerIconButton(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat",
                    {
                        controller.repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    },
                    active = repeatMode != Player.REPEAT_MODE_OFF,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvPlayerActionChip("Lyrics", {})
                TvPlayerActionChip("Queue", {})
                if (isNetworkCasting) {
                    TvPlayerActionChip("Disconnect", onDisconnect, destructive = true)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "About the artist",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = artist,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(126.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            artist.ifBlank { "Unknown artist" },
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                        Text(
                            "Explore more music from this artist",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                        TextButton(
                            onClick = { if (artist.isNotBlank()) onOpenArtistSearch(artist) },
                            modifier = Modifier.tvFocusBorder(RoundedCornerShape(50)),
                        ) {
                            Text("Go to artist", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPlayerTopLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.78f),
    )
}

@Composable
private fun TvPlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .tvFocusBorder(CircleShape),
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (active) Color(0xFF66E6A8) else Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun TvPlayerActionChip(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.tvFocusBorder(RoundedCornerShape(50)),
    ) {
        Text(
            label,
            color = if (destructive) Color(0xFFFF9E95) else Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

// ── Music video player ────────────────────────────────────────────────────────────────────────
//
// Plays a muxed mp4 YouTube stream in place of the album-art thumbnail.
// Volume is kept at 0f — audio continues through MusicPlaybackService's ExoPlayer.
// The player seeks to [startPositionMs] once prepared so it stays roughly in sync with audio.
// It also mirrors pause/play state from the main controller.
@OptIn(UnstableApi::class)
@Composable
private fun MusicVideoPlayer(
    url: String,
    userAgent: String?,
    startPositionMs: Long,
    controllerPositionMs: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val player = remember(url, userAgent) {
        val dataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(userAgent.orEmpty())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
            volume = 0f   // muted — audio comes from MusicPlaybackService
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    LaunchedEffect(url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.seekTo(startPositionMs)
        player.playWhenReady = true
    }

    // Mirror main-player pause/play
    LaunchedEffect(isPlaying) {
        player.playWhenReady = isPlaying
    }

    // Drift correction: every 5 s compare video position to the main controller.
    // If drift exceeds 2 s, re-seek so the video stays visually in sync with the audio.
    LaunchedEffect(url, controllerPositionMs) {
        val videoPosMs = player.currentPosition
        val driftMs = kotlin.math.abs(videoPosMs - controllerPositionMs)
        if (driftMs > 2_000L && player.playbackState == Player.STATE_READY) {
            player.seekTo(controllerPositionMs)
        }
    }

    DisposableEffect(url) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        player.setVideoSurface(android.view.Surface(st))
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        player.setVideoSurface(null); return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        update = { tv ->
            if (tv.isAvailable) {
                player.setVideoSurface(android.view.Surface(tv.surfaceTexture!!))
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

// Canvas video player using TextureView so it renders inline with the Compose layer.
// SurfaceView (used by PlayerView) punches below the Compose window and gets covered
// by any opaque Compose background. TextureView renders within the Compose hierarchy.
@OptIn(UnstableApi::class)
@Composable
private fun CanvasVideoLayer(
    url: String,
    userAgent: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val player = remember(url, userAgent) {
        val dataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(userAgent.orEmpty())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
            }
    }

    LaunchedEffect(url, userAgent) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(url, userAgent) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // Scale to fill (zoom/crop) — centre-crop the video to fill the screen
                scaleX = 1f; scaleY = 1f
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        player.setVideoSurface(android.view.Surface(st))
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        player.setVideoSurface(null); return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        update = { tv ->
            // Re-attach surface if TextureView is already available (recomposition)
            if (tv.isAvailable) {
                player.setVideoSurface(android.view.Surface(tv.surfaceTexture!!))
            }
        },
        modifier = modifier,
    )
}
