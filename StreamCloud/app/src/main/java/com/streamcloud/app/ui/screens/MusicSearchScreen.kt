package com.streamcloud.app.ui.screens

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.ui.viewmodel.MusicViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSearchScreen(
    onBack: () -> Unit,
    onArtistClick: (url: String, thumbnail: String?) -> Unit = { _, _ -> },
    onOpenPlaylist: (id: String, title: String) -> Unit = { _, _ -> },
    initialQuery: String = "",
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val vm: MusicViewModel = viewModel(factory = MusicViewModel.factory(context))
    val state by vm.state.collectAsState()
    val searchHistory by sl.settings.musicSearchHistory.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf(initialQuery) }

    // ── Dynamic theme: tint the screen with the dominant colour of the now-playing artwork ──
    val nowArtwork = state.nowPlayingTrack?.thumbnail
    val dominant  by rememberDominant(nowArtwork)
    val animAccent by animateColorAsState(
        targetValue = dominant,
        animationSpec = tween(durationMillis = 700),
        label = "search-accent",
    )
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (initialQuery.isBlank()) runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            query = initialQuery
            vm.search(initialQuery)
        }
    }

    LaunchedEffect(query) {
        vm.fetchSuggestions(query)
        if (query.length >= 2) {
            kotlinx.coroutines.delay(400)
            vm.search(query)
        }
    }

    fun submitSearch(q: String) {
        if (q.trim().length >= 2) {
            scope.launch { sl.settings.addMusicSearchHistory(q.trim()) }
            vm.search(q)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(
                Brush.verticalGradient(
                    0.00f to animAccent.copy(alpha = 0.28f),
                    0.35f to animAccent.copy(alpha = 0.08f),
                    1.00f to androidx.compose.ui.graphics.Color.Transparent,
                )
            ),
    ) {

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            Surface(
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.statusBarsPadding(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                "Search songs, artists, albums...",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            when {
                                state.loading -> CircularProgressIndicator(
                                    Modifier.size(20.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, "Clear")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch(query) }),
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
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (query.isBlank()) {
                // ── Saved search history ──
                if (searchHistory.isNotEmpty()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Recent searches",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { scope.launch { sl.settings.clearMusicSearchHistory() } }) {
                                Text(
                                    "Clear all",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(searchHistory, key = { "hist_$it" }) { term ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = term
                                    submitSearch(term)
                                }
                                .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                term,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            IconButton(
                                onClick = { scope.launch { sl.settings.removeMusicSearchHistory(term) } },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                }

                // ── Suggestions chips ──
                if (state.suggestions.isNotEmpty()) {
                    item {
                        Text(
                            "Try searching for",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.suggestions) { s ->
                                SuggestionChip(
                                    onClick = { query = s; submitSearch(s) },
                                    label = { Text(s) },
                                )
                            }
                        }
                    }
                }
            } else {
                // ── Search results: top results + live suggestions ──
                val sections = state.sections
                val topArtist = sections.artists.firstOrNull()
                val topSongs = sections.songs.take(if (topArtist != null) 2 else 3)

                topArtist?.let { artist ->
                    item(key = "sr_artist_${artist.url}") {
                        MusicSearchResultRow(
                            thumbnail = artist.thumbnail,
                            title = artist.name,
                            subtitle = "Artist",
                            isCircle = true,
                            onClick = { onArtistClick(artist.url, artist.thumbnail) },
                        )
                    }
                }

                items(topSongs, key = { "sr_song_${it.url}" }) { track ->
                    MusicSearchResultRow(
                        thumbnail = track.thumbnail,
                        title = track.title,
                        subtitle = track.uploader,
                        isCircle = false,
                        onClick = { vm.play(track); submitSearch(query) },
                    )
                }

                if (topArtist == null && topSongs.isEmpty()) {
                    sections.albums.firstOrNull()?.let { album ->
                        item(key = "sr_album_${album.url}") {
                            val uri = Uri.parse(album.url)
                            val id = uri.getQueryParameter("list")
                                ?: uri.lastPathSegment?.takeIf { seg -> seg.isNotBlank() }
                                ?: album.url
                            MusicSearchResultRow(
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
                    MusicSuggestionListRow(
                        text = suggestion,
                        onClick = { query = suggestion; vm.search(suggestion) },
                    )
                }

                if (topArtist == null && topSongs.isEmpty() && sections.albums.isEmpty() &&
                    state.suggestions.isEmpty() && query.length >= 2 && !state.loading
                ) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No results for \"$query\"",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    } // end outer Box (dynamic theme wrapper)
}

@Composable
private fun MusicSearchResultRow(
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
        val imgMod = Modifier
            .size(54.dp)
            .clip(if (isCircle) CircleShape else RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = imgMod,
            )
        } else {
            Box(imgMod, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
private fun MusicSuggestionListRow(text: String, onClick: () -> Unit) {
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
            modifier = Modifier
                .size(18.dp)
                .rotate(-45f),
        )
    }
}
