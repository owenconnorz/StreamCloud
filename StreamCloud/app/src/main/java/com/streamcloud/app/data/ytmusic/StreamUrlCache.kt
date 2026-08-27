package com.streamcloud.app.data.ytmusic

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object StreamUrlCache {

    private const val PREFERENCES_NAME = "yt_music_stream_urls"
    private const val URL_KEY = "url"
    private const val USER_AGENT_KEY = "userAgent"
    private const val EXPIRY_KEY = "expiryMs"
    private const val CLIENT_LABEL_KEY = "clientLabel"
    private const val WEB_SESSION_HEADERS_KEY = "requiresWebSessionHeaders"
    private const val SESSION_FINGERPRINT_KEY = "sessionFingerprint"
    private const val CONTENT_LENGTH_KEY = "contentLength"
    private const val REQUIRES_BYTE_RANGE_KEY = "requiresByteRange"
    private const val GENERATION_KEY = "generation"
    private const val MAX_PERSISTED_ENTRIES = 128

    data class Entry(
        val url: String,

        val userAgent: String,
        val expiryMs: Long,
        /** Innertube client that minted this URL, used to avoid it after a CDN rejection. */
        val clientLabel: String? = null,
        /**
         * Web/PoToken streams need the browser session that created them. Anonymous app-client
         * streams must be requested without browser-session headers.
         */
        val requiresWebSessionHeaders: Boolean = false,
        /** Non-secret hash of the cookie/visitor state that minted this web-bound URL. */
        val sessionFingerprint: String? = null,
        /** Advertised complete media length, when supplied by the selected player format. */
        val contentLength: Long? = null,
        /** Whether playback must retain Media3's bounded HTTP range request semantics. */
        val requiresByteRange: Boolean = true,
        /** Resolver generation that owns this entry; older work cannot replace it. */
        val generation: Long = 0L,
    )

    private val cache = ConcurrentHashMap<String, Entry>()
    @Volatile
    private var preferences: SharedPreferences? = null

    /**
     * Keeps valid signed URLs across a player-service or application restart. The URL remains
     * bounded by YouTube's own expiry, and a CDN rejection removes it immediately via [remove].
     */
    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences = prefs
        val expiredOrInvalidKeys = prefs.all
            .filterValues { value ->
                val expiryMs = (value as? String)
                    ?.let(::decode)
                    ?.expiryMs
                    ?: Long.MIN_VALUE
                expiryMs <= System.currentTimeMillis()
            }
            .keys
        if (expiredOrInvalidKeys.isNotEmpty()) {
            prefs.edit().apply {
                expiredOrInvalidKeys.forEach { key -> remove(key) }
            }.apply()
        }
        trimPersistentEntries(prefs)
    }




    fun getEntry(videoId: String, expectedSessionFingerprint: String? = null): Entry? {
        val entry = cache[videoId]
            ?: preferences?.getString(videoId, null)
                ?.let(::decode)
                ?.also { cache[videoId] = it }
            ?: return null
        if (entry.requiresWebSessionHeaders &&
            (expectedSessionFingerprint == null || entry.sessionFingerprint != expectedSessionFingerprint)
        ) {
            discard(videoId)
            return null
        }
        return entry.takeIf { System.currentTimeMillis() < it.expiryMs }
            ?: run {
                discard(videoId)
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
        requiresWebSessionHeaders: Boolean = false,
        sessionFingerprint: String? = null,
        contentLength: Long? = null,
        requiresByteRange: Boolean = true,
        generation: Long = 0L,
    ) {
        val entry = Entry(
            url = url,
            userAgent = userAgent,
            expiryMs = expiryMs,
            clientLabel = clientLabel,
            requiresWebSessionHeaders = requiresWebSessionHeaders,
            sessionFingerprint = sessionFingerprint,
            contentLength = contentLength,
            requiresByteRange = requiresByteRange,
            generation = generation,
        )
        cache[videoId] = entry
        if (expiryMs > System.currentTimeMillis()) {
            preferences?.edit()
                ?.putString(videoId, encode(entry))
                ?.apply()
            preferences?.let(::trimPersistentEntries)
        }
    }

    /**
     * Removes and returns the cached stream. A signed Googlevideo URL can be rejected before its
     * advertised expiry when YouTube changes its client/PoToken requirements, so callers must be
     * able to discard it immediately rather than retrying it for hours.
     */
    fun remove(videoId: String): Entry? {
        val entry = cache.remove(videoId)
            ?: preferences?.getString(videoId, null)?.let(::decode)
        preferences?.edit()?.remove(videoId)?.apply()
        return entry
    }


    fun ttlSeconds(videoId: String): Long? {
        val entry = getEntry(videoId) ?: return null
        val remaining = (entry.expiryMs - System.currentTimeMillis()) / 1_000L
        return if (remaining > 0) remaining else null
    }

    private fun discard(videoId: String) {
        cache.remove(videoId)
        preferences?.edit()?.remove(videoId)?.apply()
    }

    private fun encode(entry: Entry): String = JSONObject()
        .put(URL_KEY, entry.url)
        .put(USER_AGENT_KEY, entry.userAgent)
        .put(EXPIRY_KEY, entry.expiryMs)
        .put(CLIENT_LABEL_KEY, entry.clientLabel)
        .put(WEB_SESSION_HEADERS_KEY, entry.requiresWebSessionHeaders)
        .put(SESSION_FINGERPRINT_KEY, entry.sessionFingerprint)
        .put(CONTENT_LENGTH_KEY, entry.contentLength)
        .put(REQUIRES_BYTE_RANGE_KEY, entry.requiresByteRange)
        .put(GENERATION_KEY, entry.generation)
        .toString()

    private fun decode(value: String): Entry? = runCatching {
        JSONObject(value).let { json ->
            Entry(
                url = json.getString(URL_KEY),
                userAgent = json.getString(USER_AGENT_KEY),
                expiryMs = json.getLong(EXPIRY_KEY),
                clientLabel = json.optString(CLIENT_LABEL_KEY)
                    .takeUnless { it.isBlank() || it == "null" },
                requiresWebSessionHeaders = json.optBoolean(WEB_SESSION_HEADERS_KEY),
                sessionFingerprint = json.optString(SESSION_FINGERPRINT_KEY)
                    .takeUnless { it.isBlank() || it == "null" },
                contentLength = json.optLong(CONTENT_LENGTH_KEY, -1L)
                    .takeIf { it >= 0L },
                requiresByteRange = json.optBoolean(REQUIRES_BYTE_RANGE_KEY, true),
                generation = json.optLong(GENERATION_KEY, 0L),
            )
        }
    }.getOrNull()

    @Synchronized
    private fun trimPersistentEntries(prefs: SharedPreferences) {
        val entries = prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.let(::decode)?.let { key to it }
        }
        if (entries.size <= MAX_PERSISTED_ENTRIES) return
        val removeCount = entries.size - MAX_PERSISTED_ENTRIES
        val oldestKeys = entries
            .sortedWith(compareBy<Pair<String, Entry>>({ it.second.expiryMs }, { it.first }))
            .take(removeCount)
            .map { it.first }
        prefs.edit().apply {
            oldestKeys.forEach(::remove)
        }.apply()
        oldestKeys.forEach(cache::remove)
    }

}
