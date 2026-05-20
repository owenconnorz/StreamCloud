package com.streamcloud.app.data.sonos

import android.content.Context
import android.util.Log
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

/**
 * Application-level orchestrator for Sonos device discovery and cast control.
 *
 * Cast lifecycle:
 *   1. Call [startDiscovery] — populates [devices] within a few seconds.
 *   2. Call [connect] with the chosen device + current track info —
 *      starts the proxy server, resolves + sends the stream URL to Sonos.
 *   3. Call [pause] / [resume] / [disconnect] to control or end the session.
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

    private var activeDevice: SonosDevice? = null

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
                // on the critical path which would trigger Sonos's probe timeout).
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

                // Tell the proxy which track to serve (including the pre-resolved URL)
                SonosProxyServer.setTrack(
                    SonosProxyServer.TrackInfo(
                        videoId = videoId,
                        title = title,
                        watchUrl = watchUrl,
                        resolvedUrl = resolvedUrl,
                    ),
                )

                // Start proxy and get the URL Sonos will stream from
                val proxyUrl = SonosProxyServer.start(localIp)
                Log.d(TAG, "Proxy URL: $proxyUrl")

                // Send SetAVTransportURI + Play to Sonos
                val ok = SonosController.setUri(device, proxyUrl, title) &&
                    SonosController.play(device)

                if (ok) {
                    activeDevice = device
                    _castState.update { CastState.Casting(device, title) }
                } else {
                    SonosProxyServer.stop()
                    _castState.update { CastState.Error("Sonos rejected the stream. Check that the device is on the same network.") }
                }
            } catch (e: Exception) {
                SonosProxyServer.stop()
                Log.w(TAG, "connect failed", e)
                _castState.update { CastState.Error(e.message ?: "Connection failed") }
            }
        }
    }

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

    /** Stop casting and tear down the proxy. */
    fun disconnect() {
        val device = activeDevice
        activeDevice = null
        SonosProxyServer.stop()
        if (device != null) scope.launch { SonosController.stop(device) }
        _castState.update { CastState.Idle }
        _devices.value = emptyList()
    }
}
