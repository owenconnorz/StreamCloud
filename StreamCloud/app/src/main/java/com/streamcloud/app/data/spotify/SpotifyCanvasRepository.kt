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
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SpotifyCanvasRepository {

    private const val TAG = "SpotifyCanvas"

    // Hardcoded fallback secret (v61, as of May 2025). Live secrets are fetched below.
    private val FALLBACK_SECRET_BYTES = intArrayOf(
        44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75,
        76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78,
    )
    private const val FALLBACK_SECRET_VERSION = "61"

    private const val SECRETS_URL =
        "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json"
    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val TOKEN_URL      = "https://open.spotify.com/api/token"
    private const val OLD_TOKEN_URL  = "https://open.spotify.com/get_access_token"
    private const val SEARCH_URL     = "https://api.spotify.com/v1/search"
    private const val CANVAS_URL     = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"

    private const val UA_WEB  = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val UA_MOBILE = "Spotify/8.5.49 iOS/13.3.1 (Pixel 4)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Stored state ──────────────────────────────────────────────────────────

    @Volatile private var storedSpDc: String? = null

    @Volatile private var cachedAccessToken: String?  = null
    @Volatile private var accessTokenExpiryMs: Long   = 0L

    @Volatile private var cachedClientToken: String?  = null
    @Volatile private var clientTokenExpiryMs: Long   = 0L

    @Volatile private var secretBytes: IntArray = FALLBACK_SECRET_BYTES
    @Volatile private var secretVersion: String = FALLBACK_SECRET_VERSION
    @Volatile private var secretFetchedMs: Long = 0L          // re-fetch every 6 hours

    private val urlCache   = LinkedHashMap<String, String?>(64, 0.75f, true)
    private val cacheLock  = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by SpotifyLoginActivity / SpotifyAccountRow on login or logout.
     * Accepts either a raw sp_dc value ("AQxxx…") or the full WebView cookie string
     * ("sp_dc=AQxxx; sp_key=…") and extracts/stores only the sp_dc portion.
     */
    fun setSpotifyCookie(cookie: String?) {
        storedSpDc = when {
            cookie.isNullOrBlank() -> null
            cookie.contains("sp_dc=") -> cookie.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("sp_dc=") }
                ?.substringAfter("sp_dc=")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            else -> cookie.trim().takeIf { it.isNotBlank() }
        }
        invalidateTokens()
    }

    fun invalidateTokens() {
        cachedAccessToken  = null
        accessTokenExpiryMs = 0L
        cachedClientToken  = null
        clientTokenExpiryMs = 0L
    }

    /**
     * Returns a looping Canvas video URL for the currently playing track, or null if not found.
     * [videoId] is used only as a cache key (it's the YouTube ID).
     */
    suspend fun getCanvasUrl(videoId: String, title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                if (urlCache.containsKey(videoId)) return@withContext urlCache[videoId]
            }
            val url = runCatching {
                if (storedSpDc.isNullOrBlank()) {
                    Log.d(TAG, "No sp_dc cookie — canvas requires Spotify login")
                    return@runCatching null
                }
                val token       = ensureAccessToken() ?: run { Log.d(TAG, "No access token"); return@runCatching null }
                val clientToken = ensureClientToken() ?: run { Log.d(TAG, "No client token"); return@runCatching null }
                val trackId     = searchTrackId(token, clientToken, title, artist) ?: run {
                    Log.d(TAG, "Track not found on Spotify: \"$title\" – $artist")
                    return@runCatching null
                }
                fetchCanvas(token, clientToken, trackId)
            }.getOrElse { e ->
                Log.d(TAG, "Canvas error for [$title]: ${e.message}")
                null
            }
            synchronized(cacheLock) { urlCache[videoId] = url }
            url
        }

    // ── TOTP secret ───────────────────────────────────────────────────────────

    private fun ensureSecrets() {
        val now = System.currentTimeMillis()
        if (now - secretFetchedMs < 6 * 3_600_000L) return
        runCatching {
            val req = Request.Builder().url(SECRETS_URL)
                .header("User-Agent", UA_WEB).get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching
                r.body?.string()
            } ?: return@runCatching

            val obj     = JSONObject(body)
            val latestV = obj.keys().asSequence().mapNotNull { it.toIntOrNull() }.maxOrNull()
                ?.toString() ?: return@runCatching
            val arr     = obj.optJSONArray(latestV) ?: return@runCatching
            val bytes   = IntArray(arr.length()) { arr.getInt(it) }
            secretBytes   = bytes
            secretVersion = latestV
            secretFetchedMs = now
            Log.d(TAG, "TOTP secrets updated to v$latestV (${bytes.size} bytes)")
        }.onFailure { Log.d(TAG, "Secret fetch failed: ${it.message}") }
    }

    // ── Server time ───────────────────────────────────────────────────────────

    private fun fetchServerTime(spDc: String): Long {
        return runCatching {
            val req = Request.Builder().url(SERVER_TIME_URL)
                .header("User-Agent", UA_WEB)
                .header("Cookie", "sp_dc=$spDc")
                .header("App-platform", "WebPlayer")
                .header("Spotify-App-Version", "1.2.61.20.g3b4cd5b2")
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching System.currentTimeMillis()
                r.body?.string()
            } ?: return@runCatching System.currentTimeMillis()
            val seconds = JSONObject(body).optLong("serverTime")
            if (seconds > 0L) seconds * 1000L else System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())
    }

    // ── TOTP computation ──────────────────────────────────────────────────────
    // Standard HOTP/TOTP per RFC 6238, using raw bytes as secret and 30-second window.

    private fun generateTotp(serverTimeMs: Long, secret: IntArray): String {
        val counter  = serverTimeMs / 1000L / 30L
        val msg      = ByteBuffer.allocate(8).putLong(counter).array()
        val keyBytes = ByteArray(secret.size) { secret[it].toByte() }
        val mac      = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hmac   = mac.doFinal(msg)
        val offset = hmac[19].toInt() and 0x0f
        val code   = ((hmac[offset].toInt() and 0x7f) shl 24) or
                     ((hmac[offset + 1].toInt() and 0xff) shl 16) or
                     ((hmac[offset + 2].toInt() and 0xff) shl  8) or
                      (hmac[offset + 3].toInt() and 0xff)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    // ── Access token (TOTP-first, then old fallback) ──────────────────────────

    private fun ensureAccessToken(): String? {
        val now = System.currentTimeMillis()
        cachedAccessToken?.takeIf { now < accessTokenExpiryMs - 60_000L }?.let { return it }

        val spDc = storedSpDc ?: return null

        ensureSecrets()
        val serverTimeMs = fetchServerTime(spDc)
        val otp = generateTotp(serverTimeMs, secretBytes)

        // Try the TOTP-based endpoint first (both "transport" and "init" reasons)
        for (reason in listOf("transport", "init")) {
            val token = requestTotpToken(spDc, otp, reason) ?: continue
            cachedAccessToken   = token
            accessTokenExpiryMs = now + 50L * 60L * 1000L   // 50 min
            Log.d(TAG, "TOTP token obtained via reason=$reason (v$secretVersion)")
            return token
        }

        // Fallback: old get_access_token endpoint
        val fallback = requestLegacyToken(spDc)
        if (fallback != null) {
            cachedAccessToken   = fallback
            accessTokenExpiryMs = now + 50L * 60L * 1000L
            Log.d(TAG, "Fell back to legacy access token")
        }
        return fallback
    }

    private fun requestTotpToken(spDc: String, otp: String, reason: String): String? {
        val url = "$TOKEN_URL?reason=$reason&productType=mobile-web-player" +
                  "&totp=$otp&totpVer=$secretVersion&totpServer=$otp"
        return runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_WEB)
                .header("Cookie", "sp_dc=$spDc")
                .header("App-platform", "WebPlayer")
                .header("Spotify-App-Version", "1.2.61.20.g3b4cd5b2")
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.d(TAG, "TOTP token HTTP ${r.code} ($reason)"); null }
                else r.body?.string()
            } ?: return@runCatching null
            // Token must be long enough to be real (anon tokens are short)
            JSONObject(body).optString("accessToken").takeIf { it.length >= 100 }
        }.getOrNull()
    }

    private fun requestLegacyToken(spDc: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url("$OLD_TOKEN_URL?reason=transport&productType=web_player")
                .header("User-Agent", UA_WEB)
                .header("Accept", "application/json")
                .header("Cookie", "sp_dc=$spDc")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            } ?: return@runCatching null
            val obj   = JSONObject(body)
            val token = obj.optString("accessToken").takeIf { it.length >= 100 }
            val isAnon = obj.optBoolean("isAnonymous", true)
            if (isAnon) Log.d(TAG, "Legacy token is anonymous — TOTP may be outdated")
            token
        }.getOrNull()
    }

    // ── Client token ──────────────────────────────────────────────────────────

    private fun ensureClientToken(): String? {
        val now = System.currentTimeMillis()
        cachedClientToken?.takeIf { now < clientTokenExpiryMs - 60_000L }?.let { return it }
        return runCatching {
            val payload = """
                {"client_data":{"client_version":"1.2.52.442",
                "client_id":"d8a5ed958d274c2e8ee717e6a4b0971d",
                "js_sdk_data":{"device_brand":"unknown","device_model":"unknown",
                "os":"android","os_version":"unknown"}}}
            """.trimIndent().replace("\n", "")
            val req = Request.Builder().url(CLIENT_TOKEN_URL)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .header("User-Agent", UA_WEB)
                .header("Origin", "https://open.spotify.com")
                .build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.d(TAG, "ClientToken HTTP ${r.code}"); null }
                else r.body?.string()
            } ?: return@runCatching null
            val obj       = json.parseToJsonElement(body).jsonObject
            val granted   = obj["granted_token"]?.jsonObject ?: return@runCatching null
            val ct        = granted["token"]?.jsonPrimitive?.content ?: return@runCatching null
            val expiresIn = granted["expires_after_seconds"]?.jsonPrimitive?.content
                ?.toLongOrNull() ?: 1800L
            cachedClientToken  = ct
            clientTokenExpiryMs = now + expiresIn * 1000L
            ct
        }.getOrElse { Log.d(TAG, "ClientToken error: ${it.message}"); null }
    }

    // ── Track search (Spotify REST API) ───────────────────────────────────────

    private fun searchTrackId(token: String, clientToken: String, title: String, artist: String): String? {
        // Try multiple query strategies to maximise match rate
        val cleanTitle  = sanitize(title)
        val cleanArtist = sanitize(artist)
        val queries = listOf(
            "$cleanTitle $cleanArtist",
            "$cleanArtist $cleanTitle",
            cleanTitle,
            "$title $artist",
        ).distinct()

        for (query in queries) {
            val id = searchOnce(token, clientToken, query) ?: continue
            Log.d(TAG, "Track found for \"$query\" → $id")
            return id
        }
        return null
    }

    private fun searchOnce(token: String, clientToken: String, query: String): String? {
        val q = URLEncoder.encode(query.take(100), "UTF-8")
        return runCatching {
            val req = Request.Builder()
                .url("$SEARCH_URL?q=$q&type=track&limit=3")
                .header("Authorization", "Bearer $token")
                .header("Client-Token", clientToken)
                .header("User-Agent", UA_WEB)
                .header("Accept", "application/json")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            } ?: return@runCatching null
            json.parseToJsonElement(body).jsonObject
                .get("tracks")?.jsonObject
                ?.get("items")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("id")?.jsonPrimitive?.content
        }.getOrNull()
    }

    // ── Canvas fetch ──────────────────────────────────────────────────────────

    private fun fetchCanvas(token: String, clientToken: String, trackId: String): String? {
        val body = buildCanvasProto(trackId)
        val req = Request.Builder().url(CANVAS_URL)
            .post(body.toRequestBody("application/x-protobuf".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Client-Token", clientToken)
            .header("Accept", "application/x-protobuf")
            .header("User-Agent", UA_MOBILE)
            .build()
        return runCatching {
            val bytes = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.d(TAG, "Canvas API HTTP ${r.code}"); null }
                else r.body?.bytes()
            } ?: return@runCatching null
            parseCanvasProto(bytes).also { url ->
                if (url != null) Log.i(TAG, "Canvas found: ${url.take(60)}…")
                else Log.d(TAG, "No canvas for track $trackId")
            }
        }.getOrNull()
    }

    // ── Protobuf helpers ──────────────────────────────────────────────────────
    //
    // Canvas request:  field 1 (LEN) → inner message
    //   inner field 1 (LEN) → "spotify:track:<id>"
    //
    // Canvas response: field 1 (LEN) → canvas entry
    //   entry field 2 (LEN) → canvas URL string

    private fun buildCanvasProto(trackId: String): ByteArray {
        val uri      = "spotify:track:$trackId".toByteArray(Charsets.UTF_8)
        val inner    = byteArrayOf(0x0A) + varint(uri.size)    + uri
        return          byteArrayOf(0x0A) + varint(inner.size) + inner
    }

    private fun parseCanvasProto(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, n) = readVarint(bytes, i); i += n
            val field    = (tag ushr 3).toInt()
            val wire     = (tag and 7L).toInt()
            if (wire == 2 && field == 1) {
                val (len, n2) = readVarint(bytes, i); i += n2
                val entry = bytes.sliceArray(i until (i + len.toInt())); i += len.toInt()
                parseCanvasEntry(entry)?.let { return it }
            } else {
                i = skipField(bytes, i, wire) ?: return null
            }
        }
        return null
    }

    private fun parseCanvasEntry(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, n) = readVarint(bytes, i); i += n
            val field    = (tag ushr 3).toInt()
            val wire     = (tag and 7L).toInt()
            when (wire) {
                2 -> {
                    val (len, n2) = readVarint(bytes, i); i += n2
                    val data = bytes.sliceArray(i until (i + len.toInt())); i += len.toInt()
                    if (field == 2) {
                        val url = String(data, Charsets.UTF_8)
                        if (url.startsWith("https://")) return url
                    }
                }
                0 -> { val (_, n2) = readVarint(bytes, i); i += n2 }
                else -> return null
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

    private fun skipField(bytes: ByteArray, offset: Int, wire: Int): Int? = when (wire) {
        0    -> { val (_, n) = readVarint(bytes, offset); offset + n }
        1    -> offset + 8
        2    -> { val (len, n) = readVarint(bytes, offset); offset + n + len.toInt() }
        5    -> offset + 4
        else -> null
    }

    // ── String cleaning ───────────────────────────────────────────────────────

    private fun sanitize(s: String): String = s
        .replace(Regex("\\((feat\\.|ft\\.|official|lyrics?|video|audio|visualizer)[^)]*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[[^]]*\\]"), "")
        .replace(Regex("[-–]\\s*(Topic|VEVO|Official)$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
