package com.streamcloud.app.data.nuvio

import android.content.Context
import android.util.Log
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.SettingsRepository
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.UserCollectionEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.stremio.StremioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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

private const val NUVIO_CLOUD_SOURCE = "__nuvio__"

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
    val watchedItems: Int = 0,
    val library: Int = 0,
    val collections: Int = 0,
    val addons: Int = 0,
    val plugins: Int = 0,
)

@Serializable
private data class PullAddon(
    val url: String = "",
    val sort_order: Int = 0,
)

@Serializable
private data class PullPlugin(
    val url: String = "",
    val name: String? = null,
    val enabled: Boolean = true,
)

@Serializable
private data class PullWatchProgress(
    val content_id: String = "",
    val content_type: String = "movie",
    val position: Long = 0L,
    val duration: Long = 0L,
    val last_watched: Long = 0L,
    val name: String? = null,
    val poster: String? = null,
)

@Serializable
private data class PullLibraryItem(
    val content_id: String = "",
    val content_type: String = "movie",
    val name: String = "",
    val poster: String? = null,
    val added_at: Long = 0L,
)

@Serializable
private data class PullCollectionFolder(
    val name: String = "",
    val cover_url: String = "",
    val provider_type: String = "tmdb",
    val linked_category_id: String = "",
    val tile_shape: String = "wide",
    val sort_order: Int = 0,
)

@Serializable
private data class PullCollection(
    val name: String = "",
    val is_pinned: Boolean = true,
    val sort_order: Int = 0,
    val folders: List<PullCollectionFolder> = emptyList(),
)

