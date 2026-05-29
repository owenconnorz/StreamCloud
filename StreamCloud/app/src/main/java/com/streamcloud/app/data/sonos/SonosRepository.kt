package com.streamcloud.app.data.sonos

import android.content.Context
import android.util.Log
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        data class DevicesFound(
            val devices: List<SonosDevice>,
            val groups: List<SonosGroup> = emptyList(),
        ) : CastState
        object Connecting : CastState
        data class Casting(val device: SonosDevice, val title: String, val displayName: String = device.name) : CastState
        data class Error(val message: String) : CastState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _castState = MutableStateFlow<CastState>(CastState.Idle)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    private val _devices = MutableStateFlow<List<SonosDevice>>(emptyList())
    val devices: StateFlow<List<SonosDevice>> = _devices.asStateFlow()

    private val _sonosVolume = MutableStateFlow(50)
    val sonosVolume: StateFlow<Int> = _sonosVolume.asStateFlow()

    private val _isSonosPlaying = MutableStateFlow(false)
    val isSonosPlaying: StateFlow<Boolean> = _isSonosPlaying.asStateFlow()

    private val _sonosPositionMs = MutableStateFlow(0L)
    val sonosPositionMs: StateFlow<Long> = _sonosPositionMs.asStateFlow()

    private val _sonosDurationMs = MutableStateFlow(0L)
    val sonosDurationMs: StateFlow<Long> = _sonosDurationMs.asStateFlow()

    private var activeDevice: SonosDevice? = null
    private var appContext: Context? = null

    fun startDiscovery(context: Context) {
        _castState.update { CastState.Discovering }
        scope.launch {
            val found = SonosDiscovery.discover(context)
            _devices.value = found
            if (found.isEmpty()) {
                _castState.update { CastState.Error("No Sonos devices found on this network.") }
                return@launch
            }
            val allZones = runCatching {
                SonosDiscovery.buildGroups(found.first(), found)
            }.getOrDefault(emptyList())
            if (allZones.isNotEmpty()) {
                val multiRoom     = allZones.filter { it.isMultiRoom }
                val singleDevices = allZones.filter { !it.isMultiRoom }.map { it.coordinatorDevice }
                _castState.update { CastState.DevicesFound(singleDevices, multiRoom) }
            } else {
                _castState.update { CastState.DevicesFound(found, emptyList()) }
            }
        }
    }

    fun connect(
        context: Context,
        device: SonosDevice,
        videoId: String,
        title: String,
        watchUrl: String,
        displayName: String = device.name,
    ) {
        _castState.update { CastState.Connecting }
        scope.launch {
            try {
                val localIp = SonosDiscovery.localIp(context)
                if (localIp == null) {
                    _castState.update { CastState.Error("Cannot determine local IP — connect to WiFi first.") }
                    return@launch
                }

                // Pre-resolve the audio URL + MIME type before starting the proxy.
                // Lazy resolution during Sonos's synchronous URI probe causes the SOAP call
                // to time-out (even at 30 s) and Sonos reports "stream rejected."
                val formatInfo = withContext(Dispatchers.IO) {
                    if (videoId.isNotBlank())
                        runCatching { YtPlayerUtils.resolveAudioFormatInfo(videoId, sonosSafe = true) }.getOrNull()
                    else null
                }
                val resolvedUrl: String? = formatInfo?.url
                    ?: withContext(Dispatchers.IO) {
                        runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }.getOrNull()
                    }
                // Normalise MIME type to bare type (strip codec params) so DIDL/HEAD agree.
                val mimeType = formatInfo?.mimeType
                    ?.substringBefore(";")?.trim()
                    ?: "audio/mp4"

                // IMPORTANT: call start() BEFORE setTrack().
                // start() internally calls stop() which clears currentTrack — if setTrack() ran
                // first, that track reference would be immediately nulled out by stop(), and
                // Sonos's first HEAD probe would hit a null currentTrack and get a 503.
                val proxyUrl = SonosProxyServer.start(localIp)
                SonosProxyServer.setTrack(
                    SonosProxyServer.TrackInfo(
                        videoId     = videoId,
                        title       = title,
                        watchUrl    = watchUrl,
                        resolvedUrl = resolvedUrl,
                        mimeType    = mimeType,
                    ),
                )
                val resolvedTag = if (resolvedUrl != null) "pre-resolved" else "lazy"
                Log.d(TAG, "Proxy URL: $proxyUrl  resolve=$resolvedTag  mime=$mimeType")

                // Pre-flight: verify Sonos is reachable before attempting transport commands.
                // GetTransportInfo uses the same host:port as SetAVTransportURI — if it returns
                // null the device is unreachable (wrong IP, AP isolation, device offline).
                val reachable = SonosController.getState(device) != null
                if (!reachable) {
                    SonosProxyServer.stop()
                    _castState.update {
                        CastState.Error("Cannot reach ${device.name} (${device.host}). Make sure both devices are on the same WiFi network.")
                    }
                    return@launch
                }

                // Retry up to 2 times: some Sonos firmware takes a moment after Stop()
                // to become ready for a new SetAVTransportURI command.
                var ok = false
                var failReason = ""
                for (attempt in 0 until 2) {
                    if (attempt > 0) delay(2_000L)
                    SonosController.stop(device)

                    val uriError = SonosController.setUri(device, proxyUrl, title, mimeType)
                    if (uriError != null) {
                        failReason = uriError
                        Log.w(TAG, "attempt $attempt: setUri failed — $uriError")
                        continue
                    }

                    if (!SonosController.play(device)) {
                        failReason = "Play command rejected by Sonos"
                        Log.w(TAG, "attempt $attempt: play failed")
                        continue
                    }

                    ok = true
                    break
                }

                if (ok) {
                    _isSonosPlaying.value = true
                    activeDevice  = device
                    appContext    = context.applicationContext

                    runCatching {
                        withContext(Dispatchers.Main) {
                            MusicController.get(context.applicationContext).pause()
                        }
                    }

                    SonosController.getVolume(device)?.let { _sonosVolume.value = it }
                    _castState.update { CastState.Casting(device, title, displayName) }
                } else {
                    SonosProxyServer.stop()
                    _castState.update {
                        CastState.Error("Sonos stream failed: $failReason")
                    }
                }
            } catch (e: Exception) {
                SonosProxyServer.stop()
                Log.w(TAG, "connect failed", e)
                _castState.update { CastState.Error(e.message ?: "Connection failed") }
            }
        }
    }

    fun pause() {
        val device = activeDevice ?: return
        _isSonosPlaying.value = false
        scope.launch { SonosController.pause(device) }
    }

    fun resume() {
        val device = activeDevice ?: return
        _isSonosPlaying.value = true
        scope.launch { SonosController.play(device) }
    }

    fun updateTrack(context: Context, videoId: String, title: String, watchUrl: String) {
        val device = activeDevice ?: return
        scope.launch {
            val localIp = SonosDiscovery.localIp(context) ?: return@launch
            val resolvedUrl: String? = withContext(Dispatchers.IO) {
                if (videoId.isNotBlank())
                    runCatching { YtPlayerUtils.resolveAudioStream(videoId, sonosSafe = true) }.getOrNull()
                else null
            } ?: withContext(Dispatchers.IO) {
                runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }.getOrNull()
            }
            SonosProxyServer.setTrack(
                SonosProxyServer.TrackInfo(
                    videoId     = videoId,
                    title       = title,
                    watchUrl    = watchUrl,
                    resolvedUrl = resolvedUrl,
                ),
            )
            val proxyUrl = SonosProxyServer.start(localIp)
            SonosController.setUriBoolean(device, proxyUrl, title)
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
        _isSonosPlaying.value = false
        val device = activeDevice
        val ctx    = appContext
        activeDevice = null
        appContext    = null
        SonosProxyServer.stop()
        if (device != null) scope.launch { SonosController.stop(device) }
        _castState.update { CastState.Idle }
        _devices.value    = emptyList()
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
