package com.streamcloud.app.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

    /** Strip feat., official video, and other suffixes YouTube Music adds to track names. */
    private fun cleanTitle(title: String): String =
        title
            .replace(Regex("""\s*[\(\[]?(feat\.?|ft\.?|featuring)\s+[^\(\)\[\]]*[\)\]]?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[\(\[](official\s+(?:video|audio|music video|lyric video|visualizer)|lyrics?|lyric video|audio|mv|m/v|hd|4k|visualizer|prod\.?[^\)\]]*)\s*[\)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*(official\s+\w+|lyrics?|audio)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    /** Strip YouTube-added suffixes from artist names (e.g. "· Topic", "VEVO"). */
    private fun cleanArtist(artist: String): String =
        artist
            .replace(Regex("""\s*[·•\-]\s*(?:topic|music|official|vevo|records?|entertainment)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""VEVO\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    suspend fun fetch(track: String, artist: String, durationSec: Long): LrcEntry? =
        withContext(Dispatchers.IO) {
            val cleanTrack = cleanTitle(track)
            val cleanArt  = cleanArtist(artist)

            // 1. Exact endpoint with duration (tight match — best result when available)
            if (durationSec > 0) {
                runCatching { fetchExact(cleanTrack, cleanArt, durationSec) }.getOrNull()
                    ?.let { return@withContext it }
            }

            // 2. Structured field search: track_name + artist_name params
            runCatching { fetchByField(cleanTrack, cleanArt) }.getOrNull()
                ?.let { return@withContext it }

            // 3. Free-text search with cleaned values
            runCatching { fetchSearch(cleanTrack, cleanArt) }.getOrNull()
                ?.let { return@withContext it }

            // 4. Track-only structured search (no artist — broader match)
            runCatching { fetchByField(cleanTrack, null) }.getOrNull()
                ?.let { return@withContext it }

            // 5. Free-text with just track name
            runCatching { fetchSearch(cleanTrack, "") }.getOrNull()
                ?.let { return@withContext it }

            // 6. Raw original values as last resort
            if (cleanTrack != track || cleanArt != artist) {
                runCatching { fetchSearch(track, artist) }.getOrNull()
            } else null
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
            val entry = json.decodeFromString(LrcEntry.serializer(), body)
            if (entry.instrumental) return null           // skip instrumentals
            if (entry.syncedLyrics.isNullOrBlank() && entry.plainLyrics.isNullOrBlank()) return null
            return entry
        }
    }

    /** Structured LRCLIB search using individual track_name / artist_name params. */
    private fun fetchByField(track: String, artist: String?): LrcEntry? {
        val sb = StringBuilder("https://lrclib.net/api/search?")
            .append("track_name=").append(URLEncoder.encode(track, "UTF-8"))
        if (!artist.isNullOrBlank()) {
            sb.append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
        }
        val resp = http.newCall(Request.Builder().url(sb.toString()).build()).execute()
        resp.use {
            if (!it.isSuccessful) return null
            val body = it.body?.string().orEmpty()
            val list = json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(LrcEntry.serializer()), body,
            )
            return list.firstOrNull { e ->
                !e.instrumental && (!e.syncedLyrics.isNullOrBlank() || !e.plainLyrics.isNullOrBlank())
            } ?: list.firstOrNull { e -> !e.instrumental } ?: list.firstOrNull()
        }
    }

    private fun fetchSearch(track: String, artist: String): LrcEntry? {
        val q = if (artist.isBlank()) track else "$track $artist"
        val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8")
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            if (!it.isSuccessful) return null
            val body = it.body?.string().orEmpty()
            val list = json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(LrcEntry.serializer()), body,
            )
            return list.firstOrNull { e ->
                !e.instrumental && (!e.syncedLyrics.isNullOrBlank() || !e.plainLyrics.isNullOrBlank())
            } ?: list.firstOrNull { e -> !e.instrumental } ?: list.firstOrNull()
        }
    }


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
