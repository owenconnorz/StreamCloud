package com.streamcloud.app.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouter
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.SessionManagerListener
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.cast.dlna.DlnaController
import com.streamcloud.app.cast.dlna.DlnaDevice
import com.streamcloud.app.cast.dlna.DlnaRepository
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends the current music item to the same publicly-supported remote destinations exposed in the
 * picker. This deliberately uses the local proxy for remote devices, so signed audio URLs and
 * their resolver identity never have to be understood by a speaker or television.
 */
object MusicRemoteCast {
    enum class DestinationType { GoogleCast, Dlna }

    sealed interface State {
        data object Idle : State
        data class Connecting(val destination: DestinationType, val name: String) : State
        data class Casting(
            val destination: DestinationType,
            val name: String,
            val title: String,
            val artist: String = "",
            val album: String = "",
            val artworkUrl: String? = null,
        ) : State
        data class Error(val message: String) : State
    }

    private data class PreparedAudio(
        val url: String,
        val mimeType: String,
        val userAgent: String,
    )

    private sealed interface Destination {
        data class Google(
            val session: CastSession,
            val routeId: String,
            val name: String,
        ) : Destination
        data class Dlna(val device: DlnaDevice) : Destination
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _remoteState = MutableStateFlow<RemotePlaybackState?>(null)
    val remoteState: StateFlow<RemotePlaybackState?> = _remoteState.asStateFlow()

    private var activeDestination: Destination? = null
    private var activeContext: Context? = null
    private var pendingGoogleDestination: Destination.Google? = null
    private var pendingGoogleContext: Context? = null
    private var activeGoogleCastContext: CastContext? = null
    private var activeGoogleSessionListener: SessionManagerListener<CastSession>? = null
    private var observedMusicController: Player? = null
    private var musicQueueListener: Player.Listener? = null
    private var connectJob: Job? = null
    private var remotePollJob: Job? = null
    private val transitionGeneration = AtomicLong(0L)
    private val transitionMutex = Mutex()

    fun connectGoogle(
        context: Context,
        route: MediaRouter.RouteInfo,
        videoId: String,
        title: String,
        watchUrl: String,
        artist: String = "",
        album: String = "",
        artworkUrl: String? = null,
    ) {
        val generation = transitionGeneration.incrementAndGet()
        connectJob?.cancel()
        _state.value = State.Connecting(DestinationType.GoogleCast, route.name.toString())
        connectJob = scope.launch {
            transitionMutex.withLock {
                stopActiveDestination()
                CastProxyServer.stop()
                if (!isCurrent(generation)) return@launch
                val source = prepareAudio(videoId, watchUrl)
                    ?: return@launch fail(generation, "Unable to prepare this track for Google Cast.")
                val appContext = context.applicationContext
                val session = awaitSelectedGoogleSession(appContext, route)
                    ?: return@launch fail(generation, "Couldn't connect to ${route.name}.")
                var committed = false
                try {
                    if (!isCurrent(generation)) return@launch
                    val streamUrl = startProxy(source)
                        ?: return@launch fail(generation, "A local Cast connection is required for this track.")
                    withContext(Dispatchers.Main) {
                        if (!isCurrent(generation)) return@withContext
                        loadRemoteMedia(
                            session = session,
                            streamUrl = streamUrl,
                            title = title,
                            artist = artist,
                            album = album,
                            artworkUrl = artworkUrl,
                            contentType = source.mimeType,
                            mediaType = com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK,
                        )
                        MusicController.get(appContext).pause()
                    }
                    if (!isCurrent(generation)) return@launch
                    activeContext = appContext
                    pendingGoogleDestination = null
                    pendingGoogleContext = null
                    activeDestination = Destination.Google(session, route.id, route.name.toString())
                    observeMusicQueue(appContext)
                    observeActiveGoogleSession(appContext, session)
                    publishTrack(
                        destination = DestinationType.GoogleCast,
                        deviceName = route.name.toString(),
                        title = title,
                        artist = artist,
                        album = album,
                        artworkUrl = artworkUrl,
                    )
                    _state.value = State.Casting(
                        destination = DestinationType.GoogleCast,
                        name = route.name.toString(),
                        title = title,
                        artist = artist,
                        album = album,
                        artworkUrl = artworkUrl,
                    )
                    startRemotePolling()
                    committed = true
                } finally {
                    if (!committed) {
                        withContext(NonCancellable) {
                            stopGoogleSession(appContext, session, route.id)
                        }
                    }
                }
            }
        }
    }

