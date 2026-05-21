package com.streamcloud.app.audio

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

@UnstableApi
object PlaybackBus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _nowPlayingMediaId = MutableStateFlow<String?>(null)
    val nowPlayingMediaId: StateFlow<String?> = _nowPlayingMediaId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile private var listenerInstalled = false


    suspend fun ensureAttached(context: Context) {
        if (listenerInstalled) return
        val controller = withContext(Dispatchers.IO) {
            MusicController.get(context.applicationContext)
        }
        if (listenerInstalled) return
        listenerInstalled = true


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


    fun attach(context: Context) {
        scope.launch { runCatching { ensureAttached(context) } }
    }
}
