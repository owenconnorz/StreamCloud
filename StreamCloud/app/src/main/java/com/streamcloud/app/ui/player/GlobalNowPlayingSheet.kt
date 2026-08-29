package com.streamcloud.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.audio.PlaybackBus
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@OptIn(UnstableApi::class)
@Composable
fun GlobalNowPlayingSheet(
    playerSurfaceState: PlayerSurfaceState,
    onOpenSettings: () -> Unit = {},
    onOpenArtistSearch: (String) -> Unit = {},
) {
    val context = LocalContext.current

    val playingId by PlaybackBus.nowPlayingMediaId.collectAsState()
    val latestPlayingId by rememberUpdatedState(playingId)
    var controller by remember { mutableStateOf<Player?>(null) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var connectionAttempt by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        PlayerExpandBus.events.collect {
            if (latestPlayingId != null) {
                playerSurfaceState.expand()
            }
        }
    }

    LaunchedEffect(playingId) {
        if (playingId == null) playerSurfaceState.collapse()
    }

    LaunchedEffect(playingId != null, connectionAttempt) {
        controller = null
        connectionError = null
        if (playingId == null) return@LaunchedEffect
        controller = runCatching {
            withTimeout(8_000L) {
                MusicController.get(context.applicationContext)
            }
        }.getOrElse { error ->
            connectionError = error.message
                ?: "The media controls did not become ready. Try again."
            null
        }
    }

    val progress = playerSurfaceState.progress
    if (progress <= 0f) return
    BackHandler(onBack = playerSurfaceState::collapse)

    val npScrollState = rememberScrollState()
    var surfaceHeightPx by remember { mutableStateOf(0) }
    val settleSurface: (Float) -> Unit = { velocityY ->
        when (settlePlayerSurfaceProgress(playerSurfaceState.progress, velocityY)) {
            MiniPlayerVerticalAction.Expand -> playerSurfaceState.expand()
            MiniPlayerVerticalAction.SnapBack -> playerSurfaceState.collapse()
        }
    }
    val collapseAtTopConnection = remember(npScrollState, surfaceHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (
                    source != NestedScrollSource.UserInput ||
                    npScrollState.value != 0 ||
                    surfaceHeightPx == 0
                ) {
                    return Offset.Zero
                }
                val shouldMoveSurface =
                    available.y > 0f ||
                        (available.y < 0f && playerSurfaceState.progress < 1f)
                return if (shouldMoveSurface) {
                    playerSurfaceState.dragByPixels(available.y, surfaceHeightPx)
                    Offset(0f, available.y)
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (
                    source == NestedScrollSource.UserInput &&
                    available.y > 0f &&
                    npScrollState.value == 0 &&
                    surfaceHeightPx > 0
                ) {
                    playerSurfaceState.dragByPixels(available.y, surfaceHeightPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val surfaceIsBetweenAnchors = playerSurfaceState.progress < 1f
                if (
                    npScrollState.value == 0 &&
                    (available.y > 0f || surfaceIsBetweenAnchors)
                ) {
                    settleSurface(available.y / 1_000f)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (npScrollState.value == 0 && playerSurfaceState.progress < 1f) {
                    settleSurface(available.y / 1_000f)
                }
                return Velocity.Zero
            }
        }
    }
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f * progress))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = playerSurfaceState::collapse,
                ),
        )
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { surfaceHeightPx = it.height }
                .graphicsLayer {
                    translationY = size.height * (1f - progress)
                }
                .nestedScroll(collapseAtTopConnection)
                .background(Color(0xFF0E0E0E)),
        ) {
            val connectedController = controller
            if (connectedController != null) {
                GlobalNowPlayingContent(
                    controller = connectedController,
                    npScrollState = npScrollState,
                    onClose = playerSurfaceState::collapse,
                    onOpenSettings = onOpenSettings,
                    onOpenArtistSearch = onOpenArtistSearch,
                )
            } else {
                NowPlayingConnectionState(
                    error = connectionError,
                    onRetry = { connectionAttempt++ },
                )
            }
        }
    }
}

@Composable
private fun NowPlayingConnectionState(
    error: String?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (error == null) {
            CircularProgressIndicator(modifier = Modifier.size(42.dp))
            Text(
                "Connecting player controls…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 18.dp),
            )
        } else {
            Text(
                "Player controls couldn't connect",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) {
                Text("Retry player")
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun GlobalNowPlayingContent(
    controller: Player,
    npScrollState: ScrollState,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArtistSearch: (String) -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        com.streamcloud.app.ui.screens.NowPlayingShell(
            controller = controller,
            npScrollState = npScrollState,
            onClose = onClose,
            onOpenSettings = onOpenSettings,
            onOpenArtistSearch = onOpenArtistSearch,
        )
    }
}
