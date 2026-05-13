package com.aioweb.app.ui.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.aioweb.app.audio.MusicController
import com.aioweb.app.audio.PlaybackBus
import kotlinx.coroutines.launch

/**
 * App-wide Now-Playing bottom sheet. Lives at the very root of [AioWebApp]
 * so swipe-up on the [GlobalMiniPlayer] can open it from any tab — Movies,
 * Library, AI, Settings, you name it.
 *
 * Why not reuse the rich `NowPlayingSheet` inside MusicScreen? That version is
 * pinned to MusicViewModel state (likes, sleep timer, lyrics) and only renders
 * when the Music tab is on screen, which is why swipe-up from Library / Movies
 * never showed the full player. This shell is a thin wrapper around the
 * *exact same* `NowPlayingSheet` composable but feeds it the global
 * [MusicController] state directly, so no view-model wiring is needed.
 *
 * Listens for [PlayerExpandBus] events to know when to open. Swipe-down or
 * tap-outside dismisses via Material3's built-in `onDismissRequest`.
 */
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun GlobalNowPlayingSheet(
    onOpenSettings: () -> Unit = {},
    onOpenArtistSearch: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val playingId by PlaybackBus.nowPlayingMediaId.collectAsState()

    // Open when the mini player asks; only if a track is actually loaded.
    LaunchedEffect(Unit) {
        PlayerExpandBus.events.collect {
            if (playingId != null) {
                open = true
            }
        }
    }
    // If playback stops entirely, close the sheet so we don't render an empty shell.
    LaunchedEffect(playingId) {
        if (playingId == null) open = false
    }

    if (!open) return

    var controller by remember { mutableStateOf<Player?>(null) }
    LaunchedEffect(Unit) {
        controller = runCatching {
            MusicController.get(context.applicationContext)
        }.getOrNull()
    }
    val c = controller ?: return

    ModalBottomSheet(
        onDismissRequest = {
            scope.launch { sheetState.hide() }
            open = false
        },
        sheetState = sheetState,
        containerColor = Color(0xFF0E0E0E),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = null,
        modifier = Modifier.fillMaxSize(),
    ) {
        GlobalNowPlayingContent(
            controller = c,
            onClose = {
                scope.launch { sheetState.hide() }
                open = false
            },
            onOpenSettings = onOpenSettings,
            onOpenArtistSearch = onOpenArtistSearch,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun GlobalNowPlayingContent(
    controller: Player,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArtistSearch: (String) -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Reuse the existing rich UI by importing the inner content. We render
        // a slim, controller-driven version: artwork + title + transport.
        com.aioweb.app.ui.screens.NowPlayingShell(
            controller = controller,
            onClose = onClose,
            onOpenSettings = onOpenSettings,
            onOpenArtistSearch = onOpenArtistSearch,
        )
    }
}
