package com.streamcloud.app.data.sonos

import android.content.Context
import android.util.Log
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application-level orchestrator for Sonos device discovery and cast control.
 *
 * Cast lifecycle:
 *   1. Call [startDiscovery] — populates [devices] within a few seconds.
 *   2. Call [connect] with the chosen device + current track info —
 *      starts the proxy server, resolves + sends the stream URL to Sonos.
 *      The local phone player is automatically paused so only Sonos plays.
 *   3. Call [pause] / [resume] / [disconnect] to control or end the session.
 *      [disconnect] resumes phone playback.
 *
 * Volume:
 *   [sonosVolume] reflects the current Sonos speaker volume (0–100).
 *   [adjustVolume] / [setVolume] update Sonos via RenderingControl SOAP and
 *   update the local [sonosVolume] optimistically so the UI responds instantly.
 *   MainActivity intercepts hardware volume keys and calls [adjustVolume] when
 *   casting is active.
 *
 * The [castState] flow drives the cast button and now-playing cast banner.
 */
object SonosRepository {

    private const val TAG = "SonosRepository"

    sealed interface CastState {
        object Idle : CastState
        object Discovering : CastState
        data class DevicesFound(val devices: List<SonosDevice>) : CastState
        object Connecting : CastState
        data class Casting(val device: SonosDevice, val title: String) : CastState
        data class Error(val message: String) : CastState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _castState = MutableStateFlow<CastState>(CastState.Idle)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    private val _devices = MutableStateFlow<List<SonosDevice>>(emptyList())
    val devices: StateFlow<List<SonosDevice>> = _devices.asStateFlow()

    /** Current Sonos speaker volume, 0–100. Updated optimistically on change. */
    private val _sonosVolume = MutableStateFlow(50)
    val sonosVolume: StateFlow<Int> = _sonosVolume.asStateFlow()

    private var activeDevice: SonosDevice? = null

    /** Retained so [disconnect] can resume the phone player without a Context parameter. */
    private var appContext: Context? = null

    // ── Discovery ─────────────────────────────────────────────────────────

    fun startDiscovery(context: Context) {
        _castState.update { CastState.Discovering }
        scope.launch {
            val found = SonosDiscovery.discover(context)
            _devices.value = found
            _castState.update {
                if (found.isEmpty()) CastState.Error("No Sonos devices found on this network.")
                else CastState.DevicesFound(found)
            }
        }
    }

    // ── Connect ───────────────────────────────────────────────────────────

