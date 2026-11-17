package com.streamcloud.app.wear

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.streamcloud.app.audio.MusicPlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WearMusicBridgeService : com.google.android.gms.wearable.WearableListenerService() {
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearMusicProtocol.REQUEST_PATH) return
        val request = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }
            .getOrElse { return }

        bridgeScope.launch {
            // Media3 MediaBrowser/MediaController APIs are bound to the main
            // application looper. Keep message handling and blocking Wearable
            // Tasks on IO, but marshal the complete controller transaction to
            // Main so the browser is also released on its owning thread.
            val response = runCatching {
                withContext(Dispatchers.Main.immediate) { handleRequest(request) }
            }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    JSONObject()
                        .put("ok", false)
                        .put("message", error.message ?: "The phone couldn't complete that request.")
                }
                .put("requestId", request.optString("requestId"))

            runCatching {
                Tasks.await(
                    Wearable.getMessageClient(this@WearMusicBridgeService)
                        .sendMessage(
                            event.sourceNodeId,
                            WearMusicProtocol.RESPONSE_PATH,
                            response.toString().toByteArray(Charsets.UTF_8),
                        ),
                )
            }
        }
    }

    private suspend fun handleRequest(request: JSONObject): JSONObject {
        val browser = connectBrowser()
        try {
            return when (request.optString("operation")) {
                "root" -> browseRoot(browser)
                "children" -> browseChildren(
                    browser = browser,
                    parentId = request.optString("parentId"),
                    page = request.optInt("page").coerceAtLeast(0),
                    pageSize = request.optInt("pageSize", DEFAULT_PAGE_SIZE)
                        .coerceIn(1, MAX_PAGE_SIZE),
                )
                "playPhone" -> playOnPhone(
                    browser = browser,
                    parentId = request.optString("parentId"),
                    page = request.optInt("page").coerceAtLeast(0),
                    index = request.optInt("index").coerceAtLeast(0),
                    pageSize = request.optInt("pageSize", DEFAULT_PAGE_SIZE)
                        .coerceIn(1, MAX_PAGE_SIZE),
                )
                "transport" -> controlPhone(
                    browser = browser,
                    action = request.optString("action"),
                    positionMs = request.optLong("positionMs"),
                )
                "state" -> phoneState(browser)
                "resolveWatch" -> resolveForWatch(browser, request.optString("mediaId"))
                "rejectWatchStream" -> rejectWatchStream(
                    browser = browser,
                    videoId = request.optString("videoId"),
                    failedUrl = request.optString("failedUrl"),
                )
                else -> error("Unknown music request.")
            }
        } finally {
            browser.release()
        }
    }

    private suspend fun connectBrowser(): MediaBrowser {
        // This listener service can be torn down shortly after replying. Media3
        // may defer unbinding until pending session commands finish, so bind with
        // the application context rather than retaining a short-lived Service
        // context for the controller's delayed release.
        val appContext = applicationContext
        val token = SessionToken(
            appContext,
            ComponentName(appContext, MusicPlaybackService::class.java),
        )
        return MediaBrowser.Builder(appContext, token).buildAsync().awaitValue()
    }

    private suspend fun browseRoot(browser: MediaBrowser): JSONObject {
        val root = browser.getLibraryRoot(null).awaitValue().value
            ?: error("The music library isn't available on the phone.")
        val children = browser.getChildren(root.mediaId, 0, DEFAULT_PAGE_SIZE, null)
            .awaitValue().value.orEmpty()
        val serialized = serializeItems(children, root.mediaId, 0)
        val existingIds = children.mapTo(mutableSetOf()) { it.mediaId }
        listOf(
            MusicPlaybackService.HOME_ID to "Music home",
            MusicPlaybackService.PLAYLISTS_ID to "My playlists",
        ).forEach { (id, label) ->
            if (existingIds.add(id)) {
                serialized.put(
                    JSONObject()
                        .put("id", id)
                        .put("title", label)
                        .put("browsable", true)
                        .put("playable", false)
                        .put("parentId", root.mediaId)
                        .put("page", 0)
                        .put("index", serialized.length()),
                )
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("rootId", root.mediaId)
            .put("items", serialized)
    }

    private suspend fun browseChildren(
        browser: MediaBrowser,
        parentId: String,
        page: Int,
        pageSize: Int,
    ): JSONObject {
        require(parentId.isNotBlank()) { "Choose a music section first." }
        val result = browser.getChildren(parentId, page, pageSize, null).awaitValue()
        if (result.resultCode != LibraryResult.RESULT_SUCCESS) {
            error("That music section couldn't be opened.")
        }
        return JSONObject()
            .put("ok", true)
            .put("parentId", parentId)
            .put("page", page)
            .put("pageSize", pageSize)
            .put("items", serializeItems(result.value.orEmpty(), parentId, page))
    }

    private suspend fun playOnPhone(
        browser: MediaBrowser,
        parentId: String,
        page: Int,
        index: Int,
        pageSize: Int,
    ): JSONObject {
        require(parentId.isNotBlank()) { "Choose a track first." }
        val result = browser.getChildren(parentId, page, pageSize, null).awaitValue()
        val item = result.value?.getOrNull(index)
            ?: error("That track is no longer in the list.")
        require(item.mediaMetadata.isPlayable == true || item.mediaMetadata.isBrowsable == true) {
            "That item can't be played."
        }
        browser.setMediaItem(item)
        browser.prepare()
        browser.play()
        return JSONObject().put("ok", true).put("message", "Playing on your phone.")
    }

    private fun controlPhone(browser: MediaBrowser, action: String, positionMs: Long): JSONObject {
        when (action) {
            "play" -> browser.play()
            "pause" -> browser.pause()
            "next" -> browser.seekToNextMediaItem()
            "previous" -> browser.seekToPreviousMediaItem()
            "seek" -> browser.seekTo(positionMs.coerceAtLeast(0L))
            else -> error("That playback control isn't available.")
        }
        return JSONObject().put("ok", true).put("message", "Playback updated.")
    }

    private fun phoneState(browser: MediaBrowser): JSONObject {
        val item = browser.currentMediaItem
        val duration = browser.duration.coerceAtLeast(0L)
        return JSONObject()
            .put("ok", true)
            .put("hasItem", item != null)
            .put("playing", browser.isPlaying)
            .put("title", item?.mediaMetadata?.title?.toString().orEmpty())
            .put("artist", item?.mediaMetadata?.artist?.toString().orEmpty())
            .put("artwork", item?.mediaMetadata?.artworkUri?.toString().orEmpty())
            .put("positionMs", browser.currentPosition.coerceAtLeast(0L))
            .put("durationMs", duration)
            .put("mediaId", item?.mediaId.orEmpty())
    }

    private suspend fun resolveForWatch(
        browser: MediaBrowser,
        mediaId: String,
    ): JSONObject {
        require(mediaId.startsWith("https://music.youtube.com/watch?v=") ||
            mediaId.startsWith("https://www.youtube.com/watch?v=")
        ) {
            "Direct watch playback is available for YouTube Music tracks. Use phone playback for this source."
        }
        val args = Bundle().apply { putString(WearMusicProtocol.ARG_MEDIA_ID, mediaId) }
        val result = browser.sendCustomCommand(
            SessionCommand(WearMusicProtocol.ACTION_RESOLVE_STREAM, Bundle.EMPTY),
            args,
        ).awaitValue()
        if (result.resultCode != SessionResult.RESULT_SUCCESS) {
            error(result.extras.getString(WearMusicProtocol.RESULT_ERROR)
                ?: "This track needs phone playback.")
        }
        val url = result.extras.getString(WearMusicProtocol.RESULT_STREAM_URL)
            ?: error("The phone couldn't prepare a watch stream.")
        return JSONObject()
            .put("ok", true)
            .put("streamUrl", url)
            .put("userAgent", result.extras.getString(WearMusicProtocol.RESULT_USER_AGENT).orEmpty())
    }

    private suspend fun rejectWatchStream(
        browser: MediaBrowser,
        videoId: String,
        failedUrl: String,
    ): JSONObject {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid track identity." }
        val args = Bundle().apply {
            putString(WearMusicProtocol.ARG_VIDEO_ID, videoId)
            putString(WearMusicProtocol.ARG_FAILED_URL, failedUrl)
        }
        val result = browser.sendCustomCommand(
            SessionCommand(WearMusicProtocol.ACTION_REJECT_STREAM, Bundle.EMPTY),
            args,
        ).awaitValue()
        if (result.resultCode != SessionResult.RESULT_SUCCESS) {
            error("The phone couldn't refresh that stream.")
        }
        return JSONObject().put("ok", true)
    }

    private fun serializeItems(
        items: List<MediaItem>,
        parentId: String,
        page: Int,
    ): JSONArray = JSONArray().apply {
        items.forEachIndexed { index, item ->
            val metadata = item.mediaMetadata
            put(
                JSONObject()
                    .put("id", item.mediaId)
                    .put("title", metadata.title?.toString().orEmpty())
                    .put("subtitle", metadata.subtitle?.toString().orEmpty())
                    .put("artist", metadata.artist?.toString().orEmpty())
                    .put("artwork", metadata.artworkUri?.toString().orEmpty())
                    .put("browsable", metadata.isBrowsable == true)
                    .put("playable", metadata.isPlayable == true)
                    .put("parentId", parentId)
                    .put("page", page)
                    .put("index", index)
            )
        }
    }

    override fun onDestroy() {
        bridgeScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 40
        const val MAX_PAGE_SIZE = 80
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
