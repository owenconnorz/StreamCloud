package com.streamcloud.app.data.ytmusic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface HomeSection {
    val title: String

    data class PlaylistRail(override val title: String, val items: List<YtmPlaylist>) : HomeSection

    data class SongRail(override val title: String, val items: List<YtmSong>) : HomeSection

    data class MoodChips(override val title: String, val chips: List<MoodChip>) : HomeSection
}

data class MoodChip(val label: String, val params: String?)

data class YtMusicHomeFeed(
    val sections: List<HomeSection> = emptyList(),
    val failureReason: String? = null,
)

object YtMusicHomeRepository {

    private const val TAG = "YtMusicHome"

    suspend fun load(cookie: String): YtMusicHomeFeed = withContext(Dispatchers.IO) {
        try {
            val client = InnerTubeClient(cookie)
            val resp = client.browse("FEmusic_home")
                ?: return@withContext YtMusicHomeFeed(failureReason = "Home feed failed to load.")




            val sectionList = resp.findFirst("sectionListRenderer") as? JsonObject
                ?: return@withContext YtMusicHomeFeed(failureReason = "No sections in response.")


            val chips = parseChips(sectionList)


            val contents = sectionList["contents"] as? JsonArray ?: JsonArray(emptyList())
            val sections = buildList<HomeSection> {
                if (chips.isNotEmpty()) add(HomeSection.MoodChips("", chips))
                contents.forEach { entry ->
                    val shelf = (entry as? JsonObject)?.get("musicCarouselShelfRenderer") as? JsonObject
                        ?: (entry as? JsonObject)?.get("musicImmersiveCarouselShelfRenderer") as? JsonObject
                        ?: return@forEach
                    parseCarousel(shelf)?.let { add(it) }
                }




                var token = resp.findContinuationToken()
                var safety = 12
                while (!token.isNullOrBlank() && safety-- > 0) {
                    val page = client.browseContinuation(token)
                    if (page == null) {
                        Log.w(TAG, "browseContinuation returned null, stopping pagination")
                        break
                    }
                    val before = size
                    page.findAll("musicCarouselShelfRenderer")
                        .mapNotNull { it as? JsonObject }
                        .forEach { shelf -> parseCarousel(shelf)?.let { add(it) } }
                    if (size == before) break
                    token = page.findContinuationToken()
                }
            }
            val hasPlayableRail = sections.any {
                when (it) {
                    is HomeSection.PlaylistRail -> it.items.isNotEmpty()
                    is HomeSection.SongRail -> it.items.isNotEmpty()
                    is HomeSection.MoodChips -> false
                }
            }
            YtMusicHomeFeed(
                sections = sections,
                failureReason = if (hasPlayableRail) null else {
                    "YouTube Music returned no playable sections."
                },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "home feed crashed", e)
            YtMusicHomeFeed(failureReason = e.message)
        }
    }

    private fun parseChips(sectionList: JsonObject): List<MoodChip> {


        val cloud = sectionList.findFirst("chipCloudRenderer") as? JsonObject ?: return emptyList()
        val chips = (cloud["chips"] as? JsonArray) ?: return emptyList()
        return chips.mapNotNull {
            val c = (it as? JsonObject)?.get("chipCloudChipRenderer") as? JsonObject ?: return@mapNotNull null
            val label = c["text"].runsText() ?: return@mapNotNull null
            val params = (c.findFirst("params") as? JsonPrimitive)?.contentOrNull
            MoodChip(label = label, params = params)
        }
    }

