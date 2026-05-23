package com.streamcloud.app.data.nuvio

import android.util.Log
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.binding.toJsObject
import com.dokar.quickjs.quickJs
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
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import kotlin.random.Random

object NuvioRuntime {
    private const val TAG = "NuvioRuntime"


    private const val MAX_FETCH_BODY_CHARS = 5 * 1024 * 1024
    private val lastErrorByScript = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()


    fun lastError(scriptKey: String): String? = lastErrorByScript[scriptKey]

    suspend fun runProvider(
        scriptText: String,
        tmdbId: String,
        // IMDB ID (tt…) — passed as params.id and as the first positional argument
        // to getStreams(), which is the contract every public Nuvio provider follows.
        imdbId: String = "",
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        scriptKey: String = "default",
    ): List<NuvioStream> {
        val documentCache = mutableMapOf<String, Document>()
        val elementCache = mutableMapOf<String, Element>()
        val idCounter = AtomicInteger()


        var capturedJson = "[]"
        return try {
            withTimeoutOrNull(60_000L) {
            quickJs(Dispatchers.IO) {
                installConsole(scriptKey)
                installFetchBridge()
                installCryptoBindings()
                installUrlBinding()
                installCheerioBindings(documentCache, elementCache, idCounter)


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
                    // id = IMDB ID if resolved, else fall back to numeric TMDB ID.
                    // Nuvio providers read params.id for the IMDB ID and params.tmdbId
                    // for the numeric TMDB ID — both must be present.
                    val resolvedId = imdbId.takeIf { it.isNotBlank() } ?: tmdbId
                    appendLine("  globalThis.params = {")
                    appendLine("    id:        ${jsString(resolvedId)},")
                    appendLine("    tmdbId:    ${jsString(tmdbId)},")
                    appendLine("    imdbId:    ${jsString(imdbId)},")
                    appendLine("    mediaType: ${jsString(mediaType)},")
                    appendLine("    season:    $seasonArg,")
                    appendLine("    episode:   $episodeArg,")
                    appendLine("    scraperId: ${jsString(scriptKey)},")
                    appendLine("    settings:  globalThis.SCRAPER_SETTINGS || {}")
                    appendLine("  };")
                    appendLine("  // ── Provider code (runs directly — no try-catch wrapper) ──────────────")
                    appendLine("  // Exactly mirrors official NuvioMobile new Function() body: provider")
                    appendLine("  // code is NOT wrapped in any inner scope, so function declarations")
                    appendLine("  // (e.g. function getStreams() {}) are hoisted into the async IIFE's")
                    appendLine("  // own scope and are visible to the 3-way lookup that follows.")
                    appendLine("  // If the provider throws at init time the QuickJsException bubbles")
                    appendLine("  // up to the Kotlin catch block and is surfaced via lastError().")
                    append(scriptText)
                    appendLine()
                    appendLine("  // ── Locate getStreams (same 3-way lookup as official app) ──")
                    appendLine("  var __fn =")
                    appendLine("    (typeof getStreams === 'function')                                    ? getStreams :")
                    appendLine("    (module.exports && typeof module.exports.getStreams === 'function')  ? module.exports.getStreams :")
                    appendLine("    (typeof globalThis.getStreams === 'function')                        ? globalThis.getStreams :")
                    appendLine("    null;")
                    appendLine("  if (typeof __fn !== 'function') {")
                    appendLine("    console.error('[provider] getStreams not found. module.exports keys:', Object.keys(module.exports || {}).join(', '));")
                    appendLine("    __capture_result('[]');")
                    appendLine("    return '[]';")
                    appendLine("  }")
                    appendLine("  try {")
                    // Pass the IMDB ID (or TMDB fallback) as the first positional
                    // argument — this is the universal Nuvio provider contract.
                    appendLine("    var arr = await __fn(${jsString(resolvedId)}, ${jsString(mediaType)}, $seasonArg, $episodeArg);")
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




                val finalJson = (directResult as? String)
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?: capturedJson
                val streams = parseStreams(finalJson)
                Log.i(TAG, "$scriptKey returned ${streams.size} stream(s)")
                if (streams.isEmpty() && !lastErrorByScript.containsKey(scriptKey)) {
                    lastErrorByScript[scriptKey] = "No streams found (provider returned empty list)"
                } else if (streams.isNotEmpty()) {
                    lastErrorByScript.remove(scriptKey)
                }
                streams
            }
            } ?: run {
                Log.w(TAG, "Provider $scriptKey timed out after 60s")
                lastErrorByScript[scriptKey] = "Timed out after 60s"
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
                        "warn" -> Log.w("$TAG/$scriptKey", msg)
                        "error" -> {
                            Log.e("$TAG/$scriptKey", msg)
                            lastErrorByScript[scriptKey] = msg
                        }
                        "debug" -> Log.d("$TAG/$scriptKey", msg)
                        else -> Log.i("$TAG/$scriptKey", msg)
                    }
                    null
                }
            }
        }
    }



    private fun com.dokar.quickjs.QuickJs.installFetchBridge() {




        asyncFunction("__native_fetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString()?.uppercase() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString().orEmpty()
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            performFetch(url, method, headersJson, body, followRedirects)
        }
    }

    private fun performFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
    ): String {
        return try {
            val headers = parseHeaders(headersJson).toMutableMap()
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
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
                val text = resp.body?.string().orEmpty().let {
                    if (it.length > MAX_FETCH_BODY_CHARS) it.substring(0, MAX_FETCH_BODY_CHARS) else it
                }
                val hdrs = resp.headers.associate { (n, v) -> n.lowercase() to v }
                buildJson {
                    put("ok", resp.isSuccessful)
                    put("status", resp.code)
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

        // ── AES encrypt / decrypt ─────────────────────────────────────────
        // args: mode (CBC/ECB/CTR), operation (encrypt/decrypt),
        //       keyHex, ivHex, dataHex, padding (PKCS5Padding/NoPadding)
        function("__crypto_aes_process") { args ->
            val mode      = args.getOrNull(0)?.toString() ?: "CBC"
            val operation = args.getOrNull(1)?.toString() ?: "decrypt"
            val keyHex    = args.getOrNull(2)?.toString() ?: ""
            val ivHex     = args.getOrNull(3)?.toString() ?: ""
            val dataHex   = args.getOrNull(4)?.toString() ?: ""
            val padding   = args.getOrNull(5)?.toString() ?: "PKCS5Padding"
            runCatching {
                val keyBytes  = normaliseAesKey(hexToBytes(keyHex))
                val dataBytes = hexToBytes(dataHex)
                if (dataBytes.isEmpty()) return@runCatching ""
                val padSpec   = if (padding.equals("NoPadding", ignoreCase = true)) "NoPadding" else "PKCS5Padding"
                val transform = when (mode.uppercase()) {
                    "ECB" -> "AES/ECB/$padSpec"
                    "CTR" -> "AES/CTR/NoPadding"
                    else  -> "AES/CBC/$padSpec"
                }
                val cipher  = Cipher.getInstance(transform)
                val keySpec = SecretKeySpec(keyBytes, "AES")
                val opCode  = if (operation.equals("encrypt", ignoreCase = true))
                    Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
                if (mode.uppercase() == "ECB") {
                    cipher.init(opCode, keySpec)
                } else {
                    val ivBytes = if (ivHex.isNotBlank()) hexToBytes(ivHex).copyOf(16) else ByteArray(16)
                    cipher.init(opCode, keySpec, IvParameterSpec(ivBytes))
                }
                cipher.doFinal(dataBytes).joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }

        // OpenSSL EVP_BytesToKey — MD5-based key + IV derivation used by
        // CryptoJS when the caller passes a passphrase string (not a WordArray).
        // Returns "keyHex:ivHex".
        function("__crypto_evp_bytes_to_key") { args ->
            val passphrase = args.getOrNull(0)?.toString() ?: ""
            val saltHex    = args.getOrNull(1)?.toString() ?: ""
            val keyLen     = (args.getOrNull(2) as? Number)?.toInt() ?: 32
            val ivLen      = (args.getOrNull(3) as? Number)?.toInt() ?: 16
            runCatching {
                val pass = passphrase.toByteArray(Charsets.UTF_8)
                val salt = if (saltHex.isNotBlank()) hexToBytes(saltHex) else ByteArray(0)
                val md   = java.security.MessageDigest.getInstance("MD5")
                val out  = mutableListOf<Byte>()
                var prev = ByteArray(0)
                while (out.size < keyLen + ivLen) {
                    md.reset(); md.update(prev); md.update(pass)
                    if (salt.isNotEmpty()) md.update(salt)
                    prev = md.digest()
                    out.addAll(prev.toList())
                }
                val key = out.subList(0, keyLen).toByteArray()
                val iv  = out.subList(keyLen, minOf(keyLen + ivLen, out.size)).toByteArray()
                key.joinToString("") { "%02x".format(it) } + ":" +
                    iv.joinToString("") { "%02x".format(it) }
            }.getOrDefault(":")
        }

        // Cryptographically random hex string of n bytes (for AES salt generation).
        function("__crypto_random_hex") { args ->
            val n = (args.getOrNull(0) as? Number)?.toInt() ?: 8
            ByteArray(n).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
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
            // __native_fetch is now an asyncFunction (returns a Promise); await it so
            // QuickJS processes it through the proper coroutine → Promise mechanism.
            var result = await __native_fetch(url, method, JSON.stringify(headers), body, followRedirects);
            var parsed = JSON.parse(result);
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

        // Legacy positional signature used by D3adlyRocket / phisher98 forks.
        async function fetchv2(url, headers, method, body, encodeUrl, encoding) {
            return await fetch(url, { method: method || 'GET', headers: headers || {}, body: body });
        }
        globalThis.fetchv2 = fetchv2;

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
                if (!ms || ms <= 0) {
                    // Zero / no delay — yield to next microtask tick.
                    __pendingTimers[id] = fn;
                    Promise.resolve().then(function() {
                        var f = __pendingTimers[id];
                        if (f) { delete __pendingTimers[id]; try { f(); } catch(e) {} }
                    });
                } else {
                    // Non-zero delay — store without firing.  clearTimeout() removes
                    // it; if never cleared it is simply never called (the QuickJS
                    // coroutine loop has no real timer mechanism).
                    __pendingTimers[id] = fn;
                }
                return id;
            };
            globalThis.clearTimeout  = function(id) { if (id) delete __pendingTimers[id]; };
            globalThis.setInterval   = function(fn, ms) { return ++__timerSeq; };
            globalThis.clearInterval = function(id) { if (id) delete __pendingTimers[id]; };
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
                    digest: function(algo, data) {
                        // Return real digest for providers that use crypto.subtle.digest()
                        var algoName = (typeof algo === 'string') ? algo : (algo && algo.name) || 'SHA-256';
                        var hex = '';
                        try {
                            if (data instanceof Uint8Array) {
                                var s = '';
                                for (var _i = 0; _i < data.length; _i++) s += String.fromCharCode(data[_i]);
                                hex = __crypto_digest_hex(algoName, s);
                            } else {
                                hex = __crypto_digest_hex(algoName, (typeof data === 'string') ? data : '');
                            }
                        } catch(e) {}
                        // Return ArrayBuffer from hex
                        var ab = new ArrayBuffer(hex.length / 2);
                        var view = new Uint8Array(ab);
                        for (var _j = 0; _j < hex.length; _j += 2) view[_j/2] = parseInt(hex.substring(_j, _j+2), 16);
                        return Promise.resolve(ab);
                    },
                    importKey: function(fmt, keyData, algo, extractable, uses) { return Promise.resolve({ _keyData: keyData, _algo: algo }); },
                    sign: function(algo, key, data) { return Promise.resolve(new ArrayBuffer(32)); },
                    verify: function() { return Promise.resolve(true); },
                    encrypt: function(algo, key, data) {
                        // AES-CBC encrypt via native bridge
                        try {
                            var algoName = (typeof algo === 'string') ? algo : (algo && algo.name) || '';
                            if (algoName.indexOf('AES') >= 0) {
                                var keyHex  = key._keyData ? Array.prototype.map.call(key._keyData, function(b) { return ('00'+b.toString(16)).slice(-2); }).join('') : '';
                                var ivHex   = (algo.iv) ? Array.prototype.map.call(algo.iv, function(b) { return ('00'+b.toString(16)).slice(-2); }).join('') : '';
                                var dataHex = Array.prototype.map.call(data instanceof Uint8Array ? data : new Uint8Array(data||[]), function(b) { return ('00'+b.toString(16)).slice(-2); }).join('');
                                var res = __crypto_aes_process('CBC', 'encrypt', keyHex, ivHex, dataHex, 'PKCS5Padding');
                                var ab2 = new ArrayBuffer(res.length / 2);
                                var v2 = new Uint8Array(ab2);
                                for (var _k = 0; _k < res.length; _k += 2) v2[_k/2] = parseInt(res.substring(_k, _k+2), 16);
                                return Promise.resolve(ab2);
                            }
                        } catch(e) {}
                        return Promise.resolve(new ArrayBuffer(0));
                    },
                    decrypt: function(algo, key, data) {
                        // AES-CBC decrypt via native bridge
                        try {
                            var algoName = (typeof algo === 'string') ? algo : (algo && algo.name) || '';
                            if (algoName.indexOf('AES') >= 0) {
                                var keyHex  = key._keyData ? Array.prototype.map.call(key._keyData, function(b) { return ('00'+b.toString(16)).slice(-2); }).join('') : '';
                                var ivHex   = (algo.iv) ? Array.prototype.map.call(algo.iv, function(b) { return ('00'+b.toString(16)).slice(-2); }).join('') : '';
                                var dataHex = Array.prototype.map.call(data instanceof Uint8Array ? data : new Uint8Array(data||[]), function(b) { return ('00'+b.toString(16)).slice(-2); }).join('');
                                var res = __crypto_aes_process('CBC', 'decrypt', keyHex, ivHex, dataHex, 'PKCS5Padding');
                                var ab3 = new ArrayBuffer(res.length / 2);
                                var v3 = new Uint8Array(ab3);
                                for (var _l = 0; _l < res.length; _l += 2) v3[_l/2] = parseInt(res.substring(_l, _l+2), 16);
                                return Promise.resolve(ab3);
                            }
                        } catch(e) {}
                        return Promise.resolve(new ArrayBuffer(0));
                    },
                    deriveBits: function() { return Promise.resolve(new ArrayBuffer(32)); },
                    deriveKey: function() { return Promise.resolve({}); },
                    generateKey: function() { return Promise.resolve({}); },
                    exportKey: function() { return Promise.resolve(new ArrayBuffer(0)); },
                    wrapKey: function() { return Promise.resolve(new ArrayBuffer(0)); },
                    unwrapKey: function() { return Promise.resolve({}); },
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

            // ── CryptoJS.lib ────────────────────────────────────────────────
            lib: {
                WordArray: {
                    create: function(words, sigBytes) {
                        if (!words) return __hexWrap('');
                        if (words instanceof Uint8Array || (words && typeof words.length === 'number' && !Array.isArray(words))) {
                            var hex2 = '';
                            for (var _i = 0; _i < words.length; _i++) hex2 += ('00' + words[_i].toString(16)).slice(-2);
                            return __hexWrap(hex2);
                        }
                        if (Array.isArray(words)) {
                            var hex3 = words.map(function(w) { return ('00000000' + (w >>> 0).toString(16)).slice(-8); }).join('');
                            if (typeof sigBytes === 'number') hex3 = hex3.substring(0, sigBytes * 2);
                            return __hexWrap(hex3);
                        }
                        return __hexWrap('');
                    },
                    random: function(nBytes) { return __hexWrap(__crypto_random_hex(nBytes)); },
                },
                CipherParams: {
                    create: function(params) {
                        return { ciphertext: params.ciphertext || __hexWrap(''), iv: params.iv, salt: params.salt };
                    }
                },
            },

            // ── CryptoJS.mode / pad ─────────────────────────────────────────
            mode: { CBC: {name:'CBC'}, ECB: {name:'ECB'}, CTR: {name:'CTR'}, CFB: {name:'CBC'}, OFB: {name:'CBC'} },
            pad: { Pkcs7: 'Pkcs7', NoPadding: 'NoPadding', ZeroPadding: 'NoPadding', AnsiX923: 'Pkcs7', Iso10126: 'Pkcs7', ISO_IEC_7816_4: 'Pkcs7' },

            // ── CryptoJS.AES ────────────────────────────────────────────────
            AES: {
                _modeStr: function(opt) {
                    var m = opt && opt.mode;
                    if (!m) return 'CBC';
                    return (typeof m === 'string') ? m.toUpperCase() : ((m.name || 'CBC').toUpperCase());
                },
                _padStr: function(opt) {
                    var p = opt && opt.padding;
                    if (!p || p === 'Pkcs7' || p === 'AnsiX923' || p === 'Iso10126' || p === 'ISO_IEC_7816_4') return 'PKCS5Padding';
                    return 'NoPadding';
                },
                _ctHex: function(ct) {
                    if (typeof ct === 'string') return __crypto_base64_to_hex(ct);
                    if (ct && ct.ciphertext && ct.ciphertext.__hex) return ct.ciphertext.__hex;
                    if (ct && ct.__hex) return ct.__hex;
                    return '';
                },
                decrypt: function(ciphertext, key, options) {
                    options = options || {};
                    var modeStr = this._modeStr(options);
                    var padStr  = this._padStr(options);
                    var ctHex   = this._ctHex(ciphertext);
                    var keyHex, ivHex;
                    if (typeof key === 'string') {
                        var saltHex = '';
                        if (ctHex.substring(0, 16) === '53616c7465645f5f') {
                            saltHex = ctHex.substring(16, 32);
                            ctHex   = ctHex.substring(32);
                        }
                        var dv = __crypto_evp_bytes_to_key(key, saltHex, 32, 16).split(':');
                        keyHex = dv[0]; ivHex = dv[1] || '';
                    } else if (key && key.__hex) {
                        keyHex = key.__hex;
                        var iv = options.iv;
                        ivHex  = iv ? (iv.__hex || '') : '';
                    } else { return __hexWrap(''); }
                    var res = __crypto_aes_process(modeStr, 'decrypt', keyHex, ivHex, ctHex, padStr);
                    return __hexWrap(res);
                },
                encrypt: function(message, key, options) {
                    options = options || {};
                    var modeStr = this._modeStr(options);
                    var padStr  = this._padStr(options);
                    var msgHex  = (typeof message === 'string') ? __crypto_utf8_to_hex(message)
                                  : (message && message.__hex) ? message.__hex : '';
                    var keyHex, ivHex, saltHex = '';
                    if (typeof key === 'string') {
                        saltHex = __crypto_random_hex(8);
                        var dv = __crypto_evp_bytes_to_key(key, saltHex, 32, 16).split(':');
                        keyHex = dv[0]; ivHex = dv[1] || '';
                    } else if (key && key.__hex) {
                        keyHex = key.__hex;
                        var iv = options.iv;
                        ivHex  = iv ? (iv.__hex || '') : '';
                    } else { keyHex = ''; ivHex = ''; }
                    var res = __crypto_aes_process(modeStr, 'encrypt', keyHex, ivHex, msgHex, padStr);
                    var header = saltHex ? ('53616c7465645f5f' + saltHex) : '';
                    var full = header + res;
                    return {
                        ciphertext: __hexWrap(res),
                        toString: function(enc) {
                            var raw = '';
                            for (var _i2 = 0; _i2 < full.length; _i2 += 2)
                                raw += String.fromCharCode(parseInt(full.substring(_i2, _i2 + 2), 16));
                            if (!enc || enc === CryptoJS.enc.Base64) return __crypto_base64_encode(raw);
                            return full;
                        }
                    };
                },
            },

            // Additional common CryptoJS algorithms
            RC4:      { decrypt: function() { return __hexWrap(''); }, encrypt: function() { return { ciphertext: __hexWrap(''), toString: function() { return ''; } }; } },
            TripleDES:{ decrypt: function() { return __hexWrap(''); }, encrypt: function() { return { ciphertext: __hexWrap(''), toString: function() { return ''; } }; } },
            Rabbit:   { decrypt: function() { return __hexWrap(''); }, encrypt: function() { return { ciphertext: __hexWrap(''), toString: function() { return ''; } }; } },
        };
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

        var require = function(name) {
            if (name === 'cheerio' || name === 'cheerio-without-node-native' || name === 'react-native-cheerio') return cheerio;
            if (name === 'crypto-js' || name === 'crypto-js/core' ||
                name === 'crypto-js/aes' || name === 'crypto-js/enc-utf8' ||
                name === 'crypto-js/enc-hex' || name === 'crypto-js/enc-base64' ||
                name === 'crypto-js/md5' || name === 'crypto-js/sha1' ||
                name === 'crypto-js/sha256' || name === 'crypto-js/sha512' ||
                name === 'crypto-js/hmac-sha256' || name === 'crypto-js/hmac-sha512' ||
                name === 'crypto-js/pad-pkcs7' || name === 'crypto-js/mode-cbc' ||
                name.startsWith('crypto-js/')) return CryptoJS;
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
    """.trimIndent()



    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.replace(" ", "").lowercase()
        if (h.isEmpty()) return ByteArray(0)
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // AES key must be 16/24/32 bytes — pad or truncate to nearest valid size.
    private fun normaliseAesKey(raw: ByteArray): ByteArray = when {
        raw.size <= 16 -> raw.copyOf(16)
        raw.size <= 24 -> raw.copyOf(24)
        else           -> raw.copyOf(32)
    }

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

    private fun parseStreams(json: String): List<NuvioStream> {
        if (json.isBlank() || json == "null") return emptyList()
        val J = kotlinx.serialization.json.Json

        fun prim(o: kotlinx.serialization.json.JsonObject, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }

        val element = runCatching { J.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        val arr = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {

                is kotlinx.serialization.json.JsonPrimitive -> {
                    val u = item.content.takeIf { it.looksLikeUrl() } ?: return@mapNotNull null
                    NuvioStream(url = u)
                }
                is kotlinx.serialization.json.JsonObject -> {
                    val url = prim(item, "url", "stream_url", "streamUrl", "link", "href")
                        ?.takeIf { it.looksLikeUrl() }
                        ?: return@mapNotNull null
                    val name    = prim(item, "name", "label", "description")
                    val title   = prim(item, "title")
                    val quality = prim(item, "quality", "resolution", "res", "qualityTag", "quality_tag")
                    val headers: Map<String, String>? = when (val h = item["headers"]) {
                        is kotlinx.serialization.json.JsonObject ->
                            h.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
                                .filterKeys { it.isNotBlank() }
                        is kotlinx.serialization.json.JsonArray ->

                            h.filterIsInstance<kotlinx.serialization.json.JsonObject>()
                                .mapNotNull { entry ->
                                    val k = (entry["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                    val v = (entry["value"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                                    k to v
                                }.toMap()
                        else -> null
                    }
                    NuvioStream(name = name, title = title, url = url, quality = quality, headers = headers)
                }
                else -> null
            }
        }
    }
}
