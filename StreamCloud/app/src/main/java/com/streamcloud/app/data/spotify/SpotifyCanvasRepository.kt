package com.streamcloud.app.data.spotify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fetches Spotify Canvas looping video URLs.
 * Exact port of SimpMusic / VxMusic:
 *   SpotifyTotp.kt  → generateSecret (XOR → join → base32 → trim padding)
 *   SpotifyAuth.kt  → refreshToken   (server-time → TOTP → transport then init)
 *   SpotifyClient.kt → all HTTP calls
 *
 * IMPORTANT: Never set Accept-Encoding manually on JSON requests.
 * OkHttp auto-adds "Accept-Encoding: gzip" and transparently decompresses gzip responses.
 * Explicitly setting Accept-Encoding disables that transparent decompression.
 */
object SpotifyCanvasRepository {

    private const val TAG = "SpotifyCanvas"

    // Exactly matches SimpMusic SpotifyClient companion object
    private const val UA_WEB    = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.157 Safari/537.36"
    private const val UA_MOBILE = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
    private const val UA_CT     = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0"
    private const val APP_VER   = "1.2.61.20.g3b4cd5b2"

    // TOTP_SECRET_V22 from SimpMusic SpotifyTotp.kt (last-resort fallback)
    private val CIPHER_V22  = intArrayOf(99,101,119,123,69,120,91,123,97,74,53,48,76,102,55,69,110,54)
    private const val VER_22 = 22

    // Latest known cipher (v61) — replaced by remote fetch on first run
    private val CIPHER_V61  = intArrayOf(44,55,47,42,70,40,34,114,76,74,50,111,120,97,75,76,94,102,43,69,49,120,118,80,64,78)
    private const val VER_61 = 61

    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val SEARCH_SHA = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"

    // Client body matches SimpMusic SpotifyClientBody exactly
    private const val CLIENT_BODY = """{"client_data":{"client_version":"1.2.62.476.g2ad6e7f3","client_id":"d8a5ed958d274c2e8ee717e6a4b0971d","js_sdk_data":{"device_brand":"Apple","device_model":"unknown","os":"macos","os_version":"10.15.7","device_id":"4fd0c748-b282-4927-9658-6d51a24e58b7","device_type":"computer"}}}"""

    // In-memory cookie jar — mirrors Ktor's AcceptAllCookiesStorage used by SimpMusic.
    // Critical: open.spotify.com/api/server-time sets session cookies (e.g. sp_t) that
    // MUST be forwarded to open.spotify.com/api/token or the token endpoint rejects the request.
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            cookieStore.getOrPut(host) { mutableListOf() }.also { list ->
                cookies.forEach { new ->
                    list.removeAll { it.name == new.name }
                    list.add(new)
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host] ?: emptyList()
    }

    // OkHttp with an AcceptAll cookie jar — DO NOT override Accept-Encoding on JSON calls.
    // OkHttp automatically adds "Accept-Encoding: gzip" and decompresses gzip transparently.
    // Setting it manually disables that and we'd get compressed bytes instead of text.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    // ── Cached state ──────────────────────────────────────────────────────────

    @Volatile private var storedSpDc: String?      = null
    @Volatile private var cachedToken: String?     = null
    @Volatile private var tokenExpiryMs: Long      = 0L
    @Volatile private var cachedCt: String?        = null
    @Volatile private var ctExpiryMs: Long         = 0L
    @Volatile private var cipherVer: Int           = VER_61
    @Volatile private var cipherBytes: IntArray    = CIPHER_V61
    @Volatile private var secretFetchedMs: Long    = 0L

    private val urlCache  = LinkedHashMap<String, String?>(64, 0.75f, true)
    private val cacheLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    fun setSpotifyCookie(cookie: String?) {
        val extracted = when {
            cookie.isNullOrBlank() -> null
            cookie.contains("sp_dc=") ->
                cookie.split(";").map { it.trim() }
                    .firstOrNull { it.startsWith("sp_dc=") }
                    ?.removePrefix("sp_dc=")?.trim()?.takeIf { it.isNotBlank() }
            else -> cookie.trim().takeIf { it.isNotBlank() }
        }
        if (extracted == storedSpDc) return
        storedSpDc = extracted
        cachedToken = null; tokenExpiryMs = 0L
        // Clear URL cache so stale null-results from before login don't block new fetches
        synchronized(cacheLock) { urlCache.clear() }
        // Clear cookie jar so session cookies from old sp_dc don't poison new token requests
        cookieStore.clear()
        Log.i(TAG, if (extracted != null) "sp_dc updated (${extracted.take(6)}…)" else "sp_dc cleared")
    }