    fun connectDlna(
        context: Context,
        device: DlnaDevice,
        videoId: String,
        title: String,
        watchUrl: String,
        artist: String = "",
        album: String = "",
        artworkUrl: String? = null,
    ) {
        val generation = transitionGeneration.incrementAndGet()
        connectJob?.cancel()
        _state.value = State.Connecting(DestinationType.Dlna, device.name)
        connectJob = scope.launch {
            transitionMutex.withLock {
                stopActiveDestination()
                CastProxyServer.stop()
                if (!isCurrent(generation)) return@launch
                val source = prepareAudio(videoId, watchUrl)
                    ?: return@launch fail(generation, "Unable to prepare this track for ${device.name}.")
                val streamUrl = startProxy(source)
                    ?: return@launch fail(generation, "A local network connection is required for ${device.name}.")
                DlnaRepository.resetPlaybackState()
                val loaded = DlnaController.setUri(
                    device = device,
                    streamUrl = streamUrl,
                    title = title,
                    mimeType = source.mimeType,
                    artist = artist,
                    album = album,
                    artworkUrl = artworkUrl,
                )
                if (!loaded || !DlnaController.play(device)) {
                    CastProxyServer.stop()
                    return@launch fail(generation, "${device.name} could not start this track.")
                }
                if (!isCurrent(generation)) return@launch
                DlnaRepository.selectDevice(device)
                DlnaRepository.startPolling()
                MusicController.get(context.applicationContext).pause()
                activeContext = context.applicationContext
                activeDestination = Destination.Dlna(device)
                observeMusicQueue(context.applicationContext)
                publishTrack(
                    destination = DestinationType.Dlna,
                    deviceName = device.name,
                    title = title,
                    artist = artist,
                    album = album,
                    artworkUrl = artworkUrl,
                )
                _state.value = State.Casting(
                    destination = DestinationType.Dlna,
                    name = device.name,
                    title = title,
                    artist = artist,
                    album = album,
                    artworkUrl = artworkUrl,
                )
                startRemotePolling()
            }
        }
    }

