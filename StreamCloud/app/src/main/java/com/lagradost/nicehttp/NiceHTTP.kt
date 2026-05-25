@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.nicehttp

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

// ResponseParser MUST be an interface — plugins reference it via invokeinterface.
// Declaring it as a class causes IncompatibleClassChangeError at runtime.
interface ResponseParser

private object NoOpResponseParser : ResponseParser

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

    // Signature matches real NiceHTTP exactly so plugins' invokestatic descriptor resolves.
    // Order: url, headers, referer, params, cookies, allowRedirects,
    //        timeout:Int, timeUnit:TimeUnit, cacheTime:Long,
    //        interceptor:Interceptor?, verify:Boolean, responseParser:ResponseParser?
    suspend fun get(
        url: String,
        headers: Map<String, String>? = null,
        referer: String? = null,
        params: Map<String, String>? = null,
        cookies: Map<String, String>? = null,
        allowRedirects: Boolean = true,
        timeout: Int = 10,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
        cacheTime: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = null,
    ): NiceResponse = execute("GET", url, headers, referer, params, cookies, body = null)

    // Parameter order must match the real CloudStream NiceHTTP exactly so the $default
    // synthetic stub resolves at runtime:
    // url, headers, referer, params, cookies, data, files, json,
    // requestBody (okhttp3.RequestBody), allowRedirects, timeout, timeUnit,
    // cacheTime, interceptor, verify, responseParser
    suspend fun post(
        url: String,
        headers: Map<String, String>? = null,
        referer: String? = null,
        params: Map<String, String>? = null,
        cookies: Map<String, String>? = null,
        data: Map<String, String>? = null,
        files: List<Any>? = null,
        json: Any? = null,
        requestBody: RequestBody? = null,
        allowRedirects: Boolean = true,
        timeout: Int = 10,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
        cacheTime: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = null,
    ): NiceResponse {
        val body: RequestBody? = when {
            requestBody != null -> requestBody
            json != null -> {
                val bytes = json.toString().toByteArray(Charsets.UTF_8)
                bytes.toRequestBody("application/json; charset=utf-8".toMediaType())
            }
            data != null -> {
                val form = data.entries.joinToString("&") {
                    "${urlEncode(it.key)}=${urlEncode(it.value)}"
                }
                form.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            }
            else -> null
        }
        return execute("POST", url, headers, referer, params, cookies, body)
    }

    suspend fun put(
        url: String,
        headers: Map<String, String>? = null,
        referer: String? = null,
        params: Map<String, String>? = null,
        cookies: Map<String, String>? = null,
        data: Map<String, String>? = null,
        json: Any? = null,
        timeout: Int = 10,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
        cacheTime: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = null,
    ): NiceResponse {
        val body: RequestBody? = when {
            json != null -> json.toString().toByteArray(Charsets.UTF_8)
                .toRequestBody("application/json".toMediaType())
            data != null -> data.entries.joinToString("&") { "${it.key}=${it.value}" }
                .toByteArray()
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            else -> null
        }
        return execute("PUT", url, headers, referer, params, cookies, body)
    }

    suspend fun head(
        url: String,
        headers: Map<String, String>? = null,
        referer: String? = null,
        params: Map<String, String>? = null,
        cookies: Map<String, String>? = null,
        timeout: Int = 10,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
    ): NiceResponse = execute("HEAD", url, headers, referer, params, cookies, body = null)

    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>?,
        referer: String?,
        params: Map<String, String>?,
        cookies: Map<String, String>?,
        body: RequestBody?,
    ): NiceResponse {
        val finalUrl = buildUrl(url, params ?: emptyMap())
        val builder = Request.Builder().url(finalUrl)

        val hdrs = headers ?: emptyMap()
        builder.header("User-Agent", hdrs["User-Agent"] ?: DEFAULT_UA)
        hdrs.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true)) builder.header(k, v)
        }
        if (referer != null) builder.header("Referer", referer)
        val ck = cookies ?: emptyMap()
        if (ck.isNotEmpty()) {
            builder.header("Cookie", ck.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }

        val emptyBody = ByteArray(0).toRequestBody("application/octet-stream".toMediaType())
        when (method.uppercase()) {
            "POST"   -> builder.post(body ?: emptyBody)
            "PUT"    -> builder.put(body ?: emptyBody)
            "PATCH"  -> builder.patch(body ?: emptyBody)
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

@PublishedApi internal val jacksonMapper by lazy {
    jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

class NiceResponse(
    val code: Int,
    val text: String,
    val headers: Map<String, List<String>>,
    val url: String,
    val error: Throwable? = null,
    val parser: ResponseParser = NoOpResponseParser,
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

    fun parsed(): Document = document

    inline fun <reified T> parsedSafe(): T? = try {
        jacksonMapper.readValue<T>(text)
    } catch (_: Exception) { null }

    override fun toString(): String = "NiceResponse(code=$code, url=$url)"
}
