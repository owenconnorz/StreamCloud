package com.streamcloud.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lagradost.cloudstream3.SearchResponse
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.collections.HomeCollections
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.data.stremio.StremioMetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

internal data class FolderRow(
    val label: String,
    val csItems: List<SearchResponse> = emptyList(),
    val stremioItems: List<StremioMetaPreview> = emptyList(),
    val tmdbItems: List<TmdbMovie> = emptyList(),
    val isTv: Boolean = false,
    val loading: Boolean = true,
    val pluginInternalName: String = "",
    val sectionName: String = "",
    val pluginDisplayName: String = "",
    val addonId: String = "",
    val contentType: String = "",
    val catalogId: String = "",
)

/** Loads content rows for a single [folder]. Called from both folder and tabbed screens. */
internal suspend fun loadFolderRows(
    context: Context,
    folder: CollectionFolderEntity,
): List<FolderRow> {
    val sl = ServiceLocator.get(context)
    val pluginRepo = PluginRepository(context.applicationContext)
    val entries = folder.linkedCategoryId.split("\n").filter { it.isNotBlank() }
    if (entries.isEmpty()) return emptyList()

    val installed = runCatching { pluginRepo.installed.first() }.getOrDefault(emptyList())
    val addons = runCatching { sl.stremio.addons.first() }.getOrDefault(emptyList())

    return coroutineScope {
        entries.mapIndexed { i, enc ->
            async {
                val p = enc.split("|||")
                try {
                    when (folder.providerType) {
                        "cloudstream" -> {
                            val iname = p.getOrNull(0) ?: ""
                            val sname = p.getOrNull(1) ?: ""
                            val dname = p.getOrNull(2)?.ifBlank { null } ?: p.getOrNull(0) ?: ""
                            val plugin = installed.firstOrNull { it.internalName == iname }
                                ?: return@async FolderRow(
                                    label = dname.ifBlank { "Section ${i + 1}" },
                                    loading = false,
                                    pluginInternalName = iname, sectionName = sname, pluginDisplayName = dname,
                                )
                            val items: List<SearchResponse> = if (sname.isNotBlank()) {
                                runCatching { PluginRuntime.homePage(context, plugin.filePath, sname, 1) }.getOrDefault(emptyList())
                            } else {
                                runCatching { PluginRuntime.home(context, plugin.filePath).firstOrNull()?.second }.getOrNull() ?: emptyList()
                            }
                            FolderRow(
                                label = dname.ifBlank { sname.ifBlank { "Section ${i + 1}" } },
                                csItems = items, loading = false,
                                pluginInternalName = iname, sectionName = sname, pluginDisplayName = dname,
                            )
                        }

                        "stremio" -> {
                            val addonId = p.getOrNull(0) ?: ""
                            val cType = p.getOrNull(1) ?: ""
                            val cId = p.getOrNull(2) ?: ""
                            val cName = p.getOrNull(3)?.ifBlank { null } ?: "Catalog ${i + 1}"
                            val addon = addons.firstOrNull { it.id == addonId }
                                ?: return@async FolderRow(label = cName, loading = false, addonId = addonId, contentType = cType, catalogId = cId)
                            val items = runCatching { sl.stremio.fetchCatalog(addon, cType, cId) }.getOrDefault(emptyList())
                            FolderRow(
                                label = cName, stremioItems = items, loading = false,
                                addonId = addonId, contentType = cType, catalogId = cId,
                            )
                        }

                        "trakt" -> {
                            val traktId = enc.trim()
                            val label = traktSourceDisplayLabel(traktId)
                            val items = loadTraktItems(context, traktId)
                            val isTv = traktId.contains("show")
                            FolderRow(label = label, tmdbItems = items, isTv = isTv, loading = false)
                        }

                        else -> {
                            // TMDB
                            val catId = enc.trim()
                            val label = HomeCollections.byId(catId)?.title ?: decodeTmdbCategoryLabel(catId)
                            val isTv = catId.startsWith("discover_tv:") || catId.startsWith("network:")
                            val items = loadTmdbItems(context, catId)
                            FolderRow(label = label, tmdbItems = items, isTv = isTv, loading = false)
                        }
                    }
                } catch (_: Throwable) {
                    val label = when (folder.providerType) {
                        "cloudstream" -> p.getOrNull(2)?.ifBlank { null } ?: p.getOrNull(0) ?: "Section ${i + 1}"
                        "stremio"     -> p.getOrNull(3)?.ifBlank { null } ?: p.getOrNull(2) ?: "Catalog ${i + 1}"
                        "trakt"       -> traktSourceDisplayLabel(enc.trim())
                        else          -> HomeCollections.byId(enc.trim())?.title ?: "Category ${i + 1}"
                    }
                    FolderRow(label = label, loading = false)
                }
            }
        }.awaitAll()
    }
}

