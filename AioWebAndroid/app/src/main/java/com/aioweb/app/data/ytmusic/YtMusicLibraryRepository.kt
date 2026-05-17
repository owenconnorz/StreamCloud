package com.aioweb.app.data.ytmusic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * High-level wrapper over [InnerTubeClient] — does the nested-JSON archaeology required
 * to turn raw browse responses into the typed models consumed by the Library tab.
 *
 * The JSON walkers here are deliberately defensive: every step is wrapped in `?.` /
 * `runCatching` so a YT Music layout change breaks one section at most, not the whole
 * sync.
 *
 * Browse IDs mirrored from Metrolist's YouTube.kt:
 *   • `FEmusic_liked_playlists`  → user's playlists + saved playlists
 *   • `FEmusic_liked_videos`     → Liked songs (a virtual playlist)
 *   • `FEmusic_library_corpus_track_artists` → Subscribed artists
 *   • `FEmusic_library_landing`  → Albums saved to the library
 */
object YtMusicLibraryRepository {

    private const val TAG = "YtMusicLibrary"

    /**
     * Like a song on the signed-in YouTube Music account.
     *
     * Fires the InnerTube `like/like` endpoint so the song appears in the
     * user's "Liked Music" YTM playlist. The local Room update (setting
     * `likedAt`) is handled separately by the caller so the UI stays
     * optimistic regardless of network outcome.
     *
     * @param cookie the user's YTM cookie string (from SettingsRepository)
     * @param videoId the YouTube video ID (NOT the full watch URL)
     * @return true if YTM acknowledged the like, false on error / not signed in
     */
    suspend fun likeSong(cookie: String, videoId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (cookie.isBlank() || videoId.isBlank()) return@withContext false
            runCatching { InnerTubeClient(cookie).likeSong(videoId) }
                .getOrElse { Log.w(TAG, "likeSong failed", it); false }
        }

    /**
     * Remove a like from the signed-in YouTube Music account.
     */
    suspend fun unlikeSong(cookie: String, videoId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (cookie.isBlank() || videoId.isBlank()) return@withContext false
            runCatching { InnerTubeClient(cookie).unlikeSong(videoId) }
                .getOrElse { Log.w(TAG, "unlikeSong failed", it); false }
        }

