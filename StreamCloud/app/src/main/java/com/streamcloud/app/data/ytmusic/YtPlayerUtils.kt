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
        val supportsAuth: Boolean = true,
        val useWebPoTokens: Boolean = false,
    )

    private val CLIENTS = listOf(

        ClientConfig(
            label         = "ANDROID_VR_1_43_32",
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
            supportsAuth    = false,
            useWebPoTokens  = true,
        ),

        ClientConfig(
            label         = "ANDROID_VR_1_61_48",
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
            supportsAuth = false,
        ),

        ClientConfig(
            label         = "ANDROID_MUSIC",
            playerUrl     = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_MUSIC",
            clientId      = "21",
            clientVersion = "7.27.52",
            userAgent     = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "11",
                "androidSdkVersion" to "30",
            ),
        ),

        ClientConfig(
            label         = "ANDROID",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID",
            clientId      = "3",
            clientVersion = "21.03.38",
            userAgent     = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "14",
                "androidSdkVersion" to "34",
            ),
            supportsAuth  = false,
        ),

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
            supportsAuth  = false,
        ),

        ClientConfig(
            label            = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            playerUrl        = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName       = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientId         = "85",
            clientVersion    = "2.0",
            userAgent        = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            embedUrlTemplate = "https://www.youtube.com/embed/%VIDEO_ID%",
            supportsAuth     = false,
        ),

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
            supportsAuth  = false,
        ),

        ClientConfig(
            label         = "IPADOS",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "IOS",
            clientId      = "5",
            clientVersion = "21.03.3",
            userAgent     = "com.google.ios.youtube/21.03.3 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)",
            extraClientFields = mapOf(
                "deviceMake"  to "Apple",
                "deviceModel" to "iPad7,6",
                "osName"      to "iPadOS",
                "osVersion"   to "17.7.10.21H450",
            ),
            supportsAuth  = false,
        ),

        ClientConfig(
            label         = "ANDROID_CREATOR",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_CREATOR",
            clientId      = "14",
            clientVersion = "25.03.101",
            userAgent     = "com.google.android.apps.youtube.creator/25.03.101 (Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "15",
                "deviceMake"        to "Google",
                "deviceModel"       to "Pixel 9 Pro Fold",
                "androidSdkVersion" to "35",
            ),
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
                val vd = json.parseToJsonElement(text).jsonObject["responseContext"]
                    ?.jsonObject?.get("visitorData")
                    ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return
                cachedVisitorData = vd
                visitorDataFetchedAt = now
                Log.d(TAG, "visitorData refreshed: ${vd.take(24)}…")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "visitorData fetch failed: ${e.message}")
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
            if (client.useWebPoTokens && sessionId == null) {
                AppLogger.w(TAG, "[${client.label}] PoToken skipped — visitorData is null")
            } else if (client.useWebPoTokens && sessionId != null) {
                val ctx = appContext
                if (ctx != null) {
                    try {
                        poToken = poTokenGenerator.getWebClientPoToken(ctx, videoId, sessionId)
                            ?.playerRequestPoToken
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "[${client.label}] PoToken generation failed: ${e.message}")
                    }
                }
            }

            val result = tryClient(client, videoId, preferItag, preferHighQuality, poToken)
            when (result) {
                is ClientResult.Success -> {
                    AppLogger.i(TAG, "[${client.label}] resolved $videoId → itag=${result.info.itag}")
                    return@withContext result.info
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

    suspend fun resolveAudioStream(videoId: String): String? =
        resolveAudioFormatInfo(videoId)?.url

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

            val plainUrl = audioOnly.filter {
                it["url"]?.jsonPrimitive?.content?.isNotBlank() == true
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
            val url = buildString {
                append(rawUrl)
                append("&cpn=").append(cpn)
            }

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
            val auth = YtMusicAuth.sapisidHashHeader(cookie, requestOrigin)
            if (auth != null) reqBuilder.header("Authorization", auth)
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
