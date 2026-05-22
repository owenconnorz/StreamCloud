package com.streamcloud.app.data.plugins

import android.content.Context
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.installPrefs
import com.lagradost.cloudstream3.plugins.Plugin
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

object PluginRuntime {

    private data class LoadedPlugin(val plugin: Plugin, val apis: List<MainAPI>)
    private val cache = mutableMapOf<String, LoadedPlugin>()
    private val lastErrors = mutableMapOf<String, String>()

    fun lastErrorFor(filePath: String): String? = lastErrors[filePath]

    suspend fun load(context: Context, filePath: String): List<MainAPI> = withContext(Dispatchers.IO) {
        cache[filePath]?.let { return@withContext it.apis }

        installPrefs(context)
        try {
            val src = File(filePath)
            if (!src.exists()) error("Plugin file missing: $filePath")


            val readOnlyDir = File(context.codeCacheDir, "plugins-ro").apply { mkdirs() }
            val readOnlyFile = File(readOnlyDir, src.name)
            if (!readOnlyFile.exists() ||
                readOnlyFile.length() != src.length() ||
                readOnlyFile.lastModified() < src.lastModified()
            ) {
                src.copyTo(readOnlyFile, overwrite = true)
            }


            @Suppress("ResultOfMethodCallIgnored")
            readOnlyFile.setReadOnly()

            val optimizedDir = File(context.codeCacheDir, "plugins-opt").apply { mkdirs() }
            val loader = DexClassLoader(
                readOnlyFile.absolutePath,
                optimizedDir.absolutePath,
                null,
                context.classLoader,
            )
            val pluginClassName = readPluginClassName(readOnlyFile)
                ?: scanDexForPluginClass(readOnlyFile, optimizedDir, context.classLoader)
                ?: error("Could not find plugin class in `$filePath` (no `manifest.json`, " +
                    "no `Plugin-Class` in MANIFEST.MF, and no `Plugin` subclass found in dex).")
            val klass = loader.loadClass(pluginClassName)
            val instance = klass.getDeclaredConstructor().newInstance() as? Plugin
                ?: error("Class `$pluginClassName` is not a subclass of `Plugin`")
            // Set filename before any lifecycle calls so plugins can use it for
            // cache keys, preferences namespacing, etc.
            instance.filename = filePath
            instance.beforeLoad()
            instance.load(context)
            instance.afterLoad()
            val loaded = LoadedPlugin(instance, instance.apis.toList())
            cache[filePath] = loaded
            lastErrors.remove(filePath)
            loaded.apis
        } catch (e: Throwable) {
            lastErrors[filePath] = "${e::class.simpleName}: ${e.message}"
            emptyList()
        }
    }

