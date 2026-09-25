package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.data.YtMusicAccountInfo
import com.streamcloud.app.ui.theme.AlbumArtThemeBus

private val StreamCloudSheet = Color(0xFF0B1514)
private val StreamCloudCard = Color(0xFF101F1D)
private val StreamCloudOnSheet = Color(0xFFE8F2F0)
private val StreamCloudMuted = Color(0xFF9CB2AE)

@OptIn(UnstableApi::class)
@Composable
fun YtMusicAccountSheet(
    userName: String,
    avatarUrl: String,
    signedIn: Boolean,
    accounts: List<YtMusicAccountInfo>,
    activeAccountId: String?,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onSignOut: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val albumArtAccent by AlbumArtThemeBus.accent.collectAsState()
    val albumArtNavBackground by AlbumArtThemeBus.navPillBg.collectAsState()
    val hasAlbumArt by AlbumArtThemeBus.hasArtwork.collectAsState()
    val accent = if (hasAlbumArt) albumArtAccent else MaterialTheme.colorScheme.primary
    val sheetColor = if (hasAlbumArt) {
        lerp(StreamCloudSheet, albumArtNavBackground, 0.70f)
    } else {
        StreamCloudSheet
    }
    val cardColor = if (hasAlbumArt) {
        lerp(StreamCloudCard, albumArtNavBackground, 0.82f)
    } else {
        StreamCloudCard
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(620.dp)
                .clip(RoundedCornerShape(30.dp)),
            color = sheetColor,
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
                    color = cardColor,
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
                            accent = accent,
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
                                    containerColor = accent,
                                    contentColor = if (accent.luminance() > 0.45f) {
                                        Color.Black
                                    } else {
                                        Color.White
                                    },
                                ),
                            ) {
                                Text("Sign in", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (accounts.size > 1) {
                    Text(
                        text = "Your YouTube Music accounts",
                        style = MaterialTheme.typography.labelLarge,
                        color = StreamCloudMuted,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    accounts
                        .filterNot { it.id == activeAccountId }
                        .forEach { account ->
                            AccountChoiceRow(
                                account = account,
                                cardColor = cardColor,
                                accent = accent,
                                onClick = { onSelectAccount(account.id) },
                            )
                        }
                }

                if (signedIn) {
                    AccountActionRow(
                        icon = Icons.Default.SwapHoriz,
                        title = "Add YouTube account",
                        subtitle = "Sign in and keep this account available",
                        cardColor = cardColor,
                        accent = accent,
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
                    cardColor = cardColor,
                    accent = accent,
                    onClick = if (signedIn) null else onSignIn,
                )
                AccountActionRow(
                    icon = Icons.Default.Extension,
                    title = "Integrations",
                    subtitle = "Manage connected services",
                    cardColor = cardColor,
                    accent = accent,
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                )
                AccountActionRow(
                    icon = Icons.Default.Settings,
                    title = "Settings",
                    subtitle = "Playback, appearance, downloads and more",
                    cardColor = cardColor,
                    accent = accent,
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
private fun AccountChoiceRow(
    account: YtMusicAccountInfo,
    cardColor: Color,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(
            avatarUrl = account.avatar,
            size = 42.dp,
            accent = accent,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = account.name.ifBlank { "YouTube Music account" },
            style = MaterialTheme.typography.titleSmall,
            color = StreamCloudOnSheet,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Switch",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun AccountActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    cardColor: Color,
    accent: Color,
    trailing: String? = null,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardColor)
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
                .background(lerp(cardColor, accent, 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
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
                color = accent,
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
    accent: Color,
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
                .background(lerp(StreamCloudCard, accent, 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "YouTube Music profile",
                tint = accent,
                modifier = Modifier.size(size * 0.72f),
            )
        }
    }
}