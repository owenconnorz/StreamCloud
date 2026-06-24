package com.streamcloud.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.RedditAdultSubs
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.ui.viewmodel.AdultViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdultScreen(
    onPlay: (videoId: String, fallbackEmbed: String, title: String) -> Unit,
    onOpenRedditLogin: () -> Unit = {},
    screenTitle: String = "Adult",
    screenSubtitle: String = "18+ · Powered by Eporner",
) {
    val context = LocalContext.current
    val vm: AdultViewModel = viewModel(factory = AdultViewModel.factory(context))
    val state by vm.state.collectAsState()
    val sl = remember(context) { com.streamcloud.app.data.ServiceLocator.get(context) }
    val redditUsername by sl.settings.redditUsername.collectAsState(initial = "")
    val redditAccounts by sl.settings.redditAccounts.collectAsState(initial = emptyList())

    var detailItem by remember { mutableStateOf<AdultItem?>(null) }

    if (state.source == AdultSource.Reddit) {
        val customCsv by sl.settings.adultRedditSubsCsv.collectAsState(initial = "")
        val customSubs = remember(customCsv) {
            customCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        val scope = rememberCoroutineScope()
        com.streamcloud.app.ui.screens.adult.RedditFeedView(
            vm = vm,
            customSubs = customSubs,
            redditUsername = redditUsername,
            onLoginClick  = onOpenRedditLogin,
            onLogoutClick = {
                scope.launch {
                    sl.settings.clearRedditUsername()
                    sl.settings.removeRedditAccount(redditUsername)
                }
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
            },
            accounts = redditAccounts,
            onSwitchAccount = { name, cookies ->
                val cm = android.webkit.CookieManager.getInstance()
                cookies.split("; ").forEach { c ->
                    cm.setCookie("https://www.reddit.com", c.trim())
                    cm.setCookie("https://reddit.com", c.trim())
                }
                cm.flush()
                scope.launch { sl.settings.setRedditUsername(name) }
            },
            onAddSub = { sub ->
                scope.launch {
                    val cleaned = sub.removePrefix("r/").trim()
                    if (cleaned.isNotBlank()) {
                        val updated = (customSubs + cleaned).distinct().joinToString(",")
                        sl.settings.setAdultRedditSubs(updated)
                    }
                }
            },
            onRemoveSub = { sub ->
                scope.launch {
                    val updated = (customSubs - sub).joinToString(",")
                    sl.settings.setAdultRedditSubs(updated)
                }
            },
            onSwitchSource = { vm.setSource(AdultSource.Eporner) },
        )
        return
    }

    var query by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, state.source) {
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
            screenTitle, style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            screenSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))

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
                AdultCard(v) {
                    if (v.source == AdultSource.Eporner) {
                        detailItem = v
                    } else {
                        onPlay(v.routeId(), v.routeFallback(), v.title)
                    }
                }
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

    detailItem?.let { item ->
        EpornerDetailSheet(
            item = item,
            context = context,
            onPlay = {
                detailItem = null
                onPlay(item.routeId(), item.routeFallback(), item.title)
            },
            onDismiss = { detailItem = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpornerDetailSheet(
    item: AdultItem,
    context: Context,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        LibraryDb.get(context).watchlist()
            .isWatchlisted(epornerWatchlistId(item.id))
            .collect { saved = it }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            if (!item.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }

            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.durationLabel?.let { InfoPill(Icons.Default.Timer, it) }
                item.views?.let         { InfoPill(Icons.Default.Visibility, "$it views") }
                item.rating?.let        { InfoPill(Icons.Default.Star, it) }
            }

            item.tags?.takeIf { it.isNotBlank() }?.let { tagStr ->
                val tags = tagStr.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(14)
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(tags) { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val db = LibraryDb.get(context)
                            val wid = epornerWatchlistId(item.id)
                            if (saved) {
                                db.watchlist().remove(wid)
                            } else {
                                db.watchlist().add(
                                    WatchlistEntity(
                                        tmdbId    = wid,
                                        title     = item.title,
                                        posterUrl = item.thumbnail,
                                        mediaType = "eporner",
                                        csPlugin  = "eporner",
                                        csUrl     = item.embedUrl.orEmpty(),
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = if (saved) Color(0xFF7C5CFF) else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (saved) "Saved" else "Save")
                }
            }
        }
    }
}

@Composable
private fun InfoPill(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun epornerWatchlistId(epornerId: String): Long =
    (-9_000_000_000L) - (epornerId.hashCode().toLong() and 0xFFFFFL)

private fun AdultItem.routeId(): String = when (source) {
    AdultSource.Eporner -> epornerId ?: id
    AdultSource.Reddit  -> "direct://${streamUrl ?: ""}"
    AdultSource.Redtube -> "direct://"
}

private fun AdultItem.routeFallback(): String = when (source) {
    AdultSource.Eporner -> embedUrl.orEmpty()
    AdultSource.Reddit  -> streamUrl.orEmpty()
    AdultSource.Redtube -> embedUrl.orEmpty()
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
            if (v.source == AdultSource.Redtube) {
                Text(
                    "Redtube",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFCC0000).copy(alpha = 0.85f))
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
