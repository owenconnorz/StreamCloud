package com.aioweb.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aioweb.app.data.ServiceLocator
import com.aioweb.app.data.ytmusic.YtMusicLibraryRepository
import com.aioweb.app.data.ytmusic.YtPlayback
import com.aioweb.app.data.ytmusic.YtmSong
import androidx.compose.foundation.lazy.rememberLazyListState
import com.aioweb.app.ui.util.verticalScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YouTube Music playlist detail — Metrolist parity.
 *
 * Layout:
 *   1. Hero header with large cover art (derived from the first track's artwork
 *      if the playlist doesn't have its own) + title + track count + Play /
 *      Shuffle / MoreVert buttons.
 *   2. Track list where each row exposes a 3-dot menu with Play, Play next,
 *      Add to queue, Download / Remove download, Share.
 *
 * Tapping the MoreVert (⋮) button next to Shuffle opens [PlaylistActionsSheet]
 * with: Edit, Sync, Add to queue, Download all, Share, Delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtPlaylistScreen(
    playlistId: String,
    title: String,
    onBack: () -> Unit,
    onPlay: (YtmSong) -> Unit = {},
) {
    val context = LocalContext.current
    val sl = remember(context) { ServiceLocator.get(context) }
    val cookie by sl.settings.ytMusicCookie.collectAsState(initial = "")
    var tracks by remember(playlistId) { mutableStateOf<List<YtmSong>?>(null) }
    var error by remember(playlistId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var showPlaylistMenu by remember { mutableStateOf(false) }
    var syncTrigger by remember { mutableStateOf(0) }

    val downloadProgress by com.aioweb.app.data.downloads.MusicDownloader.progressFlow
        .collectAsState(initial = emptyMap())

    val playlistThumbsJson by sl.settings.playlistThumbsJson.collectAsState(initial = "{}")
    val customThumbUri = remember(playlistThumbsJson, playlistId) {
        val regex = Regex("\"${Regex.escape(playlistId)}\"\\s*:\\s*\"([^\"]+)\"")
        regex.find(playlistThumbsJson)?.groupValues?.getOrNull(1)
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
        scope.launch { sl.settings.setPlaylistThumb(playlistId, uri.toString()) }
    }

    LaunchedEffect(playlistId, cookie, syncTrigger) {
        if (cookie.isBlank()) {
            error = "Not signed in."
            return@LaunchedEffect
        }
        error = null

        val cached = withContext(Dispatchers.IO) {
            com.aioweb.app.data.ytmusic.PlaylistCache.read(context, playlistId)
        }
        if (!cached.isNullOrEmpty()) tracks = cached

        val fresh = withContext(Dispatchers.IO) {
            runCatching { YtMusicLibraryRepository.playlistTracks(cookie, playlistId) }
                .getOrElse {
                    if (cached.isNullOrEmpty()) error = it.message
                    null
                }
        }
        if (fresh != null) {
            tracks = fresh
            if (fresh.isNotEmpty()) {
                com.aioweb.app.data.ytmusic.PlaylistCache.write(context, playlistId, fresh)
            }
        } else if (cached == null) {
            tracks = emptyList()
        }
    }

    fun playSongHandoff(list: List<YtmSong>, index: Int) {
        val s = list.getOrNull(index) ?: return
        onPlay(s)
        scope.launch {
            runCatching { YtPlayback.playPlaylist(context, list, index) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            com.aioweb.app.ui.player.GlobalMiniPlayer()
        },
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
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScrollbar(listState),
                ) {
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
                        onEditCover = { pickThumb.launch(arrayOf("image/*")) },
                        onMoreOptions = { showPlaylistMenu = true },
                    )
                }
                itemsIndexed(
                    list,
                    key = { idx, s -> "pt_${s.videoId}_$idx" },
                ) { index, s ->
                    PlaylistTrackRow(
                        song = s,
                        downloadFraction = downloadProgress[YtPlayback.watchUrl(s.videoId)],
                        onClick = { playSongHandoff(list, index) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
            }  // closes list != null branch

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
                                com.aioweb.app.data.ytmusic.PlaylistCache.delete(context, playlistId)
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
    LaunchedEffect(song.videoId, downloadFraction) {
        if (downloadFraction == null) downloaded = YtPlayback.isDownloaded(context, song)
    }

    val nowPlayingId by com.aioweb.app.audio.PlaybackBus.nowPlayingMediaId.collectAsState()
    val isPlaying by com.aioweb.app.audio.PlaybackBus.isPlaying.collectAsState()
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
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
                com.aioweb.app.ui.components.PlayingBars(
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
            com.aioweb.app.ui.components.SongRowMenu(song = song, onPlay = onClick)
        }
    }
}
