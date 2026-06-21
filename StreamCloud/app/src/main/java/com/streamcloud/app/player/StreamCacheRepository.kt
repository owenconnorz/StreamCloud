package com.streamcloud.app.player

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

object StreamCacheRepository {

    private const val PREFS_NAME = "stream_cache_v1"
    private const val TTL_MS = 5L * 60 * 60 * 1000 // 5 hours

    fun movieKey(imdbId: String): String = "movie:$imdbId"

    fun episodeKey(imdbId: String, season: Int, episode: Int): String =
        "episode:$imdbId:$season:$episode"

    fun get(context: Context, key: String): List<PlayerSource>? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            if (System.currentTimeMillis() - obj.getLong("ts") > TTL_MS) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit { remove(key) }
                return null
            }
            val arr = obj.getJSONArray("sources")
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val headers = mutableMapOf<String, String>()
                s.optJSONObject("headers")?.keys()?.forEach { k ->
                    headers[k] = s.getJSONObject("headers").getString(k)
                }
                PlayerSource(
                    id = s.getString("id"),
                    url = s.getString("url"),
                    label = s.getString("label"),
                    addonName = s.getString("addonName"),
                    qualityTag = s.optString("qualityTag").takeIf { it.isNotEmpty() },
                    isMagnet = s.optBoolean("isMagnet", false),
                    headers = headers,
                )
            }.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    fun put(context: Context, key: String, sources: List<PlayerSource>) {
        if (sources.isEmpty()) return
        val arr = JSONArray()
        sources.forEach { src ->
            val s = JSONObject()
            s.put("id", src.id)
            s.put("url", src.url)
            s.put("label", src.label)
            s.put("addonName", src.addonName)
            src.qualityTag?.let { s.put("qualityTag", it) }
            s.put("isMagnet", src.isMagnet)
            if (src.headers.isNotEmpty()) {
                val h = JSONObject()
                src.headers.forEach { (k, v) -> h.put(k, v) }
                s.put("headers", h)
            }
            arr.put(s)
        }
        val entry = JSONObject()
        entry.put("ts", System.currentTimeMillis())
        entry.put("sources", arr)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(key, entry.toString()) }
    }
}
