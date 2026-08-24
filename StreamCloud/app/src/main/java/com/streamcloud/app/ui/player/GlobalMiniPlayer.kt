package com.streamcloud.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.audio.PlaybackBus
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository
import com.streamcloud.app.ui.theme.AlbumArtThemeBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun GlobalMiniPlayer(
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = { PlayerExpandBus.requestExpand() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sl = remember(context) { ServiceLocator.get(context) }
    val ytCookie by sl.settings.ytMusicCookie.collectAsState(initial = "")
    val dynamicMiniTheme by sl.settings.dynamicMiniPlayerTheme.collectAsState(initial = true)

    var controller by remember { mutableStateOf<Player?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf<String?>(null) }
    var artworkUri by remember { mutableStateOf<String?>(null) }

    val isPlaying by PlaybackBus.isPlaying.collectAsState()
    val nowMediaId by PlaybackBus.nowPlayingMediaId.collectAsState()

    var isLiked by remember(nowMediaId) { mutableStateOf(false) }
    var showPlayHint by remember { mutableStateOf(false) }
    var showSonosPicker by remember { mutableStateOf(false) }
    val videoId = remember(nowMediaId) {
        nowMediaId?.let { id ->
            id.substringAfter("v=").substringBefore("&").takeIf { it.length in 6..15 } ?: id
        } ?: ""
    }

    LaunchedEffect(showPlayHint) {
        if (showPlayHint) {
            delay(1300L)
            showPlayHint = false
        }
    }

    val accent by AlbumArtThemeBus.accent.collectAsState()
    val navPillBgColor by AlbumArtThemeBus.navPillBg.collectAsState()

    val bgColor by animateColorAsState(
        targetValue = if (dynamicMiniTheme) navPillBgColor else Color(0xFF1C1C1E),
        animationSpec = tween(600),
        label = "miniPlayerBg",
    )

    val ringColor by animateColorAsState(
        targetValue = if (dynamicMiniTheme) accent else Color.White,
        animationSpec = tween(600),
        label = "miniRingColor",
    )

    var playbackProgress by remember { mutableStateOf(0f) }
    LaunchedEffect(controller) {
        while (true) {
            val c = controller
            if (c != null) {
                val dur = c.duration
                if (dur > 0L) {
                    playbackProgress = (c.currentPosition.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                }
            }
            delay(100L)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { MusicController.get(context.applicationContext) }
            .onSuccess { c ->
                controller = c
                title = c.mediaMetadata.title?.toString()
                artist = c.mediaMetadata.artist?.toString()
                artworkUri = c.mediaMetadata.artworkUri?.toString()
                c.addListener(object : Player.Listener {
                    override fun onMediaMetadataChanged(md: androidx.media3.common.MediaMetadata) {
                        title = md.title?.toString()
                        artist = md.artist?.toString()
                        artworkUri = md.artworkUri?.toString()
                    }
                })
            }
    }

    LaunchedEffect(nowMediaId) {
        val mediaId = nowMediaId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val track = LibraryDb.get(context).tracks().byUrl(mediaId)
            isLiked = track?.likedAt != null
        }
    }

    val swipeOffsetX = remember { Animatable(0f) }
    val swipeOffsetY = remember { Animatable(0f) }
    var liveDragX by remember { mutableStateOf(0f) }
    var liveDragY by remember { mutableStateOf(0f) }
    val verticalOffset = swipeOffsetY.value + liveDragY
    // A short upward drag previews the expanded player without stretching the
    // compact layout all the way to full-screen. The sheet then completes the
    // visual handoff once the expand threshold is reached.
    val expandProgress = (-verticalOffset / 240f).coerceIn(0f, 1f)
    val dismissProgress = (verticalOffset / 200f).coerceIn(0f, 1f)

    AnimatedVisibility(
        visible = title != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .offset {
                    IntOffset(
                        (swipeOffsetX.value + liveDragX).roundToInt(),
                        verticalOffset.roundToInt(),
                    )
                }
                .graphicsLayer {
                    // Match the responsive feel of YouTube Music: the mini-player
                    // lifts, opens up, and fades into the full player as it is dragged.
                    scaleX = 1f + (expandProgress * 0.04f) - (dismissProgress * 0.05f)
                    scaleY = 1f + (expandProgress * 0.18f) - (dismissProgress * 0.10f)
                    alpha = 1f - (expandProgress * 0.65f) - (dismissProgress * 0.40f)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .clickable {
                    scope.launch {
                        swipeOffsetY.animateTo(-240f, tween(140))
                        onExpand()
                        // Give the now-playing sheet one frame to cover the final
                        // compact-player pose before resetting it underneath.
                        delay(32)
                        swipeOffsetY.snapTo(0f)
                    }
                }
                .pointerInput(controller) {
                    while (true) {
                        var totalX = 0f
                        var totalY = 0f
                        var dirLocked = false
                        var isHorizontal = false
                        awaitPointerEventScope {
                            awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val pos = change.position - change.previousPosition
                                totalX += pos.x
                                totalY += pos.y
                                if (!dirLocked && (abs(totalX) > 16f || abs(totalY) > 16f)) {
                                    isHorizontal = abs(totalX) > abs(totalY)
                                    dirLocked = true
                                }
                                if (dirLocked && isHorizontal) {
                                    liveDragX = totalX.coerceIn(-280f, 280f)
                                    change.consume()
                                } else if (dirLocked && !isHorizontal) {
                                    // Both vertical directions follow the finger:
                                    // up previews expansion; down previews dismissal.
                                    liveDragY = totalY.coerceIn(-240f, 200f)
                                    change.consume()
                                }
                            }
                        }
                        swipeOffsetX.snapTo(liveDragX)
                        liveDragX = 0f
                        liveDragY = 0f
                        when (resolveMiniPlayerSwipeAction(dirLocked, isHorizontal, totalX, totalY)) {
                            MiniPlayerSwipeAction.SeekNext -> {
                                swipeOffsetX.animateTo(-90f, tween(100))
                                controller?.seekToNextMediaItem()
                                swipeOffsetX.snapTo(90f)
                                swipeOffsetX.animateTo(0f, tween(220))
                            }
                            MiniPlayerSwipeAction.SeekPrev -> {
                                swipeOffsetX.animateTo(90f, tween(100))
                                controller?.seekToPreviousMediaItem()
                                swipeOffsetX.snapTo(-90f)
                                swipeOffsetX.animateTo(0f, tween(220))
                            }
                            MiniPlayerSwipeAction.SnapBack -> swipeOffsetX.animateTo(0f, spring())
                            MiniPlayerSwipeAction.Expand   -> {
                                swipeOffsetY.animateTo(-240f, tween(140))
                                onExpand()
                                delay(32)
                                swipeOffsetY.snapTo(0f)
                            }
                            MiniPlayerSwipeAction.Dismiss  -> {
                                // Swipe-down: stop playback and hide the mini-player
                                swipeOffsetY.animateTo(200f, tween(160))
                                controller?.stop()
                                controller?.clearMediaItems()
                                title = null
                                swipeOffsetY.snapTo(0f)
                            }
                            MiniPlayerSwipeAction.None     -> {
                                swipeOffsetX.animateTo(0f, spring(dampingRatio = 0.68f))
                                swipeOffsetY.animateTo(0f, spring(dampingRatio = 0.68f))
                            }
                        }
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 3.dp.toPx()
                    val halfStroke = strokePx / 2f
                    val arcSize = Size(size.width - strokePx, size.height - strokePx)
                    val arcOffset = Offset(halfStroke, halfStroke)
                    drawArc(
                        color = Color.White.copy(alpha = 0.15f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = arcOffset,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                    if (playbackProgress > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = 360f * playbackProgress,
                            useCenter = false,
                            topLeft = arcOffset,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round),
                        )
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                            showPlayHint = true
                        },
                ) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showPlayHint,
                        enter = fadeIn(tween(120)),
                        exit = fadeOut(tween(450)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.50f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
            }

            Spacer(Modifier.width(8.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isLiked) Color(0xFFE91E63) else Color(0xFF2C2C2E))
                    .clickable {
                        val mediaId = nowMediaId ?: return@clickable
                        val nowLiked = isLiked
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val dao = LibraryDb.get(context).tracks()
                                dao.setLikedAt(mediaId, if (nowLiked) null else System.currentTimeMillis())
                                isLiked = !nowLiked
                                val videoId = mediaId.substringAfter("v=").substringBefore("&")
                                    .takeIf { it.isNotBlank() } ?: return@withContext
                                if (nowLiked) YtMusicLibraryRepository.unlikeSong(ytCookie, videoId)
                                else YtMusicLibraryRepository.likeSong(ytCookie, videoId)
                            }
                        }
                    },
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(6.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2C2C2E))
                    .clickable { showSonosPicker = true },
            ) {
                Icon(
                    imageVector = Icons.Default.Cast,
                    contentDescription = "Cast to Sonos",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (showSonosPicker) {
        SonosDevicePickerSheet(
            videoId  = videoId,
            title    = title.orEmpty(),
            watchUrl = nowMediaId ?: "",
            onDismiss = { showSonosPicker = false },
        )
    }
}
