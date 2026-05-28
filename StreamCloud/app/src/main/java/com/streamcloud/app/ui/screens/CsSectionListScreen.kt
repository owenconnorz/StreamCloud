package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lagradost.cloudstream3.SearchResponse
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.plugins.PluginRuntime
import kotlinx.coroutines.flow.first

@Composable
fun CsSectionListScreen(
    pluginInternalName: String,
    sectionName: String,
    pluginDisplayName: String,
    onBack: () -> Unit,
    onOpenItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    val repo = remember { PluginRepository(context.applicationContext) }

    var items by remember { mutableStateOf<List<SearchResponse>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    var filePath by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val gridState = rememberLazyGridState()

    LaunchedEffect(pluginInternalName, sectionName) {
        loading = true
        error = null
        val plugin = repo.installed.first().firstOrNull { it.internalName == pluginInternalName }
        if (plugin == null) {
            loading = false
            error = "Plugin not installed."
            return@LaunchedEffect
        }
        filePath = plugin.filePath
        val firstPage = runCatching {
            PluginRuntime.homePage(context, plugin.filePath, sectionName, 1)
        }.getOrElse {
            error = it.message
            emptyList()
        }
        items = firstPage
        page = 1
        noMore = firstPage.isEmpty()
        loading = false
    }

    LaunchedEffect(gridState) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            last to total
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 6 && !loadingMore && !noMore && !loading && filePath.isNotBlank()) {
                loadingMore = true
                val nextPage = page + 1
                val more = runCatching {
                    PluginRuntime.homePage(context, filePath, sectionName, nextPage)
                }.getOrDefault(emptyList())
                if (more.isEmpty()) {
                    noMore = true
                } else {
                    items = items + more
                    page = nextPage
                }
                loadingMore = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(3) }) {
                Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                    Text(
                        sectionName,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        pluginDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                loading -> {
                    item(span = { GridItemSpan(3) }) {
                        Box(
                            Modifier.fillMaxWidth().padding(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                error != null -> {
                    item(span = { GridItemSpan(3) }) {
                        Text(
                            error ?: "Unknown error",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
                }
                else -> {
                    items(items, key = { it.url }) { sr ->
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onOpenItem(pluginInternalName, sr.url, sr.name, sr.posterUrl)
                                },
                        ) {
                            AsyncImage(
                                model = sr.posterUrl,
                                contentDescription = sr.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth().aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                sr.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (loadingMore) {
                        item(span = { GridItemSpan(3) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
