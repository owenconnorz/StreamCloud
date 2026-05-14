package com.aioweb.app.ui.screens

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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aioweb.app.data.newpipe.NewPipeRepository
import com.aioweb.app.data.newpipe.YtTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Metrolist-style artist page — large banner, avatar, subscriber count, About card,
 * Subscribe / Radio / Shuffle action row, then horizontal "Top tracks" + "Albums" rails.
 *
 * The track tap-through hands off to [onPlay] which the host wires into MusicPlaybackService.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicArtistScreen(
    channelUrl: String,
    onBack: () -> Unit,
    onPlay: (YtTrack) -> Unit,
) {
    var page by remember(channelUrl) {
        mutableStateOf<NewPipeRepository.ArtistPage?>(null)
    }
    var loading by remember(channelUrl) { mutableStateOf(true) }
    var error by remember(channelUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(channelUrl) {
        loading = true
        error = null
        try {
            page = withContext(Dispatchers.IO) { NewPipeRepository.loadArtist(channelUrl) }
        } catch (e: Throwable) {
            error = e.message
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.name ?: "Artist") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        when {
            loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            error != null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Couldn't load artist: $error", color = MaterialTheme.colorScheme.error)
            }

            page != null -> ArtistContent(
                page = page!!,
                modifier = Modifier.padding(padding),
                onTrackPlay = onPlay,
            )
        }
    }
}

@Composable
private fun ArtistContent(
    page: NewPipeRepository.ArtistPage,
    modifier: Modifier,
    onTrackPlay: (YtTrack) -> Unit,
) {
    LazyColumn(modifier.fillMaxSize()) {
        item {
            // Banner + avatar overlay
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                AsyncImage(
                    model = page.banner ?: page.avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Box(
                    Modifier.matchParentSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                        ),
                    ),
                )
                Row(
                    Modifier.align(Alignment.BottomStart).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = page.avatar,
                        contentDescription = page.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(80.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            page.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        page.subscriberLabel?.let {
                            Text(it, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
        // Action row
        item {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { page.topTracks.firstOrNull()?.let(onTrackPlay) },
                    enabled = page.topTracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Shuffle, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Shuffle")
                }
                OutlinedButton(
                    onClick = { page.topTracks.firstOrNull()?.let(onTrackPlay) },
                    enabled = page.topTracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Radio, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Radio")
                }
            }
        }
        // About card
        if (page.description.isNotBlank()) {
            item {
                Text(
                    "About",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item {
                Text(
                    page.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        // Top tracks
        if (page.topTracks.isNotEmpty()) {
            item {
                Text(
                    "Top tracks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            items(page.topTracks, key = { "atrk_${it.url}" }) { tr ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onTrackPlay(tr) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = tr.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr.title,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            tr.uploader,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        // Albums
        if (page.albums.isNotEmpty()) {
            item {
                Text(
                    "Albums",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(page.albums, key = { "alb_${it.url}" }) { alb ->
                        Column(Modifier.width(140.dp)) {
                            AsyncImage(
                                model = alb.thumbnail,
                                contentDescription = alb.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(140.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                alb.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
