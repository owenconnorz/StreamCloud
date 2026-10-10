package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
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
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.stremio.StremioMeta
import com.streamcloud.app.data.stremio.StremioStream
import com.streamcloud.app.data.stremio.StremioVideo
import com.streamcloud.app.ui.theme.rememberBannerPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StremioDetailScreen(
    addonId: String,
    type: String,
    metaId: String,
    initialTitle: String,
    initialPoster: String?,
    onBack: () -> Unit,
    onPlay: (streamUrl: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val libraryDb = remember { LibraryDb.get(context.applicationContext) }
    val watchlistDao = remember { libraryDb.watchlist() }
    val syntheticId = remember(addonId, metaId) {
        val hash = "$addonId|$type|$metaId".hashCode().toLong()
        if (hash < 0L) hash else -(hash + 1L)
    }
    val isDefaultWatchlisted by watchlistDao.isWatchlisted(syntheticId)
        .collectAsState(initial = false)
    val isCustomWatchlisted by libraryDb.movieWatchlists()
        .isInAnyWatchlist(syntheticId)
        .collectAsState(initial = false)
    val isWatchlisted = isDefaultWatchlisted || isCustomWatchlisted

    var meta by remember(addonId, type, metaId) { mutableStateOf<StremioMeta?>(null) }
    var streams by remember(addonId, type, metaId) { mutableStateOf<List<StremioStream>>(emptyList()) }
    var loadingStreams by remember { mutableStateOf(true) }
    var selectedEpisode by remember(addonId, type, metaId) { mutableStateOf<StremioVideo?>(null) }
    var loadingEpisodeStreams by remember(addonId, type, metaId) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionsExpanded by remember(addonId, type, metaId) { mutableStateOf(false) }
    var markedWatched by remember(addonId, type, metaId) { mutableStateOf(false) }
    var watchlistPickerEntry by remember(addonId, type, metaId) {
        mutableStateOf<WatchlistEntity?>(null)
    }

    val isSeries = type.equals("series", ignoreCase = true) ||
        type.equals("tv", ignoreCase = true) ||
        type.equals("show", ignoreCase = true)
    val episodes = remember(meta?.videos) {
        meta?.videos.orEmpty()
            .sortedWith(compareBy<StremioVideo> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })
    }

    LaunchedEffect(addonId, type, metaId) {
        loadingStreams = true
        error = null
        runCatching {
            val addon = sl.stremio.addons.first().firstOrNull { it.id == addonId }
            if (addon == null) {
                error = "Addon no longer installed."
                loadingStreams = false
                return@runCatching
            }

            val loadedMeta = runCatching { sl.stremio.fetchMeta(addon, type, metaId) }.getOrNull()
            meta = loadedMeta
            val hasEpisodes = isSeries && !loadedMeta?.videos.isNullOrEmpty()

            // Series stream endpoints are episode-specific. Movies retain the
            // item-level request only when the addon did not provide episodes.
            streams = if (hasEpisodes) {
                emptyList()
            } else {
                runCatching { sl.stremio.fetchStreams(addon, type, metaId) }
                    .getOrDefault(emptyList())
            }
            if (!hasEpisodes && streams.isEmpty()) {
                error = "No streams returned by this addon for this item."
            }
        }.onFailure { error = "Failed: ${it.message}" }
        loadingStreams = false
    }

    LaunchedEffect(addonId, type, metaId, selectedEpisode?.id) {
        val episode = selectedEpisode ?: return@LaunchedEffect
        loadingEpisodeStreams = true
        error = null
        streams = emptyList()
        try {
            val addon = sl.stremio.addons.first().firstOrNull { it.id == addonId }
            if (addon == null) {
                error = "Addon no longer installed."
                return@LaunchedEffect
            }
            streams = sl.stremio.fetchStreams(addon, type, episode.id)
            if (streams.isEmpty()) {
                error = "No streams returned for ${episode.displayLabel()}."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = "Failed to load ${episode.displayLabel()}: ${failure.message}"
        } finally {
            loadingEpisodeStreams = false
        }
    }

    val firstPlayableStream = streams.firstNotNullOfOrNull { buildStreamUrl(it) }
    val bannerImageUrl = meta?.background?.takeIf { it.isNotBlank() }
        ?: meta?.poster?.takeIf { it.isNotBlank() }
        ?: initialPoster?.takeIf { it.isNotBlank() }
    val bannerPalette = rememberBannerPalette(
        imageUrl = bannerImageUrl,
        fallbackAccent = MaterialTheme.colorScheme.primary,
        fallbackOnAccent = MaterialTheme.colorScheme.onPrimary,
        fallbackBackground = MaterialTheme.colorScheme.background,
        fallbackSurface = MaterialTheme.colorScheme.surface,
    )

    Box(Modifier.fillMaxSize().background(bannerPalette.backgroundTint)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .background(bannerPalette.surfaceTint),
                    ) {
                        AsyncImage(
                            model = bannerImageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(Color.Transparent, bannerPalette.backgroundTint),
                                    ),
                                ),
                        )
                    }
                    Column(
                        Modifier.padding(horizontal = 20.dp).offset(y = (-50).dp),
                    ) {
                        Text(
                            meta?.name ?: initialTitle,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (!isSeries || selectedEpisode != null || episodes.isEmpty()) {
                                Button(
                                    onClick = {
                                        firstPlayableStream?.let {
                                            val title = selectedEpisode?.displayLabel()
                                                ?.let { episodeTitle -> "${meta?.name ?: initialTitle} · $episodeTitle" }
                                                ?: (meta?.name ?: initialTitle)
                                            onPlay(it, title)
                                        }
                                    },
                                    enabled = firstPlayableStream != null && !loadingEpisodeStreams,
                                    modifier = Modifier.weight(1f).height(52.dp),
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = bannerPalette.accent,
                                        contentColor = bannerPalette.onAccent,
                                    ),
                                ) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (isSeries) {
                                            "Play ${selectedEpisode?.displayLabel() ?: "Series"}"
                                        } else {
                                            "Play Movie"
                                        },
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            if (actionsExpanded) {
                                IconButton(
                                    onClick = {
                                        watchlistPickerEntry = WatchlistEntity(
                                            tmdbId = syntheticId,
                                            title = meta?.name ?: initialTitle,
                                            posterUrl = meta?.poster ?: initialPoster,
                                            mediaType = "stremio",
                                            csPlugin = addonId,
                                            csUrl = "$type|||$metaId",
                                        )
                                    },
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(bannerPalette.surfaceTint),
                                ) {
                                    Icon(
                                        if (isWatchlisted) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                        "Save to library",
                                        tint = if (isWatchlisted) bannerPalette.accent else LocalContentColor.current,
                                    )
                                }
                                IconButton(
                                    onClick = { markedWatched = !markedWatched },
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(bannerPalette.surfaceTint),
                                ) {
                                    Icon(
                                        if (markedWatched) Icons.Default.Check else Icons.Default.Check,
                                        "Mark as watched",
                                        tint = if (markedWatched) bannerPalette.accent else LocalContentColor.current,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { actionsExpanded = !actionsExpanded },
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(bannerPalette.surfaceTint),
                            ) {
                                Icon(
                                    if (actionsExpanded) Icons.Default.Close else Icons.Default.MoreVert,
                                    if (actionsExpanded) "Close actions" else "More actions",
                                )
                            }
                        }
                        if (isSeries && episodes.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))
                            Text(
                                "Episodes",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(8.dp))
                            episodes.forEach { episode ->
                                val selected = selectedEpisode?.id == episode.id
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) bannerPalette.accentContainer
                                            else bannerPalette.surfaceTint.copy(alpha = 0.55f),
                                        )
                                        .clickable { selectedEpisode = episode }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(
                                        model = episode.thumbnail ?: bannerImageUrl,
                                        contentDescription = episode.displayLabel(),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 92.dp, height = 52.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            episode.displayLabel(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = bannerPalette.accent,
                                        )
                                        Text(
                                            episode.title ?: "Episode",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (selected && loadingEpisodeStreams) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = bannerPalette.accent,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Load episode streams",
                                            tint = bannerPalette.accent,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        } else if (isSeries) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "This addon did not provide an episode list; available streams are shown below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 14.dp),
                        ) {
                            meta?.releaseInfo?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            meta?.runtime?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            meta?.imdbRating?.takeIf { it.isNotBlank() }?.let {
                                Text("★ $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        meta?.genres?.takeIf { it.isNotEmpty() }?.let {
                            Text(
                                it.joinToString(" • "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Text(
                            "Find in Source",
                            style = MaterialTheme.typography.labelLarge,
                            color = bannerPalette.accent,
                            modifier = Modifier.padding(top = 18.dp),
                        )
                        Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                addonId,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(bannerPalette.accentContainer)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                        meta?.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 20.dp),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    if (isSeries && selectedEpisode != null) {
                        "Streams · ${selectedEpisode?.displayLabel()}"
                    } else {
                        "Streams"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (isSeries && episodes.isNotEmpty() && selectedEpisode == null) {
                    Text(
                        "Choose an episode above to load streams.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            if (loadingStreams || loadingEpisodeStreams) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                            color = bannerPalette.accent,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (loadingEpisodeStreams) "Asking addon for episode streams…"
                            else "Asking addon for streams…",
                        )
                    }
                }
            }
            error?.let {
                item {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
            // Addons can return duplicate stream URLs (for example, the same HLS endpoint
            // with different labels/metadata). The content fields are not a unique identity.
            itemsIndexed(streams, key = { index, s ->
                "s_${index}_${s.url ?: s.infoHash ?: s.title.orEmpty()}"
            }) { _, s ->
                StreamRow(
                    s = s,
                    accentColor = bannerPalette.accent,
                    accentContainerColor = bannerPalette.accentContainer,
                ) {
                    val streamUrl = buildStreamUrl(s) ?: return@StreamRow
                    val title = selectedEpisode?.displayLabel()
                        ?.let { episodeTitle -> "${meta?.name ?: initialTitle} · $episodeTitle" }
                        ?: (meta?.name ?: initialTitle)
                    onPlay(streamUrl, title)
                }
            }
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(start = 12.dp, top = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(bannerPalette.surfaceTint.copy(alpha = 0.92f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
    }

    watchlistPickerEntry?.let { entry ->
        MovieWatchlistPickerDialog(
            entry = entry,
            onDismiss = { watchlistPickerEntry = null },
        )
    }
}

private fun buildStreamUrl(s: StremioStream): String? {

    s.url?.takeIf { it.isNotBlank() }?.let { return it }

    val hash = s.infoHash?.takeIf { it.isNotBlank() } ?: return null



    val trackerParams = s.sources.orEmpty()
        .filter { it.startsWith("tracker:", ignoreCase = true) }
        .mapNotNull { it.substringAfter("tracker:", "").substringAfter("TRACKER:", "").takeIf { url -> url.isNotBlank() } }
        .joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }

    val magnet = "magnet:?xt=urn:btih:$hash$trackerParams"


    return if (s.fileIdx != null) "$magnet&_sc_fidx=${s.fileIdx}" else magnet
}

private fun StremioVideo.displayLabel(): String {
    return when {
        season != null && episode != null -> "S${season}E${episode}"
        episode != null -> "Episode $episode"
        else -> "Episode"
    }
}

@Composable
private fun StreamRow(
    s: StremioStream,
    accentColor: Color,
    accentContainerColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accentContainerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = accentColor)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.title ?: s.name ?: "Stream",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            s.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (s.infoHash != null) {
            Text(
                "Torrent",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFF7A29),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFF7A29).copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
