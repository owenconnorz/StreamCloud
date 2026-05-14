package com.aioweb.app.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Async, cached connector to the [MusicPlaybackService] from UI code.
 * The returned [MediaController] is functionally a `Player` — the Composable wires
 * it to its `Player.Listener` and treats it like any other ExoPlayer instance,
 * but playback now happens in the foreground service so it survives navigation
 * AND publishes the system media notification automatically.
 */
@UnstableApi
object MusicController {

    @Volatile private var future: ListenableFuture<MediaController>? = null
    @Volatile private var controller: MediaController? = null

    suspend fun get(context: Context): MediaController {
        controller?.let { return it }
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, MusicPlaybackService::class.java),
        )
        val f = MediaController.Builder(context.applicationContext, token).buildAsync()
        future = f
        return suspendCancellableCoroutine { cont ->
            f.addListener({
                runCatching {
                    val c = f.get()
                    controller = c
                    cont.resume(c)
                }.onFailure { cont.resumeWith(Result.failure(it)) }
            }, MoreExecutors.directExecutor())
            cont.invokeOnCancellation { runCatching { f.cancel(true) } }
        }
    }

    fun release() {
        future?.let { runCatching { MediaController.releaseFuture(it) } }
        future = null
        controller = null
    }
}