    /**
     * Called when the music queue advances so a selected network destination stays in sync.
     * Bluetooth is intentionally excluded: Android routes normal Media3 audio to it directly.
     */
    fun updateTrack(
        context: Context,
        videoId: String,
        title: String,
        watchUrl: String,
        artist: String = "",
        album: String = "",
        artworkUrl: String? = null,
    ) {
        connectJob = scope.launch {
            transitionMutex.withLock {
                val destination = activeDestination ?: return@withLock
                if (_state.value !is State.Casting) return@withLock
                val generation = transitionGeneration.incrementAndGet()
                val source = prepareAudio(videoId, watchUrl)
                    ?: return@launch fail(generation, "Unable to prepare the next track for casting.")
                if (
                    !isCurrent(generation) ||
                    activeDestination != destination ||
                    _state.value !is State.Casting
                ) return@withLock
                when (destination) {
                    is Destination.Google -> {
                        val appContext = context.applicationContext
                        val session = withContext(Dispatchers.Main) {
                            val router = MediaRouter.getInstance(appContext)
                            destination.session.takeIf {
                                router.selectedRoute?.id == destination.routeId &&
                                    it.remoteMediaClient != null
                            }
                        } ?: return@launch fail(generation, "Google Cast session ended.")
                        if (activeDestination != destination) return@withLock
                        val streamUrl = startProxy(source)
                            ?: return@launch fail(generation, "A local Cast connection is required for the next track.")
                        withContext(Dispatchers.Main) {
                            if (
                                !isCurrent(generation) ||
                                activeDestination != destination
                            ) return@withContext
                            loadRemoteMedia(
                                session = session,
                                streamUrl = streamUrl,
                                title = title,
                                artist = artist,
                                album = album,
                                artworkUrl = artworkUrl,
                                contentType = source.mimeType,
                                mediaType = com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK,
                            )
                            MusicController.get(appContext).pause()
                        }
                        if (
                            !isCurrent(generation) ||
                            activeDestination != destination
                        ) return@withLock
                        publishTrack(
                            destination = DestinationType.GoogleCast,
                            deviceName = destination.name,
                            title = title,
                            artist = artist,
                            album = album,
                            artworkUrl = artworkUrl,
                        )
                        _state.value = State.Casting(
                            destination = DestinationType.GoogleCast,
                            name = destination.name,
                            title = title,
                            artist = artist,
                            album = album,
                            artworkUrl = artworkUrl,
                        )
                    }

                    is Destination.Dlna -> {
                        if (activeDestination != destination) return@withLock
                        val streamUrl = startProxy(source)
                            ?: return@launch fail(
                                generation,
                                "A local network connection is required for the next track.",
                            )
                        DlnaRepository.resetPlaybackState()
                        val loaded = DlnaController.setUri(
                            device = destination.device,
                            streamUrl = streamUrl,
                            title = title,
                            mimeType = source.mimeType,
                            artist = artist,
                            album = album,
                            artworkUrl = artworkUrl,
                        )
                        if (loaded && DlnaController.play(destination.device)) {
                            if (
                                !isCurrent(generation) ||
                                activeDestination != destination
                            ) return@withLock
                            MusicController.get(context.applicationContext).pause()
                            publishTrack(
                                destination = DestinationType.Dlna,
                                deviceName = destination.device.name,
                                title = title,
                                artist = artist,
                                album = album,
                                artworkUrl = artworkUrl,
                            )
                            _state.value = State.Casting(
                                destination = DestinationType.Dlna,
                                name = destination.device.name,
                                title = title,
                                artist = artist,
                                album = album,
                                artworkUrl = artworkUrl,
                            )
                        } else {
                            CastProxyServer.stop()
                            fail(generation, "${destination.device.name} could not start the next track.")
                        }
                    }
                }
            }
        }
    }

    fun disconnect(resumeOnPhone: Boolean = true) {
        val generation = transitionGeneration.incrementAndGet()
        connectJob?.cancel()
        scope.launch {
            transitionMutex.withLock {
                val context = activeContext
                stopActiveDestination()
                if (!isCurrent(generation)) return@launch
                CastProxyServer.stop()
                _state.value = State.Idle
                if (resumeOnPhone && context != null) {
                    withContext(Dispatchers.Main) {
                        MusicController.get(context).play()
                    }
                }
            }
        }
    }

