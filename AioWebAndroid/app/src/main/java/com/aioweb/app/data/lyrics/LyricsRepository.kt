package com.aioweb.app.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * LRClib (https://lrclib.net) free, no-auth lyrics service.
 * Returns synced (LRC) when available, falls back to plain.
 */

@Serializable
data class LrcEntry(
    val id: Long = 0,
    val name: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

object LyricsRepository {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @return null if no lyrics found.
     */
    suspend fun fetch(track: String, artist: String, durationSec: Long): LrcEntry? =
        withContext(Dispatchers.IO) {
            // 1. Exact match endpoint — fastest
            val exact = runCatching { fetchExact(track, artist, durationSec) }.getOrNull()
            if (exact != null) return@withContext exact
            // 2. Search endpoint as fallback (artist may not perfectly match)
            runCatching { fetchSearch(track, artist) }.getOrNull()
        }

    private fun fetchExact(track: String, artist: String, durationSec: Long): LrcEntry? {
        val url = StringBuilder("https://lrclib.net/api/get?")
            .append("track_name=").append(URLEncoder.encode(track, "UTF-8"))
            .append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
            .append("&duration=").append(durationSec)
            .toString()
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) error("LRClib HTTP ${it.code}")
            val body = it.body?.string().orEmpty()
            return json.decodeFromString(LrcEntry.serializer(), body)
        }
    }

    private fun fetchSearch(track: String, artist: String): LrcEntry? {
        val url = "https://lrclib.net/api/search?q=" +
            URLEncoder.encode("$track $artist", "UTF-8")
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            if (!it.isSuccessful) return null
            val body = it.body?.string().orEmpty()
            val list = json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(LrcEntry.serializer()), body,
            )
            return list.firstOrNull { e ->
                !e.syncedLyrics.isNullOrBlank() || !e.plainLyrics.isNullOrBlank()
            } ?: list.firstOrNull()
        }
    }

    /**
     * Parses an LRC-format string into a list of [(timeMs, line)] pairs sorted by timestamp.
     */
    fun parseLrc(lrc: String): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        val re = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]""")
        lrc.lineSequence().forEach { rawLine ->
            val matches = re.findAll(rawLine).toList()
            if (matches.isEmpty()) return@forEach
            val text = rawLine.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val mins = m.groupValues[1].toInt()
                val secs = m.groupValues[2].toInt()
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toIntOrNull() ?: 0
                val ms = (mins * 60 + secs) * 1000L + frac
                out += ms to text
            }
        }
        return out.sortedBy { it.first }
    }
}
