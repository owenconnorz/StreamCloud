package com.streamcloud.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.streamcloud.app.data.recognition.MusicRecognitionState
import com.streamcloud.app.data.recognition.RecognitionResult
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicRecognitionSheet(
    state: MusicRecognitionState,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onSearch: (RecognitionResult) -> Unit,
    onDismiss: () -> Unit,
) {
    if (LocalUiFormFactor.current == UiFormFactor.Mobile) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF7446B6), Color(0xFFD83D79)),
                        ),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = "Close audio search",
                                tint = Color.White,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(Color.White.copy(alpha = 0.42f)),
                        )
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.size(48.dp))
                    }
                    when (state) {
                        MusicRecognitionState.Ready -> Text(
                            "Play, sing or hum a song",
                            color = Color.White,
                            fontSize = 28.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        MusicRecognitionState.Listening -> FullScreenRecognitionStatus(
                            "Listening…",
                            "Hold your phone near the music.",
                        )
                        MusicRecognitionState.Processing -> FullScreenRecognitionStatus(
                            "Identifying…",
                            "Checking the short audio fingerprint.",
                        )
                        MusicRecognitionState.PermissionDenied -> FullScreenRecognitionStatus(
                            "Microphone access is needed",
                            "Allow microphone access to identify music nearby.",
                        )
                        is MusicRecognitionState.NoMatch -> FullScreenRecognitionStatus(
                            "No match found",
                            state.message,
                        )
                        is MusicRecognitionState.Error -> FullScreenRecognitionStatus(
                            "Recognition failed",
                            state.message,
                        )
                        is MusicRecognitionState.Success -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AsyncImage(
                                model = state.result.artworkHqUrl ?: state.result.artworkUrl,
                                contentDescription = state.result.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(160.dp).clip(MaterialTheme.shapes.large),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                state.result.title,
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                state.result.artist,
                                color = Color.White.copy(alpha = 0.82f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        when (state) {
                            is MusicRecognitionState.Success -> Button(
                                onClick = { onSearch(state.result) },
                                modifier = Modifier.size(92.dp).clip(CircleShape),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFFD83D79),
                                ),
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Search for this song")
                            }
                            MusicRecognitionState.Listening,
                            MusicRecognitionState.Processing -> Button(
                                onClick = onDismiss,
                                modifier = Modifier.size(92.dp).clip(CircleShape),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFFD83D79),
                                ),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Stop listening")
                            }
                            else -> Button(
                                onClick = onStart,
                                modifier = Modifier.size(92.dp).clip(CircleShape),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFFD83D79),
                                ),
                            ) {
                                WaveformMark()
                            }
                        }
                        if (state == MusicRecognitionState.Ready) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "A short audio fingerprint, time and time zone are sent to Shazam. The recording isn’t saved.",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    } else {
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
}

@Composable
private fun FullScreenRecognitionStatus(title: String, message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            color = Color.White,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            message,
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WaveformMark() {
    Row(
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = "Start song recognition" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(8.dp, 17.dp, 23.dp, 14.dp, 7.dp).forEach { barHeight ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(CircleShape)
                    .background(Color(0xFFD83D79)),
            )
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