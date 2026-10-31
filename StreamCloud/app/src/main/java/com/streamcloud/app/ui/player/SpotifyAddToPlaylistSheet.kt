package com.streamcloud.app.ui.player

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.streamcloud.app.data.spotify.SpotifyPlaylist
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository
import com.streamcloud.app.data.spotify.SpotifyWebPlayerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SpotifyPlaylistSheetBackground = Color(0xFF121212)
private val SpotifyPlaylistSheetAccent = Color(0xFF1ED760)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyAddToPlaylistSheet(
    spotifyTrackId: String?,
    songTitle: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val services = remember(context) { ServiceLocator.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spotifyCookie by services.settings.spotifyCookie.collectAsState(initial = "")
    val trackId = spotifyTrackId
        ?.removePrefix("spotify:track:")
        ?.takeIf(String::isNotBlank)

    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var nextOffset by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var addingToPlaylistId by remember { mutableStateOf<String?>(null) }

    fun loadPlaylists(reset: Boolean) {
        if (loading || loadingMore) return
        val offset = if (reset) 0 else nextOffset ?: return
        if (spotifyCookie.isBlank()) {
            error = "Sign in to Spotify in Settings to load your playlists."
            playlists = emptyList()
            return
        }

        val cookie = spotifyCookie
        scope.launch {
            if (reset) loading = true else loadingMore = true
            error = null
            try {
                val page = withContext(Dispatchers.IO) {
                    SpotifyWebPlayerRepository.getPlaylistsPage(cookie, offset)
                }
                playlists = if (reset) {
                    page.items.distinctBy(SpotifyPlaylist::id)
                } else {
                    (playlists + page.items).distinctBy(SpotifyPlaylist::id)
                }
                nextOffset = page.nextOffset
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Your Spotify playlists could not be loaded."
            } finally {
                loading = false
                loadingMore = false
            }
        }
    }

    LaunchedEffect(spotifyCookie, trackId) {
        playlists = emptyList()
        nextOffset = null
        error = null
        if (trackId == null) {
            error = "Spotify track information is unavailable."
        } else if (spotifyCookie.isBlank()) {
            error = "Sign in to Spotify in Settings to load your playlists."
        } else {
            loadPlaylists(reset = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SpotifyPlaylistSheetBackground,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.62f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 300.dp, max = 640.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Add to Spotify playlist",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                songTitle,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))

            if (error != null) {
                Text(
                    error.orEmpty(),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                if (spotifyCookie.isNotBlank() && trackId != null) {
                    TextButton(
                        onClick = { loadPlaylists(reset = true) },
                        enabled = !loading && !loadingMore,
                    ) {
                        Text("Try again", color = SpotifyPlaylistSheetAccent)
                    }
                }
            }

            when {
                loading && playlists.isEmpty() -> Box(
                    Modifier.fillMaxWidth().height(112.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = SpotifyPlaylistSheetAccent)
                }

                !loading && playlists.isEmpty() && error == null -> Box(
                    Modifier.fillMaxWidth().height(112.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No Spotify playlists were found.",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }

                playlists.isNotEmpty() -> LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(playlists, key = SpotifyPlaylist::id) { playlist ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = addingToPlaylistId == null) {
                                    val cookie = spotifyCookie
                                    val id = trackId ?: return@clickable
                                    scope.launch {
                                        addingToPlaylistId = playlist.id
                                        error = null
                                        try {
                                            withContext(Dispatchers.IO) {
                                                SpotifyPlaylistRepository.addTrack(
                                                    cookie,
                                                    playlist.id,
                                                    "spotify:track:$id",
                                                )
                                            }
                                            Toast.makeText(
                                                context,
                                                "Added to ${playlist.name}",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            onDismiss()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            error = failure.message
                                                ?: "Couldn't add this song to ${playlist.name}."
                                        } finally {
                                            addingToPlaylistId = null
                                        }
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = playlist.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(
                                    playlist.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    playlist.trackCount?.let { "$it songs" } ?: "Spotify playlist",
                                    color = Color.White.copy(alpha = 0.65f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (addingToPlaylistId == playlist.id) {
                                CircularProgressIndicator(
                                    Modifier.size(22.dp),
                                    color = SpotifyPlaylistSheetAccent,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(
                                    "＋",
                                    color = SpotifyPlaylistSheetAccent,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    }

                    nextOffset?.let {
                        item(key = "spotify-add-playlist-load-more") {
                            TextButton(
                                onClick = { loadPlaylists(reset = false) },
                                enabled = !loadingMore && addingToPlaylistId == null,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            ) {
                                if (loadingMore) {
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        color = SpotifyPlaylistSheetAccent,
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    if (loadingMore) "Loading…" else "Load more playlists",
                                    color = SpotifyPlaylistSheetAccent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
