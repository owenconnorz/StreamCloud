package com.streamcloud.app.data.ytmusic

import com.streamcloud.app.data.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

internal fun boundedPrefetchVideoIds(videoIds: Iterable<String>, limit: Int): List<String> =
    videoIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(limit.coerceAtLeast(0))
        .toList()

internal fun queuePrefetchVideoIds(
    videoIds: List<String>,
    currentIndex: Int,
    lookAhead: Int,
): List<String> {
    if (videoIds.isEmpty()) return emptyList()
    val start = currentIndex.coerceIn(0, videoIds.lastIndex)
    return boundedPrefetchVideoIds(
        videoIds = videoIds.drop(start),
        limit = lookAhead.coerceAtLeast(1),
    )
}

/**
 * Resolves and caches the short-lived YouTube audio URL independently of ExoPlayer.
 *
 * Keeping this work shared between the UI prefetch and ResolvingDataSource means a track is
 * resolved only once: a tap can overlap resolution with service startup, while the data source
 * simply consumes the cached result when ExoPlayer prepares.
 */
object YtMusicStreamResolver {
    private const val TAG = "YtMusicStreamResolver"

    private enum class PrefetchPriority {
        VISIBLE_LIST,
        ACTIVE_QUEUE,
    }

    private data class InFlightResolution(
        val deferred: Deferred<StreamUrlCache.Entry>,
        val speculative: Boolean,
    )

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val foregroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolutionLocks = ConcurrentHashMap<String, Mutex>()
    private val inFlightResolutions = ConcurrentHashMap<String, InFlightResolution>()
    private val resolutionGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val queuedPrefetchPriorities = ConcurrentHashMap<String, PrefetchPriority>()
    private val visibleListPrefetchPermits = Semaphore(1)
    private val activeQueuePrefetchPermits = Semaphore(1)

    suspend fun resolveInnertube(
        videoId: String,
        excludedClientLabels: Set<String> = emptySet(),
    ): StreamUrlCache.Entry {
        require(videoId.isNotBlank()) { "A YouTube video ID is required." }

        StreamUrlCache.getEntry(videoId, YtPlayerUtils.currentWebSessionFingerprint())
            ?.takeIf { entry ->
                excludedClientLabels.isEmpty() || entry.clientLabel !in excludedClientLabels
            }
            ?.let { return it }

        // A rejected client must not reuse a speculative request that may be resolving the same
        // client. This path only follows a confirmed CDN failure and is intentionally isolated.
        if (excludedClientLabels.isNotEmpty()) {
            val generation = invalidateResolution(videoId)
            return resolveFresh(
                videoId = videoId,
                excludedClientLabels = excludedClientLabels,
                serializeWithPrefetch = false,
                generation = generation,
            )
        }

        // A foreground request must never inherit a slow, speculative warm-up. It promotes the
        // work to its own resolver job, while concurrent foreground requests still share one job.
        return resolveShared(videoId, speculative = false)
    }

