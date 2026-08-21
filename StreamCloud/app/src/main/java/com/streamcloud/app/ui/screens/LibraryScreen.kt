                DlMenuItem(
                    icon = Icons.Default.Share,
                    title = "Open with...",
                    subtitle = "Send or open with another app",
                    onClick = {
                        menuEntry = null
                        openWithChooser(context, dlEntry.filePath)
                    },
                )
            }
            HorizontalDivider()
            DlMenuItem(
                icon = Icons.Default.Delete,
                title = "Delete Movie",
                subtitle = "Remove from downloads permanently",
                isDestructive = true,
                onClick = {
                    menuEntry = null
                    scope.launch { MovieDownloader.remove(context, dlEntry.tmdbId) }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun openInSamsungMyFiles(context: android.content.Context, filePath: String) {
    val samsungPkg = "com.sec.android.app.myfiles"
    val moviesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
    val streamCloudDir = java.io.File(moviesDir, "StreamCloud")
    val folderUri = android.net.Uri.fromFile(streamCloudDir)
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setPackage(samsungPkg)
                setDataAndType(folderUri, "resource/folder")
            }
        )
        return
    } catch (_: Exception) { }
    try {
        val launch = context.packageManager.getLaunchIntentForPackage(samsungPkg)
        if (launch != null) { context.startActivity(launch); return }
    } catch (_: Exception) { }
    openWithChooser(context, filePath)
}

private fun openWithChooser(context: android.content.Context, filePath: String) {
    val uri: android.net.Uri = if (filePath.startsWith("content://")) {
        android.net.Uri.parse(filePath)
    } else {
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))
        } catch (_: Exception) { return }
    }
    try {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Open with",
            )
        )
    } catch (_: Exception) { }
}

