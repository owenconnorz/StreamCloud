package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lagradost.cloudstream3.SubtitleFile
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.ui.viewmodel.MoviesViewModel
import com.streamcloud.app.data.api.TmdbVideo
import com.streamcloud.app.data.nuvio.InstalledNuvioProvider
import com.streamcloud.app.data.nuvio.NuvioStream
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioStream
import com.streamcloud.app.player.PlayerSource
import com.streamcloud.app.player.WatchProgressKey
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    movieId: Long,
    mediaType: String = "movie",
    onBack: () -> Unit,

    onPlay: (initialUrl: String, title: String, sources: List<PlayerSource>, progressKey: WatchProgressKey) -> Unit,
    onOpenCsPluginForMovie: (internalName: String, title: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()

    var movie by remember { mutableStateOf<TmdbMovie?>(null) }
    var videos by remember { mutableStateOf<List<TmdbVideo>>(emptyList()) }
    var imdbId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val installedAddons by sl.stremio.addons.collectAsState(initial = emptyList())
    val installedNuvio by sl.nuvio.installed.collectAsState(initial = emptyList())
    val installedCsPlugins by sl.plugins.installed.collectAsState(initial = emptyList())
    var resolving by remember { mutableStateOf(false) }
    var resolverMessage by remember { mutableStateOf<String?>(null) }

    // CS source picker sheet
    var csPickerPlugin by remember { mutableStateOf<InstalledPlugin?>(null) }
    var csPickerSources by remember { mutableStateOf<List<ExtractorLink>>(emptyList()) }
    var csPickerSubs by remember { mutableStateOf<List<SubtitleFile>>(emptyList()) }
    var csPickerLoading by remember { mutableStateOf(false) }
    var csPickerError by remember { mutableStateOf<String?>(null) }
    var csPickerSelSource by remember { mutableStateOf<ExtractorLink?>(null) }
    var csPickerSelSub by remember { mutableStateOf<SubtitleFile?>(null) }

    LaunchedEffect(movieId) {
        try {
            if (mediaType == "tv") {
                movie = sl.tmdb.tvDetails(movieId, sl.tmdbApiKey)
                videos = sl.tmdb.tvVideos(movieId, sl.tmdbApiKey).results
                imdbId = sl.tmdb.tvExternalIds(movieId, sl.tmdbApiKey).imdbId
            } else {
                movie = sl.tmdb.details(movieId, sl.tmdbApiKey)
                videos = sl.tmdb.videos(movieId, sl.tmdbApiKey).results
                imdbId = sl.tmdb.externalIds(movieId, sl.tmdbApiKey).imdbId
            }
        } catch (e: Exception) {
            error = "Failed to load: ${e.message}"
        }
    }

    fun playMovie() {
        val tt = imdbId
        if (tt == null) {
            resolverMessage = "Loading IMDB id… try again in a second."
            return
        }
        if (installedAddons.isEmpty() && installedNuvio.isEmpty() && installedCsPlugins.isEmpty()) {
            resolverMessage = "No Stremio addons, Nuvio providers or CloudStream plugins installed. Add some from Settings → Plugins."
            return
        }
        scope.launch {
            resolving = true
            resolverMessage = null
            try {




                val stremioType = if (mediaType == "tv") "series" else "movie"
                val stremioJob = async {
                    installedAddons.map { addon ->
                        async {
                            runCatching { sl.stremio.fetchStreams(addon, stremioType, tt) }
                                .map { streams -> streams.mapNotNull { it.toPlayerSource(addon) } }
                                .getOrDefault(emptyList())
                        }
                    }.awaitAll().flatten()
                }
                val csJob = async {
                    val title = movie?.displayTitle.orEmpty()
                    if (title.isBlank()) emptyList<PlayerSource>()
                    else installedCsPlugins
                        .filter { !it.isAdultPlugin() }
                        .map { plugin ->
                            async { resolveCsPluginForMovie(context, plugin, title, movie?.year()) }
                        }.awaitAll().flatten()
                }

                val fastSources = stremioJob.await() + csJob.await()

                val m = movie
                val displayTitle = m?.displayTitle ?: "Playback"
                val progressKey = WatchProgressKey(
                    tmdbId = movieId,
                    title = displayTitle,
                    posterUrl = m?.posterUrl ?: m?.backdropUrl,
                    mediaType = mediaType,
                )

                if (fastSources.isNotEmpty()) {


                    val sorted = fastSources.sortedByDescending { it.qualityScore() }



                    if (installedNuvio.isNotEmpty()) {
                        com.streamcloud.app.player.MoviePlayerSession.setNuvioScanning(true)
                    }

                    onPlay(sorted.first().url, displayTitle, sorted, progressKey)


                    if (installedNuvio.isNotEmpty()) {
                        scope.launch {
                            try {
                                val nuvioSources = runCatching {
                                    sl.nuvio.resolveAll(movieId.toString(), mediaType)
                                        .map { (provider, stream) -> stream.toPlayerSource(provider) }
                                }.getOrDefault(emptyList())
                                if (nuvioSources.isNotEmpty()) {
                                    com.streamcloud.app.player.MoviePlayerSession.mergeSources(nuvioSources)
                                }
                            } finally {
                                com.streamcloud.app.player.MoviePlayerSession.setNuvioScanning(false)
                            }
                        }
                    }
                } else {

                    val nuvioSources = runCatching {
                        sl.nuvio.resolveAll(movieId.toString(), mediaType)
                            .map { (provider, stream) -> stream.toPlayerSource(provider) }
                    }.getOrDefault(emptyList())

                    val all = nuvioSources
                    if (all.isEmpty()) {
                        val total = installedAddons.size + installedNuvio.size + installedCsPlugins.size
                        resolverMessage = "No streams found across $total source(s)."
                        return@launch
                    }
                    val sorted = all.sortedByDescending { it.qualityScore() }
                    onPlay(sorted.first().url, displayTitle, sorted, progressKey)
                }
            } finally {
                resolving = false
            }
        }
    }

    fun openCsSourcePicker(plugin: InstalledPlugin) {
        val title = movie?.displayTitle ?: return
        val year = movie?.year()
        csPickerPlugin = plugin
        csPickerSources = emptyList()
        csPickerSubs = emptyList()
        csPickerSelSource = null
        csPickerSelSub = null
        csPickerError = null
        csPickerLoading = true
        scope.launch {
            try {
                val results = runCatching {
                    PluginRuntime.search(context, plugin.filePath, title)
                }.getOrDefault(emptyList())
                val best = pickBestMatch(results, title, year)
                if (best == null) {
                    csPickerError = "No results found for \"$title\" in ${plugin.name}."
                    return@launch
                }
                val detail = runCatching {
                    PluginRuntime.loadDetail(context, plugin.filePath, best.url)
                }.getOrNull()
                val movieDetail = detail as? MovieLoadResponse ?: run {
                    csPickerError = "Could not load movie details from ${plugin.name}."
                    return@launch
                }
                val (links, subs) = runCatching {
                    PluginRuntime.loadLinks(context, plugin.filePath, movieDetail.dataUrl)
                }.getOrElse { emptyList<ExtractorLink>() to emptyList<SubtitleFile>() }
                if (links.isEmpty()) {
                    csPickerError = "No streams found for \"$title\" in ${plugin.name}."
                    return@launch
                }
                csPickerSources = links.sortedByDescending { it.quality }
                csPickerSubs = subs
                csPickerSelSource = csPickerSources.first()
            } catch (e: Throwable) {
                csPickerError = "Error: ${e.message}"
            } finally {
                csPickerLoading = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                AsyncImage(
                    model = movie?.backdropUrl ?: movie?.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.4f),
                                Color.Transparent,
                                MaterialTheme.colorScheme.background,
                            )
                        )
                    )
                )
            }
            Column(Modifier.padding(20.dp).offset(y = (-40).dp)) {
                Text(
                    movie?.displayTitle ?: "Loading…",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        String.format("%.1f", movie?.voteAverage ?: 0.0),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.width(16.dp))
                    movie?.releaseDate?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it.substringBefore("-"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))


                val moviesVm: MoviesViewModel = viewModel(factory = MoviesViewModel.factory(context))
                val watchlistIds = moviesVm.state.collectAsState().value.watchlist.map { it.tmdbId }.toSet()
                val inWatchlist = movie?.id?.let { it in watchlistIds } ?: false
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        PlayMovieCta(
                            addonCount = installedAddons.size + installedNuvio.size + installedCsPlugins.size,
                            enabled = imdbId != null && (installedAddons.isNotEmpty() || installedNuvio.isNotEmpty() || installedCsPlugins.isNotEmpty()) && !resolving,
                            loading = resolving,
                            onClick = { playMovie() },
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    IconButton(
                        onClick = {
                            movie?.let {
                                moviesVm.toggleWatchlist(
                                    tmdbId = it.id,
                                    title = it.displayTitle,
                                    posterUrl = it.posterUrl,
                                    mediaType = "movie",
                                )
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        Icon(
                            if (inWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (inWatchlist) "Remove from watchlist" else "Add to watchlist",
                            tint = if (inWatchlist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                resolverMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                val csTitle = movie?.displayTitle.orEmpty()
                if (installedCsPlugins.isNotEmpty() && csTitle.isNotBlank()) {
                    val safePlugins = remember(installedCsPlugins) { installedCsPlugins.filter { !it.isAdultPlugin() } }
                    if (safePlugins.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Find in Source",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            safePlugins.forEach { plugin ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable { openCsSourcePicker(plugin) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        plugin.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text("Overview", style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(
                    movie?.overview ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(40.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(12.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.45f))
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
    }

    // CloudStream source picker bottom sheet
    val csSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pickerPlugin = csPickerPlugin
    if (pickerPlugin != null) {
        ModalBottomSheet(
            onDismissRequest = { csPickerPlugin = null },
            sheetState = csSheetState,
            containerColor = Color(0xFF1C1C1E),
            contentColor = Color.White,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            ) {
                // Header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pickerPlugin.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    if (csPickerLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                when {
                    csPickerLoading && csPickerSources.isEmpty() -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Searching ${pickerPlugin.name}…",
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    csPickerError != null -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 32.dp),
                        ) {
                            Column {
                                Text(
                                    csPickerError ?: "",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(16.dp))
                                TextButton(onClick = { csPickerPlugin = null }) {
                                    Text("Close", color = Color.White.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                    else -> {
                        // Two-column picker: Sources | Subtitles
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                        ) {
                            // Sources column
                            Column(Modifier.weight(1f)) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Sources",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 11.sp,
                                    )
                                    if (!csPickerLoading && csPickerSources.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${csPickerSources.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        )
                                    }
                                }
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(csPickerSources, key = { it.url }) { link ->
                                        val isSelected = link.url == csPickerSelSource?.url
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { csPickerSelSource = link }
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    else Color.Transparent,
                                                )
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            } else {
                                                Spacer(Modifier.width(24.dp))
                                            }
                                            Column(Modifier.weight(1f)) {
                                                val qLabel = csQualityLabel(link.quality)
                                                val sourceName = link.name.ifBlank { link.source }
                                                Text(
                                                    buildString {
                                                        append(sourceName)
                                                        if (qLabel != null) append(" · $qLabel")
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    ),
                                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            VerticalDivider(color = Color.White.copy(alpha = 0.1f))

                            // Subtitles column
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Subtitles",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                LazyColumn(Modifier.fillMaxSize()) {
                                    item(key = "no-sub") {
                                        val isSelected = csPickerSelSub == null
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { csPickerSelSub = null }
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    else Color.Transparent,
                                                )
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            } else {
                                                Spacer(Modifier.width(24.dp))
                                            }
                                            Text(
                                                "No Subtitles",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                ),
                                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                                            )
                                        }
                                    }
                                    items(csPickerSubs, key = { it.url }) { sub ->
                                        val isSelected = sub.url == csPickerSelSub?.url
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { csPickerSelSub = sub }
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    else Color.Transparent,
                                                )
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            } else {
                                                Spacer(Modifier.width(24.dp))
                                            }
                                            Text(
                                                sub.lang,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                ),
                                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                        // Apply / Cancel
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { csPickerPlugin = null }) {
                                Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val selLink = csPickerSelSource ?: return@Button
                                    val m = movie
                                    val displayTitle = m?.displayTitle ?: "Playback"
                                    val progressKey = WatchProgressKey(
                                        tmdbId = movieId,
                                        title = displayTitle,
                                        posterUrl = m?.posterUrl ?: m?.backdropUrl,
                                        mediaType = mediaType,
                                    )
                                    val ps = csPickerSources.toCsPlayerSources(pickerPlugin.name)
                                    val selPs = ps.find { it.url == selLink.url } ?: ps.first()
                                    val reordered = listOf(selPs) + ps.filter { it.url != selPs.url }
                                    csPickerPlugin = null
                                    onPlay(selPs.url, displayTitle, reordered, progressKey)
                                },
                                enabled = csPickerSelSource != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Apply", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayMovieCta(
    addonCount: Int,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(22.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Finding best stream…",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(
                Icons.Default.PlayArrow, null,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Play Movie",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (addonCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "· $addonCount source${if (addonCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun StremioStream.toPlayerSource(addon: InstalledStremioAddon): PlayerSource? {
    val playable = toPlayableUrl() ?: return null
    val isMagnet = playable.startsWith("magnet:")
    val label = title?.takeIf { it.isNotBlank() } ?: name ?: description ?: "Stream"
    val quality = qualityTag()
    return PlayerSource(
        id = addon.id + "::" + (infoHash ?: ytId ?: url ?: "").take(64) + "::" + label.hashCode(),
        url = playable,
        label = label,
        addonName = addon.name,
        qualityTag = quality,
        isMagnet = isMagnet,
    )
}

private fun StremioStream.toPlayableUrl(): String? = when {
    !url.isNullOrBlank() -> url
    !ytId.isNullOrBlank() -> "https://www.youtube.com/watch?v=$ytId"
    !infoHash.isNullOrBlank() -> {
        val baseTrackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://9.rarbg.com:2810/announce",
        )
        val trackers = (sources?.filter { it.startsWith("tracker:") }?.map { it.removePrefix("tracker:") }
            ?: emptyList()) + baseTrackers
        val name = title?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: "Stream"
        val trk = trackers.joinToString("&") { "tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        "magnet:?xt=urn:btih:$infoHash&dn=$name&$trk"
    }
    else -> null
}

private fun StremioStream.qualityTag(): String? {
    val haystack = listOfNotNull(name, title, description).joinToString(" ").lowercase()
    return when {
        "2160" in haystack || "4k" in haystack || "uhd" in haystack -> "4K"
        "1440" in haystack -> "1440p"
        "1080" in haystack -> "1080p"
        "720" in haystack -> "720p"
        "480" in haystack -> "480p"
        "hd" in haystack -> "HD"
        else -> null
    }
}

private fun PlayerSource.qualityScore(): Int {
    val q = when (qualityTag) {
        "4K" -> 4; "1440p" -> 3
        "1080p" -> 3
        "720p" -> 2
        "480p" -> 1
        else -> 0
    }
    return q * 10 + if (!isMagnet) 1 else 0
}

private fun NuvioStream.toPlayerSource(provider: InstalledNuvioProvider): PlayerSource {
    val label = title?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: "Stream"
    return PlayerSource(
        id = "nuvio::${provider.id}::${url.hashCode()}::${label.hashCode()}",
        url = url,
        label = label,
        addonName = provider.name,
        qualityTag = normaliseNuvioQuality(quality),
        isMagnet = url.startsWith("magnet:"),
        headers = headers ?: emptyMap(),
    )
}

private fun normaliseNuvioQuality(q: String?): String? {
    if (q.isNullOrBlank()) return null
    val s = q.trim()
    return when {
        s.equals("4K", ignoreCase = true) ||
            s.contains("2160", ignoreCase = true) ||
            s.contains("uhd", ignoreCase = true) -> "4K"
        s.contains("1440", ignoreCase = true) ||
            s.equals("2K", ignoreCase = true) -> "1440p"
        s.contains("1080", ignoreCase = true) ||
            s.equals("fhd", ignoreCase = true) ||
            s.equals("fullhd", ignoreCase = true) ||
            s.equals("full hd", ignoreCase = true) -> "1080p"
        s.contains("720", ignoreCase = true) ||
            s.equals("hd", ignoreCase = true) -> "720p"
        s.contains("480", ignoreCase = true) ||
            s.equals("sd", ignoreCase = true) -> "480p"
        s.contains("360", ignoreCase = true) -> "360p"
        else -> s
    }
}

private suspend fun resolveCsPluginForMovie(
    context: android.content.Context,
    plugin: InstalledPlugin,
    title: String,
    year: Int?,
): List<PlayerSource> {
    return try {
        val results: List<SearchResponse> = runCatching {
            PluginRuntime.search(context, plugin.filePath, title)
        }.getOrDefault(emptyList())
        val best = pickBestMatch(results, title, year) ?: return emptyList()
        val detail = runCatching { PluginRuntime.loadDetail(context, plugin.filePath, best.url) }.getOrNull()
        val movieDetail = detail as? MovieLoadResponse ?: return emptyList()
        val (links, _) = runCatching {
            PluginRuntime.loadLinks(context, plugin.filePath, movieDetail.dataUrl, isCasting = false)
        }.getOrElse { return emptyList() }
        links.toCsPlayerSources(plugin.name)
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun pickBestMatch(results: List<SearchResponse>, title: String, year: Int?): SearchResponse? {
    if (results.isEmpty()) return null
    val cleanTitle = title.normalizedTitle()
    return results
        .map { sr ->
            var score = 0
            if (sr.name.normalizedTitle() == cleanTitle) score += 50
            else if (sr.name.normalizedTitle().contains(cleanTitle)) score += 10
            if (year != null) {
                val srYear = (sr as? com.lagradost.cloudstream3.MovieSearchResponse)?.year
                    ?: (sr as? com.lagradost.cloudstream3.TvSeriesSearchResponse)?.year
                if (srYear == year) score += 30
            }
            sr to score
        }
        .sortedByDescending { it.second }
        .firstOrNull()
        ?.first
}

private fun String.normalizedTitle(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private fun List<ExtractorLink>.toCsPlayerSources(pluginDisplayName: String): List<PlayerSource> =
    this.mapIndexedNotNull { idx, link ->
        if (link.url.isBlank()) return@mapIndexedNotNull null
        PlayerSource(
            id = "cs::$pluginDisplayName::${link.url.hashCode()}::$idx",
            url = link.url,
            label = link.name.ifBlank { link.source.ifBlank { "Stream" } },
            addonName = link.source.ifBlank { pluginDisplayName },
            qualityTag = csQualityLabel(link.quality),
            isMagnet = link.url.startsWith("magnet:"),
            headers = buildMap {
                if (link.referer.isNotBlank()) put("Referer", link.referer)
                putAll(link.headers)
            },
        )
    }

private fun csQualityLabel(q: Int): String? = when {
    q >= 2160 -> "4K"
    q >= 1440 -> "1440p"
    q >= 1080 -> "1080p"
    q >= 720 -> "720p"
    q >= 480 -> "480p"
    q >= 360 -> "360p"
    q > 0 -> "${q}p"
    else -> null
}

private fun com.streamcloud.app.data.api.TmdbMovie.year(): Int? =
    releaseDate?.takeIf { it.isNotBlank() }?.substringBefore('-')?.toIntOrNull()
