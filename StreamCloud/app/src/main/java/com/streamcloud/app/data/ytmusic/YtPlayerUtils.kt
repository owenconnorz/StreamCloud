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
 *   1. ANDROID_MUSIC 7.27.52   — YTM-specific, best loudnessDb metadata.
 *      Returns plain Android-compatible URLs.  No bot detection.
 *   2. ANDROID 19.29.37        — Standard YouTube Android client.  Plain URLs,
 *      no cipher, no bot detection.
 *   3. ANDROID_TESTSUITE 1.9   — YouTube-internal test client (id=30).
 *      Whitelisted by YouTube: no PoToken required, not subject to bot
 *      detection quotas.  Primary fallback used by NewPipe Extractor.
 *   4. ANDROID_VR 1.61.48      — Oculus Quest 3 client.
 *   5. ANDROID_VR 1.43.32      — Older Oculus VR client.
 *   6. IOS 19.29.4              — iPhone client.  Resolves plain URLs but
 *      YouTube CDN enforces iOS-specific constraints.
 *   7. IPADOS 19.29.4           — iPad IOS variant.
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

        // ── 2. ANDROID 19.29.37 — standard YouTube Android client ────────
        // Plain URLs, no cipher, no bot-detection.
        ClientConfig(
            label         = "ANDROID",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID",
            clientId      = "3",
            clientVersion = "19.29.37",
            userAgent     = "com.google.android.youtube/19.29.37 (Linux; U; Android 11) gzip",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "11",
                "androidSdkVersion" to "30",
            ),
        ),

        // ── 3. ANDROID_TESTSUITE 1.9 — YouTube-whitelisted test client ───
        // id=30, clientVersion=1.9.  This is YouTube's own internal testing
        // client.  YouTube explicitly whitelists it: no PoToken required,
        // no bot-detection quota.  NewPipe Extractor uses this as its
        // primary client.  TV_EMBEDDED was removed — YouTube now returns
        // "YouTube is no longer supported in this application or device"
        // for that client on the vast majority of content.
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

        // ── 6. IOS 19.29.4 — iPhone client ───────────────────────────────
        // Resolves plain URLs; YouTube CDN sometimes enforces iOS-specific
        // TLS fingerprint constraints on Android fetches.
        ClientConfig(
            label         = "IOS",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "IOS",
            clientId      = "5",
            clientVersion = "19.29.4",
            userAgent     = "com.google.ios.youtube/19.29.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
            extraClientFields = mapOf(
                "deviceMake"  to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName"      to "iPhone",
                "osVersion"   to "17.5.1.21F90",
            ),
        ),

        // ── 7. IPADOS 19.29.4 — iPad IOS variant ────────────────────────
        ClientConfig(
            label         = "IPADOS",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "IOS",
            clientId      = "5",
            clientVersion = "19.29.4",
            userAgent     = "com.google.ios.youtube/19.29.4 (iPad7,6; U; CPU iPadOS 17_5_1 like Mac OS X; en-US)",
            extraClientFields = mapOf(
                "deviceMake"  to "Apple",
                "deviceModel" to "iPad7,6",
                "osName"      to "iPadOS",
                "osVersion"   to "17.5.1.21F90",
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

    /**
     * Content language (Innertube `hl`) and country/region (`gl`).
     * Kept in sync by MusicPlaybackService from SettingsRepository so every
     * Innertube player request honours the user's preference, just like Metrolist.
     */
    @Volatile var contentLanguage: String = "en"
    @Volatile var contentCountry:  String = "US"

    /**
     * Cached `visitorData` string from YouTube's visitor_id endpoint.
     * Sent as `context.client.visitorData` and `X-Goog-Visitor-Id` header.
     * Without this, IOS and ANDROID clients may receive throttled or
     * incomplete stream data on certain videos.
     */
    @Volatile private var cachedVisitorData: String? = null
    @Volatile private var visitorDataFetchedAt: Long = 0L

    /**
     * Fetch and cache a fresh `visitorData` from YouTube's lightweight
     * Innertube visitor_id endpoint.  Cached for 6 hours; no-op on failure.
     * Must be called from an IO thread.
     */
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
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                )
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
        ensureVisitorData()
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

        // Each Innertube endpoint lives on a specific host; SAPISIDHASH is
        // validated against the Origin that was used when signing in.  Sending
        // the wrong origin causes YouTube to reject the auth with HTTP 403.
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
                    // visitorData ties requests to a session, reducing bot-detection
                    // false-positives on IOS and ANDROID clients.
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

        // Forward visitorData as a request header too — some CDN edge nodes
        // read it from the header rather than the body.
        if (vd != null) reqBuilder.header("X-Goog-Visitor-Id", vd)

        // When the user is signed in, attach their session cookie and the
        // SAPISIDHASH Authorization header.  The hash must be computed with
        // the SAME origin as the endpoint being called (music.youtube.com vs
        // www.youtube.com) — a mismatch causes YouTube to return HTTP 403.
        // TV_EMBEDDED is a browser-embedded client; user cookies don't apply.
        val cookie = ytMusicCookie
        if (cookie.isNotBlank() && client.label != "TV_EMBEDDED") {
            reqBuilder.header("Cookie", cookie)
            reqBuilder.header("Origin", requestOrigin)
            val auth = YtMusicAuth.sapisidHashHeader(cookie, requestOrigin)
            if (auth != null) reqBuilder.header("Authorization", auth)
        }

        val request = reqBuilder.build()

        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLogger.w(TAG, "[${client.label}] $videoId — HTTP ${resp.code}")
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
