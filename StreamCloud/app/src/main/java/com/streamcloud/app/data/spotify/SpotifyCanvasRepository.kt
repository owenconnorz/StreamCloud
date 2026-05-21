package com.streamcloud.app.data.spotify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Fetches Spotify Canvas video URLs for a track — a short looping video
 * (typically 3–8 s) displayed as a full-bleed animated background on the
 * Now Playing screen.
 *
 * No Spotify login required.  Uses the anonymous web-player token endpoint
 * to authenticate, then searches Spotify for the track and calls the
 * protobuf Canvas API.
 *
 *  1. GET open.spotify.com/get_access_token  → anonymous Bearer token
 *  2. GET api.spotify.com/v1/search           → Spotify track URI
 *  3. POST spclient.wg.spotify.com/canvaz-cache/v0/canvases (protobuf)
 *                                             → canvas video URL
 */
object SpotifyCanvasRepository {

    private const val TAG = "SpotifyCanvas"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Stable anonymous session tracking cookie — any UUID works. */
    private val spT: String = UUID.randomUUID().toString().replace("-", "")

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryMs: Long = 0L

    /** videoId → canvas URL (null = checked, no canvas available). */
    private val cache = LinkedHashMap<String, String?>(64, 0.75f, true)
    private val cacheLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the Spotify Canvas video URL for the given track, or null when
     * no canvas is available.  Results are cached so subsequent calls for the
     * same track are instant.
     */
    suspend fun getCanvasUrl(videoId: String, title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                if (cache.containsKey(videoId)) return@withContext cache[videoId]
            }
            val url = runCatching {
                val token = ensureToken() ?: return@runCatching null
                val uri   = searchTrack(token, title, artist) ?: run {
                    Log.d(TAG, "Not found on Spotify: \"$title\" – $artist")
                    return@runCatching null
                }
                fetchCanvas(token, uri).also { u ->
                    if (u != null) Log.i(TAG, "Canvas [$title]: ${u.take(60)}…")
                }
            }.getOrElse { e ->
                Log.d(TAG, "Canvas error for $videoId: ${e.message}")
                null
            }
            synchronized(cacheLock) { cache[videoId] = url }
            url
        }

    // ── Token ─────────────────────────────────────────────────────────────────

    private fun ensureToken(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiryMs - 60_000L }?.let { return it }
        return runCatching {
            val req = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                )
                .header("Accept", "application/json")
                .header("Cookie", "sp_t=$spT; sp_new=1")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.d(TAG, "Token HTTP ${resp.code}"); return null }
                val obj = json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
                val t   = obj["accessToken"]?.jsonPrimitive?.content ?: return null
                val exp = obj["accessTokenExpirationTimestampMs"]
                    ?.jsonPrimitive?.content?.toLongOrNull() ?: (now + 3_600_000L)
                cachedToken    = t
                tokenExpiryMs  = exp
                t
            }
        }.getOrElse { Log.d(TAG, "Token error: ${it.message}"); null }
    }

    // ── Spotify search ────────────────────────────────────────────────────────

    private fun searchTrack(token: String, title: String, artist: String): String? {
        val q   = URLEncoder.encode("${title.take(50)} ${artist.take(30)}", "UTF-8")
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/search?q=$q&type=track&limit=3")
            .header("Authorization", "Bearer $token")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            )
            .get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
                    ["tracks"]?.jsonObject
                    ?.get("items")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("uri")?.jsonPrimitive?.content
            }
        }.getOrNull()
    }

    // ── Canvas protobuf API ───────────────────────────────────────────────────

    private fun fetchCanvas(token: String, spotifyUri: String): String? {
        val req = Request.Builder()
            .url("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")
            .post(buildCanvasRequest(spotifyUri).toRequestBody("application/x-protobuf".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/x-protobuf")
            .header("User-Agent", "Spotify/8.6.72 Android/29 (Pixel 4)")
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.d(TAG, "Canvas API HTTP ${resp.code}"); return null }
                parseCanvasResponse(resp.body?.bytes() ?: return null)
            }
        }.getOrNull()
    }

    // ── Hand-rolled minimal protobuf ──────────────────────────────────────────

    /**
     * Encode:
     *   EntityCanvazRequest {
     *     entities (field 1) {
     *       entity_uri (field 1) = spotifyUri
     *     }
     *   }
     */
    private fun buildCanvasRequest(spotifyUri: String): ByteArray {
        val uriBytes = spotifyUri.toByteArray(Charsets.UTF_8)
        val inner    = byteArrayOf(0x0A) + varint(uriBytes.size) + uriBytes
        return          byteArrayOf(0x0A) + varint(inner.size)    + inner
    }

    /**
     * Decode:
     *   EntityCanvaz {
     *     canvases (field 1) {
     *       entity_uri (field 1)   — ignored
     *       url        (field 2)   — this is what we want
     *       ...
     *     }
     *   }
     */
    private fun parseCanvasResponse(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, t1) = readVarint(bytes, i); i += t1
            val field = (tag ushr 3).toInt()
            val wire  = (tag and 7L).toInt()
            if (wire == 2 && field == 1) {          // canvases sub-message
                val (len, t2) = readVarint(bytes, i); i += t2
                val inner = bytes.sliceArray(i until (i + len.toInt())); i += len.toInt()
                val url = parseCanvasEntry(inner)
                if (url != null) return url
            } else {
                i = skipField(bytes, i, wire) ?: return null
            }
        }
        return null
    }

    private fun parseCanvasEntry(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, t1) = readVarint(bytes, i); i += t1
            val field = (tag ushr 3).toInt()
            val wire  = (tag and 7L).toInt()
            when {
                wire == 2 -> {
                    val (len, t2) = readVarint(bytes, i); i += t2
                    val data = bytes.sliceArray(i until (i + len.toInt())); i += len.toInt()
                    if (field == 2) {                // url
                        val url = String(data, Charsets.UTF_8)
                        if (url.startsWith("https://")) return url
                    }
                }
                wire == 0 -> { val (_, t2) = readVarint(bytes, i); i += t2 }
                else      -> return null
            }
        }
        return null
    }

    // ── Protobuf wire helpers ─────────────────────────────────────────────────

    private fun varint(value: Int): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (v and 0x7F.inv() != 0) { out.add(((v and 0x7F) or 0x80).toByte()); v = v ushr 7 }
        out.add(v.toByte())
        return out.toByteArray()
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        var r = 0L; var shift = 0; var i = offset
        while (i < bytes.size) {
            val b = bytes[i++].toLong() and 0xFF
            r = r or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return r to (i - offset)
    }

    private fun skipField(bytes: ByteArray, offset: Int, wire: Int): Int? {
        return when (wire) {
            0 -> { val (_, n) = readVarint(bytes, offset); offset + n }
            1 -> offset + 8
            2 -> { val (len, n) = readVarint(bytes, offset); offset + n + len.toInt() }
            5 -> offset + 4
            else -> null
        }
    }
}
