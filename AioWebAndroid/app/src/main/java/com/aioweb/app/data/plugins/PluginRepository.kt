package com.aioweb.app.data.plugins

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aioweb.app.data.network.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.pluginStore by preferencesDataStore("aioweb_plugins")
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

    /**
     * Fetch the plugin list. Tries multiple URL forms because CloudStream forks vary:
     *   1. The given URL as-is
     *   2. URL + /plugins.json (most common)
     *   3. URL + /repo.json (older form)
     */
    suspend fun fetchPluginList(repoUrl: String): List<CloudStreamPlugin> = withContext(Dispatchers.IO) {
        val candidates = buildList {
            val base = repoUrl.trim().trimEnd('/')
            add(base)
            if (!base.endsWith(".json")) {
                add("$base/plugins.json")
                add("$base/repo.json")
            }
        }
        val errors = mutableListOf<String>()
        for (url in candidates) {
            try {
                val plugins = fetchOne(url)
                if (plugins.isNotEmpty()) return@withContext plugins
                errors += "$url → 200 OK but 0 plugins parsed"
            } catch (e: Exception) {
                errors += "$url → ${e.message ?: e::class.simpleName}"
            }
        }
        // Surface every URL we tried so users can spot typos / wrong branches fast.
        error("No plugins found. Tried:\n" + errors.joinToString("\n"))
    }

    private fun fetchOne(url: String): List<CloudStreamPlugin> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AioWebAndroid/1.0")
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${resp.message}")
            val body = resp.body?.string().orEmpty()
            val element = runCatching { Net.json.parseToJsonElement(body) }.getOrNull()
                ?: error("Response is not JSON")
            return parsePluginsFromAny(element)
        }
    }

    /**
     * Walks any JSON shape and pulls out objects that look like CloudStream plugins.
     * Real-world repo shapes seen in the wild:
     *   • Top-level array of plugin objects (recloudstream)
     *   • Object with `pluginLists` key (older recloudstream)
     *   • Object with `plugins` key (some forks)
     *   • Object with `extensions` key (a few forks)
     *   • Object whose values are themselves plugin arrays per category
     */
    private fun parsePluginsFromAny(element: kotlinx.serialization.json.JsonElement): List<CloudStreamPlugin> {
        val out = mutableListOf<CloudStreamPlugin>()
        fun looksLikePlugin(o: JsonObject): Boolean {
            val hasName = o.containsKey("name")
            val hasUrl = o.containsKey("url") || o.containsKey("jarUrl") || o.containsKey("download")
            return hasName && hasUrl
        }
        fun visit(el: kotlinx.serialization.json.JsonElement) {
            when (el) {
                is JsonArray -> {
                    if (el.all { it is JsonObject && looksLikePlugin(it) }) {
                        el.forEach { jo ->
                            runCatching {
                                Net.json.decodeFromJsonElement(CloudStreamPlugin.serializer(), jo)
                            }.getOrNull()?.let(out::add)
                        }
                    } else {
                        el.forEach { visit(it) }
                    }
                }
                is JsonObject -> {
                    // A few repos put the array under a known key — check first.
                    listOf("pluginLists", "plugins", "extensions").forEach { k ->
                        (el[k] as? JsonArray)?.let { visit(it) }
                    }
                    if (out.isEmpty()) {
                        // Fallback: walk every value.
                        el.values.forEach { visit(it) }
                    }
                }
                else -> { /* primitive */ }
            }
        }
        visit(element)
        return out.distinctBy { (it.internalName ?: it.name) + "|" + it.downloadUrl }
    }

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
            // Replace any prior install of the SAME plugin from the SAME repo only.
            // (Plugins from different repos can legitimately share `internalName` —
            // e.g., two forks of the same scraper — and must coexist.)
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

    /** Total bytes used by installed plugin files. */
    suspend fun pluginsCacheSize(): Long = withContext(Dispatchers.IO) {
        pluginsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Internal cache + plugin files. */
    suspend fun clearAppCache(): Long = withContext(Dispatchers.IO) {
        val before = (context.cacheDir.walkBottomUp().sumOf { it.length() })
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        before
    }
}
