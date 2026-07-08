package com.streamcloud.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.streamcloud.app.data.ytmusic.YtmSong
import com.streamcloud.app.ui.components.SongRowMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dedicated full-list page for a single section of an artist's page. */
@Composable
fun MusicArtistSectionScreen(
    channelUrl: String,
    sectionType: String,
    onBack: () -> Unit,
    onPlay: (YtTrack) -> Unit,
    onAlbumClick: (id: String, title: String, thumbnail: String?) -> Unit = { _, _, _ -> },
    onArtistClick: (url: String, thumbnail: String?) -> Unit = { _, _ -> },
) {
    var page by remember(channelUrl) { mutableStateOf<NewPipeRepository.ArtistPage?>(null) }
    var loading by remember(channelUrl) { mutableStateOf(true) }
    var error by remember(channelUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(channelUrl) {
        loading = true; error = null; page = null
        try { page = withContext(Dispatchers.IO) { NewPipeRepository.loadArtist(channelUrl) } }
        catch (e: Throwable) { error = e.message }
        loading = false
    }

    val sectionTitle = when (sectionType) {
        "popular"  -> "Popular"
        "singles"  -> "Singles"
        "albums"   -> "Albums"
        "videos"   -> "Videos"
        "featured" -> "Featured on"
        "related"  -> "Related Artists"
        else       -> sectionType.replaceFirstChar { it.uppercaseChar() }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Couldn't load section\n$error",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            page != null -> ArtistSectionContent(
                page = page!!,
                sectionType = sectionType,
                sectionTitle = sectionTitle,
                statusBarPadding = statusBarPadding,
                onPlay = onPlay,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
            )
        }

        // Back button overlay
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(top = statusBarPadding + 6.dp, start = 8.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ArtistSectionContent(
    page: NewPipeRepository.ArtistPage,
    sectionType: String,
    sectionTitle: String,
    statusBarPadding: androidx.compose.ui.unit.Dp,
    onPlay: (YtTrack) -> Unit,
    onAlbumClick: (id: String, title: String, thumbnail: String?) -> Unit,
    onArtistClick: (url: String, thumbnail: String?) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = statusBarPadding + 56.dp,
            bottom = 24.dp,
        ),
    ) {
        item {
            Text(
                sectionTitle,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            )
        }

        when (sectionType) {
            "popular" -> {
                items(page.topTracks, key = { "pop_${it.url}" }) { tr ->
                    ArtistSectionTrackRow(
                        track = tr,
                        onPlay = { onPlay(tr) },
                        onArtistClick = onArtistClick,
                    )
                }
            }
            "singles" -> {
                items(page.singles, key = { "sin_${it.url}" }) { album ->
                    ArtistSectionAlbumRow(
                        album = album,
                        onClick = { onAlbumClick(albumIdFromUrl(album.url), album.title, album.thumbnail) },
                    )
                }
            }
            "albums" -> {
                items(page.albums, key = { "alb_${it.url}" }) { album ->
                    ArtistSectionAlbumRow(
                        album = album,
                        onClick = { onAlbumClick(albumIdFromUrl(album.url), album.title, album.thumbnail) },
                    )
                }
            }
            "videos" -> {
                items(page.videos, key = { "vid_${it.url}" }) { vid ->
                    ArtistSectionVideoRow(
                        track = vid,
                        onClick = { onPlay(vid) },
                    )
                }
            }
            "featured" -> {
                items(page.featuredOn, key = { "feat_${it.url}" }) { pl ->
                    ArtistSectionAlbumRow(
                        album = pl,
                        subtitle = "YouTube Music",
                        onClick = { onAlbumClick(albumIdFromUrl(pl.url), pl.title, pl.thumbnail) },
                    )
                }
            }
            "related" -> {
                items(page.relatedArtists, key = { "rel_${it.url}" }) { artist ->
                    ArtistSectionRelatedRow(
                        artist = artist,
                        onClick = { onArtistClick(artist.url, artist.thumbnail) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistSectionTrackRow(
    track: YtTrack,
    onPlay: () -> Unit,
    onArtistClick: (url: String, thumbnail: String?) -> Unit,
) {
    val song = remember(track.url) {
        YtmSong(
            videoId         = Uri.parse(track.url).getQueryParameter("v") ?: track.url.substringAfterLast("/"),
            title           = track.title,
            artist          = track.uploader,
            album           = null,
            thumbnail       = track.thumbnail,
            durationSeconds = null,
        )
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.thumbnail, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("♪ ${track.uploader}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SongRowMenu(
            song = song,
            onPlay = onPlay,
            onViewArtist = { artistName ->
                onArtistClick(
                    "https://music.youtube.com/search?q=${Uri.encode(artistName)}",
                    null,
                )
            },
        )
    }
}

@Composable
private fun ArtistSectionAlbumRow(album: YtAlbum, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = album.thumbnail, contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = subtitle ?: album.year
            meta?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun ArtistSectionVideoRow(track: YtTrack, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.thumbnail, contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                append(track.uploader)
                if (track.viewCount > 0) append(" • ${humanViewCountSection(track.viewCount)} views")
            }
            Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ArtistSectionRelatedRow(artist: YtArtist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = artist.thumbnail, contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist.subscriberLabel?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

private fun albumIdFromUrl(url: String): String {
    val uri = Uri.parse(url)
    return uri.getQueryParameter("list") ?: uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: url
}

private fun humanViewCountSection(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1f".format(n / 1_000_000_000.0).trimEnd('0').trimEnd('.') + "B"
    n >= 1_000_000     -> "%.1f".format(n / 1_000_000.0).trimEnd('0').trimEnd('.') + "M"
    n >= 1_000         -> "%.1f".format(n / 1_000.0).trimEnd('0').trimEnd('.') + "K"
    else               -> n.toString()
}
