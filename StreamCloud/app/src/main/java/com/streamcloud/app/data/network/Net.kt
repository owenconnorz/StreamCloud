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
        // ── Gzip-cache fix ────────────────────────────────────────────────────
        // OkHttp's BridgeInterceptor normally adds "Accept-Encoding: gzip"
        // and transparently decompresses responses.  However when a server
        // also sends "Transfer-Encoding: chunked", the decompressed bytes can
        // be stored in the disk cache WITH the "Content-Encoding: gzip" header
        // still present.  On the next cache hit OkHttp tries to decompress the
        // already-plain bytes → "gzip finished without exhausting source".
        //
        // Fix: tell every server we only accept identity (no compression).
        // Responses are stored as plain JSON — zero risk of double-decompression.
        // The bandwidth cost for typical API payloads (< 100 KB) is negligible.
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Accept-Encoding", "identity")
                .build()
            chain.proceed(req)
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
