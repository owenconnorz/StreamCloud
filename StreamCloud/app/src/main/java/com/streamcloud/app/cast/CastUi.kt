package com.streamcloud.app.cast

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastButton(modifier: Modifier = Modifier, tint: Color = Color.White) {
    val context = LocalContext.current




    val castContext = remember(context) {
        runCatching { CastContext.getSharedInstance(context.applicationContext) }
            .getOrNull()
    } ?: return

    val mediaRouter = remember(context) { MediaRouter.getInstance(context.applicationContext) }
    val selector = remember(castContext) { castContext.mergedSelector ?: MediaRouteSelector.EMPTY }


    val routes = remember { mutableStateListOf<MediaRouter.RouteInfo>() }
    var selectedRouteId by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }




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

    val connected = selectedRouteId != null

    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { showDialog = true },
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
            onPickRoute = { route ->
                mediaRouter.selectRoute(route)
                showDialog = false
            },
            onDisconnect = {
                mediaRouter.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
                showDialog = false
            },
            onDismiss = { showDialog = false },
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
    onPickRoute: (MediaRouter.RouteInfo) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cast to") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (routes.isEmpty()) {
                    Text(
                        "Looking for nearby devices…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    routes.forEach { route ->
                        RouteRow(
                            route = route,
                            isSelected = route.id == selectedRouteId,
                            onClick = { onPickRoute(route) },
                        )
                    }
                }
                if (selectedRouteId != null) {
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
): androidx.compose.runtime.MutableState<Boolean> {
    val context = LocalContext.current
    val isCasting = remember { mutableStateOf(false) }

    DisposableEffect(streamUrl, title, artworkUrl, contentType, context) {
        val castContext = runCatching {
            CastContext.getSharedInstance(context.applicationContext)
        }.getOrNull()
        if (castContext == null) {
            return@DisposableEffect onDispose { }
        }

        castContext.sessionManager.currentCastSession?.let { session ->
            isCasting.value = true
            loadRemoteMedia(session, streamUrl, title, artworkUrl, contentType)
        }

        val listener = object : com.google.android.gms.cast.framework.SessionManagerListener<
            com.google.android.gms.cast.framework.CastSession,
            > {
            override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {
                isCasting.value = true
                loadRemoteMedia(session, streamUrl, title, artworkUrl, contentType)
            }
            override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) {
                isCasting.value = true
                loadRemoteMedia(session, streamUrl, title, artworkUrl, contentType)
            }
            override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
                isCasting.value = false
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

private fun guessMimeType(url: String): String {
    val q = url.substringBefore('?').lowercase()
    return when {
        q.endsWith(".m3u8") -> "application/x-mpegURL"
        q.endsWith(".mpd") -> "application/dash+xml"
        q.endsWith(".webm") -> "video/webm"
        q.endsWith(".mkv") -> "video/x-matroska"
        q.endsWith(".mov") -> "video/quicktime"
        else -> "video/mp4"
    }
}

fun initCast(context: Context) {
    runCatching { CastContext.getSharedInstance(context.applicationContext) }
}
