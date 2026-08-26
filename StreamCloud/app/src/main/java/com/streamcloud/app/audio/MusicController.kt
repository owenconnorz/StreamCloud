package com.streamcloud.app.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@UnstableApi
object MusicController {

    private val mutex = Mutex()


    @Volatile private var pending: CompletableDeferred<MediaController>? = null


    @Volatile private var controller: MediaController? = null


    suspend fun get(context: Context): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }

        val connection: CompletableDeferred<MediaController> = mutex.withLock {
            controller?.takeIf { it.isConnected }?.let { return@withLock CompletableDeferred(it) }

            pending?.takeIf { it.isActive }?.let { return@withLock it }

            controller?.let { runCatching { it.release() } }
            controller = null

            val deferred = CompletableDeferred<MediaController>()
            pending = deferred

            val token = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, MusicPlaybackService::class.java),
            )
            val future = MediaController.Builder(context.applicationContext, token).buildAsync()

            future.addListener({
                runCatching {
                    future.get()
                }.onSuccess { connected ->
                    controller = connected
                    deferred.complete(connected)
                }.onFailure { error ->
                    if (pending === deferred) pending = null
                    deferred.completeExceptionally(error)
                }
            }, MoreExecutors.directExecutor())
            deferred
        }
        // Do not hold the connection mutex while Media3 starts and binds the service. Concurrent
        // callers await the same deferred, and cancellation of one UI caller does not tear down
        // the shared connection that playback is about to use.
        return connection.await()
    }

    fun release() {
        controller?.let { runCatching { it.release() } }
        controller = null
        pending = null
    }
}
