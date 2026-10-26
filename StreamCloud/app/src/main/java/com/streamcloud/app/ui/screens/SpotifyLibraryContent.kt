package com.streamcloud.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.data.spotify.SpotifyAlbum
import com.streamcloud.app.data.spotify.SpotifyArtist
import com.streamcloud.app.data.spotify.SpotifyPlaylist
import com.streamcloud.app.data.spotify.SpotifyTrack
import com.streamcloud.app.data.spotify.SpotifyWebPlayerRepository
import com.streamcloud.app.data.ytmusic.YtMusicSearchRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SpotifyLibraryTab(val label: String) {
    Playlists("Playlists"),
    Albums("Albums"),
    Artists("Artists"),
    Songs("Liked songs"),
}

private fun YtTrack.asSpotifyMatchSong() = YtmSong(
    videoId = url.substringAfter("v=").substringBefore("&")
        .ifBlank { url.substringAfterLast("/") },
    title = title,
    artist = uploader,
    album = null,
    thumbnail = thumbnail,
    durationSeconds = durationSec,
    isVideo = isVideo,
)

@Composable
internal fun SpotifyLibraryContent(
    spotifyCookie: String,
    onOpenCollection: (id: String, title: String) -> Unit,
    refreshGeneration: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(SpotifyLibraryTab.Playlists) }
    var gridView by rememberSaveable { mutableStateOf(false) }
    var retryGeneration by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<SpotifyAlbum>>(emptyList()) }
    var artists by remember { mutableStateOf<List<SpotifyArtist>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var loadedTabs by remember { mutableStateOf<Set<SpotifyLibraryTab>>(emptySet()) }
    var nextOffsets by remember { mutableStateOf<Map<SpotifyLibraryTab, Int?>>(emptyMap()) }
    var loadingMoreTab by remember { mutableStateOf<SpotifyLibraryTab?>(null) }
    var loadMoreError by remember { mutableStateOf<Pair<SpotifyLibraryTab, String>?>(null) }
    var playingTrack by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(spotifyCookie, refreshGeneration) {
        if (refreshGeneration > 0) selectedTab = SpotifyLibraryTab.Playlists
        loadedTabs = emptySet()
        nextOffsets = emptyMap()
        loadMoreError = null
        playlists = emptyList()
        albums = emptyList()
        artists = emptyList()
        tracks = emptyList()
        error = null
    }

    LaunchedEffect(spotifyCookie, selectedTab, retryGeneration, refreshGeneration) {
        if (spotifyCookie.isBlank()) {
            error = "Sign in to Spotify in Settings to view your account library."
            loading = false
            return@LaunchedEffect
        }
        if (selectedTab in loadedTabs) return@LaunchedEffect

        loading = true
        error = null
        try {
            when (selectedTab) {
                SpotifyLibraryTab.Playlists -> {
                    val page = withContext(Dispatchers.IO) {
                        SpotifyWebPlayerRepository.getPlaylistsPage(
                            spotifyCookie,
                            forceRefresh = retryGeneration > 0 || refreshGeneration > 0,
                        )
                    }
                    playlists = page.items
                    nextOffsets = nextOffsets + (selectedTab to page.nextOffset)
                }
                SpotifyLibraryTab.Albums -> {
                    val page = withContext(Dispatchers.IO) {
                        SpotifyWebPlayerRepository.getAlbumsPage(
                            spotifyCookie,
                            forceRefresh = retryGeneration > 0 || refreshGeneration > 0,
                        )
                    }
                    albums = page.items
                    nextOffsets = nextOffsets + (selectedTab to page.nextOffset)
                }
                SpotifyLibraryTab.Artists -> {
                    val page = withContext(Dispatchers.IO) {
                        SpotifyWebPlayerRepository.getArtistsPage(
                            spotifyCookie,
                            forceRefresh = retryGeneration > 0 || refreshGeneration > 0,
                        )
                    }
                    artists = page.items
                    nextOffsets = nextOffsets + (selectedTab to page.nextOffset)
                }
                SpotifyLibraryTab.Songs -> {
                    val page = withContext(Dispatchers.IO) {
                        SpotifyWebPlayerRepository.getSavedTracksPage(
                            spotifyCookie,
                            forceRefresh = retryGeneration > 0 || refreshGeneration > 0,
                        )
                    }
                    tracks = page.items
                    nextOffsets = nextOffsets + (selectedTab to page.nextOffset)
                }
            }
            loadMoreError = null
            loadedTabs = loadedTabs + selectedTab
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Spotify library could not be loaded."
        } finally {
            loading = false
        }
    }

    fun loadMoreSelectedTab() {
        val tab = selectedTab
        val offset = nextOffsets[tab] ?: return
        if (loadingMoreTab != null) return
        loadingMoreTab = tab
        loadMoreError = null
        scope.launch {
            try {
                when (tab) {
                    SpotifyLibraryTab.Playlists -> {
                        val page = withContext(Dispatchers.IO) {
                            SpotifyWebPlayerRepository.getPlaylistsPage(spotifyCookie, offset)
                        }
                        playlists = (playlists + page.items).distinctBy { it.id }
                        nextOffsets = nextOffsets + (tab to page.nextOffset)
                    }
                    SpotifyLibraryTab.Albums -> {
                        val page = withContext(Dispatchers.IO) {
                            SpotifyWebPlayerRepository.getAlbumsPage(spotifyCookie, offset)
                        }
                        albums = (albums + page.items).distinctBy { it.id }
                        nextOffsets = nextOffsets + (tab to page.nextOffset)
                    }
                    SpotifyLibraryTab.Artists -> {
                        val page = withContext(Dispatchers.IO) {
                            SpotifyWebPlayerRepository.getArtistsPage(spotifyCookie, offset)
                        }
                        artists = (artists + page.items).distinctBy { it.id }
                        nextOffsets = nextOffsets + (tab to page.nextOffset)
                    }
                    SpotifyLibraryTab.Songs -> {
                        val page = withContext(Dispatchers.IO) {
                            SpotifyWebPlayerRepository.getSavedTracksPage(spotifyCookie, offset)
                        }
                        tracks = (tracks + page.items).distinctBy { it.id }
                        nextOffsets = nextOffsets + (tab to page.nextOffset)
                    }
                }
                loadMoreError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                loadMoreError = tab to (failure.message ?: "More Spotify items could not be loaded.")
            } finally {
                loadingMoreTab = null
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpotifyLibraryTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    FilterChip(
                        selected = selected,
                        onClick = {
                            retryGeneration = 0
                            loadMoreError = null
                            selectedTab = tab
                        },
                        label = {
                            Text(
                                tab.label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
            IconButton(
                onClick = { gridView = !gridView },
                modifier = Modifier.padding(end = 12.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            ) {
                Icon(
                    if (gridView) Icons.Default.ViewList else Icons.Default.GridView,
                    contentDescription = if (gridView) "Switch to list view" else "Switch to grid view",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (loading) {
            Row(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    color = SpotifyGreen,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Loading Spotify ${selectedTab.label.lowercase()}…",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        } else if (error != null) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    loadedTabs = loadedTabs - selectedTab
                    retryGeneration++
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        } else if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (selectedTab) {
                    SpotifyLibraryTab.Playlists -> {
                        if (playlists.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SpotifyEmptyMessage("No playlists were returned for this Spotify account.") {
                                loadedTabs = loadedTabs - selectedTab
                                retryGeneration++
                            }
                        }
                        gridItems(playlists, key = { "playlist-${it.id}" }) { playlist ->
                            SpotifyCollectionGridTile(
                                imageUrl = playlist.imageUrl,
                                title = playlist.name,
                                subtitle = listOfNotNull(
                                    playlist.trackCount?.let { "$it tracks" },
                                    playlist.ownerName.takeIf(String::isNotBlank)?.let { "by $it" },
                                ).joinToString(" · "),
                                icon = Icons.Default.QueueMusic,
                                onClick = { onOpenCollection(playlist.id, playlist.name) },
                            )
                        }
                    }
                    SpotifyLibraryTab.Albums -> {
                        if (albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SpotifyEmptyMessage("No saved albums were returned for this Spotify account.") {
                                loadedTabs = loadedTabs - selectedTab
                                retryGeneration++
                            }
                        }
                        gridItems(albums, key = { "album-${it.id}" }) { album ->
                            SpotifyCollectionGridTile(
                                imageUrl = album.imageUrl,
                                title = album.name,
                                subtitle = listOfNotNull(
                                    album.artists.takeIf(String::isNotBlank),
                                    album.trackCount?.let { "$it tracks" },
                                    album.releaseDate.takeIf(String::isNotBlank),
                                ).joinToString(" · "),
                                icon = Icons.Default.Album,
                                onClick = { onOpenCollection("album:${album.id}", album.name) },
                            )
                        }
                    }
                    SpotifyLibraryTab.Artists -> {
                        if (artists.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SpotifyEmptyMessage("No followed artists were returned for this Spotify account.") {
                                loadedTabs = loadedTabs - selectedTab
                                retryGeneration++
                            }
                        }
                        gridItems(artists, key = { "artist-${it.id}" }) { artist ->
                            SpotifyCollectionGridTile(
                                imageUrl = artist.imageUrl,
                                title = artist.name,
                                subtitle = artist.followers.takeIf { it > 0L }?.let {
                                    "%,d followers".format(it)
                                }.orEmpty(),
                                icon = Icons.Default.Person,
                                circularImage = true,
                            )
                        }
                    }
                    SpotifyLibraryTab.Songs -> {
                        if (tracks.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SpotifyEmptyMessage("No liked songs were returned for this Spotify account.") {
                                loadedTabs = loadedTabs - selectedTab
                                retryGeneration++
                            }
                        }
                        gridItems(tracks, key = { "track-${it.id}" }) { track ->
                            SpotifyTrackGridTile(
                                track = track,
                                loading = playingTrack == track.id,
                                onClick = {
                                    if (playingTrack == null) {
                                        scope.launch {
                                            playingTrack = track.id
                                            try {
                                                val match = withContext(Dispatchers.IO) {
                                                    YtMusicSearchRepository.songs("${track.title} ${track.artists}")
                                                        .firstOrNull()
                                                }
                                                if (match == null) {
                                                    Toast.makeText(
                                                        context,
                                                        "\"${track.title}\" was not found on YouTube Music.",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                } else {
                                                    YtPlayback.playSong(context, match.asSpotifyMatchSong())
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "Could not play this song.", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                playingTrack = null
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                if (nextOffsets[selectedTab] != null) {
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = "spotify-grid-load-more-${selectedTab.name}",
                    ) {
                        TextButton(
                            onClick = ::loadMoreSelectedTab,
                            enabled = loadingMoreTab == null,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            if (loadingMoreTab == selectedTab) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (loadingMoreTab == selectedTab) "Loading more…" else "Load more")
                        }
                    }
                }
                loadMoreError?.takeIf { it.first == selectedTab }?.let { (_, message) ->
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = "spotify-grid-load-more-error-${selectedTab.name}",
                    ) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = ::loadMoreSelectedTab) { Text("Try again") }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (selectedTab) {
                    SpotifyLibraryTab.Playlists -> {
                        if (playlists.isEmpty()) item {
                            SpotifyEmptyMessage("No playlists were returned for this Spotify account.") {
                                loadedTabs = loadedTabs - selectedTab
                                retryGeneration++
                            }
                        }
                        items(playlists, key = { "playlist-${it.id}" }) { playlist ->
                            SpotifyCollectionRow(
                                imageUrl = playlist.imageUrl,
                                title = playlist.name,
                                subtitle = listOfNotNull(
                                    playlist.trackCount?.let { "$it tracks" },
                                    playlist.ownerName.takeIf(String::isNotBlank)?.let { "by $it" },
                                ).joinToString(" · "),
                                detail = playlist.description,
                                icon = Icons.Default.QueueMusic,
                                onClick = { onOpenCollection(playlist.id, playlist.name) },
                            )
                        }
                    }
                    SpotifyLibraryTab.Albums -> {
                        if (albums.isEmpty()) item {
                            SpotifyEmptyMessage("No saved albums were returned for this Spotify account.") {
                                loadedTabs = loadedTabs - selectedTab
                                retryGeneration++
                            }
                        }
                        items(albums, key = { "album-${it.id}" }) { album ->
                            SpotifyCollectionRow(
                                imageUrl = album.imageUrl,
                                title = album.name,
                                subtitle = listOfNotNull(
                                    album.artists.takeIf(String::isNotBlank),
                                    album.trackCount?.let { "$it tracks" },
                                    album.releaseDate.takeIf(String::isNotBlank),
                                ).joinToString(" · "),
                                icon = Icons.Default.Album,
                                onClick = { onOpenCollection("album:${album.id}", album.name) },
                            )
                        }
                    }
                    SpotifyLibraryTab.Artists -> {
                        if (artists.isEmpty()) item {
                            SpotifyEmptyMessage("No followed artists were returned for this Spotify account.") {
                                loadedTabs = loadedTabs - selectedTab
                                retryGeneration++
                            }
                        }
                        items(artists, key = { "artist-${it.id}" }) { artist ->
                            SpotifyCollectionRow(
                                imageUrl = artist.imageUrl,
                                title = artist.name,
                                subtitle = artist.followers.takeIf { it > 0L }?.let {
                                    "%,d followers".format(it)
                                }.orEmpty(),
                                icon = Icons.Default.Person,
                            )
                        }
                    }
                    SpotifyLibraryTab.Songs -> {
                        if (tracks.isEmpty()) item {
                            SpotifyEmptyMessage("No liked songs were returned for this Spotify account.") {
                                loadedTabs = loadedTabs - selectedTab
                                retryGeneration++
                            }
                        }
                        items(tracks, key = { "track-${it.id}" }) { track ->
                            SpotifyTrackRow(
                                track = track,
                                loading = playingTrack == track.id,
                                onClick = {
                                    if (playingTrack == null) {
                                        scope.launch {
                                            playingTrack = track.id
                                            try {
                                                val match = withContext(Dispatchers.IO) {
                                                    YtMusicSearchRepository.songs("${track.title} ${track.artists}")
                                                        .firstOrNull()
                                                }
                                                if (match == null) {
                                                    Toast.makeText(
                                                        context,
                                                        "\"${track.title}\" was not found on YouTube Music.",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                } else {
                                                    YtPlayback.playSong(context, match.asSpotifyMatchSong())
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "Could not play this song.", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                playingTrack = null
                                            }
                                        }
                                    }
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
                if (nextOffsets[selectedTab] != null) {
                    item(key = "spotify-library-load-more-${selectedTab.name}") {
                        TextButton(
                            onClick = ::loadMoreSelectedTab,
                            enabled = loadingMoreTab == null,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            if (loadingMoreTab == selectedTab) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (loadingMoreTab == selectedTab) "Loading more…" else "Load more")
                        }
                    }
                }
                loadMoreError?.takeIf { it.first == selectedTab }?.let { (_, message) ->
                    item(key = "spotify-library-load-more-error-${selectedTab.name}") {
                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = ::loadMoreSelectedTab) { Text("Try again") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyEmptyMessage(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp)) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text("Refresh") }
    }
}

@Composable
private fun SpotifyCollectionRow(
    imageUrl: String?,
    title: String,
    subtitle: String,
    detail: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.size(68.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(68.dp).padding(18.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Open $title", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SpotifyCollectionGridTile(
    imageUrl: String?,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    circularImage: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(if (circularImage) CircleShape else RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SpotifyTrackGridTile(
    track: SpotifyTrack,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!track.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (loading) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play ${track.title}",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(8.dp)
                        .size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            track.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artists,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpotifyTrackRow(track: SpotifyTrack, loading: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                track.title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(track.artists, track.album).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Icon(
            Icons.Default.PlayArrow,
            contentDescription = "Play ${track.title}",
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
