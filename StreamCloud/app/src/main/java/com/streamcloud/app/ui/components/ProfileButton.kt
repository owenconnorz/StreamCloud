package com.streamcloud.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streamcloud.app.data.ServiceLocator

@Composable
fun ProfileButton(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val sl = remember(context) { ServiceLocator.get(context) }
    val avatar by sl.settings.ytMusicUserAvatar.collectAsState(initial = "")

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatar)
                    .crossfade(true)
                    .allowHardware(false)
                    .build(),
                contentDescription = "Profile",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = "Profile",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.75f),
            )
        }
    }
}
