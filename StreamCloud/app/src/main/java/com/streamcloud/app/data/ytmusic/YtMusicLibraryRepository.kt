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


    suspend fun playlistTracks(cookie: String, playlistId: String): List<YtmSong> =
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

            // For album browse IDs (MPREb_), tracks don't carry individual thumbnails —
            // the album cover lives only in the header. Extract it once as a fallback.
            val albumCover: String? = if (browseId.startsWith("MPREb_")) {
                first.findFirst("musicDetailHeaderRenderer")?.jsonObject
                    ?.get("thumbnail").bestThumbnail()
                    ?: first.findFirst("musicImmersiveHeaderRenderer")?.jsonObject
                        ?.get("thumbnail").bestThumbnail()
                    ?: first.findFirst("musicThumbnailRenderer")?.jsonObject
                        ?.findFirst("thumbnail").bestThumbnail()
            } else null

            val collected = mutableListOf<YtmSong>()
            var pageNum = 0
            var safetyPages = 50


            var (renderers, token) = extractPlaylistPage(first)
            Log.d(TAG, "playlist[$playlistId] page$pageNum: renderers=${renderers.size} token=${token?.take(40)}")
            collected += renderers.mapNotNull { parseResponsiveSong(it, albumCover) }

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
                collected += pageRenderers.mapNotNull { parseResponsiveSong(it, albumCover) }
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


    private fun parseResponsiveSong(item: JsonObject, fallbackThumb: String? = null): YtmSong? {
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

        return YtmSong(
            videoId = videoId,
            title = title,
            artist = artist,
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
