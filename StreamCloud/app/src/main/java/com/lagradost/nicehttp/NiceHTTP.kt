@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.nicehttp

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

// ── ResponseParser ──────────────────────────────────────────────────────────
//
// Signature matches the real NiceHTTP library (Blatzar/NiceHttp).
// Plugins compiled against CloudStream call:
//   response.getParser()!!.parse(text, T::class)
// so both the interface and the property must exactly match the published SDK.

interface ResponseParser {
    /** Parse JSON text into an instance of [kClass]. May throw. */
    fun <T : Any> parse(text: String, kClass: KClass<T>): T

    /** Same as [parse] but returns null on any failure. */
    fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T?

    /** Serialize [obj] to a JSON string (used for request bodies). */
    fun writeValueAsString(obj: Any): String
}

// ── Default JSON parser (Jackson) ──────────────────────────────────────────

private val defaultMapper by lazy {
    jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

/**
 * Jackson-backed [ResponseParser] used as the default for every [NiceResponse].
 * Plugins that call [NiceResponse.parsed] / [NiceResponse.parsedSafe] will use
 * this unless the caller explicitly passes a different parser.
 */
object DefaultResponseParser : ResponseParser {
    override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
        defaultMapper.readValue(text, kClass.java)

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
        runCatching { defaultMapper.readValue(text, kClass.java) }.getOrNull()

    override fun writeValueAsString(obj: Any): String =
        defaultMapper.writeValueAsString(obj)
}

// ── NiceFile ────────────────────────────────────────────────────────────────

class NiceFile(val name: String, val fileName: String) {
    constructor(name: String, file: java.io.File) : this(name, file.name)
}

// ── Requests ────────────────────────────────────────────────────────────────

object Requests {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // ── GET ──────────────────────────────────────────────────────────────────
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 30L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = DefaultResponseParser,
    ): NiceResponse = execute("GET", url, headers, referer, params, cookies,
        body = null, allowRedirects = allowRedirects, timeout = timeout,
        interceptor = interceptor, responseParser = responseParser)

    // ── POST ─────────────────────────────────────────────────────────────────
    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: Any? = null,
        requestBody: String? = null,
        files: List<Any>? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 30L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = DefaultResponseParser,
    ): NiceResponse {
        val (bodyBytes, contentType) = buildBody(json, requestBody, data)
        return execute("POST", url, headers, referer, params, cookies,
            body = if (bodyBytes != null) bodyBytes to contentType else null,
            allowRedirects = allowRedirects, timeout = timeout,
            interceptor = interceptor, responseParser = responseParser)
    }

    // ── PUT ──────────────────────────────────────────────────────────────────
    suspend fun put(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: Any? = null,
        requestBody: String? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 30L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = DefaultResponseParser,
    ): NiceResponse {
        val (bodyBytes, contentType) = buildBody(json, requestBody, data)
        return execute("PUT", url, headers, referer, params, cookies,
            body = if (bodyBytes != null) bodyBytes to contentType else null,
            allowRedirects = allowRedirects, timeout = timeout,
            interceptor = interceptor, responseParser = responseParser)
    }

    // ── PATCH ────────────────────────────────────────────────────────────────
    suspend fun patch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: Any? = null,
        requestBody: String? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 30L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = DefaultResponseParser,
    ): NiceResponse {
        val (bodyBytes, contentType) = buildBody(json, requestBody, data)
        return execute("PATCH", url, headers, referer, params, cookies,
            body = if (bodyBytes != null) bodyBytes to contentType else null,
            allowRedirects = allowRedirects, timeout = timeout,
            interceptor = interceptor, responseParser = responseParser)
    }

    // ── DELETE ───────────────────────────────────────────────────────────────
    suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: Any? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 30L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = DefaultResponseParser,
    ): NiceResponse {
        val (bodyBytes, contentType) = buildBody(json, null, data)
        return execute("DELETE", url, headers, referer, params, cookies,
            body = if (bodyBytes != null) bodyBytes to contentType else null,
            allowRedirects = allowRedirects, timeout = timeout,
            interceptor = interceptor, responseParser = responseParser)
    }

    // ── HEAD ─────────────────────────────────────────────────────────────────
    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        cacheUnit: TimeUnit = TimeUnit.MINUTES,
        timeout: Long = 30L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = DefaultResponseParser,
    ): NiceResponse = execute("HEAD", url, headers, referer, params, cookies,
        body = null, allowRedirects = allowRedirects, timeout = timeout,
        interceptor = interceptor, responseParser = responseParser)

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun buildBody(json: Any?, requestBody: String?, data: Map<String, String>?):
        Pair<ByteArray?, String> = when {
        json != null -> {
            val s = if (json is String) json else defaultMapper.writeValueAsString(json)
            s.toByteArray(Charsets.UTF_8) to "application/json; charset=utf-8"
        }
        requestBody != null -> requestBody.toByteArray(Charsets.UTF_8) to "text/plain; charset=utf-8"
        data != null -> data.entries.joinToString("&") {
            "${urlEncode(it.key)}=${urlEncode(it.value)}"
        }.toByteArray(Charsets.UTF_8) to "application/x-www-form-urlencoded"
        else -> null to "application/octet-stream"
    }

    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        referer: String?,
        params: Map<String, String>,
        cookies: Map<String, String>,
        body: Any?,
        allowRedirects: Boolean,
        timeout: Long,
        interceptor: Interceptor?,
        responseParser: ResponseParser?,
    ): NiceResponse {
        val finalUrl = buildUrl(url, params)
        val httpClient = (if (interceptor != null)
            client.newBuilder().addInterceptor(interceptor).build()
        else client)

        val builder = Request.Builder().url(finalUrl)
        builder.header("User-Agent", headers["User-Agent"] ?: DEFAULT_UA)
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true)) builder.header(k, v)
        }
        if (referer != null) builder.header("Referer", referer)
        if (cookies.isNotEmpty())
            builder.header("Cookie",
                cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })

        @Suppress("UNCHECKED_CAST")
        when (method.uppercase()) {
            "POST", "PUT", "PATCH" -> {
                val pair = body as? Pair<ByteArray?, String>
                val raw = pair?.first ?: ByteArray(0)
                val ct  = pair?.second ?: "application/octet-stream"
                val rb  = raw.toRequestBody(ct.toMediaType())
                when (method.uppercase()) {
                    "PUT"   -> builder.put(rb)
                    "PATCH" -> builder.patch(rb)
                    else    -> builder.post(rb)
                }
            }
            "HEAD"   -> builder.head()
            "DELETE" -> {
                val pair = body as? Pair<ByteArray?, String>
                if (pair?.first != null)
                    builder.delete(pair.first!!.toRequestBody(pair.second.toMediaType()))
                else
                    builder.delete()
            }
            else -> builder.get()
        }

        return try {
            httpClient.newCall(builder.build()).execute().use { resp ->
                NiceResponse(
                    code    = resp.code,
                    text    = resp.body?.string().orEmpty(),
                    headers = resp.headers.toMultimap(),
                    url     = resp.request.url.toString(),
                    parser  = responseParser ?: DefaultResponseParser,
                )
            }
        } catch (e: Throwable) {
            NiceResponse(code = -1, text = "", headers = emptyMap(), url = finalUrl,
                error = e, parser = responseParser ?: DefaultResponseParser)
        }
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val qs = params.entries.joinToString("&") {
            "${urlEncode(it.key)}=${urlEncode(it.value)}"
        }
        return if (base.contains("?")) "$base&$qs" else "$base?$qs"
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

