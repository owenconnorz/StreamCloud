package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import coil.compose.AsyncImage
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.ui.viewmodel.MusicViewModel
import com.streamcloud.app.ui.viewmodel.SearchMode
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Top hits 2026", "Lo-fi beats", "Chill", "Workout",
    "Throwback", "K-pop", "Hip hop", "Jazz", "EDM", "Acoustic"
)

@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun MusicScreen(
    onArtistClick: (String) -> Unit = {},
    onOpenPlaylist: (id: String, title: String) -> Unit = { _, _ -> },
    onProfileClick: () -> Unit = {},
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (nowPlaying != null) 96.dp else 12.dp),
        ) {
            item {
                MusicHeader(
                    onProfileClick = onProfileClick,
                    onHistoryClick = { showHistory = true },
                )
            }
            item {
                MusicSearchField(
                    query = query,
                    loading = state.loading,
                    onQueryChange = { query = it },
                )
                LaunchedEffect(query) {
                    if (query.length >= 2) {
                        kotlinx.coroutines.delay(400)
                        vm.search(query)
                    }
                }
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


            if (query.isBlank() && state.tracks.isEmpty()) {
                item { SuggestionsRow(onPick = { query = it; vm.search(it) }) }


                if (state.liked.isNotEmpty()) {
                    item { SectionTitle("Liked songs") }
                    items(state.liked.take(5), key = { "lib_liked_${it.url}" }) { entity ->
                        LibraryRow(entity, isPlaying = isPlaying && state.nowPlayingUrl == entity.url) {
                            val track = YtTrack(
                                title = entity.title, uploader = entity.artist,
                                durationSec = entity.durationSec,
                                url = entity.url, thumbnail = entity.thumbnail,
                            )
                            if (state.nowPlayingUrl == track.url && (player?.isPlaying == true)) player?.pause()
                            else if (state.nowPlayingUrl == track.url) player?.play()
                            else vm.play(track) { audioUrl -> player?.let { playTrack(it, track, audioUrl) } }
                        }
                    }
                }





                state.ytHome.sections.forEachIndexed { idx, section ->
                    when (section) {
                        is com.streamcloud.app.data.ytmusic.HomeSection.MoodChips -> {
                            item(key = "yt_chips_$idx") { YtMoodChipRow(section.chips) }
                        }
                        is com.streamcloud.app.data.ytmusic.HomeSection.PlaylistRail -> {
                            item(key = "yt_prail_title_$idx") { SectionTitle(section.title) }
                            item(key = "yt_prail_$idx") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(section.items, key = { "${it.id}_$idx" }) { pl ->



                                        YtHomePlaylistCard(pl) {
                                            onOpenPlaylist(pl.id, pl.title)
                                        }
                                    }
                                }
                            }
                        }
                        is com.streamcloud.app.data.ytmusic.HomeSection.SongRail -> {
                            item(key = "yt_srail_title_$idx") { SectionTitle(section.title) }
                            item(key = "yt_srail_$idx") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(section.items, key = { "${it.videoId}_$idx" }) { s ->
                                        YtHomeSongCard(s)
                                    }
                                }
                            }
                        }
                    }
                }



                if (state.ytHome.sections.isEmpty() && state.homeFeed.isNotEmpty()) {
                    item { SectionTitle("Trending today") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.homeFeed.take(10), key = { "home_${it.url}" }) { track ->
                                HeroCard(
                                    track = track,
                                    isPlaying = isPlaying && state.nowPlayingUrl == track.url,
                                    onClick = {
                                        if (state.nowPlayingUrl == track.url && (player?.isPlaying == true)) player?.pause()
                                        else if (state.nowPlayingUrl == track.url) player?.play()
                                        else vm.play(track) { audioUrl -> player?.let { playTrack(it, track, audioUrl) } }
                                    }
                                )
                            }
                        }
                    }
                    item { SectionTitle("More from YouTube") }
                    items(state.homeFeed.drop(10), key = { "homerow_${it.url}" }) { track ->
                        SongRow(
                            track = track,
                            nowPlayingUrl = state.nowPlayingUrl,
                            isPlaying = isPlaying && state.nowPlayingUrl == track.url,
                            loading = state.resolvingUrl == track.url,
                            onClick = {
                                if (state.nowPlayingUrl == track.url && (player?.isPlaying == true)) player?.pause()
                                else if (state.nowPlayingUrl == track.url) player?.play()
                                else vm.play(track) { audioUrl -> player?.let { playTrack(it, track, audioUrl) } }
                            }
                        )
                    }
                } else if ((state.ytHomeLoading && state.ytHome.sections.isEmpty()) || state.homeLoading) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(40.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                } else {
                    item {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(96.dp).clip(CircleShape).background(
                                    Brush.linearGradient(
                                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                                    )
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Tap a vibe or search", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                            Text("Stream from YouTube · audio only", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }


            if (query.isNotBlank()) {
                item {
                    SearchModeChips(
                        active = state.searchMode,
                        onPick = { mode -> vm.setSearchMode(mode) },
                    )
                }
            }


            if (query.isNotBlank()) {
                val sections = state.sections

                sections.topResult?.takeIf { state.searchMode == SearchMode.All }?.let { top ->
                    item { SectionTitle("Top result") }
                    item {
                        TopResultCard(
                            track = top,
                            isPlaying = isPlaying && state.nowPlayingUrl == top.url,
                            onClick = {
                                if (state.nowPlayingUrl == top.url && (player?.isPlaying == true)) player?.pause()
                                else if (state.nowPlayingUrl == top.url) player?.play()
                                else vm.play(top) { audioUrl -> player?.let { playTrack(it, top, audioUrl) } }
                            },
                        )
                    }
                }

                if (sections.songs.isNotEmpty() && (state.searchMode == SearchMode.All || state.searchMode == SearchMode.Songs)) {
                    item { SectionTitle(if (state.searchMode == SearchMode.All) "Songs" else "All songs") }
                    items(sections.songs, key = { "song_${it.url}" }) { track ->
                        SongRow(
                            track = track,
                            nowPlayingUrl = state.nowPlayingUrl,
                            isPlaying = isPlaying && state.nowPlayingUrl == track.url,
                            loading = state.resolvingUrl == track.url,
                            onClick = {
                                if (state.nowPlayingUrl == track.url && (player?.isPlaying == true)) player?.pause()
                                else if (state.nowPlayingUrl == track.url) player?.play()
                                else vm.play(track) { audioUrl -> player?.let { playTrack(it, track, audioUrl) } }
                            },
                        )
                    }
                }

                if (sections.videos.isNotEmpty() && (state.searchMode == SearchMode.All || state.searchMode == SearchMode.Videos)) {
                    item { SectionTitle("Videos") }
                    items(sections.videos, key = { "vid_${it.url}" }) { track ->
                        SongRow(
                            track = track,
                            nowPlayingUrl = state.nowPlayingUrl,
                            isPlaying = isPlaying && state.nowPlayingUrl == track.url,
                            loading = state.resolvingUrl == track.url,
                            onClick = {
                                if (state.nowPlayingUrl == track.url && (player?.isPlaying == true)) player?.pause()
                                else if (state.nowPlayingUrl == track.url) player?.play()
                                else vm.play(track) { audioUrl -> player?.let { playTrack(it, track, audioUrl) } }
                            },
                        )
                    }
                }

                if (sections.albums.isNotEmpty() && (state.searchMode == SearchMode.All || state.searchMode == SearchMode.Albums)) {
                    item { SectionTitle("Albums") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(sections.albums, key = { "alb_${it.url}" }) { album ->
                                AlbumCard(album = album)
                            }
                        }
                    }
                }

                if (sections.artists.isNotEmpty() && (state.searchMode == SearchMode.All || state.searchMode == SearchMode.Artists)) {
                    item { SectionTitle("Artists") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(sections.artists, key = { "art_${it.url}" }) { artist ->
                                ArtistCard(artist = artist, onClick = { onArtistClick(artist.url) })
                            }
                        }
                    }
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






        com.streamcloud.app.ui.player.GlobalMiniPlayer(
            modifier = Modifier.align(Alignment.BottomCenter),
        )

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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 16.dp, end = 4.dp, bottom = 4.dp),
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
        IconButton(onClick = {}) {
            Icon(
                Icons.Default.TrendingUp,
                contentDescription = "Trending",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(onClick = {}) {
            Icon(
                Icons.Default.Group,
                contentDescription = "Friends",
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
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search songs, artists, albums") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (loading) CircularProgressIndicator(
                Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        )
    )
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
private fun AlbumCard(album: com.streamcloud.app.data.newpipe.YtAlbum) {
    Column(Modifier.width(160.dp)) {
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
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
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
                contentScale = ContentScale.Crop,
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
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun YtMoodChipRow(chips: List<com.streamcloud.app.data.ytmusic.MoodChip>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { "chip_${it.label}" }) { chip ->
            AssistChip(
                onClick = {  },
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
private fun YtHomeSongCard(s: com.streamcloud.app.data.ytmusic.YtmSong) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onPlay = {
        scope.launch {
            runCatching { com.streamcloud.app.data.ytmusic.YtPlayback.playSong(context, s) }
        }
        Unit
    }
    Column(Modifier.width(150.dp)) {
        AsyncImage(
            model = s.thumbnail,
            contentDescription = s.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onPlay() },
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { onPlay() }) {
                Text(
                    s.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
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
}

