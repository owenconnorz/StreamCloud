package com.streamcloud.app.data.ytmusic

import android.util.Log
import com.streamcloud.app.data.AppLogger
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Fast audio stream resolver — multi-client Innertube approach.
 *
 * YouTube's bot-detection (PoToken) causes many clients to receive either ciphered
 * stream URLs (signatureCipher instead of url) or empty adaptiveFormats.  We try
 * four clients in priority order, stopping at the first that returns a plain,
 * un-ciphered audio stream URL:
 *
 *   1. IOS (client id 5) — historically the most reliable client that bypasses
 *      PoToken requirements and returns pre-signed URLs for all content.
 *   2. TVHTML5_SIMPLY_EMBEDDED_PLAYER (client id 85) — embedded TV player with
 *      thirdParty.embedUrl context; designed for third-party embedding and exempt
 *      from PoToken requirements.
 *   3. ANDROID (client id 3) — reliable for most content, some videos now require
 *      PoToken since mid-2024 which may cause cipher-only responses.
 *   4. ANDROID_MUSIC (client id 21) — YouTube Music specific, best for YTM
 *      metadata (loudnessDb), kept as last-resort Innertube attempt.
 *
 * If every client returns only cipher formats or no audio, null is returned and
 * MusicPlaybackService falls back to NewPipe.
 */
object YtPlayerUtils {

    private const val TAG = "YtPlayerUtils"

    // ── Innertube client descriptors ─────────────────────────────────────

    private data class ClientConfig(
        /** Human-readable label for logging / AppLogger. */
        val label: String,
        val playerUrl: String,
        val clientName: String,
        /** Numeric string sent in X-YouTube-Client-Name header. */
        val clientId: String,
        val clientVersion: String,
        val userAgent: String,
        /** Additional fields injected into the JSON context.client object. */
        val extraClientFields: Map<String, Any> = emptyMap(),
        /**
         * If non-null, a `thirdParty { embedUrl }` object is added to the
         * Innertube context.  Required for TVHTML5_SIMPLY_EMBEDDED_PLAYER to
         * correctly bypass PoToken restrictions.
         */
        val embedUrl: String? = null,
    )

    private val CLIENTS = listOf(
        // ── 1. IOS — most reliable PoToken bypass, pre-signed URLs ───────────
        ClientConfig(
            label         = "IOS",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "IOS",
            clientId      = "5",
            clientVersion = "19.45.4",
            userAgent     = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X)",
            extraClientFields = mapOf(
                "deviceMake"  to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName"      to "iPhone",
                "osVersion"   to "18.1.0.22B83",
            ),
        ),
        // ── 2. TVHTML5_SIMPLY_EMBEDDED_PLAYER — embedded, PoToken-exempt ─────
        ClientConfig(
            label         = "TV_EMBEDDED",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientId      = "85",
            clientVersion = "2.0",
            userAgent     = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
            embedUrl      = "https://www.youtube.com/",
        ),
        // ── 3. ANDROID — good general coverage ───────────────────────────────
        ClientConfig(
            label         = "ANDROID",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID",
            clientId      = "3",
            clientVersion = "19.44.38",
            userAgent     = "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
            extraClientFields = mapOf("androidSdkVersion" to 30),
        ),
        // ── 4. ANDROID_MUSIC — YTM-specific metadata fallback ────────────────
        ClientConfig(
            label         = "ANDROID_MUSIC",
            playerUrl     = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_MUSIC",
            clientId      = "21",
            clientVersion = "7.27.52",
            userAgent     = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip",
            extraClientFields = mapOf("androidSdkVersion" to 30),
        ),
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Public data classes ───────────────────────────────────────────────