    /**
     * Start a user-requested resolve immediately. The returned work is shared with the
     * ResolvingDataSource, so preparing a Media3 item cannot cancel and repeat it as speculative
     * list prefetch.
     *
     * Resolution failures are represented as [Result] because Media3 still owns the maintained
     * extractor fallback and client-rejection recovery path.
     */
    fun primeForPlayback(videoId: String): Deferred<Result<StreamUrlCache.Entry>> =
        foregroundScope.async {
            try {
                Result.success(resolveInnertube(videoId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

    private suspend fun resolveForPrefetch(videoId: String): StreamUrlCache.Entry {
        StreamUrlCache.getEntry(videoId, YtPlayerUtils.currentWebSessionFingerprint())?.let { return it }
        return resolveShared(videoId, speculative = true)
    }

    private suspend fun resolveShared(
        videoId: String,
        speculative: Boolean,
    ): StreamUrlCache.Entry {
        while (true) {
            val active = inFlightResolutions[videoId]
            if (active != null) {
                if (!speculative && active.speculative) {
                    AppLogger.i(TAG, "Promoting active prefetch for $videoId without restarting extraction")
                }
                return active.deferred.await()
            }

            val scope = if (speculative) prefetchScope else foregroundScope
            val generation = if (speculative) {
                currentResolutionGeneration(videoId)
            } else {
                nextResolutionGeneration(videoId)
            }
            val deferred = scope.async(start = CoroutineStart.LAZY) {
                resolveFresh(
                    videoId = videoId,
                    excludedClientLabels = emptySet(),
                    // A promoted request must not wait for a cancelled/non-cooperative prefetch
                    // still holding the per-video mutex.
                    serializeWithPrefetch = speculative,
                    generation = generation,
                )
            }
            val created = InFlightResolution(deferred, speculative)
            if (inFlightResolutions.putIfAbsent(videoId, created) != null) {
                deferred.cancel()
                continue
            }

            deferred.invokeOnCompletion {
                inFlightResolutions.remove(videoId, created)
            }
            deferred.start()
            return deferred.await()
        }
    }

    private suspend fun resolveFresh(
        videoId: String,
        excludedClientLabels: Set<String>,
        serializeWithPrefetch: Boolean = true,
        generation: Long = currentResolutionGeneration(videoId),
    ): StreamUrlCache.Entry {
        if (!serializeWithPrefetch) {
            return resolveFreshUnserialized(videoId, excludedClientLabels, generation)
        }
        val lock = resolutionLocks.computeIfAbsent(videoId) { Mutex() }
        return lock.withLock {
            resolveFreshUnserialized(videoId, excludedClientLabels, generation)
        }
    }

    private suspend fun resolveFreshUnserialized(
        videoId: String,
        excludedClientLabels: Set<String>,
        generation: Long,
    ): StreamUrlCache.Entry {
        StreamUrlCache.getEntry(videoId, YtPlayerUtils.currentWebSessionFingerprint())
            ?.takeIf { entry ->
                excludedClientLabels.isEmpty() || entry.clientLabel !in excludedClientLabels
            }
            ?.let { return it }

        val now = System.currentTimeMillis()
        PlaybackLatencyTrace.mark(videoId, "resolver-start")
        val info = YtPlayerUtils.resolveAudioFormatInfo(
            videoId = videoId,
            excludedClientLabels = excludedClientLabels,
        ) ?: error("YouTube returned no audio stream for $videoId")
        PlaybackLatencyTrace.mark(videoId, "resolver-end-${info.clientLabel}")
        val expiryMs = now + (info.expiresInSeconds - EXPIRY_SAFETY_SECONDS)
            .coerceAtLeast(MINIMUM_CACHE_SECONDS) * 1_000L

        val entry = StreamUrlCache.Entry(
            url = info.url,
            userAgent = info.userAgent,
            expiryMs = expiryMs,
            clientLabel = info.clientLabel,
            requiresWebSessionHeaders = info.requiresWebSessionHeaders,
            sessionFingerprint = info.sessionFingerprint,
        )
        // A foreground recovery/promotion may have superseded this resolver while an underlying
        // extractor ignored cancellation. Generation validation and cache publication share the
        // same per-video lock as invalidation, so an old resolver cannot slip in a stale URL.
        publishIfCurrent(videoId, generation, entry)
        return entry
    }

    private fun generationState(videoId: String): AtomicLong =
        resolutionGenerations.computeIfAbsent(videoId) { AtomicLong() }

    private fun currentResolutionGeneration(videoId: String): Long =
        synchronized(generationState(videoId)) {
            generationState(videoId).get()
        }

    private fun nextResolutionGeneration(videoId: String): Long =
        synchronized(generationState(videoId)) {
            generationState(videoId).incrementAndGet()
        }

    private fun invalidateResolution(videoId: String): Long =
        synchronized(generationState(videoId)) {
            generationState(videoId).incrementAndGet().also {
                StreamUrlCache.remove(videoId)
            }
        }

    private fun publishIfCurrent(
        videoId: String,
        generation: Long,
        entry: StreamUrlCache.Entry,
    ) {
        synchronized(generationState(videoId)) {
            if (generation != generationState(videoId).get()) return
            StreamUrlCache.put(
                videoId = videoId,
                url = entry.url,
                userAgent = entry.userAgent,
                expiryMs = entry.expiryMs,
                clientLabel = entry.clientLabel,
                requiresWebSessionHeaders = entry.requiresWebSessionHeaders,
                sessionFingerprint = entry.sessionFingerprint,
            )
        }
    }

    /**
     * Warm visible songs without flooding the player endpoint. A foreground playback request is
     * never queued behind these permits: it either consumes an active shared request or starts its
     * own resolver immediately.
     */
    fun prime(videoIds: Iterable<String>, limit: Int = DEFAULT_PREFETCH_COUNT) {
        enqueuePrefetch(
            videoIds = boundedPrefetchVideoIds(videoIds, limit),
            priority = PrefetchPriority.VISIBLE_LIST,
        )
    }

    private fun enqueuePrefetch(
        videoIds: List<String>,
        priority: PrefetchPriority,
    ) {
        videoIds.forEach { videoId ->
            if (!schedulePrefetch(videoId, priority)) return@forEach
            prefetchScope.launch {
                try {
                    val permit = when (priority) {
                        PrefetchPriority.VISIBLE_LIST -> visibleListPrefetchPermits
                        PrefetchPriority.ACTIVE_QUEUE -> activeQueuePrefetchPermits
                    }
                    permit.withPermit {
                        resolveForPrefetch(videoId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    AppLogger.w(TAG, "Prefetch failed for $videoId: ${error.message}")
                } finally {
                    queuedPrefetchPriorities.remove(videoId, priority)
                }
            }
        }
    }

    private fun schedulePrefetch(videoId: String, priority: PrefetchPriority): Boolean {
        if (StreamUrlCache.getEntry(videoId, YtPlayerUtils.currentWebSessionFingerprint()) != null) return false
        if (queuedPrefetchPriorities.putIfAbsent(videoId, priority) != null) return false
        val budget = when (priority) {
            PrefetchPriority.VISIBLE_LIST -> MAX_VISIBLE_LIST_PREFETCH
            PrefetchPriority.ACTIVE_QUEUE -> MAX_ACTIVE_QUEUE_PREFETCH
        }
        val queuedAtPriority = queuedPrefetchPriorities.values.count { it == priority }
        if (queuedAtPriority <= budget) return true
        queuedPrefetchPriorities.remove(videoId, priority)
        return false
    }

    /**
     * Warm the current queue position and a bounded number of following songs.
     *
     * Queue prefetch is deliberately separate from visible-list prefetch: a long playlist should
     * not cause every item to resolve, while advancing playback should always keep a useful
     * runway ahead of the player.
     */
    fun primeQueue(
        videoIds: List<String>,
        currentIndex: Int,
        lookAhead: Int = PLAYBACK_LOOKAHEAD_COUNT,
    ) {
        enqueuePrefetch(
            videoIds = queuePrefetchVideoIds(videoIds, currentIndex, lookAhead),
            priority = PrefetchPriority.ACTIVE_QUEUE,
        )
    }

    private const val DEFAULT_PREFETCH_COUNT = 4
    const val PLAYBACK_LOOKAHEAD_COUNT = 6
    private const val MAX_VISIBLE_LIST_PREFETCH = 4
    private const val MAX_ACTIVE_QUEUE_PREFETCH = 6
    private const val EXPIRY_SAFETY_SECONDS = 300L
    private const val MINIMUM_CACHE_SECONDS = 60L
}