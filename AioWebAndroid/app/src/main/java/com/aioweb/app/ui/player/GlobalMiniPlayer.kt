package com.aioweb.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.aioweb.app.audio.MusicController
import com.aioweb.app.audio.PlaybackBus
import com.aioweb.app.data.downloads.MusicDownloader
import com.aioweb.app.data.library.LibraryDb
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

    var controller by remember { mutableStateOf<Player?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf<String?>(null) }
    var artworkUri by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    val isPlaying by PlaybackBus.isPlaying.collectAsState()
    val nowMediaId by PlaybackBus.nowPlayingMediaId.collectAsState()

    val downloadProgressMap by MusicDownloader.progressFlow.collectAsState(initial = emptyMap())
    val downloadProgress = nowMediaId?.let { downloadProgressMap[it] }

    var isLiked by remember(nowMediaId) { mutableStateOf(false) }
    var isDownloaded by remember(nowMediaId) { mutableStateOf(false) }

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
    // Refresh like / downloaded state from Room whenever the playing track changes.
    LaunchedEffect(nowMediaId) {
        val mediaId = nowMediaId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val track = LibraryDb.get(context).tracks().byUrl(mediaId)
            isLiked = track?.likedAt != null
            isDownloaded = track?.localPath?.let { java.io.File(it).exists() } == true
        }
    }

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
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onExpand)
                // Vertical drag detector — upward fling/drag past ~60px
                // expands to the full now-playing sheet (Spotify gesture).
                .pointerInput(Unit) {
                    var accumulated = 0f
                    detectVerticalDragGestures(
                        onDragStart = { accumulated = 0f },
                        onDragEnd = { accumulated = 0f },
                        onDragCancel = { accumulated = 0f },
                    ) { _, drag ->
                        accumulated += drag
                        if (accumulated < -60f) {
                            accumulated = 0f
                            onExpand()
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
                    if (downloadProgress != null) {
                        Spacer(Modifier.height(2.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                        )
                    }
                }
                IconButton(onClick = {
                    val mediaId = nowMediaId ?: return@IconButton
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val dao = LibraryDb.get(context).tracks()
                            val now = if (isLiked) null else System.currentTimeMillis()
                            dao.setLikedAt(mediaId, now)
                            isLiked = !isLiked
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
                IconButton(
                    onClick = {
                        val mediaId = nowMediaId ?: return@IconButton
                        val songTitle = title ?: return@IconButton
                        scope.launch {
                            runCatching {
                                MusicDownloader.download(context, mediaId, songTitle)
                                isDownloaded = true
                            }
                        }
                    },
                    enabled = !isDownloaded && downloadProgress == null,
                ) {
                    Icon(
                        if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                        if (isDownloaded) "Downloaded" else "Download",
                        tint = if (isDownloaded) MaterialTheme.colorScheme.primary
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
