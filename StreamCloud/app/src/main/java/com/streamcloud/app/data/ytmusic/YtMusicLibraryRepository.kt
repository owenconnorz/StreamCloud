package com.streamcloud.app.data.ytmusic

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

object YtMusicLibraryRepository {

    private const val TAG = "YtMusicLibrary"


    suspend fun likeSong(cookie: String, videoId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (cookie.isBlank() || videoId.isBlank()) return@withContext false
            runCatching { InnerTubeClient(cookie).likeSong(videoId) }
                .getOrElse { Log.w(TAG, "likeSong failed", it); false }
        }


    suspend fun unlikeSong(cookie: String, videoId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (cookie.isBlank() || videoId.isBlank()) return@withContext false
            runCatching { InnerTubeClient(cookie).unlikeSong(videoId) }
                .getOrElse { Log.w(TAG, "unlikeSong failed", it); false }
        }


    suspend fun sync(cookie: String): YtMusicLibrary = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) return@withContext YtMusicLibrary(
            failureReason = "Not signed in — open Settings → Account and sign in to YouTube Music.",
        )
        val client = InnerTubeClient(cookie)
        try {
            coroutineScope {
                val playlistsJob = async { fetchLibraryPlaylists(client) }
                val likedJob    = async { fetchLikedSongs(client) }
                val artistsJob  = async { fetchLibraryArtists(client) }
                val albumsJob   = async { fetchLibraryAlbums(client) }

                val (playlists, playlistsDiag) = playlistsJob.await()
                val likedSongs = likedJob.await()
                val albums     = albumsJob.await()
                val artists    = artistsJob.await()

                // Full auth failure: everything empty (expired cookie returns a sign-in page)
                if (playlists.isEmpty() && likedSongs.isEmpty() &&
                    albums.isEmpty()   && artists.isEmpty()) {
                    val hasSapisid = YtMusicAuth.sapisidHashHeader(cookie) != null
                    val reason = if (hasSapisid)
                        "YouTube Music returned empty — cookie may have expired.\n" +
                        "Re-enter it in Settings → Account, then tap ↻.\n" +
                        (playlistsDiag ?: "")
                    else
                        "Cookie is missing the auth token (SAPISID).\nRe-enter your YouTube Music cookie in Settings → Account."
                    Log.w(TAG, "library sync all-empty: hasSapisid=$hasSapisid diag=$playlistsDiag")
                    return@coroutineScope YtMusicLibrary(failureReason = reason)
                }

                // Partial failure: playlists specifically came back empty
                if (playlists.isEmpty() && playlistsDiag != null) {
                    Log.w(TAG, "library sync: playlists empty — $playlistsDiag")
                    return@coroutineScope YtMusicLibrary(
                        likedSongs = likedSongs,
                        playlists  = emptyList(),
                        albums     = albums,
                        artists    = artists,
                        failureReason = "Playlists not loading.\n$playlistsDiag\n\nPlease share this with the developer.",
                    )
                }

                YtMusicLibrary(
                    likedSongs = likedSongs,
                    playlists  = playlists.filter { !it.isAlbum },
                    albums     = albums + playlists.filter { it.isAlbum },
                    artists    = artists,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "library sync crashed", e)
            YtMusicLibrary(failureReason = e.message ?: "Sync failed")
        }
    }


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

    /**
     * Fetch library playlists, returning the list AND a diagnostic string.
     * The diagnostic is null when items were found, or a description of
     * what renderer types were in the raw response when nothing parsed.
     * This lets the Playlists tab show exactly why parsing failed.
     */
    private suspend fun fetchLibraryPlaylists(
        client: InnerTubeClient,
    ): Pair<List<YtmPlaylist>, String?> {
        val resp = client.browse("FEmusic_liked_playlists")
            ?: return emptyList<YtmPlaylist>() to "browse() returned null (network error)"

        // Collect every *Renderer key name anywhere in the response for diagnostics.
        val rendererTypes = linkedSetOf<String>()
        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    el.keys.forEach { k -> if (k.endsWith("Renderer")) rendererTypes.add(k) }
                    el.values.forEach(::walk)
                }
                is JsonArray  -> el.forEach(::walk)
                else          -> {}
            }
        }
        walk(resp)
        Log.d(TAG, "FEmusic_liked_playlists renderers: $rendererTypes")

        // ── Path 1: grid-view (musicTwoRowItemRenderer) deep search ──────────
        val twoRowRaw = resp.collectTwoRowItems()
        val twoRow = twoRowRaw.mapNotNull { parseTwoRowAsPlaylist(it) }
        if (twoRow.isNotEmpty()) return twoRow to null

        // Per-item diagnostics: why did every twoRow item parse as null?
        val twoRowDiag = if (twoRowRaw.isEmpty()) "twoRowCount=0" else {
            val first = twoRowRaw.first()
            val firstKeys  = first.keys.take(8).joinToString(",")
            val titleEl    = first["title"]
            val titleText  = titleEl?.runsText()
            val titleBrowse  = titleEl?.findFirst("browseId")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
            val titlePLId  = titleEl?.findFirst("playlistId")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
            val rendBrowse = first.findFirst("browseId")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
            val rendPLId   = first.findFirst("playlistId")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
            val rendNavKeys = (first["navigationEndpoint"] as? JsonObject)?.keys
                ?.take(4)?.joinToString(",")
            "twoRowCount=${twoRowRaw.size} firstKeys=[$firstKeys] " +
            "title=$titleText titleBrowse=$titleBrowse titlePL=$titlePLId " +
            "rendBrowse=$rendBrowse rendPL=$rendPLId navEpKeys=[$rendNavKeys]"
        }

        // ── Path 2: list-view (musicResponsiveListItemRenderer) deep search ──
        val responsiveRaw = resp.collectResponsiveListItems()
        val responsive = responsiveRaw.mapNotNull { parseResponsiveAsPlaylist(it) }
        if (responsive.isNotEmpty()) return responsive to null
        val responsiveDiag = "responsiveCount=${responsiveRaw.size}"

        // ── UI-chrome guard ───────────────────────────────────────────────────
        // If every twoRow item has a createPlaylistEndpoint (the "New playlist"
        // button) the library is genuinely empty — not a parse failure.
        val allChrome = twoRowRaw.isNotEmpty() &&
            twoRowRaw.all { r -> r.findFirst("createPlaylistEndpoint") != null }
        if (allChrome && responsiveRaw.isEmpty()) {
            Log.d(TAG, "fetchLibraryPlaylists: valid empty (only create-playlist button)")
            return emptyList<YtmPlaylist>() to null
        }

        // ── Path 3: ytmusicapi explicit navigation path ───────────────────────
        // singleColumnBrowseResultsRenderer → tabs[0] → sectionList → itemSectionRenderer
        //   → musicShelfRenderer → contents  (each item is a renderer wrapper)
        val sectionListContents = (resp["contents"] as? JsonObject)
            ?.get("singleColumnBrowseResultsRenderer")?.let { it as? JsonObject }
            ?.get("tabs")?.let { it as? JsonArray }
            ?.getOrNull(0)?.let { it as? JsonObject }
            ?.get("tabRenderer")?.let { it as? JsonObject }
            ?.get("content")?.let { it as? JsonObject }
            ?.get("sectionListRenderer")?.let { it as? JsonObject }
            ?.get("contents")?.let { it as? JsonArray }

        val shelfContents: JsonArray? = sectionListContents?.firstNotNullOfOrNull { entry ->
            val obj = entry as? JsonObject ?: return@firstNotNullOfOrNull null
            // itemSectionRenderer → [musicShelfRenderer | direct items]
            val itemSection = (obj["itemSectionRenderer"] as? JsonObject)
                ?.get("contents")?.let { it as? JsonArray }
            itemSection?.firstNotNullOfOrNull { inner ->
                (inner as? JsonObject)?.get("musicShelfRenderer")?.let { it as? JsonObject }
                    ?.get("contents")?.let { it as? JsonArray }
            }
            // fall back: musicShelfRenderer directly in sectionList
            ?: (obj["musicShelfRenderer"] as? JsonObject)
                ?.get("contents")?.let { it as? JsonArray }
            // or: itemSectionRenderer contents are the items themselves
            ?: itemSection
        }

        if (shelfContents != null) {
            val fromShelf = shelfContents.mapNotNull { wrapper ->
                val obj = wrapper as? JsonObject ?: return@mapNotNull null
                val twoRowItem = (obj["musicTwoRowItemRenderer"] as? JsonObject)
                    ?.let { parseTwoRowAsPlaylist(it) }
                twoRowItem
                    ?: (obj["musicResponsiveListItemRenderer"] as? JsonObject)
                        ?.let { parseResponsiveAsPlaylist(it) }
            }
            if (fromShelf.isNotEmpty()) return fromShelf to null
        }

        // ── Nothing found — return full diagnostic so the UI can display it ─────
        val renderers = rendererTypes.take(12).joinToString(", ")
        val diag = "Renderers: [$renderers]\n$twoRowDiag\n$responsiveDiag"
        Log.w(TAG, "fetchLibraryPlaylists: no items. $diag")
        return emptyList<YtmPlaylist>() to diag
    }

    private suspend fun fetchLibraryAlbums(client: InnerTubeClient): List<YtmPlaylist> =
        drainBrowse(client, "FEmusic_liked_albums") { resp ->
            val twoRow = resp.collectTwoRowItems().mapNotNull { parseTwoRowAsPlaylist(it, forceIsAlbum = true) }
            if (twoRow.isNotEmpty()) twoRow
            else resp.collectResponsiveListItems().mapNotNull { parseResponsiveAsPlaylist(it, forceIsAlbum = true) }
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


    suspend fun playlistTracks(
        cookie: String,
        playlistId: String,
        externalThumb: String? = null,
    ): List<YtmSong> =
        withContext(Dispatchers.IO) {
            val client = InnerTubeClient(cookie)
            // MPREb_ = YouTube Music album browse ID → use as-is (no VL prefix)
            // VL…   = already a playlist browse ID → keep as-is
            // everything else (PL…, OLAK5uy_…, etc.) → prepend VL
            val browseId = when {
                playlistId.startsWith("VL") -> playlistId
                playlistId.startsWith("MPREb_") -> playlistId
                else -> "VL$playlistId"
            }
            val first = client.browse(browseId) ?: return@withContext emptyList()

            // Extract album cover from page header as thumbnail fallback.
            // Album pages (MPREb_, OLAK5uy_) never include per-track thumbnails.
            // externalThumb (passed from the artist-page card) is used as the final fallback.
            val headerRenderer = first.findFirst("musicDetailHeaderRenderer")?.jsonObject
                ?: first.findFirst("musicImmersiveHeaderRenderer")?.jsonObject
            val albumCover: String? =
                headerRenderer?.get("thumbnail").bestThumbnail()
                    ?: externalThumb

            // Extract album artist from the header subtitle — the artist run is the one
            // that carries a navigation endpoint (browseEndpoint to their channel).
            val albumArtist: String? = headerRenderer?.let { hdr ->
                val runs = hdr["subtitle"]?.jsonObject?.get("runs") as? JsonArray
                runs?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { run ->
                        run["navigationEndpoint"] != null &&
                        run["text"]?.jsonPrimitive?.contentOrNull.orEmpty().let { t ->
                            t.isNotBlank() && t != " · " && t != "•" &&
                            !t.matches(Regex("\\d{4}")) &&
                            !t.matches(Regex("(Album|EP|Single|Compilation|Podcast|\\d+\\s+\\w+.*)", RegexOption.IGNORE_CASE))
                        }
                    }
                    ?.get("text")?.jsonPrimitive?.contentOrNull
            }
            Log.d(TAG, "playlist[$playlistId] albumArtist=$albumArtist cover=${albumCover?.take(60)}")

            val collected = mutableListOf<YtmSong>()
            var pageNum = 0
            var safetyPages = 50

            var (renderers, token) = extractPlaylistPage(first)
            Log.d(TAG, "playlist[$playlistId] page$pageNum: renderers=${renderers.size} token=${token?.take(40)}")
            collected += renderers.mapNotNull { parseResponsiveSong(it, albumCover, albumArtist) }

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
                collected += pageRenderers.mapNotNull { parseResponsiveSong(it, albumCover, albumArtist) }
                if (collected.size == before) {
                    Log.w(TAG, "playlist[$playlistId] page$pageNum: no progress — stopping")
                    break
                }
                token = nextToken
            }
            Log.d(TAG, "playlist[$playlistId] done: total=${collected.size} distinct=${collected.distinctBy { it.videoId }.size}")
            collected.distinctBy { it.videoId }
        }


    private fun extractPlaylistPage(response: JsonObject): Pair<List<JsonObject>, String?> {




        fun JsonArray.songItems(): List<JsonObject> =
            mapNotNull { (it as? JsonObject)?.get("musicResponsiveListItemRenderer") as? JsonObject }


        fun JsonArray.inlinedToken(): String? =
            firstNotNullOfOrNull { item ->
                (item as? JsonObject)
                    ?.let { it["continuationItemRenderer"] as? JsonObject }
                    ?.let { it["continuationEndpoint"] as? JsonObject }
                    ?.let { it["continuationCommand"] as? JsonObject }
                    ?.get("token")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }


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


        val cc = response["continuationContents"] as? JsonObject
        if (cc != null) {
            val allItems = mutableListOf<JsonObject>()
            var token: String? = null


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


            (cc["musicPlaylistShelfContinuation"] as? JsonObject)?.let { shelf ->
                val contents = shelf["contents"] as? JsonArray
                allItems += contents?.songItems() ?: emptyList()
                if (token == null) token = contents?.inlinedToken() ?: shelf.continuationsToken()
            }


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


        Log.w(TAG, "extractPlaylistPage: no known shelf found — keys=${response.keys}")
        return response.collectResponsiveListItems() to response.findContinuationToken()
    }




    private fun parseTwoRowAsPlaylist(
        renderer: JsonObject,
        forceIsAlbum: Boolean = false,
    ): YtmPlaylist? {
        val titleEl = renderer["title"] ?: return null
        val title = titleEl.runsText() ?: return null
        val subtitle = renderer["subtitle"].runsText()


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
     * Parse a playlist/album from a musicResponsiveListItemRenderer.
     * YouTube Music library pages can return list-view renderers instead of
     * the grid-view musicTwoRowItemRenderer depending on the user's setting.
     */
    private fun parseResponsiveAsPlaylist(
        item: JsonObject,
        forceIsAlbum: Boolean = false,
    ): YtmPlaylist? {
        val flexColumns = (item["flexColumns"] as? JsonArray) ?: return null
        val titleEl = flexColumns.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")
        val title = titleEl.runsText() ?: return null
        val playlistId = titleEl.firstNavigationBrowseId()
            ?: item.firstNavigationBrowseId()
            ?: return null
        val subtitle = flexColumns.getOrNull(1)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text").runsText()
        val thumb = item["thumbnail"].bestThumbnail()
        val isAlbum = forceIsAlbum ||
            subtitle?.contains("Album", ignoreCase = true) == true ||
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


    private fun parseResponsiveSong(
        item: JsonObject,
        fallbackThumb: String? = null,
        fallbackArtist: String? = null,
    ): YtmSong? {
        val flexColumns = (item["flexColumns"] as? JsonArray) ?: return null
        val titleText = flexColumns.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")
        val title = titleText.runsText() ?: return null



        val videoId = (item["playlistItemData"] as? JsonObject)
            ?.get("videoId")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: titleText?.firstNavigationVideoId()
            ?: return null


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

        val thumb = item["thumbnail"].bestThumbnail() ?: fallbackThumb
        // Use fallbackArtist (album-level artist from header) when the track row is blank
        val resolvedArtist = artist.ifBlank { fallbackArtist.orEmpty() }

        return YtmSong(
            videoId = videoId,
            title = title,
            artist = resolvedArtist,
            album = album,
            thumbnail = thumb,
            durationSeconds = duration,
        )
    }


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
