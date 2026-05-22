package com.streamcloud.app.data.plugins

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamcloud.app.data.network.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.pluginStore by preferencesDataStore("streamcloud_plugins")
private val KEY_REPOS = stringPreferencesKey("repos_json")
private val KEY_INSTALLED = stringPreferencesKey("installed_json")

class PluginRepository(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val pluginsDir: File = File(context.filesDir, "plugins").apply { mkdirs() }

    val repos: Flow<List<CloudStreamRepo>> = context.pluginStore.data.map { prefs ->
        prefs[KEY_REPOS]?.let {
            runCatching {
                Net.json.decodeFromString(ListSerializer(CloudStreamRepo.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    val installed: Flow<List<InstalledPlugin>> = context.pluginStore.data.map { prefs ->
        prefs[KEY_INSTALLED]?.let {
            runCatching {
                Net.json.decodeFromString(ListSerializer(InstalledPlugin.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun addRepo(name: String, url: String) {
        val current = repos.first()
        val cleaned = url.trim().trimEnd('/')
        val newRepo = CloudStreamRepo(id = UUID.randomUUID().toString(), name = name, url = cleaned)
        saveRepos(current + newRepo)
    }

    suspend fun removeRepo(id: String) {
        val current = repos.first().filterNot { it.id == id }
        saveRepos(current)
    }

    private suspend fun saveRepos(list: List<CloudStreamRepo>) {
        val text = Net.json.encodeToString(ListSerializer(CloudStreamRepo.serializer()), list)
        context.pluginStore.edit { it[KEY_REPOS] = text }
    }

    private suspend fun saveInstalled(list: List<InstalledPlugin>) {
        val text = Net.json.encodeToString(ListSerializer(InstalledPlugin.serializer()), list)
        context.pluginStore.edit { it[KEY_INSTALLED] = text }
    }

    // ── Fetch plugin list from a repo URL ─────────────────────────────────────
    //
    // Some repos use a "pluginLists" key whose value is a list of *URL strings*
    // pointing to separate JSON arrays (e.g. Phisher's cloudstream-extensions repo).
    // We detect that pattern and fetch each sub-URL individually.
    //
    // Other repos (e.g. CS3-standard) put plugins directly in "plugins",
    // "extensions", or as a flat JSON array.

    suspend fun fetchPluginList(repoUrl: String): List<CloudStreamPlugin> = withContext(Dispatchers.IO) {
        val base = repoUrl.trim().trimEnd('/')

        // Candidates in priority order
        val candidates = buildList {
            add(base)
            if (!base.endsWith(".json")) {
                add("$base/plugins.json")
                add("$base/repo.json")
            }
        }

        val errors = mutableListOf<String>()

        for (url in candidates) {
            try {
                // 1. Try to parse the URL directly as a plugin array / repo manifest
                val (rawBody, directPlugins) = fetchAndParse(url)
                if (directPlugins.isNotEmpty()) return@withContext directPlugins

                // 2. Check if the manifest has pluginLists containing URL strings
                val subListPlugins = resolvePluginLists(url, rawBody)
                if (subListPlugins.isNotEmpty()) return@withContext subListPlugins

                errors += "$url → 200 OK but 0 plugins parsed"
            } catch (e: Exception) {
                errors += "$url → ${e.message ?: e::class.simpleName}"
            }
        }

        error("No plugins found. Tried:\n" + errors.joinToString("\n"))
    }

    /**
     * Fetch [url], return the raw body AND the plugins parsed from it.
     * Returns a pair so callers can inspect the raw JSON for pluginLists URLs.
     */
    private fun fetchAndParse(url: String): Pair<String, List<CloudStreamPlugin>> {
        val body = fetchRaw(url)
        val element = runCatching { Net.json.parseToJsonElement(body) }.getOrNull()
            ?: error("Response is not JSON")
        return body to parsePluginsFromAny(element)
    }

    /**
     * If the JSON at [repoUrl] has a "pluginLists" key whose values are URL strings,
     * fetch each sub-URL and collect all plugins from them.
     */
    private fun resolvePluginLists(repoUrl: String, rawBody: String): List<CloudStreamPlugin> {
        val element = runCatching { Net.json.parseToJsonElement(rawBody) }.getOrNull() ?: return emptyList()
        val obj = element as? JsonObject ?: return emptyList()

        // Collect URL strings from pluginLists (and also "lists", "repos" as aliases)
        val subUrls = mutableListOf<String>()
        for (key in listOf("pluginLists", "lists", "repos")) {
            val arr = obj[key] as? JsonArray ?: continue
            arr.forEach { el ->
                if (el is JsonPrimitive && el.isString) {
                    val s = el.content.trim()
                    if (s.startsWith("http")) subUrls += s
                }
            }
        }

        if (subUrls.isEmpty()) return emptyList()

        val out = mutableListOf<CloudStreamPlugin>()
        for (subUrl in subUrls) {
            runCatching {
                val subBody = fetchRaw(subUrl)
                val subEl = Net.json.parseToJsonElement(subBody)
                out += parsePluginsFromAny(subEl)
            }
        }
        return out.distinctBy { (it.internalName ?: it.name) + "|" + it.downloadUrl }
    }

    private fun fetchRaw(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "StreamCloud/1.0")
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${resp.message}")
            return resp.body?.string().orEmpty()
        }
    }

    // ── Plugin JSON parser ────────────────────────────────────────────────────
    //
    // Handles the following repo formats:
    //   • Flat array:    [ { name, url|jarUrl, ... }, ... ]
    //   • Wrapped array: { "plugins": [...] }  or  { "extensions": [...] }
    //   • Nested:        { "pluginLists": [ { "url": "...", "plugins": [...] } ] }
    //
    // Field name aliases for download URL (CloudStreamPlugin.downloadUrl computed prop):
    //   url, jarUrl  — already handled by @SerialName in CloudStreamPlugin

    private fun parsePluginsFromAny(element: kotlinx.serialization.json.JsonElement): List<CloudStreamPlugin> {
        val out = mutableListOf<CloudStreamPlugin>()

        fun looksLikePlugin(o: JsonObject): Boolean {
            val hasName = o.containsKey("name")
            // Accept any of the common URL field names
            val hasUrl = o.containsKey("url") || o.containsKey("jarUrl") ||
                o.containsKey("download") || o.containsKey("downloadUrl")
            return hasName && hasUrl
        }

        fun visit(el: kotlinx.serialization.json.JsonElement) {
            when (el) {
                is JsonArray -> {
                    if (el.isNotEmpty() && el.all { it is JsonObject && looksLikePlugin(it) }) {
                        // Flat plugin array — decode each object
                        el.forEach { jo ->
                            runCatching {
                                Net.json.decodeFromJsonElement(CloudStreamPlugin.serializer(), jo)
                            }.getOrNull()?.takeIf { it.downloadUrl.isNotBlank() }?.let(out::add)
                        }
                    } else {
                        // Mixed array — recurse into each element
                        el.forEach { visit(it) }
                    }
                }
                is JsonObject -> {
                    // If this object itself looks like a plugin, try to decode it
                    if (looksLikePlugin(el)) {
                        runCatching {
                            Net.json.decodeFromJsonElement(CloudStreamPlugin.serializer(), el)
                        }.getOrNull()?.takeIf { it.downloadUrl.isNotBlank() }?.let(out::add)
                        return
                    }
                    // Look for well-known container keys first
                    var found = false
                    for (key in listOf("plugins", "extensions", "pluginLists", "items", "data")) {
                        val child = el[key] as? JsonArray ?: continue
                        val prevSize = out.size
                        visit(child)
                        if (out.size > prevSize) found = true
                    }
                    // Fall back: recurse all values
                    if (!found) {
                        el.values.forEach { visit(it) }
                    }
                }
                else -> { /* JsonPrimitive — skip */ }
            }
        }

        visit(element)
        return out.distinctBy { (it.internalName ?: it.name) + "|" + it.downloadUrl }
    }

    // ── Install / uninstall ───────────────────────────────────────────────────

    suspend fun installPlugin(repo: CloudStreamRepo, plugin: CloudStreamPlugin): InstalledPlugin =
        withContext(Dispatchers.IO) {
            if (plugin.downloadUrl.isBlank()) error("Plugin has no download URL")
            val safeName = (plugin.internalName ?: plugin.name)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "plugin_${System.currentTimeMillis()}" }
            val outFile = File(pluginsDir, "$safeName.cs3")
            val req = Request.Builder().url(plugin.downloadUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed HTTP ${resp.code}")
                val body = resp.body ?: error("Empty body")
                outFile.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            val installed = InstalledPlugin(
                name = plugin.name,
                internalName = plugin.internalName ?: safeName,
                version = plugin.version,
                filePath = outFile.absolutePath,
                sourceRepoId = repo.id,
                sourceUrl = plugin.downloadUrl,
                installedAt = System.currentTimeMillis(),
                iconUrl = plugin.iconUrl,
                description = plugin.description,
                authors = plugin.authors,
                language = plugin.language,
            )
            val list = this@PluginRepository.installed.first()
                .filterNot { it.sourceRepoId == repo.id && it.internalName == installed.internalName } + installed
            saveInstalled(list)
            installed
        }

    suspend fun uninstallPlugin(internalName: String, sourceRepoId: String? = null) = withContext(Dispatchers.IO) {
        val current = installed.first()
        val matches = current.filter {
            it.internalName == internalName && (sourceRepoId == null || it.sourceRepoId == sourceRepoId)
        }
        matches.forEach { File(it.filePath).delete() }
        val keep = current - matches.toSet()
        saveInstalled(keep)
    }

    suspend fun pluginsCacheSize(): Long = withContext(Dispatchers.IO) {
        pluginsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun clearAppCache(): Long = withContext(Dispatchers.IO) {
        val before = (context.cacheDir.walkBottomUp().sumOf { it.length() })
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        before
    }
}
