package com.streamcloud.app.ui.screens.adult

import android.view.ViewGroup
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
        } else if (items.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    state.error ?: "No posts found.",
                    color     = if (state.error != null) MaterialTheme.colorScheme.error
                                else Color.White.copy(alpha = 0.7f),
                    style     = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { vm.refresh() },
                    colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4500)),
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh", color = Color.White)
                }
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { items.size })

            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage >= items.size - 3) vm.loadMore()
            }

            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val isActive = pagerState.currentPage == page
                RedditPostCard(
                    item        = items[page],
                    isActive    = isActive,
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
        // Note: no statusBarsPadding here — the parent AdultScreen Column handles it.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                    )
                )
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
private fun RedditPostCard(item: AdultItem, isActive: Boolean = false, onPlayClick: () -> Unit) {
    val context = LocalContext.current
    val isVideoItem = item.isVideo && item.streamUrl != null

    // Build an ExoPlayer for video/gif items; null for still images
    val player = remember(item.streamUrl) {
        if (isVideoItem) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(item.streamUrl!!))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                prepare()
            }
        } else null
    }

    // Start / pause based on whether this card is the currently visible page
    LaunchedEffect(isActive) {
        player?.playWhenReady = isActive
    }

    // Release the player when this card leaves composition
    DisposableEffect(item.streamUrl) {
        onDispose { player?.release() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = !isVideoItem || !isActive, onClick = onPlayClick),
    ) {
        if (player != null) {
            // Inline video / gif playback via ExoPlayer
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            // Tap-to-open-full player overlay (only visible when NOT autoplaying)
            if (!isActive) {
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
        } else {
            // Static image
            val imageUrl = item.previewImage ?: item.thumbnail
            if (imageUrl != null) {
                AsyncImage(
                    model              = imageUrl,
                    contentDescription = item.title,
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
            // Play button for non-autoplaying video posts (e.g. external links)
            if (item.streamUrl != null) {
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
        }

        // Bottom info overlay
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
