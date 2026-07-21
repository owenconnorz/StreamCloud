package com.streamcloud.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class AppAnnouncement(
    val version: String,
    val title: String,
    val body: String,
)

val CURRENT_ANNOUNCEMENT = AppAnnouncement(
    version = "2.1",
    title   = "What's New in StreamCloud",
    body    = "• Skip Intro / Outro button — powered by IntroDB\n" +
              "• Parental Guide overlay — content warnings at video start\n" +
              "• Quality badge colours — 4K gold, 1080p blue, 720p green\n" +
              "• Long-press stream card to copy URL\n" +
              "• Hold-to-Speed mode in the player\n" +
              "• Subtitle language groups\n" +
              "• Sortable movie watchlist\n" +
              "• Up Next row on the home screen",
)

@Composable
fun AnnouncementOverlay(
    seenVersion: String,
    onDismiss: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(seenVersion != CURRENT_ANNOUNCEMENT.version) }

    if (!visible) return

    Dialog(
        onDismissRequest = {
            visible = false
            onDismiss(CURRENT_ANNOUNCEMENT.version)
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        CURRENT_ANNOUNCEMENT.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            visible = false
                            onDismiss(CURRENT_ANNOUNCEMENT.version)
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    CURRENT_ANNOUNCEMENT.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 24.sp,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        visible = false
                        onDismiss(CURRENT_ANNOUNCEMENT.version)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Got it")
                }
            }
        }
    }
}
