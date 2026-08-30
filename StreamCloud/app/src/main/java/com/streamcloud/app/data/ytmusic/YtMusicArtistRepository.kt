package com.streamcloud.app.data.ytmusic

import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.newpipe.YtAlbum
import com.streamcloud.app.data.newpipe.YtArtist
import com.streamcloud.app.data.newpipe.YtTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

/**
 * Loads a full YouTube Music artist page via the InnerTube browse API (WEB_REMIX context).
 * This is the same method SimpMusic uses — it returns Songs, Albums, Singles, Videos,
 * Featured on, and Related Artists, with a follow-up browse for the complete song list.
 */
object YtMusicArtistRepository {

    private val client = InnerTubeClient("")

    suspend fun load(channelId: String): NewPipeRepository.ArtistPage? = withContext(Dispatchers.IO) {
        val resp = runCatching { client.browse(channelId) }.getOrNull() ?: return@withContext null

        // Header — artist name and images
        val headerObj = resp.findFirst("musicImmersiveHeaderRenderer")?.jsonObject
            ?: resp.findFirst("musicVisualHeaderRenderer")?.jsonObject
        val name = headerObj?.get("title").runsText()?.removeSuffix(" - Topic")?.trim()
            ?: return@withContext null

        val banner = headerObj?.findFirst("thumbnail").bestThumbnail()
            ?: headerObj?.findFirst("foregroundThumbnail").bestThumbnail()
        // Avatar: look for the thumbnail in the cropped/circle variant
        val avatar = resp.findFirst("musicThumbnailRenderer")?.jsonObject
            ?.findFirst("thumbnail").bestThumbnail()
            ?: banner

        // Subscriber count
        val subLabel = resp.findAll("subscribeButton")
            .firstNotNullOfOrNull { el ->
                (el as? JsonObject)?.findFirst("subscriberCountText").runsText()
            }

        // Description
        val description = resp.findFirst("musicDescriptionShelfRenderer")?.jsonObject
            ?.get("description").runsText().orEmpty()

        // Songs shelf (musicShelfRenderer). The artist overview only contains a five-song
        // preview; its bottomEndpoint opens the complete artist song playlist.
        val songsShelf = resp.findAll("musicShelfRenderer").firstOrNull { shelf ->
            val t = (shelf as? JsonObject)?.findFirst("title").runsText()?.lowercase() ?: ""
            "song" in t || t.isBlank()
        } as? JsonObject
        val songs = loadAllArtistSongs(songsShelf)

        // Carousel shelves (Albums, Singles, Videos, Featured on, Related)
        val carousels = resp.findAll("musicCarouselShelfRenderer").mapNotNull { it as? JsonObject }

        fun carouselByTitle(vararg keywords: String): JsonObject? = carousels.firstOrNull { shelf ->
            val t = shelf.findFirst("title").runsText()?.lowercase() ?: ""
            keywords.any { it in t }
        }

        val albums = carouselByTitle("album")
            ?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseAlbum(it as? JsonObject) }
            .orEmpty()

