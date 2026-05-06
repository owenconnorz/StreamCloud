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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import com.aioweb.app.ui.viewmodel.MoviesViewModel
import com.aioweb.app.ui.viewmodel.SOURCE_BUILTIN

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesScreen(onMovieClick: (Long) -> Unit) {
    val context = LocalContext.current
    val vm: MoviesViewModel = viewModel(factory = MoviesViewModel.factory(context))
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item { MoviesHeader() }
            item {
                MoviesSearchField(
                    query = query,
                    loading = state.loading,
                    onQueryChange = { query = it; vm.search(it) },
                )
            }
            item {
                SourceChipsRow(
                    plugins = state.installedPlugins,
                    selectedId = state.selectedSourceId,
                    onSelect = vm::selectSource,
                )
            }
            state.notice?.let {
                item {
                    NoticeBanner(it, onDismiss = vm::clearNotice)
                }
            }
            state.error?.let {
                item {
                    Text(
                        it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }

            // Plugin error/loading banner
            if (state.pluginLoading) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Loading ${state.selectedSourceName} home feed…",
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            state.pluginError?.let { err ->
                item {
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
                        Spacer(Modifier.height(12.dp))
                        // Many CloudStream plugins have no `mainPage` — they only
                        // expose search. Hint the user instead of dead-ending
                        // them on the empty error card.
                        Text(
                            "Tip: Use the search bar above to look up a title — most plugins return results that way even when their home feed is empty.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            if (query.isNotBlank()) {
                item { SectionTitle("Search results") }
                if (state.isPluginActive) {
                    item {
                        PluginPosterGrid(state.pluginSearchResults) { /* TODO link to plugin detail */ }
                    }
                } else {
                    item {
                        PosterGrid(
                            movies = state.searchResults,
                            onClick = onMovieClick,
                        )
                    }
                }
            } else if (state.isPluginActive) {
                // ── Plugin home feed sections ────────────────────────────
                state.pluginSections.forEachIndexed { idx, section ->
                    item(key = "psec_t_$idx") { SectionTitle(section.title) }
                    item(key = "psec_$idx") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(section.items, key = { "ps_${idx}_${it.url}" }) { sr ->
                                PluginPoster(sr, onClick = { /* TODO link to plugin detail */ })
                            }
                        }
                    }
                }
            } else {
                val srcLabel = state.selectedSourceName
                if (state.heroBanner.isNotEmpty()) {
                    item(key = "hero_pager") {
                        HeroPager(
                            items = state.heroBanner,
                            onClick = { onMovieClick(it) },
                        )
                    }
                }
                if (state.continueWatching.isNotEmpty()) {
                    item(key = "continue_watching_t") {
                        SectionTitle("Continue Watching")
                    }
                    item(key = "continue_watching") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                state.continueWatching,
                                key = { "cw_${it.tmdbId}" },
                            ) { entry ->
                                ContinueWatchingCard(
                                    entry = entry,
                                    onClick = { onMovieClick(entry.tmdbId) },
                                )
                            }
                        }
                    }
                }
                state.collections.forEachIndexed { idx, row ->
                    item(key = "col_t_${row.id}") { SectionTitle("${row.emoji}  ${row.title}") }
                    item(key = "col_${row.id}") {
                        if (idx == 0 || row.id == "popular") {
                            // Top trending row uses a tighter 3x grid.
                            PosterGrid(movies = row.items.take(9), onClick = onMovieClick)
                        } else {
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
                }
                if (state.collections.isEmpty() && !state.loading) {
                    item {
                        Text(
                            "No collections enabled. Open Settings → Home collections to pick rows.",
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
private fun MoviesHeader() {
    Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp)) {
        Text(
            "Discover",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Black,
        )
        Text(
            "Movies, series, plugins — all in one place",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroPager(
    items: List<TmdbMovie>,
    onClick: (Long) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })

    // Auto-advance every 6 seconds — Nuvio-style.
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
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp),
            pageSpacing = 0.dp,
        ) { page ->
            val m = items[page]
            HeroBannerSlide(movie = m, onClick = { onClick(m.id) })
        }
        Spacer(Modifier.height(10.dp))
        // Dot indicator
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
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
        // Vertical scrim — dark on top + bottom for text legibility.
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
        // Bottom-anchored content
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
            // White "View Details" pill (matches Nuvio screenshot exactly)
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
    // OpenTune-style filled search bar — pill-shaped, no border, sits cleanly
    // against the dark background. Replaces the old OutlinedTextField that
    // looked off after the global theme picked up dynamic album-art colors.
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
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun SourceChipsRow(
    plugins: List<InstalledPlugin>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SourceChip(
                label = "Built-in",
                icon = Icons.Default.AllInclusive,
                selected = selectedId == SOURCE_BUILTIN,
                onClick = { onSelect(SOURCE_BUILTIN) },
            )
        }
        items(plugins, key = { "pl_${it.internalName}" }) { p ->
            SourceChip(
                label = p.name,
                icon = Icons.Default.Extension,
                logoUrl = p.iconUrl,
                selected = selectedId == p.internalName,
                onClick = { onSelect(p.internalName) },
            )
        }
    }
}

@Composable
private fun SourceChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    logoUrl: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Fixed brand purple — independent of the dynamic album-art accent so
    // CloudStream / Stremio source chips always read the same regardless
    // of what's playing. Pill shape (50% rounded) per Metrolist /
    // OpenTune conventions.
    val brandPurple = androidx.compose.ui.graphics.Color(0xFF7C5CFF)
    val onBrand = androidx.compose.ui.graphics.Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) brandPurple
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (!logoUrl.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Icon(
                icon, null,
                tint = if (selected) onBrand else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) onBrand else MaterialTheme.colorScheme.onSurface,
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
            Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp))
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

@Composable
private fun HeroPoster(m: TmdbMovie, onClick: () -> Unit) {
    Box(
        Modifier
            .width(280.dp).height(160.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = m.backdropUrl ?: m.posterUrl,
            contentDescription = m.displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))
            )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(
                m.displayTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    String.format("%.1f", m.voteAverage),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
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
            // Progress bar
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
private fun PluginPoster(sr: com.lagradost.cloudstream3.SearchResponse, onClick: () -> Unit) {
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
private fun StremioPoster(meta: com.aioweb.app.data.stremio.StremioMetaPreview) {
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp)),
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
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StremioPosterGrid(items: List<com.aioweb.app.data.stremio.StremioMetaPreview>) {
    if (items.isEmpty()) {
        Text(
            "No results from this addon.",
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
                row.forEach { m ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        AsyncImage(
                            model = m.poster,
                            contentDescription = m.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth().aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            m.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}


@Composable
private fun PluginPosterGrid(
    items: List<com.lagradost.cloudstream3.SearchResponse>,
    onClick: (com.lagradost.cloudstream3.SearchResponse) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            "No results from this plugin.",
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
                // Fill remaining slots
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