    data class AudioFormatInfo(
        val url: String,
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

    // ── Public API ────────────────────────────────────────────────────────

    suspend fun resolveAudioFormatInfo(
        videoId: String,
        preferItag: Int? = null,
        preferHighQuality: Boolean = true,
    ): AudioFormatInfo? = withContext(Dispatchers.IO) {
        for (client in CLIENTS) {
            val result = tryClient(client, videoId, preferItag, preferHighQuality)
            when (result) {
                is ClientResult.Success -> {
                    AppLogger.i(TAG, "[${client.label}] resolved $videoId → itag=${result.info.itag} ${result.info.mimeType.substringBefore(';')}")
                    return@withContext result.info
                }
                is ClientResult.CipheredOnly -> {
                    AppLogger.w(TAG, "[${client.label}] $videoId — all streams ciphered (PoToken required), trying next client")
                    Log.d(TAG, "[${client.label}] $videoId ciphered-only")
                }
                is ClientResult.NoStreams -> {
                    val why = result.reason?.let { " (reason: $it)" } ?: ""
                    AppLogger.w(TAG, "[${client.label}] $videoId — no audio streams$why, trying next client")
                    Log.d(TAG, "[${client.label}] $videoId no streams$why")
                }
                is ClientResult.Error -> {
                    AppLogger.w(TAG, "[${client.label}] $videoId — request failed: ${result.cause?.message}")
                    Log.d(TAG, "[${client.label}] $videoId error", result.cause)
                }
            }
        }
        AppLogger.e(TAG, "All Innertube clients failed for $videoId — NewPipe fallback will be used")
        null
    }

    suspend fun resolveAudioStreamInfo(videoId: String): AudioStreamInfo? =
        resolveAudioFormatInfo(videoId)?.let { AudioStreamInfo(it.url, it.contentLength) }

    suspend fun resolveAudioStream(videoId: String): String? =
        resolveAudioFormatInfo(videoId)?.url

    // ── Internal ──────────────────────────────────────────────────────────

    private sealed interface ClientResult {
        data class Success(val info: AudioFormatInfo) : ClientResult
        data object CipheredOnly : ClientResult
        data class NoStreams(val reason: String? = null) : ClientResult
        data class Error(val cause: Throwable?) : ClientResult
    }

    private fun tryClient(
        client: ClientConfig,
        videoId: String,
        preferItag: Int?,
        preferHighQuality: Boolean,
    ): ClientResult = try {
        val root = fetchPlayerResponse(client, videoId)
            ?: return ClientResult.Error(null)

        val playabilityStatus = root["playabilityStatus"]?.jsonObject
        val playabilityReason = playabilityStatus?.get("reason")?.jsonPrimitive?.content

        val streamingData = root["streamingData"]?.jsonObject
        if (streamingData == null) {
            return ClientResult.NoStreams(playabilityReason)
        }

        val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray
        if (adaptiveFormats == null) {
            return ClientResult.NoStreams(playabilityReason)
        }

        val audioOnly = adaptiveFormats
            .mapNotNull { it as? JsonObject }
            .filter { it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/") }

        if (audioOnly.isEmpty()) return ClientResult.NoStreams(playabilityReason)

        // Filter to formats that have a plain url (not signatureCipher).
        val plainUrl = audioOnly.filter {
            it["url"]?.jsonPrimitive?.content?.isNotBlank() == true
        }
        if (plainUrl.isEmpty()) return ClientResult.CipheredOnly

        val expiresInSeconds =
            streamingData["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 21_600L

        val best = if (preferItag != null) {
            plainUrl.find { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == preferItag }
                ?: selectByQuality(plainUrl, preferHighQuality)
        } else {
            selectByQuality(plainUrl, preferHighQuality)
        }

        val url = best["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ClientResult.CipheredOnly

        val loudnessDb = root["playerConfig"]
            ?.jsonObject?.get("audioConfig")
            ?.jsonObject?.get("loudnessDb")
            ?.jsonPrimitive?.content?.toDoubleOrNull()

        ClientResult.Success(
            AudioFormatInfo(
                url              = url,
                itag             = best["itag"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                mimeType         = best["mimeType"]?.jsonPrimitive?.content.orEmpty(),
                bitrate          = best["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                sampleRate       = best["audioSampleRate"]?.jsonPrimitive?.content?.toIntOrNull(),
                contentLength    = best["contentLength"]?.jsonPrimitive?.content?.toLongOrNull(),
                loudnessDb       = loudnessDb,
                expiresInSeconds = expiresInSeconds,
            )
        )
    } catch (e: Exception) {
        ClientResult.Error(e)
    }

    private fun fetchPlayerResponse(client: ClientConfig, videoId: String): JsonObject? {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.clientName)
                    put("clientVersion", client.clientVersion)
                    put("userAgent", client.userAgent)
                    put("hl", "en")
                    put("gl", "US")
                    client.extraClientFields.forEach { (k, v) ->
                        when (v) {
                            is Int     -> put(k, v)
                            is Long    -> put(k, v)
                            is Boolean -> put(k, v)
                            else       -> put(k, v.toString())
                        }
                    }
                }
                // TVHTML5_SIMPLY_EMBEDDED_PLAYER requires thirdParty.embedUrl to
                // signal it's running inside an iframe, which bypasses PoToken.
                client.embedUrl?.let { embedUrl ->
                    putJsonObject("thirdParty") {
                        put("embedUrl", embedUrl)
                    }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(client.playerUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientId)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .header("X-Goog-Api-Format-Version", "1")
            .header("Content-Type", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.d(TAG, "[${client.label}] HTTP ${resp.code} for $videoId")
                return null
            }
            val text = resp.body?.string() ?: return null
            json.parseToJsonElement(text).jsonObject
        }
    }

    private fun selectByQuality(
        audioFormats: List<JsonObject>,
        preferHighQuality: Boolean,
    ): JsonObject = audioFormats.maxByOrNull { fmt ->
        val bitrate = fmt["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val isOpus  = fmt["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/webm")
        val sign    = if (preferHighQuality) 1L else -1L
        bitrate * sign + (if (isOpus) 10_240L else 0L)
    } ?: audioFormats.first()
}
