package com.streamcloud.app.ui.screens

import android.content.Context
import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.nuvio.InstalledNuvioProvider
import com.streamcloud.app.data.nuvio.NuvioRuntime
import com.streamcloud.app.data.nuvio.NuvioStream
import com.streamcloud.app.data.nuvio.toSummary
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioStream
import com.streamcloud.app.player.PlayerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.unit.sp

private data class PickerGroupState(
    val addonName: String,
    val streams: List<PlayerSource> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamPickerOverlay(
    movie: TmdbMovie?,
    mediaType: String,
    tmdbId: Long,
    imdbId: String?,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    installedAddons: List<InstalledStremioAddon>,
    installedNuvio: List<InstalledNuvioProvider>,
    installedCsPlugins: List<InstalledPlugin>,
    onBack: () -> Unit,
    onPlay: (url: String, sources: List<PlayerSource>) -> Unit,
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }

    val eligibleCs = remember(installedCsPlugins, mediaType) {
        if (mediaType == "tv") {
            installedCsPlugins.filter { !it.isAdultPlugin() }
        } else {
            installedCsPlugins.filter { plugin ->
                !plugin.isAdultPlugin() && run {
                    val types = plugin.tvTypes
                    types == null || types.any { t ->
                        val lt = t.lowercase()
                        lt.contains("movie") || lt == "others" || lt == "live"
                    }
                }
            }
        }
    }

    val groupOrder: List<Pair<String, String>> = remember(installedAddons, installedNuvio, eligibleCs) {
        buildList {
            installedAddons.forEach { add("stremio:${it.id}" to it.name) }
            installedNuvio.forEach { add("nuvio:${it.id}" to it.name) }
            eligibleCs.forEach { add("cs:${it.internalName}" to it.name) }
        }
    }

    var groups by remember {
        mutableStateOf(groupOrder.associate { (key, name) -> key to PickerGroupState(name) })
    }
    var selectedTab by remember { mutableStateOf(0) }
    var revision by remember { mutableStateOf(0) }
    // Tracks when the current scan started so we can display elapsed seconds.
    var scanStartMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scanElapsedSecs by remember { mutableIntStateOf(0) }

    fun updateGroup(key: String, streams: List<PlayerSource>, error: String? = null) {
        groups = groups.toMutableMap().apply {
            val existing = get(key) ?: return
            put(key, existing.copy(streams = streams, isLoading = false, error = error))
        }
    }

    // Tick every second so the elapsed-time counter in the header stays current.
    LaunchedEffect(scanStartMs) {
        while (true) {
            delay(1_000L)
            scanElapsedSecs = ((System.currentTimeMillis() - scanStartMs) / 1_000L).toInt()
        }
    }

    // Restart the scan whenever the movie, season, or episode changes —
    // not just on explicit refresh.  Previously keying only on `revision` meant
    // a picker opened for movie A would keep showing A's results when the composable
    // was reused or the movie changed underneath it.
    LaunchedEffect(revision, tmdbId, imdbId, mediaType, season ?: -1, episode ?: -1) {
        scanStartMs = System.currentTimeMillis()
        scanElapsedSecs = 0
        groups = groupOrder.associate { (key, name) -> key to PickerGroupState(name) }
        selectedTab = 0

        val stremioType = if (mediaType == "tv") "series" else "movie"
        val tt = imdbId ?: ""
        val csTitle = movie?.displayTitle.orEmpty()
        val csYear = movie?.pickerYear()

        coroutineScope {
            installedAddons.forEach { addon ->
                launch {
                    val sources = withContext(Dispatchers.IO) {
                        runCatching {
                            val ids = buildList {
                                if (tt.isNotBlank()) add(tt)
                                if (!tt.startsWith("tmdb:")) add("tmdb:$tmdbId")
                            }
                            val seen = mutableSetOf<String>()
                            ids.flatMap { id ->
                                withTimeoutOrNull(20_000L) {
                                    sl.stremio.fetchStreams(addon, stremioType, id)
                                        .mapNotNull { it.pickerToPlayerSource(addon) }
                                } ?: emptyList()
                            }.filter { seen.add(it.url) }
                        }.getOrElse { e ->
                            Log.d("StreamPicker", "Stremio ${addon.name}: ${e.message}")
                            emptyList()
                        }
                    }
                    updateGroup("stremio:${addon.id}", streams = sources)
                }
            }

            // Each Nuvio provider gets its own coroutine so the UI updates
            // as soon as each provider finishes — fast providers are not held
            // back by slow ones.
            installedNuvio.forEach { provider ->
                launch {
                    val streams = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(90_000L) {
                            runCatching {
                                sl.nuvio.resolveSingle(
                                    provider = provider,
                                    tmdbId = tmdbId.toString(),
                                    mediaType = mediaType,
                                    season = season,
                                    episode = episode,
                                    imdbId = imdbId,
                                )
                            }.getOrElse { e ->
                                Log.d("StreamPicker", "Nuvio ${provider.name}: ${e.message}")
                                emptyList()
                            }
                        } ?: run {
                            Log.d("StreamPicker", "Nuvio ${provider.name}: timed out after 90s")
                            emptyList()
                        }
                    }
                    val sources = streams.map { stream -> stream.pickerToPlayerSource(provider) }
                    val diagnostics = NuvioRuntime.lastDiagnostics(provider.id)
                    val fetchCount  = diagnostics?.requestCount ?: NuvioRuntime.lastFetchCount(provider.id)
                    val lastErr     = NuvioRuntime.lastError(provider.id)
                    val lastLog     = NuvioRuntime.lastLog(provider.id)
                    val err = if (sources.isEmpty()) {
                        val base = when {
                            lastErr != null && !lastErr.startsWith("No streams found") &&
                                !lastErr.startsWith("No requests made") -> lastErr
                            lastLog != null -> lastLog
                            diagnostics != null -> diagnostics.toSummary()
                            lastErr != null -> lastErr
                            else            -> "No streams found"
                        }
                        "${base} (${fetchCount} req)"
                    } else null
                    updateGroup("nuvio:${provider.id}", streams = sources, error = err)
                }
            }

            eligibleCs.forEach { plugin ->
                launch {
                    // Clear any stale error from a previous scan for this plugin before starting.
                    PluginRuntime.clearLastError(plugin.filePath)
                    val sources = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(95_000L) {
                            runCatching {
                                resolveCsForPicker(context, plugin, csTitle, csYear)
                            }.getOrElse { e ->
                                Log.d("StreamPicker", "CS ${plugin.name}: ${e.message}")
                                emptyList()
                            }
                        } ?: run {
                            Log.d("StreamPicker", "CS ${plugin.name}: timed out after 95s")
                            emptyList()
                        }
                    }
                    val lastErr = PluginRuntime.lastErrorFor(plugin.filePath)
                    val err = if (sources.isEmpty()) {
                        lastErr ?: "No streams found"
                    } else null
                    updateGroup("cs:${plugin.internalName}", streams = sources, error = err)
                }
            }
        }
    }

    val isAnyLoading = groups.values.any { it.isLoading }

    val allSources = remember(groups) {
        groupOrder.mapNotNull { (key, _) -> groups[key]?.streams }.flatten()
    }

    val addonTabs = remember(groups, eligibleCs, installedNuvio) {
        val csKeys = eligibleCs.map { "cs:${it.internalName}" }.toSet()
        val nuvioKeys = installedNuvio.map { "nuvio:${it.id}" }.toSet()
        groupOrder.mapNotNull { (key, name) ->
            val g = groups[key]
            // CS plugin tabs always stay visible (even with 0 streams) so the user can
            // see the error. Other provider types only appear while loading or with streams.
            if (g != null && (g.isLoading || g.streams.isNotEmpty() || key in csKeys || (key in nuvioKeys && g.error != null))) name else null
        }.distinct()
    }
    val tabs = remember(addonTabs) { listOf("All") + addonTabs }
    val safeTab = selectedTab.coerceAtMost(tabs.lastIndex)

    val visibleGroups = remember(groups, safeTab, tabs) {
        if (safeTab == 0) {
            groupOrder.mapNotNull { (key, name) ->
                val g = groups[key] ?: return@mapNotNull null
                Triple(key, name, g)
            }
        } else {
            val selectedAddon = tabs.getOrNull(safeTab)
            groupOrder.mapNotNull { (key, name) ->
                if (name != selectedAddon) return@mapNotNull null
                val g = groups[key] ?: return@mapNotNull null
                Triple(key, name, g)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            ) {
                val backdropUrl = movie?.backdropUrl ?: movie?.posterUrl
                if (backdropUrl != null) {
                    AsyncImage(
                        model = backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Black.copy(alpha = 0.90f),
                                )
                            )
                        )
                )

                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }

                IconButton(
                    onClick = { revision++ },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(4.dp),
                ) {
                    if (isAnyLoading) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                            CircularProgressIndicator(
                                Modifier.size(28.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            if (scanElapsedSecs > 0) {
                                Text(
                                    "${scanElapsedSecs}s",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                                    color = Color.White,
                                )
                            }
                        }
                    } else {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Color.White)
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        movie?.displayTitle ?: "Select a stream",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (season != null && episode != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            buildString {
                                append("Season $season · Episode $episode")
                                episodeTitle?.takeIf { it.isNotBlank() }
                                    ?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.78f),
                        )
                    }
                }
            }

            // Provider filter chips (All + one per provider) – pill-style like Nuvio
            if (tabs.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(tabs) { i, tab ->
                        val isSelected = i == safeTab
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.White.copy(alpha = 0.14f)
                                )
                                .clickable { selectedTab = i }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                tab,
                                color = Color.White,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // When no streams have arrived yet, show Nuvio-style centred loading state
            if (isAnyLoading && allSources.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(42.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Finding streams…",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                visibleGroups.forEach { (key, addonName, groupState) ->
                    if (safeTab == 0) {
                        item(key = "hdr:$key") {
                            PickerSectionHeader(
                                name = addonName,
                                isLoading = groupState.isLoading,
                            )
                        }
                    } else if (groupState.isLoading) {
                        item(key = "hdr:$key") {
                            PickerSectionHeader(name = addonName, isLoading = true)
                        }
                    }

                    if (groupState.isLoading) {
                        item(key = "prog:$key") {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    } else if (groupState.streams.isEmpty()) {
                        item(key = "empty:$key") {
                            val msg = groupState.error
                                ?.takeIf { it.isNotBlank() }
                                ?.let { "No streams — $it" }
                                ?: "No streams found"
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                            )
                        }
                    } else {
                        val sections = groupState.streams.pickerInnerSections(addonName)
                        sections.forEach { (innerName, sectionStreams) ->
                            if (sections.size > 1 || innerName != addonName) {
                                item(key = "inner:$key:$innerName") {
                                    Text(
                                        innerName,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            start = 4.dp,
                                            top = 8.dp,
                                            bottom = 2.dp,
                                        ),
                                    )
                                }
                            }
                            items(sectionStreams, key = { "s:${it.id}" }) { src ->
                                PickerStreamCard(
                                    source = src,
                                    onClick = { onPlay(src.url, allSources) },
                                    onDownload = if (!src.isMagnet) ({
                                        VideoDownloadManager.enqueue(
                                            context,
                                            StreamDownloadRequest(
                                                url = src.url,
                                                title = src.label.lines().firstOrNull() ?: src.addonName,
                                                headers = src.headers,
                                            ),
                                        )
                                        Toast.makeText(context, "Download started", android.widget.Toast.LENGTH_SHORT).show()
                                    }) else null,
                                )
                            }
                        }
                    }
                }

                if (!isAnyLoading && allSources.isEmpty()) {
                    item(key = "no_results") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "No streams found",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tap the refresh button to try again",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
            } // end else (streams visible)
        }
    }
}

