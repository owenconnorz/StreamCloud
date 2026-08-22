package com.streamcloud.app.data.lyrics

import com.streamcloud.app.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "Lyrics"

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
        // lrclib.net is behind Cloudflare which returns 520 for the default OkHttp UA
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                    .header("Accept", "application/json")
                    .build()
            )
        }
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

            AppLogger.i(TAG, "fetch: track='$cleanTrack' artist='$cleanArt' dur=${durationSec}s (raw: '$track' / '$artist')")

            // 1. Exact endpoint with duration (tight match — best result when available)
            if (durationSec > 0) {
                val result = runCatching { fetchExact(cleanTrack, cleanArt, durationSec) }
                result.exceptionOrNull()?.let { AppLogger.w(TAG, "Stage 1 (exact) error: ${it.message}") }
                result.getOrNull()?.let {
                    AppLogger.i(TAG, "Stage 1 (exact) hit: '${it.name}' by '${it.artistName}'")
                    return@withContext it
                }
            }

            // 2. Structured field search: track_name + artist_name params
            val r2 = runCatching { fetchByField(cleanTrack, cleanArt) }
            r2.exceptionOrNull()?.let { AppLogger.w(TAG, "Stage 2 (field) error: ${it.message}") }
            r2.getOrNull()?.let {
                AppLogger.i(TAG, "Stage 2 (field) hit: '${it.name}' by '${it.artistName}'")
                return@withContext it
            } ?: AppLogger.i(TAG, "Stage 2 (field) miss for '$cleanTrack' / '$cleanArt'")

            // 3. Free-text search with cleaned values
            val r3 = runCatching { fetchSearch(cleanTrack, cleanArt) }
            r3.exceptionOrNull()?.let { AppLogger.w(TAG, "Stage 3 (q-clean) error: ${it.message}") }
            r3.getOrNull()?.let {
                AppLogger.i(TAG, "Stage 3 (q-clean) hit: '${it.name}' by '${it.artistName}'")
                return@withContext it
            } ?: AppLogger.i(TAG, "Stage 3 (q-clean) miss")

            // 4. Track-only structured search (no artist — broader match)
            val r4 = runCatching { fetchByField(cleanTrack, null) }
            r4.exceptionOrNull()?.let { AppLogger.w(TAG, "Stage 4 (field-no-artist) error: ${it.message}") }
            r4.getOrNull()?.let {
                AppLogger.i(TAG, "Stage 4 (field-no-artist) hit: '${it.name}' by '${it.artistName}'")
                return@withContext it
            } ?: AppLogger.i(TAG, "Stage 4 (field-no-artist) miss")

            // 5. Free-text with just track name
            val r5 = runCatching { fetchSearch(cleanTrack, "") }
            r5.exceptionOrNull()?.let { AppLogger.w(TAG, "Stage 5 (q-track-only) error: ${it.message}") }
            r5.getOrNull()?.let {
                AppLogger.i(TAG, "Stage 5 (q-track-only) hit: '${it.name}' by '${it.artistName}'")
                return@withContext it
            } ?: AppLogger.i(TAG, "Stage 5 (q-track-only) miss")

            // 6. Raw original values as last resort
            if (cleanTrack != track || cleanArt != artist) {
                val r6 = runCatching { fetchSearch(track, artist) }
                r6.exceptionOrNull()?.let { AppLogger.w(TAG, "Stage 6 (raw) error: ${it.message}") }
                r6.getOrNull()?.let {
                    AppLogger.i(TAG, "Stage 6 (raw) hit: '${it.name}' by '${it.artistName}'")
                    return@withContext it
                } ?: AppLogger.i(TAG, "Stage 6 (raw) miss — no lyrics found")
                null
            } else {
                AppLogger.i(TAG, "All stages exhausted — no lyrics for '$cleanTrack'")
                null
            }
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
            if (entry.instrumental) return null
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
        val url = sb.toString()
        AppLogger.i(TAG, "fetchByField url: $url")
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            AppLogger.i(TAG, "fetchByField response: ${it.code}")
            if (!it.isSuccessful) return null
            val body = it.body?.string().orEmpty()
            val list = json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(LrcEntry.serializer()), body,
            )
            AppLogger.i(TAG, "fetchByField parsed ${list.size} results")
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

    fun parseLrc(lrc: String?): List<Pair<Long, String>> {
        if (lrc.isNullOrBlank()) return emptyList()
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
