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
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.schemaStripRegex
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

object PluginRuntime {

    private const val DEFAULT_LOAD_LINKS_TIMEOUT_MS = 30_000L
    private const val TOTAL_LOAD_LINKS_TIMEOUT_MS = 90_000L

    private data class LoadedPlugin(
        val plugin: Plugin,
        val apis: List<MainAPI>,
        val extractors: List<ExtractorApi>,
    )
    private val cache = mutableMapOf<String, LoadedPlugin>()
    private val lastErrors = ConcurrentHashMap<String, String>()
    private val lastLogs = ConcurrentHashMap<String, String>()
    private val fetchCountByPlugin = ConcurrentHashMap<String, AtomicInteger>()

    fun lastErrorFor(filePath: String): String? = lastErrors[filePath]
    fun lastLogFor(filePath: String): String? = lastLogs[filePath]
    fun fetchCountFor(filePath: String): Int = fetchCountByPlugin[filePath]?.get() ?: 0

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

            val optimizedDir = context.getDir("plugins-opt", android.content.Context.MODE_PRIVATE)
            // Primary parent: the classloader that loaded our Plugin class.
            // Fallback: the app's full PathClassLoader (helps with NoClassDefFoundError on some
            // plugins that reference classes not visible through the narrower Plugin classloader).
            val primaryParent = Plugin::class.java.classLoader ?: context.classLoader
            val fallbackParent = context.classLoader
            val loader = DexClassLoader(
                readOnlyFile.absolutePath,
                optimizedDir.absolutePath,
                null,
                primaryParent,
            )
            // Read manifest from the original file first (avoids codeCacheDir SELinux issues)
            val pluginClassName = readPluginClassName(src)
                ?: readPluginClassName(readOnlyFile)
                ?: scanDexForPluginClass(readOnlyFile, optimizedDir, primaryParent)
                ?: error("Could not find plugin class in `$filePath` (no `manifest.json`, " +
                    "no `Plugin-Class` in MANIFEST.MF, and no `Plugin` subclass found in dex).")

            // Load the class. If it fails with a linkage/class-not-found error, retry with the
            // fallback classloader so plugins that reference additional app classes can still load.
            val klass = run {
                val primary = runCatching { loader.loadClass(pluginClassName) }
                if (primary.isSuccess) {
                    primary.getOrThrow()
                } else {
                    val fallbackLoader = DexClassLoader(
                        readOnlyFile.absolutePath,
                        optimizedDir.absolutePath,
                        null,
                        fallbackParent,
                    )
                    runCatching { fallbackLoader.loadClass(pluginClassName) }.getOrElse { e ->
                        error("Found plugin class `$pluginClassName` but failed to load it " +
                            "(tried primary + fallback classloaders): " +
                            "${e::class.simpleName}: ${e.message}")
                    }
                }
            }

