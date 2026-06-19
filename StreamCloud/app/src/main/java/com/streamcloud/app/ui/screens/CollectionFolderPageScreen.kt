package com.streamcloud.app.ui.screens

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
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.data.stremio.StremioMetaPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

private data class FolderRow(
    val label: String,
    val csItems: List<SearchResponse> = emptyList(),
    val stremioItems: List<StremioMetaPreview> = emptyList(),
    val loading: Boolean = true,
    val pluginInternalName: String = "",
    val sectionName: String = "",
    val pluginDisplayName: String = "",
    val addonId: String = "",
    val contentType: String = "",
    val catalogId: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionFolderPageScreen(
    folderId: Long,
    onBack: () -> Unit,
    onOpenCsItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit =
        { _, _, _, _ -> },
    onViewAllCsSection: (pluginInternalName: String, sectionName: String, pluginDisplayName: String) -> Unit =
        { _, _, _ -> },
    onOpenCatalog: (source: String, title: String, subtitle: String) -> Unit = { _, _, _ -> },
    onOpenStremio: (addonId: String, type: String, metaId: String, title: String, poster: String?) -> Unit =
        { _, _, _, _, _ -> },
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val pluginRepo = remember { PluginRepository(context.applicationContext) }

    var folderName by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<FolderRow>>(emptyList()) }
    var pageLoading by remember { mutableStateOf(true) }

    LaunchedEffect(folderId) {
        pageLoading = true
        val folder = LibraryDb.get(context.applicationContext).collectionFolders().byId(folderId)
        if (folder == null) {
            pageLoading = false
            return@LaunchedEffect
        }
        folderName = folder.name
        val entries = folder.linkedCategoryId.split("\n").filter { it.isNotBlank() }

        val skeletons = entries.mapIndexed { i, enc ->
            val p = enc.split("|||")
            when (folder.providerType) {
                "cloudstream" -> FolderRow(
                    label = p.getOrNull(2)?.ifBlank { null }
                        ?: p.getOrNull(1)?.ifBlank { null }
                        ?: p.getOrNull(0) ?: "Section ${i + 1}",
                    loading = true,
                    pluginInternalName = p.getOrNull(0) ?: "",
                    sectionName = p.getOrNull(1) ?: "",
                    pluginDisplayName = p.getOrNull(2)?.ifBlank { null }
                        ?: p.getOrNull(0) ?: "",
                )
                "stremio" -> FolderRow(
                    label = p.getOrNull(3)?.ifBlank { null }
                        ?: p.getOrNull(2) ?: "Catalog ${i + 1}",
                    loading = true,
                    addonId = p.getOrNull(0) ?: "",
                    contentType = p.getOrNull(1) ?: "",
                    catalogId = p.getOrNull(2) ?: "",
                )
                else -> FolderRow(
                    label = p.getOrNull(0) ?: "Category ${i + 1}",
                    loading = false,
                )
            }
        }
        rows = skeletons
        pageLoading = false

        val installed = runCatching { pluginRepo.installed.first() }.getOrDefault(emptyList())
        val addons = runCatching { sl.stremio.addons.first() }.getOrDefault(emptyList())

        val loaded = coroutineScope {
            skeletons.mapIndexed { i, skeleton ->
                async {
                    val enc = entries.getOrNull(i) ?: return@async skeleton.copy(loading = false)
                    val p = enc.split("|||")
                    try {
                        when (folder.providerType) {
                            "cloudstream" -> {
                                val iname = p.getOrNull(0) ?: ""
                                val sname = p.getOrNull(1) ?: ""
                                val plugin = installed.firstOrNull { it.internalName == iname }
                                    ?: return@async skeleton.copy(loading = false)
                                val items: List<SearchResponse> = if (sname.isNotBlank()) {
                                    runCatching {
                                        PluginRuntime.homePage(context, plugin.filePath, sname, 1)
                                    }.getOrDefault(emptyList())
                                } else {
                                    runCatching {
                                        PluginRuntime.home(context, plugin.filePath)
                                            .firstOrNull()?.second
                                    }.getOrNull() ?: emptyList()
                                }
                                skeleton.copy(csItems = items, loading = false)
                            }
                            "stremio" -> {
                                val addonId = p.getOrNull(0) ?: ""
                                val cType = p.getOrNull(1) ?: ""
                                val cId = p.getOrNull(2) ?: ""
                                val addon = addons.firstOrNull { it.id == addonId }
                                    ?: return@async skeleton.copy(loading = false)
                                val items = runCatching {
                                    sl.stremio.fetchCatalog(addon, cType, cId)
                                }.getOrDefault(emptyList())
                                skeleton.copy(stremioItems = items, loading = false)
                            }
                            else -> skeleton.copy(loading = false)
                        }
                    } catch (_: Throwable) {
                        skeleton.copy(loading = false)
                    }
                }
            }.awaitAll()
        }
        rows = loaded
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        folderName.ifBlank { "Loading…" },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
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
                item(key = "hdr_${idx}") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!row.loading) {
                            when {
                                row.csItems.isNotEmpty() && row.sectionName.isNotBlank() ->
                                    TextButton(onClick = {
                                        onViewAllCsSection(
                                            row.pluginInternalName,
                                            row.sectionName,
                                            row.pluginDisplayName,
                                        )
                                    }) { Text("View all →") }
                                row.stremioItems.isNotEmpty() ->
                                    TextButton(onClick = {
                                        onOpenCatalog(
                                            "stremio:${row.addonId}:${row.contentType}:${row.catalogId}",
                                            row.label,
                                            "",
                                        )
                                    }) { Text("View all →") }
                            }
                        }
                    }
                }
                item(key = "content_${idx}") {
                    when {
                        row.loading -> Box(
                            Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }

                        row.csItems.isNotEmpty() -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(row.csItems.take(20)) { item ->
                                FolderPosterCard(
                                    title = item.name,
                                    posterUrl = item.posterUrl,
                                    onClick = {
                                        onOpenCsItem(
                                            row.pluginInternalName,
                                            item.url,
                                            item.name,
                                            item.posterUrl,
                                        )
                                    },
                                )
                            }
                        }

                        row.stremioItems.isNotEmpty() -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(row.stremioItems.take(20)) { item ->
                                FolderPosterCard(
                                    title = item.name,
                                    posterUrl = item.poster,
                                    onClick = {
                                        onOpenStremio(
                                            row.addonId,
                                            row.contentType,
                                            item.id,
                                            item.name,
                                            item.poster,
                                        )
                                    },
                                )
                            }
                        }

                        else -> Box(
                            Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No content available",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = posterUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E)),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        )
    }
}
