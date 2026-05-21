package com.streamcloud.app.data.ytmusic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide in-memory cache for resolved YouTube stream URLs.
 *
 * ── Why this exists ──────────────────────────────────────────────────────
 * [MusicPlaybackService]'s [ResolvingDataSource] lambda is called by ExoPlayer
 * on *every* data-spec request — initial probe, seeks, rebuffer fills — so
 * without caching it would do a fresh Innertube POST (300–800 ms) each time.
 * This object centralises the cache so both the service and [StreamUrlCache.warmup]
 * share the same map.
 *
 * ── URL lifetime ─────────────────────────────────────────────────────────
 * YouTube CDN URLs are valid for ≈ 21 600 s (6 h). The expiry reported in the
 * Innertube player response is stored as the cache entry's TTL minus a 5-minute
 * safety buffer. NewPipe-sourced URLs are cached for 1 hour (YouTube doesn't
 * report their lifetime explicitly).
 *
 * ── Pre-warming (playlist open) ──────────────────────────────────────────
 * [warmup] is called from [YtPlaylistScreen] when a playlist finishes loading.
 * It resolves every track's stream URL in the background (3 at a time so
 * YouTube's API isn't hammered) so the first play is instant regardless of
 * which song the user taps.
 */
object StreamUrlCache {

    private const val TAG = "StreamUrlCache"

    /** videoId → (streamUrl, expiryEpochMs) */
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    // ── Read / write ──────────────────────────────────────────────────────

    /**
     * Return the cached stream URL for [videoId] if present and not expired,
     * null otherwise. Expired entries are removed on access.
     */
    fun get(videoId: String): String? {
        val (url, expiryMs) = cache[videoId] ?: return null
        return if (System.currentTimeMillis() < expiryMs) url
        else {
            cache.remove(videoId)
            null
        }
    }

    /** Store a resolved URL with an explicit [expiryMs] epoch timestamp. */
    fun put(videoId: String, url: String, expiryMs: Long) {
        cache[videoId] = Pair(url, expiryMs)
    }

    /** Remaining TTL in seconds for a cached entry, or null if not cached. */
    fun ttlSeconds(videoId: String): Long? {
        val (_, expiryMs) = cache[videoId] ?: return null
        val remaining = (expiryMs - System.currentTimeMillis()) / 1_000L
        return if (remaining > 0) remaining else null
    }

    // ── Pre-warming ───────────────────────────────────────────────────────

    /**
     * Pre-resolve stream URLs for every [videoId] in [videoIds] so they are
     * ready in the cache before the user taps play.
     *
     * Behaviour:
     *  - Already-cached (non-expired) entries are skipped instantly.
     *  - Remaining IDs are resolved 3 at a time (batch concurrency) to avoid
     *    flooding the Innertube API.  A 100 ms gap between batches gives
     *    YouTube's rate-limiter some breathing room.
     *  - If the calling coroutine is cancelled (e.g. user leaves the screen),
     *    in-flight `async` blocks are also cancelled automatically.
     *  - Failures per track are swallowed silently — the service will fall back
     *    to its own resolution when the user actually taps play.
     *
     * Typical timing for a 380-track playlist (500 ms avg per track, 3 concurrent):
     *   first  3 tracks ready in   ~0.5 s
     *   first 30 tracks ready in   ~5 s
     *   all  380 tracks ready in  ~64 s  (runs quietly in background)
     */
    suspend fun warmup(videoIds: List<String>) = coroutineScope {
        val toResolve = videoIds.filter { get(it) == null }
        if (toResolve.isEmpty()) {
            Log.d(TAG, "warmup: all ${videoIds.size} tracks already cached")
            return@coroutineScope
        }
        Log.d(TAG, "warmup: pre-resolving ${toResolve.size}/${videoIds.size} tracks (3 concurrent)")

        toResolve.chunked(3).forEach { batch ->
            batch.map { videoId ->
                async(Dispatchers.IO) {
                    runCatching {
                        val info = YtPlayerUtils.resolveAudioFormatInfo(videoId)
                            ?: return@runCatching
                        val expiryMs = System.currentTimeMillis() +
                            (info.expiresInSeconds - 300).coerceAtLeast(60) * 1_000L
                        put(videoId, info.url, expiryMs)
                        Log.d(TAG, "warmup: cached $videoId itag=${info.itag}")
                    }.onFailure {
                        Log.d(TAG, "warmup: skipped $videoId — ${it.message}")
                    }
                }
            }.awaitAll()

            // Brief pause between batches — keeps Innertube happy.
            delay(100)
        }

        Log.d(TAG, "warmup done: ${cache.size} total entries in cache")
    }
}
