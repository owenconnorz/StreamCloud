package com.streamcloud.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.data.spotify.SpotifyArtist
import com.streamcloud.app.data.spotify.SpotifyHomeFeed
import com.streamcloud.app.data.spotify.SpotifyHomeSection
import com.streamcloud.app.data.spotify.SpotifyTrack
import com.streamcloud.app.data.spotify.SpotifyWebPlayerRepository
import com.streamcloud.app.data.ytmusic.YtMusicSearchRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun YtTrack.asSpotifyHomeSong() = YtmSong(
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
internal fun SpotifyHomeContent(
    spotifyCookie: String,
    onOpenCollection: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf<SpotifyHomeFeed?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var playingTrackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(spotifyCookie, refreshGeneration) {
        if (spotifyCookie.isBlank()) {
            feed = null
            error = "Sign in to Spotify in Settings to see your personalized Home."
            loading = false
            return@LaunchedEffect
        }

        loading = true
        error = null
        try {
            feed = withContext(Dispatchers.IO) {
                SpotifyWebPlayerRepository.getHomeFeed(
                    spotifyCookie,
                    forceRefresh = refreshGeneration > 0,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            feed = null
            error = failure.message ?: "Spotify Home could not be loaded."
        } finally {
            loading = false
        }
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
                    YtPlayback.playSong(context, match.asSpotifyHomeSong())
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

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Spotify", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Picked for you",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
            ) {
                Text("Music", Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }

        if (loading && feed == null) {
            Row(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Loading your Spotify Home…")
            }
        } else if (error != null && feed == null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refreshGeneration++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Try again")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val home = feed
                if (home != null) {
                    itemsIndexed(
                        items = home.sections,
                        key = { index, section -> "${section.id}-$index" },
                    ) { _, section ->
                        SpotifyWebPlayerShelf(
                            section = section,
                            playingTrackId = playingTrackId,
                            onPlay = ::playTrack,
                            onOpenCollection = onOpenCollection,
                        )
                    }
                    if (home.sections.isEmpty()) {
                        item(key = "home-empty") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                                Text(
                                    "Spotify did not return any Home recommendations.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { refreshGeneration++ }) { Text("Refresh") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyWebPlayerShelf(
    section: SpotifyHomeSection,
    playingTrackId: String?,
    onPlay: (SpotifyTrack) -> Unit,
    onOpenCollection: (String, String) -> Unit,
) {
    Column {
        SpotifyShelfTitle(section.title, section.subtitle)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(section.items, key = { it.id }) { card ->
                val isTrack = card.track != null
                val canOpen = isTrack || card.collectionId != null
                Column(
                    Modifier.width(148.dp)
                        .clickable(enabled = canOpen) {
                            val track = card.track
                            if (track != null) onPlay(track)
                            else card.collectionId?.let { onOpenCollection(it, card.title) }
                        },
                ) {
                    Box(
                        Modifier.size(148.dp)
                            .clip(
                                if (card.collectionId?.startsWith("artist:") == true) {
                                    CircleShape
                                } else {
                                    RoundedCornerShape(12.dp)
                                },
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!card.imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = card.imageUrl,
                                contentDescription = card.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        card.track?.let { track ->
                            if (playingTrackId == track.id) {
                                CircularProgressIndicator(Modifier.size(30.dp))
                            } else {
                                Surface(
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    shape = CircleShape,
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Play ${card.title}",
                                        modifier = Modifier.padding(8.dp).size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        card.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        card.subtitle.ifBlank { "Spotify" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotifyArtistShelf(
    title: String,
    subtitle: String,
    artists: List<SpotifyArtist>,
    onOpenArtist: (String, String) -> Unit,
) {
    Column {
        SpotifyShelfTitle(title, subtitle)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(artists, key = { it.id }) { artist ->
                Column(
                    Modifier.width(148.dp).clickable { onOpenArtist(artist.id, artist.name) },
                ) {
                    Box(
                        Modifier.size(148.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!artist.imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = artist.imageUrl,
                                contentDescription = artist.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(topEnd = 8.dp),
                        ) {
                            Text(
                                "MIX",
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "${artist.name} Mix",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Spotify · personal mix",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotifyTrackShelf(
    title: String,
    subtitle: String,
    tracks: List<SpotifyTrack>,
    playingTrackId: String?,
    onPlay: (SpotifyTrack) -> Unit,
) {
    Column {
        SpotifyShelfTitle(title, subtitle)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(tracks, key = { "recommended-${it.id}" }) { track ->
                Column(
                    Modifier.width(148.dp)
                        .clickable(enabled = playingTrackId == null) { onPlay(track) },
                ) {
                    Box(
                        Modifier.size(148.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = track.imageUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (playingTrackId == track.id) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                        } else {
                            Surface(
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = CircleShape,
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play ${track.title}",
                                    modifier = Modifier.padding(9.dp).size(22.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        track.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        track.artists,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotifyRecentTrackRow(
    track: SpotifyTrack,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !loading, onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                track.artists,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.PlayArrow, contentDescription = "Play ${track.title}")
    }
}

@Composable
private fun SpotifyShelfTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
