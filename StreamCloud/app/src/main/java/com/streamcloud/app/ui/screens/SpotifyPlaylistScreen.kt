package com.streamcloud.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd as PlaylistAddIcon
import androidx.compose.material.icons.filled.Album as AlbumIcon
import androidx.compose.material.icons.filled.Delete as DeleteIcon
import androidx.compose.material.icons.filled.FavoriteBorder as FavoriteBorderIcon
import androidx.compose.material.icons.filled.Person as PersonIcon
import androidx.compose.material.icons.filled.Podcasts as PodcastsIcon
import androidx.compose.material.icons.filled.QueueMusic as QueueMusicIcon
import androidx.compose.material.icons.filled.Share as ShareIcon
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.data.spotify.SpotifyPlaylist
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository
import com.streamcloud.app.data.spotify.SpotifyTrack
import com.streamcloud.app.data.spotify.SpotifyWebPlayerRepository
import com.streamcloud.app.data.ytmusic.YtMusicSearchRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    val albumId = remember(playlistId) {
        playlistId.takeIf { it.startsWith("album:") }?.removePrefix("album:")
    }
    val artistId = remember(playlistId) {
        playlistId.takeIf { it.startsWith("artist:") }?.removePrefix("artist:")
    }
    val collectionId = albumId ?: artistId ?: playlistId
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var tracks by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    var loadingMore by remember(playlistId) { mutableStateOf(false) }
    var nextTrackOffset by remember(playlistId) { mutableStateOf<Int?>(null) }
    var totalTrackCount by remember(playlistId) { mutableStateOf(0) }
    var loadMoreError by remember(playlistId) { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var playingIndex by remember { mutableStateOf<Int?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Pair<Int, SpotifyTrack>?>(null) }
    var menuTrack by remember { mutableStateOf<SpotifyTrack?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<SpotifyTrack?>(null) }
    var playlistChoices by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var playlistChoicesLoading by remember { mutableStateOf(false) }
    var loadingMorePlaylistChoices by remember { mutableStateOf(false) }
    var nextPlaylistChoiceOffset by remember { mutableStateOf<Int?>(null) }
    var playlistChoicesError by remember { mutableStateOf<String?>(null) }

    suspend fun syncTracks(forceRefresh: Boolean = false) {
        if (spDc.isBlank()) {
            loading = false
            loadError = "Sign in to Spotify in Settings to view this collection."
            return
        }
        try {
            val page = withContext(Dispatchers.IO) {
                when {
                    albumId != null -> SpotifyWebPlayerRepository.getAlbumTracksPage(
                        spDc,
                        collectionId,
                        forceRefresh = forceRefresh,
                    )
                    artistId != null -> SpotifyWebPlayerRepository.getArtistTopTracks(
                        spDc,
                        collectionId,
                        forceRefresh = forceRefresh,
                    )
                    else -> SpotifyWebPlayerRepository.getPlaylistTracksPage(
                        spDc,
                        collectionId,
                        forceRefresh = forceRefresh,
                    )
                }
            }
            tracks = page.items
            nextTrackOffset = page.nextOffset
            totalTrackCount = page.totalCount.coerceAtLeast(page.items.size)
            loadMoreError = null
            loadError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: "Spotify collection could not be loaded."
        } finally {
            loading = false
            syncing = false
        }
    }

    fun loadMoreTracks() {
        val offset = nextTrackOffset ?: return
        if (loadingMore) return
        loadingMore = true
        loadMoreError = null
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    when {
                        albumId != null -> SpotifyWebPlayerRepository.getAlbumTracksPage(
                            spDc,
                            collectionId,
                            offset = offset,
                        )
                        artistId != null -> SpotifyWebPlayerRepository.getArtistTopTracks(spDc, collectionId)
                        else -> SpotifyWebPlayerRepository.getPlaylistTracksPage(
                            spDc,
                            collectionId,
                            offset = offset,
                        )
                    }
                }
                tracks = (tracks + page.items).distinctBy { it.uri }
                nextTrackOffset = page.nextOffset
                totalTrackCount = maxOf(totalTrackCount, page.totalCount, tracks.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                loadMoreError = failure.message ?: "More Spotify tracks could not be loaded."
            } finally {
                loadingMore = false
            }
        }
    }

    fun queueTrack(track: SpotifyTrack, playNext: Boolean = false, startRadio: Boolean = false) {
        menuTrack = null
        scope.launch {
            try {
                val match = withContext(Dispatchers.IO) {
                    YtMusicSearchRepository.songs("${track.title} ${track.artists}").firstOrNull()
                }
                if (match == null) {
                    snackbarHostState.showSnackbar("\"${track.title}\" was not found on YouTube Music.")
                    return@launch
                }
                val song = match.asYtmSong()
                when {
                    startRadio -> {
                        YtPlayback.startRadioFromCurrent(
                            context,
                            "https://music.youtube.com/watch?v=${song.videoId}",
                        )
                        YtPlayback.playSong(context, song)
                        snackbarHostState.showSnackbar("Started radio from ${track.title}")
                    }
                    playNext -> {
                        YtPlayback.playNext(context, song)
                        snackbarHostState.showSnackbar("Playing next: ${track.title}")
                    }
                    else -> {
                        YtPlayback.addToQueue(context, song)
                        snackbarHostState.showSnackbar("Added to queue: ${track.title}")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("Could not add this track to playback.")
            }
        }
    }

    fun loadMorePlaylistChoices() {
        val offset = nextPlaylistChoiceOffset ?: return
        if (loadingMorePlaylistChoices) return
        loadingMorePlaylistChoices = true
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    SpotifyWebPlayerRepository.getPlaylistsPage(spDc, offset)
                }
                playlistChoices = (playlistChoices + page.items)
                    .filter { it.id != collectionId }
                    .distinctBy { it.id }
                nextPlaylistChoiceOffset = page.nextOffset
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbarHostState.showSnackbar(
                    failure.message ?: "More Spotify playlists could not be loaded.",
                )
            } finally {
                loadingMorePlaylistChoices = false
            }
        }
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

    LaunchedEffect(addToPlaylistTrack?.id, spDc) {
        if (addToPlaylistTrack == null) return@LaunchedEffect
        playlistChoicesLoading = true
        playlistChoicesError = null
        try {
            val page = withContext(Dispatchers.IO) {
                SpotifyWebPlayerRepository.getPlaylistsPage(spDc)
            }
            playlistChoices = page.items.filter { it.id != collectionId }
            nextPlaylistChoiceOffset = page.nextOffset
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            playlistChoicesError = failure.message ?: "Your Spotify playlists could not be loaded."
        } finally {
            playlistChoicesLoading = false
        }
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
                            SpotifyPlaylistRepository.removeTrack(spDc, collectionId, target.second.uri)
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

    menuTrack?.let { track ->
        ModalBottomSheet(
            onDismissRequest = { menuTrack = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF202020),
            contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.62f),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = track.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(7.dp)),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            track.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(track.artists)
                                if (track.album.isNotBlank()) append(" · ${track.album}")
                            },
                            color = Color.White.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                SpotifyTrackMenuAction(ShareIcon, "Share") {
                    menuTrack = null
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, track.title)
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "${track.title} — ${track.artists}\nhttps://open.spotify.com/track/${track.id}",
                        )
                    }
                    context.startActivity(Intent.createChooser(share, "Share track"))
                }
                SpotifyTrackMenuAction(FavoriteBorderIcon, "Add to Liked Songs") {
                    menuTrack = null
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            SpotifyPlaylistRepository.saveTrack(spDc, track.id)
                        }
                        snackbarHostState.showSnackbar(
                            if (saved) "Added to Spotify Liked Songs"
                            else "Spotify couldn't save this track. Check your sign-in and try again.",
                        )
                    }
                }
                SpotifyTrackMenuAction(PlaylistAddIcon, "Add to playlist") {
                    menuTrack = null
                    playlistChoices = emptyList()
                    addToPlaylistTrack = track
                }
                SpotifyTrackMenuAction(QueueMusicIcon, "Play next") {
                    queueTrack(track, playNext = true)
                }
                SpotifyTrackMenuAction(PlaylistAddIcon, "Add to queue") {
                    queueTrack(track)
                }
                if (track.album.isNotBlank()) {
                    SpotifyTrackMenuAction(AlbumIcon, "Go to album") {
                        menuTrack = null
                        val url = "https://open.spotify.com/search/${Uri.encode(track.album)}"
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            scope.launch { snackbarHostState.showSnackbar("Could not open Spotify.") }
                        }
                    }
                }
                if (track.artists.isNotBlank()) {
                    SpotifyTrackMenuAction(PersonIcon, "Go to artists") {
                        menuTrack = null
                        val artistId = track.artistIds.firstOrNull()
                        val url = artistId?.let { "https://open.spotify.com/artist/$it" }
                            ?: "https://open.spotify.com/search/${Uri.encode(track.artists)}"
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            scope.launch { snackbarHostState.showSnackbar("Could not open Spotify.") }
                        }
                    }
                }
                SpotifyTrackMenuAction(PodcastsIcon, "Go to song radio") {
                    queueTrack(track, startRadio = true)
                }
                if (albumId == null && artistId == null) {
                    SpotifyTrackMenuAction(DeleteIcon, "Remove from this playlist") {
                        menuTrack = null
                        val index = tracks.indexOfFirst { it.uri == track.uri }
                        if (index >= 0) removeTarget = index to track
                    }
                }
            }
        }
    }

    addToPlaylistTrack?.let { track ->
        ModalBottomSheet(
            onDismissRequest = {
                addToPlaylistTrack = null
                playlistChoicesError = null
            },
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
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    track.title,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    playlistChoicesLoading -> Box(
                        Modifier.fillMaxWidth().height(112.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = Color(0xFF1DB954)) }
                    playlistChoicesError != null -> Column(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(playlistChoicesError!!, color = Color.White.copy(alpha = 0.8f))
                        TextButton(onClick = {
                            playlistChoicesError = null
                            playlistChoicesLoading = true
                            scope.launch {
                                try {
                                            val page = withContext(Dispatchers.IO) {
                                                SpotifyWebPlayerRepository.getPlaylistsPage(spDc)
                                            }
                                            playlistChoices = page.items.filter { it.id != collectionId }
                                            nextPlaylistChoiceOffset = page.nextOffset
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    playlistChoicesError =
                                        failure.message ?: "Your Spotify playlists could not be loaded."
                                } finally {
                                    playlistChoicesLoading = false
                                }
                            }
                        }) { Text("Try again", color = Color(0xFF9ABEFF)) }
                    }
                    playlistChoices.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(112.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No other Spotify playlists were found.",
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    ) {
                        items(playlistChoices, key = { it.id }) { playlist ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            val added = withContext(Dispatchers.IO) {
                                                SpotifyPlaylistRepository.addTrack(
                                                    spDc,
                                                    playlist.id,
                                                    track.uri,
                                                )
                                            }
                                            if (added) {
                                                addToPlaylistTrack = null
                                                snackbarHostState.showSnackbar(
                                                    "Added to ${playlist.name}",
                                                )
                                            } else {
                                                snackbarHostState.showSnackbar(
                                                    "Couldn't add this track to ${playlist.name}.",
                                                )
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
                                    modifier = Modifier.size(46.dp)
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
                                        "${playlist.trackCount ?: 0} songs",
                                        color = Color.White.copy(alpha = 0.65f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add to ${playlist.name}",
                                    tint = Color(0xFF9ABEFF),
                                )
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        }
                        if (nextPlaylistChoiceOffset != null) {
                            item(key = "spotify-playlist-picker-more") {
                                TextButton(
                                    onClick = ::loadMorePlaylistChoices,
                                    enabled = !loadingMorePlaylistChoices,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                ) {
                                    if (loadingMorePlaylistChoices) {
                                        CircularProgressIndicator(
                                            Modifier.size(18.dp),
                                            color = Color(0xFF9ABEFF),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        if (loadingMorePlaylistChoices) "Loading…" else "Load more playlists",
                                        color = Color(0xFF9ABEFF),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
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
                                            SpotifyPlaylistRepository.addTrack(spDc, collectionId, track.uri)
                                        }
                                        if (ok) {
                                            snackbarHostState.showSnackbar("\"${track.title}\" added")
                                 scope.launch { loading = true; syncTracks(forceRefresh = true) }
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
                                scope.launch { syncing = true; syncTracks(forceRefresh = true) }
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
            if (!loading && spDc.isNotBlank() && albumId == null && artistId == null) {
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
                    if (totalTrackCount > tracks.size) {
                        "${tracks.size} of $totalTrackCount songs"
                    } else {
                        "${tracks.size} songs"
                    },
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
            } else if (loadError != null) {
                item(key = "error") {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = {
                            scope.launch { loading = true; syncTracks(forceRefresh = true) }
                        }) { Text("Retry") }
                    }
                }
            } else if (tracks.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (albumId != null) "This album has no available tracks."
                            else if (artistId != null) "No top tracks for this artist were returned by Spotify."
                            else "This playlist is empty. Tap + to add songs.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(tracks, key = { _, t -> t.uri }) { index, track ->
                    SpotifyTrackRow(
                        track     = track,
                        isLoading = playingIndex == index,
                        allowRemove = albumId == null && artistId == null,
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
                        onMore = { menuTrack = track },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(start = 68.dp),
                    )
                }
            }
            if (!loading && nextTrackOffset != null) {
                item(key = "spotify-collection-load-more") {
                    TextButton(
                        onClick = ::loadMoreTracks,
                        enabled = !loadingMore,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    ) {
                        if (loadingMore) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (loadingMore) "Loading more…" else "Load more")
                    }
                }
            }
            loadMoreError?.let { message ->
                item(key = "spotify-collection-load-more-error") {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = ::loadMoreTracks) { Text("Try again") }
                    }
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
    allowRemove: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
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
        if (allowRemove) {
            IconButton(onClick = onMore, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More track options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SpotifyTrackMenuAction(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
