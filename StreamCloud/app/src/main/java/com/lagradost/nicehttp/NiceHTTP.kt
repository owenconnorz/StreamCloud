@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.nicehttp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Drop-in replica of cloudstream3's `com.lagradost.nicehttp.Requests`.
 *
 * IMPORTANT: plugins are compiled against the REAL cloudstream3 JAR where
 * `MainActivityKt.getApp()` has JVM descriptor:
 *
 *     getApp()Lcom/lagradost/nicehttp/Requests;
 *
 * The return type MUST be exactly `com.lagradost.nicehttp.Requests` — any
 * other package causes `NoSuchMethodError` at plugin load time.
 */
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

    // ── Core request methods ────────────────────────────────────────────

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        timeout: Long = 30,
        interceptor: Any? = null,
        verify: Boolean = true,
    ): NiceResponse = executeBlocking("GET", url, headers, referer, params, cookies, body = null)

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
        timeout: Long = 30,
    ): NiceResponse {
        val bodyBytes: ByteArray? = when {
            json != null -> json.toString().toByteArray(Charsets.UTF_8)
            requestBody != null -> requestBody.toByteArray(Charsets.UTF_8)
            data != null -> data.entries.joinToString("&") {
                "${urlEncode(it.key)}=${urlEncode(it.value)}"
            }.toByteArray(Charsets.UTF_8)
            else -> null
        }
        val contentType = when {
            json != null -> "application/json; charset=utf-8"
            else -> "application/x-www-form-urlencoded"
        }
        return executeBlocking("POST", url, headers, referer, params, cookies, bodyBytes to contentType)
    }

    suspend fun put(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: Any? = null,
        timeout: Long = 30,
    ): NiceResponse {
        val bodyBytes: ByteArray? = when {
            json != null -> json.toString().toByteArray(Charsets.UTF_8)
            data != null -> data.entries.joinToString("&") { "${it.key}=${it.value}" }.toByteArray()
            else -> null
        }
        val ct = if (json != null) "application/json" else "application/x-www-form-urlencoded"
        return executeBlocking("PUT", url, headers, referer, params, cookies, bodyBytes to ct)
    }

    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        timeout: Long = 30,
    ): NiceResponse = executeBlocking("HEAD", url, headers, referer, params, cookies, body = null)

    // ── Internal execution ──────────────────────────────────────────────

    private fun executeBlocking(
        method: String,
        url: String,
        headers: Map<String, String>,
        referer: String?,
        params: Map<String, String>,
        cookies: Map<String, String>,
        body: Any?,
    ): NiceResponse {
        val finalUrl = buildUrl(url, params)
        val builder = Request.Builder().url(finalUrl)

        builder.header("User-Agent", headers["User-Agent"] ?: DEFAULT_UA)
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true)) builder.header(k, v)
        }
        if (referer != null) builder.header("Referer", referer)
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }

        when (method.uppercase()) {
            "POST", "PUT", "PATCH" -> {
                @Suppress("UNCHECKED_CAST")
                val pair = body as? Pair<ByteArray?, String>
                val raw = pair?.first ?: ByteArray(0)
                val ct = pair?.second ?: "application/octet-stream"
                val reqBody = raw.toRequestBody(ct.toMediaType())
                when (method.uppercase()) {
                    "PUT"   -> builder.put(reqBody)
                    "PATCH" -> builder.patch(reqBody)
                    else    -> builder.post(reqBody)
                }
            }
            "HEAD"   -> builder.head()
            "DELETE" -> builder.delete()
            else     -> builder.get()
        }

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                NiceResponse(
                    code    = resp.code,
                    text    = resp.body?.string().orEmpty(),
                    headers = resp.headers.toMultimap(),
                    url     = resp.request.url.toString(),
                )
            }
        } catch (e: Throwable) {
            NiceResponse(code = -1, text = "", headers = emptyMap(), url = finalUrl, error = e)
        }
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val qs = params.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
        return if (base.contains("?")) "$base&$qs" else "$base?$qs"
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

// ── Response wrapper ────────────────────────────────────────────────────────

class NiceResponse(
    val code: Int,
    val text: String,
    val headers: Map<String, List<String>>,
    val url: String,
    val error: Throwable? = null,
) {
    val ok: Boolean get() = code in 200..299
    val isSuccessful: Boolean get() = ok

    /** Raw body string — same as [text]. */
    val body: String get() = text

    val cookies: Map<String, String> by lazy {
        (headers["set-cookie"] ?: headers["Set-Cookie"] ?: emptyList())
            .mapNotNull { it.substringBefore(';').takeIf { c -> c.contains('=') } }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    val document: Document by lazy { Jsoup.parse(text, url) }

    fun parsed(): Document = document

    inline fun <reified T> parsedSafe(): T? = try {
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }.decodeFromString(kotlinx.serialization.serializer<T>(), text)
    } catch (_: Exception) { null }

    override fun toString(): String = "NiceResponse(code=$code, url=$url)"
}
