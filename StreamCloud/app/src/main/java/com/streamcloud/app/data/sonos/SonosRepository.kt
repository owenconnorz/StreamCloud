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
            val groups = runCatching {
                SonosDiscovery.buildGroups(found.first(), found)
            }.getOrDefault(emptyList())
            _castState.update { CastState.DevicesFound(found, groups) }
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




                val resolvedUrl = if (videoId.isNotBlank()) {
                    runCatching { YtPlayerUtils.resolveAudioStream(videoId) }.getOrNull()
                        ?: runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }.getOrNull()
                } else {
                    runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }.getOrNull()
                }
                if (resolvedUrl == null) {
                    _castState.update { CastState.Error("Could not get audio stream — track may be restricted by YouTube.") }
                    return@launch
                }



                val proxyUrl = SonosProxyServer.start(localIp)





                SonosProxyServer.setTrack(
                    SonosProxyServer.TrackInfo(
                        videoId = videoId,
                        title = title,
                        watchUrl = watchUrl,
                        resolvedUrl = resolvedUrl,
                    ),
                )
                Log.d(TAG, "Proxy URL: $proxyUrl")



                SonosController.stop(device)


                val ok = SonosController.setUri(device, proxyUrl, title) &&
                    SonosController.play(device)

                if (ok) {
                    _isSonosPlaying.value = true
                    activeDevice = device
                    appContext = context.applicationContext


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
