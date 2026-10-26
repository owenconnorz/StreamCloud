package com.streamcloud.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material.icons.Icons as MaterialIcons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.streamcloud.app.ui.theme.rememberBannerPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun YtTrack.asYtmSong(spotifyTrackId: String? = null) = YtmSong(
    videoId        = url.substringAfter("v=").substringBefore("&").ifBlank { url.substringAfterLast("/") },
    title          = title,
    artist         = uploader,
    album          = null,
    thumbnail      = thumbnail,
    durationSeconds = durationSec,
    isVideo        = isVideo,
    spotifyTrackId = spotifyTrackId,
)

private fun Long.toMinSec(): String {
    val s = this / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    var playlistDetails by remember(playlistId) { mutableStateOf<SpotifyPlaylist?>(null) }
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
    var searchError by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<Pair<Int, SpotifyTrack>?>(null) }
    var menuTrack by remember { mutableStateOf<SpotifyTrack?>(null) }
    var menuTrackLiked by remember { mutableStateOf<Boolean?>(null) }
    var menuTrackLikeLoading by remember { mutableStateOf(false) }
    var addToPlaylistTrack by remember { mutableStateOf<SpotifyTrack?>(null) }
    var playlistChoices by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var playlistChoicesLoading by remember { mutableStateOf(false) }
    var loadingMorePlaylistChoices by remember { mutableStateOf(false) }
    var nextPlaylistChoiceOffset by remember { mutableStateOf<Int?>(null) }
    var playlistChoicesError by remember { mutableStateOf<String?>(null) }
    var recommendedTracks by remember(playlistId) { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var recommendationsLoading by remember(playlistId) { mutableStateOf(false) }
    var recommendationsError by remember(playlistId) { mutableStateOf<String?>(null) }
    var recommendationRetryGeneration by remember(playlistId) { mutableIntStateOf(0) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var sortOrder by rememberSaveable(playlistId) { mutableStateOf("playlist") }

    val isPlaylist = albumId == null && artistId == null
    val listState = rememberLazyListState()
    val playlistCollapsed = isPlaylist && listState.firstVisibleItemIndex > 0
    val playlistImageUrl = playlistDetails?.imageUrl
        ?.takeIf(String::isNotBlank)
        ?: tracks.firstOrNull()?.imageUrl
    val playlistPalette = rememberBannerPalette(
        imageUrl = playlistImageUrl.takeIf { isPlaylist },
        fallbackAccent = SpotifyGreen,
        fallbackOnAccent = Color.Black,
        fallbackBackground = SpotifyBlack,
        fallbackSurface = SpotifyBlack,
    )
    val visibleTracks = remember(tracks, sortOrder) {
        when (sortOrder) {
            "title" -> tracks.sortedBy { it.title.lowercase() }
            "artist" -> tracks.sortedBy { it.artists.lowercase() }
            else -> tracks
        }
    }
    val playlistTrackUris = remember(tracks) { tracks.mapTo(mutableSetOf()) { it.uri } }
    val availableRecommendedTracks = remember(recommendedTracks, playlistTrackUris) {
        recommendedTracks.filterNot { it.uri in playlistTrackUris }
    }

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
            if (isPlaylist) playlistDetails = page.collection
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
                val song = match.asYtmSong(track.id)
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

    fun playPlaylistTrack(track: SpotifyTrack, index: Int) {
        if (playingIndex != null) return
        scope.launch {
            playingIndex = index
            try {
                val match = withContext(Dispatchers.IO) {
                    YtMusicSearchRepository.songs("${track.title} ${track.artists}").firstOrNull()
                }
                if (match == null) {
                    snackbarHostState.showSnackbar("\"${track.title}\" was not found on YouTube Music.")
                } else {
                    YtPlayback.playSong(context, match.asYtmSong(track.id))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("Could not play this track.")
            } finally {
                playingIndex = null
            }
        }
    }

    fun playPlaylist(shuffle: Boolean = false) {
        val selected = if (shuffle) {
            visibleTracks.indices.randomOrNull()
        } else {
            visibleTracks.indices.firstOrNull()
        } ?: return
        playPlaylistTrack(visibleTracks[selected], selected)
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

    LaunchedEffect(spDc, playlistId, tracks.isNotEmpty(), recommendationRetryGeneration) {
        if (!isPlaylist || spDc.isBlank() || tracks.isEmpty()) {
            recommendedTracks = emptyList()
            recommendationsError = null
            recommendationsLoading = false
            return@LaunchedEffect
        }

        val seedArtistIds = tracks.asSequence()
            .flatMap { it.artistIds.asSequence() }
            .filter(String::isNotBlank)
            .distinct()
            .take(2)
            .toList()
        if (seedArtistIds.isEmpty()) {
            recommendationsLoading = false
            recommendedTracks = emptyList()
            return@LaunchedEffect
        }

        recommendationsLoading = true
        recommendationsError = null
        try {
            val existingUris = tracks.mapTo(mutableSetOf()) { it.uri }
            val candidates = buildList {
                seedArtistIds.forEach { artistId ->
                    val page = withContext(Dispatchers.IO) {
                        SpotifyWebPlayerRepository.getArtistTopTracks(spDc, artistId)
                    }
                    addAll(page.items)
                }
            }
            recommendedTracks = candidates
                .filterNot { it.uri in existingUris }
                .distinctBy { it.uri }
                .take(6)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recommendationsError = failure.message ?: "Spotify recommendations could not be loaded."
        } finally {
            recommendationsLoading = false
        }
    }

    // Debounced, account-authenticated Spotify catalogue search for the "Add songs" sheet.
    LaunchedEffect(searchQuery, spDc) {
        val cleanQuery = searchQuery.trim()
        if (cleanQuery.length < 2) {
            searchResults = emptyList()
            searchError = null
            searchLoading = false
            return@LaunchedEffect
        }
        delay(400L)
        searchLoading = true
        searchError = null
        try {
            val found = withContext(Dispatchers.IO) {
                SpotifyPlaylistRepository.searchTracks(spDc, cleanQuery)
            }
            if (searchQuery.trim() == cleanQuery) searchResults = found
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (searchQuery.trim() == cleanQuery) {
                searchResults = emptyList()
                searchError = failure.message ?: "Spotify search could not be loaded."
            }
        } finally {
            if (searchQuery.trim() == cleanQuery) searchLoading = false
        }
    }

    LaunchedEffect(menuTrack?.id, spDc) {
        val track = menuTrack ?: return@LaunchedEffect
        menuTrackLiked = null
        menuTrackLikeLoading = true
        try {
            menuTrackLiked = withContext(Dispatchers.IO) {
                SpotifyPlaylistRepository.isTrackSaved(spDc, track.id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            menuTrackLiked = null
        } finally {
            if (menuTrack?.id == track.id) menuTrackLikeLoading = false
        }
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
                        try {
                            withContext(Dispatchers.IO) {
                                SpotifyPlaylistRepository.removeTrack(
                                    spDc,
                                    collectionId,
                                    target.second.uri,
                                    position = target.first,
                                )
                            }
                            tracks = tracks.toMutableList().also { it.removeAt(target.first) }
                            totalTrackCount = (totalTrackCount - 1).coerceAtLeast(0)
                            syncTracks(forceRefresh = true)
                            snackbarHostState.showSnackbar("Removed from playlist")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            snackbarHostState.showSnackbar(
                                failure.message ?: "Spotify couldn't remove this track.",
                            )
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
                SpotifyTrackMenuAction(MaterialIcons.Filled.Share, "Share") {
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
                SpotifyTrackMenuAction(
                    MaterialIcons.Filled.FavoriteBorder,
                    when {
                        menuTrackLikeLoading -> "Checking Liked Songs…"
                        menuTrackLiked == true -> "Remove from Liked Songs"
                        else -> "Add to Liked Songs"
                    },
                ) {
                    if (menuTrackLikeLoading || menuTrackLiked == null) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "Couldn't check Spotify Liked Songs. Close the menu and try again.",
                            )
                        }
                    } else {
                        val shouldSave = menuTrackLiked != true
                        menuTrack = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    SpotifyPlaylistRepository.saveTrack(spDc, track.id, saved = shouldSave)
                                }
                                menuTrackLiked = shouldSave
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
                }
                SpotifyTrackMenuAction(
                    MaterialIcons.Filled.PlaylistAdd,
                    "Add to playlist",
                ) {
                    menuTrack = null
                    playlistChoices = emptyList()
                    addToPlaylistTrack = track
                }
                SpotifyTrackMenuAction(MaterialIcons.Filled.QueueMusic, "Play next") {
                    queueTrack(track, playNext = true)
                }
                SpotifyTrackMenuAction(
                    MaterialIcons.Filled.PlaylistAdd,
                    "Add to queue",
                ) {
                    queueTrack(track)
                }
                if (track.album.isNotBlank()) {
                    SpotifyTrackMenuAction(MaterialIcons.Filled.Album, "Go to album") {
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
                    SpotifyTrackMenuAction(MaterialIcons.Filled.Person, "Go to artists") {
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
                SpotifyTrackMenuAction(MaterialIcons.Filled.Podcasts, "Go to song radio") {
                    queueTrack(track, startRadio = true)
                }
                if (albumId == null && artistId == null) {
                    SpotifyTrackMenuAction(MaterialIcons.Filled.Delete, "Remove from this playlist") {
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
                    ) { CircularProgressIndicator(color = SpotifyGreen) }
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
                        }) { Text("Try again", color = SpotifyGreen) }
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
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    SpotifyPlaylistRepository.addTrack(
                                                        spDc,
                                                        playlist.id,
                                                        track.uri,
                                                    )
                                                }
                                                addToPlaylistTrack = null
                                                snackbarHostState.showSnackbar(
                                                    "Added to ${playlist.name}",
                                                )
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                snackbarHostState.showSnackbar(
                                                    failure.message
                                                        ?: "Couldn't add this track to ${playlist.name}.",
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
                                        playlist.trackCount?.let { "$it songs" } ?: "Spotify playlist",
                                        color = Color.White.copy(alpha = 0.65f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add to ${playlist.name}",
                                    tint = SpotifyGreen,
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
                                            color = SpotifyGreen,
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        if (loadingMorePlaylistChoices) "Loading…" else "Load more playlists",
                                        color = SpotifyGreen,
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
                if (searchError != null) {
                    Text(
                        searchError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else if (searchQuery.trim().length >= 2 && !searchLoading && searchResults.isEmpty()) {
                    Text(
                        "No matching Spotify tracks.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    itemsIndexed(searchResults, key = { _, t -> t.uri }) { _, track ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                SpotifyPlaylistRepository.addTrack(spDc, collectionId, track.uri)
                                            }
                                            snackbarHostState.showSnackbar("\"${track.title}\" added")
                                            showAddSheet = false
                                            syncTracks(forceRefresh = true)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            snackbarHostState.showSnackbar(
                                                failure.message ?: "Spotify couldn't add this track.",
                                            )
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
                                tint = SpotifyGreen,
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
                title = {
                    if (!isPlaylist || playlistCollapsed) {
                        Text(playlistTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isPlaylist && !playlistCollapsed) {
                        playlistPalette.backgroundTint
                    } else {
                        SpotifyBlack
                    },
                    scrolledContainerColor = SpotifyBlack,
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top    = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 100.dp,
            ),
        ) {
            if (isPlaylist) {
                item(key = "spotify-playlist-hero") {
                    SpotifyPlaylistHero(
                        title = playlistDetails?.name?.takeIf(String::isNotBlank) ?: playlistTitle,
                        description = playlistDetails?.description.orEmpty(),
                        ownerName = playlistDetails?.ownerName.orEmpty(),
                        imageUrl = playlistImageUrl,
                        tracks = tracks,
                        totalTrackCount = totalTrackCount,
                        durationLabel = if (nextTrackOffset == null && tracks.isNotEmpty()) {
                            formatSpotifyPlaylistDuration(tracks.sumOf { it.durationMs })
                        } else {
                            null
                        },
                        artworkColor = playlistPalette.artworkColor,
                        accentColor = playlistPalette.accent,
                        accentContainerColor = playlistPalette.accentContainer,
                        onShuffle = { playPlaylist(shuffle = true) },
                        onPlay = { playPlaylist() },
                    )
                }
                stickyHeader(key = "spotify-playlist-actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SpotifyBlack)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistActionPill(
                            label = "Add",
                            icon = MaterialIcons.Filled.Add,
                            onClick = { showAddSheet = true },
                        )
                        Box {
                            PlaylistActionPill(
                                label = when (sortOrder) {
                                    "title" -> "Title"
                                    "artist" -> "Artist"
                                    else -> "Playlist order"
                                },
                                icon = MaterialIcons.Filled.Sort,
                                onClick = { sortMenuExpanded = true },
                            )
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                listOf(
                                    "playlist" to "Playlist order",
                                    "title" to "Title",
                                    "artist" to "Artist",
                                ).forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            sortOrder = value
                                            playingIndex = null
                                            sortMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { playPlaylist() },
                            shape = CircleShape,
                            color = SpotifyGreen,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    MaterialIcons.Filled.PlayArrow,
                                    contentDescription = "Play playlist",
                                    tint = Color.Black,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                item(key = "count") {
                    Text(
                        if (totalTrackCount > tracks.size) {
                            "${tracks.size} of $totalTrackCount songs"
                        } else {
                            "${tracks.size} songs"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp, bottom = 8.dp),
                    )
                }
            }

            if (loading) {
                item(key = "loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = SpotifyGreen) }
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
                itemsIndexed(visibleTracks, key = { _, t -> t.uri }) { index, track ->
                    SpotifyTrackRow(
                        track     = track,
                        isLoading = playingIndex == index,
                        allowRemove = albumId == null && artistId == null,
                        onClick   = { playPlaylistTrack(track, index) },
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
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = ::loadMoreTracks) { Text("Try again") }
                    }
                }
            }

            if (isPlaylist && !loading && tracks.isNotEmpty()) {
                item(key = "spotify-recommended-songs-title") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Recommended Songs",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            "Popular songs from artists in this playlist",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.68f),
                        )
                    }
                }
                when {
                    recommendationsLoading -> item(key = "spotify-recommendations-loading") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = SpotifyGreen)
                        }
                    }
                    recommendationsError != null -> item(key = "spotify-recommendations-error") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                            Text(
                                recommendationsError.orEmpty(),
                                color = Color.White.copy(alpha = 0.7f),
                            )
                            TextButton(onClick = { recommendationRetryGeneration++ }) {
                                Text("Try again", color = SpotifyGreen)
                            }
                        }
                    }
                    availableRecommendedTracks.isEmpty() -> item(key = "spotify-recommendations-empty") {
                        Text(
                            "No additional songs were found for the artists in this playlist.",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                            color = Color.White.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> itemsIndexed(
                        availableRecommendedTracks,
                        key = { _, track -> "recommended-${track.uri}" },
                    ) { index, track ->
                        SpotifyRecommendedTrackRow(
                            track = track,
                            onClick = { playPlaylistTrack(track, visibleTracks.size + index) },
                            onAdd = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            SpotifyPlaylistRepository.addTrack(spDc, collectionId, track.uri)
                                        }
                                        snackbarHostState.showSnackbar("\"${track.title}\" added")
                                        syncTracks(forceRefresh = true)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        snackbarHostState.showSnackbar(
                                            failure.message ?: "Couldn't add this song to the playlist.",
                                        )
                                    }
                                }
                            },
                        )
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
            .padding(horizontal = 18.dp, vertical = 10.dp),
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
                    color = SpotifyGreen,
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
