package com.streamcloud.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    var controller by remember { mutableStateOf<Player?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf<String?>(null) }
    var artworkUri by remember { mutableStateOf<String?>(null) }

    val isPlaying by PlaybackBus.isPlaying.collectAsState()
    val nowMediaId by PlaybackBus.nowPlayingMediaId.collectAsState()

    var isLiked by remember(nowMediaId) { mutableStateOf(false) }

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
    var liveDragX by remember { mutableStateOf(0f) }

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
                .offset { IntOffset((swipeOffsetX.value + liveDragX).roundToInt(), 0) }
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                .clickable(onClick = onExpand)
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
                                }
                            }
                        }
                        swipeOffsetX.snapTo(liveDragX)
                        liveDragX = 0f
                        when {
                            dirLocked && isHorizontal && totalX < -80f -> {
                                swipeOffsetX.animateTo(-90f, tween(100))
                                controller?.seekToNextMediaItem()
                                controller?.play()
                                swipeOffsetX.snapTo(90f)
                                swipeOffsetX.animateTo(0f, tween(220))
                            }
                            dirLocked && isHorizontal && totalX > 80f -> {
                                swipeOffsetX.animateTo(90f, tween(100))
                                controller?.seekToPreviousMediaItem()
                                controller?.play()
                                swipeOffsetX.snapTo(-90f)
                                swipeOffsetX.animateTo(0f, tween(220))
                            }
                            dirLocked && isHorizontal -> swipeOffsetX.animateTo(0f, spring())
                            dirLocked && !isHorizontal && totalY < -60f -> onExpand()
                            else -> swipeOffsetX.snapTo(0f)
                        }
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        controller?.let { if (it.isPlaying) it.pause() else it.play() }
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
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
        }
    }
}
