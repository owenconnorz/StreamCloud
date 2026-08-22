@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.rememberLazyListState
import com.streamcloud.app.ui.util.verticalScrollbar
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistPlay
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import com.streamcloud.app.ui.theme.AlbumArtThemeBus
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import com.streamcloud.app.data.downloads.MovieDownloader
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.MovieDownloadEntity
import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.ytmusic.YtMusicLibrary
import com.streamcloud.app.data.ytmusic.YtmLibraryArtist
import com.streamcloud.app.data.ytmusic.YtmPlaylist
import com.streamcloud.app.data.ytmusic.YtmSong
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup

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
    onMovieClick: (Long) -> Unit = {},
    onPlayLocalFile: (filePath: String, title: String, tmdbId: Long, mediaType: String) -> Unit = { _, _, _, _ -> },
    onTvClick: (Long) -> Unit = {},
    onCsClick: (plugin: String, url: String, title: String, poster: String?) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val gridColumns = if (isTv) 4 else 2
    val dao = remember { LibraryDb.get(context).tracks() }
    val sl = remember(context) { com.streamcloud.app.data.ServiceLocator.get(context) }
    val ytCookie by sl.settings.ytMusicCookie.collectAsState(initial = "")
    val playlistThumbsJson by sl.settings.playlistThumbsJson.collectAsState(initial = "{}")
    val playlistThumbs = remember(playlistThumbsJson) {
        playlistThumbsJson
            .removePrefix("{").removeSuffix("}")
            .split(",").filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split(":", limit = 2)
                if (parts.size != 2) null
                else parts[0].trim().trim('"') to parts[1].trim().trim('"')
            }.toMap()
    }
    val scope = rememberCoroutineScope()
    fun playTrackQueue(tracks: List<TrackEntity>, startIndex: Int) {
        if (startIndex !in tracks.indices) return
        scope.launch {
            runCatching {
                com.streamcloud.app.data.ytmusic.YtPlayback.playPlaylist(
                    context,
                    tracks.map(TrackEntity::toYtmSong),
                    startIndex,
                )
            }
        }
    }
    var menuEntry by remember { mutableStateOf<MovieDownloadEntity?>(null) }



    var ytLibrary by remember { mutableStateOf(com.streamcloud.app.data.ytmusic.YtMusicLibrary()) }
    var ytLoading by remember { mutableStateOf(false) }

    LaunchedEffect(ytCookie) {
        if (ytCookie.isBlank()) {
            ytLibrary = com.streamcloud.app.data.ytmusic.YtMusicLibrary(
                failureReason = "Not signed in.",
            )
            return@LaunchedEffect
        }


        val cached = withContext(Dispatchers.IO) {
            com.streamcloud.app.data.ytmusic.LibraryCache.read(context)
        }
        if (cached != null) {
            ytLibrary = cached
        } else {
            ytLoading = true
        }


        val fresh = com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository.sync(ytCookie)
        ytLoading = false

        val freshHasContent = fresh.playlists.isNotEmpty() || fresh.albums.isNotEmpty() ||
            fresh.likedSongs.isNotEmpty() || fresh.artists.isNotEmpty()
        val cachedHasContent = cached != null &&
            (cached.playlists.isNotEmpty() || cached.albums.isNotEmpty())

        if (fresh.failureReason == null && freshHasContent) {
            ytLibrary = fresh
            withContext(Dispatchers.IO) {
                com.streamcloud.app.data.ytmusic.LibraryCache.write(context, fresh)
            }
        } else if (fresh.failureReason == null && !freshHasContent && cachedHasContent) {
            // Sync succeeded but returned nothing — likely a transient API hiccup.
            // Keep the cached version on screen rather than blanking everything out.
            // (ytLibrary is already showing cached from above, so no reassignment needed.)
        } else {
            ytLibrary = fresh
            // If the sync returned an auth error, clear the stale empty cache so the
            // next successful sign-in will be able to populate it fresh.
            if (fresh.failureReason != null) {
                withContext(Dispatchers.IO) {
                    com.streamcloud.app.data.ytmusic.LibraryCache.clear(context)
                }
            }
        }
    }

    val combined by remember(dao) {
        combine(dao.liked(), dao.recent(), dao.downloaded(), dao.mostPlayed()) { l, r, d, mp ->
            arrayOf(l, r, d, mp)
        }
    }.collectAsState(initial = arrayOf<List<TrackEntity>>(emptyList(), emptyList(), emptyList(), emptyList()))

    val liked = combined[0]; val recent = combined[1]; val downloaded = combined[2]; val mostPlayed = combined[3]

    LaunchedEffect(ytLibrary.likedSongs) {
        com.streamcloud.app.data.ytmusic.YtMusicStreamResolver.prime(
            ytLibrary.likedSongs.map(YtmSong::videoId),
        )
    }

    LaunchedEffect(liked, recent, mostPlayed) {
        val visibleLibrarySongs = (liked + recent + mostPlayed)
            .asSequence()
            .map(TrackEntity::toYtmSong)
            .filter { it.videoId.length == 11 }
            .toList()
        com.streamcloud.app.data.ytmusic.YtMusicStreamResolver.prime(
            visibleLibrarySongs.map(YtmSong::videoId),
        )
    }

    var tab by remember { mutableStateOf(LibTab.Playlists) }
    var openTile by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var sectionMode by remember { mutableStateOf("Music") }
    val watchlistItems by LibraryDb.get(context).watchlist().all().collectAsState(initial = emptyList())
    val downloadedMovies by LibraryDb.get(context).movieDownloads().all().collectAsState(initial = emptyList())
    var movieSubTab by remember { mutableStateOf("Watchlist") }

    val localPlaylists by remember(context) {
        LibraryDb.get(context).localPlaylists().allPlaylists()
    }.collectAsState(initial = emptyList())

    if (showCreatePlaylistDialog) {
        CreateLocalPlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
                showCreatePlaylistDialog = false
                scope.launch {
                    LibraryDb.get(context).localPlaylists().createPlaylist(
                        com.streamcloud.app.data.library.LocalPlaylistEntity(name = name),
                    )
                }
            },
        )
    }

    val bgTintColorRaw by AlbumArtThemeBus.bgTint.collectAsState()
    val bgTintColor by animateColorAsState(bgTintColorRaw, animationSpec = tween(700), label = "libBgTint")
    val vibrantRaw by AlbumArtThemeBus.vibrant.collectAsState()
    val animVibrant by animateColorAsState(vibrantRaw, animationSpec = tween(700), label = "libVibrant")
    Column(Modifier.fillMaxSize().background(bgTintColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, top = 8.dp, end = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Library",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("Music" to Icons.Default.MusicNote, "Movies" to Icons.Default.Movie).forEach { (label, icon) ->
                    val selected = sectionMode == label
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .tvFocusBorder(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { sectionMode = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            icon, label,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (sectionMode == "Movies") {
            // ── Sub-tab: Watchlist / Downloaded ───────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .tvFocusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Watchlist", "Downloaded").forEach { label ->
                    val selected = movieSubTab == label
                    FilterChip(
                        selected = selected,
                        onClick = { movieSubTab = label },
                        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = if (label == "Downloaded") {
                            { Icon(Icons.Default.DownloadDone, null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (movieSubTab == "Downloaded") {
                if (downloadedMovies.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.DownloadDone, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("No downloaded movies",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("Tap the ··· menu on a movie to download it",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (isTv) 5 else 3),
                        modifier = Modifier.fillMaxSize().tvFocusGroup(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(downloadedMovies, key = { "dl_${it.tmdbId}" }) { entry ->
                            Column(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .tvFocusBorder(RoundedCornerShape(10.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (entry.status == "done" && !entry.filePath.isNullOrBlank()) {
                                                onPlayLocalFile(entry.filePath, entry.title, entry.tmdbId, entry.mediaType)
                                            } else {
                                                when (entry.mediaType) {
                                                    "tv" -> onTvClick(entry.tmdbId)
                                                    else -> onMovieClick(entry.tmdbId)
                                                }
                                            }
                                        },
                                        onLongClick = { menuEntry = entry },
                                    )
                            ) {
                                Box {
                                    AsyncImage(
                                        model = entry.posterUrl,
                                        contentDescription = entry.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(2f / 3f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    )
                                    if (entry.status == "downloading") {
                                        Box(
                                            Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .background(Color.Black.copy(alpha = 0.55f))
                                                .padding(4.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            LinearProgressIndicator(
                                                progress = { entry.progress },
                                                modifier = Modifier.fillMaxWidth(),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    } else if (entry.status == "done") {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Default.DownloadDone, null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            } else {
                if (watchlistItems.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Movie, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("No saved movies yet",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("Bookmark a movie or show to see it here",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (isTv) 5 else 3),
                        modifier = Modifier.fillMaxSize().tvFocusGroup(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(watchlistItems, key = { "wl_${it.tmdbId}" }) { entry ->
                            Column(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .tvFocusBorder(RoundedCornerShape(10.dp))
                                    .clickable {
                                        when (entry.mediaType) {
                                            "tv" -> onTvClick(entry.tmdbId)
                                            "cloudstream" -> onCsClick(entry.csPlugin, entry.csUrl, entry.title, entry.posterUrl)
                                            else -> onMovieClick(entry.tmdbId)
                                        }
                                    }
                            ) {
                                AsyncImage(
                                    model = entry.posterUrl,
                                    contentDescription = entry.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        } else {

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibTab.values().forEach { t ->
                LibFilterChip(
                    label = t.label,
                    selected = tab == t,
                    accentColor = animVibrant,
                    onClick = { tab = t },
                )
            }
        }
        Spacer(Modifier.height(16.dp))


        LibrarySubHeader(
            tab = tab,
            localTileCount = 3,
            ytLibrary = ytLibrary,
            ytLoading = ytLoading,
            onRefresh = {
                scope.launch {
                    ytLoading = true
                    ytLibrary = com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository.sync(ytCookie)
                    ytLoading = false
                }
            },
        )

        if (openTile != null) {
            val tileName = when (openTile) {
                "downloaded" -> "Downloaded"
                "top50" -> "My top 50"
                "cached" -> "Cached"
                else -> openTile.orEmpty()
            }
            val list = when (openTile) {
                "liked" -> liked
                "downloaded" -> downloaded
                "top50" -> mostPlayed
                "cached" -> recent
                else -> emptyList()
            }
            val openTileListState = rememberLazyListState()
            LazyColumn(
                state = openTileListState,
                modifier = Modifier.fillMaxSize().verticalScrollbar(openTileListState),
            ) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    0f to animVibrant.copy(alpha = 0.40f),
                                    1f to Color.Transparent,
                                )
                            )
                            .padding(bottom = 20.dp),
                    ) {
                        Column {
                            BackButton(label = tileName) { openTile = null }
                            Text(
                                "${list.size} songs",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                modifier = Modifier.padding(start = 52.dp),
                            )
                        }
                    }
                }
                itemsIndexed(
                    list,
                    key = { index, entry -> "library_${index}_${entry.url}" },
                ) { index, e ->
                    LibTrackRow(
                        e,
                        accentColor = animVibrant,
                        onClick = { playTrackQueue(list, index) },
                    )
                }
            }
        } else {



            Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                modifier = Modifier.fillMaxSize().tvFocusGroup(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 16.dp),
            ) {
                when (tab) {
                    LibTab.Playlists -> {
                        item { LocalSystemTile("Downloaded", Icons.Default.DownloadDone,
                            downloaded.size, downloaded.mapNotNull { it.thumbnail }) { openTile = "downloaded" } }
                        item { LocalSystemTile("My top 50", Icons.Default.TrendingUp,
                            mostPlayed.size, mostPlayed.mapNotNull { it.thumbnail }) { openTile = "top50" } }
                        item { LocalSystemTile("Cached", Icons.Default.History,
                            recent.size, recent.mapNotNull { it.thumbnail }) { openTile = "cached" } }
                        items(localPlaylists, key = { "lp_${it.id}" }) { pl ->
                            LocalPlaylistGridTile(
                                name = pl.name,
                                customThumb = playlistThumbs[pl.id.toString()],
                                onDelete = {
                                    scope.launch {
                                        val dao = LibraryDb.get(context).localPlaylists()
                                        dao.clearPlaylistTracks(pl.id)
                                        dao.deletePlaylist(pl.id)
                                    }
                                },
                            )
                        }
                        if (ytLibrary.failureReason != null && !ytLoading) {
                            item(span = { GridItemSpan(gridColumns) }) {
                                EmptyStateRow(
                                    message = ytLibrary.failureReason!!,
                                    notSignedIn = ytCookie.isBlank(),
                                )
                            }
                        }
                        items(ytLibrary.playlists, key = { "yp_${it.id}" }) { pl ->
                            YtPlaylistTile(pl, customThumb = playlistThumbs[pl.id]) { onOpenPlaylist(pl.id, pl.title) }
                        }
                    }
                    LibTab.Albums -> {
                        if (ytLibrary.albums.isEmpty() && !ytLoading) {
                            item(span = { GridItemSpan(gridColumns) }) {
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
                            item(span = { GridItemSpan(gridColumns) }) {
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

                        if (ytLibrary.likedSongs.isEmpty() && liked.isEmpty()) {
                            item(span = { GridItemSpan(gridColumns) }) {
                                EmptyStateRow(
                                    "Like a song to see it here.",
                                    ytCookie.isBlank(),
                                )
                            }
                        }
                        itemsIndexed(
                            ytLibrary.likedSongs,
                            span = { _, _ -> GridItemSpan(gridColumns) },
                        ) { index, s ->
                            YtSongRow(s) {
                                scope.launch {
                                    runCatching {
                                        com.streamcloud.app.data.ytmusic.YtPlayback.playPlaylist(
                                            context,
                                            ytLibrary.likedSongs,
                                            index,
                                        )
                                    }
                                }
                            }
                        }
                        itemsIndexed(
                            liked,
                            span = { _, _ -> GridItemSpan(gridColumns) },
                        ) { index, e ->
                            LibTrackRow(e, onClick = { playTrackQueue(liked, index) })
                        }
                    }
                }
            }
            if (tab == LibTab.Playlists) {
                FloatingActionButton(
                    onClick = { showCreatePlaylistDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 88.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New playlist")
                }
            }
            }
        }
        } // end Music else
    }

    // ── Download options bottom sheet ─────────────────────────────────────────
    if (menuEntry != null) {
        val dlEntry = menuEntry!!
        ModalBottomSheet(
            onDismissRequest = { menuEntry = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AsyncImage(
                    model = dlEntry.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 52.dp, height = 74.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        dlEntry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (dlEntry.mediaType == "tv") "TV Show" else "Movie",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            if (!dlEntry.filePath.isNullOrBlank()) {
                val samsungInstalled = remember {
                    try { context.packageManager.getPackageInfo("com.sec.android.app.myfiles", 0); true }
                    catch (_: Exception) { false }
                }
                if (samsungInstalled) {
                    DlMenuItem(
                        icon = Icons.Default.FolderOpen,
                        title = "Open in Samsung My Files",
                        subtitle = "Browse the StreamCloud folder",
                        onClick = {
                            menuEntry = null
                            openInSamsungMyFiles(context, dlEntry.filePath)
                        },
                    )
                }
                DlMenuItem(
                    icon = Icons.Default.Share,
                    title = "Open with...",
                    subtitle = "Send or open with another app",
                    onClick = {
                        menuEntry = null
                        openWithChooser(context, dlEntry.filePath)
                    },
                )
            }
            HorizontalDivider()
            DlMenuItem(
                icon = Icons.Default.Delete,
                title = "Delete Movie",
                subtitle = "Remove from downloads permanently",
                isDestructive = true,
                onClick = {
                    menuEntry = null
                    scope.launch { MovieDownloader.remove(context, dlEntry.tmdbId) }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun openInSamsungMyFiles(context: android.content.Context, filePath: String) {
    val samsungPkg = "com.sec.android.app.myfiles"
    val moviesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
    val streamCloudDir = java.io.File(moviesDir, "StreamCloud")
    val folderUri = android.net.Uri.fromFile(streamCloudDir)
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setPackage(samsungPkg)
                setDataAndType(folderUri, "resource/folder")
            }
        )
        return
    } catch (_: Exception) { }
    try {
        val launch = context.packageManager.getLaunchIntentForPackage(samsungPkg)
        if (launch != null) { context.startActivity(launch); return }
    } catch (_: Exception) { }
    openWithChooser(context, filePath)
}

private fun openWithChooser(context: android.content.Context, filePath: String) {
    val uri: android.net.Uri = if (filePath.startsWith("content://")) {
        android.net.Uri.parse(filePath)
    } else {
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))
        } catch (_: Exception) { return }
    }
    try {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Open with",
            )
        )
    } catch (_: Exception) { }
}

@Composable
private fun DlMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDestructive) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibFilterChip(
    label: String,
    selected: Boolean,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) accentColor.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
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
private fun LibTrackRow(
    entity: TrackEntity,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
                tint = accentColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun TrackEntity.toYtmSong(): YtmSong {
    val videoId = url
        .substringAfter("v=", missingDelimiterValue = "")
        .substringBefore("&")
        .ifBlank { url.substringAfterLast("/") }
    return YtmSong(
        videoId = videoId,
        title = title,
        artist = artist,
        album = null,
        thumbnail = thumbnail,
        durationSeconds = durationSec,
    )
}

@Composable
private fun LibrarySubHeader(
    tab: LibTab,
    localTileCount: Int,
    ytLibrary: YtMusicLibrary,
    ytLoading: Boolean,
    onRefresh: () -> Unit,
) {

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
private fun LocalPlaylistGridTile(
    name: String,
    customThumb: String?,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete playlist") },
            text = { Text("Delete \"$name\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
    Column(
        modifier = Modifier.clickable { },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!customThumb.isNullOrBlank()) {
                AsyncImage(
                    model = android.net.Uri.parse(customThumb),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.PlaylistPlay,
                    contentDescription = name,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
            ) {
                Icon(
                    Icons.Default.Close, "Delete",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Local playlist",
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
private fun YtPlaylistTile(pl: YtmPlaylist, customThumb: String? = null, onClick: () -> Unit) {
    val thumbModel: Any? = when {
        !customThumb.isNullOrBlank() -> android.net.Uri.parse(customThumb)
        !pl.thumbnail.isNullOrBlank() -> pl.thumbnail
        else -> null
    }
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = pl.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.PlaylistPlay,
                    contentDescription = pl.title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
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
    var downloaded by remember(s.videoId) {
        mutableStateOf(com.streamcloud.app.data.ytmusic.YtPlayback.isDownloaded(context, s))
    }




    LaunchedEffect(s.videoId) {
        val url = com.streamcloud.app.data.ytmusic.YtPlayback.watchUrl(s.videoId)
        com.streamcloud.app.data.downloads.YtMusicDownloadUtil.downloads.collect { dlMap ->
            val state = dlMap[url]?.state
            downloaded = (state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED)
                || com.streamcloud.app.data.downloads.MusicDownloader.isDownloaded(context, url)
        }
    }


    val downloadProgressMap by com.streamcloud.app.data.downloads.MusicDownloader.progressFlow
        .collectAsState(initial = emptyMap())
    LaunchedEffect(s.videoId, downloadProgressMap) {
        if (downloadProgressMap[com.streamcloud.app.data.ytmusic.YtPlayback.watchUrl(s.videoId)] == null) {
            downloaded = com.streamcloud.app.data.ytmusic.YtPlayback.isDownloaded(context, s)
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
        com.streamcloud.app.ui.components.SongRowMenu(song = s, onPlay = onClick)
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
    var showAll by remember { mutableStateOf(false) }
    val displayed = if (showAll) songs else songs.take(5)

    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )

    Column {
        displayed.forEach { s ->
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
                com.streamcloud.app.ui.components.SongRowMenu(song = s, onPlay = { onSongClick(s) })
            }
        }
        if (songs.size > 5) {
            TextButton(
                onClick = { showAll = !showAll },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(if (showAll) "Show less" else "View all ${songs.size} liked songs")
            }
        }
    }
}

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

