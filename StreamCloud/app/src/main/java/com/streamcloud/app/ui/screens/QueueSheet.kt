package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.streamcloud.app.ui.components.PlayingBars

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    controller: Player,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var queueItems by remember { mutableStateOf(snapshotQueue(controller)) }
    var currentIndex by remember { mutableStateOf(controller.currentMediaItemIndex) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = controller.currentMediaItemIndex
                queueItems = snapshotQueue(controller)
            }
            override fun onTimelineChanged(
                timeline: androidx.media3.common.Timeline,
                reason: Int,
            ) {
                queueItems = snapshotQueue(controller)
                currentIndex = controller.currentMediaItemIndex
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1210),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 300.dp, max = 700.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Queue",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                val count = queueItems.size
                Text(
                    "$count track${if (count != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.45f),
                )
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close, "Close",
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

            if (queueItems.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Nothing queued",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(queueItems, key = { i, _ -> i }) { index, item ->
                        QueueItemRow(
                            item = item,
                            isCurrent = index == currentIndex,
                            onTap = {
                                controller.seekToDefaultPosition(index)
                                controller.play()
                            },
                            onRemove = {
                                controller.removeMediaItem(index)
                                queueItems = snapshotQueue(controller)
                                currentIndex = controller.currentMediaItemIndex
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    item: QueueEntry,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(7.dp)),
        ) {
            if (!item.artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = item.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF2E2220)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow, null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            if (isCurrent) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayingBars(modifier = Modifier.size(22.dp))
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.artist.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.artist,
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close, "Remove from queue",
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private data class QueueEntry(
    val title: String,
    val artist: String?,
    val artworkUri: String?,
)

private fun snapshotQueue(controller: Player): List<QueueEntry> {
    val count = controller.mediaItemCount
    return (0 until count).map { i ->
        val meta = controller.getMediaItemAt(i).mediaMetadata
        QueueEntry(
            title = meta.title?.toString() ?: "Unknown",
            artist = meta.artist?.toString(),
            artworkUri = meta.artworkUri?.toString(),
        )
    }
}
