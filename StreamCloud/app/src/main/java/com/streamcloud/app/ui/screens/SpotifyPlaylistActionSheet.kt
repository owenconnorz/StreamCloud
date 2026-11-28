package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.spotify.SpotifyPlaylist

internal enum class SpotifyPlaylistMenuAction(
    val label: String,
    val icon: ImageVector,
) {
    SHARE("Share", Icons.Default.Share),
    DETAILS("Name and details", Icons.Default.Edit),
    COVER_ART("Create cover art", Icons.Default.MusicNote),
    DELETE("Delete playlist", Icons.Default.Delete),
    PIN("Pin playlist", Icons.Default.PushPin),
    COLLABORATORS("Invite collaborators", Icons.Default.GroupAdd),
    JAM("Start a Jam", Icons.Default.MusicNote),
    FOLDER("Move to folder", Icons.Default.FolderOpen),
    PROFILE("Add to your profile", Icons.Default.PersonAdd),
    PRIVACY("Make private", Icons.Default.Lock),
    TASTE_PROFILE("Exclude from your taste profile", Icons.Default.RemoveCircleOutline),
    SPOTIFY_CODE("Show Spotify Code", Icons.Default.Code),
}

@Composable
internal fun SpotifyPlaylistActionSheet(
    playlist: SpotifyPlaylist,
    onDismiss: () -> Unit,
    onAction: (SpotifyPlaylistMenuAction) -> Unit,
) {
    val background = Color(0xFF202020)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = background,
        contentColor = Color.White,
        dragHandle = {
            Spacer(
                Modifier
                    .padding(top = 10.dp, bottom = 14.dp)
                    .size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.45f)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 700.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!playlist.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = playlist.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Icon(
                        SpotifyPlaylistMenuAction.JAM.icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .padding(14.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            if (playlist.ownerName.isNotBlank()) {
                                append("by ${playlist.ownerName}")
                            } else {
                                append("Spotify playlist")
                            }
                            playlist.trackCount?.let { append(" · $it tracks") }
                        },
                        color = Color.White.copy(alpha = 0.64f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(
                Modifier.padding(top = 14.dp),
                color = Color.White.copy(alpha = 0.12f),
            )
            SpotifyPlaylistMenuAction.entries.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAction(action) }
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        action.label,
                        color = Color.White,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (action !in setOf(
                            SpotifyPlaylistMenuAction.SHARE,
                            SpotifyPlaylistMenuAction.DETAILS,
                        )
                    ) {
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Continue in Spotify",
                            tint = Color.White.copy(alpha = 0.42f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
