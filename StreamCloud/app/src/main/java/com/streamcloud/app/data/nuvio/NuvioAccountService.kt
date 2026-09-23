package com.streamcloud.app.data.nuvio

import android.content.Context
import android.util.Log
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.SettingsRepository
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.UserCollectionEntity
import com.streamcloud.app.data.library.WatchedMovieEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.data.profiles.UserProfile
import com.streamcloud.app.data.stremio.StremioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
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
private const val NUVIO_OFFICIAL_SERVER = "https://api.nuvio.tv"
private const val NUVIO_DISCOVERY_URL = "$NUVIO_OFFICIAL_SERVER/.well-known/nuvio"
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

@Serializable
private data class NuvioServerConfiguration(
    @SerialName("backend_url") val backendUrl: String = "",
    @SerialName("publishable_key") val publishableKey: String = "",
)

data class NuvioSyncResult(
    val plugins: Int = 0,
    val addons: Int = 0,
    val watchProgress: Int = 0,
    val watchedItems: Int = 0,
    val library: Int = 0,
    val collections: Int = 0,
    val collectionError: String? = null,
    val profiles: Int = 0,
    val errors: List<String> = emptyList(),
)

data class NuvioPullResult(
    val watchProgress: Int = 0,
    val watchedItems: Int = 0,
    val library: Int = 0,
    val collections: Int = 0,
    val addons: Int = 0,
    val plugins: Int = 0,
    val profiles: Int = 0,
    val collectionError: String? = null,
    val errors: List<String> = emptyList(),
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
private data class PullProfile(
    val profile_index: Int = 0,
    val name: String = "",
    val avatar_url: String? = null,
    val avatar_id: String? = null,
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
private data class PullWatchedItem(
    val content_id: String = "",
    val content_type: String = "movie",
    val title: String = "",
    val watched_at: Long = 0L,
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
    @Volatile private var serverConfiguration: NuvioServerConfiguration? = null

    private suspend fun currentServerConfiguration(): NuvioServerConfiguration =
        withContext(Dispatchers.IO) {
            serverConfiguration?.let { return@withContext it }
            val req = Request.Builder()
                .url(NUVIO_DISCOVERY_URL)
                .header("Accept", "application/json")
                .build()
            val resp = http.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Nuvio server discovery failed (${resp.code})")
            }
            val discovered = json.decodeFromString<NuvioServerConfiguration>(text)
            val backendUrl = discovered.backendUrl.trim().trimEnd('/')
            require(backendUrl.startsWith("https://")) {
                "Nuvio server returned an insecure backend URL"
            }
            require(discovered.publishableKey.isNotBlank()) {
                "Nuvio server did not provide a publishable key"
            }
            NuvioServerConfiguration(
                backendUrl = backendUrl,
                publishableKey = discovered.publishableKey.trim(),
            ).also { serverConfiguration = it }
        }

    suspend fun signIn(email: String, password: String): Result<NuvioSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = currentServerConfiguration()
                val body = buildJsonObject {
                    put("email", email.trim())
                    put("password", password)
                }.toString()
                val req = Request.Builder()
                    .url("${config.backendUrl}/auth/v1/token?grant_type=password")
                    .post(body.toRequestBody(JSON_MT))
                    .header("apikey", config.publishableKey)
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
                val config = currentServerConfiguration()
                val body = buildJsonObject { put("refresh_token", refreshToken) }.toString()
                val req = Request.Builder()
                    .url("${config.backendUrl}/auth/v1/token?grant_type=refresh_token")
                    .post(body.toRequestBody(JSON_MT))
                    .header("apikey", config.publishableKey)
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
            val config = currentServerConfiguration()
            val req = Request.Builder()
                .url("${config.backendUrl}/auth/v1/logout")
                .post("{}".toRequestBody(JSON_MT))
                .header("apikey", config.publishableKey)
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
            val config = currentServerConfiguration()
            val req = Request.Builder()
                .url("${config.backendUrl}/rest/v1/rpc/$function")
                .post(params.toString().toRequestBody(JSON_MT))
                .header("apikey", config.publishableKey)
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
        val stremioRepo = StremioRepository(context)
        val nuvioRepo = ServiceLocator.get(context).nuvio
        val resolvedTmdbIds = mutableMapOf<String, Long?>()
        val metadataCache = mutableMapOf<String, TmdbMovie?>()

        suspend fun resolveTmdbId(contentId: String, contentType: String): Long? {
            val cacheKey = "${contentType.lowercase()}|${contentId.trim()}"
            if (cacheKey in resolvedTmdbIds) return resolvedTmdbIds[cacheKey]
            val resolved = resolveTmdbIdFromNuvio(contentId, contentType)
            resolvedTmdbIds[cacheKey] = resolved
            return resolved
        }

        suspend fun resolveMetadata(tmdbId: Long, contentType: String): TmdbMovie? {
            val cacheKey = "${contentType.lowercase()}|$tmdbId"
            if (cacheKey in metadataCache) return metadataCache[cacheKey]
            val sl = ServiceLocator.get(context)
            val metadata = runCatching {
                if (contentType.equals("series", ignoreCase = true) ||
                    contentType.equals("tv", ignoreCase = true)
                ) {
                    sl.tmdb.tvDetails(tmdbId, sl.tmdbApiKey)
                } else {
                    sl.tmdb.details(tmdbId, sl.tmdbApiKey)
                }
            }.onFailure {
                Log.w(TAG, "resolve Nuvio metadata $tmdbId: ${it.message}")
            }.getOrNull()
            metadataCache[cacheKey] = metadata
            return metadata
        }

        var pulledAddons = 0
        var pulledPlugins = 0
        var pulledProgress = 0
        var pulledWatched = 0
        var pulledLibrary = 0
        var pulledCollections = 0
        var pulledProfiles = 0
        var collectionError: String? = null
        val errors = mutableListOf<String>()

        // ── Profiles ─────────────────────────────────────────────────────────
        runCatching {
            pulledProfiles = pullProfiles(accessToken)
        }.onFailure {
            errors += "profiles pull: ${it.message ?: "unknown error"}"
            Log.w(TAG, "pull profiles: ${it.message}")
        }

        val profileIndex = activeCloudProfileIndex()
            ?: 1 // Nuvio accounts created before profile linking use profile 1.
        val db = LibraryDb.get(context)

        // ── Stremio addons ──────────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_addons",
                buildJsonObject { put("p_profile_id", profileIndex) },
                accessToken,
            ).getOrThrow()
            val addons = json.decodeFromString(ListSerializer(PullAddon.serializer()), text)
            val existingAddons = stremioRepo.addons.first()
            val remoteUrls = addons.map { it.url.trim() }.filter { it.isNotBlank() }.toSet()
            existingAddons
                .filter { it.manifestUrl !in remoteUrls }
                .forEach { stremioRepo.removeAddon(it.manifestUrl) }
            addons.forEach { pulled ->
                val url = pulled.url.trim()
                if (url.isNotBlank() && existingAddons.none { it.manifestUrl == url }) {
                    stremioRepo.addAddon(url)
                    pulledAddons++
                }
            }
        }.onFailure {
            errors += "addons pull: ${it.message ?: "unknown error"}"
            Log.w(TAG, "pull addons: ${it.message}")
        }

        // ── Nuvio plugin repositories ───────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_plugins",
                buildJsonObject { put("p_profile_id", profileIndex) },
                accessToken,
            ).getOrThrow()
            val plugins = json.decodeFromString(ListSerializer(PullPlugin.serializer()), text)
            val existingRepos = nuvioRepo.savedRepos.first()
            val remoteUrls = plugins.map { it.url.trim() }.filter { it.isNotBlank() }.toSet()
            existingRepos
                .filter { it.url !in remoteUrls }
                .forEach { nuvioRepo.removeSavedRepo(it.id) }
            plugins.forEach { pulled ->
                val url = pulled.url.trim()
                if (url.isNotBlank() && existingRepos.none { it.url == url }) {
                    val name = pulled.name?.takeIf { it.isNotBlank() } ?: url.substringAfterLast("/").substringBefore(".")
                    nuvioRepo.addSavedRepo(url, name)
                    pulledPlugins++
                }
            }
        }.onFailure {
            errors += "plugins pull: ${it.message ?: "unknown error"}"
            Log.w(TAG, "pull plugins: ${it.message}")
        }

        // ── Watch progress ──────────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_watch_progress",
                buildJsonObject {
                    put("p_profile_id", profileIndex)
                    put("p_limit", 500)
                },
                accessToken,
            ).getOrThrow()
            val entries = json.decodeFromString(ListSerializer(PullWatchProgress.serializer()), text)
            val progressDao = db.watchProgress()
            entries.forEach { e ->
                val tmdbId = resolveTmdbId(e.content_id, e.content_type) ?: return@forEach
                val mediaType = if (e.content_type == "series") "tv" else "movie"
                val existing = progressDao.byId(tmdbId)
                val updatedAt = e.last_watched.takeIf { it > 0 } ?: System.currentTimeMillis()
                val shouldUseProgress = existing == null || existing.updatedAt < updatedAt
                val needsMetadata = existing == null ||
                    existing.title.isBlank() ||
                    existing.title == "Movie" ||
                    existing.title == "Series" ||
                    existing.posterUrl.isNullOrBlank()
                if (shouldUseProgress || needsMetadata) {
                    val metadata = if (needsMetadata) {
                        resolveMetadata(tmdbId, e.content_type)
                    } else {
                        null
                    }
                    progressDao.upsert(
                        WatchProgressEntity(
                            tmdbId = tmdbId,
                            title = e.name?.takeIf { it.isNotBlank() }
                                ?: existing?.title?.takeIf { it.isNotBlank() && it != "Movie" && it != "Series" }
                                ?: metadata?.displayTitle
                                ?: if (mediaType == "tv") "Series" else "Movie",
                            posterUrl = e.poster?.takeIf { it.isNotBlank() }
                                ?: existing?.posterUrl
                                ?: metadata?.posterUrl,
                            mediaType = mediaType,
                            positionMs = if (shouldUseProgress) e.position else existing!!.positionMs,
                            durationMs = if (shouldUseProgress) e.duration else existing!!.durationMs,
                            updatedAt = if (shouldUseProgress) updatedAt else existing!!.updatedAt,
                            sourceRoute = existing?.sourceRoute,
                        )
                    )
                    if (shouldUseProgress) {
                        val pct = if (e.duration > 0) e.position.toDouble() / e.duration else 0.0
                        if (pct < 0.95) pulledProgress++
                    }
                }
            }
        }.onFailure {
            errors += "watch progress pull: ${it.message ?: "unknown error"}"
            Log.w(TAG, "pull watch progress: ${it.message}")
        }

        // ── Library / watchlist ─────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_library",
                buildJsonObject {
                    put("p_profile_id", profileIndex)
                    put("p_limit", 500)
                    put("p_offset", 0)
                },
                accessToken,
            ).getOrThrow()
            val items = json.decodeFromString(ListSerializer(PullLibraryItem.serializer()), text)
            val watchlistDao = db.watchlist()
            val existingIds = watchlistDao.all().first().map { it.tmdbId }.toSet()
            items.forEach { item ->
                val tmdbId = resolveTmdbId(item.content_id, item.content_type) ?: return@forEach
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
        }.onFailure {
            errors += "library pull: ${it.message ?: "unknown error"}"
            Log.w(TAG, "pull library: ${it.message}")
        }

        // ── Collections ─────────────────────────────────────────────────────
        runCatching {
            val text = rpc(
                "sync_pull_collections",
                buildJsonObject { put("p_profile_id", profileIndex) },
                accessToken,
            ).getOrThrow()
            val collections = json.decodeFromString(ListSerializer(PullCollection.serializer()), text)
            val collectionDao = db.userCollections()
            val folderDao = db.collectionFolders()
            val deletedKeys = SettingsRepository(context).deletedManagedCollections.first()

            // Nuvio is authoritative for the collections it owns. Always remove the
            // previous cloud set first, including when the server returns an empty
            // list, so deleted remote collections do not remain as local ghosts.
            val oldNuvio = collectionDao.bySourceAddon(NUVIO_CLOUD_SOURCE)
            oldNuvio.forEach { col ->
                folderDao.deleteForCollection(col.id)
                collectionDao.delete(col.id)
            }

            collections.forEachIndexed { idx, col ->
                // Skip collections the user has manually deleted.
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
        }.onFailure {
            collectionError = it.message ?: "Nuvio collections could not be pulled"
            errors += "collections pull: ${collectionError}"
            Log.w(TAG, "pull collections: ${it.message}")
        }

        runCatching {
            pullWatchedItems(
                accessToken = accessToken,
                db = db,
                profileIndex = profileIndex,
                resolveTmdbId = { contentId, contentType ->
                    resolveTmdbId(contentId, contentType)
                },
            ) { pulledWatched++ }
        }.onFailure {
            errors += "watched items pull: ${it.message ?: "unknown error"}"
            Log.w(TAG, "pull watched items: ${it.message}")
        }

        NuvioPullResult(
            watchProgress = pulledProgress,
            watchedItems = pulledWatched,
            library = pulledLibrary,
            collections = pulledCollections,
            addons = pulledAddons,
            plugins = pulledPlugins,
            profiles = pulledProfiles,
            collectionError = collectionError,
            errors = errors.distinct(),
        )
    }

    suspend fun syncAll(accessToken: String): NuvioSyncResult = withContext(Dispatchers.IO) {
        val db = LibraryDb.get(context)
        val nuvioRepo = ServiceLocator.get(context).nuvio
        val stremioRepo = StremioRepository(context)
        var plugins = 0
        var addons = 0
        var progress = 0
        var library = 0
        var profiles = 0
        var collections = 0
        var collectionError: String? = null
        val errors = mutableListOf<String>()

        runCatching {
            profiles = pushProfiles(accessToken)
        }.onFailure {
            errors += "profiles push: ${it.message ?: "unknown error"}"
            Log.w(TAG, "push profiles: ${it.message}")
        }

        val profileIndex = activeCloudProfileIndex()
            ?: 1 // Keep legacy accounts syncing even before local linking completes.

        runCatching {
            val repos = nuvioRepo.savedRepos.first()
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
                buildJsonObject { put("p_plugins", arr); put("p_profile_id", profileIndex) },
                accessToken,
            ).getOrThrow()
            plugins = repos.size
        }.onFailure {
            errors += "plugins push: ${it.message ?: "unknown error"}"
            Log.w(TAG, "push Nuvio plugins: ${it.message}")
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
                buildJsonObject { put("p_addons", arr); put("p_profile_id", profileIndex) },
                accessToken,
            ).getOrThrow()
            addons = addonList.size
        }.onFailure {
            errors += "addons push: ${it.message ?: "unknown error"}"
            Log.w(TAG, "push Nuvio addons: ${it.message}")
        }

        runCatching {
            val entries = db.watchProgress().getAllEntries()
                .filter { it.mediaType == "movie" || it.mediaType == "tv" }
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
                buildJsonObject { put("p_entries", arr); put("p_profile_id", profileIndex) },
                accessToken,
            ).getOrThrow()
            progress = entries.size
        }.onFailure {
            errors += "watch progress push: ${it.message ?: "unknown error"}"
            Log.w(TAG, "push watch progress: ${it.message}")
        }

        runCatching {
            val items = db.watchlist().all().first()
                .filter { it.mediaType == "movie" || it.mediaType == "tv" }
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
                "sync_push_library_items",
                buildJsonObject { put("p_items", arr); put("p_profile_id", profileIndex) },
                accessToken,
            ).getOrThrow()
            library = items.size
        }.onFailure {
            errors += "library push: ${it.message ?: "unknown error"}"
            Log.w(TAG, "push library: ${it.message}")
        }

        runCatching {
            val collectionDao = db.userCollections()
            val folderDao = db.collectionFolders()

            // Only Nuvio-owned collections belong in the Nuvio cloud dataset.
            // Existing local collections are adopted on the first sync; Stremio
            // and other provider-generated collections stay local and are never
            // uploaded as if they were Nuvio collections.
            val allCols = collectionDao.all().first()
            val nuvioCols = allCols
                .filter { it.sourceAddonId.isBlank() || it.sourceAddonId == NUVIO_CLOUD_SOURCE }
                .map { col ->
                    if (col.sourceAddonId.isBlank()) {
                        col.copy(sourceAddonId = NUVIO_CLOUD_SOURCE)
                    } else {
                        col
                    }
                }
            nuvioCols
                .filter { it.sourceAddonId == NUVIO_CLOUD_SOURCE }
                .filter { it.id != 0L && allCols.firstOrNull { old -> old.id == it.id }?.sourceAddonId.isNullOrBlank() }
                .forEach { collectionDao.upsert(it) }

            // Pre-fetch folders outside the buildJsonArray lambda (suspend calls not allowed inside)
            val foldersByCol = nuvioCols.associate { col -> col.id to folderDao.forCollectionOnce(col.id) }
            val arr = buildJsonArray {
                nuvioCols.forEachIndexed { _, col ->
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
                buildJsonObject { put("p_collections", arr); put("p_profile_id", profileIndex) },
                accessToken,
            ).getOrThrow()
            collections = nuvioCols.size
        }.onFailure {
            collectionError = it.message ?: "Nuvio collections could not be uploaded"
            errors += "collections push: ${collectionError}"
            Log.w(TAG, "push collections: ${it.message}")
        }

        val watchedItems = runCatching {
            pushWatchedItems(accessToken, db, profileIndex)
        }.onFailure {
            errors += "watched items push: ${it.message ?: "unknown error"}"
            Log.w(TAG, "push watched items: ${it.message}")
        }.getOrDefault(0)

        NuvioSyncResult(
            plugins = plugins,
            addons = addons,
            watchProgress = progress,
            watchedItems = watchedItems,
            library = library,
            collections = collections,
            collectionError = collectionError,
            profiles = profiles,
            errors = errors.distinct(),
        )
    }

    private suspend fun pushProfiles(accessToken: String): Int {
        val profileRepo = ServiceLocator.get(context).profiles
        val localProfiles = profileRepo.currentProfiles().take(6)
        if (localProfiles.isEmpty()) return 0

        val usedIndexes = localProfiles.mapNotNull { it.nuvioProfileIndex }.toMutableSet()
        var nextIndex = 1
        val assignedIndexes = mutableMapOf<String, Int>()
        val payload = buildJsonArray {
            localProfiles.forEach { profile ->
                val profileIndex = profile.nuvioProfileIndex ?: run {
                    while (nextIndex in usedIndexes) nextIndex++
                    nextIndex.also { usedIndexes += it; nextIndex++ }
                }
                assignedIndexes[profile.id] = profileIndex
                addJsonObject {
                    put("profile_index", profileIndex)
                    put("name", profile.name)
                    put("avatar_color_hex", "#1E88E5")
                    profile.avatarSeed.takeIf { it.isNotBlank() }?.let { put("avatar_id", it) }
                    profile.avatarUrl.takeIf { it.isNotBlank() }?.let { put("avatar_url", it) }
                }
            }
        }
        rpc(
            "sync_push_profiles",
            buildJsonObject {
                put("p_client_max_profiles", 6)
                put("p_profiles", payload)
            },
            accessToken,
        ).getOrThrow()
        profileRepo.setNuvioProfileIndexes(assignedIndexes)
        return localProfiles.size
    }

    private fun activeCloudProfileIndex(): Int? {
        val repo = ServiceLocator.get(context).profiles
        val activeId = repo.currentActiveId()
        val profiles = repo.currentProfiles()
        val activeIndex = profiles.firstOrNull { it.id == activeId }?.nuvioProfileIndex
        if (activeIndex != null) return activeIndex

        // A single cloud profile is unambiguous even when the local profile
        // was created with a different name or before cloud linking existed.
        return profiles.mapNotNull { it.nuvioProfileIndex }.singleOrNull()
            ?: profiles.firstOrNull()?.nuvioProfileIndex
    }

    private suspend fun pullProfiles(accessToken: String): Int {
        val text = rpc(
            "sync_pull_profiles",
            buildJsonObject {},
            accessToken,
        ).getOrThrow()
        val remoteProfiles = json.decodeFromString(
            ListSerializer(PullProfile.serializer()),
            text,
        ).filter { it.profile_index > 0 && it.name.isNotBlank() }
        val localProfiles = remoteProfiles
            .sortedBy { it.profile_index }
            .map { remote ->
                UserProfile(
                    id = "nuvio-profile-${remote.profile_index}",
                    name = remote.name,
                    avatarUrl = remote.avatar_url
                        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                        .orEmpty(),
                    avatarSeed = remote.avatar_id?.takeIf { it.isNotBlank() } ?: remote.name,
                    nuvioProfileIndex = remote.profile_index,
                )
            }
        ServiceLocator.get(context).profiles.mergeNuvioProfiles(localProfiles)
        return localProfiles.size
    }

    private suspend fun pushWatchedItems(
        accessToken: String,
        db: LibraryDb,
        profileIndex: Int,
    ): Int {
        val items = db.watchedMovies().all().first()
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
        val payload = buildJsonArray {
            items.forEach { item ->
                addJsonObject {
                    put("content_id", "tmdb:${item.tmdbId}")
                    put("content_type", if (item.mediaType == "tv") "series" else "movie")
                    put("title", item.title)
                    put("watched_at", item.watchedAt)
                }
            }
        }
        rpc(
            "sync_push_watched_items",
            buildJsonObject {
                put("p_items", payload)
                put("p_profile_id", profileIndex)
            },
            accessToken,
        ).getOrThrow()
        return items.size
    }

    private suspend fun pullWatchedItems(
        accessToken: String,
        db: LibraryDb,
        profileIndex: Int,
        resolveTmdbId: suspend (String, String) -> Long?,
        onItemPulled: () -> Unit,
    ) {
        val watchedDao = db.watchedMovies()
        val pageSize = 200
        var page = 1
        do {
            val text = rpc(
                "sync_pull_watched_items",
                buildJsonObject {
                    put("p_profile_id", profileIndex)
                    put("p_page", page)
                    put("p_page_size", pageSize)
                },
                accessToken,
            ).getOrThrow()
            val items = json.decodeFromString(
                ListSerializer(PullWatchedItem.serializer()),
                text,
            )
            items.forEach { item ->
                val tmdbId = resolveTmdbId(item.content_id, item.content_type) ?: return@forEach
                watchedDao.mark(
                    WatchedMovieEntity(
                        tmdbId = tmdbId,
                        title = item.title,
                        posterUrl = null,
                        mediaType = if (item.content_type == "series") "tv" else "movie",
                        watchedAt = item.watched_at.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                )
                onItemPulled()
            }
            page++
        } while (items.size == pageSize)
    }

    private fun parseTmdbId(contentId: String): Long? {
        val raw = contentId.trim()
        return when {
            raw.matches(Regex("tmdb:(?:(?:movie|series|tv):)?\\d+")) ->
                raw.substringAfterLast(':').toLongOrNull()
            raw.matches(Regex("(?:movie|series|tv):\\d+")) ->
                raw.substringAfterLast(':').toLongOrNull()
            raw.all { it.isDigit() } -> raw.toLongOrNull()
            else -> null
        }
    }

    private suspend fun resolveTmdbIdFromNuvio(
        contentId: String,
        contentType: String,
    ): Long? {
        parseTmdbId(contentId)?.let { return it }

        val externalId = contentId.trim()
            .removePrefix("imdb:")
            .takeIf { it.startsWith("tt", ignoreCase = true) }
            ?: return null
        val apiKey = ServiceLocator.get(context).tmdbApiKey
        if (apiKey.isBlank()) return null

        return runCatching {
            val result = ServiceLocator.get(context).tmdb.find(externalId, apiKey, "imdb_id")
            val isSeries = contentType.equals("series", ignoreCase = true) ||
                contentType.equals("tv", ignoreCase = true)
            if (isSeries) {
                result.tvResults.firstOrNull()?.id ?: result.movieResults.firstOrNull()?.id
            } else {
                result.movieResults.firstOrNull()?.id ?: result.tvResults.firstOrNull()?.id
            }
        }.onFailure {
            Log.w(TAG, "resolve Nuvio content $externalId: ${it.message}")
        }.getOrNull()
    }

    companion object {
        @Volatile private var INSTANCE: NuvioAccountService? = null
        fun get(ctx: Context): NuvioAccountService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NuvioAccountService(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
