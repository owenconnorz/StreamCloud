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
 * Tries three YouTube Innertube clients in priority order until one returns
 * pre-signed (un-ciphered) audio stream URLs:
 *
 *   1. ANDROID (client id 3) — youtube.com endpoint, historically the most
 *      reliable for pre-signed URLs without po_token / nsig decoding.
 *   2. ANDROID_MUSIC (client id 21) — music.youtube.com endpoint, best for
 *      YT-Music–specific metadata such as loudnessDb.
 *   3. TVHTML5_SIMPLY_EMBEDDED_PLAYER (client id 85) — embedded player, usually
 *      skips cipher requirements, useful as a last-resort Innertube path.
 *
 * "Pre-signed" means the adaptive format object carries a top-level `url` field.
 * Ciphered formats carry `signatureCipher` / `cipher` instead — those require
 * JavaScript nsig deobfuscation which we do not perform here; if every client
 * returns only cipher formats we return null so MusicPlaybackService can fall
 * back to NewPipe.
 */
object YtPlayerUtils {

    private const val TAG = "YtPlayerUtils"
    private const val ANDROID_SDK_VERSION = 30

    // ── Innertube client descriptors ─────────────────────────────────────

    private data class ClientConfig(
        /** Human-readable label for logging. */
        val label: String,
        val playerUrl: String,
        val clientName: String,
        /** Numeric string sent in X-YouTube-Client-Name header. */
        val clientId: String,
        val clientVersion: String,
        val userAgent: String,
        /** Extra fields injected into context.client JSON (e.g. androidSdkVersion). */
        val extraClientFields: Map<String, Any> = emptyMap(),
    )

