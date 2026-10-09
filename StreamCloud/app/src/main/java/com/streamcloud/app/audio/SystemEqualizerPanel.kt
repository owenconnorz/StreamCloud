package com.streamcloud.app.audio

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/** Opens the device/OEM equalizer for the active Media3 audio session. */
object SystemEqualizerPanel {
    fun open(context: Context, audioSessionId: Int?): Boolean {
        val sessionId = audioSessionId?.takeIf { it > 0 } ?: return false
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        }
        val started = runCatching {
            AudioFx.openControlSession(context, sessionId)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!started) AudioFx.closeControlSession(context, sessionId)
        return started
    }
}
