package com.streamcloud.app.data.ytmusic

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal class InnerTubeClient(private val cookie: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    suspend fun browse(browseId: String, params: String? = null): JsonObject? =
        postInnerTube("browse", buildJsonObject {
            putContext()
            put("browseId", browseId)
            if (params != null) put("params", params)
        })


    suspend fun next(body: JsonObject): JsonObject? = postInnerTube("next", body)


    suspend fun likeSong(videoId: String): Boolean =
        postInnerTube("like/like", buildJsonObject {
            putContext()
            putJsonObject("target") { put("videoId", videoId) }
        }) != null


    suspend fun unlikeSong(videoId: String): Boolean =
        postInnerTube("like/removelike", buildJsonObject {
            putContext()
            putJsonObject("target") { put("videoId", videoId) }
        }) != null


    suspend fun browseContinuation(token: String): JsonObject? {


        val enc = URLEncoder.encode(token, "UTF-8")
        return postInnerTube(
            endpoint = "browse",
            body = buildJsonObject {
                putContext()
                put("continuation", token)
            },
            extraQuery = "&ctoken=$enc&continuation=$enc&type=next",
        )
    }

    private suspend fun postInnerTube(
        endpoint: String,
        body: JsonObject,
        extraQuery: String = "",
    ): JsonObject? {
        return try {
            val url = "https://music.youtube.com/youtubei/v1/$endpoint" +
                "?prettyPrint=false&alt=json$extraQuery"
            val reqBuilder = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                )
                .header("X-Origin", YtMusicAuth.ORIGIN)
                .header("Origin", YtMusicAuth.ORIGIN)
                .header("Referer", "${YtMusicAuth.ORIGIN}/")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/json")
                .header("X-Youtube-Client-Name", "67")
                .header("X-Youtube-Client-Version", CLIENT_VERSION)

            if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)
            YtMusicAuth.sapisidHashHeader(cookie)?.let { reqBuilder.header("Authorization", it) }

            http.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "InnerTube /$endpoint HTTP ${resp.code}: ${text.take(200)}")
                    return null
                }
                json.parseToJsonElement(text).jsonObject
            }
        } catch (e: Throwable) {
            Log.w(TAG, "InnerTube /$endpoint failed: ${e.message}")
            null
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putContext() {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", CLIENT_VERSION)
                put("hl", YtPlayerUtils.contentLanguage)
                put("gl", YtPlayerUtils.contentCountry)
                put("platform", "DESKTOP")
            }
            putJsonObject("user") {
                put("lockedSafetyMode", false)
            }
        }
    }

    companion object {
        private const val TAG = "InnerTube"


        private const val CLIENT_VERSION = "1.20260501.01.00"
    }
}

internal fun JsonElement.findFirst(key: String): JsonElement? {
    when (this) {
        is JsonObject -> {
            this[key]?.let { return it }
            values.forEach { v ->
                val hit = v.findFirst(key)
                if (hit != null) return hit
            }
        }
        is JsonArray -> forEach { v ->
            val hit = v.findFirst(key)
            if (hit != null) return hit
        }
        else -> {}
    }
    return null
}

internal fun JsonElement.findAll(key: String): List<JsonElement> {
    val out = mutableListOf<JsonElement>()
    walk { el ->
        if (el is JsonObject) el[key]?.let(out::add)
    }
    return out
}

internal fun JsonElement.walk(visit: (JsonElement) -> Unit) {
    visit(this)
    when (this) {
        is JsonObject -> values.forEach { it.walk(visit) }
        is JsonArray -> forEach { it.walk(visit) }
        else -> {}
    }
}

internal fun JsonElement.collectResponsiveListItems(): List<JsonObject> =
    findAll("musicResponsiveListItemRenderer")
        .mapNotNull { it as? JsonObject }

internal fun JsonElement.collectTwoRowItems(): List<JsonObject> =
    findAll("musicTwoRowItemRenderer")
        .mapNotNull { it as? JsonObject }

internal fun JsonElement.findContinuationToken(): String? {

    val CONTINUATION_WRAPPER_KEYS = listOf(
        "nextContinuationData",
        "nextRadioContinuationData",
        "timedContinuationData",
        "reloadContinuationData",
    )
    val continuations = findAll("continuations")
    for (cs in continuations) {
        val arr = cs as? JsonArray ?: continue
        for (entry in arr) {
            val obj = entry as? JsonObject ?: continue
            for (key in CONTINUATION_WRAPPER_KEYS) {
                (obj[key] as? JsonObject)?.get("continuation")
                    ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
    }





    for (item in findAll("continuationItemRenderer")) {
        val token = (item as? JsonObject)
            ?.let { it["continuationEndpoint"] as? JsonObject }
            ?.let { it["continuationCommand"] as? JsonObject }
            ?.get("token")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        if (token != null) return token
    }


    return findAll("continuationCommand")
        .mapNotNull { (it as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull }
        .firstOrNull { it.isNotBlank() }
}

internal fun JsonElement?.runsText(): String? {
    if (this !is JsonObject) return null
    val runs = this["runs"] as? JsonArray ?: return (this["simpleText"] as? JsonPrimitive)?.contentOrNull
    return runs.mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull }
        .joinToString("")
        .takeIf { it.isNotBlank() }
}

internal fun JsonElement?.bestThumbnail(): String? {
    if (this !is JsonObject) return null

    fun thumbs(o: JsonObject): JsonArray? {
        (o["thumbnails"] as? JsonArray)?.let { return it }
        val thumbObj = o["thumbnail"] as? JsonObject
        (thumbObj?.get("thumbnails") as? JsonArray)?.let { return it }
        val musicInner = o["musicThumbnailRenderer"] as? JsonObject
        if (musicInner != null) thumbs(musicInner)?.let { return it }
        val rendererOuter = o["thumbnailRenderer"] as? JsonObject
        if (rendererOuter != null) thumbs(rendererOuter)?.let { return it }
        return null
    }
    val list = thumbs(this) ?: return null
    val raw = list.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
        .lastOrNull()
        ?: return null
    return raw.upgradeToHqSize()
}

private fun String.upgradeToHqSize(): String {
    val cut = indexOf('=')
    if (cut < 0) return this
    val base = substring(0, cut)
    return "$base=w544-h544-l90-rj"
}