    /**
     * Fetch everything the Library tab shows in one go — playlists, albums, subscribed
     * artists, and the "Liked songs" virtual playlist. All four calls run in parallel,
     * and a failure in one subsection doesn't cancel the others.
     */
    suspend fun sync(cookie: String): YtMusicLibrary = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) return@withContext YtMusicLibrary(
            failureReason = "Not signed in — open Settings → Account and sign in to YouTube Music.",
        )
        val client = InnerTubeClient(cookie)
        try {
            coroutineScope {
                val playlistsJob = async { fetchLibraryPlaylists(client) }
                val likedJob = async { fetchLikedSongs(client) }
                val artistsJob = async { fetchLibraryArtists(client) }
                val albumsJob = async { fetchLibraryAlbums(client) }
                YtMusicLibrary(
                    likedSongs = likedJob.await(),
                    playlists = playlistsJob.await().filter { !it.isAlbum },
                    albums = albumsJob.await() + playlistsJob.await().filter { it.isAlbum },
                    artists = artistsJob.await(),
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "library sync crashed", e)
            YtMusicLibrary(failureReason = e.message ?: "Sync failed")
        }
    }

    /** Drain a paginated browse shelf — first page + every continuation token. */
    private suspend inline fun <T> drainBrowse(
        client: InnerTubeClient,
        browseId: String,
        crossinline parsePage: (JsonObject) -> List<T>,
    ): List<T> {
        val first = client.browse(browseId) ?: return emptyList()
        val out = parsePage(first).toMutableList()
        var token = first.findContinuationToken()
        var safety = 50
        while (!token.isNullOrBlank() && safety-- > 0) {
            val page = client.browseContinuation(token) ?: break
            val before = out.size
            out += parsePage(page)
            if (out.size == before) break
            token = page.findContinuationToken()
        }
        return out
    }

    private suspend fun fetchLibraryPlaylists(client: InnerTubeClient): List<YtmPlaylist> =
        drainBrowse(client, "FEmusic_liked_playlists") { resp ->
            resp.collectTwoRowItems().mapNotNull { parseTwoRowAsPlaylist(it) }
        }

    /**
     * YouTube Music exposes your albums under a separate `library_landing` browse ID.
     * It returns the same `musicTwoRowItemRenderer` shape as playlists, just with
     * `MUSIC_RELEASE_TYPE_ALBUM` / `MUSIC_RELEASE_TYPE_SINGLE` in the subtitle runs.
     */
    private suspend fun fetchLibraryAlbums(client: InnerTubeClient): List<YtmPlaylist> =
        drainBrowse(client, "FEmusic_liked_albums") { resp ->
            resp.collectTwoRowItems().mapNotNull { parseTwoRowAsPlaylist(it, forceIsAlbum = true) }
        }

    private suspend fun fetchLikedSongs(client: InnerTubeClient): List<YtmSong> =
        drainBrowse(client, "VLLM") { resp ->
            resp.collectResponsiveListItems().mapNotNull { parseResponsiveSong(it) }
        }

    private suspend fun fetchLibraryArtists(client: InnerTubeClient): List<YtmLibraryArtist> =
        drainBrowse(client, "FEmusic_library_corpus_track_artists") { resp ->
            resp.collectResponsiveListItems().mapNotNull { item ->
                val flexColumns = (item["flexColumns"] as? JsonArray) ?: return@mapNotNull null
                val nameRun = flexColumns.getOrNull(0)?.jsonObject
                    ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                    ?.get("text")
                val name = nameRun.runsText() ?: return@mapNotNull null
                val channelId = nameRun?.firstNavigationBrowseId() ?: return@mapNotNull null
                val subtitle = flexColumns.getOrNull(1)?.jsonObject
                    ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                    ?.get("text").runsText()
                val thumb = item["thumbnail"].bestThumbnail()
                YtmLibraryArtist(
                    channelId = channelId,
                    name = name,
                    thumbnail = thumb,
                    subtitle = subtitle,
                )
            }
        }

    /**
     * Load the FULL track list of a YT Music playlist by id, following
     * `continuations[].nextContinuationData` until exhausted.
     *
     * The first browse response only carries the first ~100 songs and a
     * continuation token; subsequent pages come from the `next` endpoint with
     * `?ctoken=...&continuation=...&type=next`. Metrolist parity — without
     * this, mega-playlists (Liked, history mixes) silently truncate at 100.
     */
    suspend fun playlistTracks(cookie: String, playlistId: String): List<YtmSong> =
        withContext(Dispatchers.IO) {
            val client = InnerTubeClient(cookie)
            val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            val first = client.browse(browseId) ?: return@withContext emptyList()

            val collected = mutableListOf<YtmSong>()
            var pageNum = 0
            var safetyPages = 50

            // Use the targeted extractor for the first page so we don't accidentally
            // collect songs from "Related" / "Recommended" shelves that YouTube appends
            // after the playlist content (those shelves also use musicResponsiveListItemRenderer
            // and were the source of the "wrong songs" / wrong song count bug).
            var (renderers, token) = extractPlaylistPage(first)
            Log.d(TAG, "playlist[$playlistId] page$pageNum: renderers=${renderers.size} token=${token?.take(40)}")
            collected += renderers.mapNotNull { parseResponsiveSong(it) }

            while (!token.isNullOrBlank() && safetyPages-- > 0) {
                pageNum++
                val page = client.browseContinuation(token)
                if (page == null) {
                    Log.w(TAG, "playlist[$playlistId] page$pageNum: browseContinuation returned null, stopping")
                    break
                }
                val (pageRenderers, nextToken) = extractPlaylistPage(page)
                Log.d(TAG, "playlist[$playlistId] page$pageNum: renderers=${pageRenderers.size} token=${nextToken?.take(40)}")
                val before = collected.size
                collected += pageRenderers.mapNotNull { parseResponsiveSong(it) }
                if (collected.size == before) {
                    Log.w(TAG, "playlist[$playlistId] page$pageNum: no progress — stopping")
                    break
                }
                token = nextToken
            }
            Log.d(TAG, "playlist[$playlistId] done: total=${collected.size} distinct=${collected.distinctBy { it.videoId }.size}")
            collected.distinctBy { it.videoId }
        }

    /**
     * Extract the playlist song renderers and the next-page continuation token from a
     * single InnerTube browse/continuation response.
     *
     * Mirrors Metrolist's YouTube.kt + playlistContinuation() logic exactly:
     *
     * Initial browse  → twoColumnBrowseResultsRenderer.secondaryContents
     *                    .sectionListRenderer.contents[0].musicPlaylistShelfRenderer
     *   Token priority: 1. inlined continuationItemRenderer in contents[]
     *                   2. musicPlaylistShelfRenderer.continuations[]
     *                   3. sectionListRenderer.continuations[]
     *
     * Continuation    → all four shapes that Metrolist unions together:
     *   • continuationContents.sectionListContinuation.contents[].musicPlaylistShelfRenderer
     *   • continuationContents.musicPlaylistShelfContinuation
     *   • continuationContents.musicShelfContinuation
     *   • onResponseReceivedActions[0].appendContinuationItemsAction.continuationItems
     */
    private fun extractPlaylistPage(response: JsonObject): Pair<List<JsonObject>, String?> {

        // ── local helpers ────────────────────────────────────────────────────────────

        /** Extract song renderers from a shelf/continuation contents array. */
        fun JsonArray.songItems(): List<JsonObject> =
            mapNotNull { (it as? JsonObject)?.get("musicResponsiveListItemRenderer") as? JsonObject }

        /**
         * Metrolist's getContinuation(): look for a `continuationItemRenderer` inlined
         * as the last element of a shelf's contents[] — this is the primary token source
         * for musicPlaylistShelfRenderer pages where the "load more" cursor is embedded
         * in the item list rather than in a separate continuations key.
         */
        fun JsonArray.inlinedToken(): String? =
            firstNotNullOfOrNull { item ->
                (item as? JsonObject)
                    ?.let { it["continuationItemRenderer"] as? JsonObject }
                    ?.let { it["continuationEndpoint"] as? JsonObject }
                    ?.let { it["continuationCommand"] as? JsonObject }
                    ?.get("token")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }

        /** Read the next-page token from a `continuations[]` wrapper array. */
        fun JsonArray.continuationsToken(): String? {
            for (entry in this) {
                val obj = entry as? JsonObject ?: continue
                for (key in listOf(
                    "nextContinuationData", "nextRadioContinuationData",
                    "timedContinuationData", "reloadContinuationData",
                )) {
                    val t = (obj[key] as? JsonObject)?.get("continuation")
                        ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    if (t != null) return t
                }
            }
            return null
        }

        fun JsonObject.continuationsToken(): String? =
            (this["continuations"] as? JsonArray)?.continuationsToken()

        // ── Path A: initial browse — twoColumnBrowseResultsRenderer ─────────────────
        // Metrolist: contents.twoColumnBrowseResultsRenderer
        //              .secondaryContents.sectionListRenderer.contents[0]
        //              .musicPlaylistShelfRenderer
        val twoColContents = (response["contents"] as? JsonObject)
            ?.let { it["twoColumnBrowseResultsRenderer"] as? JsonObject }

        if (twoColContents != null) {
            val secSectionList = twoColContents
                .let { it["secondaryContents"] as? JsonObject }
                ?.let { it["sectionListRenderer"] as? JsonObject }

            val shelf = secSectionList
                ?.let { it["contents"] as? JsonArray }
                ?.firstNotNullOfOrNull { (it as? JsonObject)?.get("musicPlaylistShelfRenderer") as? JsonObject }
                ?: secSectionList
                    ?.let { it["contents"] as? JsonArray }
                    ?.firstNotNullOfOrNull { (it as? JsonObject)?.get("musicShelfRenderer") as? JsonObject }

            if (shelf != null) {
                val contents = shelf["contents"] as? JsonArray
                val items = contents?.songItems() ?: emptyList()
                val token = contents?.inlinedToken()
                    ?: shelf.continuationsToken()
                    ?: secSectionList?.continuationsToken()
                if (items.isNotEmpty()) {
                    Log.d(TAG, "extractPlaylistPage: twoColumn path, items=${items.size} token=${token?.take(30)}")
                    return items to token
                }
            }
        }

        // ── Path B: initial browse — singleColumnBrowseResultsRenderer ──────────────
        val singleColContents = (response["contents"] as? JsonObject)
            ?.let { it["singleColumnBrowseResultsRenderer"] as? JsonObject }

        if (singleColContents != null) {
            val sectionList = (singleColContents["tabs"] as? JsonArray)
                ?.firstOrNull()?.let { it as? JsonObject }
                ?.let { it["tabRenderer"] as? JsonObject }
                ?.let { it["content"] as? JsonObject }
                ?.let { it["sectionListRenderer"] as? JsonObject }

            val shelf = sectionList
                ?.let { it["contents"] as? JsonArray }
                ?.firstNotNullOfOrNull { c ->
                    val obj = c as? JsonObject ?: return@firstNotNullOfOrNull null
                    (obj["musicPlaylistShelfRenderer"] ?: obj["musicShelfRenderer"]) as? JsonObject
                }

            if (shelf != null) {
                val contents = shelf["contents"] as? JsonArray
                val items = contents?.songItems() ?: emptyList()
                val token = contents?.inlinedToken()
                    ?: shelf.continuationsToken()
                    ?: sectionList?.continuationsToken()
                if (items.isNotEmpty()) {
                    Log.d(TAG, "extractPlaylistPage: singleColumn path, items=${items.size} token=${token?.take(30)}")
                    return items to token
                }
            }
        }

        // ── Path C: continuation response — union all four Metrolist shapes ──────────
        val cc = response["continuationContents"] as? JsonObject
        if (cc != null) {
            val allItems = mutableListOf<JsonObject>()
            var token: String? = null

            // C1. sectionListContinuation.contents[].musicPlaylistShelfRenderer/musicShelfRenderer
            (cc["sectionListContinuation"] as? JsonObject)?.let { slc ->
                (slc["contents"] as? JsonArray)?.forEach { c ->
                    val obj = c as? JsonObject ?: return@forEach
                    val shelfObj = (obj["musicPlaylistShelfRenderer"] ?: obj["musicShelfRenderer"]) as? JsonObject
                        ?: return@forEach
                    val contents = shelfObj["contents"] as? JsonArray
                    allItems += contents?.songItems() ?: emptyList()
                    if (token == null) token = contents?.inlinedToken() ?: shelfObj.continuationsToken()
                }
                if (token == null) token = slc.continuationsToken()
            }

            // C2. musicPlaylistShelfContinuation
            (cc["musicPlaylistShelfContinuation"] as? JsonObject)?.let { shelf ->
                val contents = shelf["contents"] as? JsonArray
                allItems += contents?.songItems() ?: emptyList()
                if (token == null) token = contents?.inlinedToken() ?: shelf.continuationsToken()
            }

            // C3. musicShelfContinuation
            (cc["musicShelfContinuation"] as? JsonObject)?.let { shelf ->
                val contents = shelf["contents"] as? JsonArray
                allItems += contents?.songItems() ?: emptyList()
                if (token == null) token = contents?.inlinedToken() ?: shelf.continuationsToken()
            }

            if (allItems.isNotEmpty()) {
                Log.d(TAG, "extractPlaylistPage: continuationContents path, items=${allItems.size} token=${token?.take(30)}")
                return allItems to token
            }
        }

        // ── Path D: appendContinuationItemsAction (2025+ format) ────────────────────
        val actionItems = (response["onResponseReceivedActions"] as? JsonArray)
            ?.firstOrNull()?.let { it as? JsonObject }
            ?.let { it["appendContinuationItemsAction"] as? JsonObject }
            ?.let { it["continuationItems"] as? JsonArray }

        if (actionItems != null) {
            val items = actionItems.songItems()
            val token = actionItems.inlinedToken()
            if (items.isNotEmpty()) {
                Log.d(TAG, "extractPlaylistPage: appendContinuationItemsAction path, items=${items.size} token=${token?.take(30)}")
                return items to token
            }
        }

        // ── Fallback ─────────────────────────────────────────────────────────────────
        Log.w(TAG, "extractPlaylistPage: no known shelf found — keys=${response.keys}")
        return response.collectResponsiveListItems() to response.findContinuationToken()
    }

    // ───────────────────────── parsers ─────────────────────────

    /**
     * Convert a `musicTwoRowItemRenderer` object into a playlist model. These show up
     * for both playlists and albums; we use the subtitle text to disambiguate.
     */
    private fun parseTwoRowAsPlaylist(
        renderer: JsonObject,
        forceIsAlbum: Boolean = false,
    ): YtmPlaylist? {
        val titleEl = renderer["title"] ?: return null
        val title = titleEl.runsText() ?: return null
        val subtitle = renderer["subtitle"].runsText()
        // Walk through `thumbnailRenderer` itself — the bestThumbnail helper
        // drills down through every nested level YouTube uses.
        val thumb = renderer["thumbnailRenderer"].bestThumbnail()
        val playlistId = titleEl.firstNavigationBrowseId()
            ?: renderer.firstNavigationBrowseId()
            ?: return null
        val isAlbum = forceIsAlbum || subtitle?.contains("Album", ignoreCase = true) == true ||
            subtitle?.contains("Single", ignoreCase = true) == true ||
            subtitle?.contains("EP", ignoreCase = true) == true
        return YtmPlaylist(
            id = playlistId,
            title = title,
            thumbnail = thumb,
            subtitle = subtitle,
            isAlbum = isAlbum,
        )
    }

    /**
     * Convert a `musicResponsiveListItemRenderer` object into a song. Handles the
     * common YT Music layout where flex column 0 is the title, column 1 is
     * `Artist · Album · Plays`, and overlay carries the play action's videoId.
     */
    private fun parseResponsiveSong(item: JsonObject): YtmSong? {
        val flexColumns = (item["flexColumns"] as? JsonArray) ?: return null
        val titleText = flexColumns.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")
        val title = titleText.runsText() ?: return null
        // Prefer playlistItemData.videoId — it is the direct, always-present video ID
        // field that Metrolist also uses.  Fall back to the title navigation endpoint
        // for non-playlist contexts (liked songs, artist pages, etc.).
        val videoId = (item["playlistItemData"] as? JsonObject)
            ?.get("videoId")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: titleText?.firstNavigationVideoId()
            ?: return null

        // Column 1 is usually `Artist · Album · Plays`. We peel off the first two.
        val subtitleRuns = flexColumns.getOrNull(1)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject?.get("runs") as? JsonArray
        var artist = ""
        var album: String? = null
        subtitleRuns?.forEachIndexed { i, run ->
            val text = (run.jsonObject["text"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
            if (text == " · " || text == "•") return@forEachIndexed
            if (artist.isEmpty()) artist = text
            else if (album == null && i > 1) album = text
        }

        val duration = (item["fixedColumns"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?.get("musicResponsiveListItemFixedColumnRenderer")?.jsonObject
            ?.get("text").runsText()
            ?.parseDuration()

        val thumb = item["thumbnail"].bestThumbnail()

        return YtmSong(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            thumbnail = thumb,
            durationSeconds = duration,
        )
    }

    /** Navigation endpoint walker — finds the first `videoId` in a runs tree. */
    private fun JsonElement?.firstNavigationVideoId(): String? {
        this ?: return null
        return findFirst("videoId")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun JsonElement?.firstNavigationBrowseId(): String? {
        this ?: return null
        return findFirst("browseId")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: findFirst("playlistId")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun String.parseDuration(): Long? {
        val parts = split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }
}
