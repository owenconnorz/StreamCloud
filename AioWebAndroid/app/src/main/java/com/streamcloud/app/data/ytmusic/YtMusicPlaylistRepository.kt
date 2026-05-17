package com.streamcloud.app.data.ytmusic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 * High-level wrapper for the YouTube Music playlist mutation endpoints that
 * the InnerTube web client uses internally:
 *
 *   - `POST /youtubei/v1/playlist/create`           — create new playlist
 *   - `POST /youtubei/v1/browse/edit_playlist`      — add / remove videos
 *
 * Authenticated calls — the user **must** be signed in (cookie present)
 * for either endpoint to return success. Anonymous calls return 401.
 *
 * Discovered via the same approach as Metrolist / OpenTune: trace the
 * music.youtube.com web app's network panel while creating a playlist
 * → mirror the request body + headers verbatim.
 */
object YtMusicPlaylistRepository {

    private const val TAG = "YtmPlaylistRepo"
    private const val CLIENT_VERSION = "1.20250127.01.00"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Add a single videoId to an existing user playlist. The playlist id is
     * the same one Library tab surfaces (sans `VL` prefix). Returns true on
     * success — false on auth/network/playlist-not-found errors.
     */
    suspend fun addVideoToPlaylist(
        cookie: String,
        playlistId: String,
        videoId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (cookie.isBlank() || videoId.isBlank()) return@withContext false
        val cleanPlaylistId = playlistId.removePrefix("VL")
        val body = buildJsonObject {
            putContext()
            put("playlistId", cleanPlaylistId)
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("action", "ACTION_ADD_VIDEO")
                    put("addedVideoId", videoId)
                })
            })
        }
        val resp = postInnerTube(cookie, "browse/edit_playlist", body) ?: return@withContext false
        val status = (resp["status"] as? JsonPrimitive)?.contentOrNull
        val ok = status == "STATUS_SUCCEEDED"
        if (!ok) Log.w(TAG, "addVideoToPlaylist status=$status resp=${resp.toString().take(160)}")
        ok
    }

    /**
     * Create a new playlist (PRIVATE by default, mirroring the YT Music web
     * "+ New playlist" button). Optionally seed it with [seedVideoId].
     * Returns the new playlist id, or null on failure.
     */
    suspend fun createPlaylist(
        cookie: String,
        title: String,
        seedVideoId: String? = null,
        privacy: Privacy = Privacy.PRIVATE,
    ): String? = withContext(Dispatchers.IO) {
        if (cookie.isBlank() || title.isBlank()) return@withContext null
        val body = buildJsonObject {
            putContext()
            put("title", title)
            put("privacyStatus", privacy.wire)
            if (!seedVideoId.isNullOrBlank()) {
                put("videoIds", buildJsonArray { add(JsonPrimitive(seedVideoId)) })
            }
        }
        val resp = postInnerTube(cookie, "playlist/create", body) ?: return@withContext null
        // YT returns the new id at top-level `playlistId` (string).
        val id = (resp["playlistId"] as? JsonPrimitive)?.contentOrNull
        if (id.isNullOrBlank()) {
            Log.w(TAG, "createPlaylist no id in response: ${resp.toString().take(200)}")
            null
        } else id
    }

    enum class Privacy(val wire: String) {
        PRIVATE("PRIVATE"),
        UNLISTED("UNLISTED"),
        PUBLIC("PUBLIC"),
    }

    // ────────────────────────────── plumbing ─────────────────────────────

    private fun kotlinx.serialization.json.JsonObjectBuilder.putContext() {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", CLIENT_VERSION)
                put("hl", "en")
                put("gl", "US")
                put("platform", "DESKTOP")
            }
            putJsonObject("user") {
                put("lockedSafetyMode", false)
            }
        }
    }

    private fun postInnerTube(
        cookie: String,
        endpoint: String,
        body: JsonObject,
    ): JsonObject? {
        return try {
            val url = "https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false&alt=json"
            val req = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                )
                .header("X-Origin", YtMusicAuth.ORIGIN)
                .header("Origin", YtMusicAuth.ORIGIN)
                .header("Referer", "${YtMusicAuth.ORIGIN}/")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/json")
                .header("X-Youtube-Client-Name", "67")
                .header("X-Youtube-Client-Version", CLIENT_VERSION)
                .header("Cookie", cookie)
                .apply {
                    YtMusicAuth.sapisidHashHeader(cookie)?.let { header("Authorization", it) }
                }
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "POST /$endpoint HTTP ${resp.code}: ${text.take(200)}")
                    return null
                }
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(text).jsonObject
            }
        } catch (e: Throwable) {
            Log.w(TAG, "POST /$endpoint failed: ${e.message}")
            null
        }
    }
}
