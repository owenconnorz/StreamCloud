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


    /**
     * Scan the plugin DEX for a concrete subclass of [Plugin].
     *
     * DexFile.loadDex() was deprecated in API 26 and REMOVED in API 31 (Android 12) —
     * calling it on modern devices throws RuntimeException: "No DexFile".
     * We parse class names directly from the DEX binary instead, which works on
     * all API levels without reflection into internal JVM structures.
     */
    private fun scanDexForPluginClass(
        readOnlyFile: File,
        optimizedDir: File,
        parent: ClassLoader,
    ): String? {
        val classNames = extractClassNamesFromDex(readOnlyFile) ?: return null
        val loader = DexClassLoader(
            readOnlyFile.absolutePath,
            optimizedDir.absolutePath,
            null,
            parent,
        )
        val pluginBase = Plugin::class.java
        return classNames.firstOrNull { className ->
            // Skip framework classes that are definitely not Plugin subclasses
            if (className.startsWith("com.lagradost.cloudstream3.") &&
                !className.contains("plugin", ignoreCase = true) &&
                !className.contains("Plugin", ignoreCase = false)) return@firstOrNull false
            if (className.startsWith("kotlin.") || className.startsWith("kotlinx.") ||
                className.startsWith("androidx.") || className.startsWith("android.")) return@firstOrNull false
            runCatching {
                val c = loader.loadClass(className)
                pluginBase.isAssignableFrom(c) && !java.lang.reflect.Modifier.isAbstract(c.modifiers)
            }.getOrDefault(false)
        }
    }

    /**
     * Extract all class names from the classes.dex inside a plugin .cs3 file by
     * parsing the DEX binary format directly — no deprecated APIs needed.
     *
     * DEX format reference: https://source.android.com/docs/core/runtime/dex-format
     *   Offset 56: string_ids_size (u4)
     *   Offset 60: string_ids_off  (u4)
     *   Offset 64: type_ids_size   (u4)
     *   Offset 68: type_ids_off    (u4)
     */
    private fun extractClassNamesFromDex(file: File): List<String>? = runCatching {
        val dexBytes: ByteArray = java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.getEntry("classes.dex") ?: return@runCatching null
            zip.getInputStream(entry).readBytes()
        } ?: return null

        if (dexBytes.size < 112) return null
        val bb = java.nio.ByteBuffer.wrap(dexBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        bb.position(56)
        val stringIdsSize = bb.int
        val stringIdsOff  = bb.int
        val typeIdsSize   = bb.int
        val typeIdsOff    = bb.int

        if (typeIdsSize <= 0 || typeIdsOff <= 0 || stringIdsOff <= 0) return null

        // Read type_id_list: each entry is a 4-byte index into string_ids
        val typeStringIndices = IntArray(typeIdsSize)
        bb.position(typeIdsOff)
        for (i in 0 until typeIdsSize) typeStringIndices[i] = bb.int

        // Read each string that corresponds to a class type (starts with 'L', ends with ';')
        typeStringIndices.mapNotNull { strIdx ->
            runCatching {
                bb.position(stringIdsOff + strIdx * 4)
                val strDataOff = bb.int
                bb.position(strDataOff)
                // ULEB128-encoded string length in UTF-16 code units, followed by MUTF-8 bytes
                var b = bb.get().toInt() and 0xFF
                var charLen = b and 0x7F
                var shift = 7
                while (b and 0x80 != 0) {
                    b = bb.get().toInt() and 0xFF
                    charLen = charLen or ((b and 0x7F) shl shift)
                    shift += 7
                }
                val bytes = ByteArray(charLen)
                bb.get(bytes)
                val raw = String(bytes, Charsets.UTF_8)
                // DEX class descriptors: Lcom/example/Foo; → com.example.Foo
                if (raw.startsWith("L") && raw.endsWith(";"))
                    raw.substring(1, raw.length - 1).replace('/', '.')
                else null
            }.getOrNull()
        }
    }.getOrNull()

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
