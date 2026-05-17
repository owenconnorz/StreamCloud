package com.streamcloud.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streamcloud.app.data.newpipe.YtAlbum
import com.streamcloud.app.data.newpipe.YtArtist
import com.streamcloud.app.data.newpipe.YtTrack
import com.streamcloud.app.ui.viewmodel.MusicViewModel
import com.streamcloud.app.ui.viewmodel.SearchMode
import kotlinx.coroutines.delay

private const val HISTORY_PREFS = "music_search_history"
private const val HISTORY_KEY = "queries"
private const val MAX_HISTORY = 10

private fun loadHistory(ctx: Context): List<String> =
    ctx.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        .getString(HISTORY_KEY, "")
        ?.split("|||")
        ?.filter { it.isNotBlank() }
        .orEmpty()

private fun saveHistory(ctx: Context, history: List<String>) =
    ctx.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE).edit()
        .putString(HISTORY_KEY, history.take(MAX_HISTORY).joinToString("|||"))
        .apply()

private fun addToHistory(ctx: Context, query: String): List<String> {
    val prev = loadHistory(ctx).filter { it != query }
    val next = listOf(query) + prev
    saveHistory(ctx, next)
    return next
}

// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onMovieClick: (Long) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: MusicViewModel = viewModel(factory = MusicViewModel.factory(context))
    val state by vm.state.collectAsState()

    var query by remember { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadHistory(context)) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val bg = Color(0xFF0E0E0E)
    val rowBg = Color(0xFF1C1C1E)

    // Debounced full search
    LaunchedEffect(query) {
        if (query.length >= 2) {
            delay(500)
            vm.search(query)
        } else if (query.isEmpty()) {
            vm.fetchSuggestions("")
        }
    }

    // Live suggestions while typing
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(250)
            vm.fetchSuggestions(query)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val hasResults = query.length >= 2 &&
        (state.sections.songs.isNotEmpty() || state.artistResults.isNotEmpty() ||
            state.albumResults.isNotEmpty() || state.sections.videos.isNotEmpty())

    val showSuggestions = query.isNotBlank() && state.suggestions.isNotEmpty() && !hasResults
    val showHistory = history.isNotEmpty() && (query.isEmpty() || !hasResults)

    Column(
        Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        // ── Search bar ────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (isFocused) {
                    focusManager.clearFocus()
                } else {
                    query = ""
                }
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    tint = Color.White,
                )
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                placeholder = {
                    Text(
                        "Search songs, artists, albums…",
                        color = Color.White.copy(alpha = 0.38f),
                        fontSize = 16.sp,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, "Clear", tint = Color.White.copy(alpha = 0.7f))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    if (query.isNotBlank()) {
                        history = addToHistory(context, query)
                        vm.search(query)
                    }
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF2A2A2A),
                    unfocusedContainerColor = Color(0xFF2A2A2A),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
            )
        }

        // ── Content ───────────────────────────────────────────────────────────
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            // ── Search history ─────────────────────────────────────────────
            if (showHistory) {
                val filtered = if (query.isBlank()) history
                               else history.filter { it.startsWith(query, ignoreCase = true) }
                if (filtered.isNotEmpty()) {
                    item { MetroSectionHeader("Search history") }
                    item {
                        MetroGroupedContainer(rowBg) {
                            filtered.forEachIndexed { idx, q ->
                                if (idx > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                                HistoryRow(
                                    text = q,
                                    onFill = { query = q },
                                    onRemove = {
                                        history = history.filter { it != q }
                                        saveHistory(context, history)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── Suggestions ────────────────────────────────────────────────
            if (showSuggestions) {
                item { MetroSectionHeader("Suggestions") }
                item {
                    MetroGroupedContainer(rowBg) {
                        state.suggestions.take(8).forEachIndexed { idx, s ->
                            if (idx > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                            SuggestionRow(
                                text = s,
                                onFill = {
                                    query = s
                                    focusManager.clearFocus()
                                    history = addToHistory(context, s)
                                    vm.search(s)
                                },
                            )
                        }
                    }
                }
            }

            // ── Loading ────────────────────────────────────────────────────
            if (state.loading && query.length >= 2 && !hasResults) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            // ── Top result + results ───────────────────────────────────────
            if (hasResults) {
                // Filter chips
                item {
                    SearchModeChips(
                        selected = state.searchMode,
                        onSelect = { vm.setSearchMode(it) },
                    )
                }

                item { MetroSectionHeader("Top result") }

                // Artists first (circular avatar style)
                items(state.artistResults.take(3), key = { "ar_${it.url}" }) { artist ->
                    ArtistResultRow(
                        artist = artist,
                        rowBg = rowBg,
                        onClick = { onArtistClick(artist.url) },
                    )
                }

                // Songs
                items(state.sections.songs.take(15), key = { "s_${it.url}" }) { track ->
                    SongResultRow(
                        track = track,
                        rowBg = rowBg,
                        isCurrent = state.nowPlayingUrl == track.url,
                        onClick = { vm.play(track) { } },
                    )
                }

                // Videos section
                if (state.sections.videos.isNotEmpty() && state.searchMode == SearchMode.All) {
                    item { MetroSectionHeader("Videos") }
                    items(state.sections.videos.take(8), key = { "v_${it.url}" }) { track ->
                        SongResultRow(
                            track = track,
                            rowBg = rowBg,
                            isCurrent = state.nowPlayingUrl == track.url,
                            onClick = { vm.play(track) { } },
                        )
                    }
                }

                // Albums
                if (state.albumResults.isNotEmpty() && state.searchMode == SearchMode.All) {
                    item { MetroSectionHeader("Albums") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.albumResults, key = { it.url }) { album ->
                                AlbumResultCard(album)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // ── Empty state ────────────────────────────────────────────────
            if (query.length >= 2 && !hasResults && !state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Search, null,
                                tint = Color.White.copy(alpha = 0.2f),
                                modifier = Modifier.size(56.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No results for \"$query\"",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Reusable primitives ───────────────────────────────────────────────────────

@Composable
private fun MetroSectionHeader(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp, end = 16.dp),
    )
}

@Composable
private fun MetroGroupedContainer(
    bg: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        content = content,
    )
}

@Composable
private fun HistoryRow(text: String, onFill: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onFill)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.History, null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, "Remove", tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onFill, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, "Fill", tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SuggestionRow(text: String, onFill: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onFill)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Search, null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onFill, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, "Fill", tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ArtistResultRow(
    artist: YtArtist,
    rowBg: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(rowBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Circular avatar
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF2E2E2E)),
            contentAlignment = Alignment.Center,
        ) {
            if (!artist.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = artist.thumbnail,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.4f))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                artist.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!artist.subscriberLabel.isNullOrBlank()) {
                Text(
                    artist.subscriberLabel,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.MoreVert, "More", tint = Color.White.copy(alpha = 0.6f))
        }
    }
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.05f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun SongResultRow(
    track: YtTrack,
    rowBg: Color,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isCurrent) Color.White.copy(alpha = 0.07f) else rowBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2E2E2E)),
            contentAlignment = Alignment.Center,
        ) {
            if (!track.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = track.thumbnail,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.3f))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.uploader,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.MoreVert, "More", tint = Color.White.copy(alpha = 0.6f))
        }
    }
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.05f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun AlbumResultCard(album: YtAlbum) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable { },
    ) {
        Box(
            Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2E2E2E)),
            contentAlignment = Alignment.Center,
        ) {
            if (!album.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = album.thumbnail,
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(album.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!album.year.isNullOrBlank()) {
            Text(album.year, color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SearchModeChips(
    selected: SearchMode,
    onSelect: (SearchMode) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SearchMode.values().toList()) { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = {
                    Text(
                        when (mode) {
                            SearchMode.All -> "All"
                            SearchMode.Songs -> "Songs"
                            SearchMode.Videos -> "Videos"
                            SearchMode.Albums -> "Albums"
                            SearchMode.Artists -> "Artists"
                        },
                        fontSize = 13.sp,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                    containerColor = Color(0xFF1C1C1E),
                    labelColor = Color.White.copy(alpha = 0.7f),
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = mode == selected,
                    selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    borderColor = Color.White.copy(alpha = 0.08f),
                ),
            )
        }
    }
}