    private fun parseCarousel(shelf: JsonObject): HomeSection? {
        val title = shelf["header"]?.jsonObject
            ?.get("musicCarouselShelfBasicHeaderRenderer")?.jsonObject
            ?.get("title").runsText()
            ?: shelf["header"].runsText()
            ?: return null
        val contents = shelf["contents"] as? JsonArray ?: return null


        val first = contents.firstOrNull() as? JsonObject ?: return null
        return when {
            first.containsKey("musicTwoRowItemRenderer") -> {
                val items = contents.mapNotNull { raw ->
                    val r = (raw as? JsonObject)?.get("musicTwoRowItemRenderer") as? JsonObject
                        ?: return@mapNotNull null
                    parseTwoRowPlaylist(r)
                }.distinctBy { it.id }
                HomeSection.PlaylistRail(title, items)
            }
            first.containsKey("musicResponsiveListItemRenderer") -> {
                val items = contents.mapNotNull { raw ->
                    val r = (raw as? JsonObject)?.get("musicResponsiveListItemRenderer") as? JsonObject
                        ?: return@mapNotNull null
                    parseResponsiveSong(r)
                }.distinctBy { it.videoId }
                HomeSection.SongRail(title, items)
            }
            else -> {
                Log.d(TAG, "parseCarousel: unrecognised first-item renderer keys=${first.keys}, title=$title")
                null
            }
        }
    }

    private fun parseTwoRowPlaylist(renderer: JsonObject): YtmPlaylist? {
        val titleEl = renderer["title"] ?: return null
        val title = titleEl.runsText() ?: return null
        val subtitle = renderer["subtitle"].runsText()

        // Thumbnail: try standard renderer path first, fall back to searching entire renderer tree
        val thumb = renderer["thumbnailRenderer"].bestThumbnail()
            ?: renderer.bestThumbnailAnywhere()

        // Prefer the title's own navigation endpoint. A card can also include endpoints for its
        // artist, overflow menu, or overlay; a generic recursive browseId lookup can select one
        // of those and send every home category to the wrong page.
        val browseId = titleEl.musicBrowseId()
            ?: renderer["navigationEndpoint"].musicBrowseId()
            ?: renderer["playNavigationEndpoint"].musicBrowseId()
        val playlistId = titleEl.musicPlaylistId()
            ?: renderer["navigationEndpoint"].musicPlaylistId()
            ?: renderer["playNavigationEndpoint"].musicPlaylistId()
        val videoId = titleEl.musicVideoId()
            ?: renderer["navigationEndpoint"].musicVideoId()
            ?: renderer["playNavigationEndpoint"].musicVideoId()
        val id = browseId ?: playlistId?.toMusicBrowseId() ?: videoId ?: return null

        val isAlbum = subtitle?.contains("Album", ignoreCase = true) == true ||
            subtitle?.contains("Single", ignoreCase = true) == true
        // Items in "Music videos for you" sections have only a videoId — no browseId or playlistId.
        // Flag them so the click handler plays them directly instead of opening a playlist page.
        val isVideo = browseId == null && playlistId == null && videoId != null
        return YtmPlaylist(
            id = id,
            title = title,
            thumbnail = thumb,
            subtitle = subtitle,
            isAlbum = isAlbum,
            isVideo = isVideo,
        )
    }

    private fun parseResponsiveSong(item: JsonObject): YtmSong? {
        val flexColumns = (item["flexColumns"] as? JsonArray) ?: return null
        val titleText = flexColumns.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")
        val title = titleText.runsText() ?: return null
        val videoId = (titleText?.findFirst("videoId") as? JsonPrimitive)?.contentOrNull ?: return null
        val subtitleRuns = flexColumns.getOrNull(1)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject?.get("runs") as? JsonArray
        val artist = subtitleRuns
            ?.mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull }
            ?.firstOrNull { it.isNotBlank() && it != " · " && it != "•" }
            .orEmpty()

        // Thumbnail: try standard path first, fall back to searching whole item tree
        val thumb = item["thumbnail"].bestThumbnail()
            ?: item.bestThumbnailAnywhere()

        return YtmSong(
            videoId = videoId, title = title, artist = artist,
            album = null, thumbnail = thumb, durationSeconds = null,
        )
    }
}
