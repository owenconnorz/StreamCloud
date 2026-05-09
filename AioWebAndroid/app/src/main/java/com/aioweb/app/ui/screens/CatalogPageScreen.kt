package com.aioweb.app.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.aioweb.app.data.ServiceLocator
import com.aioweb.app.data.api.TmdbMovie
import com.aioweb.app.data.api.TmdbFindResponse
import com.aioweb.app.data.collections.HomeCollections
import com.aioweb.app.data.stremio.StremioMetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Endless-scroll View All page used by the "View all →" links on the Movies
 * tab. Handles two source types via [source]:
 *
 *  - `tmdb:<collectionId>` — paginates a curated TMDB collection (Trending,
 *    Marvel, Pixar, …) by hitting [HomeCollection.fetchPage].
 *  - `stremio:<addonId>:<type>:<catalogId>` — paginates a Stremio addon
 *    catalog using its `skip=` query parameter.
 *
 * Posters click through to either the existing TMDB MovieDetail (for TMDB
 * collections) OR resolve via IMDB→TMDB and then route to MovieDetail (for
 * Stremio metas). Stremio resolve failures surface as a small inline banner
 * so the user knows why a tap didn't navigate.
 */
@Composable
fun CatalogPageScreen(
    source: String,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onMovieClick: (Long) -> Unit,
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var tmdbItems by remember(source) { mutableStateOf<List<TmdbMovie>>(emptyList()) }
    var stremioItems by remember(source) { mutableStateOf<List<StremioMetaPreview>>(emptyList()) }
    var page by remember(source) { mutableStateOf(1) }
    var loading by remember(source) { mutableStateOf(false) }
    var endReached by remember(source) { mutableStateOf(false) }
    var notice by remember(source) { mutableStateOf<String?>(null) }

    val isTmdb = source.startsWith("tmdb:")
    val isStremio = source.startsWith("stremio:")

    suspend fun loadNext() {
        if (loading || endReached) return
        loading = true
        try {
            if (isTmdb) {
                val id = source.removePrefix("tmdb:")
                val def = HomeCollections.byId(id)
                if (def == null) { endReached = true; return }
                val items = def.fetchPage(sl.tmdb, sl.tmdbApiKey, page)
                if (items.isEmpty()) endReached = true
                else {
                    tmdbItems = tmdbItems + items
                    page++
                }
            } else if (isStremio) {
                val parts = source.removePrefix("stremio:").split(":")
                if (parts.size < 3) { endReached = true; return }
                val addonId = parts[0]
                val type = parts[1]
                val catalogId = parts.drop(2).joinToString(":")
                val addons = sl.stremio.addons.first()
                val addon = addons.firstOrNull { it.id == addonId }
                if (addon == null) { endReached = true; return }
                // Stremio paginates by `skip`; pull pages of 50 by default.
                val skip = stremioItems.size
                val items = sl.stremio.fetchCatalog(addon, type, catalogId, skip = skip)
                if (items.isEmpty()) endReached = true
                else stremioItems = stremioItems + items
            }
        } catch (e: Exception) {
            notice = "Couldn't load more: ${e.message}"
        } finally {
            loading = false
        }
    }

    // Initial load + endless scroll trigger.
    LaunchedEffect(source) { loadNext() }
    LaunchedEffect(gridState) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && last >= total - 6
        }.collect { reachedEnd -> if (reachedEnd) loadNext() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ── Header (back + title + subtitle), Nuvio-style ──────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        notice?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp),
            )
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (isTmdb) {
                items(tmdbItems, key = { "tmdb_${it.id}" }) { m ->
                    GridPosterTmdb(m) { onMovieClick(m.id) }
                }
            } else if (isStremio) {
                items(stremioItems, key = { "st_${it.id}" }) { meta ->
                    GridPosterStremio(meta) {
                        val id = meta.id
                        if (id.startsWith("tt", ignoreCase = true)) {
                            scope.launch {
                                runCatching {
                                    val r: TmdbFindResponse = sl.tmdb.find(id, sl.tmdbApiKey, "imdb_id")
                                    val t = r.movieResults.firstOrNull()?.id
                                        ?: r.tvResults.firstOrNull()?.id
                                    if (t != null) {
                                        withContext(Dispatchers.Main) { onMovieClick(t) }
                                    } else {
                                        notice = "Couldn't match \"${meta.name}\" to TMDB."
                                    }
                                }.onFailure { e -> notice = "Resolve failed: ${e.message}" }
                            }
                        } else {
                            notice = "Stremio item id is not an IMDB id — open detail unsupported."
                        }
                    }
                }
            }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridPosterTmdb(m: TmdbMovie, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = m.posterUrl,
            contentDescription = m.displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            m.displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GridPosterStremio(meta: StremioMetaPreview, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = meta.poster,
            contentDescription = meta.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            meta.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