    suspend fun getCanvasUrl(videoId: String, title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                if (urlCache.containsKey(videoId)) return@withContext urlCache[videoId]
            }
            val url = try { fetchCanvas(title, artist) }
            catch (e: Exception) { Log.e(TAG, "[$title] ${e.javaClass.simpleName}: ${e.message}"); null }
            // Only cache successes — null stays uncached so the next play retries the network
            if (url != null) synchronized(cacheLock) { urlCache[videoId] = url }
            Log.i(TAG, "[$title] canvasUrl=${url?.take(60) ?: "null"}")
            url
        }

    // ── Canvas pipeline ───────────────────────────────────────────────────────

    private fun fetchCanvas(title: String, artist: String): String? {
        val spDc = storedSpDc
        if (spDc.isNullOrBlank()) { Log.w(TAG, "[$title] no sp_dc"); return null }

        val token = ensureToken(spDc)
            ?: run { Log.e(TAG, "[$title] token=null"); return null }
        val ct = ensureClientToken()
            ?: run { Log.e(TAG, "[$title] clientToken=null"); return null }

        val q = buildQuery(title, artist)
        val trackId = searchTrackId(token, ct, q)
            ?: run { Log.w(TAG, "[$title] track not found (q=\"$q\")"); return null }
        Log.d(TAG, "[$title] trackId=$trackId")

        return fetchCanvasUrl(token, ct, trackId)
    }

    // ── TOTP — exact port of SimpMusic SpotifyTotp.generateSecret ────────────
    //
    // Step-by-step (mirrors the Kotlin source):
    //   transformed[i] = cipher[i] XOR ((i % 33) + 9)
    //   joined          = transformed.joinToString("")          e.g. "3761404938…"
    //   hexStr          = joined.toByteArray().toHexString()    hex of ASCII bytes
    //   secret          = base64ToBase32(Base64.encode(hexStr.hexToByteArray())).trimEnd('=')
    //
    // The hex roundtrip cancels: hexStr.hexToByteArray() == joined.toByteArray()
    // So net result: secret = base32Encode(joined.toByteArray()).trimEnd('=')
    //
    // Then: GoogleAuthenticator(secret.toByteArray()).generate(Date(timestampMs))
    //   = HMAC-SHA1( key=secret.toByteArray(), counter=timestampMs/30000 )

    private fun buildSecret(cipher: IntArray): String {
        val transformed = cipher.mapIndexed { i, b -> b xor ((i % 33) + 9) }
        val joined = transformed.joinToString("")           // decimal ASCII string
        val joinedBytes = joined.toByteArray(Charsets.US_ASCII)
        return base32Encode(joinedBytes).trimEnd('=')       // base32 of ASCII bytes, no padding
    }

    private fun generateTotp(secret: String, timestampMs: Long): String {
        // secret.toByteArray() = raw ASCII bytes of the base32 string (NOT base32-decoded)
        // This matches: GoogleAuthenticator(secret.toByteArray()).generate(Date(timestampMs))
        val key     = secret.toByteArray(Charsets.US_ASCII)
        val counter = timestampMs / 30_000L
        val msg     = ByteBuffer.allocate(8).putLong(counter).array()
        val mac     = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val h      = mac.doFinal(msg)
        val offset = h.last().toInt() and 0x0F
        val code   = ((h[offset].toInt()     and 0x7F) shl 24) or
                     ((h[offset + 1].toInt() and 0xFF) shl 16) or
                     ((h[offset + 2].toInt() and 0xFF) shl  8) or
                      (h[offset + 3].toInt() and 0xFF)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32Encode(data: ByteArray): String {
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

    // ── TOTP secret refresh (SimpMusic SpotifyAuth.getTotpSecret) ─────────────

    private fun refreshSecrets() {
        if (System.currentTimeMillis() - secretFetchedMs < 6 * 3_600_000L) return
        runCatching {
            // Same URL as SimpMusic SpotifyClient.getSpotifyLastestTotpSecret
            val req = Request.Builder()
                .url("https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json")
                .header("User-Agent", UA_WEB)
                // ← NO Accept-Encoding header: let OkHttp handle gzip automatically
                .get().build()
            val text = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "Secrets HTTP ${r.code}"); return@runCatching }
                r.body?.string()
            } ?: return@runCatching
            val obj  = JSONObject(text)
            val keys = obj.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
            val key  = keys.lastOrNull() ?: return@runCatching
            val arr  = obj.optJSONArray(key) ?: return@runCatching
            cipherVer   = key.toInt()
            cipherBytes = IntArray(arr.length()) { arr.getInt(it) }
            secretFetchedMs = System.currentTimeMillis()
            Log.i(TAG, "TOTP secrets refreshed: v$cipherVer (${cipherBytes.size} bytes)")
        }.onFailure { Log.w(TAG, "Secrets fetch failed: ${it.message}") }
    }

    // ── Server time (SimpMusic SpotifyClient.getSpotifyServerTime) ────────────

    private fun fetchServerTime(spDc: String): Long {
        // ← NO Accept-Encoding: OkHttp handles gzip automatically → body?.string() works
        return runCatching {
            val req = Request.Builder()
                .url("https://open.spotify.com/api/server-time")
                .header("User-Agent", UA_WEB)
                .header("Cookie", "sp_dc=$spDc")
                .header("App-platform", "WebPlayer")
                .header("Spotify-App-Version", APP_VER)
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            val text = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "ServerTime HTTP ${r.code}"); return@runCatching null }
                r.body?.string()
            } ?: return@runCatching null
            val t = JSONObject(text).optLong("serverTime", 0L)
            Log.d(TAG, "serverTime=${t}s")
            if (t > 0L) t else null
        }.getOrNull() ?: (System.currentTimeMillis() / 1000L)
    }

    // ── Personal token (SimpMusic SpotifyAuth.refreshToken) ───────────────────

    private fun ensureToken(spDc: String): String? {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiryMs - 60_000L }?.let { return it }

        // Attempt 0: old non-TOTP endpoint (SimpMusic's getSpotifyLyricsToken path).
        // Faster and simpler — works for many accounts. Falls through if anonymous.
        tryTokenLegacy(spDc)?.let { return it }

        refreshSecrets()

        val serverTimeSec = fetchServerTime(spDc)
        val tsMs          = serverTimeSec * 1000L      // Date(tsMs) as in SimpMusic

        // Primary cipher (fetched or v61 fallback)
        val secret1  = buildSecret(cipherBytes)
        val otp1     = generateTotp(secret1, tsMs)
        Log.d(TAG, "TOTP v$cipherVer otp=$otp1 ts=${serverTimeSec}s")

        // SimpMusic tries transport first, then init
        var token = tryToken(spDc, otp1, "transport", cipherVer)
            ?: tryToken(spDc, otp1, "init", cipherVer)

        // Fallback: TOTP_SECRET_V22 (SimpMusic keeps this as a hard constant)
        if (token == null) {
            val secret22 = buildSecret(CIPHER_V22)
            val otp22    = generateTotp(secret22, tsMs)
            Log.d(TAG, "TOTP v22 fallback otp=$otp22")
            token = tryToken(spDc, otp22, "transport", VER_22)
                ?: tryToken(spDc, otp22, "init", VER_22)
        }

        return token
    }

    /** Old non-TOTP endpoint — SimpMusic's getSpotifyLyricsToken. Works on many accounts. */
    private fun tryTokenLegacy(spDc: String): String? {
        val url = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
        return runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_WEB)
                .header("Cookie", "sp_dc=$spDc")
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .get().build()
            val text = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.d(TAG, "LegacyToken HTTP ${r.code}"); return@runCatching null }
                r.body?.string()
            } ?: return@runCatching null
            val obj    = JSONObject(text)
            val at     = obj.optString("accessToken", "")
            val isAnon = obj.optBoolean("isAnonymous", false)
            if (isAnon || at.length < 100) {
                Log.d(TAG, "LegacyToken anonymous/short (len=${at.length}) — trying TOTP")
                return@runCatching null
            }
            val exp = obj.optLong("accessTokenExpirationTimestampMs", 0L)
            cachedToken   = at
            tokenExpiryMs = if (exp > 0L) exp else System.currentTimeMillis() + 50L * 60L * 1000L
            Log.i(TAG, "LegacyToken OK (len=${at.length})")
            at
        }.getOrElse { Log.d(TAG, "LegacyToken exception: ${it.message}"); null }
    }

    private fun tryToken(spDc: String, otp: String, reason: String, ver: Int): String? {
        // Matches SimpMusic SpotifyClient.getSpotifyAccessToken — productType=mobile-web-player
        // ← NO Accept-Encoding header here either
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
            val text = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.d(TAG, "Token HTTP ${r.code} ($reason/$ver)"); return@runCatching null }
                r.body?.string()
            } ?: return@runCatching null
            val obj = JSONObject(text)
            val at       = obj.optString("accessToken", "")
            // Default false matches SimpMusic PersonalTokenResponse(isAnonymous: Boolean = false)
            val isAnon   = obj.optBoolean("isAnonymous", false)
            if (isAnon || at.length < 100) {
                Log.d(TAG, "Token rejected: isAnonymous=$isAnon len=${at.length} ($reason/$ver)")
                return@runCatching null
            }
            val exp = obj.optLong("accessTokenExpirationTimestampMs", 0L)
            cachedToken  = at
            tokenExpiryMs = if (exp > 0L) exp else System.currentTimeMillis() + 50L * 60L * 1000L
            Log.i(TAG, "Token OK ($reason/v$ver len=${at.length})")
            at
        }.getOrElse { Log.d(TAG, "Token exception ($reason/$ver): ${it.message}"); null }
    }

    // ── Client token (SimpMusic SpotifyClient.getSpotifyClientToken) ──────────

    private fun ensureClientToken(): String? {
        val now = System.currentTimeMillis()
        cachedCt?.takeIf { now < ctExpiryMs - 60_000L }?.let { return it }
        // ← NO Accept-Encoding: OkHttp handles gzip → body?.string() is plain JSON
        return runCatching {
            val req = Request.Builder()
                .url("https://clienttoken.spotify.com/v1/clienttoken")
                .post(CLIENT_BODY.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", UA_CT)
                .header("Accept", "application/json")
                .build()
            val text = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.e(TAG, "ClientToken HTTP ${r.code}"); return@runCatching null }
                r.body?.string()
            } ?: return@runCatching null
            val gt = JSONObject(text).optJSONObject("granted_token") ?: return@runCatching null
            val ct  = gt.optString("token", "").takeIf { it.isNotBlank() } ?: return@runCatching null
            val exp = gt.optLong("expires_after_seconds", 1800L)
            cachedCt    = ct
            ctExpiryMs  = now + exp * 1000L
            Log.d(TAG, "ClientToken OK (exp=${exp}s)")
            ct
        }.getOrElse { Log.e(TAG, "ClientToken exception: ${it.message}"); null }
    }

    // ── Track search (SimpMusic SpotifyClient.searchSpotifyTrack — api-partner GraphQL) ─────────

    private fun buildQuery(title: String, artist: String): String =
        "$title $artist"
            .replace(Regex("\\((feat\\.|ft\\.) [^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[()]"), "")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun searchTrackId(token: String, ct: String, q: String): String? {
        // Matches SimpMusic SpotifyClient.searchSpotifyTrack (api-partner GraphQL, NOT v1/search)
        val variables  = URLEncoder.encode(
            """{"searchTerm":"$q","offset":0,"limit":3,"numberOfTopResults":3,"includeAudiobooks":true,"includePreReleases":false}""",
            "UTF-8")
        val extensions = URLEncoder.encode(
            """{"persistedQuery":{"version":1,"sha256Hash":"$SEARCH_SHA"}}""",
            "UTF-8")
        val url = "https://api-partner.spotify.com/pathfinder/v1/query" +
            "?operationName=searchTracks&variables=$variables&extensions=$extensions"

        // ← NO Accept-Encoding: OkHttp handles gzip → body?.string() is plain JSON
        return runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("Client-Token", ct)
                .header("User-Agent", UA_WEB)
                .get().build()
            val text = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.d(TAG, "Search HTTP ${r.code} q=\"$q\""); return@runCatching null }
                r.body?.string()
            } ?: return@runCatching null
            // Parse: data.searchV2.tracksV2.items[0].item.data.id
            JSONObject(text)
                .optJSONObject("data")
                ?.optJSONObject("searchV2")
                ?.optJSONObject("tracksV2")
                ?.optJSONArray("items")
                ?.takeIf { it.length() > 0 }
                ?.getJSONObject(0)
                ?.optJSONObject("item")
                ?.optJSONObject("data")
                ?.optString("id", "")
                ?.takeIf { it.isNotBlank() }
        }.getOrElse { Log.d(TAG, "Search exception: ${it.message}"); null }
    }

    // ── Canvas fetch (SimpMusic SpotifyClient.getSpotifyCanvas) ──────────────

    private fun fetchCanvasUrl(token: String, ct: String, trackId: String): String? {
        val body = buildCanvasProto(trackId)
        Log.d(TAG, "Canvas POST for $trackId (${body.size}b)")

        // Matches SimpMusic SpotifyClient.getSpotifyCanvas headers exactly
        // NOTE: for binary protobuf we want raw bytes, so we DO NOT set Accept-Encoding
        // (OkHttp will add gzip automatically but we need to handle compressed binary)
        // → safest: don't advertise compression so Spotify sends uncompressed proto bytes
        val req = Request.Builder()
            .url("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")
            .post(body.toRequestBody("application/protobuf".toMediaType()))
            .header("Accept", "application/protobuf")
            .header("Authorization", "Bearer $token")
            .header("Client-Token", ct)
            .header("User-Agent", UA_MOBILE)
            .build()

        return runCatching {
            val bytes = http.newCall(req).execute().use { r ->
                Log.d(TAG, "Canvas HTTP ${r.code} (${r.body?.contentLength() ?: "?"}b)")
                when {
                    r.code == 401 -> { invalidateToken(); return@runCatching null }
                    !r.isSuccessful -> { Log.w(TAG, "Canvas HTTP ${r.code}"); return@runCatching null }
                    else -> r.body?.bytes()
                }
            } ?: run { Log.d(TAG, "Canvas: empty body"); return@runCatching null }

            parseCanvasUrl(bytes).also {
                if (it != null) Log.i(TAG, "Canvas URL: ${it.take(80)}")
                else Log.d(TAG, "Canvas: no URL in ${bytes.size}b")
            }
        }.getOrElse { Log.e(TAG, "Canvas exception: ${it.message}"); null }
    }

    private fun invalidateToken() {
        cachedToken = null; tokenExpiryMs = 0L
        Log.w(TAG, "Token invalidated (401)")
    }

    // ── Protobuf encode/decode ────────────────────────────────────────────────
    //
    // CanvasRequest:  { repeated Track tracks = 1; }
    //   Track:        { string track_uri = 1; }
    // CanvasResponse: { repeated Canvas canvases = 1; }
    //   Canvas:       { string id = 1; string canvas_url = 2; … }

    private fun buildCanvasProto(trackId: String): ByteArray {
        val uri   = "spotify:track:$trackId".toByteArray(Charsets.UTF_8)
        val track = protoLenField(1, uri)   // Track { track_uri (field 1) = uri }
        return      protoLenField(1, track) // CanvasRequest { tracks (field 1) = track }
    }

    private fun protoLenField(fieldNumber: Int, data: ByteArray): ByteArray {
        val tag = varint((fieldNumber shl 3) or 2)  // wire type 2 = length-delimited
        return tag + varint(data.size) + data
    }

    private fun parseCanvasUrl(bytes: ByteArray): String? {
        // Walk top-level fields (field 1 = canvases, repeated)
        var i = 0
        while (i < bytes.size) {
            val (tagVal, tn) = readVarint(bytes, i); i += tn
            val wire   = (tagVal and 7L).toInt()
            val field  = (tagVal ushr 3).toInt()
            if (wire == 2) {
                val (len, ln) = readVarint(bytes, i); i += ln
                val end = (i + len.toInt()).coerceAtMost(bytes.size)
                if (field == 1) {   // canvases[n]
                    parseCanvasEntry(bytes.copyOfRange(i, end))?.let { return it }
                }
                i = end
            } else {
                i = skipField(bytes, i, wire) ?: return null
            }
        }
        return null
    }

    private fun parseCanvasEntry(bytes: ByteArray): String? {
        var i = 0
        while (i < bytes.size) {
            val (tagVal, tn) = readVarint(bytes, i); i += tn
            val wire  = (tagVal and 7L).toInt()
            val field = (tagVal ushr 3).toInt()
            if (wire == 2) {
                val (len, ln) = readVarint(bytes, i); i += ln
                val end = (i + len.toInt()).coerceAtMost(bytes.size)
                if (field == 2) {   // canvas_url = field 2
                    val url = String(bytes.copyOfRange(i, end), Charsets.UTF_8)
                    if (url.startsWith("https://")) return url
                }
                i = end
            } else {
                i = skipField(bytes, i, wire) ?: return null
            }
        }
        return null
    }

    // ── Protobuf varint helpers ───────────────────────────────────────────────

    private fun varint(v: Int): ByteArray {
        val out = mutableListOf<Byte>(); var x = v
        while (x and 0x7F.inv() != 0) { out.add(((x and 0x7F) or 0x80).toByte()); x = x ushr 7 }
        out.add(x.toByte()); return out.toByteArray()
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var i = offset
        while (i < bytes.size) {
            val b = bytes[i++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return result to (i - offset)
    }

    private fun skipField(bytes: ByteArray, offset: Int, wire: Int): Int? = when (wire) {
        0 -> { val (_, n) = readVarint(bytes, offset); offset + n }
        1 -> offset + 8
        2 -> { val (len, n) = readVarint(bytes, offset); offset + n + len.toInt() }
        5 -> offset + 4
        else -> null
    }
}
