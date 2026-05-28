package com.streamcloud.app.data.spotify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fetches Spotify Canvas looping video URLs.
 * Implementation mirrors SimpMusic / VxMusic (SpotifyAuth + SpotifyTotp + SpotifyClient).
 *
 * Key references:
 *   SpotifyTotp.kt  — https://github.com/ABCGop/VxMusic
 *   SpotifyAuth.kt  — https://github.com/ABCGop/VxMusic
 *   SpotifyClient.kt — https://github.com/ABCGop/VxMusic
 */
object SpotifyCanvasRepository {

    private const val TAG = "SpotifyCanvas"

    // ── Constants (matches SimpMusic SpotifyClient.USER_AGENT / header values) ──────────────────
    private const val UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.157 Safari/537.36"
    private const val UA_MOBILE   = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
    private const val APP_VERSION = "1.2.61.20.g3b4cd5b2"

    // Fallback cipher v61 (current latest as of May 2025)
    private val FALLBACK_CIPHER  = intArrayOf(44,55,47,42,70,40,34,114,76,74,50,111,120,97,75,76,94,102,43,69,49,120,118,80,64,78)
    private const val FALLBACK_VER = 61

    // SimpMusic SpotifyTotp.TOTP_SECRET_V22 — hard fallback if secrets fetch fails
    private val TOTP_SECRET_V22_CIPHER = listOf(99,101,119,123,69,120,91,123,97,74,53,48,76,102,55,69,110,54)
    private const val TOTP_V22 = 22

    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    // Search SHA — matches SimpMusic SpotifyClient.searchSpotifyTrack
    private const val SEARCH_SHA = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var storedSpDc: String? = null

    @Volatile private var cachedToken: String?   = null
    @Volatile private var tokenExpiryMs: Long    = 0L
    @Volatile private var cachedClientToken: String? = null
    @Volatile private var clientTokenExpiryMs: Long  = 0L

    @Volatile private var cipherVer: Int      = FALLBACK_VER
    @Volatile private var cipherList: IntArray = FALLBACK_CIPHER
    @Volatile private var secretFetchMs: Long  = 0L

    private val urlCache  = LinkedHashMap<String, String?>(64, 0.75f, true)
    private val cacheLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    fun setSpotifyCookie(cookie: String?) {
        val spDc = when {
            cookie.isNullOrBlank() -> null
            cookie.contains("sp_dc=") ->
                cookie.split(";").map { it.trim() }
                    .firstOrNull { it.startsWith("sp_dc=") }
                    ?.substringAfter("sp_dc=")?.trim()?.takeIf { it.isNotBlank() }
            else -> cookie.trim().takeIf { it.isNotBlank() }
        }
        if (spDc == storedSpDc) return
        storedSpDc = spDc
        cachedToken = null; tokenExpiryMs = 0L
        cachedClientToken = null; clientTokenExpiryMs = 0L
        Log.i(TAG, if (spDc != null) "sp_dc set (${spDc.take(8)}…)" else "sp_dc cleared")
    }

    fun invalidateTokens() {
        cachedToken = null; tokenExpiryMs = 0L
        cachedClientToken = null; clientTokenExpiryMs = 0L
    }