    @Serializable
    private data class PluginManifest(
        val pluginClassName: String? = null,
        val name: String? = null,
        val version: Int? = null,
        val requiresResources: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    private fun readPluginClassName(file: File): String? {

        try {
            ZipFile(file).use { zf ->
                val entry = zf.getEntry("manifest.json")
                if (entry != null) {
                    val body = zf.getInputStream(entry).bufferedReader().use { it.readText() }
                    val mf = runCatching { json.decodeFromString(PluginManifest.serializer(), body) }.getOrNull()
                    val name = mf?.pluginClassName?.takeIf { it.isNotBlank() }
                    if (name != null) return name
                }
            }
        } catch (_: Exception) {  }


        return try {
            ZipFile(file).use { zf ->
                val entry = zf.getEntry("META-INF/MANIFEST.MF") ?: return null
                zf.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith("Plugin-Class:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                }
            }
        } catch (_: Exception) { null }
    }


    @Suppress("DEPRECATION")
    private fun scanDexForPluginClass(
        readOnlyFile: File,
        optimizedDir: File,
        parent: ClassLoader,
    ): String? {
        return try {
            val loader = DexClassLoader(
                readOnlyFile.absolutePath,
                optimizedDir.absolutePath,
                null,
                parent,
            )
            val dexFile = DexFile.loadDex(
                readOnlyFile.absolutePath,
                File(optimizedDir, readOnlyFile.name + ".odex").absolutePath,
                0,
            )
            val pluginBase = Plugin::class.java
            dexFile.entries().toList().firstOrNull { className ->

                if (className.startsWith("com.lagradost.cloudstream3.") &&
                    !className.contains("plugin", ignoreCase = true)) return@firstOrNull false
                runCatching {
                    val c = loader.loadClass(className)
                    pluginBase.isAssignableFrom(c) && !java.lang.reflect.Modifier.isAbstract(c.modifiers)
                }.getOrDefault(false)
            }
        } catch (_: Throwable) { null }
    }

    suspend fun search(context: Context, filePath: String, query: String): List<SearchResponse> {
        val apis = load(context, filePath)
        return apis.flatMap { api ->
            try { api.search(query).orEmpty() } catch (_: Throwable) { emptyList() }
        }
    }

    suspend fun home(context: Context, filePath: String): List<Pair<String, List<SearchResponse>>> {
        val apis = load(context, filePath)
        if (apis.isEmpty()) {


            if (lastErrors[filePath] == null) {
                lastErrors[filePath] = "Plugin loaded but registered 0 MainAPIs."
            }
            return emptyList()
        }
        val out = mutableListOf<Pair<String, List<SearchResponse>>>()
        val perApiErrors = mutableListOf<String>()
        apis.forEach { api ->




            val requests = if (api.mainPage.isNotEmpty()) api.mainPage
            else listOf(MainPageRequest(name = api.name, data = "", horizontalImages = false))
            var apiSectionsAdded = 0
            requests.forEach { req: MainPageRequest ->
                try {
                    val page = withContext(Dispatchers.IO) { api.getMainPage(1, req) }
                    page?.items?.forEach { hpl ->
                        if (hpl.list.isNotEmpty()) {
                            out += hpl.name to hpl.list
                            apiSectionsAdded++
                        }
                    }
                } catch (e: Throwable) {
                    perApiErrors += "${api.name} · ${req.name}: ${e::class.simpleName}: ${e.message}"
                }
            }
            if (apiSectionsAdded == 0 && perApiErrors.isEmpty()) {
                perApiErrors += "${api.name}: getMainPage returned no items."
            }
        }
        if (out.isEmpty()) {
            lastErrors[filePath] = perApiErrors.joinToString("\n").ifBlank {
                "Plugin loaded but no sections were returned."
            }
        } else {
            lastErrors.remove(filePath)
        }
        return out
    }

    suspend fun loadDetail(context: Context, filePath: String, url: String): LoadResponse? =
        withContext(Dispatchers.IO) {
            val apis = load(context, filePath)
            var lastEx: Throwable? = null
            val result = apis.firstNotNullOfOrNull { api ->
                try {
                    api.load(url)
                } catch (e: Throwable) {
                    lastEx = e
                    null
                }
            }
            if (result == null) {
                val errMsg = lastEx?.let { "${it::class.simpleName}: ${it.message}" }
                    ?: "Plugin returned no detail page for this URL."
                lastErrors[filePath] = errMsg
            } else {
                lastErrors.remove(filePath)
            }
            result
        }


    suspend fun loadLinks(
        context: Context,
        filePath: String,
        data: String,
        isCasting: Boolean = false,
    ): Pair<List<ExtractorLink>, List<SubtitleFile>> = withContext(Dispatchers.IO) {
        val apis = load(context, filePath)
        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())
        for (api in apis) {
            try {
                api.loadLinks(
                    data = data,
                    isCasting = isCasting,
                    subtitleCallback = { sub -> subs.add(sub) },
                    callback = { link -> links.add(link) },
                )
            } catch (_: Throwable) {

            }
        }
        links.toList() to subs.toList()
    }


    suspend fun firstApi(context: Context, filePath: String): MainAPI? = load(context, filePath).firstOrNull()

    fun clear(filePath: String) {
        cache.remove(filePath)
        lastErrors.remove(filePath)
    }
}