private fun traktSourceDisplayLabel(id: String) = when (id) {
    "trending_movies"    -> "Trending Movies"
    "trending_shows"     -> "Trending TV Shows"
    "popular_movies"     -> "Popular Movies"
    "popular_shows"      -> "Popular TV Shows"
    "watchlist_movies"   -> "My Watchlist (Movies)"
    "watchlist_shows"    -> "My Watchlist (Shows)"
    "recommended_movies" -> "Recommended Movies"
    "recommended_shows"  -> "Recommended TV Shows"
    else                 -> id
}

private val traktHttpClient by lazy { OkHttpClient() }

private suspend fun loadTraktItems(context: Context, sourceId: String): List<TmdbMovie> {
    val sl = ServiceLocator.get(context)
    val clientId = sl.settings.traktClientId.first()
    if (clientId.isBlank()) return emptyList()

    val token = sl.settings.traktToken.first()
    val username = sl.settings.traktUsername.first()

    val endpoint = when (sourceId) {
        "trending_movies"    -> "https://api.trakt.tv/movies/trending?limit=20"
        "popular_movies"     -> "https://api.trakt.tv/movies/popular?limit=20"
        "trending_shows"     -> "https://api.trakt.tv/shows/trending?limit=20"
        "popular_shows"      -> "https://api.trakt.tv/shows/popular?limit=20"
        "recommended_movies" -> "https://api.trakt.tv/movies/recommended/weekly?limit=20"
        "recommended_shows"  -> "https://api.trakt.tv/shows/recommended/weekly?limit=20"
        "watchlist_movies"   -> if (username.isNotBlank()) "https://api.trakt.tv/users/$username/watchlist/movies?limit=20" else return emptyList()
        "watchlist_shows"    -> if (username.isNotBlank()) "https://api.trakt.tv/users/$username/watchlist/shows?limit=20" else return emptyList()
        else                 -> return emptyList()
    }

    val isTv = sourceId.contains("show")
    val mediaKey = if (isTv) "show" else "movie"

    return withContext(Dispatchers.IO) {
        runCatching {
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .header("trakt-api-key", clientId)
                .header("trakt-api-version", "2")
                .header("Content-Type", "application/json")
            if (token.isNotBlank()) reqBuilder.header("Authorization", "Bearer $token")
            val response = traktHttpClient.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: return@runCatching emptyList<TmdbMovie>()
            val arr = JSONArray(body)
            (0 until minOf(arr.length(), 20)).mapNotNull { idx ->
                val obj = arr.getJSONObject(idx)
                val media = if (obj.has(mediaKey)) obj.getJSONObject(mediaKey) else obj
                val ids = media.optJSONObject("ids") ?: return@mapNotNull null
                val tmdbId = ids.optLong("tmdb", -1L)
                if (tmdbId <= 0) return@mapNotNull null
                val title = media.optString("title", "").ifBlank { null }
                TmdbMovie(id = tmdbId, title = if (!isTv) title else null, name = if (isTv) title else null)
            }
        }.getOrDefault(emptyList())
    }
}

