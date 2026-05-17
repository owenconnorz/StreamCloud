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
 * Fast audio stream resolver — mirrors Metrolist's YTPlayerUtils.
 *
 * Instead of the NewPipe path (download watch-page HTML + player JS → regex-parse
 * 2–4 MB → deobfuscate nsig → 3–8 s per track), we POST to the YouTube Innertube
 * /player endpoint using the **ANDROID_MUSIC** client.  That client ID causes
 * YouTube to return pre-deciphered stream URLs in a plain JSON response, so the
 * whole operation is a single round-trip — typically 300–800 ms.
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

    /**
     * Resolve the best audio-only stream URL for [videoId] via a single Innertube POST.
     * Returns null if the request fails or no audio stream is found; caller should
     * fall back to NewPipe.
     */
    suspend fun resolveAudioStream(videoId: String): String? = withContext(Dispatchers.IO) {
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

            pickBestAudio(root).also { url ->
                if (url == null) Log.d(TAG, "no audio stream found for $videoId")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Innertube player failed for $videoId: ${e.message}")
            null
        }
    }

    /**
     * Pick the highest-bitrate audio-only format from [root].
     *
     * Preference order:
     *  1. M4A (itag 140 / 141) — widest hardware decoder support on Android
     *  2. Any other audio-only format at the highest available bitrate
     *
     * The ANDROID_MUSIC client returns plain `url` fields (no signatureCipher),
     * so no deobfuscation step is needed.
     */
    private fun pickBestAudio(root: JsonObject): String? {
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

        return best["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }
}
