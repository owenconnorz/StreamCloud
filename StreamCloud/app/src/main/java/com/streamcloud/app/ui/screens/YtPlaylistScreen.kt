package com.streamcloud.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository
import com.streamcloud.app.data.ytmusic.YtMusicPlaylistRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import androidx.compose.foundation.lazy.rememberLazyListState
import com.streamcloud.app.ui.util.verticalScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtPlaylistScreen(
    playlistId: String,
    title: String,
    initialThumb: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sl = remember(context) { ServiceLocator.get(context) }
    val cookie by sl.settings.ytMusicCookie.collectAsState(initial = "")
    var tracks by remember(playlistId) { mutableStateOf<List<YtmSong>?>(null) }
    var error by remember(playlistId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var showPlaylistMenu by remember { mutableStateOf(false) }
    var showThumbSheet   by remember { mutableStateOf(false) }
    var syncTrigger by remember { mutableStateOf(0) }

    // ── Search ────────────────────────────────────────────────────────────────
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery  by remember { mutableStateOf("") }
    val searchFocus  = remember { FocusRequester() }

    // ── Snackbar ──────────────────────────────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }


    val okhttpProgress by com.streamcloud.app.data.downloads.MusicDownloader.progressFlow
        .collectAsState(initial = emptyMap())




    val exoDownloads by com.streamcloud.app.data.downloads.YtMusicDownloadUtil.downloads
        .collectAsState()


    val downloadProgress = remember(okhttpProgress, exoDownloads) {
        val merged = okhttpProgress.toMutableMap()
        exoDownloads.forEach { (downloadId, dl) ->
            val videoId = com.streamcloud.app.data.downloads.YtMusicDownloadUtil
                .videoIdFromDownloadId(downloadId)
            if (!merged.containsKey(videoId)) {
                when (dl.state) {
                    androidx.media3.exoplayer.offline.Download.STATE_QUEUED,
                    androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING ->
                        merged[videoId] = dl.percentDownloaded.coerceIn(0f, 100f) / 100f
                    else -> Unit
                }
            }
        }
        merged
    }

    val playlistThumbsJson by sl.settings.playlistThumbsJson.collectAsState(initial = "{}")
    val customThumbUri = remember(playlistThumbsJson, playlistId, initialThumb) {
        // User-saved custom thumb takes priority, then the album thumbnail passed from the artist page
        val saved = run {
            val regex = Regex("\"${Regex.escape(playlistId)}\"\\s*:\\s*\"([^\"]+)\"")
            regex.find(playlistThumbsJson)?.groupValues?.getOrNull(1)
        }
        saved ?: initialThumb
    }
    val hasCustomThumb = remember(playlistThumbsJson, playlistId) {
        Regex("\"${Regex.escape(playlistId)}\"\\s*:\\s*\"([^\"]+)\"")
            .containsMatchIn(playlistThumbsJson)
    }
    val pickThumb = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        // Save locally
        scope.launch { sl.settings.setPlaylistThumb(playlistId, uri.toString()) }
        // Sync to YouTube Music only when a cookie is available
        scope.launch {
            if (cookie.isBlank()) {
                snackbarHostState.showSnackbar("Thumbnail saved ✓")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // Read + compress the image to JPEG ≤ 1500 px
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val src = BitmapFactory.decodeStream(stream) ?: return@use null
                        val maxSide = 1500
                        val scaled = if (src.width > maxSide || src.height > maxSide) {
                            val ratio = maxSide.toFloat() / maxOf(src.width, src.height)
                            Bitmap.createScaledBitmap(
                                src,
                                (src.width * ratio).toInt(),
                                (src.height * ratio).toInt(),
                                true,
                            )
                        } else src
                        ByteArrayOutputStream().also { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }.toByteArray()
                    } ?: return@runCatching false
                    YtMusicPlaylistRepository.uploadAndSetPlaylistThumbnail(
                        cookie = cookie,
                        playlistId = playlistId,
                        imageBytes = bytes,
                        mimeType = mimeType,
                    )
                }.getOrElse { false }
            }
            snackbarHostState.showSnackbar(
                if (ok) "Thumbnail synced to YouTube Music ✓"
                else "Thumbnail saved locally (sync to YouTube Music failed)"
            )
        }
    }

    LaunchedEffect(playlistId, cookie, syncTrigger) {
        if (cookie.isBlank()) {
            error = "Not signed in."
            return@LaunchedEffect
        }
        error = null

        val cached = withContext(Dispatchers.IO) {
            com.streamcloud.app.data.ytmusic.PlaylistCache.read(context, playlistId)
        }
        if (!cached.isNullOrEmpty()) tracks = cached

        val fresh = withContext(Dispatchers.IO) {
            runCatching { YtMusicLibraryRepository.playlistTracks(cookie, playlistId, externalThumb = customThumbUri) }
                .getOrElse {
                    if (cached.isNullOrEmpty()) error = it.message
                    null
                }
        }
        if (fresh != null) {
            tracks = fresh
            if (fresh.isNotEmpty()) {
                com.streamcloud.app.data.ytmusic.PlaylistCache.write(context, playlistId, fresh)


                com.streamcloud.app.data.ytmusic.LibraryCache.updatePlaylistCount(
                    context, playlistId, fresh.size,
                )




            }
        } else if (cached == null) {
            tracks = emptyList()
        }
    }

    fun playSongHandoff(list: List<YtmSong>, index: Int) {
        if (list.getOrNull(index) == null) return
        scope.launch {
            runCatching { YtPlayback.playPlaylist(context, list, index) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        // ── Inline search text field ────────────────────────────
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus),
                            decorationBox = { inner ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search in playlist…",
                                        style = TextStyle(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 16.sp,
                                        ),
                                    )
                                }
                                inner()
                            },
                        )
                        LaunchedEffect(searchActive) {
                            if (searchActive) searchFocus.requestFocus()
                        }
                    } else {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (searchActive) {
                            searchActive = false
                            searchQuery = ""
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (searchActive && searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    } else if (!searchActive) {
                        IconButton(onClick = {
                            searchActive = true
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Search in playlist")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val list = tracks
        when {
            list == null && error == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            error != null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    error ?: "Couldn't load playlist",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            list != null -> {
                // Apply search filter — empty query shows the full list
                val query = searchQuery.trim().lowercase()
                val filteredList = list.withIndex().filter { indexedSong ->
                    val s = indexedSong.value
                    query.isBlank() ||
                        s.title.lowercase().contains(query) ||
                            s.artist.lowercase().contains(query)
                }

                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScrollbar(
                            state = listState,
                            width = 8.dp,
                            dragGestureWidth = 24.dp,
                            minThumbHeight = 72.dp,
                            fixedThumbHeight = 72.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            activeColor = MaterialTheme.colorScheme.secondary,
                            alwaysVisible = true,
                            minItemCountForScroll = 15,
                            headerItems = if (searchActive) 0 else 1,
                        ),
                ) {
                // Hide the hero when search is active so results start immediately
                if (!searchActive) {
                    item {
                        PlaylistHero(
                            title = title,
                            coverArt = customThumbUri ?: list.firstOrNull()?.thumbnail,
                            trackCount = list.size,
                            onPlay = { playSongHandoff(list, 0) },
                            onShuffle = {
                                val shuffled = list.shuffled()
                                scope.launch {
                                    runCatching { YtPlayback.playPlaylist(context, shuffled, 0) }
                                }
                            },
                            onEditCover = { showThumbSheet = true },
                            onMoreOptions = { showPlaylistMenu = true },
                        )
                    }
                }
                if (searchActive && filteredList.isEmpty() && query.isNotBlank()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No songs match \"$searchQuery\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                itemsIndexed(
                    filteredList,
                    key = { _, indexedSong -> "pt_${indexedSong.index}_${indexedSong.value.videoId}" },
                ) { _, indexedSong ->
                    PlaylistTrackRow(
                        song = indexedSong.value,
                        downloadFraction = downloadProgress[indexedSong.value.videoId],
                        onClick = { playSongHandoff(list, indexedSong.index) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
            }

        }

        if (showThumbSheet) {
            ModalBottomSheet(onDismissRequest = { showThumbSheet = false }) {
                ListItem(
                    headlineContent = { Text("Choose from library") },
                    leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showThumbSheet = false
                        pickThumb.launch(arrayOf("image/*"))
                    },
                )
                if (hasCustomThumb) {
                    ListItem(
                        headlineContent = { Text("Remove custom image", color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable {
                            scope.launch { sl.settings.setPlaylistThumb(playlistId, null) }
                            showThumbSheet = false
                        },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (showPlaylistMenu) {
            val currentList = tracks ?: emptyList()
            PlaylistActionsSheet(
                playlistId = playlistId,
                playlistTitle = title,
                songs = currentList,
                onDismiss = { showPlaylistMenu = false },
                onEdit = {
                    showPlaylistMenu = false
                    pickThumb.launch(arrayOf("image/*"))
                },
                onSync = {
                    showPlaylistMenu = false
                    syncTrigger++
                },
                onAddToQueue = {
                    showPlaylistMenu = false
                    scope.launch {
                        currentList.forEach { s ->
                            runCatching { YtPlayback.addToQueue(context, s) }
                        }
                    }
                },
                onDownloadAll = {
                    showPlaylistMenu = false
                    currentList.forEach { s ->
                        runCatching { YtPlayback.downloadSong(context, s) }
                    }
                },
                onShare = {
                    showPlaylistMenu = false
                    val url = "https://music.youtube.com/playlist?list=$playlistId"
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(send, "Share playlist"))
                },
                onDelete = {
                    showPlaylistMenu = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                com.streamcloud.app.data.ytmusic.PlaylistCache.delete(context, playlistId)
                            }
                        }
                        onBack()
                    }
                },
            )
        }
    }
}

@Composable
private fun PlaylistHero(
    title: String,
    coverArt: String?,
    trackCount: Int,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onEditCover: () -> Unit,
    onMoreOptions: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (coverArt != null) {
                AsyncImage(
                    model = coverArt,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(64.dp),
                )
            }
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = onEditCover,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit playlist cover",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "$trackCount songs",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPlay,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Play")
            }
            OutlinedButton(
                onClick = onShuffle,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Shuffle, null)
                Spacer(Modifier.width(6.dp))
                Text("Shuffle")
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onMoreOptions),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistActionsSheet(
    playlistId: String,
    playlistTitle: String,
    songs: List<YtmSong>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSync: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownloadAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111111),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            PlaylistActionRow(
                icon = Icons.Default.Edit,
                title = "Edit",
                subtitle = "Edit playlist",
                onClick = onEdit,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)
            PlaylistActionRow(
                icon = Icons.Default.Refresh,
                title = "Sync",
                subtitle = "Sync playlist with YouTube Music",
                onClick = onSync,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)
            PlaylistActionRow(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "Add to queue",
                subtitle = "Add to the bottom of your queue",
                onClick = onAddToQueue,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)
            PlaylistActionRow(
                icon = Icons.Default.Download,
                title = "Download",
                subtitle = "Download all songs for offline playback",
                onClick = onDownloadAll,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)
            PlaylistActionRow(
                icon = Icons.Default.Share,
                title = "Share",
                subtitle = "Share this playlist with others",
                onClick = onShare,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f), thickness = 0.5.dp)
            PlaylistActionRow(
                icon = Icons.Default.Delete,
                title = "Delete",
                subtitle = "Remove this playlist permanently",
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun PlaylistActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = tint,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    song: YtmSong,
    downloadFraction: Float?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    var downloaded by remember(song.videoId) {
        mutableStateOf(YtPlayback.isDownloaded(context, song))
    }







    LaunchedEffect(song.videoId) {
        val url = YtPlayback.watchUrl(song.videoId)
        com.streamcloud.app.data.downloads.YtMusicDownloadUtil.downloads.collect { dlMap ->
            val state = dlMap[song.videoId]?.state ?: dlMap[url]?.state
            downloaded = (state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED)
                || com.streamcloud.app.data.downloads.MusicDownloader.isDownloaded(context, url)
        }
    }


    LaunchedEffect(song.videoId, downloadFraction) {
        if (downloadFraction == null) downloaded = YtPlayback.isDownloaded(context, song)
    }

    val nowPlayingId by com.streamcloud.app.audio.PlaybackBus.nowPlayingMediaId.collectAsState()
    val isPlaying by com.streamcloud.app.audio.PlaybackBus.isPlaying.collectAsState()
    val rowMediaId = YtPlayback.watchUrl(song.videoId)
    val isCurrent = nowPlayingId == rowMediaId

    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            AsyncImage(
                model = song.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent) {
                com.streamcloud.app.ui.components.PlayingBars(
                    modifier = Modifier.fillMaxSize(),
                    paused = !isPlaying,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val sub = buildString {
                append(song.artist)
                if (!song.album.isNullOrBlank()) {
                    append(" · "); append(song.album)
                }
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (downloadFraction != null) {
            Box(
                Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = downloadFraction.coerceIn(0f, 1f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            com.streamcloud.app.ui.components.SongRowMenu(song = song, onPlay = onClick)
        }
    }
}