@Composable
private fun DlMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDestructive) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibFilterChip(
    label: String,
    selected: Boolean,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) accentColor.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun LibTile(
    title: String,
    icon: ImageVector,
    count: Int,
    thumbs: List<String>,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbs.isEmpty()) {
                Icon(
                    icon, title,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(72.dp),
                )
            } else {

                Column {
                    Row(Modifier.weight(1f)) {
                        ThumbCell(thumbs.getOrNull(0), Modifier.weight(1f))
                        ThumbCell(thumbs.getOrNull(1), Modifier.weight(1f))
                    }
                    Row(Modifier.weight(1f)) {
                        ThumbCell(thumbs.getOrNull(2), Modifier.weight(1f))
                        ThumbCell(thumbs.getOrNull(3), Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$count " + if (count == 1) "track" else "tracks",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThumbCell(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().background(Color(0xFF222222))) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun BackButton(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            "Back",
            tint = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun LibTrackRow(
    entity: TrackEntity,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = entity.thumbnail,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entity.title, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                entity.artist, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (entity.localPath != null) {
            Icon(
                Icons.Default.DownloadDone, "Downloaded",
                tint = accentColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun TrackEntity.toYtmSong(): YtmSong {
    val videoId = url
        .substringAfter("v=", missingDelimiterValue = "")
        .substringBefore("&")
        .ifBlank { url.substringAfterLast("/") }
    return YtmSong(
        videoId = videoId,
        title = title,
        artist = artist,
        album = null,
        thumbnail = thumbnail,
        durationSeconds = durationSec,
    )
}

@Composable
private fun LibrarySubHeader(
    tab: LibTab,
    localTileCount: Int,
    ytLibrary: YtMusicLibrary,
    ytLoading: Boolean,
    onRefresh: () -> Unit,
) {

    val count = when (tab) {
        LibTab.Playlists -> localTileCount + ytLibrary.playlists.size
        LibTab.Albums -> ytLibrary.albums.size
        LibTab.Artists -> ytLibrary.artists.size
        LibTab.Songs -> ytLibrary.likedSongs.size
    }
    val label = when (tab) {
        LibTab.Playlists -> "playlists"
        LibTab.Albums -> "albums"
        LibTab.Artists -> "artists"
        LibTab.Songs -> "songs"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Date added",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ArrowDropDown, null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "$count $label",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh, enabled = !ytLoading) {
            if (ytLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Sync YouTube Music",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun LocalSystemTile(
    title: String,
    icon: ImageVector,
    count: Int,
    thumbs: List<String>,
    onClick: () -> Unit,
) {
    val topThumbs = thumbs.take(4)
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (topThumbs.size >= 4) {

                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        MosaicCell(topThumbs[0], Modifier.weight(1f).fillMaxHeight())
                        MosaicCell(topThumbs[1], Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.weight(1f)) {
                        MosaicCell(topThumbs[2], Modifier.weight(1f).fillMaxHeight())
                        MosaicCell(topThumbs[3], Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else if (topThumbs.isNotEmpty()) {
                AsyncImage(
                    model = topThumbs.first(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$count songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalPlaylistGridTile(
    name: String,
    customThumb: String?,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete playlist") },
            text = { Text("Delete \"$name\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
    Column(
        modifier = Modifier.clickable { },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!customThumb.isNullOrBlank()) {
                AsyncImage(
                    model = android.net.Uri.parse(customThumb),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.PlaylistPlay,
                    contentDescription = name,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
            ) {
                Icon(
                    Icons.Default.Close, "Delete",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Local playlist",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MosaicCell(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun YtPlaylistTile(pl: YtmPlaylist, customThumb: String? = null, onClick: () -> Unit) {
    val thumbModel: Any? = when {
        !customThumb.isNullOrBlank() -> android.net.Uri.parse(customThumb)
        !pl.thumbnail.isNullOrBlank() -> pl.thumbnail
        else -> null
    }
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = pl.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.PlaylistPlay,
                    contentDescription = pl.title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            pl.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        pl.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun YtArtistTile(a: YtmLibraryArtist, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = a.thumbnail,
            contentDescription = a.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            a.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun YtSongRow(s: YtmSong, onClick: () -> Unit) {
    val context = LocalContext.current
    var downloaded by remember(s.videoId) {
        mutableStateOf(com.streamcloud.app.data.ytmusic.YtPlayback.isDownloaded(context, s))
    }




    LaunchedEffect(s.videoId) {
        val url = com.streamcloud.app.data.ytmusic.YtPlayback.watchUrl(s.videoId)
        com.streamcloud.app.data.downloads.YtMusicDownloadUtil.downloads.collect { dlMap ->
            val state = dlMap[url]?.state
            downloaded = (state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED)
                || com.streamcloud.app.data.downloads.MusicDownloader.isDownloaded(context, url)
        }
    }


    val downloadProgressMap by com.streamcloud.app.data.downloads.MusicDownloader.progressFlow
        .collectAsState(initial = emptyMap())
    LaunchedEffect(s.videoId, downloadProgressMap) {
        if (downloadProgressMap[com.streamcloud.app.data.ytmusic.YtPlayback.watchUrl(s.videoId)] == null) {
            downloaded = com.streamcloud.app.data.ytmusic.YtPlayback.isDownloaded(context, s)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = s.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(onClick = onClick)) {
            Text(
                s.title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                    s.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        com.streamcloud.app.ui.components.SongRowMenu(song = s, onPlay = onClick)
    }
}

@Composable
private fun EmptyStateRow(message: String, notSignedIn: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (notSignedIn) Icons.Default.AutoAwesome else Icons.Default.CloudDone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (notSignedIn) "Sign in to YouTube Music to sync your library." else message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun YtMusicSyncHeader(
    signedIn: Boolean,
    loading: Boolean,
    library: YtMusicLibrary,
    onRefresh: () -> Unit,
) {
    if (!signedIn) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CloudDone, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "YouTube Music · Synced",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            val subtitle = when {
                loading -> "Refreshing…"
                library.failureReason != null -> library.failureReason.orEmpty()
                library.likedSongs.isEmpty() && library.playlists.isEmpty() &&
                    library.albums.isEmpty() && library.artists.isEmpty() ->
                    "Nothing in your YouTube Music library yet."
                else -> "${library.playlists.size} playlists · " +
                    "${library.albums.size} albums · " +
                    "${library.artists.size} artists · " +
                    "${library.likedSongs.size} liked"
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, "Refresh")
            }
        }
    }
}

@Composable
private fun YtPlaylistSection(
    title: String,
    playlists: List<YtmPlaylist>,
    onClick: (YtmPlaylist) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(playlists, key = { "yp_${it.id}" }) { pl ->
            Column(
                Modifier
                    .width(150.dp)
                    .clickable { onClick(pl) },
            ) {
                AsyncImage(
                    model = pl.thumbnail,
                    contentDescription = pl.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    pl.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                pl.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun YtArtistsSection(
    artists: List<YtmLibraryArtist>,
    onClick: (YtmLibraryArtist) -> Unit,
) {
    Text(
        "Subscribed artists",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(artists, key = { "ya_${it.channelId}" }) { a ->
            Column(
                Modifier
                    .width(120.dp)
                    .clickable { onClick(a) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = a.thumbnail,
                    contentDescription = a.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    a.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun YtSongsSection(
    title: String,
    songs: List<YtmSong>,
    onSongClick: (YtmSong) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    val displayed = if (showAll) songs else songs.take(5)

    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )

    Column {
        displayed.forEach { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = s.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSongClick(s) },
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable { onSongClick(s) }) {
                    Text(
                        s.title,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        s.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                com.streamcloud.app.ui.components.SongRowMenu(song = s, onPlay = { onSongClick(s) })
            }
        }
        if (songs.size > 5) {
            TextButton(
                onClick = { showAll = !showAll },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(if (showAll) "Show less" else "View all ${songs.size} liked songs")
            }
        }
    }
}

@Composable
private fun CreatePlaylistTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Create new playlist",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            "New playlist",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Add songs from anywhere",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CreateLocalPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create playlist") },
        text = {
            Column {
                Text(
                    "Give your playlist a name. You can add songs later from any track's 3-dot menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Playlist name") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

