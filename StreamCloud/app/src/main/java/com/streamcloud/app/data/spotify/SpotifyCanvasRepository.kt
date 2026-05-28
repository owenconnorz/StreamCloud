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

object SpotifyCanvasRepository {

    private const val TAG = "SpotifyCanvas"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val spT: String = UUID.randomUUID().toString().replace("-", "")

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryMs: Long = 0L

    @Volatile private var cachedClientToken: String? = null
    @Volatile private var clientTokenExpiryMs: Long = 0L

    @Volatile private var storedCookie: String? = null

    fun setSpotifyCookie(cookie: String?) {
        storedCookie = cookie?.takeIf { it.isNotBlank() }
        cachedToken = null
        tokenExpiryMs = 0L
        cachedClientToken = null
        clientTokenExpiryMs = 0L
    }

    fun invalidateToken() {
        cachedToken = null
        tokenExpiryMs = 0L
        cachedClientToken = null
        clientTokenExpiryMs = 0L
    }

    private val cache = LinkedHashMap<String, String?>(64, 0.75f, true)
    private val cacheLock = Any()

    suspend fun getCanvasUrl(videoId: String, title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                if (cache.containsKey(videoId)) return@withContext cache[videoId]
            }
            val url = runCatching {
                val token       = ensureToken()       ?: return@runCatching null
                val clientToken = ensureClientToken() ?: return@runCatching null
                val uri = searchTrack(token, title, artist) ?: run {
                    Log.d(TAG, "Not found on Spotify: \"$title\" – $artist")
                    return@runCatching null
                }
                fetchCanvas(token, clientToken, uri).also { u ->
                    if (u != null) Log.i(TAG, "Canvas [$title]: ${u.take(60)}…")
                }
            }.getOrElse { e ->
                Log.d(TAG, "Canvas error for $videoId: ${e.message}")
                null
            }
            synchronized(cacheLock) { cache[videoId] = url }
            url
        }

    // ── Access token (from sp_dc cookie or anonymous) ─────────────────────────

    private fun ensureToken(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiryMs - 60_000L }?.let { return it }
        return runCatching {
            val cookieHeader = storedCookie ?: "sp_t=$spT; sp_new=1"
            val req = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .header("Cookie", cookieHeader)
                .header("Referer", "https://open.spotify.com/")
                .get().build()

            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.d(TAG, "Token HTTP ${resp.code}"); null }
                else resp.body?.string()
            } ?: return@runCatching null

            val obj  = json.parseToJsonElement(body).jsonObject
            val t    = obj["accessToken"]?.jsonPrimitive?.content ?: return@runCatching null
            val exp  = obj["accessTokenExpirationTimestampMs"]
                ?.jsonPrimitive?.content?.toLongOrNull() ?: (now + 3_600_000L)
            val isAnon = obj["isAnonymous"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
            if (isAnon && storedCookie != null) {
                Log.d(TAG, "Stored cookie yielded anonymous token — cookie may have expired")
            }
            cachedToken   = t
            tokenExpiryMs = exp
            t
        }.getOrElse { Log.d(TAG, "Token error: ${it.message}"); null }
    }

    // ── Client token (required by spclient endpoints since ~2023) ─────────────
    // SimpMusic and other open-source players use this exact endpoint + payload.

    private fun ensureClientToken(): String? {
        val now = System.currentTimeMillis()
        cachedClientToken?.takeIf { now < clientTokenExpiryMs - 60_000L }?.let { return it }
        return runCatching {
            val payload = """
                {
                  "client_data": {
                    "client_version": "1.2.52.442",
                    "client_id": "d8a5ed958d274c2e8ee717e6a4b0971d",
                    "js_sdk_data": {
                      "device_brand": "unknown",
                      "device_model": "unknown",
                      "os": "android",
                      "os_version": "unknown"
                    }
                  }
                }
            """.trimIndent()
            val req = Request.Builder()
                .url("https://clienttoken.spotify.com/v1/clienttoken")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Origin", "https://open.spotify.com")
                .build()

            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.d(TAG, "ClientToken HTTP ${resp.code}"); null }
                else resp.body?.string()
            } ?: return@runCatching null

            val obj          = json.parseToJsonElement(body).jsonObject
            val grantedToken = obj["granted_token"]?.jsonObject ?: return@runCatching null
            val ct           = grantedToken["token"]?.jsonPrimitive?.content ?: return@runCatching null
            val expiresIn    = grantedToken["expires_after_seconds"]?.jsonPrimitive
                ?.content?.toLongOrNull() ?: 1800L
            cachedClientToken   = ct
            clientTokenExpiryMs = now + expiresIn * 1000L
            ct
        }.getOrElse { Log.d(TAG, "ClientToken error: ${it.message}"); null }
    }

    // ── Track search ──────────────────────────────────────────────────────────

    private fun searchTrack(token: String, title: String, artist: String): String? {
        val q = URLEncoder.encode("${title.take(50)} ${artist.take(30)}", "UTF-8")
        val req = Request.Builder()
            .url("https://api.spotify.com/v1/search?q=$q&type=track&limit=3")
            .header("Authorization", "Bearer $token")
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .get().build()
        return runCatching {
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            } ?: return@runCatching null
            json.parseToJsonElement(body).jsonObject
                .get("tracks")?.jsonObject
                ?.get("items")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("uri")?.jsonPrimitive?.content
        }.getOrNull()
    }

    // ── Canvas fetch ──────────────────────────────────────────────────────────

    private fun fetchCanvas(token: String, clientToken: String, spotifyUri: String): String? {
        val req = Request.Builder()
            .url("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")
            .post(buildCanvasRequest(spotifyUri).toRequestBody("application/x-protobuf".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("client-token", clientToken)
            .header("Accept", "application/x-protobuf")
            .header("User-Agent", "Spotify/8.6.72 Android/29 (Pixel 4)")
            .build()
        return runCatching {
            val bytes = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.d(TAG, "Canvas API HTTP ${resp.code}"); null }
                else resp.body?.bytes()
            } ?: return@runCatching null
            parseCanvasResponse(bytes)
        }.getOrNull()
    }

    // ── Protobuf helpers ──────────────────────────────────────────────────────

    private fun buildCanvasRequest(spotifyUri: String): ByteArray {
        val uriBytes = spotifyUri.toByteArray(Charsets.UTF_8)
        val inner    = byteArrayOf(0x0A) + varint(uriBytes.size) + uriBytes
        return          byteArrayOf(0x0A) + varint(inner.size)    + inner
    }

    private fun parseCanvasResponse(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, t1) = readVarint(bytes, i); i += t1
            val field = (tag ushr 3).toInt()
            val wire  = (tag and 7L).toInt()
            if (wire == 2 && field == 1) {
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
                    if (field == 2) {
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
