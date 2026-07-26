package com.streamcloud.app.cast

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import coil.compose.AsyncImage
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.delay
import com.streamcloud.app.cast.dlna.DlnaDevice
import com.streamcloud.app.cast.dlna.DlnaDiscovery
import com.streamcloud.app.cast.dlna.DlnaRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastButton(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    showDialog: Boolean = false,
    onShowDialogChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    val castContext = remember(context) {
        runCatching { CastContext.getSharedInstance(context.applicationContext) }
            .getOrNull()
    } ?: return

    val mediaRouter = remember(context) { MediaRouter.getInstance(context.applicationContext) }
    val selector = remember(castContext) { castContext.mergedSelector ?: MediaRouteSelector.EMPTY }

    val routes = remember { mutableStateListOf<MediaRouter.RouteInfo>() }
    var selectedRouteId by remember { mutableStateOf<String?>(null) }

    val dlnaState by DlnaRepository.state.collectAsState()
    val dlnaSelected by DlnaRepository.selectedDevice.collectAsState()
    val dlnaDevices = (dlnaState as? com.streamcloud.app.cast.dlna.DlnaRepository.State.Ready)?.devices ?: emptyList()

    DisposableEffect(selector, showDialog) {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                refreshRoutes(router, selector, routes)
            }
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                refreshRoutes(router, selector, routes)
            }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                refreshRoutes(router, selector, routes)
            }
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
                selectedRouteId = route.id
            }
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
                if (selectedRouteId == route.id) selectedRouteId = null
            }
        }
        val flags = if (showDialog) {
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
        } else {
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
        }
        mediaRouter.addCallback(selector, callback, flags)
        refreshRoutes(mediaRouter, selector, routes)
        selectedRouteId = mediaRouter.selectedRoute.id.takeIf { !mediaRouter.selectedRoute.isDefault }
        onDispose { mediaRouter.removeCallback(callback) }
    }

    LaunchedEffect(showDialog) {
        while (showDialog) {
            delay(1500)
            refreshRoutes(mediaRouter, selector, routes)
        }
    }

    LaunchedEffect(showDialog) {
        if (showDialog) DlnaRepository.discover(context)
    }

    val connected = selectedRouteId != null || dlnaSelected != null

    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onShowDialogChange(true) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (connected) Icons.Default.CastConnected else Icons.Default.Cast,
            contentDescription = if (connected) "Cast (connected)" else "Cast",
            tint = if (connected) Color(0xFF66D9A6) else tint,
            modifier = Modifier.size(22.dp),
        )
    }

    if (showDialog) {
        CastRouteDialog(
            routes = routes,
            selectedRouteId = selectedRouteId,
            dlnaDevices = dlnaDevices,
            selectedDlnaDevice = dlnaSelected,
            isDlnaDiscovering = dlnaState is com.streamcloud.app.cast.dlna.DlnaRepository.State.Discovering,
            onPickRoute = { route ->
                DlnaRepository.selectDevice(null)
                mediaRouter.selectRoute(route)
                onShowDialogChange(false)
            },
            onPickDlna = { device ->
                mediaRouter.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
                DlnaRepository.selectDevice(device)
                onShowDialogChange(false)
            },
            onDisconnect = {
                mediaRouter.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
                DlnaRepository.selectDevice(null)
                onShowDialogChange(false)
            },
            onDismiss = { onShowDialogChange(false) },
        )
    }
}

private fun refreshRoutes(
    router: MediaRouter,
    selector: MediaRouteSelector,
    out: androidx.compose.runtime.snapshots.SnapshotStateList<MediaRouter.RouteInfo>,
) {
    val filtered = router.routes.filter { route ->
        !route.isDefault &&
            !route.isBluetooth &&
            route.matchesSelector(selector)
    }
    if (filtered.map { it.id } != out.map { it.id }) {
        out.clear()
        out.addAll(filtered)
    }
}

