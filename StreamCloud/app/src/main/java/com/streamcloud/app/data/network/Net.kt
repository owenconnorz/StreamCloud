package com.streamcloud.app.data.network

import com.streamcloud.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object Net {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    /** 50 MB HTTP disk cache. */
    private var httpCache: Cache? = null

    fun init(cacheDir: File) {
        if (httpCache == null) {
            // Cache dir versioned so stale entries from previous gzip-handling
            // changes are automatically ignored on first run after an update.
            httpCache = Cache(File(cacheDir, "okhttp_v3"), 50L * 1024 * 1024)
        }
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .cache(httpCache)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Force uncompressed responses so OkHttp never stores a gzip-encoded
        // body in the disk cache.  Without this, OkHttp's BridgeInterceptor
        // adds "Accept-Encoding: gzip", the server responds with compressed
        // bytes, and OkHttp stores those compressed bytes in the cache WITH
        // the "Content-Encoding: gzip" header still set.  On a subsequent
        // cache hit BridgeInterceptor tries to decompress them again, which
        // throws "gzip finished without exhausting source" and breaks TMDB
        // and any other JSON API that uses this client.
        //
        // "identity" tells the server to send plain (uncompressed) bytes.
        // The size penalty for JSON API responses is negligible.
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Accept-Encoding", "identity")
                    .build()
            )
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
