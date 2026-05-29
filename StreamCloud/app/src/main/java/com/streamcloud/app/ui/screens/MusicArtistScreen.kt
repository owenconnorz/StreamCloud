package com.streamcloud.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.newpipe.YtAlbum
import com.streamcloud.app.data.newpipe.YtTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MusicArtistScreen(
    channelUrl: String,
    onBack: () -> Unit,
    onPlay: (YtTrack) -> Unit,
    onAlbumClick: (id: String, title: String) -> Unit = { _, _ -> },
) {
    var page by remember(channelUrl) { mutableStateOf<NewPipeRepository.ArtistPage?>(null) }
    var loading by remember(channelUrl) { mutableStateOf(true) }
    var error by remember(channelUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(channelUrl) {
        loading = true; error = null
        try { page = withContext(Dispatchers.IO) { NewPipeRepository.loadArtist(channelUrl) } }
        catch (e: Throwable) { error = e.message }
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't load artist: $error", color = Color(0xFFFF5555), modifier = Modifier.padding(24.dp))
            }
            page != null -> ArtistPageContent(page = page!!, onPlay = onPlay, onAlbumClick = onAlbumClick)
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(4.dp)
                .align(Alignment.TopStart),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
    }
}

@Composable
private fun ArtistPageContent(
    page: NewPipeRepository.ArtistPage,
    onPlay: (YtTrack) -> Unit,
    onAlbumClick: (id: String, title: String) -> Unit,
) {
    var descExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {

        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                AsyncImage(
                    model = page.banner ?: page.avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.25f),
                            0.45f to Color.Black.copy(alpha = 0.15f),
                            1.0f to Color(0xFF0A0A0A),
                        )
                    )
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text(
                        page.name,
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val metaLine = buildString {
                        page.subscriberLabel?.let { append(it) }
                        if (page.viewCount > 0) {
                            if (isNotEmpty()) append("   ")
                            append(artistViewCount(page.viewCount) + " views")
                        }
                    }
                    if (metaLine.isNotBlank()) {
                        Text(metaLine, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { page.topTracks.firstOrNull()?.let(onPlay) },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Text("Shuffle", color = Color(0xFF111111), fontWeight = FontWeight.Bold)
                }
                IconButton(
                    onClick = { page.topTracks.firstOrNull()?.let(onPlay) },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f)),
                ) {
                    Icon(Icons.Default.Shuffle, "Shuffle", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = {},
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f)),
                ) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }

        if (page.topTracks.isNotEmpty()) {
            item {
                ArtistSectionHeader(title = "Popular", onMore = {})
            }
            items(page.topTracks.take(5), key = { "pop_${it.url}" }) { tr ->
                ArtistTrackRow(track = tr, onPlay = { onPlay(tr) })
            }
        }

        if (page.albums.isNotEmpty()) {
            item {
                ArtistSectionHeader(title = "Albums", onMore = {})
            }
            items(
                items = page.albums.chunked(2),
                key = { "alb_row_${it.first().url}" },
            ) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    row.forEach { album ->
                        ArtistAlbumCell(
                            album = album,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val uri = Uri.parse(album.url)
                                val id = uri.getQueryParameter("list")
                                    ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                                    ?: album.url
                                onAlbumClick(id, album.title)
                            },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (page.videos.isNotEmpty()) {
            item {
                ArtistSectionHeader(title = "Videos", onMore = {})
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(page.videos, key = { "vid_${it.url}" }) { vid ->
                        ArtistVideoCard(track = vid, onClick = { onPlay(vid) })
                    }
                }
            }
        }

        if (page.description.isNotBlank()) {
            item {
                Text(
                    "Description",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
                )
            }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1C1C))
                        .clickable { descExpanded = !descExpanded }
                        .padding(16.dp),
                ) {
                    Column {
                        Text(
                            page.description,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 4,
                            overflow = if (descExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (descExpanded) "Less" else "More",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistSectionHeader(title: String, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onMore) {
            Text("More", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ArtistTrackRow(track: YtTrack, onPlay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A2A2A)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "\uD83C\uDFB5 ${track.uploader}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.MoreVert, null, tint = Color.White.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun ArtistAlbumCell(
    album: YtAlbum,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        AsyncImage(
            model = album.thumbnail,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2A2A2A)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            album.title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
        )
        album.year?.let {
            Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ArtistVideoCard(track: YtTrack, onClick: () -> Unit) {
    Column(
        Modifier
            .width(190.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = track.thumbnail,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2A2A2A)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            track.title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
        )
        Text(
            track.uploader,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

private fun artistViewCount(n: Long): String = when {
    n >= 1_000_000_000 -> "%.2fB".format(n / 1_000_000_000.0)
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