    fun connect(
        context: Context,
        device: SonosDevice,
        videoId: String,
        title: String,
        watchUrl: String,
    ) {
        _castState.update { CastState.Connecting }
        scope.launch {
            try {
                val localIp = SonosDiscovery.localIp(context)
                if (localIp == null) {
                    _castState.update { CastState.Error("Cannot determine local IP — connect to WiFi first.") }
                    return@launch
                }

                // Pre-resolve the audio stream URL so the proxy can answer
                // Sonos's HEAD probe immediately (no 300-800 ms Innertube RTT
                // on the critical path, which would trigger Sonos's probe timeout).
                val resolvedUrl = if (videoId.isNotBlank()) {
                    YtPlayerUtils.resolveAudioStream(videoId)
                        ?: runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }.getOrNull()
                } else {
                    runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }.getOrNull()
                }
                if (resolvedUrl == null) {
                    _castState.update { CastState.Error("Could not resolve audio stream for this track.") }
                    return@launch
                }

                // Start proxy first (start() calls stop() internally which would wipe
                // any previously-set track, so we must set the track AFTER start()).
                val proxyUrl = SonosProxyServer.start(localIp)

                // Now set the track — this must happen before SetAVTransportURI is sent
                // because Sonos immediately sends a HEAD probe to the proxy URL while
                // processing that SOAP command. If currentTrack is null the proxy
                // returns 503 and Sonos rejects the stream.
                SonosProxyServer.setTrack(
                    SonosProxyServer.TrackInfo(
                        videoId = videoId,
                        title = title,
                        watchUrl = watchUrl,
                        resolvedUrl = resolvedUrl,
                    ),
                )
                Log.d(TAG, "Proxy URL: $proxyUrl")

                // Stop any current playback first — Sonos firmwares return HTTP 500
                // on SetAVTransportURI if the transport is in PLAYING state.
                SonosController.stop(device)

                // Send SetAVTransportURI + Play to Sonos
                val ok = SonosController.setUri(device, proxyUrl, title) &&
                    SonosController.play(device)

                if (ok) {
                    activeDevice = device
                    appContext = context.applicationContext

                    // Pause the local phone player — Sonos is now the speaker.
                    runCatching {
                        withContext(Dispatchers.Main) {
                            MusicController.get(context.applicationContext).pause()
                        }
                    }

                    // Fetch Sonos's current volume so the slider starts at the right position.
                    SonosController.getVolume(device)?.let { _sonosVolume.value = it }

                    _castState.update { CastState.Casting(device, title) }
                } else {
                    SonosProxyServer.stop()
                    _castState.update {
                        CastState.Error("Sonos rejected the stream. Check that the device is on the same network.")
                    }
                }
            } catch (e: Exception) {
                SonosProxyServer.stop()
                Log.w(TAG, "connect failed", e)
                _castState.update { CastState.Error(e.message ?: "Connection failed") }
            }
        }
    }

    // ── Playback control ──────────────────────────────────────────────────

    /** Pause the Sonos player (does not stop the proxy). */
    fun pause() {
        val device = activeDevice ?: return
        scope.launch { SonosController.pause(device) }
    }

    /** Resume a paused Sonos player. */
    fun resume() {
        val device = activeDevice ?: return
        scope.launch { SonosController.play(device) }
    }

    /**
     * Switch the currently casting track on the same Sonos device.
     * Call this whenever the user skips / plays a new song while casting.
     */
    fun updateTrack(context: Context, videoId: String, title: String, watchUrl: String) {
        val device = activeDevice ?: return
        SonosProxyServer.setTrack(
            SonosProxyServer.TrackInfo(videoId = videoId, title = title, watchUrl = watchUrl),
        )
        scope.launch {
            val localIp = SonosDiscovery.localIp(context) ?: return@launch
            val proxyUrl = SonosProxyServer.start(localIp)
            SonosController.setUri(device, proxyUrl, title)
            SonosController.play(device)
            _castState.update { CastState.Casting(device, title) }
        }
    }

    // ── Volume ────────────────────────────────────────────────────────────

    /**
     * Adjust Sonos volume by [delta] percent (positive = louder, negative = quieter).
     * The local [sonosVolume] state is updated optimistically so the UI responds
     * instantly; the SOAP command is sent asynchronously.
     */
    fun adjustVolume(delta: Int) {
        val device = activeDevice ?: return
        val newVol = (_sonosVolume.value + delta).coerceIn(0, 100)
        _sonosVolume.value = newVol
        scope.launch { SonosController.setVolume(device, newVol) }
    }

    /** Set Sonos volume to an absolute [level] (0–100). */
    fun setVolume(level: Int) {
        val device = activeDevice ?: return
        val clamped = level.coerceIn(0, 100)
        _sonosVolume.value = clamped
        scope.launch { SonosController.setVolume(device, clamped) }
    }

    // ── Disconnect ────────────────────────────────────────────────────────

    /** Stop casting, tear down the proxy, and resume the phone's local player. */
    fun disconnect() {
        val device = activeDevice
        val ctx = appContext
        activeDevice = null
        appContext = null
        SonosProxyServer.stop()
        if (device != null) scope.launch { SonosController.stop(device) }
        _castState.update { CastState.Idle }
        _devices.value = emptyList()
        _sonosVolume.value = 50

        // Resume the phone player now that Sonos is no longer the output.
        if (ctx != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.Main) {
                        MusicController.get(ctx).play()
                    }
                }
            }
        }
    }
}
