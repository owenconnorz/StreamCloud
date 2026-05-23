package com.streamcloud.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.audio.PlaybackBus
import com.streamcloud.app.data.sonos.SonosRepository
import kotlinx.coroutines.launch

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

    // Observe cast state so the key interceptor inside the sheet has it available.
    val castState by SonosRepository.castState.collectAsState()
    val isCasting = castState is SonosRepository.CastState.Casting

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

    // ModalBottomSheet creates its own Dialog Window, so the Activity's
    // dispatchKeyEvent is never called while the sheet is open (the Dialog
    // Window has focus, not the Activity Window). We intercept hardware volume
    // keys here using onPreviewKeyEvent on a focused composable so that pressing
    // Volume Up / Down adjusts Sonos volume instead of the phone's system volume.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // onPreviewKeyEvent fires before children handle the event.
                    // Returning true consumes it — the Dialog won't pass it to
                    // the system volume handler and the phone volume stays unchanged.
                    if (isCasting && event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.VolumeUp   -> { SonosRepository.adjustVolume(5);  true }
                            Key.VolumeDown -> { SonosRepository.adjustVolume(-5); true }
                            else -> false
                        }
                    } else false
                },
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
}

@OptIn(UnstableApi::class)
@Composable
private fun GlobalNowPlayingContent(
    controller: Player,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArtistSearch: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        com.streamcloud.app.ui.screens.NowPlayingShell(
            controller = controller,
            onClose = onClose,
            onOpenSettings = onOpenSettings,
            onOpenArtistSearch = onOpenArtistSearch,
        )
    }
}
