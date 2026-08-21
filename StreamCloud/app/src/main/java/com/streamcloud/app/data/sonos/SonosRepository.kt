package com.streamcloud.app.data.sonos

import android.content.Context
import android.util.Log
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull

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
    private var pollingJob: kotlinx.coroutines.Job? = null
    private var connectionJob: kotlinx.coroutines.Job? = null

    private const val STREAM_PREPARATION_TIMEOUT_MS = 35_000L

    private data class PreparedSonosStream(
        val url: String?,
        val mimeType: String,
        val userAgent: String,
        val contentLength: Long?,
    )

    private suspend fun prepareSonosStream(
        videoId: String,
        watchUrl: String,
    ): PreparedSonosStream {
        val formatInfo = if (videoId.isNotBlank()) {
            runCatching {
                YtPlayerUtils.resolveAudioFormatInfo(videoId, sonosSafe = true)
            }.getOrNull()
        } else {
            null
        }
        // Browser/PoToken URLs depend on session headers that a separate Sonos speaker cannot
        // send. Prefer the independent extractor in that case rather than advertising a URL that
        // will fail after the speaker has accepted the cast request.
        val sonosFormat = formatInfo?.takeUnless { it.requiresWebSessionHeaders }
        val extractorStream = if (sonosFormat?.url == null) {
            runCatching {
                NewPipeRepository.resolveVerifiedAudioStream(watchUrl)
            }.getOrNull()
        } else {
            null
        }
        return PreparedSonosStream(
            url = sonosFormat?.url ?: extractorStream?.url,
            mimeType = sonosFormat?.mimeType?.substringBefore(";")?.trim() ?: "audio/mp4",
            userAgent = sonosFormat?.userAgent
                ?: extractorStream?.userAgent
                ?: SonosProxyServer.DEFAULT_UPSTREAM_USER_AGENT,
            contentLength = sonosFormat?.contentLength,
        )
    }

    // ── Position polling ──────────────────────────────────────────────────────

    private fun startPositionPolling(device: SonosDevice) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var tick = 0
            while (true) {
                delay(1_000)
                val upstreamFailure = SonosProxyServer.consumeUpstreamFailure()
                if (upstreamFailure != null) {
                    Log.w(TAG, "Sonos proxy stopped serving audio: $upstreamFailure")
                    _isSonosPlaying.value = false
                    _castState.update { CastState.Error("Sonos playback stopped: $upstreamFailure") }
                    SonosProxyServer.stop()
                    runCatching { SonosController.stop(device) }
                    break
                }
                // GetPositionInfo every second for smooth progress bar
                runCatching { SonosController.getPositionInfo(device) }.getOrNull()?.let { (pos, dur) ->
                    _sonosPositionMs.value = pos
                    _sonosDurationMs.value = dur
                }
                // Sync transport state every 5 seconds
                if (tick % 5 == 0) {
                    runCatching { SonosController.getState(device) }.getOrNull()?.let { state ->
                        _isSonosPlaying.value = state == "PLAYING"
                    }
                }
                tick++
            }
        }
    }

    fun seek(positionMs: Long) {
        val device = activeDevice ?: return
        // Optimistically update UI while the SOAP call is in-flight
        _sonosPositionMs.value = positionMs
        scope.launch { SonosController.seek(device, positionMs) }
    }

    fun startDiscovery(context: Context) {
        _castState.update { CastState.Discovering }
        scope.launch {
            val found = SonosDiscovery.discover(context)
            _devices.value = found
            if (found.isEmpty()) {
                _castState.update { CastState.Error("No Sonos devices found on this network.") }
                return@launch
            }
            // Try each discovered device in turn until one answers GetZoneGroupState.
            // Non-Sonos devices (e.g. Vestel) don't support ZoneGroupTopology and
            // return null/error; a real Sonos speaker will respond correctly.
            val allZones = run {
                var zones = emptyList<SonosGroup>()
                for (device in found) {
                    zones = runCatching {
                        SonosDiscovery.buildGroups(device, found)
                    }.getOrDefault(emptyList())
                    if (zones.isNotEmpty()) break
                }
                zones
            }
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
        connectionJob?.cancel()
        _castState.update { CastState.Connecting }
        connectionJob = scope.launch {
            try {
                val localIp = SonosDiscovery.localIp(context)
                if (localIp == null) {
                    _castState.update { CastState.Error("Cannot determine local IP — connect to WiFi first.") }
                    return@launch
                }

                // Check the selected speaker before resolving a remote stream. This makes a
                // stale discovery result fail promptly instead of displaying Connecting while
                // extractor calls run for tens of seconds.
                val reachable = SonosController.getState(device) != null
                if (!reachable) {
                    _castState.update {
                        CastState.Error(
                            "Cannot reach ${device.name} (${device.host}). " +
                                "Make sure both devices are on the same WiFi network.",
                        )
                    }
                    return@launch
                }

                // Pre-resolve the audio URL + MIME type before starting the proxy.
                // Lazy resolution during Sonos's synchronous URI probe causes the SOAP call
                // to time-out (even at 30 s) and Sonos reports "stream rejected."
                val preparedStream = withTimeoutOrNull(STREAM_PREPARATION_TIMEOUT_MS) {
                    prepareSonosStream(videoId, watchUrl)
                }
                val prepared = preparedStream?.takeIf { it.url != null }
                if (prepared == null) {
                    _castState.update {
                        CastState.Error(
                            "Unable to prepare this stream for Sonos. Check your connection and try again.",
                        )
                    }
                    return@launch
                }
                val preflight = SonosProxyServer.preflight(
                    SonosProxyServer.TrackInfo(
                        videoId = videoId,
                        title = title,
                        watchUrl = watchUrl,
                        resolvedUrl = requireNotNull(prepared.url),
                        mimeType = prepared.mimeType,
                        userAgent = prepared.userAgent,
                        contentLength = prepared.contentLength,
                    ),
                )
                val verifiedTrack = (preflight as? SonosProxyServer.TrackPreflight.Ready)?.track
                if (verifiedTrack == null) {
                    val reason = (preflight as? SonosProxyServer.TrackPreflight.Failed)?.message
                        ?: "The audio source could not be verified."
                    _castState.update { CastState.Error("Sonos cannot play this track: $reason") }
                    return@launch
                }

                // IMPORTANT: call start() BEFORE setTrack().
                // start() internally calls stop() which clears currentTrack — if setTrack() ran
                // first, that track reference would be immediately nulled out by stop(), and
                // Sonos's first HEAD probe would hit a null currentTrack and get a 503.
                val proxyUrl = SonosProxyServer.start(localIp)
                SonosProxyServer.setTrack(verifiedTrack)
                Log.d(
                    TAG,
                    "Proxy URL: $proxyUrl  resolved=verified mime=${verifiedTrack.mimeType} " +
                        "length=${verifiedTrack.contentLength}",
                )

                // Retry up to 2 times: some Sonos firmware takes a moment after Stop()
                // to become ready for a new SetAVTransportURI command.
                var ok = false
                var failReason = ""
                for (attempt in 0 until 2) {
                    if (attempt > 0) delay(2_000L)
                    SonosController.stop(device)

                    val uriError = SonosController.setUri(
                        device,
                        proxyUrl,
                        title,
                        verifiedTrack.mimeType,
                    )
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
                    _sonosPositionMs.value = 0L
                    _sonosDurationMs.value = 0L
                    startPositionPolling(device)
                    _castState.update { CastState.Casting(device, title, displayName) }
                } else {
                    SonosProxyServer.stop()
                    _castState.update {
                        CastState.Error("Sonos stream failed: $failReason")
                    }
                }
            } catch (e: CancellationException) {
                SonosProxyServer.stop()
                throw e
            } catch (e: Exception) {
                SonosProxyServer.stop()
                Log.w(TAG, "connect failed", e)
                _castState.update { CastState.Error(e.message ?: "Connection failed") }
            }
        }
    }

    fun cancelConnection(context: Context) {
        connectionJob?.cancel()
        connectionJob = null
        SonosProxyServer.stop()
        _castState.update { CastState.Idle }
        startDiscovery(context)
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
        // Reset UI state immediately so the player shows 0:00 for the new track
        // rather than stale position/duration from the previous one.
        _sonosPositionMs.value = 0L
        _sonosDurationMs.value = 0L
        _isSonosPlaying.value = false
        scope.launch {
            val localIp = SonosDiscovery.localIp(context) ?: return@launch

            // Use resolveAudioFormatInfo (same as connect()) so we also get the mimeType,
            // which the proxy needs for correct Content-Type in HEAD responses.
            val preparedStream = withTimeoutOrNull(STREAM_PREPARATION_TIMEOUT_MS) {
                prepareSonosStream(videoId, watchUrl)
            }
            val prepared = preparedStream?.takeIf { it.url != null }
            if (prepared == null) {
                Log.w(TAG, "updateTrack could not prepare a stream for $videoId")
                _castState.update {
                    CastState.Error(
                        "Sonos cannot play this track: unable to prepare a playable audio stream.",
                    )
                }
                return@launch
            }
            val preflight = SonosProxyServer.preflight(
                SonosProxyServer.TrackInfo(
                    videoId = videoId,
                    title = title,
                    watchUrl = watchUrl,
                    resolvedUrl = requireNotNull(prepared.url),
                    mimeType = prepared.mimeType,
                    userAgent = prepared.userAgent,
                    contentLength = prepared.contentLength,
                ),
            )
            val verifiedTrack = (preflight as? SonosProxyServer.TrackPreflight.Ready)?.track
            if (verifiedTrack == null) {
                val reason = (preflight as? SonosProxyServer.TrackPreflight.Failed)?.message
                    ?: "The audio source could not be verified."
                Log.w(TAG, "updateTrack preflight failed: $reason")
                _castState.update { CastState.Error("Sonos cannot play this track: $reason") }
                return@launch
            }

            // start() calls stop() internally which clears currentTrack — must call
            // start() FIRST, then setTrack(), so Sonos's HEAD probe after setUri finds
            // the track rather than getting a 503 No Track Set response.
            val proxyUrl = SonosProxyServer.start(localIp)
            SonosProxyServer.setTrack(verifiedTrack)

            // Stop Sonos before SetAVTransportURI — some firmware versions reject the
            // command while the transport is in PLAYING state, which causes a silent
            // failure and leaves the player stuck at 0:00 with nothing loading.
            SonosController.stop(device)

            var uriError = SonosController.setUri(device, proxyUrl, title, verifiedTrack.mimeType)
            if (uriError != null) {
                Log.w(TAG, "updateTrack setUri failed ($uriError), retrying after 1.5 s")
                delay(1_500L)
                SonosController.stop(device)
                uriError = SonosController.setUri(device, proxyUrl, title, verifiedTrack.mimeType)
            }

            if (uriError == null) {
                SonosController.play(device)
                _isSonosPlaying.value = true
            } else {
                Log.w(TAG, "updateTrack setUri failed after retry: $uriError")
            }

            // Preserve the device reference and display name from the current cast state.
            _castState.update { cs ->
                if (cs is CastState.Casting) cs.copy(title = title) else cs
            }
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
        pollingJob?.cancel()
        pollingJob = null
        _isSonosPlaying.value = false
        _sonosPositionMs.value = 0L
        _sonosDurationMs.value = 0L
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
