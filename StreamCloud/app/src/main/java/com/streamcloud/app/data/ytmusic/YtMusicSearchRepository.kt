package com.streamcloud.app.data.ytmusic

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
 * Searches YouTube Music directly via the InnerTube API (same backend SimpMusic uses).
 * Returns results from the real YouTube Music catalog — better ranking, correct artist
 * profiles, proper album/single categorisation, and genuine autocomplete suggestions.
 *
 * All methods fall back gracefully (returning emptyList) on network or parse failure.
 */
object YtMusicSearchRepository {

    // Anonymous client — search doesn't need auth
    private val client = InnerTubeClient("")

    // Base64-encoded protobuf filter tokens captured from the YouTube Music web client
    private const val PARAM_SONGS   = "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo="
    private const val PARAM_VIDEOS  = "Eg-KAQwIARABGAAgACgAMABqChAEEAMQCRAFEAo="
    private const val PARAM_ALBUMS  = "Eg-KAQwIABAAGAEgACgAMABqChAEEAMQCRAFEAo="
    private const val PARAM_ARTISTS = "Eg-KAQwIABAAGAAgASgAMABqChAEEAMQCRAFEAo="

    // ── Autocomplete ──────────────────────────────────────────────────────────────

    suspend fun suggestions(input: String): List<String> = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext emptyList()
        val resp = client.searchSuggestions(input) ?: return@withContext emptyList()
        resp.findAll("searchSuggestionRenderer").mapNotNull { item ->
            (item as? JsonObject)
                ?.let { it["navigationEndpoint"] as? JsonObject }
                ?.let { it["searchEndpoint"] as? JsonObject }
                ?.get("query")?.jsonPrimitive?.contentOrNull
        }
    }

    // ── Songs ─────────────────────────────────────────────────────────────────────

    suspend fun songs(query: String): List<YtTrack> = withContext(Dispatchers.IO) {
        val resp = client.search(query, PARAM_SONGS) ?: return@withContext emptyList()
        resp.collectResponsiveListItems().mapNotNull { item ->
            parseSongItem(item, isVideo = false)
        }
    }

    // ── Videos ───────────────────────────────────────────────────────────────────

    suspend fun videos(query: String): List<YtTrack> = withContext(Dispatchers.IO) {
        val resp = client.search(query, PARAM_VIDEOS) ?: return@withContext emptyList()
        resp.collectResponsiveListItems().mapNotNull { item ->
            parseSongItem(item, isVideo = true)
        }
    }

    // ── Albums / Singles ──────────────────────────────────────────────────────────

    suspend fun albums(query: String): List<YtAlbum> = withContext(Dispatchers.IO) {
        val resp = client.search(query, PARAM_ALBUMS) ?: return@withContext emptyList()
        resp.collectTwoRowItems().mapNotNull { item -> parseAlbumItem(item) }
    }

    // ── Artists ───────────────────────────────────────────────────────────────────

    suspend fun artists(query: String): List<YtArtist> = withContext(Dispatchers.IO) {
        val resp = client.search(query, PARAM_ARTISTS) ?: return@withContext emptyList()
        resp.collectTwoRowItems().mapNotNull { item -> parseArtistItem(item) }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────────

    private fun parseSongItem(item: JsonObject, isVideo: Boolean): YtTrack? {
        // videoId lives inside the navigation endpoint of the title run or the overlay button
        val videoId = item.findFirst("videoId")?.jsonPrimitive?.contentOrNull
            ?: return null
        val flexColumns = item["flexColumns"] as? JsonArray ?: return null
        val title = flexColumns.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text").runsText() ?: return null
        val artist = flexColumns.getOrNull(1)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text").runsText()?.split(" • ")?.firstOrNull()?.trim() ?: ""
        val durationText = (item["fixedColumns"] as? JsonArray)
            ?.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFixedColumnRenderer")?.jsonObject
            ?.get("text").runsText()
        return YtTrack(
            title = title,
            uploader = artist,
            durationSec = parseDuration(durationText),
            url = "https://music.youtube.com/watch?v=$videoId",
            thumbnail = item["thumbnail"].bestThumbnail(),
            isVideo = isVideo,
        )
    }

    private fun parseAlbumItem(item: JsonObject): YtAlbum? {
        val browseId = item.findFirst("browseId")?.jsonPrimitive?.contentOrNull
            ?: return null
        val title = item["title"].runsText() ?: return null
        val subtitle = item["subtitle"].runsText() ?: ""
        val parts = subtitle.split(" • ")
        val artist = parts.getOrNull(0)?.trim() ?: ""
        val year = parts.getOrNull(1)?.trim()?.takeIf { it.matches(Regex("\\d{4}")) }
        val thumb = item["thumbnailRenderer"].bestThumbnail()
            ?: item["thumbnail"].bestThumbnail()
        return YtAlbum(
            title = title,
            artist = artist,
            url = "https://music.youtube.com/browse/$browseId",
            thumbnail = thumb,
            year = year,
        )
    }

    private fun parseArtistItem(item: JsonObject): YtArtist? {
        val browseId = item.findFirst("browseId")?.jsonPrimitive?.contentOrNull
            ?: return null
        val name = item["title"].runsText() ?: return null
        val sub = item["subtitle"].runsText()
        val thumb = item["thumbnailRenderer"].bestThumbnail()
            ?: item["thumbnail"].bestThumbnail()
        return YtArtist(
            name = name,
            url = "https://www.youtube.com/channel/$browseId",
            thumbnail = thumb,
            subscriberLabel = sub,
        )
    }

    private fun parseDuration(text: String?): Long {
        val t = text?.trim() ?: return 0L
        val parts = t.split(":").map { it.toLongOrNull() ?: 0L }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }
}
