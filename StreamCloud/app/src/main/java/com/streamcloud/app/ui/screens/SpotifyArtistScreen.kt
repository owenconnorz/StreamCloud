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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.data.spotify.SpotifyAlbum
import com.streamcloud.app.data.spotify.SpotifyArtist
import com.streamcloud.app.data.spotify.SpotifyArtistOverview
import com.streamcloud.app.data.spotify.SpotifyMusicVideo
import com.streamcloud.app.data.spotify.SpotifyPlaylist
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository
import com.streamcloud.app.data.spotify.SpotifyTrack
import com.streamcloud.app.data.ytmusic.YtMusicSearchRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ArtistTab(val title: String) {
    Music("Music"),
    Clips("Clips"),
}

@Composable
fun SpotifyArtistScreen(
    artistId: String,
    initialName: String,
    initialImageUrl: String?,
    onBack: () -> Unit,
    onOpenAlbum: (SpotifyAlbum) -> Unit,
    onOpenArtist: (SpotifyArtist) -> Unit,
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
) {
    val context = LocalContext.current
    val services = remember(context) { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val spotifyCookie by services.settings.spotifyCookie.collectAsState(initial = "")
    var overview by remember(artistId) { mutableStateOf<SpotifyArtistOverview?>(null) }
    var loading by remember(artistId) { mutableStateOf(true) }
    var error by remember(artistId) { mutableStateOf<String?>(null) }
    var refreshGeneration by remember(artistId) { mutableStateOf(0) }
    var selectedTab by remember(artistId) { mutableStateOf(ArtistTab.Music) }
    var following by remember(artistId) { mutableStateOf(false) }
    var followBusy by remember(artistId) { mutableStateOf(false) }
    var playingTrackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artistId, spotifyCookie, refreshGeneration) {
        if (spotifyCookie.isBlank()) {
            error = "Sign in to Spotify in Settings to view artist pages."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            overview = withContext(Dispatchers.IO) {
                SpotifyPlaylistRepository.getArtistOverview(spotifyCookie, artistId)
            }
            following = overview?.saved == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Spotify artist could not be loaded."
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
                    YtPlayback.playSong(context, match.toSpotifyDetailSong())
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

    fun toggleFollow() {
        if (followBusy || spotifyCookie.isBlank()) return
        val nextFollowed = !following
        scope.launch {
            followBusy = true
            try {
                withContext(Dispatchers.IO) {
                    SpotifyPlaylistRepository.setArtistFollowed(spotifyCookie, artistId, nextFollowed)
                }
                following = nextFollowed
                overview = overview?.copy(saved = nextFollowed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Toast.makeText(
                    context,
                    failure.message ?: "Could not update this artist.",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                followBusy = false
            }
        }
    }

    Scaffold(containerColor = SpotifyBlack) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).background(SpotifyBlack)) {
            val loaded = overview
            if (loaded != null) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 36.dp),
                ) {
                    item(key = "artist-hero") {
                        SpotifyArtistHero(
                            artist = loaded.artist,
                            fallbackName = initialName,
                            fallbackImageUrl = initialImageUrl,
                            headerImageUrl = loaded.headerImageUrl,
                            verified = loaded.verified,
                            onBack = onBack,
                        )
                    }
                    item(key = "artist-controls") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                if (loaded.monthlyListeners > 0L) {
                                    "${formatSpotifyCount(loaded.monthlyListeners)} monthly listeners"
                                } else {
                                    "Spotify artist"
                                },
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            OutlinedButton(
                                onClick = ::toggleFollow,
                                enabled = !followBusy,
                                contentPadding = PaddingValues(horizontal = 15.dp),
                            ) {
                                if (followBusy) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(if (following) "Following" else "Follow")
                                }
                            }
                            IconButton(
                                onClick = { loaded.topTracks.randomOrNull()?.let(::playTrack) },
                                enabled = loaded.topTracks.isNotEmpty() && playingTrackId == null,
                            ) {
                                Icon(Icons.Default.Shuffle, "Shuffle top tracks", tint = SpotifyGreen)
                            }
                            Box(
                                Modifier.size(54.dp).clip(CircleShape).background(SpotifyGreen)
                                    .clickable { loaded.topTracks.firstOrNull()?.let(::playTrack) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    "Play ${loaded.artist.name}",
                                    modifier = Modifier.size(34.dp),
                                    tint = Color.Black,
                                )
                            }
                        }
                    }
                    item(key = "artist-tabs") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            ArtistTab.entries.forEach { tab ->
                                Column(
                                    Modifier.clickable { selectedTab = tab }.padding(vertical = 11.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        tab.title,
                                        color = if (selectedTab == tab) Color.White else Color.White.copy(alpha = 0.65f),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    Spacer(
                                        Modifier.padding(top = 9.dp).width(40.dp).height(3.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(if (selectedTab == tab) SpotifyGreen else Color.Transparent),
                                    )
                                }
                            }
                        }
                    }
                    if (selectedTab == ArtistTab.Music) {
                        if (loaded.topTracks.isNotEmpty()) {
                            item(key = "popular-title") { SpotifyArtistSectionTitle("Popular") }
                            items(
                                items = loaded.topTracks.take(10),
                                key = { "artist-track-${it.id}" },
                            ) { track ->
                                SpotifyDetailTrackRow(
                                    track = track,
                                    position = loaded.topTracks.indexOf(track) + 1,
                                    showPlayCount = true,
                                    playing = playingTrackId == track.id,
                                    onClick = { playTrack(track) },
                                )
                            }
                        }
                        if (loaded.popularReleases.isNotEmpty()) {
                            item(key = "artist-releases") {
                                SpotifyArtistHorizontalSection("Popular releases") {
                                    items(loaded.popularReleases, key = { "release-${it.id}" }) { album ->
                                        SpotifyDetailAlbumCard(album) { onOpenAlbum(album) }
                                    }
                                }
                            }
                        }
                        if (loaded.featuringPlaylists.isNotEmpty()) {
                            item(key = "artist-featuring") {
                                SpotifyArtistHorizontalSection("Featuring ${loaded.artist.name}") {
                                    items(loaded.featuringPlaylists, key = { "featuring-${it.id}" }) { playlist ->
                                        SpotifyDetailPlaylistCard(playlist) { onOpenPlaylist(playlist) }
                                    }
                                }
                            }
                        }
                        if (loaded.clips.isNotEmpty()) {
                            item(key = "artist-clips-row") {
                                SpotifyArtistHorizontalSection("Clips from ${loaded.artist.name}") {
                                    items(loaded.clips, key = { "clip-${it.id}" }) { clip ->
                                        SpotifyArtistClipCard(clip)
                                    }
                                }
                            }
                        }
                        if (loaded.biography.isNotBlank()) {
                            item(key = "artist-about") {
                                SpotifyArtistAbout(loaded, onFollow = ::toggleFollow)
                            }
                        }
                        if (loaded.artistPlaylists.isNotEmpty()) {
                            item(key = "artist-playlists") {
                                SpotifyArtistHorizontalSection("Artist playlists") {
                                    items(loaded.artistPlaylists, key = { "artist-playlist-${it.id}" }) { playlist ->
                                        SpotifyDetailPlaylistCard(playlist) { onOpenPlaylist(playlist) }
                                    }
                                }
                            }
                        }
                        if (loaded.relatedArtists.isNotEmpty()) {
                            item(key = "related-artists") {
                                SpotifyArtistHorizontalSection("Fans also like") {
                                    items(loaded.relatedArtists, key = { "related-${it.id}" }) { artist ->
                                        SpotifyDetailArtistCard(artist) { onOpenArtist(artist) }
                                    }
                                }
                            }
                        }
                    } else if (loaded.clips.isNotEmpty()) {
                        item(key = "clips-tab") {
                            SpotifyArtistHorizontalSection("Clips from ${loaded.artist.name}") {
                                items(loaded.clips, key = { "clip-tab-${it.id}" }) { clip ->
                                    SpotifyArtistClipCard(clip)
                                }
                            }
                        }
                    } else {
                        item(key = "clips-empty") {
                            Text(
                                "No clips are available for this artist.",
                                Modifier.fillMaxWidth().padding(22.dp),
                                color = Color.White.copy(alpha = 0.68f),
                            )
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
                            error ?: "Could not load ${initialName.ifBlank { "artist" }}.",
                            Modifier.padding(horizontal = 28.dp),
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        TextButton(onClick = { refreshGeneration++ }) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Try again")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyArtistHero(
    artist: SpotifyArtist,
    fallbackName: String,
    fallbackImageUrl: String?,
    headerImageUrl: String?,
    verified: Boolean,
    onBack: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().height(370.dp).background(SpotifyBlack),
    ) {
        AsyncImage(
            model = headerImageUrl ?: artist.imageUrl ?: fallbackImageUrl,
            contentDescription = artist.name.ifBlank { fallbackName },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.12f),
                    0.48f to Color.Black.copy(alpha = 0.06f),
                    1f to SpotifyBlack,
                ),
            ),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 8.dp, top = 4.dp)
                .size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.34f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                artist.name.ifBlank { fallbackName },
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (verified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = Color(0xFFB5E8C8), modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Verified by Spotify", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SpotifyArtistSectionTitle(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 8.dp),
        color = Color.White,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SpotifyArtistHorizontalSection(
    title: String,
    content: LazyListScope.() -> Unit,
) {
    Column {
        SpotifyArtistSectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        Spacer(Modifier.height(5.dp))
    }
}

@Composable
private fun SpotifyArtistClipCard(clip: SpotifyMusicVideo) {
    Column(Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SpotifyDetailArtwork(
            imageUrl = clip.imageUrl,
            contentDescription = clip.title,
            modifier = Modifier.width(132.dp).height(206.dp),
        )
        Text(
            clip.title,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SpotifyArtistAbout(
    overview: SpotifyArtistOverview,
    onFollow: () -> Unit,
) {
    Column {
        SpotifyArtistSectionTitle("About")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color(0xFF242424)),
        ) {
            AsyncImage(
                model = overview.aboutImageUrl ?: overview.headerImageUrl ?: overview.artist.imageUrl,
                contentDescription = overview.artist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(230.dp),
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                overview.worldRank?.let { rank ->
                    Text("#$rank in Top Artists", color = Color.White.copy(alpha = 0.7f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        overview.artist.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (overview.verified) {
                        Icon(Icons.Default.Check, null, tint = Color(0xFFB5E8C8))
                    }
                    OutlinedButton(onClick = onFollow) {
                        Text(if (overview.saved) "Following" else "Follow")
                    }
                }
                if (overview.monthlyListeners > 0L) {
                    Text(
                        "${formatSpotifyCount(overview.monthlyListeners)} monthly listeners",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Text(
                    overview.biography,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                )
            }
        }
    }
}

private fun YtTrack.toSpotifyDetailSong() = YtmSong(
    videoId = url.substringAfter("v=").substringBefore("&")
        .ifBlank { url.substringAfterLast("/") },
    title = title,
    artist = uploader,
    album = null,
    thumbnail = thumbnail,
    durationSeconds = durationSec,
    isVideo = isVideo,
)
