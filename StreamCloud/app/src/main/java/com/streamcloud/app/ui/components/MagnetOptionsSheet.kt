package com.streamcloud.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
    data class Downloading(val progress: Float) : DownloadState
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

    // Once we've fired the download, watch MovieDownloader.progressFlow so we can
    // reflect real-time progress in the sheet (same data the play-button bar uses).
    val progressMap by MovieDownloader.progressFlow.collectAsState(initial = emptyMap())
    val liveProgress = progressMap[tmdbId]

    // Sync progressMap → dlState while the sheet is open
    LaunchedEffect(liveProgress, dlState) {
        when {
            liveProgress != null && dlState is DownloadState.Downloading ->
                dlState = DownloadState.Downloading(liveProgress)
            // Download finished (key removed from map) → dismiss
            liveProgress == null && dlState is DownloadState.Downloading ->
                onDismiss()
        }
    }

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
                                    // Switch to Downloading state before firing the background job
                                    // so the LaunchedEffect can sync liveProgress → dlState.
                                    dlState = DownloadState.Downloading(0f)
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

                is DownloadState.Downloading -> {
                    DownloadProgressBar(fraction = s.progress)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Your download will continue in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
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

@Composable
private fun DownloadProgressBar(fraction: Float) {
    val primary       = MaterialTheme.colorScheme.primary
    val surface       = MaterialTheme.colorScheme.surface
    val onPrimary     = MaterialTheme.colorScheme.onPrimary
    val animatedFill  by animateFloatAsState(targetValue = fraction, label = "dlFill")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .drawBehind {
                drawRect(color = surface)
                drawRect(color = primary, size = Size(size.width * animatedFill, size.height))
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Download, null, tint = onPrimary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Downloading · ${(animatedFill * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = onPrimary,
            )
        }
    }
}
