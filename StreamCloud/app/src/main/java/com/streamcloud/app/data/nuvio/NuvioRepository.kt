package com.streamcloud.app.data.nuvio

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamcloud.app.data.network.Net
import com.streamcloud.app.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import com.streamcloud.app.data.network.BrowserCookieJar
import com.streamcloud.app.data.network.BrowserHeaders
import com.streamcloud.app.data.network.CloudflareKiller
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.nuvioStore by preferencesDataStore("streamcloud_nuvio")
private val KEY_INSTALLED   = stringPreferencesKey("installed_json")
private val KEY_SAVED_REPOS = stringPreferencesKey("saved_repos_json")
private const val TAG = "NuvioRepository"

class NuvioRepository(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .cookieJar(BrowserCookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun cacheDir(): File =
        File(context.filesDir, "nuvio").apply { mkdirs() }

    private suspend fun backendProxyUrl(): String? =
        SettingsRepository(context).backendUrl.first().trim().takeIf { it.isNotBlank() }

    val installed: Flow<List<InstalledNuvioProvider>> = context.nuvioStore.data.map { prefs ->
        prefs[KEY_INSTALLED]?.let {
            runCatching {
                Net.json.decodeFromString(ListSerializer(InstalledNuvioProvider.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    val savedRepos: Flow<List<NuvioSavedRepo>> = context.nuvioStore.data.map { prefs ->
        prefs[KEY_SAVED_REPOS]?.let {
            runCatching {
                Net.json.decodeFromString(ListSerializer(NuvioSavedRepo.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun addSavedRepo(url: String, name: String?) {
        val normalised = normaliseRepoUrl(url)
        val existing = savedRepos.first()
        if (existing.any { it.url == normalised }) return
        val repo = NuvioSavedRepo(
            id = normalised.hashCode().toString(),
            url = normalised,
            name = name,
            addedAt = System.currentTimeMillis(),
        )
        val updated = existing + repo
        context.nuvioStore.edit {
            it[KEY_SAVED_REPOS] = Net.json.encodeToString(ListSerializer(NuvioSavedRepo.serializer()), updated)
        }
    }

    suspend fun removeSavedRepo(id: String) {
        val updated = savedRepos.first().filterNot { it.id == id }
        context.nuvioStore.edit {
            it[KEY_SAVED_REPOS] = Net.json.encodeToString(ListSerializer(NuvioSavedRepo.serializer()), updated)
        }
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
                version = entry.version,
            )
            val list = installed.first().filterNot { it.id == entry.id } + rec
            save(list)
            rec
        }

    suspend fun updateProvider(updated: InstalledNuvioProvider) {
        val list = installed.first().map { if (it.id == updated.id) updated else it }
        save(list)
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
        imdbId: String? = null,
    ): List<Pair<InstalledNuvioProvider, NuvioStream>> = coroutineScope {
        val resolvedTmdb = resolvedTmdbIdOrWarn(tmdbId, mediaType, season, episode)
            ?: return@coroutineScope emptyList()
        // Auto-lookup IMDb ID from TMDB when caller didn't supply one.
        // Many Nuvio providers guard with `if (!imdbId) return` so passing null
        // causes phase=precheck (0 req) for every plugin.
        val effectiveImdbId = imdbId ?: lookupImdbId(resolvedTmdb, mediaType)
        val list = installed.first()
        list.map { provider ->
            async(Dispatchers.IO) {
                val js = runCatching { File(provider.filePath).readText() }.getOrNull()
                    ?: return@async emptyList()
                val normalizedContentId = normaliseNuvioContentId(resolvedTmdb, season, episode)
                    ?: resolvedTmdb
                val streams = NuvioRuntime.runProvider(
                    scriptText = js,
                    tmdbId = normalizedContentId,
                    imdbId = effectiveImdbId,
                    mediaType = nuvioMediaType(mediaType),
                    season = season,
                    episode = episode,
                    scriptKey = provider.id,
                    context = context,
                    filePath = provider.filePath,
                    proxyBaseUrl = backendProxyUrl(),
                )
                streams.map { provider to it }
            }
        }.awaitAll().flatten()
    }


    private val tmdbIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private suspend fun resolveTmdbId(raw: String, mediaType: String): String? {
        val noPrefix = normaliseNuvioIdToken(raw) ?: return null
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

    // Reverse-lookup: TMDB numeric ID → IMDb "tt..." ID.
    // Called when resolveAll is given no imdbId so that every provider receives
    // a real imdbId — the most common cause of phase=precheck (0 req).
    private val imdbIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private suspend fun lookupImdbId(tmdbId: String, mediaType: String): String? {
        val num = tmdbId.trim().takeIf { it.all(Char::isDigit) } ?: return null
        val mt  = normaliseMediaType(mediaType)
        val cacheKey = "$num:$mt"
        imdbIdCache[cacheKey]?.let { return it }
        return withContext(Dispatchers.IO) {
            val apiKey = com.streamcloud.app.BuildConfig.TMDB_API_KEY
            if (apiKey.isBlank()) return@withContext null
            val endpoint = if (mt == "tv") "tv" else "movie"
            val url = "https://api.themoviedb.org/3/$endpoint/$num/external_ids?api_key=$apiKey"
            runCatching {
                val text = httpGet(url)
                val root = Net.json.parseToJsonElement(text) as?
                    kotlinx.serialization.json.JsonObject ?: return@runCatching null
                val imdb = (root["imdb_id"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content
                    ?.trim()
                    ?.takeIf { it.startsWith("tt") && it.length >= 7 }
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

    private fun nuvioMediaType(mediaType: String): String =
        when (normaliseMediaType(mediaType)) {
            "tv" -> "tv"
            else -> "movie"
        }

    suspend fun resolveSingle(
        provider: InstalledNuvioProvider,
        tmdbId: String,
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        imdbId: String? = null,
    ): List<NuvioStream> = withContext(Dispatchers.IO) {
        val js = runCatching { File(provider.filePath).readText() }.getOrNull()
            ?: return@withContext emptyList()
        val resolvedTmdb = resolvedTmdbIdOrWarn(tmdbId, mediaType, season, episode)
            ?: return@withContext emptyList()
        val normalizedContentId = normaliseNuvioContentId(resolvedTmdb, season, episode)
            ?: resolvedTmdb
        val effectiveImdbId = imdbId ?: lookupImdbId(resolvedTmdb, mediaType)
        NuvioRuntime.runProvider(
            scriptText = js,
            tmdbId = normalizedContentId,
            imdbId = effectiveImdbId,
            mediaType = nuvioMediaType(mediaType),
            season = season,
            episode = episode,
            scriptKey = provider.id,
            context = context,
            filePath = provider.filePath,
            proxyBaseUrl = backendProxyUrl(),
        )
    }

    suspend fun testSingleProvider(
        provider: InstalledNuvioProvider,
        tmdbId: String = "155",
        mediaType: String = "movie",
        imdbId: String? = "tt0468569",
    ): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val js = runCatching { File(provider.filePath).readText() }.getOrElse {
            return@withContext 0 to "Could not read provider file"
        }
        val resolvedTmdb = resolvedTmdbIdOrWarn(tmdbId, mediaType, null, null)
            ?: return@withContext 0 to "Invalid TMDB ID after sanitization"
        val normalizedContentId = normaliseNuvioContentId(resolvedTmdb)
            ?: resolvedTmdb
        try {
            val streams = NuvioRuntime.runProvider(
                scriptText = js,
                tmdbId = normalizedContentId,
                imdbId = imdbId,
                mediaType = nuvioMediaType(mediaType),
                season = null,
                episode = null,
                scriptKey = "test__${provider.id}",
                context = context,
                filePath = provider.filePath,
                proxyBaseUrl = backendProxyUrl(),
            )
            streams.size to null
        } catch (e: Exception) {
            0 to (e.message ?: "Unknown error")
        }
    }

    private suspend fun resolvedTmdbIdOrWarn(
        raw: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null,
    ): String? {
        val resolved = resolveTmdbId(raw, mediaType) ?: raw
        return sanitizeNuvioTmdbId(resolved, season, episode) ?: run {
            val shown = raw.trim().ifBlank { "<blank>" }
            Log.w(TAG, "Skipping Nuvio lookup for invalid TMDB id \"$shown\" (mediaType=${normaliseMediaType(mediaType)})")
            null
        }
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
        // Prefer explicit URL fields over the filename fallback.  Only use `filename` as a
        // last resort and only when it looks like a relative path (contains a '/') or an
        // absolute URL — a plain basename like "provider.js" is relative to the manifest dir.
        val raw = e.downloadUrl ?: e.downloadUrlSnake ?: e.url
            ?: e.filename?.takeIf { it.isNotBlank() }
            ?: return null
        if (raw.startsWith("http")) return raw

        val base = manifestUrl.substringBeforeLast('/')
        return "$base/${raw.trimStart('/')}"
    }

    private suspend fun httpGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", NUVIO_DEFAULT_USER_AGENT)
            .header("Accept", BrowserHeaders.ACCEPT_JSON)
            .header("Accept-Language", BrowserHeaders.ACCEPT_LANGUAGE)
            .build()
        var code: Int
        var body: String
        var hdrs: Map<String, List<String>>
        http.newCall(req).execute().use { resp ->
            code = resp.code
            body = resp.body?.string().orEmpty()
            hdrs = resp.headers.toMultimap()
        }
        if (CloudflareKiller.isCfChallenge(code, hdrs, body)) {
            val bypassed = CloudflareKiller.bypass(context, url, NUVIO_DEFAULT_USER_AGENT, BrowserCookieJar)
            if (bypassed) {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code} from $url (after CF bypass)")
                    return r.body?.string().orEmpty()
                }
            }
            error("Cloudflare challenge unresolved for $url")
        }
        if (code !in 200..299) error("HTTP $code from $url")
        return body
    }
}
