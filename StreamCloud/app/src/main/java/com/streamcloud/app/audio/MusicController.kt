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

/**
 * Thread-safe, cached connector to [MusicPlaybackService] from UI / business logic.
 *
 * ── Why a Mutex + CompletableDeferred? ──────────────────────────────────────
 * `MediaController.buildAsync()` is asynchronous.  Without synchronisation,
 * two coroutines calling `get()` in parallel (e.g. `playSong()` and the
 * immediate `addMediaItem()` calls in `playPlaylist()`) both see
 * `controller == null` and each start their own `buildAsync()`.  The second
 * future overwrites the first; whichever resolves last wins.  The controller
 * that "lost" has its `play()` / `setMediaItem()` commands silently dropped by
 * Media3 — this is the root cause of "song doesn't play until I press play
 * manually".
 *
 * The fix:
 *  • A [Mutex] ensures only one build is ever in-flight at a time.
 *  • A shared [CompletableDeferred] is stored while the build is pending so
 *    concurrent callers all `await()` the same result instead of starting new
 *    builds.
 *  • `isConnected` is checked on the cached controller so a stale reference
 *    (service restarted, process death) is detected and rebuilt automatically.
 */
@UnstableApi
object MusicController {

    private val mutex = Mutex()

    /** Non-null while a connection is in-flight; completed once connected. */
    @Volatile private var pending: CompletableDeferred<MediaController>? = null

    /** Non-null after a successful connection. Nulled on [release]. */
    @Volatile private var controller: MediaController? = null

    /**
     * Return the connected [MediaController], building one if necessary.
     *
     * Concurrent callers are safe: the first caller acquires the mutex and
     * starts the build; subsequent callers see [pending] and simply `await()`
     * its result without triggering a second `buildAsync()`.
     */
    suspend fun get(context: Context): MediaController {
        // Fast path: cached controller is still connected.
        controller?.takeIf { it.isConnected }?.let { return it }

        // Slow path: either first call or controller went stale.
        return mutex.withLock {
            // Re-check inside the lock — another coroutine may have just built it.
            controller?.takeIf { it.isConnected }?.let { return@withLock it }

            // Reuse an in-flight build if one is already pending.
            pending?.takeIf { it.isActive }?.let { return@withLock it.await() }

            // Release any stale/disconnected instance.
            controller?.let { runCatching { it.release() } }
            controller = null

            // Start a fresh build and share it with any concurrent waiters.
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
                    // Don't cancel the future — other awaiters still need it.
                    // The deferred stays active so concurrent callers can still
                    // get the controller once it connects.
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
