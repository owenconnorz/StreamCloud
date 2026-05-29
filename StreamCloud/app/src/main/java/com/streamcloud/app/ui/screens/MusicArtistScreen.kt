package com.streamcloud.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.newpipe.YtAlbum
import com.streamcloud.app.data.newpipe.YtArtist
import com.streamcloud.app.data.newpipe.YtTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MusicArtistScreen(
    channelUrl: String,
    onBack: () -> Unit,
    onPlay: (YtTrack) -> Unit,
    onAlbumClick: (id: String, title: String) -> Unit = { _, _ -> },
    onArtistClick: (String) -> Unit = {},
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
            page != null -> ArtistPageContent(
                page = page!!,
                onPlay = onPlay,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
            )
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
    onArtistClick: (String) -> Unit,
) {
    var descExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {

        // ── Banner ──
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
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
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text(
                        page.name,
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    page.subscriberLabel?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                }
            }
        }

        // ── Action buttons ──
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
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
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f)),
                ) {
                    Icon(Icons.Default.Shuffle, "Shuffle", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = {},
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f)),
                ) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }

        // ── Popular ──
        if (page.topTracks.isNotEmpty()) {
            item { ArtistSectionHeader("Popular") }
            items(page.topTracks.take(5), key = { "pop_${it.url}" }) { tr ->
                ArtistTrackRow(track = tr, onPlay = { onPlay(tr) })
            }
        }

        // ── Singles ──
        if (page.singles.isNotEmpty()) {
            item { ArtistSectionHeader("Singles") }
            items(page.singles.chunked(2), key = { "sin_row_${it.first().url}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    row.forEach { album ->
                        ArtistAlbumCell(album, Modifier.weight(1f), onClick = {
                            onAlbumClick(albumId(album.url), album.title)
                        })
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        // ── Albums ──
        if (page.albums.isNotEmpty()) {
            item { ArtistSectionHeader("Albums") }
            items(page.albums.chunked(2), key = { "alb_row_${it.first().url}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    row.forEach { album ->
                        ArtistAlbumCell(album, Modifier.weight(1f), onClick = {
                            onAlbumClick(albumId(album.url), album.title)
                        })
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        // ── Videos ──
        if (page.videos.isNotEmpty()) {
            item { ArtistSectionHeader("Videos") }
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

        // ── Featured on ──
        if (page.featuredOn.isNotEmpty()) {
            item { ArtistSectionHeader("Featured on") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(page.featuredOn, key = { "feat_${it.url}" }) { pl ->
                        FeaturedPlaylistCard(album = pl, onClick = {
                            onAlbumClick(albumId(pl.url), pl.title)
                        })
                    }
                }
            }
        }

        // ── Related Artists ──
        if (page.relatedArtists.isNotEmpty()) {
            item { ArtistSectionHeader("Related Artists") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(page.relatedArtists, key = { "rel_${it.url}" }) { artist ->
                        RelatedArtistCard(artist = artist, onClick = { onArtistClick(artist.url) })
                    }
                }
            }
        }

        // ── Description ──
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
private fun ArtistSectionHeader(title: String) {
    Text(
        title,
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 10.dp),
    )
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
            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A)),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "\uD83C\uDFB5 ${track.uploader}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.MoreVert, null, tint = Color.White.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun ArtistAlbumCell(album: YtAlbum, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(8.dp)) {
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
        Text(album.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        album.year?.let { Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp) }
    }
}

@Composable
private fun ArtistVideoCard(track: YtTrack, onClick: () -> Unit) {
    Column(Modifier.width(200.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = track.thumbnail,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2A2A2A)),
        )
        Spacer(Modifier.height(6.dp))
        Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        val meta = buildString {
            append(track.uploader)
            if (track.viewCount > 0) append(" • ${humanViewCount(track.viewCount)} views")
        }
        Text(meta, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeaturedPlaylistCard(album: YtAlbum, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = album.thumbnail,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2A2A2A)),
        )
        Spacer(Modifier.height(6.dp))
        Text(album.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        Text("YouTube Music", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun RelatedArtistCard(artist: YtArtist, onClick: () -> Unit) {
    Column(
        Modifier.width(110.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = artist.thumbnail,
            contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF2A2A2A)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            artist.name,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        artist.subscriberLabel?.let {
            Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

private fun albumId(url: String): String {
    val uri = Uri.parse(url)
    return uri.getQueryParameter("list")
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: url
}

private fun humanViewCount(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
