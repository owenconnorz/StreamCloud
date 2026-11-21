package com.streamcloud.app.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

@Composable
internal fun WearVideoPlayerScreen(
    title: String,
    streamUrl: String,
    headers: Map<String, String>,
    mimeType: String,
    startPositionMs: Long = 0L,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val safeHeaders = remember(headers) { headers.filterKeys(::isSafeWatchPlaybackHeader) }
    val player = remember(streamUrl, safeHeaders, startPositionMs) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(safeHeaders)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMimeType(mimeType.takeIf(String::isNotBlank))
                        .build(),
                )
                prepare()
                if (startPositionMs > 0L) seekTo(startPositionMs)
                playWhenReady = true
            }
    }

    BackHandler(onBack = onBack)
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var positionMs by remember(player) { mutableLongStateOf(startPositionMs.coerceAtLeast(0L)) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L } ?: 0L
            delay(400L)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().clip(CircleShape),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                }
            },
            update = { it.player = player },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x8808070D), Color.Transparent, Color(0xEE08070D)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactButton(onClick = onBack) {
                    Text("‹", style = MaterialTheme.typography.title3)
                }
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.caption1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .pointerInput(durationMs) {
                            detectTapGestures { point ->
                                if (durationMs > 0L) {
                                    player.seekTo(
                                        (point.x / size.width.coerceAtLeast(1).toFloat() * durationMs.toFloat())
                                            .toLong()
                                            .coerceIn(0L, durationMs),
                                    )
                                }
                            }
                        },
                ) {
                    val barHeight = 3.dp.toPx()
                    val y = (size.height - barHeight) / 2f
                    drawRoundRect(
                        color = Color(0xFF78647C),
                        topLeft = Offset(0f, y),
                        size = Size(size.width, barHeight),
                        cornerRadius = CornerRadius(barHeight),
                    )
                    val progress = if (durationMs > 0L) {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val progressWidth = size.width * progress
                    drawRoundRect(
                        color = Color(0xFFE34DFF),
                        topLeft = Offset(0f, y),
                        size = Size(progressWidth, barHeight),
                        cornerRadius = CornerRadius(barHeight),
                    )
                    drawCircle(Color.White, 5.dp.toPx(), Offset(progressWidth, size.height / 2f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatVideoTime(positionMs), color = Color.White, style = MaterialTheme.typography.caption2)
                    Text(formatVideoTime(durationMs), color = Color.White, style = MaterialTheme.typography.caption2)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                        Text("↶", style = MaterialTheme.typography.title3)
                    }
                    Button(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            isPlaying = player.isPlaying
                        },
                        colors = ButtonDefaults.primaryButtonColors(),
                    ) {
                        Text(if (isPlaying) "Ⅱ" else "▶", style = MaterialTheme.typography.title3)
                    }
                    CompactButton(onClick = {
                        player.seekTo((player.currentPosition + 10_000L).coerceAtMost(durationMs))
                    }) {
                        Text("↷", style = MaterialTheme.typography.title3)
                    }
                }
            }
            Spacer(Modifier.size(1.dp))
        }
    }
}

private fun formatVideoTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

private fun isSafeWatchPlaybackHeader(name: String): Boolean =
    name.equals("User-Agent", true) ||
        name.equals("Referer", true) ||
        name.equals("Origin", true) ||
        name.equals("Accept", true) ||
        name.equals("Accept-Language", true)
