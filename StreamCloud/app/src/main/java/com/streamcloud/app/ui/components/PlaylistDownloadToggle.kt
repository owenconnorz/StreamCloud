package com.streamcloud.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PlaylistDownloadToggle(
    checked: Boolean,
    busy: Boolean,
    enabled: Boolean = true,
    detailText: String? = null,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    val downloadTint = if (checked) MaterialTheme.colorScheme.primary else Color.White
    IconButton(
        onClick = { onCheckedChange(!checked) },
        enabled = enabled && !busy,
        modifier = modifier
            .size(48.dp)
            .border(1.5.dp, downloadTint, CircleShape),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = downloadTint,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Default.ArrowDownward,
                contentDescription = detailText ?: if (checked) "Turn off playlist downloads" else "Download playlist for offline listening",
                tint = downloadTint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
