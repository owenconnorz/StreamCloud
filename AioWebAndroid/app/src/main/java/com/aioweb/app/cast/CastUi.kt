package com.aioweb.app.cast

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage

/**
 * Material-styled [MediaRouteButton] wrapped for Compose. Renders the
 * standard Google Cast icon — tapping it opens the system route-chooser
 * dialog. Once a session is connected, [CastController] takes over and
 * pushes the currently-playing stream URL to the receiver.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier, tint: Color = Color.White) {
    val context = LocalContext.current
    // CastContext.getSharedInstance is lazy + safe to call repeatedly.
    // Wrapped in runCatching so the absence of Google Play Services on the
    // device doesn't crash the player.
    val castReady = remember(context) {
        runCatching { CastContext.getSharedInstance(context.applicationContext) }
            .getOrNull() != null
    }
    if (!castReady) return

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MediaRouteButton(ctx).also { btn ->
                CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, btn)
            }
        },
        update = { _ ->
            // Cast icon tint is driven by the inflated drawable resources from
            // the cast SDK (mr_cast_button_*). Recoloring on the fly would
            // require swapping the drawable, which isn't worth the complexity —
            // the default white indicator already reads well over our dark
            // semi-transparent capsule background.
        },
    )
}

/**
 * Bridges between local ExoPlayer playback and a Google Cast session.
 *
 * Behaviour:
 *  - When a Cast session connects, push [streamUrl] (with [title] +
 *    optional [artworkUrl]) to the receiver via `RemoteMediaClient.load`.
 *  - When the session ends, the player keeps playing locally — the host
 *    composable doesn't need to do anything.
 *
 * Returns a [MutableState<Boolean>] indicating whether a Cast session is
 * currently connected, so callers can pause/duck the local ExoPlayer if
 * they want to (we don't here — local + cast both play, the user can mute
 * the device manually if needed).
 */
@Composable
fun rememberCastController(
    streamUrl: String,
    title: String,
    artworkUrl: String? = null,
    contentType: String? = null,
): MutableState<Boolean> {
    val context = LocalContext.current
    val isCasting = remember { mutableStateOf(false) }

    DisposableEffect(streamUrl, title, artworkUrl, contentType, context) {
        val castContext = runCatching {
            CastContext.getSharedInstance(context.applicationContext)
        }.getOrNull()
        if (castContext == null) {
            return@DisposableEffect onDispose { }
        }

        // If a session is already live (e.g. user opened the player after
        // starting cast from a different screen), load the stream right away.
        castContext.sessionManager.currentCastSession?.let { session ->
            isCasting.value = true
            loadRemoteMedia(session, streamUrl, title, artworkUrl, contentType)
        }

        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                isCasting.value = true
                loadRemoteMedia(session, streamUrl, title, artworkUrl, contentType)
            }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                isCasting.value = true
                loadRemoteMedia(session, streamUrl, title, artworkUrl, contentType)
            }
            override fun onSessionEnded(session: CastSession, error: Int) {
                isCasting.value = false
            }
            override fun onSessionSuspended(session: CastSession, reason: Int) {
                isCasting.value = false
            }
            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionStartFailed(session: CastSession, error: Int) {}
            override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        }
        castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        onDispose {
            castContext.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
        }
    }
    return isCasting
}

private fun loadRemoteMedia(
    session: CastSession,
    streamUrl: String,
    title: String,
    artworkUrl: String?,
    contentType: String?,
) {
    val client = session.remoteMediaClient ?: return
    // Local-proxy URLs (TorrentStreamServer) won't reach a Chromecast on the
    // LAN — skip silently rather than throwing a confusing receiver error.
    if (streamUrl.startsWith("http://127.0.0.1") || streamUrl.startsWith("http://localhost")) {
        return
    }

    val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
        putString(MediaMetadata.KEY_TITLE, title)
        if (!artworkUrl.isNullOrBlank()) {
            addImage(WebImage(android.net.Uri.parse(artworkUrl)))
        }
    }

    val mime = contentType ?: guessMimeType(streamUrl)
    val mediaInfo = MediaInfo.Builder(streamUrl)
        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
        .setContentType(mime)
        .setMetadata(metadata)
        .build()

    client.load(
        MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build(),
    )
}

/** Best-effort MIME guess based on the URL extension. */
private fun guessMimeType(url: String): String {
    val q = url.substringBefore('?').lowercase()
    return when {
        q.endsWith(".m3u8")          -> "application/x-mpegURL"
        q.endsWith(".mpd")           -> "application/dash+xml"
        q.endsWith(".webm")          -> "video/webm"
        q.endsWith(".mkv")           -> "video/x-matroska"
        q.endsWith(".mov")           -> "video/quicktime"
        else                         -> "video/mp4"
    }
}

/** Force-init the Cast SDK — call from MainActivity.onCreate so cast notifications fire promptly. */
fun initCast(context: Context) {
    runCatching { CastContext.getSharedInstance(context.applicationContext) }
}
