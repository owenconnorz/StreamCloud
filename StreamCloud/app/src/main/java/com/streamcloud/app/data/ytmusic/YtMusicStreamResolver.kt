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

internal fun boundedPrefetchVideoIds(videoIds: Iterable<String>, limit: Int): List<String> =
    videoIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(limit.coerceAtLeast(0))
        .toList()

/**
 * Resolves and caches the short-lived YouTube audio URL independently of ExoPlayer.
 *
 * Keeping this work shared between the UI prefetch and ResolvingDataSource means a track is
 * resolved only once: a tap can overlap resolution with service startup, while the data source
 * simply consumes the cached result when ExoPlayer prepares.
 */
object YtMusicStreamResolver {
    private const val TAG = "YtMusicStreamResolver"

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolutionLocks = ConcurrentHashMap<String, Mutex>()
    private val inFlightResolutions = ConcurrentHashMap<String, Deferred<StreamUrlCache.Entry>>()
    private val prefetchPermits = Semaphore(PREFETCH_PARALLELISM)

    suspend fun resolveInnertube(
        videoId: String,
        excludedClientLabels: Set<String> = emptySet(),
    ): StreamUrlCache.Entry {
        require(videoId.isNotBlank()) { "A YouTube video ID is required." }

        StreamUrlCache.getEntry(videoId)
            ?.takeIf { entry ->
                excludedClientLabels.isEmpty() || entry.clientLabel !in excludedClientLabels
            }
            ?.let { return it }

        // A rejected client must not reuse a speculative request that may be resolving the same
        // client. This path only follows a confirmed CDN failure and is intentionally isolated.
        if (excludedClientLabels.isNotEmpty()) {
            return resolveFresh(videoId, excludedClientLabels)
        }

        // UI prefetch and ResolvingDataSource often begin within the same few milliseconds. Share
        // the actual resolver Deferred, rather than merely serializing callers behind a mutex, so
        // the player always receives the cache entry produced by the warm-up request.
        inFlightResolutions[videoId]?.let { return it.await() }
        val created = prefetchScope.async(start = CoroutineStart.LAZY) {
            resolveFresh(videoId, emptySet())
        }
        val shared = inFlightResolutions.putIfAbsent(videoId, created)
        if (shared != null) return shared.await()

        created.start()
        return try {
            created.await()
        } finally {
            inFlightResolutions.remove(videoId, created)
        }
    }

    private suspend fun resolveFresh(
        videoId: String,
        excludedClientLabels: Set<String>,
    ): StreamUrlCache.Entry {
        val lock = resolutionLocks.computeIfAbsent(videoId) { Mutex() }
        return lock.withLock {
            StreamUrlCache.getEntry(videoId)
                ?.takeIf { entry ->
                    excludedClientLabels.isEmpty() || entry.clientLabel !in excludedClientLabels
                }
                ?.let { return@withLock it }

            val now = System.currentTimeMillis()
            val info = YtPlayerUtils.resolveAudioFormatInfo(
                videoId = videoId,
                excludedClientLabels = excludedClientLabels,
            ) ?: error("YouTube returned no audio stream for $videoId")
            val expiryMs = now + (info.expiresInSeconds - EXPIRY_SAFETY_SECONDS)
                .coerceAtLeast(MINIMUM_CACHE_SECONDS) * 1_000L

            StreamUrlCache.put(
                videoId = videoId,
                url = info.url,
                userAgent = info.userAgent,
                expiryMs = expiryMs,
                clientLabel = info.clientLabel,
                requiresWebSessionHeaders = info.requiresWebSessionHeaders,
            )
            StreamUrlCache.getEntry(videoId)
                ?: error("Resolved YouTube stream was not cached for $videoId")
        }
    }

    /**
     * Warm visible songs without flooding the player endpoint. A foreground playback request is
     * never queued behind these permits: it either consumes an active shared request or starts its
     * own resolver immediately.
     */
    fun prime(videoIds: Iterable<String>, limit: Int = DEFAULT_PREFETCH_COUNT) {
        val ids = boundedPrefetchVideoIds(videoIds, limit)
        if (ids.isEmpty()) return

        ids.forEach { videoId ->
            prefetchScope.launch {
                prefetchPermits.withPermit {
                    runCatching { resolveInnertube(videoId) }
                }
                    .onFailure { error ->
                        AppLogger.w(TAG, "Prefetch failed for $videoId: ${error.message}")
                    }
            }
        }
    }

    private const val DEFAULT_PREFETCH_COUNT = 8
    private const val PREFETCH_PARALLELISM = 2
    private const val EXPIRY_SAFETY_SECONDS = 300L
    private const val MINIMUM_CACHE_SECONDS = 60L
}