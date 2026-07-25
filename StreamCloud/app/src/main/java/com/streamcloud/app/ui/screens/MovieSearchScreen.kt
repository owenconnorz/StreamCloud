package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streamcloud.app.ui.viewmodel.CsSearchResult
import com.streamcloud.app.ui.viewmodel.MoviesViewModel
import com.streamcloud.app.ui.viewmodel.StremioSearchResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieSearchScreen(
    onBack: () -> Unit,
    onMovieClick: (Long) -> Unit,
    onTvClick: (Long) -> Unit = {},
    onOpenCsItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit = { _, _, _, _ -> },
    onOpenStremio: (addonId: String, type: String, metaId: String, title: String, poster: String?) -> Unit = { _, _, _, _, _ -> },
) {
    val context = LocalContext.current
    val vm: MoviesViewModel = viewModel(factory = MoviesViewModel.factory(context))
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(query) {
        if (query.length >= 2) {
            kotlinx.coroutines.delay(300)
            vm.search(query)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("Search all sources…") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            when {
                                state.loading -> CircularProgressIndicator(
                                    Modifier.size(20.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, "Clear")
                                }
                            }
                        },
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
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            query.length < 2 -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Type to search across Movies, Series, CloudStream & Stremio addons",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> {
                CombinedResultsList(
                    movieResults = state.searchResults,
                    tvResults = state.tvSearchResults,
                    csResults = state.csSearchResults,
                    stremioResults = state.stremioSearchResults,
                    loading = state.loading,
                    query = query,
                    padding = padding,
                    onMovieClick = onMovieClick,
                    onTvClick = onTvClick,
                    onOpenCsItem = onOpenCsItem,
                    onOpenStremio = onOpenStremio,
                )
            }
        }
    }
}

@Composable
private fun CombinedResultsList(
    movieResults: List<com.streamcloud.app.data.api.TmdbMovie>,
    tvResults: List<com.streamcloud.app.data.api.TmdbMovie>,
    csResults: List<CsSearchResult>,
    stremioResults: List<StremioSearchResult>,
    loading: Boolean,
    query: String,
    padding: PaddingValues,
    onMovieClick: (Long) -> Unit,
    onTvClick: (Long) -> Unit,
    onOpenCsItem: (pluginInternalName: String, url: String, name: String, poster: String?) -> Unit,
    onOpenStremio: (addonId: String, type: String, metaId: String, title: String, poster: String?) -> Unit,
) {
    val csGrouped = remember(csResults) { csResults.groupBy { it.pluginName } }
    val stremioGrouped = remember(stremioResults) { stremioResults.groupBy { it.addonName } }
    val hasAny = movieResults.isNotEmpty() || tvResults.isNotEmpty() ||
        csResults.isNotEmpty() || stremioResults.isNotEmpty()

    if (!hasAny && !loading) {
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No results for \"$query\"",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val movieRows = remember(movieResults) { movieResults.chunked(2) }
    val tvRows = remember(tvResults) { tvResults.chunked(2) }

    LazyColumn(
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = 32.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // ── Series (TMDB TV) ──────────────────────────────────────────────
        if (tvResults.isNotEmpty()) {
            item(key = "series-header") {
                NuvioSectionHeader(title = "Series", topPadding = 0.dp)
            }
            items(tvRows, key = { "tv-row-${it.first().id}" }) { pair ->
                LandscapeCardRow(pair) { movie -> onTvClick(movie.id) }
            }
        }

        // ── Movies (TMDB) ─────────────────────────────────────────────────
        if (movieResults.isNotEmpty()) {
            item(key = "movies-header") {
                NuvioSectionHeader(
                    title = "Movies",
                    topPadding = if (tvResults.isNotEmpty()) 20.dp else 0.dp,
                )
            }
            items(movieRows, key = { "movie-row-${it.first().id}" }) { pair ->
                LandscapeCardRow(pair) { movie -> onMovieClick(movie.id) }
            }
        }

        // ── CloudStream ───────────────────────────────────────────────────
        if (csGrouped.isNotEmpty()) {
            item(key = "cs-header") {
                NuvioSectionHeader(
                    title = "CloudStream",
                    topPadding = if (movieResults.isNotEmpty() || tvResults.isNotEmpty()) 20.dp else 0.dp,
                )
            }
            csGrouped.forEach { (pluginName, results) ->
                item(key = "cs-plugin-$pluginName") {
                    Text(
                        pluginName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    )
                }
                val csRows = results.chunked(2)
                items(csRows, key = { "cs-row-$pluginName-${it.first().item.url}" }) { pair ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { r ->
                            LandscapeCardItem(
                                imageUrl = r.item.posterUrl,
                                title = r.item.name,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenCsItem(r.pluginInternalName, r.item.url, r.item.name, r.item.posterUrl) },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ── Stremio ───────────────────────────────────────────────────────
        if (stremioGrouped.isNotEmpty()) {
            item(key = "stremio-header") {
                NuvioSectionHeader(
                    title = "Stremio",
                    topPadding = if (movieResults.isNotEmpty() || tvResults.isNotEmpty() || csGrouped.isNotEmpty()) 20.dp else 0.dp,
                )
            }
            stremioGrouped.forEach { (addonName, results) ->
                item(key = "stremio-addon-$addonName") {
                    Text(
                        addonName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    )
                }
                val sRows = results.chunked(2)
                items(sRows, key = { "stremio-row-$addonName-${it.first().item.id}" }) { pair ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { r ->
                            LandscapeCardItem(
                                imageUrl = r.item.poster,
                                title = r.item.name,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpenStremio(r.addonId, r.item.type, r.item.id, r.item.name, r.item.poster) },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** 2-card landscape row for TMDB results (movie or TV). */
@Composable
private fun LandscapeCardRow(
    pair: List<com.streamcloud.app.data.api.TmdbMovie>,
    onClick: (com.streamcloud.app.data.api.TmdbMovie) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pair.forEach { m ->
            LandscapeCardItem(
                imageUrl = m.backdropUrl ?: m.posterUrl,
                title = m.displayTitle,
                modifier = Modifier.weight(1f),
                onClick = { onClick(m) },
            )
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
}

/** Single landscape card — matches Nuvio's wide 2-column tile style. */
@Composable
private fun LandscapeCardItem(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Gradient scrim + title overlaid at the bottom (Nuvio style)
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                        ),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                    ),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NuvioSectionHeader(title: String, topPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = topPadding, bottom = 12.dp),
    )
}
