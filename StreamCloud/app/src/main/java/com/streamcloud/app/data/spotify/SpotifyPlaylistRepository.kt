package com.streamcloud.app.data.spotify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object SpotifyPlaylistRepository {
    private const val TAG = "SpotifyPlaylistRepo"
    private const val BASE = "https://api.spotify.com/v1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authHeader(token: String) = "Bearer $token"

    // ── Playlists ─────────────────────────────────────────────────────────────

    suspend fun getPlaylists(spDc: String): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val tok = SpotifyCanvasRepository.getPersonalToken(spDc) ?: return@withContext emptyList()
        val result = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val req = Request.Builder()
                .url("$BASE/me/playlists?limit=50&offset=$offset")
                .header("Authorization", authHeader(tok))
                .header("Accept", "application/json")
                .get().build()
            val text = runCatching {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { Log.w(TAG, "playlists HTTP ${r.code}"); return@withContext result }
                    r.body?.string()
                }
            }.getOrElse { Log.e(TAG, "playlists: ${it.message}"); return@withContext result } ?: break
            val obj = JSONObject(text)
            val items = obj.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val images = item.optJSONArray("images")
                val imageUrl = images?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                result += SpotifyPlaylist(
                    id          = item.optString("id"),
                    name        = item.optString("name", "Playlist"),
                    description = item.optString("description", ""),
                    imageUrl    = imageUrl,
                    trackCount  = item.optJSONObject("tracks")?.optInt("total", 0) ?: 0,
                    snapshotId  = item.optString("snapshot_id", ""),
                )
            }
            val total = obj.optInt("total", 0)
            offset += items.length()
            if (offset >= total || items.length() == 0) break
        }
        result
    }

    // ── Tracks ────────────────────────────────────────────────────────────────

    suspend fun getPlaylistTracks(spDc: String, playlistId: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val tok = SpotifyCanvasRepository.getPersonalToken(spDc) ?: return@withContext emptyList()
        val result = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val fields = "total,items(track(id,uri,name,duration_ms,artists(name),album(name,images)))"
            val req = Request.Builder()
                .url("$BASE/playlists/$playlistId/tracks?limit=100&offset=$offset&fields=${URLEncoder.encode(fields,"UTF-8")}")
                .header("Authorization", authHeader(tok))
                .header("Accept", "application/json")
                .get().build()
            val text = runCatching {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { Log.w(TAG, "tracks HTTP ${r.code}"); return@withContext result }
                    r.body?.string()
                }
            }.getOrElse { Log.e(TAG, "tracks: ${it.message}"); return@withContext result } ?: break
            val obj = JSONObject(text)
            val items = obj.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val track = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                if (track.isNull("id")) continue
                val id = track.optString("id").takeIf { it.isNotBlank() } ?: continue
                val artistsArr = track.optJSONArray("artists")
                val artists = buildString {
                    if (artistsArr != null) for (j in 0 until artistsArr.length()) {
                        if (j > 0) append(", ")
                        append(artistsArr.optJSONObject(j)?.optString("name", "") ?: "")
                    }
                }
                val album = track.optJSONObject("album")
                val imageUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                result += SpotifyTrack(
                    id         = id,
                    uri        = track.optString("uri"),
                    title      = track.optString("name", ""),
                    artists    = artists,
                    album      = album?.optString("name", "") ?: "",
                    imageUrl   = imageUrl,
                    durationMs = track.optLong("duration_ms", 0L),
                )
            }
            val total = obj.optInt("total", 0)
            offset += items.length()
            if (offset >= total || items.length() == 0) break
        }
        result
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    suspend fun removeTrack(spDc: String, playlistId: String, trackUri: String): Boolean = withContext(Dispatchers.IO) {
        val tok = SpotifyCanvasRepository.getPersonalToken(spDc) ?: return@withContext false
        runCatching {
            val body = JSONObject().put("tracks", JSONArray().put(JSONObject().put("uri", trackUri)))
                .toString().toRequestBody("application/json".toMediaType())
            http.newCall(
                Request.Builder()
                    .url("$BASE/playlists/$playlistId/tracks")
                    .header("Authorization", authHeader(tok))
                    .delete(body).build(),
            ).execute().use { it.isSuccessful }
        }.getOrElse { Log.e(TAG, "removeTrack: ${it.message}"); false }
    }

    suspend fun addTrack(spDc: String, playlistId: String, trackUri: String): Boolean = withContext(Dispatchers.IO) {
        val tok = SpotifyCanvasRepository.getPersonalToken(spDc) ?: return@withContext false
        runCatching {
            val body = JSONObject().put("uris", JSONArray().put(trackUri))
                .toString().toRequestBody("application/json".toMediaType())
            http.newCall(
                Request.Builder()
                    .url("$BASE/playlists/$playlistId/tracks")
                    .header("Authorization", authHeader(tok))
                    .header("Accept", "application/json")
                    .post(body).build(),
            ).execute().use { it.isSuccessful }
        }.getOrElse { Log.e(TAG, "addTrack: ${it.message}"); false }
    }

    // ── Search (for the "Add songs" sheet) ────────────────────────────────────

    suspend fun searchTracks(spDc: String, query: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val tok = SpotifyCanvasRepository.getPersonalToken(spDc) ?: return@withContext emptyList()
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("$BASE/search?q=$q&type=track&limit=20")
                .header("Authorization", authHeader(tok))
                .header("Accept", "application/json")
                .get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching emptyList()
                val items = JSONObject(r.body!!.string())
                    .optJSONObject("tracks")?.optJSONArray("items")
                    ?: return@runCatching emptyList<SpotifyTrack>()
                buildList {
                    for (i in 0 until items.length()) {
                        val t = items.optJSONObject(i) ?: continue
                        val id = t.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val artistsArr = t.optJSONArray("artists")
                        val artists = buildString {
                            if (artistsArr != null) for (j in 0 until artistsArr.length()) {
                                if (j > 0) append(", ")
                                append(artistsArr.optJSONObject(j)?.optString("name", "") ?: "")
                            }
                        }
                        val album = t.optJSONObject("album")
                        val imageUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                        add(SpotifyTrack(
                            id = id, uri = t.optString("uri"),
                            title = t.optString("name", ""), artists = artists,
                            album = album?.optString("name", "") ?: "",
                            imageUrl = imageUrl, durationMs = t.optLong("duration_ms", 0L),
                        ))
                    }
                }
            }
        }.getOrElse { Log.e(TAG, "searchTracks: ${it.message}"); emptyList() }
    }
}
