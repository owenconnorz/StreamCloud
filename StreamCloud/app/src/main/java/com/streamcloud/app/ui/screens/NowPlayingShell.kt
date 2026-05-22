package com.streamcloud.app.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BedtimeOff
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamcloud.app.data.spotify.SpotifyCanvasRepository
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.streamcloud.app.data.downloads.MusicDownloader
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.lyrics.LyricsRepository
import com.streamcloud.app.data.sonos.SonosRepository
import com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository
import com.streamcloud.app.ui.player.MusicActionsSheet
import com.streamcloud.app.ui.player.SonosDevicePickerSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun NowPlayingShell(
    controller: Player,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenArtistSearch: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sl = remember(context) { com.streamcloud.app.data.ServiceLocator.get(context) }
    val ytCookie by sl.settings.ytMusicCookie.collectAsState(initial = "")


    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var title by remember { mutableStateOf(controller.mediaMetadata.title?.toString().orEmpty()) }
    var artist by remember { mutableStateOf(controller.mediaMetadata.artist?.toString().orEmpty()) }
    var artwork by remember { mutableStateOf(controller.mediaMetadata.artworkUri?.toString()) }
    var mediaId by remember { mutableStateOf(controller.currentMediaItem?.mediaId) }
    var isPlaying by remember { mutableStateOf(controller.isPlaying) }
    var shuffleOn by remember { mutableStateOf(controller.shuffleModeEnabled) }
    var repeatMode by remember { mutableStateOf(controller.repeatMode) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(md: MediaMetadata) {
                title = md.title?.toString().orEmpty()
                artist = md.artist?.toString().orEmpty()
                artwork = md.artworkUri?.toString()
                mediaId = controller.currentMediaItem?.mediaId
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffleOn = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
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
    val onBg = if (animDominant.luminance() > 0.5f) Color(0xFF111111) else Color.White


    val artworkSwipeX = remember { Animatable(0f) }
    var artworkDragX by remember { mutableStateOf(0f) }


    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember(mediaId) { mutableStateOf<com.streamcloud.app.data.lyrics.LrcEntry?>(null) }
    var lyricsLoading by remember(mediaId) { mutableStateOf(false) }
    LaunchedEffect(mediaId, title, artist) {
        if (mediaId == null || title.isBlank()) return@LaunchedEffect
        lyricsLoading = true
        lyrics = runCatching { LyricsRepository.fetch(title, artist, 0L) }.getOrNull()
        lyricsLoading = false
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
    val isCasting = castState is SonosRepository.CastState.Casting



    val videoId = remember(mediaId) {
        val mid = mediaId ?: return@remember ""
        if (mid.startsWith("http")) {

            mid.substringAfter("v=", "").substringBefore("&").takeIf { it.isNotBlank() } ?: ""
        } else {

            mid
        }
    }
    val sonosCastWatchUrl = remember(mediaId, videoId) {
        when {
            mediaId?.startsWith("http") == true -> mediaId.orEmpty()
            videoId.isNotBlank() -> "https://music.youtube.com/watch?v=$videoId"
            else -> ""
        }
    }


    var showActions by remember { mutableStateOf(false) }


    val canvasEnabled by sl.settings.canvasEnabled.collectAsState(initial = false)
    var canvasUrl by remember(mediaId) { mutableStateOf<String?>(null) }
    LaunchedEffect(mediaId, title, artist, canvasEnabled) {
        canvasUrl = null
        if (!canvasEnabled || title.isBlank() || videoId.isBlank()) return@LaunchedEffect
        canvasUrl = runCatching {
            SpotifyCanvasRepository.getCanvasUrl(videoId, title, artist)
        }.getOrNull()
    }
    val activeCanvas = if (canvasEnabled) canvasUrl else null

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (activeCanvas == null) Modifier
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
                else Modifier
            )
    ) {

        if (activeCanvas != null) {
            CanvasVideoLayer(url = activeCanvas, modifier = Modifier.fillMaxSize())

            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.30f),
                                Color.Black.copy(alpha = 0.60f),
                            )
                        )
                    )
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                        title.ifBlank { "—" }.take(40),
                        color = onBg,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(40.dp))
            }

            Spacer(Modifier.height(12.dp))


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
                                    controller.seekToNextMediaItem()
                                    controller.play()
                                    artworkSwipeX.snapTo(widthPx)
                                    artworkSwipeX.animateTo(0f, tween(300))
                                }
                                totalX > threshold -> {
                                    artworkSwipeX.animateTo(widthPx, tween(220))
                                    controller.seekToPreviousMediaItem()
                                    controller.play()
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
                        .fillMaxWidth(0.92f)
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
                }
            }

            Spacer(Modifier.height(20.dp))


            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { "—" },
                        color = onBg,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist.ifBlank { "Unknown artist" },
                        color = onBg.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
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

            Spacer(Modifier.height(16.dp))


            Slider(
                value = if (durationMs > 0) positionMs / durationMs.toFloat() else 0f,
                onValueChange = { v ->
                    if (durationMs > 0) controller.seekTo((v * durationMs).toLong())
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
                    formatTime(positionMs), color = onBg.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    formatTime(durationMs), color = onBg.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            Spacer(Modifier.height(16.dp))


            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DarkCapsule(
                    icon = Icons.Default.SkipPrevious, contentDescription = "Previous",
                    onClick = { controller.seekToPreviousMediaItem(); controller.play() },
                )
                PlayPill(
                    playing = isPlaying,
                    onClick = { if (isPlaying) controller.pause() else controller.play() },
                    modifier = Modifier.weight(1f),
                )
                DarkCapsule(
                    icon = Icons.Default.SkipNext, contentDescription = "Next",
                    onClick = { controller.seekToNextMediaItem(); controller.play() },
                )
            }

            Spacer(Modifier.height(16.dp))


            BottomToolbar(
                shuffleOn = shuffleOn,
                repeatMode = repeatMode,
                sleepActive = sleepEndTs != null,
                lyricsActive = showLyrics,
                onQueue = { showQueueSheet = true },
                onSleep = {
                    if (sleepEndTs != null) sleepEndTs = null
                    else showSleepDialog = true
                },
                onLyrics = { showLyrics = !showLyrics },
                onCast = { showSonos = true },
                isCasting = isCasting,
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

            if (showLyrics) {
                Spacer(Modifier.height(12.dp))
                LyricsView(
                    lyrics = lyrics,
                    loading = lyricsLoading,
                    positionMs = positionMs,
                    onTextColor = onBg,
                )
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
            videoId = videoId,
            title = title,
            watchUrl = sonosCastWatchUrl,
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
    sleepActive: Boolean,
    lyricsActive: Boolean,
    isCasting: Boolean,
    onQueue: () -> Unit,
    onSleep: () -> Unit,
    onLyrics: () -> Unit,
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
        ToolbarChip(
            if (sleepActive) Icons.Default.BedtimeOff else Icons.Default.Bedtime,
            "Sleep timer", sleepActive, false, onSleep, Modifier.weight(1f),
        )
        ToolbarChip(Icons.Default.Lyrics, "Lyrics", lyricsActive, false, onLyrics, Modifier.weight(1f))
        ToolbarChip(Icons.Default.Cast, "Cast to Sonos", isCasting, false, onCast, Modifier.weight(1f))
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
private fun rememberDominant(thumbnailUrl: String?): State<Color> {
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
private fun LyricsView(
    lyrics: com.streamcloud.app.data.lyrics.LrcEntry?,
    loading: Boolean,
    positionMs: Long,
    onTextColor: Color,
) {
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = onTextColor)
        }
        lyrics?.syncedLyrics?.isNotBlank() == true -> {
            val parsed = remember(lyrics) { LyricsRepository.parseLrc(lyrics.syncedLyrics) }
            val activeIdx = remember(positionMs, parsed) {
                parsed.indexOfLast { it.first <= positionMs }.coerceAtLeast(0)
            }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(parsed.size) { i ->
                    val (_, line) = parsed[i]
                    Text(
                        line,
                        color = if (i == activeIdx) onTextColor else onTextColor.copy(alpha = 0.55f),
                        style = if (i == activeIdx) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
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
            color = onTextColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(8.dp),
        )
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

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@OptIn(UnstableApi::class)
@Composable
private fun CanvasVideoLayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false,
            )
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        update = { view -> view.player = player },
        modifier = modifier,
    )
}
