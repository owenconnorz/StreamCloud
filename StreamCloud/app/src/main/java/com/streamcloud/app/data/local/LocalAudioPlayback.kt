package com.streamcloud.app.data.local

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.ui.player.PlayerExpandBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
object LocalAudioPlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun play(context: Context, item: LocalAudioItem) {
        scope.launch {
            withContext(Dispatchers.Main) {
                val controller = MusicController.get(context.applicationContext)
                controller.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(item.uri.toString())
                        .setUri(item.uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(item.title)
                                .setArtist(item.artist)
                                .setAlbumTitle(item.album)
                                .setArtworkUri(item.artworkUri)
                                .setExtras(Bundle().apply {
                                    putBoolean("localMedia", true)
                                })
                                .build(),
                        )
                        .build(),
                )
                controller.prepare()
                controller.play()
                delay(150)
                PlayerExpandBus.requestExpand()
            }
        }
    }
}
