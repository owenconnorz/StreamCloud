package com.aioweb.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
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
import coil.compose.AsyncImage
import com.aioweb.app.data.api.AdultItem
import com.aioweb.app.data.api.AdultSource
import com.aioweb.app.data.api.RedditAdultSubs
import com.aioweb.app.ui.viewmodel.AdultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdultScreen(onPlay: (videoId: String, fallbackEmbed: String, title: String) -> Unit) {
    val context = LocalContext.current
    val vm: AdultViewModel = viewModel(factory = AdultViewModel.factory(context))
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()

    // Endless scroll on Reddit — pull the next page once we're within ~6 rows of the bottom.
    LaunchedEffect(gridState, state.source, state.subreddit, state.nextAfter) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && last >= total - 6
        }.collect { reachedEnd -> if (reachedEnd) vm.loadMore() }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Adult", style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "18+ · " + when (state.source) {
                AdultSource.Eporner -> "Powered by Eporner"
                AdultSource.Reddit -> "Browsing r/${state.subreddit}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))

        // ---- Source switcher chips ----------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdultSource.values().forEach { src ->
                SourceChip(
                    label = src.label,
                    selected = state.source == src,
                    onClick = {
                        query = ""
                        vm.setSource(src)
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it; vm.search(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            placeholder = {
                Text(
                    if (state.source == AdultSource.Reddit) "Subreddit (e.g. nsfw, gonewild)…"
                    else "Search…"
                )
            },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (state.loading) CircularProgressIndicator(
                    Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            )
        )

        // ---- Subreddit preset chips (Reddit only) -------------------------
        if (state.source == AdultSource.Reddit) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RedditAdultSubs.PRESETS.forEach { (label, sub) ->
                    SubChip(
                        label = label,
                        selected = state.subreddit.equals(sub, ignoreCase = true),
                        onClick = { query = sub; vm.setSubreddit(sub) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.items, key = { "${it.source.name}:${it.id}" }) { v ->
                AdultCard(v) { onPlay(v.routeId(), v.routeFallback(), v.title) }
            }
            if (state.loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

/**
 * The navigation hop expects `(videoId, fallbackEmbed, title)` — for Eporner those
 * map to the page id + embed URL, for Reddit we shove the direct stream URL into
 * `videoId` (already URL-encoded by the host) and prefix with `direct://` so the
 * resolver knows to skip the Eporner detail call.
 */
private fun AdultItem.routeId(): String = when (source) {
    AdultSource.Eporner -> epornerId ?: id
    AdultSource.Reddit -> "direct://${streamUrl ?: ""}"
}

private fun AdultItem.routeFallback(): String = when (source) {
    AdultSource.Eporner -> embedUrl.orEmpty()
    AdultSource.Reddit -> streamUrl.orEmpty()
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF7C5CFF) else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SubChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFFFF7A29) else Color(0xFFFF7A29).copy(alpha = 0.18f)
    val fg = if (selected) Color.White else Color(0xFFFFB37A)
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AdultCard(v: AdultItem, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surface)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AsyncImage(
                model = v.thumbnail,
                contentDescription = v.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        )
                    )
            )
            v.durationLabel?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (v.source == AdultSource.Reddit) {
                Text(
                    "Reddit",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFFF4500).copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Icon(
                Icons.Default.PlayCircle, null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).size(48.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            v.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
