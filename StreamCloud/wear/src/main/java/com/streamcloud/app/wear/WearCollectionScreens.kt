package com.streamcloud.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberScalingLazyListState
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

internal data class WearMovieGenre(val id: Int, val label: String)

internal val wearMovieGenres = listOf(
    WearMovieGenre(28, "Action"),
    WearMovieGenre(12, "Adventure"),
    WearMovieGenre(16, "Animation"),
    WearMovieGenre(35, "Comedy"),
    WearMovieGenre(80, "Crime"),
    WearMovieGenre(99, "Documentary"),
    WearMovieGenre(18, "Drama"),
    WearMovieGenre(10751, "Family"),
    WearMovieGenre(14, "Fantasy"),
    WearMovieGenre(36, "History"),
    WearMovieGenre(27, "Horror"),
    WearMovieGenre(10402, "Music"),
    WearMovieGenre(9648, "Mystery"),
    WearMovieGenre(10749, "Romance"),
    WearMovieGenre(878, "Sci-Fi"),
    WearMovieGenre(53, "Thriller"),
    WearMovieGenre(10752, "War"),
    WearMovieGenre(37, "Western"),
)

private val screenBackground = Brush.radialGradient(
    listOf(Color(0xFF32113A), Color(0xFF120B17), Color(0xFF08070D)),
)
private val accent = Color(0xFFE34DFF)
private val dimText = Color(0xFFC7B9D0)

@Composable
internal fun WearMovieGenresScreen(
    selectedGenre: WearMovieGenre?,
    items: List<WearVideoItem>,
    loading: Boolean,
    message: String,
    onSelectGenre: (WearMovieGenre) -> Unit,
    onSelectItem: (WearVideoItem) -> Unit,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    LaunchedEffect(selectedGenre) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(listState, selectedGenre, items.size, hasMore, loading) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to layout.totalItemsCount
        }
            .map { (lastVisibleIndex, totalItems) -> totalItems > 0 && lastVisibleIndex >= totalItems - 2 }
            .distinctUntilChanged()
            .filter { nearEnd -> nearEnd && selectedGenre != null && hasMore && !loading }
            .collect { onLoadMore() }
    }
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(screenBackground)
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        item {
            Text(
                selectedGenre?.label?.uppercase() ?: "MOVIE GENRES",
                color = accent,
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        item { WearBackChip(if (selectedGenre == null) "Home" else "All genres", onBack) }
        if (selectedGenre == null) {
            wearMovieGenres.forEach { genre ->
                item {
                    Chip(
                        onClick = { onSelectGenre(genre) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(genre.label, maxLines = 1) },
                        secondaryLabel = { Text("Browse ${genre.label.lowercase()} movies", maxLines = 1) },
                        icon = { WearBadge("⌁", genre.label) },
                    )
                }
            }
        } else {
            if (loading) item { Text("Loading ${selectedGenre.label}…", color = dimText) }
            if (message.isNotBlank()) item { Text(message, color = dimText, textAlign = TextAlign.Center) }
            items.forEach { video ->
                item {
                    WearVideoChip(video, onClick = { onSelectItem(video) })
                }
            }
        }
    }
}

