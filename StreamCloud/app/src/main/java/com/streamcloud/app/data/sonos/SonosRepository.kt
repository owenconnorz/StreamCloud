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
        /** Discovered groups ready for the user to choose from. */
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
                // 1. SSDP to find raw devices
                val devices = SonosDiscovery.discover(context)
                if (devices.isEmpty()) {
                    _castState.update { CastState.Error("No Sonos speakers found on this network.") }
                    return@launch
                }

                // 2. Fetch zone group topology from the first responsive device.
                //    GetZoneGroupState returns the topology for the ENTIRE household,
                //    so querying any single speaker is sufficient.
                val groups = fetchGroups(devices)
                _castState.update { CastState.GroupsFound(groups) }
            } catch (e: Exception) {
                Log.w(TAG, "discovery error", e)
                _castState.update { CastState.Error("Discovery failed: ${e.message}") }
            }
        }
    }

    /**
     * Build the group list from the household topology.
     * Falls back to wrapping individual devices when the topology call fails.
     */
    private suspend fun fetchGroups(devices: List<SonosDevice>): List<SonosGroup> {
        for (dev in devices) {
            val soap = SonosController.getZoneGroupState(dev) ?: continue
            val groups = SonosGroup.parseZoneGroupXml(soap)
            if (groups.isNotEmpty()) {
                Log.d(TAG, "Parsed ${groups.size} group(s) from ${dev.name}")
                return groups
            }
        }
        // Fallback: present each SSDP device as a solo group
        Log.w(TAG, "GetZoneGroupState unavailable — falling back to individual devices")
        return SonosGroup.fromDevices(devices)
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    /**
     * Start casting [title] to the selected Sonos [group].
     *
     * All UPnP commands are routed to the **group coordinator** — the master
     * speaker that tells every member of the group what to play.  Sending
     * SetAVTransportURI to a non-coordinator returns UPnP error 701 and causes
     * the "Sonos rejected the stream" failure.
     */
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
                    _castState.update {
                        CastState.Error("Cannot determine local IP — connect to Wi-Fi first.")
                    }
                    return@launch
                }

                // Resolve the audio stream URL before starting the proxy, so the
                // proxy can serve it immediately without an extra round-trip.
                val resolvedUrl = resolveStream(videoId, watchUrl)
                if (resolvedUrl == null) {
                    _castState.update {
                        CastState.Error("Could not resolve audio stream for this track.")
                    }
                    return@launch
                }

                val proxyUrl = SonosProxyServer.start(localIp)
                SonosProxyServer.setTrack(
                    SonosProxyServer.TrackInfo(
                        videoId     = videoId,
                        title       = title,
                        watchUrl    = watchUrl,
                        resolvedUrl = resolvedUrl,
                    ),
                )
                Log.d(TAG, "Proxy: $proxyUrl  Coordinator: ${group.coordinatorHost}:${group.coordinatorPort}")

                // Route to the GROUP COORDINATOR — this is what makes all speakers
                // in the group play simultaneously.
                val coordinator = group.coordinatorDevice

                // Stop current playback and wait for Sonos to reach STOPPED.
                // SetAVTransportURI during TRANSITIONING → UPnP error 701.
                SonosController.stop(coordinator)
                waitForStopped(coordinator, timeoutMs = 2_000)

                // Set URI + Play (with one automatic retry on transient failure)
                var ok = SonosController.setUri(coordinator, proxyUrl, title) &&
                    SonosController.play(coordinator)
                if (!ok) {
                    Log.w(TAG, "setUri/play failed — retrying in 600 ms")
                    delay(600)
                    ok = SonosController.setUri(coordinator, proxyUrl, title) &&
                        SonosController.play(coordinator)
                }

                if (ok) {
                    activeGroup = group
                    appContext  = context.applicationContext
                    // Pause the on-device player so audio doesn't overlap
                    runCatching {
                        withContext(Dispatchers.Main) {
                            MusicController.get(context.applicationContext).pause()
                        }
                    }
                    SonosController.getVolume(coordinator)?.let { _sonosVolume.value = it }
                    _castState.update { CastState.Casting(group, title) }
                } else {
                    SonosProxyServer.stop()
                    _castState.update {
                        CastState.Error(
                            "Sonos rejected the stream.\n" +
                            "Try selecting a different group or restarting the speaker."
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

    private suspend fun resolveStream(videoId: String, watchUrl: String): String? {
        return if (videoId.isNotBlank()) {
            YtPlayerUtils.resolveAudioStream(videoId)
                ?: runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }.getOrNull()
        } else {
            runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }.getOrNull()
        }
    }

    /**
     * Poll GetTransportInfo until Sonos is STOPPED (or timeout elapses).
     * Prevents SetAVTransportURI from failing with UPnP error 701.
     */
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
            val localIp  = SonosDiscovery.localIp(context) ?: return@launch
            val resolved = resolveStream(videoId, watchUrl) ?: return@launch
            SonosProxyServer.setTrack(
                SonosProxyServer.TrackInfo(
                    videoId     = videoId,
                    title       = title,
                    watchUrl    = watchUrl,
                    resolvedUrl = resolved,
                ),
            )
            val proxyUrl    = SonosProxyServer.start(localIp)
            val coordinator = group.coordinatorDevice
            SonosController.stop(coordinator)
            waitForStopped(coordinator, 1_500)
            SonosController.setUri(coordinator, proxyUrl, title)
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
        val group   = activeGroup ?: return
        val clamped = level.coerceIn(0, 100)
        _sonosVolume.value = clamped
        scope.launch { SonosController.setVolume(group.coordinatorDevice, clamped) }
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
