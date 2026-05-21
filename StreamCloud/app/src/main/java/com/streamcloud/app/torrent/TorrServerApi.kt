package com.streamcloud.app.torrent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TorrServerFile(
    val id: Int,
    val path: String,
    val length: Long,
)

data class TorrServerStats(
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val peers: Int,
    val seeds: Int,
    val preloadedBytes: Long,
    val loadedSize: Long,
    val torrentSize: Long,
    val files: List<TorrServerFile>,
)

class TorrServerApi(private val binary: TorrServerBinary) {

    companion object {
        private const val TAG = "TorrServerApi"
        private val JSON = "application/json".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val base: String get() = binary.baseUrl

    suspend fun addTorrent(magnetLink: String, title: String? = null): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("action", "add")
                put("link", magnetLink)
                put("save_to_db", false)
                if (title != null) put("title", title)
            }
            runCatching {
                val req = Request.Builder()
                    .url("$base/torrents")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "addTorrent HTTP ${resp.code}")
                        return@withContext null
                    }
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    json.optString("hash", "").ifEmpty { null }
                }
            }.getOrElse { Log.e(TAG, "addTorrent error", it); null }
        }

    suspend fun getTorrentStats(hash: String): TorrServerStats? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("action", "get")
            put("hash", hash)
        }
        runCatching {
            val req = Request.Builder()
                .url("$base/torrents")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: "{}")
                val files = mutableListOf<TorrServerFile>()
                val arr = json.optJSONArray("file_stats") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    files.add(TorrServerFile(
                        id     = f.optInt("id", i + 1),
                        path   = f.optString("path", ""),
                        length = f.optLong("length", 0),
                    ))
                }
                TorrServerStats(
                    downloadSpeed  = json.optLong("download_speed", 0),
                    uploadSpeed    = json.optLong("upload_speed", 0),
                    peers          = json.optInt("active_peers", 0),
                    seeds          = json.optInt("connected_seeders", 0),
                    preloadedBytes = json.optLong("preloaded_bytes", 0),
                    loadedSize     = json.optLong("loaded_size", 0),
                    torrentSize    = json.optLong("torrent_size", 0),
                    files          = files,
                )
            }
        }.getOrElse { Log.w(TAG, "getTorrentStats error", it); null }
    }

    suspend fun dropTorrent(hash: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("action", "drop")
            put("hash", hash)
        }
        runCatching {
            val req = Request.Builder()
                .url("$base/torrents")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().close()
        }
    }


    fun getStreamUrl(magnetLink: String, fileIdx: Int): String {
        val encoded = URLEncoder.encode(magnetLink, "UTF-8")
        return "$base/stream?link=$encoded&index=$fileIdx&play"
    }
}
