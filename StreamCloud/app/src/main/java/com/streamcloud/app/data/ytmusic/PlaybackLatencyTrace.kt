package com.streamcloud.app.data.ytmusic

import android.os.SystemClock
import com.streamcloud.app.data.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy-safe startup timing for diagnosing real devices. It records only a video ID and elapsed
 * durations; signed stream URLs, cookies, tokens, and request headers are never logged.
 */
object PlaybackLatencyTrace {
    private const val TAG = "MusicStartup"
    private const val TRACE_TIMEOUT_MS = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class Attempt(
        val id: Long,
        val startedAtMs: Long,
        val firstAudio: CompletableDeferred<Boolean> = CompletableDeferred(),
        val markedStages: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    class Handle internal constructor(
        internal val videoId: String,
        internal val attemptId: Long,
        internal val firstAudio: CompletableDeferred<Boolean>,
    )

    private val attempts = ConcurrentHashMap<String, Attempt>()
    private val nextAttemptId = AtomicLong()
    private val activeLock = Any()
    @Volatile private var activeVideoId: String? = null

    fun begin(videoId: String): Handle {
        val attempt = Attempt(
            id = nextAttemptId.incrementAndGet(),
            startedAtMs = SystemClock.elapsedRealtime(),
        )
        synchronized(activeLock) {
            activeVideoId?.let { previousVideoId ->
                attempts.remove(previousVideoId)?.firstAudio?.complete(false)
            }
            attempts[videoId] = attempt
            activeVideoId = videoId
        }
        AppLogger.i(TAG, "$videoId tap +0ms")
        scope.launch {
            delay(TRACE_TIMEOUT_MS)
            if (attempts.remove(videoId, attempt)) {
                synchronized(activeLock) {
                    if (activeVideoId == videoId) activeVideoId = null
                }
                attempt.firstAudio.complete(false)
                AppLogger.w(TAG, "$videoId abandoned +${TRACE_TIMEOUT_MS}ms")
            }
        }
        return Handle(videoId, attempt.id, attempt.firstAudio)
    }

    fun mark(videoId: String, stage: String) {
        markOnce(videoId, stage, stage)
    }

    fun markOnce(videoId: String, key: String, stage: String) {
        val attempt = attempts[videoId] ?: return
        if (!attempt.markedStages.add(key)) return
        AppLogger.i(TAG, "$videoId $stage +${SystemClock.elapsedRealtime() - attempt.startedAtMs}ms")
    }

    fun finish(videoId: String, attemptId: Long, stage: String) {
        val attempt = attempts[videoId]?.takeIf { it.id == attemptId } ?: return
        if (!attempts.remove(videoId, attempt)) return
        synchronized(activeLock) {
            if (activeVideoId == videoId) activeVideoId = null
        }
        AppLogger.i(TAG, "$videoId $stage +${SystemClock.elapsedRealtime() - attempt.startedAtMs}ms")
        attempt.firstAudio.complete(true)
    }

    fun abort(videoId: String, attemptId: Long, stage: String) {
        val attempt = attempts[videoId]?.takeIf { it.id == attemptId } ?: return
        if (!attempts.remove(videoId, attempt)) return
        synchronized(activeLock) {
            if (activeVideoId == videoId) activeVideoId = null
        }
        AppLogger.w(TAG, "$videoId $stage +${SystemClock.elapsedRealtime() - attempt.startedAtMs}ms")
        attempt.firstAudio.complete(false)
    }

    suspend fun awaitFirstAudio(handle: Handle, timeoutMs: Long = TRACE_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { handle.firstAudio.await() } ?: false
}