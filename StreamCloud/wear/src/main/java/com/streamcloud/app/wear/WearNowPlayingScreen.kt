package com.streamcloud.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import coil.compose.AsyncImage

@Composable
internal fun WearNowPlayingScreen(
    snapshot: WearPlaybackSnapshot,
    selectedOutput: PlaybackTarget,
    statusMessage: String,
    updateTitle: String?,
    updateDetails: String,
    updateEnabled: Boolean,
    onSelectOutput: (PlaybackTarget) -> Unit,
    onUpdate: () -> Unit,
    onBrowse: () -> Unit,
    onTransport: (String, Long) -> Unit,
) {
    val duration = snapshot.durationMs.coerceAtLeast(0L)
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember(snapshot.title, duration) {
        mutableFloatStateOf(snapshot.positionMs.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)))
    }
    LaunchedEffect(snapshot.positionMs, snapshot.title, duration) {
        if (!isScrubbing) {
            scrubPosition = snapshot.positionMs
                .toFloat()
                .coerceIn(0f, duration.toFloat().coerceAtLeast(1f))
        }
    }

    androidx.wear.compose.material.ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101827))
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item {
            Text(
                text = "NOW PLAYING",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.caption2,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Box(
                modifier = Modifier
                    .size(164.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center,
            ) {
                if (snapshot.artwork.isNotBlank()) {
                    AsyncImage(
                        model = snapshot.artwork,
                        contentDescription = "Album artwork",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text("♪", color = Color(0xFF1BC8E8), style = MaterialTheme.typography.display1)
                }
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = snapshot.title.ifBlank { "Nothing playing" },
                    color = Color.White,
                    style = MaterialTheme.typography.title3,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = snapshot.artist.ifBlank {
                        if (snapshot.hasItem) "StreamCloud Music" else "Choose a track from your library"
                    },
                    color = Color(0xFFB8C4D8),
                    style = MaterialTheme.typography.caption1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                WatchSeekBar(
                    positionMs = scrubPosition.toLong(),
                    durationMs = duration,
                    enabled = snapshot.hasItem && duration > 0L,
                    onPreview = { position ->
                        isScrubbing = true
                        scrubPosition = position.toFloat()
                    },
                    onSeek = { position ->
                        isScrubbing = false
                        scrubPosition = position.toFloat()
                        onTransport("seek", position)
                    },
                    onCancel = {
                        isScrubbing = false
                        scrubPosition = snapshot.positionMs.toFloat()
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatTime(scrubPosition.toLong()), color = Color.LightGray, style = MaterialTheme.typography.caption2)
                    Text(formatTime(duration), color = Color.LightGray, style = MaterialTheme.typography.caption2)
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CompactButton(
                    onClick = { onTransport("previous", 0L) },
                    enabled = snapshot.hasItem,
                ) {
                    Text("Prev", style = MaterialTheme.typography.caption2)
                }
                Button(
                    onClick = { onTransport(if (snapshot.playing) "pause" else "play", 0L) },
                    enabled = snapshot.hasItem,
                ) {
                    Text(if (snapshot.playing) "Pause" else "Play")
                }
                CompactButton(
                    onClick = { onTransport("next", 0L) },
                    enabled = snapshot.hasItem,
                ) {
                    Text("Next", style = MaterialTheme.typography.caption2)
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaybackTarget.entries.forEach { target ->
                    OutputDeviceOption(
                        target = target,
                        selected = target == selectedOutput,
                        onSelect = onSelectOutput,
                    )
                }
            }
        }
        if (updateTitle != null) {
            item {
                WearUpdateChip(
                    title = updateTitle,
                    details = updateDetails,
                    enabled = updateEnabled,
                    onClick = onUpdate,
                )
            }
        }
        item {
            Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                Text("Browse music")
            }
        }
        if (statusMessage.isNotBlank()) {
            item {
                Text(
                    statusMessage,
                    color = Color(0xFFB8C4D8),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun WearUpdateChip(
    title: String,
    details: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OutputDeviceOption(
    target: PlaybackTarget,
    selected: Boolean,
    onSelect: (PlaybackTarget) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CompactButton(
            onClick = { onSelect(target) },
            colors = if (selected) {
                ButtonDefaults.primaryButtonColors()
            } else {
                ButtonDefaults.secondaryButtonColors()
            },
            modifier = Modifier.semantics {
                contentDescription = "${target.label} audio output${if (selected) ", selected" else ""}"
                role = Role.RadioButton
            },
        ) {
            DeviceGlyph(isWatch = target == PlaybackTarget.WATCH)
        }
        Text(
            text = target.label,
            color = if (selected) Color.White else Color(0xFFB8C4D8),
            style = MaterialTheme.typography.caption2,
        )
    }
}

@Composable
private fun DeviceGlyph(isWatch: Boolean) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val strokeWidth = 2.dp.toPx()
        val white = Color.White
        if (isWatch) {
            drawRoundRect(
                color = white,
                topLeft = Offset(size.width * 0.36f, size.height * 0.02f),
                size = Size(size.width * 0.28f, size.height * 0.23f),
                cornerRadius = CornerRadius(strokeWidth),
            )
            drawRoundRect(
                color = white,
                topLeft = Offset(size.width * 0.36f, size.height * 0.75f),
                size = Size(size.width * 0.28f, size.height * 0.23f),
                cornerRadius = CornerRadius(strokeWidth),
            )
            drawRoundRect(
                color = white,
                topLeft = Offset(size.width * 0.19f, size.height * 0.19f),
                size = Size(size.width * 0.62f, size.height * 0.62f),
                cornerRadius = CornerRadius(size.width * 0.16f),
                style = Stroke(strokeWidth),
            )
            drawCircle(
                color = white,
                radius = strokeWidth * 0.65f,
                center = Offset(size.width * 0.5f, size.height * 0.5f),
            )
        } else {
            drawRoundRect(
                color = white,
                topLeft = Offset(size.width * 0.25f, size.height * 0.05f),
                size = Size(size.width * 0.5f, size.height * 0.9f),
                cornerRadius = CornerRadius(size.width * 0.1f),
                style = Stroke(strokeWidth),
            )
            drawLine(
                color = white,
                start = Offset(size.width * 0.4f, size.height * 0.16f),
                end = Offset(size.width * 0.6f, size.height * 0.16f),
                strokeWidth = strokeWidth,
            )
            drawLine(
                color = white,
                start = Offset(size.width * 0.43f, size.height * 0.84f),
                end = Offset(size.width * 0.57f, size.height * 0.84f),
                strokeWidth = strokeWidth,
            )
        }
    }
}

@Composable
private fun WatchSeekBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onPreview: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    val latestPreview by rememberUpdatedState(onPreview)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestCancel by rememberUpdatedState(onCancel)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(durationMs, enabled) {
                if (!enabled || durationMs <= 0L) return@pointerInput
                detectTapGestures { point ->
                    val target = (
                        point.x / size.width.coerceAtLeast(1).toFloat() * durationMs.toFloat()
                    )
                        .toLong()
                        .coerceIn(0L, durationMs)
                    latestPreview(target)
                    latestSeek(target)
                }
            }
            .pointerInput(durationMs, enabled) {
                if (!enabled || durationMs <= 0L) return@pointerInput
                var dragPosition = positionMs
                detectDragGestures(
                    onDragStart = { point ->
                        dragPosition = (
                            point.x / size.width.coerceAtLeast(1).toFloat() * durationMs.toFloat()
                        )
                            .toLong()
                            .coerceIn(0L, durationMs)
                        latestPreview(dragPosition)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragPosition = (
                            change.position.x / size.width.coerceAtLeast(1).toFloat() * durationMs.toFloat()
                        )
                            .toLong()
                            .coerceIn(0L, durationMs)
                        latestPreview(dragPosition)
                    },
                    onDragEnd = { latestSeek(dragPosition) },
                    onDragCancel = { latestCancel() },
                )
            },
    ) {
        val trackHeight = 4.dp.toPx()
        val y = (size.height - trackHeight) / 2f
        drawRoundRect(
            color = Color(0xFF526174),
            topLeft = Offset(0f, y),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        val fraction = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val progressWidth = size.width * fraction
        drawRoundRect(
            color = Color(0xFF1BC8E8),
            topLeft = Offset(0f, y),
            size = Size(progressWidth, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        drawCircle(
            color = Color.White,
            radius = 5.dp.toPx(),
            center = Offset(progressWidth, size.height / 2f),
        )
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
