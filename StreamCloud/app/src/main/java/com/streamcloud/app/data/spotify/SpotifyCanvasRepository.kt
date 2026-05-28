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

/**
 * Fetches Spotify Canvas looping video URLs via the undocumented spclient protobuf API.
 *
 * TOTP derivation reference:
 *   https://github.com/mkirsten/beosound5c/blob/main/services/lib/spotify_canvas.py
 *   https://github.com/ABCGop/VxMusic (SpotifyTotp.kt)
 */
object SpotifyCanvasRepository {

    private const val TAG = "SpotifyCanvas"

    // ── Fallback cipher bytes (v61, May 2025 — fetched live, used if GitHub unreachable) ──────────
    private val FALLBACK_CIPHER  = intArrayOf(44,55,47,42,70,40,34,114,76,74,50,111,120,97,75,76,94,102,43,69,49,120,118,80,64,78)
    private const val FALLBACK_VER = "61"

    private const val SECRETS_URL      = "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json"
    private const val SERVER_TIME_URL  = "https://open.spotify.com/api/server-time"
    private const val TOKEN_URL        = "https://open.spotify.com/api/token"
    private const val SEARCH_URL       = "https://api.spotify.com/v1/search"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"

    // Geo-specific spclient hosts — try in order, first success wins.
    private val CANVAS_HOSTS = listOf(
        "gew1-spclient.spotify.com",
        "gae2-spclient.spotify.com",
        "guc3-spclient.spotify.com",
        "spclient.wg.spotify.com",
    )

    private const val UA_WEB    = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val UA_MOBILE = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
    private const val SPOTIFY_APP_VER = "1.2.61.20.g3b4cd5b2"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Mutable state ─────────────────────────────────────────────────────────

    @Volatile private var storedSpDc: String? = null

    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var accessTokenExpiryMs: Long  = 0L

    @Volatile private var cachedClientToken: String? = null
    @Volatile private var clientTokenExpiryMs: Long  = 0L

    @Volatile private var cipherBytes:   IntArray = FALLBACK_CIPHER
    @Volatile private var cipherVersion: String   = FALLBACK_VER
    @Volatile private var secretFetchMs: Long     = 0L

