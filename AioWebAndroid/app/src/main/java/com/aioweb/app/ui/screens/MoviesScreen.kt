package com.aioweb.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aioweb.app.data.api.TmdbMovie
import com.aioweb.app.data.library.WatchProgressEntity
import com.aioweb.app.data.plugins.InstalledPlugin
import com.aioweb.app.data.stremio.StremioHomeRow
import com.aioweb.app.data.stremio.StremioMetaPreview
import com.aioweb.app.ui.viewmodel.MoviesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesScreen(
    onMovieClick: (Long) -> Unit,
    onOpenCloudStreamPlugin: (internalName: String) -> Unit = {},
    onProfileClick: () -> Unit = {},
    onOpenCatalog: (source: String, title: String, subtitle: String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val vm: MoviesViewModel = viewModel(factory = MoviesViewModel.factory(context))
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item { MoviesHeader(onProfileClick = onProfileClick) }
            item {
                MoviesSearchField(
                    query = query,
                    loading = state.loading,
                    onQueryChange = { query = it; vm.search(it) },
                )
            }
            // CloudStream plugins — separate page each. Stremio addons feed inline rows.
            if (state.installedPlugins.isNotEmpty()) {
                item {
                    CloudStreamChipsRow(
                        plugins = state.installedPlugins,
                        onOpen = onOpenCloudStreamPlugin,
                    )
                }
            }
            state.notice?.let {
                item { NoticeBanner(it, onDismiss = vm::clearNotice) }
            }
            state.error?.let {
                item {
                    Text(
                        it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }

            if (query.isNotBlank()) {
                item { SectionTitle("Search results") }
                item {
                    PosterGrid(movies = state.searchResults, onClick = onMovieClick)
                }
            } else {
                if (state.heroBanner.isNotEmpty()) {
                    item(key = "hero_pager") {
                        HeroPager(
                            items = state.heroBanner,
                            onClick = { onMovieClick(it) },
                        )
                    }
                }
                if (state.continueWatching.isNotEmpty()) {
                    item(key = "continue_watching_t") { SectionTitle("Continue Watching") }
                    item(key = "continue_watching") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.continueWatching, key = { "cw_${it.tmdbId}" }) { entry ->
                                ContinueWatchingCard(
                                    entry = entry,
                                    onClick = { onMovieClick(entry.tmdbId) },
                                )
                            }
                        }
                    }
                }
                state.collections.forEachIndexed { _, row ->
                    item(key = "col_t_${row.id}") {
                        SectionTitleWithViewAll(
                            title = row.title,
                            onViewAll = {
                                onOpenCatalog(
                                    "tmdb:${row.id}",
                                    row.title,
                                    com.aioweb.app.data.collections.HomeCollections
                                        .byId(row.id)?.subtitle.orEmpty(),
                                )
                            },
                        )
                    }
                    item(key = "col_${row.id}") {
                        // Single-line horizontal scroll for every TMDB row,
                        // matching Nuvio's design. Trending / Popular used to
                        // render as a 3-col grid which broke visual rhythm
                        // against the addon rows below.
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(row.items, key = { "${row.id}_${it.id}" }) { m ->
                                MidPoster(m, onClick = { onMovieClick(m.id) })
                            }
                        }
                    }
                }
                // ── Stremio addon catalogs — NuvioMobile parity ─────────
                state.stremioRows.forEach { row ->
                    item(key = "stremio_t_${row.rowKey}") {
                        AddonSectionTitleWithViewAll(
                            addon = row.addonName,
                            catalog = row.catalogName,
                            onViewAll = {
                                onOpenCatalog(
                                    "stremio:${row.addonId}:${row.type}:${row.catalogId}",
                                    row.catalogName,
                                    row.addonName,
                                )
                            },
                        )
                    }
                    item(key = "stremio_${row.rowKey}") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(row.items, key = { "${row.rowKey}_${it.id}" }) { meta ->
                                StremioPoster(meta = meta) {
                                    vm.openStremioMeta(meta) { tmdbId, _ ->
                                        if (tmdbId != null) onMovieClick(tmdbId)
                                        // else: VM has already set a notice
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.collections.isEmpty() && state.stremioRows.isEmpty() && !state.loading) {
                    item {
                        Text(
                            "No collections enabled. Open Settings → Home collections to pick rows, or install a Stremio addon.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoviesHeader(onProfileClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 16.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Discover",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Movies, series, addons — all in one place",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        com.aioweb.app.ui.components.ProfileButton(onClick = onProfileClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroPager(
    items: List<TmdbMovie>,
    onClick: (Long) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(6_000)
            val next = (pagerState.currentPage + 1) % items.size
            pagerState.animateScrollToPage(next)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(520.dp),
            pageSpacing = 0.dp,
        ) { page ->
            val m = items[page]
            HeroBannerSlide(movie = m, onClick = { onClick(m.id) })
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            items.forEachIndexed { i, _ ->
                val active = i == pagerState.currentPage
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .height(6.dp)
                        .width(if (active) 22.dp else 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroBannerSlide(movie: TmdbMovie, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = movie.backdropUrl ?: movie.posterUrl,
            contentDescription = movie.displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.45f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.92f),
                    ),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY,
                )
            )
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                movie.displayTitle,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                ),
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            val meta = listOfNotNull(
                "Movie",
                movie.releaseDate?.takeIf { it.isNotBlank() }?.substringBefore('-'),
                movie.voteAverage?.takeIf { it > 0 }?.let { String.format("%.1f ★", it) },
            ).joinToString("  •  ")
            Text(
                meta,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 38.dp, vertical = 14.dp),
            ) {
                Text(
                    "View Details",
                    color = Color(0xFF111111),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoviesSearchField(query: String, loading: Boolean, onQueryChange: (String) -> Unit) {
    androidx.compose.material3.TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp)),
        placeholder = { Text("Search movies, series, anime") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
            if (loading) CircularProgressIndicator(
                Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * Horizontal chip strip listing every installed CloudStream `.cs3` plugin.
 * Tap a chip → host navigates to a dedicated page that renders the plugin's
 * own home sections (separate from the unified Stremio + TMDB feed).
 */
@Composable
private fun CloudStreamChipsRow(
    plugins: List<InstalledPlugin>,
    onOpen: (String) -> Unit,
) {
    Column {
        Text(
            "CloudStream plugins",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(plugins, key = { "pl_${it.internalName}" }) { p ->
                PluginChip(label = p.name, logoUrl = p.iconUrl, onClick = { onOpen(p.internalName) })
            }
        }
    }
}

@Composable
private fun PluginChip(label: String, logoUrl: String?, onClick: () -> Unit) {
    val brand = Color(0xFF7C5CFF)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Icon(
                Icons.Default.Extension, null,
                tint = brand,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(12.dp),
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close, "Dismiss",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** Section header with a trailing tappable "View all →" affordance. */
@Composable
private fun SectionTitleWithViewAll(title: String, onViewAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "View all →",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onViewAll)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * NuvioMobile-style row title — primary catalog name + a tinted "from <addon>"
 * subtitle so the user always knows which addon contributed the row.
 */
@Composable
private fun AddonSectionTitle(addon: String, catalog: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            catalog,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "from $addon",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Stremio addon row header with a trailing "View all →" link. */
@Composable
private fun AddonSectionTitleWithViewAll(
    addon: String,
    catalog: String,
    onViewAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                catalog,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "from $addon",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "View all →",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onViewAll)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ContinueWatchingCard(
    entry: WatchProgressEntity,
    onClick: () -> Unit,
) {
    val pct = if (entry.durationMs > 0L)
        (entry.positionMs.toFloat() / entry.durationMs.toFloat()).coerceIn(0f, 1f)
    else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        AsyncImage(
            model = entry.posterUrl,
            contentDescription = entry.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 84.dp, height = 116.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (entry.mediaType == "tv") "Series" else "Movie",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(pct.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${(pct * 100).toInt()}% watched",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MidPoster(m: TmdbMovie, onClick: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
private fun StremioPoster(meta: StremioMetaPreview, onClick: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
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
        if (!meta.releaseInfo.isNullOrBlank()) {
            Text(
                meta.releaseInfo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PosterGrid(movies: List<TmdbMovie>, onClick: (Long) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        movies.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onClick(m.id) }
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
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