@Composable
internal fun WearAdultCategoriesScreen(
    selectedCategory: String?,
    items: List<WearVideoItem>,
    loading: Boolean,
    message: String,
    adultPin: String,
    onPinChange: (String) -> Unit,
    onSelectCategory: (String, String) -> Unit,
    onSelectItem: (WearVideoItem) -> Unit,
    onBack: () -> Unit,
) {
    val categories = listOf(
        "Trending" to "",
        "Action" to "action",
        "Comedy" to "comedy",
        "Drama" to "drama",
        "Animation" to "animation",
        "Romance" to "romance",
        "Music" to "music",
        "Popular" to "popular",
    )
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBackground)
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        item {
            Text(
                selectedCategory?.uppercase() ?: "ADULT CATEGORIES",
                color = accent,
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text("18+ · Uses your phone's configured provider", color = dimText, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1B1222))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("Adult PIN, if enabled", color = dimText, style = MaterialTheme.typography.caption2)
                BasicTextField(
                    value = adultPin,
                    onValueChange = onPinChange,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.caption1.fontSize),
                    decorationBox = { innerTextField ->
                        if (adultPin.isBlank()) Text("Enter PIN", color = dimText, maxLines = 1)
                        innerTextField()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { WearBackChip(if (selectedCategory == null) "Home" else "Categories", onBack) }
        if (selectedCategory == null) {
            categories.forEach { (label, query) ->
                item {
                    Chip(
                        onClick = { onSelectCategory(label, query) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(label, maxLines = 1) },
                        secondaryLabel = { Text(if (query.isBlank()) "Browse current feed" else "Search $label", maxLines = 1) },
                        icon = { WearBadge("♡", label) },
                    )
                }
            }
        } else {
            if (loading) item { Text("Loading…", color = dimText) }
            if (message.isNotBlank()) item { Text(message, color = dimText, textAlign = TextAlign.Center) }
            items.forEach { video -> item { WearVideoChip(video, onClick = { onSelectItem(video) }) } }
        }
    }
}

@Composable
internal fun WearContinueWatchingScreen(
    items: List<WearVideoItem>,
    loading: Boolean,
    message: String,
    onSelectItem: (WearVideoItem) -> Unit,
    onBack: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBackground)
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("CONTINUE WATCHING", color = accent, style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        item { WearBackChip("Home", onBack) }
        if (loading) item { Text("Loading watch history…", color = dimText) }
        if (items.isEmpty() && !loading) {
            item {
                WearEmptyState(
                    icon = "▶",
                    title = "Nothing to resume",
                    message = message.ifBlank { "Movies and episodes you have started on your phone will appear here." },
                )
            }
        } else {
            items.forEach { video ->
                item { WearVideoChip(video, onClick = { onSelectItem(video) }) }
            }
        }
    }
}

@Composable
internal fun WearDownloadsScreen(
    items: List<WearVideoItem>,
    loading: Boolean,
    message: String,
    onBack: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBackground)
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("DOWNLOADS", color = accent, style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold) }
        item { WearBackChip("Home", onBack) }
        item { Text("Saved on your phone", color = dimText, style = MaterialTheme.typography.caption2) }
        if (loading) item { Text("Loading downloads…", color = dimText) }
        if (items.isEmpty() && !loading) {
            item {
                WearEmptyState(
                    icon = "↓",
                    title = "No downloads yet",
                    message = message.ifBlank { "Downloads saved in StreamCloud on your phone appear here." },
                )
            }
        } else {
            items.forEach { download ->
                item {
                    WearVideoChip(
                        download,
                        onClick = {},
                        enabled = false,
                        supportingText = if (download.availableOffline) "Offline on phone" else download.status.ifBlank { "Saved on phone" },
                    )
                }
            }
        }
    }
}

@Composable
internal fun WearVideoChip(
    video: WearVideoItem,
    onClick: () -> Unit,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        icon = {
            if (video.artwork.isNotBlank()) {
                AsyncImage(
                    model = video.artwork,
                    contentDescription = "${video.title} artwork",
                    modifier = Modifier.size(38.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                WearBadge(if (video.kind == "music") "♪" else "▶", video.title)
            }
        },
        label = {
            Text(
                video.title.ifBlank { "Untitled" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
        },
        secondaryLabel = {
            Text(
                supportingText ?: video.subtitle.ifBlank { video.status },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
internal fun WearEmptyState(icon: String, title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1B1222))
            .border(1.dp, Color(0xFF693675), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        WearBadge(icon, title)
        Text(title, color = Color.White, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(message, color = dimText, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WearBackChip(label: String, onBack: () -> Unit) {
    Chip(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("‹  $label", color = Color.White, textAlign = TextAlign.Center) },
    )
}

@Composable
private fun WearBadge(icon: String, description: String) {
    Column(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFF35163E))
            .border(1.dp, Color(0xFF9C49AC), CircleShape)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WearLineIcon(icon, tint = Color(0xFFFF70D1), size = 18.dp)
    }
}
