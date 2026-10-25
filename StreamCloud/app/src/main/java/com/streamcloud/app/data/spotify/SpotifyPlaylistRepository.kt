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

    private fun accessToken(spDc: String): String =
        SpotifyCanvasRepository.getPersonalToken(spDc)
            ?: throw IllegalStateException(
                "Spotify sign-in has expired. Sign out of Spotify in StreamCloud Settings, then sign in again.",
            )

    private fun getJson(token: String, url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(token))
            .header("Accept", "application/json")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401 -> "Spotify sign-in has expired. Sign out in StreamCloud Settings, then sign in again."
                    403 -> "Spotify denied access to this library (HTTP 403). Reconnect the account and check its library permissions."
                    429 -> "Spotify is temporarily rate-limiting library requests. Wait a moment and retry."
                    else -> "Spotify library request failed (HTTP ${response.code})."
                }
                throw IllegalStateException(message)
            }
            JSONObject(response.body?.string() ?: throw IllegalStateException("Spotify returned an empty response."))
        }
    }

    private fun parseArtists(items: JSONArray?): String = buildString {
        if (items != null) for (index in 0 until items.length()) {
            val name = items.optJSONObject(index)?.optString("name").orEmpty()
            if (name.isBlank()) continue
            if (isNotEmpty()) append(", ")
            append(name)
        }
    }

    private fun parseTrack(track: JSONObject): SpotifyTrack? {
        if (track.isNull("id")) return null
        val id = track.optString("id").takeIf(String::isNotBlank) ?: return null
        val album = track.optJSONObject("album")
        return SpotifyTrack(
            id = id,
            uri = track.optString("uri"),
            title = track.optString("name", ""),
            artists = parseArtists(track.optJSONArray("artists")),
            album = album?.optString("name").orEmpty(),
            imageUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                ?.takeIf(String::isNotBlank),
            durationMs = track.optLong("duration_ms", 0L),
        )
    }

    private fun parseAlbum(album: JSONObject): SpotifyAlbum? {
        val id = album.optString("id").takeIf(String::isNotBlank) ?: return null
        return SpotifyAlbum(
            id = id,
            name = album.optString("name", "Album"),
            artists = parseArtists(album.optJSONArray("artists")),
            imageUrl = album.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                ?.takeIf(String::isNotBlank),
            trackCount = album.optInt("total_tracks", 0),
            releaseDate = album.optString("release_date", ""),
        )
    }

    // ── Playlists ─────────────────────────────────────────────────────────────

    suspend fun getPlaylists(spDc: String): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val tok = accessToken(spDc)
        val result = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val obj = getJson(tok, "$BASE/me/playlists?limit=50&offset=$offset")
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
                    ownerName   = item.optJSONObject("owner")?.optString("display_name").orEmpty(),
                )
            }
            val total = obj.optInt("total", 0)
            offset += items.length()
            if (offset >= total || items.length() == 0) break
        }
        result
    }

    suspend fun getSavedAlbums(spDc: String): List<SpotifyAlbum> = withContext(Dispatchers.IO) {
        val token = accessToken(spDc)
        val result = mutableListOf<SpotifyAlbum>()
        var offset = 0
        while (true) {
            val response = getJson(token, "$BASE/me/albums?limit=50&offset=$offset")
            val items = response.optJSONArray("items") ?: break
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                parseAlbum(item.optJSONObject("album") ?: continue)?.let(result::add)
            }
            offset += items.length()
            if (offset >= response.optInt("total", 0) || items.length() == 0) break
        }
        result
    }

    suspend fun getFollowedArtists(spDc: String): List<SpotifyArtist> = withContext(Dispatchers.IO) {
        val token = accessToken(spDc)
        val result = mutableListOf<SpotifyArtist>()
        var after: String? = null
        while (true) {
            val cursor = after?.let { "&after=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
            val response = getJson(token, "$BASE/me/following?type=artist&limit=50$cursor")
            val artists = response.optJSONObject("artists") ?: break
            val items = artists.optJSONArray("items") ?: break
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                result += SpotifyArtist(
                    id = id,
                    name = item.optString("name", "Artist"),
                    imageUrl = item.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                        ?.takeIf(String::isNotBlank),
                    followers = item.optJSONObject("followers")?.optLong("total", 0L) ?: 0L,
                )
            }
            after = artists.optJSONObject("cursors")?.optString("after")?.takeIf(String::isNotBlank)
            if (after == null || items.length() == 0) break
        }
        result
    }

    suspend fun getSavedTracks(spDc: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val token = accessToken(spDc)
        val result = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val response = getJson(token, "$BASE/me/tracks?limit=50&offset=$offset")
            val items = response.optJSONArray("items") ?: break
            for (index in 0 until items.length()) {
                val track = items.optJSONObject(index)?.optJSONObject("track") ?: continue
                parseTrack(track)?.let(result::add)
            }
            offset += items.length()
            if (offset >= response.optInt("total", 0) || items.length() == 0) break
        }
        result
    }

    suspend fun getAlbumTracks(spDc: String, albumId: String): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val token = accessToken(spDc)
            val result = mutableListOf<SpotifyTrack>()
            var offset = 0
            while (true) {
                val response = getJson(token, "$BASE/albums/$albumId/tracks?limit=50&offset=$offset")
                val items = response.optJSONArray("items") ?: break
                for (index in 0 until items.length()) {
                    val track = items.optJSONObject(index) ?: continue
                    parseTrack(track)?.let(result::add)
                }
                offset += items.length()
                if (offset >= response.optInt("total", 0) || items.length() == 0) break
            }
            result
        }

    // ── Tracks ────────────────────────────────────────────────────────────────

    suspend fun getPlaylistTracks(spDc: String, playlistId: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val tok = accessToken(spDc)
        val result = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val fields = "total,items(track(id,uri,name,duration_ms,artists(name),album(name,images)))"
            val url = "$BASE/playlists/$playlistId/tracks?limit=100&offset=$offset&fields=${URLEncoder.encode(fields, "UTF-8")}"
            val obj = getJson(tok, url)
            val items = obj.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val track = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                parseTrack(track)?.let(result::add)
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
