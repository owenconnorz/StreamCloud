package com.aioweb.app.data.ytmusic

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
import java.util.concurrent.TimeUnit

/**
 * Thin InnerTube client — the private API `music.youtube.com` uses to fetch its own
 * webapp data. We send the same `client: WEB_REMIX` context Metrolist uses so YT Music
 * returns the sections it would render in a desktop browser.
 *
 * Only the subset we need (`/browse`, `/next`, `/search`) is exposed — the rest of
 * InnerTube is left on the table.
 *
 * **Auth:** every request attaches the captured `Cookie` header plus a freshly-minted
 * `SAPISIDHASH` (see [YtMusicAuth]). Anonymous calls still work — they just return
 * public pages (trending, etc.) instead of personalised data.
 */
internal class InnerTubeClient(private val cookie: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `POST /youtubei/v1/browse` — used for library, playlist pages, artist pages, etc. */
    suspend fun browse(browseId: String, params: String? = null): JsonObject? =
        postInnerTube("browse", buildJsonObject {
            putContext()
            put("browseId", browseId)
            if (params != null) put("params", params)
        })

    /** `POST /youtubei/v1/next` — used to follow playlist pagination cursors. */
    suspend fun next(body: JsonObject): JsonObject? = postInnerTube("next", body)

    /**
     * `POST /youtubei/v1/browse` — page through a playlist or list shelf. The
     * token comes from `nextContinuationData.continuation` (legacy) or
     * `continuationCommand.token` (current) on the previous page.
     *
     * We send the token BOTH in query params (legacy spec) AND the request body
     * (current spec) — InnerTube tolerates the redundancy and ignoring
     * either-shape is what made paging silently truncate at ~100 entries.
     */
    suspend fun browseContinuation(token: String): JsonObject? = postInnerTube(
        endpoint = "browse",
        body = buildJsonObject {
            putContext()
            put("continuation", token)
        },
        extraQuery = "&ctoken=$token&continuation=$token&type=next",
    )

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
                put("hl", "en")
                put("gl", "US")
                put("platform", "DESKTOP")
            }
            putJsonObject("user") {
                put("lockedSafetyMode", false)
            }
        }
    }

    companion object {
        private const val TAG = "InnerTube"
        // Version harvested from music.youtube.com on 2026-02 — tracked by Metrolist too.
        private const val CLIENT_VERSION = "1.20250127.01.00"
    }
}

// ───────────────────────── JSON walking helpers ─────────────────────────

/** Deep-search the element tree for the first object that contains [key]. */
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

/** Collect every object that contains [key], anywhere in the tree. */
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

/** Collects every object that contains a `musicResponsiveListItemRenderer` key.
 *  That renderer is what YT Music uses for songs in lists (liked / playlist pages). */
internal fun JsonElement.collectResponsiveListItems(): List<JsonObject> =
    findAll("musicResponsiveListItemRenderer")
        .mapNotNull { it as? JsonObject }

/** Collects every `musicTwoRowItemRenderer` — the tile used for playlists / albums. */
internal fun JsonElement.collectTwoRowItems(): List<JsonObject> =
    findAll("musicTwoRowItemRenderer")
        .mapNotNull { it as? JsonObject }

/**
 * Pull a `continuations[].nextContinuationData.continuation` (or
 * `continuationCommand.token`) out of any place YT Music nests it. Returns
 * null when the page has no continuation, indicating the playlist is fully
 * loaded.
 */
internal fun JsonElement.findContinuationToken(): String? {
    val continuations = findAll("continuations")
    for (cs in continuations) {
        val arr = cs as? JsonArray ?: continue
        for (entry in arr) {
            val obj = entry as? JsonObject ?: continue
            (obj["nextContinuationData"] as? JsonObject)?.get("continuation")
                ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { return it }
            (obj["nextRadioContinuationData"] as? JsonObject)?.get("continuation")
                ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
    }
    // Newer responses wrap in `continuationCommand.token`.
    return findAll("continuationCommand")
        .mapNotNull { (it as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull }
        .firstOrNull { it.isNotBlank() }
}

/** Extract a runs-style title — YT wraps text as `{ runs: [{ text: "..." }, ...] }`. */
internal fun JsonElement?.runsText(): String? {
    if (this !is JsonObject) return null
    val runs = this["runs"] as? JsonArray ?: return (this["simpleText"] as? JsonPrimitive)?.contentOrNull
    return runs.mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull }
        .joinToString("")
        .takeIf { it.isNotBlank() }
}

/** Pick the largest thumbnail URL out of a `{ thumbnails: [...] }` object.
 *  Handles every layer YouTube nests it at:
 *   • `thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails[]`
 *   • `musicThumbnailRenderer.thumbnail.thumbnails[]`
 *   • plain `{ thumbnails: [...] }`
 *  After picking the URL we also upgrade the size suffix (`=w60-h60-...`) to a
 *  544×544 variant so grid tiles and track rows don't render as 60px blobs. */
internal fun JsonElement?.bestThumbnail(): String? {
    if (this !is JsonObject) return null
    // Drill down through whichever wrapper layer YouTube used.
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

/** YouTube image URLs end with `=w60-h60-l90-rj` or `=s60-c`. Replace the size
 *  (whichever is first) with `w544-h544-l90-rj` to get a crisp 544px artwork. */
private fun String.upgradeToHqSize(): String {
    val cut = indexOf('=')
    if (cut < 0) return this
    val base = substring(0, cut)
    return "$base=w544-h544-l90-rj"
}
