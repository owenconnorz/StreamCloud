package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.spotify.SpotifyAlbum
import com.streamcloud.app.data.spotify.SpotifyArtist
import com.streamcloud.app.data.spotify.SpotifyPlaylist
import com.streamcloud.app.data.spotify.SpotifyTrack
import java.text.NumberFormat
import java.util.Locale

internal val SpotifyBlack = Color(0xFF121212)
internal val SpotifyGreen = Color(0xFF1ED760)

@Composable
internal fun SpotifyDetailArtwork(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
) {
    Box(
        modifier.clip(
            if (circle) CircleShape else RoundedCornerShape(10.dp),
        ).background(Color(0xFF292929)),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
internal fun SpotifyDetailTrackRow(
    track: SpotifyTrack,
    position: Int? = null,
    showPlayCount: Boolean = false,
    playing: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (position != null) {
            Text(
                position.toString(),
                modifier = Modifier.width(26.dp),
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SpotifyDetailArtwork(
            imageUrl = track.imageUrl,
            contentDescription = track.title,
            modifier = Modifier.size(54.dp),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(
                track.title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = when {
                showPlayCount && track.playCount > 0L -> formatSpotifyCount(track.playCount)
                track.artists.isNotBlank() -> track.artists
                else -> formatSpotifyDuration(track.durationMs)
            }
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (playing) {
            Text("…", color = SpotifyGreen, style = MaterialTheme.typography.titleLarge)
        } else {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play ${track.title}",
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
internal fun SpotifyDetailAlbumCard(
    album: SpotifyAlbum,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(156.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SpotifyDetailArtwork(
            imageUrl = album.imageUrl,
            contentDescription = album.name,
            modifier = Modifier.size(156.dp),
        )
        Text(
            album.name,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            listOf(album.releaseDate.take(4), "Album").filter(String::isNotBlank).joinToString(" · "),
            color = Color.White.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun SpotifyDetailPlaylistCard(
    playlist: SpotifyPlaylist,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(156.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SpotifyDetailArtwork(
            imageUrl = playlist.imageUrl,
            contentDescription = playlist.name,
            modifier = Modifier.size(156.dp),
        )
        Text(
            playlist.name,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            playlist.description.ifBlank { playlist.ownerName.ifBlank { "Spotify playlist" } },
            color = Color.White.copy(alpha = 0.65f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun SpotifyDetailArtistCard(
    artist: SpotifyArtist,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(142.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SpotifyDetailArtwork(
            imageUrl = artist.imageUrl,
            contentDescription = artist.name,
            modifier = Modifier.size(126.dp),
            circle = true,
        )
        Text(
            artist.name,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Artist",
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun formatSpotifyCount(value: Long): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).format(value.coerceAtLeast(0L))

internal fun formatSpotifyDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
