package com.streamcloud.app.data.nuvio

import android.content.Context
import android.util.Log
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.stremio.StremioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "NuvioAccountService"
internal const val SUPABASE_URL = "https://api.nuvio.tv"
internal const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI"
private val JSON_MT = "application/json; charset=utf-8".toMediaType()

@Serializable
data class NuvioUser(
    val id: String,
    val email: String? = null,
)

@Serializable
data class NuvioSession(
    val access_token: String,
    val refresh_token: String,
    val token_type: String? = null,
    val expires_in: Long? = null,
    val user: NuvioUser? = null,
)

data class NuvioSyncResult(
    val plugins: Int = 0,
    val addons: Int = 0,
    val watchProgress: Int = 0,
    val library: Int = 0,
)

data class NuvioPullResult(
    val watchProgress: Int = 0,
    val library: Int = 0,
    val plugins: Int = 0,
    val addons: Int = 0,
)

class NuvioAccountService(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun signIn(email: String, password: String): Result<NuvioSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("email", email.trim())
                    put("password", password)
                }.toString()
                val req = Request.Builder()
                    .url("$SUPABASE_URL/auth/v1/token?grant_type=password")
                    .post(body.toRequestBody(JSON_MT))
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Content-Type", "application/json")
                    .build()
                val resp = http.newCall(req).execute()
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        val obj = json.parseToJsonElement(text) as? JsonObject
                        (obj?.get("msg") ?: obj?.get("message") ?: obj?.get("error_description"))
                            ?.jsonPrimitive?.content
                    }.getOrNull() ?: text.take(200)
                    error(msg.ifBlank { "Sign in failed (${resp.code})" })
                }
                json.decodeFromString<NuvioSession>(text)
            }
        }

    suspend fun refreshToken(refreshToken: String): Result<NuvioSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject { put("refresh_token", refreshToken) }.toString()
                val req = Request.Builder()
                    .url("$SUPABASE_URL/auth/v1/token?grant_type=refresh_token")
                    .post(body.toRequestBody(JSON_MT))
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Content-Type", "application/json")
                    .build()
                val resp = http.newCall(req).execute()
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Token refresh failed (${resp.code})")
                json.decodeFromString<NuvioSession>(text)
            }
        }

    suspend fun signOut(accessToken: String) = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/logout")
                .post("{}".toRequestBody(JSON_MT))
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $accessToken")
                .build()
            http.newCall(req).execute().close()
        }
    }

    private suspend fun rpc(
        function: String,
        params: JsonObject,
        accessToken: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/rpc/$function")
                .post(params.toString().toRequestBody(JSON_MT))
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .build()
            val resp = http.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "RPC $function ${resp.code}: $text")
                error("RPC $function failed (${resp.code})")
            }
            text
        }.also { if (it.isFailure) Log.e(TAG, "rpc $function", it.exceptionOrNull()) }
    }

    suspend fun syncAll(accessToken: String): NuvioSyncResult = withContext(Dispatchers.IO) {
        val db = LibraryDb.get(context)
        val pluginRepo = PluginRepository(context)
        val stremioRepo = StremioRepository(context)
        var plugins = 0
        var addons = 0
        var progress = 0
        var library = 0

        runCatching {
            val repos = pluginRepo.repos.first()
            val arr = buildJsonArray {
                repos.forEachIndexed { i, repo ->
                    addJsonObject {
                        put("url", repo.url)
                        put("name", repo.name)
                        put("enabled", true)
                        put("sort_order", i)
                    }
                }
            }
            rpc(
                "sync_push_plugins",
                buildJsonObject { put("p_plugins", arr); put("p_profile_id", 1) },
                accessToken,
            )
            plugins = repos.size
        }

        runCatching {
            val addonList = stremioRepo.addons.first()
            val arr = buildJsonArray {
                addonList.forEachIndexed { i, addon ->
                    addJsonObject {
                        put("url", addon.manifestUrl)
                        put("sort_order", i)
                    }
                }
            }
            rpc(
                "sync_push_addons",
                buildJsonObject { put("p_addons", arr); put("p_profile_id", 1) },
                accessToken,
            )
            addons = addonList.size
        }

        runCatching {
            val entries = db.watchProgress().getAllEntries()
            val arr = buildJsonArray {
                entries.forEach { e ->
                    val cid = "tmdb:${e.tmdbId}"
                    val ctype = if (e.mediaType == "tv") "series" else "movie"
                    addJsonObject {
                        put("content_id", cid)
                        put("content_type", ctype)
                        put("video_id", cid)
                        put("position", e.positionMs)
                        put("duration", e.durationMs)
                        put("last_watched", e.updatedAt)
                        put("progress_key", cid)
                    }
                }
            }
            rpc(
                "sync_push_watch_progress",
                buildJsonObject { put("p_entries", arr); put("p_profile_id", 1) },
                accessToken,
            )
            progress = entries.size
        }

        runCatching {
            val items = db.watchlist().all().first()
            val arr = buildJsonArray {
                items.forEach { item ->
                    val cid = "tmdb:${item.tmdbId}"
                    val ctype = if (item.mediaType == "tv") "series" else "movie"
                    addJsonObject {
                        put("content_id", cid)
                        put("content_type", ctype)
                        put("name", item.title)
                        item.posterUrl?.let { put("poster", it) }
                        put("poster_shape", "POSTER")
                        put("added_at", item.addedAt)
                    }
                }
            }
            rpc(
                "sync_push_library",
                buildJsonObject { put("p_items", arr); put("p_profile_id", 1) },
                accessToken,
            )
            library = items.size
        }

        NuvioSyncResult(plugins = plugins, addons = addons, watchProgress = progress, library = library)
    }

    /**
     * Pull data FROM the Nuvio cloud into the local Room database.
     * This is the counterpart of [syncAll] (push).  The home screen's
     * Continue-Watching and Library rows are Flow-driven from Room, so they
     * update automatically once this completes.
     */
    suspend fun syncPull(accessToken: String): NuvioPullResult = withContext(Dispatchers.IO) {
        val db         = LibraryDb.get(context)
        val pluginRepo = PluginRepository(context)
        var progress = 0
        var library  = 0
        var plugins  = 0
        var addons   = 0

        // ── Watch progress ────────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_watch_progress",
                buildJsonObject { put("p_profile_id", JsonPrimitive(1)) },
                accessToken,
            ).getOrThrow()
            val arr = json.parseToJsonElement(text).jsonArray
            arr.forEach { el ->
                val obj       = el.jsonObject
                val contentId = obj["content_id"]?.jsonPrimitive?.content ?: return@forEach
                val tmdbId    = contentId.removePrefix("tmdb:").toLongOrNull() ?: return@forEach
                val posMs     = obj["position"]?.jsonPrimitive?.longOrNull   ?: return@forEach
                val durMs     = obj["duration"]?.jsonPrimitive?.longOrNull   ?: return@forEach
                val updAt     = obj["last_watched"]?.jsonPrimitive?.longOrNull
                                ?: System.currentTimeMillis()
                val ctype     = obj["content_type"]?.jsonPrimitive?.content ?: "movie"
                val title     = obj["name"]?.jsonPrimitive?.content.orEmpty()
                val poster    = obj["poster"]?.jsonPrimitive?.content
                if (durMs > 0 && posMs > 0) {
                    db.watchProgress().upsert(
                        WatchProgressEntity(
                            tmdbId     = tmdbId,
                            title      = title,
                            posterUrl  = poster,
                            mediaType  = if (ctype == "series") "tv" else "movie",
                            positionMs = posMs,
                            durationMs = durMs,
                            updatedAt  = updAt,
                        )
                    )
                    progress++
                }
            }
        }.onFailure { Log.w(TAG, "syncPull watch_progress", it) }

        // ── Library / watchlist ───────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_library",
                buildJsonObject { put("p_profile_id", JsonPrimitive(1)) },
                accessToken,
            ).getOrThrow()
            val arr = json.parseToJsonElement(text).jsonArray
            arr.forEach { el ->
                val obj       = el.jsonObject
                val contentId = obj["content_id"]?.jsonPrimitive?.content ?: return@forEach
                val tmdbId    = contentId.removePrefix("tmdb:").toLongOrNull() ?: return@forEach
                val title     = obj["name"]?.jsonPrimitive?.content ?: return@forEach
                val poster    = obj["poster"]?.jsonPrimitive?.content
                val ctype     = obj["content_type"]?.jsonPrimitive?.content ?: "movie"
                val addedAt   = obj["added_at"]?.jsonPrimitive?.longOrNull
                                ?: System.currentTimeMillis()
                db.watchlist().add(
                    WatchlistEntity(
                        tmdbId    = tmdbId,
                        title     = title,
                        posterUrl = poster,
                        mediaType = if (ctype == "series") "tv" else "movie",
                        addedAt   = addedAt,
                    )
                )
                library++
            }
        }.onFailure { Log.w(TAG, "syncPull library", it) }

        // ── Plugin repos ──────────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_plugins",
                buildJsonObject { put("p_profile_id", JsonPrimitive(1)) },
                accessToken,
            ).getOrThrow()
            val arr = json.parseToJsonElement(text).jsonArray
            arr.forEach { el ->
                val obj  = el.jsonObject
                val url  = obj["url"]?.jsonPrimitive?.content  ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.content ?: url
                pluginRepo.addRepo(name = name, url = url)
                plugins++
            }
        }.onFailure { Log.w(TAG, "syncPull plugins", it) }

        // ── Stremio addons ────────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_addons",
                buildJsonObject { put("p_profile_id", JsonPrimitive(1)) },
                accessToken,
            ).getOrThrow()
            val stremioRepo = StremioRepository(context)
            val arr = json.parseToJsonElement(text).jsonArray
            arr.forEach { el ->
                val obj = el.jsonObject
                val url = obj["url"]?.jsonPrimitive?.content ?: return@forEach
                runCatching { stremioRepo.addAddon(url) }
                addons++
            }
        }.onFailure { Log.w(TAG, "syncPull addons", it) }

        NuvioPullResult(watchProgress = progress, library = library, plugins = plugins, addons = addons)
    }

    companion object {
        @Volatile private var INSTANCE: NuvioAccountService? = null
        fun get(ctx: Context): NuvioAccountService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NuvioAccountService(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
