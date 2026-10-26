package com.streamcloud.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.streamcloud.app.data.spotify.SpotifyAlbum
import com.streamcloud.app.data.spotify.SpotifyArtist
import com.streamcloud.app.data.spotify.SpotifyEpisode
import com.streamcloud.app.data.spotify.SpotifyPlaylist
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository.RateLimitedException
import com.streamcloud.app.data.spotify.SpotifyPodcast
import com.streamcloud.app.data.spotify.SpotifyTrack
import com.streamcloud.app.data.spotify.SpotifyWebPlayerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed class SpotifySearchMenuTarget {
    abstract val title: String
    abstract val subtitle: String
    abstract val imageUrl: String?
    abstract val kind: String

    data class Track(val value: SpotifyTrack) : SpotifySearchMenuTarget() {
        override val title get() = value.title
        override val subtitle get() = listOf(value.artists, value.album)
            .filter(String::isNotBlank).joinToString(" · ")
        override val imageUrl get() = value.imageUrl
        override val kind = "Song"
    }

    data class Artist(val value: SpotifyArtist) : SpotifySearchMenuTarget() {
        override val title get() = value.name
        override val subtitle = "Artist"
        override val imageUrl get() = value.imageUrl
        override val kind = "Artist"
    }

    data class Album(val value: SpotifyAlbum) : SpotifySearchMenuTarget() {
        override val title get() = value.name
        override val subtitle get() = value.artists
        override val imageUrl get() = value.imageUrl
        override val kind = "Album"
    }

    data class Playlist(val value: SpotifyPlaylist) : SpotifySearchMenuTarget() {
        override val title get() = value.name
        override val subtitle get() = value.ownerName
        override val imageUrl get() = value.imageUrl
        override val kind = "Playlist"
    }

    data class Podcast(val value: SpotifyPodcast) : SpotifySearchMenuTarget() {
        override val title get() = value.name
        override val subtitle get() = value.publisher
        override val imageUrl get() = value.imageUrl
        override val kind = "Podcast"
    }

    data class Episode(val value: SpotifyEpisode) : SpotifySearchMenuTarget() {
        override val title get() = value.name
        override val subtitle get() = value.podcast
        override val imageUrl get() = value.imageUrl
        override val kind = "Episode"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpotifySearchActionSheets(
    target: SpotifySearchMenuTarget?,
    onDismissTarget: () -> Unit,
    spotifyCookie: String,
    onPlayTrack: (SpotifyTrack) -> Unit,
    onOpenPlaylist: (String, String) -> Unit,
    onOpenArtist: (SpotifyArtist) -> Unit,
    onOpenAlbum: (SpotifyAlbum) -> Unit,
    onOpenSpotifyUrl: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var likedTrack by remember { mutableStateOf<Boolean?>(null) }
    var likedTrackLoading by remember { mutableStateOf(false) }
    var addToPlaylistTrack by remember { mutableStateOf<SpotifyTrack?>(null) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var nextPlaylistOffset by remember { mutableStateOf<Int?>(null) }
    var playlistsLoading by remember { mutableStateOf(false) }
    var playlistsError by remember { mutableStateOf<String?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }
    var addingToPlaylistId by remember { mutableStateOf<String?>(null) }

    fun loadPlaylists(reset: Boolean) {
        if (playlistsLoading) return
        val offset = if (reset) 0 else nextPlaylistOffset ?: return
        if (spotifyCookie.isBlank()) {
            playlistsError = "Sign in to Spotify in Settings to load your playlists."
            playlists = emptyList()
            return
        }
        scope.launch {
            playlistsLoading = true
            playlistsError = null
            try {
                val page = withContext(Dispatchers.IO) {
                    SpotifyWebPlayerRepository.getPlaylistsPage(spotifyCookie, offset)
                }
                playlists = if (reset) page.items.distinctBy { it.id }
                else (playlists + page.items).distinctBy { it.id }
                nextPlaylistOffset = page.nextOffset
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                playlistsError = failure.message ?: "Your Spotify playlists could not be loaded."
            } finally {
                playlistsLoading = false
            }
        }
    }

    LaunchedEffect((target as? SpotifySearchMenuTarget.Track)?.value?.id, spotifyCookie) {
        val track = (target as? SpotifySearchMenuTarget.Track)?.value
            ?: return@LaunchedEffect
        likedTrack = null
        likedTrackLoading = true
        try {
            likedTrack = withContext(Dispatchers.IO) {
                SpotifyPlaylistRepository.isTrackSaved(spotifyCookie, track.id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep actions available if the REST endpoint is in its own cooldown.
            likedTrack = null
        } finally {
            if ((target as? SpotifySearchMenuTarget.Track)?.value?.id == track.id) {
                likedTrackLoading = false
            }
        }
    }

    LaunchedEffect(addToPlaylistTrack?.id, spotifyCookie) {
        if (addToPlaylistTrack == null) {
            playlists = emptyList()
            nextPlaylistOffset = null
            playlistsError = null
            addError = null
        } else {
            playlists = emptyList()
            nextPlaylistOffset = null
            addError = null
            loadPlaylists(reset = true)
        }
    }

    fun share(target: SpotifySearchMenuTarget) {
        val type = target.kind.lowercase()
        val url = when (target) {
            is SpotifySearchMenuTarget.Track -> "https://open.spotify.com/track/${target.value.id}"
            is SpotifySearchMenuTarget.Artist -> "https://open.spotify.com/artist/${target.value.id}"
            is SpotifySearchMenuTarget.Album -> "https://open.spotify.com/album/${target.value.id}"
            is SpotifySearchMenuTarget.Playlist -> "https://open.spotify.com/playlist/${target.value.id}"
            is SpotifySearchMenuTarget.Podcast -> "https://open.spotify.com/show/${target.value.id}"
            is SpotifySearchMenuTarget.Episode -> "https://open.spotify.com/episode/${target.value.id}"
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, target.title)
            putExtra(Intent.EXTRA_TEXT, "${target.title} · $type\n$url")
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "Share $type"))
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("Could not share this Spotify item.") }
        }
    }

    fun open(target: SpotifySearchMenuTarget) {
        onDismissTarget()
        when (target) {
            is SpotifySearchMenuTarget.Track -> onPlayTrack(target.value)
            is SpotifySearchMenuTarget.Artist -> onOpenArtist(target.value)
            is SpotifySearchMenuTarget.Album -> onOpenAlbum(target.value)
            is SpotifySearchMenuTarget.Playlist -> onOpenPlaylist(target.value.id, target.value.name)
            is SpotifySearchMenuTarget.Podcast ->
                onOpenSpotifyUrl("https://open.spotify.com/show/${target.value.id}")
            is SpotifySearchMenuTarget.Episode ->
                onOpenSpotifyUrl("https://open.spotify.com/episode/${target.value.id}")
        }
    }

    target?.let { selected ->
        ModalBottomSheet(
            onDismissRequest = onDismissTarget,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF202020),
            contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.62f),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchActionArtwork(selected.imageUrl, Modifier.size(54.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            selected.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(selected.kind, selected.subtitle)
                                .filter(String::isNotBlank).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                when (selected) {
                    is SpotifySearchMenuTarget.Track -> {
                        SpotifySearchMenuAction("Play") { open(selected) }
                        SpotifySearchMenuAction(
                            when {
                                likedTrackLoading -> "Checking Liked Songs…"
                                likedTrack == true -> "Remove from Liked Songs"
                                else -> "Add to Liked Songs"
                            },
                            enabled = !likedTrackLoading,
                        ) {
                            val shouldSave = likedTrack != true
                            onDismissTarget()
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        SpotifyPlaylistRepository.saveTrack(
                                            spotifyCookie,
                                            selected.value.id,
                                            saved = shouldSave,
                                        )
                                    }
                                    snackbarHostState.showSnackbar(
                                        if (shouldSave) "Added to Spotify Liked Songs"
                                        else "Removed from Spotify Liked Songs",
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    snackbarHostState.showSnackbar(
                                        failure.message ?: "Spotify couldn't update Liked Songs.",
                                    )
                                }
                            }
                        }
                        SpotifySearchMenuAction("Add to playlist") {
                            onDismissTarget()
                            addError = null
                            addToPlaylistTrack = selected.value
                        }
                        selected.value.artistIds.firstOrNull()?.let { artistId ->
                            SpotifySearchMenuAction("Go to artist") {
                                onOpenArtist(
                                    SpotifyArtist(
                                        id = artistId,
                                        name = selected.value.artists.substringBefore(",").trim()
                                            .ifBlank { "Artist" },
                                    ),
                                )
                                onDismissTarget()
                            }
                        }
                    }
                    is SpotifySearchMenuTarget.Artist -> SpotifySearchMenuAction("Open artist") {
                        open(selected)
                    }
                    is SpotifySearchMenuTarget.Album -> SpotifySearchMenuAction("Open album") {
                        open(selected)
                    }
                    is SpotifySearchMenuTarget.Playlist -> SpotifySearchMenuAction("Open playlist") {
                        open(selected)
                    }
                    is SpotifySearchMenuTarget.Podcast,
                    is SpotifySearchMenuTarget.Episode -> SpotifySearchMenuAction("Open in Spotify") {
                        open(selected)
                    }
                }
                SpotifySearchMenuAction("Share") {
                    onDismissTarget()
                    share(selected)
                }
            }
        }
    }

    addToPlaylistTrack?.let { track ->
        ModalBottomSheet(
            onDismissRequest = {
                addToPlaylistTrack = null
                addError = null
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF202020),
            contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.62f),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            ) {
                Text(
                    "Add to playlist",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    track.title,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                addError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (playlistsError != null) {
                    Text(
                        playlistsError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = { loadPlaylists(reset = playlists.isEmpty()) },
                        enabled = !playlistsLoading,
                    ) { Text("Try again", color = MaterialTheme.colorScheme.primary) }
                }
                when {
                    spotifyCookie.isBlank() -> Text(
                        "Sign in to Spotify in Settings to add this song to a playlist.",
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                    playlistsLoading && playlists.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(112.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    playlists.isEmpty() && playlistsError == null -> Text(
                        "No Spotify playlists were found.",
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(
                                        enabled = addingToPlaylistId == null &&
                                            playlistsError == null,
                                    ) {
                                        scope.launch {
                                            addingToPlaylistId = playlist.id
                                            addError = null
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    SpotifyPlaylistRepository.addTrack(
                                                        spotifyCookie,
                                                        playlist.id,
                                                        track.uri.ifBlank { "spotify:track:${track.id}" },
                                                    )
                                                }
                                                addToPlaylistTrack = null
                                                snackbarHostState.showSnackbar("Added to ${playlist.name}")
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                addError = if (failure is RateLimitedException) {
                                                    "${failure.message} This song was not added; try again after the wait."
                                                } else {
                                                    failure.message
                                                        ?: "Couldn't add this song to ${playlist.name}."
                                                }
                                            } finally {
                                                if (addingToPlaylistId == playlist.id) {
                                                    addingToPlaylistId = null
                                                }
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SearchActionArtwork(playlist.imageUrl, Modifier.size(46.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(
                                        playlist.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        playlist.trackCount?.let { "$it songs" }
                                            ?: "Spotify playlist",
                                        color = Color.White.copy(alpha = 0.65f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (addingToPlaylistId == playlist.id) {
                                    CircularProgressIndicator(
                                        Modifier.size(22.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(
                                        "＋",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        }
                        if (nextPlaylistOffset != null) {
                            item(key = "spotify-search-playlist-picker-more") {
                                TextButton(
                                    onClick = { loadPlaylists(reset = false) },
                                    enabled = !playlistsLoading,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                ) {
                                    if (playlistsLoading) {
                                        CircularProgressIndicator(
                                            Modifier.size(18.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        if (playlistsLoading) "Loading…" else "Load more playlists",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifySearchMenuAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SearchActionArtwork(imageUrl: String?, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
