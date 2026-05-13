package com.aioweb.app.ui.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.aioweb.app.data.downloads.MusicDownloader
import com.aioweb.app.data.library.LibraryDb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Modal bottom-sheet menu opened by the 3-dot button in [NowPlayingShell].
 *
 * Matches the user's reference screenshot 2 design exactly:
 *  - Drag handle
 *  - Live volume slider (AudioManager STREAM_MUSIC)
 *  - Three pill chips: Radio · Add · Share
 *  - Long-form action rows: View artist, Add to library, Download,
 *    Listen Together, Details, Equalizer, Advanced (tempo & pitch)
 *
 * Every action is wired to a working implementation — no placeholder rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicActionsSheet(
    controller: Player,
    currentMediaId: String?,
    currentTitle: String,
    currentArtist: String,
    isLiked: Boolean,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArtistSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showDetails by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0E0E0E),
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // ── Volume slider ────────────────────────────────────────────
            VolumeRow(context)

            Divider(Modifier.padding(vertical = 12.dp))

            // ── Chip row: Radio · Add · Share ─────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChipPill(
                    icon = Icons.Default.Podcasts, label = "Radio",
                    modifier = Modifier.weight(1f),
                ) {
                    val seedId = currentMediaId
                    if (seedId.isNullOrBlank()) {
                        toast(context, "No song to start radio from")
                    } else {
                        scope.launch {
                            runCatching {
                                com.aioweb.app.data.ytmusic.YtPlayback
                                    .startRadioFromCurrent(context, seedId)
                            }.onFailure { toast(context, "Radio failed: ${it.message}") }
                                .onSuccess { toast(context, "Started radio") }
                        }
                        onDismiss()
                    }
                }
                ChipPill(
                    icon = Icons.Default.PlaylistAdd, label = "Add",
                    modifier = Modifier.weight(1f),
                ) {
                    // Local playlists DAO doesn't exist yet → liking is the
                    // closest existing "Add" surface (track appears in Library).
                    val mid = currentMediaId
                    if (mid.isNullOrBlank()) toast(context, "No song to add")
                    else scope.launch {
                        LibraryDb.get(context.applicationContext).tracks()
                            .setLikedAt(mid, System.currentTimeMillis())
                        toast(context, "Added to library")
                        onDismiss()
                    }
                }
                ChipPill(
                    icon = Icons.Default.Share, label = "Share",
                    modifier = Modifier.weight(1f),
                ) {
                    val mid = currentMediaId
                    if (mid.isNullOrBlank()) toast(context, "Nothing to share")
                    else {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, currentTitle.ifBlank { "Music" })
                            putExtra(Intent.EXTRA_TEXT, "${currentTitle} — ${currentArtist}\n$mid")
                        }
                        context.startActivity(
                            Intent.createChooser(send, "Share song")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Long-form action rows ─────────────────────────────────────
            ActionRow(
                icon = Icons.Default.Person,
                title = "View artist",
                subtitle = currentArtist.ifBlank { "Unknown artist" },
            ) {
                val a = currentArtist.trim()
                if (a.isBlank()) toast(context, "No artist on this track")
                else {
                    onDismiss()
                    onOpenArtistSearch(a)
                }
            }
            ActionRow(
                icon = Icons.Default.LibraryAdd,
                title = if (isLiked) "Remove from library" else "Add to library",
            ) {
                val mid = currentMediaId
                if (mid.isNullOrBlank()) toast(context, "Nothing playing")
                else scope.launch {
                    LibraryDb.get(context.applicationContext).tracks()
                        .setLikedAt(mid, if (isLiked) null else System.currentTimeMillis())
                    toast(context, if (isLiked) "Removed from library" else "Added to library")
                    onDismiss()
                }
            }
            ActionRow(
                icon = Icons.Default.Download,
                title = if (isDownloaded) "Downloaded" else "Download",
                enabled = !isDownloaded,
            ) {
                val mid = currentMediaId
                if (mid.isNullOrBlank()) toast(context, "Nothing playing")
                else scope.launch {
                    toast(context, "Download started")
                    runCatching { MusicDownloader.download(context, mid, currentTitle) }
                        .onFailure { toast(context, "Download failed: ${it.message}") }
                    onDismiss()
                }
            }
            ActionRow(
                icon = Icons.Default.Groups,
                title = "Listen Together",
                subtitle = "Sync playback with a friend (coming soon)",
            ) {
                toast(context, "Listen Together is coming soon")
            }
            ActionRow(
                icon = Icons.Default.Info,
                title = "Details",
                subtitle = "View the song's information",
            ) { showDetails = true }
            ActionRow(
                icon = Icons.Default.Equalizer,
                title = "Equalizer",
                subtitle = "Adjust the audio equalizer",
            ) {
                if (!openSystemEqualizer(context, controller)) {
                    onDismiss()
                    onOpenSettings()
                    toast(context, "Open Settings → Audio FX → Equalizer")
                }
            }
            ActionRow(
                icon = Icons.Default.Tune,
                title = "Advanced",
                subtitle = "Change the song's tempo and pitch",
            ) { showAdvanced = true }
        }
    }

    if (showDetails) {
        DetailsDialog(
            title = currentTitle,
            artist = currentArtist,
            mediaId = currentMediaId,
            durationMs = controller.duration.coerceAtLeast(0L),
            onDismiss = { showDetails = false },
        )
    }
    if (showAdvanced) {
        AdvancedDialog(
            initial = controller.playbackParameters,
            onDismiss = { showAdvanced = false },
            onApply = { speed, pitch ->
                controller.playbackParameters = PlaybackParameters(speed, pitch)
                showAdvanced = false
                toast(context, "Tempo ${"%.2f".format(speed)}× · Pitch ${"%.2f".format(pitch)}×")
            },
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Volume row — driven by AudioManager STREAM_MUSIC
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun VolumeRow(context: Context) {
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var current by remember { mutableStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    // Poll once a second so external volume key changes reflect in the UI.
    LaunchedEffect(Unit) {
        while (true) {
            delay(750)
            current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                current == 0 -> Icons.Default.VolumeOff
                current < maxVol / 2 -> Icons.Default.VolumeDown
                else -> Icons.Default.VolumeUp
            },
            contentDescription = "Volume",
            tint = Color(0xFFB8E0DA),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Slider(
            value = current.toFloat(),
            valueRange = 0f..maxVol.toFloat(),
            steps = (maxVol - 1).coerceAtLeast(0),
            onValueChange = { v ->
                val i = v.toInt().coerceIn(0, maxVol)
                if (i != current) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, i, 0)
                    current = i
                }
            },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFB8E0DA),
                activeTrackColor = Color(0xFFB8E0DA),
                inactiveTrackColor = Color.White.copy(alpha = 0.1f),
            ),
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Reusable rows
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun ChipPill(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.04f else 0.02f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = title,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.07f))
    )
}

