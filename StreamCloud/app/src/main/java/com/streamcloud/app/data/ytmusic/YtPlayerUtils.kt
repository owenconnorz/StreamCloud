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
 * Audio stream resolver — multi-client Innertube waterfall.
 *
 * Client priority (tuned for Android HTTP stack compatibility):
 *
 *   1. ANDROID_MUSIC 7.27.52 — YTM-specific, best loudnessDb metadata.
 *      Returns plain Android-compatible URLs.  No bot detection.
 *   2. ANDROID 21.03.38   — Standard YouTube Android client.  Plain URLs,
 *      no cipher, no bot detection, works with Android HttpURLConnection.
 *   3. TV_EMBEDDED        — TVHTML5_SIMPLY_EMBEDDED_PLAYER (id=85),
 *      bypass for age-restricted content.  embedUrl must be video-specific.
 *   4. ANDROID_VR 1.61.48 — Oculus Quest 3 client.  Gets bot-detection
 *      "Sign in to confirm…" without X-Goog-Visitor-Id / PoToken.
 *   5. ANDROID_VR 1.43.32 — Older Oculus VR client, same limitation.
 *   6. IOS 21.03.1        — iPhone client.  Resolves plain URLs but
 *      YouTube CDN enforces iOS-specific constraints (TLS fingerprint,
 *      alr=yes handling) that reject Android HttpURLConnection fetches.
 *   7. IPADOS 21.03.3     — iPad IOS variant, same CDN limitation as IOS.
 *
 * All clients return plain stream URLs without cipher when they succeed,
 * so no WebView-based cipher deobfuscation is required.
 */
object YtPlayerUtils {

    private const val TAG = "YtPlayerUtils"

    // ── Client descriptors ────────────────────────────────────────────────

    private data class ClientConfig(
        val label: String,
        /** Innertube player endpoint — use music.youtube.com for ANDROID_MUSIC. */
        val playerUrl: String,
        val clientName: String,
        /** Sent in X-YouTube-Client-Name header (numeric string). */
        val clientId: String,
        val clientVersion: String,
        val userAgent: String,
        /** Extra fields merged into context.client JSON object. */
        val extraClientFields: Map<String, Any> = emptyMap(),
        /**
         * When non-null, context.thirdParty { embedUrl } is added.
         * Use "%VIDEO_ID%" as placeholder — it is replaced with the actual
         * video ID at request time.  Required for TVHTML5_SIMPLY_EMBEDDED_PLAYER.
         */
        val embedUrlTemplate: String? = null,
    )

    private val CLIENTS = listOf(

        // ── 1. ANDROID_MUSIC 7.27.52 — best for YT Music, plain Android URLs
        // Returns plain stream URLs designed for Android HTTP stack.
        // No bot-detection issues.  Best loudnessDb metadata for music.
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

        // ── 2. ANDROID 21.03.38 — standard YouTube Android client ────────
        // Plain URLs, no cipher, no bot-detection.  Compatible with Android
        // HttpURLConnection / DefaultHttpDataSource.
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
        ),

        // ── 3. TV_EMBEDDED — age-restriction bypass ───────────────────────
        // TVHTML5_SIMPLY_EMBEDDED_PLAYER (id=85).
        // embedUrl must be video-specific: watch?v=<videoId>.
        ClientConfig(
            label            = "TV_EMBEDDED",
            playerUrl        = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName       = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientId         = "85",
            clientVersion    = "2.0",
            userAgent        = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            embedUrlTemplate = "https://www.youtube.com/watch?v=%VIDEO_ID%",
        ),

        // ── 4. ANDROID_VR 1.61.48 — Oculus Quest 3 ───────────────────────
        // Gets "Sign in to confirm you're not a bot" without PoToken /
        // X-Goog-Visitor-Id.  Kept as fallback in case bot-detection eases.
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
        ),

        // ── 5. ANDROID_VR 1.43.32 — older Oculus VR client ──────────────
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
        ),

        // ── 6. IOS 21.03.1 — iPhone client ───────────────────────────────
        // Resolves plain URLs but YouTube CDN enforces iOS-specific
        // constraints (TLS fingerprint, alr=yes handling) that cause HTTP 403
        // when bytes are fetched via Android's HttpURLConnection.
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
        ),

        // ── 7. IPADOS 21.03.3 — iPad IOS variant — same CDN limitation ───
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
        ),
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Raw `Cookie:` header from music.youtube.com — set by [MusicPlaybackService]
     * when the user is signed in.  When present, it is sent with every Innertube
     * player request so that ANDROID_MUSIC / ANDROID clients resolve URLs as an
     * authenticated user, bypassing YouTube's bot-detection quota that blocks
     * anonymous requests from those clients.
     */
    @Volatile var ytMusicCookie: String = ""

    // ── Public data classes ───────────────────────────────────────────────

    data class AudioFormatInfo(
        val url: String,
        /** User-Agent of the client that produced this URL.
         *  The stream request MUST use this exact UA — YouTube CDN binds
         *  stream URLs to the client that requested them. */
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
                    AppLogger.w(TAG, "[${client.label}] $videoId — all streams ciphered, trying next client")
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
    ): ClientResult {
        return try {
            val root = fetchPlayerResponse(client, videoId)
                ?: return ClientResult.Error(null)

            val playabilityStatus = root["playabilityStatus"]?.jsonObject
            val playabilityReason = playabilityStatus?.get("reason")?.jsonPrimitive?.content

            val streamingData = root["streamingData"]?.jsonObject
                ?: return ClientResult.NoStreams(playabilityReason)

            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray
                ?: return ClientResult.NoStreams(playabilityReason)

            val audioOnly = adaptiveFormats
                .mapNotNull { it as? JsonObject }
                .filter { it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/") }

            if (audioOnly.isEmpty()) return ClientResult.NoStreams(playabilityReason)

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
                    userAgent        = client.userAgent,
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
    }

    private fun fetchPlayerResponse(client: ClientConfig, videoId: String): JsonObject? {
        val resolvedEmbedUrl = client.embedUrlTemplate?.replace("%VIDEO_ID%", videoId)

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
                // TVHTML5_SIMPLY_EMBEDDED_PLAYER requires a video-specific thirdParty.embedUrl
                // to signal it is running inside an embedded iframe — this bypasses PoToken.
                resolvedEmbedUrl?.let { embedUrl ->
                    putJsonObject("thirdParty") {
                        put("embedUrl", embedUrl)
                    }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
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

        // When the user is signed in, attach their session cookie and the
        // SAPISIDHASH Authorization header.  This is required for ANDROID_MUSIC
        // and ANDROID clients to bypass YouTube's bot-detection for anonymous
        // requests; signed-in requests are trusted and return plain stream URLs.
        val cookie = ytMusicCookie
        if (cookie.isNotBlank()) {
            reqBuilder.header("Cookie", cookie)
            reqBuilder.header("Origin", YtMusicAuth.ORIGIN)
            val auth = YtMusicAuth.sapisidHashHeader(cookie)
            if (auth != null) reqBuilder.header("Authorization", auth)
        }

        val request = reqBuilder.build()

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
