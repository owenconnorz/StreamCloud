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

    // ── Hardcoded fallback TOTP secrets (v61, May 2025). Live secrets override these. ─────
    // Raw cipher bytes from xyloflake/spot-secrets-go. They are XOR-encoded; see deriveHmacKey().
    private val FALLBACK_CIPHER = intArrayOf(
        44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75,
        76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78,
    )
    private const val FALLBACK_VERSION = "61"

    private const val SECRETS_URL =
        "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json"
    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val TOKEN_URL       = "https://open.spotify.com/api/token"
    private const val SEARCH_URL      = "https://api.spotify.com/v1/search"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"

    // Geo-distributed spclient hosts (try in order; first success wins)
    private val CANVAS_HOSTS = listOf(
        "gew1-spclient.spotify.com",
        "gae2-spclient.spotify.com",
        "guc3-spclient.spotify.com",
        "spclient.wg.spotify.com",
    )

    private const val UA_WEB    = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val UA_MOBILE = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var storedSpDc: String? = null

    @Volatile private var cachedAccessToken: String?  = null
    @Volatile private var accessTokenExpiryMs: Long   = 0L

    @Volatile private var cachedClientToken: String?  = null
    @Volatile private var clientTokenExpiryMs: Long   = 0L

    @Volatile private var cipherBytes:   IntArray = FALLBACK_CIPHER
    @Volatile private var cipherVersion: String   = FALLBACK_VERSION
    @Volatile private var secretFetchMs: Long     = 0L   // refresh every 6 hours

    private val urlCache  = LinkedHashMap<String, String?>(64, 0.75f, true)
    private val cacheLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called from NowPlayingShell whenever the DataStore cookie changes.
     * Accepts the full WebView cookie string ("sp_dc=AQ…; sp_key=…") or
     * a bare sp_dc value, and extracts/stores only the sp_dc portion.
     */
    fun setSpotifyCookie(cookie: String?) {
        val spDc = when {
            cookie.isNullOrBlank() -> null
            cookie.contains("sp_dc=") -> cookie.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("sp_dc=") }
                ?.substringAfter("sp_dc=")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            else -> cookie.trim().takeIf { it.isNotBlank() }
        }
        if (spDc == storedSpDc) return
        storedSpDc = spDc
        invalidateTokens()
        Log.d(TAG, if (spDc != null) "sp_dc set (${spDc.take(8)}…)" else "sp_dc cleared")
    }

    fun invalidateTokens() {
        cachedAccessToken  = null
        accessTokenExpiryMs = 0L
        cachedClientToken  = null
        clientTokenExpiryMs = 0L
    }

    /**
     * Returns a looping Canvas video URL for the track, or null.
     * [videoId] is used only as a cache key (it's the YouTube video ID).
     */
    suspend fun getCanvasUrl(videoId: String, title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                if (urlCache.containsKey(videoId)) return@withContext urlCache[videoId]
            }
            val url = runCatching { fetchCanvas(title, artist) }
                .getOrElse { e -> Log.d(TAG, "Canvas error [$title]: ${e.message}"); null }
            synchronized(cacheLock) { urlCache[videoId] = url }
            url
        }

    private fun fetchCanvas(title: String, artist: String): String? {
        if (storedSpDc.isNullOrBlank()) {
            Log.d(TAG, "Canvas skipped — no sp_dc (Spotify not logged in)")
            return null
        }
        val token  = ensureAccessToken()  ?: run { Log.d(TAG, "No access token"); return null }
        val ct     = ensureClientToken()  ?: run { Log.d(TAG, "No client token"); return null }
        val trackId = searchTrackId(token, ct, title, artist)
            ?: run { Log.d(TAG, "Track not found: \"$title\" – $artist"); return null }
        return requestCanvas(token, ct, trackId)
    }

    // ── TOTP secret management ────────────────────────────────────────────────

    private fun ensureSecrets() {
        val now = System.currentTimeMillis()
        if (now - secretFetchMs < 6 * 3_600_000L) return
        runCatching {
            val req  = Request.Builder().url(SECRETS_URL).header("User-Agent", UA_WEB).get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching
                r.body?.string()
            } ?: return@runCatching

            val obj     = JSONObject(body)
            val latestV = obj.keys().asSequence().mapNotNull { it.toIntOrNull() }.maxOrNull()
                ?.toString() ?: return@runCatching
            val arr     = obj.optJSONArray(latestV) ?: return@runCatching
            val bytes   = IntArray(arr.length()) { arr.getInt(it) }
            cipherBytes   = bytes
            cipherVersion = latestV
            secretFetchMs = now
            Log.d(TAG, "TOTP secrets updated → v$latestV (${bytes.size} cipher bytes)")
        }.onFailure { Log.d(TAG, "Secrets fetch failed: ${it.message}") }
    }

    /**
     * Derive the HMAC-SHA1 key from raw Spotify cipher bytes.
     *
     * Spotify's cipher dict stores encoded secret bytes. The real secret is obtained by:
     *   1. XOR each byte with ((index % 33) + 9)
     *   2. Join the resulting decimal values as a single string  e.g. [37, 61, …] → "3761…"
     *   3. Use the ASCII encoding of that string as the HMAC key
     *
     * Reference: https://github.com/mkirsten/beosound5c/blob/main/services/lib/spotify_canvas.py
     *            _generate_totp_secret / _totp_code
     */
    private fun deriveHmacKey(cipher: IntArray): ByteArray {
        val transformed = IntArray(cipher.size) { i -> cipher[i] xor ((i % 33) + 9) }
        val joined = transformed.joinToString("") { it.toString() }
        return joined.toByteArray(Charsets.US_ASCII)
    }

    // ── Server time ───────────────────────────────────────────────────────────

    private fun fetchServerTime(spDc: String): Long {
        return runCatching {
            val req = Request.Builder().url(SERVER_TIME_URL)
                .header("User-Agent", UA_WEB)
                .header("Cookie", "sp_dc=$spDc")
                .header("App-Platform", "WebPlayer")
                .header("Accept", "application/json")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching System.currentTimeMillis()
                r.body?.string()
            } ?: return@runCatching System.currentTimeMillis()
            val seconds = JSONObject(body).optLong("serverTime")
            if (seconds > 0L) seconds * 1000L else System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())
    }

    // ── TOTP (RFC 6238) ───────────────────────────────────────────────────────

    private fun generateTotp(serverTimeMs: Long, cipher: IntArray): String {
        val counter  = serverTimeMs / 1000L / 30L
        val msg      = ByteBuffer.allocate(8).putLong(counter).array()
        val keyBytes = deriveHmacKey(cipher)
        val mac      = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hmac   = mac.doFinal(msg)
        val offset = hmac.last().toInt() and 0x0f
        val code   = ((hmac[offset].toInt()     and 0x7f) shl 24) or
                     ((hmac[offset + 1].toInt() and 0xff) shl 16) or
                     ((hmac[offset + 2].toInt() and 0xff) shl  8) or
                      (hmac[offset + 3].toInt() and 0xff)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    // ── Access token (TOTP-based) ─────────────────────────────────────────────

    private fun ensureAccessToken(): String? {
        val now = System.currentTimeMillis()
        cachedAccessToken?.takeIf { now < accessTokenExpiryMs - 60_000L }?.let { return it }

        val spDc = storedSpDc ?: return null

        ensureSecrets()
        val serverTimeMs = fetchServerTime(spDc)
        val otp = generateTotp(serverTimeMs, cipherBytes)

        // Try all TOTP versions × both reasons for maximum compatibility
        for (reason in listOf("transport", "init")) {
            val url = "$TOKEN_URL?reason=$reason&productType=web-player" +
                      "&totp=$otp&totpVer=$cipherVersion&totpServer=$otp"
            val token = runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA_WEB)
                    .header("Cookie", "sp_dc=$spDc")
                    .header("App-Platform", "WebPlayer")
                    .header("Accept", "application/json")
                    .get().build()
                val body = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { Log.d(TAG, "Token HTTP ${r.code} ($reason)"); null }
                    else r.body?.string()
                } ?: return@runCatching null
                val obj = JSONObject(body)
                val t   = obj.optString("accessToken").takeIf { it.length >= 100 }
                val exp = obj.optLong("accessTokenExpirationTimestampMs")
                if (t != null) {
                    cachedAccessToken   = t
                    accessTokenExpiryMs = if (exp > 0L) exp else now + 50L * 60L * 1000L
                    Log.d(TAG, "Token obtained (TOTP v$cipherVersion, reason=$reason)")
                } else if (obj.optBoolean("isAnonymous", false)) {
                    Log.d(TAG, "Got anonymous token — sp_dc may be expired")
                }
                t
            }.getOrNull()
            if (token != null) return token
        }
        return cachedAccessToken
    }

    // ── Client token ──────────────────────────────────────────────────────────

    private fun ensureClientToken(): String? {
        val now = System.currentTimeMillis()
        cachedClientToken?.takeIf { now < clientTokenExpiryMs - 60_000L }?.let { return it }
        return runCatching {
            val payload = """{"client_data":{"client_version":"1.2.52.442","client_id":"d8a5ed958d274c2e8ee717e6a4b0971d","js_sdk_data":{"device_brand":"unknown","device_model":"unknown","os":"android","os_version":"unknown"}}}"""
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
            val obj     = json.parseToJsonElement(body).jsonObject
            val granted = obj["granted_token"]?.jsonObject ?: return@runCatching null
            val ct      = granted["token"]?.jsonPrimitive?.content ?: return@runCatching null
            val expSec  = granted["expires_after_seconds"]?.jsonPrimitive?.content
                ?.toLongOrNull() ?: 1800L
            cachedClientToken  = ct
            clientTokenExpiryMs = now + expSec * 1000L
            Log.d(TAG, "Client token obtained (expires in ${expSec}s)")
            ct
        }.getOrElse { Log.d(TAG, "ClientToken error: ${it.message}"); null }
    }

    // ── Track search ──────────────────────────────────────────────────────────

    private fun searchTrackId(token: String, clientToken: String, title: String, artist: String): String? {
        val queries = buildSearchQueries(title, artist)
        for (query in queries) {
            val id = searchOnce(token, clientToken, query) ?: continue
            Log.d(TAG, "Track found → $id (query: \"${query.take(50)}\")")
            return id
        }
        return null
    }

    private fun buildSearchQueries(title: String, artist: String): List<String> {
        val ct = sanitize(title)
        val ca = sanitize(artist)
        return listOf(
            "$ct $ca",
            "$ca $ct",
            ct,
            "$title $artist",
        ).distinct().filter { it.isNotBlank() }
    }

    private fun searchOnce(token: String, clientToken: String, query: String): String? {
        val q = URLEncoder.encode(query.take(100), "UTF-8")
        return runCatching {
            val req = Request.Builder()
                .url("$SEARCH_URL?q=$q&type=track&limit=5")
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

    // ── Canvas fetch (protobuf, try multiple spclient hosts) ──────────────────

    private fun requestCanvas(token: String, clientToken: String, trackId: String): String? {
        val protoBytes = buildCanvasProto(trackId)
        for (host in CANVAS_HOSTS) {
            val url = "https://$host/canvaz-cache/v0/canvases"
            val result = runCatching {
                val req = Request.Builder().url(url)
                    .post(protoBytes.toRequestBody("application/x-protobuf".toMediaType()))
                    .header("Authorization", "Bearer $token")
                    .header("Client-Token", clientToken)
                    .header("Accept", "application/x-protobuf")
                    .header("User-Agent", UA_MOBILE)
                    .header("Accept-Language", "en")
                    .build()
                val bytes = http.newCall(req).execute().use { r ->
                    when {
                        r.code == 401 -> { Log.d(TAG, "Canvas 401 ($host) — token expired"); null }
                        !r.isSuccessful -> { Log.d(TAG, "Canvas HTTP ${r.code} ($host)"); null }
                        else -> r.body?.bytes()
                    }
                } ?: return@runCatching null
                parseCanvasProto(bytes)
            }.getOrElse { Log.d(TAG, "Canvas error ($host): ${it.message}"); null }

            if (result != null) {
                Log.i(TAG, "Canvas found via $host: ${result.take(60)}…")
                return result
            }
        }
        return null
    }

    // ── Protobuf helpers ──────────────────────────────────────────────────────
    //
    // CanvasRequest proto (confirmed from canvas.proto):
    //   message CanvasRequest {
    //     message Track { string track_uri = 1; }
    //     repeated Track tracks = 1;
    //   }
    //
    // CanvasResponse proto:
    //   message CanvasResponse {
    //     message Canvas {
    //       string id        = 1;
    //       string canvas_url = 2;
    //       ...
    //     }
    //     repeated Canvas canvases = 1;
    //   }

    private fun buildCanvasProto(trackId: String): ByteArray {
        val uri   = "spotify:track:$trackId".toByteArray(Charsets.UTF_8)
        val track = byteArrayOf(0x0A) + varint(uri.size)   + uri    // field 1 LEN (track_uri)
        return      byteArrayOf(0x0A) + varint(track.size) + track  // field 1 LEN (tracks[0])
    }

    private fun parseCanvasProto(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, n) = readVarint(bytes, i); i += n
            val field = (tag ushr 3).toInt()
            val wire  = (tag and 7L).toInt()
            if (wire == 2 && field == 1) {                    // canvases[0]
                val (len, n2) = readVarint(bytes, i); i += n2
                val entry = bytes.sliceArray(i until (i + len.toInt())); i += len.toInt()
                parseCanvasEntry(entry)?.let { return it }
            } else {
                i = skipField(bytes, i, wire) ?: break
            }
        }
        return null
    }

    private fun parseCanvasEntry(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, n) = readVarint(bytes, i); i += n
            val field = (tag ushr 3).toInt()
            val wire  = (tag and 7L).toInt()
            when (wire) {
                2 -> {
                    val (len, n2) = readVarint(bytes, i); i += n2
                    val data = bytes.sliceArray(i until (i + len.toInt())); i += len.toInt()
                    if (field == 2) {                          // canvas_url
                        val url = String(data, Charsets.UTF_8)
                        if (url.startsWith("https://")) return url
                    }
                }
                0 -> { val (_, n2) = readVarint(bytes, i); i += n2 }
                1 -> i += 8
                5 -> i += 4
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

    // ── Sanitize title/artist for search ─────────────────────────────────────

    private fun sanitize(s: String): String = s
        .replace(Regex("\\((feat\\.|ft\\.|official|lyrics?|video|audio|visualizer|live)[^)]*\\)",
            RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[[^]]*\\]"), "")
        .replace(Regex("[-–]\\s*(Topic|VEVO|Official)\\s*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
