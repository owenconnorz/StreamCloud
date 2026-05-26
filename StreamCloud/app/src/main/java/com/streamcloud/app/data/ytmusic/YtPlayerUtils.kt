package com.streamcloud.app.data.ytmusic

import android.content.Context
import android.util.Log
import com.streamcloud.app.data.AppLogger
import com.streamcloud.app.data.ytmusic.potoken.PoTokenGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object YtPlayerUtils {

    private const val TAG = "YtPlayerUtils"

    private data class ClientConfig(
        val label: String,
        val playerUrl: String,
        val clientName: String,
        val clientId: String,
        val clientVersion: String,
        val userAgent: String,
        val extraClientFields: Map<String, Any> = emptyMap(),
        val embedUrlTemplate: String? = null,
        val requiresAuth: Boolean = false,
        // supportsAuth: send the YTM cookie (correct for Android/iOS music clients).
        val supportsAuth: Boolean = true,
        // useWebAuth: also send the SAPISIDHASH Authorization header. This is a
        // browser/web mechanism — Android and iOS app clients reject it with HTTP 400.
        // Only WEB_REMIX (and similar web clients) should set this to true.
        val useWebAuth: Boolean = false,
        val useWebPoTokens: Boolean = false,
    )

    private val CLIENTS = listOf(

        // ── REMOVAL NOTES ────────────────────────────────────────────────────────
        // ANDROID_MUSIC:  music.youtube.com returns LOGIN_REQUIRED for all unauthenticated
        //                 requests; browser cookies are also rejected (endpoint needs OAuth2).
        // ANDROID_VR:     Returns "Sign in to confirm you're not a bot" on every unauthenticated
        //                 request — bot-detection triggered before IOS even gets a chance.
        // IOS / IPADOS:   Resolve successfully but the CDN returns HTTP 403. YouTube now
        //                 enforces the 'n' parameter for iOS clients; without descrambling
        //                 the obfuscated 'n' value the CDN rejects the byte-fetch entirely.
        //                 ANDROID (id=3) has the same problem.
        // ─────────────────────────────────────────────────────────────────────────

        // #1 ANDROID_TESTSUITE — the only current client whose stream URLs bypass 'n'-parameter
        // enforcement.  YouTube's CDN does not validate 'n' for this internal test client,
        // so the URL is usable directly without JS-based descrambling.
        ClientConfig(
            label         = "ANDROID_TESTSUITE",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_TESTSUITE",
            clientId      = "30",
            clientVersion = "1.9",
            userAgent     = "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "11",
                "androidSdkVersion" to "30",
            ),
            supportsAuth = false,
        ),

        // #2 WEB_REMIX — YouTube Music web client with SAPISIDHASH + PoToken.
        // Returns ciphered-only streams when PoToken generation fails, but succeeds
        // for logged-in content when PoToken is available.
        ClientConfig(
            label          = "WEB_REMIX",
            playerUrl      = "https://music.youtube.com/youtubei/v1/player?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-KLET5YdUo&prettyPrint=false",
            clientName     = "WEB_REMIX",
            clientId       = "67",
            clientVersion  = "1.20260501.01.00",
            userAgent      = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            useWebAuth     = true,
            useWebPoTokens = true,
        ),

        // #3 TVHTML5_SIMPLY_EMBEDDED_PLAYER — embedded PS4 UA; bypasses age-restriction without
        // auth.  useSignatureTimestamp is not set so YouTube returns plain stream URLs.
        // Metrolist uses this as the first fallback after WEB_REMIX.
        ClientConfig(
            label         = "TVHTML5_SIMPLY_EMBEDDED",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientId      = "85",
            clientVersion = "2.0",
            userAgent     = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            supportsAuth  = false,
        ),

        // #4 TVHTML5 — Smart TV UA; n-transform IS required for this client.
        ClientConfig(
            label         = "TVHTML5",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "TVHTML5",
            clientId      = "7",
            clientVersion = "7.20260213.00.00",
            userAgent     = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            supportsAuth  = false,
        ),

        // #5 ANDROID_VR (Oculus Quest 3, v1.43.32) — returns plain stream URLs with no
        // signature cipher and no 'n' enforcement.  Comment in Metrolist: "uses non-adaptive
        // bitrate, which fixes audio stuttering with YT Music; does not use AV1."
        ClientConfig(
            label         = "ANDROID_VR_1_43",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_VR",
            clientId      = "28",
            clientVersion = "1.43.32",
            userAgent     = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "12",
                "deviceMake"        to "Oculus",
                "deviceModel"       to "Quest 3",
                "androidSdkVersion" to "32",
            ),
            supportsAuth  = false,
        ),

        // #6 ANDROID_VR (Oculus Quest 3, v1.61.48) — same as above, newer version.
        ClientConfig(
            label         = "ANDROID_VR_1_61",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_VR",
            clientId      = "28",
            clientVersion = "1.61.48",
            userAgent     = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "12",
                "deviceMake"        to "Oculus",
                "deviceModel"       to "Quest 3",
                "androidSdkVersion" to "32",
            ),
            supportsAuth  = false,
        ),

        // #7 IOS — last resort; 'n' enforcement applies but descramble may succeed.
        ClientConfig(
            label         = "IOS",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "IOS",
            clientId      = "5",
            clientVersion = "21.03.1",
            userAgent     = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            extraClientFields = mapOf(
                "deviceMake"  to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName"      to "iPhone",
                "osVersion"   to "18.2.22C152",
            ),
            supportsAuth = false,
        ),
    )

    private val http = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val poTokenGenerator = PoTokenGenerator()

    @Volatile var appContext: Context? = null
    @Volatile var ytMusicCookie: String = ""
    @Volatile var contentLanguage: String = "en"
    @Volatile var contentCountry:  String = "US"

    @Volatile private var cachedVisitorData: String? = null
    @Volatile private var visitorDataFetchedAt: Long = 0L

    private fun ensureVisitorData() {
        val now = System.currentTimeMillis()
        if (cachedVisitorData != null && now - visitorDataFetchedAt < 6 * 3_600_000L) return
        try {
            val body = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", contentLanguage)
                        put("gl", contentCountry)
                    }
                }
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false&alt=json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val text = resp.body?.string() ?: return
                val vd = json.parseToJsonElement(text).jsonObject["visitorData"]
                    ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return
                cachedVisitorData = vd
                visitorDataFetchedAt = now
                Log.d(TAG, "visitorData refreshed: ${vd.take(24)}…")
            }
        } catch (e: Exception) {
            Log.d(TAG, "visitorData fetch failed: ${e.message}")
        }
    }

    data class AudioFormatInfo(
        val url: String,
        val userAgent: String,
        val itag: Int,
        val mimeType: String,
        val bitrate: Long,
        val sampleRate: Int?,
        val contentLength: Long?,
        val loudnessDb: Double?,
        val expiresInSeconds: Long,
    )

    data class AudioStreamInfo(
        val url: String,
        val contentLength: Long?,
    )

    suspend fun warmUp() = withContext(Dispatchers.IO) { ensureVisitorData() }

    suspend fun resolveAudioFormatInfo(
        videoId: String,
        preferItag: Int? = null,
        preferHighQuality: Boolean = true,
        sonosSafe: Boolean = false,
    ): AudioFormatInfo? = withContext(Dispatchers.IO) {
        ensureVisitorData()
        val isLoggedIn = ytMusicCookie.isNotBlank()
        val sessionId = cachedVisitorData

        for (client in CLIENTS) {
            if (client.requiresAuth && !isLoggedIn) {
                Log.d(TAG, "[${client.label}] skipped — requires auth")
                continue
            }

            var poToken: String? = null
            if (client.useWebPoTokens && sessionId != null) {
                val ctx = appContext
                if (ctx != null) {
                    try {
                        poToken = poTokenGenerator.getWebClientPoToken(ctx, videoId, sessionId)
                            ?.playerRequestPoToken
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "[${client.label}] PoToken failed: ${e.message}")
                    }
                }
            }

            val result = tryClient(client, videoId, preferItag, preferHighQuality, poToken, sonosSafe)
            when (result) {
                is ClientResult.Success -> {
                    // Only web-family clients require n-parameter descrambling.
                    // ANDROID_VR, IOS, TVHTML5_SIMPLY_EMBEDDED_PLAYER return plain CDN URLs
                    // where the n-param is already valid — matches Metrolist's needsNTransform logic.
                    val needsNDescramble = client.useWebPoTokens ||
                        client.clientName in setOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5")
                    val candidateUrl = if (needsNDescramble) {
                        YtNSigDescrambler.descrambleUrl(result.info.url)
                    } else {
                        result.info.url
                    }
                    val nDescrambled = candidateUrl != result.info.url
                    AppLogger.i(TAG, "[${client.label}] resolved $videoId → itag=${result.info.itag} n-descrambled=$nDescrambled")

                    // If this client needed n-descrambling but the nsig function couldn't be
                    // extracted from the player JS (URL is unchanged), the CDN will 403 on the
                    // actual byte stream even though HEAD returns 200.  Skip immediately.
                    if (needsNDescramble && !nDescrambled) {
                        AppLogger.w(TAG, "[${client.label}] $videoId — n-descramble failed (player JS unsupported), trying next client")
                        continue
                    }

                    // Validate the URL with a HEAD request before committing — same as Metrolist's
                    // validateStatus().  If it 403s, skip to the next client instead of handing a
                    // broken URL to ExoPlayer (which takes 5-10 s to surface the error to the UI).
                    if (validateStreamUrl(candidateUrl)) {
                        return@withContext result.info.copy(url = candidateUrl)
                    } else {
                        AppLogger.w(TAG, "[${client.label}] $videoId — URL failed HEAD validation (403), trying next client")
                    }
                }
                is ClientResult.CipheredOnly ->
                    AppLogger.w(TAG, "[${client.label}] $videoId — ciphered only, trying next")
                is ClientResult.NoStreams -> {
                    val why = result.reason?.let { " ($it)" } ?: ""
                    AppLogger.w(TAG, "[${client.label}] $videoId — no streams$why, trying next")
                    Log.d(TAG, "[${client.label}] no streams status=${result.status}")
                }
                is ClientResult.Error ->
                    AppLogger.w(TAG, "[${client.label}] $videoId — error: ${result.cause?.message}")
            }
        }
        AppLogger.e(TAG, "All clients failed for $videoId")
        throw IllegalStateException("YouTube returned no audio streams for $videoId")
    }

    suspend fun resolveAudioStreamInfo(videoId: String): AudioStreamInfo? =
        resolveAudioFormatInfo(videoId)?.let { AudioStreamInfo(it.url, it.contentLength) }

    suspend fun resolveAudioStream(videoId: String, sonosSafe: Boolean = false): String? =
        resolveAudioFormatInfo(videoId, sonosSafe = sonosSafe)?.url

    private val AGE_GATE_STATUSES = setOf(
        "AGE_CHECK_REQUIRED",
        "AGE_VERIFICATION_REQUIRED",
        "LOGIN_REQUIRED",
        "CONTENT_CHECK_REQUIRED",
    )

    private sealed interface ClientResult {
        data class Success(val info: AudioFormatInfo) : ClientResult
        data object CipheredOnly : ClientResult
        data class NoStreams(val reason: String? = null, val status: String? = null) : ClientResult
        data class Error(val cause: Throwable?) : ClientResult
    }

    private fun tryClient(
        client: ClientConfig,
        videoId: String,
        preferItag: Int?,
        preferHighQuality: Boolean,
        poToken: String?,
        sonosSafe: Boolean = false,
    ): ClientResult {
        return try {
            val root = fetchPlayerResponse(client, videoId, poToken)
                ?: return ClientResult.Error(null)

            val playabilityStatusObj = root["playabilityStatus"]?.jsonObject
            val playabilityReason = playabilityStatusObj?.get("reason")?.jsonPrimitive?.content
            val playabilityStatus = playabilityStatusObj?.get("status")?.jsonPrimitive?.content

            val streamingData = root["streamingData"]?.jsonObject
                ?: return ClientResult.NoStreams(playabilityReason, playabilityStatus)

            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray
                ?: return ClientResult.NoStreams(playabilityReason, playabilityStatus)

            val audioOnly = adaptiveFormats
                .mapNotNull { it as? JsonObject }
                .filter { it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/") }

            if (audioOnly.isEmpty()) return ClientResult.NoStreams(playabilityReason, playabilityStatus)

            // Prefer formats with a direct url field; fall back to signatureCipher extraction.
            // As of May 2026 (player hash 57f5d44f) YouTube no longer verifies the cipher
            // signature — the base url= inside signatureCipher is valid without decryption.
            val candidateFormats = run {
                val withDirectUrl = audioOnly.filter {
                    it["url"]?.jsonPrimitive?.content?.isNotBlank() == true
                }
                val pool = withDirectUrl.ifEmpty {
                    audioOnly.filter { fmt ->
                        val cipher = fmt["signatureCipher"]?.jsonPrimitive?.content
                            ?: fmt["cipher"]?.jsonPrimitive?.content
                        cipher != null && parseCipherUrl(cipher) != null
                    }
                }
                if (sonosSafe) {
                    val mp4Only = pool.filter {
                        !it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/webm")
                    }
                    mp4Only.takeIf { it.isNotEmpty() } ?: pool
                } else pool
            }
            if (candidateFormats.isEmpty()) return ClientResult.CipheredOnly

            val expiresInSeconds =
                streamingData["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 21_600L

            val best = if (preferItag != null) {
                candidateFormats.find { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == preferItag }
                    ?: selectHighQuality(candidateFormats)
                    ?: selectByQuality(candidateFormats, preferHighQuality)
            } else {
                selectHighQuality(candidateFormats) ?: selectByQuality(candidateFormats, preferHighQuality)
            }

            val cpn = generateCpn()
            // Get the URL — direct field first, then extract from signatureCipher
            val rawUrl = best["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: run {
                    val cipher = best["signatureCipher"]?.jsonPrimitive?.content
                        ?: best["cipher"]?.jsonPrimitive?.content
                    cipher?.let { parseCipherUrl(it) }
                }
                ?: return ClientResult.CipheredOnly
            val contentLength = best["contentLength"]?.jsonPrimitive?.content?.toLongOrNull()
            // Do NOT append &range= to the URL — YouTube CDN will lock the response to that byte range
            // and ignore ExoPlayer's HTTP Range headers, causing ERROR_CODE_IO_UNSPECIFIED when seeking.
            // ExoPlayer handles range requests via standard Range: bytes=X-Y headers automatically.
            val url = "$rawUrl&cpn=$cpn"

            val loudnessDb = root["playerConfig"]
                ?.jsonObject?.get("audioConfig")
                ?.jsonObject?.get("loudnessDb")
                ?.jsonPrimitive?.content?.toDoubleOrNull()

            ClientResult.Success(
                AudioFormatInfo(
                    url              = url,
                    userAgent        = client.userAgent,
                    itag             = best["itag"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    mimeType         = best["mimeType"]?.jsonPrimitive?.content.orEmpty(),
                    bitrate          = best["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    sampleRate       = best["audioSampleRate"]?.jsonPrimitive?.content?.toIntOrNull(),
                    contentLength    = contentLength,
                    loudnessDb       = loudnessDb,
                    expiresInSeconds = expiresInSeconds,
                )
            )
        } catch (e: Exception) {
            ClientResult.Error(e)
        }
    }

    private fun fetchPlayerResponse(
        client: ClientConfig,
        videoId: String,
        poToken: String?,
    ): JsonObject? {
        val embedUrl = client.embedUrlTemplate?.replace("%VIDEO_ID%", videoId)
        val requestOrigin = if (client.playerUrl.contains("music.youtube.com"))
            "https://music.youtube.com" else "https://www.youtube.com"
        val vd = cachedVisitorData

        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.clientName)
                    put("clientVersion", client.clientVersion)
                    put("userAgent", client.userAgent)
                    put("hl", contentLanguage)
                    put("gl", contentCountry)
                    if (vd != null) put("visitorData", vd)
                    client.extraClientFields.forEach { (k, v) ->
                        when (v) {
                            is Int     -> put(k, v)
                            is Long    -> put(k, v)
                            is Boolean -> put(k, v)
                            else       -> put(k, v.toString())
                        }
                    }
                }
                if (embedUrl != null) {
                    putJsonObject("thirdParty") {
                        put("embedUrl", embedUrl)
                    }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (poToken != null) {
                putJsonObject("serviceIntegrityDimensions") {
                    put("poToken", poToken)
                }
            }
        }

        val reqBuilder = Request.Builder()
            .url(client.playerUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientId)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .header("X-Goog-Api-Format-Version", "1")
            .header("Content-Type", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")

        if (vd != null) reqBuilder.header("X-Goog-Visitor-Id", vd)

        val cookie = ytMusicCookie
        if (cookie.isNotBlank() && client.supportsAuth) {
            reqBuilder.header("Cookie", cookie)
            reqBuilder.header("Origin", requestOrigin)
            // SAPISIDHASH is a browser/web auth mechanism. Android and iOS app clients
            // return HTTP 400 when it is present — only send it for web clients (useWebAuth).
            if (client.useWebAuth) {
                val auth = YtMusicAuth.sapisidHashHeader(cookie, requestOrigin)
                if (auth != null) reqBuilder.header("Authorization", auth)
            }
        }

        return http.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLogger.w(TAG, "[${client.label}] $videoId — HTTP ${resp.code}")
                return null
            }
            val text = resp.body?.string() ?: return null
            json.parseToJsonElement(text).jsonObject
        }
    }

    private fun selectByQuality(audioFormats: List<JsonObject>, preferHighQuality: Boolean): JsonObject =
        audioFormats.maxByOrNull { fmt ->
            val bitrate = fmt["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val isOpus  = fmt["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/webm")
            val sign    = if (preferHighQuality) 1L else -1L
            bitrate * sign + (if (isOpus) 10_240L else 0L)
        } ?: audioFormats.first()

    private fun selectHighQuality(audioFormats: List<JsonObject>): JsonObject? {
        val high = audioFormats.filter {
            it["audioQuality"]?.jsonPrimitive?.content == "AUDIO_QUALITY_HIGH"
        }
        if (high.isEmpty()) return null
        return high.firstOrNull { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == 774 }
            ?: high.firstOrNull { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == 141 }
            ?: high.first()
    }

    /**
     * Validate a stream URL by making a HEAD request — identical to Metrolist's validateStatus().
     * Returns true if the server responds 2xx, false for 403/404/etc.
     * Skips validation (returns true) if the network call itself fails so we don't block playback
     * on transient connectivity issues.
     */
    private fun validateStreamUrl(url: String): Boolean {
        return try {
            val req = Request.Builder().url(url).head().build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.d(TAG, "validateStreamUrl exception (assuming ok): ${e.message}")
            true   // network error ≠ 403 — don't skip a potentially good URL
        }
    }

    /**
     * Extract the base url= value from a YouTube signatureCipher / cipher param string.
     * The cipher is URL-encoded: s=...&sp=sig&url=https%3A%2F%2F...
     * As of May 2026 (player hash 57f5d44f) YouTube stopped enforcing cipher signatures,
     * so using this base URL directly (without decrypting or appending 's') works fine.
     */
    private fun parseCipherUrl(cipher: String): String? {
        for (part in cipher.split("&")) {
            val eqIdx = part.indexOf('=')
            if (eqIdx < 0) continue
            if (part.substring(0, eqIdx) == "url") {
                return java.net.URLDecoder.decode(part.substring(eqIdx + 1), "UTF-8")
                    .takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun generateCpn(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return (1..16).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }
}
