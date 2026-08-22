package com.streamcloud.app.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouter
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
        data class Casting(val destination: DestinationType, val name: String, val title: String) : State
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

    private var activeDestination: Destination? = null
    private var activeContext: Context? = null
    private var pendingGoogleDestination: Destination.Google? = null
    private var pendingGoogleContext: Context? = null
    private var connectJob: Job? = null
    private val transitionGeneration = AtomicLong(0L)
    private val transitionMutex = Mutex()

    fun connectGoogle(
        context: Context,
        route: MediaRouter.RouteInfo,
        videoId: String,
        title: String,
        watchUrl: String,
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
                            artworkUrl = null,
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
                    _state.value = State.Casting(DestinationType.GoogleCast, route.name.toString(), title)
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
                val loaded = DlnaController.setUri(device, streamUrl, title, source.mimeType)
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
                _state.value = State.Casting(DestinationType.Dlna, device.name, title)
            }
        }
    }

    /**
     * Called when the music queue advances so a selected network destination stays in sync.
     * Bluetooth is intentionally excluded: Android routes normal Media3 audio to it directly.
     */
    fun updateTrack(context: Context, videoId: String, title: String, watchUrl: String) {
        val destination = activeDestination ?: return
        val generation = transitionGeneration.incrementAndGet()
        connectJob?.cancel()
        connectJob = scope.launch {
            transitionMutex.withLock {
                val source = prepareAudio(videoId, watchUrl)
                    ?: return@launch fail(generation, "Unable to prepare the next track for casting.")
                if (!isCurrent(generation)) return@launch
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
                        val streamUrl = startProxy(source)
                            ?: return@launch fail(generation, "A local Cast connection is required for the next track.")
                        withContext(Dispatchers.Main) {
                            if (!isCurrent(generation)) return@withContext
                            loadRemoteMedia(
                                session = session,
                                streamUrl = streamUrl,
                                title = title,
                                artworkUrl = null,
                                contentType = source.mimeType,
                                mediaType = com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK,
                            )
                            MusicController.get(appContext).pause()
                        }
                        if (!isCurrent(generation)) return@launch
                        _state.value = State.Casting(DestinationType.GoogleCast, destination.name, title)
                    }

                    is Destination.Dlna -> {
                        val streamUrl = startProxy(source)
                            ?: return@launch fail(
                                generation,
                                "A local network connection is required for the next track.",
                            )
                        val loaded = DlnaController.setUri(
                            destination.device,
                            streamUrl,
                            title,
                            source.mimeType,
                        )
                        if (loaded && DlnaController.play(destination.device)) {
                            MusicController.get(context.applicationContext).pause()
                            _state.value = State.Casting(DestinationType.Dlna, destination.device.name, title)
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