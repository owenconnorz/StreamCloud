package com.streamcloud.app.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class StreamDownloadRequest(
    val url: String,
    val title: String,
    val description: String = "",
    val headers: Map<String, String> = emptyMap(),
)

object VideoDownloadManager {
    fun enqueue(context: Context, req: StreamDownloadRequest): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val r = DownloadManager.Request(Uri.parse(req.url)).apply {
            setTitle(req.title)
            setDescription(req.description.ifBlank { "StreamCloud download" })
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, sanitiseFileName(req.title))
            req.headers.forEach { (k, v) -> addRequestHeader(k, v) }
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        return dm.enqueue(r)
    }

    fun listDownloads(context: Context): List<DownloadItem> {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query()
        val cursor = dm.query(query)
        val result = mutableListOf<DownloadItem>()
        try {
            while (cursor.moveToNext()) {
                val id     = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val title  = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total  = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val done   = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val pct = if (total > 0) (done.toFloat() / total * 100).toInt() else 0
                result.add(DownloadItem(id, title ?: "Download $id", status, pct, done, total, localUri))
            }
        } finally { cursor.close() }
        return result
    }

    fun remove(context: Context, id: Long) {
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
    }

    private fun sanitiseFileName(name: String): String =
        name.replace(Regex("""[^a-zA-Z0-9._\- ]"""), "_").trimEnd() + ".mp4"
}

data class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val progressPct: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val localUri: String?,
)

fun DownloadItem.statusLabel(): String = when (status) {
    DownloadManager.STATUS_RUNNING  -> "Downloading $progressPct%"
    DownloadManager.STATUS_PAUSED   -> "Paused $progressPct%"
    DownloadManager.STATUS_PENDING  -> "Queued"
    DownloadManager.STATUS_SUCCESSFUL -> "Complete"
    DownloadManager.STATUS_FAILED   -> "Failed"
    else                            -> "Unknown"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloadsScreen(
    onBack: () -> Unit,
    onPlayFile: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            items = VideoDownloadManager.listDownloads(context)
            delay(2_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No downloads yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Long-press a stream to copy its URL, or tap ⬇ to download",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    DownloadItemCard(
                        item = item,
                        onPlay = {
                            val uri = item.localUri ?: return@DownloadItemCard
                            onPlayFile(uri, item.title)
                        },
                        onDelete = { VideoDownloadManager.remove(context, item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(item.statusLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.status == DownloadManager.STATUS_RUNNING) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.progressPct / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (item.status == DownloadManager.STATUS_SUCCESSFUL && item.localUri != null) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, "Play",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}
