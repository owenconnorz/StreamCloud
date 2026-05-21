package com.streamcloud.app.ui.screens.adult

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.RedditAdultSubs
import com.streamcloud.app.ui.viewmodel.AdultViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RedditFeedView(
    vm: AdultViewModel,
    customSubs: List<String>,
    onAddSub: (String) -> Unit,
    onRemoveSub: (String) -> Unit,
    onSwitchSource: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { state.items.size })
    var showAdd by remember { mutableStateOf(false) }


    LaunchedEffect(pagerState, state.subreddit) {
        snapshotFlow { pagerState.currentPage }.collect { idx ->
            if (state.items.isNotEmpty() && idx >= state.items.size - 3) vm.loadMore()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.items.isEmpty() && state.loading) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else if (state.items.isEmpty()) {
            Text(
                state.error ?: "No posts found in r/${state.subreddit}",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = state.items[page]
                FeedCard(
                    item = item,
                    isActive = page == pagerState.currentPage,
                    onShare = { shareUrl(context, "https://www.reddit.com${item.permalinkOrUrl()}", item.title) },
                    onDownload = { downloadUrl(context, item.streamUrl ?: item.thumbnail.orEmpty()) },
                )
            }
        }


        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(top = 12.dp, bottom = 8.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "r/${state.subreddit}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, "Add subreddit", tint = Color.White)
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(RedditAdultSubs.PRESETS, key = { "p_${it.second}" }) { (label, sub) ->
                    SubChipDark(
                        label = label,
                        selected = state.subreddit.equals(sub, ignoreCase = true),
                        onClick = { vm.setSubreddit(sub) },
                    )
                }
                items(customSubs, key = { "u_$it" }) { sub ->
                    SubChipDark(
                        label = "r/$sub",
                        selected = state.subreddit.equals(sub, ignoreCase = true),
                        onClick = { vm.setSubreddit(sub) },
                        onLongClick = { onRemoveSub(sub) },
                    )
                }
            }
        }


        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp)
                .clickable(onClick = onSwitchSource),
        ) {
            Text(
                "Reddit  ›",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    if (showAdd) {
        AddSubredditDialog(
            onDismiss = { showAdd = false },
            onConfirm = { typed ->
                showAdd = false
                if (typed.isNotBlank()) {
                    onAddSub(typed)
                    vm.setSubreddit(typed)
                }
            },
        )
    }
}

@Composable
private fun FeedCard(
    item: AdultItem,
    isActive: Boolean,
    onShare: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {

        val streamUrl = item.streamUrl
        if (item.isVideo && !streamUrl.isNullOrBlank()) {


            val player = remember(streamUrl) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(streamUrl))
                    repeatMode = ExoPlayer.REPEAT_MODE_ONE
                    prepare()
                }
            }
            DisposableEffect(streamUrl) { onDispose { player.release() } }
            LaunchedEffect(isActive) { player.playWhenReady = isActive }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {


            AsyncImage(
                model = item.thumbnail,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }


        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ActionPill(
                icon = Icons.Default.Bookmark,
                label = "Save",
                tint = if (saved) MaterialTheme.colorScheme.primary else Color.White,
                onClick = { saved = !saved },
            )
            ActionPill(
                icon = Icons.Default.Share,
                label = "Share",
                tint = Color.White,
                onClick = onShare,
            )
            ActionPill(
                icon = Icons.Default.Download,
                label = "Save",
                tint = Color.White,
                onClick = onDownload,
            )
        }


        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
                .padding(start = 16.dp, end = 84.dp, bottom = 56.dp, top = 64.dp),
        ) {
            Column {
                Text(
                    item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reddit · ${item.id}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubChipDark(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.18f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White
    Surface(
        color = bg,
        shape = RoundedCornerShape(50),
        modifier = Modifier.then(
            if (onLongClick != null) {
                Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            } else {
                Modifier.clickable(onClick = onClick)
            }
        ),
    ) {
        Text(
            label, color = fg,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AddSubredditDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a subreddit") },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.removePrefix("r/").trim() },
                placeholder = { Text("nsfw_videos") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(typed.trim()) }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun AdultItem.permalinkOrUrl(): String {


    return "/comments/$id/"
}

private fun shareUrl(context: android.content.Context, url: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun downloadUrl(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
