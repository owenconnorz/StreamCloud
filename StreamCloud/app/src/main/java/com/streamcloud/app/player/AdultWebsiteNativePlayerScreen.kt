package com.streamcloud.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
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
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.AdultWebsitePlayback
import com.streamcloud.app.data.api.AdultWebsiteRepository

@Composable
fun AdultWebsitePlayerScreen(
    sourceKey: String,
    pageUrl: String,
    title: String,
    onBack: () -> Unit,
    onOpenOfficialPage: () -> Unit,
) {
    val context = LocalContext.current
    val userAgent = remember(context) {
        AdultWebsiteRepository.browserUserAgent(context)
    }
    val source = remember(sourceKey) {
        when (sourceKey.lowercase()) {
            "porn4days" -> AdultSource.Porn4Days
            "pornhd3x" -> AdultSource.PornHd3x
            "pornslash" -> AdultSource.PornSlash
            else -> null
        }
    }
    var playback by remember(sourceKey, pageUrl) { mutableStateOf<AdultWebsitePlayback?>(null) }
    var error by remember(sourceKey, pageUrl) { mutableStateOf<String?>(null) }
    var attempt by remember(sourceKey, pageUrl) { mutableIntStateOf(0) }
    var openedProviderPlayer by remember(sourceKey, pageUrl) { mutableStateOf(false) }

    LaunchedEffect(source, pageUrl, userAgent, attempt) {
        playback = null
        error = null
        if (source == null) {
            error = "This provider is not supported."
        } else {
            runCatching {
                AdultWebsiteRepository.resolvePlayback(
                    source = source,
                    pageUrl = pageUrl,
                    userAgent = userAgent,
                )
            }.onSuccess {
                playback = it
            }.onFailure {
                error = it.message?.takeIf(String::isNotBlank)
                    ?: "${source.label} could not prepare native playback."
                if (source == AdultSource.PornHd3x && !openedProviderPlayer) {
                    openedProviderPlayer = true
                    onOpenOfficialPage()
                }
            }
        }
    }

    val resolvedPlayback = playback
    if (resolvedPlayback != null) {
        NativePlayerScreen(
            streamUrl = resolvedPlayback.url,
            title = title,
            headers = resolvedPlayback.headers,
            onBack = onBack,
            forceDirectPlay = true,
            forceHls = resolvedPlayback.isHls,
            onRefresh = { attempt++ },
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
                    text = error.orEmpty(),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { attempt++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("Retry", modifier = Modifier.padding(start = 8.dp))
                    }
                    if (source != AdultSource.PornSlash) {
                        Button(onClick = onOpenOfficialPage) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null)
                            Text("Use provider player", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
