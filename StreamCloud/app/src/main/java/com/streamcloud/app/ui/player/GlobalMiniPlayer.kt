package com.streamcloud.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.audio.PlaybackBus
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rich, app-wide mini-player that matches the in-Music-tab MiniPlayer 1:1
 * (album art + title + artist • album • year + Like ❤ + Download ⬇ + ⏮ ⏯ ⏭).
 *
 * Renders at the bottom of every tab whenever a track is loaded into the
 * foreground [MusicPlaybackService]. Reads playback state purely from the
 * global [MusicController] / [PlaybackBus] / Room — no ViewModel.
 *
 * Swipe-up or tap routes to [GlobalNowPlayingSheet] via [PlayerExpandBus].
 */
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

    var controller by remember { mutableStateOf<Player?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf<String?>(null) }
    var artworkUri by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    val isPlaying by PlaybackBus.isPlaying.collectAsState()
    val nowMediaId by PlaybackBus.nowPlayingMediaId.collectAsState()

    var isLiked by remember(nowMediaId) { mutableStateOf(false) }

    // Bind to the shared controller once; failure to bind simply hides the bar.
    LaunchedEffect(Unit) {
        runCatching { MusicController.get(context.applicationContext) }
            .onSuccess { c ->
                controller = c
                title = c.mediaMetadata.title?.toString()
                artist = c.mediaMetadata.artist?.toString()
                artworkUri = c.mediaMetadata.artworkUri?.toString()
                positionMs = c.currentPosition
                durationMs = c.duration.coerceAtLeast(0L)
                c.addListener(object : Player.Listener {
                    override fun onMediaMetadataChanged(md: androidx.media3.common.MediaMetadata) {
                        title = md.title?.toString()
                        artist = md.artist?.toString()
                        artworkUri = md.artworkUri?.toString()
                    }
                })
            }
    }
    // Poll position so the thin progress bar advances.
    LaunchedEffect(controller, isPlaying) {
        while (controller != null) {
            positionMs = controller!!.currentPosition
            durationMs = controller!!.duration.coerceAtLeast(0L)
            delay(500)
        }
    }
    // Refresh liked state whenever the playing track changes.
    LaunchedEffect(nowMediaId) {
        val mediaId = nowMediaId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val track = LibraryDb.get(context).tracks().byUrl(mediaId)
            isLiked = track?.likedAt != null
        }
    }

    // Tracks the horizontal slide offset while the user drags the mini player.
    val swipeOffsetX = remember { Animatable(0f) }

    AnimatedVisibility(
        visible = title != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .offset { IntOffset(swipeOffsetX.value.roundToInt(), 0) }
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onExpand)
                // Unified gesture handler: horizontal swipe skips tracks,
                // vertical swipe-up expands to full now-playing sheet.
                .pointerInput(controller) {
                    val scope = this
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var dirLocked = false
                        var isHorizontal = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val pos = change.positionChange()
                            totalX += pos.x
                            totalY += pos.y
                            if (!dirLocked && (abs(totalX) > 16f || abs(totalY) > 16f)) {
                                isHorizontal = abs(totalX) > abs(totalY)
                                dirLocked = true
                            }
                            if (dirLocked) {
                                change.consume()
                                if (isHorizontal) {
                                    scope.launch { swipeOffsetX.snapTo(totalX.coerceIn(-280f, 280f)) }
                                }
                            }
                        }
                        when {
                            dirLocked && isHorizontal && totalX < -80f -> scope.launch {
                                swipeOffsetX.animateTo(-90f, tween(100))
                                controller?.seekToNextMediaItem()
                                swipeOffsetX.snapTo(90f)
                                swipeOffsetX.animateTo(0f, tween(220))
                            }
                            dirLocked && isHorizontal && totalX > 80f -> scope.launch {
                                swipeOffsetX.animateTo(90f, tween(100))
                                controller?.seekToPreviousMediaItem()
                                swipeOffsetX.snapTo(-90f)
                                swipeOffsetX.animateTo(0f, tween(220))
                            }
                            dirLocked && isHorizontal -> scope.launch {
                                swipeOffsetX.animateTo(0f, spring())
                            }
                            dirLocked && !isHorizontal && totalY < -60f -> onExpand()
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    val mediaId = nowMediaId ?: return@IconButton
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
                }) {
                    Icon(
                        if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (isLiked) "Unlike" else "Like",
                        tint = if (isLiked) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        "Previous",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                    Icon(
                        Icons.Default.SkipNext,
                        "Skip next",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Thin progress under the row.
            val progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(top = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
