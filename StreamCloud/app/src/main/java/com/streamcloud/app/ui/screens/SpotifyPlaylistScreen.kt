package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository
import com.streamcloud.app.data.spotify.SpotifyTrack
import com.streamcloud.app.data.ytmusic.YtMusicSearchRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun YtTrack.asYtmSong() = YtmSong(
    videoId        = url.substringAfter("v=").substringBefore("&").ifBlank { url.substringAfterLast("/") },
    title          = title,
    artist         = uploader,
    album          = null,
    thumbnail      = thumbnail,
    durationSeconds = durationSec,
    isVideo        = isVideo,
)

private fun Long.toMinSec(): String {
    val s = this / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaylistScreen(
    playlistId: String,
    playlistTitle: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val spDc by sl.settings.spotifyCookie.collectAsState(initial = "")
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var tracks by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    var playingIndex by remember { mutableStateOf<Int?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Pair<Int, SpotifyTrack>?>(null) }

    suspend fun syncTracks() {
        if (spDc.isBlank()) { loading = false; return }
        tracks = withContext(Dispatchers.IO) {
            SpotifyPlaylistRepository.getPlaylistTracks(spDc, playlistId)
        }
        loading = false
        syncing = false
    }

    LaunchedEffect(spDc, playlistId) { syncTracks() }

    // Debounced Spotify search for the "Add songs" sheet
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) { searchResults = emptyList(); return@LaunchedEffect }
        delay(400)
        searchLoading = true
        searchResults = SpotifyPlaylistRepository.searchTracks(spDc, searchQuery)
        searchLoading = false
    }

    // ── Remove dialog ─────────────────────────────────────────────────────────
    removeTarget?.let { (idx, track) ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove from playlist?") },
            text  = { Text("\"${track.title}\" by ${track.artists} will be removed from this Spotify playlist.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = removeTarget
                    removeTarget = null
                    if (target != null) scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            SpotifyPlaylistRepository.removeTrack(spDc, playlistId, target.second.uri)
                        }
                        if (ok) {
                            tracks = tracks.toMutableList().also { it.removeAt(target.first) }
                            snackbarHostState.showSnackbar("Removed from playlist")
                        } else {
                            snackbarHostState.showSnackbar("Couldn't remove track")
                        }
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }

    // ── Add songs sheet ───────────────────────────────────────────────────────
    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = {
            showAddSheet = false
            searchQuery = ""
            searchResults = emptyList()
        }) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    "Add songs",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search Spotify") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (searchLoading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, "Clear")
                                }
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    itemsIndexed(searchResults, key = { _, t -> t.uri }) { _, track ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            SpotifyPlaylistRepository.addTrack(spDc, playlistId, track.uri)
                                        }
                                        if (ok) {
                                            snackbarHostState.showSnackbar("\"${track.title}\" added")
                                            scope.launch { loading = true; syncTracks() }
                                        } else {
                                            snackbarHostState.showSnackbar("Couldn't add track")
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = track.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    track.artists,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                Icons.Default.Add, "Add",
                                tint = Color(0xFF1DB954),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }

    // ── Main Scaffold ─────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlistTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (spDc.isNotBlank()) {
                        if (syncing) {
                            Box(
                                Modifier.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = {
                                scope.launch { syncing = true; syncTracks() }
                            }) {
                                Icon(Icons.Default.Refresh, "Sync playlist")
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!loading && spDc.isNotBlank()) {
                FloatingActionButton(
                    onClick = { showAddSheet = true },
                    containerColor = Color(0xFF1DB954),
                    contentColor  = Color.White,
                ) {
                    Icon(Icons.Default.Add, "Add songs")
                }
            }
        },
    ) { innerPadding ->
        if (spDc.isBlank()) {
            Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Sign in to Spotify in Settings to view your playlists.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(
                top    = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 100.dp,
                start  = 16.dp,
                end    = 16.dp,
            ),
        ) {
            item(key = "count") {
                Text(
                    "${tracks.size} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (loading) {
                item(key = "loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = Color(0xFF1DB954)) }
                }
            } else if (tracks.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "This playlist is empty. Tap + to add songs.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(tracks, key = { _, t -> t.uri }) { index, track ->
                    SpotifyTrackRow(
                        track     = track,
                        isLoading = playingIndex == index,
                        onClick   = {
                            scope.launch {
                                playingIndex = index
                                runCatching {
                                    val results = YtMusicSearchRepository.songs("${track.title} ${track.artists}")
                                    val first = results.firstOrNull()
                                    if (first != null) {
                                        YtPlayback.playSong(context, first.asYtmSong())
                                    } else {
                                        snackbarHostState.showSnackbar("\"${track.title}\" not found on YouTube Music")
                                    }
                                }.onFailure { snackbarHostState.showSnackbar("Playback failed") }
                                playingIndex = null
                            }
                        },
                        onRemove  = { removeTarget = index to track },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(start = 68.dp),
                    )
                }
            }
        }
    }
}

// ── Track row ─────────────────────────────────────────────────────────────────

@Composable
private fun SpotifyTrackRow(
    track: SpotifyTrack,
    isLoading: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF1DB954),
                )
            } else {
                AsyncImage(
                    model = track.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (track.album.isNotBlank()) "${track.artists} · ${track.album}" else track.artists,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            track.durationMs.toMinSec(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Remove from playlist",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
