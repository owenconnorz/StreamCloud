package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.stremio.StremioMetaPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionTabbedScreen(
    collectionId: Long,
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
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    var collectionName by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf<List<CollectionFolderEntity>>(emptyList()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    // Map from folderId to loaded rows
    var rowsMap by remember { mutableStateOf<Map<Long, List<FolderRow>>>(emptyMap()) }
    var loadingFolders by remember { mutableStateOf(true) }

    LaunchedEffect(collectionId) {
        loadingFolders = true
        val db = LibraryDb.get(context.applicationContext)
        val collection = db.userCollections().byId(collectionId)
        collectionName = collection?.name ?: "Collection"
        val loaded = db.collectionFolders().forCollectionOnce(collectionId)
        folders = loaded
        loadingFolders = false
    }

    // Load content for the currently selected tab
    val currentFolder = folders.getOrNull(selectedTab)
    LaunchedEffect(currentFolder?.id) {
        val folder = currentFolder ?: return@LaunchedEffect
        if (rowsMap.containsKey(folder.id)) return@LaunchedEffect
        rowsMap = rowsMap + (folder.id to listOf(FolderRow(label = "Loading…", loading = true)))
        val loaded = loadFolderRows(context.applicationContext, folder)
        rowsMap = rowsMap + (folder.id to loaded)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        collectionName.ifBlank { "Collection" },
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
        if (loadingFolders) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (folders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No folders in this collection.", color = Color.Gray)
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            // Folder tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab.coerceIn(0, folders.lastIndex),
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                folders.forEachIndexed { i, folder ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = {
                            Text(
                                folder.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }

            HorizontalDivider()

            // Content for selected tab
            val rows = rowsMap[currentFolder?.id] ?: emptyList()
            if (rows.isEmpty() || (rows.size == 1 && rows[0].loading)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // Flatten all items across rows into a grid
                val allItems = rows.flatMap { row ->
                    when {
                        row.tmdbItems.isNotEmpty() -> row.tmdbItems.map { TabbedItem.Tmdb(it, row.isTv) }
                        row.stremioItems.isNotEmpty() -> row.stremioItems.map { TabbedItem.Stremio(it, row.addonId, row.contentType) }
                        row.csItems.isNotEmpty() -> row.csItems.map { TabbedItem.Cs(it, row.pluginInternalName) }
                        else -> emptyList()
                    }
                }

                if (allItems.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No content available", color = Color.Gray)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = if (isTv) GridCells.Fixed(6) else GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(allItems, key = { it.key }) { item ->
                            val cardMod = if (isTv) Modifier.fillMaxWidth() else Modifier.width(110.dp)
                            when (item) {
                                is TabbedItem.Tmdb -> FolderPosterCard(
                                    title = item.movie.displayTitle,
                                    posterUrl = item.movie.posterUrl,
                                    onClick = { if (item.isTv) onTvClick(item.movie.id) else onMovieClick(item.movie.id) },
                                    modifier = cardMod,
                                )
                                is TabbedItem.Stremio -> FolderPosterCard(
                                    title = item.meta.name,
                                    posterUrl = item.meta.poster,
                                    onClick = { onOpenStremio(item.addonId, item.contentType, item.meta.id, item.meta.name, item.meta.poster) },
                                    modifier = cardMod,
                                )
                                is TabbedItem.Cs -> FolderPosterCard(
                                    title = item.result.name,
                                    posterUrl = item.result.posterUrl,
                                    onClick = { onOpenCsItem(item.pluginInternalName, item.result.url, item.result.name, item.result.posterUrl) },
                                    modifier = cardMod,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed class TabbedItem {
    abstract val key: String

    data class Tmdb(val movie: TmdbMovie, val isTv: Boolean) : TabbedItem() {
        override val key get() = "tmdb_${movie.id}"
    }
    data class Stremio(val meta: StremioMetaPreview, val addonId: String, val contentType: String) : TabbedItem() {
        override val key get() = "stremio_${meta.id}"
    }
    data class Cs(val result: com.lagradost.cloudstream3.SearchResponse, val pluginInternalName: String) : TabbedItem() {
        override val key get() = "cs_${result.url}"
    }
}
