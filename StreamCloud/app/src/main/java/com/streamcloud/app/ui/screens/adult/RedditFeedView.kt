package com.streamcloud.app.ui.screens.adult

import android.content.Intent
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.ui.viewmodel.AdultViewModel
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch

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
    var showLogin by rememberSaveable { mutableStateOf(false) }

    if (showLogin) {
        RedditLoginScreen(
            onLoginSuccess = { username ->
                showLogin = false
                vm.completeRedditLogin(username)
            },
            onBack = { showLogin = false },
        )
        return
    }

    val subLabels = remember(customSubs) {
        val preset = RedditAdultSubs.PRESETS.map { it.first }
        (preset + customSubs.map { if (it.startsWith("r/")) it else "r/$it" }).distinct()
    }

    if (state.redditNeedsAuth && items.isEmpty()) {
        SubredditUnavailablePrompt(
            subreddit = state.currentSubreddit,
            onRetry   = { vm.refresh() },
            onSignIn  = { showLogin = true },
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

        // ── Top bar: subreddit chips + refresh ────────────────────────────
        // Parent AdultScreen Column already handles statusBarsPadding — no extra padding needed here.
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
                        "Reddit sign-in required",
                        modifier = Modifier.weight(1f),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = { showLogin = true }) { Text("Sign in") }
                }
            }
        }
    }
}

@Composable
private fun RedditPostCard(item: AdultItem, isActive: Boolean = false, onPlayClick: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val isVideoItem = item.isVideo && item.streamUrl != null

    // ── ExoPlayer for video / gif items ──────────────────────────────────
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
    LaunchedEffect(isActive) { player?.playWhenReady = isActive }
    DisposableEffect(item.streamUrl) { onDispose { player?.release() } }

    // ── Library save state ───────────────────────────────────────────────
    val db = remember(context) { LibraryDb.get(context) }
    var isSaved by remember { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        db.watchlist().isWatchlisted(redditWatchlistId(item.id)).collect { isSaved = it }
    }

    fun onSave() {
        scope.launch {
            val wid = redditWatchlistId(item.id)
            if (isSaved) {
                db.watchlist().remove(wid)
            } else {
                db.watchlist().add(
                    WatchlistEntity(
                        tmdbId    = wid,
                        title     = item.title,
                        posterUrl = item.thumbnail,
                        mediaType = "reddit",
                        csPlugin  = "reddit",
                        csUrl     = item.streamUrl ?: item.previewImage ?: "",
                    )
                )
            }
        }
    }

    fun onShare() {
        val url = "https://reddit.com/${item.id}/"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "Share post"))
    }

    fun onDownload() {
        val url = item.streamUrl ?: item.previewImage ?: return
        runCatching {
            val uri      = Uri.parse(url)
            val fileName = uri.lastPathSegment?.let {
                if (it.contains('.')) it else "$it.mp4"
            } ?: "reddit_${item.id}.mp4"

            val req = DownloadManager.Request(uri).apply {
                setTitle(item.title.take(80))
                setDescription("Downloading from Reddit…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                addRequestHeader("User-Agent", "android:com.streamcloud.app:v1.0.0")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(context, "Downloading…", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = !isVideoItem || !isActive, onClick = onPlayClick),
    ) {
        // ── Media content ────────────────────────────────────────────────
        if (player != null) {
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
                update   = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
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
                    Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }
        } else {
            val imageUrl = item.previewImage ?: item.thumbnail
            if (imageUrl != null) {
                AsyncImage(
                    model              = imageUrl,
                    contentDescription = item.title,
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
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
                    Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }
        }

        // ── Title — top-right overlay ─────────────────────────────────────
        Text(
            text       = item.title,
            color      = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 3,
            overflow   = TextOverflow.Ellipsis,
            style      = MaterialTheme.typography.bodyMedium,
            textAlign  = TextAlign.End,
            modifier   = Modifier
                .align(Alignment.TopEnd)
                .widthIn(max = 220.dp)
                .padding(top = 72.dp, end = 12.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )

        // ── Bottom bar: subreddit badge + Save/Share/Download row ─────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                    )
                )
                .navigationBarsPadding()          // system nav bar inset first
                .padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Subreddit chip
            val subredditLabel = item.tags?.let { "r/$it" } ?: "Reddit"
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFFFF4500).copy(alpha = 0.85f),
            ) {
                Text(
                    subredditLabel,
                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    color      = Color.White,
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // ── Action buttons row ────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                PostActionButton(
                    icon  = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    label = if (isSaved) "Saved" else "Save",
                    tint  = if (isSaved) Color(0xFFFF4500) else Color.White,
                    onClick = ::onSave,
                )
                Spacer(Modifier.width(36.dp))
                PostActionButton(
                    icon    = Icons.Default.Share,
                    label   = "Share",
                    onClick = ::onShare,
                )
                Spacer(Modifier.width(36.dp))
                PostActionButton(
                    icon    = Icons.Default.Download,
                    label   = "Download",
                    onClick = ::onDownload,
                )
            }
        }
    }
}

@Composable
private fun PostActionButton(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick  = onClick,
            modifier = Modifier
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.12f), CircleShape),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun redditWatchlistId(postId: String): Long =
    (-8_000_000_000L) - (postId.hashCode().toLong() and 0xFFFFFL)

@Composable
private fun SubredditUnavailablePrompt(
    subreddit: String,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
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
                "Sign in to load r/$subreddit",
                color      = Color.White,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
            )
            Text(
                "Reddit blocks anonymous NSFW feeds. Sign in securely in Reddit, then StreamCloud will retry using that session.",
                color     = Color.White.copy(alpha = 0.7f),
                style     = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onSignIn,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF4500),
                    contentColor   = Color.White,
                ),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign in to Reddit", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}
