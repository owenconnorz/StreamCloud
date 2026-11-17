package com.streamcloud.app.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

@Composable
internal fun WearVideoPlayerScreen(
    title: String,
    streamUrl: String,
    headers: Map<String, String>,
    mimeType: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val safeHeaders = remember(headers) { headers.filterKeys(::isSafeWatchPlaybackHeader) }
    val player = remember(streamUrl, safeHeaders) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(safeHeaders)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMimeType(mimeType.takeIf(String::isNotBlank))
                        .build(),
                )
                prepare()
                playWhenReady = true
            }
    }

    BackHandler(onBack = onBack)
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                }
            },
            update = { it.player = player },
        )
        CompactButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 24.dp, start = 12.dp),
        ) {
            Text("‹", style = MaterialTheme.typography.title3)
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.caption1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 34.dp, start = 56.dp, end = 18.dp),
        )
    }
}

private fun isSafeWatchPlaybackHeader(name: String): Boolean =
    name.equals("User-Agent", true) ||
        name.equals("Referer", true) ||
        name.equals("Origin", true) ||
        name.equals("Accept", true) ||
        name.equals("Accept-Language", true)