    private val urlCache  = LinkedHashMap<String, String?>(64, 0.75f, true)
    private val cacheLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    fun setSpotifyCookie(cookie: String?) {
        val spDc = when {
            cookie.isNullOrBlank() -> null
            cookie.contains("sp_dc=") ->
                cookie.split(";")
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
        Log.i(TAG, if (spDc != null) "sp_dc set (${spDc.take(8)}…)" else "sp_dc cleared")
    }

    fun invalidateTokens() {
        cachedAccessToken  = null
        accessTokenExpiryMs = 0L
        cachedClientToken  = null
        clientTokenExpiryMs = 0L
    }

    suspend fun getCanvasUrl(videoId: String, title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                if (urlCache.containsKey(videoId)) {
                    val cached = urlCache[videoId]
                    Log.d(TAG, "[$title] cache hit → ${cached?.take(40) ?: "null"}")
                    return@withContext cached
                }
            }
            val url = runCatching { fetchCanvas(title, artist) }
                .getOrElse { e ->
                    Log.e(TAG, "[$title] uncaught error: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            synchronized(cacheLock) { urlCache[videoId] = url }
            Log.i(TAG, "[$title] result → ${url?.take(60) ?: "null (no canvas)"}")
            url
        }

    // ── Internal fetch pipeline ───────────────────────────────────────────────

    private fun fetchCanvas(title: String, artist: String): String? {
        val spDc = storedSpDc
        if (spDc.isNullOrBlank()) {
            Log.w(TAG, "[$title] skipped — storedSpDc is null (not logged into Spotify)")
            return null
        }
        Log.d(TAG, "[$title] fetching canvas (sp_dc present: ${spDc.take(8)}…)")

        val token = ensureAccessToken(spDc)
        if (token == null) {
            Log.e(TAG, "[$title] FAILED — could not obtain access token")
            return null
        }

        val ct = ensureClientToken()
        if (ct == null) {
            Log.e(TAG, "[$title] FAILED — could not obtain client token")
            return null
        }

        val trackId = searchTrackId(token, ct, title, artist)
        if (trackId == null) {
            Log.w(TAG, "[$title] FAILED — track not found on Spotify for \"$title\" by \"$artist\"")
            return null
        }

        return requestCanvas(token, ct, trackId)
    }

    // ── TOTP secret management ────────────────────────────────────────────────

    private fun ensureSecrets() {
        val now = System.currentTimeMillis()
        if (now - secretFetchMs < 6 * 3_600_000L) return
        runCatching {
            val req  = Request.Builder().url(SECRETS_URL).header("User-Agent", UA_WEB).get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "Secrets fetch HTTP ${r.code}")
                    return@runCatching
                }
                r.body?.string()
            } ?: return@runCatching
            val obj = JSONObject(body)
            val latestV = obj.keys().asSequence()
                .mapNotNull { it.toIntOrNull() }.maxOrNull()?.toString() ?: return@runCatching
            val arr   = obj.optJSONArray(latestV) ?: return@runCatching
            cipherBytes   = IntArray(arr.length()) { arr.getInt(it) }
            cipherVersion = latestV
            secretFetchMs = now
            Log.i(TAG, "TOTP secrets updated → v$latestV (${cipherBytes.size} bytes)")
        }.onFailure { Log.w(TAG, "Secrets fetch failed: ${it.message}") }
    }

    // ── TOTP key derivation (two variants; we try both) ───────────────────────

    /**
     * Variant A — Python reference (mkirsten/beosound5c):
     *   key = ASCII bytes of joined decimal string of XOR-transformed cipher bytes
     *   e.g. transformed=[37,61,36,...] → "376136..." → [51,55,54,49,...]
     */
    private fun hmacKeyPython(cipher: IntArray): ByteArray {
        val transformed = IntArray(cipher.size) { i -> cipher[i] xor ((i % 33) + 9) }
        val joined = transformed.joinToString("") { it.toString() }
        return joined.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Variant B — VxMusic/SimpMusic (SpotifyTotp.kt + GoogleAuthenticator):
     *   Same XOR transform, then base32-encode the joined ASCII bytes,
     *   then use the ASCII bytes of that base32 string as the HMAC key.
     */
    private fun hmacKeyVxMusic(cipher: IntArray): ByteArray {
        val transformed = IntArray(cipher.size) { i -> cipher[i] xor ((i % 33) + 9) }
        val joined = transformed.joinToString("") { it.toString() }
        val joinedBytes = joined.toByteArray(Charsets.US_ASCII)
        // base32-encode the joined bytes, then convert the resulting string to bytes
        val b32 = encodeBase32(joinedBytes).trimEnd('=')
        return b32.toByteArray(Charsets.US_ASCII)
    }

    private fun generateTotp(serverTimeMs: Long, keyBytes: ByteArray): String {
        val counter = serverTimeMs / 1000L / 30L
        val msg     = ByteBuffer.allocate(8).putLong(counter).array()
        val mac     = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val h      = mac.doFinal(msg)
        val offset = h.last().toInt() and 0x0f
        val code   = ((h[offset].toInt()     and 0x7f) shl 24) or
                     ((h[offset + 1].toInt() and 0xff) shl 16) or
                     ((h[offset + 2].toInt() and 0xff) shl  8) or
                      (h[offset + 3].toInt() and 0xff)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    private val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private fun encodeBase32(data: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0; var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(BASE32[(value shr (bits - 5)) and 0x1F]); bits -= 5 }
        }
        if (bits > 0) sb.append(BASE32[(value shl (5 - bits)) and 0x1F])
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }

    // ── Server time ───────────────────────────────────────────────────────────

    private fun fetchServerTime(spDc: String): Long {
        return runCatching {
            val req = Request.Builder().url(SERVER_TIME_URL)
                .header("User-Agent", UA_WEB)
                .header("Cookie", "sp_dc=$spDc")
                .header("App-Platform", "WebPlayer")
                .header("Spotify-App-Version", SPOTIFY_APP_VER)
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "ServerTime HTTP ${r.code}"); return@runCatching null }
                r.body?.string()
            } ?: return@runCatching null
            val s = JSONObject(body).optLong("serverTime")
            Log.d(TAG, "Server time: ${s}s (device: ${System.currentTimeMillis() / 1000}s)")
            if (s > 0L) s * 1000L else null
        }.getOrNull() ?: System.currentTimeMillis().also {
            Log.w(TAG, "Using device time as fallback")
        }
    }

    // ── Access token (tries both TOTP variants × both reasons) ───────────────

    private fun ensureAccessToken(spDc: String): String? {
        val now = System.currentTimeMillis()
        cachedAccessToken?.takeIf { now < accessTokenExpiryMs - 60_000L }?.let {
            Log.d(TAG, "Using cached access token (expires in ${(accessTokenExpiryMs - now) / 60000}m)")
            return it
        }

        ensureSecrets()
        val serverTimeMs = fetchServerTime(spDc)

        val keyPython  = hmacKeyPython(cipherBytes)
        val keyVxMusic = hmacKeyVxMusic(cipherBytes)
        val otpPython  = generateTotp(serverTimeMs, keyPython)
        val otpVxMusic = generateTotp(serverTimeMs, keyVxMusic)
        Log.d(TAG, "TOTP v$cipherVersion: python=$otpPython vxmusic=$otpVxMusic (t=${serverTimeMs / 1000}s)")

        // Try all combinations: (Python OTP, VxMusic OTP) × (transport, init) × (web-player, mobile-web-player)
        val attempts = listOf(
            Triple(otpPython,  "transport",  "web-player"),
            Triple(otpPython,  "transport",  "mobile-web-player"),
            Triple(otpVxMusic, "transport",  "web-player"),
            Triple(otpVxMusic, "transport",  "mobile-web-player"),
            Triple(otpPython,  "init",       "web-player"),
            Triple(otpVxMusic, "init",       "web-player"),
        )

        for ((otp, reason, productType) in attempts) {
            val token = tryFetchToken(spDc, otp, reason, productType, cipherVersion, now)
            if (token != null) {
                Log.i(TAG, "Token OK (otp=$otp reason=$reason productType=$productType)")
                return token
            }
        }

        Log.e(TAG, "All token attempts failed for v$cipherVersion")
        return null
    }

    private fun tryFetchToken(
        spDc: String, otp: String, reason: String, productType: String, ver: String, now: Long,
    ): String? {
        val url = "$TOKEN_URL?reason=$reason&productType=$productType" +
                  "&totp=$otp&totpVer=$ver&totpServer=$otp"
        return runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_WEB)
                .header("Cookie", "sp_dc=$spDc")
                .header("App-Platform", "WebPlayer")
                .header("Spotify-App-Version", SPOTIFY_APP_VER)
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.d(TAG, "Token HTTP ${r.code} ($reason/$productType/otp=$otp)")
                    return@runCatching null
                }
                r.body?.string()
            } ?: return@runCatching null

            val obj = JSONObject(body)
            if (obj.optBoolean("isAnonymous", false)) {
                Log.d(TAG, "Token isAnonymous=true ($reason/$productType) — sp_dc expired or wrong OTP")
                return@runCatching null
            }
            val t   = obj.optString("accessToken", "").takeIf { it.length >= 100 }
            val exp = obj.optLong("accessTokenExpirationTimestampMs", 0L)
            if (t != null) {
                cachedAccessToken   = t
                accessTokenExpiryMs = if (exp > 0L) exp else now + 50L * 60L * 1000L
            } else {
                Log.d(TAG, "Token response missing accessToken: ${body.take(200)}")
            }
            t
        }.getOrElse {
            Log.d(TAG, "Token request exception ($reason/$productType): ${it.message}")
            null
        }
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
                if (!r.isSuccessful) { Log.e(TAG, "ClientToken HTTP ${r.code}"); null }
                else r.body?.string()
            } ?: return@runCatching null
            val granted = json.parseToJsonElement(body).jsonObject["granted_token"]?.jsonObject
            val ct      = granted?.get("token")?.jsonPrimitive?.content
            val expSec  = granted?.get("expires_after_seconds")?.jsonPrimitive?.content?.toLongOrNull() ?: 1800L
            if (ct != null) {
                cachedClientToken  = ct
                clientTokenExpiryMs = now + expSec * 1000L
                Log.d(TAG, "Client token obtained (expires in ${expSec}s)")
            } else {
                Log.e(TAG, "ClientToken: no token in response: ${body.take(200)}")
            }
            ct
        }.getOrElse { Log.e(TAG, "ClientToken error: ${it.message}"); null }
    }

    // ── Track search ──────────────────────────────────────────────────────────

    private fun searchTrackId(token: String, ct: String, title: String, artist: String): String? {
        val cleanTitle  = sanitize(title)
        val cleanArtist = sanitize(artist)
        val queries = listOf(
            "$cleanTitle $cleanArtist",
            "$cleanArtist $cleanTitle",
            cleanTitle,
            "$title $artist",
        ).distinct().filter { it.isNotBlank() }

        for (q in queries) {
            val id = searchOnce(token, ct, q)
            if (id != null) {
                Log.d(TAG, "Track found → $id (query: \"${q.take(60)}\")")
                return id
            }
        }
        Log.w(TAG, "No Spotify track found for \"$title\" by \"$artist\"")
        return null
    }

    private fun searchOnce(token: String, ct: String, query: String): String? {
        val q = URLEncoder.encode(query.take(100), "UTF-8")
        return runCatching {
            val req = Request.Builder()
                .url("$SEARCH_URL?q=$q&type=track&limit=5")
                .header("Authorization", "Bearer $token")
                .header("Client-Token", ct)
                .header("User-Agent", UA_WEB)
                .header("Accept", "application/json")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.d(TAG, "Search HTTP ${r.code} for \"$query\""); null }
                else r.body?.string()
            } ?: return@runCatching null
            json.parseToJsonElement(body).jsonObject
                .get("tracks")?.jsonObject
                ?.get("items")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("id")?.jsonPrimitive?.content
        }.getOrElse { Log.d(TAG, "Search exception: ${it.message}"); null }
    }

    private fun sanitize(s: String): String = s
        .replace(Regex("\\((feat\\.|ft\\.|official|lyrics?|video|audio|visualizer|live)[^)]*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[[^]]*\\]"), "")
        .replace(Regex("[-–]\\s*(Topic|VEVO|Official)\\s*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ").trim()

    // ── Canvas fetch ──────────────────────────────────────────────────────────

    private fun requestCanvas(token: String, ct: String, trackId: String): String? {
        val protoBody = buildCanvasProto(trackId)
        Log.d(TAG, "Fetching canvas for track $trackId (proto: ${protoBody.size} bytes)")

        for (host in CANVAS_HOSTS) {
            val result = tryCanvasHost(token, ct, host, protoBody)
            if (result == null) continue
            if (result.isEmpty()) {
                Log.d(TAG, "  $host → no canvas for this track")
                return null   // definitive "no canvas" answer
            }
            Log.i(TAG, "  $host → CANVAS FOUND: ${result.take(80)}")
            return result
        }
        Log.w(TAG, "Canvas: all hosts exhausted without a result")
        return null
    }

    private fun tryCanvasHost(token: String, ct: String, host: String, body: ByteArray): String? {
        val url = "https://$host/canvaz-cache/v0/canvases"
        return runCatching {
            val req = Request.Builder().url(url)
                .post(body.toRequestBody("application/x-protobuf".toMediaType()))
                .header("Authorization", "Bearer $token")
                .header("Client-Token", ct)
                .header("Accept", "application/x-protobuf")
                .header("User-Agent", UA_MOBILE)
                .header("Accept-Language", "en")
                .build()
            val bytes = http.newCall(req).execute().use { r ->
                when {
                    r.code == 401 -> { Log.w(TAG, "  $host → 401 (token expired?)"); return@runCatching null }
                    r.code == 404 -> { Log.d(TAG, "  $host → 404"); return@runCatching "" }
                    !r.isSuccessful -> { Log.d(TAG, "  $host → HTTP ${r.code}"); return@runCatching null }
                    else -> r.body?.bytes()
                }
            } ?: run { Log.d(TAG, "  $host → empty response"); return@runCatching "" }
            Log.d(TAG, "  $host → ${bytes.size} bytes response")
            parseCanvasProto(bytes) ?: ""  // "" = no canvas URL in response (track has no canvas)
        }.getOrElse {
            Log.d(TAG, "  $host → ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    // ── Protobuf builder & parser ─────────────────────────────────────────────
    //
    // CanvasRequest:
    //   message CanvasRequest { message Track { string track_uri = 1; }; repeated Track tracks = 1; }
    //
    // CanvasResponse:
    //   message CanvasResponse { message Canvas { string id=1; string canvas_url=2; ... }; repeated Canvas canvases=1; }

    private fun buildCanvasProto(trackId: String): ByteArray {
        val uri   = "spotify:track:$trackId".toByteArray(Charsets.UTF_8)
        val track = protoLen(1) + varint(uri.size) + uri
        return protoLen(1) + varint(track.size) + track
    }

    private fun parseCanvasProto(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, n) = readVarint(bytes, i); i += n
            val field = (tag ushr 3).toInt()
            val wire  = (tag and 7L).toInt()
            when {
                wire == 2 && field == 1 -> {
                    val (len, n2) = readVarint(bytes, i); i += n2
                    val entry = bytes.copyOfRange(i, (i + len.toInt()).coerceAtMost(bytes.size))
                    i += len.toInt()
                    parseCanvasEntry(entry)?.let { return it }
                }
                else -> { i = skipField(bytes, i, wire) ?: break }
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
                    val end  = (i + len.toInt()).coerceAtMost(bytes.size)
                    val data = bytes.copyOfRange(i, end); i += len.toInt()
                    if (field == 2) {
                        val url = String(data, Charsets.UTF_8)
                        if (url.startsWith("https://")) return url
                    }
                }
                0    -> { val (_, n2) = readVarint(bytes, i); i += n2 }
                1    -> i += 8
                5    -> i += 4
                else -> return null
            }
        }
        return null
    }

    // wire type 2 (LEN) tag for field N
    private fun protoLen(field: Int): ByteArray = varint((field shl 3) or 2)

    private fun varint(v: Int): ByteArray {
        val out = mutableListOf<Byte>()
        var x = v
        while (x and 0x7F.inv() != 0) { out.add(((x and 0x7F) or 0x80).toByte()); x = x ushr 7 }
        out.add(x.toByte())
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
}