@Composable
private fun CastRouteDialog(
    routes: List<MediaRouter.RouteInfo>,
    selectedRouteId: String?,
    dlnaDevices: List<DlnaDevice>,
    selectedDlnaDevice: DlnaDevice?,
    isDlnaDiscovering: Boolean,
    onPickRoute: (MediaRouter.RouteInfo) -> Unit,
    onPickDlna: (DlnaDevice) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val anyConnected = selectedRouteId != null || selectedDlnaDevice != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cast to") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (routes.isNotEmpty()) {
                    Text(
                        "Chromecast / Google TV",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    routes.forEach { route ->
                        RouteRow(
                            route = route,
                            isSelected = route.id == selectedRouteId,
                            onClick = { onPickRoute(route) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Samsung / LG Smart TV (DLNA)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                when {
                    isDlnaDiscovering && dlnaDevices.isEmpty() -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Scanning for TVs on your network…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    dlnaDevices.isEmpty() -> {
                        Text(
                            "No DLNA TVs found. Make sure your TV is on the same Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    else -> {
                        dlnaDevices.forEach { device ->
                            DlnaDeviceRow(
                                device = device,
                                isSelected = device.udn == selectedDlnaDevice?.udn,
                                onClick = { onPickDlna(device) },
                            )
                        }
                    }
                }

                if (routes.isEmpty() && !isDlnaDiscovering && dlnaDevices.isEmpty()) {
                    Text(
                        "Looking for nearby devices…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                if (anyConnected) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DlnaDeviceRow(
    device: DlnaDevice,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    ) {
        Icon(
            Icons.Default.Tv,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                device.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isSelected) {
            Text("Connected", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RouteRow(
    route: MediaRouter.RouteInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    ) {
        Icon(
            Icons.Default.Tv,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                route.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            val desc = route.description?.takeIf { it.isNotBlank() }
            if (desc != null) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isSelected) {
            Text("Connected", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun rememberCastController(
    streamUrl: String,
    title: String,
    artworkUrl: String? = null,
    contentType: String? = null,
    headers: Map<String, String> = emptyMap(),
): androidx.compose.runtime.MutableState<Boolean> {
    val context = LocalContext.current
    val isCasting = remember { mutableStateOf(false) }

    val isLocalhost = remember(streamUrl) {
        streamUrl.startsWith("http://127.0.0.1") || streamUrl.startsWith("http://localhost")
    }
    // Local downloaded files (content:// from MediaStore or file://) cannot be fetched
    // directly by Chromecast — the phone serves them via the cast proxy instead.
    val isLocalFile = remember(streamUrl) {
        streamUrl.startsWith("content://") || streamUrl.startsWith("file://")
    }

    // The URL we actually give Chromecast.  Starts as the direct URL while the
    // phone-side proxy spins up, then switches to http://PHONE_IP:PORT once ready.
    // The proxy is critical for Nuvio/Stremio/debrid streams because:
    //   • Debrid CDN links are IP-restricted — Chromecast's IP gets a 403, phone's IP is allowed.
    //   • Many streams require Referer/User-Agent headers the Cast receiver cannot send.
    //   • HLS segment rewriting ensures every chunk also flows through the proxy.
    //   • Local files (content://) need the proxy to be reachable by Chromecast at all.
    // Chromecast CAN load HTTP from local-network IPs via its native media APIs —
    // the "mixed content" restriction only applies to XHR/fetch inside web pages.
    var castUrl by remember { mutableStateOf(if (isLocalhost || isLocalFile) "" else streamUrl) }
    var activeMime by remember { mutableStateOf(contentType ?: guessMimeType(streamUrl)) }

    LaunchedEffect(streamUrl) {
        if (streamUrl.isBlank() || isLocalhost) return@LaunchedEffect

        // Give the proxy server access to ContentResolver for local file serving.
        CastProxyServer.appContext = context

        // Start the proxy on an IO thread (opens a ServerSocket)
        val proxy = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CastProxyServer.start(streamUrl, if (isLocalFile) emptyMap() else headers)
        }

        if (proxy != null) {
            castUrl = proxy
            // Wait for the proxy's background probe (up to 5 s) to get the real MIME
            repeat(10) {
                delay(500)
                val ct = CastProxyServer.detectedContentType
                if (!ct.isNullOrBlank()) {
                    activeMime = ct
                    return@LaunchedEffect
                }
            }
        } else {
            // Proxy unavailable — local files can't be cast without it; HTTP streams can try direct
            if (isLocalFile) return@LaunchedEffect
            // Fall back to direct URL + manual HEAD probe
            castUrl = streamUrl
            val probed = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .followRedirects(true).followSslRedirects(true).build()
                    val reqBuilder = okhttp3.Request.Builder().url(streamUrl).head()
                    headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        resp.header("Content-Type")?.substringBefore(';')?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }
                }
            }.getOrNull()
            if (!probed.isNullOrBlank()) activeMime = probed
        }
    }

    // Re-send media to TV whenever castUrl or activeMime changes.
    // Covers: proxy URL becoming available, MIME type resolved, existing session on entry.
    LaunchedEffect(castUrl, activeMime) {
        if (castUrl.isBlank()) return@LaunchedEffect
        val castCtx = runCatching {
            CastContext.getSharedInstance(context.applicationContext)
        }.getOrNull() ?: return@LaunchedEffect
        val session = castCtx.sessionManager.currentCastSession ?: return@LaunchedEffect
        isCasting.value = true
        loadRemoteMedia(session, castUrl, title, artworkUrl, activeMime)
    }

    DisposableEffect(streamUrl, title, artworkUrl, context) {
        val castContext = runCatching {
            CastContext.getSharedInstance(context.applicationContext)
        }.getOrNull()
        if (castContext == null) return@DisposableEffect onDispose { }

        val listener = object : com.google.android.gms.cast.framework.SessionManagerListener<
            com.google.android.gms.cast.framework.CastSession,
            > {
            override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {
                isCasting.value = true
                loadRemoteMedia(session, castUrl, title, artworkUrl, activeMime)
            }
            override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) {
                isCasting.value = true
                loadRemoteMedia(session, castUrl, title, artworkUrl, activeMime)
            }
            override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
                isCasting.value = false
                CastProxyServer.stop()
            }
            override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {
                isCasting.value = false
            }
            override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {}
            override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
            override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
        }
        castContext.sessionManager.addSessionManagerListener(
            listener, com.google.android.gms.cast.framework.CastSession::class.java,
        )
        onDispose {
            castContext.sessionManager.removeSessionManagerListener(
                listener, com.google.android.gms.cast.framework.CastSession::class.java,
            )
            CastProxyServer.stop()
        }
    }
    return isCasting
}

private fun loadRemoteMedia(
    session: com.google.android.gms.cast.framework.CastSession,
    streamUrl: String,
    title: String,
    artworkUrl: String?,
    contentType: String?,
) {




    if (streamUrl.isBlank()) return
    val client = session.remoteMediaClient ?: return
    if (streamUrl.startsWith("http://127.0.0.1") || streamUrl.startsWith("http://localhost")) {
        return
    }
    val metadata = com.google.android.gms.cast.MediaMetadata(
        com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE,
    ).apply {
        putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, title)
        if (!artworkUrl.isNullOrBlank()) {
            addImage(com.google.android.gms.common.images.WebImage(android.net.Uri.parse(artworkUrl)))
        }
    }
    val mime = contentType ?: guessMimeType(streamUrl)
    val mediaInfo = com.google.android.gms.cast.MediaInfo.Builder(streamUrl)
        .setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED)
        .setContentType(mime)
        .setMetadata(metadata)
        .build()
    client.load(
        com.google.android.gms.cast.MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build(),
    )
}

internal fun guessMimeType(url: String): String {
    val path = url.substringBefore('?').lowercase()
    val query = url.substringAfter('?', "").lowercase()
    return when {
        // Explicit HLS indicators
        path.endsWith(".m3u8") -> "application/x-mpegURL"
        path.contains("/m3u8") -> "application/x-mpegURL"
        path.contains("/hls/") -> "application/x-mpegURL"
        path.contains("/hlsvod/") -> "application/x-mpegURL"
        path.contains("/playlist") && !path.endsWith(".mp4") -> "application/x-mpegURL"
        query.contains("type=hls") || query.contains("format=hls") -> "application/x-mpegURL"
        // DASH
        path.endsWith(".mpd") -> "application/dash+xml"
        path.contains("/dash/") -> "application/dash+xml"
        // Other formats
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".mkv") -> "video/x-matroska"
        path.endsWith(".mov") -> "video/quicktime"
        path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
        // Stremio/Nuvio proxy streams often declare type in URL path
        else -> "video/mp4"
    }
}

fun initCast(context: Context) {
    runCatching { CastContext.getSharedInstance(context.applicationContext) }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Full-screen cast remote controller shown on the phone while the movie plays on the TV.
 * - Polls RemoteMediaClient every 500 ms for position / duration / playing state.
 * - Exposes play/pause, ±10 s skip, and a seek slider.
 * - Shows the movie artwork and "Casting to <device>" label.
 * - If media never starts (IDLE state after grace period), shows "Send to TV" retry button.
 */
@Composable
fun CastRemoteController(
    title: String,
    streamUrl: String = "",
    artworkUrl: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val castContext = remember(context) {
        runCatching { CastContext.getSharedInstance(context.applicationContext) }.getOrNull()
    } ?: return

    var positionMs    by remember { mutableLongStateOf(0L) }
    var durationMs    by remember { mutableLongStateOf(0L) }
    var isPlaying     by remember { mutableStateOf(true) }
    var isSeeking     by remember { mutableStateOf(false) }
    var seekProgress  by remember { mutableStateOf(0f) }
    var playerState   by remember { mutableStateOf(MediaStatus.PLAYER_STATE_UNKNOWN) }
    var idleSeconds   by remember { mutableStateOf(0) }
    val deviceName    = remember {
        castContext.sessionManager.currentCastSession?.castDevice?.friendlyName ?: "TV"
    }

    // Poll remote state every 500 ms; track consecutive idle seconds for retry prompt
    LaunchedEffect(Unit) {
        while (true) {
            val client = castContext.sessionManager.currentCastSession?.remoteMediaClient
            if (client != null) {
                val status = client.mediaStatus
                val state  = status?.playerState ?: MediaStatus.PLAYER_STATE_UNKNOWN
                playerState = state
                if (status != null) {
                    val pos = status.streamPosition.coerceAtLeast(0L)
                    val dur = client.mediaInfo?.streamDuration?.coerceAtLeast(0L) ?: 0L
                    positionMs = pos
                    durationMs = dur
                    isPlaying  = state == MediaStatus.PLAYER_STATE_PLAYING
                    if (!isSeeking && dur > 0L) seekProgress = pos.toFloat() / dur
                }
                // Count half-seconds spent in genuine IDLE (media explicitly not loaded).
                // UNKNOWN is a normal in-between state during startup/loading — do NOT treat
                // it as idle or we'll retry before the stream has had a chance to start.
                if (state == MediaStatus.PLAYER_STATE_IDLE) {
                    idleSeconds++
                } else {
                    idleSeconds = 0
                }
            }
            delay(500)
        }
    }

    // After 15 s of genuine IDLE (not UNKNOWN/BUFFERING), automatically retry once.
    // Re-probes the real MIME type before retrying — wrong MIME (e.g. video/mp4
    // for an HLS stream) is the most common cause of Cast failing silently.
    LaunchedEffect(streamUrl) {
        if (streamUrl.isBlank()) return@LaunchedEffect
        delay(15_000)
        val session = castContext.sessionManager.currentCastSession ?: return@LaunchedEffect
        if (playerState == MediaStatus.PLAYER_STATE_IDLE) {
            val mime = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .followRedirects(true).followSslRedirects(true).build()
                    client.newCall(
                        okhttp3.Request.Builder().url(streamUrl).head().build()
                    ).execute().use { resp ->
                        resp.header("Content-Type")?.substringBefore(';')?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }
                }
            }.getOrNull() ?: guessMimeType(streamUrl)
            loadRemoteMedia(session, streamUrl, title, artworkUrl, mime)
        }
    }

    // Show the manual "Send to TV" retry button after 30 poll ticks (15 s) of genuine IDLE
    val showRetry = idleSeconds >= 30 && streamUrl.isNotBlank()
    val isBuffering = playerState == MediaStatus.PLAYER_STATE_BUFFERING

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // Dimmed artwork backdrop
        if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.18f,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        ) {
            Spacer(Modifier.height(40.dp))

            // Artwork
            if (!artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Box(
                    Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Cast,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(72.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // Title
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )

            Spacer(Modifier.height(6.dp))

            // Casting to <device>
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.CastConnected,
                    contentDescription = null,
                    tint = Color(0xFF66D9A6),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Casting to $deviceName",
                    color = Color(0xFF66D9A6),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(32.dp))

            when {
                isBuffering -> {
                    // Show spinner while Chromecast is buffering
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Loading on TV…",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(40.dp))
                }
                showRetry -> {
                    // Media never loaded — offer manual retry
                    Text(
                        "The video didn't start on your TV.",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val session = castContext.sessionManager.currentCastSession
                            if (session != null && streamUrl.isNotBlank()) {
                                val castUrl = CastProxyServer.currentProxyUrl ?: streamUrl
                                val mime = (CastProxyServer.detectedContentType?.substringBefore(';')?.trim())
                                    ?: guessMimeType(streamUrl)
                                loadRemoteMedia(session, castUrl, title, artworkUrl, mime)
                                idleSeconds = 0
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    ) {
                        Icon(
                            Icons.Default.Cast,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Send to TV", color = Color.Black)
                    }
                    Spacer(Modifier.height(40.dp))
                }
                else -> {
                    // Normal seek slider (media is playing or just started)
                    Slider(
                        value = if (isSeeking) seekProgress
                                else if (durationMs > 0L) positionMs.toFloat() / durationMs else 0f,
                        onValueChange = { v ->
                            isSeeking    = true
                            seekProgress = v
                        },
                        onValueChangeFinished = {
                            val target = (seekProgress * durationMs).toLong()
                            castContext.sessionManager.currentCastSession?.remoteMediaClient
                                ?.seek(
                                    MediaSeekOptions.Builder()
                                        .setPosition(target)
                                        .setResumeState(MediaSeekOptions.RESUME_STATE_PLAY)
                                        .build()
                                )
                            isSeeking = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.30f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (isSeeking) formatMs((seekProgress * durationMs).toLong())
                            else formatMs(positionMs),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            formatMs(durationMs),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // Playback controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Rewind 10s
                        IconButton(
                            onClick = {
                                val target = (positionMs - 10_000L).coerceAtLeast(0L)
                                castContext.sessionManager.currentCastSession?.remoteMediaClient
                                    ?.seek(MediaSeekOptions.Builder().setPosition(target).build())
                            },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                Icons.Default.Replay10,
                                contentDescription = "Rewind 10s",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp),
                            )
                        }

                        // Play / Pause
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable {
                                    val client = castContext.sessionManager.currentCastSession?.remoteMediaClient
                                    if (isPlaying) client?.pause() else client?.play()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(38.dp),
                            )
                        }

                        // Forward 10s
                        IconButton(
                            onClick = {
                                val target = (positionMs + 10_000L).coerceAtMost(durationMs)
                                castContext.sessionManager.currentCastSession?.remoteMediaClient
                                    ?.seek(MediaSeekOptions.Builder().setPosition(target).build())
                            },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                Icons.Default.Forward10,
                                contentDescription = "Forward 10s",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                } // end else (normal playback)
            } // end when

            Spacer(Modifier.height(32.dp))

            // Back / disconnect row
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = Color.White.copy(alpha = 0.7f))
                }
                TextButton(
                    onClick = {
                        castContext.sessionManager.currentCastSession?.remoteMediaClient?.stop()
                        androidx.mediarouter.media.MediaRouter
                            .getInstance(context.applicationContext)
                            .unselect(androidx.mediarouter.media.MediaRouter.UNSELECT_REASON_STOPPED)
                        onBack()
                    },
                ) {
                    Text("Stop Casting", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * DLNA cast controller — mirrors [rememberCastController] but targets a DLNA/UPnP
 * media renderer (Samsung, LG, and other DLNA-capable smart TVs).
 *
 * Call this alongside [rememberCastController] in the player screen; the returned
 * state is `true` whenever a DLNA device is selected and media has been loaded.
 *
 * The same [CastProxyServer] used for Chromecast is reused so that IP-restricted
 * debrid links work correctly (phone IP is whitelisted, TV IP is not).
 */
@Composable
fun rememberDlnaCastController(
    streamUrl: String,
    title: String,
    contentType: String? = null,
    headers: Map<String, String> = emptyMap(),
): androidx.compose.runtime.MutableState<Boolean> {
    val context = LocalContext.current
    val isCasting = remember { mutableStateOf(false) }
    val selectedDevice by DlnaRepository.selectedDevice.collectAsState()

    val isLocalhost = remember(streamUrl) {
        streamUrl.startsWith("http://127.0.0.1") || streamUrl.startsWith("http://localhost")
    }

    LaunchedEffect(selectedDevice, streamUrl) {
        val device = selectedDevice
        if (device == null || streamUrl.isBlank() || isLocalhost) {
            isCasting.value = false
            DlnaRepository.stopPolling()
            return@LaunchedEffect
        }

        isCasting.value = true

        val mime = contentType ?: guessMimeType(streamUrl)

        // Try to start the proxy (reuses CastProxyServer which is already running for
        // Chromecast sessions, or starts a new one if only DLNA is active).
        val proxyUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CastProxyServer.start(streamUrl, headers)
        }
        val effectiveUrl = proxyUrl ?: streamUrl

        val ok = com.streamcloud.app.cast.dlna.DlnaController.setUri(device, effectiveUrl, title, mime)
        if (ok) {
            com.streamcloud.app.cast.dlna.DlnaController.play(device)
            DlnaRepository.startPolling()
        } else {
            isCasting.value = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            DlnaRepository.stopPolling()
            CastProxyServer.stop()
        }
    }

    return isCasting
}
