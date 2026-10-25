package com.streamcloud.app.data.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * StreamCloud-owned adapter for the Spotify Web Player's metadata operations.
 *
 * These web-player operations are undocumented and can change independently of the public API.
 * Spotify audio is not played here; the returned metadata is still matched to YouTube Music.
 */
object SpotifyWebPlayerRepository {
    private const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val HOME_HASH = "3357ffed7961629ba92b4e0a41514e4d5004a14355c964c23ce442205c9e44a1"
    private const val LIBRARY_HASH = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
    private const val SAVED_TRACKS_HASH = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    private const val PLAYLIST_HASH = "cd2275433b29f7316176e7b5b5e098ae7744724e1a52d63549c76636b3257749"
    private const val ALBUM_HASH = "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10"
    private const val ARTIST_HASH = "7f86ff63e38c24973a2842b672abe44c910c1973978dc8a4a0cb648edef34527"
    private const val PAGE_SIZE = 50
    private const val HOME_PAGE_SIZE = 20
    private const val CACHE_TTL_MS = 5L * 60L * 1000L
    private const val STALE_CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val DEFAULT_RETRY_MS = 60_000L
    private const val MAX_CACHE_ENTRIES = 256

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class CacheEntry(val body: String, val savedAtMs: Long)

    private val responseCache = ConcurrentHashMap<String, CacheEntry>()
    private val retryAtByAccount = ConcurrentHashMap<String, Long>()

    private data class SpotifySession(val token: String, val cookies: Map<String, String>)

    suspend fun getHomeFeed(cookie: String, forceRefresh: Boolean = false): SpotifyHomeFeed =
        withContext(Dispatchers.IO) {
            val session = session(cookie)
            val variables = JSONObject()
                .put("timeZone", java.util.TimeZone.getDefault().id)
                .put("sp_t", session.cookies["sp_t"].orEmpty())
                .put("facet", "")
                .put("sectionItemsLimit", HOME_PAGE_SIZE)
            val response = request(
                session = session,
                operation = "home",
                variables = variables,
                hash = HOME_HASH,
                forceRefresh = forceRefresh,
            )
            SpotifyHomeFeed(parseHomeSections(response))
        }

    suspend fun getPlaylistsPage(
        cookie: String,
        offset: Int = 0,
        forceRefresh: Boolean = false,
    ): SpotifyLibraryPage<SpotifyPlaylist> = getLibraryPage(cookie, "Playlists", offset, forceRefresh) {
        parsePlaylist(it)
    }

    suspend fun getAlbumsPage(
        cookie: String,
        offset: Int = 0,
        forceRefresh: Boolean = false,
    ): SpotifyLibraryPage<SpotifyAlbum> = getLibraryPage(cookie, "Albums", offset, forceRefresh) {
        parseAlbum(it)
    }

    suspend fun getArtistsPage(
        cookie: String,
        offset: Int = 0,
        forceRefresh: Boolean = false,
    ): SpotifyLibraryPage<SpotifyArtist> = getLibraryPage(cookie, "Artists", offset, forceRefresh) {
        parseArtist(it)
    }

