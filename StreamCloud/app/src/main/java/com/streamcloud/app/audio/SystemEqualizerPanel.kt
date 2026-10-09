package com.streamcloud.app.audio

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/** Opens the device/OEM equalizer for a Media3 audio session when the device provides a panel. */
object SystemEqualizerPanel {
    fun open(context: Context, audioSessionId: Int?): Boolean {
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            audioSessionId?.takeIf { it > 0 }?.let { sessionId ->
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            }
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
