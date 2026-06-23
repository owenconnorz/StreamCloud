package com.streamcloud.app.data.livetv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LiveTvRepository(context: Context) {

    private val prefs = context.getSharedPreferences("livetv_prefs", Context.MODE_PRIVATE)

    // ── Source persistence ──────────────────────────────────────────────────

    fun loadSources(): List<LiveTvSource> {
        val raw = prefs.getString("sources", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    LiveTvSource(
                        id           = o.optString("id"),
                        name         = o.optString("name"),
                        type         = SourceType.valueOf(o.optString("type", "M3U_URL")),
                        url          = o.optString("url"),
                        xtreamServer = o.optString("xtreamServer"),
                        xtreamUser   = o.optString("xtreamUser"),
                        xtreamPass   = o.optString("xtreamPass"),
                        epgUrl       = o.optString("epgUrl"),
                    )
                }.getOrNull()
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveSources(sources: List<LiveTvSource>) {
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("type", s.type.name)
                put("url", s.url); put("xtreamServer", s.xtreamServer)
                put("xtreamUser", s.xtreamUser); put("xtreamPass", s.xtreamPass)
                put("epgUrl", s.epgUrl)
            })
        }
        prefs.edit().putString("sources", arr.toString()).apply()
    }

    // ── Channel fetching ────────────────────────────────────────────────────

    suspend fun fetchChannels(source: LiveTvSource): List<LiveTvChannel> =
        withContext(Dispatchers.IO) {
            when (source.type) {
                SourceType.M3U_URL -> parseM3u(fetchUrl(source.url), source.id)
                SourceType.XTREAM  -> fetchXtreamChannels(source)
                SourceType.SINGLE  -> listOf(
                    LiveTvChannel(id = source.id, name = source.name,
                                  url = source.url, sourceId = source.id)
                )
            }
        }

    private fun parseM3u(content: String, sourceId: String): List<LiveTvChannel> {
        val logoRe  = Regex("""tvg-logo="([^"]*)"""")
        val groupRe = Regex("""group-title="([^"]*)"""")
        val epgRe   = Regex("""tvg-id="([^"]*)"""")
        val channels = mutableListOf<LiveTvChannel>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val name  = line.substringAfterLast(",").trim().ifBlank { "Channel ${channels.size + 1}" }
                val logo  = logoRe.find(line)?.groupValues?.getOrNull(1) ?: ""
                val group = groupRe.find(line)?.groupValues?.getOrNull(1)?.ifBlank { "General" } ?: "General"
                val epgId = epgRe.find(line)?.groupValues?.getOrNull(1) ?: ""
                var j = i + 1
                while (j < lines.size && lines[j].isBlank()) j++
                val url = if (j < lines.size) lines[j].trim() else ""
                if (url.isNotBlank() && !url.startsWith("#")) {
                    channels += LiveTvChannel(
                        id = "${sourceId}_${channels.size}",
                        name = name, url = url, logo = logo,
                        group = group, epgId = epgId, sourceId = sourceId,
                    )
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return channels
    }

    private fun fetchXtreamChannels(source: LiveTvSource): List<LiveTvChannel> {
        val base = source.xtreamServer.trimEnd('/')
        val u = source.xtreamUser; val p = source.xtreamPass
        val raw = fetchUrl("$base/player_api.php?username=$u&password=$p&action=get_live_streams")
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o   = arr.getJSONObject(i)
                val sid = o.optInt("stream_id", i)
                val ext = o.optString("container_extension", "ts")
                LiveTvChannel(
                    id       = "${source.id}_$sid",
                    name     = o.optString("name", "Channel $i"),
                    url      = "$base/live/$u/$p/$sid.$ext",
                    logo     = o.optString("stream_icon", ""),
                    group    = o.optString("category_name", "General"),
                    epgId    = o.optString("epg_channel_id", ""),
                    sourceId = source.id,
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Fetch short EPG (current + next) for a single channel from an Xtream server. */
    suspend fun fetchShortEpg(source: LiveTvSource, streamId: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            if (source.type != SourceType.XTREAM) return@withContext Pair("", "")
            try {
                val sid  = streamId.substringAfterLast("_")
                val base = source.xtreamServer.trimEnd('/')
                val raw  = fetchUrl(
                    "$base/player_api.php?username=${source.xtreamUser}" +
                    "&password=${source.xtreamPass}&action=get_short_epg&stream_id=$sid&limit=2"
                )
                val listings = JSONObject(raw).optJSONArray("epg_listings")
                    ?: return@withContext Pair("", "")
                val current = if (listings.length() > 0)
                    listings.getJSONObject(0).optString("title", "") else ""
                val next = if (listings.length() > 1)
                    listings.getJSONObject(1).optString("title", "") else ""
                Pair(current, next)
            } catch (e: Exception) { Pair("", "") }
        }

    // ── HTTP helper ─────────────────────────────────────────────────────────

    private fun fetchUrl(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 30_000
        conn.setRequestProperty("User-Agent", "StreamCloud/1.0")
        return try { conn.inputStream.bufferedReader().readText() }
        finally   { conn.disconnect() }
    }
}
