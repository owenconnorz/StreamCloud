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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streamcloud.app.ui.theme.Teal

private val StreamCloudSheet = Color(0xFF0B1514)
private val StreamCloudCard = Color(0xFF101F1D)
private val StreamCloudCardPressed = Color(0xFF18302D)
private val StreamCloudOnSheet = Color(0xFFE8F2F0)
private val StreamCloudMuted = Color(0xFF9CB2AE)

@Composable
fun YtMusicAccountSheet(
    userName: String,
    avatarUrl: String,
    signedIn: Boolean,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(620.dp)
                .clip(RoundedCornerShape(30.dp)),
            color = StreamCloudSheet,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "StreamCloud",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = StreamCloudOnSheet,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close account menu",
                            tint = StreamCloudOnSheet,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    color = StreamCloudCard,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AccountAvatar(
                            avatarUrl = avatarUrl,
                            size = 58.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (signedIn) userName.ifBlank { "YouTube Music account" }
                                else "YouTube Music",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = StreamCloudOnSheet,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (signedIn) "Connected to StreamCloud"
                                else "Sign in for your music library",
                                style = MaterialTheme.typography.bodySmall,
                                color = StreamCloudMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (signedIn) {
                            TextButton(onClick = onSignOut) {
                                Text("Log out", color = StreamCloudOnSheet)
                            }
                        } else {
                            Button(
                                onClick = onSignIn,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Teal,
                                    contentColor = Color(0xFF06201D),
                                ),
                            ) {
                                Text("Sign in", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (signedIn) {
                    AccountActionRow(
                        icon = Icons.Default.SwapHoriz,
                        title = "Switch YouTube account",
                        subtitle = "Sign in with a different YouTube Music account",
                        onClick = onSwitchAccount,
                    )
                }
                AccountActionRow(
                    icon = Icons.Default.Sync,
                    title = "Account sync",
                    subtitle = if (signedIn) {
                        "Recommendations and library use your connected account"
                    } else {
                        "Sign in to sync recommendations and your library"
                    },
                    trailing = if (signedIn) "On" else "Off",
                    onClick = if (signedIn) null else onSignIn,
                )
                AccountActionRow(
                    icon = Icons.Default.Extension,
                    title = "StreamCloud integrations",
                    subtitle = "Manage connected services",
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                )
                AccountActionRow(
                    icon = Icons.Default.Settings,
                    title = "StreamCloud settings",
                    subtitle = "Playback, appearance, downloads and more",
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Your YouTube Music session stays on this device. StreamCloud never displays your account cookie or token.",
                    style = MaterialTheme.typography.labelSmall,
                    color = StreamCloudMuted,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    trailing: String? = null,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(StreamCloudCard)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF19312E)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Teal,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = StreamCloudOnSheet,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = StreamCloudMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Teal,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun AccountAvatar(
    avatarUrl: String,
    size: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current
    if (avatarUrl.isNotBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(avatarUrl)
                .crossfade(true)
                .allowHardware(false)
                .build(),
            contentDescription = "YouTube Music profile photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFF19312E)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "YouTube Music profile",
                tint = Teal,
                modifier = Modifier.size(size * 0.72f),
            )
        }
    }
}