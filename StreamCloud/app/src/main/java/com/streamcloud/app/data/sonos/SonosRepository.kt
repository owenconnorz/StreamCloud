package com.streamcloud.app.data.sonos

import android.content.Context
import android.util.Log
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val _sonosVolume = MutableStateFlow(50)
    val sonosVolume: StateFlow<Int> = _sonosVolume.asStateFlow()

    private var activeDevice: SonosDevice? = null
    private var appContext: Context? = null

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

                // ── Resolve audio stream ───────────────────────────────────
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

                // ── Start proxy with the pre-resolved URL ──────────────────
                val proxyUrl = SonosProxyServer.start(localIp)
                SonosProxyServer.setTrack(
                    SonosProxyServer.TrackInfo(
                        videoId    = videoId,
                        title      = title,
                        watchUrl   = watchUrl,
                        resolvedUrl = resolvedUrl,
                    ),
                )
                Log.d(TAG, "Proxy URL: $proxyUrl")

                // ── Stop any current playback, then wait for STOPPED state ─
                // Some Sonos firmware briefly enters TRANSITIONING after Stop.
                // Calling SetAVTransportURI during TRANSITIONING returns UPnP
                // error 701, so we poll until the transport is idle.
                SonosController.stop(device)
                waitForStopped(device, timeoutMs = 2_000)

                // ── Set URI + Play (retry once on transient failures) ──────
                var ok = SonosController.setUri(device, proxyUrl, title) &&
                    SonosController.play(device)

                if (!ok) {
                    // Retry after a brief pause — Sonos may still be settling
                    Log.w(TAG, "setUri/play failed, retrying in 600 ms…")
                    delay(600)
                    ok = SonosController.setUri(device, proxyUrl, title) &&
                        SonosController.play(device)
                }

                if (ok) {
                    activeDevice = device
                    appContext = context.applicationContext

                    runCatching {
                        withContext(Dispatchers.Main) {
                            MusicController.get(context.applicationContext).pause()
                        }
                    }

                    SonosController.getVolume(device)?.let { _sonosVolume.value = it }
                    _castState.update { CastState.Casting(device, title) }
                } else {
                    SonosProxyServer.stop()
                    _castState.update {
                        CastState.Error(
                            "Sonos rejected the stream.\n" +
                            "Make sure the speaker is not grouped with others and retry."
                        )
                    }
                }
            } catch (e: Exception) {
                SonosProxyServer.stop()
                Log.w(TAG, "connect failed", e)
                _castState.update { CastState.Error(e.message ?: "Connection failed") }
            }
        }
    }

    /**
     * Poll GetTransportInfo until Sonos reports STOPPED (or timeout).
     * This prevents SetAVTransportURI from hitting UPnP error 701
     * (Transition Not Available) when the device is still TRANSITIONING.
     */
    private suspend fun waitForStopped(device: SonosDevice, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = SonosController.getState(device) ?: break
            if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") break
            delay(200)
        }
    }

    fun pause() {
        val device = activeDevice ?: return
        scope.launch { SonosController.pause(device) }
    }

    fun resume() {
        val device = activeDevice ?: return
        scope.launch { SonosController.play(device) }
    }

    fun updateTrack(context: Context, videoId: String, title: String, watchUrl: String) {
        val device = activeDevice ?: return
        SonosProxyServer.setTrack(
            SonosProxyServer.TrackInfo(videoId = videoId, title = title, watchUrl = watchUrl),
        )
        scope.launch {
            val localIp = SonosDiscovery.localIp(context) ?: return@launch
            val proxyUrl = SonosProxyServer.start(localIp)
            SonosController.stop(device)
            waitForStopped(device, 1_500)
            SonosController.setUri(device, proxyUrl, title)
            SonosController.play(device)
            _castState.update { CastState.Casting(device, title) }
        }
    }

    fun adjustVolume(delta: Int) {
        val device = activeDevice ?: return
        val newVol = (_sonosVolume.value + delta).coerceIn(0, 100)
        _sonosVolume.value = newVol
        scope.launch { SonosController.setVolume(device, newVol) }
    }

    fun setVolume(level: Int) {
        val device = activeDevice ?: return
        val clamped = level.coerceIn(0, 100)
        _sonosVolume.value = clamped
        scope.launch { SonosController.setVolume(device, clamped) }
    }

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