    private val CLIENTS = listOf(
        // ── 1. ANDROID — www.youtube.com, most reliable for pre-signed URLs ──
        ClientConfig(
            label         = "ANDROID",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID",
            clientId      = "3",
            clientVersion = "19.44.38",
            userAgent     = "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
            extraClientFields = mapOf("androidSdkVersion" to ANDROID_SDK_VERSION),
        ),
        // ── 2. ANDROID_MUSIC — music.youtube.com, best for YTM-specific metadata ──
        ClientConfig(
            label         = "ANDROID_MUSIC",
            playerUrl     = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_MUSIC",
            clientId      = "21",
            clientVersion = "7.27.52",
            userAgent     = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip",
            extraClientFields = mapOf("androidSdkVersion" to ANDROID_SDK_VERSION),
        ),
        // ── 3. TVHTML5_SIMPLY_EMBEDDED_PLAYER — embedded TV player, bypasses cipher ──
        ClientConfig(
            label         = "TV_EMBEDDED",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientId      = "85",
            clientVersion = "2.0",
            userAgent     = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
        ),
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Public data classes ───────────────────────────────────────────────

    /**
     * Full format metadata returned by the Innertube player endpoint.
     *
     * Mirrors InnerTune's FormatEntity so that our download resolver can
     * store exactly the same fields in Room.
     */
    data class AudioFormatInfo(
        val url: String,
        /** YouTube adaptive format itag (e.g. 140 = m4a 128 kbps, 251 = opus 160 kbps). */
        val itag: Int,
        /** Full MIME type string, e.g. `audio/mp4; codecs="mp4a.40.2"`. */
        val mimeType: String,
        val bitrate: Long,
        val sampleRate: Int?,
        /** Declared content length in bytes — used to append `&range=0-N`. */
        val contentLength: Long?,
        /** Loudness normalisation offset in dB from playerConfig.audioConfig. */
        val loudnessDb: Double?,
        /**
         * Seconds until the CDN URL expires (typically 21600 = 6 h).
         * Used as the URL-cache TTL so we re-resolve only when the URL is
         * actually stale rather than on a fixed 3-hour schedule.
         */
        val expiresInSeconds: Long,
    )

    /** Lightweight wrapper for callers that only need url + contentLength. */
    data class AudioStreamInfo(
        val url: String,
        val contentLength: Long?,
    )

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Resolve the best audio-only stream for [videoId] by trying each Innertube
     * client in [CLIENTS] order.  Skips clients that return only ciphered formats.
     * Returns null if all clients fail so callers can fall back to NewPipe.
     */
    suspend fun resolveAudioFormatInfo(
        videoId: String,
        preferItag: Int? = null,
        preferHighQuality: Boolean = true,
    ): AudioFormatInfo? = withContext(Dispatchers.IO) {
        for (client in CLIENTS) {
            val result = tryClient(client, videoId, preferItag, preferHighQuality)
            when (result) {
                is ClientResult.Success -> {
                    AppLogger.i(TAG, "[${ client.label}] resolved $videoId itag=${result.info.itag}")
                    return@withContext result.info
                }
                is ClientResult.CipheredOnly -> {
                    AppLogger.w(TAG, "[${client.label}] $videoId returned only ciphered streams — trying next client")
                    Log.d(TAG, "[${client.label}] $videoId ciphered-only, trying next")
                }
                is ClientResult.NoStreams -> {
                    AppLogger.w(TAG, "[${client.label}] $videoId returned no audio streams — trying next client")
                    Log.d(TAG, "[${client.label}] $videoId no streams, trying next")
                }
                is ClientResult.Error -> {
                    AppLogger.w(TAG, "[${client.label}] $videoId failed: ${result.cause?.message}")
                    Log.d(TAG, "[${client.label}] $videoId error: ${result.cause?.message}")
                }
            }
        }
        AppLogger.e(TAG, "All Innertube clients failed for $videoId — NewPipe fallback will be used")
        null
    }

    /** Backward-compat wrapper — returns (url, contentLength). */
    suspend fun resolveAudioStreamInfo(videoId: String): AudioStreamInfo? =
        resolveAudioFormatInfo(videoId)?.let { AudioStreamInfo(it.url, it.contentLength) }

    /** URL-only backward-compat wrapper used by MusicPlaybackService. */
    suspend fun resolveAudioStream(videoId: String): String? =
        resolveAudioFormatInfo(videoId)?.url

    // ── Internal helpers ──────────────────────────────────────────────────

    private sealed interface ClientResult {
        data class Success(val info: AudioFormatInfo) : ClientResult
        /** Server returned formats but all have signatureCipher / cipher instead of url. */
        data object CipheredOnly : ClientResult
        /** Server returned a valid response but no audio-only adaptive formats at all. */
        data object NoStreams : ClientResult
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

            val streamingData = root["streamingData"]?.jsonObject
            if (streamingData == null) {
                val reason = root["playabilityStatus"]?.jsonObject
                    ?.get("reason")?.jsonPrimitive?.content
                Log.d(TAG, "[${client.label}] no streamingData for $videoId, reason=$reason")
                return ClientResult.NoStreams
            }

            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray
            if (adaptiveFormats == null) {
                return ClientResult.NoStreams
            }

            val audioOnly = adaptiveFormats
                .mapNotNull { it as? JsonObject }
                .filter { it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/") }

            if (audioOnly.isEmpty()) return ClientResult.NoStreams

            // Check if all returned formats are ciphered (no plain `url` field).
            val anyPlainUrl = audioOnly.any { fmt ->
                fmt["url"]?.jsonPrimitive?.content?.isNotBlank() == true
            }
            if (!anyPlainUrl) return ClientResult.CipheredOnly

            val best = if (preferItag != null) {
                audioOnly.find {
                    it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == preferItag
                            && it["url"]?.jsonPrimitive?.content?.isNotBlank() == true
                } ?: selectByQuality(audioOnly.filter {
                    it["url"]?.jsonPrimitive?.content?.isNotBlank() == true
                }, preferHighQuality)
            } else {
                selectByQuality(audioOnly.filter {
                    it["url"]?.jsonPrimitive?.content?.isNotBlank() == true
                }, preferHighQuality)
            }

            val url = best["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return ClientResult.CipheredOnly

            val expiresInSeconds =
                streamingData["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 21_600L
            val loudnessDb = root["playerConfig"]
                ?.jsonObject?.get("audioConfig")
                ?.jsonObject?.get("loudnessDb")
                ?.jsonPrimitive?.content?.toDoubleOrNull()

            ClientResult.Success(
                AudioFormatInfo(
                    url             = url,
                    itag            = best["itag"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    mimeType        = best["mimeType"]?.jsonPrimitive?.content.orEmpty(),
                    bitrate         = best["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    sampleRate      = best["audioSampleRate"]?.jsonPrimitive?.content?.toIntOrNull(),
                    contentLength   = best["contentLength"]?.jsonPrimitive?.content?.toLongOrNull(),
                    loudnessDb      = loudnessDb,
                    expiresInSeconds = expiresInSeconds,
                )
            )
        } catch (e: Exception) {
            ClientResult.Error(e)
        }
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
                            is Int    -> put(k, v)
                            is Long   -> put(k, v)
                            is Boolean-> put(k, v)
                            else      -> put(k, v.toString())
                        }
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

    /**
     * Rank audio formats by quality score — identical to InnerTune's comparator.
     *
     * HIGH: max score  → highest bitrate, opus preferred
     * LOW:  min score  → lowest bitrate (data saving)
     */
    private fun selectByQuality(
        audioFormats: List<JsonObject>,
        preferHighQuality: Boolean,
    ): JsonObject {
        return audioFormats.maxByOrNull { fmt ->
            val bitrate = fmt["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val isOpus  = fmt["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/webm")
            val sign    = if (preferHighQuality) 1L else -1L
            bitrate * sign + (if (isOpus) 10_240L else 0L)
        } ?: audioFormats.first()
    }
}