        val singles = carouselByTitle("single")
            ?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseAlbum(it as? JsonObject) }
            .orEmpty()

        val videos = carouselByTitle("video")
            ?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseVideo(it as? JsonObject) }
            .orEmpty()

        val featuredOn = carouselByTitle("featured")
            ?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseAlbum(it as? JsonObject) }
            .orEmpty()

        val relatedArtists = carousels.firstOrNull { shelf ->
            val t = shelf.findFirst("title").runsText()?.lowercase() ?: ""
            "fan" in t || "related" in t || "similar" in t || "also like" in t
        }?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseArtist(it as? JsonObject) }
            .orEmpty()

        NewPipeRepository.ArtistPage(
            name = name,
            avatar = avatar,
            banner = banner,
            description = description,
            subscriberLabel = subLabel,
            topTracks = songs,
            albums = albums,
            singles = singles,
            videos = videos,
            featuredOn = featuredOn,
            relatedArtists = relatedArtists,
        )
    }

    /**
     * Loads the complete song list behind an artist's Top Songs shelf.
     *
     * The overview browse response intentionally contains only a short preview. Most artists
     * expose a bottomEndpoint with a playlist browse ID for the full list. Some responses instead
     * expose continuation tokens, so retain that path for clients/regions where the playlist
     * endpoint is not present.
     */
    private suspend fun loadAllArtistSongs(previewShelf: JsonObject?): List<YtTrack> {
        previewShelf ?: return emptyList()

        val preview = previewShelf.findAll("musicResponsiveListItemRenderer")
            .mapNotNull { parseSong(it as? JsonObject) }

        val browseEndpoint = (previewShelf["bottomEndpoint"] as? JsonObject)
            ?.get("browseEndpoint") as? JsonObject
        val fullBrowseId = browseEndpoint
            ?.get("browseId")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        val fullParams = browseEndpoint
            ?.get("params")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

        if (fullBrowseId != null) {
            val fullResponse = runCatching {
                client.browse(fullBrowseId, fullParams)
            }.getOrNull()
            val fullTracks = fullResponse
                ?.findFirst("musicPlaylistShelfRenderer")
                ?.findAll("musicResponsiveListItemRenderer")
                ?.mapNotNull { parseSong(it as? JsonObject) }
                .orEmpty()
            if (fullTracks.isNotEmpty()) {
                return (preview + fullTracks).distinctBy(YtTrack::url)
            }
        }

        val collected = preview.toMutableList()
        var continuation = previewShelf.findContinuationToken()
        val seenTokens = mutableSetOf<String>()
        var loadedPages = 0
        while (continuation != null && loadedPages < 20) {
            val token = continuation ?: break
            if (!seenTokens.add(token)) break
            val next = runCatching { client.browseContinuation(token) }.getOrNull()
                ?: break
            collected += next.findAll("musicResponsiveListItemRenderer")
                .mapNotNull { parseSong(it as? JsonObject) }
            continuation = next.findContinuationToken()
            loadedPages++
        }
        return collected.distinctBy(YtTrack::url)
    }

    private fun parseSong(item: JsonObject?): YtTrack? {
        item ?: return null
        val videoId = item.findFirst("videoId")?.jsonPrimitive?.contentOrNull ?: return null
        val flexColumns = item["flexColumns"] as? JsonArray ?: return null
        val title = flexColumns.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text").runsText() ?: return null
        val artist = flexColumns.getOrNull(1)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text").runsText()?.split(" • ")?.firstOrNull()?.trim() ?: ""
        return YtTrack(
            title = title,
            uploader = artist,
            durationSec = 0L,
            url = "https://music.youtube.com/watch?v=$videoId",
            thumbnail = item["thumbnail"].bestThumbnail(),
        )
    }

    private fun parseAlbum(item: JsonObject?): YtAlbum? {
        item ?: return null
        val browseId = item.findFirst("browseId")?.jsonPrimitive?.contentOrNull ?: return null
        val title = item["title"].runsText() ?: return null
        val subtitle = item["subtitle"].runsText() ?: ""
        val parts = subtitle.split(" • ")
        val year = parts.firstOrNull { it.trim().matches(Regex("\\d{4}")) }?.trim()
        val thumb = item["thumbnailRenderer"].bestThumbnail() ?: item["thumbnail"].bestThumbnail()
        return YtAlbum(
            title = title,
            artist = parts.getOrNull(0)?.trim() ?: "",
            url = "https://music.youtube.com/browse/$browseId",
            thumbnail = thumb,
            year = year,
        )
    }

    private fun parseVideo(item: JsonObject?): YtTrack? {
        item ?: return null
        val videoId = item.findFirst("videoId")?.jsonPrimitive?.contentOrNull ?: return null
        val title = item["title"].runsText() ?: return null
        val subtitle = item["subtitle"].runsText() ?: ""
        val artist = subtitle.split(" • ").firstOrNull()?.trim() ?: ""
        val thumb = item["thumbnailRenderer"].bestThumbnail() ?: item["thumbnail"].bestThumbnail()
        return YtTrack(
            title = title,
            uploader = artist,
            durationSec = 0L,
            url = "https://music.youtube.com/watch?v=$videoId",
            thumbnail = thumb,
            isVideo = true,
        )
    }

    private fun parseArtist(item: JsonObject?): YtArtist? {
        item ?: return null
        val browseId = item.findFirst("browseId")?.jsonPrimitive?.contentOrNull ?: return null
        val name = item["title"].runsText() ?: return null
        val sub = item["subtitle"].runsText()
        val thumb = item["thumbnailRenderer"].bestThumbnail() ?: item["thumbnail"].bestThumbnail()
        return YtArtist(
            name = name,
            url = "https://www.youtube.com/channel/$browseId",
            thumbnail = thumb,
            subscriberLabel = sub,
        )
    }
}
