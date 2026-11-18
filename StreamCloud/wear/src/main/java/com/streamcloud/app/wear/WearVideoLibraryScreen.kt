package com.streamcloud.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.items
import androidx.wear.compose.material.rememberScalingLazyListState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect

internal enum class WearLibrarySection(val label: String) {
    MUSIC("Music"),
    MOVIES("Movies"),
    ADULT("Adult"),
}

private val WearLibrarySection.iconLabel: String
    get() = when (this) {
        WearLibrarySection.MUSIC -> "♫"
        WearLibrarySection.MOVIES -> "▶"
        WearLibrarySection.ADULT -> "18+"
    }

@Composable
internal fun WearLibraryNavigation(
    selectedSection: WearLibrarySection,
    onSelectSection: (WearLibrarySection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WearLibrarySection.entries.forEach { section ->
            Chip(
                onClick = { onSelectSection(section) },
                label = {
                    Text(
                        section.iconLabel,
                        color = if (section == selectedSection) Color(0xFF1BC8E8) else Color.White,
                        style = if (section == WearLibrarySection.ADULT) {
                            MaterialTheme.typography.caption2
                        } else {
                            MaterialTheme.typography.title3
                        },
                        maxLines = 1,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = section.label
                        selected = section == selectedSection
                    },
            )
        }
    }
}

internal data class WearVideoItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val artwork: String,
    val overview: String = "",
    val year: Int = 0,
    val source: String = "",
    val pageUrl: String = "",
    val directUrl: String = "",
)

internal data class WearVideoStream(
    val title: String,
    val provider: String,
    val quality: String,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String = "",
)

@Composable
internal fun WearVideoLibraryScreen(
    section: WearLibrarySection,
    items: List<WearVideoItem>,
    selectedMovie: WearVideoItem?,
    streams: List<WearVideoStream>,
    query: String,
    adultPin: String,
    loading: Boolean,
    hasMore: Boolean,
    message: String,
    onQueryChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectSection: (WearLibrarySection) -> Unit,
    onSelectItem: (WearVideoItem) -> Unit,
    onSelectStream: (WearVideoStream) -> Unit,
    onBackToItems: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    LaunchedEffect(listState, items.size, hasMore, loading, selectedMovie) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to layout.totalItemsCount
        }
            .map { (lastVisibleIndex, totalItems) ->
                totalItems > 0 && lastVisibleIndex >= totalItems - 2
            }
            .distinctUntilChanged()
            .filter { nearEnd -> nearEnd && hasMore && !loading && selectedMovie == null }
            .collect { onLoadMore() }
    }

    ScalingLazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101827))
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                section.label,
                color = Color(0xFF1BC8E8),
                style = MaterialTheme.typography.title3,
            )
        }
        item { WearLibraryNavigation(section, onSelectSection) }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A2638), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    if (section == WearLibrarySection.ADULT) "Search adult videos" else "Search movies",
                    color = Color(0xFFDCE8F4),
                    style = MaterialTheme.typography.caption2,
                )
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = MaterialTheme.typography.body2.fontSize,
                    ),
                    decorationBox = { innerTextField ->
                        if (query.isBlank()) {
                            Text("Type a title", color = Color(0xFF97A9BE), maxLines = 1)
                        }
                        innerTextField()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (section == WearLibrarySection.ADULT) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A2638), RoundedCornerShape(24.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("Adult PIN, if enabled", color = Color(0xFFDCE8F4), style = MaterialTheme.typography.caption2)
                    BasicTextField(
                        value = adultPin,
                        onValueChange = onPinChange,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = MaterialTheme.typography.body2.fontSize,
                        ),
                        decorationBox = { innerTextField ->
                            if (adultPin.isBlank()) {
                                Text("Enter PIN", color = Color(0xFF97A9BE), maxLines = 1)
                            }
                            innerTextField()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            Chip(
                onClick = onSearch,
                label = { Text("Browse / search") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (selectedMovie != null) {
            item {
                Text(
                    selectedMovie.title,
                    color = Color.White,
                    style = MaterialTheme.typography.title3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectedMovie.overview.isNotBlank()) {
                item {
                    Text(
                        selectedMovie.overview,
                        color = Color(0xFFDCE8F4),
                        style = MaterialTheme.typography.caption2,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Chip(
                    onClick = onBackToItems,
                    label = {
                        Text(if (section == WearLibrarySection.ADULT) "Back to videos" else "Back to movies")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (loading) {
                item { Text("Finding streams…", color = Color.LightGray) }
            }
            if (message.isNotBlank()) {
                item {
                    Text(
                        message,
                        color = Color(0xFFB8C4D8),
                        style = MaterialTheme.typography.caption2,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (streams.isNotEmpty()) {
                item { Text("Choose a stream", color = Color.White, style = MaterialTheme.typography.title3) }
                items(streams) { stream ->
                    Chip(
                        onClick = { onSelectStream(stream) },
                        label = {
                            Text(
                                stream.title.ifBlank { stream.provider },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                listOf(stream.provider, stream.quality).filter(String::isNotBlank).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            if (loading) {
                item { Text("Loading…", color = Color.LightGray) }
            }
            if (message.isNotBlank()) {
                item {
                    Text(
                        message,
                        color = Color(0xFFB8C4D8),
                        style = MaterialTheme.typography.caption2,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            items(items) { item ->
                Chip(
                    onClick = { onSelectItem(item) },
                    icon = {
                        if (item.artwork.isNotBlank()) {
                            AsyncImage(
                                model = item.artwork,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    },
                    label = {
                        Text(
                            item.title.ifBlank { "Untitled" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    secondaryLabel = {
                        Text(
                            item.subtitle.ifBlank { "Tap to play" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (hasMore && !loading) {
                item {
                    Text(
                        "Keep scrolling for more",
                        color = Color(0xFF97A9BE),
                        style = MaterialTheme.typography.caption2,
                    )
                }
            }
        }
    }
}
