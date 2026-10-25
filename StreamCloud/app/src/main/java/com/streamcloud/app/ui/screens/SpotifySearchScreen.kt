package com.streamcloud.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.data.spotify.SpotifyAlbum
import com.streamcloud.app.data.spotify.SpotifyArtist
import com.streamcloud.app.data.spotify.SpotifyEpisode
import com.streamcloud.app.data.spotify.SpotifyPlaylist
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository.RateLimitedException
import com.streamcloud.app.data.spotify.SpotifyPodcast
import com.streamcloud.app.data.spotify.SpotifySearchResults
import com.streamcloud.app.data.spotify.SpotifyTrack
import com.streamcloud.app.data.ytmusic.YtMusicSearchRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SpotifySearchFilter(val label: String) {
    All("All"),
    Tracks("Tracks"),
    Artists("Artists"),
    Albums("Albums"),
    Playlists("Playlists"),
    Podcasts("Podcasts"),
    Episodes("Episodes"),
}

private fun formatSpotifyWait(remainingMs: Long): String {
    val totalMinutes = ((remainingMs.coerceAtLeast(0L) + 59_999L) / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun YtTrack.asSpotifySearchSong() = YtmSong(
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
fun SpotifySearchScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (id: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val services = remember(context) { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val spotifyCookie by services.settings.spotifyCookie.collectAsState(initial = "")
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var searchGeneration by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<SpotifySearchResults?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rateLimitUntilMs by remember { mutableStateOf(0L) }
    var currentTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var filter by remember { mutableStateOf(SpotifySearchFilter.All) }
    var playingTrackId by remember { mutableStateOf<String?>(null) }

    fun submitSearch() {
        val cleanQuery = query.trim()
        if (
            loading ||
            cleanQuery.length < 2 ||
            rateLimitUntilMs > System.currentTimeMillis()
        ) return
        keyboardController?.hide()
        submittedQuery = cleanQuery
        searchGeneration += 1
        results = null
        error = null
        rateLimitUntilMs = 0L
    }

    LaunchedEffect(submittedQuery, spotifyCookie, searchGeneration) {
        val cleanQuery = submittedQuery.trim()
        if (cleanQuery.length < 2) {
            results = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        if (spotifyCookie.isBlank()) {
            results = null
            error = "Sign in to Spotify in Settings to search its catalogue."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            results = withContext(Dispatchers.IO) {
                SpotifyPlaylistRepository.searchCatalog(spotifyCookie, cleanQuery)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RateLimitedException) {
            results = null
            rateLimitUntilMs = failure.retryAtMs
        } catch (failure: Exception) {
            results = null
            error = failure.message ?: "Spotify search could not be loaded."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(rateLimitUntilMs) {
        while (rateLimitUntilMs > 0L && System.currentTimeMillis() < rateLimitUntilMs) {
            currentTimeMs = System.currentTimeMillis()
            delay(1_000L)
        }
        currentTimeMs = System.currentTimeMillis()
    }

    fun playTrack(track: SpotifyTrack) {
        if (playingTrackId != null) return
        scope.launch {
            playingTrackId = track.id
            try {
                val match = withContext(Dispatchers.IO) {
                    YtMusicSearchRepository.songs("${track.title} ${track.artists}").firstOrNull()
                }
                if (match == null) {
                    Toast.makeText(
                        context,
                        "\"${track.title}\" was not found on YouTube Music.",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    YtPlayback.playSong(context, match.asSpotifySearchSong())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "Could not play this song.", Toast.LENGTH_SHORT).show()
            } finally {
                playingTrackId = null
            }
        }
    }

    fun openSpotifyUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "Could not open Spotify.", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Spotify search",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (loading) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    results = null
                    error = null
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("What do you want to listen to?") },
                leadingIcon = {
                    IconButton(
                        onClick = ::submitSearch,
                        enabled = !loading &&
                            query.trim().length >= 2 &&
                            rateLimitUntilMs <= System.currentTimeMillis(),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search Spotify")
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty() && !loading) {
                        IconButton(onClick = {
                            query = ""
                            submittedQuery = ""
                            results = null
                            error = null
                            rateLimitUntilMs = 0L
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                enabled = !loading,
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = ::submitSearch),
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpotifySearchFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }

            when {
                spotifyCookie.isBlank() -> SpotifySearchMessage(
                    "Sign in to Spotify in Settings to search its catalogue.",
                )
                query.trim().length < 2 -> SpotifySearchMessage(
                    "Search Spotify for songs, artists, albums, playlists and podcasts.",
                )
                rateLimitUntilMs > 0L && currentTimeMs < rateLimitUntilMs -> SpotifySearchMessage(
                    "Spotify has temporarily limited requests. Try again in " +
                        "${formatSpotifyWait(rateLimitUntilMs - currentTimeMs)}.",
                )
                query.trim() != submittedQuery -> SpotifySearchMessage(
                    "Tap the search icon or Search on the keyboard to load results.",
                )
                loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                rateLimitUntilMs > 0L -> Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Spotify's cooldown has ended.")
                    TextButton(onClick = ::submitSearch) { Text("Try again") }
                }
                error != null -> Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = ::submitSearch) { Text("Try again") }
                }
                results != null -> SpotifySearchResultsList(
                    results = results!!,
                    filter = filter,
                    playingTrackId = playingTrackId,
                    onPlayTrack = ::playTrack,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenSpotifyUrl = ::openSpotifyUrl,
                )
            }
        }
    }
}

@Composable
private fun SpotifySearchMessage(message: String) {
    Box(
        Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SpotifySearchResultsList(
    results: SpotifySearchResults,
    filter: SpotifySearchFilter,
    playingTrackId: String?,
    onPlayTrack: (SpotifyTrack) -> Unit,
    onOpenPlaylist: (String, String) -> Unit,
    onOpenSpotifyUrl: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (filter) {
            SpotifySearchFilter.All -> {
                if (results.tracks.isNotEmpty()) {
                    item { SpotifySearchSectionTitle("Songs") }
                    items(results.tracks.take(6), key = { "track-${it.id}" }) { track ->
                        SpotifyTrackSearchRow(track, playingTrackId == track.id) { onPlayTrack(track) }
                    }
                }
                if (results.artists.isNotEmpty()) {
                    item { SpotifySearchSectionTitle("Artists") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(results.artists.take(10), key = { "artist-${it.id}" }) { artist ->
                                SpotifySearchCircleCard(artist.name, artist.imageUrl) {
                                    onOpenSpotifyUrl("https://open.spotify.com/artist/${artist.id}")
                                }
                            }
                        }
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item { SpotifySearchSectionTitle("Albums") }
                    items(results.albums.take(8), key = { "album-${it.id}" }) { album ->
                        SpotifySearchEntityRow(
                            imageUrl = album.imageUrl,
                            title = album.name,
                            subtitle = listOf(album.artists, album.releaseDate.take(4))
                                .filter(String::isNotBlank).joinToString(" · "),
                            kind = "Album",
                        ) { onOpenSpotifyUrl("https://open.spotify.com/album/${album.id}") }
                    }
                }
                if (results.playlists.isNotEmpty()) {
                    item { SpotifySearchSectionTitle("Playlists") }
                    items(results.playlists.take(8), key = { "playlist-${it.id}" }) { playlist ->
                        SpotifySearchEntityRow(
                            imageUrl = playlist.imageUrl,
                            title = playlist.name,
                            subtitle = playlist.ownerName.ifBlank { "Spotify playlist" },
                            kind = "Playlist",
                        ) { onOpenPlaylist(playlist.id, playlist.name) }
                    }
                }
                if (results.podcasts.isNotEmpty()) {
                    item { SpotifySearchSectionTitle("Podcasts") }
                    items(results.podcasts.take(6), key = { "podcast-${it.id}" }) { podcast ->
                        SpotifySearchEntityRow(
                            imageUrl = podcast.imageUrl,
                            title = podcast.name,
                            subtitle = podcast.publisher,
                            kind = "Podcast",
                        ) { onOpenSpotifyUrl("https://open.spotify.com/show/${podcast.id}") }
                    }
                }
                if (results.episodes.isNotEmpty()) {
                    item { SpotifySearchSectionTitle("Episodes") }
                    items(results.episodes.take(8), key = { "episode-${it.id}" }) { episode ->
                        SpotifySearchEntityRow(
                            imageUrl = episode.imageUrl,
                            title = episode.name,
                            subtitle = episode.podcast,
                            kind = "Episode",
                        ) { onOpenSpotifyUrl("https://open.spotify.com/episode/${episode.id}") }
                    }
                }
                if (results.tracks.isEmpty() && results.artists.isEmpty() &&
                    results.albums.isEmpty() && results.playlists.isEmpty() &&
                    results.podcasts.isEmpty() && results.episodes.isEmpty()
                ) {
                    item { SpotifySearchMessage("No Spotify results found.") }
                }
            }
            SpotifySearchFilter.Tracks -> {
                if (results.tracks.isEmpty()) item { SpotifySearchMessage("No songs found.") }
                items(results.tracks, key = { "track-${it.id}" }) { track ->
                    SpotifyTrackSearchRow(track, playingTrackId == track.id) { onPlayTrack(track) }
                }
            }
            SpotifySearchFilter.Artists -> {
                if (results.artists.isEmpty()) item { SpotifySearchMessage("No artists found.") }
                items(results.artists, key = { "artist-${it.id}" }) { artist ->
                    SpotifySearchEntityRow(
                        imageUrl = artist.imageUrl,
                        title = artist.name,
                        subtitle = "Artist",
                        kind = "Artist",
                        circleImage = true,
                    ) { onOpenSpotifyUrl("https://open.spotify.com/artist/${artist.id}") }
                }
            }
            SpotifySearchFilter.Albums -> {
                if (results.albums.isEmpty()) item { SpotifySearchMessage("No albums found.") }
                items(results.albums, key = { "album-${it.id}" }) { album ->
                    SpotifyAlbumSearchRow(album) {
                        onOpenSpotifyUrl("https://open.spotify.com/album/${album.id}")
                    }
                }
            }
            SpotifySearchFilter.Playlists -> {
                if (results.playlists.isEmpty()) item { SpotifySearchMessage("No playlists found.") }
                items(results.playlists, key = { "playlist-${it.id}" }) { playlist ->
                    SpotifyPlaylistSearchRow(playlist) {
                        onOpenPlaylist(playlist.id, playlist.name)
                    }
                }
            }
            SpotifySearchFilter.Podcasts -> {
                if (results.podcasts.isEmpty()) item { SpotifySearchMessage("No podcasts found.") }
                items(results.podcasts, key = { "podcast-${it.id}" }) { podcast ->
                    SpotifyPodcastSearchRow(podcast) {
                        onOpenSpotifyUrl("https://open.spotify.com/show/${podcast.id}")
                    }
                }
            }
            SpotifySearchFilter.Episodes -> {
                if (results.episodes.isEmpty()) item { SpotifySearchMessage("No episodes found.") }
                items(results.episodes, key = { "episode-${it.id}" }) { episode ->
                    SpotifyEpisodeSearchRow(episode) {
                        onOpenSpotifyUrl("https://open.spotify.com/episode/${episode.id}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifySearchSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SpotifyTrackSearchRow(
    track: SpotifyTrack,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpotifySearchArtwork(track.imageUrl)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                listOf(track.artists, track.album).filter(String::isNotBlank).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.PlayArrow, contentDescription = "Play ${track.title}", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SpotifyAlbumSearchRow(album: SpotifyAlbum, onClick: () -> Unit) =
    SpotifySearchEntityRow(
        imageUrl = album.imageUrl,
        title = album.name,
        subtitle = listOf(album.artists, album.releaseDate.take(4))
            .filter(String::isNotBlank).joinToString(" · "),
        kind = "Album",
        onClick = onClick,
    )

@Composable
private fun SpotifyPlaylistSearchRow(playlist: SpotifyPlaylist, onClick: () -> Unit) =
    SpotifySearchEntityRow(
        imageUrl = playlist.imageUrl,
        title = playlist.name,
        subtitle = playlist.ownerName.ifBlank { "${playlist.trackCount ?: 0} songs" },
        kind = "Playlist",
        onClick = onClick,
    )

@Composable
private fun SpotifyPodcastSearchRow(podcast: SpotifyPodcast, onClick: () -> Unit) =
    SpotifySearchEntityRow(
        imageUrl = podcast.imageUrl,
        title = podcast.name,
        subtitle = podcast.publisher,
        kind = "Podcast",
        onClick = onClick,
    )

@Composable
private fun SpotifyEpisodeSearchRow(episode: SpotifyEpisode, onClick: () -> Unit) =
    SpotifySearchEntityRow(
        imageUrl = episode.imageUrl,
        title = episode.name,
        subtitle = episode.podcast,
        kind = "Episode",
        onClick = onClick,
    )

@Composable
private fun SpotifySearchEntityRow(
    imageUrl: String?,
    title: String,
    subtitle: String,
    kind: String,
    circleImage: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpotifySearchArtwork(imageUrl, circleImage)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                listOf(kind, subtitle).filter(String::isNotBlank).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SpotifySearchCircleCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(112.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SpotifySearchArtwork(imageUrl, circle = true, modifier = Modifier.size(104.dp))
        Text(
            title,
            Modifier.padding(top = 6.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SpotifySearchArtwork(
    imageUrl: String?,
    circle: Boolean = false,
    modifier: Modifier = Modifier.size(54.dp),
) {
    Box(
        modifier.clip(
            if (circle) androidx.compose.foundation.shape.CircleShape
            else RoundedCornerShape(7.dp),
        ).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(25.dp),
            )
        }
    }
}
