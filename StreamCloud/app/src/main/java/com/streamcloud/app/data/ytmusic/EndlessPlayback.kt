package com.streamcloud.app.data.ytmusic

import android.content.Context
import android.util.Log
import com.streamcloud.app.data.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object EndlessPlayback {
    private const val TAG = "EndlessPlayback"


    suspend fun relatedSongs(
        context: Context,
        videoId: String,
        limit: Int = 20,
    ): List<YtmSong> {
        val cookie = runCatching {
            ServiceLocator.get(context).settings.ytMusicCookie.first()
        }.getOrNull().orEmpty()

        val client = InnerTubeClient(cookie)
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250127.01.00")
                    put("hl", YtPlayerUtils.contentLanguage)
                    put("gl", YtPlayerUtils.contentCountry)
                    put("platform", "DESKTOP")
                }
            }
            put("videoId", videoId)
            put("isAudioOnly", true)


            put("playlistId", "RDAMVM$videoId")
        }

        val response = runCatching { client.next(body) }.getOrNull() ?: return emptyList()

        return parseWatchNext(response, excludeVideoId = videoId).take(limit)
    }


    private fun parseWatchNext(root: JsonObject, excludeVideoId: String): List<YtmSong> {
        val nodes = root.findAll("playlistPanelVideoRenderer")
            .mapNotNull { it as? JsonObject }
        if (nodes.isEmpty()) {
            Log.d(TAG, "No playlistPanelVideoRenderer entries in `next` response")
            return emptyList()
        }
        return nodes.mapNotNull { node ->
            val vid = node["videoId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (vid == excludeVideoId) return@mapNotNull null

            val musicVideoType = node["navigationEndpoint"]?.jsonObject
                ?.get("watchEndpoint")?.jsonObject
                ?.get("watchEndpointMusicSupportedConfigs")?.jsonObject
                ?.get("watchEndpointMusicConfig")?.jsonObject
                ?.get("musicVideoType")?.jsonPrimitive?.contentOrNull
            if (musicVideoType == "MUSIC_VIDEO_TYPE_OMV" || musicVideoType == "MUSIC_VIDEO_TYPE_UGC") {
                return@mapNotNull null
            }

            val title = node["title"].runsText()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null


            val byline = node["longBylineText"].runsText()
                ?: node["shortBylineText"].runsText().orEmpty()
            val (artist, album) = splitArtistAlbum(byline)

            val thumb = node.bestThumbnail()
            val durationSec = node["lengthText"].runsText()?.parseDurationSec()

            YtmSong(
                videoId = vid,
                title = title,
                artist = artist.ifBlank { "Unknown artist" },
                album = album,
                thumbnail = thumb,
                durationSeconds = durationSec,
            )
        }
    }


    private fun splitArtistAlbum(byline: String): Pair<String, String?> {
        val parts = byline.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        return when (parts.size) {
            0 -> "" to null
            1 -> parts[0] to null
            else -> parts[0] to parts[1].takeUnless { it.matches(Regex("^\\d{4}$")) }
        }
    }


    private fun String.parseDurationSec(): Long? {
        val parts = split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        return parts.fold(0L) { acc, n -> acc * 60 + n }
    }
}
