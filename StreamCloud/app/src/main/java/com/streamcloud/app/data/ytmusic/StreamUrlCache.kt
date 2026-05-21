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
 * Each entry stores the stream URL, the User-Agent of the client that produced
 * it, and an expiry timestamp.  The UA must be preserved because YouTube CDN
 * binds stream URLs to the requesting client — using a different UA when
 * fetching the bytes returns HTTP 403.
 */
object StreamUrlCache {

    private const val TAG = "StreamUrlCache"

    data class Entry(
        val url: String,
        /** UA of the Innertube client that returned this URL. */
        val userAgent: String,
        val expiryMs: Long,
    )

    private const val FALLBACK_UA =
        "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip"

    /** videoId → Entry */
    private val cache = ConcurrentHashMap<String, Entry>()

    // ── Read / write ──────────────────────────────────────────────────────

    /**
     * Return the cached [Entry] for [videoId] if present and not expired,
     * null otherwise. Expired entries are removed on access.
     */
    fun getEntry(videoId: String): Entry? {
        val entry = cache[videoId] ?: return null
        return if (System.currentTimeMillis() < entry.expiryMs) entry
        else {
            cache.remove(videoId)
            null
        }
    }

    /** Convenience overload — returns just the URL. */
    fun get(videoId: String): String? = getEntry(videoId)?.url

    /** Store a resolved URL with its User-Agent and an explicit [expiryMs]. */
    fun put(videoId: String, url: String, userAgent: String, expiryMs: Long) {
        cache[videoId] = Entry(url, userAgent, expiryMs)
    }

    /** Remaining TTL in seconds for a cached entry, or null if not cached. */
    fun ttlSeconds(videoId: String): Long? {
        val entry = cache[videoId] ?: return null
        val remaining = (entry.expiryMs - System.currentTimeMillis()) / 1_000L
        return if (remaining > 0) remaining else null
    }

    // ── Pre-warming ───────────────────────────────────────────────────────

    /**
     * Pre-resolve stream URLs for every [videoId] in [videoIds] so they are
     * ready in the cache before the user taps play.
     */
    suspend fun warmup(videoIds: List<String>) = coroutineScope {
        val toResolve = videoIds.filter { getEntry(it) == null }
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
                        put(videoId, info.url, info.userAgent, expiryMs)
                        Log.d(TAG, "warmup: cached $videoId itag=${info.itag} ua=${info.userAgent.take(40)}")
                    }.onFailure {
                        Log.d(TAG, "warmup: skipped $videoId — ${it.message}")
                    }
                }
            }.awaitAll()

            delay(100)
        }

        Log.d(TAG, "warmup done: ${cache.size} total entries in cache")
    }
}
