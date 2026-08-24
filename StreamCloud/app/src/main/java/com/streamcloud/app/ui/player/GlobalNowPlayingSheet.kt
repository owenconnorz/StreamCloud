package com.streamcloud.app.ui.player

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.audio.PlaybackBus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun GlobalNowPlayingSheet(
    onOpenSettings: () -> Unit = {},
    onOpenArtistSearch: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val playingId by PlaybackBus.nowPlayingMediaId.collectAsState()

    LaunchedEffect(Unit) {
        PlayerExpandBus.events.collect {
            if (playingId != null) {
                open = true
            }
        }
    }

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

    // Hoisted scroll state — shared between the sheet (for dismiss guard) and NowPlayingShell
    val npScrollState = rememberScrollState()

    // Track whether the inner content is scrolled away from the top
    var innerScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(npScrollState) {
        snapshotFlow { npScrollState.value }.collect { innerScrolled = it > 0 }
    }
    // Always use the latest value inside confirmValueChange
    val innerScrolledState = rememberUpdatedState(innerScrolled)

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // Prevent the sheet from dismissing via swipe while the content is scrolled down.
        // The user must scroll back to the top first, then swipe down to close.
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden) !innerScrolledState.value else true
        },
    )
    // Keep the sheet composed until its native hide animation completes. This
    // lets a swipe-down visibly settle back to the mini-player rather than
    // removing the full player in the same frame.
    val minimizePlayer: () -> Unit = {
        scope.launch {
            sheetState.hide()
            open = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = minimizePlayer,
        sheetState = sheetState,
        containerColor = Color(0xFF0E0E0E),
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.fillMaxSize(),
    ) {
        GlobalNowPlayingContent(
            controller = c,
            npScrollState = npScrollState,
            onClose = minimizePlayer,
            onOpenSettings = onOpenSettings,
            onOpenArtistSearch = onOpenArtistSearch,
        )
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
