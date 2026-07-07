package com.streamcloud.app.data.nuvio

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.binding.toJsObject
import com.dokar.quickjs.quickJs
import com.streamcloud.app.data.network.BrowserCookieJar
import com.streamcloud.app.data.network.BrowserHeaders
import com.streamcloud.app.data.network.CloudflareKiller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object NuvioRuntime {
    private const val TAG = "NuvioRuntime"


    private const val MAX_FETCH_BODY_CHARS = 5 * 1024 * 1024
    private val lastErrorByScript = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Last console.log/info/warn message per provider (non-error progress messages).
    // Used in the picker to explain WHY a provider found no streams even when
    // it did not call console.error (e.g. "[Vixsrc] No stream data found").
    private val lastLogByScript   = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Number of __native_fetch calls made during a provider's execution.
    // 0 = provider returned early without touching the network (init crash / guard clause).
    // N = provider ran but APIs returned empty / error.
    private val fetchCountByScript = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private val http = OkHttpClient.Builder()
        .cookieJar(BrowserCookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()


    fun lastError(scriptKey: String): String? = lastErrorByScript[scriptKey]
    fun lastLog(scriptKey: String): String?   = lastLogByScript[scriptKey]
    fun lastFetchCount(scriptKey: String): Int = fetchCountByScript[scriptKey]?.get() ?: 0

    suspend fun runProvider(
        scriptText: String,
        tmdbId: String,
        imdbId: String? = null,
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        scriptKey: String = "default",
        context: Context? = null,
        filePath: String? = null,
    ): List<NuvioStream> {
        val documentCache = mutableMapOf<String, Document>()
        val elementCache = mutableMapOf<String, Element>()
        val idCounter = AtomicInteger()


        // Clear per-provider diagnostic state so a previous run's data never bleeds in.
        lastLogByScript.remove(scriptKey)
        lastErrorByScript.remove(scriptKey)
        fetchCountByScript[scriptKey] = java.util.concurrent.atomic.AtomicInteger(0)
        var capturedJson = "[]"
        return try {
            withTimeoutOrNull(90_000L) {
            quickJs(Dispatchers.IO) {
                installConsole(scriptKey)
                installFetchBridge(context, scriptKey)
                installCryptoBindings()
                installUrlBinding()
                installCheerioBindings(documentCache, elementCache, idCounter)
                installModuleLoader(filePath)


                function("__capture_result") { args ->
                    capturedJson = args.firstOrNull()?.toString() ?: "[]"
                    null
                }




                evaluate<Any?>(buildPolyfillCode(scriptKey))

                val seasonArg = season?.toString() ?: "undefined"
                val episodeArg = episode?.toString() ?: "undefined"













                val directResult = evaluate<Any?>(buildString {
                    appendLine("(async function() {")
                    appendLine("  var module = { exports: {} };")
                    appendLine("  var exports = module.exports;")
                    appendLine("  // ── Inject per-execution globals (official app contract) ──")
                    appendLine("  var __tmdbId  = ${jsString(tmdbId)};")
                    appendLine("  var __imdbId  = ${if (imdbId != null) jsString(imdbId) else "undefined"};")
                    appendLine("  var __mediaType = ${jsString(mediaType)};")
                    appendLine("  var __season  = $seasonArg;")
                    appendLine("  var __episode = $episodeArg;")
                    // Expose params as a single object (new-style destructured providers)
                    appendLine("  globalThis.params = {")
                    appendLine("    tmdbId:    __tmdbId,")
                    appendLine("    imdbId:    __imdbId,")
                    appendLine("    mediaType: __mediaType,")
                    appendLine("    season:    __season,")
                    appendLine("    episode:   __episode,")
                    appendLine("    scraperId: ${jsString(scriptKey)},")
                    appendLine("    settings:  globalThis.SCRAPER_SETTINGS || {},")
                    // Common field-name aliases used by different provider ecosystems
                    appendLine("    type:      __mediaType,")
                    appendLine("    id:        __tmdbId,")
                    appendLine("    tmdb_id:   __tmdbId,")
                    appendLine("    movieId:   __tmdbId,")
                    appendLine("    movie_id:  __tmdbId,")
                    appendLine("    imdbID:    __imdbId,")
                    appendLine("    imdb_id:   __imdbId,")
                    appendLine("    seriesId:  __tmdbId,")
                    appendLine("    showId:    __tmdbId,")
                    appendLine("    contentType: __mediaType")
                    appendLine("  };")
                    // Also expose each value as a top-level global so providers that
                    // access `tmdbId` / `imdbId` as free variables (instead of from params)
                    // get the correct strings rather than undefined or [object Object].
                    appendLine("  globalThis.tmdbId  = __tmdbId;")
                    appendLine("  globalThis.imdbId  = __imdbId;")
                    appendLine("  globalThis.mediaType = __mediaType;")
                    appendLine("  globalThis.type    = __mediaType;")
                    appendLine("  globalThis.season  = __season;")
                    appendLine("  globalThis.episode = __episode;")
                    // Override the polyfill's hardcoded TMDB key with the app's own valid key.
                    // Many providers call TMDB internally (e.g. to resolve imdb_id from tmdbId)
                    // and use the TMDB_API_KEY global for this.  The polyfill hardcodes a
                    // different key that may be expired or rate-limited.
                    appendLine("  globalThis.TMDB_API_KEY = ${jsString(com.streamcloud.app.BuildConfig.TMDB_API_KEY)};")
                    appendLine("  if (!globalThis.SCRAPER_SETTINGS) globalThis.SCRAPER_SETTINGS = {};")
                    appendLine("  globalThis.SCRAPER_SETTINGS.tmdb_api_key = ${jsString(com.streamcloud.app.BuildConfig.TMDB_API_KEY)};")
                    appendLine("  // ── Provider code — wrapped in try-catch to survive init errors ──────")
                    appendLine("  // function declarations inside a try block are still hoisted to the IIFE")
                    appendLine("  // scope in QuickJS, so 'function getStreams(){}' is visible below.")
                    appendLine("  // Without this wrapper, top-level await failures (API key fetch, config")
                    appendLine("  // load, etc.) reject the whole IIFE Promise and swallow every result as")
                    appendLine("  // the silent 'Promise { <state>: rejected }' we were seeing.")
                    appendLine("  var __initErr = null;")
                    appendLine("  try {")
                    append(scriptText)
                    appendLine("  } catch (__e) {")
                    appendLine("    __initErr = __e;")
                    appendLine("    console.error('[provider init] threw:', (__e && __e.message) || String(__e));")
                    appendLine("  }")
                    appendLine()
                    appendLine("  // ── Locate getStreams (official NuvioMobile lookup order) ──────────────")
                    appendLine("  var __fn =")
                    appendLine("    (typeof getStreams === 'function')                                                                                 ? getStreams :")
                    appendLine("    (module.exports && typeof module.exports.getStreams === 'function')                                                ? module.exports.getStreams :")
                    appendLine("    (module.exports && module.exports.default && typeof module.exports.default.getStreams === 'function')              ? module.exports.default.getStreams :")
                    appendLine("    (module.exports && module.exports['default'] && typeof module.exports['default'].getStreams === 'function')        ? module.exports['default'].getStreams :")
                    appendLine("    (typeof globalThis.getStreams === 'function')                                                                      ? globalThis.getStreams :")
                    appendLine("    null;")
                    appendLine("  if (typeof __fn !== 'function') {")
                    appendLine("    console.error('[provider] getStreams not found. module.exports keys:', Object.keys(module.exports || {}).join(', '));")
                    appendLine("    __capture_result('[]');")
                    appendLine("    return '[]';")
                    appendLine("  }")
                    appendLine("  try {")
                    // Official NuvioMobile calling convention (verified from pluginService.ts):
                    //   getStreams(params.tmdbId, params.mediaType, params.season, params.episode)
                    // All providers in tapframe/nuvio-providers use this exact 4-arg signature:
                    //   function getStreams(tmdbId, mediaType, seasonNum, episodeNum)
                    // We also expose `params` as a local var (mirrors the official new Function
                    // named-parameter) so providers that access params.tmdbId from scope work too.
                    appendLine("    var params = globalThis.params;")
                    // Pre-call trace: only stays visible if provider never calls console.log itself.
                    appendLine("    console.log('[runtime] calling ' + (__fn.name || 'getStreams') + ' tmdb=' + params.tmdbId + ' imdb=' + (params.imdbId || 'null') + ' type=' + params.mediaType);")
                    appendLine("    var arr = await __fn(params.tmdbId, params.mediaType, params.season, params.episode);")
                    appendLine("    var result = JSON.stringify(arr || []);")
                    appendLine("    __capture_result(result);")
                    appendLine("    return result;")
                    appendLine("  } catch (__runErr) {")
                    appendLine("    console.error('[provider] getStreams threw:', (__runErr && __runErr.message) || __runErr, (__runErr && __runErr.stack) || '');")
                    appendLine("    __capture_result('[]');")
                    appendLine("    return '[]';")
                    appendLine("  }")
                    appendLine("})()")
                })




                // evaluate() returns the rejected-Promise string repr ("Promise { <state>: ... }")
                // when the IIFE Promise rejects before our capture.  Discard it.
                val directStr = (directResult as? String)
                    ?.takeIf { it.isNotBlank() && it != "null" && !it.trimStart().startsWith("Promise ") }
                val finalJson = directStr ?: capturedJson
                val streams = parseStreams(finalJson)
                Log.i(TAG, "$scriptKey returned ${streams.size} stream(s)")
                if (streams.isEmpty()) Log.d(TAG, "$scriptKey raw json (first 500): ${finalJson.take(500)}")
                if (streams.isEmpty() && !lastErrorByScript.containsKey(scriptKey)) {
                    // Show a preview of what the provider actually returned.
                    // If finalJson is non-trivial (not "[]") it means parseStreams
                    // failed to recognise the format — visible in the stream picker.
                    val rawPreview = finalJson.trim().let {
                        if (it.isNotBlank() && it != "[]" && it != "null") {
                            " · raw: " + it.take(300)
                        } else ""
                    }
                    lastErrorByScript[scriptKey] = "No streams found (provider returned empty list)$rawPreview"
                } else if (streams.isNotEmpty()) {
                    lastErrorByScript.remove(scriptKey)
                }
                streams
            }
            } ?: run {
                Log.w(TAG, "Provider $scriptKey timed out after 90s")
                lastErrorByScript[scriptKey] = "Timed out after 90s"
                emptyList()
            }
        } catch (e: QuickJsException) {
            Log.w(TAG, "QuickJS error in $scriptKey: ${e.message}", e)
            lastErrorByScript[scriptKey] = "JS error: ${e.message}"
            emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "Provider $scriptKey crashed: ${e.message}", e)
            lastErrorByScript[scriptKey] = "Crashed: ${e.message}"
            emptyList()
        } finally {
            documentCache.clear()
            elementCache.clear()
        }
    }



    private fun com.dokar.quickjs.QuickJs.installConsole(scriptKey: String) {
        define("console") {
            listOf("log", "info", "warn", "error", "debug").forEach { level ->
                function(level) { args ->
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    when (level) {
                        "warn" -> {
                            Log.w("$TAG/$scriptKey", msg)
                            lastLogByScript[scriptKey] = "⚠ $msg"
                        }
                        "error" -> {
                            Log.e("$TAG/$scriptKey", msg)
                            lastErrorByScript[scriptKey] = msg
                        }
                        "debug" -> Log.d("$TAG/$scriptKey", msg)
                        else -> {
                            Log.i("$TAG/$scriptKey", msg)
                            lastLogByScript[scriptKey] = msg
                        }
                    }
                    null
                }
            }
        }
    }



    private fun com.dokar.quickjs.QuickJs.installFetchBridge(context: Context?, scriptKey: String) {
        // performFetchSync is a fully blocking, non-suspend function — it calls
        // OkHttp's .execute() directly so no coroutine or runBlocking wrapper is
        // needed here.  Keeping this as a plain function() (not asyncFunction) means
        // QuickJS sees __native_fetch as synchronous: the IIFE's async generator
        // chain resolves entirely within the microtask job queue, __capture_result
        // is called before evaluate() returns, and capturedJson has real data.
        function("__native_fetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString()?.uppercase() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString().orEmpty()
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            Log.d(TAG, "[$scriptKey] fetch $method ${url.take(200)}")
            fetchCountByScript.getOrPut(scriptKey) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
            val result = performFetchSync(url, method, headersJson, body, followRedirects, context)
            // Surface HTTP-level errors (non-2xx, connection failures, etc.) in the picker UI.
            // Providers that silently return [] on !response.ok would otherwise show only the
            // generic "provider returned empty list" message — with this we show the real cause.
            try {
                val J = kotlinx.serialization.json.Json
                val obj = J.parseToJsonElement(result) as? kotlinx.serialization.json.JsonObject
                val ok = (obj?.get("ok") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: true
                val status = (obj?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                if (!ok) {
                    val shortUrl = url.take(120)
                    // Only set if no provider-supplied console.error already exists for this key.
                    // Provider errors are more specific; HTTP errors are the fallback.
                    if (!lastErrorByScript.containsKey(scriptKey) || lastErrorByScript[scriptKey]?.startsWith("No streams") == true) {
                        lastErrorByScript[scriptKey] = if (status == 0) "Network error reaching $shortUrl" else "HTTP $status from $shortUrl"
                    }
                }
            } catch (_: Exception) {}
            result
        }
    }

    private fun com.dokar.quickjs.QuickJs.installModuleLoader(providerFilePath: String?) {
        if (providerFilePath == null) return
        val providerDir = java.io.File(providerFilePath).parentFile ?: return
        function("__native_load_module") { args ->
            val relPath = args.getOrNull(0)?.toString() ?: return@function null
            // Extract the meaningful module name from a relative path.
            // e.g.  "../guardahd/index"  → "guardahd"
            //       "./utils"            → "utils"
            // Segments that are navigation artefacts (.., .) or the generic "index"
            // are skipped; the first real segment becomes the module file name.
            val segments = relPath.split("/")
                .filter { it.isNotEmpty() && it != "." && it != ".." && it != "index" }
            val moduleName = segments.firstOrNull() ?: return@function null
            // Installed providers all live in the same flat directory, so a sibling
            // module like "../guardahd/index" maps to "$providerDir/guardahd.js".
            val candidates = listOf(
                java.io.File(providerDir, "$moduleName.js"),
                java.io.File(providerDir, moduleName),
            )
            val src = candidates.firstNotNullOfOrNull { f ->
                if (f.exists() && f.isFile) runCatching { f.readText() }.getOrNull() else null
            }
            Log.d(TAG, "Module '$relPath' → '$moduleName' in ${providerDir.name}: " +
                    if (src != null) "found (${src.length} chars)" else "NOT FOUND")
            src
        }
    }

    private suspend fun performFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
        context: Context? = null,
    ): String {
        return try {
            val headers = parseHeaders(headersJson).toMutableMap()
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = BrowserHeaders.USER_AGENT
            }
            if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                headers["Accept"] = BrowserHeaders.ACCEPT_JSON
            }
            if (headers.keys.none { it.equals("Accept-Language", ignoreCase = true) }) {
                headers["Accept-Language"] = BrowserHeaders.ACCEPT_LANGUAGE
            }
            val client = if (followRedirects) http else http.newBuilder().followRedirects(false).build()

            val requestBody = when {
                method == "GET" || method == "HEAD" -> null
                body.isEmpty() -> ByteArray(0).toRequestBody()
                else -> body.toRequestBody()
            }
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                method(method, requestBody)
            }.build()

            client.newCall(req).execute().use { resp ->
                val respCode = resp.code
                val respBody = resp.body?.string().orEmpty()
                val respMultimap = resp.headers.toMultimap()

                // Cloudflare challenge — try WebView bypass and retry once
                if (context != null && CloudflareKiller.isCfChallenge(respCode, respMultimap, respBody)) {
                    val ua = headers["User-Agent"] ?: BrowserHeaders.USER_AGENT
                    val bypassed = CloudflareKiller.bypass(context, url, ua, BrowserCookieJar)
                    if (bypassed) {
                        return client.newCall(req).execute().use { r2 ->
                            val raw2 = r2.body?.string().orEmpty()
                            val text2 = if (raw2.length > MAX_FETCH_BODY_CHARS) {
                                Log.w(TAG, "performFetch CF-retry: response truncated ${raw2.length} → $MAX_FETCH_BODY_CHARS chars for $url")
                                raw2.substring(0, MAX_FETCH_BODY_CHARS)
                            } else raw2
                            buildJson {
                                put("ok", r2.isSuccessful)
                                put("status", r2.code)
                                put("statusText", r2.message)
                                put("url", r2.request.url.toString())
                                put("body", text2)
                                put("headers", r2.headers.associate { (n, v) -> n.lowercase() to v })
                            }
                        }
                    }
                }

                val text = if (respBody.length > MAX_FETCH_BODY_CHARS) {
                    Log.w(TAG, "performFetch: response truncated ${respBody.length} → $MAX_FETCH_BODY_CHARS chars for $url")
                    respBody.substring(0, MAX_FETCH_BODY_CHARS)
                } else respBody
                val hdrs = resp.headers.associate { (n, v) -> n.lowercase() to v }
                buildJson {
                    put("ok", resp.isSuccessful)
                    put("status", respCode)
                    put("statusText", resp.message)
                    put("url", resp.request.url.toString())
                    put("body", text)
                    put("headers", hdrs)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetch($url) failed: ${t.message}")
            buildJson {
                put("ok", false)
                put("status", 0)
                put("statusText", t.message ?: "Fetch failed")
                put("url", url)
                put("body", "")
                put("headers", emptyMap<String, String>())
            }
        }
    }

    // Non-suspend variant used by the synchronous __native_fetch JS bridge.
    // OkHttp's .execute() is already blocking so no coroutine wrapper is needed;
    // the only suspend work (CloudflareKiller WebView bypass) is handled by
    // spinning a new coroutine scope on Dispatchers.Main and joining it via
    // runBlocking(Dispatchers.Main) — safe because this always runs on an IO thread.
    private fun performFetchSync(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
        context: Context? = null,
    ): String {
        return try {
            val headers = parseHeaders(headersJson).toMutableMap()
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = BrowserHeaders.USER_AGENT
            }
            if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                headers["Accept"] = BrowserHeaders.ACCEPT_JSON
            }
            if (headers.keys.none { it.equals("Accept-Language", ignoreCase = true) }) {
                headers["Accept-Language"] = BrowserHeaders.ACCEPT_LANGUAGE
            }
            val client = if (followRedirects) http else http.newBuilder().followRedirects(false).build()

            val requestBody = when {
                method == "GET" || method == "HEAD" -> null
                body.isEmpty() -> ByteArray(0).toRequestBody()
                else -> body.toRequestBody()
            }
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                method(method, requestBody)
            }.build()

            client.newCall(req).execute().use { resp ->
                val respCode = resp.code
                val respBody = resp.body?.string().orEmpty()
                val respMultimap = resp.headers.toMultimap()

                // Cloudflare challenge — WebView bypass requires Dispatchers.Main.
                // runBlocking(Dispatchers.Main) is safe here: we are on an IO thread
                // (called from within quickJs(Dispatchers.IO) {}), so blocking it
                // while the main thread runs the WebView does not deadlock.
                if (context != null && CloudflareKiller.isCfChallenge(respCode, respMultimap, respBody)) {
                    val ua = headers["User-Agent"] ?: BrowserHeaders.USER_AGENT
                    val bypassed = runBlocking(Dispatchers.Main) {
                        CloudflareKiller.bypass(context, url, ua, BrowserCookieJar)
                    }
                    if (bypassed) {
                        return client.newCall(req).execute().use { r2 ->
                            val raw2 = r2.body?.string().orEmpty()
                            val text2 = if (raw2.length > MAX_FETCH_BODY_CHARS) {
                                Log.w(TAG, "fetchSync CF-retry: response truncated ${raw2.length} → $MAX_FETCH_BODY_CHARS chars for $url")
                                raw2.substring(0, MAX_FETCH_BODY_CHARS)
                            } else raw2
                            buildJson {
                                put("ok", r2.isSuccessful)
                                put("status", r2.code)
                                put("statusText", r2.message)
                                put("url", r2.request.url.toString())
                                put("body", text2)
                                put("headers", r2.headers.associate { (n, v) -> n.lowercase() to v })
                            }
                        }
                    }
                }

                val text = if (respBody.length > MAX_FETCH_BODY_CHARS) {
                    Log.w(TAG, "fetchSync: response truncated ${respBody.length} → $MAX_FETCH_BODY_CHARS chars for $url")
                    respBody.substring(0, MAX_FETCH_BODY_CHARS)
                } else respBody
                val hdrs = resp.headers.associate { (n, v) -> n.lowercase() to v }
                buildJson {
                    put("ok", resp.isSuccessful)
                    put("status", respCode)
                    put("statusText", resp.message)
                    put("url", resp.request.url.toString())
                    put("body", text)
                    put("headers", hdrs)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetchSync($url) failed: ${t.message}")
            buildJson {
                put("ok", false)
                put("status", 0)
                put("statusText", t.message ?: "Fetch failed")
                put("url", url)
                put("body", "")
                put("headers", emptyMap<String, String>())
            }
        }
    }



    private fun com.dokar.quickjs.QuickJs.installCryptoBindings() {
        function("__crypto_digest_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA-256"
            val data = args.getOrNull(1)?.toString() ?: ""
            runCatching {
                val md = MessageDigest.getInstance(normalizeDigestAlgo(algorithm))
                md.digest(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
        function("__crypto_hmac_hex") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA-256"
            val key = args.getOrNull(1)?.toString() ?: ""
            val data = args.getOrNull(2)?.toString() ?: ""
            runCatching {
                val macAlgo = "Hmac" + normalizeDigestAlgo(algorithm).replace("-", "")
                val mac = Mac.getInstance(macAlgo)
                mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), macAlgo))
                mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
        function("__crypto_base64_encode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            android.util.Base64.encodeToString(data.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }
        function("__crypto_base64_decode") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching { String(android.util.Base64.decode(data, android.util.Base64.DEFAULT), Charsets.UTF_8) }
                .getOrDefault("")
        }
        function("__crypto_utf8_to_hex") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            data.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        }

        function("__crypto_base64_to_hex") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    .joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
        function("__crypto_hex_to_utf8") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                val bytes = ByteArray(data.length / 2) {
                    data.substring(it * 2, it * 2 + 2).toInt(16).toByte()
                }
                String(bytes, Charsets.UTF_8)
            }.getOrDefault("")
        }
        // Real AES-CBC / AES-CTR / AES-GCM decryption via Android javax.crypto.
        // Called from the crypto.subtle.decrypt polyfill.  All buffers passed as hex strings.
        // Returns decrypted bytes as hex, or "" on error (provider will then get empty ArrayBuffer).
        // 3DES (TripleDES) decryption — used by ShowBox and similar providers.
        function("__crypto_3des_decrypt") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "DESede/CBC/PKCS5Padding"
            val keyHex    = args.getOrNull(1)?.toString() ?: ""
            val ivHex     = args.getOrNull(2)?.toString() ?: ""
            val dataHex   = args.getOrNull(3)?.toString() ?: ""
            if (keyHex.isEmpty() || dataHex.isEmpty()) return@function ""
            fun hex2bytes(h: String) = runCatching {
                ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }.getOrNull() ?: ByteArray(0)
            runCatching {
                val keyBytes  = hex2bytes(keyHex).let { k ->
                    // 3DES requires 16 or 24 byte key; extend 16->24 by repeating first 8 bytes
                    when { k.size == 24 -> k; k.size == 16 -> k + k.copyOfRange(0, 8); else -> k }
                }
                val ivBytes   = if (ivHex.isNotEmpty()) hex2bytes(ivHex) else ByteArray(8)
                val dataBytes = hex2bytes(dataHex)
                val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "DESede")
                val cipher = when {
                    algorithm.contains("ECB", ignoreCase = true) ->
                        javax.crypto.Cipher.getInstance("DESede/ECB/PKCS5Padding").also {
                            it.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey) }
                    else ->
                        javax.crypto.Cipher.getInstance("DESede/CBC/PKCS5Padding").also {
                            it.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey,
                                javax.crypto.spec.IvParameterSpec(ivBytes)) }
                }
                cipher.doFinal(dataBytes).joinToString("") { "%02x".format(it) }
            }.onFailure { Log.w(TAG, "3DES decrypt error: ${it.message}") }
             .getOrDefault("")
        }
        function("__crypto_aes_decrypt") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "AES-CBC"
            val keyHex    = args.getOrNull(1)?.toString() ?: ""
            val ivHex     = args.getOrNull(2)?.toString() ?: ""
            val dataHex   = args.getOrNull(3)?.toString() ?: ""
            if (keyHex.isEmpty() || dataHex.isEmpty()) return@function ""
            fun hex2bytes(h: String) = runCatching {
                ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }.getOrNull() ?: ByteArray(0)
            runCatching {
                val keyBytes  = hex2bytes(keyHex)
                val ivBytes   = if (ivHex.isNotEmpty()) hex2bytes(ivHex) else ByteArray(16)
                val dataBytes = hex2bytes(dataHex)
                val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
                val cipher = when {
                    algorithm.contains("GCM", ignoreCase = true) -> {
                        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").also {
                            it.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey,
                                javax.crypto.spec.GCMParameterSpec(128, ivBytes))
                        }
                    }
                    algorithm.contains("CTR", ignoreCase = true) -> {
                        javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").also {
                            it.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey,
                                javax.crypto.spec.IvParameterSpec(ivBytes))
                        }
                    }
                    else -> { // AES-CBC (default)
                        javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").also {
                            it.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey,
                                javax.crypto.spec.IvParameterSpec(ivBytes))
                        }
                    }
                }
                cipher.doFinal(dataBytes).joinToString("") { "%02x".format(it) }
            }.onFailure { Log.w(TAG, "AES decrypt error (${algorithm}): ${it.message}") }
             .getOrDefault("")
        }
    }

    private fun normalizeDigestAlgo(algorithm: String): String {
        val a = algorithm.uppercase()
        return when {
            a.startsWith("SHA-") -> a
            a.startsWith("SHA") -> "SHA-${a.removePrefix("SHA")}"
            a == "MD5" -> "MD5"
            else -> a
        }
    }



    private fun com.dokar.quickjs.QuickJs.installUrlBinding() {
        function("__parse_url") { args ->
            val urlString = args.firstOrNull()?.toString() ?: ""
            try {
                val u = java.net.URL(urlString)
                val portStr = if (u.port != -1) u.port.toString() else ""
                val host = if (u.port != -1) "${u.host}:${u.port}" else u.host
                val search = u.query?.let { "?$it" } ?: ""
                val hash = u.ref?.let { "#$it" } ?: ""
                buildJson {
                    put("protocol", "${u.protocol}:")
                    put("host", host)
                    put("hostname", u.host)
                    put("port", portStr)
                    put("pathname", u.path.ifBlank { "/" })
                    put("search", search)
                    put("hash", hash)
                }
            } catch (_: Throwable) {
                buildJson {
                    put("protocol", "")
                    put("host", "")
                    put("hostname", "")
                    put("port", "")
                    put("pathname", "/")
                    put("search", "")
                    put("hash", "")
                }
            }
        }
    }



    private fun com.dokar.quickjs.QuickJs.installCheerioBindings(
        documentCache: MutableMap<String, Document>,
        elementCache: MutableMap<String, Element>,
        idCounter: AtomicInteger,
    ) {
        function("__cheerio_load") { args ->
            val html = args.firstOrNull()?.toString().orEmpty()
            val docId = "doc_${idCounter.incrementAndGet()}_${Random.nextInt(0, Int.MAX_VALUE)}"
            documentCache[docId] = Jsoup.parse(html)
            docId
        }
        function("__cheerio_select") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val selector = args.getOrNull(1)?.toString().orEmpty()
            val doc = documentCache[docId] ?: return@function "[]"
            runCatching {
                val els = if (selector.isEmpty()) emptyList() else doc.select(selector).toList()
                val ids = els.mapIndexed { i, el ->
                    val id = "$docId:$i:${el.hashCode()}"
                    elementCache[id] = el
                    id
                }
                ids.toJsonStringArray()
            }.getOrDefault("[]")
        }
        function("__cheerio_find") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            val selector = args.getOrNull(2)?.toString().orEmpty()
            val element = elementCache[elementId] ?: return@function "[]"
            runCatching {
                val els = element.select(selector).toList()
                val ids = els.mapIndexed { i, el ->
                    val id = "$docId:find:$i:${el.hashCode()}"
                    elementCache[id] = el
                    id
                }
                ids.toJsonStringArray()
            }.getOrDefault("[]")
        }
        function("__cheerio_text") { args ->
            val ids = args.getOrNull(1)?.toString().orEmpty()
            ids.split(",")
                .mapNotNull { elementCache[it.trim()]?.text() }
                .joinToString(" ")
        }
        function("__cheerio_html") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            if (elementId.isEmpty()) documentCache[docId]?.html().orEmpty()
            else elementCache[elementId]?.outerHtml().orEmpty()
        }
        function("__cheerio_inner_html") { args ->
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            elementCache[elementId]?.html().orEmpty()
        }
        function("__cheerio_attr") { args ->
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            val attrName = args.getOrNull(2)?.toString().orEmpty()
            val value = elementCache[elementId]?.attr(attrName)
            if (value.isNullOrEmpty()) "__UNDEFINED__" else value
        }
        function("__cheerio_next") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            val el = elementCache[elementId] ?: return@function "__NONE__"
            val next = el.nextElementSibling() ?: return@function "__NONE__"
            val nextId = "$docId:next:${next.hashCode()}"
            elementCache[nextId] = next
            nextId
        }
        function("__cheerio_prev") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            val el = elementCache[elementId] ?: return@function "__NONE__"
            val prev = el.previousElementSibling() ?: return@function "__NONE__"
            val prevId = "$docId:prev:${prev.hashCode()}"
            elementCache[prevId] = prev
            prevId
        }
        function("__cheerio_parent") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            val el = elementCache[elementId] ?: return@function "__NONE__"
            val parent = el.parent() ?: return@function "__NONE__"
            val parentId = "$docId:par:${parent.hashCode()}"
            elementCache[parentId] = parent
            parentId
        }
        function("__cheerio_closest") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            val selector = args.getOrNull(2)?.toString().orEmpty()
            val el = elementCache[elementId] ?: return@function "__NONE__"
            runCatching {
                var cur: Element? = el
                while (cur != null) {
                    if (cur.`is`(selector)) {
                        val id = "$docId:cls:${cur.hashCode()}"
                        elementCache[id] = cur
                        return@runCatching id
                    }
                    cur = cur.parent()
                }
                "__NONE__"
            }.getOrDefault("__NONE__")
        }
        function("__cheerio_matches") { args ->
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            val selector = args.getOrNull(2)?.toString().orEmpty()
            val el = elementCache[elementId] ?: return@function false
            runCatching { el.`is`(selector) }.getOrDefault(false)
        }
        function("__cheerio_siblings") { args ->
            val docId = args.getOrNull(0)?.toString().orEmpty()
            val elementId = args.getOrNull(1)?.toString().orEmpty()
            val selector = args.getOrNull(2)?.toString().orEmpty()
            val el = elementCache[elementId] ?: return@function "[]"
            runCatching {
                val siblings = el.siblingElements().toList()
                    .filter { it !== el && (selector.isEmpty() || it.`is`(selector)) }
                val ids = siblings.mapIndexed { i, sib ->
                    val id = "$docId:sib:$i:${sib.hashCode()}"
                    elementCache[id] = sib
                    id
                }
                ids.toJsonStringArray()
            }.getOrDefault("[]")
        }
    }



    private fun buildPolyfillCode(scriptKey: String): String = """
        globalThis.SCRAPER_ID = ${jsString(scriptKey)};
        if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
        if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
        if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;

        // ── Globals injected by official NuvioMobile to match provider contract ──────
        // Some providers use these as free variables without declaring them.
        if (typeof globalThis.SCRAPER_SETTINGS === 'undefined') globalThis.SCRAPER_SETTINGS = {};
        if (typeof globalThis.PRIMARY_KEY === 'undefined')       globalThis.PRIMARY_KEY = '';
        if (typeof globalThis.TMDB_API_KEY === 'undefined')      globalThis.TMDB_API_KEY = '439c478a771f35c05022f9feabcca01c';
        // logger — official app injects a logger object; fall back to console so
        // providers that call logger.log() instead of console.log() don't crash.
        if (typeof globalThis.logger === 'undefined') globalThis.logger = {
            log:   function() { console.log.apply(console, arguments); },
            info:  function() { console.log.apply(console, arguments); },
            warn:  function() { console.warn.apply(console, arguments); },
            error: function() { console.error.apply(console, arguments); },
            debug: function() { console.log.apply(console, arguments); },
        };
        // params — per-execution context object injected by the official app;
        // populated with real values in the execute IIFE below.
        if (typeof globalThis.params === 'undefined') globalThis.params = {};

        var fetch = async function(url, options) {
            options = options || {};
            // If an AbortSignal is already aborted reject immediately (matching
            // browser behaviour); if not yet aborted, just ignore it — our
            // synchronous native bridge can't cancel in-flight requests, but
            // throwing on an already-cancelled signal prevents unnecessary work.
            var signal = options.signal;
            if (signal && signal.aborted) {
                var abortErr = new Error('The operation was aborted.');
                abortErr.name = 'AbortError';
                return Promise.reject(abortErr);
            }
            var method = (options.method || 'GET').toUpperCase();
            var headers = options.headers || {};
            // Normalise headers to a plain {key:value} object regardless of what
            // the provider passed: Headers instance, array-of-pairs [[k,v],…], or
            // plain object are all accepted so we don't drop headers silently.
            if (Array.isArray(headers)) {
                var plain = {};
                for (var _hi = 0; _hi < headers.length; _hi++) {
                    var _hpair = headers[_hi];
                    if (Array.isArray(_hpair) && _hpair.length >= 2) plain[_hpair[0]] = _hpair[1];
                }
                headers = plain;
            } else if (headers && typeof headers.forEach === 'function') {
                var plain = {};
                headers.forEach(function(v, k) { plain[k] = v; });
                headers = plain;
            }
            var body = options.body || '';
            var followRedirects = options.redirect !== 'manual';
            // __native_fetch is a synchronous blocking function (returns a String directly).
            // Do NOT await it — it is not a Promise. The fetch shim itself is still async
            // so providers that use .then() chaining still get a proper Promise from fetch().
            var result = __native_fetch(url, method, JSON.stringify(headers), body, followRedirects);
            var parsed = JSON.parse(result);
            // Compact response trace (same format as XHR so the picker shows real API output).
            try {
                var _fPath = String(url || '').replace(/^https?:\/\/[^\/]+/, '').substring(0, 50);
                var _fBody = String(parsed.body || '').substring(0, 55);
                console.log('[rsp] ' + method + ' ' + _fPath + ' \u2192 ' + parsed.status + ' ' + _fBody);
            } catch(_fErr) {}
            return {
                ok: parsed.ok, status: parsed.status, statusText: parsed.statusText,
                url: parsed.url, redirected: parsed.redirected || false, type: 'basic',
                headers: (function() {
                    var hdrsObj = parsed.headers || {};
                    return {
                        get: function(name) { return hdrsObj[String(name).toLowerCase()] || null; },
                        has: function(name) { return !!hdrsObj[String(name).toLowerCase()]; },
                        entries: function() { return Object.entries(hdrsObj); },
                        keys: function() { return Object.keys(hdrsObj); },
                        values: function() { return Object.values(hdrsObj); },
                        forEach: function(cb) {
                            Object.entries(hdrsObj).forEach(function(e) { cb(e[1], e[0], this); });
                        },
                    };
                })(),
                text: function() { return Promise.resolve(parsed.body); },
                json: function() {
                    try { return Promise.resolve(JSON.parse(parsed.body)); }
                    catch (e) { return Promise.resolve(null); }
                },
                arrayBuffer: function() { return Promise.resolve(new ArrayBuffer(0)); },
                blob: function() { return Promise.resolve(null); },
                formData: function() { return Promise.resolve(null); },
                clone: function() { return this; },
            };
        };
        // Make fetch reachable via every global alias providers might use.
        globalThis.fetch = fetch;

        // ── Headers constructor ──────────────────────────────────────────────
        // Many providers use `new Headers({ 'Content-Type': 'application/json' })`.
        // Without this, the constructor throws and the provider's first fetch call
        // crashes, returning an empty stream list.
        if (typeof globalThis.Headers === 'undefined') {
            globalThis.Headers = function Headers(init) {
                this._h = {};
                var self = this;
                if (init) {
                    if (Array.isArray(init)) {
                        init.forEach(function(pair) { if (pair.length >= 2) self._h[pair[0].toLowerCase()] = pair[1]; });
                    } else if (typeof init === 'object') {
                        Object.keys(init).forEach(function(k) { self._h[k.toLowerCase()] = init[k]; });
                    }
                }
            };
            globalThis.Headers.prototype.get    = function(k) { return this._h[k.toLowerCase()] || null; };
            globalThis.Headers.prototype.has    = function(k) { return k.toLowerCase() in this._h; };
            globalThis.Headers.prototype.set    = function(k, v) { this._h[k.toLowerCase()] = v; };
            globalThis.Headers.prototype.append = function(k, v) { this._h[k.toLowerCase()] = v; };
            globalThis.Headers.prototype.delete = function(k) { delete this._h[k.toLowerCase()]; };
            globalThis.Headers.prototype.entries= function() { return Object.entries(this._h); };
            globalThis.Headers.prototype.keys   = function() { return Object.keys(this._h); };
            globalThis.Headers.prototype.values = function() { return Object.values(this._h); };
            globalThis.Headers.prototype.forEach= function(cb) { var h = this._h; Object.keys(h).forEach(function(k) { cb(h[k], k); }); };
        }

        // Legacy positional signature used by D3adlyRocket / phisher98 forks.
        async function fetchv2(url, headers, method, body, encodeUrl, encoding) {
            return await fetch(url, { method: method || 'GET', headers: headers || {}, body: body });
        }
        globalThis.fetchv2 = fetchv2;

        // ── XMLHttpRequest polyfill ──────────────────────────────────────────────
        // Many Nuvio providers (Cineby, MovieBox, Dahmermovies, VidLink, MoviesMod,
        // AllMovieLand and others) use XHR instead of fetch(). Without this shim,
        // any `new XMLHttpRequest()` throws a ReferenceError which is silently caught
        // inside the provider's try-catch, causing getStreams to return [] with 0 req.
        // This shim delegates to the same __native_fetch Kotlin bridge used by fetch().
        if (typeof XMLHttpRequest === 'undefined') {
            globalThis.XMLHttpRequest = function XMLHttpRequest() {
                this.readyState  = 0;   // UNSENT
                this.status      = 0;
                this.statusText  = '';
                this.responseText= '';
                this.response    = '';
                this.responseURL = '';
                this.responseType= '';
                this.withCredentials = false;
                this.timeout     = 0;
                this.onreadystatechange = null;
                this.onload      = null;
                this.onloadend   = null;
                this.onerror     = null;
                this.ontimeout   = null;
                this.onprogress  = null;
                this.onloadstart = null;
                this.responseXML = null;
                this._method     = 'GET';
                this._url        = '';
                this._headers    = {};
                this._responseHeaders = {};
            };
            XMLHttpRequest.UNSENT           = 0;
            XMLHttpRequest.OPENED           = 1;
            XMLHttpRequest.HEADERS_RECEIVED = 2;
            XMLHttpRequest.LOADING          = 3;
            XMLHttpRequest.DONE             = 4;
            XMLHttpRequest.prototype.open = function(method, url, async) {
                this._method  = (method || 'GET').toUpperCase();
                this._url     = url;
                this._headers = {};
                this.readyState = 1; // OPENED
                if (typeof this.onreadystatechange === 'function') {
                    try { this.onreadystatechange(); } catch(e) {}
                }
            };
            XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                if (name != null) this._headers[name] = String(value == null ? '' : value);
            };
            XMLHttpRequest.prototype.getResponseHeader = function(name) {
                return (name && this._responseHeaders) ? (this._responseHeaders[name.toLowerCase()] || null) : null;
            };
            XMLHttpRequest.prototype.getAllResponseHeaders = function() {
                if (!this._responseHeaders) return '';
                return Object.entries(this._responseHeaders).map(function(e) { return e[0] + ': ' + e[1]; }).join('\r\n');
            };
            XMLHttpRequest.prototype.send = function(body) {
                var self = this;
                // SYNCHRONOUS send: __native_fetch is a blocking Kotlin bridge (runs on
                // the QuickJS IO thread), so we call it directly and fire all callbacks
                // before send() returns.  This matches synchronous XHR patterns used by
                // many providers:
                //   xhr.onload = function() { streams = parse(this.responseText); };
                //   xhr.send();
                //   return streams;  // ← populated correctly with sync callbacks
                //
                // For Promise-based providers the synchronous resolve() still works:
                // onload fires → resolve(data) → Promise resolves as a microtask →
                // the outer await picks it up correctly.
                var _doSend = function() {
                    try {
                        var result = __native_fetch(
                            self._url, self._method,
                            JSON.stringify(self._headers),
                            (body == null ? '' : String(body)),
                            true   // followRedirects
                        );
                        var parsed = JSON.parse(result);
                        self.status      = parsed.status      || 0;
                        self.statusText  = parsed.statusText  || '';
                        self.responseURL = parsed.url         || self._url;
                        self._responseHeaders = parsed.headers || {};
                        var rawBody = parsed.body || '';
                        if (self.responseType === 'json') {
                            try { self.response = JSON.parse(rawBody); } catch(e) { self.response = null; }
                        } else {
                            self.response = rawBody;
                        }
                        self.responseText = rawBody;
                        self.readyState   = 4; // DONE
                        // Compact HTTP response trace — visible as lastLog in the stream picker.
                        // Format: "[rsp] METHOD /path/... → STATUS body_preview"
                        try {
                            var _rspPath = String(self._url || '').replace(/^https?:\/\/[^\/]+/, '').substring(0, 50);
                            var _rspBody = String(rawBody || '').substring(0, 55);
                            console.log('[rsp] ' + (self._method||'?') + ' ' + _rspPath + ' \u2192 ' + self.status + ' ' + _rspBody);
                        } catch(_rspErr) {}
                        if (typeof self.onreadystatechange === 'function') {
                            try { self.onreadystatechange(); } catch(e) {}
                        }
                        // Browser spec: onload fires for ALL completed HTTP responses
                        // (200, 301, 404, 500…). onerror fires ONLY for network failures
                        // (no connection, DNS fail) where status === 0.
                        // Many providers check xhr.status / xhr.responseText inside onload.
                        if (typeof self.onload === 'function') {
                            try { self.onload({ type: 'load', target: self, currentTarget: self }); } catch(e) {}
                        }
                        if (parsed.status === 0 && typeof self.onerror === 'function') {
                            try { self.onerror({ type: 'error', target: self }); } catch(e) {}
                        }
                        if (typeof self.onloadend === 'function') {
                            try { self.onloadend({ type: 'loadend', target: self }); } catch(e) {}
                        }
                    } catch(err) {
                        self.status    = 0;
                        self.statusText= (err && err.message) || 'Network Error';
                        self.readyState= 4;
                        if (typeof self.onreadystatechange === 'function') {
                            try { self.onreadystatechange(); } catch(e) {}
                        }
                        if (typeof self.onerror === 'function') {
                            try { self.onerror({ type: 'error', target: self }); } catch(e) {}
                        }
                    }
                };
                // If we are inside an async context, run sync immediately (no microtask hop).
                // If we are at the top level of a sync function, still run sync — the caller's
                // result variable will be populated before send() returns.
                _doSend();
            };
            XMLHttpRequest.prototype.abort = function() { this.readyState = 0; this.status = 0; };
            XMLHttpRequest.prototype.addEventListener = function(type, fn) {
                if (!fn || typeof fn !== 'function') return;
                if (type === 'load')             this.onload     = fn;
                else if (type === 'loadend')     this.onloadend  = fn;
                else if (type === 'loadstart')   this.onloadstart= fn;
                else if (type === 'error')       this.onerror    = fn;
                else if (type === 'readystatechange') this.onreadystatechange = fn;
                else if (type === 'progress')    this.onprogress = fn;
                else if (type === 'timeout')     this.ontimeout  = fn;
            };
            XMLHttpRequest.prototype.removeEventListener = function() {};
            globalThis.XMLHttpRequest = XMLHttpRequest;
        }

        // setTimeout / clearTimeout stubs.
        //
        // IMPORTANT: do NOT fire non-zero-delay callbacks synchronously.
        // The most common Nuvio provider pattern is:
        //   const controller = new AbortController();
        //   setTimeout(() => controller.abort(), 10000);
        //   const res = await fetch(url, { signal: controller.signal });
        //   clearTimeout(id);
        //
        // If we call controller.abort() synchronously before the fetch() call,
        // our fetch shim (line: `if (signal && signal.aborted) reject`) fires
        // immediately and EVERY network request is aborted → provider returns [].
        //
        // Fix: store non-zero-delay callbacks but never fire them — clearTimeout()
        // removes them so they are no-ops, exactly as in a real browser where the
        // fetch completes long before the timeout fires.
        // Zero-delay / no-delay timeouts (used as Promise-yield / queueMicrotask
        // equivalents) are executed via Promise.resolve().then() so they run
        // at the next microtask checkpoint, which is what providers expect.
        if (typeof setTimeout === 'undefined') {
            var __timerSeq = 0;
            var __pendingTimers = {};
            globalThis.setTimeout = function(fn, ms) {
                var id = ++__timerSeq;
                if (typeof fn !== 'function') return id;
                // Fire ALL callbacks as a Promise.resolve().then() microtask,
                // regardless of the requested delay.
                //
                // WHY: Many Nuvio providers use sleep() patterns to rate-limit:
                //   const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
                //   await sleep(1500);  // delay between path resolutions
                // With the old "store but never fire" approach, sleep() Promises
                // never resolved and the provider hung for 90 seconds returning [].
                //
                // ABORT-SIGNAL SAFETY: Our __native_fetch bridge is synchronous and
                // blocking.  When `await fetch(url, {signal})` executes, the entire
                // HTTP round-trip completes before any microtask (including an abort
                // callback) can run.  Therefore signal.aborted is always false during
                // the active fetch call.  Abort callbacks that fire as microtasks after
                // the await only matter for the NEXT fetch() call — but standard Nuvio
                // plugins do not pass abort signals to their inner-loop fetch calls
                // (signals are only on the outer guard request which has already returned).
                //
                // setInterval is still a no-op (providers should not poll in scrapers).
                __pendingTimers[id] = fn;
                Promise.resolve().then(function() {
                    var f = __pendingTimers[id];
                    if (f) { delete __pendingTimers[id]; try { f(); } catch(e) {} }
                });
                return id;
            };
            globalThis.clearTimeout  = function(id) { if (id) delete __pendingTimers[id]; };
            globalThis.setInterval   = function(fn, ms) { return ++__timerSeq; };
            globalThis.clearInterval = function(id) { if (id) delete __pendingTimers[id]; };
            globalThis.setImmediate  = function(fn) { return globalThis.setTimeout(fn, 0); };
            globalThis.clearImmediate= function(id) { if (id) delete __pendingTimers[id]; };
        }

        if (typeof AbortSignal === 'undefined') {
            var AbortSignal = function() { this.aborted = false; this._listeners = []; };
            AbortSignal.prototype.addEventListener = function(type, listener) {
                if (type === 'abort' && typeof listener === 'function') this._listeners.push(listener);
            };
            globalThis.AbortSignal = AbortSignal;
        }
        if (typeof AbortController === 'undefined') {
            var AbortController = function() { this.signal = new AbortSignal(); };
            AbortController.prototype.abort = function(reason) { this.signal.aborted = true; };
            globalThis.AbortController = AbortController;
        }

        // ── WebSocket stub ────────────────────────────────────────────────────
        // Providers that open WebSocket connections (e.g. StreamFlix for live TV
        // episode tracking) will crash with ReferenceError without this stub.
        // Our runtime has no real network-layer WS support; we return a closed
        // socket immediately so providers can catch the error and fall back to
        // their HTTP code path (which works correctly with our fetch polyfill).
        if (typeof WebSocket === 'undefined') {
            globalThis.WebSocket = function WebSocket(url, protocols) {
                var self = this;
                self.url = url || ''; self.readyState = 3; // CLOSED
                self.onopen = null; self.onclose = null; self.onerror = null; self.onmessage = null;
                self.bufferedAmount = 0; self.extensions = ''; self.protocol = '';
                self.send = function() {};
                self.close = function() { self.readyState = 3; };
                self.addEventListener = function(type, fn) {
                    if (type === 'open')    self.onopen = fn;
                    else if (type === 'close')  self.onclose = fn;
                    else if (type === 'error')  self.onerror = fn;
                    else if (type === 'message')self.onmessage = fn;
                };
                // Fire error/close asynchronously so the provider can attach listeners first.
                Promise.resolve().then(function() {
                    var err = new Error('WebSocket is not supported in the Nuvio native runtime');
                    err.type = 'error';
                    if (typeof self.onerror === 'function') try { self.onerror(err); } catch(e) {}
                    if (typeof self.onclose === 'function') try {
                        self.onclose({ type: 'close', code: 1006, reason: 'No WS support', wasClean: false });
                    } catch(e) {}
                });
            };
            WebSocket.CONNECTING = 0; WebSocket.OPEN = 1; WebSocket.CLOSING = 2; WebSocket.CLOSED = 3;
            globalThis.WebSocket = WebSocket;
        }
        if (typeof atob === 'undefined') {
            globalThis.atob = function(input) { return __crypto_base64_decode(input); };
        }
        if (typeof btoa === 'undefined') {
            globalThis.btoa = function(input) { return __crypto_base64_encode(input); };
        }

        // ── location stub ─────────────────────────────────────────────────────
        // Providers may read window.location.origin / href to build Referer headers.
        if (typeof location === 'undefined') {
            var __loc = { href: 'https://streamcloud.app/', hostname: 'streamcloud.app', host: 'streamcloud.app', origin: 'https://streamcloud.app', pathname: '/', search: '', hash: '', protocol: 'https:' };
            globalThis.location = __loc;
            if (typeof globalThis.window !== 'undefined') globalThis.window.location = __loc;
        }

        // ── document stub ─────────────────────────────────────────────────────
        // Minimal stub so providers that sniff `typeof document` don't crash.
        if (typeof document === 'undefined') {
            globalThis.document = {
                createElement: function(tag) {
                    var el = { tagName: (tag||'').toUpperCase(), innerHTML: '', textContent: '', value: '', style: {}, className: '', id: '', href: '', src: '' };
                    el.setAttribute = function(k, v) { el[k] = v; };
                    el.getAttribute = function(k) { return el[k] || null; };
                    el.appendChild = function() {}; el.removeChild = function() {}; el.addEventListener = function() {};
                    return el;
                },
                getElementById: function() { return null; },
                querySelector: function() { return null; },
                querySelectorAll: function() { return []; },
                getElementsByTagName: function() { return []; },
                head: { appendChild: function() {} },
                body: { appendChild: function() {}, style: {} },
                cookie: '',
                domain: 'streamcloud.app',
                location: globalThis.location,
                createElementNS: function(ns, tag) { return this.createElement(tag); },
            };
        }

        // ── process stub ──────────────────────────────────────────────────────
        // Node.js-targeted providers check process.env, process.browser, etc.
        if (typeof process === 'undefined') {
            globalThis.process = {
                env: { NODE_ENV: 'production' },
                browser: true,
                version: 'v18.0.0',
                platform: 'android',
                nextTick: function(fn) { try { if (typeof fn === 'function') fn(); } catch(e) {} },
            };
        }

        // ── crypto stub ──────────────────────────────────────────────────────
        // Many providers use crypto.getRandomValues() for nonce/token generation and
        // crypto.randomUUID() for session ids.  Without this they throw a TypeError
        // and silently return no streams.
        if (typeof globalThis.crypto === 'undefined') {
            globalThis.crypto = {
                getRandomValues: function(arr) {
                    for (var _i = 0; _i < arr.length; _i++) arr[_i] = Math.floor(Math.random() * 256);
                    return arr;
                },
                randomUUID: function() {
                    var b = [];
                    for (var _i = 0; _i < 16; _i++) b.push(Math.floor(Math.random() * 256));
                    b[6] = (b[6] & 0x0f) | 0x40;
                    b[8] = (b[8] & 0x3f) | 0x80;
                    var h = function(n) { return ('00' + n.toString(16)).slice(-2); };
                    return h(b[0])+h(b[1])+h(b[2])+h(b[3])+'-'+h(b[4])+h(b[5])+'-'+h(b[6])+h(b[7])+'-'+h(b[8])+h(b[9])+'-'+h(b[10])+h(b[11])+h(b[12])+h(b[13])+h(b[14])+h(b[15]);
                },
                subtle: {
                    // importKey: store key material as hex in the returned CryptoKey object.
                    // decode helper inlined — converts Buffer shim / Uint8Array / ArrayBuffer to hex.
                    importKey: function(format, keyData, algorithm, extractable, usages) {
                        var alg = (typeof algorithm === 'string') ? algorithm : ((algorithm && algorithm.name) || 'AES-CBC');
                        var kh = '';
                        if (keyData) {
                            try { var tmp0 = keyData.toString('hex'); if (tmp0 && /^[0-9a-fA-F]+$/.test(tmp0)) kh = tmp0.toLowerCase(); } catch(_e) {}
                            if (!kh) {
                                var ksrc = (keyData.buffer && keyData.buffer.byteLength !== undefined) ? new Uint8Array(keyData.buffer) : (keyData.byteLength !== undefined ? new Uint8Array(keyData) : null);
                                if (ksrc) { for (var ki = 0; ki < ksrc.length; ki++) kh += ('00' + (ksrc[ki] & 0xff).toString(16)).slice(-2); }
                            }
                        }
                        return Promise.resolve({ _keyHex: kh, _alg: alg, type: 'secret', extractable: !!extractable, algorithm: { name: alg } });
                    },
                    // decrypt: real AES via native __crypto_aes_decrypt (AES-CBC/CTR/GCM).
                    decrypt: function(algorithm, key, data) {
                        var alg = (typeof algorithm === 'string') ? algorithm : ((algorithm && algorithm.name) || 'AES-CBC');
                        var ivHex = '';
                        if (algorithm && algorithm.iv) {
                            try { var tmp1 = algorithm.iv.toString('hex'); if (tmp1 && /^[0-9a-fA-F]+$/.test(tmp1)) ivHex = tmp1.toLowerCase(); } catch(_e) {}
                            if (!ivHex) {
                                var ivsrc = (algorithm.iv.buffer && algorithm.iv.buffer.byteLength !== undefined) ? new Uint8Array(algorithm.iv.buffer) : (algorithm.iv.byteLength !== undefined ? new Uint8Array(algorithm.iv) : null);
                                if (ivsrc) { for (var ii = 0; ii < ivsrc.length; ii++) ivHex += ('00' + (ivsrc[ii] & 0xff).toString(16)).slice(-2); }
                            }
                        }
                        var keyHex = (key && key._keyHex) ? key._keyHex : '';
                        var dataHex = '';
                        if (data) {
                            try { var tmp2 = data.toString('hex'); if (tmp2 && /^[0-9a-fA-F]+$/.test(tmp2)) dataHex = tmp2.toLowerCase(); } catch(_e) {}
                            if (!dataHex) {
                                var dsrc = (data.buffer && data.buffer.byteLength !== undefined) ? new Uint8Array(data.buffer) : (data.byteLength !== undefined ? new Uint8Array(data) : null);
                                if (dsrc) { for (var di = 0; di < dsrc.length; di++) dataHex += ('00' + (dsrc[di] & 0xff).toString(16)).slice(-2); }
                            }
                        }
                        var resultHex = __crypto_aes_decrypt(alg, keyHex, ivHex, dataHex);
                        if (!resultHex) return Promise.resolve(new ArrayBuffer(0));
                        var dlen = resultHex.length >>> 1;
                        var darr = new Uint8Array(dlen);
                        for (var ri = 0; ri < dlen; ri++) darr[ri] = parseInt(resultHex.substr(ri * 2, 2), 16);
                        return Promise.resolve(darr.buffer);
                    },
                    encrypt:    function() { return Promise.resolve(new ArrayBuffer(0)); },
                    digest:     function(algo, data) { return Promise.resolve(new ArrayBuffer(32)); },
                    sign:       function() { return Promise.resolve(new ArrayBuffer(32)); },
                    verify:     function() { return Promise.resolve(true); },
                    generateKey: function(algorithm, extractable, usages) {
                        var alg = (typeof algorithm === 'string') ? algorithm : ((algorithm && algorithm.name) || 'AES-CBC');
                        return Promise.resolve({ _keyHex: '', _alg: alg, type: 'secret', extractable: !!extractable, algorithm: { name: alg } });
                    },
                    exportKey:  function() { return Promise.resolve(new ArrayBuffer(0)); },
                    wrapKey:    function() { return Promise.resolve(new ArrayBuffer(0)); },
                    unwrapKey:  function() { return Promise.resolve(new ArrayBuffer(0)); },
                    deriveBits: function() { return Promise.resolve(new ArrayBuffer(32)); },
                    deriveKey:  function(algorithm, baseKey, derivedKeyAlgorithm, extractable, usages) {
                        var alg = (typeof derivedKeyAlgorithm === 'string') ? derivedKeyAlgorithm : ((derivedKeyAlgorithm && derivedKeyAlgorithm.name) || 'AES-CBC');
                        return Promise.resolve({ _keyHex: '', _alg: alg, type: 'secret', extractable: !!extractable, algorithm: { name: alg } });
                    },
                },
            };
        }

        // ── navigator stub ────────────────────────────────────────────────────
        // Providers may sniff navigator.userAgent or navigator.language.
        if (typeof navigator === 'undefined') {
            globalThis.navigator = {
                userAgent: 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
                language: 'en-US',
                languages: ['en-US', 'en'],
                onLine: true,
                platform: 'Android',
            };
        }

        // ── Buffer shim ───────────────────────────────────────────────────────
        // Node.js providers commonly use Buffer.from(str, 'base64').toString('hex')
        // and Buffer.from(str).toString('base64').
        if (typeof Buffer === 'undefined') {
            globalThis.Buffer = {
                from: function(data, encoding) {
                    var enc = (encoding || 'utf8').toLowerCase();
                    return {
                        _data: data, _enc: enc,
                        toString: function(fmt) {
                            var f = (fmt || 'utf8').toLowerCase();
                            if (enc === 'base64') {
                                if (f === 'hex')              return __crypto_base64_to_hex(data);
                                if (f === 'base64')           return data;
                                return __crypto_base64_decode(data);   // utf-8
                            }
                            if (enc === 'hex') {
                                if (f === 'base64') return __crypto_base64_encode(__crypto_hex_to_utf8(data));
                                if (f === 'hex')    return data;
                                return __crypto_hex_to_utf8(data);
                            }
                            // default: treat input as utf-8 string
                            if (f === 'base64') return __crypto_base64_encode(data);
                            if (f === 'hex')    return __crypto_utf8_to_hex(data);
                            return data;
                        },
                        length: (data ? data.length : 0),
                    };
                },
                alloc: function(size) { return { length: size, toString: function() { return ''; } }; },
                concat: function(list) {
                    return {
                        toString: function(fmt) {
                            return (list || []).map(function(b) { return b.toString(fmt); }).join('');
                        },
                    };
                },
                isBuffer: function() { return false; },
                byteLength: function(s) { return s ? s.length : 0; },
            };
        }

        // ── TextEncoder / TextDecoder shims ───────────────────────────────────
        if (typeof TextEncoder === 'undefined') {
            globalThis.TextEncoder = function() {};
            TextEncoder.prototype.encode = function(str) {
                var hex = __crypto_utf8_to_hex(str || '');
                var len = hex.length >>> 1;
                var arr = new Uint8Array(len);
                for (var i = 0; i < len; i++) arr[i] = parseInt(hex.substr(i * 2, 2), 16);
                return arr;
            };
        }
        if (typeof TextDecoder === 'undefined') {
            globalThis.TextDecoder = function(enc) { this.encoding = enc || 'utf-8'; };
            TextDecoder.prototype.decode = function(buf) {
                if (!buf) return '';
                if (typeof buf === 'string') return buf;
                var hex = '';
                for (var i = 0; i < buf.length; i++) hex += ('00' + buf[i].toString(16)).slice(-2);
                return __crypto_hex_to_utf8(hex);
            };
        }

        var URL = function(urlString, base) {
            var fullUrl = urlString;
            if (base) {
                var b = typeof base === 'string' ? base : (base.href || String(base));
                if (/^\/\//.test(urlString)) {
                    // Protocol-relative → inherit protocol from base
                    var proto = b.match(/^(https?:)/i);
                    fullUrl = (proto ? proto[1] : 'https:') + urlString;
                } else if (!/^https?:\/\//i.test(urlString)) {
                    if (urlString.charAt(0) === '/') {
                        var m = b.match(/^(https?:\/\/[^\/]+)/);
                        fullUrl = m ? m[1] + urlString : urlString;
                    } else { fullUrl = b.replace(/\/[^\/]*$/, '/') + urlString; }
                }
            }
            var data = JSON.parse(__parse_url(fullUrl));
            this.href = fullUrl;
            this.protocol = data.protocol; this.host = data.host; this.hostname = data.hostname;
            this.port = data.port; this.pathname = data.pathname; this.search = data.search;
            this.hash = data.hash; this.origin = data.protocol + '//' + data.host;
            // searchParams whose mutations propagate back to this.href / this.search.
            var self = this;
            var sp = new URLSearchParams(data.search || '');
            var origSet = sp.set.bind(sp), origAppend = sp.append.bind(sp), origDel = sp.delete.bind(sp);
            function syncHref() {
                var qs = sp.toString();
                self.search = qs ? ('?' + qs) : '';
                var base2 = fullUrl.split('?')[0].split('#')[0];
                self.href = base2 + self.search + self.hash;
            }
            sp.set    = function(k, v) { origSet(k, v);    syncHref(); };
            sp.append = function(k, v) { origAppend(k, v); syncHref(); };
            sp.delete = function(k)    { origDel(k);        syncHref(); };
            this.searchParams = sp;
        };
        URL.prototype.toString = function() { return this.href; };
        globalThis.URL = URL;

        var URLSearchParams = function(init) {
            this._params = {};
            var self = this;
            if (init && typeof init === 'object' && !Array.isArray(init)) {
                Object.keys(init).forEach(function(k) { self._params[k] = String(init[k]); });
            } else if (typeof init === 'string') {
                init.replace(/^\?/, '').split('&').forEach(function(p) {
                    var parts = p.split('=');
                    if (parts[0]) self._params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1] || '');
                });
            }
        };
        URLSearchParams.prototype.toString = function() {
            var s = this;
            return Object.keys(this._params).map(function(k) {
                return encodeURIComponent(k) + '=' + encodeURIComponent(s._params[k]);
            }).join('&');
        };
        URLSearchParams.prototype.get = function(k) { return Object.prototype.hasOwnProperty.call(this._params, k) ? this._params[k] : null; };
        URLSearchParams.prototype.set = function(k, v) { this._params[k] = String(v); };
        URLSearchParams.prototype.append = function(k, v) { this._params[k] = String(v); };
        URLSearchParams.prototype.has = function(k) { return Object.prototype.hasOwnProperty.call(this._params, k); };
        URLSearchParams.prototype.delete = function(k) { delete this._params[k]; };
        URLSearchParams.prototype.getAll = function(k) { return this.has(k) ? [this._params[k]] : []; };
        URLSearchParams.prototype.forEach = function(cb, thisArg) {
            var self = this;
            Object.keys(this._params).forEach(function(k) { cb.call(thisArg, self._params[k], k, self); });
        };
        URLSearchParams.prototype.entries = function() {
            var keys = Object.keys(this._params), i = 0, self = this;
            var iter = { next: function() { return i < keys.length ? { value: [keys[i], self._params[keys[i++]]], done: false } : { value: undefined, done: true }; } };
            try { iter[Symbol.iterator] = function() { return iter; }; } catch(e) {}
            return iter;
        };
        URLSearchParams.prototype.keys = function() {
            var keys = Object.keys(this._params), i = 0;
            var iter = { next: function() { return i < keys.length ? { value: keys[i++], done: false } : { value: undefined, done: true }; } };
            try { iter[Symbol.iterator] = function() { return iter; }; } catch(e) {}
            return iter;
        };
        URLSearchParams.prototype.values = function() {
            var keys = Object.keys(this._params), i = 0, self = this;
            var iter = { next: function() { return i < keys.length ? { value: self._params[keys[i++]], done: false } : { value: undefined, done: true }; } };
            try { iter[Symbol.iterator] = function() { return iter; }; } catch(e) {}
            return iter;
        };
        try {
            URLSearchParams.prototype[Symbol.iterator] = URLSearchParams.prototype.entries;
        } catch(e) {}
        globalThis.URLSearchParams = URLSearchParams;

        // ── Cheerio (Jsoup-backed) ──────────────────────────────────────
        function __createWrapperFromIds(docId, ids) {
            var wrapper = {
                _docId: docId, _elementIds: ids, length: ids.length,
                each: function(cb) {
                    for (var i = 0; i < ids.length; i++) {
                        var w = __createWrapperFromIds(docId, [ids[i]]);
                        cb.call(w, i, w);
                    }
                    return wrapper;
                },
                find: function(sel) {
                    var allIds = [];
                    for (var i = 0; i < ids.length; i++) {
                        var sub = JSON.parse(__cheerio_find(docId, ids[i], sel));
                        for (var j = 0; j < sub.length; j++) allIds.push(sub[j]);
                    }
                    return __createWrapperFromIds(docId, allIds);
                },
                text: function() { return ids.length ? __cheerio_text(docId, ids.join(',')) : ''; },
                html: function() { return ids.length ? __cheerio_inner_html(docId, ids[0]) : ''; },
                attr: function(name) {
                    if (!ids.length) return undefined;
                    var v = __cheerio_attr(docId, ids[0], name);
                    return v === '__UNDEFINED__' ? undefined : v;
                },
                first: function() { return __createWrapperFromIds(docId, ids.length ? [ids[0]] : []); },
                last: function() { return __createWrapperFromIds(docId, ids.length ? [ids[ids.length - 1]] : []); },
                eq: function(i) { return (i >= 0 && i < ids.length) ? __createWrapperFromIds(docId, [ids[i]]) : __createWrapperFromIds(docId, []); },
                next: function() {
                    var next = [];
                    for (var i = 0; i < ids.length; i++) {
                        var n = __cheerio_next(docId, ids[i]);
                        if (n && n !== '__NONE__') next.push(n);
                    }
                    return __createWrapperFromIds(docId, next);
                },
                prev: function() {
                    var prev = [];
                    for (var i = 0; i < ids.length; i++) {
                        var p = __cheerio_prev(docId, ids[i]);
                        if (p && p !== '__NONE__') prev.push(p);
                    }
                    return __createWrapperFromIds(docId, prev);
                },
                map: function(cb) {
                    var out = [];
                    for (var i = 0; i < ids.length; i++) {
                        var w = __createWrapperFromIds(docId, [ids[i]]);
                        var r = cb.call(w, i, w);
                        if (r !== undefined && r !== null) out.push(r);
                    }
                    return { length: out.length, get: function(i) { return typeof i === 'number' ? out[i] : out; }, toArray: function() { return out; } };
                },
                filter: function(predOrSel) {
                    if (typeof predOrSel === 'function') {
                        var keep = [];
                        for (var i = 0; i < ids.length; i++) {
                            var w = __createWrapperFromIds(docId, [ids[i]]);
                            if (predOrSel.call(w, i, w)) keep.push(ids[i]);
                        }
                        return __createWrapperFromIds(docId, keep);
                    }
                    if (typeof predOrSel === 'string') {
                        var keep2 = [];
                        for (var i = 0; i < ids.length; i++) {
                            if (__cheerio_matches(docId, ids[i], predOrSel)) keep2.push(ids[i]);
                        }
                        return __createWrapperFromIds(docId, keep2);
                    }
                    return wrapper;
                },
                children: function(sel) { return this.find(sel || '*'); },
                parent: function() {
                    var pids = [];
                    for (var i = 0; i < ids.length; i++) {
                        var p = __cheerio_parent(docId, ids[i]);
                        if (p && p !== '__NONE__') pids.push(p);
                    }
                    return __createWrapperFromIds(docId, pids);
                },
                parents: function(sel) {
                    var pids = [], seen = {};
                    for (var i = 0; i < ids.length; i++) {
                        var p = __cheerio_parent(docId, ids[i]);
                        while (p && p !== '__NONE__') {
                            if (seen[p]) break;
                            seen[p] = true;
                            var el = __createWrapperFromIds(docId, [p]);
                            if (!sel || el.is(sel)) pids.push(p);
                            p = __cheerio_parent(docId, p);
                        }
                    }
                    return __createWrapperFromIds(docId, pids);
                },
                closest: function(sel) {
                    var cids = [];
                    for (var i = 0; i < ids.length; i++) {
                        var c = __cheerio_closest(docId, ids[i], sel);
                        if (c && c !== '__NONE__') cids.push(c);
                    }
                    return __createWrapperFromIds(docId, cids);
                },
                is: function(sel) {
                    if (!ids.length) return false;
                    if (typeof sel === 'string') return !!__cheerio_matches(docId, ids[0], sel);
                    return false;
                },
                hasClass: function(cls) {
                    if (!ids.length) return false;
                    var v = __cheerio_attr(docId, ids[0], 'class');
                    if (v === '__UNDEFINED__') return false;
                    return (' ' + v + ' ').indexOf(' ' + cls + ' ') >= 0;
                },
                removeClass: function(cls) { return wrapper; },
                addClass: function(cls) { return wrapper; },
                data: function(key) {
                    if (!ids.length) return undefined;
                    var v = __cheerio_attr(docId, ids[0], 'data-' + key);
                    return v === '__UNDEFINED__' ? undefined : v;
                },
                outerHtml: function() { return ids.length ? __cheerio_html(docId, ids[0]) : ''; },
                siblings: function(sel) {
                    var sids = [];
                    for (var i = 0; i < ids.length; i++) {
                        var sub = JSON.parse(__cheerio_siblings(docId, ids[i], sel || ''));
                        for (var j = 0; j < sub.length; j++) sids.push(sub[j]);
                    }
                    return __createWrapperFromIds(docId, sids);
                },
                toArray: function() { return ids.map(function(id) { return __createWrapperFromIds(docId, [id]); }); }
            };
            return wrapper;
        }
        var cheerio = {
            load: function(html) {
                var docId = __cheerio_load(html);
                var ${'$'} = function(sel, ctx) {
                    if (sel && sel._elementIds) return sel;
                    if (ctx && ctx._elementIds && ctx._elementIds.length > 0) {
                        var all = [];
                        for (var i = 0; i < ctx._elementIds.length; i++) {
                            var sub = JSON.parse(__cheerio_find(docId, ctx._elementIds[i], sel));
                            for (var j = 0; j < sub.length; j++) all.push(sub[j]);
                        }
                        return __createWrapperFromIds(docId, all);
                    }
                    if (typeof sel === 'string') {
                        var idsJson = __cheerio_select(docId, sel);
                        return __createWrapperFromIds(docId, JSON.parse(idsJson));
                    }
                    return __createWrapperFromIds(docId, []);
                };
                ${'$'}.html = function(el) {
                    if (el && el._elementIds && el._elementIds.length) {
                        return __cheerio_html(docId, el._elementIds[0]);
                    }
                    return __cheerio_html(docId, '');
                };
                return ${'$'};
            }
        };
        globalThis.cheerio = cheerio;

        // ── CryptoJS shim ──────────────────────────────────────────────
        function __hexWrap(hex) {
            var lo = (hex || '').toLowerCase();
            return {
                __hex: lo, sigBytes: lo.length / 2,
                toString: function(enc) {
                    if (!enc || enc === CryptoJS.enc.Hex) return this.__hex;
                    if (enc === CryptoJS.enc.Utf8) return __crypto_hex_to_utf8(this.__hex);
                    if (enc === CryptoJS.enc.Base64) return __crypto_base64_encode(__crypto_hex_to_utf8(this.__hex));
                    return this.__hex;
                }
            };
        }
        function __normUtf8(v) {
            if (v == null) return '';
            if (typeof v === 'object' && typeof v.__hex === 'string') return __crypto_hex_to_utf8(v.__hex);
            return String(v);
        }
        var CryptoJS = {
            enc: {
                Hex:    { stringify: function(w) { return w.__hex || __crypto_utf8_to_hex(__normUtf8(w)); },
                          parse: function(s) { return __hexWrap(s); } },
                Utf8:   { stringify: function(w) { return __normUtf8(w); },
                          parse: function(s) { return __hexWrap(__crypto_utf8_to_hex(s || '')); } },
                Base64: { stringify: function(w) { return __crypto_base64_encode(__normUtf8(w)); },
                          parse: function(s) { return __hexWrap(__crypto_utf8_to_hex(__crypto_base64_decode(s || ''))); } },
            },
            MD5:    function(m) { return __hexWrap(__crypto_digest_hex('MD5', __normUtf8(m))); },
            SHA1:   function(m) { return __hexWrap(__crypto_digest_hex('SHA-1', __normUtf8(m))); },
            SHA256: function(m) { return __hexWrap(__crypto_digest_hex('SHA-256', __normUtf8(m))); },
            SHA512: function(m) { return __hexWrap(__crypto_digest_hex('SHA-512', __normUtf8(m))); },
            HmacMD5:    function(m, k) { return __hexWrap(__crypto_hmac_hex('MD5', __normUtf8(k), __normUtf8(m))); },
            HmacSHA1:   function(m, k) { return __hexWrap(__crypto_hmac_hex('SHA-1', __normUtf8(k), __normUtf8(m))); },
            HmacSHA256: function(m, k) { return __hexWrap(__crypto_hmac_hex('SHA-256', __normUtf8(k), __normUtf8(m))); },
            HmacSHA512: function(m, k) { return __hexWrap(__crypto_hmac_hex('SHA-512', __normUtf8(k), __normUtf8(m))); },
        };
        // Mode and padding stubs (objects only — our AES impl ignores mode/pad args)
        CryptoJS.mode = { CBC: {name:'CBC'}, ECB: {name:'ECB'}, CTR: {name:'CTR'}, OFB: {name:'OFB'}, CFB: {name:'CFB'} };
        CryptoJS.pad  = { Pkcs7: {}, ZeroPadding: {}, NoPadding: {}, AnsiX923: {}, Iso10126: {}, Iso97971: {} };
        // WordArray / lib stubs
        CryptoJS.lib  = {
            WordArray: {
                create: function(words, sigBytes) {
                    var hex = '';
                    if (words) {
                        for (var i = 0; i < words.length; i++) hex += ('00000000' + ((words[i] >>> 0).toString(16))).slice(-8);
                        if (sigBytes !== undefined) hex = hex.substring(0, sigBytes * 2);
                    }
                    return __hexWrap(hex);
                },
                random: function(nBytes) {
                    var hex = '';
                    for (var i = 0; i < nBytes; i++) hex += ('00' + Math.floor(Math.random()*256).toString(16)).slice(-2);
                    return __hexWrap(hex);
                }
            }
        };
        // Helper: extract hex string from a CryptoJS key argument (string or WordArray)
        function __cjsKeyToHex(key) {
            if (!key) return '';
            if (typeof key === 'string') {
                // CryptoJS EVP_BytesToKey approximation: MD5 hash of passphrase
                return __crypto_digest_hex('MD5', key);
            }
            if (key.__hex) return key.__hex;
            if (key.words) {
                var h = '';
                for (var i = 0; i < key.words.length; i++) h += ('00000000' + (key.words[i] >>> 0).toString(16)).slice(-8);
                return key.sigBytes != null ? h.substring(0, key.sigBytes * 2) : h;
            }
            return __normUtf8(key) ? __crypto_utf8_to_hex(__normUtf8(key)) : '';
        }
        // Helper: extract hex from ciphertext argument (base64 string, CipherParams, or WordArray)
        function __cjsCipherToHex(ct) {
            if (!ct) return '';
            if (typeof ct === 'string') return __crypto_base64_to_hex(ct);
            if (ct.ciphertext) return ct.ciphertext.__hex || '';
            if (ct.__hex) return ct.__hex;
            if (ct.words) {
                var h = '';
                for (var i = 0; i < ct.words.length; i++) h += ('00000000' + (ct.words[i] >>> 0).toString(16)).slice(-8);
                return ct.sigBytes != null ? h.substring(0, ct.sigBytes * 2) : h;
            }
            return '';
        }
        // Helper: extract IV hex
        function __cjsIvToHex(opts) {
            if (!opts || !opts.iv) return '';
            var iv = opts.iv;
            if (iv.__hex) return iv.__hex;
            if (typeof iv === 'string') return __crypto_utf8_to_hex(iv);
            if (iv.words) {
                var h = '';
                for (var i = 0; i < iv.words.length; i++) h += ('00000000' + (iv.words[i] >>> 0).toString(16)).slice(-8);
                return iv.sigBytes != null ? h.substring(0, iv.sigBytes * 2) : h;
            }
            return '';
        }
        // CryptoJS.AES — real decryption via __crypto_aes_decrypt native (AES-CBC/CTR/GCM)
        CryptoJS.AES = {
            encrypt: function(message, key, opts) {
                return { toString: function() { return ''; }, ciphertext: __hexWrap('') };
            },
            decrypt: function(ciphertext, key, opts) {
                opts = opts || {};
                var modeName = (opts.mode && opts.mode.name) || 'CBC';
                var alg = 'AES-' + modeName;
                var keyHex = __cjsKeyToHex(key);
                var ivHex  = __cjsIvToHex(opts);
                var dataHex = __cjsCipherToHex(ciphertext);
                var resultHex = __crypto_aes_decrypt(alg, keyHex, ivHex, dataHex);
                return __hexWrap(resultHex);
            },
        };
        // CryptoJS.TripleDES — real decryption via __crypto_3des_decrypt native
        CryptoJS.TripleDES = {
            encrypt: function(message, key, opts) {
                return { toString: function() { return ''; }, ciphertext: __hexWrap('') };
            },
            decrypt: function(ciphertext, key, opts) {
                opts = opts || {};
                var modeName = (opts.mode && opts.mode.name) || 'CBC';
                var alg = 'DESede/' + modeName + '/PKCS5Padding';
                var keyHex  = __cjsKeyToHex(key);
                var ivHex   = __cjsIvToHex(opts);
                var dataHex = __cjsCipherToHex(ciphertext);
                var resultHex = __crypto_3des_decrypt(alg, keyHex, ivHex, dataHex);
                return __hexWrap(resultHex);
            },
        };
        // CryptoJS.RC4 stub
        CryptoJS.RC4  = { encrypt: function() { return { toString: function() { return ''; } }; }, decrypt: function() { return __hexWrap(''); } };
        // CryptoJS.PBKDF2 stub
        CryptoJS.PBKDF2 = function(password, salt, cfg) { return __hexWrap(__crypto_digest_hex('SHA-256', __normUtf8(password) + __normUtf8(salt))); };
        globalThis.CryptoJS = CryptoJS;

        // Full-featured axios / ky / got shim.
        // Supports: create()/extend() with baseURL/prefixUrl, default headers,
        // params/searchParams query building, json request option, and
        // .json()/.text() response chaining (ky/got style).
        var __axiosShim = (function() {
            function _resolveUrl(base, url) {
                if (!url) return url || '';
                if (/^https?:\/\//i.test(url)) return url;
                if (!base) return url;
                return base.replace(/\/$/, '') + (url.charAt(0) === '/' ? url : '/' + url);
            }
            function _mergeHdrs(base, extra) {
                var b = Object.assign({}, base || {});
                if (b.common) { Object.assign(b, b.common); delete b.common; }
                return Object.assign(b, extra || {});
            }
            function doRequest(cfg) {
                var url = cfg.url || '';
                var method = (cfg.method || 'GET').toUpperCase();
                var headers = _mergeHdrs(cfg.headers);
                var body = '';
                if (cfg.json != null) {
                    body = JSON.stringify(cfg.json);
                    if (!headers['content-type'] && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
                } else if (cfg.data != null) {
                    body = typeof cfg.data === 'string' ? cfg.data : JSON.stringify(cfg.data);
                }
                var qp = cfg.params || cfg.searchParams;
                if (qp) {
                    var qs;
                    if (typeof qp === 'string') qs = qp.replace(/^\?/, '');
                    else qs = Object.keys(qp).map(function(k) { return encodeURIComponent(k) + '=' + encodeURIComponent(qp[k]); }).join('&');
                    if (qs) url += (url.indexOf('?') >= 0 ? '&' : '?') + qs;
                }
                var p = fetch(url, { method: method, headers: headers, body: body }).then(function(r) {
                    return r.text().then(function(text) {
                        var data;
                        try { data = JSON.parse(text); } catch(e) { data = text; }
                        return { data: data, _body: text, status: r.status, statusText: r.statusText,
                                 headers: r.headers, config: cfg, request: {}, ok: r.ok };
                    });
                });
                p.json = function() { return p.then(function(r) { return r.data; }); };
                p.text = function() { return p.then(function(r) { return r._body; }); };
                p.buffer = function() { return p.then(function(r) { return r._body; }); };
                return p;
            }
            function makeInstance(defaults) {
                defaults = defaults || {};
                var bURL = (defaults.baseURL || defaults.prefixUrl || '').replace(/\/$/, '');
                var bHdrs = defaults.headers || {};
                function resolve(url) { return _resolveUrl(bURL, url); }
                function hdrs(extra) { return _mergeHdrs(Object.assign({}, bHdrs, extra || {})); }
                var inst = function(cfg) {
                    var c = typeof cfg === 'string' ? { url: cfg } : (cfg || {});
                    return doRequest(Object.assign({}, defaults, c, { url: resolve(c.url || ''), headers: hdrs(c.headers) }));
                };
                inst.get    = function(url, cfg) { return doRequest(Object.assign({}, defaults, cfg||{}, { url: resolve(url), method: 'GET',    headers: hdrs((cfg||{}).headers) })); };
                inst.post   = function(url, d, cfg) { return doRequest(Object.assign({}, defaults, cfg||{}, { url: resolve(url), method: 'POST',   data: d, headers: hdrs((cfg||{}).headers) })); };
                inst.put    = function(url, d, cfg) { return doRequest(Object.assign({}, defaults, cfg||{}, { url: resolve(url), method: 'PUT',    data: d, headers: hdrs((cfg||{}).headers) })); };
                inst.patch  = function(url, d, cfg) { return doRequest(Object.assign({}, defaults, cfg||{}, { url: resolve(url), method: 'PATCH',  data: d, headers: hdrs((cfg||{}).headers) })); };
                inst.delete = function(url, cfg)    { return doRequest(Object.assign({}, defaults, cfg||{}, { url: resolve(url), method: 'DELETE', headers: hdrs((cfg||{}).headers) })); };
                inst.head   = function(url, cfg)    { return doRequest(Object.assign({}, defaults, cfg||{}, { url: resolve(url), method: 'HEAD',   headers: hdrs((cfg||{}).headers) })); };
                inst.create = function(d2) { return makeInstance(Object.assign({}, defaults, d2 || {})); };
                inst.extend = function(d2) { return makeInstance(Object.assign({}, defaults, d2 || {})); };
                inst.defaults = { headers: Object.assign({ common: {} }, bHdrs), baseURL: bURL };
                inst.interceptors = { request: { use: function() {}, eject: function() {} }, response: { use: function() {}, eject: function() {} } };
                return inst;
            }
            return makeInstance({});
        })();
        globalThis.axios = __axiosShim;

        // ESBuild __async passes null as argsArray to generator.apply(ctx, null).
        // Spec says null == no-args; patch for QuickJS safety.
        (function() {
            var _origApply = Function.prototype.apply;
            Function.prototype.apply = function(thisArg, args) {
                return _origApply.call(this, thisArg, args == null ? [] : args);
            };
        })();
        var __module_cache__ = {};
        var require = function(name) {
            if (name === 'cheerio' || name === 'cheerio-without-node-native' || name === 'react-native-cheerio') return cheerio;
            if (name === 'crypto-js' || name === 'crypto-js/core') return CryptoJS;
            if (name === 'axios') return __axiosShim;
            if (name === 'node-fetch' || name === 'cross-fetch' || name === 'isomorphic-fetch') return fetch;
            // got — supports extend({prefixUrl}) and .json()/.text() response chaining.
            if (name === 'got' || name === 'got/dist/source' || name === '@sindresorhus/got') {
                function makeGot(defs) {
                    defs = defs || {};
                    var base = (defs.prefixUrl || defs.baseURL || '').replace(/\/$/, '');
                    var dHdrs = defs.headers || {};
                    function resolveGot(url) {
                        if (!url) return url || '';
                        if (/^https?:\/\//i.test(url)) return url;
                        return base ? base + (url.charAt(0) === '/' ? url : '/' + url) : url;
                    }
                    var g = function(url, opts) {
                        opts = opts || {};
                        var fullUrl = resolveGot(url);
                        var hdr = Object.assign({}, dHdrs, opts.headers || {});
                        var params = opts.searchParams;
                        if (params) {
                            var qs = Object.keys(params).map(function(k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); }).join('&');
                            if (qs) fullUrl += (fullUrl.indexOf('?') >= 0 ? '&' : '?') + qs;
                        }
                        var p = fetch(fullUrl, Object.assign({}, opts, { headers: hdr }));
                        return {
                            json: function() { return p.then(function(r) { return r.json(); }); },
                            text: function() { return p.then(function(r) { return r.text(); }); },
                            then: function(res, rej) { return p.then(res, rej); },
                            catch: function(rej) { return p.catch(rej); },
                        };
                    };
                    ['get','post','put','patch','delete','head'].forEach(function(m) {
                        g[m] = function(url, opts) { return g(url, Object.assign({}, opts || {}, { method: m.toUpperCase() })); };
                    });
                    g.extend = function(d2) { return makeGot(Object.assign({}, defs, d2 || {})); };
                    g.create = function(d2) { return makeGot(Object.assign({}, defs, d2 || {})); };
                    return g;
                }
                return makeGot({});
            }
            // ky — primary API is ky.get(url, {searchParams, json}).json()
            if (name === 'ky' || name === 'ky-universal') {
                function makeKy(defs) {
                    defs = defs || {};
                    var base = (defs.prefixUrl || defs.baseURL || '').replace(/\/$/, '');
                    var dHdrs = defs.headers || {};
                    function resolveKy(url) {
                        if (!url) return url || '';
                        if (/^https?:\/\//i.test(url)) return url;
                        return base ? base + (url.charAt(0) === '/' ? url : '/' + url) : url;
                    }
                    function kyReq(url, opts) {
                        opts = opts || {};
                        var fullUrl = resolveKy(url);
                        var hdr = Object.assign({}, dHdrs, opts.headers || {});
                        var params = opts.searchParams;
                        if (params) {
                            var qs = Object.keys(params).map(function(k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); }).join('&');
                            if (qs) fullUrl += (fullUrl.indexOf('?') >= 0 ? '&' : '?') + qs;
                        }
                        var body = '';
                        if (opts.json != null) {
                            body = JSON.stringify(opts.json);
                            if (!hdr['content-type'] && !hdr['Content-Type']) hdr['Content-Type'] = 'application/json';
                        } else if (opts.body != null) { body = opts.body; }
                        var p = fetch(fullUrl, { method: (opts.method || 'GET').toUpperCase(), headers: hdr, body: body });
                        return {
                            json: function() { return p.then(function(r) { return r.json(); }); },
                            text: function() { return p.then(function(r) { return r.text(); }); },
                            arrayBuffer: function() { return p.then(function(r) { return r.arrayBuffer ? r.arrayBuffer() : r.text(); }); },
                            then: function(res, rej) { return p.then(res, rej); },
                            catch: function(rej) { return p.catch(rej); },
                        };
                    }
                    ['get','post','put','patch','delete','head'].forEach(function(m) {
                        kyReq[m] = function(url, opts) { return kyReq(url, Object.assign({}, opts || {}, { method: m.toUpperCase() })); };
                    });
                    kyReq.extend = function(d2) { return makeKy(Object.assign({}, defs, d2 || {})); };
                    kyReq.create = function(d2) { return makeKy(Object.assign({}, defs, d2 || {})); };
                    return kyReq;
                }
                return makeKy({});
            }
            if (name === 'superagent' || name === 'request' || name === 'needle') return __axiosShim;
            // Relative path (e.g. "../guardahd/index") — attempt to load the sibling
            // provider module from disk via the Kotlin __native_load_module bridge.
            // This fixes cross-provider dependencies like VixSrc → guardahd.
            if (name.charAt(0) === '.' && typeof __native_load_module === 'function') {
                if (__module_cache__[name] !== undefined) return __module_cache__[name];
                var __src = __native_load_module(name);
                if (__src) {
                    var __mod = { exports: {} };
                    try {
                        (new Function('module', 'exports', 'require', __src))(__mod, __mod.exports, require);
                    } catch(__modErr) {
                        console.warn('Nuvio runtime: error evaluating module "' + name + '": ' + (__modErr.message || __modErr));
                    }
                    __module_cache__[name] = __mod.exports;
                    return __mod.exports;
                }
            }
            // Unknown module — return a stub instead of throwing so the provider
            // script does not crash during initialisation.
            console.warn('Nuvio runtime: require("' + name + '") is not available, returning empty stub.');
            return {};
        };
        globalThis.require = require;

        // Promise.allSettled polyfill (ES2020 — QuickJS may not have it).
        if (typeof Promise.allSettled === 'undefined') {
            Promise.allSettled = function(promises) {
                return Promise.all(promises.map(function(p) {
                    return Promise.resolve(p).then(
                        function(v) { return { status: 'fulfilled', value: v }; },
                        function(r) { return { status: 'rejected',  reason: r }; }
                    );
                }));
            };
        }
        // Promise.any polyfill
        if (typeof Promise.any === 'undefined') {
            Promise.any = function(promises) {
                return new Promise(function(resolve, reject) {
                    var errors = [], n = promises.length;
                    if (!n) { reject(new Error('All promises were rejected')); return; }
                    promises.forEach(function(p, i) {
                        Promise.resolve(p).then(resolve, function(e) {
                            errors[i] = e;
                            if (--n === 0) reject(new Error('All promises were rejected'));
                        });
                    });
                });
            };
        }

        if (!Array.prototype.flat) {
            Array.prototype.flat = function(d) {
                d = d === undefined ? 1 : Math.floor(d);
                if (d < 1) return Array.prototype.slice.call(this);
                return (function flatten(a, dd) {
                    return dd > 0 ? a.reduce(function(acc, v) { return acc.concat(Array.isArray(v) ? flatten(v, dd - 1) : v); }, []) : a.slice();
                })(this, d);
            };
        }
        if (!Array.prototype.flatMap) {
            Array.prototype.flatMap = function(cb, thisArg) { return this.map(cb, thisArg).flat(); };
        }
        if (!Object.entries) {
            Object.entries = function(o) {
                var r = []; for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) r.push([k, o[k]]); return r;
            };
        }
        if (!Object.fromEntries) {
            Object.fromEntries = function(es) {
                var r = {}; for (var i = 0; i < es.length; i++) r[es[i][0]] = es[i][1]; return r;
            };
        }
        if (!String.prototype.replaceAll) {
            String.prototype.replaceAll = function(s, r) {
                if (s instanceof RegExp) { if (!s.global) throw new TypeError('replaceAll needs a global RegExp'); return this.replace(s, r); }
                return this.split(s).join(r);
            };
        }
        if (!String.prototype.trimStart) {
            String.prototype.trimStart = function() { return this.replace(/^\s+/, ''); };
            String.prototype.trimEnd   = function() { return this.replace(/\s+${'$'}/, ''); };
        }
        if (!String.prototype.at) {
            String.prototype.at = function(i) { var n = i >= 0 ? i : this.length + i; return this[n]; };
        }
        if (!Array.prototype.at) {
            Array.prototype.at = function(i) { var n = i >= 0 ? i : this.length + i; return this[n]; };
        }
        if (typeof Array.from === 'undefined') {
            Array.from = function(iter, mapFn) {
                var arr = [];
                if (iter == null) return arr;
                if (typeof iter[Symbol.iterator] === 'function') {
                    var it = iter[Symbol.iterator]();
                    var step;
                    while (!(step = it.next()).done) arr.push(mapFn ? mapFn(step.value) : step.value);
                } else if (typeof iter.length === 'number') {
                    for (var i = 0; i < iter.length; i++) arr.push(mapFn ? mapFn(iter[i]) : iter[i]);
                }
                return arr;
            };
        }
        if (typeof Object.assign === 'undefined') {
            Object.assign = function(target) {
                for (var i = 1; i < arguments.length; i++) {
                    var src = arguments[i];
                    if (src) for (var k in src) if (Object.prototype.hasOwnProperty.call(src, k)) target[k] = src[k];
                }
                return target;
            };
        }
        if (typeof Object.values === 'undefined') {
            Object.values = function(o) { return Object.keys(o).map(function(k) { return o[k]; }); };
        }
        // queueMicrotask — run the callback as a resolved Promise (next microtask tick).
        if (typeof queueMicrotask === 'undefined') {
            globalThis.queueMicrotask = function(fn) { Promise.resolve().then(fn); };
        }
        // structuredClone — deep copy via JSON round-trip (good enough for provider data).
        if (typeof structuredClone === 'undefined') {
            globalThis.structuredClone = function(v) {
                try { return JSON.parse(JSON.stringify(v)); } catch(e) { return v; }
            };
        }
        // String.prototype.matchAll polyfill (ES2020)
        if (!String.prototype.matchAll) {
            String.prototype.matchAll = function(re) {
                var str = this, flags = re.flags;
                if (flags.indexOf('g') === -1) re = new RegExp(re.source, flags + 'g');
                var results = [], m;
                re.lastIndex = 0;
                while ((m = re.exec(str)) !== null) results.push(m);
                var i = 0;
                var iter = { next: function() { return i < results.length ? { value: results[i++], done: false } : { done: true }; } };
                try { iter[Symbol.iterator] = function() { return iter; }; } catch(e) {}
                return iter;
            };
        }
        // (Promise.allSettled / Promise.any / Array.flat / Object.entries — defined above)
    """.trimIndent()



    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun parseHeaders(headersJson: String): Map<String, String> = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(headersJson) as?
            kotlinx.serialization.json.JsonObject ?: return@runCatching emptyMap()
        obj.mapValues { (_, v) ->
            (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        }
    }.getOrDefault(emptyMap())

    private fun List<String>.toJsonStringArray(): String =
        "[" + joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" } + "]"


    private class JsonBuilder {
        private val sb = StringBuilder("{")
        private var first = true
        fun put(k: String, v: Boolean) = append(k, if (v) "true" else "false")
        fun put(k: String, v: Int) = append(k, v.toString())
        fun put(k: String, v: String) = append(k, jsString(v))
        fun put(k: String, v: Map<String, String>) =
            append(k, "{" + v.entries.joinToString(",") { jsString(it.key) + ":" + jsString(it.value) } + "}")
        private fun append(k: String, raw: String) {
            if (!first) sb.append(',')
            first = false
            sb.append(jsString(k)).append(':').append(raw)
        }
        fun build(): String { sb.append('}'); return sb.toString() }
    }
    private fun buildJson(block: JsonBuilder.() -> Unit): String = JsonBuilder().also(block).build()


    private fun String.looksLikeUrl(): Boolean {
        if (isBlank()) return false
        val lower = trimStart()

        if (!lower.startsWith("http", ignoreCase = true) &&
            !lower.startsWith("magnet:", ignoreCase = true) &&
            !lower.startsWith("blob:", ignoreCase = true) &&
            !lower.startsWith("data:", ignoreCase = true)) return false

        val sentinel = lower.lowercase()
        return sentinel != "undefined" && sentinel != "null" && sentinel != "none"
    }

    private fun parseSimpleUrl(value: String): NuvioStream? {
        val url = value.takeIf { it.looksLikeUrl() } ?: return null
        return NuvioStream(url = url)
    }

    private fun parseTorrentObject(
        obj: kotlinx.serialization.json.JsonObject,
        prim: (kotlinx.serialization.json.JsonObject, Array<out String>) -> String?,
    ): NuvioStream? {
        val infoHash = prim(obj, arrayOf("infoHash", "info_hash", "infohash"))
            ?.trim()
            ?.takeIf { it.length >= 20 }
            ?: return null

        val trackers = (obj["sources"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { source ->
                (source as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content
                    ?.trim()
                    ?.removePrefix("tracker:")
                    ?.takeIf { it.isNotBlank() }
            }
            .orEmpty()

        val magnetUrl = buildString {
            append("magnet:?xt=urn:btih:$infoHash")
            trackers.forEach { tracker ->
                append("&tr=").append(java.net.URLEncoder.encode(tracker, "UTF-8"))
            }
            prim(obj, arrayOf("name", "title", "label"))
                ?.substringBefore('\n')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { displayName ->
                    append("&dn=").append(java.net.URLEncoder.encode(displayName, "UTF-8"))
                }
        }

        return NuvioStream(
            name = prim(obj, arrayOf("name", "label", "provider")),
            title = prim(obj, arrayOf("title")),
            url = magnetUrl,
            quality = prim(obj, arrayOf("quality", "resolution", "q", "format", "res")),
        )
    }

    private fun extractNestedStreams(
        element: kotlinx.serialization.json.JsonElement,
        depth: Int = 0,
    ): List<kotlinx.serialization.json.JsonElement> {
        if (depth > 6) return emptyList()
        return when (element) {
            is kotlinx.serialization.json.JsonArray -> element.toList()
            is kotlinx.serialization.json.JsonObject -> {
                val nestedKeys = listOf("streams", "data", "results", "items")
                nestedKeys.firstNotNullOfOrNull { key ->
                    element[key]?.let { nested ->
                        extractNestedStreams(nested, depth + 1).takeIf { it.isNotEmpty() }
                    }
                }.orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun parseStreamObject(
        obj: kotlinx.serialization.json.JsonObject,
        prim: (kotlinx.serialization.json.JsonObject, Array<out String>) -> String?,
    ): NuvioStream? {
        parseTorrentObject(obj, prim)?.let { return it }

        val url = prim(obj, arrayOf("url", "link", "src", "stream", "href", "stream_url", "streamUrl"))
            ?.takeIf { it.looksLikeUrl() }
            ?: return null

        val headers: Map<String, String>? = when (val h = obj["headers"]) {
            is kotlinx.serialization.json.JsonObject ->
                h.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
                    .filterKeys { it.isNotBlank() }
            is kotlinx.serialization.json.JsonArray ->
                h.filterIsInstance<kotlinx.serialization.json.JsonObject>()
                    .mapNotNull { entry ->
                        val key = (entry["name"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content
                            ?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val value = (entry["value"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                        key to value
                    }
                    .toMap()
            else -> null
        }

        return NuvioStream(
            name = prim(obj, arrayOf("name", "title", "label", "provider", "description")),
            title = prim(obj, arrayOf("title", "name")),
            url = url,
            quality = prim(obj, arrayOf("quality", "resolution", "q", "format", "res", "qualityTag", "quality_tag")),
            headers = headers,
        )
    }

    private fun parseStreams(json: String): List<NuvioStream> {
        if (json.isBlank() || json == "null") return emptyList()
        val J = kotlinx.serialization.json.Json

        fun prim(
            o: kotlinx.serialization.json.JsonObject,
            keys: Array<out String>,
        ): String? = keys.firstNotNullOfOrNull { key ->
            (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }

        val element = runCatching { J.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        val items = extractNestedStreams(element).ifEmpty {
            when (element) {
                is kotlinx.serialization.json.JsonArray -> element.toList()
                is kotlinx.serialization.json.JsonObject,
                is kotlinx.serialization.json.JsonPrimitive -> listOf(element)
                else -> emptyList()
            }
        }

        return items.mapNotNull { item ->
            when (item) {
                is kotlinx.serialization.json.JsonPrimitive -> parseSimpleUrl(item.content)
                is kotlinx.serialization.json.JsonObject ->
                    parseStreamObject(item, ::prim)
                        ?: extractNestedStreams(item, depth = 1).asSequence()
                            .mapNotNull { nested ->
                                when (nested) {
                                    is kotlinx.serialization.json.JsonPrimitive -> parseSimpleUrl(nested.content)
                                    is kotlinx.serialization.json.JsonObject -> parseStreamObject(nested, ::prim)
                                    else -> null
                                }
                            }
                            .firstOrNull()
                else -> null
            }
        }
    }
}
