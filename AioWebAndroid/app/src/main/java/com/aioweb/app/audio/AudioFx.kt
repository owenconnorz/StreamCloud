package com.aioweb.app.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import com.aioweb.app.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Holds an Equalizer + BassBoost effect bound to the service's ExoPlayer audio session.
 *
 * Presets (Metrolist parity): flat / pop / rock / jazz / bass / vocal.
 * Reactively listens to settings and re-applies on each change.
 */
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
        runCatching { eq = Equalizer(0, audioSessionId).apply { enabled = false } }
        runCatching { bass = BassBoost(0, audioSessionId).apply { enabled = false } }

        val sl = ServiceLocator.get(context)
        watcher = scope.launch {
            combine(
                sl.settings.eqEnabled,
                sl.settings.eqPreset,
                sl.settings.bassBoost,
            ) { enabled, preset, boost ->
                Triple(enabled, preset, boost)
            }.collect { (enabled, preset, boost) ->
                applyEq(enabled, preset)
                applyBass(boost)
            }
        }
    }

    private fun applyEq(enabled: Boolean, preset: String) {
        val e = eq ?: return
        runCatching {
            e.enabled = enabled
            if (!enabled) return@runCatching
            val gains = PRESETS[preset] ?: PRESETS["flat"]!!
            val bands = e.numberOfBands.toInt()
            val (minLevel, maxLevel) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            for (i in 0 until bands) {
                // Map preset's 5-band gains (in mB) to the device's actual band count.
                val frac = if (bands <= 1) 0f else i.toFloat() / (bands - 1)
                val srcIdx = (frac * (gains.size - 1)).toInt().coerceIn(0, gains.size - 1)
                val target = gains[srcIdx].coerceIn(minLevel, maxLevel)
                e.setBandLevel(i.toShort(), target.toShort())
            }
        }
    }

    private fun applyBass(boost: Boolean) {
        val b = bass ?: return
        runCatching {
            b.enabled = boost
            if (boost) b.setStrength(800)  // 0..1000 — moderate punch
        }
    }

    fun release() {
        watcher?.cancel(); watcher = null
        runCatching { eq?.release() }; eq = null
        runCatching { bass?.release() }; bass = null
    }

    companion object {
        // 5-band gains in mB (millibel). Roughly: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz.
        private val PRESETS: Map<String, IntArray> = mapOf(
            "flat"  to intArrayOf(   0,    0,    0,    0,    0),
            "pop"   to intArrayOf(-100,  200,  400,  100, -100),
            "rock"  to intArrayOf( 500,  300,  -50,  150,  300),
            "jazz"  to intArrayOf( 300,  150,    0,  100,  200),
            "bass"  to intArrayOf( 700,  500,  100, -100, -200),
            "vocal" to intArrayOf(-200,    0,  400,  200,  100),
        )
    }
}