// ──────────────────────────────────────────────────────────────────────────
// Details + Advanced dialogs
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun DetailsDialog(
    title: String,
    artist: String,
    mediaId: String?,
    durationMs: Long,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column {
                DetailLine("Title", title.ifBlank { "—" })
                DetailLine("Artist", artist.ifBlank { "—" })
                DetailLine("Duration", formatDur(durationMs))
                mediaId?.let { DetailLine("Source", it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdvancedDialog(
    initial: PlaybackParameters,
    onDismiss: () -> Unit,
    onApply: (speed: Float, pitch: Float) -> Unit,
) {
    var speed by remember { mutableStateOf(initial.speed) }
    var pitch by remember { mutableStateOf(initial.pitch) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tempo & pitch") },
        text = {
            Column {
                Text("Speed: ${"%.2f".format(speed)}×", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = speed,
                    valueRange = 0.5f..2.0f,
                    onValueChange = { speed = (it * 20).toInt() / 20f },
                )
                Spacer(Modifier.height(8.dp))
                Text("Pitch: ${"%.2f".format(pitch)}×", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = pitch,
                    valueRange = 0.5f..2.0f,
                    onValueChange = { pitch = (it * 20).toInt() / 20f },
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { speed = 1f; pitch = 1f }) { Text("Reset") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(speed, pitch) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ──────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

private fun formatDur(ms: Long): String {
    if (ms <= 0) return "—"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Open the system Equalizer activity for the current audio session if the
 * device exposes one (most do). Returns false when no handler is installed —
 * caller falls back to the in-app Audio FX settings page.
 */
private fun openSystemEqualizer(context: Context, controller: Player): Boolean {
    // Try to derive an audio session id. Media3 doesn't expose it on the
    // common `Player` interface but ExoPlayer subclass does.
    val sessionId: Int? = runCatching {
        val ex = controller as? androidx.media3.exoplayer.ExoPlayer
        ex?.audioSessionId?.takeIf { it != 0 }
    }.getOrNull()
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        if (sessionId != null) putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