            // Instantiate and normalise. Some plugins extend Plugin (standard), others extend
            // MainAPI directly without a Plugin wrapper (bare-provider format). Wrap the latter
            // automatically so both formats work.
            val rawInstance = runCatching { klass.getDeclaredConstructor().newInstance() }
                .getOrElse { e ->
                    error("Failed to instantiate `$pluginClassName`: " +
                        "${e::class.simpleName}: ${e.message}")
                }
            val instance: Plugin = when (rawInstance) {
                is Plugin -> rawInstance
                is MainAPI -> object : Plugin() {
                    override fun load(ctx: android.content.Context) {
                        registerMainAPI(rawInstance)
                    }
                }
                else -> {
                    // instanceof failed — classloader mismatch: the plugin's Plugin/MainAPI was
                    // resolved from its own dex rather than from our app's class hierarchy.
                    // Strategy 1: walk the superclass chain by name and reload with fallbackParent.
                    val hierarchy = generateSequence(rawInstance::class.java as Class<*>?) { it.superclass }
                        .map { it.name }
                        .toList()

                    // Helper: reload the class under a given parent and return a wrapped Plugin.
                    fun reloadAsPlugin(parent: ClassLoader): Plugin? {
                        val reloaded = runCatching {
                            DexClassLoader(readOnlyFile.absolutePath, optimizedDir.absolutePath, null, parent)
                                .loadClass(pluginClassName).getDeclaredConstructor().newInstance()
                        }.getOrNull() ?: return null
                        return when {
                            reloaded is Plugin -> reloaded
                            reloaded is MainAPI -> object : Plugin() {
                                override fun load(ctx: android.content.Context) { registerMainAPI(reloaded) }
                            }
                            else -> null
                        }
                    }

                    // Strategy 2: the class's OWN classloader may hold a copy of Plugin —
                    // check membership there, then reload via our fallbackParent so the cast works.
                    fun resolveViaOwnClassloader(): Plugin? {
                        val ownCL = rawInstance::class.java.classLoader ?: return null
                        val pluginInOwnCL = runCatching {
                            ownCL.loadClass("com.lagradost.cloudstream3.plugins.Plugin")
                        }.getOrNull()
                        val mainApiInOwnCL = runCatching {
                            ownCL.loadClass("com.lagradost.cloudstream3.MainAPI")
                        }.getOrNull()
                        return when {
                            pluginInOwnCL?.isInstance(rawInstance) == true -> reloadAsPlugin(fallbackParent)
                            mainApiInOwnCL?.isInstance(rawInstance) == true -> reloadAsPlugin(fallbackParent)
                            else -> null
                        }
                    }

                    // Strategy 3: try every plausible parent classloader in turn.
                    // On some devices / Android versions the Plugin::class.java.classLoader
                    // is subtly different from context.classLoader so the first loader's
                    // instanceof check fails even though the class IS a Plugin subclass.
                    fun tryAllParents(): Plugin? {
                        val parents = listOf(
                            Thread.currentThread().contextClassLoader,
                            context.classLoader,
                            ClassLoader.getSystemClassLoader(),
                        ).filterNotNull().distinct()
                        for (p in parents) {
                            val inst = runCatching {
                                DexClassLoader(readOnlyFile.absolutePath, optimizedDir.absolutePath, null, p)
                                    .loadClass(pluginClassName).getDeclaredConstructor().newInstance()
                            }.getOrNull() ?: continue
                            when {
                                inst is Plugin -> return inst
                                inst is MainAPI -> return object : Plugin() {
                                    override fun load(ctx: android.content.Context) { registerMainAPI(inst) }
                                }
                            }
                        }
                        return null
                    }

                    // Strategy 4 (last resort): reflection bridge.
                    // If EVERY instanceof check fails regardless of classloader, duck-type the
                    // class: find a "load" method by walking declaredMethods on each class in the
                    // hierarchy (more robust than getMethod which requires exact parameter types to
                    // match across classloaders), call it, then harvest the `apis` list via field
                    // reflection.  Any api that IS our MainAPI gets registered; others are ignored.
                    fun reflectionBridge(): Plugin? {
                        val cls = rawInstance::class.java
                        val hierarchy = generateSequence(cls as Class<*>?) { it.superclass }

                        // Find any public method named "load" that takes a single Context-like param
                        val loadMethod = hierarchy
                            .flatMap { c -> runCatching { c.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
                            .firstOrNull { m ->
                                m.name == "load" && m.parameterCount == 1 &&
                                    (m.parameterTypes[0].name == "android.content.Context" ||
                                        m.parameterTypes[0].name.endsWith("Context"))
                            }
                        // Also accept a no-arg load() if nothing else found
                        val loadMethodNoArg = if (loadMethod == null)
                            generateSequence(cls as Class<*>?) { it.superclass }
                                .flatMap { c -> runCatching { c.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
                                .firstOrNull { m -> m.name == "load" && m.parameterCount == 0 }
                        else null

                        // Must find SOME load method; otherwise it's truly not a Plugin-like class
                        if (loadMethod == null && loadMethodNoArg == null) return null

                        return object : Plugin() {
                            override fun load(ctx: android.content.Context) {
                                loadMethod?.let { m ->
                                    m.isAccessible = true
                                    runCatching { m.invoke(rawInstance, ctx) }
                                }
                                loadMethodNoArg?.let { m ->
                                    m.isAccessible = true
                                    runCatching { m.invoke(rawInstance) }
                                }
                                // Harvest apis from the raw instance's field via reflection
                                val apisField = generateSequence(cls as Class<*>?) { it.superclass }
                                    .firstNotNullOfOrNull { c ->
                                        runCatching { c.getDeclaredField("apis") }.getOrNull()
                                    }
                                if (apisField != null) {
                                    apisField.isAccessible = true
                                    @Suppress("UNCHECKED_CAST")
                                    val rawApis = runCatching {
                                        apisField.get(rawInstance) as? Iterable<*>
                                    }.getOrNull()
                                    rawApis?.forEach { api ->
                                        if (api is MainAPI) registerMainAPI(api)
                                    }
                                }
                            }
                        }
                    }

                    when {
                        "com.lagradost.cloudstream3.plugins.Plugin" in hierarchy ->
                            reloadAsPlugin(fallbackParent)
                                ?: tryAllParents()
                                ?: error("Class `$pluginClassName` detected as Plugin by name but cast failed")

                        "com.lagradost.cloudstream3.MainAPI" in hierarchy ->
                            reloadAsPlugin(fallbackParent)
                                ?: tryAllParents()
                                ?: error("Class `$pluginClassName` detected as MainAPI by name but cast failed")

                        else -> resolveViaOwnClassloader()
                            ?: tryAllParents()
                            ?: reflectionBridge()
                            ?: error("Class `$pluginClassName` is not a subclass of `Plugin` or `MainAPI`")
                    }
                }
            }
            instance.__initContext(context)
            instance.beforeLoad()
            instance.load(context)
            instance.afterLoad()
            val apis = instance.apis.toList().onEach { it.sourcePlugin = filePath }
            val pluginExtractors = instance.extractors.toList().onEach { it.sourcePlugin = filePath }
            pluginExtractors.forEach { extractor ->
                val alreadyRegistered = extractorApis.any {
                    it === extractor ||
                        (it.sourcePlugin == filePath &&
                            it.name == extractor.name &&
                            it.mainUrl == extractor.mainUrl)
                }
                if (!alreadyRegistered) extractorApis.add(extractor)
            }
            val loaded = LoadedPlugin(instance, apis, pluginExtractors)
            cache[filePath] = loaded
            lastErrors.remove(filePath)
            lastLogs.remove(filePath)
            fetchCountByPlugin.remove(filePath)
            loaded.apis
        } catch (e: Throwable) {
            lastErrors[filePath] = "${e::class.simpleName}: ${e.message}"
            emptyList()
        }
    }

    @Serializable
    private data class PluginManifest(
        val pluginClassName: String? = null,
        // Some plugin repos use "className" instead of "pluginClassName"
        val className: String? = null,
        val name: String? = null,
        val version: Int? = null,
        val requiresResources: Boolean = false,
    ) {
        val resolvedClassName: String? get() = (pluginClassName ?: className)?.takeIf { it.isNotBlank() }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    private fun readPluginClassName(file: File): String? {
        // Strategy A: ZipFile (fast random-access)
        runCatching {
            ZipFile(file).use { zf ->
                zf.getEntry("manifest.json")?.let { entry ->
                    val body = zf.getInputStream(entry).bufferedReader().use { it.readText() }
                    json.decodeFromString(PluginManifest.serializer(), body)
                        .resolvedClassName?.let { return it }
                }
                zf.getEntry("META-INF/MANIFEST.MF")?.let { entry ->
                    zf.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines.firstOrNull { it.startsWith("Plugin-Class:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        // Strategy B: ZipInputStream (works even when ZipFile fails due to OS restrictions)
        runCatching {
            ZipInputStream(file.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "manifest.json") {
                        val body = zis.readBytes().toString(Charsets.UTF_8)
                        json.decodeFromString(PluginManifest.serializer(), body)
                            .resolvedClassName?.let { return it }
                    } else if (name == "META-INF/MANIFEST.MF") {
                        zis.readBytes().toString(Charsets.UTF_8).lineSequence()
                            .firstOrNull { it.startsWith("Plugin-Class:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return null
    }

    /**
     * Scans the classes.dex inside the .cs3 ZIP by parsing the DEX string table directly,
     * without using the deprecated DexFile.loadDex API (which is restricted on Android 12+).
     * Falls back to DexFile.loadDex on older devices.
     */
    private fun scanDexForPluginClass(
        readOnlyFile: File,
        optimizedDir: File,
        parent: ClassLoader,
    ): String? {
        val pluginBase = Plugin::class.java
        val skipPrefixes = listOf(
            "kotlin.", "kotlinx.", "java.", "javax.", "android.",
            "androidx.", "okhttp3.", "okio.", "org.jsoup.", "com.fasterxml.",
            "com.lagradost.cloudstream3.utils.", "com.lagradost.cloudstream3.mvvm.",
            "com.lagradost.cloudstream3.extractors.",
            "com.lagradost.cloudstream3.syncproviders.",
            "com.lagradost.cloudstream3.metaproviders.",
        )

        val mainApiBase = MainAPI::class.java

        // Primary: read DEX string table directly from the ZIP (no deprecated APIs)
        val candidates = readDexClassNamesFromZip(readOnlyFile)
            .filterNot { name -> skipPrefixes.any { name.startsWith(it) } }

        if (candidates.isNotEmpty()) {
            val loader = DexClassLoader(readOnlyFile.absolutePath, optimizedDir.absolutePath, null, parent)
            // First pass: look for proper Plugin subclass (preferred)
            val foundPlugin = candidates.firstOrNull { className ->
                runCatching {
                    val c = loader.loadClass(className)
                    pluginBase.isAssignableFrom(c) && !java.lang.reflect.Modifier.isAbstract(c.modifiers)
                }.getOrDefault(false)
            }
            if (foundPlugin != null) return foundPlugin
            // Second pass: bare MainAPI subclass — the load() function will wrap it automatically
            val foundApi = candidates.firstOrNull { className ->
                runCatching {
                    val c = loader.loadClass(className)
                    mainApiBase.isAssignableFrom(c) && !java.lang.reflect.Modifier.isAbstract(c.modifiers)
                }.getOrDefault(false)
            }
            if (foundApi != null) return foundApi

            // Third pass (name heuristic): isAssignableFrom failed for everything — likely because
            // loadClass throws NoClassDefFoundError (missing dependency) or returns a class whose
            // Plugin superclass is from a different loader.  Pick the best candidate purely by name
            // without verifying class membership.  The main loading code will handle mismatches.
            val fileBaseName = readOnlyFile.name.removeSuffix(".cs3").removeSuffix(".jar")
            val heuristic = candidates
                // Prefer concrete (non-inner, non-anonymous) classes with "Plugin" in the simple name
                .sortedWith(compareByDescending<String> { name ->
                    val simple = name.substringAfterLast('.')
                    when {
                        simple.contains("Plugin") && name.contains(fileBaseName, ignoreCase = true) -> 4
                        simple.contains("Plugin") -> 3
                        name.contains(fileBaseName, ignoreCase = true) -> 2
                        !simple.contains('$') -> 1
                        else -> 0
                    }
                })
                // Take the highest-scored candidate. The $ filter is intentionally removed:
            // if every class in the DEX has '$' in its simple name, the old filter
            // returned null and caused "Could not find plugin class" for valid plugins.
                .firstOrNull()
            if (heuristic != null) return heuristic
        }

        // Fallback: deprecated DexFile.loadDex for older Android versions
        @Suppress("DEPRECATION")
        return try {
            val loader = DexClassLoader(readOnlyFile.absolutePath, optimizedDir.absolutePath, null, parent)
            val dexFile = dalvik.system.DexFile.loadDex(
                readOnlyFile.absolutePath,
                File(optimizedDir, readOnlyFile.name + ".odex").absolutePath, 0,
            )
            val allNames = dexFile.entries().toList()
                .filterNot { name -> skipPrefixes.any { name.startsWith(it) } }
            allNames.firstOrNull { className ->
                runCatching {
                    val c = loader.loadClass(className)
                    pluginBase.isAssignableFrom(c) && !java.lang.reflect.Modifier.isAbstract(c.modifiers)
                }.getOrDefault(false)
            } ?: allNames.firstOrNull { className ->
                runCatching {
                    val c = loader.loadClass(className)
                    mainApiBase.isAssignableFrom(c) && !java.lang.reflect.Modifier.isAbstract(c.modifiers)
                }.getOrDefault(false)
            }
        } catch (_: Throwable) { null }
    }

    /**
     * Extracts all classes*.dex entries from the .cs3 ZIP (multi-dex aware) and
     * returns the union of class names found in each DEX's string table.
     *
     * Also handles the case where the .cs3 is a raw DEX file (not a ZIP) — some
     * older CloudStream plugin builds ship bare .dex files renamed to .cs3.
     */
    private fun readDexClassNamesFromZip(cs3File: File): List<String> {
        // Try as ZIP first
        val zipResult = runCatching {
            val result = mutableListOf<String>()
            ZipInputStream(cs3File.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // Match classes.dex, classes2.dex, classes3.dex, etc.
                    if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                        result += parseDexTypeDescriptors(zis.readBytes())
                    } else {
                        zis.closeEntry()
                    }
                    entry = zis.nextEntry
                }
            }
            result.distinct()
        }.getOrElse { emptyList() }

        if (zipResult.isNotEmpty()) return zipResult

        // Fallback: try the file itself as a raw DEX (magic bytes "dex\n")
        return runCatching {
            val bytes = cs3File.readBytes()
            if (bytes.size >= 8 &&
                bytes[0] == 'd'.code.toByte() &&
                bytes[1] == 'e'.code.toByte() &&
                bytes[2] == 'x'.code.toByte()
            ) {
                parseDexTypeDescriptors(bytes).distinct()
            } else emptyList()
        }.getOrElse { emptyList() }
    }

    /**
     * Parses a DEX file's string pool and returns class names inferred from type descriptors
     * (entries of the form "Lcom/example/ClassName;").
     * DEX format ref: https://source.android.com/docs/core/runtime/dex-format
     */
    private fun parseDexTypeDescriptors(dex: ByteArray): List<String> {
        if (dex.size < 112) return emptyList()
        return try {
            val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
            val stringIdsSize = buf.getInt(56)
            val stringIdsOff  = buf.getInt(60)
            if (stringIdsSize <= 0 || stringIdsOff + stringIdsSize.toLong() * 4 > dex.size) return emptyList()
            val result = mutableListOf<String>()
            repeat(stringIdsSize) { i ->
                val strDataOff = buf.getInt(stringIdsOff + i * 4)
                if (strDataOff <= 0 || strDataOff >= dex.size) return@repeat
                // ULEB128 character count (not byte count) — skip it
                var pos = strDataOff
                while (pos < dex.size && dex[pos].toInt() and 0x80 != 0) pos++
                pos++ // skip final byte of ULEB128
                if (pos >= dex.size || dex[pos].toInt().toChar() != 'L') return@repeat
                // Scan to terminating ';'
                val end = dex.indexOf(';'.code.toByte(), pos)
                // pos points at 'L'; skip it — class name is pos+1 .. end-1
                if (end < 0 || end <= pos + 1) return@repeat
                if (!dex.slice(pos + 1 until end).any { it == '/'.code.toByte() }) return@repeat
                // Convert Lcom/example/ClassName to com.example.ClassName (strip L, replace /)
                val descriptor = String(dex, pos + 1, end - pos - 1, Charsets.UTF_8)
                result.add(descriptor.replace('/', '.'))
            }
            result.distinct()
        } catch (_: Throwable) { emptyList() }
    }

    private fun ByteArray.indexOf(byte: Byte, fromIndex: Int): Int {
        for (i in fromIndex until size) if (this[i] == byte) return i
        return -1
    }

    suspend fun search(context: Context, filePath: String, query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val apis = load(context, filePath)
        apis.flatMap { api ->
            try { api.search(query).orEmpty() } catch (_: Throwable) { emptyList() }
        }
    }

    suspend fun home(context: Context, filePath: String): List<Pair<String, List<SearchResponse>>> = withContext(Dispatchers.IO) {
        val apis = load(context, filePath)
        if (apis.isEmpty()) {
            if (lastErrors[filePath] == null) {
                lastErrors[filePath] = "Plugin loaded but registered 0 MainAPIs."
            }
            return@withContext emptyList()
        }
        val out = mutableListOf<Pair<String, List<SearchResponse>>>()
        val perApiErrors = mutableListOf<String>()
        apis.forEach { api ->
            val requests = if (api.mainPage.isNotEmpty()) api.mainPage
            else listOf(MainPageRequest(name = api.name, data = "", horizontalImages = false))
            var apiSectionsAdded = 0
            requests.forEach { req: MainPageRequest ->
                try {
                    val page = api.getMainPage(1, req)
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
        out
    }

    suspend fun homePage(
        context: Context,
        filePath: String,
        sectionName: String,
        pageNum: Int,
    ): List<SearchResponse> = withContext(Dispatchers.IO) {
        val apis = load(context, filePath)
        val results = mutableListOf<SearchResponse>()
        apis.forEach { api ->
            val req = api.mainPage.firstOrNull { it.name == sectionName }
                ?: return@forEach
            try {
                val page = api.getMainPage(pageNum, req)
                page?.items?.forEach { hpl ->
                    results.addAll(hpl.list)
                }
            } catch (_: Throwable) {}
        }
        results
    }

    suspend fun loadDetail(context: Context, filePath: String, url: String): LoadResponse? = withContext(Dispatchers.IO) {
        val apis = load(context, filePath)
        apis.firstNotNullOfOrNull { api ->
            try { api.load(url) } catch (_: Throwable) { null }
        }
    }


    suspend fun loadLinks(
        context: Context,
        filePath: String,
        data: String,
        isCasting: Boolean = false,
    ): Pair<List<ExtractorLink>, List<SubtitleFile>> = withContext(Dispatchers.IO) {
        load(context, filePath)
        val loaded = cache[filePath]
        if (loaded == null) {
            fetchCountByPlugin.remove(filePath)
            return@withContext emptyList<ExtractorLink>() to emptyList()
        }

        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val diagnostics = mutableListOf<String>()
        val fetchCounter = AtomicInteger(0)
        val extractors = loaded.extractors
        lastErrors.remove(filePath)
        lastLogs.remove(filePath)

        fun record(message: String, asError: Boolean = false) {
            diagnostics += message
            lastLogs[filePath] = diagnostics.joinToString(" · ").take(1500)
            if (asError) lastErrors[filePath] = message
        }

        fun matchingExtractor(url: String): ExtractorApi? {
            val compareUrl = url.lowercase().replace(schemaStripRegex, "")
            return extractors.asReversed().firstOrNull { extractor ->
                compareUrl.startsWith(extractor.mainUrl.lowercase().replace(schemaStripRegex, ""))
            }
        }

        suspend fun resolveCandidate(
            candidate: ExtractorLink,
            seen: MutableSet<String>,
            owner: String,
        ): Int {
            coroutineContext.ensureActive()
            val url = candidate.url.trim()
            if (url.isBlank()) return 0

            val match = matchingExtractor(url) ?: run {
                links.add(candidate)
                return 1
            }

            val visitKey = "${match.name}|$url|${candidate.referer}"
            if (!seen.add(visitKey)) {
                links.add(candidate)
                return 1
            }

            val chained = mutableListOf<ExtractorLink>()
            val completed = withTimeoutOrNull(DEFAULT_LOAD_LINKS_TIMEOUT_MS) {
                try {
                    match.getUrl(
                        url = url,
                        referer = candidate.referer.takeIf { it.isNotBlank() },
                        subtitleCallback = { sub -> subs.add(sub) },
                        callback = { link ->
                            fetchCounter.incrementAndGet()
                            chained += link
                        },
                    )
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    record("${match.name}: ${e::class.simpleName}: ${e.message}", asError = true)
                    false
                }
            }

            if (completed == null) {
                record("${match.name}: Timed out after 30s", asError = true)
                return 0
            }
            if (!completed || chained.isEmpty()) {
                record("${match.name}: Extractor returned 0 results from $owner")
                return 0
            }

            var produced = 0
            for (next in chained) {
                produced += resolveCandidate(next, seen, match.name)
            }
            return produced
        }

        val result = withTimeoutOrNull(TOTAL_LOAD_LINKS_TIMEOUT_MS) {
            if (loaded.apis.isEmpty() && extractors.isEmpty()) {
                record("Plugin registered no APIs or extractors", asError = true)
                return@withTimeoutOrNull
            }

            for (api in loaded.apis) {
                coroutineContext.ensureActive()
                val rawLinks = mutableListOf<ExtractorLink>()
                var apiCrashed = false
                val handled = withTimeoutOrNull(
                    api.loadLinksTimeoutMs?.coerceAtMost(DEFAULT_LOAD_LINKS_TIMEOUT_MS)
                        ?: DEFAULT_LOAD_LINKS_TIMEOUT_MS,
                ) {
                    try {
                        api.loadLinks(
                            data = data,
                            isCasting = isCasting,
                            subtitleCallback = { sub -> subs.add(sub) },
                            callback = { link ->
                                fetchCounter.incrementAndGet()
                                rawLinks += link
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        apiCrashed = true
                        record("${api.name}: ${e::class.simpleName}: ${e.message}", asError = true)
                        null
                    }
                }

                when {
                    handled == null && rawLinks.isEmpty() ->
                        if (!apiCrashed) record("${api.name}: Timed out after 30s", asError = true)

                    rawLinks.isEmpty() ->
                        record("${api.name}: API returned 0 results")

                    else -> {
                        var produced = 0
                        val seen = linkedSetOf<String>()
                        rawLinks.forEach { candidate ->
                            produced += resolveCandidate(candidate, seen, api.name)
                        }
                        if (produced > 0) {
                            record("${api.name}: Resolved $produced link(s)")
                        } else {
                            record("${api.name}: Produced ${rawLinks.size} candidate link(s) but no playable streams", asError = true)
                        }
                    }
                }
            }

            if (links.isEmpty() && extractors.isNotEmpty() && matchingExtractor(data.trim()) != null) {
                val directSeed = ExtractorLink(
                    source = loaded.plugin::class.java.simpleName.ifBlank { "Plugin" },
                    name = loaded.plugin::class.java.simpleName.ifBlank { "Plugin" },
                    url = data,
                    referer = "",
                    quality = 0,
                    headers = emptyMap(),
                    extractorData = null,
                    type = ExtractorLinkType.VIDEO,
                    audioTracks = emptyList(),
                )
                val produced = resolveCandidate(directSeed, linkedSetOf(), "plugin data")
                if (produced > 0) {
                    record("Plugin extractors resolved $produced link(s) from data")
                }
            }
        }

        fetchCountByPlugin[filePath] = fetchCounter
        if (result == null) {
            record("Timed out after 90s", asError = true)
        }
        if (links.isNotEmpty()) {
            lastErrors.remove(filePath)
        } else if (!lastErrors.containsKey(filePath)) {
            lastErrors[filePath] = diagnostics.lastOrNull() ?: "No links found"
        }
        links.toList() to subs.toList()
    }


    suspend fun firstApi(context: Context, filePath: String): MainAPI? = load(context, filePath).firstOrNull()

    fun clear(filePath: String) {
        cache.remove(filePath)
        lastErrors.remove(filePath)
        lastLogs.remove(filePath)
        fetchCountByPlugin.remove(filePath)
        extractorApis.removeAll { it.sourcePlugin == filePath }
    }

    suspend fun hasSettings(context: Context, filePath: String): Boolean = withContext(Dispatchers.IO) {
        load(context, filePath)
        cache[filePath]?.plugin?.openSettings != null
    }

    /**
     * Invokes the plugin's openSettings callback.
     * Returns a Throwable if the callback threw (covers Error subclasses like
     * NoClassDefFoundError and LinkageError, not just Exception), null on success/no-op.
     * Must be called on the main thread with a valid Activity context.
     */
    fun openSettings(context: Context, filePath: String): Throwable? = try {
        cache[filePath]?.plugin?.openSettings?.invoke(context)
        null
    } catch (e: Throwable) {
        e
    }
}
