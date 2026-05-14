@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Replica of cloudstream3's `Requests` class — the singleton HTTP client every
 * plugin uses. Real cloudstream uses NiceHTTP; we use OkHttp directly since the
 * public surface is similar.
 *
 * Plugin code accesses this via the top-level `app` property defined in
 * [MainActivity.kt], which compiles to `MainActivityKt.getApp()` — matching the
 * symbol real CloudStream plugins are linked against.
 */
object Requests {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
    ): NiceResponse = execute("GET", url, headers, referer, params, cookies, body = null)

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: Any? = null,
        files: List<Any>? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = 0,
        timeout: Long = 30,
    ): NiceResponse {
        val body: ByteArray? = when {
            json != null -> kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                kotlinx.serialization.json.Json.parseToJsonElement(json.toString())
            ).toByteArray()
            data != null -> data.entries.joinToString("&") { "${it.key}=${it.value}" }.toByteArray()
            else -> null
        }
        val contentType = if (json != null) "application/json"
        else "application/x-www-form-urlencoded"
        return execute("POST", url, headers, referer, params, cookies, body to contentType)
    }

    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        referer: String?,
        params: Map<String, String>,
        cookies: Map<String, String>,
        body: Any?,
    ): NiceResponse {
        val finalUrl = if (params.isEmpty()) url else {
            val qs = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            if (url.contains("?")) "$url&$qs" else "$url?$qs"
        }
        val builder = Request.Builder().url(finalUrl)
        builder.header("User-Agent", headers["User-Agent"] ?: DEFAULT_UA)
        headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) builder.header(k, v) }
        if (referer != null) builder.header("Referer", referer)
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        when (method.uppercase()) {
            "POST" -> {
                val pair = body as? Pair<*, *>
                @Suppress("UNCHECKED_CAST")
                val raw = pair?.first as? ByteArray
                @Suppress("UNCHECKED_CAST")
                val ct = (pair?.second as? String) ?: "application/octet-stream"
                builder.post((raw ?: ByteArray(0)).toRequestBody(ct.toMediaType()))
            }
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return NiceResponse(
                code = resp.code,
                text = text,
                headers = resp.headers.toMultimap(),
                url = resp.request.url.toString(),
            )
        }
    }
}

class NiceResponse(
    val code: Int,
    val text: String,
    val headers: Map<String, List<String>>,
    val url: String,
) {
    val ok: Boolean = code in 200..299
    val isSuccessful: Boolean = ok
    val body: String = text
    val cookies: Map<String, String> by lazy {
        (headers["set-cookie"] ?: headers["Set-Cookie"] ?: emptyList())
            .mapNotNull { it.substringBefore(';').takeIf { c -> c.contains('=') } }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
    val document: Document by lazy { Jsoup.parse(text, url) }
    fun parsed(): Document = document
    inline fun <reified T> parsedSafe(): T? = try {
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true; isLenient = true
        }.decodeFromString(kotlinx.serialization.serializer<T>(), text)
    } catch (_: Exception) { null }
}