package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.music.MusicProvider
import com.streamcloud.app.data.music.MusicProviderCatalogRepository
import com.streamcloud.app.data.spotify.SpotifyPlaylist
import com.streamcloud.app.data.spotify.SpotifyPlaylistRepository
import com.streamcloud.app.data.downloads.YtMusicDownloadUtil
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MusicScreen(
    onArtistClick: (String, String?) -> Unit = { _, _ -> },
    onOpenPlaylist: (String, String, String?) -> Unit = { _, _, _ -> },
    onProfileClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSearchWithQuery: (String) -> Unit = {},
    tvNavFocusRequester: FocusRequester? = null,
    onSpotifyPlaylistClick: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val services = remember(context) { ServiceLocator.get(context) }
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    // Wait for the persisted choice instead of briefly opening the online provider.
    val selected by services.settings.musicHomeProvider
        .collectAsState(initial = null)
    val installed by services.settings.installedMusicProviders.collectAsState(initial = emptyMap())
    val spotifyCookie by services.settings.spotifyCookie.collectAsState(initial = "")
    val scope = rememberCoroutineScope()
    var showProviders by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var catalogue by remember { mutableStateOf<List<com.streamcloud.app.data.music.MusicProviderManifest>?>(null) }
    var catalogueLoading by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        TextButton(
            onClick = { showProviders = true },
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = if (isTv) 68.dp else 4.dp)
                .then(
                    if (isTv && tvNavFocusRequester != null) Modifier.focusRequester(tvNavFocusRequester)
                    else Modifier,
                ),
        ) {
            Text(selected?.title ?: "Music providers")
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = "Switch music provider")
        }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        Box(Modifier.weight(1f)) {
            when (selected) {
                MusicProvider.YouTubeMusic -> YouTubeMusicHome(
                    onArtistClick, onOpenPlaylist, onProfileClick, onSearchClick,
                    onSearchWithQuery, null,
                )
                MusicProvider.Offline -> OfflineMusicHome()
                MusicProvider.Spotify -> {
                    if (installed["spotify"] == null) {
                        Text(
                            "Spotify provider is not installed. Open Music providers to install it.",
                            Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else {
                        SpotifyMusicHome(spotifyCookie, onSpotifyPlaylistClick)
                    }
                }
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
    if (showProviders) {
        AlertDialog(
            onDismissRequest = { showProviders = false },
            title = { Text("Music providers") },
            text = {
                Column {
                    MusicProvider.entries.forEach { provider ->
                        if (provider == MusicProvider.Spotify && installed["spotify"] == null) return@forEach
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    try {
                                        services.settings.setMusicHomeProvider(provider)
                                        error = null
                                        showProviders = false
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = "Could not save music provider. Please try again."
                                        showProviders = false
                                    }
                                }
                            },
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(provider.title)
                                Text(provider.description, style = MaterialTheme.typography.bodySmall)
                            }
                            if (provider == selected) Icon(Icons.Default.Check, "Selected")
                        }
                    }
                    Text(
                        "Additional music providers will be available in a future update.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Manage providers", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Public StreamCloud catalogue", Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                scope.launch {
                                    catalogueLoading = true
                                    try {
                                        catalogue = MusicProviderCatalogRepository.fetch()
                                        error = null
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Could not load music providers."
                                    } finally {
                                        catalogueLoading = false
                                    }
                                }
                            },
                            enabled = !catalogueLoading,
                        ) {
                            if (catalogueLoading) CircularProgressIndicator(Modifier.size(20.dp))
                            else Icon(Icons.Default.Refresh, contentDescription = "Refresh provider catalogue")
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    catalogue.orEmpty().forEach { manifest ->
                        val version = installed[manifest.id]
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${manifest.name} · v${manifest.version}")
                                Text(manifest.description, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Playlist audio is matched through YouTube Music, not streamed from Spotify.",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(
                                enabled = version != manifest.version,
                                onClick = {
                                    scope.launch {
                                        try {
                                            services.settings.setInstalledMusicProvider(manifest.id, manifest.version)
                                            error = null
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            error = "Could not install this provider."
                                        }
                                    }
                                },
                            ) {
                                Text(when {
                                    version == null -> "Install"
                                    version < manifest.version -> "Update"
                                    else -> "Installed"
                                })
                            }
                            if (version != null) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                services.settings.setInstalledMusicProvider(manifest.id, null)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                error = "Could not remove this provider."
                                            }
                                        }
                                    },
                                ) { Text("Remove") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviders = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun SpotifyMusicHome(
    spotifyCookie: String,
    onPlaylistClick: (String, String) -> Unit,
) {
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(spotifyCookie, retryGeneration) {
        loading = true
        error = null
        if (spotifyCookie.isBlank()) {
            playlists = emptyList()
            error = "Sign in to Spotify in Settings to load your playlists."
            loading = false
        } else {
            try {
                playlists = withContext(Dispatchers.IO) {
                    SpotifyPlaylistRepository.getPlaylists(spotifyCookie)
                }
                if (playlists.isEmpty()) error = "No Spotify playlists returned. Reconnect Spotify in Settings and try again."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Spotify playlists could not be loaded. Reconnect Spotify in Settings and retry."
            } finally {
                loading = false
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Spotify", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Browse playlists with your existing Spotify sign-in. Playback uses YouTube matches, not Spotify audio.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (loading) item { CircularProgressIndicator() }
        error?.let { message ->
            item {
                Text(message)
                TextButton(onClick = { retryGeneration++ }) { Text("Retry") }
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            OutlinedButton(
                onClick = { onPlaylistClick(playlist.id, playlist.name) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = playlist.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                        Text("${playlist.trackCount} tracks", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
@androidx.media3.common.util.UnstableApi
private fun OfflineMusicHome() {
    val context = LocalContext.current
    val services = remember(context) { ServiceLocator.get(context) }
    val profileId by services.profiles.activeProfileId.collectAsState(
        initial = services.profiles.currentActiveId(),
    )
    val userId by services.settings.nuvioUserId.collectAsState(initial = "")
    val dao = remember(profileId, userId) {
        LibraryDb.getForProfile(context.applicationContext, profileId ?: "default", userId).tracks()
    }
    val tracks by remember(dao) { dao.downloaded() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var error by remember(profileId, userId) { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    fun play(queue: List<TrackEntity>, index: Int) {
        if (starting || index !in queue.indices) return
        starting = true
        scope.launch {
            try {
                // Support both legacy files and Media3 downloads used by today's downloader.
                val items = withContext(Dispatchers.IO) {
                    queue.map { track ->
                        val path = requireNotNull(track.localPath)
                        val uri = if (path.startsWith("cache:")) {
                            check(YtMusicDownloadUtil.isDownloaded(track.url)) {
                                "A download is incomplete or missing. Download it again in Library."
                            }
                            android.net.Uri.parse(track.url)
                        } else {
                            val file = File(path)
                            check(file.isFile && file.canRead()) {
                                "A downloaded file is missing. Remove or download it again in Library."
                            }
                            android.net.Uri.fromFile(file)
                        }
                        MediaItem.Builder()
                            .setMediaId(track.url)
                            .setUri(uri)
                            .setCustomCacheKey(track.url)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(track.title).setArtist(track.artist)
                                    .setArtworkUri(track.thumbnail?.let(android.net.Uri::parse))
                                    .build(),
                            ).build()
                    }
                }
                val controller = MusicController.get(context.applicationContext)
                controller.setMediaItems(items, index, 0L)
                controller.prepare()
                controller.play()
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not play this download."
            } finally {
                starting = false
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Offline downloads", style = MaterialTheme.typography.headlineMedium) }
        error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        val queue = tracks
        if (queue == null) {
            item { CircularProgressIndicator() }
        } else if (queue.isEmpty()) {
            item {
                Text("No downloaded music yet. Download songs from your playlists to listen here offline.")
            }
        } else {
            item {
                Button(onClick = { play(queue, 0) }, enabled = !starting) {
                    Text(if (starting) "Starting…" else "Play all · ${queue.size} songs")
                }
            }
            itemsIndexed(queue, key = { _, track -> track.url }) { index, track ->
                OutlinedButton(
                    onClick = { play(queue, index) },
                    enabled = !starting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium)
                        Text(track.artist, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