    /**
     * Receiver transport commands. Keeping these here prevents a Compose screen from accidentally
     * toggling the paused Media3 phone player while a network destination owns playback.
     */
    fun togglePlayPause() {
        val destination = activeDestination ?: return
        val generation = transitionGeneration.get()
        scope.launch {
            transitionMutex.withLock {
                if (
                    !isCurrent(generation) ||
                    activeDestination != destination ||
                    _state.value !is State.Casting
                ) return@withLock
                when (destination) {
                    is Destination.Google -> withContext(Dispatchers.Main) {
                        destination.session.remoteMediaClient?.let { client ->
                            if (_remoteState.value?.isPlaying == true) client.pause() else client.play()
                        }
                    }
                    is Destination.Dlna -> {
                        val device = destination.device
                        if (_remoteState.value?.isPlaying == true) DlnaController.pause(device)
                        else DlnaController.play(device)
                    }
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val destination = activeDestination ?: return
        val generation = transitionGeneration.get()
        val target = positionMs.coerceAtLeast(0L)
        scope.launch {
            transitionMutex.withLock {
                if (
                    !isCurrent(generation) ||
                    activeDestination != destination ||
                    _state.value !is State.Casting
                ) return@withLock
                _remoteState.value = _remoteState.value?.copy(positionMs = target)
                when (destination) {
                    is Destination.Google -> withContext(Dispatchers.Main) {
                        destination.session.remoteMediaClient?.seek(
                            com.google.android.gms.cast.MediaSeekOptions.Builder()
                                .setPosition(target)
                                .build(),
                        )
                    }
                    is Destination.Dlna -> DlnaController.seek(destination.device, target)
                }
            }
        }
    }

    fun switchToBluetooth(
        context: Context,
        route: MediaRouter.RouteInfo,
        onSelected: () -> Unit,
    ) {
        val generation = transitionGeneration.incrementAndGet()
        connectJob?.cancel()
        scope.launch {
            transitionMutex.withLock {
                stopActiveDestination()
                if (!isCurrent(generation)) return@launch
                CastProxyServer.stop()
                _state.value = State.Idle
                withContext(Dispatchers.Main) {
                    if (!isCurrent(generation)) return@withContext
                    MediaRouter.getInstance(context.applicationContext).selectRoute(route)
                    MusicController.get(context.applicationContext).play()
                    onSelected()
                }
            }
        }
    }

    fun handOffToSonos(onReady: () -> Unit) {
        val generation = transitionGeneration.incrementAndGet()
        connectJob?.cancel()
        scope.launch {
            transitionMutex.withLock {
                stopActiveDestination()
                if (!isCurrent(generation)) return@launch
                CastProxyServer.stop()
                _state.value = State.Idle
                withContext(Dispatchers.Main) {
                    if (isCurrent(generation)) onReady()
                }
            }
        }
    }

    private suspend fun prepareAudio(videoId: String, watchUrl: String): PreparedAudio? {
        val resolved = YtPlayerUtils.resolveAudioFormatInfo(videoId, sonosSafe = true)
            ?.takeUnless { it.requiresWebSessionHeaders }
        if (resolved != null) {
            return PreparedAudio(
                url = resolved.url,
                mimeType = resolved.mimeType.substringBefore(";").trim(),
                userAgent = resolved.userAgent,
            )
        }
        val extracted = watchUrl.takeIf { it.isNotBlank() }
            ?.let { NewPipeRepository.resolveVerifiedAudioStream(it) }
            ?: return null
        return PreparedAudio(
            url = extracted.url,
            mimeType = "audio/mp4",
            userAgent = extracted.userAgent,
        )
    }

    private fun startProxy(source: PreparedAudio): String? =
        CastProxyServer.start(
            source.url,
            headers = mapOf("User-Agent" to source.userAgent),
        )

    private suspend fun stopActiveDestination() {
        val destination = activeDestination
        val context = activeContext
        val pendingGoogle = pendingGoogleDestination
        val pendingContext = pendingGoogleContext
        activeDestination = null
        activeContext = null
        remotePollJob?.cancel()
        remotePollJob = null
        _remoteState.value = null
        removeMusicQueueObserver()
        removeActiveGoogleSessionListener()
        pendingGoogleDestination = null
        pendingGoogleContext = null
        when (destination) {
            is Destination.Google -> if (context != null) {
                stopGoogleSession(context, destination.session, destination.routeId)
            }

            is Destination.Dlna -> {
                runCatching { DlnaController.stop(destination.device) }
                DlnaRepository.selectDevice(null)
                DlnaRepository.stopPolling()
            }

            null -> Unit
        }
        if (destination !is Destination.Google && pendingGoogle != null && pendingContext != null) {
            stopGoogleSession(pendingContext, pendingGoogle.session, pendingGoogle.routeId)
        }
    }

    private fun isCurrent(generation: Long): Boolean =
        transitionGeneration.get() == generation

    private fun publishTrack(
        destination: DestinationType,
        deviceName: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
    ) {
        _remoteState.value = RemotePlaybackState(
            destination = destination,
            deviceName = deviceName,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
        )
    }

    private fun startRemotePolling() {
        remotePollJob?.cancel()
        remotePollJob = scope.launch {
            while (true) {
                when (val destination = activeDestination) {
                    is Destination.Google -> {
                        val snapshot = withContext(Dispatchers.Main) {
                            destination.session.remoteMediaClient?.let { client ->
                                val status = client.mediaStatus ?: return@withContext null
                                val previous = _remoteState.value ?: return@withContext null
                                val phase = when (status.playerState) {
                                    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING ->
                                        RemotePlaybackPhase.Playing
                                    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PAUSED ->
                                        RemotePlaybackPhase.Paused
                                    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING ->
                                        RemotePlaybackPhase.Buffering
                                    com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE ->
                                        RemotePlaybackPhase.Idle
                                    else -> RemotePlaybackPhase.Unknown
                                }
                                previous.withReceiverSample(
                                    positionMs = status.streamPosition,
                                    durationMs = client.mediaInfo?.streamDuration,
                                    phase = phase,
                                )
                            }
                        }
                        if (snapshot != null) _remoteState.value = snapshot
                    }
                    is Destination.Dlna -> {
                        val previous = _remoteState.value
                        if (previous != null) {
                            _remoteState.value = previous.withReceiverSample(
                                positionMs = DlnaRepository.positionMs.value,
                                durationMs = DlnaRepository.durationMs.value,
                                phase = if (DlnaRepository.isPlaying.value) {
                                    RemotePlaybackPhase.Playing
                                } else {
                                    RemotePlaybackPhase.Paused
                                },
                            )
                        }
                    }
                    null -> return@launch
                }
                kotlinx.coroutines.delay(500L)
            }
        }
    }

    private suspend fun observeActiveGoogleSession(context: Context, session: CastSession) {
        removeActiveGoogleSessionListener()
        val castContext = withContext(Dispatchers.Main) {
            runCatching { CastContext.getSharedInstance(context) }.getOrNull()
        } ?: return
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionSuspended(suspended: CastSession, reason: Int) {
                if (suspended != session) return
                scope.launch {
                    transitionMutex.withLock {
                        val active = activeDestination as? Destination.Google
                        if (active?.session != suspended) return@withLock
                        remotePollJob?.cancel()
                        remotePollJob = null
                        _remoteState.value = _remoteState.value?.copy(
                            isPlaying = false,
                            isBuffering = true,
                        )
                        _state.value = State.Connecting(DestinationType.GoogleCast, active.name)
                    }
                }
            }

            override fun onSessionResumed(resumed: CastSession, wasSuspended: Boolean) {
                if (resumed != session) return
                scope.launch {
                    transitionMutex.withLock {
                        val active = activeDestination as? Destination.Google
                        val playback = _remoteState.value
                        if (active?.session != resumed || playback == null) return@withLock
                        _state.value = State.Casting(
                            destination = DestinationType.GoogleCast,
                            name = active.name,
                            title = playback.title,
                            artist = playback.artist,
                            album = playback.album,
                            artworkUrl = playback.artworkUrl,
                        )
                        startRemotePolling()
                    }
                }
            }

            override fun onSessionEnded(ended: CastSession, error: Int) {
                if (ended != session) return
                scope.launch {
                    transitionMutex.withLock {
                        val active = activeDestination as? Destination.Google
                        if (active?.session != ended) return@withLock
                        activeDestination = null
                        activeContext = null
                        remotePollJob?.cancel()
                        remotePollJob = null
                        _remoteState.value = null
                        _state.value = State.Idle
                        CastProxyServer.stop()
                        removeMusicQueueObserver()
                        removeActiveGoogleSessionListener()
                    }
                }
            }

            override fun onSessionStarted(started: CastSession, sessionId: String) = Unit
            override fun onSessionStarting(starting: CastSession) = Unit
            override fun onSessionStartFailed(failed: CastSession, error: Int) = Unit
            override fun onSessionEnding(ending: CastSession) = Unit
            override fun onSessionResuming(resuming: CastSession, sessionId: String) = Unit
            override fun onSessionResumeFailed(failed: CastSession, error: Int) {
                if (failed != session) return
                scope.launch {
                    transitionMutex.withLock {
                        val active = activeDestination as? Destination.Google
                        if (active?.session != failed) return@withLock
                        activeDestination = null
                        activeContext = null
                        remotePollJob?.cancel()
                        remotePollJob = null
                        _remoteState.value = null
                        _state.value = State.Error("Google Cast connection was lost.")
                        CastProxyServer.stop()
                        removeMusicQueueObserver()
                        removeActiveGoogleSessionListener()
                    }
                }
            }
        }
        withContext(Dispatchers.Main) {
            castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        }
        activeGoogleCastContext = castContext
        activeGoogleSessionListener = listener
    }

    private suspend fun removeActiveGoogleSessionListener() {
        val castContext = activeGoogleCastContext
        val listener = activeGoogleSessionListener
        activeGoogleCastContext = null
        activeGoogleSessionListener = null
        if (castContext != null && listener != null) {
            withContext(Dispatchers.Main) {
                castContext.sessionManager.removeSessionManagerListener(
                    listener,
                    CastSession::class.java,
                )
            }
        }
    }

    private suspend fun observeMusicQueue(context: Context) {
        removeMusicQueueObserver()
        val controller = MusicController.get(context)
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val item = mediaItem ?: return
                if (activeDestination == null) return
                controller.pause()
                val mediaId = item.mediaId
                val videoId = if (mediaId.startsWith("http")) {
                    mediaId.substringAfter("v=", "").substringBefore("&")
                } else {
                    mediaId
                }
                val watchUrl = if (mediaId.startsWith("http")) {
                    mediaId
                } else {
                    "https://music.youtube.com/watch?v=$mediaId"
                }
                updateTrack(
                    context = context,
                    videoId = videoId,
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    watchUrl = watchUrl,
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                    album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                    artworkUrl = item.mediaMetadata.artworkUri?.toString(),
                )
            }
        }
        withContext(Dispatchers.Main) {
            controller.addListener(listener)
        }
        observedMusicController = controller
        musicQueueListener = listener
    }

