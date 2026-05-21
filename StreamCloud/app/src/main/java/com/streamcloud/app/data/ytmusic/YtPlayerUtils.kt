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
 * [resolveAudioStreamInfo] returns the URL **and** the declared `contentLength` from
 * the player response.  The download path appends `&range=0-{contentLength}` to the
 * CDN URL — the same trick InnerTune uses — which instructs YouTube's CDN to deliver
 * the entire audio file in one response instead of using its default internal chunking
 * with bandwidth throttling.  Without this parameter downloads are throttled to a
 * fraction of available bandwidth; with it they run at full line speed.
 *
 * [resolveAudioStream] is kept for backward-compat callers that only need the URL
 * (e.g. the playback resolver in MusicPlaybackService).
 *
 * Returns **null** on any error; callers should fall back to NewPipe in that case.
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

    /** Full result of stream resolution — URL and the CDN-declared byte count. */
    data class AudioStreamInfo(
        val url: String,
        /** Declared content length in bytes, or null if not present in the response. */
        val contentLength: Long?,
    )

    /**
     * Resolve the best audio-only stream URL **and** its content length for [videoId]
     * via a single Innertube POST.
     *
     * The content length is used by the download resolver to append `&range=0-N` to
     * the CDN URL, forcing full-speed delivery instead of YouTube's throttled chunked
     * streaming.  See [resolveAudioStream] for the URL-only convenience wrapper.
     *
     * Returns null if the request fails or no audio stream is found.
     */
    suspend fun resolveAudioStreamInfo(videoId: String): AudioStreamInfo? =
        withContext(Dispatchers.IO) {
            try {
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

                val root = http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(TAG, "player HTTP ${resp.code} for $videoId")
                        return@withContext null
                    }
                    val text = resp.body?.string() ?: return@withContext null
                    json.parseToJsonElement(text).jsonObject
                }

                pickBestAudioInfo(root).also { info ->
                    if (info == null) Log.d(TAG, "no audio stream found for $videoId")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Innertube player failed for $videoId: ${e.message}")
                null
            }
        }

    /**
     * URL-only convenience wrapper around [resolveAudioStreamInfo].
     * Used by [com.streamcloud.app.audio.MusicPlaybackService] which does not need
     * the content length (playback streams don't use the `&range=` trick).
     */
    suspend fun resolveAudioStream(videoId: String): String? =
        resolveAudioStreamInfo(videoId)?.url

    /**
     * Pick the highest-bitrate audio-only format from [root] and return both its URL
     * and its declared `contentLength`.
     *
     * Preference order:
     *  1. M4A (itag 140 / 141) — widest hardware decoder support on Android
     *  2. Any other audio-only format at the highest available bitrate
     *
     * The ANDROID_MUSIC client returns plain `url` fields (no signatureCipher),
     * so no deobfuscation step is needed.
     */
    private fun pickBestAudioInfo(root: JsonObject): AudioStreamInfo? {
        val adaptiveFormats = root["streamingData"]
            ?.jsonObject?.get("adaptiveFormats")
            ?.jsonArray ?: return null

        val audioOnly = adaptiveFormats
            .mapNotNull { it as? JsonObject }
            .filter { fmt ->
                fmt["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/")
            }

        if (audioOnly.isEmpty()) return null

        val m4a = audioOnly.filter { fmt ->
            val mime = fmt["mimeType"]?.jsonPrimitive?.content.orEmpty()
            mime.contains("mp4") || mime.contains("m4a")
        }
        val pool = if (m4a.isNotEmpty()) m4a else audioOnly

        val best = pool.maxByOrNull { fmt ->
            fmt["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        } ?: return null

        val url = best["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val contentLength = best["contentLength"]?.jsonPrimitive?.content?.toLongOrNull()

        return AudioStreamInfo(url = url, contentLength = contentLength)
    }
}
