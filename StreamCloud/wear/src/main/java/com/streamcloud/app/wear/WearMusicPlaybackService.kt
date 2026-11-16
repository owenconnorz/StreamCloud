package com.streamcloud.app.wear

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WearMusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retriedVideoIds = ConcurrentHashMap.newKeySet<String>()
    private val resolvedUrls = ConcurrentHashMap<String, String>()
    private val httpDataSourceFactory by lazy {
        DefaultHttpDataSource.Factory().setUserAgent(DEFAULT_USER_AGENT)
    }
    private val resolvingDataSourceFactory by lazy {
        ResolvingDataSource.Factory(
            httpDataSourceFactory,
            ResolvingDataSource.Resolver { dataSpec -> resolveDataSpec(dataSpec) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        val createdPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingDataSourceFactory))
            .build()
        createdPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                refreshRejectedStream(createdPlayer, error)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val activeId = mediaItem?.mediaId
                    ?.substringAfter("v=", "")
                    ?.substringBefore("&")
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                resolvedUrls.keys.toList()
                    .filter { it != activeId }
                    .forEach { resolvedUrls.remove(it) }
                if (activeId != null) retriedVideoIds.remove(activeId)
            }
        })
        mediaSession = MediaSession.Builder(this, createdPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        resolvedUrls.clear()
        retriedVideoIds.clear()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun refreshRejectedStream(player: ExoPlayer, error: PlaybackException) {
        if (!isCdnRejection(error)) return
        val current = player.currentMediaItem ?: return
        val mediaId = current.mediaId
        val videoId = mediaId.substringAfter("v=", "").substringBefore("&")
        val failedUrl = resolvedUrls[videoId].orEmpty()
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}")) || failedUrl.isBlank()) return
        if (!retriedVideoIds.add(videoId)) return

        serviceScope.launch {
            val rejected = runCatching {
                WearMusicBridge.request(
                    this@WearMusicPlaybackService,
                    "rejectWatchStream",
                    JSONObject().put("videoId", videoId).put("failedUrl", failedUrl),
                )
            }.isSuccess
            if (!rejected) return@launch
            resolvedUrls.remove(videoId, failedUrl)
            withContext(Dispatchers.Main) {
                player.prepare()
                player.play()
            }
        }
    }

    private fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != STREAMCLOUD_WEAR_SCHEME) return dataSpec
        val mediaId = dataSpec.uri.getQueryParameter(MEDIA_ID_QUERY)
            ?: throw IOException("The selected watch track has no media identity.")
        val response = runBlocking {
            WearMusicBridge.request(
                this@WearMusicPlaybackService,
                "resolveWatch",
                JSONObject().put("mediaId", mediaId),
            )
        }
        if (!response.optBoolean("ok")) {
            throw IOException(response.optString("message", "This track needs phone playback."))
        }
        val url = response.optString("streamUrl").takeIf(String::isNotBlank)
            ?: throw IOException("The phone didn't return a playable audio stream.")
        val userAgent = response.optString("userAgent").takeIf(String::isNotBlank)
            ?: DEFAULT_USER_AGENT
        val videoId = mediaId.substringAfter("v=", "").substringBefore("&")
        if (videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) resolvedUrls[videoId] = url
        return dataSpec.buildUpon()
            .setUri(Uri.parse(url))
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("User-Agent" to userAgent))
            .build()
    }

    private fun isCdnRejection(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException &&
                cause.responseCode in setOf(403, 416)
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    companion object {
        const val STREAMCLOUD_WEAR_SCHEME = "streamcloud-wear"
        const val MEDIA_ID_QUERY = "mediaId"
        private const val DEFAULT_USER_AGENT = "StreamCloud Wear"
    }
}

internal data class WearWatchTrack(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artwork: String,
)

internal object WearWatchPlayback {
    private var controller: MediaController? = null
    private val lock = Any()

    suspend fun playQueue(
        context: Context,
        tracks: List<WearWatchTrack>,
        startIndex: Int,
    ) {
        val player = getController(context)
        require(tracks.isNotEmpty()) { "This playlist has no watch-compatible tracks." }
        require(startIndex in tracks.indices) { "Choose a track in the playlist." }
        val mediaItems = tracks.map { track ->
            val resolverUri = Uri.Builder()
                .scheme(WearMusicPlaybackService.STREAMCLOUD_WEAR_SCHEME)
                .authority("track")
                .appendQueryParameter(WearMusicPlaybackService.MEDIA_ID_QUERY, track.mediaId)
                .build()
            MediaItem.Builder()
                .setMediaId(track.mediaId)
                .setUri(resolverUri)
                .setMimeType(MimeTypes.AUDIO_MPEG)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setArtworkUri(track.artwork.takeIf(String::isNotBlank)?.let(Uri::parse))
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
        }
        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()
    }

    suspend fun setPlaying(context: Context, playing: Boolean) {
        val player = getController(context)
        if (playing) player.play() else player.pause()
    }

    private suspend fun getController(context: Context): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        val appContext = context.applicationContext
        val token = SessionToken(
            appContext,
            ComponentName(appContext, WearMusicPlaybackService::class.java),
        )
        val connected = MediaController.Builder(appContext, token)
            .buildAsync()
            .awaitValue()
        synchronized(lock) {
            controller?.takeIf { it.isConnected }?.let {
                connected.release()
                return it
            }
            controller?.release()
            controller = connected
        }
        return connected
    }
}

private suspend fun <T> ListenableFuture<T>.awaitValue(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    val value = get()
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: ExecutionException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error.cause ?: error)
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
            MoreExecutors.directExecutor(),
        )
        continuation.invokeOnCancellation { cancel(true) }
    }
