package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.WatchlistEntity
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
import coil.compose.AsyncImage
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.player.PlayerSource
import com.streamcloud.app.player.WatchProgressKey
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF120D07)
private val AccentColor = Color(0xFFE8735A)
private val StarColor = Color(0xFFE8B25A)
private val TextPrimary = Color(0xFFF5F0EA)
private val TextSecondary = Color(0xFFAA9B8A)
private val SurfaceColor = Color(0xFF1E1710)
private val DarkOverlay = Color(0xFF1A1108).copy(alpha = 0.7f)

private sealed class CsDetailState {
    data object Loading : CsDetailState()
    data class Error(val message: String) : CsDetailState()
    data class Ready(val response: LoadResponse) : CsDetailState()
}

// Pre-fetched source count — null = not fetched yet, -1 = failed, >= 0 = count
private sealed class SourcesState {
    data object Idle : SourcesState()
    data object Fetching : SourcesState()
    data class Done(val count: Int) : SourcesState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudStreamDetailScreen(
    pluginInternalName: String,
    url: String,
    initialTitle: String,
    initialPoster: String?,
    onBack: () -> Unit,
    onPlay: (initialUrl: String, title: String, sources: List<PlayerSource>, progressKey: WatchProgressKey) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { PluginRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val watchlistDao = remember { LibraryDb.get(context.applicationContext).watchlist() }
    val syntheticId = remember(pluginInternalName, url) {
        val h = (pluginInternalName + "|" + url).hashCode().toLong()
        if (h < 0L) h else -(h + 1L)
    }
    val isWatchlisted by watchlistDao.isWatchlisted(syntheticId).collectAsState(initial = false)

    var state by remember { mutableStateOf<CsDetailState>(CsDetailState.Loading) }
    var sourcesState by remember { mutableStateOf<SourcesState>(SourcesState.Idle) }
    var pluginFilePath by remember { mutableStateOf<String?>(null) }
    var pluginDisplayName by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var cachedSources by remember { mutableStateOf<List<ExtractorLink>>(emptyList()) }

    LaunchedEffect(pluginInternalName, url) {
        val installed = repo.installed.first().firstOrNull { it.internalName == pluginInternalName }
        if (installed == null) {
            state = CsDetailState.Error("Plugin '$pluginInternalName' is no longer installed.")
            return@LaunchedEffect
        }
        pluginFilePath = installed.filePath
        pluginDisplayName = installed.name
        try {
            val lr = PluginRuntime.loadDetail(context, installed.filePath, url)
            state = if (lr == null) {
                val msg = PluginRuntime.lastErrorFor(installed.filePath)
                    ?: "Plugin returned no detail page for this item."
                CsDetailState.Error(msg)
            } else {
                // Background pre-fetch of sources for the source count badge
                val dataUrl = (lr as? MovieLoadResponse)?.dataUrl
                if (dataUrl != null) {
                    sourcesState = SourcesState.Fetching
                    scope.launch {
                        try {
                            val (links, _) = PluginRuntime.loadLinks(context, installed.filePath, dataUrl)
                            cachedSources = links
                            sourcesState = SourcesState.Done(links.size)
                        } catch (_: Throwable) {
                            sourcesState = SourcesState.Done(0)
                        }
                    }
                }
                CsDetailState.Ready(lr)
            }
        } catch (e: Throwable) {
            state = CsDetailState.Error("Plugin failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    fun resolveAndPlay(data: String, episodeTitle: String?) {
        val path = pluginFilePath ?: return
        scope.launch {
            resolving = true
            resolveError = null
            try {
                val links = if (data == (state as? CsDetailState.Ready)?.let {
                        (it.response as? MovieLoadResponse)?.dataUrl } && cachedSources.isNotEmpty()
                ) {
                    cachedSources
                } else {
                    val (l, _) = PluginRuntime.loadLinks(context, path, data)
                    l
                }
                if (links.isEmpty()) {
                    val internalErr = PluginRuntime.lastErrorFor(path)
                    resolveError = buildString {
                        append("No streams found")
                        if (!pluginDisplayName.isNullOrBlank()) append(" — $pluginDisplayName")
                        if (!internalErr.isNullOrBlank()) append("\n\n$internalErr")
                    }
                    return@launch
                }
                val sources = links.toPlayerSources(pluginDisplayName ?: pluginInternalName)
                val sorted = sources.sortedByDescending { it.qualityScoreCs() }
                val displayTitle = listOfNotNull(initialTitle, episodeTitle).joinToString(" · ")
                val progressKey = WatchProgressKey(
                    tmdbId = -((pluginInternalName + "|" + url + "|" + (episodeTitle ?: "")).hashCode().toLong()),
                    title = displayTitle,
                    posterUrl = (state as? CsDetailState.Ready)?.response?.posterUrl ?: initialPoster,
                    mediaType = "movie",
                )
                onPlay(sorted.first().url, displayTitle, sorted, progressKey)
            } catch (e: Throwable) {
                resolveError = "Failed to load streams: ${e::class.simpleName}: ${e.message}"
            } finally {
                resolving = false
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgColor)
    ) {
        when (val s = state) {
            is CsDetailState.Loading -> CsLoadingScreen(initialTitle)
            is CsDetailState.Error  -> CsErrorScreen(s.message, onBack)
            is CsDetailState.Ready  -> CsReadyContent(
                lr              = s.response,
                initialTitle    = initialTitle,
                initialPoster   = initialPoster,
                pluginName      = pluginDisplayName.orEmpty(),
                sourcesState    = sourcesState,
                resolving       = resolving,
                resolveError    = resolveError,
                isWatchlisted   = isWatchlisted,
                onPlayMovie     = { resolveAndPlay((s.response as MovieLoadResponse).dataUrl, null) },
                onPlayEpisode   = { ep -> resolveAndPlay(ep.data, ep.displayLabel()) },
                onToggleWatchlist = {
                    scope.launch {
                        if (isWatchlisted) {
                            watchlistDao.remove(syntheticId)
                        } else {
                            watchlistDao.add(
                                WatchlistEntity(
                                    tmdbId    = syntheticId,
                                    title     = s.response.name.ifBlank { initialTitle },
                                    posterUrl = s.response.posterUrl ?: initialPoster,
                                    mediaType = "cloudstream",
                                    csPlugin  = pluginInternalName,
                                    csUrl     = url,
                                )
                            )
                        }
                    }
                },
            )
        }

        // Back button — always visible, overlaid on content
        Box(
            Modifier
                .padding(16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White,
                modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun CsLoadingScreen(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AccentColor, strokeWidth = 2.5.dp,
                modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(16.dp))
            Text("Loading $title…", color = TextSecondary,
                style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CsErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't open item",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary)
        Spacer(Modifier.height(12.dp))
        Text(message, color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
            shape = RoundedCornerShape(50),
        ) { Text("Go back", color = Color.White) }
    }
}

@Composable
private fun CsReadyContent(
    lr: LoadResponse,
    initialTitle: String,
    initialPoster: String?,
    pluginName: String,
    sourcesState: SourcesState,
    resolving: Boolean,
    resolveError: String?,
    isWatchlisted: Boolean,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onToggleWatchlist: () -> Unit,
) {
    val isSeries = lr is TvSeriesLoadResponse
    val episodes = (lr as? TvSeriesLoadResponse)?.episodes.orEmpty()
    val displayTitle = lr.name.ifBlank { initialTitle }

    // Use Score.toDouble(10) for a 0-10 scale display value
    val ratingDisplay = lr.score?.let { s ->
        val v = s.toDouble(10)
        if (v > 0) "%.1f".format(v) else null
    }

    LazyColumn(Modifier.fillMaxSize()) {

        // ── Hero backdrop ─────────────────────────────────────────────────────
        item {
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                AsyncImage(
                    model = lr.backgroundPosterUrl ?: lr.posterUrl ?: initialPoster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(SurfaceColor),
                )
                // Bottom gradient fade into BgColor
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f    to Color.Black.copy(alpha = 0.25f),
                            0.55f to Color.Transparent,
                            1f    to BgColor,
                        )
                    )
                )
            }
        }

        // ── Metadata panel ────────────────────────────────────────────────────
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(BgColor)
                    .padding(horizontal = 20.dp)
                    .offset(y = (-20).dp),
            ) {
                // Title
                Text(
                    displayTitle,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 38.sp,
                    ),
                    color = TextPrimary,
                )
                Spacer(Modifier.height(8.dp))

                // Star · rating · year · tags
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ratingDisplay != null) {
                        Icon(Icons.Filled.Star, null, tint = StarColor,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(ratingDisplay, color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.width(12.dp))
                    }
                    lr.year?.let { y ->
                        Text(y.toString(), color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(12.dp))
                    }
                    lr.duration?.let { d ->
                        Text("${d}m", color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(12.dp))
                    }
                    if (pluginName.isNotBlank()) {
                        Text(pluginName, color = TextSecondary.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Action row — Play + Bookmark ───────────────────────────
                if (!isSeries) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Play button
                        val playLabel = when {
                            resolving -> "Finding streams…"
                            sourcesState is SourcesState.Done && (sourcesState as SourcesState.Done).count > 0 ->
                                "Play Movie · ${(sourcesState as SourcesState.Done).count} sources"
                            sourcesState is SourcesState.Fetching -> "Play Movie"
                            else -> "Play Movie"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(50))
                                .background(AccentColor)
                                .clickable(enabled = !resolving, onClick = onPlayMovie)
                                .padding(horizontal = 20.dp),
                        ) {
                            if (resolving) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp), strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                                Spacer(Modifier.width(10.dp))
                            } else {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.White,
                                    modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                playLabel,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Bookmark button
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(SurfaceColor)
                                .clickable(onClick = onToggleWatchlist),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (isWatchlisted) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (isWatchlisted) "Remove from watchlist" else "Add to watchlist",
                                tint = if (isWatchlisted) AccentColor else TextSecondary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // Resolve error
                resolveError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }

                // ── Tags ───────────────────────────────────────────────────
                val tags = lr.tags
                if (!tags.isNullOrEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.take(4).forEach { tag ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceColor)
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(tag, color = TextSecondary,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // ── Overview ───────────────────────────────────────────────
                lr.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                    Spacer(Modifier.height(24.dp))
                    Text("Overview",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(plot, color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
                }

                // ── Episodes heading ───────────────────────────────────────
                if (isSeries) {
                    Spacer(Modifier.height(24.dp))
                    Text("Episodes",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))
            }
        }

        // ── Episode list ──────────────────────────────────────────────────────
        if (isSeries) {
            items(episodes,
                key = { ep -> ep.season.toString() + ":" + ep.episode + ":" + ep.data.hashCode() }
            ) { ep ->
                EpisodeRow(ep = ep, loading = resolving, onClick = { onPlayEpisode(ep) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, loading: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceColor)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(12.dp),
    ) {
        if (!ep.posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = ep.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 112.dp, height = 68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgColor),
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Box(
                Modifier
                    .size(width = 52.dp, height = 52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = TextSecondary,
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                ep.displayLabel(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            ep.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Default.PlayArrow, null, tint = AccentColor,
            modifier = Modifier.size(20.dp))
    }
}

private fun Episode.displayLabel(): String {
    val parts = mutableListOf<String>()
    if (season != null && episode != null) parts += "S${season}E${episode}"
    name?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (parts.isEmpty()) parts += "Episode"
    return parts.joinToString(" · ")
}

private fun List<ExtractorLink>.toPlayerSources(pluginDisplayName: String): List<PlayerSource> =
    this.mapIndexedNotNull { idx, link ->
        if (link.url.isBlank()) return@mapIndexedNotNull null
        PlayerSource(
            id = "cs::$pluginDisplayName::${link.url.hashCode()}::$idx",
            url = link.url,
            label = link.name.ifBlank { link.source.ifBlank { "Stream" } },
            addonName = link.source.ifBlank { pluginDisplayName },
            qualityTag = qualityLabel(link.quality),
            isMagnet = link.url.startsWith("magnet:"),
            headers = buildMap {
                if (link.referer.isNotBlank()) put("Referer", link.referer)
                putAll(link.headers)
            },
        )
    }

private fun qualityLabel(q: Int): String? = when {
    q >= 2160 -> "4K"
    q >= 1440 -> "1440p"
    q >= 1080 -> "1080p"
    q >= 720  -> "720p"
    q >= 480  -> "480p"
    q >= 360  -> "360p"
    q > 0     -> "${q}p"
    else      -> null
}

private fun PlayerSource.qualityScoreCs(): Int {
    val q = when (qualityTag) {
        "4K"    -> 4
        "1440p" -> 3
        "1080p" -> 3
        "720p"  -> 2
        "480p"  -> 1
        else    -> 0
    }
    return q * 10 + if (!isMagnet) 1 else 0
}