private fun List<PlayerSource>.pickerInnerSections(
    addonName: String,
): List<Pair<String, List<PlayerSource>>> {
    val result = mutableListOf<Pair<String, MutableList<PlayerSource>>>()
    for (stream in this) {
        val inner = stream.pickerInnerName()
        val last = result.lastOrNull()
        if (last == null || last.first != inner) {
            result.add(inner to mutableListOf(stream))
        } else {
            last.second.add(stream)
        }
    }
    return result.map { (name, streams) -> name to streams.toList() }
}

private fun PlayerSource.pickerInnerName(): String {
    val firstLine = label.lines().firstOrNull()?.trim() ?: return addonName
    return firstLine.split("|").firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: addonName
}

@Composable
private fun PickerSectionHeader(name: String, isLoading: Boolean) {
    Row(
        Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        if (isLoading) {
            CircularProgressIndicator(
                Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerStreamCard(
    source: PlayerSource,
    onClick: () -> Unit,
    onDownload: (() -> Unit)? = null,
) {
    val lines = source.label.lines().map { it.trim() }.filter { it.isNotBlank() }
    val titleLine = lines.firstOrNull() ?: source.addonName
    val descLines = lines.drop(1)
    val context = LocalContext.current

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", source.url))
                    Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    titleLine,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val q = source.qualityTag
                if (!q.isNullOrBlank()) {
                    val (badgeBg, badgeFg) = when {
                        q.equals("4K", ignoreCase = true)   -> Color(0xFFFFD700).copy(alpha = 0.22f) to Color(0xFFFFD700)
                        q.contains("1080")                  -> Color(0xFF4EA8DE).copy(alpha = 0.22f) to Color(0xFF4EA8DE)
                        q.contains("1440") || q.equals("2K", ignoreCase = true) -> Color(0xFF7B68EE).copy(alpha = 0.22f) to Color(0xFF7B68EE)
                        q.contains("720")                   -> Color(0xFF4CAF50).copy(alpha = 0.22f) to Color(0xFF4CAF50)
                        q.contains("480") || q.contains("360") -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) to MaterialTheme.colorScheme.primary
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            q,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = badgeFg,
                        )
                    }
                }
                if (source.isMagnet) {
                    Text(
                        "⚡",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            descLines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onDownload != null) {
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.Download,
                    contentDescription = "Download stream",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun TmdbMovie.pickerYear(): Int? =
    releaseDate?.takeIf { it.isNotBlank() }?.substringBefore('-')?.toIntOrNull()

private fun StremioStream.pickerToPlayerSource(addon: InstalledStremioAddon): PlayerSource? {
    val playable = pickerPlayableUrl() ?: return null
    val isMagnet = playable.startsWith("magnet:")
    val label = title?.takeIf { it.isNotBlank() } ?: name ?: description ?: "Stream"
    val quality = pickerQualityTag()
    return PlayerSource(
        id = "${addon.id}::${(infoHash ?: ytId ?: url ?: "").take(64)}::${label.hashCode()}",
        url = playable,
        label = label,
        addonName = addon.name,
        qualityTag = quality,
        isMagnet = isMagnet,
    )
}

private fun StremioStream.pickerPlayableUrl(): String? = when {
    !url.isNullOrBlank() -> url
    !ytId.isNullOrBlank() -> "https://www.youtube.com/watch?v=$ytId"
    !infoHash.isNullOrBlank() -> {
        val baseTrackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
        )
        val trackers = (sources?.filter { it.startsWith("tracker:") }
            ?.map { it.removePrefix("tracker:") } ?: emptyList()) + baseTrackers
        val dn = title?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: "Stream"
        val trk = trackers.joinToString("&") { "tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        "magnet:?xt=urn:btih:$infoHash&dn=$dn&$trk"
    }
    else -> null
}

private fun StremioStream.pickerQualityTag(): String? {
    val h = listOfNotNull(name, title, description).joinToString(" ").lowercase()
    return when {
        "2160" in h || "4k" in h || "uhd" in h -> "4K"
        "1440" in h -> "1440p"
        "1080" in h -> "1080p"
        "720" in h -> "720p"
        "480" in h -> "480p"
        "hd" in h -> "HD"
        else -> null
    }
}

private fun NuvioStream.pickerToPlayerSource(provider: InstalledNuvioProvider): PlayerSource {
    val cleanName = name?.trim()?.takeIf { it.isNotBlank() }
    val label = buildString {
        if (!cleanName.isNullOrBlank()) append(cleanName)
        val desc = title?.trim()?.takeIf { it.isNotBlank() }
        if (!desc.isNullOrBlank()) {
            if (isNotEmpty()) append("\n")
            append(desc)
        }
        if (isEmpty()) append("Stream")
    }
    val qualityHint = quality?.takeIf { it.isNotBlank() }
        ?: name?.lines()?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()
    return PlayerSource(
        id = "nuvio::${provider.id}::${url.hashCode()}::${label.hashCode()}",
        url = url,
        label = label,
        addonName = provider.name,
        qualityTag = pickerNormaliseQuality(qualityHint),
        isMagnet = url.startsWith("magnet:"),
        headers = headers ?: emptyMap(),
    )
}

private fun pickerNormaliseQuality(q: String?): String? {
    if (q.isNullOrBlank()) return null
    val s = q.trim()
    return when {
        s.equals("4K", ignoreCase = true) || s.contains("2160") || s.contains("uhd", ignoreCase = true) -> "4K"
        s.contains("1440") || s.equals("2K", ignoreCase = true) -> "1440p"
        s.contains("1080") || s.equals("fhd", ignoreCase = true) || s.equals("fullhd", ignoreCase = true) || s.equals("full hd", ignoreCase = true) -> "1080p"
        s.contains("720") || s.equals("hd", ignoreCase = true) -> "720p"
        s.contains("480") || s.equals("sd", ignoreCase = true) -> "480p"
        s.contains("360") -> "360p"
        else -> s
    }
}

private suspend fun resolveCsForPicker(
    context: Context,
    plugin: InstalledPlugin,
    title: String,
    year: Int?,
): List<PlayerSource> {
    return try {
        if (title.isBlank()) return emptyList()
        val results: List<SearchResponse> = runCatching {
            PluginRuntime.search(context, plugin.filePath, title)
        }.getOrDefault(emptyList())
        val best = pickerBestMatch(results, title, year) ?: return emptyList()
        val detail = runCatching {
            PluginRuntime.loadDetail(context, plugin.filePath, best.url)
        }.getOrNull() ?: return emptyList()
        val dataStr: String? = when (detail) {
            is MovieLoadResponse -> detail.dataUrl
            is LiveStreamLoadResponse -> detail.dataUrl
            is TvSeriesLoadResponse -> {
                val eps = detail.episodes
                if (eps.size == 1) eps.first().data else null
            }
            is AnimeLoadResponse -> {
                val eps = detail.episodes.values.flatten()
                if (eps.size == 1) eps.first().data else null
            }
            else -> null
        }
        val data = dataStr ?: return emptyList()
        val (links, _) = runCatching {
            PluginRuntime.loadLinks(context, plugin.filePath, data, isCasting = false)
        }.getOrElse { return emptyList() }
        links.pickerToCsSources(plugin.name)
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun pickerBestMatch(results: List<SearchResponse>, title: String, year: Int?): SearchResponse? {
    if (results.isEmpty()) return null
    val clean = title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    return results.map { sr ->
        var score = 0
        val srClean = sr.name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        if (srClean == clean) score += 50
        else if (srClean.contains(clean)) score += 10
        if (year != null) {
            val srYear = (sr as? com.lagradost.cloudstream3.MovieSearchResponse)?.year
                ?: (sr as? com.lagradost.cloudstream3.TvSeriesSearchResponse)?.year
            if (srYear == year) score += 30
        }
        sr to score
    }.sortedByDescending { it.second }.firstOrNull()?.first
}

private fun List<ExtractorLink>.pickerToCsSources(pluginName: String): List<PlayerSource> =
    mapIndexedNotNull { idx, link ->
        if (link.url.isBlank()) return@mapIndexedNotNull null
        val extractorName = link.source.takeIf { it.isNotBlank() } ?: pluginName
        val label = when {
            link.name.isBlank() -> extractorName
            link.name.trim().equals(pluginName.trim(), ignoreCase = true) -> extractorName
            else -> link.name
        }
        val quality: String? = when {
            link.quality >= 2160 -> "4K"
            link.quality >= 1440 -> "1440p"
            link.quality >= 1080 -> "1080p"
            link.quality >= 720 -> "720p"
            link.quality >= 480 -> "480p"
            link.quality > 0 -> "${link.quality}p"
            else -> null
        }
        PlayerSource(
            id = "cs::$pluginName::${link.url.hashCode()}::$idx",
            url = link.url,
            label = label,
            addonName = extractorName,
            qualityTag = quality,
            isMagnet = link.url.startsWith("magnet:"),
            headers = buildMap {
                if (link.referer.isNotBlank()) put("Referer", link.referer)
                putAll(link.headers)
            },
        )
    }
