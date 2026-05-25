package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.plugins.PluginRuntime
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class PluginPageState(
    val sections: List<Pair<String, List<SearchResponse>>> = emptyList(),
    val searchResults: List<SearchResponse> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val pluginName: String = "",
    val pluginFilePath: String = "",
)

private data class ViewAllState(
    val title: String,
    val items: List<SearchResponse>,
    val filePath: String,
    val page: Int = 1,
    val loadingMore: Boolean = false,
    val noMore: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudStreamPluginScreen(
    internalName: String,
    onBack: () -> Unit,
    onOpenItem: (pluginInternalName: String, url: String, name: String, posterUrl: String?) -> Unit = { _, _, _, _ -> },
    initialSearch: String? = null,
) {
    val context = LocalContext.current
    val repo = remember { PluginRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(PluginPageState(loading = true)) }
    var query by remember { mutableStateOf(initialSearch ?: "") }
    var viewAllSection by remember { mutableStateOf<ViewAllState?>(null) }

    LaunchedEffect(internalName, initialSearch) {
        if (!initialSearch.isNullOrBlank()) {
            val p = repo.installed.first().firstOrNull { it.internalName == internalName }
            if (p != null) {
                state = state.copy(pluginName = p.name, pluginFilePath = p.filePath, loading = false)
                runCatching { PluginRuntime.search(context, p.filePath, initialSearch) }
                    .onSuccess { state = state.copy(searchResults = it) }
                    .onFailure { state = state.copy(error = it.message) }
            }
            return@LaunchedEffect
        }
    }

    LaunchedEffect(internalName) {
        if (!initialSearch.isNullOrBlank()) return@LaunchedEffect
        val plugin = repo.installed.first().firstOrNull { it.internalName == internalName }
        if (plugin == null) {
            state = state.copy(loading = false, error = "Plugin not installed.")
            return@LaunchedEffect
        }
        state = state.copy(pluginName = plugin.name, pluginFilePath = plugin.filePath, loading = true, error = null)
        try {
            val sections = PluginRuntime.home(context, plugin.filePath)
            if (sections.isEmpty()) {
                val err = PluginRuntime.lastErrorFor(plugin.filePath)
                    ?: "This plugin has no home feed. Use search above — most plugins return results that way."
                state = state.copy(loading = false, sections = emptyList(), error = err)
            } else {
                state = state.copy(loading = false, sections = sections, error = null)
            }
        } catch (e: Throwable) {
            state = state.copy(
                loading = false,
                error = "Plugin failed: ${e::class.simpleName}: ${e.message}",
            )
        }
    }

    // "View All" sub-screen with infinite scroll
    val section = viewAllSection
    if (section != null) {
        val gridState = rememberLazyGridState()

        LaunchedEffect(gridState, section.page) {
            snapshotFlow {
                val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val total = gridState.layoutInfo.totalItemsCount
                last to total
            }.collect { (last, total) ->
                if (total > 0 && last >= total - 6 && !section.loadingMore && !section.noMore) {
                    val vs = viewAllSection ?: return@collect
                    viewAllSection = vs.copy(loadingMore = true)
                    val nextPage = vs.page + 1
                    val more = runCatching {
                        PluginRuntime.homePage(context, vs.filePath, vs.title, nextPage)
                    }.getOrDefault(emptyList())
                    viewAllSection = vs.copy(
                        items = vs.items + more,
                        page = nextPage,
                        loadingMore = false,
                        noMore = more.isEmpty(),
                    )
                }
            }
        }

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TopAppBar(
                title = { Text(section.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewAllSection = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(section.items, key = { it.url }) { sr ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenItem(internalName, sr.url, sr.name, sr.posterUrl) }
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
                if (section.loadingMore) {
                    item(key = "loading_more", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
        return
    }

    // Main home screen
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(state.pluginName.ifBlank { "Plugin" }, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        )
        TextField(
            value = query,
            onValueChange = { newQuery ->
                query = newQuery
                scope.launch {
                    delay(350)
                    if (newQuery == query && newQuery.isNotBlank()) {
                        val p = repo.installed.first()
                            .firstOrNull { it.internalName == internalName }
                        if (p != null) {
                            runCatching { PluginRuntime.search(context, p.filePath, newQuery) }
                                .onSuccess { state = state.copy(searchResults = it) }
                        }
                    } else if (newQuery.isBlank()) {
                        state = state.copy(searchResults = emptyList())
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(28.dp)),
            placeholder = { Text("Search ${state.pluginName.ifBlank { "this plugin" }}…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )

        if (state.loading) {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Loading ${state.pluginName} home…")
            }
        }

        state.error?.let { err ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(16.dp),
            ) {
                Text(
                    "Plugin error",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    err,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (query.isNotBlank()) {
                item {
                    Text(
                        "Search results",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
                item { PluginGrid(items = state.searchResults, onClick = { sr ->
                    onOpenItem(internalName, sr.url, sr.name, sr.posterUrl)
                }) }
            } else {
                state.sections.forEachIndexed { idx, (title, items) ->
                    item(key = "psec_t_$idx") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                viewAllSection = ViewAllState(
                                    title = title,
                                    items = items,
                                    filePath = state.pluginFilePath,
                                )
                            }) {
                                Text(
                                    "View All",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    item(key = "psec_$idx") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(items, key = { "ps_${idx}_${it.url}" }) { sr ->
                                PluginPoster(sr, onClick = {
                                    onOpenItem(internalName, sr.url, sr.name, sr.posterUrl)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginPoster(sr: SearchResponse, onClick: () -> Unit = {}) {
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
        Spacer(Modifier.height(6.dp))
        Text(
            sr.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PluginGrid(items: List<SearchResponse>, onClick: (SearchResponse) -> Unit = {}) {
    if (items.isEmpty()) {
        Text(
            "No results.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { sr ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onClick(sr) }
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
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
