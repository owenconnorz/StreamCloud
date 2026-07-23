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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import com.streamcloud.app.data.api.RedGifsRepository
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.ui.viewmodel.AdultViewModel
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch

/** Accent colour for RedGifs branding (vivid cyan/teal). */
private val RG_ACCENT = Color(0xFF00BCD4)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RedGifsFeedView(
    vm: AdultViewModel,
    modifier: Modifier = Modifier,
    onPlayItem: (AdultItem) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val items = state.items

    Box(modifier.fillMaxSize()) {
        if (state.loading && items.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = RG_ACCENT,
            )
        } else if (items.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    state.error ?: "No GIFs found.",
                    color     = if (state.error != null) MaterialTheme.colorScheme.error
                                else Color.White.copy(alpha = 0.7f),
                    style     = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { vm.refresh() },
                    colors  = ButtonDefaults.buttonColors(containerColor = RG_ACCENT),
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
                RedGifsPostCard(
                    item        = items[page],
                    isActive    = pagerState.currentPage == page,
                    onPlayClick = { onPlayItem(items[page]) },
                )
            }
            if (state.loadingMore) {
                LinearProgressIndicator(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color    = RG_ACCENT,
                )
            }
        }

        // ── Top chip bar ─────────────────────────────────────────────────
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
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
                    items(RedGifsRepository.POPULAR_TAGS) { tag ->
                        val active = tag == state.currentRedGifsTag
                        FilterChip(
                            selected = active,
                            onClick  = { vm.setRedGifsTag(tag) },
                            label    = { Text(tag, fontSize = 12.sp, maxLines = 1) },
                            shape    = RoundedCornerShape(50),
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RG_ACCENT,
                                selectedLabelColor     = Color.White,
                            ),
                        )
                    }
                }
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun RedGifsPostCard(item: AdultItem, isActive: Boolean = false, onPlayClick: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val db      = remember(context) { LibraryDb.get(context) }
    var isSaved by remember { mutableStateOf(false) }

    val player = remember(item.streamUrl) {
        if (item.streamUrl != null) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(item.streamUrl))
                repeatMode = Player.REPEAT_MODE_ONE
                volume     = 0f
                prepare()
            }
        } else null
    }
    LaunchedEffect(isActive) { player?.playWhenReady = isActive }
    DisposableEffect(item.streamUrl) { onDispose { player?.release() } }

    LaunchedEffect(item.id) {
        db.watchlist().isWatchlisted(redgifsWatchlistId(item.id)).collect { isSaved = it }
    }

    fun onSave() = scope.launch {
        val wid = redgifsWatchlistId(item.id)
        if (isSaved) {
            db.watchlist().remove(wid)
        } else {
            db.watchlist().add(WatchlistEntity(
                tmdbId    = wid,
                title     = item.title,
                posterUrl = item.thumbnail,
                mediaType = "reddit",
                csPlugin  = "redgifs",
                csUrl     = item.streamUrl ?: item.previewImage ?: "",
            ))
        }
    }

    fun onShare() {
        val url = "https://www.redgifs.com/watch/${item.id}"
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) },
            "Share GIF",
        ))
    }

    fun onDownload() {
        val url = item.streamUrl ?: item.previewImage ?: return
        runCatching {
            val uri      = Uri.parse(url)
            val fileName = uri.lastPathSegment?.let {
                if (it.contains('.')) it else "$it.mp4"
            } ?: "redgifs_${item.id}.mp4"

            val req = DownloadManager.Request(uri).apply {
                setTitle(item.title.take(80))
                setDescription("Downloading from RedGifs…")
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams  = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update   = { v -> v.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val img = item.previewImage ?: item.thumbnail
            if (img != null) {
                AsyncImage(model = img, contentDescription = item.title,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }

        if (!isActive && item.streamUrl != null) {
            Box(
                Modifier.size(72.dp).align(Alignment.Center)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onPlayClick),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(44.dp)) }
        }

        // Title top-right
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

        // Bottom: tag badge + Save/Share/Download row
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                .navigationBarsPadding()
                .padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val badge = item.tags?.let { "#$it" } ?: "RedGifs"
            Surface(shape = RoundedCornerShape(50), color = RG_ACCENT.copy(alpha = 0.85f)) {
                Text(badge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                RgActionButton(if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    if (isSaved) "Saved" else "Save",
                    if (isSaved) RG_ACCENT else Color.White) { onSave() }
                Spacer(Modifier.width(36.dp))
                RgActionButton(Icons.Default.Share, "Share") { onShare() }
                Spacer(Modifier.width(36.dp))
                RgActionButton(Icons.Default.Download, "Download") { onDownload() }
            }
        }
    }
}

@Composable
private fun RgActionButton(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        IconButton(
            onClick  = onClick,
            modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.12f), CircleShape),
        ) { Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp)) }
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun redgifsWatchlistId(id: String): Long =
    (-9_000_000_000L) - (id.hashCode().toLong() and 0xFFFFFL)
