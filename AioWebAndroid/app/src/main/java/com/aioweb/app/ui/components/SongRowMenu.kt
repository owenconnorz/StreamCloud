package com.aioweb.app.ui.components

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aioweb.app.data.ytmusic.YtPlayback
import com.aioweb.app.data.ytmusic.YtmSong
import kotlinx.coroutines.launch

/**
 * Reusable Metrolist-style 3-dot menu for any YT-Music-backed song row.
 *
 * Exposes Play / Play next / Add to queue / Download (or Remove download) /
 * Share actions — all routed through [YtPlayback] so they show up in the
 * global MiniPlayer without the caller needing to wire anything up.
 *
 * Drop it at the end of a song row's [Row]:
 * ```
 * Row { ...; SongRowMenu(song = s, onPlay = { /* caller's play hook */ }) }
 * ```
 * The caller still controls **how** playback starts (single-song vs playlist
 * context) via [onPlay]; the menu never assumes.
 */
@Composable
fun SongRowMenu(
    song: YtmSong,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }

    // Re-evaluate "is downloaded" whenever the menu opens so the Download /
    // Remove download label always reflects the current disk state.
    var downloaded by remember(song.videoId) {
        mutableStateOf(YtPlayback.isDownloaded(context, song))
    }
    LaunchedEffect(open, song.videoId) {
        if (open) downloaded = YtPlayback.isDownloaded(context, song)
    }

    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            Icons.Default.MoreVert,
            contentDescription = "More options",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Play") },
            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
            onClick = { open = false; onPlay() },
        )
        DropdownMenuItem(
            text = { Text("Play next") },
            leadingIcon = { Icon(Icons.Default.SkipNext, null) },
            onClick = {
                open = false
                scope.launch { runCatching { YtPlayback.playNext(context, song) } }
            },
        )
        DropdownMenuItem(
            text = { Text("Add to queue") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
            onClick = {
                open = false
                scope.launch { runCatching { YtPlayback.addToQueue(context, song) } }
            },
        )
        if (downloaded) {
            DropdownMenuItem(
                text = { Text("Remove download") },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = {
                    open = false
                    scope.launch {
                        runCatching { YtPlayback.removeDownload(context, song) }
                        downloaded = false
                    }
                },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Download") },
                leadingIcon = { Icon(Icons.Default.Download, null) },
                onClick = {
                    open = false
                    scope.launch { runCatching { YtPlayback.downloadSong(context, song) } }
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Share") },
            leadingIcon = { Icon(Icons.Default.Share, null) },
            onClick = {
                open = false
                val url = YtPlayback.watchUrl(song.videoId)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, song.title)
                    putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artist}\n$url")
                }
                context.startActivity(
                    Intent.createChooser(intent, "Share song").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            },
        )
    }
}