class NuvioAccountService(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
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

    suspend fun syncPull(accessToken: String): NuvioPullResult = withContext(Dispatchers.IO) {
        val db = LibraryDb.get(context)
        val stremioRepo = StremioRepository(context)
        val pluginRepo = PluginRepository(context)

        var pulledAddons = 0
        var pulledPlugins = 0
        var pulledProgress = 0
        var pulledWatched = 0
        var pulledLibrary = 0
        var pulledCollections = 0

        // ── Stremio addons ──────────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_addons",
                buildJsonObject { put("p_profile_id", 1) },
                accessToken,
            ).getOrThrow()
            val addons = json.decodeFromString(ListSerializer(PullAddon.serializer()), text)
            val existing = stremioRepo.addons.first().map { it.manifestUrl }.toSet()
            addons.forEach { pulled ->
                val url = pulled.url.trim()
                if (url.isNotBlank() && url !in existing) {
                    runCatching { stremioRepo.addAddon(url) }
                    pulledAddons++
                }
            }
        }.onFailure { Log.w(TAG, "pull addons: ${it.message}") }

        // ── CloudStream repos ───────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_plugins",
                buildJsonObject { put("p_profile_id", 1) },
                accessToken,
            ).getOrThrow()
            val plugins = json.decodeFromString(ListSerializer(PullPlugin.serializer()), text)
            val existingUrls = pluginRepo.repos.first().map { it.url }.toSet()
            plugins.forEach { pulled ->
                val url = pulled.url.trim()
                if (url.isNotBlank() && url !in existingUrls) {
                    val name = pulled.name?.takeIf { it.isNotBlank() } ?: url.substringAfterLast("/").substringBefore(".")
                    runCatching { pluginRepo.addRepo(name, url) }
                    pulledPlugins++
                }
            }
        }.onFailure { Log.w(TAG, "pull plugins: ${it.message}") }

        // ── Watch progress ──────────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_watch_progress",
                buildJsonObject { put("p_profile_id", 1) },
                accessToken,
            ).getOrThrow()
            val entries = json.decodeFromString(ListSerializer(PullWatchProgress.serializer()), text)
            val progressDao = db.watchProgress()
            entries.forEach { e ->
                val tmdbId = parseTmdbId(e.content_id) ?: return@forEach
                val mediaType = if (e.content_type == "series") "tv" else "movie"
                val existing = progressDao.byId(tmdbId)
                val updatedAt = e.last_watched.takeIf { it > 0 } ?: System.currentTimeMillis()
                if (existing == null || existing.updatedAt < updatedAt) {
                    progressDao.upsert(
                        WatchProgressEntity(
                            tmdbId = tmdbId,
                            title = e.name?.takeIf { it.isNotBlank() } ?: existing?.title ?: "",
                            posterUrl = e.poster ?: existing?.posterUrl,
                            mediaType = mediaType,
                            positionMs = e.position,
                            durationMs = e.duration,
                            updatedAt = updatedAt,
                            sourceRoute = existing?.sourceRoute,
                        )
                    )
                    val pct = if (e.duration > 0) e.position.toDouble() / e.duration else 0.0
                    if (pct >= 0.95) pulledWatched++ else pulledProgress++
                }
            }
        }.onFailure { Log.w(TAG, "pull watch progress: ${it.message}") }

        // ── Library / watchlist ─────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_library",
                buildJsonObject { put("p_profile_id", 1) },
                accessToken,
            ).getOrThrow()
            val items = json.decodeFromString(ListSerializer(PullLibraryItem.serializer()), text)
            val watchlistDao = db.watchlist()
            val existingIds = watchlistDao.all().first().map { it.tmdbId }.toSet()
            items.forEach { item ->
                val tmdbId = parseTmdbId(item.content_id) ?: return@forEach
                if (tmdbId !in existingIds) {
                    val mediaType = if (item.content_type == "series") "tv" else "movie"
                    val addedAt = item.added_at.takeIf { it > 0 } ?: System.currentTimeMillis()
                    watchlistDao.add(
                        WatchlistEntity(
                            tmdbId = tmdbId,
                            title = item.name,
                            posterUrl = item.poster,
                            mediaType = mediaType,
                            addedAt = addedAt,
                        )
                    )
                    pulledLibrary++
                }
            }
        }.onFailure { Log.w(TAG, "pull library: ${it.message}") }

        // ── Collections ─────────────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_collections",
                buildJsonObject { put("p_profile_id", 1) },
                accessToken,
            ).getOrThrow()
            val collections = json.decodeFromString(ListSerializer(PullCollection.serializer()), text)
            if (collections.isNotEmpty()) {
                val collectionDao = db.userCollections()
                val folderDao = db.collectionFolders()
                val deletedKeys = SettingsRepository(context).deletedManagedCollections.first()
                val oldNuvio = collectionDao.bySourceAddon(NUVIO_CLOUD_SOURCE)
                oldNuvio.forEach { col ->
                    folderDao.deleteForCollection(col.id)
                    collectionDao.delete(col.id)
                }
                collections.forEachIndexed { idx, col ->
                    // Skip collections the user has manually deleted
                    if ("$NUVIO_CLOUD_SOURCE::${col.name}" in deletedKeys) return@forEachIndexed
                    val colId = collectionDao.upsert(
                        UserCollectionEntity(
                            name = col.name,
                            isPinned = col.is_pinned,
                            sortOrder = col.sort_order.takeIf { it >= 0 } ?: idx,
                            sourceAddonId = NUVIO_CLOUD_SOURCE,
                        )
                    )
                    col.folders.forEachIndexed { fIdx, folder ->
                        folderDao.upsert(
                            CollectionFolderEntity(
                                collectionId = colId,
                                name = folder.name,
                                coverUrl = folder.cover_url,
                                tileShape = folder.tile_shape,
                                providerType = folder.provider_type,
                                linkedCategoryId = folder.linked_category_id,
                                sortOrder = folder.sort_order.takeIf { it >= 0 } ?: fIdx,
                            )
                        )
                    }
                    pulledCollections++
                }
            }
        }.onFailure { Log.w(TAG, "pull collections: ${it.message}") }

        NuvioPullResult(
            watchProgress = pulledProgress,
            watchedItems = pulledWatched,
            library = pulledLibrary,
            collections = pulledCollections,
            addons = pulledAddons,
            plugins = pulledPlugins,
        )
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
                        e.title.takeIf { it.isNotBlank() }?.let { put("name", it) }
                        e.posterUrl?.let { put("poster", it) }
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

        runCatching {
            val collectionDao = db.userCollections()
            val folderDao = db.collectionFolders()
            val allCols = collectionDao.all().first()
            // Pre-fetch folders outside the buildJsonArray lambda (suspend calls not allowed inside)
            val foldersByCol = allCols.associate { col -> col.id to folderDao.forCollectionOnce(col.id) }
            val arr = buildJsonArray {
                allCols.forEachIndexed { idx, col ->
                    addJsonObject {
                        put("name", col.name)
                        put("is_pinned", col.isPinned)
                        put("sort_order", col.sortOrder)
                        put("source_addon_id", col.sourceAddonId)
                        val folders = foldersByCol[col.id] ?: emptyList()
                        put("folders", buildJsonArray {
                            folders.forEach { f ->
                                addJsonObject {
                                    put("name", f.name)
                                    put("cover_url", f.coverUrl)
                                    put("provider_type", f.providerType)
                                    put("linked_category_id", f.linkedCategoryId)
                                    put("tile_shape", f.tileShape)
                                    put("sort_order", f.sortOrder)
                                }
                            }
                        })
                    }
                }
            }
            rpc(
                "sync_push_collections",
                buildJsonObject { put("p_collections", arr); put("p_profile_id", 1) },
                accessToken,
            )
        }.onFailure { Log.w(TAG, "push collections: ${it.message}") }

        NuvioSyncResult(plugins = plugins, addons = addons, watchProgress = progress, library = library)
    }

    private fun parseTmdbId(contentId: String): Long? {
        val raw = contentId.trim()
        return when {
            raw.startsWith("tmdb:") -> raw.removePrefix("tmdb:").toLongOrNull()
            raw.all { it.isDigit() } -> raw.toLongOrNull()
            else -> null
        }
    }

    companion object {
        @Volatile private var INSTANCE: NuvioAccountService? = null
        fun get(ctx: Context): NuvioAccountService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NuvioAccountService(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
