package com.streamcloud.app.audio

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import com.streamcloud.app.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class AudioFx(
    private val context: Context,
    initialAudioSessionId: Int,
) {
    private var audioSessionId = 0
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var eqEnabled = false
    private var bassBoostEnabled = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watcher: Job? = null

    fun start() {
        val sl = ServiceLocator.get(context)
        watcher = scope.launch {
            combine(
                sl.settings.eqEnabled,
                sl.settings.bassBoost,
            ) { enabled, boost -> enabled to boost }
                .distinctUntilChanged()
                .collect { (enabled, boost) ->
                    eqEnabled = enabled
                    bassBoostEnabled = boost
                    applyEq(enabled)
                    applyBass(boost)
                }
        }
        updateAudioSessionId(initialAudioSessionId)
    }

    /** Rebind effects and device-control broadcasts when Media3 assigns or changes its session. */
    fun updateAudioSessionId(newSessionId: Int) {
        if (newSessionId < 0 || newSessionId == audioSessionId) return

        val previousSessionId = audioSessionId
        if (previousSessionId > 0) {
            closeControlSession(context, previousSessionId)
            runCatching { eq?.release() }; eq = null
            runCatching { bass?.release() }; bass = null
        }

        audioSessionId = newSessionId
        if (newSessionId <= 0) {
            if (activeSessionId == previousSessionId) activeSessionId = 0
            return
        }

        activeSessionId = newSessionId
        runCatching { eq = Equalizer(0, newSessionId).apply { enabled = false } }
        runCatching { bass = BassBoost(0, newSessionId).apply { enabled = false } }
        applyEq(eqEnabled)
        applyBass(bassBoostEnabled)
    }

    /** Keep the session effect enabled for the device panel to control, not for app-side band edits. */
    private fun applyEq(enabled: Boolean) {
        runCatching { eq?.enabled = enabled }
        if (audioSessionId <= 0) return
        if (enabled) openControlSession(context, audioSessionId)
        else closeControlSession(context, audioSessionId)
    }

    private fun applyBass(boost: Boolean) {
        val b = bass ?: return
        runCatching {
            b.enabled = boost
            if (boost) b.setStrength(800)
        }
    }

    fun release() {
        watcher?.cancel(); watcher = null
        closeControlSession(context)
        runCatching { eq?.release() }; eq = null
        runCatching { bass?.release() }; bass = null
        if (activeSessionId == audioSessionId) activeSessionId = 0
        audioSessionId = 0
    }

    companion object {
        private val controlSessionLock = Any()
        @Volatile private var activeSessionId: Int = 0
        @Volatile private var controlSessionId: Int = 0

        fun activeAudioSessionId(): Int? = activeSessionId.takeIf { it > 0 }

        internal fun openControlSession(context: Context, sessionId: Int) {
            if (sessionId <= 0) return
            synchronized(controlSessionLock) {
                if (controlSessionId != sessionId) {
                    if (controlSessionId > 0) {
                        sendControlSessionBroadcast(
                            context, AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, controlSessionId,
                        )
                    }
                    sendControlSessionBroadcast(
                        context, AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, sessionId,
                    )
                    controlSessionId = sessionId
                }
            }
        }

        internal fun closeControlSession(context: Context, sessionId: Int? = null) {
            synchronized(controlSessionLock) {
                val openSession = controlSessionId
                if (openSession <= 0 || (sessionId != null && openSession != sessionId)) return@synchronized
                sendControlSessionBroadcast(
                    context, AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, openSession,
                )
                controlSessionId = 0
            }
        }

        private fun sendControlSessionBroadcast(context: Context, action: String, sessionId: Int) {
            context.sendBroadcast(Intent(action).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            })
        }
    }
}
