package com.streamcloud.app.data.nuvio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamcloud.app.data.network.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.nuvioStore by preferencesDataStore("streamcloud_nuvio")
private val KEY_INSTALLED = stringPreferencesKey("installed_json")

class NuvioRepository(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun cacheDir(): File =
        File(context.filesDir, "nuvio").apply { mkdirs() }

    val installed: Flow<List<InstalledNuvioProvider>> = context.nuvioStore.data.map { prefs ->
        prefs[KEY_INSTALLED]?.let {
            runCatching {
                Net.json.decodeFromString(ListSerializer(InstalledNuvioProvider.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun fetchManifest(repoUrl: String): NuvioRepoManifest = withContext(Dispatchers.IO) {
        val url = normaliseRepoUrl(repoUrl)
        val body = httpGet(url)
        Net.json.decodeFromString(NuvioRepoManifest.serializer(), body)
    }

    suspend fun installProvider(repoUrl: String, entry: NuvioProviderEntry): InstalledNuvioProvider =
        withContext(Dispatchers.IO) {
            val manifestUrl = normaliseRepoUrl(repoUrl)
            val absDl = resolveDownloadUrl(manifestUrl, entry)
                ?: error("Provider entry has no downloadUrl/url")
            val safeId = entry.id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val outFile = File(cacheDir(), "$safeId.js")
            val text = httpGet(absDl)
            outFile.writeText(text)

            val rec = InstalledNuvioProvider(
                id = entry.id, name = entry.name,
                repoUrl = manifestUrl, downloadUrl = absDl,
                filePath = outFile.absolutePath, installedAt = System.currentTimeMillis(),
                logo = entry.logo ?: entry.icon, description = entry.description,
            )
            val list = installed.first().filterNot { it.id == entry.id } + rec
            save(list)
            rec
        }

    suspend fun uninstall(id: String) {
        val list = installed.first()
        list.firstOrNull { it.id == id }?.let { File(it.filePath).delete() }
        save(list.filterNot { it.id == id })
    }


    suspend fun resolveAll(
        tmdbId: String,
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
    ): List<Pair<InstalledNuvioProvider, NuvioStream>> = coroutineScope {
        // Strip any prefix so we always work with a bare numeric TMDB ID.
        val numericId = numericTmdbId(tmdbId)
        // Resolve to IMDB ID (tt…) — the first-arg contract of every Nuvio provider.
        val imdbId = resolveImdbId(numericId, mediaType) ?: ""
        val list = installed.first()
        list.map { provider ->
            async(Dispatchers.IO) {
                val js = runCatching { File(provider.filePath).readText() }.getOrNull()
                    ?: return@async emptyList()
                val streams = NuvioRuntime.runProvider(
                    scriptText = js,
                    tmdbId = numericId,
                    imdbId = imdbId,
                    mediaType = normaliseMediaType(mediaType),
                    season = season,
                    episode = episode,
                    scriptKey = provider.id,
                )
                streams.map { provider to it }
            }
        }.awaitAll().flatten()
    }

    // Re-run a single provider on demand — used when the user selects a Nuvio
    // source from the player source picker to get a fresh (non-stale) URL.
    suspend fun resolveOne(
        providerId: String,
        tmdbId: String,
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
    ): List<Pair<InstalledNuvioProvider, NuvioStream>> = withContext(Dispatchers.IO) {
        val numericId = numericTmdbId(tmdbId)
        val imdbId = resolveImdbId(numericId, mediaType) ?: ""
        val provider = installed.first().firstOrNull { it.id == providerId }
            ?: return@withContext emptyList()
        val js = runCatching { File(provider.filePath).readText() }.getOrNull()
            ?: return@withContext emptyList()
        val streams = NuvioRuntime.runProvider(
            scriptText = js,
            tmdbId = numericId,
            imdbId = imdbId,
            mediaType = normaliseMediaType(mediaType),
            season = season,
            episode = episode,
            scriptKey = provider.id,
        )
        streams.map { provider to it }
    }


    // Strip any textual prefix (tmdb:/movie:/series:) and return the bare numeric ID.
    private fun numericTmdbId(raw: String): String =
        raw.trim()
            .removePrefix("tmdb:").removePrefix("tmdb/")
            .removePrefix("movie:").removePrefix("series:")
            .substringBefore(':').substringBefore('/')
            .trim()

    // TMDB numeric ID → IMDB "tt..." ID.
    // This is the direction Nuvio providers need: their getStreams() first argument
    // is an IMDB ID, not a numeric TMDB ID.
    private val imdbIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private suspend fun resolveImdbId(numericId: String, mediaType: String): String? {
        if (numericId.isBlank()) return null

        // If the caller already handed us an IMDB ID, return it unchanged.
        if (numericId.startsWith("tt", ignoreCase = true)) return numericId.lowercase()

        // Only process bare numeric TMDB IDs.
        if (!numericId.all(Char::isDigit)) return null

        val cacheKey = "$numericId:${normaliseMediaType(mediaType)}"
        imdbIdCache[cacheKey]?.let { return it }

        return withContext(Dispatchers.IO) {
            val apiKey = com.streamcloud.app.BuildConfig.TMDB_API_KEY
            if (apiKey.isBlank()) return@withContext null
            val type = if (normaliseMediaType(mediaType) == "tv") "tv" else "movie"
            val url = "https://api.themoviedb.org/3/$type/$numericId/external_ids?api_key=$apiKey"
            runCatching {
                val text = httpGet(url)
                val root = Net.json.parseToJsonElement(text) as?
                    kotlinx.serialization.json.JsonObject ?: return@runCatching null
                val imdb = (root["imdb_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.takeIf { it.isNotBlank() && it != "null" && it.startsWith("tt") }
                if (imdb != null) imdbIdCache[cacheKey] = imdb
                imdb
            }.getOrNull()
        }
    }

    private fun normaliseMediaType(mediaType: String): String =
        when (mediaType.trim().lowercase()) {
            "movie", "film" -> "movie"
            "tv", "series", "show", "tvshow" -> "tv"
            else -> mediaType.trim().lowercase()
        }

    private suspend fun save(list: List<InstalledNuvioProvider>) {
        val text = Net.json.encodeToString(ListSerializer(InstalledNuvioProvider.serializer()), list)
        context.nuvioStore.edit { it[KEY_INSTALLED] = text }
    }

    private fun normaliseRepoUrl(s: String): String {
        val t = s.trim()
        return when {
            t.endsWith("manifest.json") -> t
            t.startsWith("http") -> "$t/manifest.json".replace("//manifest.json", "/manifest.json")
            else -> "https://$t/manifest.json"
        }
    }


    private fun resolveDownloadUrl(manifestUrl: String, e: NuvioProviderEntry): String? {
        val raw = e.downloadUrl ?: e.downloadUrlSnake ?: e.url ?: e.filename ?: return null
        if (raw.startsWith("http")) return raw

        val base = manifestUrl.substringBeforeLast('/')
        return "$base/${raw.trimStart('/')}"
    }

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "StreamCloud/1.0 (Nuvio compatible)")
            .header("Accept", "application/json, text/javascript, */*")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} from $url")
            return resp.body?.string().orEmpty()
        }
    }
}
