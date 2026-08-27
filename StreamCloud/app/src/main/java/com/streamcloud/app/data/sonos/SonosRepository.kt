package com.streamcloud.app.data.sonos

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    @Volatile private var activeDevice: SonosDevice? = null
    private var appContext: Context? = null
    private var queuePlayer: Player? = null
    private var queueListener: Player.Listener? = null
    private var observedQueueMediaId: String? = null
    private var pollingJob: kotlinx.coroutines.Job? = null
    private var connectionJob: kotlinx.coroutines.Job? = null
    private var trackUpdateJob: kotlinx.coroutines.Job? = null
    private val trackUpdateMutex = Mutex()
    private val trackUpdateGeneration = AtomicInteger(0)
    private data class TrackUpdateIntent(val generation: Int, val shouldPlay: Boolean)
    private val trackUpdateIntent = AtomicReference(TrackUpdateIntent(0, true))

    private val _isSonosTrackUpdating = MutableStateFlow(false)
    val isSonosTrackUpdating: StateFlow<Boolean> = _isSonosTrackUpdating.asStateFlow()

    private const val STREAM_PREPARATION_TIMEOUT_MS = 35_000L

    private data class PreparedSonosStream(
        val url: String?,
        val mimeType: String,
        val userAgent: String,
        val contentLength: Long?,
    )

    private fun isCurrentTrackUpdate(generation: Int): Boolean =
        trackUpdateGeneration.get() == generation

    private fun setTrackUpdatePlaybackIntent(shouldPlay: Boolean) {
        while (true) {
            val current = trackUpdateIntent.get()
            if (trackUpdateIntent.compareAndSet(current, current.copy(shouldPlay = shouldPlay))) return
        }
    }

    private fun shouldPlayAfterTrackUpdate(generation: Int): Boolean =
        trackUpdateIntent.get().let { it.generation == generation && it.shouldPlay }

    private fun queueTrack(mediaItem: MediaItem): Triple<String, String, String>? {
        val extras = mediaItem.mediaMetadata.extras
        val mediaId = mediaItem.mediaId
        val explicitVideoId = extras?.getString(YtPlayback.EXTRA_VIDEO_ID).orEmpty()
        val videoId = explicitVideoId.ifBlank {
            if (mediaId.startsWith("http")) {
                mediaId.substringAfter("v=", "").substringBefore("&")
            } else {
                mediaId
            }
        }
        val watchUrl = extras?.getString(YtPlayback.EXTRA_WATCH_URL)
            ?.takeIf { it.isNotBlank() }
            ?: mediaId.takeIf { it.startsWith("http") }
            ?: videoId.takeIf { it.isNotBlank() }
                ?.let { YtPlayback.watchUrl(it) }
        if (videoId.isBlank() || watchUrl.isNullOrBlank()) return null
        return Triple(videoId, mediaItem.mediaMetadata.title?.toString().orEmpty(), watchUrl)
    }

    private fun detachQueueObserverOnMain() {
        val player = queuePlayer
        val listener = queueListener
        if (player != null && listener != null) {
            player.removeListener(listener)
        }
        queuePlayer = null
        queueListener = null
        observedQueueMediaId = null
    }

    private suspend fun attachQueueObserver(context: Context) =
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            detachQueueObserverOnMain()
            observedQueueMediaId = controller.currentMediaItem?.mediaId
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val item = mediaItem ?: return
                    if (activeDevice == null || _castState.value !is CastState.Casting) return
                    if (item.mediaId == observedQueueMediaId) return
                    observedQueueMediaId = item.mediaId
                    val (videoId, title, watchUrl) = queueTrack(item) ?: run {
                        Log.w(TAG, "Cannot map queue item ${item.mediaId} to a Sonos stream")
                        _castState.update {
                            CastState.Error("Sonos cannot play the selected queue item.")
                        }
                        return
                    }
                    // Sonos owns only one external URI at a time. Media3 remains the queue owner,
                    // and every transition replaces that URI through the generation-aware path.
                    controller.pause()
                    updateTrack(context.applicationContext, videoId, title, watchUrl)
                }
            }
            queuePlayer = controller
            queueListener = listener
            controller.addListener(listener)
            controller.pause()
        }

    private suspend fun prepareSonosStream(
        videoId: String,
        watchUrl: String,
    ): PreparedSonosStream {
        // The maintained extractors validate a real byte-range read before returning a stream.
        // Prefer that independently verified, anonymous source for Sonos: some InnerTube URLs
        // accept our tiny preflight but reject Sonos's later long-lived range request with 403.
        val extractorStream = if (watchUrl.isNotBlank()) {
            runCatching {
                NewPipeRepository.resolveVerifiedAudioStream(watchUrl)
            }.getOrNull()
        } else {
            null
        }
        val formatInfo = if (extractorStream == null && videoId.isNotBlank()) {
            runCatching {
                YtPlayerUtils.resolveAudioFormatInfo(videoId, sonosSafe = true)
            }.getOrNull()
        } else {
            null
        }
        // Browser/PoToken URLs depend on session headers that a separate Sonos speaker cannot
        // send, so never expose them through the proxy.
        val sonosFormat = formatInfo?.takeUnless { it.requiresWebSessionHeaders }
        return PreparedSonosStream(
            url = extractorStream?.url ?: sonosFormat?.url,
            mimeType = sonosFormat?.mimeType?.substringBefore(";")?.trim() ?: "audio/mp4",
            userAgent = extractorStream?.userAgent
                ?: sonosFormat?.userAgent
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
                withContext(Dispatchers.Main) { detachQueueObserverOnMain() }
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

                    val observerError = runCatching {
                        attachQueueObserver(context.applicationContext)
                    }.exceptionOrNull()
                    if (observerError != null) {
                        Log.w(TAG, "Could not attach Sonos queue controls", observerError)
                        activeDevice = null
                        appContext = null
                        _isSonosPlaying.value = false
                        SonosController.stop(device)
                        SonosProxyServer.stop()
                        _castState.update {
                            CastState.Error("Sonos connected, but queue controls could not be started.")
                        }
                        return@launch
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
        scope.launch(Dispatchers.Main) { detachQueueObserverOnMain() }
        SonosProxyServer.stop()
        _castState.update { CastState.Idle }
        startDiscovery(context)
    }

    fun pause() {
        val device = activeDevice ?: return
        _isSonosPlaying.value = false
        scope.launch {
            // Keep pause in the same ordered lane as URI replacement. If SetURI/Play is already
            // blocking, this command runs immediately afterwards and is therefore the final
            // speaker state rather than being overtaken by a late Play.
            trackUpdateMutex.withLock {
                setTrackUpdatePlaybackIntent(shouldPlay = false)
                SonosController.pause(device)
            }
        }
    }

    fun resume() {
        val device = activeDevice ?: return
        _isSonosPlaying.value = true
        scope.launch {
            // A resume received while a replacement is loading is ordered after it, so it can
            // only play the new URI and never revive the old speaker track.
            trackUpdateMutex.withLock {
                setTrackUpdatePlaybackIntent(shouldPlay = true)
                SonosController.play(device)
            }
        }
    }

    fun updateTrack(context: Context, videoId: String, title: String, watchUrl: String) {
        val device = activeDevice ?: return
        trackUpdateJob?.cancel()
        val updateGeneration = trackUpdateGeneration.incrementAndGet()
        trackUpdateIntent.set(TrackUpdateIntent(updateGeneration, shouldPlay = true))
        _isSonosTrackUpdating.value = true
        // Reset UI state immediately so the player shows 0:00 for the new track
        // rather than stale position/duration from the previous one.
        _sonosPositionMs.value = 0L
        _sonosDurationMs.value = 0L
        _isSonosPlaying.value = false
        trackUpdateJob = scope.launch {
            trackUpdateMutex.withLock {
                try {
                val localIp = SonosDiscovery.localIp(context) ?: return@withLock
                currentCoroutineContext().ensureActive()
                if (!isCurrentTrackUpdate(updateGeneration)) return@withLock

                // Use resolveAudioFormatInfo (same as connect()) so we also get the mimeType,
                // which the proxy needs for correct Content-Type in HEAD responses.
                val preparedStream = withTimeoutOrNull(STREAM_PREPARATION_TIMEOUT_MS) {
                    prepareSonosStream(videoId, watchUrl)
                }
                currentCoroutineContext().ensureActive()
                if (!isCurrentTrackUpdate(updateGeneration)) return@withLock
                val prepared = preparedStream?.takeIf { it.url != null }
                if (prepared == null) {
                    Log.w(TAG, "updateTrack could not prepare a stream for $videoId")
                    _castState.update {
                        CastState.Error(
                            "Sonos cannot play this track: unable to prepare a playable audio stream.",
                        )
                    }
                    return@withLock
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
                currentCoroutineContext().ensureActive()
                if (!isCurrentTrackUpdate(updateGeneration)) return@withLock
                val verifiedTrack = (preflight as? SonosProxyServer.TrackPreflight.Ready)?.track
                if (verifiedTrack == null) {
                    val reason = (preflight as? SonosProxyServer.TrackPreflight.Failed)?.message
                        ?: "The audio source could not be verified."
                    Log.w(TAG, "updateTrack preflight failed: $reason")
                    _castState.update { CastState.Error("Sonos cannot play this track: $reason") }
                    return@withLock
                }

                // start() calls stop() internally which clears currentTrack — must call
                // start() FIRST, then setTrack(), so Sonos's HEAD probe after setUri finds
                // the track rather than getting a 503 No Track Set response.
                val proxyUrl = SonosProxyServer.start(localIp)
                if (!isCurrentTrackUpdate(updateGeneration)) {
                    SonosProxyServer.stop()
                    return@withLock
                }
                SonosProxyServer.setTrack(verifiedTrack)
                if (!isCurrentTrackUpdate(updateGeneration)) {
                    SonosProxyServer.stop()
                    return@withLock
                }

                // Stop Sonos before SetAVTransportURI — some firmware versions reject the
                // command while the transport is in PLAYING state, which causes a silent
                // failure and leaves the player stuck at 0:00 with nothing loading.
                SonosController.stop(device)
                if (!isCurrentTrackUpdate(updateGeneration)) return@withLock

                var uriError = SonosController.setUri(device, proxyUrl, title, verifiedTrack.mimeType)
                currentCoroutineContext().ensureActive()
                if (!isCurrentTrackUpdate(updateGeneration)) return@withLock
                if (uriError != null) {
                    Log.w(TAG, "updateTrack setUri failed ($uriError), retrying after 1.5 s")
                    delay(1_500L)
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentTrackUpdate(updateGeneration)) return@withLock
                    SonosController.stop(device)
                    uriError = SonosController.setUri(device, proxyUrl, title, verifiedTrack.mimeType)
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentTrackUpdate(updateGeneration)) return@withLock
                }

                if (uriError == null) {
                    if (shouldPlayAfterTrackUpdate(updateGeneration) && SonosController.play(device)) {
                        _isSonosPlaying.value = true
                    } else {
                        _isSonosPlaying.value = false
                    }
                    // Preserve the device reference and display name from the current cast state.
                    _castState.update { cs ->
                        if (cs is CastState.Casting) cs.copy(title = title) else cs
                    }
                } else {
                    Log.w(TAG, "updateTrack setUri failed after retry: $uriError")
                    _castState.update { CastState.Error("Sonos cannot play this track: $uriError") }
                }
                } finally {
                    if (trackUpdateGeneration.get() == updateGeneration) {
                        _isSonosTrackUpdating.value = false
                        trackUpdateJob = null
                    }
                }
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

    fun disconnect(resumeOnPhone: Boolean = true) {
        trackUpdateGeneration.incrementAndGet()
        trackUpdateJob?.cancel()
        trackUpdateJob = null
        _isSonosTrackUpdating.value = false
        pollingJob?.cancel()
        pollingJob = null
        _isSonosPlaying.value = false
        _sonosPositionMs.value = 0L
        _sonosDurationMs.value = 0L
        val device = activeDevice
        val ctx    = appContext
        activeDevice = null
        appContext    = null
        scope.launch(Dispatchers.Main) { detachQueueObserverOnMain() }
        SonosProxyServer.stop()
        if (device != null) scope.launch { SonosController.stop(device) }
        _castState.update { CastState.Idle }
        _devices.value    = emptyList()
        _sonosVolume.value = 50

        if (resumeOnPhone && ctx != null) {
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
