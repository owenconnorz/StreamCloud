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
        data class GroupsFound(val groups: List<SonosGroup>) : CastState
        object Connecting : CastState
        data class Casting(val group: SonosGroup, val title: String) : CastState
        data class Error(val message: String) : CastState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _castState = MutableStateFlow<CastState>(CastState.Idle)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    private val _sonosVolume = MutableStateFlow(50)
    val sonosVolume: StateFlow<Int> = _sonosVolume.asStateFlow()

    private var activeGroup: SonosGroup? = null
    private var appContext: Context? = null

    // ── Discovery ──────────────────────────────────────────────────────────────

    fun startDiscovery(context: Context) {
        _castState.update { CastState.Discovering }
        scope.launch {
            try {
                val devices = SonosDiscovery.discover(context)
                if (devices.isEmpty()) {
                    _castState.update { CastState.Error("No Sonos speakers found on this network.") }
                    return@launch
                }
                val groups = fetchGroups(devices)
                _castState.update { CastState.GroupsFound(groups) }
            } catch (e: Exception) {
                Log.w(TAG, "discovery error", e)
                _castState.update { CastState.Error("Discovery failed: ${e.message}") }
            }
        }
    }

    private suspend fun fetchGroups(devices: List<SonosDevice>): List<SonosGroup> {
        for (dev in devices) {
            val soap = SonosController.getZoneGroupState(dev) ?: continue
            Log.d(TAG, "GetZoneGroupState from ${dev.name}: ${soap.take(200)}")
            val groups = SonosGroup.parseZoneGroupXml(soap)
            if (groups.isNotEmpty()) {
                Log.d(TAG, "Parsed ${groups.size} group(s): ${groups.map { it.displayName }}")
                return groups
            }
        }
        Log.w(TAG, "GetZoneGroupState unavailable — falling back to individual devices")
        return SonosGroup.fromDevices(devices)
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    fun connect(
        context: Context,
        group: SonosGroup,
        videoId: String,
        title: String,
        watchUrl: String,
    ) {
        _castState.update { CastState.Connecting }
        scope.launch {
            try {
                val localIp = SonosDiscovery.localIp(context)
                if (localIp == null) {
                    _castState.update { CastState.Error("Cannot determine local IP — connect to Wi-Fi first.") }
                    return@launch
                }

                // Resolve stream preferring audio/mp4 (M4A/AAC).
                // Sonos does NOT support WebM/Opus — it causes UPnP error 714.
                val (resolvedUrl, mimeType) = resolveStreamForSonos(videoId, watchUrl) ?: run {
                    _castState.update { CastState.Error("Could not resolve audio stream for this track.") }
                    return@launch
                }
                Log.d(TAG, "Resolved stream: mimeType=$mimeType url=${resolvedUrl.take(80)}")

                val proxyUrl = SonosProxyServer.start(localIp)
                SonosProxyServer.setTrack(SonosProxyServer.TrackInfo(
                    videoId     = videoId,
                    title       = title,
                    watchUrl    = watchUrl,
                    resolvedUrl = resolvedUrl,
                    mimeType    = mimeType,
                ))
                Log.d(TAG, "Proxy: $proxyUrl  Coordinator: ${group.coordinatorHost}:${group.coordinatorPort}")

                val coordinator = group.coordinatorDevice

                // Stop → wait for STOPPED → set URI → play
                SonosController.stop(coordinator)
                waitForStopped(coordinator, 2_000)

                var ok = SonosController.setUri(coordinator, proxyUrl, title, mimeType) &&
                    SonosController.play(coordinator)

                // Retry without Stop in case Stop left device in bad state
                if (!ok) {
                    Log.w(TAG, "Attempt 1 failed [${SonosController.lastSoapError}] — retrying")
                    delay(600)
                    ok = SonosController.setUri(coordinator, proxyUrl, title, mimeType) &&
                        SonosController.play(coordinator)
                }

                if (ok) {
                    activeGroup = group
                    appContext  = context.applicationContext
                    runCatching {
                        withContext(Dispatchers.Main) {
                            MusicController.get(context.applicationContext).pause()
                        }
                    }
                    SonosController.getVolume(coordinator)?.let { _sonosVolume.value = it }
                    _castState.update { CastState.Casting(group, title) }
                } else {
                    SonosProxyServer.stop()
                    val detail = SonosController.lastSoapError.ifBlank { "unknown error" }
                    _castState.update {
                        CastState.Error(
                            "Sonos rejected the stream.\n[$detail]\n\n" +
                            "Try restarting the Sonos app or rebooting the speaker."
                        )
                    }
                }
            } catch (e: Exception) {
                SonosProxyServer.stop()
                Log.w(TAG, "connect failed", e)
                _castState.update { CastState.Error(e.message ?: "Connection failed.") }
            }
        }
    }

    /**
     * Resolve audio stream for Sonos, explicitly preferring M4A/AAC (audio/mp4).
     * WebM/Opus is excluded because Sonos hardware cannot decode it.
     * Returns (url, mimeType) or null if resolution fails entirely.
     */
    private suspend fun resolveStreamForSonos(videoId: String, watchUrl: String): Pair<String, String>? {
        if (videoId.isNotBlank()) {
            val result = runCatching { YtPlayerUtils.resolveAudioStreamForSonos(videoId) }.getOrNull()
            if (result != null) return result
        }
        // Fallback: NewPipe / direct URL — assume audio/mpeg if unknown
        val url = runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }.getOrNull()
            ?: return null
        return Pair(url, "audio/mpeg")
    }

    private suspend fun waitForStopped(device: SonosDevice, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = SonosController.getState(device) ?: break
            if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") break
            delay(200)
        }
    }

    // ── Playback controls ──────────────────────────────────────────────────────

    fun pause() {
        scope.launch { activeGroup?.coordinatorDevice?.let { SonosController.pause(it) } }
    }

    fun resume() {
        scope.launch { activeGroup?.coordinatorDevice?.let { SonosController.play(it) } }
    }

    fun updateTrack(context: Context, videoId: String, title: String, watchUrl: String) {
        val group = activeGroup ?: return
        scope.launch {
            val localIp = SonosDiscovery.localIp(context) ?: return@launch
            val (resolved, mimeType) = resolveStreamForSonos(videoId, watchUrl) ?: return@launch
            SonosProxyServer.setTrack(SonosProxyServer.TrackInfo(
                videoId = videoId, title = title, watchUrl = watchUrl,
                resolvedUrl = resolved, mimeType = mimeType,
            ))
            val proxyUrl    = SonosProxyServer.start(localIp)
            val coordinator = group.coordinatorDevice
            SonosController.stop(coordinator)
            waitForStopped(coordinator, 1_500)
            SonosController.setUri(coordinator, proxyUrl, title, mimeType)
            SonosController.play(coordinator)
            _castState.update { CastState.Casting(group, title) }
        }
    }

    // ── Volume ─────────────────────────────────────────────────────────────────

    fun adjustVolume(delta: Int) {
        val group = activeGroup ?: return
        val newVol = (_sonosVolume.value + delta).coerceIn(0, 100)
        _sonosVolume.value = newVol
        scope.launch { SonosController.setVolume(group.coordinatorDevice, newVol) }
    }

    fun setVolume(level: Int) {
        val group = activeGroup ?: return
        _sonosVolume.value = level.coerceIn(0, 100)
        scope.launch { SonosController.setVolume(group.coordinatorDevice, _sonosVolume.value) }
    }

    // ── Disconnect ─────────────────────────────────────────────────────────────

    fun disconnect() {
        val group = activeGroup
        val ctx   = appContext
        activeGroup = null
        appContext  = null
        SonosProxyServer.stop()
        if (group != null) scope.launch { SonosController.stop(group.coordinatorDevice) }
        _castState.update { CastState.Idle }
        _sonosVolume.value = 50

        if (ctx != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.Main) { MusicController.get(ctx).play() }
                }
            }
        }
    }
}
