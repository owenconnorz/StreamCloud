package com.streamcloud.app.ui.components

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamcloud.app.data.api.YtsTorrent
import com.streamcloud.app.data.torrent.TorrentRepository
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDownloadSheet(
    onDismiss: () -> Unit,
    imdbId: String?,
    title: String,
    year: Int?,
    torrentRepo: TorrentRepository,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    var loading by remember { mutableStateOf(true) }
    var torrents by remember { mutableStateOf<List<YtsTorrent>>(emptyList()) }
    var movieTitle by remember { mutableStateOf(title) }
    var movieYear by remember { mutableStateOf(year ?: 0) }
    var error by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(imdbId, title) {
        loading = true; error = null
        val movie = torrentRepo.find(imdbId = imdbId, title = title, year = year)
        if (movie == null || movie.torrents.isEmpty()) {
            error = "No torrents found for \"$title\" on YTS.\nOnly movies available via YTS."
        } else {
            movieTitle = movie.title
            movieYear = movie.year
            torrents = movie.torrents.sortedWith(
                compareByDescending<YtsTorrent> { it.seeds }
                    .thenByDescending { it.sizeBytes }
            )
        }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
        ) {
            Text(
                "Download Torrent",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                movieTitle.takeIf { it.isNotBlank() } ?: title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            when {
                loading -> {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Searching YTS…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                error != null -> {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                else -> {
                    Text(
                        "Source: YTS · ${torrents.size} option${if (torrents.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    torrents.forEach { torrent ->
                        TorrentOptionCard(
                            torrent = torrent,
                            movieTitle = movieTitle,
                            movieYear = movieYear,
                            onDownloadTorrent = {
                                downloadTorrentFile(context, torrent, movieTitle)
                                toast = "Downloading ${torrent.quality} .torrent file…"
                            },
                            onCopyMagnet = {
                                val magnet = torrent.magnetLink(movieTitle, movieYear)
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("magnet", magnet))
                                toast = "Magnet link copied to clipboard"
                            },
                            onOpenExternal = {
                                val magnet = torrent.magnetLink(movieTitle, movieYear)
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(magnet))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            toast?.let { msg ->
                LaunchedEffect(msg) {
                    delay(2500)
                    toast = null
                }
                Snackbar(
                    modifier = Modifier.padding(vertical = 4.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    }
}

@Composable
private fun TorrentOptionCard(
    torrent: YtsTorrent,
    movieTitle: String,
    movieYear: Int,
    onDownloadTorrent: () -> Unit,
    onCopyMagnet: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val meta = buildList {
        if (torrent.size.isNotBlank()) add(torrent.size)
        if (torrent.videoCodec.isNotBlank()) add(torrent.videoCodec.uppercase())
        if (torrent.type.isNotBlank()) add(torrent.type.uppercase())
    }.joinToString(" · ")

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        torrent.quality,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "▲ ${torrent.seeds}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "▼ ${torrent.peers}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDownloadTorrent,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(".torrent", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onCopyMagnet,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Magnet", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onOpenExternal,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.OpenInNew, "Open in torrent app", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun downloadTorrentFile(context: Context, torrent: YtsTorrent, title: String) {
    if (torrent.url.isBlank()) return
    runCatching {
        val safeName = "${title}_${torrent.quality}.torrent"
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
        val req = DownloadManager.Request(Uri.parse(torrent.url))
            .setTitle("$title (${torrent.quality})")
            .setDescription("Downloading torrent file via YTS")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(req)
    }
}
