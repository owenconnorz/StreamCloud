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
            collected += first.collectResponsiveListItems().mapNotNull { parseResponsiveSong(it) }

            var token = first.findContinuationToken()
            // Cap at 50 pages (~5000 songs) so a runaway response doesn't loop forever.
            var safetyPages = 50
            while (!token.isNullOrBlank() && safetyPages-- > 0) {
                val page = client.browseContinuation(token) ?: break
                val before = collected.size
                collected += page.collectResponsiveListItems().mapNotNull { parseResponsiveSong(it) }
                if (collected.size == before) break // no progress, bail
                token = page.findContinuationToken()
            }
            collected.distinctBy { it.videoId }
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
        val videoId = titleText?.firstNavigationVideoId() ?: return null

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
