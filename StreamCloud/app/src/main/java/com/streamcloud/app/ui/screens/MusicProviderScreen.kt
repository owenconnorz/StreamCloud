package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
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
) {
    val context = LocalContext.current
    val services = remember(context) { ServiceLocator.get(context) }
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    // Wait for the persisted choice instead of briefly opening the online provider.
    val selected by services.settings.musicHomeProvider
        .collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var showProviders by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviders = false }) { Text("Close") }
            },
        )
    }
}

@Composable
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
