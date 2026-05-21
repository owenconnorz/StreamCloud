package com.streamcloud.app.data.ytmusic

import android.util.Log
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
 * Fast audio stream resolver — mirrors Metrolist/InnerTune's YTPlayerUtils.
 *
 * Instead of the NewPipe path (download watch-page HTML + player JS → regex-parse
 * 2–4 MB → deobfuscate nsig → 3–8 s per track), we POST to the YouTube Innertube
 * /player endpoint using the **ANDROID_MUSIC** client.  That client ID causes
 * YouTube to return pre-deciphered stream URLs in a plain JSON response, so the
 * whole operation is a single round-trip — typically 300–800 ms.
 *
 * [resolveAudioFormatInfo] is the primary entry point — it returns full format
 * metadata (url, itag, mimeType, bitrate, sampleRate, contentLength, loudnessDb,
 * expiresInSeconds), matching exactly what InnerTune stores in its FormatEntity.
 *
 * The download resolver appends `&range=0-{contentLength}` to the CDN URL —
 * the same trick InnerTune/Metrolist uses — which instructs YouTube's CDN to
 * deliver the entire file in one response at full network speed instead of using
 * its default bandwidth-throttled chunked delivery.
 *
 * Backward-compat wrappers:
 *  - [resolveAudioStreamInfo]  → (url, contentLength) pair used by older call sites
 *  - [resolveAudioStream]      → url-only string used by MusicPlaybackService
 */
object YtPlayerUtils {

    private const val TAG = "YtPlayerUtils"
    private const val PLAYER_URL =
        "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val CLIENT_VERSION = "7.27.52"
    private const val ANDROID_SDK_VERSION = 30

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Public data classes ───────────────────────────────────────────────

    /**
     * Full format metadata returned by the Innertube player endpoint.
     *
     * Mirrors InnerTune's [com.zionhuang.music.db.entities.FormatEntity] so that
     * our download resolver can store exactly the same fields in Room.
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
     * Resolve the best audio-only stream for [videoId] via a single Innertube
     * POST, respecting the caller's quality preference and any previously stored
     * [preferItag].
     *
     * Quality selection (mirrors InnerTune):
     *  - If [preferItag] is non-null, YouTube is asked for that exact format
     *    first (so users always get the codec they've heard before). Falls back
     *    to quality-based selection if the itag is no longer available.
     *  - [preferHighQuality] = true  → highest bitrate, opus preferred (+10 kbps bonus)
     *  - [preferHighQuality] = false → lowest bitrate (metered / "Low" setting)
     *
     * Returns null on any error; callers must fall back to NewPipe.
     */
    suspend fun resolveAudioFormatInfo(
        videoId: String,
        preferItag: Int? = null,
        preferHighQuality: Boolean = true,
    ): AudioFormatInfo? = withContext(Dispatchers.IO) {
        try {
            val root = fetchPlayerResponse(videoId) ?: return@withContext null
            pickBestAudioInfo(root, preferItag, preferHighQuality).also { info ->
                if (info == null) Log.d(TAG, "no audio stream found for $videoId")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Innertube player failed for $videoId: ${e.message}")
            null
        }
    }

    /**
     * Backward-compat wrapper — returns (url, contentLength) for callers that
     * don't need the full format metadata.
     */
    suspend fun resolveAudioStreamInfo(videoId: String): AudioStreamInfo? =
        resolveAudioFormatInfo(videoId)?.let { AudioStreamInfo(it.url, it.contentLength) }

    /**
     * URL-only backward-compat wrapper used by [com.streamcloud.app.audio.MusicPlaybackService].
     * Playback doesn't use the `&range=` trick (ExoPlayer issues its own ranged
     * requests during seeking), so only the URL is needed there.
     */
    suspend fun resolveAudioStream(videoId: String): String? =
        resolveAudioFormatInfo(videoId)?.url

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun fetchPlayerResponse(videoId: String): JsonObject? {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID_MUSIC")
                    put("clientVersion", CLIENT_VERSION)
                    put("androidSdkVersion", ANDROID_SDK_VERSION)
                    put(
                        "userAgent",
                        "com.google.android.apps.youtube.music/$CLIENT_VERSION " +
                            "(Linux; U; Android 11) gzip",
                    )
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(PLAYER_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header(
                "User-Agent",
                "com.google.android.apps.youtube.music/$CLIENT_VERSION " +
                    "(Linux; U; Android 11) gzip",
            )
            .header("X-Goog-Api-Format-Version", "1")
            .header("Content-Type", "application/json")
            .build()

        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.d(TAG, "player HTTP ${resp.code} for $videoId")
                return null
            }
            val text = resp.body?.string() ?: return null
            json.parseToJsonElement(text).jsonObject
        }
    }

    /**
     * Pick the best audio-only adaptive format from the player response.
     *
     * Preference order (mirrors InnerTune/Metrolist exactly):
     *  1. If [preferItag] is given and still available, use that format.
     *  2. Otherwise rank by:
     *       score = bitrate × quality_sign + (10240 if opus/webm else 0)
     *     where quality_sign = +1 (high quality) or −1 (low/data-saving).
     *     The 10 240 bonus gives opus streams a ~80 kbps advantage over m4a at
     *     the same bitrate, matching InnerTune's codec preference.
     */
    private fun pickBestAudioInfo(
        root: JsonObject,
        preferItag: Int?,
        preferHighQuality: Boolean,
    ): AudioFormatInfo? {
        val streamingData = root["streamingData"]?.jsonObject ?: return null
        val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray ?: return null
        val expiresInSeconds =
            streamingData["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 21_600L

        val audioOnly = adaptiveFormats
            .mapNotNull { it as? JsonObject }
            .filter { it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/") }

        if (audioOnly.isEmpty()) return null

        val best = if (preferItag != null) {
            audioOnly.find {
                it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == preferItag
            } ?: selectByQuality(audioOnly, preferHighQuality)
        } else {
            selectByQuality(audioOnly, preferHighQuality)
        }

        val url = best["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null

        val loudnessDb = root["playerConfig"]
            ?.jsonObject?.get("audioConfig")
            ?.jsonObject?.get("loudnessDb")
            ?.jsonPrimitive?.content?.toDoubleOrNull()

        return AudioFormatInfo(
            url = url,
            itag = best["itag"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            mimeType = best["mimeType"]?.jsonPrimitive?.content.orEmpty(),
            bitrate = best["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            sampleRate = best["audioSampleRate"]?.jsonPrimitive?.content?.toIntOrNull(),
            contentLength = best["contentLength"]?.jsonPrimitive?.content?.toLongOrNull(),
            loudnessDb = loudnessDb,
            expiresInSeconds = expiresInSeconds,
        )
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
            val isOpus = fmt["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/webm")
            val sign = if (preferHighQuality) 1L else -1L
            bitrate * sign + (if (isOpus) 10_240L else 0L)
        } ?: audioFormats.first()
    }
}
