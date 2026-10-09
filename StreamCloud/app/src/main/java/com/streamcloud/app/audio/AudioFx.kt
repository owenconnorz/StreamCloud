package com.streamcloud.app.audio

import android.content.Context
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
    private val audioSessionId: Int,
) {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watcher: Job? = null

    fun start() {
        if (audioSessionId == 0) return
        activeSessionId = audioSessionId
        runCatching { eq = Equalizer(0, audioSessionId).apply { enabled = false } }
        runCatching { bass = BassBoost(0, audioSessionId).apply { enabled = false } }

        val sl = ServiceLocator.get(context)
        watcher = scope.launch {
            combine(
                sl.settings.eqEnabled,
                sl.settings.bassBoost,
            ) { enabled, boost -> enabled to boost }
                .distinctUntilChanged()
                .collect { (enabled, boost) ->
                    applyEq(enabled)
                    applyBass(boost)
                }
        }
    }

    /** Keep the Android audio effect attached to this session, but leave its bands to the device panel. */
    private fun applyEq(enabled: Boolean) {
        val effect = eq ?: return
        runCatching { effect.enabled = enabled }
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
        runCatching { eq?.release() }; eq = null
        runCatching { bass?.release() }; bass = null
        if (activeSessionId == audioSessionId) activeSessionId = 0
    }

    companion object {
        @Volatile private var activeSessionId: Int = 0

        fun activeAudioSessionId(): Int? = activeSessionId.takeIf { it > 0 }
    }
}
