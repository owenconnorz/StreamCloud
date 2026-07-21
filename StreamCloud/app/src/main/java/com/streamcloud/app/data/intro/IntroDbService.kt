package com.streamcloud.app.data.intro

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SkipSegment(
    val type: String,
    val startMs: Long,
    val endMs: Long,
)

data class IntroDbSubmitResult(val success: Boolean, val message: String)

object IntroDbService {

    private const val BASE = "https://beta.introdb.app"
    private const val TAG  = "IntroDbService"

    suspend fun fetchSegments(imdbId: String, season: Int?, episode: Int?, apiKey: String): List<SkipSegment> =
        withContext(Dispatchers.IO) {
            try {
                val path = if (season != null && episode != null)
                    "/api/v1/show/$imdbId/$season/$episode"
                else
                    "/api/v1/movie/$imdbId"
                val url = URL("$BASE$path")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout    = 5_000
                    if (apiKey.isNotBlank()) setRequestProperty("X-API-Key", apiKey)
                }
                val code = conn.responseCode
                if (code != 200) return@withContext emptyList()
                val body = conn.inputStream.bufferedReader().readText()
                parseSegments(body)
            } catch (e: Throwable) {
                Log.w(TAG, "fetchSegments failed", e)
                emptyList()
            }
        }

    private fun parseSegments(json: String): List<SkipSegment> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val type  = obj.optString("category", "intro")
                val start = (obj.optDouble("start", -1.0) * 1000).toLong()
                val end   = (obj.optDouble("end",   -1.0) * 1000).toLong()
                if (start >= 0 && end > start) SkipSegment(type, start, end) else null
            }
        } catch (_: Throwable) { emptyList() }
    }

    suspend fun submitSegment(
        imdbId: String,
        season: Int?,
        episode: Int?,
        type: String,
        startSec: Double,
        endSec: Double,
        apiKey: String,
    ): IntroDbSubmitResult = withContext(Dispatchers.IO) {
        try {
            val path = if (season != null && episode != null)
                "/api/v1/show/$imdbId/$season/$episode"
            else
                "/api/v1/movie/$imdbId"
            val url  = URL("$BASE$path")
            val body = JSONObject().apply {
                put("category", type)
                put("start", startSec)
                put("end", endSec)
            }.toString()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod     = "POST"
                doOutput          = true
                connectTimeout    = 8_000
                readTimeout       = 8_000
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("X-API-Key", apiKey)
            }
            conn.outputStream.bufferedWriter().use { it.write(body) }
            val code = conn.responseCode
            IntroDbSubmitResult(code in 200..299, "HTTP $code")
        } catch (e: Throwable) {
            Log.w(TAG, "submitSegment failed", e)
            IntroDbSubmitResult(false, e.message ?: "Unknown error")
        }
    }
}
