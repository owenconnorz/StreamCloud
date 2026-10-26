package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.spotify.SpotifyTrack

internal fun formatSpotifyPlaylistDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}min" else "${totalMinutes}min"
}

@Composable
internal fun SpotifyPlaylistHero(
    title: String,
    description: String,
    ownerName: String,
    imageUrl: String?,
    tracks: List<SpotifyTrack>,
    totalTrackCount: Int,
    durationLabel: String?,
    artworkColor: Color,
    accentColor: Color,
    accentContainerColor: Color,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        artworkColor.copy(alpha = 0.8f),
                        accentContainerColor.copy(alpha = 0.9f),
                        SpotifyBlack,
                    ),
                ),
            )
            .padding(bottom = 10.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            val coverSize = (maxWidth - 48.dp).coerceAtMost(300.dp)
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF292929)),
            ) {
                when {
                    !imageUrl.isNullOrBlank() -> AsyncImage(
                        model = imageUrl,
                        contentDescription = "$title playlist cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    tracks.size >= 4 -> Column(Modifier.fillMaxSize()) {
                        listOf(tracks.take(2), tracks.drop(2).take(2)).forEach { rowTracks ->
                            Row(Modifier.weight(1f)) {
                                rowTracks.forEach { track ->
                                    AsyncImage(
                                        model = track.imageUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                            }
                        }
                    }
                    tracks.firstOrNull()?.imageUrl != null -> AsyncImage(
                        model = tracks.first().imageUrl,
                        contentDescription = "$title playlist cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = Color.White.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ownerName.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        ownerName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                buildString {
                    append("${totalTrackCount.takeIf { it > 0 } ?: tracks.size} songs")
                    durationLabel?.let { append(" · $it") }
                },
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onShuffle, enabled = tracks.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Shuffle play",
                        tint = accentColor,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(SpotifyGreen)
                        .clickable(enabled = tracks.isNotEmpty(), onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play playlist",
                        tint = Color.Black,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlaylistActionPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF292929),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SpotifyRecommendedTrackRow(
    track: SpotifyTrack,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpotifyDetailArtwork(
            imageUrl = track.imageUrl,
            contentDescription = track.title,
            modifier = Modifier.size(54.dp),
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                track.title,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artists,
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Filled.AddCircleOutline,
                contentDescription = "Add ${track.title} to playlist",
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
