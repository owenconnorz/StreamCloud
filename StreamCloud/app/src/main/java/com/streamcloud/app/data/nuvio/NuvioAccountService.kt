package com.streamcloud.app.data.nuvio

import android.content.Context
import android.util.Log
import com.streamcloud.app.data.library.LibraryDb
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "NuvioAccountService"
internal const val SUPABASE_URL = "https://dpyhjjcoabcglfmgecug.supabase.co"
internal const val SUPABASE_ANON_KEY = "sb_publishable_zcNkgqGJjBtj8GoRlMvl9A_zkdmXhf5"
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

    companion object {
        @Volatile private var INSTANCE: NuvioAccountService? = null
        fun get(ctx: Context): NuvioAccountService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NuvioAccountService(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
