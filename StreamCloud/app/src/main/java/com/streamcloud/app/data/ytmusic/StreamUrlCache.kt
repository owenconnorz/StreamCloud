package com.streamcloud.app.data.ytmusic

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object StreamUrlCache {

    private const val TAG = "StreamUrlCache"

    data class Entry(
        val url: String,

        val userAgent: String,
        val expiryMs: Long,
        /** Innertube client that minted this URL, used to avoid it after a CDN rejection. */
        val clientLabel: String? = null,
    )

    private const val FALLBACK_UA =
        "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip"


    private val cache = ConcurrentHashMap<String, Entry>()




    fun getEntry(videoId: String): Entry? {
        val entry = cache[videoId] ?: return null
        return if (System.currentTimeMillis() < entry.expiryMs) entry
        else {
            cache.remove(videoId)
            null
        }
    }


    fun get(videoId: String): String? = getEntry(videoId)?.url


    fun put(
        videoId: String,
        url: String,
        userAgent: String,
        expiryMs: Long,
        clientLabel: String? = null,
    ) {
        cache[videoId] = Entry(url, userAgent, expiryMs, clientLabel)
    }

    /**
     * Removes and returns the cached stream. A signed Googlevideo URL can be rejected before its
     * advertised expiry when YouTube changes its client/PoToken requirements, so callers must be
     * able to discard it immediately rather than retrying it for hours.
     */
    fun remove(videoId: String): Entry? = cache.remove(videoId)


    fun ttlSeconds(videoId: String): Long? {
        val entry = cache[videoId] ?: return null
        val remaining = (entry.expiryMs - System.currentTimeMillis()) / 1_000L
        return if (remaining > 0) remaining else null
    }




}