    suspend fun getCanvasUrl(videoId: String, title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                if (urlCache.containsKey(videoId)) return@withContext urlCache[videoId]
            }
            val url = runCatching { fetchCanvas(title, artist) }.getOrElse {
                Log.e(TAG, "[$title] ${it.javaClass.simpleName}: ${it.message}"); null
            }
            synchronized(cacheLock) { urlCache[videoId] = url }
            Log.i(TAG, "[$title] → ${url?.take(60) ?: "no canvas"}")
            url
        }

    // ── Internal pipeline ─────────────────────────────────────────────────────

    private fun fetchCanvas(title: String, artist: String): String? {
        val spDc = storedSpDc
        if (spDc.isNullOrBlank()) {
            Log.w(TAG, "[$title] no sp_dc — Spotify login required"); return null
        }

        val token = ensurePersonalToken(spDc) ?: run {
            Log.e(TAG, "[$title] personal token failed"); return null
        }
        val ct = ensureClientToken() ?: run {
            Log.e(TAG, "[$title] client token failed"); return null
        }

        val q = buildQuery(title, artist)
        val trackId = searchTrackId(token, ct, q) ?: run {
            Log.w(TAG, "[$title] track not found on Spotify (query: \"$q\")"); return null
        }
        Log.d(TAG, "[$title] track=$trackId")

        return fetchCanvasProto(token, ct, trackId)
    }

    // ── TOTP secret (SimpMusic SpotifyAuth.getTotpSecret) ─────────────────────

    private fun ensureSecrets() {
        val now = System.currentTimeMillis()
        if (now - secretFetchMs < 6 * 3_600_000L) return
        runCatching {
            val req = Request.Builder()
                .url("https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json")
                .header("User-Agent", UA_WEB).get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "Secrets HTTP ${r.code}"); return@runCatching }
                r.body?.string()
            } ?: return@runCatching
            val obj = JSONObject(body)
            // entries().last() — same as SimpMusic which takes the last entry
            val keys = obj.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
            val latestKey = keys.lastOrNull() ?: return@runCatching
            val arr = obj.optJSONArray(latestKey) ?: return@runCatching
            cipherVer  = latestKey.toInt()
            cipherList = IntArray(arr.length()) { arr.getInt(it) }
            secretFetchMs = now
            Log.i(TAG, "TOTP secrets: v$cipherVer (${cipherList.size} bytes)")
        }.onFailure { Log.w(TAG, "Secrets fetch failed: ${it.message}") }
    }

    // ── TOTP (exact mirror of SimpMusic SpotifyTotp.generateSecret) ───────────
    //
    // 1. Transform: byte XOR ((index % 33) + 9)
    // 2. Join decimals: [37,61,...] → "3761..."
    // 3. hexStr = joined.toByteArray().toHexString()  (ASCII bytes → hex string)
    // 4. hexStr.hexToByteArray()                       (same bytes back)
    // 5. Base64.encode(hexBytes)                        (base64 of ASCII bytes)
    // 6. base64ToBase32(base64)                         (decode base64, base32-encode)
    //    → net result: base32( joined.toByteArray() )
    // 7. trimEnd('=')
    // 8. HMAC key = secret.toByteArray()               (ASCII bytes of base32 string)
    // 9. GoogleAuthenticator.generate(Date(timestamp)) → counter = timestamp / 30000

    private fun generateSecret(cipher: IntArray): String {
        val transformed = cipher.mapIndexed { i, b -> b xor ((i % 33) + 9) }
        val joined = transformed.joinToString("")
        val joinedBytes = joined.toByteArray(Charsets.US_ASCII)
        // base32-encode the joined bytes (matching base64ToBase32(Base64.encode(bytes)))
        return base32Encode(joinedBytes).trimEnd('=')
    }

    private fun generateTotpFromSecret(secret: String, timestampMs: Long): String {
        // Matches: GoogleAuthenticator(secret.toByteArray()).generate(Date(timestampMs))
        // GoogleAuthenticator uses HMAC-SHA1, counter = Date.time / 30000
        val key     = secret.toByteArray(Charsets.US_ASCII)  // ASCII bytes of base32 string
        val counter = timestampMs / 30_000L
        val msg     = ByteBuffer.allocate(8).putLong(counter).array()
        val mac     = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val h      = mac.doFinal(msg)
        val offset = h.last().toInt() and 0x0f
        val code   = ((h[offset].toInt()     and 0x7f) shl 24) or
                     ((h[offset + 1].toInt() and 0xff) shl 16) or
                     ((h[offset + 2].toInt() and 0xff) shl  8) or
                      (h[offset + 3].toInt() and 0xff)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0; var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(BASE32_ALPHABET[(value shr (bits - 5)) and 0x1F]); bits -= 5 }
        }
        if (bits > 0) sb.append(BASE32_ALPHABET[(value shl (5 - bits)) and 0x1F])
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }

    // ── Server time (SimpMusic SpotifyClient.getSpotifyServerTime) ────────────

    private fun fetchServerTime(spDc: String): Long {
        return runCatching {
            val req = Request.Builder()
                .url("https://open.spotify.com/api/server-time")
                .header("User-Agent", UA_WEB)
                .header("Cookie", "sp_dc=$spDc")
                .header("App-platform", "WebPlayer")
                .header("Spotify-App-Version", APP_VERSION)
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "ServerTime HTTP ${r.code}"); return@runCatching null }
                r.body?.string()
            } ?: return@runCatching null
            val s = JSONObject(body).optLong("serverTime")
            Log.d(TAG, "Server time: ${s}s")
            if (s > 0L) s else null
        }.getOrNull() ?: (System.currentTimeMillis() / 1000L)
    }

    // ── Personal token (SimpMusic SpotifyAuth.refreshToken) ───────────────────

    private fun ensurePersonalToken(spDc: String): String? {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiryMs - 60_000L }?.let { return it }

        ensureSecrets()

        // serverTime is in SECONDS (as returned by the API)
        val serverTimeSec = fetchServerTime(spDc)
        val timestampMs   = serverTimeSec * 1000L   // passed to generateTotp as Date(ms)

        // Primary: use fetched cipher
        val secret     = generateSecret(cipherList)
        val otpValue   = generateTotpFromSecret(secret, timestampMs)
        val totpVersion = cipherVer

        Log.d(TAG, "TOTP v$totpVersion otp=$otpValue serverTime=${serverTimeSec}s")

        // Try transport first, then init (same as SimpMusic SpotifyAuth.refreshToken)
        var token = tryGetToken(spDc, otpValue, "transport", totpVersion)
        if (token == null) {
            Log.d(TAG, "transport failed, trying init")
            token = tryGetToken(spDc, otpValue, "init", totpVersion)
        }

        // Fallback to V22 secret if primary failed (mirrors SimpMusic TOTP_SECRET_V22 fallback)
        if (token == null) {
            val v22Secret = generateSecret(TOTP_SECRET_V22_CIPHER.toIntArray())
            val v22Otp    = generateTotpFromSecret(v22Secret, timestampMs)
            Log.d(TAG, "Trying v22 fallback, otp=$v22Otp")
            token = tryGetToken(spDc, v22Otp, "transport", TOTP_V22)
                ?: tryGetToken(spDc, v22Otp, "init", TOTP_V22)
        }

        return token
    }

    private fun tryGetToken(spDc: String, otp: String, reason: String, ver: Int): String? {
        // URL matches SimpMusic SpotifyClient.getSpotifyAccessToken exactly
        val url = "https://open.spotify.com/api/token" +
            "?reason=$reason&productType=mobile-web-player" +
            "&totp=$otp&totpServer=$otp&totpVer=$ver"
        return runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_WEB)
                .header("Cookie", "sp_dc=$spDc")
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.d(TAG, "Token HTTP ${r.code} ($reason)"); return@runCatching null }
                r.body?.string()
            } ?: return@runCatching null
            val obj = JSONObject(body)
            val at  = obj.optString("accessToken", "")
            // SimpMusic checks length == 374
            if (at.length != 374) {
                Log.d(TAG, "Token len=${at.length} ≠ 374 ($reason), isAnonymous=${obj.optBoolean("isAnonymous")}")
                return@runCatching null
            }
            val exp = obj.optLong("accessTokenExpirationTimestampMs", 0L)
            cachedToken  = at
            tokenExpiryMs = if (exp > 0L) exp else System.currentTimeMillis() + 50L * 60L * 1000L
            Log.i(TAG, "Personal token OK ($reason, len=${at.length})")
            at
        }.getOrElse { Log.d(TAG, "Token exception ($reason): ${it.message}"); null }
    }

    // ── Client token (SimpMusic SpotifyClient.getSpotifyClientToken) ──────────

    private fun ensureClientToken(): String? {
        val now = System.currentTimeMillis()
        cachedClientToken?.takeIf { now < clientTokenExpiryMs - 60_000L }?.let { return it }
        // Body matches SimpMusic SpotifyClientBody exactly
        val payload = """{"client_data":{"client_version":"1.2.62.476.g2ad6e7f3","client_id":"d8a5ed958d274c2e8ee717e6a4b0971d","js_sdk_data":{"device_brand":"Apple","device_model":"unknown","os":"macos","os_version":"10.15.7","device_id":"4fd0c748-b282-4927-9658-6d51a24e58b7","device_type":"computer"}}}"""
        return runCatching {
            val req = Request.Builder()
                .url("https://clienttoken.spotify.com/v1/clienttoken")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0")
                .build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.e(TAG, "ClientToken HTTP ${r.code}"); null }
                else r.body?.string()
            } ?: return@runCatching null
            val granted = JSONObject(body).optJSONObject("granted_token") ?: return@runCatching null
            val ct   = granted.optString("token", "").takeIf { it.isNotBlank() } ?: return@runCatching null
            val expS = granted.optLong("expires_after_seconds", 1800L)
            cachedClientToken  = ct
            clientTokenExpiryMs = now + expS * 1000L
            Log.d(TAG, "Client token OK (expires ${expS}s)")
            ct
        }.getOrElse { Log.e(TAG, "ClientToken error: ${it.message}"); null }
    }

    // ── Track search (SimpMusic SpotifyClient.searchSpotifyTrack — api-partner GraphQL) ─────────

    private fun buildQuery(title: String, artist: String): String {
        // SimpMusic's query construction from LyricsCanvasRepositoryImpl
        return ("$title $artist")
            .replace(Regex("\\((feat\\.|ft\\.) [^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[()]"), "")
            .replace(Regex("\\s+"), " ")
            .replace(".", " ")
            .trim()
    }

    private fun searchTrackId(token: String, ct: String, q: String): String? {
        // SimpMusic uses api-partner GraphQL, NOT api.spotify.com/v1/search
        val variables = URLEncoder.encode(
            """{"searchTerm":"$q","offset":0,"limit":3,"numberOfTopResults":3,"includeAudiobooks":true,"includePreReleases":false}""",
            "UTF-8"
        )
        val extensions = URLEncoder.encode(
            """{"persistedQuery":{"version":1,"sha256Hash":"$SEARCH_SHA"}}""",
            "UTF-8"
        )
        val url = "https://api-partner.spotify.com/pathfinder/v1/query" +
            "?operationName=searchTracks&variables=$variables&extensions=$extensions"

        return runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("Client-Token", ct)
                .header("User-Agent", UA_WEB)
                .header("Accept-Encoding", "gzip, deflate, br")
                .get().build()
            val body = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.d(TAG, "Search HTTP ${r.code} q=\"$q\""); null }
                else r.body?.string()
            } ?: return@runCatching null

            // Parse: data.searchV2.tracksV2.items[0].item.data.id
            val items = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONObject("searchV2")
                ?.optJSONObject("tracksV2")
                ?.optJSONArray("items")
                ?: return@runCatching null

            if (items.length() == 0) return@runCatching null
            items.getJSONObject(0)
                .optJSONObject("item")
                ?.optJSONObject("data")
                ?.optString("id", "")
                ?.takeIf { it.isNotBlank() }
        }.getOrElse { Log.d(TAG, "Search exception: ${it.message}"); null }
    }

    // ── Canvas fetch (SimpMusic SpotifyClient.getSpotifyCanvas) ──────────────

    private fun fetchCanvasProto(token: String, ct: String, trackId: String): String? {
        val protoBody = buildCanvasProto(trackId)
        Log.d(TAG, "Canvas request for $trackId (${protoBody.size} bytes)")

        // SimpMusic uses spclient.wg.spotify.com
        val req = Request.Builder()
            .url("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")
            .post(protoBody.toRequestBody("application/protobuf".toMediaType()))
            // Headers exactly as in SimpMusic SpotifyClient.getSpotifyCanvas
            .header("Accept", "application/protobuf")
            .header("Content-Type", "application/protobuf")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Authorization", "Bearer $token")
            .header("Client-Token", ct)
            .header("User-Agent", UA_MOBILE)
            .build()

        return runCatching {
            val bytes = http.newCall(req).execute().use { r ->
                Log.d(TAG, "Canvas HTTP ${r.code} (${r.body?.contentLength() ?: "?"}b)")
                when {
                    r.code == 401 -> { Log.w(TAG, "Canvas 401 — token expired"); return@runCatching null }
                    !r.isSuccessful -> { Log.w(TAG, "Canvas HTTP ${r.code}"); return@runCatching null }
                    else -> r.body?.bytes()
                }
            } ?: run { Log.d(TAG, "Canvas: empty body"); return@runCatching null }

            val url = parseCanvasProto(bytes)
            if (url != null) Log.i(TAG, "Canvas URL: ${url.take(80)}")
            else Log.d(TAG, "Canvas: no URL in ${bytes.size}b response")
            url
        }.getOrElse { Log.e(TAG, "Canvas exception: ${it.message}"); null }
    }

    // ── Protobuf (CanvasRequest / CanvasResponse) ─────────────────────────────
    //
    // message CanvasRequest  { message Track { string track_uri = 1; } repeated Track tracks = 1; }
    // message CanvasResponse { message Canvas { string id=1; string canvas_url=2; ... }
    //                          repeated Canvas canvases = 1; }

    private fun buildCanvasProto(trackId: String): ByteArray {
        val uri   = "spotify:track:$trackId".toByteArray(Charsets.UTF_8)
        val track = tag(1, 2) + varint(uri.size) + uri   // field 1 LEN (track_uri)
        return      tag(1, 2) + varint(track.size) + track // field 1 LEN (tracks[0])
    }

    private fun parseCanvasProto(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, n) = readVarint(bytes, i); i += n
            when {
                wire(tag) == 2 && fieldNum(tag) == 1 -> {
                    val (len, n2) = readVarint(bytes, i); i += n2
                    val end   = (i + len.toInt()).coerceAtMost(bytes.size)
                    val entry = bytes.copyOfRange(i, end); i += len.toInt()
                    parseCanvas(entry)?.let { return it }
                }
                else -> { i = skip(bytes, i, wire(tag)) ?: return null }
            }
        }
        return null
    }

    private fun parseCanvas(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tag, n) = readVarint(bytes, i); i += n
            when (wire(tag)) {
                2 -> {
                    val (len, n2) = readVarint(bytes, i); i += n2
                    val end  = (i + len.toInt()).coerceAtMost(bytes.size)
                    val data = bytes.copyOfRange(i, end); i += len.toInt()
                    if (fieldNum(tag) == 2) {           // canvas_url = field 2
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

    // protobuf tag byte: (fieldNumber << 3) | wireType
    private fun tag(field: Int, wireType: Int): ByteArray = varint((field shl 3) or wireType)
    private fun wire(tag: Long)     = (tag and 7L).toInt()
    private fun fieldNum(tag: Long) = (tag ushr 3).toInt()

    private fun varint(v: Int): ByteArray {
        val out = mutableListOf<Byte>(); var x = v
        while (x and 0x7F.inv() != 0) { out.add(((x and 0x7F) or 0x80).toByte()); x = x ushr 7 }
        out.add(x.toByte()); return out.toByteArray()
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        var r = 0L; var shift = 0; var i = offset
        while (i < bytes.size) {
            val b = bytes[i++].toLong() and 0xFF
            r = r or ((b and 0x7F) shl shift); if (b and 0x80 == 0L) break; shift += 7
        }
        return r to (i - offset)
    }

    private fun skip(bytes: ByteArray, offset: Int, wire: Int): Int? = when (wire) {
        0    -> { val (_, n) = readVarint(bytes, offset); offset + n }
        1    -> offset + 8
        2    -> { val (len, n) = readVarint(bytes, offset); offset + n + len.toInt() }
        5    -> offset + 4
        else -> null
    }
}
