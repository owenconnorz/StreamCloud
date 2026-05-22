package com.streamcloud.app.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives hardware media-button broadcasts (steering wheel, Bluetooth headset,
 * wired headphones) and forwards them to [MusicPlaybackService].
 *
 * Media3's [androidx.media3.session.MediaSessionService.onStartCommand] already
 * knows how to route ACTION_MEDIA_BUTTON intents to the active MediaSession, so
 * all we need to do here is start the service with the original intent.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val svc = Intent(intent).setClass(context, MusicPlaybackService::class.java)
        context.startForegroundService(svc)
    }
}