    suspend fun getSavedTracksPage(
        cookie: String,
        offset: Int = 0,
        forceRefresh: Boolean = false,
    ): SpotifyLibraryPage<SpotifyTrack> = withContext(Dispatchers.IO) {
        require(offset >= 0) { "Spotify library offset cannot be negative." }
        val session = session(cookie)
        val variables = JSONObject()
            .put("offset", offset)
            .put("limit", PAGE_SIZE)
        val response = request(
            session = session,
            operation = "fetchLibraryTracks",
            variables = variables,
            hash = SAVED_TRACKS_HASH,
            forceRefresh = forceRefresh,
        )
        val library = response.optJSONObject("data")
            ?.optJSONObject("me")
            ?.optJSONObject("library")
            ?: throw IllegalStateException("Spotify did not return your saved tracks.")
        val tracks = library.optJSONObject("tracks")
        val rawItems = tracks?.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until rawItems.length()) {
                val trackWrapper = rawItems.optJSONObject(index)
                    ?.optJSONObject("track") ?: continue
                val trackData = trackWrapper.optJSONObject("data") ?: trackWrapper
                parseTrack(trackData, trackWrapper.optString("_uri"))?.let(::add)
            }
        }
        val total = tracks?.optInt("totalCount", rawItems.length()) ?: 0
        SpotifyLibraryPage(
            items = items,
            totalCount = total,
            nextOffset = nextOffset(offset, rawItems.length(), total),
        )
    }

    suspend fun getPlaylistTracksPage(
        cookie: String,
        playlistId: String,
        offset: Int = 0,
        forceRefresh: Boolean = false,
    ): SpotifyLibraryPage<SpotifyTrack> = withContext(Dispatchers.IO) {
        require(offset >= 0) { "Spotify playlist offset cannot be negative." }
        val session = session(cookie)
        val variables = JSONObject()
            .put("uri", "spotify:playlist:$playlistId")
            .put("offset", offset)
            .put("limit", PAGE_SIZE)
            .put("enableWatchFeedEntrypoint", true)
        val response = request(
            session = session,
            operation = "fetchPlaylist",
            variables = variables,
            hash = PLAYLIST_HASH,
            forceRefresh = forceRefresh,
        )
        val playlist = response.optJSONObject("data")
            ?.optJSONObject("playlistV2")
            ?: throw IllegalStateException("Spotify did not return this playlist.")
        val content = playlist.optJSONObject("content")
        val rawItems = content?.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until rawItems.length()) {
                val item = rawItems.optJSONObject(index)
                    ?.optJSONObject("itemV2")
                    ?.optJSONObject("data") ?: continue
                parseTrack(item)?.let(::add)
            }
        }
        val total = content?.optInt("totalCount", rawItems.length()) ?: rawItems.length()
        SpotifyLibraryPage(
            items = items,
            totalCount = total,
            nextOffset = nextOffset(offset, rawItems.length(), total),
        )
    }

    suspend fun getAlbumTracksPage(
        cookie: String,
        albumId: String,
        offset: Int = 0,
        forceRefresh: Boolean = false,
    ): SpotifyLibraryPage<SpotifyTrack> = withContext(Dispatchers.IO) {
        require(offset >= 0) { "Spotify album offset cannot be negative." }
        val session = session(cookie)
        val variables = JSONObject()
            .put("uri", "spotify:album:$albumId")
            .put("locale", "")
            .put("offset", offset)
            .put("limit", PAGE_SIZE)
        val response = request(
            session = session,
            operation = "getAlbum",
            variables = variables,
            hash = ALBUM_HASH,
            forceRefresh = forceRefresh,
        )
        val album = response.optJSONObject("data")
            ?.optJSONObject("albumUnion")
            ?: throw IllegalStateException("Spotify did not return this album.")
        val trackList = album.optJSONObject("tracksV2")
        val rawItems = trackList?.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until rawItems.length()) {
                val item = rawItems.optJSONObject(index)?.optJSONObject("track") ?: continue
                parseTrack(item)?.let(::add)
            }
        }
        val total = trackList?.optInt("totalCount", rawItems.length()) ?: rawItems.length()
        SpotifyLibraryPage(
            items = items,
            totalCount = total,
            nextOffset = nextOffset(offset, rawItems.length(), total),
        )
    }

    suspend fun getArtistTopTracks(
        cookie: String,
        artistId: String,
        forceRefresh: Boolean = false,
    ): SpotifyLibraryPage<SpotifyTrack> = withContext(Dispatchers.IO) {
        val session = session(cookie)
        val variables = JSONObject()
            .put("uri", "spotify:artist:$artistId")
            .put("locale", "")
            .put("preReleaseV2", false)
        val response = request(
            session = session,
            operation = "queryArtistOverview",
            variables = variables,
            hash = ARTIST_HASH,
            forceRefresh = forceRefresh,
        )
        val artist = response.optJSONObject("data")
            ?.optJSONObject("artistUnion")
            ?: throw IllegalStateException("Spotify did not return this artist.")
        val topTracks = artist.optJSONObject("discography")?.optJSONObject("topTracks")
            ?: throw IllegalStateException("Spotify did not return this artist's top tracks.")
        val rawItems = topTracks.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until rawItems.length()) {
                val track = rawItems.optJSONObject(index)?.optJSONObject("track") ?: continue
                parseTrack(track)?.let(::add)
            }
        }
        SpotifyLibraryPage(
            items = items,
            totalCount = topTracks.optInt("totalCount", rawItems.length()),
            nextOffset = null,
        )
    }

    private suspend fun <T> getLibraryPage(
        cookie: String,
        filter: String,
        offset: Int,
        forceRefresh: Boolean,
        parse: (JSONObject) -> T?,
    ): SpotifyLibraryPage<T> = withContext(Dispatchers.IO) {
        require(offset >= 0) { "Spotify library offset cannot be negative." }
        val session = session(cookie)
        val variables = JSONObject()
            .put("filters", JSONArray().put(filter))
            .put("order", JSONObject.NULL)
            .put("textFilter", "")
            .put("features", JSONArray())
            .put("limit", PAGE_SIZE)
            .put("offset", offset)
            .put("flatten", true)
            .put("expandedFolders", JSONArray())
            .put("folderUri", JSONObject.NULL)
            .put("includeFoldersWhenFlattening", true)
        val response = request(
            session = session,
            operation = "libraryV3",
            variables = variables,
            hash = LIBRARY_HASH,
            forceRefresh = forceRefresh,
        )
        val library = response.optJSONObject("data")
            ?.optJSONObject("me")
            ?.optJSONObject("libraryV3")
            ?: throw IllegalStateException("Spotify did not return your $filter library.")
        val rawItems = library.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until rawItems.length()) {
                val wrapper = rawItems.optJSONObject(index)?.optJSONObject("item") ?: continue
                val data = wrapper.optJSONObject("data") ?: continue
                // Spotify exposes Liked Songs as a pseudo-playlist in the Playlists filter;
                // StreamCloud has a dedicated Liked songs tab, so don't duplicate it here.
                if (data.optString("__typename") == "PseudoPlaylist") continue
                parse(data)?.let(::add)
            }
        }
        val total = library.optInt("totalCount", rawItems.length())
        SpotifyLibraryPage(
            items = items,
            totalCount = total,
            nextOffset = nextOffset(offset, rawItems.length(), total),
        )
    }

    private fun nextOffset(offset: Int, rawItemCount: Int, total: Int): Int? {
        val next = offset + rawItemCount
        return next.takeIf { rawItemCount > 0 && it < total }
    }

    private fun request(
        session: SpotifySession,
        operation: String,
        variables: JSONObject,
        hash: String,
        forceRefresh: Boolean,
    ): JSONObject {
        val cookies = session.cookies
        val token = session.token
        val accountKey = digest(cookies["sp_dc"].orEmpty().ifBlank { session.token })
        val payload = JSONObject()
            .put("operationName", operation)
            .put("variables", variables)
            .put(
                "extensions",
                JSONObject().put(
                    "persistedQuery",
                    JSONObject().put("version", 1).put("sha256Hash", hash),
                ),
            )
        val body = payload.toString()
        val cacheVariables = JSONObject(variables.toString()).apply { remove("sp_t") }
        val cacheMaterial = "$operation|$cacheVariables"
        val cacheKey = "$accountKey|${digest(cacheMaterial)}"
        if (!forceRefresh) cached(cacheKey, CACHE_TTL_MS)?.let { return JSONObject(it) }

        val remainingBackoff = (retryAtByAccount[accountKey] ?: 0L) - System.currentTimeMillis()
        if (remainingBackoff > 0L) {
            cached(cacheKey, STALE_CACHE_TTL_MS)?.let { return JSONObject(it) }
            throw rateLimitError(remainingBackoff)
        }
        retryAtByAccount.remove(accountKey)

        val cookieHeader = listOf("sp_dc", "sp_t", "sp_key")
            .mapNotNull { name -> cookies[name]?.takeIf(String::isNotBlank)?.let { "$name=$it" } }
            .joinToString("; ")
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            )
            .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
            .post(body.toRequestBody(jsonType))
            .build()

        return http.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.code == 429) {
                val waitMs = response.header("Retry-After")
                    ?.toLongOrNull()
                    ?.let { TimeUnit.SECONDS.toMillis(it.coerceAtLeast(1L)) }
                    ?: DEFAULT_RETRY_MS
                val retryAt = System.currentTimeMillis() + waitMs
                retryAtByAccount.merge(accountKey, retryAt) { previous, update -> maxOf(previous, update) }
                cached(cacheKey, STALE_CACHE_TTL_MS)?.let { return@use JSONObject(it) }
                throw rateLimitError(retryAt - System.currentTimeMillis())
            }
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401 -> "Spotify sign-in has expired. Sign out in Settings, then sign in again."
                    403 -> "Spotify denied access to this data (HTTP 403). Reconnect the account and retry."
                    else -> "Spotify web-player request failed (HTTP ${response.code})."
                }
                throw IllegalStateException(message)
            }
            if (responseBody.isBlank()) {
                throw IllegalStateException("Spotify returned an empty web-player response.")
            }

            val result = JSONObject(responseBody)
            if (result.optJSONObject("data") == null) {
                val message = result.optJSONArray("errors")
                    ?.optJSONObject(0)
                    ?.optString("message")
                    ?.takeIf(String::isNotBlank)
                if (message != null && (
                        message.contains("rate", ignoreCase = true) ||
                            message.contains("limit", ignoreCase = true) ||
                            message.contains("too many", ignoreCase = true) ||
                            message.contains("429")
                        )
                ) {
                    val retryAt = System.currentTimeMillis() + DEFAULT_RETRY_MS
                    retryAtByAccount.merge(accountKey, retryAt) { previous, update -> maxOf(previous, update) }
                    cached(cacheKey, STALE_CACHE_TTL_MS)?.let { return@use JSONObject(it) }
                }
                throw IllegalStateException(message ?: "Spotify could not load this web-player data.")
            }
            store(cacheKey, responseBody)
            result
        }
    }

    private fun rateLimitError(waitMs: Long) = IllegalStateException(
        "Spotify is rate-limiting web-player requests. Try again in " +
            "${((waitMs + 999L) / 1000L).coerceAtLeast(1L)} seconds.",
    )

    private fun cached(key: String, maxAgeMs: Long): String? {
        val entry = responseCache[key] ?: return null
        val age = System.currentTimeMillis() - entry.savedAtMs
        return entry.body.takeIf { age in 0..maxAgeMs }
    }

    private fun store(key: String, body: String) {
        if (responseCache.size >= MAX_CACHE_ENTRIES) {
            responseCache.entries.minByOrNull { it.value.savedAtMs }
                ?.let { responseCache.remove(it.key) }
        }
        responseCache[key] = CacheEntry(body, System.currentTimeMillis())
    }

    private fun spotifyCookies(cookie: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        cookie.split(';').forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@forEach
            val name = pair.substring(0, separator).trim()
            if (name in setOf("sp_dc", "sp_t", "sp_key")) {
                values[name] = pair.substring(separator + 1).trim()
            }
        }
        return values
    }

    private fun session(cookie: String): SpotifySession {
        if (cookie.isBlank()) {
            throw IllegalStateException("Sign in to Spotify in StreamCloud Settings to continue.")
        }
        val token = SpotifyCanvasRepository.getPersonalToken(cookie)
            ?: throw IllegalStateException(
                "Spotify sign-in has expired. Sign out of Spotify in Settings, then sign in again.",
            )
        val cookies = spotifyCookies(cookie).toMutableMap()
        listOf("sp_dc", "sp_t", "sp_key").forEach { name ->
            val refreshedCookie = SpotifyCanvasRepository.getStoredSessionCookie(name)
                ?.takeIf(String::isNotBlank)
            if (name == "sp_t" && refreshedCookie != null) {
                cookies[name] = refreshedCookie
            } else if (cookies[name].isNullOrBlank() && refreshedCookie != null) {
                cookies[name] = refreshedCookie
            }
        }
        return SpotifySession(token, cookies)
    }

    private fun parseHomeSections(response: JSONObject): List<SpotifyHomeSection> {
        val rawSections = response.optJSONObject("data")
            ?.optJSONObject("home")
            ?.optJSONObject("sectionContainer")
            ?.optJSONObject("sections")
            ?.optJSONArray("items")
            ?: throw IllegalStateException("Spotify did not return its personalized Home feed.")
        return buildList {
            for (index in 0 until rawSections.length()) {
                val section = rawSections.optJSONObject(index) ?: continue
                val data = section.optJSONObject("data") ?: continue
                val title = label(data.opt("title"))
                if (title.isBlank()) continue
                val rawItems = section.optJSONObject("sectionItems")?.optJSONArray("items") ?: JSONArray()
                val items = buildList {
                    for (itemIndex in 0 until rawItems.length()) {
                        val item = rawItems.optJSONObject(itemIndex) ?: continue
                        val wrapper = item.optJSONObject("content") ?: continue
                        val content = wrapper.optJSONObject("data") ?: continue
                        parseHomeCard(wrapper.optString("__typename"), content, item.optString("uri"))
                            ?.let(::add)
                    }
                }
                if (items.isNotEmpty()) {
                    add(
                        SpotifyHomeSection(
                            id = section.optString("uri").ifBlank { "section-$index" },
                            title = title,
                            subtitle = label(data.opt("subtitle")),
                            items = items,
                        ),
                    )
                }
            }
        }
    }

    private fun parseHomeCard(
        wrapperType: String,
        data: JSONObject,
        fallbackUri: String,
    ): SpotifyHomeCard? {
        val uri = data.optString("uri")
            .ifBlank { data.optString("_uri") }
            .ifBlank { fallbackUri }
        return when (wrapperType) {
            "TrackResponseWrapper" -> {
                val track = parseTrack(data, uri) ?: return null
                SpotifyHomeCard(track.id, track.title, track.artists, track.imageUrl, track = track)
            }
            "PlaylistResponseWrapper" -> {
                val playlist = parsePlaylist(data) ?: return null
                SpotifyHomeCard(
                    id = playlist.id,
                    title = playlist.name,
                    subtitle = playlist.ownerName,
                    imageUrl = playlist.imageUrl,
                    collectionId = playlist.id,
                )
            }
            "AlbumResponseWrapper" -> {
                val album = parseAlbum(data) ?: return null
                SpotifyHomeCard(
                    id = album.id,
                    title = album.name,
                    subtitle = album.artists,
                    imageUrl = album.imageUrl,
                    collectionId = "album:${album.id}",
                )
            }
            "ArtistResponseWrapper" -> {
                val artist = parseArtist(data) ?: return null
                SpotifyHomeCard(
                    id = artist.id,
                    title = artist.name,
                    subtitle = "Artist",
                    imageUrl = artist.imageUrl,
                    collectionId = "artist:${artist.id}",
                )
            }
            else -> {
                val id = uri.substringAfterLast(':').takeIf(String::isNotBlank) ?: return null
                val name = data.optString("name").takeIf(String::isNotBlank) ?: return null
                SpotifyHomeCard(
                    id = id,
                    title = name,
                    imageUrl = imageFrom(data),
                    collectionId = uri.takeIf { it.startsWith("spotify:playlist:") }?.let {
                        it.substringAfterLast(':')
                    },
                )
            }
        }
    }

    private fun parsePlaylist(data: JSONObject): SpotifyPlaylist? {
        val uri = data.optString("uri").ifBlank { data.optString("_uri") }
        val id = spotifyId(uri, "playlist") ?: return null
        val owner = data.optJSONObject("ownerV2")?.optJSONObject("data")
        val trackCount = data.optJSONObject("content")?.intOrNull("totalCount")
            ?: data.optJSONObject("tracksV2")?.intOrNull("totalCount")
            ?: data.optJSONObject("tracks")?.intOrNull("total")
        return SpotifyPlaylist(
            id = id,
            name = data.optString("name").takeIf(String::isNotBlank) ?: "Playlist",
            description = data.optString("description"),
            imageUrl = imageFrom(data),
            trackCount = trackCount,
            snapshotId = data.optString("revisionId"),
            ownerName = owner?.optString("name")
                ?.takeIf(String::isNotBlank)
                ?: owner?.optString("username").orEmpty(),
        )
    }

    private fun parseAlbum(data: JSONObject): SpotifyAlbum? {
        val uri = data.optString("uri").ifBlank { data.optString("_uri") }
        val id = spotifyId(uri, "album") ?: return null
        val artists = artistsFrom(data.optJSONObject("artists"))
        val date = data.optJSONObject("date")
        return SpotifyAlbum(
            id = id,
            name = data.optString("name").takeIf(String::isNotBlank) ?: "Album",
            artists = artists,
            imageUrl = imageFrom(data, "coverArt"),
            trackCount = data.optJSONObject("tracksV2")?.intOrNull("totalCount")
                ?: data.optJSONObject("tracks")?.intOrNull("totalCount"),
            releaseDate = date?.optString("isoString").orEmpty(),
        )
    }

    private fun parseArtist(data: JSONObject): SpotifyArtist? {
        val uri = data.optString("uri").ifBlank { data.optString("_uri") }
        val id = spotifyId(uri, "artist") ?: return null
        val profile = data.optJSONObject("profile")
        val visuals = data.optJSONObject("visuals")
        val followers = data.optJSONObject("stats")?.optLong("followers")
            ?: data.optJSONObject("followers")?.optLong("total")
            ?: 0L
        return SpotifyArtist(
            id = id,
            name = profile?.optString("name")
                ?.takeIf(String::isNotBlank)
                ?: data.optString("name").ifBlank { "Artist" },
            imageUrl = imageFrom(visuals ?: data, "avatarImage"),
            followers = followers,
        )
    }

    private fun parseTrack(data: JSONObject, fallbackUri: String = ""): SpotifyTrack? {
        val album = data.optJSONObject("albumOfTrack")
        val uri = data.optString("uri")
            .ifBlank { data.optString("_uri") }
            .ifBlank { fallbackUri }
        val id = spotifyId(uri, "track") ?: return null
        val artistItems = data.optJSONObject("artists")?.optJSONArray("items")
        val artistIds = buildList {
            if (artistItems != null) {
                for (index in 0 until artistItems.length()) {
                    val artistUri = artistItems.optJSONObject(index)?.optString("uri").orEmpty()
                    spotifyId(artistUri, "artist")?.let(::add)
                }
            }
        }
        val duration = data.optJSONObject("duration")?.optLong("totalMilliseconds")
            ?: data.optJSONObject("trackDuration")?.optLong("totalMilliseconds")
            ?: 0L
        return SpotifyTrack(
            id = id,
            uri = uri,
            title = data.optString("name").takeIf(String::isNotBlank) ?: return null,
            artists = artistsFrom(data.optJSONObject("artists")),
            artistIds = artistIds,
            album = album?.optString("name").orEmpty(),
            imageUrl = album?.let { imageFrom(it, "coverArt") },
            durationMs = duration,
        )
    }

    private fun artistsFrom(container: JSONObject?): String {
        val items = container?.optJSONArray("items") ?: return ""
        return buildList {
            for (index in 0 until items.length()) {
                val artist = items.optJSONObject(index) ?: continue
                val name = artist.optJSONObject("profile")?.optString("name")
                    ?.takeIf(String::isNotBlank)
                    ?: artist.optString("name").takeIf(String::isNotBlank)
                name?.let(::add)
            }
        }.joinToString(", ")
    }

    private fun JSONObject.intOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun imageFrom(data: JSONObject, field: String? = null): String? {
        val source = if (field == null) data else data.optJSONObject(field) ?: return null
        val sources = source.optJSONArray("sources")
            ?: source.optJSONObject("items")?.optJSONArray("sources")
        if (sources != null) {
            for (index in sources.length() - 1 downTo 0) {
                val url = sources.optJSONObject(index)?.optString("url")
                    ?.takeIf(String::isNotBlank)
                if (url != null) return url
            }
        }
        val images = source.optJSONObject("images")?.optJSONArray("items")
            ?: source.optJSONArray("images")
            ?: source.optJSONArray("items")
        if (images != null) {
            for (index in 0 until images.length()) {
                val image = images.optJSONObject(index) ?: continue
                image.optString("url").takeIf(String::isNotBlank)?.let { return it }
                val nestedSources = image.optJSONArray("sources") ?: continue
                for (sourceIndex in nestedSources.length() - 1 downTo 0) {
                    nestedSources.optJSONObject(sourceIndex)?.optString("url")
                        ?.takeIf(String::isNotBlank)
                        ?.let { return it }
                }
            }
        }
        return null
    }

    private fun label(value: Any?): String = when (value) {
        is JSONObject -> value.optString("transformedLabel")
            .ifBlank { value.optString("text") }
            .ifBlank { value.optString("translatedBaseText") }
        is String -> value
        else -> ""
    }

    private fun spotifyId(uri: String, type: String): String? =
        uri.takeIf { it.startsWith("spotify:$type:") }
            ?.substringAfterLast(':')
            ?.takeIf(String::isNotBlank)

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
