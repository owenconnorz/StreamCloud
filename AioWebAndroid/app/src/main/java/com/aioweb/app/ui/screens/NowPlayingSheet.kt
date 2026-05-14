package com.aioweb.app.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.request.SuccessResult
import com.aioweb.app.data.newpipe.YtTrack
import com.aioweb.app.ui.viewmodel.MusicState
import kotlinx.coroutines.delay

/**
 * Full-screen "Now Playing" — Metrolist / OpenTune-style.
 *
 * Layout (top → bottom):
 *  - Header: "Now Playing" + uppercase TRACK ALBUM
 *  - Wide 16:9 high-res artwork with rounded corners + soft shadow
 *  - Title (uppercase, bold) + artist · right side: download pill + like pill
 *  - Slider + 0:21 / 2:47 timestamps
 *  - BIG white "Play" pill flanked by dark-capsule prev / next
 *  - Bottom toolbar: queue, sleep, equalizer, lyrics, shuffle, repeat, more
 *  - Background = soft gradient pulled from the album-art dominant color (Palette)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    track: YtTrack,
    player: Player?,
    state: MusicState,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    onDismiss: () -> Unit,
    onSetSleepTimer: (minutes: Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onLike: () -> Unit,
    onDownload: () -> Unit,
) {
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            player?.let {
                positionMs = it.currentPosition.coerceAtLeast(0L)
                durationMs = it.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }
    var showSleep by remember { mutableStateOf(false) }
    var showLyricsPane by remember { mutableStateOf(false) }

    // Palette: pull dominant warm color from the album thumbnail for the background.
    val dominant by rememberDominantColor(track.thumbnail)
    val animDominant by animateColorAsState(
        targetValue = dominant,
        animationSpec = tween(durationMillis = 600),
        label = "np-bg",
    )
    val onBg = if (animDominant.luminance() > 0.5f) Color(0xFF111111) else Color.White

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E0E0E),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = null,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0E0E0E))   // solid base — no leak-through
                .background(
                    Brush.verticalGradient(
                        // All stops fully opaque, just darker as you scroll down.
                        listOf(
                            animDominant,
                            animDominant.copy(alpha = 0.55f).compositeOver(Color(0xFF161616)),
                            Color(0xFF0E0E0E),
                        ),
                    )
                )
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Top row: chevron-down — title — sleep chip
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    NpIconButton(onClick = onDismiss, tint = onBg) {
                        Icon(Icons.Default.ExpandMore, "Close")
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Now Playing",
                            color = onBg,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            track.title.uppercase().take(40),
                            color = onBg,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NpIconButton(onClick = { showSleep = true }, tint = onBg) {
                        Icon(Icons.Default.MoreVert, "More")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Square (1:1) artwork — Metrolist's exact size — with rounded corners + soft shadow.
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = track.thumbnail,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .aspectRatio(1f)
                            .shadow(20.dp, RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.25f)),
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Title + artist + (download / like pills)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title.uppercase(),
                            color = onBg,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.uploader,
                            color = onBg.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    PillButton(
                        icon = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                        contentDescription = if (isDownloaded) "Downloaded" else "Download",
                        active = isDownloaded,
                        loading = downloadProgress != null,
                        onClick = { if (!isDownloaded && downloadProgress == null) onDownload() },
                    )
                    Spacer(Modifier.width(8.dp))
                    PillButton(
                        icon = if (state.isCurrentLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (state.isCurrentLiked) "Unlike" else "Like",
                        active = state.isCurrentLiked,
                        onClick = onLike,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Slider + timestamps
                Slider(
                    value = if (durationMs > 0) positionMs / durationMs.toFloat() else 0f,
                    onValueChange = { v -> player?.seekTo((v * durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = onBg,
                        activeTrackColor = onBg,
                        inactiveTrackColor = onBg.copy(alpha = 0.3f),
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        formatTime(positionMs),
                        color = onBg.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        formatTime(durationMs),
                        color = onBg.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Big play pill flanked by prev/next dark capsules
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DarkCapsule(
                        icon = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        onClick = { player?.seekToPreviousMediaItem() },
                    )
                    PlayPill(
                        playing = player?.isPlaying == true,
                        onClick = {
                            if (player?.isPlaying == true) player.pause() else player?.play()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DarkCapsule(
                        icon = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        onClick = { player?.seekToNextMediaItem() },
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Bottom toolbar — queue / sleep / eq / lyrics / shuffle / repeat / more
                BottomToolbar(
                    state = state,
                    showLyrics = showLyricsPane,
                    sleepActive = state.sleepTimerEndTs != null,
                    onLyricsToggle = { showLyricsPane = !showLyricsPane },
                    onSleepClick = {
                        if (state.sleepTimerEndTs != null) onCancelSleepTimer() else showSleep = true
                    },
                    onShuffle = { player?.shuffleModeEnabled = !state.shuffleEnabled },
                    onRepeat = {
                        val next = when (state.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        player?.repeatMode = next
                    },
                )

                if (showLyricsPane) {
                    Spacer(Modifier.height(12.dp))
                    LyricsView(state = state, positionMs = positionMs, onTextColor = onBg)
                }
            }
        }
    }

    if (showSleep) {
        SleepTimerDialog(
            onDismiss = { showSleep = false },
            onPick = { mins -> onSetSleepTimer(mins); showSleep = false },
        )
    }
}

@Composable
private fun PillButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (active) Color.White else Color.White.copy(alpha = 0.95f)
    val fg = Color(0xFF111111)
    Box(
        Modifier
            .height(46.dp)
            .widthIn(min = 70.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(22.dp), strokeWidth = 2.dp, color = fg,
            )
        } else {
            Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(24.dp))
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
private fun NpIconButton(onClick: () -> Unit, tint: Color, content: @Composable () -> Unit) {
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
    state: MusicState,
    showLyrics: Boolean,
    sleepActive: Boolean,
    onLyricsToggle: () -> Unit,
    onSleepClick: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    val items: List<Triple<ImageVector, String, Pair<Boolean, () -> Unit>>> = listOf(
        Triple(Icons.Default.QueueMusic, "Queue", false to {}),
        Triple(Icons.Default.Bedtime, "Sleep", sleepActive to onSleepClick),
        Triple(Icons.Default.Tune, "Equalizer", false to {}),
        Triple(Icons.Default.Lyrics, "Lyrics", showLyrics to onLyricsToggle),
        Triple(Icons.Default.Shuffle, "Shuffle", state.shuffleEnabled to onShuffle),
        Triple(
            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
            "Repeat",
            (state.repeatMode != Player.REPEAT_MODE_OFF) to onRepeat,
        ),
        Triple(Icons.Default.MoreVert, "More", false to {}),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { i, (icon, desc, pair) ->
            val (active, onClick) = pair
            val isLast = i == items.lastIndex
            ToolbarChip(
                icon = icon, contentDescription = desc, active = active,
                solid = isLast, // last chip is the white solid "more" button
                onClick = onClick, modifier = Modifier.weight(1f),
            )
        }
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
private fun rememberDominantColor(thumbnailUrl: String?): State<Color> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { mutableStateOf(Color(0xFF8A6A48)) } // warm-tan default
    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl.isNullOrBlank()) return@LaunchedEffect
        runCatching {
            val loader = coil.ImageLoader(context)
            val req = coil.request.ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .allowHardware(false)
                .size(160)
                .build()
            val res = loader.execute(req)
            val drawable = (res as? SuccessResult)?.drawable as? BitmapDrawable
            val bitmap: Bitmap? = drawable?.bitmap
            if (bitmap != null) {
                Palette.from(bitmap).generate { p ->
                    val swatch = p?.dominantSwatch ?: p?.vibrantSwatch ?: p?.mutedSwatch
                    if (swatch != null) {
                        state.value = Color(swatch.rgb).copy(alpha = 1f)
                    }
                }
            }
        }
    }
    return state
}

@Composable
private fun LyricsView(state: MusicState, positionMs: Long, onTextColor: Color) {
    when {
        state.lyricsLoading -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = onTextColor)
        }
        state.lyrics?.syncedLyrics?.isNotBlank() == true -> {
            val parsed = remember(state.lyrics) {
                com.aioweb.app.data.lyrics.LyricsRepository.parseLrc(state.lyrics.syncedLyrics)
            }
            val activeIdx = remember(positionMs, parsed) {
                parsed.indexOfLast { it.first <= positionMs }.coerceAtLeast(0)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(parsed.size) { i ->
                    val (_, line) = parsed[i]
                    Text(
                        line,
                        color = if (i == activeIdx) onTextColor
                        else onTextColor.copy(alpha = 0.55f),
                        style = if (i == activeIdx) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        state.lyrics?.plainLyrics?.isNotBlank() == true -> {
            Text(
                state.lyrics.plainLyrics,
                color = onTextColor.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
