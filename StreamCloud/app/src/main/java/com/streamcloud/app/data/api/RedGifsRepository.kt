package com.streamcloud.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object RedGifsRepository {

    private const val BASE       = "https://api.redgifs.com"
    private const val USER_AGENT = "android:com.streamcloud.app:v1.0.0"

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiry: Long    = 0L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20,  TimeUnit.SECONDS)
            .build()
    }

    private suspend fun getToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { tokenExpiry > now }?.let { return@withContext it }

        val req = Request.Builder()
            .url("$BASE/v2/auth/temporary")
            .header("User-Agent", USER_AGENT)
            .build()
        val body = client.newCall(req).execute().body?.string()
            ?: throw Exception("Empty token response")
        val token = JSONObject(body).getString("token")
        cachedToken  = token
        tokenExpiry  = now + 50 * 60 * 1000L // 50-minute cache (tokens last 1h)
        token
    }

    suspend fun fetchTrending(page: Int = 1, count: Int = 20): Pair<List<AdultItem>, Boolean> =
        doFetch("$BASE/v2/gifs/trending?count=$count&page=$page", page)

    suspend fun fetchTag(tag: String, page: Int = 1, count: Int = 20): Pair<List<AdultItem>, Boolean> {
        val encoded = URLEncoder.encode(tag, "UTF-8")
        return doFetch("$BASE/v2/gifs/search?search_text=$encoded&count=$count&page=$page", page)
    }

    private suspend fun doFetch(url: String, page: Int): Pair<List<AdultItem>, Boolean> =
        withContext(Dispatchers.IO) {
            val tok = getToken()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $tok")
                .header("User-Agent", USER_AGENT)
                .build()

            val resp = client.newCall(req).execute()
            val bodyStr = resp.body?.string() ?: throw Exception("Empty response")
            if (!resp.isSuccessful) {
                if (resp.code == 401) cachedToken = null
                throw Exception("RedGifs API error ${resp.code}")
            }

            val json      = JSONObject(bodyStr)
            val gifsArr   = json.optJSONArray("gifs") ?: return@withContext emptyList<AdultItem>() to false
            val totalPages = json.optInt("pages", 1)
            val hasMore   = page < totalPages

            val items = (0 until gifsArr.length()).mapNotNull { i ->
                val gif   = gifsArr.getJSONObject(i)
                val id    = gif.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val urls  = gif.optJSONObject("urls")  ?: return@mapNotNull null
                val video = urls.optString("hd").takeIf { it.isNotBlank() }
                    ?: urls.optString("sd").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val thumb = urls.optString("poster").takeIf { it.isNotBlank() }
                    ?: urls.optString("thumbnail").takeIf { it.isNotBlank() }
                val title  = gif.optString("title").takeIf { it.isNotBlank() }
                    ?: gif.optString("userName").takeIf { it.isNotBlank() }
                    ?: "RedGifs"
                val tagsArr = gif.optJSONArray("tags")
                val tag     = (0 until (tagsArr?.length() ?: 0))
                    .mapNotNull { tagsArr?.getString(it)?.takeIf { s -> s.isNotBlank() } }
                    .firstOrNull()

                AdultItem(
                    id            = id,
                    title         = title,
                    thumbnail     = thumb,
                    previewImage  = thumb,
                    durationLabel = null,
                    streamUrl     = video,
                    isVideo       = true,
                    source        = AdultSource.RedGifs,
                    tags          = tag,
                )
            }
            items to hasMore
        }

    val POPULAR_TAGS = listOf(
        "trending", "amateur", "blowjob", "solo", "lesbian",
        "busty", "redhead", "homemade", "facial", "asian",
    )
}
