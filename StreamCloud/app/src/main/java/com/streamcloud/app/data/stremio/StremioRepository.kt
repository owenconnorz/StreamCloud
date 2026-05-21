package com.streamcloud.app.data.stremio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val Context.stremioStore by preferencesDataStore("streamcloud_stremio")
private val KEY_ADDONS = stringPreferencesKey("addons_json")

class StremioRepository(private val context: Context) {

    private val http = OkHttpClient.Builder()
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
        )
        val list = addons.first().filterNot { it.manifestUrl == url } + addon
        saveAddons(list)
        addon
    }

    suspend fun removeAddon(manifestUrl: String) {
        saveAddons(addons.first().filterNot { it.manifestUrl == manifestUrl })
    }

    suspend fun fetchManifest(manifestUrl: String): StremioManifest = withContext(Dispatchers.IO) {
        val body = httpGet(manifestUrl)
        Net.json.decodeFromString(StremioManifest.serializer(), body)
    }


    suspend fun fetchHomeCatalog(addon: InstalledStremioAddon): List<StremioMetaPreview> =
        withContext(Dispatchers.IO) {
            val mf = fetchManifest(addon.manifestUrl)
            val first = mf.catalogs.firstOrNull { c ->
                c.extra?.none { it.isRequired && it.name.lowercase() == "search" } != false
            } ?: return@withContext emptyList()
            fetchCatalog(addon, first.type, first.id)
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

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "StreamCloud/1.0 (Stremio addon client)")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} from $url")
            return resp.body?.string().orEmpty()
        }
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
