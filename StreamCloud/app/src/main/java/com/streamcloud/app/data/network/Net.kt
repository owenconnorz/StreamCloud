package com.streamcloud.app.data.network

import com.streamcloud.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.GzipSource
import okio.buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object Net {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    /**
     * 50 MB HTTP disk cache.
     *
     * Cache dir is versioned (okhttp_v2) so that any entries written by the old
     * gzip-enabled client are ignored on the first run after this update.
     */
    private var httpCache: Cache? = null

    fun init(cacheDir: File) {
        if (httpCache == null) {
            httpCache = Cache(File(cacheDir, "okhttp_v2"), 50L * 1024 * 1024)
        }
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .cache(httpCache)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // ── Gzip-before-cache decompression ──────────────────────────────────
        // Network interceptors run AFTER the wire but BEFORE the disk cache.
        // If the server sends Content-Encoding: gzip we decompress here and
        // strip the header so the cache always stores plain JSON.
        //
        // Without this, the cache can store compressed bytes with the gzip
        // header still attached. On the next cache hit OkHttp's GzipSource
        // tries to decompress already-plain bytes → "gzip finished without
        // exhausting source" / JSON parse errors.
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val encoding = response.header("Content-Encoding")
            if (encoding.equals("gzip", ignoreCase = true)) {
                val body = response.body
                if (body != null) {
                    val decompressed = GzipSource(body.source()).buffer()
                    val newBody = decompressed.readByteArray()
                        .toResponseBody(body.contentType())
                    response.newBuilder()
                        .removeHeader("Content-Encoding")
                        .removeHeader("Content-Length")
                        .body(newBody)
                        .build()
                } else response
            } else {
                response
            }
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(client())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
