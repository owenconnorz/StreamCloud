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

object YtMusicPlaylistRepository {

    private const val TAG = "YtmPlaylistRepo"
    private const val CLIENT_VERSION = "1.20250127.01.00"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()


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
     * Removes one song occurrence from an editable YouTube Music playlist.
     *
     * [playlistSetVideoId] distinguishes duplicate occurrences of the same video in a playlist.
     */
    suspend fun removeVideoFromPlaylist(
        cookie: String,
        playlistId: String,
        videoId: String,
        playlistSetVideoId: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (cookie.isBlank() || playlistId.isBlank() || videoId.isBlank()) {
            return@withContext false
        }
        val body = buildJsonObject {
            putContext()
            put("playlistId", playlistId.removePrefix("VL"))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("removedVideoId", videoId)
                    playlistSetVideoId?.takeIf { it.isNotBlank() }?.let {
                        put("setVideoId", it)
                    }
                })
            })
        }
        val resp = postInnerTube(cookie, "browse/edit_playlist", body) ?: return@withContext false
        val status = (resp["status"] as? JsonPrimitive)?.contentOrNull
        val ok = status == "STATUS_SUCCEEDED"
        if (!ok) Log.w(TAG, "removeVideoFromPlaylist status=$status resp=${resp.toString().take(160)}")
        ok
    }


    /**
     * Uploads [imageBytes] to YouTube Music's playlist image upload endpoint (resumable, 2-step),
     * then sets it as the thumbnail of [playlistId] via edit_playlist.
     *
     * Follows the same flow as Metrolist / innertube:
     *   1. POST to playlist_image_upload with X-Goog-Upload-Command: start → get upload_id
     *   2. POST image bytes to same URL with upload_id → get encryptedBlobId
     *   3. edit_playlist with addedCustomThumbnail.playlistScottyEncryptedBlobId
     *
     * Returns true on success, false on any network/API failure.
     */
    suspend fun uploadAndSetPlaylistThumbnail(
        cookie: String,
        playlistId: String,
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg",
    ): Boolean = withContext(Dispatchers.IO) {
        if (cookie.isBlank() || imageBytes.isEmpty()) return@withContext false

        val authHeader = YtMusicAuth.sapisidHashHeader(cookie)
            ?: run {
                Log.w(TAG, "uploadAndSetPlaylistThumbnail: no SAPISID in cookie")
                return@withContext false
            }

        val uploadBase = "https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail"
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // ── Step 1: start resumable upload session ────────────────────────────
        val uploadId: String? = http.newCall(
            Request.Builder()
                .url(uploadBase)
                .post(ByteArray(0).toRequestBody(null))
                .header("Authorization", authHeader)
                .header("Cookie", cookie)
                .header("Origin", YtMusicAuth.ORIGIN)
                .header("Referer", "${YtMusicAuth.ORIGIN}/")
                .header("User-Agent", ua)
                .header("X-Youtube-Client-Name", "67")
                .header("X-Youtube-Client-Version", CLIENT_VERSION)
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Header-Content-Length", imageBytes.size.toString())
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "thumbnail session start HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                return@use null
            }
            resp.header("x-guploader-uploadid")
                ?: resp.header("X-Goog-Upload-URL")
                    ?.substringAfter("upload_id=")?.substringBefore("&")
        }

        if (uploadId.isNullOrBlank()) {
            Log.w(TAG, "uploadAndSetPlaylistThumbnail: no upload_id from session start")
            return@withContext false
        }

        // ── Step 2: upload the image bytes ────────────────────────────────────
        val blobId: String? = http.newCall(
            Request.Builder()
                .url("$uploadBase?upload_id=$uploadId&upload_protocol=resumable")
                .post(imageBytes.toRequestBody(mimeType.toMediaType()))
                .header("Authorization", authHeader)
                .header("Cookie", cookie)
                .header("Origin", YtMusicAuth.ORIGIN)
                .header("Referer", "${YtMusicAuth.ORIGIN}/")
                .header("User-Agent", ua)
                .header("X-Youtube-Client-Name", "67")
                .header("X-Youtube-Client-Version", CLIENT_VERSION)
                .header("X-Goog-Upload-Command", "upload, finalize")
                .header("X-Goog-Upload-Offset", "0")
                .build(),
        ).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "thumbnail upload HTTP ${resp.code}: ${text.take(200)}")
                return@use null
            }
            Log.d(TAG, "thumbnail upload response: ${text.take(300)}")
            runCatching {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(text).jsonObject
                json["encryptedBlobId"]?.jsonPrimitive?.contentOrNull
            }.getOrElse {
                Log.w(TAG, "thumbnail upload parse: ${it.message}")
                null
            }
        }

        if (blobId.isNullOrBlank()) {
            Log.w(TAG, "uploadAndSetPlaylistThumbnail: no encryptedBlobId in upload response")
            return@withContext false
        }

        // ── Step 3: set as playlist thumbnail via edit_playlist ───────────────
        // Action name is ACTION_SET_CUSTOM_THUMBNAIL (NOT ACTION_SET_PLAYLIST_THUMBNAIL).
        // addedCustomThumbnail requires both imageKey and playlistScottyEncryptedBlobId.
        val cleanId = playlistId.removePrefix("VL")
        val body = buildJsonObject {
            putContext()
            put("playlistId", cleanId)
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("action", "ACTION_SET_CUSTOM_THUMBNAIL")
                    putJsonObject("addedCustomThumbnail") {
                        putJsonObject("imageKey") {
                            put("name", "studio_square_thumbnail")
                            put("type", "PLAYLIST_IMAGE_TYPE_CUSTOM_THUMBNAIL")
                        }
                        put("playlistScottyEncryptedBlobId", blobId)
                    }
                })
            })
        }
        val resp = postInnerTube(cookie, "browse/edit_playlist", body) ?: return@withContext false
        val status = (resp["status"] as? JsonPrimitive)?.contentOrNull
        val ok = status != null  // any non-null response means the server accepted the action
        if (!ok) Log.w(TAG, "setPlaylistThumbnail null resp from postInnerTube")
        Log.d(TAG, "setPlaylistThumbnail status=$status resp=${resp.toString().take(200)}")
        ok
    }


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



    private fun kotlinx.serialization.json.JsonObjectBuilder.putContext() {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", CLIENT_VERSION)
                put("hl", YtPlayerUtils.contentLanguage)
                put("gl", YtPlayerUtils.contentCountry)
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
