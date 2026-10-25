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
import java.security.MessageDigest
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object SpotifyPlaylistRepository {
    private const val TAG = "SpotifyPlaylistRepo"
    private const val BASE = "https://api.spotify.com/v1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class CachedResponse(val body: String, val storedAtMs: Long)

    private val responseCache = ConcurrentHashMap<String, CachedResponse>()
    @Volatile private var spotifyRetryAtMs = 0L

    private const val RESPONSE_CACHE_TTL_MS = 15L * 60L * 1000L
    private const val STALE_RESPONSE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val DEFAULT_RETRY_AFTER_MS = 60_000L
    private const val MAX_CACHED_RESPONSES = 256

    private class SpotifyRateLimitedException(retryAfterMs: Long) : IllegalStateException(
        "Spotify is rate-limiting requests. Try again in ${((retryAfterMs + 999L) / 1000L).coerceAtLeast(1L)} seconds.",
    )

    private fun authHeader(token: String) = "Bearer $token"

    private fun accessToken(spDc: String): String =
        SpotifyCanvasRepository.getPersonalToken(spDc)
            ?: throw IllegalStateException(
                "Spotify sign-in has expired. Sign out of Spotify in StreamCloud Settings, then sign in again.",
            )

    private fun tokenCacheScope(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun cachedBody(key: String, maxAgeMs: Long): String? {
        val cached = responseCache[key] ?: return null
        val age = System.currentTimeMillis() - cached.storedAtMs
        return cached.body.takeIf { age in 0..maxAgeMs }
    }

    private fun storeBody(key: String, body: String) {
        if (responseCache.size >= MAX_CACHED_RESPONSES) {
            val oldest = responseCache.entries.minByOrNull { it.value.storedAtMs }
            if (oldest != null) responseCache.remove(oldest.key)
        }
        responseCache[key] = CachedResponse(body, System.currentTimeMillis())
    }

    private fun invalidatePlaylistTrackResponses(token: String, playlistId: String) {
        val prefix = "${tokenCacheScope(token)}|$BASE/playlists/$playlistId/tracks?"
        responseCache.keys
            .filter { it.startsWith(prefix) }
            .forEach(responseCache::remove)
    }

    private fun getJson(token: String, url: String): JSONObject {
        // Cache account-specific GET responses in memory. The token is hashed in the cache key
        // so neither the Spotify credential nor private library data is persisted to disk.
        val cacheKey = "${tokenCacheScope(token)}|$url"
        cachedBody(cacheKey, RESPONSE_CACHE_TTL_MS)?.let { return JSONObject(it) }

        val remainingLimitMs = spotifyRetryAtMs - System.currentTimeMillis()
        if (remainingLimitMs > 0L) {
            cachedBody(cacheKey, STALE_RESPONSE_TTL_MS)?.let { return JSONObject(it) }
            throw SpotifyRateLimitedException(remainingLimitMs)
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(token))
            .header("Accept", "application/json")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 429) {
                    val retryAfterMs = response.header("Retry-After")
                        ?.trim()
                        ?.toLongOrNull()
                        ?.let { TimeUnit.SECONDS.toMillis(it.coerceAtLeast(1L)) }
                        ?: DEFAULT_RETRY_AFTER_MS
                    spotifyRetryAtMs = maxOf(
                        spotifyRetryAtMs,
                        System.currentTimeMillis() + retryAfterMs,
                    )
                    cachedBody(cacheKey, STALE_RESPONSE_TTL_MS)?.let {
                        return@use JSONObject(it)
                    }
                    throw SpotifyRateLimitedException(spotifyRetryAtMs - System.currentTimeMillis())
                }
                val message = when (response.code) {
                    401 -> "Spotify sign-in has expired. Sign out in StreamCloud Settings, then sign in again."
                    403 -> "Spotify denied access to this data (HTTP 403). Reconnect the account and check its permissions."
                    else -> "Spotify request failed (HTTP ${response.code})."
                }
                throw IllegalStateException(message)
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("Spotify returned an empty response.")
            storeBody(cacheKey, body)
            JSONObject(body)
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
        val artists = track.optJSONArray("artists")
        val artistIds = buildList {
            if (artists != null) {
                for (index in 0 until artists.length()) {
                    artists.optJSONObject(index)?.optString("id")
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }
        return SpotifyTrack(
            id = id,
            uri = track.optString("uri"),
            title = track.optString("name", ""),
            artists = parseArtists(artists),
            artistIds = artistIds,
            album = album?.optString("name").orEmpty(),
            imageUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                ?.takeIf(String::isNotBlank),
            durationMs = track.optLong("duration_ms", 0L),
        )
    }

    private fun parseArtist(artist: JSONObject): SpotifyArtist? {
        val id = artist.optString("id").takeIf(String::isNotBlank) ?: return null
        return SpotifyArtist(
            id = id,
            name = artist.optString("name", "Artist"),
            imageUrl = artist.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                ?.takeIf(String::isNotBlank),
            followers = artist.optJSONObject("followers")?.optLong("total", 0L) ?: 0L,
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

    private fun parsePodcast(podcast: JSONObject): SpotifyPodcast? {
        val id = podcast.optString("id").takeIf(String::isNotBlank) ?: return null
        return SpotifyPodcast(
            id = id,
            name = podcast.optString("name", "Podcast"),
            publisher = podcast.optString("publisher", ""),
            imageUrl = podcast.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                ?.takeIf(String::isNotBlank),
        )
    }

    private fun parseEpisode(episode: JSONObject): SpotifyEpisode? {
        val id = episode.optString("id").takeIf(String::isNotBlank) ?: return null
        return SpotifyEpisode(
            id = id,
            name = episode.optString("name", "Episode"),
            podcast = episode.optJSONObject("show")?.optString("name").orEmpty(),
            imageUrl = episode.optJSONObject("show")?.optJSONArray("images")
                ?.optJSONObject(0)?.optString("url")?.takeIf(String::isNotBlank),
            durationMs = episode.optLong("duration_ms", 0L),
        )
    }

    private fun <T> parseSearchItems(
        response: JSONObject,
        type: String,
        parse: (JSONObject) -> T?,
    ): List<T> {
        val items = response.optJSONObject(type)?.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                parse(item)?.let(::add)
            }
        }
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

    suspend fun getHomeFeed(spDc: String): SpotifyHomeFeed {
        val warnings = mutableListOf<String>()

        suspend fun <T> loadSection(label: String, load: suspend () -> List<T>): List<T> =
            try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                warnings += "$label: ${failure.message ?: "could not be loaded."}"
                emptyList()
            }

        // Keep requests sequential. This feed is separate from the account Library and should
        // not fan out a burst of Spotify requests when Home opens.
        val artists = loadSection("Your top artists") { getTopArtists(spDc) }
        val tracks = loadSection("Recommended for you") { getTopTracks(spDc) }
        val recent = loadSection("Recently played") { getRecentlyPlayedTracks(spDc) }
        if (artists.isEmpty() && tracks.isEmpty() && recent.isEmpty() && warnings.isNotEmpty()) {
            throw IllegalStateException(warnings.first())
        }
        return SpotifyHomeFeed(
            topArtists = artists,
            topTracks = tracks,
            recentlyPlayed = recent,
            warnings = warnings.distinct(),
        )
    }

    private suspend fun getTopArtists(spDc: String): List<SpotifyArtist> = withContext(Dispatchers.IO) {
        val token = accessToken(spDc)
        val response = getJson(token, "$BASE/me/top/artists?limit=20&time_range=medium_term")
        val items = response.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until items.length()) {
                parseArtist(items.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
    }

    private suspend fun getTopTracks(spDc: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val token = accessToken(spDc)
        val response = getJson(token, "$BASE/me/top/tracks?limit=20&time_range=medium_term")
        val items = response.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until items.length()) {
                parseTrack(items.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
    }

    suspend fun getRecentlyPlayedTracks(spDc: String): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val token = accessToken(spDc)
            val response = getJson(token, "$BASE/me/player/recently-played?limit=20")
            val items = response.optJSONArray("items") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val track = items.optJSONObject(index)?.optJSONObject("track") ?: continue
                    parseTrack(track)?.let(::add)
                }
            }
        }

    suspend fun getArtistTopTracks(spDc: String, artistId: String): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            require(artistId.matches(Regex("[A-Za-z0-9]+"))) { "Invalid Spotify artist ID." }
            val token = accessToken(spDc)
            val response = getJson(
                token,
                "$BASE/me/top/tracks?limit=50&time_range=long_term",
            )
            val items = response.optJSONArray("items") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val track = parseTrack(items.optJSONObject(index) ?: continue) ?: continue
                    if (artistId in track.artistIds) add(track)
                }
            }
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
            ).execute().use { response ->
                val success = response.isSuccessful
                if (success) invalidatePlaylistTrackResponses(tok, playlistId)
                success
            }
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
            ).execute().use { response ->
                val success = response.isSuccessful
                if (success) invalidatePlaylistTrackResponses(tok, playlistId)
                success
            }
        }.getOrElse { Log.e(TAG, "addTrack: ${it.message}"); false }
    }

    suspend fun saveTrack(spDc: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        if (!trackId.matches(Regex("[A-Za-z0-9]+"))) return@withContext false
        val tok = SpotifyCanvasRepository.getPersonalToken(spDc) ?: return@withContext false
        runCatching {
            val body = JSONObject().put("ids", JSONArray().put(trackId))
                .toString().toRequestBody("application/json".toMediaType())
            http.newCall(
                Request.Builder()
                    .url("$BASE/me/tracks")
                    .header("Authorization", authHeader(tok))
                    .header("Accept", "application/json")
                    .put(body)
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "saveTrack: Spotify returned HTTP ${response.code}")
                }
                response.isSuccessful
            }
        }.getOrElse { Log.e(TAG, "saveTrack: ${it.message}"); false }
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

    suspend fun searchCatalog(spDc: String, query: String): SpotifySearchResults =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@withContext SpotifySearchResults()
            val token = accessToken(spDc)
            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
            val response = getJson(
                token,
                "$BASE/search?q=$encodedQuery&type=track,artist,album,playlist,show,episode&limit=10",
            )
            SpotifySearchResults(
                tracks = parseSearchItems(response, "tracks", ::parseTrack),
                artists = parseSearchItems(response, "artists", ::parseArtist),
                albums = parseSearchItems(response, "albums", ::parseAlbum),
                playlists = parseSearchItems(response, "playlists") { playlist ->
                    val id = playlist.optString("id").takeIf(String::isNotBlank) ?: return@parseSearchItems null
                    SpotifyPlaylist(
                        id = id,
                        name = playlist.optString("name", "Playlist"),
                        description = playlist.optString("description", ""),
                        imageUrl = playlist.optJSONArray("images")?.optJSONObject(0)
                            ?.optString("url")?.takeIf(String::isNotBlank),
                        trackCount = playlist.optJSONObject("tracks")?.optInt("total", 0) ?: 0,
                        ownerName = playlist.optJSONObject("owner")
                            ?.optString("display_name").orEmpty(),
                    )
                },
                podcasts = parseSearchItems(response, "shows", ::parsePodcast),
                episodes = parseSearchItems(response, "episodes", ::parseEpisode),
            )
        }
}
