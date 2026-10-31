package com.streamcloud.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streamcloud.app.data.api.XnxxResolvedPlayback
import com.streamcloud.app.data.api.XnxxRepository
import com.streamcloud.app.ui.icons.AppIcons as Icons

@Composable
fun XnxxPlayerScreen(
    pageUrl: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val userAgent = remember(context) { XnxxRepository.browserUserAgent(context) }
    var playback by remember(pageUrl) { mutableStateOf<XnxxResolvedPlayback?>(null) }
    var error by remember(pageUrl) { mutableStateOf<String?>(null) }
    var attempt by remember(pageUrl) { mutableIntStateOf(0) }

    LaunchedEffect(pageUrl, attempt, userAgent) {
        playback = null
        error = null
        runCatching { XnxxRepository.resolvePlayback(pageUrl, userAgent) }
            .onSuccess { playback = it }
            .onFailure {
                error = it.message?.takeIf(String::isNotBlank)
                    ?: "XNXX could not prepare a native video stream."
            }
    }

    val resolvedPlayback = playback
    if (resolvedPlayback != null) {
        NativePlayerScreen(
            streamUrl = resolvedPlayback.url,
            title = title,
            headers = resolvedPlayback.headers,
            onBack = onBack,
        )
        return
    }

    BackHandler(onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (error == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text("Preparing native playback…", color = Color.White)
            } else {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    error.orEmpty(),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { attempt++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Retry", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
