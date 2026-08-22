package com.streamcloud.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.recognition.MusicRecognitionState
import com.streamcloud.app.data.recognition.RecognitionResult

@Composable
fun MusicRecognitionSheet(
    state: MusicRecognitionState,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onSearch: (RecognitionResult) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Recognize music",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(12.dp))
            when (state) {
                MusicRecognitionState.Ready -> ReadyContent(onStart)
                MusicRecognitionState.Listening -> ProgressContent(
                    title = "Listening…",
                    message = "Hold your phone near the music.",
                    allowCancel = true,
                    onCancel = onDismiss,
                )
                MusicRecognitionState.Processing -> ProgressContent(
                    title = "Identifying…",
                    message = "Checking the short audio fingerprint.",
                    allowCancel = true,
                    onCancel = onDismiss,
                )
                MusicRecognitionState.PermissionDenied -> MessageContent(
                    icon = Icons.Default.Mic,
                    title = "Microphone access is needed",
                    message = "Allow microphone access to identify music nearby. StreamCloud only captures one short sample and does not save it.",
                    actionLabel = "Try again",
                    onAction = onStart,
                )
                is MusicRecognitionState.NoMatch -> MessageContent(
                    icon = Icons.Default.ErrorOutline,
                    title = "No match found",
                    message = state.message,
                    actionLabel = "Listen again",
                    onAction = onRetry,
                )
                is MusicRecognitionState.Error -> MessageContent(
                    icon = Icons.Default.ErrorOutline,
                    title = "Recognition failed",
                    message = state.message,
                    actionLabel = "Try again",
                    onAction = onRetry,
                )
                is MusicRecognitionState.Success -> SuccessContent(
                    result = state.result,
                    onSearch = { onSearch(state.result) },
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(onStart: () -> Unit) {
    Icon(
        imageVector = Icons.Default.Mic,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "What’s playing?",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Listen for a few seconds to identify music around you.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Mic, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Start listening")
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "A short audio fingerprint, the time, and your time zone are sent to Shazam’s recognition service. The recording is not saved.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ProgressContent(
    title: String,
    message: String,
    allowCancel: Boolean,
    onCancel: () -> Unit,
) {
    CircularProgressIndicator(modifier = Modifier.size(56.dp))
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(
        message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (allowCancel) {
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

@Composable
private fun MessageContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp),
    )
    Spacer(Modifier.height(14.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(
        message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Replay, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(actionLabel)
    }
}

@Composable
private fun SuccessContent(
    result: RecognitionResult,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
) {
    AsyncImage(
        model = result.artworkHqUrl ?: result.artworkUrl,
        contentDescription = result.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(180.dp)
            .clip(MaterialTheme.shapes.large),
    )
    Spacer(Modifier.height(16.dp))
    Icon(
        Icons.Default.CheckCircle,
        contentDescription = null,
        tint = Color(0xFF4CAF50),
        modifier = Modifier.size(24.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        result.title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Text(
        result.artist,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    result.album?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(18.dp))
    Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Search, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Search in StreamCloud")
    }
    TextButton(onClick = onRetry) {
        Text("Listen again")
    }
}