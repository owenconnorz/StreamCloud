package com.aioweb.app.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight global playback observable. Exposes the currently playing media
 * id (e.g. `https://music.youtube.com/watch?v=VIDEO_ID`) and the player's
 * `isPlaying` state as StateFlows so any composable can react without holding
 * a reference to the [MusicController] directly.
 *
 * This is what powers the "currently playing" highlight + animated equalizer
 * bars in playlist / library / search rows — Metrolist parity.
 */
@UnstableApi
object PlaybackBus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _nowPlayingMediaId = MutableStateFlow<String?>(null)
    val nowPlayingMediaId: StateFlow<String?> = _nowPlayingMediaId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile private var listenerInstalled = false

    /**
     * Wires a single [Player.Listener] onto the global [MusicController]. Idempotent
     * — safe to call from multiple call sites; only the first invocation actually
     * subscribes. Subsequent calls become a cheap suspend no-op.
     */
    suspend fun ensureAttached(context: Context) {
        if (listenerInstalled) return
        val controller = withContext(Dispatchers.IO) {
            MusicController.get(context.applicationContext)
        }
        if (listenerInstalled) return // double-check after suspending
        listenerInstalled = true

        // Seed from current state in case a track was already playing.
        _nowPlayingMediaId.value = controller.currentMediaItem?.mediaId
        _isPlaying.value = controller.isPlaying

        controller.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _nowPlayingMediaId.value = mediaItem?.mediaId
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }
        })
    }

    /** Fire-and-forget convenience for non-suspend callers. */
    fun attach(context: Context) {
        scope.launch { runCatching { ensureAttached(context) } }
    }
}
