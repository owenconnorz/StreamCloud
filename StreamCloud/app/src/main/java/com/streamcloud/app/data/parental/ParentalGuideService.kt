package com.streamcloud.app.data.parental

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class ContentSeverity { NONE, MILD, MODERATE, SEVERE }

data class ParentalRating(
    val violence: ContentSeverity    = ContentSeverity.NONE,
    val language: ContentSeverity    = ContentSeverity.NONE,
    val nudity: ContentSeverity      = ContentSeverity.NONE,
    val substances: ContentSeverity  = ContentSeverity.NONE,
    val fright: ContentSeverity      = ContentSeverity.NONE,
) {
    val overall: ContentSeverity get() = listOf(violence, language, nudity, substances, fright)
        .maxByOrNull { it.ordinal } ?: ContentSeverity.NONE

    val isEmpty: Boolean get() = overall == ContentSeverity.NONE
}

object ParentalGuideService {

    private const val BASE = "https://parental.nuvioapp.space"
    private const val TAG  = "ParentalGuideService"

    suspend fun fetch(mediaType: String, imdbId: String): ParentalRating? =
        withContext(Dispatchers.IO) {
            try {
                val url  = URL("$BASE/$mediaType/$imdbId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout    = 5_000
                }
                if (conn.responseCode != 200) return@withContext null
                val body = conn.inputStream.bufferedReader().readText()
                parse(body)
            } catch (e: Throwable) {
                Log.w(TAG, "fetch failed", e)
                null
            }
        }

    private fun parseSeverity(raw: String?): ContentSeverity = when (raw?.lowercase()) {
        "severe", "extreme", "graphic" -> ContentSeverity.SEVERE
        "moderate", "strong"           -> ContentSeverity.MODERATE
        "mild", "minor", "light"       -> ContentSeverity.MILD
        else                           -> ContentSeverity.NONE
    }

    private fun parse(json: String): ParentalRating? = try {
        val obj = JSONObject(json)
        ParentalRating(
            violence   = parseSeverity(obj.optString("violence")),
            language   = parseSeverity(obj.optString("language")),
            nudity     = parseSeverity(obj.optString("nudity")),
            substances = parseSeverity(obj.optString("substances")),
            fright     = parseSeverity(obj.optString("fright")),
        ).takeIf { !it.isEmpty }
    } catch (_: Throwable) { null }
}
