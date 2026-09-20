package com.streamcloud.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.audio.PlaybackBus
import com.streamcloud.app.ui.theme.tvFocusBorder

@OptIn(UnstableApi::class)
@Composable
fun TvMiniPlayer(
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = { PlayerExpandBus.requestExpand() },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isPlaying by PlaybackBus.isPlaying.collectAsState()
    var controller by remember { mutableStateOf<Player?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf<String?>(null) }
    var artwork by remember { mutableStateOf<String?>(null) }

    fun applyMediaItem(item: androidx.media3.common.MediaItem?) {
        val metadata = item?.mediaMetadata ?: return
        title = metadata.title?.toString()
        artist = metadata.artist?.toString()
        artwork = metadata.artworkUri?.toString()
    }

    LaunchedEffect(Unit) {
        runCatching { MusicController.get(context.applicationContext) }
            .onSuccess { connected ->
                controller = connected
                applyMediaItem(connected.currentMediaItem)
            }
    }

    DisposableEffect(controller) {
        val activeController = controller ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                applyMediaItem(mediaItem ?: activeController.currentMediaItem)
            }

            override fun onMediaMetadataChanged(
                mediaMetadata: androidx.media3.common.MediaMetadata,
            ) {
                applyMediaItem(activeController.currentMediaItem)
            }
        }
        activeController.addListener(listener)
        onDispose { activeController.removeListener(listener) }
    }

    if (title.isNullOrBlank()) return

    Row(
        modifier = modifier
            .width(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE61B2026))
            .tvFocusBorder(RoundedCornerShape(12.dp), borderWidth = 3.dp)
            .clickable(onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFF30343A)),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title.orEmpty(),
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall
                    .copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = artist.orEmpty(),
                color = Color.White.copy(alpha = 0.68f),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier.height(30.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val barColor = if (isPlaying) Color(0xFF66E6A8) else Color.White.copy(alpha = 0.45f)
            Box(Modifier.width(3.dp).height(if (isPlaying) 20.dp else 8.dp).background(barColor, RoundedCornerShape(3.dp)))
            Box(Modifier.width(3.dp).height(if (isPlaying) 27.dp else 8.dp).background(barColor, RoundedCornerShape(3.dp)))
            Box(Modifier.width(3.dp).height(if (isPlaying) 15.dp else 8.dp).background(barColor, RoundedCornerShape(3.dp)))
        }
    }
}