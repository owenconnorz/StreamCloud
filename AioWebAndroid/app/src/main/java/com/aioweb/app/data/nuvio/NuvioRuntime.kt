package com.aioweb.app.data.nuvio

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

/**
 * Executes Nuvio JavaScript providers — feature-parity with [NuvioMobile]'s
 * `PluginRuntime.kt` (composeApp/.../plugins/PluginRuntime.kt). Every provider
 * from https://github.com/NuvioMedia/NuvioMobile plugin repos should run here
 * unchanged.
 *
 * Surface exposed to provider scripts:
 *   • `fetch(url, opts)` + Response wrapper with `.ok/.status/.headers.get()/.text()/.json()`
 *   • `URL` + `URLSearchParams`
 *   • `AbortController` / `AbortSignal`
 *   • `atob` / `btoa`
 *   • `cheerio` (`load(html)` → jQuery-like `$` selector backed by Jsoup)
 *   • `CryptoJS` (MD5, SHA1, SHA256, SHA512, HMAC variants, Hex/Utf8/Base64 enc)
 *   • Array.flat / flatMap, Object.entries / fromEntries, String.replaceAll polyfills
 *   • `require('cheerio'|'crypto-js')`
 *   • `console.{log,info,warn,error,debug}`
 *
 * Contract:
 *   Provider exports a `getStreams(tmdbId, mediaType, season, episode)` function
 *   (either via `module.exports.getStreams = ...` or as a top-level declaration).
 *   It must return a `Promise<Array<{url, name?, title?, quality?, headers?}>>`.
 */
object NuvioRuntime {
    private const val TAG = "NuvioRuntime"
    private const val MAX_FETCH_BODY_CHARS = 256 * 1024
    private val lastErrorByScript = mutableMapOf<String, String>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Last error message we captured during a [runProvider] call, for surfacing in UI. */
    fun lastError(scriptKey: String): String? = lastErrorByScript[scriptKey]

