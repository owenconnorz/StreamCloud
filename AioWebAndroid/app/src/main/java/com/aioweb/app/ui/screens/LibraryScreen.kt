package com.aioweb.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aioweb.app.data.library.LibraryDb
import com.aioweb.app.data.library.TrackEntity
import com.aioweb.app.data.ytmusic.YtMusicLibrary
import com.aioweb.app.data.ytmusic.YtmLibraryArtist
import com.aioweb.app.data.ytmusic.YtmPlaylist
import com.aioweb.app.data.ytmusic.YtmSong
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private enum class LibTab(val label: String) {
    Playlists("Playlists"),
    Songs("Songs"),
    Albums("Albums"),
    Artists("Artists"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenPlaylist: (id: String, title: String) -> Unit = { _, _ -> },
    onOpenArtist: (channelUrl: String) -> Unit = {},
    onProfileClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val dao = remember { LibraryDb.get(context).tracks() }
    val sl = remember(context) { com.aioweb.app.data.ServiceLocator.get(context) }
    val ytCookie by sl.settings.ytMusicCookie.collectAsState(initial = "")
    val scope = rememberCoroutineScope()

    // YT Music library — synced lazily the first time the tab is opened while signed in,
    // and again when the user hits the refresh button.
    var ytLibrary by remember { mutableStateOf(com.aioweb.app.data.ytmusic.YtMusicLibrary()) }
    var ytLoading by remember { mutableStateOf(false) }

    LaunchedEffect(ytCookie) {
        if (ytCookie.isBlank()) {
            ytLibrary = com.aioweb.app.data.ytmusic.YtMusicLibrary(
                failureReason = "Not signed in.",
            )
            return@LaunchedEffect
        }
        ytLoading = true
        ytLibrary = com.aioweb.app.data.ytmusic.YtMusicLibraryRepository.sync(ytCookie)
        ytLoading = false
    }

    val combined by remember(dao) {
        combine(dao.liked(), dao.recent(), dao.downloaded(), dao.mostPlayed()) { l, r, d, mp ->
            arrayOf(l, r, d, mp)
        }
    }.collectAsState(initial = arrayOf<List<TrackEntity>>(emptyList(), emptyList(), emptyList(), emptyList()))

    val liked = combined[0]; val recent = combined[1]; val downloaded = combined[2]; val mostPlayed = combined[3]

    var tab by remember { mutableStateOf(LibTab.Playlists) }
    var openTile by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    if (showCreatePlaylistDialog) {
        CreateLocalPlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
                showCreatePlaylistDialog = false
                // Local playlist creation isn't wired through Room yet — surface
                // a friendly "in progress" hint instead of silently no-op'ing.
                android.widget.Toast.makeText(
                    context,
                    "Local playlist “$name” saved (UI only — sync arrives next).",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Library",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            com.aioweb.app.ui.components.ProfileButton(onClick = onProfileClick)
        }
        Spacer(Modifier.height(12.dp))

        // Top filter chips: Playlists / Songs / Albums / Artists
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibTab.values().forEach { t ->
                LibFilterChip(
                    label = t.label,
                    selected = tab == t,
                    onClick = { tab = t },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Secondary chip row — Metrolist parity: sort chip + count label + refresh.
        LibrarySubHeader(
            tab = tab,
            localTileCount = 4,
            ytLibrary = ytLibrary,
            ytLoading = ytLoading,
            onRefresh = {
                scope.launch {
                    ytLoading = true
                    ytLibrary = com.aioweb.app.data.ytmusic.YtMusicLibraryRepository.sync(ytCookie)
                    ytLoading = false
                }
            },
        )

        if (openTile != null) {
            val list = when (openTile) {
                "liked" -> liked
                "downloaded" -> downloaded
                "top50" -> mostPlayed
                "cached" -> recent
                else -> emptyList()
            }
            BackButton(label = openTile.orEmpty().replaceFirstChar { it.uppercase() }) { openTile = null }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.url }) { e -> LibTrackRow(e) }
            }
        } else {
            // Metrolist layout: single 2-column grid that merges the 4 local system
            // tiles with the user's YouTube Music playlists / albums / artists.
            // Each tab narrows the grid to the relevant content type.
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    LibTab.Playlists -> {
                        item { CreatePlaylistTile { showCreatePlaylistDialog = true } }
                        item { LocalSystemTile("Liked", Icons.Default.Favorite,
                            liked.size, liked.mapNotNull { it.thumbnail }) { openTile = "liked" } }
                        item { LocalSystemTile("Downloaded", Icons.Default.DownloadDone,
                            downloaded.size, downloaded.mapNotNull { it.thumbnail }) { openTile = "downloaded" } }
                        item { LocalSystemTile("My top 50", Icons.Default.TrendingUp,
                            mostPlayed.size, mostPlayed.mapNotNull { it.thumbnail }) { openTile = "top50" } }
                        item { LocalSystemTile("Cached", Icons.Default.History,
                            recent.size, recent.mapNotNull { it.thumbnail }) { openTile = "cached" } }
                        items(ytLibrary.playlists, key = { "yp_${it.id}" }) { pl ->
                            YtPlaylistTile(pl) { onOpenPlaylist(pl.id, pl.title) }
                        }
                    }
                    LibTab.Albums -> {
                        if (ytLibrary.albums.isEmpty() && !ytLoading) {
                            item(span = { GridItemSpan(2) }) {
                                EmptyStateRow(
                                    "No albums in your YouTube Music library.",
                                    ytCookie.isBlank(),
                                )
                            }
                        }
                        items(ytLibrary.albums, key = { "ya_${it.id}" }) { alb ->
                            YtPlaylistTile(alb) { onOpenPlaylist(alb.id, alb.title) }
                        }
                    }
                    LibTab.Artists -> {
                        if (ytLibrary.artists.isEmpty() && !ytLoading) {
                            item(span = { GridItemSpan(2) }) {
                                EmptyStateRow(
                                    "You haven't subscribed to any artists.",
                                    ytCookie.isBlank(),
                                )
                            }
                        }
                        items(ytLibrary.artists, key = { "yar_${it.channelId}" }) { ar ->
                            YtArtistTile(ar) {
                                onOpenArtist("https://music.youtube.com/channel/${ar.channelId}")
                            }
                        }
                    }
                    LibTab.Songs -> {
                        // Songs is a vertical list — use a single spanned column.
                        if (ytLibrary.likedSongs.isEmpty() && liked.isEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                EmptyStateRow(
                                    "Like a song to see it here.",
                                    ytCookie.isBlank(),
                                )
                            }
                        }
                        items(ytLibrary.likedSongs, span = { GridItemSpan(2) }) { s ->
                            YtSongRow(s) {
                                scope.launch {
                                    runCatching {
                                        com.aioweb.app.data.ytmusic.YtPlayback.playSong(context, s)
                                    }
                                }
                            }
                        }
                        items(liked, span = { GridItemSpan(2) }) { e -> LibTrackRow(e) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun LibTile(
    title: String,
    icon: ImageVector,
    count: Int,
    thumbs: List<String>,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbs.isEmpty()) {
                Icon(
                    icon, title,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(72.dp),
                )
            } else {
                // 2x2 thumbnail mosaic (Metrolist style)
                Column {
                    Row(Modifier.weight(1f)) {
                        ThumbCell(thumbs.getOrNull(0), Modifier.weight(1f))
                        ThumbCell(thumbs.getOrNull(1), Modifier.weight(1f))
                    }
                    Row(Modifier.weight(1f)) {
                        ThumbCell(thumbs.getOrNull(2), Modifier.weight(1f))
                        ThumbCell(thumbs.getOrNull(3), Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$count " + if (count == 1) "track" else "tracks",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThumbCell(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().background(Color(0xFF222222))) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun BackButton(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            "Back",
            tint = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun LibTrackRow(entity: TrackEntity) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = entity.thumbnail,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entity.title, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                entity.artist, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (entity.localPath != null) {
            Icon(
                Icons.Default.DownloadDone, "Downloaded",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ────────────────── Metrolist-style Library sub-header ──────────────────

@Composable
private fun LibrarySubHeader(
    tab: LibTab,
    localTileCount: Int,
    ytLibrary: YtMusicLibrary,
    ytLoading: Boolean,
    onRefresh: () -> Unit,
) {
    // Count label matches Metrolist's "N playlists" / "N albums" / "N artists" affix.
    val count = when (tab) {
        LibTab.Playlists -> localTileCount + ytLibrary.playlists.size
        LibTab.Albums -> ytLibrary.albums.size
        LibTab.Artists -> ytLibrary.artists.size
        LibTab.Songs -> ytLibrary.likedSongs.size
    }
    val label = when (tab) {
        LibTab.Playlists -> "playlists"
        LibTab.Albums -> "albums"
        LibTab.Artists -> "artists"
        LibTab.Songs -> "songs"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sort chip (static label — Metrolist's is interactive; we ship v1 fixed).
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Date added",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ArrowDropDown, null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "$count $label",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh, enabled = !ytLoading) {
            if (ytLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Sync YouTube Music",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

// ────────────────── Metrolist-style 2-col grid tiles ──────────────────

/**
 * Large rounded tile for a local "system" playlist (Liked / Downloaded / Top 50 /
 * Cached). Shows a 2×2 mosaic of track thumbnails when present, otherwise a solid
 * branded square with just the icon — matches Metrolist's playlist-card aesthetic.
 */
@Composable
private fun LocalSystemTile(
    title: String,
    icon: ImageVector,
    count: Int,
    thumbs: List<String>,
    onClick: () -> Unit,
) {
    val topThumbs = thumbs.take(4)
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (topThumbs.size >= 4) {
                // 2×2 mosaic — Metrolist's signature look.
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        MosaicCell(topThumbs[0], Modifier.weight(1f).fillMaxHeight())
                        MosaicCell(topThumbs[1], Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.weight(1f)) {
                        MosaicCell(topThumbs[2], Modifier.weight(1f).fillMaxHeight())
                        MosaicCell(topThumbs[3], Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else if (topThumbs.isNotEmpty()) {
                AsyncImage(
                    model = topThumbs.first(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$count songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MosaicCell(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun YtPlaylistTile(pl: YtmPlaylist, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = pl.thumbnail,
            contentDescription = pl.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            pl.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        pl.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun YtArtistTile(a: YtmLibraryArtist, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = a.thumbnail,
            contentDescription = a.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            a.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun YtSongRow(s: YtmSong, onClick: () -> Unit) {
    val context = LocalContext.current
    // Check the downloaded state of this row's track without blocking the UI
    // thread — flips the tick on as soon as the file lands on disk.
    var downloaded by remember(s.videoId) {
        mutableStateOf(com.aioweb.app.data.ytmusic.YtPlayback.isDownloaded(context, s))
    }
    val downloadProgressMap by com.aioweb.app.data.downloads.MusicDownloader.progressFlow
        .collectAsState(initial = emptyMap())
    LaunchedEffect(s.videoId, downloadProgressMap) {
        if (downloadProgressMap[com.aioweb.app.data.ytmusic.YtPlayback.watchUrl(s.videoId)] == null) {
            downloaded = com.aioweb.app.data.ytmusic.YtPlayback.isDownloaded(context, s)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = s.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(onClick = onClick)) {
            Text(
                s.title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    s.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        com.aioweb.app.ui.components.SongRowMenu(song = s, onPlay = onClick)
    }
}

@Composable
private fun EmptyStateRow(message: String, notSignedIn: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (notSignedIn) Icons.Default.AutoAwesome else Icons.Default.CloudDone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (notSignedIn) "Sign in to YouTube Music to sync your library." else message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}


// ─────────────────────── YouTube Music sync widgets ───────────────────────

@Composable
private fun YtMusicSyncHeader(
    signedIn: Boolean,
    loading: Boolean,
    library: YtMusicLibrary,
    onRefresh: () -> Unit,
) {
    if (!signedIn) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CloudDone, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "YouTube Music · Synced",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            val subtitle = when {
                loading -> "Refreshing…"
                library.failureReason != null -> library.failureReason.orEmpty()
                library.likedSongs.isEmpty() && library.playlists.isEmpty() &&
                    library.albums.isEmpty() && library.artists.isEmpty() ->
                    "Nothing in your YouTube Music library yet."
                else -> "${library.playlists.size} playlists · " +
                    "${library.albums.size} albums · " +
                    "${library.artists.size} artists · " +
                    "${library.likedSongs.size} liked"
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, "Refresh")
            }
        }
    }
}

@Composable
private fun YtPlaylistSection(
    title: String,
    playlists: List<YtmPlaylist>,
    onClick: (YtmPlaylist) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(playlists, key = { "yp_${it.id}" }) { pl ->
            Column(
                Modifier
                    .width(150.dp)
                    .clickable { onClick(pl) },
            ) {
                AsyncImage(
                    model = pl.thumbnail,
                    contentDescription = pl.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    pl.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                pl.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun YtArtistsSection(
    artists: List<YtmLibraryArtist>,
    onClick: (YtmLibraryArtist) -> Unit,
) {
    Text(
        "Subscribed artists",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(artists, key = { "ya_${it.channelId}" }) { a ->
            Column(
                Modifier
                    .width(120.dp)
                    .clickable { onClick(a) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = a.thumbnail,
                    contentDescription = a.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    a.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun YtSongsSection(
    title: String,
    songs: List<YtmSong>,
    onSongClick: (YtmSong) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    // Show a preview — first 5 songs — with a "View all" affordance at the bottom.
    Column {
        songs.take(5).forEach { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = s.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSongClick(s) },
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable { onSongClick(s) }) {
                    Text(
                        s.title,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        s.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                com.aioweb.app.ui.components.SongRowMenu(song = s, onPlay = { onSongClick(s) })
            }
        }
        if (songs.size > 5) {
            TextButton(
                onClick = { /* TODO: open dedicated liked songs screen */ },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text("View all ${songs.size} liked songs")
            }
        }
    }
}


/**
 * Big "Create playlist" tile — Metrolist parity. Lives at the start of the
 * Playlists grid so it's always one tap away.
 */
@Composable
private fun CreatePlaylistTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Create new playlist",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            "New playlist",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Add songs from anywhere",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CreateLocalPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create playlist") },
        text = {
            Column {
                Text(
                    "Give your playlist a name. You can add songs later from any track's 3-dot menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Playlist name") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

