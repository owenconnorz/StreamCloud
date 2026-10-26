package com.streamcloud.app.data.spotify

import android.content.Context
import android.content.SharedPreferences
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
import java.security.SecureRandom
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object SpotifyPlaylistRepository {
    private const val TAG = "SpotifyPlaylistRepo"
    private const val BASE = "https://api.spotify.com/v1"
    private const val RATE_LIMIT_PREFS = "spotify_api_rate_limits"
    private const val RATE_LIMIT_UNTIL_KEY = "retry_until_ms"
    private const val PATHFINDER_RATE_LIMIT_UNTIL_KEY = "catalog_retry_until_ms"
    private const val WEBPLAYER_RATE_LIMIT_UNTIL_KEY = "webplayer_retry_until_ms"
    private const val PENDING_WEB_PLAYLIST_KEY_PREFIX = "pending_web_playlist_"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class CachedResponse(val body: String, val storedAtMs: Long)
    private data class PendingWebPlaylistSetup(
        val playlistId: String,
        val name: String,
        val username: String,
        val addedToLibrary: Boolean,
    )

    private val responseCache = ConcurrentHashMap<String, CachedResponse>()
    private val pendingWebPlaylistSetups = ConcurrentHashMap<String, PendingWebPlaylistSetup>()
    @Volatile private var spotifyRetryAtMs = 0L
    @Volatile private var pathfinderRetryAtMs = 0L
    @Volatile private var webPlayerRetryAtMs = 0L
    @Volatile private var rateLimitPreferences: SharedPreferences? = null

    private const val RESPONSE_CACHE_TTL_MS = 15L * 60L * 1000L
    private const val STALE_RESPONSE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val DEFAULT_RETRY_AFTER_MS = 60_000L
    private const val MAX_CACHED_RESPONSES = 256

    class RateLimitedException(val retryAtMs: Long) : IllegalStateException(
        "Spotify is rate-limiting requests. Try again in " +
            "${((retryAtMs - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(1L)} seconds.",
    )

    @Synchronized
    fun initialize(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(
            RATE_LIMIT_PREFS,
            Context.MODE_PRIVATE,
        )
        rateLimitPreferences = preferences
        spotifyRetryAtMs = maxOf(
            spotifyRetryAtMs,
            preferences.getLong(RATE_LIMIT_UNTIL_KEY, 0L),
        )
        pathfinderRetryAtMs = maxOf(
            pathfinderRetryAtMs,
            preferences.getLong(PATHFINDER_RATE_LIMIT_UNTIL_KEY, 0L),
        )
        webPlayerRetryAtMs = maxOf(
            webPlayerRetryAtMs,
            preferences.getLong(WEBPLAYER_RATE_LIMIT_UNTIL_KEY, 0L),
        )
    }

    @Synchronized
    private fun currentRetryAtMs(): Long {
        val preferences = rateLimitPreferences
        val retryAt = maxOf(
            spotifyRetryAtMs,
            preferences?.getLong(RATE_LIMIT_UNTIL_KEY, 0L) ?: 0L,
        )
        if (retryAt <= System.currentTimeMillis()) {
            spotifyRetryAtMs = 0L
            preferences?.edit()?.remove(RATE_LIMIT_UNTIL_KEY)?.apply()
            return 0L
        }
        spotifyRetryAtMs = retryAt
        return retryAt
    }

    /**
     * Playlist creation depends on different endpoint families:
     * standard playlists use Web Player + Pathfinder, collaborative playlists use REST.
     */
    fun playlistCreateCooldownUntilMs(collaborative: Boolean): Long = if (collaborative) {
        currentRetryAtMs()
    } else {
        maxOf(currentWebPlayerRetryAtMs(), currentPathfinderRetryAtMs())
    }

    @Synchronized
    private fun extendRetryAtMs(candidateRetryAtMs: Long): Long {
        val retryAt = maxOf(currentRetryAtMs(), candidateRetryAtMs)
        spotifyRetryAtMs = retryAt
        rateLimitPreferences?.edit()?.putLong(RATE_LIMIT_UNTIL_KEY, retryAt)?.apply()
        return retryAt
    }

    @Synchronized
    private fun currentPathfinderRetryAtMs(): Long {
        val preferences = rateLimitPreferences
        val retryAt = maxOf(
            pathfinderRetryAtMs,
            preferences?.getLong(PATHFINDER_RATE_LIMIT_UNTIL_KEY, 0L) ?: 0L,
        )
        if (retryAt <= System.currentTimeMillis()) {
            pathfinderRetryAtMs = 0L
            preferences?.edit()?.remove(PATHFINDER_RATE_LIMIT_UNTIL_KEY)?.apply()
            return 0L
        }
        pathfinderRetryAtMs = retryAt
        return retryAt
    }

    @Synchronized
    private fun extendPathfinderRetryAtMs(candidateRetryAtMs: Long): Long {
        val retryAt = maxOf(currentPathfinderRetryAtMs(), candidateRetryAtMs)
        pathfinderRetryAtMs = retryAt
        rateLimitPreferences?.edit()?.putLong(PATHFINDER_RATE_LIMIT_UNTIL_KEY, retryAt)?.apply()
        return retryAt
    }

    @Synchronized
    private fun currentWebPlayerRetryAtMs(): Long {
        val preferences = rateLimitPreferences
        val retryAt = maxOf(
            webPlayerRetryAtMs,
            preferences?.getLong(WEBPLAYER_RATE_LIMIT_UNTIL_KEY, 0L) ?: 0L,
        )
        if (retryAt <= System.currentTimeMillis()) {
            webPlayerRetryAtMs = 0L
            preferences?.edit()?.remove(WEBPLAYER_RATE_LIMIT_UNTIL_KEY)?.apply()
            return 0L
        }
        webPlayerRetryAtMs = retryAt
        return retryAt
    }

    @Synchronized
    private fun extendWebPlayerRetryAtMs(candidateRetryAtMs: Long): Long {
        val retryAt = maxOf(currentWebPlayerRetryAtMs(), candidateRetryAtMs)
        webPlayerRetryAtMs = retryAt
        rateLimitPreferences?.edit()?.putLong(WEBPLAYER_RATE_LIMIT_UNTIL_KEY, retryAt)?.apply()
        return retryAt
    }

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

    private fun pendingWebPlaylistKey(spDc: String): String {
        val accountCookie = spDc.split(";")
            .map(String::trim)
            .firstOrNull { it.startsWith("sp_dc=") }
            ?.removePrefix("sp_dc=")
            ?.takeIf(String::isNotBlank)
            ?: spDc.trim()
        return PENDING_WEB_PLAYLIST_KEY_PREFIX + tokenCacheScope(accountCookie)
    }

    private fun pendingWebPlaylistSetup(spDc: String): PendingWebPlaylistSetup? {
        val key = pendingWebPlaylistKey(spDc)
        pendingWebPlaylistSetups[key]?.let { return it }
        val raw = rateLimitPreferences?.getString(key, null) ?: return null
        val pending = runCatching {
            JSONObject(raw).let { json ->
                PendingWebPlaylistSetup(
                    playlistId = json.getString("playlistId"),
                    name = json.getString("name"),
                    username = json.getString("username"),
                    addedToLibrary = json.optBoolean("addedToLibrary", false),
                )
            }
        }.getOrNull()
        if (pending == null) {
            rateLimitPreferences?.edit()?.remove(key)?.apply()
            return null
        }
        pendingWebPlaylistSetups[key] = pending
        return pending
    }

    private fun storePendingWebPlaylistSetup(
        spDc: String,
        pending: PendingWebPlaylistSetup,
    ) {
        val key = pendingWebPlaylistKey(spDc)
        pendingWebPlaylistSetups[key] = pending
        val serialized = JSONObject()
            .put("playlistId", pending.playlistId)
            .put("name", pending.name)
            .put("username", pending.username)
            .put("addedToLibrary", pending.addedToLibrary)
            .toString()
        rateLimitPreferences?.edit()?.putString(key, serialized)?.apply()
    }

    private fun clearPendingWebPlaylistSetup(spDc: String) {
        val key = pendingWebPlaylistKey(spDc)
        pendingWebPlaylistSetups.remove(key)
        rateLimitPreferences?.edit()?.remove(key)?.apply()
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
        val prefix = "${tokenCacheScope(token)}|$BASE/playlists/$playlistId/items?"
        responseCache.keys
            .filter { it.startsWith(prefix) }
            .forEach(responseCache::remove)
    }

    private fun invalidateSavedTrackResponses(token: String) {
        val prefix = "${tokenCacheScope(token)}|$BASE/me/tracks"
        responseCache.keys
            .filter { it.startsWith(prefix) }
            .forEach(responseCache::remove)
    }

    private fun getJsonArray(token: String, url: String): JSONArray {
        val retryAtMs = currentRetryAtMs()
        if (retryAtMs > System.currentTimeMillis()) {
            throw RateLimitedException(retryAtMs)
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(token))
            .header("Accept", "application/json")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 429) {
                val retryAfterMs = response.header("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.let { TimeUnit.SECONDS.toMillis(it.coerceAtLeast(1L)) }
                    ?: DEFAULT_RETRY_AFTER_MS
                val retryAt = extendRetryAtMs(System.currentTimeMillis() + retryAfterMs)
                throw RateLimitedException(retryAt)
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    spotifyErrorMessage(response.code, body, "check Spotify Liked Songs"),
                )
            }
            if (body.isBlank()) throw IllegalStateException("Spotify returned an empty response.")
            JSONArray(body)
        }
    }

    private fun spotifyErrorMessage(code: Int, body: String, action: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
        val message = when (code) {
            401 -> "Spotify sign-in has expired. Sign out in Settings, then sign in again."
            403 -> "Spotify denied this change for the current account or playlist."
            404 -> "The Spotify item or playlist could not be found."
            else -> "Spotify couldn't $action (HTTP $code)."
        }
        return if (detail == null) message else "$message $detail"
    }

    private fun postSpotifyJson(token: String, url: String, payload: JSONObject): JSONObject {
        val retryAt = currentRetryAtMs()
        if (retryAt > System.currentTimeMillis()) throw RateLimitedException(retryAt)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(token))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 429) {
                val retryAfterMs = response.header("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.let { TimeUnit.SECONDS.toMillis(it.coerceAtLeast(1L)) }
                    ?: DEFAULT_RETRY_AFTER_MS
                throw RateLimitedException(
                    extendRetryAtMs(System.currentTimeMillis() + retryAfterMs),
                )
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    spotifyErrorMessage(response.code, body, "create this playlist"),
                )
            }
            body.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        }
    }

    private fun webPlayerJson(
        token: String,
        spDc: String,
        method: String,
        path: String,
        body: String? = null,
    ): JSONObject {
        val retryAt = currentWebPlayerRetryAtMs()
        if (retryAt > System.currentTimeMillis()) throw RateLimitedException(retryAt)

        val response = SpotifyCanvasRepository.webPlayerRequest(
            token = token,
            spDc = spDc,
            method = method,
            path = path,
            body = body,
        )
        if (response.statusCode == 429) {
            val retryAfterMs = response.retryAfter
                ?.trim()
                ?.toLongOrNull()
                ?.let { TimeUnit.SECONDS.toMillis(it.coerceAtLeast(1L)) }
                ?: DEFAULT_RETRY_AFTER_MS
            throw RateLimitedException(
                extendWebPlayerRetryAtMs(System.currentTimeMillis() + retryAfterMs),
            )
        }
        if (response.statusCode !in 200..299) {
            val detail = runCatching {
                JSONObject(response.body).optString("message")
                    .takeIf(String::isNotBlank)
                    ?: JSONObject(response.body).optString("error")
                        .takeIf(String::isNotBlank)
            }.getOrNull()
            val message = when (response.statusCode) {
                401 -> "Spotify sign-in has expired. Sign out in Settings, then sign in again."
                403 -> "Spotify denied this Web Player action for the current account."
                else -> "Spotify couldn't complete this Web Player action (HTTP ${response.statusCode})."
            }
            throw IllegalStateException(if (detail == null) message else "$message $detail")
        }
        val json = response.body.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val detail = errors.optJSONObject(0)?.optString("message")
                ?.takeIf(String::isNotBlank)
                ?: "Spotify returned an error."
            throw IllegalStateException(detail)
        }
        return json
    }

    private fun invalidatePlaylistListResponses(token: String) {
        val prefix = "${tokenCacheScope(token)}|$BASE/me/playlists"
        responseCache.keys
            .filter { it.startsWith(prefix) }
            .forEach(responseCache::remove)
    }

    private fun executeMutation(request: Request, action: String): Boolean {
        val retryAt = currentRetryAtMs()
        if (retryAt > System.currentTimeMillis()) throw RateLimitedException(retryAt)
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 429) {
                val retryAfterMs = response.header("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.let { TimeUnit.SECONDS.toMillis(it.coerceAtLeast(1L)) }
                    ?: DEFAULT_RETRY_AFTER_MS
                throw RateLimitedException(
                    extendRetryAtMs(System.currentTimeMillis() + retryAfterMs),
                )
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(spotifyErrorMessage(response.code, body, action))
            }
            true
        }
    }

    private fun getJson(token: String, url: String): JSONObject {
        // Cache account-specific GET responses in memory. The token is hashed in the cache key
        // so neither the Spotify credential nor private library data is persisted to disk.
        val cacheKey = "${tokenCacheScope(token)}|$url"
        cachedBody(cacheKey, RESPONSE_CACHE_TTL_MS)?.let { return JSONObject(it) }

        val retryAtMs = currentRetryAtMs()
        val remainingLimitMs = retryAtMs - System.currentTimeMillis()
        if (remainingLimitMs > 0L) {
            cachedBody(cacheKey, STALE_RESPONSE_TTL_MS)?.let { return JSONObject(it) }
            throw RateLimitedException(retryAtMs)
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
                    val retryAt = extendRetryAtMs(System.currentTimeMillis() + retryAfterMs)
                    cachedBody(cacheKey, STALE_RESPONSE_TTL_MS)?.let {
                        return@use JSONObject(it)
                    }
                    throw RateLimitedException(retryAt)
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

    private fun spotifyId(item: JSONObject, uriKey: String = "uri"): String? =
        item.optString("id").takeIf(String::isNotBlank)
            ?: item.optString(uriKey).takeIf(String::isNotBlank)
                ?.substringAfterLast(':')
                ?.takeIf(String::isNotBlank)

    private fun firstSourceUrl(container: JSONObject?): String? =
        container?.optJSONArray("sources")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf(String::isNotBlank)

    private fun firstImageItemSourceUrl(container: JSONObject?, key: String): String? =
        container?.optJSONObject(key)
            ?.optJSONArray("items")
            ?.optJSONObject(0)
            ?.let { firstSourceUrl(it) ?: firstSourceUrl(it.optJSONObject("image")) }
            ?.takeIf(String::isNotBlank)

    private fun graphQlItems(search: JSONObject, key: String): JSONArray? =
        search.optJSONObject(key)?.optJSONArray("items")

    private fun graphQlItemData(item: JSONObject): JSONObject =
        item.optJSONObject("item")?.optJSONObject("data")
            ?: item.optJSONObject("data")
            ?: item

    private fun graphQlArtistNames(artists: JSONObject?): String {
        val items = artists?.optJSONArray("items") ?: return ""
        return buildList {
            for (index in 0 until items.length()) {
                val profile = items.optJSONObject(index)?.optJSONObject("profile") ?: continue
                profile.optString("name").takeIf(String::isNotBlank)?.let(::add)
            }
        }.joinToString(", ")
    }

    private fun graphQlArtistIds(artists: JSONObject?): List<String> {
        val items = artists?.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val artist = items.optJSONObject(index) ?: continue
                spotifyId(artist)?.let(::add)
            }
        }
    }

    private fun parseGraphQlTrack(item: JSONObject): SpotifyTrack? {
        val track = item.optJSONObject("track") ?: graphQlItemData(item)
        val uri = track.optString("uri")
        val id = spotifyId(track) ?: return null
        val album = track.optJSONObject("albumOfTrack")
        return SpotifyTrack(
            id = id,
            uri = uri.ifBlank { "spotify:track:$id" },
            title = track.optString("name", "Track"),
            artists = graphQlArtistNames(track.optJSONObject("artists")),
            artistIds = graphQlArtistIds(track.optJSONObject("artists")),
            album = album?.optString("name").orEmpty(),
            imageUrl = firstSourceUrl(album?.optJSONObject("coverArt")),
            durationMs = track.optJSONObject("duration")
                ?.optLong("totalMilliseconds", 0L) ?: 0L,
            playCount = track.optString("playcount").toLongOrNull() ?: 0L,
        )
    }

    private fun parseGraphQlArtist(item: JSONObject): SpotifyArtist? {
        val artist = graphQlItemData(item)
        val id = spotifyId(artist) ?: return null
        return SpotifyArtist(
            id = id,
            name = artist.optJSONObject("profile")?.optString("name")
                ?.takeIf(String::isNotBlank) ?: "Artist",
            imageUrl = firstSourceUrl(
                artist.optJSONObject("visuals")?.optJSONObject("avatarImage"),
            ),
        )
    }

    private fun parseGraphQlAlbum(item: JSONObject): SpotifyAlbum? {
        val album = graphQlItemData(item)
        val id = spotifyId(album) ?: return null
        val date = album.optJSONObject("date")
        val count = album.optJSONObject("tracks")?.optInt("totalCount", -1)
            ?.takeIf { it >= 0 }
        return SpotifyAlbum(
            id = id,
            name = album.optString("name", "Album"),
            artists = graphQlArtistNames(album.optJSONObject("artists")),
            imageUrl = firstSourceUrl(album.optJSONObject("coverArt")),
            trackCount = count,
            releaseDate = date?.optString("isoString")
                ?.takeIf(String::isNotBlank)
                ?: date?.optInt("year", 0)?.takeIf { it > 0 }?.toString().orEmpty(),
        )
    }

    private fun parseGraphQlPlaylist(item: JSONObject): SpotifyPlaylist? {
        val playlist = graphQlItemData(item)
        val id = spotifyId(playlist) ?: return null
        val owner = playlist.optJSONObject("ownerV2")?.optJSONObject("data")
        val count = playlist.optInt("trackCount", -1)
            .takeIf { it >= 0 }
        return SpotifyPlaylist(
            id = id,
            name = playlist.optString("name", "Playlist"),
            description = playlist.optString("description", ""),
            imageUrl = firstImageItemSourceUrl(playlist, "images"),
            trackCount = count,
            ownerName = owner?.optString("name").orEmpty(),
        )
    }

    private fun parseGraphQlPodcast(item: JSONObject): SpotifyPodcast? {
        val podcast = graphQlItemData(item)
        val id = spotifyId(podcast) ?: return null
        val cover = podcast.optJSONObject("coverArt")
            ?: podcast.optJSONObject("coverImage")
        return SpotifyPodcast(
            id = id,
            name = podcast.optString("name", "Podcast"),
            publisher = podcast.optString("publisher", ""),
            imageUrl = firstSourceUrl(cover)
                ?: firstImageItemSourceUrl(podcast, "images"),
        )
    }

    private fun parseGraphQlEpisode(item: JSONObject): SpotifyEpisode? {
        val episode = graphQlItemData(item)
        val id = spotifyId(episode) ?: return null
        val podcast = episode.optJSONObject("podcast")
            ?: episode.optJSONObject("podcastV2")?.optJSONObject("data")
        val cover = episode.optJSONObject("coverArt")
            ?: podcast?.optJSONObject("coverArt")
        return SpotifyEpisode(
            id = id,
            name = episode.optString("name", "Episode"),
            podcast = podcast?.optString("name").orEmpty(),
            imageUrl = firstSourceUrl(cover)
                ?: firstImageItemSourceUrl(episode, "images"),
            durationMs = episode.optJSONObject("duration")
                ?.optLong("totalMilliseconds", 0L) ?: 0L,
        )
    }

    private fun <T> parseGraphQlItems(
        search: JSONObject,
        key: String,
        parse: (JSONObject) -> T?,
    ): List<T> {
        val items = graphQlItems(search, key) ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                parse(item)?.let(::add)
            }
        }
    }

    private fun parseGraphQlSearch(response: JSONObject): SpotifySearchResults {
        val search = response.optJSONObject("data")?.optJSONObject("searchV2")
            ?: throw IllegalStateException("Spotify returned an unreadable search response.")
        return SpotifySearchResults(
            tracks = parseGraphQlItems(search, "tracksV2", ::parseGraphQlTrack),
            artists = parseGraphQlItems(search, "artists", ::parseGraphQlArtist),
            albums = parseGraphQlItems(search, "albumsV2", ::parseGraphQlAlbum),
            playlists = parseGraphQlItems(search, "playlists", ::parseGraphQlPlaylist),
            podcasts = parseGraphQlItems(search, "podcasts", ::parseGraphQlPodcast),
            episodes = parseGraphQlItems(search, "episodes", ::parseGraphQlEpisode),
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

    suspend fun createPlaylist(
        spDc: String,
        name: String,
        collaborative: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Enter a playlist name." }
        require(normalizedName.length <= 100) { "Spotify playlist names must be 100 characters or fewer." }

        val token = accessToken(spDc)
        if (!collaborative) {
            createRegularPlaylistWithWebPlayer(spDc, token, normalizedName)
        } else {
            createCollaborativePlaylistWithSpotifyApi(spDc, token, normalizedName)
        }
    }

    private fun createCollaborativePlaylistWithSpotifyApi(
        spDc: String,
        token: String,
        name: String,
    ): String {
        val userId = getJson(token, "$BASE/me").optString("id")
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Spotify did not return the signed-in account ID.")
        val payload = JSONObject()
            .put("name", name)
            .put("public", false)
            .put("collaborative", true)
        val created = postSpotifyJson(
            token,
            "$BASE/users/${URLEncoder.encode(userId, "UTF-8")}/playlists",
            payload,
        )
        val id = created.optString("id").takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Spotify did not confirm that the playlist was created.")
        invalidatePlaylistListResponses(token)
        SpotifyWebPlayerRepository.invalidateAccountCache(spDc)
        return id
    }

    private suspend fun createRegularPlaylistWithWebPlayer(
        spDc: String,
        token: String,
        name: String,
    ): String {
        val pending = pendingWebPlaylistSetup(spDc)
        if (pending != null) {
            if (pending.name != name) {
                throw IllegalStateException(
                    "Spotify already created \"${pending.name}\", but library sync is incomplete. " +
                        "Enter that name and tap Create again; StreamCloud will finish the existing playlist.",
                )
            }
            return finishPendingWebPlaylistSetup(spDc, token, pending)
        }

        val retryAt = playlistCreateCooldownUntilMs(collaborative = false)
        if (retryAt > System.currentTimeMillis()) throw RateLimitedException(retryAt)

        val profile = pathfinderJson(cacheKey = null) {
            SpotifyCanvasRepository.profileAttributes(token, spDc)
        }
        if ((profile.optJSONArray("errors")?.length() ?: 0) > 0) {
            throw IllegalStateException(
                graphQlError(profile, "Spotify could not confirm the signed-in profile."),
            )
        }
        val username = profile.optJSONObject("data")
            ?.optJSONObject("me")
            ?.optJSONObject("profile")
            ?.optString("username")
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Spotify did not return the signed-in profile name.")

        val secondRetryAt = playlistCreateCooldownUntilMs(collaborative = false)
        if (secondRetryAt > System.currentTimeMillis()) throw RateLimitedException(secondRetryAt)

        val attributes = JSONObject().put("name", name)
        val payload = JSONObject().put(
            "ops",
            JSONArray().put(
                JSONObject()
                    .put("kind", "UPDATE_LIST_ATTRIBUTES")
                    .put(
                        "updateListAttributes",
                        JSONObject().put(
                            "newAttributes",
                            JSONObject().put("values", attributes),
                        ),
                    ),
            ),
        )
        val created = webPlayerJson(
            token = token,
            spDc = spDc,
            method = "POST",
            path = "/playlist/v2/playlist?format=json",
            body = payload.toString(),
        )
        val playlistUri = created.optString("uri")
            .takeIf { it.matches(Regex("^spotify:playlist:[A-Za-z0-9]+$")) }
            ?: throw IllegalStateException(
                "Spotify did not confirm the new playlist URI. No follow-up was attempted.",
            )
        val playlistId = playlistUri.removePrefix("spotify:playlist:")
        val pendingSetup = PendingWebPlaylistSetup(
            playlistId = playlistId,
            name = name,
            username = username,
            addedToLibrary = false,
        )
        storePendingWebPlaylistSetup(spDc, pendingSetup)
        if (created.optString("revision").isBlank()) {
            throw IllegalStateException(
                "Spotify created \"$name\", but did not return its playlist revision. " +
                    "Tap Create again with the same name to finish syncing it without creating a duplicate.",
            )
        }
        return finishPendingWebPlaylistSetup(spDc, token, pendingSetup)
    }

    private suspend fun finishPendingWebPlaylistSetup(
        spDc: String,
        token: String,
        initial: PendingWebPlaylistSetup,
    ): String {
        var pending = initial
        try {
            if (!pending.addedToLibrary) {
                val follow = pathfinderJson(cacheKey = null) {
                    SpotifyCanvasRepository.addPlaylistToLibrary(
                        token = token,
                        spDc = spDc,
                        playlistUri = "spotify:playlist:${pending.playlistId}",
                    )
                }
                if ((follow.optJSONArray("errors")?.length() ?: 0) > 0) {
                    throw IllegalStateException(
                        graphQlError(follow, "Spotify could not add the playlist to your library."),
                    )
                }
                if (
                    follow.optJSONObject("data")
                        ?.optJSONObject("addItemsToRootlist") == null
                ) {
                    throw IllegalStateException(
                        "Spotify did not confirm that the playlist was added to your library.",
                    )
                }
                pending = pending.copy(addedToLibrary = true)
                storePendingWebPlaylistSetup(spDc, pending)
            }

            val encodedUsername = URLEncoder.encode(pending.username, "UTF-8")
            val playlistUri = "spotify:playlist:${pending.playlistId}"
            val operation = JSONObject()
                .put("kind", "UPDATE_ITEM_ATTRIBUTES")
                .put(
                    "updateItemAttributes",
                    JSONObject()
                        .put(
                            "newAttributes",
                            JSONObject().put(
                                "values",
                                JSONObject().put("public", false),
                            ),
                        )
                        .put("item", JSONObject().put("uri", playlistUri)),
                )
            val delta = JSONObject()
                .put("ops", JSONArray().put(operation))
                .put(
                    "info",
                    JSONObject().put("source", JSONObject().put("client", "WEBPLAYER")),
                )
            val permission = webPlayerJson(
                token = token,
                spDc = spDc,
                method = "POST",
                path = "/playlist/v2/user/$encodedUsername/rootlist/changes?format=json",
                body = JSONObject().put("deltas", JSONArray().put(delta)).toString(),
            )
            val permissionConfirmed = permission.optString("revision").isNotBlank() ||
                (permission.optJSONArray("resultingRevisions")?.length() ?: 0) > 0 ||
                permission.optJSONObject("syncResult") != null
            if (!permissionConfirmed) {
                throw IllegalStateException(
                    "Spotify did not confirm that the playlist was saved as private.",
                )
            }

            clearPendingWebPlaylistSetup(spDc)
            invalidatePlaylistListResponses(token)
            SpotifyWebPlayerRepository.invalidateAccountCache(spDc)
            return pending.playlistId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Spotify created \"${pending.name}\", but StreamCloud couldn't finish syncing it. " +
                    "After the cooldown, tap Create again with the same name; it will finish this playlist " +
                    "instead of creating a duplicate. ${failure.message.orEmpty()}",
                failure,
            )
        }
    }

    suspend fun createPlaylistFolder(spDc: String, name: String): String =
        withContext(Dispatchers.IO) {
            val normalizedName = name.trim()
            require(normalizedName.isNotEmpty()) { "Enter a folder name." }
            require(normalizedName.length <= 100) { "Spotify folder names must be 100 characters or fewer." }

            val token = accessToken(spDc)
            val userId = getJson(token, "$BASE/me").optString("id")
                .takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Spotify did not return the signed-in account ID.")
            val encodedUser = URLEncoder.encode(userId, "UTF-8")
            val rootlistPath =
                "/playlist/v2/user/$encodedUser/rootlist" +
                    "?decorate=revision,length,attributes,timestamp,owner,capabilities,status_code" +
                    "&bustCache=${System.currentTimeMillis()}"
            val rootlist = webPlayerJson(token, spDc, "GET", rootlistPath)
            if (rootlist.optString("revision").isBlank()) {
                throw IllegalStateException("Spotify did not return the current library revision.")
            }

            val id = newSpotifyFolderId()
            val encodedName = URLEncoder.encode(normalizedName, "UTF-8").replace("+", "%20")
            val startUri = "spotify:start-group:$id:$encodedName"
            val endUri = "spotify:end-group:$id"
            val add = JSONObject()
                .put("addFirst", true)
                .put(
                    "items",
                    JSONArray()
                        .put(JSONObject().put("uri", startUri))
                        .put(JSONObject().put("uri", endUri)),
                )
            val operation = JSONObject()
                .put("kind", "ADD")
                .put("add", add)
            val delta = JSONObject()
                .put("ops", JSONArray().put(operation))
                .put(
                    "info",
                    JSONObject().put("source", JSONObject().put("client", "WEBPLAYER")),
                )
            val payload = JSONObject().put("deltas", JSONArray().put(delta))
            val changeResult = webPlayerJson(
                token,
                spDc,
                "POST",
                "/playlist/v2/user/$encodedUser/rootlist/changes",
                payload.toString(),
            )
            val confirmed = changeResult.optString("revision").isNotBlank() ||
                (changeResult.optJSONArray("resultingRevisions")?.length() ?: 0) > 0 ||
                changeResult.optJSONObject("syncResult") != null
            if (!confirmed) {
                throw IllegalStateException(
                    "Spotify did not confirm that the folder was added to your library.",
                )
            }
            invalidatePlaylistListResponses(token)
            SpotifyWebPlayerRepository.invalidateAccountCache(spDc)
            id
        }

    suspend fun createBlendInvitation(spDc: String): String = withContext(Dispatchers.IO) {
        val token = accessToken(spDc)
        val invite = webPlayerJson(
            token = token,
            spDc = spDc,
            method = "POST",
            path = "/blend-invitation/v1/generate",
        ).optString("invite")
        if (!invite.startsWith("https://open.spotify.com/blend/")) {
            throw IllegalStateException("Spotify did not return a valid Blend invitation.")
        }
        invite
    }

    private fun newSpotifyFolderId(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
            val url = "$BASE/playlists/$playlistId/items?limit=100&offset=$offset&fields=${URLEncoder.encode(fields, "UTF-8")}"
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

    suspend fun removeTrack(
        spDc: String,
        playlistId: String,
        trackUri: String,
        position: Int? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        require(playlistId.matches(Regex("[A-Za-z0-9]+"))) { "Invalid Spotify playlist ID." }
        require(trackUri.startsWith("spotify:track:")) { "Invalid Spotify track URI." }
        val token = accessToken(spDc)
        val track = JSONObject().put("uri", trackUri).apply {
            position?.let { put("positions", JSONArray().put(it)) }
        }
        val body = JSONObject().put("tracks", JSONArray().put(track))
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$BASE/playlists/$playlistId/items")
            .header("Authorization", authHeader(token))
            .header("Accept", "application/json")
            .delete(body)
            .build()
        executeMutation(request, "remove this track from the playlist")
        invalidatePlaylistTrackResponses(token, playlistId)
        SpotifyWebPlayerRepository.invalidateAccountCache(spDc)
        true
    }

    suspend fun addTrack(spDc: String, playlistId: String, trackUri: String): Boolean =
        withContext(Dispatchers.IO) {
            require(playlistId.matches(Regex("[A-Za-z0-9]+"))) { "Invalid Spotify playlist ID." }
            require(trackUri.startsWith("spotify:track:")) { "Invalid Spotify track URI." }
            val token = accessToken(spDc)
            val response = pathfinderJson(cacheKey = null) {
                SpotifyCanvasRepository.addTrackToPlaylist(
                    token = token,
                    spDc = spDc,
                    playlistId = playlistId,
                    trackUri = trackUri,
                )
            }
            if ((response.optJSONArray("errors")?.length() ?: 0) > 0) {
                throw IllegalStateException(
                    graphQlError(response, "Spotify couldn't add this track to the playlist."),
                )
            }
            if (response.optJSONObject("data")?.optJSONObject("addItemsToPlaylist") == null) {
                throw IllegalStateException("Spotify did not confirm that the track was added.")
            }
            invalidatePlaylistTrackResponses(token, playlistId)
            SpotifyWebPlayerRepository.invalidateAccountCache(spDc)
            true
        }

    suspend fun isTrackSaved(spDc: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        require(trackId.matches(Regex("[A-Za-z0-9]+"))) { "Invalid Spotify track ID." }
        val token = accessToken(spDc)
        val encodedId = URLEncoder.encode(trackId, "UTF-8")
        getJsonArray(token, "$BASE/me/tracks/contains?ids=$encodedId").optBoolean(0, false)
    }

    suspend fun saveTrack(
        spDc: String,
        trackId: String,
        saved: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        require(trackId.matches(Regex("[A-Za-z0-9]+"))) { "Invalid Spotify track ID." }
        val token = accessToken(spDc)
        pathfinderJson(cacheKey = null) {
            SpotifyCanvasRepository.setTrackSaved(token, spDc, trackId, saved)
        }
        invalidateSavedTrackResponses(token)
        SpotifyWebPlayerRepository.invalidateAccountCache(spDc)
        true
    }

    // ── Search (for the "Add songs" sheet) ────────────────────────────────────

    suspend fun searchTracks(spDc: String, query: String): List<SpotifyTrack> =
        searchCatalog(spDc, query).tracks

    private suspend fun pathfinderJson(
        cacheKey: String?,
        request: () -> SpotifyCanvasRepository.PathfinderResponse,
    ): JSONObject {
        if (cacheKey != null) {
            cachedBody(cacheKey, RESPONSE_CACHE_TTL_MS)?.let { return JSONObject(it) }
        }
        val retryAtMs = currentPathfinderRetryAtMs()
        if (retryAtMs > System.currentTimeMillis()) {
            if (cacheKey != null) {
                cachedBody(cacheKey, STALE_RESPONSE_TTL_MS)?.let { return JSONObject(it) }
            }
            throw RateLimitedException(retryAtMs)
        }

        val response = request()
        if (response.statusCode == 429) {
            val retryAfterMs = response.retryAfter
                ?.trim()
                ?.toLongOrNull()
                ?.let { TimeUnit.SECONDS.toMillis(it.coerceAtLeast(1L)) }
                ?: DEFAULT_RETRY_AFTER_MS
            val retryAt = extendPathfinderRetryAtMs(System.currentTimeMillis() + retryAfterMs)
            if (cacheKey != null) {
                cachedBody(cacheKey, STALE_RESPONSE_TTL_MS)?.let { return JSONObject(it) }
            }
            throw RateLimitedException(retryAt)
        }
        if (response.statusCode !in 200..299) {
            val message = when (response.statusCode) {
                401 -> "Spotify sign-in has expired. Sign out in StreamCloud Settings, then sign in again."
                403 -> "Spotify denied access to this Spotify page (HTTP 403). Reconnect the account and try again."
                else -> "Spotify request failed (HTTP ${response.statusCode})."
            }
            throw IllegalStateException(message)
        }

        val body = response.body
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Spotify returned an empty response.")
        val json = JSONObject(body)
        if (cacheKey != null) storeBody(cacheKey, body)
        return json
    }

    private fun graphQlError(response: JSONObject, fallback: String): String =
        response.optJSONArray("errors")
            ?.optJSONObject(0)
            ?.optString("message")
            ?.takeIf(String::isNotBlank)
            ?: fallback

    private fun parseArray(items: JSONArray?, parse: (JSONObject) -> SpotifyPlaylist?): List<SpotifyPlaylist> {
        if (items == null) return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(parse)?.let(::add)
            }
        }
    }

    private fun parseArtistsArray(items: JSONArray?): List<SpotifyArtist> {
        if (items == null) return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(::parseGraphQlArtist)?.let(::add)
            }
        }
    }

    private fun parseAlbumsArray(items: JSONArray?): List<SpotifyAlbum> {
        if (items == null) return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(::parseGraphQlAlbum)?.let(::add)
            }
        }
    }

    private fun parseArtistOverview(response: JSONObject): SpotifyArtistOverview {
        val artist = response.optJSONObject("data")?.optJSONObject("artistUnion")
            ?: throw IllegalStateException(graphQlError(response, "Spotify artist could not be loaded."))
        val parsedArtist = parseGraphQlArtist(artist)
            ?: throw IllegalStateException("Spotify did not return this artist.")
        val profile = artist.optJSONObject("profile")
        val stats = artist.optJSONObject("stats")
        val discography = artist.optJSONObject("discography")
        val related = artist.optJSONObject("relatedContent")
        val playlists = profile?.optJSONObject("playlistsV2")?.optJSONArray("items")
        val featuring = related?.optJSONObject("featuringV2")?.optJSONArray("items")
        val relatedArtists = related?.optJSONObject("relatedArtists")?.optJSONArray("items")
        val topTracks = discography?.optJSONObject("topTracks")?.optJSONArray("items")
            ?.let { items ->
                buildList {
                    for (index in 0 until items.length()) {
                        val track = items.optJSONObject(index)?.optJSONObject("track") ?: continue
                        parseGraphQlTrack(track)?.let(::add)
                    }
                }
            }.orEmpty()
        val clips = listOf(
            artist.optJSONObject("relatedMusicVideos")?.optJSONArray("items"),
            artist.optJSONObject("unmappedMusicVideos")?.optJSONArray("items"),
        ).flatMap(::parseMusicVideos).distinctBy { it.id }
        val header = artist.optJSONObject("headerImage")?.optJSONObject("data")
        val galleryImage = firstImageItemSourceUrl(
            artist.optJSONObject("visuals")?.optJSONObject("gallery"),
            "items",
        )
        return SpotifyArtistOverview(
            artist = parsedArtist.copy(
                followers = stats?.optLong("followers", parsedArtist.followers) ?: parsedArtist.followers,
            ),
            headerImageUrl = firstSourceUrl(header),
            biography = profile?.optJSONObject("biography")?.optString("text").orEmpty(),
            verified = artist.optJSONObject("onPlatformReputationTrait")
                ?.optJSONObject("verification")?.optBoolean("isVerified") == true,
            monthlyListeners = stats?.optLong("monthlyListeners", 0L) ?: 0L,
            worldRank = stats?.optInt("worldRank")?.takeIf { it > 0 },
            aboutImageUrl = galleryImage ?: parsedArtist.imageUrl,
            topTracks = topTracks,
            popularReleases = parseAlbumsArray(
                discography?.optJSONObject("popularReleasesAlbums")?.optJSONArray("items"),
            ),
            featuringPlaylists = parseArray(featuring, ::parseGraphQlPlaylist),
            artistPlaylists = parseArray(playlists, ::parseGraphQlPlaylist),
            relatedArtists = parseArtistsArray(relatedArtists),
            clips = clips,
            saved = artist.optBoolean("saved", false),
        )
    }

    private fun parseMusicVideos(items: JSONArray?): List<SpotifyMusicVideo> {
        if (items == null) return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val wrapper = items.optJSONObject(index) ?: continue
                val video = wrapper.optJSONObject("data")
                    ?: wrapper.optJSONObject("item")?.optJSONObject("data")
                    ?: wrapper
                val id = spotifyId(video) ?: continue
                val title = video.optString("name")
                    .ifBlank { video.optString("title") }
                    .ifBlank { "Spotify clip" }
                val image = firstSourceUrl(video.optJSONObject("coverArt"))
                    ?: firstSourceUrl(video.optJSONObject("thumbnail"))
                    ?: firstImageItemSourceUrl(video, "images")
                add(
                    SpotifyMusicVideo(
                        id = id,
                        title = title,
                        imageUrl = image,
                        durationMs = video.optJSONObject("duration")
                            ?.optLong("totalMilliseconds", 0L) ?: 0L,
                    ),
                )
            }
        }
    }

    private fun parseAlbumDetails(response: JSONObject): SpotifyAlbumDetails {
        val albumData = response.optJSONObject("data")?.optJSONObject("albumUnion")
            ?: throw IllegalStateException(graphQlError(response, "Spotify album could not be loaded."))
        val album = parseGraphQlAlbum(albumData)
            ?: throw IllegalStateException("Spotify did not return this album.")
        val trackItems = albumData.optJSONObject("tracksV2")?.optJSONArray("items")
        val tracks = if (trackItems == null) emptyList() else buildList {
            for (index in 0 until trackItems.length()) {
                val wrapper = trackItems.optJSONObject(index) ?: continue
                val track = parseGraphQlTrack(wrapper) ?: continue
                add(track.copy(album = album.name, imageUrl = album.imageUrl))
            }
        }
        val artists = parseArtistsArray(albumData.optJSONObject("artists")?.optJSONArray("items"))
        val moreByArtist = buildList {
            val owners = albumData.optJSONObject("moreAlbumsByArtist")?.optJSONArray("items")
                ?: JSONArray()
            for (index in 0 until owners.length()) {
                val owner = owners.optJSONObject(index) ?: continue
                val discography = owner.optJSONObject("discography")
                    ?: owner.optJSONObject("data")?.optJSONObject("discography")
                    ?: continue
                val releases = parseAlbumsArray(
                    discography.optJSONObject("popularReleasesAlbums")?.optJSONArray("items"),
                )
                addAll(releases)
            }
        }.filterNot { it.id == album.id }.distinctBy { it.id }
        return SpotifyAlbumDetails(
            album = album,
            albumType = albumData.optString("type", "ALBUM"),
            label = albumData.optString("label"),
            artists = artists,
            tracks = tracks,
            moreByArtist = moreByArtist,
        )
    }

    suspend fun searchCatalog(spDc: String, query: String): SpotifySearchResults =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@withContext SpotifySearchResults()
            val token = accessToken(spDc)
            val cacheKey =
                "${tokenCacheScope(token)}|pathfinder-v2/searchDesktop/${URLEncoder.encode(cleanQuery, "UTF-8")}"
            val response = pathfinderJson(cacheKey) {
                SpotifyCanvasRepository.searchCatalog(token, spDc, cleanQuery)
            }
            val hasSearchData = response.optJSONObject("data")?.optJSONObject("searchV2") != null
            if (!hasSearchData) {
                throw IllegalStateException(graphQlError(response, "Spotify catalogue search failed."))
            }
            parseGraphQlSearch(response)
        }

    suspend fun getArtistOverview(spDc: String, artistId: String): SpotifyArtistOverview =
        withContext(Dispatchers.IO) {
            require(artistId.matches(Regex("[A-Za-z0-9]+"))) { "Invalid Spotify artist ID." }
            val token = accessToken(spDc)
            val cacheKey = "${tokenCacheScope(token)}|pathfinder-v2/queryArtistOverview/$artistId"
            val response = pathfinderJson(cacheKey) {
                SpotifyCanvasRepository.artistOverview(token, spDc, artistId)
            }
            parseArtistOverview(response)
        }

    suspend fun getAlbumDetails(spDc: String, albumId: String): SpotifyAlbumDetails =
        withContext(Dispatchers.IO) {
            require(albumId.matches(Regex("[A-Za-z0-9]+"))) { "Invalid Spotify album ID." }
            val token = accessToken(spDc)
            val cacheKey = "${tokenCacheScope(token)}|pathfinder-v2/getAlbum/$albumId"
            val response = pathfinderJson(cacheKey) {
                SpotifyCanvasRepository.albumDetails(token, spDc, albumId)
            }
            parseAlbumDetails(response)
        }

    suspend fun setArtistFollowed(spDc: String, artistId: String, followed: Boolean) =
        withContext(Dispatchers.IO) {
            require(artistId.matches(Regex("[A-Za-z0-9]+"))) { "Invalid Spotify artist ID." }
            val token = accessToken(spDc)
            val response = pathfinderJson(cacheKey = null) {
                SpotifyCanvasRepository.setArtistFollowed(token, spDc, artistId, followed)
            }
            val data = response.optJSONObject("data")
            val mutationName = if (followed) "addLibraryItems" else "removeLibraryItems"
            if (data?.optJSONObject(mutationName) == null) {
                throw IllegalStateException(graphQlError(response, "Spotify could not update this artist."))
            }
        }
}