    suspend fun runProvider(
        scriptText: String,
        tmdbId: String,
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        scriptKey: String = "default",
    ): List<NuvioStream> {
        val documentCache = mutableMapOf<String, Document>()
        val elementCache = mutableMapOf<String, Element>()
        val idCounter = AtomicInteger()
        // Belt-and-suspenders: populated via __capture_result side-effect AND via
        // the direct return value of evaluate<Any?> (whichever arrives first wins).
        var capturedJson = "[]"
        return try {
            quickJs(Dispatchers.IO) {
                installConsole(scriptKey)
                installFetchBridge()
                installCryptoBindings()
                installUrlBinding()
                installCheerioBindings(documentCache, elementCache, idCounter)

                // Side-effect capture — called from inside the async IIFE.
                function("__capture_result") { args ->
                    capturedJson = args.firstOrNull()?.toString() ?: "[]"
                    null
                }

                // Build the user-facing JS polyfill (URL, URLSearchParams,
                // AbortController, atob/btoa, cheerio, CryptoJS, Array/Object
                // polyfills, `require()`).
                evaluate<Any?>(buildPolyfillCode(scriptKey))

                // Wrap the provider so CommonJS-style `module.exports.getStreams =
                // ...` AND ES-style `function getStreams() {}` both work.
                evaluate<Any?>(
                    """
                    var module = { exports: {} };
                    var exports = module.exports;
                    (function() {
                        $scriptText
                    })();
                    """.trimIndent(),
                )

                val seasonArg = season?.toString() ?: "undefined"
                val episodeArg = episode?.toString() ?: "undefined"

                // Async IIFE — returns the JSON string AND calls __capture_result.
                // evaluate<Any?> in com.dokar.quickjs awaits the returned Promise
                // before handing control back to Kotlin, so by the time it returns
                // both the side-effect variable and the direct return value are set.
                val directResult = evaluate<Any?>(
                    """
                    (async function() {
                        try {
                            var fn = module.exports.getStreams || globalThis.getStreams;
                            if (typeof fn !== 'function') {
                                console.error('Plugin error: getStreams() not exported.');
                                __capture_result('[]');
                                return '[]';
                            }
                            var arr = await fn(${jsString(tmdbId)}, ${jsString(mediaType)}, $seasonArg, $episodeArg);
                            var result = JSON.stringify(arr || []);
                            __capture_result(result);
                            return result;
                        } catch (e) {
                            console.error('getStreams crashed:', (e && e.message) || e, e && e.stack || '');
                            __capture_result('[]');
                            return '[]';
                        }
                    })()
                    """.trimIndent(),
                )

                // Prefer the direct return value (string from resolved Promise);
                // fall back to the side-effect capture if evaluate returned the
                // Promise object itself rather than its resolved value.
                val finalJson = (directResult as? String)
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?: capturedJson
                parseStreams(finalJson)
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

    // ────────────────────────────── Console ──────────────────────────────

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

    // ─────────────────────────────── Fetch ───────────────────────────────

    private fun com.dokar.quickjs.QuickJs.installFetchBridge() {
        function("__native_fetch") { args ->
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
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                method(method, if (body.isEmpty()) null else body.toRequestBody())
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

    // ────────────────────────────── Crypto ───────────────────────────────

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
        function("__crypto_hex_to_utf8") { args ->
            val data = args.getOrNull(0)?.toString() ?: ""
            runCatching {
                val bytes = ByteArray(data.length / 2) {
                    data.substring(it * 2, it * 2 + 2).toInt(16).toByte()
                }
                String(bytes, Charsets.UTF_8)
            }.getOrDefault("")
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

    // ─────────────────────────────── URL ─────────────────────────────────

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

    // ───────────────────────────── Cheerio ───────────────────────────────

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
    }

    // ─────────────────────────── Polyfill JS ─────────────────────────────

    private fun buildPolyfillCode(scriptKey: String): String = """
        globalThis.SCRAPER_ID = ${jsString(scriptKey)};
        if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
        if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
        if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;

        var fetch = async function(url, options) {
            options = options || {};
            var method = (options.method || 'GET').toUpperCase();
            var headers = options.headers || {};
            var body = options.body || '';
            var followRedirects = options.redirect !== 'manual';
            var result = __native_fetch(url, method, JSON.stringify(headers), body, followRedirects);
            var parsed = JSON.parse(result);
            return {
                ok: parsed.ok, status: parsed.status, statusText: parsed.statusText, url: parsed.url,
                headers: { get: function(name) {
                    return parsed.headers[String(name).toLowerCase()] || null;
                } },
                text: function() { return Promise.resolve(parsed.body); },
                json: function() {
                    try { return Promise.resolve(JSON.parse(parsed.body)); }
                    catch (e) { return Promise.resolve(null); }
                },
            };
        };
        // Legacy positional signature used by D3adlyRocket / phisher98 forks.
        async function fetchv2(url, headers, method, body, encodeUrl, encoding) {
            return await fetch(url, { method: method || 'GET', headers: headers || {}, body: body });
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

        var URL = function(urlString, base) {
            var fullUrl = urlString;
            if (base && !/^https?:\/\//i.test(urlString)) {
                var b = typeof base === 'string' ? base : base.href;
                if (urlString.charAt(0) === '/') {
                    var m = b.match(/^(https?:\/\/[^\/]+)/);
                    fullUrl = m ? m[1] + urlString : urlString;
                } else { fullUrl = b.replace(/\/[^\/]*$/, '/') + urlString; }
            }
            var data = JSON.parse(__parse_url(fullUrl));
            this.href = fullUrl;
            this.protocol = data.protocol; this.host = data.host; this.hostname = data.hostname;
            this.port = data.port; this.pathname = data.pathname; this.search = data.search;
            this.hash = data.hash; this.origin = data.protocol + '//' + data.host;
            this.searchParams = new URLSearchParams(data.search || '');
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
        URLSearchParams.prototype.get = function(k) { return this._params[k] || null; };
        URLSearchParams.prototype.set = function(k, v) { this._params[k] = String(v); };
        URLSearchParams.prototype.append = function(k, v) { this._params[k] = String(v); };
        URLSearchParams.prototype.has = function(k) { return Object.prototype.hasOwnProperty.call(this._params, k); };
        URLSearchParams.prototype.delete = function(k) { delete this._params[k]; };
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
                    return wrapper;
                },
                children: function(sel) { return this.find(sel || '*'); },
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
        globalThis.CryptoJS = CryptoJS;

        var require = function(name) {
            if (name === 'cheerio' || name === 'cheerio-without-node-native' || name === 'react-native-cheerio') return cheerio;
            if (name === 'crypto-js') return CryptoJS;
            throw new Error("Module '" + name + "' is not available");
        };
        globalThis.require = require;

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
    """.trimIndent()

    // ─────────────────────────── helpers ─────────────────────────────────

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

    /** Tiny in-Kotlin JSON builder so we don't pull a serializer for every fetch. */
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

    private fun parseStreams(json: String): List<NuvioStream> {
        if (json.isBlank() || json == "null") return emptyList()
        val element = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
        }.getOrNull() ?: return emptyList()
        val arr = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val url = (obj["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val title = (obj["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val quality = (obj["quality"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val headers = (obj["headers"] as? kotlinx.serialization.json.JsonObject)
                ?.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
            NuvioStream(name = name, title = title, url = url, quality = quality, headers = headers)
        }
    }
}