// ── NiceResponse ─────────────────────────────────────────────────────────────
//
// The `parser` property is a val — Kotlin generates getParser() for it.
// Compiled plugins call response.getParser() via the inlined parsed<T>() /
// parsedSafe<T>() helpers in the real NiceHTTP library, so the property MUST
// exist with exactly this type signature.

class NiceResponse(
    val code: Int,
    val text: String,
    val headers: Map<String, List<String>>,
    val url: String,
    val error: Throwable? = null,
    /** Must be non-null; real NiceHttp SDK passes the parser in the constructor. */
    val parser: ResponseParser? = DefaultResponseParser,
) {
    val ok: Boolean get() = code in 200..299
    val isSuccessful: Boolean get() = ok
    val body: String get() = text

    val cookies: Map<String, String> by lazy {
        (headers["set-cookie"] ?: headers["Set-Cookie"] ?: emptyList())
            .mapNotNull { it.substringBefore(';').takeIf { c -> c.contains('=') } }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    val document: Document by lazy { Jsoup.parse(text, url) }

    /** Delegate to [parser] — matches the inlined version compiled into plugins. */
    inline fun <reified T : Any> parsed(): T =
        parser!!.parse(text, T::class)

    /** Null-safe variant. */
    inline fun <reified T : Any> parsedSafe(): T? =
        runCatching { parser!!.parseSafe(text, T::class) }.getOrNull()

    override fun toString(): String = "NiceResponse(code=$code, url=$url)"
}
