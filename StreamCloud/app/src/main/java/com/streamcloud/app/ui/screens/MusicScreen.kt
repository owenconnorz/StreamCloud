package com.streamcloud.app.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import coil.compose.AsyncImage
import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.data.ytmusic.HomeSection
import com.streamcloud.app.data.ytmusic.YtMusicHomeTaxonomy
import com.streamcloud.app.data.ytmusic.YtmSong
import com.streamcloud.app.ui.viewmodel.MusicViewModel
import com.streamcloud.app.ui.util.verticalScrollbar
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Top hits 2026", "Lo-fi beats", "Chill", "Workout",
    "Throwback", "K-pop", "Hip hop", "Jazz", "EDM", "Acoustic"
)

@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun MusicScreen(
    onArtistClick: (url: String, thumbnail: String?) -> Unit = { _, _ -> },
    onOpenPlaylist: (id: String, title: String) -> Unit = { _, _ -> },
    onProfileClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSearchWithQuery: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: MusicViewModel = viewModel(factory = MusicViewModel.factory(context))
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val dlScope = rememberCoroutineScope()



    var player by remember { mutableStateOf<androidx.media3.common.Player?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val controller = com.streamcloud.app.audio.MusicController.get(context.applicationContext)
            controller.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val msg = "Audio playback failed (${error.errorCodeName}): ${error.message}"
                    com.streamcloud.app.data.AppLogger.e("MusicPlayback", msg, error.cause)
                    playerError = msg
                }
                override fun onRepeatModeChanged(repeatMode: Int) { vm.setRepeatMode(repeatMode) }
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    vm.setShuffle(shuffleModeEnabled)
                }
            })

            vm.setRepeatMode(controller.repeatMode)
            vm.setShuffle(controller.shuffleModeEnabled)
            player = controller
            isPlaying = controller.isPlaying
        } catch (e: Exception) {
            playerError = "Couldn't connect to media service: ${e.message}"
        }
    }



    val nowPlaying = state.nowPlayingTrack
        ?: state.tracks.firstOrNull { it.url == state.nowPlayingUrl }
        ?: state.homeFeed.firstOrNull { it.url == state.nowPlayingUrl }
    val fallbackSongs = remember(state.homeFeed) { state.homeFeed.mapNotNull { it.toYtmSong() } }
    val approvedYtSections = remember(state.ytHome.sections, fallbackSongs) {
        YtMusicHomeTaxonomy.mapSections(state.ytHome.sections, fallbackSongs)
    }
    val listenAgainTracks = remember(state.mostPlayed) { state.mostPlayed.take(10) }
    val forgottenFavoritesTracks = remember(state.liked) { state.liked.takeLast(10).asReversed() }
    val recentlyPlayedTracks = remember(state.recent) { state.recent.take(10) }
    val fromLibraryTracks = remember(state.liked) { state.liked.take(10) }

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(state.ytHomeLoading, state.homeLoading) {
        if (!state.ytHomeLoading && !state.homeLoading) isRefreshing = false
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            vm.loadYtHome()
            vm.loadHomeFeed()
        },
        state = pullRefreshState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val listState = rememberLazyListState()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(
                    state = listState,
                    width = 5.dp,
                    dragGestureWidth = 56.dp,
                ),
            state = listState,
            contentPadding = PaddingValues(bottom = if (nowPlaying != null) 180.dp else 80.dp),
        ) {
            item {
                MusicHeader(
                    onProfileClick = onProfileClick,
                    onHistoryClick = { showHistory = true },
                    onSearchClick = onSearchClick,
                    onTrendingClick = { onSearchWithQuery("Top hits 2026") },
                )
            }



            val combinedError = state.error ?: playerError
            if (combinedError != null) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Search,
                            null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            combinedError,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { playerError = null; vm.search(query) }) {
                            Text("Dismiss")
                        }
                    }
                }
            }


            if (query.isBlank()) {
                item { SuggestionsRow(onPick = { query = it; vm.search(it) }) }

                item { SectionTitle("Listen Again") }
                if (listenAgainTracks.isEmpty()) {
                    item(key = "listen_again_empty") { HomeCategoryEmptyRow() }
                } else {
                    items(listenAgainTracks, key = { "listen_again_${it.url}" }) { entity ->
                        LibraryRow(entity, isPlaying = isPlaying && state.nowPlayingUrl == entity.url) {
                            playLibraryTrack(entity, state.nowPlayingUrl, player, vm)
                        }
                    }
                }

                item { SectionTitle("Forgotten Favorites") }
                if (forgottenFavoritesTracks.isEmpty()) {
                    item(key = "forgotten_favorites_empty") { HomeCategoryEmptyRow() }
                } else {
                    items(forgottenFavoritesTracks, key = { "forgotten_favorites_${it.url}" }) { entity ->
                        LibraryRow(entity, isPlaying = isPlaying && state.nowPlayingUrl == entity.url) {
                            playLibraryTrack(entity, state.nowPlayingUrl, player, vm)
                        }
                    }
                }

                approvedYtSections.forEachIndexed { idx, section ->
                    when (section) {
                        is HomeSection.MoodChips -> {
                            item(key = "yt_chips_title_$idx") { SectionTitle(section.title) }
                            item(key = "yt_chips_$idx") {
                                YtMoodChipRow(section.chips, onChipClick = { label -> onSearchWithQuery(label) })
                            }
                        }
                        is HomeSection.PlaylistRail -> {
                            item(key = "yt_prail_title_$idx") { SectionTitle(section.title) }
                            if (section.items.isEmpty()) {
                                item(key = "yt_prail_empty_$idx") { HomeCategoryEmptyRow() }
                            } else {
                                item(key = "yt_prail_$idx") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        items(section.items) { pl ->
                                            YtHomePlaylistCard(pl) {
                                                if (pl.isVideo) {
                                                    // Music video card — play directly; do NOT navigate
                                                    // to YtPlaylistScreen (it can't browse a videoId).
                                                    dlScope.launch {
                                                        val song = com.streamcloud.app.data.ytmusic.YtmSong(
                                                            videoId = pl.id,
                                                            title   = pl.title,
                                                            artist  = pl.subtitle
                                                                ?.substringBefore(" •")?.trim()
                                                                ?.substringBefore(" · ")?.trim()
                                                                .orEmpty(),
                                                            album            = null,
                                                            thumbnail        = pl.thumbnail,
                                                            durationSeconds  = null,
                                                            isVideo          = true,
                                                        )
                                                        runCatching {
                                                            com.streamcloud.app.data.ytmusic.YtPlayback
                                                                .playSong(context, song)
                                                        }.onFailure { e ->
                                                            Log.e("MusicScreen", "PlaylistRail video play failed for ${pl.id}: ${e.message}", e)
                                                        }
                                                    }
                                                } else {
                                                    onOpenPlaylist(pl.id, pl.title)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is HomeSection.SongRail -> {
                            item(key = "yt_srail_title_$idx") { SectionTitle(section.title) }
                            if (section.items.isEmpty()) {
                                item(key = "yt_srail_empty_$idx") { HomeCategoryEmptyRow() }
                            } else {
                                items(section.items, key = { s -> "yt_srail_${idx}_${s.videoId}" }) { s ->
                                    YtHomeSongRow(s)
                                }
                            }
                        }
                    }
                }

                item { SectionTitle("Recently Played") }
                if (recentlyPlayedTracks.isEmpty()) {
                    item(key = "recently_played_empty") { HomeCategoryEmptyRow() }
                } else {
                    items(recentlyPlayedTracks, key = { "recently_played_${it.url}" }) { entity ->
                        LibraryRow(entity, isPlaying = isPlaying && state.nowPlayingUrl == entity.url) {
                            playLibraryTrack(entity, state.nowPlayingUrl, player, vm)
                        }
                    }
                }

                item { SectionTitle("From Your Library") }
                if (fromLibraryTracks.isEmpty()) {
                    item(key = "from_library_empty") { HomeCategoryEmptyRow() }
                } else {
                    items(fromLibraryTracks, key = { "from_library_${it.url}" }) { entity ->
                        LibraryRow(entity, isPlaying = isPlaying && state.nowPlayingUrl == entity.url) {
                            playLibraryTrack(entity, state.nowPlayingUrl, player, vm)
                        }
                    }
                }
            }


            // ── SimpMusic-style: top results + live suggestions ──
            if (query.isNotBlank()) {
                val sections = state.sections
                val topArtist = sections.artists.firstOrNull()
                val topSongs = sections.songs.take(if (topArtist != null) 2 else 3)

                topArtist?.let { artist ->
                    item(key = "sr_artist_${artist.url}") {
                        SearchResultRow(
                            thumbnail = artist.thumbnail,
                            title = artist.name,
                            subtitle = "Artists",
                            isCircle = true,
                            onClick = { onArtistClick(artist.url, artist.thumbnail) },
                        )
                    }
                }

                items(topSongs, key = { "sr_song_${it.url}" }) { track ->
                    SearchResultRow(
                        thumbnail = track.thumbnail,
                        title = track.title,
                        subtitle = track.uploader,
                        isCircle = false,
                        onClick = {
                            if (state.nowPlayingUrl == track.url && (player?.isPlaying == true)) player?.pause()
                            else if (state.nowPlayingUrl == track.url) player?.play()
                            else vm.play(track) { audioUrl -> player?.let { playTrack(it, track, audioUrl) } }
                        },
                    )
                }

                if (topArtist == null && topSongs.isEmpty()) {
                    sections.albums.firstOrNull()?.let { album ->
                        item(key = "sr_album_${album.url}") {
                            val uri = Uri.parse(album.url)
                            val id = uri.getQueryParameter("list")
                                ?: uri.lastPathSegment?.takeIf { seg -> seg.isNotBlank() }
                                ?: album.url
                            SearchResultRow(
                                thumbnail = album.thumbnail,
                                title = album.title,
                                subtitle = album.artist,
                                isCircle = false,
                                onClick = { onOpenPlaylist(id, album.title) },
                            )
                        }
                    }
                }

                items(state.suggestions, key = { "sug_$it" }) { suggestion ->
                    SuggestionListRow(
                        text = suggestion,
                        onClick = { query = suggestion; vm.search(suggestion) },
                    )
                }
            }
            state.error?.let {
                item {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }





        if (showHistory) {
            HistorySheet(
                recent = state.recent,
                nowPlayingUrl = state.nowPlayingUrl,
                isPlaying = isPlaying,
                onPlay = { entity ->
                    val track = YtTrack(
                        title = entity.title, uploader = entity.artist,
                        durationSec = entity.durationSec,
                        url = entity.url, thumbnail = entity.thumbnail,
                    )
                    if (state.nowPlayingUrl == track.url && (player?.isPlaying == true)) player?.pause()
                    else if (state.nowPlayingUrl == track.url) player?.play()
                    else vm.play(track) { audioUrl -> player?.let { p -> playTrack(p, track, audioUrl) } }
                },
                onDismiss = { showHistory = false },
            )
        }
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
private fun buildMusicExoPlayer(context: android.content.Context): ExoPlayer {
    val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
    val mediaSourceFactory =
        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply { playWhenReady = true }
}

private fun playTrack(player: androidx.media3.common.Player, track: YtTrack, audioUrl: String) {
    player.setMediaItem(
        MediaItem.Builder()
            .setUri(audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.uploader)
                    .setArtworkUri(track.thumbnail?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()
    )
    player.prepare()
    player.play()
}

@Composable
private fun MusicHeader(
    onProfileClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onTrendingClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, top = 8.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Music",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onHistoryClick) {
            Icon(
                Icons.Default.History,
                contentDescription = "Recently played",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(onClick = onSearchClick) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search music",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(onClick = onTrendingClick) {
            Icon(
                Icons.Default.TrendingUp,
                contentDescription = "Trending",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        com.streamcloud.app.ui.components.ProfileButton(onClick = onProfileClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    recent: List<com.streamcloud.app.data.library.TrackEntity>,
    nowPlayingUrl: String?,
    isPlaying: Boolean,
    onPlay: (com.streamcloud.app.data.library.TrackEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Text(
            "Recently played",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (recent.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing played yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                items(recent, key = { "hist_${it.url}" }) { entity ->
                    LibraryRow(
                        entity = entity,
                        isPlaying = isPlaying && nowPlayingUrl == entity.url,
                        onClick = { onPlay(entity) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSearchField(query: String, loading: Boolean, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search songs, artists, albums") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = when {
            loading -> { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary) } }
            query.isNotEmpty() -> { { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear") } } }
            else -> null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SearchResultRow(
    thumbnail: String?,
    title: String,
    subtitle: String,
    isCircle: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val imgMod = Modifier.size(54.dp)
        com.streamcloud.app.ui.components.MusicThumbnail(
            url = thumbnail,
            modifier = imgMod,
            shape = if (isCircle) CircleShape else RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SuggestionListRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).rotate(-45f),
        )
    }
}

@Composable
private fun SuggestionsRow(onPick: (String) -> Unit) {
    Column {
        SectionTitle("Trending searches")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SUGGESTIONS) { s ->
                SuggestionChip(label = s, onClick = { onPick(s) })
            }
        }
    }
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun SearchModeChips(
    active: com.streamcloud.app.ui.viewmodel.SearchMode,
    onPick: (com.streamcloud.app.ui.viewmodel.SearchMode) -> Unit,
) {
    val modes = com.streamcloud.app.ui.viewmodel.SearchMode.values().toList()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(modes, key = { it.name }) { mode ->
            FilterChip(
                selected = mode == active,
                onClick = { onPick(mode) },
                label = { Text(mode.name) },
                leadingIcon = if (mode == active) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun TopResultCard(track: YtTrack, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.thumbnail,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                track.uploader,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClick) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun AlbumCard(album: com.streamcloud.app.data.newpipe.YtAlbum, onClick: () -> Unit = {}) {
    Column(Modifier.width(160.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = album.thumbnail,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            album.title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            album.artist,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistCard(
    artist: com.streamcloud.app.data.newpipe.YtArtist,
    onClick: () -> Unit = {},
) {
    Column(
        Modifier.width(140.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = artist.thumbnail,
            contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(120.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            artist.name,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        artist.subscriberLabel?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroCard(track: YtTrack, isPlaying: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = track.thumbnail,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                Modifier
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .align(Alignment.BottomEnd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            track.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.uploader,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SongRow(
    track: YtTrack,
    nowPlayingUrl: String?,
    isPlaying: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val highlighted = nowPlayingUrl == track.url
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (track.thumbnail != null) {
                AsyncImage(
                    model = track.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.uploader,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                null,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryRow(
    entity: com.streamcloud.app.data.library.TrackEntity,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (entity.thumbnail != null) {
                AsyncImage(
                    model = entity.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
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
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun YtMoodChipRow(
    chips: List<com.streamcloud.app.data.ytmusic.MoodChip>,
    onChipClick: (String) -> Unit = {},
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { "chip_${it.label}" }) { chip ->
            AssistChip(
                onClick = { onChipClick(chip.label) },
                label = { Text(chip.label) },
            )
        }
    }
}

@Composable
private fun YtHomePlaylistCard(
    pl: com.streamcloud.app.data.ytmusic.YtmPlaylist,
    onClick: () -> Unit = {},
) {
    Column(
        Modifier
            .width(150.dp)
            .clickable(onClick = onClick),
    ) {
        com.streamcloud.app.ui.components.MusicThumbnail(
            url = pl.thumbnail,
            size = 150.dp,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            pl.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )



        val displaySubtitle = if (pl.cachedTrackCount != null) {
            pl.subtitle
                ?.replace(Regex("\\d+\\s+songs?", RegexOption.IGNORE_CASE), "${pl.cachedTrackCount} songs")
                ?: "${pl.cachedTrackCount} songs"
        } else pl.subtitle
        displaySubtitle?.let {
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
private fun YtHomeSongRow(s: com.streamcloud.app.data.ytmusic.YtmSong) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onPlay = {
        scope.launch {
            runCatching { com.streamcloud.app.data.ytmusic.YtPlayback.playSong(context, s) }
                .onFailure { e -> Log.e("MusicScreen", "YtHomeSongRow play failed for ${s.videoId}: ${e.message}", e) }
        }
        Unit
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.streamcloud.app.ui.components.MusicThumbnail(
            url = s.thumbnail,
            size = 54.dp,
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                s.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        com.streamcloud.app.ui.components.SongRowMenu(song = s, onPlay = { onPlay() })
    }
}

private fun playLibraryTrack(
    entity: TrackEntity,
    nowPlayingUrl: String?,
    player: Player?,
    vm: MusicViewModel,
) {
    val track = YtTrack(
        title = entity.title,
        uploader = entity.artist,
        durationSec = entity.durationSec,
        url = entity.url,
        thumbnail = entity.thumbnail,
    )
    if (nowPlayingUrl == track.url && (player?.isPlaying == true)) player.pause()
    else if (nowPlayingUrl == track.url) player?.play()
    else vm.play(track) { audioUrl -> player?.let { playTrack(it, track, audioUrl) } }
}

private fun YtTrack.toYtmSong(): YtmSong? {
    val videoId = url.substringAfter("v=", "").substringBefore("&")
        .ifBlank { url.substringAfterLast("/") }
        .takeIf { it.isNotBlank() }
        ?: return null
    return YtmSong(
        videoId = videoId,
        title = title,
        artist = uploader,
        album = null,
        thumbnail = thumbnail,
        durationSeconds = durationSec,
        isVideo = isVideo,
    )
}

@Composable
private fun HomeCategoryEmptyRow() {
    Text(
        text = "Nothing here yet",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
