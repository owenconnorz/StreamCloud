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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@UnstableApi
object MusicController {

    private val mutex = Mutex()


    @Volatile private var pending: CompletableDeferred<MediaController>? = null


    @Volatile private var controller: MediaController? = null


    suspend fun get(context: Context): MediaController {

        controller?.takeIf { it.isConnected }?.let { return it }


        return mutex.withLock {

            controller?.takeIf { it.isConnected }?.let { return@withLock it }


            pending?.takeIf { it.isActive }?.let { return@withLock it.await() }


            controller?.let { runCatching { it.release() } }
            controller = null


            val deferred = CompletableDeferred<MediaController>()
            pending = deferred

            val token = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, MusicPlaybackService::class.java),
            )
            val future = MediaController.Builder(context.applicationContext, token).buildAsync()

            suspendCancellableCoroutine { cont ->
                future.addListener({
                    runCatching {
                        val c = future.get()
                        controller = c
                        deferred.complete(c)
                        cont.resume(c)
                    }.onFailure {
                        deferred.completeExceptionally(it)
                        cont.resumeWith(Result.failure(it))
                    }
                }, MoreExecutors.directExecutor())

                cont.invokeOnCancellation {



                }
            }
        }
    }

    fun release() {
        controller?.let { runCatching { it.release() } }
        controller = null
        pending = null
    }
}
