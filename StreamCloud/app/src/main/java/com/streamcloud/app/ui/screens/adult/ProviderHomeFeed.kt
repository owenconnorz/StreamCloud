package com.streamcloud.app.ui.screens.adult

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.network.BrowserHeaders
import com.streamcloud.app.data.api.PornhubHomeSection
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup

/** Each provider section owns an independent horizontal scroll position. */
@Composable
fun ProviderHomeFeed(
    sections: List<PornhubHomeSection>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (AdultItem) -> Unit,
    modifier: Modifier = Modifier,
    categories: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        // Leave the next card visible on phones; cap cards on tablets and TVs.
        val cardWidth = (maxWidth * 0.84f).coerceIn(220.dp, 360.dp)
        LazyColumn(
            modifier = Modifier.fillMaxSize().tvFocusGroup(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "categories") { categories() }
            if (loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (error != null) {
                item(key = "error") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            } else if (!loading && sections.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            "No home sections are available right now.",
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        TextButton(onClick = onRetry) { Text("Refresh") }
                    }
                }
            }
            items(sections, key = { "section:${it.id}" }) { section ->
                var expanded by rememberSaveable(section.id) { mutableStateOf(true) }
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .tvFocusBorder()
                            .clickable(
                                onClickLabel = if (expanded) "Collapse ${section.title}" else "Expand ${section.title}",
                            ) { expanded = !expanded }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            section.title,
                            modifier = Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Expanded" else "Collapsed",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    if (expanded) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().tvFocusGroup(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(section.items, key = { it.id }) { video ->
                                ProviderHomeVideoCard(
                                    item = video,
                                    modifier = Modifier.width(cardWidth),
                                    onClick = { onSelect(video) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderHomeVideoCard(item: AdultItem, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val imageUrl = item.thumbnail ?: item.previewImage
    val imageModel = remember(imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .addHeader("User-Agent", BrowserHeaders.USER_AGENT)
                .addHeader("Referer", "https://www.pornhub.com/")
                .build()
        }
    }
    Column(
        modifier
            .tvFocusBorder(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "Open video", onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            SubcomposeAsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                },
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.ImageNotSupported,
                            contentDescription = "Thumbnail unavailable",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.views?.takeIf { it.isNotBlank() }?.let { views ->
                    Icon(Icons.Default.Visibility, "Views", Modifier.size(14.dp), tint = Color.White)
                    Text(views, color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                item.durationLabel?.let { duration ->
                    Text(duration, color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            item.title,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}