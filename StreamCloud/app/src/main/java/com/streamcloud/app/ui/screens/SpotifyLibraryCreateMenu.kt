@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class SpotifyLibraryCreateAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Playlist(
        "Playlist",
        "Create a playlist with songs or episodes",
        Icons.Default.MusicNote,
    ),
    CollaborativePlaylist(
        "Collaborative playlist",
        "Create a playlist together with friends",
        Icons.Default.Share,
    ),
    Blend(
        "Blend",
        "Combine your friends’ tastes into a playlist",
        Icons.Default.AutoAwesome,
    ),
    Folder(
        "Folder",
        "Organise your Spotify playlists",
        Icons.Default.FolderOpen,
    ),
}

private val SpotifyCreateSheet = Color(0xFF202020)
private val SpotifyCreateMuted = Color(0xFFB3B3B3)

@Composable
internal fun SpotifyLibraryCreateActionSheet(
    onDismiss: () -> Unit,
    onChoose: (SpotifyLibraryCreateAction) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SpotifyCreateSheet,
        contentColor = Color.White,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(SpotifyCreateMuted),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
        ) {
            SpotifyLibraryCreateAction.entries.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(action) }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF414141)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            action.icon,
                            contentDescription = null,
                            tint = Color(0xFFE6E6E6),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            action.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            action.subtitle,
                            color = SpotifyCreateMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SpotifyLibraryCreateNameDialog(
    action: SpotifyLibraryCreateAction,
    isSaving: Boolean,
    error: String?,
    cooldownRemainingSeconds: Long = 0L,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember(action) {
        mutableStateOf(
            when (action) {
                SpotifyLibraryCreateAction.CollaborativePlaylist -> "Our playlist"
                SpotifyLibraryCreateAction.Folder -> "New folder"
                else -> "My playlist"
            },
        )
    }
    val title = when (action) {
        SpotifyLibraryCreateAction.CollaborativePlaylist -> "Name your collaborative playlist"
        SpotifyLibraryCreateAction.Folder -> "Give your folder a name"
        else -> "Give your playlist a name"
    }
    val visibleError = if (cooldownRemainingSeconds > 0L) {
        "Spotify is rate-limiting requests. Try again in ${formatSpotifyCooldown(cooldownRemainingSeconds)}."
    } else {
        error
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = SpotifyCreateSheet,
        titleContentColor = Color.White,
        textContentColor = SpotifyCreateMuted,
        title = {
            Text(title, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    singleLine = true,
                    enabled = !isSaving,
                    placeholder = { Text(action.title, color = SpotifyCreateMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = SpotifyCreateGreen,
                        focusedBorderColor = SpotifyCreateGreen,
                        unfocusedBorderColor = SpotifyCreateMuted,
                        focusedLabelColor = SpotifyCreateGreen,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (visibleError != null) {
                    Text(visibleError, color = Color(0xFFFF7777))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && !isSaving && cooldownRemainingSeconds == 0L,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyCreateGreen,
                    contentColor = Color.Black,
                ),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel", color = Color.White)
            }
        },
    )
}

private fun formatSpotifyCooldown(seconds: Long): String {
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainingSeconds = seconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${remainingSeconds}s"
        else -> "${remainingSeconds}s"
    }
}

private val SpotifyCreateGreen = Color(0xFF1ED760)
