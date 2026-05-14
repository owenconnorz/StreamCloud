package com.aioweb.app.data.ytmusic

import android.content.Context
import android.util.Log
import com.aioweb.app.data.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Fetches "Up next" / radio-style related songs for a YT Music video id. This
 * is what powers Metrolist-style **endless playback** — tap any song and a
 * 20-track auto-radio is queued behind it so skip / previous always have
 * somewhere to go (and the system notification's skip controls light up).
 *
 * Uses the public InnerTube `next` endpoint, same one music.youtube.com itself
 * calls when you click a track on the home page. No auth required, but if a
 * cookie is present we forward it so personalised mixes show up.
 */
object EndlessPlayback {
    private const val TAG = "EndlessPlayback"

    /**
     * Returns up to [limit] related songs (excluding [videoId] itself). On any
     * failure (network, parse, no cookie) returns an empty list — caller
     * should treat that as "no auto-radio available" and continue silently.
     */
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
                    put("hl", "en"); put("gl", "US")
                    put("platform", "DESKTOP")
                }
            }
            put("videoId", videoId)
            put("isAudioOnly", true)
            // `RDAMVM<id>` = "Radio based on this song" — the same mix the
            // YT Music web UI builds when you Right-click → Start radio.
            put("playlistId", "RDAMVM$videoId")
        }

        val response = runCatching { client.next(body) }.getOrNull() ?: return emptyList()

        return parseWatchNext(response, excludeVideoId = videoId).take(limit)
    }

    /**
     * The `next` response wraps the queue in
     * `contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer
     *   .watchNextTabbedResultsRenderer.tabs[0].tabRenderer.content
     *   .musicQueueRenderer.content.playlistPanelRenderer.contents[]`.
     *
     * Each entry is a `playlistPanelVideoRenderer` we can map to [YtmSong].
     */
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

            val title = node["title"].runsText()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            // longBylineText / shortBylineText holds "Artist · Album · Year" runs.
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

    /** Split a YT byline like "Artist · Album · 2024" into (artist, album?). */
    private fun splitArtistAlbum(byline: String): Pair<String, String?> {
        val parts = byline.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        return when (parts.size) {
            0 -> "" to null
            1 -> parts[0] to null
            else -> parts[0] to parts[1].takeUnless { it.matches(Regex("^\\d{4}$")) }
        }
    }

    /** "3:45" → 225L. Returns null if the string isn't a numeric duration. */
    private fun String.parseDurationSec(): Long? {
        val parts = split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        return parts.fold(0L) { acc, n -> acc * 60 + n }
    }
}
