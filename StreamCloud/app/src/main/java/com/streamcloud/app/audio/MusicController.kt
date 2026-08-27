package com.streamcloud.app.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred

@UnstableApi
object MusicController {

    private val stateLock = Any()
    @Volatile private var pending: CompletableDeferred<MediaController>? = null
    @Volatile private var controller: MediaController? = null

    suspend fun get(context: Context): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        return ensureConnection(context).await()
    }

    fun prewarm(context: Context) {
        ensureConnection(context)
    }

    private fun ensureConnection(context: Context): CompletableDeferred<MediaController> =
        synchronized(stateLock) {
            controller?.takeIf { it.isConnected }?.let { return@synchronized CompletableDeferred(it) }
            pending?.takeIf { it.isActive }?.let { return@synchronized it }

            controller?.let { runCatching { it.release() } }
            controller = null

            val deferred = CompletableDeferred<MediaController>()
            pending = deferred
            val appContext = context.applicationContext
            val token = SessionToken(
                appContext,
                ComponentName(appContext, MusicPlaybackService::class.java),
            )
            val future = MediaController.Builder(appContext, token).buildAsync()
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { connected ->
                        synchronized(stateLock) {
                            if (pending === deferred) {
                                controller = connected
                                pending = null
                                deferred.complete(connected)
                            } else {
                                runCatching { connected.release() }
                            }
                        }
                    }
                    .onFailure { error ->
                        synchronized(stateLock) {
                            if (pending === deferred) pending = null
                            deferred.completeExceptionally(error)
                        }
                    }
            }, MoreExecutors.directExecutor())
            deferred
        }

    fun release() {
        synchronized(stateLock) {
            controller?.let { runCatching { it.release() } }
            controller = null
            pending?.cancel()
            pending = null
        }
    }
}
