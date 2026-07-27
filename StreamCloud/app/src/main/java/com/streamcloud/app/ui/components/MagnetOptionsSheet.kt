package com.streamcloud.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.downloads.MovieDownloader
import com.streamcloud.app.player.PlayerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface DownloadState {
    data object Idle : DownloadState
    data object Connecting : DownloadState
    data class Error(val message: String) : DownloadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagnetOptionsSheet(
    source: PlayerSource,
    tmdbId: Long,
    title: String,
    posterUrl: String?,
    mediaType: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var dlState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
        ) {
            Text(
                "Download",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                source.label.lines().firstOrNull()?.trim() ?: source.addonName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "via ${source.addonName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(24.dp))

            when (val s = dlState) {
                is DownloadState.Idle -> {
                    Button(
                        onClick = {
                            scope.launch {
                                dlState = DownloadState.Connecting
                                try {
                                    val streamUrl = withContext(Dispatchers.IO) {
                                        sl.torrentService.resolveStreamUrlFromMagnet(source.url)
                                    }
                                    @Suppress("OPT_IN_USAGE")
                                    GlobalScope.launch(Dispatchers.IO) {
                                        MovieDownloader.download(
                                            context = context.applicationContext,
                                            tmdbId = tmdbId,
                                            title = title,
                                            posterUrl = posterUrl,
                                            mediaType = mediaType,
                                            url = streamUrl,
                                        )
                                    }
                                    onDismiss()
                                } catch (e: Exception) {
                                    dlState = DownloadState.Error(e.message ?: "Unknown error")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download to Device")
                    }
                }
                is DownloadState.Connecting -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Connecting to torrent…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This may take up to 30 seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
                is DownloadState.Error -> {
                    Text(
                        "Could not start download:\n${s.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { dlState = DownloadState.Idle },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Try Again")
                    }
                }
            }
        }
    }
}
