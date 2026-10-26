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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.data.spotify.SpotifyAlbum
import com.streamcloud.app.data.spotify.SpotifyAlbumDetails
import com.streamcloud.app.data.spotify.SpotifyArtist
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository
import com.streamcloud.app.data.spotify.SpotifyTrack
import com.streamcloud.app.data.ytmusic.YtMusicSearchRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SpotifyAlbumScreen(
    albumId: String,
    initialName: String,
    initialImageUrl: String?,
    onBack: () -> Unit,
    onOpenArtist: (SpotifyArtist) -> Unit,
    onOpenAlbum: (SpotifyAlbum) -> Unit,
) {
    val context = LocalContext.current
    val services = remember(context) { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val spotifyCookie by services.settings.spotifyCookie.collectAsState(initial = "")
    var details by remember(albumId) { mutableStateOf<SpotifyAlbumDetails?>(null) }
    var loading by remember(albumId) { mutableStateOf(true) }
    var error by remember(albumId) { mutableStateOf<String?>(null) }
    var refreshGeneration by remember(albumId) { mutableStateOf(0) }
    var playingTrackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(albumId, spotifyCookie, refreshGeneration) {
        if (spotifyCookie.isBlank()) {
            error = "Sign in to Spotify in Settings to view album pages."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            details = withContext(Dispatchers.IO) {
                SpotifyPlaylistRepository.getAlbumDetails(spotifyCookie, albumId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Spotify album could not be loaded."
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
                    YtPlayback.playSong(context, match.toSpotifyAlbumSong())
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

    Scaffold(containerColor = SpotifyBlack) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).background(SpotifyBlack)) {
            val loaded = details
            if (loaded != null) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 36.dp),
                ) {
                    item(key = "album-heading") {
                        Column(
                            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                                }
                                Text(
                                    "Album",
                                    Modifier.weight(1f),
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.size(48.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            AsyncImage(
                                model = loaded.album.imageUrl ?: initialImageUrl,
                                contentDescription = loaded.album.name.ifBlank { initialName },
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(250.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .background(Color(0xFF292929)),
                            )
                            Spacer(Modifier.height(22.dp))
                            Text(
                                loaded.album.name.ifBlank { initialName },
                                color = Color.White,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                loaded.artists.forEachIndexed { index, artist ->
                                    if (index > 0) {
                                        Text("·", color = Color.White.copy(alpha = 0.6f))
                                    }
                                    Text(
                                        artist.name,
                                        modifier = Modifier.clickable { onOpenArtist(artist) },
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                listOf(
                                    loaded.albumType.lowercase().replaceFirstChar { it.uppercase() },
                                    loaded.album.releaseDate.take(4),
                                    "${loaded.album.trackCount ?: loaded.tracks.size} songs",
                                ).filter(String::isNotBlank).joinToString(" · "),
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                color = Color.White.copy(alpha = 0.65f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                IconButton(
                                    onClick = { loaded.tracks.randomOrNull()?.let(::playTrack) },
                                    enabled = loaded.tracks.isNotEmpty() && playingTrackId == null,
                                ) {
                                    Icon(Icons.Default.Shuffle, "Shuffle album", tint = SpotifyGreen)
                                }
                                Box(
                                    Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(SpotifyGreen)
                                        .clickable { loaded.tracks.firstOrNull()?.let(::playTrack) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        "Play album",
                                        modifier = Modifier.size(36.dp),
                                        tint = Color.Black,
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        loaded.label.ifBlank { "Spotify" },
                                        color = Color.White.copy(alpha = 0.58f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (loaded.tracks.isNotEmpty()) {
                        items(loaded.tracks, key = { "spotify-album-track-${it.id}" }) { track ->
                            SpotifyDetailTrackRow(
                                track = track,
                                position = loaded.tracks.indexOf(track) + 1,
                                playing = playingTrackId == track.id,
                                onClick = { playTrack(track) },
                            )
                        }
                    } else {
                        item(key = "album-empty") {
                            Text(
                                "No tracks are available for this album.",
                                Modifier.fillMaxWidth().padding(22.dp),
                                color = Color.White.copy(alpha = 0.68f),
                            )
                        }
                    }
                    if (loaded.moreByArtist.isNotEmpty()) {
                        item(key = "more-by-artist") {
                            Column {
                                Text(
                                    "More by ${loaded.album.artists}",
                                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 12.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 18.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    items(loaded.moreByArtist, key = { "more-album-${it.id}" }) { album ->
                                        SpotifyDetailAlbumCard(album) { onOpenAlbum(album) }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    if (loading) {
                        CircularProgressIndicator(color = SpotifyGreen)
                    } else {
                        Text(
                            error ?: "Could not load ${initialName.ifBlank { "album" }}.",
                            Modifier.padding(horizontal = 28.dp),
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        TextButton(onClick = { refreshGeneration++ }) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.size(6.dp))
                            Text("Try again")
                        }
                    }
                }
            }
        }
    }
}

private fun YtTrack.toSpotifyAlbumSong() = YtmSong(
    videoId = url.substringAfter("v=").substringBefore("&")
        .ifBlank { url.substringAfterLast("/") },
    title = title,
    artist = uploader,
    album = null,
    thumbnail = thumbnail,
    durationSeconds = durationSec,
    isVideo = isVideo,
)
