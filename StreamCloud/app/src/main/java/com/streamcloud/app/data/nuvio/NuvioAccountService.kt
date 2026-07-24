package com.streamcloud.app.data.nuvio

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.UserCollectionEntity
import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.stremio.StremioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "NuvioAccountService"
internal const val SUPABASE_URL  = "https://api.nuvio.tv"
internal const val SUPABASE_ANON_KEY =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI"
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
    val plugins: Int   = 0,
    val addons: Int    = 0,
    val watchProgress: Int = 0,
    val library: Int   = 0,
)

data class NuvioPullResult(
    val watchProgress: Int  = 0,
    val library: Int        = 0,
    val collections: Int    = 0,
    val watchedItems: Int   = 0,
    val plugins: Int        = 0,
    val addons: Int         = 0,
)

class NuvioAccountService(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30,  TimeUnit.SECONDS)
        .build()

    /** Stable per-device client ID required by the Nuvio sync API. */
    @SuppressLint("HardwareIds")
    private val clientId: String by lazy {
        "android-" + (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown")
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

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
            http.newCall(
                Request.Builder()
                    .url("$SUPABASE_URL/auth/v1/logout")
                    .post("{}".toRequestBody(JSON_MT))
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $accessToken")
                    .build()
            ).execute().close()
        }
    }

    // ── Low-level RPC helper ─────────────────────────────────────────────────

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
                Log.w(TAG, "RPC $function ${resp.code}: ${text.take(200)}")
                error("RPC $function failed (${resp.code}): ${text.take(100)}")
            }
            text
        }.also { if (it.isFailure) Log.e(TAG, "rpc $function", it.exceptionOrNull()) }
    }

    // ── Helper: resolve profile_id for a user ────────────────────────────────

    private suspend fun resolveProfileId(accessToken: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            val text = rpc("sync_pull_profiles", buildJsonObject { }, accessToken).getOrThrow()
            val el   = json.parseToJsonElement(text)
            val arr  = when (el) {
                is JsonArray  -> el
                is JsonObject -> el["profiles"]?.jsonArray ?: JsonArray(emptyList())
                else          -> JsonArray(emptyList())
            }
            // Primary profile is the one with the lowest profile_index (usually 1)
            arr.mapNotNull { it.jsonObject["profile_id"]?.jsonPrimitive?.intOrNull }
                .minOrNull() ?: 1
        }.getOrDefault(1)
    }

    // ── PUSH (local → cloud) ─────────────────────────────────────────────────

    suspend fun syncAll(accessToken: String): NuvioSyncResult = withContext(Dispatchers.IO) {
        val db          = LibraryDb.get(context)
        val pluginRepo  = PluginRepository(context)
        val stremioRepo = StremioRepository(context)
        val profileId   = resolveProfileId(accessToken)
        var plugins = 0; var addons = 0; var progress = 0; var library = 0

        // Push plugin repos
        runCatching {
            val repos = pluginRepo.repos.first()
            val arr = buildJsonArray {
                repos.forEachIndexed { i, repo ->
                    addJsonObject {
                        put("url", repo.url); put("name", repo.name)
                        put("enabled", true); put("sort_order", i)
                    }
                }
            }
            rpc("sync_push_plugins", buildJsonObject {
                put("p_plugins", arr)
                put("p_profile_id", profileId)
                put("p_origin_client_id", clientId)
            }, accessToken)
            plugins = repos.size
        }.onFailure { Log.w(TAG, "push plugins", it) }

        // Push Stremio addons
        runCatching {
            val addonList = stremioRepo.addons.first()
            val arr = buildJsonArray {
                addonList.forEachIndexed { i, addon ->
                    addJsonObject { put("url", addon.manifestUrl); put("sort_order", i) }
                }
            }
            rpc("sync_push_addons", buildJsonObject {
                put("p_addons", arr)
                put("p_profile_id", profileId)
                put("p_origin_client_id", clientId)
            }, accessToken)
            addons = addonList.size
        }.onFailure { Log.w(TAG, "push addons", it) }

        // Push watch progress
        runCatching {
            val entries = db.watchProgress().getAllEntries()
            val arr = buildJsonArray {
                entries.forEach { e ->
                    val cid   = "tmdb:${e.tmdbId}"
                    val ctype = if (e.mediaType == "tv") "series" else "movie"
                    addJsonObject {
                        put("content_id",  cid)
                        put("content_type", ctype)
                        put("video_id",    cid)
                        put("position",    e.positionMs)
                        put("duration",    e.durationMs)
                        put("last_watched", e.updatedAt)
                        put("progress_key", cid)
                    }
                }
            }
            rpc("sync_push_watch_progress", buildJsonObject {
                put("p_entries",    arr)
                put("p_profile_id", profileId)
            }, accessToken)
            progress = entries.size
        }.onFailure { Log.w(TAG, "push watch_progress", it) }

        // Push library / watchlist
        runCatching {
            val items = db.watchlist().all().first()
            val arr = buildJsonArray {
                items.forEach { item ->
                    val cid   = "tmdb:${item.tmdbId}"
                    val ctype = if (item.mediaType == "tv") "series" else "movie"
                    addJsonObject {
                        put("content_id",  cid)
                        put("content_type", ctype)
                        put("name",        item.title)
                        item.posterUrl?.let { put("poster", it) }
                        put("poster_shape", "POSTER")
                        put("added_at",    item.addedAt)
                    }
                }
            }
            rpc("sync_push_library", buildJsonObject {
                put("p_items",      arr)
                put("p_profile_id", profileId)
            }, accessToken)
            library = items.size
        }.onFailure { Log.w(TAG, "push library", it) }

        NuvioSyncResult(plugins = plugins, addons = addons, watchProgress = progress, library = library)
    }

    // ── PULL (cloud → local) ─────────────────────────────────────────────────

    /**
     * Pull everything from the Nuvio cloud into the local Room DB.
     * Handles watch progress, library, collections, watched items, addons, and plugins.
     * Home screen rows update automatically via Room Flows once data is written.
     */
    suspend fun syncPull(accessToken: String): NuvioPullResult = withContext(Dispatchers.IO) {
        val db          = LibraryDb.get(context)
        val pluginRepo  = PluginRepository(context)
        val stremioRepo = StremioRepository(context)
        val profileId   = resolveProfileId(accessToken)
        var progress = 0; var library = 0; var collections = 0
        var watchedItems = 0; var plugins = 0; var addons = 0

        Log.d(TAG, "syncPull start — profileId=$profileId")

        // ── 1. Watch progress (Continue Watching row) ─────────────────────
        runCatching {
            val text = rpc("sync_pull_watch_progress", buildJsonObject {
                put("p_profile_id", profileId)
                put("p_limit", 200)
            }, accessToken).getOrThrow()

            val arr = json.parseToJsonElement(text).let {
                when (it) {
                    is JsonArray  -> it
                    is JsonObject -> it["data"]?.jsonArray ?: JsonArray(emptyList())
                    else          -> JsonArray(emptyList())
                }
            }
            arr.forEach { el ->
                val obj       = el.jsonObject
                val contentId = obj["content_id"]?.jsonPrimitive?.content ?: return@forEach
                val tmdbId    = contentId.removePrefix("tmdb:").toLongOrNull() ?: return@forEach
                val posMs     = obj["position"]?.jsonPrimitive?.longOrNull  ?: return@forEach
                val durMs     = obj["duration"]?.jsonPrimitive?.longOrNull  ?: return@forEach
                if (posMs <= 0 || durMs <= 0) return@forEach
                val lastWatched = obj["last_watched"]?.jsonPrimitive?.longOrNull
                                  ?: System.currentTimeMillis()
                val ctype = obj["content_type"]?.jsonPrimitive?.content ?: "movie"
                db.watchProgress().upsert(WatchProgressEntity(
                    tmdbId     = tmdbId,
                    title      = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    posterUrl  = obj["poster"]?.jsonPrimitive?.content,
                    mediaType  = if (ctype == "series") "tv" else "movie",
                    positionMs = posMs,
                    durationMs = durMs,
                    updatedAt  = lastWatched,
                ))
                progress++
            }
            Log.d(TAG, "syncPull watch_progress: $progress")
        }.onFailure { Log.w(TAG, "syncPull watch_progress", it) }

        // ── 2. Library items (Watchlist / Library row) ────────────────────
        runCatching {
            var offset = 0
            val pageSize = 100
            while (true) {
                val text = rpc("sync_pull_library", buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_limit",  pageSize)
                    put("p_offset", offset)
                }, accessToken).getOrThrow()

                val arr = json.parseToJsonElement(text).let {
                    when (it) {
                        is JsonArray  -> it
                        is JsonObject -> it["data"]?.jsonArray ?: JsonArray(emptyList())
                        else          -> JsonArray(emptyList())
                    }
                }
                if (arr.isEmpty()) break
                arr.forEach { el ->
                    val obj       = el.jsonObject
                    val contentId = obj["content_id"]?.jsonPrimitive?.content ?: return@forEach
                    val tmdbId    = contentId.removePrefix("tmdb:").toLongOrNull() ?: return@forEach
                    val title     = obj["name"]?.jsonPrimitive?.content
                                    ?: obj["title"]?.jsonPrimitive?.content ?: return@forEach
                    val poster    = obj["poster"]?.jsonPrimitive?.content
                                    ?: obj["poster_url"]?.jsonPrimitive?.content
                    val ctype     = obj["content_type"]?.jsonPrimitive?.content ?: "movie"
                    val addedAt   = obj["added_at"]?.jsonPrimitive?.longOrNull
                                    ?: System.currentTimeMillis()
                    db.watchlist().add(WatchlistEntity(
                        tmdbId    = tmdbId,
                        title     = title,
                        posterUrl = poster,
                        mediaType = if (ctype == "series") "tv" else "movie",
                        addedAt   = addedAt,
                    ))
                    library++
                }
                if (arr.size < pageSize) break
                offset += pageSize
            }
            Log.d(TAG, "syncPull library: $library")
        }.onFailure { Log.w(TAG, "syncPull library", it) }

        // ── 3. Collections ────────────────────────────────────────────────
        runCatching {
            val text = rpc("sync_pull_collections", buildJsonObject {
                put("p_profile_id", profileId)
            }, accessToken).getOrThrow()

            // collections_json is a JSONB blob – can be an object with a list
            // or an array directly, depending on how the RPC unwraps it.
            val rootEl = json.parseToJsonElement(text)
            // Find the collections array wherever it lives
            fun findArray(el: kotlinx.serialization.json.JsonElement): JsonArray? = when {
                el is JsonArray -> el
                el is JsonObject -> {
                    el["collections"]?.let { findArray(it) }
                        ?: el["collections_json"]?.let {
                            runCatching {
                                findArray(json.parseToJsonElement(it.jsonPrimitive.content))
                            }.getOrNull()
                        }
                }
                else -> null
            }

            val colArr = findArray(rootEl) ?: JsonArray(emptyList())
            val existing = db.userCollections().all().first().map { it.name }.toHashSet()
            colArr.forEach { el ->
                val col  = el.jsonObject
                val name = col["name"]?.jsonPrimitive?.content
                           ?: col["label"]?.jsonPrimitive?.content ?: return@forEach
                if (name !in existing) {
                    db.userCollections().upsert(UserCollectionEntity(
                        name     = name,
                        coverUrl = col["cover_url"]?.jsonPrimitive?.content
                                   ?: col["poster"]?.jsonPrimitive?.content ?: "",
                        isPinned = col["is_pinned"]?.jsonPrimitive?.content == "true",
                    ))
                    collections++
                }
            }
            Log.d(TAG, "syncPull collections: $collections")
        }.onFailure { Log.w(TAG, "syncPull collections", it) }

        // ── 4. Watched items (mark-as-watched history) ────────────────────
        runCatching {
            val text = rpc("sync_pull_watched_items", buildJsonObject {
                put("p_profile_id", profileId)
                put("p_page", 1)
                put("p_page_size", 200)
            }, accessToken).getOrThrow()

            val arr = json.parseToJsonElement(text).let {
                when (it) {
                    is JsonArray  -> it
                    is JsonObject -> it["data"]?.jsonArray ?: JsonArray(emptyList())
                    else          -> JsonArray(emptyList())
                }
            }
            arr.forEach { el ->
                val obj       = el.jsonObject
                val contentId = obj["content_id"]?.jsonPrimitive?.content ?: return@forEach
                val tmdbId    = contentId.removePrefix("tmdb:").toLongOrNull() ?: return@forEach
                val title     = obj["title"]?.jsonPrimitive?.content.orEmpty()
                val ctype     = obj["content_type"]?.jsonPrimitive?.content ?: "movie"
                val watchedAt = obj["watched_at"]?.jsonPrimitive?.longOrNull
                                ?: System.currentTimeMillis()
                // Store in watchlist if not already present
                db.watchlist().add(WatchlistEntity(
                    tmdbId    = tmdbId,
                    title     = title,
                    posterUrl = null,
                    mediaType = if (ctype == "series") "tv" else "movie",
                    addedAt   = watchedAt,
                ))
                watchedItems++
            }
            Log.d(TAG, "syncPull watched_items: $watchedItems")
        }.onFailure { Log.w(TAG, "syncPull watched_items", it) }

        // ── 5. Addons (profile settings blob) ────────────────────────────
        runCatching {
            val text = rpc("sync_pull_profile_settings_blob", buildJsonObject {
                put("p_profile_id", profileId)
                put("p_platform", "android")
            }, accessToken).getOrThrow()

            val root = json.parseToJsonElement(text).let {
                when (it) {
                    is JsonArray  -> it.firstOrNull()?.jsonObject
                    is JsonObject -> it
                    else          -> null
                }
            } ?: return@runCatching

            val settingsJson = root["settings_json"]?.let {
                when (it) {
                    is JsonObject -> it
                    else -> runCatching {
                        json.parseToJsonElement(it.jsonPrimitive.content).jsonObject
                    }.getOrNull()
                }
            } ?: return@runCatching

            // Parse addons list
            val addonsArr = settingsJson["addons"]?.jsonArray
                ?: settingsJson["stremio_addons"]?.jsonArray
            addonsArr?.forEach { el ->
                val url = when (el) {
                    is JsonObject  -> el["url"]?.jsonPrimitive?.content
                                     ?: el["manifestUrl"]?.jsonPrimitive?.content
                    is JsonPrimitive -> el.content
                    else -> null
                } ?: return@forEach
                runCatching { stremioRepo.addAddon(url) }
                addons++
            }

            // Parse plugin repos list
            val pluginsArr = settingsJson["plugins"]?.jsonArray
                ?: settingsJson["plugin_repos"]?.jsonArray
            pluginsArr?.forEach { el ->
                val obj = el.jsonObject
                val url  = obj["url"]?.jsonPrimitive?.content ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.content ?: url
                runCatching { pluginRepo.addRepo(name = name, url = url) }
                plugins++
            }
            Log.d(TAG, "syncPull addons=$addons plugins=$plugins from settings blob")
        }.onFailure { Log.w(TAG, "syncPull settings_blob", it) }

        // ── 6. Fallback: sync_pull_provider_credentials for extra addons ──
        // (no-op if already handled above, safe to run)

        NuvioPullResult(
            watchProgress = progress,
            library       = library,
            collections   = collections,
            watchedItems  = watchedItems,
            plugins       = plugins,
            addons        = addons,
        )
    }

    companion object {
        @Volatile private var INSTANCE: NuvioAccountService? = null
        fun get(ctx: Context): NuvioAccountService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NuvioAccountService(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
