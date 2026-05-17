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

/**
 * Repository for Nuvio JS providers. Two-step install flow:
 *   1. User pastes a manifest URL → we fetch + parse the repo's `manifest.json`.
 *   2. User picks providers from the list → each .js file is downloaded once and
 *      cached at /files/nuvio/<id>.js. The runtime just reads the cached file.
 */
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

    /** Run every installed provider in parallel against [tmdbId] and aggregate streams.
     *
     *  Matches the NuvioMobile contract (`composeApp/.../streams/StreamsRepository.kt`):
     *   • `tmdbId` may be prefixed (`tmdb:603`, `tmdb/603`, `imdb:tt0133093`, …) — we
     *     strip the prefix and resolve IMDB → TMDB via the public `/find` endpoint
     *     when needed (otherwise the JS providers receive a non-numeric id and bail).
     *   • Episode-suffixes (`603:1:1`) are stripped since [season]/[episode] are passed
     *     as separate args to `getStreams`.
     */
    suspend fun resolveAll(
        tmdbId: String,
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
    ): List<Pair<InstalledNuvioProvider, NuvioStream>> = coroutineScope {
        val resolvedTmdb = resolveTmdbId(tmdbId, mediaType) ?: tmdbId
        val list = installed.first()
        list.map { provider ->
            async(Dispatchers.IO) {
                val js = runCatching { File(provider.filePath).readText() }.getOrNull()
                    ?: return@async emptyList()
                val streams = NuvioRuntime.runProvider(
                    scriptText = js,
                    tmdbId = resolvedTmdb,
                    mediaType = normaliseMediaType(mediaType),
                    season = season,
                    episode = episode,
                    scriptKey = provider.id,
                )
                streams.map { provider to it }
            }
        }.awaitAll().flatten()
    }

    /**
     * Reverse of NuvioMobile's `TmdbService.ensureTmdbId`. Accepts:
     *   • Raw numeric TMDB ids ("603")
     *   • Prefixed ("tmdb:603", "tmdb/603")
     *   • IMDB ids ("tt0133093") → resolved via `/find/{imdb_id}?external_source=imdb_id`
     *   • Episode-suffixed ("603:1:5") → strip episode portion
     *
     * Cached in memory so repeated tmdb→imdb hops on the same screen are free.
     */
    private val tmdbIdCache = mutableMapOf<String, String>()
    private suspend fun resolveTmdbId(raw: String, mediaType: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val noPrefix = trimmed
            .removePrefix("tmdb:").removePrefix("tmdb/")
            .removePrefix("imdb:").removePrefix("movie:").removePrefix("series:")
            .substringBefore(':').substringBefore('/')
            .trim()
        if (noPrefix.isBlank()) return null
        if (noPrefix.all(Char::isDigit)) return noPrefix
        if (!noPrefix.startsWith("tt", ignoreCase = true)) return null

        val cacheKey = "$noPrefix:${normaliseMediaType(mediaType)}"
        tmdbIdCache[cacheKey]?.let { return it }

        return withContext(Dispatchers.IO) {
            val apiKey = com.streamcloud.app.BuildConfig.TMDB_API_KEY
            if (apiKey.isBlank()) return@withContext null
            val url = "https://api.themoviedb.org/3/find/$noPrefix?api_key=$apiKey&external_source=imdb_id"
            runCatching {
                val text = httpGet(url)
                val root = Net.json.parseToJsonElement(text) as?
                    kotlinx.serialization.json.JsonObject ?: return@runCatching null
                val results = when (normaliseMediaType(mediaType)) {
                    "tv" -> root["tv_results"] as? kotlinx.serialization.json.JsonArray
                    else -> root["movie_results"] as? kotlinx.serialization.json.JsonArray
                } ?: return@runCatching null
                val first = results.firstOrNull() as? kotlinx.serialization.json.JsonObject
                val id = (first?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.takeIf { it.isNotBlank() && it != "0" }
                if (id != null) tmdbIdCache[cacheKey] = id
                id
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

    /** Resolve the .js downloadUrl relative to the manifest URL when needed. */
    private fun resolveDownloadUrl(manifestUrl: String, e: NuvioProviderEntry): String? {
        val raw = e.downloadUrl ?: e.downloadUrlSnake ?: e.url ?: e.filename ?: return null
        if (raw.startsWith("http")) return raw
        // Relative — resolve against manifest's base.
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
