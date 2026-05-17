package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import coil.compose.AsyncImage
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.player.PlayerSource
import com.streamcloud.app.player.WatchProgressKey
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private sealed class CsDetailState {
    data object Loading : CsDetailState()
    data class Error(val message: String) : CsDetailState()
    data class Ready(val response: LoadResponse) : CsDetailState()
}

/**
 * Detail page for one item picked from a CloudStream plugin's home / search feed.
 *
 * Mirrors upstream `recloudstream/cloudstream` flow exactly:
 *   1. `api.load(url)` → [LoadResponse] (Movie or TvSeries)
 *   2. Movie: tap "Play" → `api.loadLinks(dataUrl, false, subCb, linkCb)`
 *      TvSeries: tap an Episode → `api.loadLinks(episode.data, ...)`
 *   3. Convert [ExtractorLink] list → [PlayerSource] list
 *   4. Hand off to the existing native player + source picker
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudStreamDetailScreen(
    pluginInternalName: String,
    url: String,
    initialTitle: String,
    initialPoster: String?,
    onBack: () -> Unit,
    /** Same shape as [MovieDetailScreen.onPlay] — host wires it through to the player. */
    onPlay: (initialUrl: String, title: String, sources: List<PlayerSource>, progressKey: WatchProgressKey) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { PluginRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<CsDetailState>(CsDetailState.Loading) }
    var pluginFilePath by remember { mutableStateOf<String?>(null) }
    var pluginDisplayName by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }

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
                val (links, _) = PluginRuntime.loadLinks(context, path, data, isCasting = false)
                if (links.isEmpty()) {
                    resolveError = "No streams returned by ${pluginDisplayName ?: "plugin"}."
                    return@launch
                }
                val sources = links.toPlayerSources(pluginDisplayName ?: pluginInternalName)
                val sorted = sources.sortedByDescending { it.qualityScoreCs() }
                val displayTitle = listOfNotNull(initialTitle, episodeTitle).joinToString(" · ")
                // Use a deterministic-ish negative id so it doesn't collide with TMDB ids in the
                // resume table (TMDB ids are positive).
                val progressKey = WatchProgressKey(
                    tmdbId = -((pluginInternalName + "|" + url + "|" + (episodeTitle ?: "")).hashCode().toLong()),
                    title = displayTitle,
                    posterUrl = (state as? CsDetailState.Ready)?.response?.posterUrl ?: initialPoster,
                    mediaType = "movie",
                )
                onPlay(sorted.first().url, displayTitle, sorted, progressKey)
            } catch (e: Throwable) {
                resolveError = "Resolve failed: ${e::class.simpleName}: ${e.message}"
            } finally {
                resolving = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val s = state) {
            is CsDetailState.Loading -> CenteredLoader("Loading ${initialTitle}…")
            is CsDetailState.Error -> ErrorBanner(s.message, onBack)
            is CsDetailState.Ready -> CsDetailContent(
                lr = s.response,
                initialTitle = initialTitle,
                initialPoster = initialPoster,
                pluginName = pluginDisplayName.orEmpty(),
                resolving = resolving,
                resolveError = resolveError,
                onPlayMovie = { resolveAndPlay((s.response as MovieLoadResponse).dataUrl, null) },
                onPlayEpisode = { ep -> resolveAndPlay(ep.data, ep.displayLabel()) },
            )
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
}

@Composable
private fun CenteredLoader(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't open item", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Go back") }
    }
}

@Composable
private fun CsDetailContent(
    lr: LoadResponse,
    initialTitle: String,
    initialPoster: String?,
    pluginName: String,
    resolving: Boolean,
    resolveError: String?,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
) {
    val isSeries = lr is TvSeriesLoadResponse
    val episodes = (lr as? TvSeriesLoadResponse)?.episodes.orEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                AsyncImage(
                    model = lr.backgroundPosterUrl ?: lr.posterUrl ?: initialPoster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.45f),
                                Color.Transparent,
                                MaterialTheme.colorScheme.background,
                            )
                        )
                    )
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp).offset(y = (-40).dp)) {
                Text(
                    lr.name.ifBlank { initialTitle },
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "via $pluginName" + (lr.year?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))

                if (!isSeries) {
                    PlayCsCta(
                        label = "Play",
                        loading = resolving,
                        onClick = onPlayMovie,
                    )
                }

                resolveError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(20.dp))
                lr.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                    Text("Overview", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        plot,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                }
                if (isSeries) {
                    Text(
                        "Episodes",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        if (isSeries) {
            items(episodes, key = { it.season.toString() + ":" + it.episode + ":" + it.data.hashCode() }) { ep ->
                EpisodeRow(ep = ep, loading = resolving, onClick = { onPlayEpisode(ep) })
            }
        }
    }
}

@Composable
private fun PlayCsCta(label: String, loading: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = !loading, onClick = onClick),
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
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, loading: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(12.dp),
    ) {
        if (!ep.posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = ep.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 120.dp, height = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Box(
                Modifier
                    .size(width = 56.dp, height = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                ep.displayLabel(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ep.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Episode.displayLabel(): String {
    val parts = mutableListOf<String>()
    if (season != null && episode != null) parts += "S${season}E${episode}"
    name?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (parts.isEmpty()) parts += "Episode"
    return parts.joinToString(" · ")
}

// ────────────────── ExtractorLink → PlayerSource ──────────────────

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
    q >= 720 -> "720p"
    q >= 480 -> "480p"
    q >= 360 -> "360p"
    q > 0 -> "${q}p"
    else -> null
}

private fun PlayerSource.qualityScoreCs(): Int {
    val q = when (qualityTag) {
        "4K" -> 4
        "1440p" -> 3
        "1080p" -> 3
        "720p" -> 2
        "480p" -> 1
        else -> 0
    }
    return q * 10 + if (!isMagnet) 1 else 0
}
