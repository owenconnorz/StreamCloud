package com.aioweb.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aioweb.app.data.ServiceLocator
import com.aioweb.app.data.api.TmdbMovie
import com.aioweb.app.data.api.TmdbVideo
import com.aioweb.app.data.nuvio.InstalledNuvioProvider
import com.aioweb.app.data.nuvio.NuvioStream
import com.aioweb.app.data.plugins.InstalledPlugin
import com.aioweb.app.data.plugins.PluginRuntime
import com.aioweb.app.data.stremio.InstalledStremioAddon
import com.aioweb.app.data.stremio.StremioStream
import com.aioweb.app.player.PlayerSource
import com.aioweb.app.player.WatchProgressKey
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * Detail page for a TMDB movie.
 *
 * **Single "Play Movie" button** — fans out to every installed Stremio addon in
 * parallel, takes the best-ranked stream as the initial source, and hands the
 * **full sorted list** to the NativePlayer so the user can swap source mid-
 * playback via the in-player "Sources" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    movieId: Long,
    onBack: () -> Unit,
    /** Fired with (initial url, title, full source list, progressKey) when streams resolve. */
    onPlay: (initialUrl: String, title: String, sources: List<PlayerSource>, progressKey: WatchProgressKey) -> Unit,
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

    LaunchedEffect(movieId) {
        scope.launch {
            try {
                movie = sl.tmdb.details(movieId, sl.tmdbApiKey)
                videos = sl.tmdb.videos(movieId, sl.tmdbApiKey).results
                imdbId = sl.tmdb.externalIds(movieId, sl.tmdbApiKey).imdbId
            } catch (e: Exception) {
                error = "Failed to load: ${e.message}"
            }
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
                // Fan out to ALL THREE systems in parallel:
                //   • Stremio addons keyed by IMDB tt
                //   • Nuvio JS providers keyed by TMDB id
                //   • CloudStream `.cs3` plugins — title search → load → loadLinks
                val stremioJob = async {
                    installedAddons.map { addon ->
                        async {
                            runCatching { sl.stremio.fetchStreams(addon, "movie", tt) }
                                .map { streams -> streams.mapNotNull { it.toPlayerSource(addon) } }
                                .getOrDefault(emptyList())
                        }
                    }.awaitAll().flatten()
                }
                val nuvioJob = async {
                    runCatching {
                        sl.nuvio.resolveAll(movieId.toString(), "movie")
                            .map { (provider, stream) -> stream.toPlayerSource(provider) }
                    }.getOrDefault(emptyList())
                }
                val csJob = async {
                    val title = movie?.displayTitle.orEmpty()
                    if (title.isBlank()) emptyList<PlayerSource>()
                    else installedCsPlugins.map { plugin ->
                        async { resolveCsPluginForMovie(context, plugin, title, movie?.year()) }
                    }.awaitAll().flatten()
                }
                val all = stremioJob.await() + nuvioJob.await() + csJob.await()
                if (all.isEmpty()) {
                    val total = installedAddons.size + installedNuvio.size + installedCsPlugins.size
                    resolverMessage = "No streams found across $total source(s)."
                    return@launch
                }
                val sorted = all.sortedByDescending { it.qualityScore() }
                val best = sorted.first()
                val m = movie
                val displayTitle = m?.displayTitle ?: "Playback"
                val progressKey = WatchProgressKey(
                    tmdbId = movieId,
                    title = displayTitle,
                    posterUrl = m?.posterUrl ?: m?.backdropUrl,
                    mediaType = "movie",
                )
                onPlay(best.url, displayTitle, sorted, progressKey)
            } finally {
                resolving = false
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

                // ── ONE button. ──
                PlayMovieCta(
                    addonCount = installedAddons.size + installedNuvio.size + installedCsPlugins.size,
                    enabled = imdbId != null && (installedAddons.isNotEmpty() || installedNuvio.isNotEmpty() || installedCsPlugins.isNotEmpty()) && !resolving,
                    loading = resolving,
                    onClick = { playMovie() },
                )
                resolverMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
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

// ──────────────────────────── stream → PlayerSource conversion ────────────────────────────

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

// ─────────────────────── Nuvio JS provider → PlayerSource ───────────────────────
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

/**
 * Nuvio providers return free-form quality strings ("FHD", "4k", "HD", "720", …).
 * Normalise them to the same canonical tags used by the Stremio / CloudStream paths
 * so [qualityScore] sorts them correctly alongside other sources.
 */
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

// ─────────────────────── CloudStream `.cs3` plugin → PlayerSource ───────────────────────
//
// Same pipeline as the dedicated CloudStream detail screen:
//   1. plugin.search(title) → best year-matched SearchResponse
//   2. plugin.load(searchResult.url) → LoadResponse (must be MovieLoadResponse here)
//   3. plugin.loadLinks(dataUrl, false, subCb, linkCb) → ExtractorLink list
//   4. Map ExtractorLink → PlayerSource so it shows up in the unified player picker
//
// We constrain to MovieLoadResponse — TvSeries flows go through CloudStreamDetailScreen.
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

private fun com.aioweb.app.data.api.TmdbMovie.year(): Int? =
    releaseDate?.takeIf { it.isNotBlank() }?.substringBefore('-')?.toIntOrNull()