private suspend fun loadTmdbItems(context: Context, catId: String): List<TmdbMovie> {
    val sl = ServiceLocator.get(context)
    val api = sl.tmdb
    val key = sl.tmdbApiKey
    return runCatching {
        when {
            catId.startsWith("list:") -> {
                val id = catId.removePrefix("list:").toLongOrNull() ?: return@runCatching emptyList()
                api.listItems(id, key).items
            }
            catId.startsWith("collection:") -> {
                val id = catId.removePrefix("collection:").toLongOrNull() ?: return@runCatching emptyList()
                api.collectionParts(id, key).parts
            }
            catId.startsWith("person:") -> {
                val id = catId.removePrefix("person:").toLongOrNull() ?: return@runCatching emptyList()
                api.personMovieCredits(id, key).cast.take(20)
            }
            catId.startsWith("company:") -> {
                val id = catId.removePrefix("company:")
                api.discover(key, withCompanies = id).results
            }
            catId.startsWith("network:") -> {
                val id = catId.removePrefix("network:")
                api.discoverTv(key, withNetworks = id).results
            }
            catId.startsWith("discover_movie:") -> {
                val params = catId.removePrefix("discover_movie:")
                val genreId = params.removePrefix("genre=").toIntOrNull()
                api.discover(key, withGenres = genreId?.toString()).results
            }
            catId.startsWith("discover_tv:") -> {
                val params = catId.removePrefix("discover_tv:")
                val genreId = params.removePrefix("genre=").toIntOrNull()
                api.discoverTv(key, withGenres = genreId?.toString()).results
            }
            else -> {
                // Preset category
                val preset = HomeCollections.byId(catId)
                preset?.fetchPage(api, key, 1) ?: emptyList()
            }
        }
    }.getOrDefault(emptyList())
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionFolderPageScreen(
    folderId: Long,
    onBack: () -> Unit,
    onMovieClick: (Long) -> Unit = {},
    onTvClick: (Long) -> Unit = {},
    onOpenCsItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit =
        { _, _, _, _ -> },
    onViewAllCsSection: (pluginInternalName: String, sectionName: String, pluginDisplayName: String) -> Unit =
        { _, _, _ -> },
    onOpenCatalog: (source: String, title: String, subtitle: String) -> Unit = { _, _, _ -> },
    onOpenStremio: (addonId: String, type: String, metaId: String, title: String, poster: String?) -> Unit =
        { _, _, _, _, _ -> },
) {
    val context = LocalContext.current

    var folderName by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<FolderRow>>(emptyList()) }
    var pageLoading by remember { mutableStateOf(true) }

    LaunchedEffect(folderId) {
        pageLoading = true
        val folder = LibraryDb.get(context.applicationContext).collectionFolders().byId(folderId)
        if (folder == null) { pageLoading = false; return@LaunchedEffect }
        folderName = folder.name
        // Show skeletons immediately
        val entryCount = folder.linkedCategoryId.split("\n").count { it.isNotBlank() }.coerceAtLeast(1)
        rows = List(entryCount) { FolderRow(label = "Loading…", loading = true) }
        pageLoading = false
        // Load actual content
        rows = loadFolderRows(context.applicationContext, folder)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        folderName.ifBlank { "Loading…" },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (pageLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            rows.forEachIndexed { idx, row ->
                item(key = "hdr_$idx") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!row.loading) {
                            when {
                                row.csItems.isNotEmpty() && row.sectionName.isNotBlank() ->
                                    TextButton(onClick = { onViewAllCsSection(row.pluginInternalName, row.sectionName, row.pluginDisplayName) }) { Text("View all →") }
                                row.stremioItems.isNotEmpty() ->
                                    TextButton(onClick = { onOpenCatalog("stremio:${row.addonId}:${row.contentType}:${row.catalogId}", row.label, "") }) { Text("View all →") }
                            }
                        }
                    }
                }
                item(key = "content_$idx") {
                    when {
                        row.loading -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }

                        row.csItems.isNotEmpty() -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(row.csItems.take(20)) { item ->
                                FolderPosterCard(title = item.name, posterUrl = item.posterUrl, onClick = {
                                    onOpenCsItem(row.pluginInternalName, item.url, item.name, item.posterUrl)
                                })
                            }
                        }

                        row.stremioItems.isNotEmpty() -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(row.stremioItems.take(20)) { item ->
                                FolderPosterCard(title = item.name, posterUrl = item.poster, onClick = {
                                    onOpenStremio(row.addonId, row.contentType, item.id, item.name, item.poster)
                                })
                            }
                        }

                        row.tmdbItems.isNotEmpty() -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(row.tmdbItems.take(20)) { item ->
                                FolderPosterCard(title = item.displayTitle, posterUrl = item.posterUrl, onClick = {
                                    if (row.isTv) onTvClick(item.id) else onMovieClick(item.id)
                                })
                            }
                        }

                        else -> Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                            Text("No content available", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FolderPosterCard(title: String, posterUrl: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.width(110.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = posterUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(165.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E1E1E)),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        )
    }
}
