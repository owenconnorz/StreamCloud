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

/**
 * Loads a full YouTube Music artist page via the InnerTube browse API (WEB_REMIX context).
 * This is the same method SimpMusic uses — it returns all sections: Songs, Albums, Singles,
 * Videos, Featured on, and Related Artists in one response.
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

        // Songs shelf (musicShelfRenderer)
        val songs = resp.findAll("musicShelfRenderer").firstOrNull { shelf ->
            val t = (shelf as? JsonObject)?.findFirst("title").runsText()?.lowercase() ?: ""
            "song" in t || t.isBlank()
        }?.let { shelf ->
            (shelf as? JsonObject)
                ?.findAll("musicResponsiveListItemRenderer")
                ?.mapNotNull { parseSong(it as? JsonObject) }
                ?.take(10)
        }.orEmpty()

        // Carousel shelves (Albums, Singles, Videos, Featured on, Related)
        val carousels = resp.findAll("musicCarouselShelfRenderer").mapNotNull { it as? JsonObject }

        fun carouselByTitle(vararg keywords: String): JsonObject? = carousels.firstOrNull { shelf ->
            val t = shelf.findFirst("title").runsText()?.lowercase() ?: ""
            keywords.any { it in t }
        }

        val albums = carouselByTitle("album")
            ?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseAlbum(it as? JsonObject) }
            .orEmpty().take(10)

        val singles = carouselByTitle("single")
            ?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseAlbum(it as? JsonObject) }
            .orEmpty().take(10)

        val videos = carouselByTitle("video")
            ?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseVideo(it as? JsonObject) }
            .orEmpty().take(8)

        val featuredOn = carouselByTitle("featured")
            ?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseAlbum(it as? JsonObject) }
            .orEmpty().take(10)

        val relatedArtists = carousels.firstOrNull { shelf ->
            val t = shelf.findFirst("title").runsText()?.lowercase() ?: ""
            "fan" in t || "related" in t || "similar" in t || "also like" in t
        }?.findAll("musicTwoRowItemRenderer")
            ?.mapNotNull { parseArtist(it as? JsonObject) }
            .orEmpty().take(6)

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
