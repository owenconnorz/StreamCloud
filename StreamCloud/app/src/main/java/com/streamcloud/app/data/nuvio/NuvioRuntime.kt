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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    private val lastErrorByScript     = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastFetchCountByScript = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lastLogByScript        = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastDiagnosticsByScript = java.util.concurrent.ConcurrentHashMap<String, NuvioProviderDiagnostics>()

    private val http = OkHttpClient.Builder()
        .cookieJar(BrowserCookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()


    fun lastError(scriptKey: String): String? = lastErrorByScript[scriptKey]
    fun lastFetchCount(scriptKey: String): Int = lastFetchCountByScript[scriptKey] ?: 0
    fun lastLog(scriptKey: String): String? = lastLogByScript[scriptKey]
    fun lastDiagnostics(scriptKey: String): NuvioProviderDiagnostics? = lastDiagnosticsByScript[scriptKey]

    suspend fun runProvider(
        scriptText: String,
        tmdbId: String,
        imdbId: String? = null,
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        scriptKey: String = "default",
        context: Context? = null,
        filePath: String = "",
        proxyBaseUrl: String? = null,
    ): List<NuvioStream> {
        val documentCache = mutableMapOf<String, Document>()
        val elementCache = mutableMapOf<String, Element>()
        val idCounter = AtomicInteger()


        lastFetchCountByScript[scriptKey] = 0
        lastErrorByScript.remove(scriptKey)
        lastLogByScript.remove(scriptKey)
        return withContext(Dispatchers.Default) {
        try {
            withTimeoutOrNull(60_000L) {
            val deferred = CompletableDeferred<String>()
            Log.d(TAG, "[$scriptKey] starting quickJs block")
            quickJs(Dispatchers.Default) {
                installConsole(scriptKey)
                installFetchBridge(context, scriptKey)
                installCryptoBindings()
                installUrlBinding()
                installCheerioBindings(documentCache, elementCache, idCounter)

                function("__capture_result") { args: Array<Any?> ->
                    val json = args.firstOrNull()?.toString() ?: "[]"
                    Log.d(TAG, "[$scriptKey] __capture_result called, len=${json.length}")
                    deferred.complete(json)
                    null
                }

                // Step 1: polyfills — matches official JsBindings.buildPolyfillCode exactly.
                Log.d(TAG, "[$scriptKey] evaluating polyfills")
                evaluate<Any?>(buildPolyfillCode(scriptKey))
                Log.d(TAG, "[$scriptKey] polyfills OK")

                // Step 2: extra per-run globals (not in official but harmless for compat).
                // Providers that read tmdbId/imdbId/mediaType as free globals find them here.
                val seasonArg    = season?.toString()  ?: "undefined"
                val episodeArg   = episode?.toString() ?: "undefined"
                val tmdbIdJson   = jsString(tmdbId)
                val imdbIdJson   = if (imdbId != null) jsString(imdbId) else "undefined"
                val mediaTypeJson = jsString(mediaType)
                evaluate<Any?>("""
                    globalThis.tmdbId    = $tmdbIdJson;
                    globalThis.imdbId    = $imdbIdJson;
                    globalThis.mediaType = $mediaTypeJson;
                    globalThis.type      = $mediaTypeJson;
                    globalThis.season    = $seasonArg;
                    globalThis.episode   = $episodeArg;
                """.trimIndent())
                Log.d(TAG, "[$scriptKey] extra globals OK")

                // Step 3: wrappedCode — EXACT copy of official PluginRuntime.kt.
                // module declaration + provider IIFE are ONE evaluate call so that
                // `module` is in scope when the IIFE runs and sets module.exports.
                val wrappedCode = """
                    var module = { exports: {} };
                    var exports = module.exports;
                    (function() {
                        $scriptText
                    })();
                """.trimIndent()
                Log.d(TAG, "[$scriptKey] evaluating wrappedCode (scriptLen=${scriptText.length})")
                evaluate<Any?>(wrappedCode)
                Log.d(TAG, "[$scriptKey] wrappedCode OK")

                // Step 4: callCode — EXACT copy of official PluginRuntime.kt.
                // Args passed by direct interpolation; always 4 positional (official API).
                val callCode = """
                    (async function() {
                        try {
                            console.log('[provider] callCode IIFE started');
                            var getStreams = module.exports.getStreams
                                || (module.exports.default && module.exports.default.getStreams)
                                || globalThis.getStreams;
                            if (!getStreams) {
                                console.error('[provider] getStreams not found on module.exports or globalThis');
                                __capture_result(JSON.stringify([]));
                                return;
                            }
                            console.log('[provider] calling getStreams');
                            var result = await getStreams($tmdbIdJson, $mediaTypeJson, $seasonArg, $episodeArg);
                            console.log('[provider] getStreams returned, result type=' + (Array.isArray(result) ? 'array[' + (result ? result.length : 0) + ']' : typeof result));
                            __capture_result(JSON.stringify(result || []));
                        } catch (e) {
                            console.error('[provider] getStreams error:', e && e.message ? e.message : String(e), e && e.stack ? e.stack : '');
                            __capture_result(JSON.stringify([]));
                        }
                    })();
                """.trimIndent()
                Log.d(TAG, "[$scriptKey] evaluating callCode")
                evaluate<Any?>(callCode)
                Log.d(TAG, "[$scriptKey] callCode evaluated, deferred.isCompleted=${deferred.isCompleted}")

                // Step 5: await inside the quickJs block — official pattern.
                // quickjs-kt drives the JS event loop while suspended here, allowing
                // the async IIFE Promises to resolve and __capture_result to fire.
                val capturedJson = deferred.await()
                Log.d(TAG, "[$scriptKey] deferred resolved, capturedLen=${capturedJson.length}")
                val streams = parseStreams(capturedJson)
                Log.i(TAG, "$scriptKey returned ${streams.size} stream(s)")
                if (streams.isEmpty() && !lastErrorByScript.containsKey(scriptKey)) {
                    lastErrorByScript[scriptKey] = "No streams found (provider returned empty list)"
                } else if (streams.isNotEmpty()) {
                    lastErrorByScript.remove(scriptKey)
                }
                lastDiagnosticsByScript[scriptKey] = NuvioProviderDiagnostics(
                    requestCount = lastFetchCountByScript[scriptKey] ?: 0,
                    errorSummary = lastErrorByScript[scriptKey],
                    exitedEarly = (lastFetchCountByScript[scriptKey] ?: 0) == 0,
                )
                streams
            }
            } ?: run {
                val reqCount = lastFetchCountByScript[scriptKey] ?: 0
                Log.w(TAG, "Provider $scriptKey timed out after 60s (reqs=$reqCount)")
                lastErrorByScript[scriptKey] = "Timed out after 60s"
                emptyList()
            }
        } catch (e: QuickJsException) {
            Log.w(TAG, "QuickJS error in $scriptKey: ${e.message}", e)
            lastErrorByScript[scriptKey] = "JS error: ${e.message?.take(200)}"
            emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "Provider $scriptKey crashed: ${e.message}", e)
            lastErrorByScript[scriptKey] = "Crashed: ${e.message?.take(200)}"
            emptyList()
        } finally {
            documentCache.clear()
            elementCache.clear()
        }
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
        // asyncFunction lets quickjs-kt drive the JS event loop while the HTTP
        // request is in flight on an IO thread, instead of blocking the scheduler
        // thread with runBlocking (which prevents pending JS microtasks from running).
        asyncFunction("__native_fetch") { args: Array<Any?> ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString()?.uppercase() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString().orEmpty()
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            Log.d(TAG, "[$scriptKey] fetch $method ${url.take(200)}")
            lastFetchCountByScript.merge(scriptKey, 1, Int::plus)
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                performFetch(url, method, headersJson, body, followRedirects, context)
            }
            // Surface HTTP-level errors (non-2xx, connection failures, etc.) in the picker UI.
            try {
                val J = kotlinx.serialization.json.Json
                val obj = J.parseToJsonElement(result) as? kotlinx.serialization.json.JsonObject
                val ok = (obj?.get("ok") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: true
                val status = (obj?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                if (!ok) {
                    val shortUrl = url.take(120)
                    if (!lastErrorByScript.containsKey(scriptKey) || lastErrorByScript[scriptKey]?.startsWith("No streams") == true) {
                        lastErrorByScript[scriptKey] = if (status == 0) "Network error reaching $shortUrl" else "HTTP $status from $shortUrl"
                    }
                }
            } catch (_: Exception) {}
            result
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
            val sanitizedUrl = sanitizeNuvioUrlScheme(url)
            val rawHeaders = parseHeaders(headersJson)
            val headers = buildNuvioRequestHeaders(sanitizedUrl, method, rawHeaders).toMutableMap()
            val client = if (followRedirects) http else http.newBuilder().followRedirects(false).build()

            val requestBody = when {
                method == "GET" || method == "HEAD" -> null
                body.isEmpty() -> ByteArray(0).toRequestBody()
                else -> body.toRequestBody()
            }
            val req = Request.Builder().url(sanitizedUrl).apply {
                headers.forEach { (k, v) -> header(k, v) }
                method(method, requestBody)
            }.build()

            client.newCall(req).execute().use { resp ->
                val respCode = resp.code
                val respBody = resp.body?.string().orEmpty().stripLeadingBom()
                val respMultimap = resp.headers.toMultimap()

                // Cloudflare challenge — try WebView bypass and retry once
                if (context != null && CloudflareKiller.isCfChallenge(respCode, respMultimap, respBody)) {
                    val ua = headers["User-Agent"] ?: BrowserHeaders.USER_AGENT
                    val bypassed = CloudflareKiller.bypass(context, url, ua, BrowserCookieJar)
                    if (bypassed) {
                        return client.newCall(req).execute().use { r2 ->
                            val text2 = r2.body?.string().orEmpty().let {
                                if (it.length > MAX_FETCH_BODY_CHARS) it.substring(0, MAX_FETCH_BODY_CHARS) else it
                            }
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

                val text = respBody.let {
                    if (it.length > MAX_FETCH_BODY_CHARS) it.substring(0, MAX_FETCH_BODY_CHARS) else it
                }
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
        // AES-CBC decrypt with explicit key+IV (hex). Returns decrypted bytes as hex string.
        function("__crypto_aes_decrypt_cbc") { args ->
            val cipherB64 = args.getOrNull(0)?.toString()?.trim() ?: return@function ""
            val keyHex    = args.getOrNull(1)?.toString() ?: return@function ""
            val ivHex     = args.getOrNull(2)?.toString() ?: ""
            runCatching {
                fun h(s: String) = ByteArray(s.length / 2) { s.substring(it*2, it*2+2).toInt(16).toByte() }
                // Normalise key to valid AES length: pad to 32 hex chars (16B), 48 (24B) or 64 (32B)
                val normKey = keyHex.let { k ->
                    val needed = when { k.length <= 32 -> 32; k.length <= 48 -> 48; else -> 64 }
                    k.padEnd(needed, '0').take(needed)
                }
                val normIv = if (ivHex.isBlank()) "0".repeat(32) else ivHex.padEnd(32, '0').take(32)
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(h(normKey), "AES"),
                    javax.crypto.spec.IvParameterSpec(h(normIv)),
                )
                cipher.doFinal(android.util.Base64.decode(cipherB64, android.util.Base64.DEFAULT))
                    .joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
        // AES-256-CBC decrypt with passphrase — CryptoJS OpenSSL EVP_BytesToKey (MD5, 1 iter).
        // Input ciphertext is base64; the first 16 bytes encode "Salted__" + 8-byte salt.
        // Returns decrypted bytes as hex string.
        function("__crypto_aes_decrypt_passphrase") { args ->
            val cipherB64  = args.getOrNull(0)?.toString()?.trim() ?: return@function ""
            val passphrase = args.getOrNull(1)?.toString() ?: return@function ""
            runCatching {
                val raw = android.util.Base64.decode(cipherB64, android.util.Base64.DEFAULT)
                val salt: ByteArray
                val encData: ByteArray
                if (raw.size >= 16 && String(raw, 0, 8, Charsets.ISO_8859_1) == "Salted__") {
                    salt = raw.sliceArray(8..15); encData = raw.sliceArray(16 until raw.size)
                } else { salt = ByteArray(8); encData = raw }
                val pass = passphrase.toByteArray(Charsets.UTF_8)
                val md   = MessageDigest.getInstance("MD5")
                val derived = ByteArray(48)
                var prev = ByteArray(0); var off = 0
                while (off < 48) {
                    md.reset(); md.update(prev); md.update(pass); md.update(salt)
                    prev = md.digest()
                    val n = minOf(prev.size, 48 - off)
                    System.arraycopy(prev, 0, derived, off, n); off += n
                }
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(derived.sliceArray(0..31), "AES"),
                    javax.crypto.spec.IvParameterSpec(derived.sliceArray(32..47)),
                )
                cipher.doFinal(encData).joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }

        // ── Official NuvioMobile bridge function names ────────────────────────────
        // These use hex-encoded bytes (not UTF-8 strings) for all data — matching
        // the official __nativeDigestBytes/__nativeAesBytes helper convention.

        // __crypto_digest_hex_raw(algo, dataHex) → hexResult
        function("__crypto_digest_hex_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val dataHex = args.getOrNull(1)?.toString() ?: ""
            runCatching {
                val md = MessageDigest.getInstance(normalizeDigestAlgo(algorithm))
                md.digest(hexToBytes(dataHex)).joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
        // __crypto_hmac_hex_raw(algo, keyHex, dataHex) → hexResult
        function("__crypto_hmac_hex_raw") { args ->
            val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
            val keyHex = args.getOrNull(1)?.toString() ?: ""
            val dataHex = args.getOrNull(2)?.toString() ?: ""
            runCatching {
                val macAlgo = "Hmac" + normalizeDigestAlgo(algorithm).replace("-", "")
                val mac = Mac.getInstance(macAlgo)
                mac.init(SecretKeySpec(hexToBytes(keyHex), macAlgo))
                mac.doFinal(hexToBytes(dataHex)).joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
        // __crypto_aes_encrypt_hex(mode, keyHex, ivHex, dataHex) → hexResult
        // mode: "AES-CBC", "AES-GCM", "AES-ECB", "AES-CBC-NoPadding", etc.
        function("__crypto_aes_encrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString()?.uppercase() ?: "AES-CBC"
            val keyHex = args.getOrNull(1)?.toString() ?: ""
            val ivHex  = args.getOrNull(2)?.toString() ?: ""
            val dataHex = args.getOrNull(3)?.toString() ?: ""
            runCatching {
                val noPad = "NOPADDING" in mode
                val transformation = when {
                    "GCM" in mode -> "AES/GCM/NoPadding"
                    "ECB" in mode -> if (noPad) "AES/ECB/NoPadding" else "AES/ECB/PKCS5Padding"
                    noPad -> "AES/CBC/NoPadding"
                    else -> "AES/CBC/PKCS5Padding"
                }
                val cipher = javax.crypto.Cipher.getInstance(transformation)
                val keySpec = javax.crypto.spec.SecretKeySpec(normalizeAesKey(hexToBytes(keyHex)), "AES")
                when {
                    "GCM" in mode -> {
                        val iv = hexToBytes(ivHex).let { if (it.isEmpty()) ByteArray(12) else it }
                        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))
                    }
                    "ECB" in mode -> cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
                    else -> {
                        val iv = hexToBytes(ivHex).let { if (it.isEmpty()) ByteArray(16) else it }
                        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, javax.crypto.spec.IvParameterSpec(iv))
                    }
                }
                cipher.doFinal(hexToBytes(dataHex)).joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
        // __crypto_aes_decrypt_hex(mode, keyHex, ivHex, dataHex) → hexResult
        function("__crypto_aes_decrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString()?.uppercase() ?: "AES-CBC"
            val keyHex = args.getOrNull(1)?.toString() ?: ""
            val ivHex  = args.getOrNull(2)?.toString() ?: ""
            val dataHex = args.getOrNull(3)?.toString() ?: ""
            runCatching {
                val noPad = "NOPADDING" in mode
                val transformation = when {
                    "GCM" in mode -> "AES/GCM/NoPadding"
                    "ECB" in mode -> if (noPad) "AES/ECB/NoPadding" else "AES/ECB/PKCS5Padding"
                    noPad -> "AES/CBC/NoPadding"
                    else -> "AES/CBC/PKCS5Padding"
                }
                val cipher = javax.crypto.Cipher.getInstance(transformation)
                val keySpec = javax.crypto.spec.SecretKeySpec(normalizeAesKey(hexToBytes(keyHex)), "AES")
                when {
                    "GCM" in mode -> {
                        val iv = hexToBytes(ivHex).let { if (it.isEmpty()) ByteArray(12) else it }
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))
                    }
                    "ECB" in mode -> cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec)
                    else -> {
                        val iv = hexToBytes(ivHex).let { if (it.isEmpty()) ByteArray(16) else it }
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.IvParameterSpec(iv))
                    }
                }
                cipher.doFinal(hexToBytes(dataHex)).joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
        // __crypto_pbkdf2_hex(passwordHex, saltHex, iterations, keySizeBits, hash) → hexResult
        function("__crypto_pbkdf2_hex") { args ->
            val passwordHex = args.getOrNull(0)?.toString() ?: ""
            val saltHex     = args.getOrNull(1)?.toString() ?: ""
            val iterations  = args.getOrNull(2)?.toString()?.toDoubleOrNull()?.toInt() ?: 1000
            val keySizeBits = args.getOrNull(3)?.toString()?.toDoubleOrNull()?.toInt() ?: 256
            val hash        = args.getOrNull(4)?.toString() ?: "SHA1"
            runCatching {
                val spec = javax.crypto.spec.PBEKeySpec(
                    String(hexToBytes(passwordHex), Charsets.UTF_8).toCharArray(),
                    hexToBytes(saltHex), iterations, keySizeBits
                )
                val factory = javax.crypto.SecretKeyFactory.getInstance(
                    "PBKDF2WithHmac" + normalizeDigestAlgo(hash).replace("-", "")
                )
                factory.generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }
        // __crypto_get_random_values_hex(byteLength) → hexResult
        function("__crypto_get_random_values_hex") { args ->
            val byteLength = args.getOrNull(0)?.toString()?.toDoubleOrNull()?.toInt() ?: 16
            val bytes = ByteArray(byteLength.coerceIn(0, 65536))
            java.security.SecureRandom().nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        }
        // Stubs for RSA/ECDSA sign/verify (rarely needed by providers)
        function("__crypto_sign_hex") { _ -> "" }
        function("__crypto_verify_hex") { _ -> false }
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

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(Regex("[^0-9a-fA-F]"), "").let { if (it.length % 2 == 1) "0$it" else it }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun normalizeAesKey(key: ByteArray): ByteArray = when {
        key.size <= 16 -> key.copyOf(16)
        key.size <= 24 -> key.copyOf(24)
        else -> key.copyOf(32)
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

        // ── TypeScript runtime helpers ─────────────────────────────────────────
        // Providers compiled with tsc emit __awaiter/__generator instead of native
        // async/await.  Without these every async function throws ReferenceError
        // before making a single network request → provider returns [] with 0 req.
        if (typeof __awaiter === 'undefined') {
            globalThis.__awaiter = function(thisArg, _arguments, P, generator) {
                function adopt(value) {
                    return value instanceof P ? value : new P(function(resolve) { resolve(value); });
                }
                return new (P || (P = Promise))(function(resolve, reject) {
                    function fulfilled(value) { try { step(generator.next(value)); } catch(e) { reject(e); } }
                    function rejected(value) { try { step(generator['throw'](value)); } catch(e) { reject(e); } }
                    function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
                    step((generator = generator.apply(thisArg, _arguments || [])).next());
                });
            };
        }
        if (typeof __generator === 'undefined') {
            globalThis.__generator = function(thisArg, body) {
                var _ = { label: 0, sent: function() { if (t[0] & 1) throw t[1]; return t[1]; }, trys: [], ops: [] };
                var f, y, t, g;
                return g = { next: verb(0), 'throw': verb(1), 'return': verb(2) },
                       typeof Symbol === 'function' && (g[Symbol.iterator] = function() { return this; }), g;
                function verb(n) { return function(v) { return step([n, v]); }; }
                function step(op) {
                    if (f) throw new TypeError('Generator is already executing.');
                    while (_) try {
                        if (f = 1, y && (t = op[0] & 2 ? y['return'] : op[0] ? y['throw'] || ((t = y['return']) && t.call(y), 0) : y.next) && !(t = t.call(y, op[1])).done) return t;
                        if (y = 0, t) op = [op[0] & 2, t.value];
                        switch (op[0]) {
                            case 0: case 1: t = op; break;
                            case 4: _.label++; return { value: op[1], done: false };
                            case 5: _.label++; y = op[1]; op = [0]; continue;
                            case 7: op = _.ops.pop(); _.trys.pop(); continue;
                            default:
                                if (!(t = _.trys, t = t.length > 0 && t[t.length - 1]) && (op[0] === 6 || op[0] === 2)) { _ = 0; continue; }
                                if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) { _.label = op[1]; break; }
                                if (op[0] === 6 && _.label < t[1]) { _.label = t[1]; t = op; break; }
                                if (t && _.label < t[2]) { _.label = t[2]; _.ops.push(op); break; }
                                if (t[2]) _.ops.pop();
                                _.trys.pop(); continue;
                        }
                        op = body.call(thisArg, _);
                    } catch(e) { op = [6, e]; y = 0; } finally { f = t = 0; }
                    if (op[0] & 5) throw op[1]; return { value: op[0] ? op[1] : void 0, done: true };
                }
            };
        }
        if (typeof __spreadArray === 'undefined') {
            globalThis.__spreadArray = function(to, from, pack) {
                if (pack || arguments.length === 2) for (var i = 0, l = from.length, ar = []; i < l; i++) ar[i] = from[i];
                return to.concat(from || []);
            };
        }
        if (typeof __assign === 'undefined') {
            globalThis.__assign = Object.assign || function(t) {
                for (var s, i = 1, n = arguments.length; i < n; i++) {
                    s = arguments[i];
                    for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p)) t[p] = s[p];
                }
                return t;
            };
        }
        if (typeof __extends === 'undefined') {
            globalThis.__extends = function(d, b) {
                if (typeof b !== 'function' && b !== null) throw new TypeError('Class extends value ' + String(b) + ' is not a constructor or null');
                function __() { this.constructor = d; }
                d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
            };
        }
        if (typeof __read === 'undefined') {
            globalThis.__read = function(o, n) {
                var m = typeof Symbol === 'function' && o[Symbol.iterator];
                if (!m) return o;
                var i = m.call(o), r, ar = [], e;
                try { while ((n === void 0 || n-- > 0) && !(r = i.next()).done) ar.push(r.value); }
                catch (error) { e = { error: error }; }
                finally { try { if (r && !r.done && (m = i['return'])) m.call(i); } finally { if (e) throw e.error; } }
                return ar;
            };
        }
        if (typeof __values === 'undefined') {
            globalThis.__values = function(o) {
                var s = typeof Symbol === 'function' && Symbol.iterator, m = s && o[s], i = 0;
                if (m) return m.call(o);
                if (o && typeof o.length === 'number') return {
                    next: function() { if (o && i >= o.length) o = void 0; return { value: o && o[i++], done: !o }; }
                };
                throw new TypeError(s ? 'Object is not iterable.' : 'Symbol.iterator is not defined.');
            };
        }
        if (typeof __rest === 'undefined') {
            globalThis.__rest = function(s, e) {
                var t = {};
                for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p) && e.indexOf(p) < 0) t[p] = s[p];
                if (s != null && typeof Object.getOwnPropertySymbols === 'function')
                    for (var i = 0, p = Object.getOwnPropertySymbols(s); i < p.length; i++)
                        if (e.indexOf(p[i]) < 0 && Object.prototype.propertyIsEnumerable.call(s, p[i])) t[p[i]] = s[p[i]];
                return t;
            };
        }
        if (typeof __importDefault === 'undefined') {
            globalThis.__importDefault = function(mod) { return (mod && mod.__esModule) ? mod : { 'default': mod }; };
        }
        if (typeof __importStar === 'undefined') {
            globalThis.__importStar = function(mod) {
                if (mod && mod.__esModule) return mod;
                var result = {};
                if (mod != null) for (var k in mod) if (k !== 'default' && Object.prototype.hasOwnProperty.call(mod, k)) result[k] = mod[k];
                result['default'] = mod;
                return result;
            };
        }
        if (typeof __esDecorate === 'undefined') { globalThis.__esDecorate = function() {}; }
        if (typeof __runInitializers === 'undefined') { globalThis.__runInitializers = function() {}; }
        if (typeof __setFunctionName === 'undefined') { globalThis.__setFunctionName = function(f, n) { try { Object.defineProperty(f, 'name', { value: n, configurable: true }); } catch(e) {} return f; }; }

        // Header normalisation — matches official NuvioMobile fetchPolyfill exactly.
        function __normalize_fetch_headers(headers) {
            var out = {};
            if (!headers) return out;
            if (typeof headers.forEach === 'function') {
                headers.forEach(function(value, key) { out[key] = String(value); });
                return out;
            }
            if (Array.isArray(headers)) {
                headers.forEach(function(pair) {
                    if (pair && pair.length >= 2) out[pair[0]] = String(pair[1]);
                });
                return out;
            }
            Object.keys(headers).forEach(function(key) { out[key] = String(headers[key]); });
            return out;
        }

        var fetch = async function(url, options) {
            options = options || {};
            var method = (options.method || 'GET').toUpperCase();
            var headers = __normalize_fetch_headers(options.headers);
            var body = options.body || '';
            var followRedirects = options.redirect !== 'manual';
            // __native_fetch is an async native bridge — must await so quickjs-kt can
            // drive the event loop while the HTTP request runs on the IO thread.
            var result = await __native_fetch(url, method, JSON.stringify(headers), body, followRedirects);
            var parsed = JSON.parse(result);
            return {
                ok: parsed.ok,
                status: parsed.status,
                statusText: parsed.statusText,
                url: parsed.url,
                headers: {
                    get: function(name) { return (parsed.headers || {})[name.toLowerCase()] || null; }
                },
                text: function() { return Promise.resolve(parsed.body); },
                json: function() {
                    try {
                        if (parsed.body === null || parsed.body === undefined || parsed.body === '') {
                            return Promise.resolve(null);
                        }
                        return Promise.resolve(JSON.parse(parsed.body));
                    } catch (e) { return Promise.resolve(null); }
                }
            };
        };
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
                    digest: function(algo, data) { return Promise.resolve(new ArrayBuffer(32)); },
                    importKey: function() { return Promise.resolve({}); },
                    sign: function() { return Promise.resolve(new ArrayBuffer(32)); },
                    encrypt: function() { return Promise.resolve(new ArrayBuffer(0)); },
                    decrypt: function() { return Promise.resolve(new ArrayBuffer(0)); },
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

        // ── ES2019+ Array polyfills ────────────────────────────────────────────
        if (!Array.prototype.flat) {
            Array.prototype.flat = function(depth) {
                depth = depth === undefined ? 1 : Math.floor(depth);
                if (depth < 1) return Array.prototype.slice.call(this);
                return (function flatten(arr, d) {
                    return d > 0
                        ? arr.reduce(function(acc, val) {
                            return acc.concat(Array.isArray(val) ? flatten(val, d - 1) : val);
                          }, [])
                        : arr.slice();
                })(this, depth);
            };
        }
        if (!Array.prototype.flatMap) {
            Array.prototype.flatMap = function(callback, thisArg) {
                return this.map(callback, thisArg).flat();
            };
        }
        if (!Array.prototype.at) {
            Array.prototype.at = function(index) {
                var i = Math.trunc(index) || 0;
                if (i < 0) i += this.length;
                if (i < 0 || i >= this.length) return undefined;
                return this[i];
            };
        }

        // ── ES2017+ Object polyfills ───────────────────────────────────────────
        if (!Object.entries) {
            Object.entries = function(obj) {
                var result = [];
                for (var key in obj) {
                    if (Object.prototype.hasOwnProperty.call(obj, key)) result.push([key, obj[key]]);
                }
                return result;
            };
        }
        if (!Object.fromEntries) {
            Object.fromEntries = function(entries) {
                var result = {};
                var iter = typeof entries[Symbol.iterator] === 'function' ? entries : Object.keys(entries).map(function(k) { return [k, entries[k]]; });
                var arr = Array.isArray(iter) ? iter : Array.from ? Array.from(iter) : (function(it) { var a = []; var n = it.next(); while (!n.done) { a.push(n.value); n = it.next(); } return a; })(iter[Symbol.iterator]());
                for (var i = 0; i < arr.length; i++) result[arr[i][0]] = arr[i][1];
                return result;
            };
        }
        if (!Object.values) {
            Object.values = function(obj) {
                var result = [];
                for (var key in obj) {
                    if (Object.prototype.hasOwnProperty.call(obj, key)) result.push(obj[key]);
                }
                return result;
            };
        }

        // ── ES2021 String polyfill ─────────────────────────────────────────────
        if (!String.prototype.replaceAll) {
            String.prototype.replaceAll = function(search, replace) {
                if (search instanceof RegExp) {
                    if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
                    return this.replace(search, replace);
                }
                return this.split(search).join(replace);
            };
        }
        if (!String.prototype.at) {
            String.prototype.at = function(index) {
                var i = Math.trunc(index) || 0;
                if (i < 0) i += this.length;
                if (i < 0 || i >= this.length) return undefined;
                return this[i];
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

        // ── Full official NuvioMobile CryptoJS polyfill ───────────────────
        // Matches NuvioMedia/NuvioMobile JsBindings.kt cryptoPolyfill() exactly.
        var WordArray = {
            init: function(words, sigBytes) {
                this.words = words || [];
                this.sigBytes = sigBytes != undefined ? sigBytes : this.words.length * 4;
            },
            toString: function(encoder) { return (encoder || CryptoJS.enc.Hex).stringify(this); },
            concat: function(wordArray) {
                var thisWords = this.words, thatWords = wordArray.words;
                var thisSigBytes = this.sigBytes, thatSigBytes = wordArray.sigBytes;
                this.clamp();
                for (var i = 0; i < thatSigBytes; i++) {
                    var thatByte = (thatWords[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
                    thisWords[(thisSigBytes + i) >>> 2] |= thatByte << (24 - ((thisSigBytes + i) % 4) * 8);
                }
                this.sigBytes += thatSigBytes;
                return this;
            },
            clamp: function() {
                var words = this.words, sigBytes = this.sigBytes;
                if (sigBytes % 4) words[sigBytes >>> 2] &= 0xffffffff << (32 - (sigBytes % 4) * 8);
                words.length = Math.ceil(sigBytes / 4);
                return this;
            },
            clone: function() { return __crWaCreate(this.words.slice(0), this.sigBytes); }
        };
        function __crWaCreate(words, sigBytes) { var wa = Object.create(WordArray); wa.init(words, sigBytes); return wa; }
        function __crIsWa(v) { return v && typeof v === 'object' && Array.isArray(v.words) && typeof v.sigBytes === 'number'; }
        function __crToU8(data) {
            if (!data) return new Uint8Array(0);
            if (data instanceof Uint8Array) return data;
            if (data instanceof ArrayBuffer) return new Uint8Array(data);
            if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(data))
                return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
            if (Array.isArray(data)) return new Uint8Array(data);
            if (typeof data.length === 'number') return new Uint8Array(Array.prototype.slice.call(data));
            return new Uint8Array(0);
        }
        function __crCopyU8(bytes) { bytes = __crToU8(bytes); var c = new Uint8Array(bytes.length); c.set(bytes); return c; }
        function __crU8ToAb(bytes) { return __crCopyU8(bytes).buffer; }
        function __crWaToBytes(wa) {
            if (!__crIsWa(wa)) return typeof wa === 'string' ? new TextEncoder().encode(wa) : __crToU8(wa);
            var bytes = new Uint8Array(wa.sigBytes);
            for (var i = 0; i < wa.sigBytes; i++) bytes[i] = (wa.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
            return bytes;
        }
        function __crBytesToWa(bytes) {
            bytes = __crToU8(bytes);
            var words = [];
            for (var i = 0; i < bytes.length; i++) words[i >>> 2] |= (bytes[i] & 0xff) << (24 - (i % 4) * 8);
            return __crWaCreate(words, bytes.length);
        }
        function __crNormInput(v) {
            if (__crIsWa(v)) return __crWaToBytes(v);
            if (typeof v === 'string') return new TextEncoder().encode(v);
            return __crToU8(v);
        }
        function __crBytesToHex(bytes) {
            bytes = __crToU8(bytes);
            var out = [];
            for (var i = 0; i < bytes.length; i++) { var h = bytes[i].toString(16); out.push(h.length < 2 ? '0' + h : h); }
            return out.join('');
        }
        function __crHexToBytes(hex) {
            hex = String(hex || '').replace(/[^0-9a-fA-F]/g, '');
            if (hex.length % 2) hex = '0' + hex;
            var bytes = new Uint8Array(hex.length / 2);
            for (var i = 0; i < hex.length; i += 2) bytes[i / 2] = parseInt(hex.substr(i, 2), 16) & 0xff;
            return bytes;
        }
        function __crConcat() {
            var total = 0, parts = [];
            for (var i = 0; i < arguments.length; i++) { var p = __crToU8(arguments[i]); parts.push(p); total += p.length; }
            var out = new Uint8Array(total), off = 0;
            for (var j = 0; j < parts.length; j++) { out.set(parts[j], off); off += parts[j].length; }
            return out;
        }
        function __crNormHash(hash) {
            var name = hash && hash.name ? hash.name : hash;
            name = String(name || 'SHA-256').toUpperCase().replace(/[^A-Z0-9]/g, '');
            if (name === 'SHA1' || name === 'SHA256' || name === 'SHA384' || name === 'SHA512' || name === 'MD5') return name;
            throw new Error('Unsupported hash: ' + name);
        }
        function __crNormAlgo(algo) {
            var name = algo && algo.name ? algo.name : algo;
            name = String(name || '').toUpperCase();
            if (name.indexOf('AES-GCM') >= 0) return 'AES-GCM';
            if (name.indexOf('AES-CBC') >= 0) return 'AES-CBC';
            if (name.indexOf('AES-ECB') >= 0 || name === 'ECB') return 'AES-ECB';
            if (name.indexOf('PBKDF2') >= 0) return 'PBKDF2';
            if (name.indexOf('HMAC') >= 0) return 'HMAC';
            return name;
        }
        function __crAesMode(mode, padding) {
            var n = __crNormAlgo(mode || 'AES-CBC');
            if (padding === 'NoPadding' || (CryptoJS.pad && padding === CryptoJS.pad.NoPadding)) n += '-NoPadding';
            return n;
        }
        function __crDigestBytes(hash, dataBytes) {
            return __crHexToBytes(__crypto_digest_hex_raw(__crNormHash(hash), __crBytesToHex(dataBytes)));
        }
        function __crHmacBytes(hash, keyBytes, dataBytes) {
            return __crHexToBytes(__crypto_hmac_hex_raw(__crNormHash(hash), __crBytesToHex(keyBytes), __crBytesToHex(dataBytes)));
        }
        function __crPbkdf2Bytes(passBytes, saltBytes, iterations, keySizeBits, hash) {
            return __crHexToBytes(__crypto_pbkdf2_hex(__crBytesToHex(passBytes), __crBytesToHex(saltBytes), iterations, keySizeBits, __crNormHash(hash)));
        }
        function __crAesBytes(encrypt, mode, keyBytes, ivBytes, dataBytes) {
            var fn = encrypt ? __crypto_aes_encrypt_hex : __crypto_aes_decrypt_hex;
            return __crHexToBytes(fn(mode, __crBytesToHex(keyBytes), __crBytesToHex(ivBytes), __crBytesToHex(dataBytes)));
        }
        function __crEvpKdf(passBytes, saltBytes, keySizeBytes, ivSizeBytes) {
            var target = keySizeBytes + ivSizeBytes, derived = new Uint8Array(target), block = new Uint8Array(0), off = 0;
            while (off < target) {
                block = __crDigestBytes('MD5', __crConcat(block, passBytes, saltBytes || new Uint8Array(0)));
                var take = Math.min(block.length, target - off);
                derived.set(block.subarray(0, take), off); off += take;
            }
            return { key: derived.subarray(0, keySizeBytes), iv: derived.subarray(keySizeBytes) };
        }
        function __crSaltHdr() { return new Uint8Array([83,97,108,116,101,100,95,95]); }
        function __crHasSalt(bytes) {
            var h = __crSaltHdr(); if (!bytes || bytes.length < 16) return false;
            for (var i = 0; i < h.length; i++) { if (bytes[i] !== h[i]) return false; } return true;
        }
        function __crMakeCp(ct, key, iv, salt, mode) {
            return { ciphertext: __crBytesToWa(ct), key: key ? __crBytesToWa(key) : undefined,
                     iv: iv ? __crBytesToWa(iv) : undefined, salt: salt ? __crBytesToWa(salt) : undefined, mode: mode,
                     toString: function(f) { return (f || CryptoJS.format.OpenSSL).stringify(this); } };
        }
        var CryptoJS = {
            enc: {
                Hex: {
                    stringify: function(wa) { return __crBytesToHex(__crWaToBytes(wa)); },
                    parse: function(s) { return __crBytesToWa(__crHexToBytes(s)); }
                },
                Utf8: {
                    stringify: function(wa) { return new TextDecoder('utf-8').decode(__crWaToBytes(wa)); },
                    parse: function(s) { return __crBytesToWa(new TextEncoder().encode(String(s))); }
                },
                Latin1: {
                    stringify: function(wa) {
                        var bytes = __crWaToBytes(wa), out = '';
                        for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
                        return out;
                    },
                    parse: function(s) {
                        s = String(s || ''); var bytes = new Uint8Array(s.length);
                        for (var i = 0; i < s.length; i++) bytes[i] = s.charCodeAt(i) & 0xff;
                        return __crBytesToWa(bytes);
                    }
                },
                Base64: {
                    stringify: function(wa) {
                        var bytes = __crWaToBytes(wa), bin = '';
                        for (var j = 0; j < bytes.length; j++) bin += String.fromCharCode(bytes[j]);
                        return btoa(bin);
                    },
                    parse: function(s) {
                        var bin = atob(String(s || '')), bytes = new Uint8Array(bin.length);
                        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i) & 0xff;
                        return __crBytesToWa(bytes);
                    }
                },
                Base64url: {
                    stringify: function(wa) {
                        return CryptoJS.enc.Base64.stringify(wa).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${"$"}/g, '');
                    },
                    parse: function(s) {
                        s = String(s || '').replace(/-/g, '+').replace(/_/g, '/');
                        while (s.length % 4) s += '=';
                        return CryptoJS.enc.Base64.parse(s);
                    }
                }
            },
            lib: {
                WordArray: {
                    create: function(words, sigBytes) {
                        if (words == null) return __crWaCreate([], sigBytes || 0);
                        if (__crIsWa(words)) return words.clone();
                        if (typeof words === 'string') return CryptoJS.enc.Utf8.parse(words);
                        if (words instanceof ArrayBuffer || (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(words))) {
                            var bytes = __crToU8(words);
                            return __crBytesToWa(sigBytes != undefined ? bytes.subarray(0, sigBytes) : bytes);
                        }
                        return __crWaCreate(words, sigBytes);
                    },
                    random: function(nBytes) {
                        var bytes = new Uint8Array(nBytes || 0);
                        globalThis.crypto.getRandomValues(bytes);
                        return __crBytesToWa(bytes);
                    }
                },
                CipherParams: {
                    create: function(p) {
                        p = p || {};
                        p.toString = p.toString || function(f) { return (f || CryptoJS.format.OpenSSL).stringify(this); };
                        return p;
                    }
                }
            },
            format: {
                OpenSSL: {
                    stringify: function(cp) {
                        var ct = __crWaToBytes(cp.ciphertext);
                        var out = cp.salt ? __crConcat(__crSaltHdr(), __crWaToBytes(cp.salt), ct) : ct;
                        return CryptoJS.enc.Base64.stringify(__crBytesToWa(out));
                    },
                    parse: function(s) {
                        var bytes = __crWaToBytes(CryptoJS.enc.Base64.parse(s));
                        if (__crHasSalt(bytes)) {
                            return CryptoJS.lib.CipherParams.create({
                                salt: __crBytesToWa(bytes.subarray(8, 16)),
                                ciphertext: __crBytesToWa(bytes.subarray(16))
                            });
                        }
                        return CryptoJS.lib.CipherParams.create({ ciphertext: __crBytesToWa(bytes) });
                    }
                }
            },
            mode: { CBC: 'AES-CBC', GCM: 'AES-GCM', ECB: 'AES-ECB' },
            pad: { Pkcs7: 'Pkcs7', NoPadding: 'NoPadding' },
            algo: { MD5: 'MD5', SHA1: 'SHA1', SHA256: 'SHA256', SHA384: 'SHA384', SHA512: 'SHA512', AES: 'AES' },
            MD5:    function(m) { return __crBytesToWa(__crDigestBytes('MD5',    __crNormInput(m))); },
            SHA1:   function(m) { return __crBytesToWa(__crDigestBytes('SHA1',   __crNormInput(m))); },
            SHA256: function(m) { return __crBytesToWa(__crDigestBytes('SHA256', __crNormInput(m))); },
            SHA384: function(m) { return __crBytesToWa(__crDigestBytes('SHA384', __crNormInput(m))); },
            SHA512: function(m) { return __crBytesToWa(__crDigestBytes('SHA512', __crNormInput(m))); },
            HmacMD5:    function(m, k) { return __crBytesToWa(__crHmacBytes('MD5',    __crNormInput(k), __crNormInput(m))); },
            HmacSHA1:   function(m, k) { return __crBytesToWa(__crHmacBytes('SHA1',   __crNormInput(k), __crNormInput(m))); },
            HmacSHA256: function(m, k) { return __crBytesToWa(__crHmacBytes('SHA256', __crNormInput(k), __crNormInput(m))); },
            HmacSHA384: function(m, k) { return __crBytesToWa(__crHmacBytes('SHA384', __crNormInput(k), __crNormInput(m))); },
            HmacSHA512: function(m, k) { return __crBytesToWa(__crHmacBytes('SHA512', __crNormInput(k), __crNormInput(m))); },
            PBKDF2: function(pass, salt, opts) {
                opts = opts || {};
                return __crBytesToWa(__crPbkdf2Bytes(__crNormInput(pass), __crNormInput(salt),
                    opts.iterations || 1000, (opts.keySize || 8) * 32, opts.hasher || 'SHA1'));
            },
            AES: {
                encrypt: function(msg, key, opts) {
                    opts = opts || {};
                    var data = __crNormInput(msg), kBytes, ivBytes, saltBytes;
                    if (typeof key === 'string') {
                        saltBytes = opts.salt ? __crWaToBytes(opts.salt) : __crWaToBytes(CryptoJS.lib.WordArray.random(8));
                        var derived = __crEvpKdf(new TextEncoder().encode(key), saltBytes, 32, 16);
                        kBytes = derived.key; ivBytes = opts.iv ? __crWaToBytes(opts.iv) : derived.iv;
                    } else {
                        kBytes = __crWaToBytes(key); ivBytes = opts.iv ? __crWaToBytes(opts.iv) : new Uint8Array(0);
                    }
                    var mode = __crAesMode(opts.mode || 'AES-CBC', opts.padding);
                    return __crMakeCp(__crAesBytes(true, mode, kBytes, ivBytes, data), kBytes, ivBytes, saltBytes, mode);
                },
                decrypt: function(cipher, key, opts) {
                    opts = opts || {};
                    var cp = typeof cipher === 'string' ? CryptoJS.format.OpenSSL.parse(cipher) : cipher;
                    var data = cp.ciphertext ? __crWaToBytes(cp.ciphertext) : __crToU8(cp);
                    var kBytes, ivBytes;
                    if (typeof key === 'string') {
                        var saltBytes = opts.salt ? __crWaToBytes(opts.salt) : (cp.salt ? __crWaToBytes(cp.salt) : new Uint8Array(0));
                        var derived = __crEvpKdf(new TextEncoder().encode(key), saltBytes, 32, 16);
                        kBytes = derived.key; ivBytes = opts.iv ? __crWaToBytes(opts.iv) : derived.iv;
                    } else {
                        kBytes = __crWaToBytes(key); ivBytes = opts.iv ? __crWaToBytes(opts.iv) : new Uint8Array(0);
                    }
                    var mode = __crAesMode(opts.mode || 'AES-CBC', opts.padding);
                    return __crBytesToWa(__crAesBytes(false, mode, kBytes, ivBytes, data));
                }
            }
        };
        globalThis.CryptoJS = CryptoJS;
        // Full Web Crypto API — overwrites earlier stub with native-bridge-backed version
        globalThis.crypto = {
            subtle: {
                digest: async function(algo, data) {
                    return __crU8ToAb(__crDigestBytes(algo, __crToU8(data)));
                },
                importKey: async function(fmt, data, algo, extractable, usages) {
                    var algorithm = { name: __crNormAlgo(algo || {}) };
                    var type = String(fmt||'raw') === 'spki' ? 'public' : (String(fmt||'raw') === 'pkcs8' ? 'private' : 'secret');
                    return { type: type, extractable: !!extractable, algorithm: algorithm, usages: usages||[], _raw: __crCopyU8(__crToU8(data)) };
                },
                exportKey: async function(fmt, key) { return __crU8ToAb(key._raw); },
                generateKey: async function(algo, extractable, usages) {
                    var algorithm = { name: __crNormAlgo(algo || {}) };
                    var length = (algo && algo.length) || 256;
                    var bytes = new Uint8Array(length / 8);
                    globalThis.crypto.getRandomValues(bytes);
                    return { type: 'secret', extractable: !!extractable, algorithm: algorithm, usages: usages||[], _raw: bytes };
                },
                deriveBits: async function(params, key, len) {
                    return __crU8ToAb(__crPbkdf2Bytes(__crToU8(key._raw), __crToU8(params.salt),
                        params.iterations || 1000, len, params.hash || 'SHA-256'));
                },
                deriveKey: async function(params, key, derivedKeyAlgo, extractable, usages) {
                    var algorithm = { name: __crNormAlgo(derivedKeyAlgo || {}) };
                    var length = (derivedKeyAlgo && derivedKeyAlgo.length) || 256;
                    var raw = await globalThis.crypto.subtle.deriveBits(params, key, length);
                    return { type: 'secret', extractable: !!extractable, algorithm: algorithm, usages: usages||[], _raw: new Uint8Array(raw) };
                },
                encrypt: async function(params, key, data) {
                    var mode = __crNormAlgo(params), iv = __crToU8((params && params.iv) || new Uint8Array(0));
                    return __crU8ToAb(__crAesBytes(true, mode, __crToU8(key._raw), iv, __crToU8(data)));
                },
                decrypt: async function(params, key, data) {
                    var mode = __crNormAlgo(params), iv = __crToU8((params && params.iv) || new Uint8Array(0));
                    return __crU8ToAb(__crAesBytes(false, mode, __crToU8(key._raw), iv, __crToU8(data)));
                },
                sign: async function(algo, key, data) {
                    if (__crNormAlgo(algo || (key && key.algorithm)) === 'HMAC' || (key && key.algorithm && key.algorithm.name === 'HMAC')) {
                        var hash = (algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256';
                        return __crU8ToAb(__crHmacBytes(hash, __crToU8(key._raw), __crToU8(data)));
                    }
                    return __crU8ToAb(new Uint8Array(32));
                },
                verify: async function(algo, key, sig, data) {
                    if (__crNormAlgo(algo || (key && key.algorithm)) === 'HMAC' || (key && key.algorithm && key.algorithm.name === 'HMAC')) {
                        var hash = (algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256';
                        var expected = __crHmacBytes(hash, __crToU8(key._raw), __crToU8(data));
                        var actual = __crToU8(sig);
                        if (expected.length !== actual.length) return false;
                        var diff = 0; for (var i = 0; i < expected.length; i++) diff |= expected[i] ^ actual[i];
                        return diff === 0;
                    }
                    return false;
                }
            },
            getRandomValues: function(arr) {
                if (!arr) return arr;
                var byteLength = arr.byteLength != undefined ? arr.byteLength : arr.length;
                if (!byteLength) return arr;
                var random = __crHexToBytes(__crypto_get_random_values_hex(byteLength));
                if (arr.buffer && arr.byteLength != undefined) {
                    new Uint8Array(arr.buffer, arr.byteOffset || 0, arr.byteLength).set(random);
                } else { for (var i = 0; i < arr.length; i++) arr[i] = random[i] || 0; }
                return arr;
            },
            randomUUID: function() {
                var b = new Uint8Array(16); globalThis.crypto.getRandomValues(b);
                b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
                var h = __crBytesToHex(b);
                return h.substr(0,8)+'-'+h.substr(8,4)+'-'+h.substr(12,4)+'-'+h.substr(16,4)+'-'+h.substr(20);
            }
        };
        globalThis.WebAssembly = {
            instantiate: async function(src, imports) {
                console.warn('WebAssembly.instantiate called (placeholder)');
                return { instance: { exports: {} }, module: {} };
            }
        };

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
        // Promise.allSettled polyfill (ES2020) — QuickJS provides this natively in recent
        // builds but older bindings may not. Safety net for providers that race multiple fetches.
        if (typeof Promise.allSettled === 'undefined') {
            Promise.allSettled = function(promises) {
                return Promise.all((promises || []).map(function(p) {
                    return Promise.resolve(p).then(
                        function(v)  { return { status: 'fulfilled', value: v }; },
                        function(e)  { return { status: 'rejected',  reason: e }; }
                    );
                }));
            };
        }
        // Promise.any polyfill (ES2021) — resolves on first fulfillment, rejects if all reject.
        if (typeof Promise.any === 'undefined') {
            Promise.any = function(promises) {
                return new Promise(function(resolve, reject) {
                    var arr = Array.isArray(promises) ? promises : [];
                    if (arr.length === 0) { reject(new Error('All promises were rejected')); return; }
                    var errors = new Array(arr.length);
                    var remaining = arr.length;
                    arr.forEach(function(p, i) {
                        Promise.resolve(p).then(resolve, function(e) {
                            errors[i] = e;
                            if (--remaining === 0) reject(new Error('All promises were rejected'));
                        });
                    });
                });
            };
        }
        // Array.prototype.flat / flatMap polyfills (ES2019)
        if (!Array.prototype.flat) {
            Array.prototype.flat = function(depth) {
                depth = (depth === undefined) ? 1 : Math.floor(depth);
                if (depth < 1) return Array.prototype.slice.call(this);
                return Array.prototype.reduce.call(this, function(acc, val) {
                    if (Array.isArray(val) && depth > 0) {
                        var sub = val.flat(depth - 1);
                        for (var i = 0; i < sub.length; i++) acc.push(sub[i]);
                    } else { acc.push(val); }
                    return acc;
                }, []);
            };
        }
        if (!Array.prototype.flatMap) {
            Array.prototype.flatMap = function(fn, thisArg) {
                return Array.prototype.map.call(this, fn, thisArg).flat(1);
            };
        }
        // Object.entries polyfill
        if (typeof Object.entries === 'undefined') {
            Object.entries = function(o) {
                return Object.keys(o).map(function(k) { return [k, o[k]]; });
            };
        }
        // Object.fromEntries polyfill (ES2019)
        if (typeof Object.fromEntries === 'undefined') {
            Object.fromEntries = function(iter) {
                var o = {};
                if (Array.isArray(iter)) {
                    iter.forEach(function(p) { if (p && p.length >= 2) o[p[0]] = p[1]; });
                } else if (iter && typeof iter.forEach === 'function') {
                    iter.forEach(function(v, k) { o[k] = v; });
                }
                return o;
            };
        }
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
                    // ── Stremio / Torrentio torrent format: infoHash + sources ──
                    // Providers like Torrentio and NoTorrent return torrent streams
                    // using the Stremio addon format ({infoHash, sources, name, title})
                    // rather than a direct URL. Build a magnet: URI from infoHash so
                    // they are not silently dropped.
                    val infoHash = prim(item, "infoHash", "info_hash", "infohash")
                    if (infoHash != null && infoHash.length >= 20) {
                        val magnetUrl = buildString {
                            append("magnet:?xt=urn:btih:$infoHash")
                            val sources = item["sources"] as? kotlinx.serialization.json.JsonArray
                            sources?.forEach { s ->
                                val tracker = (s as? kotlinx.serialization.json.JsonPrimitive)?.content
                                if (tracker != null && tracker.startsWith("tracker:")) {
                                    append("&tr=").append(java.net.URLEncoder.encode(tracker.removePrefix("tracker:"), "UTF-8"))
                                }
                            }
                            val dn = prim(item, "name", "title")?.substringBefore('\n')?.trim()
                            if (!dn.isNullOrBlank()) {
                                append("&dn=").append(java.net.URLEncoder.encode(dn, "UTF-8"))
                            }
                        }
                        val name    = prim(item, "name", "label")
                        val title   = prim(item, "title")
                        val quality = prim(item, "quality", "resolution", "res")
                        return@mapNotNull NuvioStream(name = name, title = title, url = magnetUrl, quality = quality)
                    }

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
