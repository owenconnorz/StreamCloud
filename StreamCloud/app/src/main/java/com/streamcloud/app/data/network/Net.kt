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


    private var httpCache: Cache? = null

    fun init(cacheDir: File) {
        if (httpCache == null) {


            httpCache = Cache(File(cacheDir, "okhttp_v3"), 50L * 1024 * 1024)
        }
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .cache(httpCache)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)











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
