package com.streamcloud.app.ui.screens.adult

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.api.RedditAdultSubs
import com.streamcloud.app.ui.viewmodel.AdultViewModel
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RedditFeedView(
    vm: AdultViewModel,
    modifier: Modifier = Modifier,
    customSubs: List<String> = emptyList(),
    onPlayItem: (AdultItem) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val items = state.items

    val subLabels = remember(customSubs) {
        val preset = RedditAdultSubs.PRESETS.map { it.first }
        (preset + customSubs.map { if (it.startsWith("r/")) it else "r/$it" }).distinct()
    }

    // Subreddit unavailable / quarantined
    if (state.redditNeedsAuth && items.isEmpty()) {
        SubredditUnavailablePrompt(
            subreddit = state.currentSubreddit,
            onRetry   = { vm.setSubreddit("nsfw") },
            modifier  = modifier,
        )
        return
    }

    Box(modifier.fillMaxSize()) {

        if (state.loading && items.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color(0xFFFF4500),
            )
        } else if (items.isEmpty() && state.error != null) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    state.error ?: "Something went wrong.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { vm.refresh() }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        } else if (items.isNotEmpty()) {
            val pagerState = rememberPagerState(pageCount = { items.size })

            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage >= items.size - 3) vm.loadMore()
            }

            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                RedditPostCard(
                    item        = items[page],
                    onPlayClick = { onPlayItem(items[page]) },
                )
            }

            if (state.loadingMore) {
                LinearProgressIndicator(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color    = Color(0xFFFF4500),
                )
            }
        }

        // ── Top bar: subreddit chips + refresh button ──────────────────────
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                    )
                )
                .statusBarsPadding()
                .padding(top = 8.dp, bottom = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(end = 8.dp),
                ) {
                    items(subLabels) { sub ->
                        val clean  = sub.removePrefix("r/")
                        val active = clean == state.currentSubreddit
                        FilterChip(
                            selected = active,
                            onClick  = { vm.setSubreddit(clean) },
                            label    = { Text(sub, fontSize = 12.sp, maxLines = 1) },
                            shape    = RoundedCornerShape(50),
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFF4500),
                                selectedLabelColor     = Color.White,
                            ),
                        )
                    }
                }

                IconButton(onClick = { vm.refresh() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint     = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // Auth-required banner (e.g. quarantined sub) when items exist
        if (state.redditNeedsAuth && items.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape          = RoundedCornerShape(12.dp),
                color          = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 4.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "r/${state.currentSubreddit} is quarantined",
                        modifier = Modifier.weight(1f),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = { vm.setSubreddit("nsfw") }) { Text("Go to r/nsfw") }
                }
            }
        }
    }
}

@Composable
private fun RedditPostCard(item: AdultItem, onPlayClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = item.isVideo || item.streamUrl != null, onClick = onPlayClick),
    ) {
        val imageUrl = item.previewImage ?: item.thumbnail
        if (imageUrl != null) {
            AsyncImage(
                model              = imageUrl,
                contentDescription = item.title,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier.fillMaxSize(),
            )
        }

        if (item.isVideo && item.streamUrl != null) {
            Box(
                Modifier
                    .size(72.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onPlayClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint     = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                    )
                )
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .navigationBarsPadding(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val subredditLabel = item.tags?.let { "r/$it" } ?: "Reddit"
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFFFF4500).copy(alpha = 0.8f),
                ) {
                    Text(
                        subredditLabel,
                        modifier   = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color      = Color.White,
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    item.title,
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 3,
                    overflow   = TextOverflow.Ellipsis,
                    style      = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SubredditUnavailablePrompt(
    subreddit: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().background(Color(0xFF111111)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(40.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint     = Color(0xFFFF4500),
                modifier = Modifier.size(64.dp),
            )
            Text(
                "r/$subreddit unavailable",
                color      = Color.White,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
            )
            Text(
                "This subreddit is private, quarantined, or not found. Try another one.",
                color     = Color.White.copy(alpha = 0.7f),
                style     = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onRetry,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF4500),
                    contentColor   = Color.White,
                ),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Go to r/nsfw", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
