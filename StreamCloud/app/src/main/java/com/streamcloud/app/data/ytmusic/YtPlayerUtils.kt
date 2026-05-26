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

        // #3 IOS — kept as fallback for region-specific content; CDN URLs give 403 when
        // YouTube enforces 'n' descrambling, but YouTube may relax enforcement per-track.
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

        // #4 TVHTML5 — Smart TV UA; 'n' enforcement status differs for this client type.
        ClientConfig(
            label         = "TVHTML5",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "TVHTML5",
            clientId      = "7",
            clientVersion = "7.20250101.14.00",
            userAgent     = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.5) AppleWebKit/538.1 (KHTML, like Gecko) Version/3.0 TV Safari/538.1",
            supportsAuth  = false,
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
                    // Descramble the 'n' query parameter — YouTube CDN rejects stream URLs whose
                    // n-value hasn't been transformed via the player JS nsig function (HTTP 403).
                    val descrambledUrl = YtNSigDescrambler.descrambleUrl(result.info.url)
                    AppLogger.i(TAG, "[${client.label}] resolved $videoId → itag=${result.info.itag} n-descrambled=${descrambledUrl != result.info.url}")
                    return@withContext result.info.copy(url = descrambledUrl)
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

            val plainUrl = run {
                val withUrl = audioOnly.filter {
                    it["url"]?.jsonPrimitive?.content?.isNotBlank() == true
                }
                if (sonosSafe) {
                    val mp4Only = withUrl.filter {
                        !it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/webm")
                    }
                    mp4Only.takeIf { it.isNotEmpty() } ?: withUrl
                } else withUrl
            }
            if (plainUrl.isEmpty()) return ClientResult.CipheredOnly

            val expiresInSeconds =
                streamingData["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 21_600L

            val best = if (preferItag != null) {
                plainUrl.find { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == preferItag }
                    ?: selectHighQuality(plainUrl)
                    ?: selectByQuality(plainUrl, preferHighQuality)
            } else {
                selectHighQuality(plainUrl) ?: selectByQuality(plainUrl, preferHighQuality)
            }

            val cpn = generateCpn()
            val rawUrl = best["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
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
                && it["url"]?.jsonPrimitive?.content?.isNotBlank() == true
        }
        if (high.isEmpty()) return null
        return high.firstOrNull { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == 774 }
            ?: high.firstOrNull { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == 141 }
            ?: high.first()
    }

    private fun generateCpn(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return (1..16).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }
}
