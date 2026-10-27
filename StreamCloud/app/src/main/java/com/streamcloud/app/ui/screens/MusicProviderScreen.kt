package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.streamcloud.app.R
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.music.MusicProvider
import com.streamcloud.app.data.music.MusicProviderCatalogRepository
import com.streamcloud.app.data.downloads.YtMusicDownloadUtil
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    onArtistClick: (String, String?) -> Unit = { _, _ -> },
    onOpenPlaylist: (String, String, String?) -> Unit = { _, _, _ -> },
    onProfileClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSpotifySearchClick: () -> Unit = onSearchClick,
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
    val providerOrder by services.settings.musicProviderOrder
        .collectAsState(initial = MusicProvider.entries)
    val installed by services.settings.installedMusicProviders.collectAsState(initial = emptyMap())
    val repositories by services.settings.musicProviderRepositories.collectAsState(initial = emptyList())
    val spotifyCookie by services.settings.spotifyCookie.collectAsState(initial = "")
    val scope = rememberCoroutineScope()
    var showProviders by rememberSaveable { mutableStateOf(false) }
    var showRepositoryManager by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var catalogues by remember { mutableStateOf<Map<String, List<com.streamcloud.app.data.music.MusicProviderManifest>>>(emptyMap()) }
    var loadingRepositories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var repositoryErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showAddRepository by rememberSaveable { mutableStateOf(false) }
    var repositoryInput by rememberSaveable { mutableStateOf("") }
    // Keep the saved source authoritative while plugin catalogues load. Falling back
    // to YouTube Music here made Spotify routes reveal the wrong source underneath.
    val activeProvider = selected
    val spotifyProviderAvailable = installed["spotify"] != null &&
        repositories.isNotEmpty() &&
        catalogues.values.flatten().any { it.id == "spotify" }
    val providerOptions = providerOrder.filter {
        it != MusicProvider.Spotify || spotifyProviderAvailable
    }
    fun moveVisibleProvider(index: Int, direction: Int) {
        val targetIndex = index + direction
        if (targetIndex !in providerOptions.indices) return

        val reorderedVisible = providerOptions.toMutableList().apply {
            add(targetIndex, removeAt(index))
        }
        val visibleProviders = providerOptions.toSet()
        var visibleIndex = 0
        val reorderedAll = providerOrder.map { provider ->
            if (provider in visibleProviders) reorderedVisible[visibleIndex++] else provider
        }
        scope.launch {
            try {
                services.settings.setMusicProviderOrder(reorderedAll)
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not save the music source order."
            }
        }
    }
    LaunchedEffect(repositories) {
        // User-added links may refresh automatically on later visits; a clean install has
        // no repositories, so it never contacts a plugin catalogue by default.
        repositories.forEach { repository ->
            loadingRepositories = loadingRepositories + repository
            try {
                catalogues = catalogues +
                    (repository to MusicProviderCatalogRepository.fetch(repository))
                repositoryErrors = repositoryErrors - repository
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                repositoryErrors = repositoryErrors +
                    (repository to (failure.message ?: "Could not load this repository."))
            } finally {
                loadingRepositories = loadingRepositories - repository
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(
            if (activeProvider == MusicProvider.Spotify) SpotifyBlack
            else MaterialTheme.colorScheme.background,
        )
            .statusBarsPadding(),
    ) {
        TextButton(
            onClick = { showProviders = true },
            colors = if (activeProvider == MusicProvider.Spotify) {
                ButtonDefaults.textButtonColors(contentColor = SpotifyGreen)
            } else {
                ButtonDefaults.textButtonColors()
            },
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = if (isTv) 68.dp else 4.dp)
                .then(
                    if (isTv && tvNavFocusRequester != null) Modifier.focusRequester(tvNavFocusRequester)
                    else Modifier,
                ),
        ) {
            if (activeProvider != null) {
                MusicProviderLogo(activeProvider, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(activeProvider.title)
            } else {
                Text("Music providers")
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = "Switch music provider")
        }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        Box(Modifier.weight(1f)) {
            when (activeProvider) {
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
                        SpotifyTheme {
                            SpotifyHomeContent(
                                spotifyCookie = spotifyCookie,
                                onOpenCollection = onSpotifyPlaylistClick,
                                onSearchClick = onSpotifySearchClick,
                            )
                        }
                    }
                }
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
    if (showProviders) {
        ModalBottomSheet(
            onDismissRequest = { showProviders = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showProviders = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close music providers")
                    }
                    Text(
                        "Music Extensions",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(
                        onClick = {
                            showProviders = false
                            showRepositoryManager = true
                        },
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Manage extensions")
                    }
                }
                Text(
                    "Choose a source for your Music home.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    providerOptions.forEachIndexed { index, provider ->
                        MusicProviderOptionRow(
                            provider = provider,
                            selected = provider == activeProvider,
                            canMoveUp = index > 0,
                            canMoveDown = index < providerOptions.lastIndex,
                            onMoveUp = { moveVisibleProvider(index, -1) },
                            onMoveDown = { moveVisibleProvider(index, 1) },
                            onClick = {
                                scope.launch {
                                    try {
                                        services.settings.setMusicHomeProvider(provider)
                                        error = null
                                        showProviders = false
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        error = "Could not save music provider. Please try again."
                                        showProviders = false
                                    }
                                }
                            },
                        )
                    }
                }
                if (repositories.isEmpty()) {
                    Text(
                        "Add a repository to browse optional music providers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            showProviders = false
                            showRepositoryManager = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Manage") }
                    Button(
                        onClick = {
                            showProviders = false
                            showAddRepository = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Add") }
                }
            }
        }
    }
    if (showRepositoryManager) {
        AlertDialog(
            onDismissRequest = { showRepositoryManager = false },
            title = { Text("Manage music extensions") },
            text = {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (repositories.isEmpty()) {
                        Text(
                            "No repositories added yet. Add a GitHub link to browse provider updates.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    repositories.forEach { repository ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Provider repository", style = MaterialTheme.typography.labelMedium)
                                        Text(
                                            repository,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        enabled = repository !in loadingRepositories,
                                        onClick = {
                                            scope.launch {
                                                loadingRepositories = loadingRepositories + repository
                                                try {
                                                    catalogues = catalogues +
                                                        (repository to MusicProviderCatalogRepository.fetch(repository))
                                                    repositoryErrors = repositoryErrors - repository
                                                    error = null
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (failure: Exception) {
                                                    repositoryErrors = repositoryErrors +
                                                        (repository to (failure.message ?: "Could not load this repository."))
                                                } finally {
                                                    loadingRepositories = loadingRepositories - repository
                                                }
                                            }
                                        },
                                    ) {
                                        if (repository in loadingRepositories) {
                                            CircularProgressIndicator(Modifier.size(20.dp))
                                        } else {
                                            Icon(Icons.Default.Refresh, contentDescription = "Refresh repository")
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    services.settings.removeMusicProviderRepository(repository)
                                                    catalogues = catalogues - repository
                                                    repositoryErrors = repositoryErrors - repository
                                                    error = null
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    error = "Could not remove this repository."
                                                }
                                            }
                                        },
                                    ) { Text("Remove") }
                                }
                                repositoryErrors[repository]?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error)
                                }
                                catalogues[repository].orEmpty().forEach { manifest ->
                                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                    val version = installed[manifest.id]
                                    Text(manifest.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "v${manifest.version} · ${manifest.description}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "Playlist audio uses YouTube matches, not Spotify audio.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            enabled = version != manifest.version,
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        services.settings.setInstalledMusicProvider(
                                                            manifest.id,
                                                            manifest.version,
                                                        )
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
                                                            error = null
                                                        } catch (cancelled: CancellationException) {
                                                            throw cancelled
                                                        } catch (_: Exception) {
                                                            error = "Could not remove this provider."
                                                        }
                                                    }
                                                },
                                            ) { Text("Uninstall") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRepositoryManager = false
                        showAddRepository = true
                    },
                ) { Text("Add repository") }
            },
            dismissButton = {
                TextButton(onClick = { showRepositoryManager = false }) { Text("Close") }
            },
        )
    }
    if (showAddRepository) {
        AlertDialog(
            onDismissRequest = { showAddRepository = false },
            title = { Text("Add music provider repository") },
            text = {
                Column {
                    Text("Paste a GitHub repository link or a raw catalog.json link. Nothing is checked until you add a link.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = repositoryInput,
                        onValueChange = { repositoryInput = it },
                        label = { Text("Repository URL") },
                        placeholder = { Text("https://github.com/owner/repository") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = repositoryInput.isNotBlank(),
                    onClick = {
                        scope.launch {
                            try {
                                val normalized = MusicProviderCatalogRepository
                                    .normalizeRepositoryUrl(repositoryInput)
                                services.settings.addMusicProviderRepository(normalized)
                                showAddRepository = false
                                repositoryInput = ""
                                error = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Could not add this repository."
                            }
                        }
                    },
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddRepository = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MusicProviderLogo(provider: MusicProvider, modifier: Modifier = Modifier) {
    when (provider) {
        MusicProvider.YouTubeMusic -> Image(
            painter = painterResource(R.drawable.ic_youtube_music_logo),
            contentDescription = null,
            modifier = modifier,
        )
        MusicProvider.Spotify -> Image(
            painter = painterResource(R.drawable.ic_spotify_logo),
            contentDescription = null,
            modifier = modifier,
        )
        MusicProvider.Offline -> Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }
}

@Composable
private fun MusicProviderOptionRow(
    provider: MusicProvider,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.52f),
        border = if (selected) BorderStroke(1.dp, colors.primary.copy(alpha = 0.7f)) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicProviderLogo(provider, Modifier.size(42.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Text(
                    provider.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                )
                Text(
                    provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.82f)
                    else colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = colors.primary,
                )
            }
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move ${provider.title} up",
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move ${provider.title} down",
                )
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
