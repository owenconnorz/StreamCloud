package com.streamcloud.app.data.stremio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.UserCollectionEntity
import com.streamcloud.app.data.network.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import com.streamcloud.app.data.network.BrowserCookieJar
import com.streamcloud.app.data.network.BrowserHeaders
import com.streamcloud.app.data.network.CloudflareKiller
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val Context.stremioStore by preferencesDataStore("streamcloud_stremio")
private val KEY_ADDONS = stringPreferencesKey("addons_json")

class StremioRepository(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .cookieJar(BrowserCookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val addons: Flow<List<InstalledStremioAddon>> = context.stremioStore.data.map { prefs ->
        prefs[KEY_ADDONS]?.let {
            runCatching {
                Net.json.decodeFromString(ListSerializer(InstalledStremioAddon.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    private suspend fun saveAddons(list: List<InstalledStremioAddon>) {
        val text = Net.json.encodeToString(ListSerializer(InstalledStremioAddon.serializer()), list)
        context.stremioStore.edit { it[KEY_ADDONS] = text }
    }


    suspend fun addAddon(manifestUrlOrBase: String): InstalledStremioAddon = withContext(Dispatchers.IO) {
        val url = normalize(manifestUrlOrBase)
        val baseUrl = url.removeSuffix("/manifest.json").trimEnd('/')
        val mf = fetchManifest(url)
        val addon = InstalledStremioAddon(
            id = mf.id, name = mf.name, manifestUrl = url, baseUrl = baseUrl,
            logo = mf.logo ?: mf.icon, installedAt = System.currentTimeMillis(),
            version = mf.version,
        )
        val list = addons.first().filterNot { it.manifestUrl == url } + addon
        saveAddons(list)
        runCatching { syncAddonCollections(addon, mf, LibraryDb.get(context)) }
        addon
    }

    suspend fun updateAddon(updated: InstalledStremioAddon) {
        val list = addons.first().map { if (it.manifestUrl == updated.manifestUrl) updated else it }
        saveAddons(list)
    }

    suspend fun removeAddon(manifestUrl: String) {
        val target = addons.first().firstOrNull { it.manifestUrl == manifestUrl }
        saveAddons(addons.first().filterNot { it.manifestUrl == manifestUrl })
        if (target != null) {
            runCatching {
                val db = LibraryDb.get(context)
                val collectionDao = db.userCollections()
                val folderDao = db.collectionFolders()
                val cols = collectionDao.bySourceAddon(target.id)
                cols.forEach { col ->
                    folderDao.deleteForCollection(col.id)
                    collectionDao.delete(col.id)
                }
            }
        }
    }

    suspend fun fetchManifest(manifestUrl: String): StremioManifest = withContext(Dispatchers.IO) {
        val body = httpGet(manifestUrl)
        Net.json.decodeFromString(StremioManifest.serializer(), body)
    }

    suspend fun syncAddonCollections(
        addon: InstalledStremioAddon,
        manifest: StremioManifest,
        db: LibraryDb,
    ) = withContext(Dispatchers.IO) {
        val collectionDao = db.userCollections()
        val folderDao = db.collectionFolders()

        val isRequired: (StremioCatalogDef) -> Boolean = { c ->
            c.extra?.any { it.isRequired } ?: false
        }

        val nonRequiredCatalogs = manifest.catalogs.filter { !isRequired(it) }

        val groups: Map<String, List<StremioCatalogDef>> = when {
            !manifest.catalogGroups.isNullOrEmpty() -> {
                manifest.catalogGroups.associate { g ->
                    g.name to g.catalogs.filter { !isRequired(it) }
                }.filter { it.value.isNotEmpty() }
            }
            manifest.catalogs.any { it.group != null } -> {
                manifest.catalogs
                    .filter { it.group != null && !isRequired(it) }
                    .groupBy { it.group!! }
            }
            manifest.catalogs.any { it.name?.contains(" - ") == true } -> {
                manifest.catalogs
                    .filter { it.name?.contains(" - ") == true && !isRequired(it) }
                    .groupBy { it.name!!.substringBefore(" - ").trim() }
            }
            nonRequiredCatalogs.isNotEmpty() -> mapOf(addon.name to nonRequiredCatalogs)
            else -> emptyMap()
        }

        if (groups.isEmpty()) return@withContext

        val oldCols = collectionDao.bySourceAddon(addon.id)
        oldCols.forEach { col ->
            folderDao.deleteForCollection(col.id)
            collectionDao.delete(col.id)
        }

        groups.entries.forEachIndexed { groupIdx, (groupName, catalogs) ->
            val collectionId = collectionDao.upsert(
                UserCollectionEntity(
                    name = groupName,
                    isPinned = true,
                    sortOrder = groupIdx,
                    sourceAddonId = addon.id,
                )
            )
            catalogs.forEachIndexed { catIdx, cat ->
                val displayName = when {
                    cat.name?.contains(" - ") == true -> cat.name.substringAfter(" - ").trim()
                    cat.name?.isNotBlank() == true -> cat.name
                    else -> cat.id
                }
                val coverUrl = cat.poster ?: cat.logo ?: ""
                folderDao.upsert(
                    CollectionFolderEntity(
                        collectionId = collectionId,
                        name = displayName,
                        coverUrl = coverUrl,
                        tileShape = "wide",
                        providerType = "stremio",
                        linkedCategoryId = "${addon.id}|||${cat.type}|||${cat.id}|||$displayName",
                        sortOrder = catIdx,
                    )
                )
            }
        }
    }

    suspend fun refreshAddonCollections(manifestUrl: String) = withContext(Dispatchers.IO) {
        val addon = addons.first().firstOrNull { it.manifestUrl == manifestUrl } ?: return@withContext
        val mf = runCatching { fetchManifest(manifestUrl) }.getOrNull() ?: return@withContext
        runCatching { syncAddonCollections(addon, mf, LibraryDb.get(context)) }
    }

    suspend fun syncAllAddonsCollections() = withContext(Dispatchers.IO) {
        val db = LibraryDb.get(context)
        val existingAddonIds = db.userCollections().allSourceAddonIds().toSet()
        addons.first().forEach { addon ->
            if (addon.id !in existingAddonIds) {
                runCatching {
                    val mf = fetchManifest(addon.manifestUrl)
                    syncAddonCollections(addon, mf, db)
                }
            }
        }
    }

    suspend fun fetchHomeCatalog(addon: InstalledStremioAddon): List<StremioMetaPreview> =
        withContext(Dispatchers.IO) {
            val mf = fetchManifest(addon.manifestUrl)
            val first = mf.catalogs.firstOrNull { c ->
                c.extra?.none { it.isRequired && it.name.lowercase() == "search" } != false
            } ?: return@withContext emptyList()
            fetchCatalog(addon, first.type, first.id)
        }


    suspend fun fetchCatalogMetas(addon: InstalledStremioAddon): List<StremioCatalogMeta> =
        withContext(Dispatchers.IO) {
            val mf = runCatching { fetchManifest(addon.manifestUrl) }.getOrNull()
                ?: return@withContext emptyList()
            mf.catalogs
                .filter { c -> c.extra?.none { it.isRequired } ?: true }
                .take(8)
                .map { c ->
                    StremioCatalogMeta(
                        addonId = addon.id,
                        addonName = addon.name,
                        catalogId = c.id,
                        catalogName = c.name?.takeIf { it.isNotBlank() } ?: c.id,
                        type = c.type,
                    )
                }
        }

    suspend fun searchAllAddons(
        addons: List<InstalledStremioAddon>,
        query: String,
    ): List<Pair<InstalledStremioAddon, StremioMetaPreview>> = coroutineScope {
        addons.map { addon ->
            async(Dispatchers.IO) {
                runCatching {
                    val mf = fetchManifest(addon.manifestUrl)
                    val searchable = mf.catalogs.filter { c ->
                        c.extra?.any { it.name.lowercase() == "search" } == true
                    }
                    searchable.flatMap { cat ->
                        runCatching {
                            fetchCatalog(addon, cat.type, cat.id, search = query)
                        }.getOrDefault(emptyList()).map { addon to it }
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    suspend fun fetchAllHomeCatalogs(addon: InstalledStremioAddon): List<StremioHomeRow> =
        withContext(Dispatchers.IO) {
            val mf = runCatching { fetchManifest(addon.manifestUrl) }.getOrNull()
                ?: return@withContext emptyList()
            val nonSearch = mf.catalogs.filter { c ->
                c.extra?.none { it.isRequired } ?: true
            }

            coroutineScope {
                nonSearch.take(8).map { c ->
                    async {
                        runCatching { fetchCatalog(addon, c.type, c.id) }
                            .getOrDefault(emptyList())
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                StremioHomeRow(
                                    addonId = addon.id,
                                    addonName = addon.name,
                                    catalogId = c.id,
                                    catalogName = c.name?.takeIf { n -> n.isNotBlank() } ?: c.id,
                                    type = c.type,
                                    items = it.take(18),
                                )
                            }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    suspend fun fetchCatalog(
        addon: InstalledStremioAddon,
        type: String,
        catalogId: String,
        search: String? = null,
        skip: Int? = null,
    ): List<StremioMetaPreview> = withContext(Dispatchers.IO) {
        val extra = buildList {
            if (search != null) add("search=${java.net.URLEncoder.encode(search, "UTF-8")}")
            if (skip != null) add("skip=$skip")
        }.joinToString("&")
        val tail = if (extra.isNotBlank()) "/$extra" else ""
        val url = "${addon.baseUrl}/catalog/$type/$catalogId$tail.json"
        val body = httpGet(url)
        Net.json.decodeFromString(StremioCatalogResponse.serializer(), body).metas
    }

    suspend fun fetchStreams(
        addon: InstalledStremioAddon,
        type: String,
        id: String,
    ): List<StremioStream> = withContext(Dispatchers.IO) {
        val url = "${addon.baseUrl}/stream/$type/$id.json"
        val body = httpGet(url)
        Net.json.decodeFromString(StremioStreamResponse.serializer(), body).streams
    }

    suspend fun fetchMeta(
        addon: InstalledStremioAddon,
        type: String,
        id: String,
    ): StremioMeta? = withContext(Dispatchers.IO) {
        val url = "${addon.baseUrl}/meta/$type/$id.json"
        runCatching {
            val body = httpGet(url)
            Net.json.decodeFromString(StremioMetaResponse.serializer(), body).meta
        }.getOrNull()
    }

    private suspend fun httpGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", BrowserHeaders.USER_AGENT)
            .header("Accept", BrowserHeaders.ACCEPT_JSON)
            .header("Accept-Language", BrowserHeaders.ACCEPT_LANGUAGE)
            .build()
        var code: Int
        var body: String
        var hdrs: Map<String, List<String>>
        http.newCall(req).execute().use { resp ->
            code = resp.code
            body = resp.body?.string().orEmpty()
            hdrs = resp.headers.toMultimap()
        }
        if (CloudflareKiller.isCfChallenge(code, hdrs, body)) {
            val bypassed = CloudflareKiller.bypass(context, url, BrowserHeaders.USER_AGENT, BrowserCookieJar)
            if (bypassed) {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code} from $url (after CF bypass)")
                    return r.body?.string().orEmpty()
                }
            }
            error("Cloudflare challenge unresolved for $url")
        }
        if (code !in 200..299) error("HTTP $code from $url")
        return body
    }

    private fun normalize(input: String): String {
        val s = input.trim().trimEnd('/')

        val withScheme = when {
            s.startsWith("stremio://") -> "https://" + s.removePrefix("stremio://")
            s.startsWith("http") -> s
            else -> "https://$s"
        }
        return if (withScheme.endsWith("/manifest.json")) withScheme else "$withScheme/manifest.json"
    }
}