    private suspend fun removeMusicQueueObserver() {
        val controller = observedMusicController
        val listener = musicQueueListener
        observedMusicController = null
        musicQueueListener = null
        if (controller != null && listener != null) {
            withContext(Dispatchers.Main) {
                controller.removeListener(listener)
            }
        }
    }

    private fun fail(generation: Long, message: String) {
        if (isCurrent(generation)) _state.value = State.Error(message)
    }

    private suspend fun stopGoogleSession(
        context: Context,
        session: CastSession,
        routeId: String,
    ) {
        withContext(Dispatchers.Main) {
            if (pendingGoogleDestination?.session == session) {
                pendingGoogleDestination = null
                pendingGoogleContext = null
            }
            session.remoteMediaClient?.stop()
            val router = MediaRouter.getInstance(context)
            if (router.selectedRoute?.id == routeId) {
                router.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
            }
        }
    }

    private suspend fun awaitSelectedGoogleSession(
        context: Context,
        route: MediaRouter.RouteInfo,
    ): CastSession? {
        val sessionResult = CompletableDeferred<CastSession?>()
        val castContext = withContext(Dispatchers.Main) {
            runCatching { CastContext.getSharedInstance(context) }.getOrNull()
        } ?: return null
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                if (MediaRouter.getInstance(context).selectedRoute?.id == route.id) {
                    pendingGoogleDestination = Destination.Google(
                        session = session,
                        routeId = route.id,
                        name = route.name.toString(),
                    )
                    pendingGoogleContext = context
                    sessionResult.complete(session)
                }
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                if (MediaRouter.getInstance(context).selectedRoute?.id == route.id) {
                    pendingGoogleDestination = Destination.Google(
                        session = session,
                        routeId = route.id,
                        name = route.name.toString(),
                    )
                    pendingGoogleContext = context
                    sessionResult.complete(session)
                }
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                sessionResult.complete(null)
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                sessionResult.complete(null)
            }

            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionEnded(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        }
        withContext(Dispatchers.Main) {
            castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
            MediaRouter.getInstance(context).selectRoute(route)
        }
        var selectedSession: CastSession? = null
        return try {
            withTimeoutOrNull(8_000L) { sessionResult.await() }
                .also { selectedSession = it }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    castContext.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
                }
                if (selectedSession == null) {
                    val pending = pendingGoogleDestination?.takeIf { it.routeId == route.id }
                    if (pending != null) {
                        stopGoogleSession(context, pending.session, pending.routeId)
                    } else {
                        withContext(Dispatchers.Main) {
                            val router = MediaRouter.getInstance(context)
                            if (router.selectedRoute?.id == route.id) {
                                router.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
                            }
                        }
                    }
                }
            }
        }
    }
}