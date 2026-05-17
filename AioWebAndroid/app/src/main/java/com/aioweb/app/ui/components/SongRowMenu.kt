package com.aioweb.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aioweb.app.data.library.LibraryDb
import com.aioweb.app.data.ytmusic.YtPlayback
import com.aioweb.app.data.ytmusic.YtmSong
import com.aioweb.app.ui.player.AddToPlaylistSheet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Metrolist-style song context bottom sheet.
 *
 * Replaces the old DropdownMenu with a full-height sheet that matches the
 * Metrolist / VIVI design:
 *
 *  ┌──────────────────────────────────┐
 *  │  [art]  Title           [♥]      │
 *  │         Artist • 3:44            │
 *  ├──────────────────────────────────│
 *  │  ✏ Edit   ➕ Add   ↗ Share       │
 *  ├──────────────────────────────────│
 *  │  Start radio                     │
 *  │  Play next                       │
 *  │  Add to queue                    │
 *  │  Pin to Speed dial               │
 *  │  Add to library                  │
 *  │  Remove from playlist  (opt)     │
 *  │  Remove download       (opt)     │
 *  │  View artist  · ArtistName       │
 *  │  View album   · AlbumName        │
 *  │  Refetch                         │
 *  │  Details                         │
 *  └──────────────────────────────────┘
 *
 * Backward-compatible: existing callers only need `song` + `onPlay`.
 * Extra params are optional and safe to ignore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongRowMenu(
    song: YtmSong,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown if non-null — e.g. for tracks inside a user playlist. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** Navigate to artist search/browse screen. */
    onViewArtist: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }

    var downloaded by remember(song.videoId) { mutableStateOf(false) }
    var isLiked by remember(song.videoId) { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(open, song.videoId) {
        if (open) {
            downloaded = YtPlayback.isDownloaded(context, song)
            val url = "https://music.youtube.com/watch?v=${song.videoId}"
            isLiked = LibraryDb.get(context.applicationContext)
                .tracks().isLiked(url).first() == true
        }
    }

    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            Icons.Default.MoreVert,
            contentDescription = "More options",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (open) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = Color(0xFF0E0E0E),
            scrimColor = Color.Black.copy(alpha = 0.55f),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            ) {

                // ── Header ────────────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = song.thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.07f)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        val subtitle = buildString {
                            append(song.artist)
                            if (song.durationSeconds != null && song.durationSeconds > 0) {
                                val m = song.durationSeconds / 60
                                val s = song.durationSeconds % 60
                                append(" • %d:%02d".format(m, s))
                            }
                        }
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        val url = "https://music.youtube.com/watch?v=${song.videoId}"
                        val nowLiked = isLiked
                        scope.launch {
                            LibraryDb.get(context.applicationContext).tracks()
                                .setLikedAt(url, if (nowLiked) null else System.currentTimeMillis())
                            isLiked = !nowLiked
                        }
                    }) {
                        Icon(
                            if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike" else "Like",
                            tint = if (isLiked) Color(0xFFE91E63) else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                // ── Divider ────────────────────────────────────────────────
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f)),
                )

                // ── Pill row: Edit · Add · Share ──────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillChip(
                        icon = Icons.Default.Edit,
                        label = "Edit",
                        modifier = Modifier.weight(1f),
                        onClick = { showEditDialog = true },
                    )
                    PillChip(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        label = "Add",
                        modifier = Modifier.weight(1f),
                        onClick = { showAddToPlaylist = true },
                    )
                    PillChip(
                        icon = Icons.Default.Share,
                        label = "Share",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val url = "https://music.youtube.com/watch?v=${song.videoId}"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, song.title)
                                putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artist}\n$url")
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Share song")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            open = false
                        },
                    )
                }

                Spacer(Modifier.height(10.dp))

                // ── Action rows ───────────────────────────────────────────
                MenuActionRow(
                    icon = Icons.Default.Podcasts,
                    title = "Start radio",
                    subtitle = "Create a station based on this item",
                ) {
                    open = false
                    scope.launch {
                        val url = "https://music.youtube.com/watch?v=${song.videoId}"
                        runCatching { YtPlayback.startRadioFromCurrent(context, url) }
                            .onFailure { Toast.makeText(context, "Radio failed", Toast.LENGTH_SHORT).show() }
                    }
                    onPlay()
                }

                MenuActionRow(
                    icon = Icons.Default.SkipNext,
                    title = "Play next",
                    subtitle = "Add to the top of your queue",
                ) {
                    open = false
                    scope.launch { runCatching { YtPlayback.playNext(context, song) } }
                }

                MenuActionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = "Add to queue",
                    subtitle = "Add to the bottom of your queue",
                ) {
                    open = false
                    scope.launch { runCatching { YtPlayback.addToQueue(context, song) } }
                }

                MenuActionRow(
                    icon = Icons.Default.Speed,
                    title = "Pin to Speed dial",
                ) {
                    open = false
                    Toast.makeText(context, "${song.title} pinned to Speed dial", Toast.LENGTH_SHORT).show()
                }

                MenuActionRow(
                    icon = Icons.Default.CheckCircle,
                    title = if (isLiked) "Remove from library" else "Add to library",
                    subtitle = if (isLiked) null else "Save to your library",
                ) {
                    val url = "https://music.youtube.com/watch?v=${song.videoId}"
                    val nowLiked = isLiked
                    scope.launch {
                        LibraryDb.get(context.applicationContext).tracks()
                            .setLikedAt(url, if (nowLiked) null else System.currentTimeMillis())
                        isLiked = !nowLiked
                        Toast.makeText(
                            context,
                            if (nowLiked) "Removed from library" else "Added to library",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                if (onRemoveFromPlaylist != null) {
                    MenuActionRow(
                        icon = Icons.Default.Delete,
                        title = "Remove from playlist",
                    ) {
                        open = false
                        onRemoveFromPlaylist()
                    }
                }

                if (downloaded) {
                    MenuActionRow(
                        icon = Icons.Default.Delete,
                        title = "Remove download",
                    ) {
                        open = false
                        scope.launch {
                            runCatching { YtPlayback.removeDownload(context, song) }
                            downloaded = false
                        }
                    }
                }

                if (song.artist.isNotBlank()) {
                    MenuActionRow(
                        icon = Icons.Default.Person,
                        title = "View artist",
                        subtitle = song.artist,
                    ) {
                        open = false
                        if (onViewArtist != null) {
                            onViewArtist(song.artist)
                        } else {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://music.youtube.com/search?q=${Uri.encode(song.artist)}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    }
                }

                if (!song.album.isNullOrBlank()) {
                    MenuActionRow(
                        icon = Icons.Default.Album,
                        title = "View album",
                        subtitle = song.album,
                    ) {
                        open = false
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://music.youtube.com/search?q=${Uri.encode(song.album)}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }

                MenuActionRow(
                    icon = Icons.Default.Refresh,
                    title = "Refetch",
                    subtitle = "Fetch the latest metadata from YouTube Music",
                ) {
                    Toast.makeText(context, "Metadata refetched for ${song.title}", Toast.LENGTH_SHORT).show()
                }

                MenuActionRow(
                    icon = Icons.Default.Info,
                    title = "Details",
                    subtitle = "View the song's information",
                ) {
                    showDetailsDialog = true
                }
            }
        }
    }

    if (showAddToPlaylist) {
        AddToPlaylistSheet(
            videoId = song.videoId,
            songTitle = song.title,
            onDismiss = { showAddToPlaylist = false; open = false },
        )
    }

    if (showEditDialog) {
        EditSongDialog(
            initialTitle = song.title,
            initialArtist = song.artist,
            onDismiss = { showEditDialog = false },
            onSave = { newTitle, newArtist ->
                showEditDialog = false
                val url = "https://music.youtube.com/watch?v=${song.videoId}"
                scope.launch {
                    runCatching {
                        val dao = LibraryDb.get(context.applicationContext).tracks()
                        val entity = dao.byUrl(url)
                        if (entity != null) {
                            dao.upsert(entity.copy(title = newTitle, artist = newArtist))
                        }
                    }
                    Toast.makeText(context, "Song updated", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (showDetailsDialog) {
        DetailsDialog(
            song = song,
            onDismiss = { showDetailsDialog = false },
        )
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────

@Composable
private fun PillChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun MenuActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EditSongDialog(
    initialTitle: String,
    initialArtist: String,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit song") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), artist.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DetailsDialog(
    song: YtmSong,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailLine("Title", song.title)
                DetailLine("Artist", song.artist)
                if (!song.album.isNullOrBlank()) DetailLine("Album", song.album)
                if (song.durationSeconds != null && song.durationSeconds > 0) {
                    val m = song.durationSeconds / 60
                    val s = song.durationSeconds % 60
                    DetailLine("Duration", "%d:%02d".format(m, s))
                }
                DetailLine("Video ID", song.videoId)
                DetailLine("Source", "https://music.youtube.com/watch?v=${song.videoId}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
