package com.streamcloud.app.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.torrent.TorrentService
import com.streamcloud.app.torrent.TorrentState
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.tvFocusBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun NativePlayerScreen(
    streamUrl: String,
    title: String,
    headers: Map<String, String> = emptyMap(),
    onBack: () -> Unit,

    subtitle: String? = null,

    sources: List<PlayerSource> = emptyList(),
    selectedSourceId: String? = null,
    onSwitchSource: ((PlayerSource) -> Unit)? = null,

    progressKey: WatchProgressKey? = null,
    artworkUrl: String? = null,

    onRefresh: (() -> Unit)? = null,
    nuvioScanning: Boolean = false,

    restartKey: Any? = null,
    forceDirectPlay: Boolean = false,

    // ── Series / binge ────────────────────────────────────────────────────
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeTitle: String? = null,
    bingeEpisodes: List<BingeEpisode> = emptyList(),
    currentBingeIndex: Int = -1,
    onPlayBingeEpisode: ((BingeEpisode) -> Unit)? = null,

    // ── Addon subtitles ───────────────────────────────────────────────────
    addonSubtitles: List<AddonSubtitle> = emptyList(),

    // ── Per-provider stream errors ────────────────────────────────────────
    sourceErrors: Map<String, String> = emptyMap(),
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sl = remember(context) { ServiceLocator.get(context) }

    var resolvedUrl by remember { mutableStateOf<String?>(null) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    val torrentService = sl.torrentService
    val torrentState by torrentService.state.collectAsState()

    val failedSourceUrls = remember(streamUrl, restartKey) { mutableStateOf<Set<String>>(emptySet()) }
    val autoSwitchIdx    = remember(streamUrl, restartKey) { mutableStateOf(-1) }
    var autoSwitchBanner by remember { mutableStateOf<String?>(null) }
    val activeAutoSource = if (autoSwitchIdx.value >= 0) sources.getOrNull(autoSwitchIdx.value) else null
    val effectiveUrl     = activeAutoSource?.url ?: streamUrl
    val effectiveHeaders = activeAutoSource?.headers?.takeIf { it.isNotEmpty() } ?: headers

    LaunchedEffect(effectiveUrl, restartKey) {
        resolvedUrl  = null
        resolveError = null
        val isTorrent = effectiveUrl.startsWith("magnet:", true) ||
            effectiveUrl.contains("&_sc_fidx=", ignoreCase = true) ||
            effectiveUrl.endsWith(".torrent", true)
        if (isTorrent) {
            var lastError: String? = null
            val proxied = withContext(Dispatchers.IO) {
                try { torrentService.startStreamFromMagnet(effectiveUrl) }
                catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    null
                }
            }
            if (proxied == null) resolveError = "Could not start torrent stream.\n\n${lastError ?: "Unknown error"}"
            else resolvedUrl = proxied
        } else {
            resolvedUrl = effectiveUrl
        }
    }

    LaunchedEffect(autoSwitchBanner) {
        if (autoSwitchBanner != null) { delay(3_000); autoSwitchBanner = null }
    }

    val player = remember { mutableStateOf<ExoPlayer?>(null) }

    val isCastConnected by com.streamcloud.app.cast.rememberCastController(
        streamUrl  = resolvedUrl.orEmpty(),
        title      = title,
        artworkUrl = artworkUrl,
        headers    = effectiveHeaders,
    )
    val isDlnaCasting by com.streamcloud.app.cast.rememberDlnaCastController(
        streamUrl = resolvedUrl.orEmpty(),
        title     = title,
        headers   = effectiveHeaders,
    )
    val anyDeviceCasting = isCastConnected || isDlnaCasting

    LaunchedEffect(anyDeviceCasting) {
        val p = player.value ?: return@LaunchedEffect
        if (anyDeviceCasting) p.pause() else if (!p.isPlaying) p.play()
    }

    val needsWebView = remember(resolvedUrl, forceDirectPlay) {
        if (forceDirectPlay) false
        else {
            val u = resolvedUrl?.lowercase().orEmpty()
            u.isNotEmpty() && !u.startsWith("http://127.0.0.1") &&
                !u.endsWith(".mp4") && !u.endsWith(".mkv") && !u.endsWith(".webm") &&
                !u.endsWith(".m4v") && !u.endsWith(".mov") &&
                !u.contains(".m3u8") && !u.contains(".mpd") &&
                !u.startsWith("magnet:") &&
                (u.contains("/embed") || u.contains("/iframe") || u.contains("/video/") ||
                 u.endsWith(".html") || u.endsWith("/"))
        }
    }

    val seekIncrementSec    by sl.settings.seekIncrementSeconds.collectAsState(initial = "10")
    val defaultSpeedStr     by sl.settings.defaultPlaybackSpeed.collectAsState(initial = "1.0")
    val hwDecodingEnabled   by sl.settings.hardwareDecodingEnabled.collectAsState(initial = true)
    val gestureVolumeOn     by sl.settings.gestureVolumeEnabled.collectAsState(initial = true)
    val gestureBrightnessOn by sl.settings.gestureBrightnessEnabled.collectAsState(initial = true)
    val resumePlaybackOn    by sl.settings.resumePlayback.collectAsState(initial = true)
    val pipEnabledOn        by sl.settings.pipEnabled.collectAsState(initial = true)

    // ── Subtitle style ────────────────────────────────────────────────────
    var subtitleStyle by remember { mutableStateOf(SubtitleStylePrefs.load(context)) }
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }

    fun applySubtitleStyle(pv: PlayerView?, style: SubtitleStyleState) {
        val sv = pv?.subtitleView ?: return
        sv.setUserDefaultStyle()
        val alpha = (style.opacityFraction * 255).toInt().coerceIn(0, 255)
        val fgColor = (style.colorArgb and 0x00FFFFFF) or (alpha shl 24)
        sv.setStyle(
            CaptionStyleCompat(
                fgColor,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                if (style.outlineEnabled) CaptionStyleCompat.EDGE_TYPE_OUTLINE
                else CaptionStyleCompat.EDGE_TYPE_NONE,
                style.outlineColorArgb,
                if (style.bold) android.graphics.Typeface.DEFAULT_BOLD else null,
            )
        )
        sv.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp)
        sv.setBottomPaddingFraction(style.bottomOffsetFraction)
    }

    LaunchedEffect(subtitleStyle) { applySubtitleStyle(playerViewRef.value, subtitleStyle) }

    LaunchedEffect(resolvedUrl, needsWebView, restartKey) {
        player.value?.release()
        player.value = null
        if (needsWebView) return@LaunchedEffect
        val url = resolvedUrl ?: return@LaunchedEffect
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("StreamCloud/1.0 (ExoPlayer)")
            .also { f -> if (effectiveHeaders.isNotEmpty()) f.setDefaultRequestProperties(effectiveHeaders) }
        val dsFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)
        val mediaItem = MediaItem.fromUri(url)
        val source: MediaSource = when {
            url.contains(".m3u8", true) -> HlsMediaSource.Factory(dsFactory).createMediaSource(mediaItem)
            url.contains(".mpd", true)  -> DashMediaSource.Factory(dsFactory).createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(dsFactory).createMediaSource(mediaItem)
        }
        val videoAudioAttrs = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        val renderersFactory = if (!hwDecodingEnabled)
            androidx.media3.exoplayer.DefaultRenderersFactory(context)
                .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        else null
        val ex = ExoPlayer.Builder(context)
            .apply { if (renderersFactory != null) setRenderersFactory(renderersFactory) }
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .setAudioAttributes(videoAudioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaSource(source); prepare(); playWhenReady = true
                val defSpeed = defaultSpeedStr.toFloatOrNull() ?: 1f
                if (defSpeed != 1f) playbackParameters = PlaybackParameters(defSpeed)
            }
        val savedPosition = if (resumePlaybackOn) {
            progressKey?.let { pk ->
                runCatching {
                    com.streamcloud.app.data.library.LibraryDb.get(context.applicationContext)
                        .watchProgress().byId(pk.tmdbId)?.positionMs?.takeIf { it > 5_000L }
                }.getOrNull()
            }
        } else null
        if (savedPosition != null) ex.seekTo(savedPosition)
        player.value = ex
        applySubtitleStyle(playerViewRef.value, subtitleStyle)
    }

    val activity     = context as? Activity
    val window       = activity?.window
    var isLandscape  by remember { mutableStateOf(true) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volumeOverlay    by remember { mutableStateOf<Float?>(null) }
    var brightnessOverlay by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        val prevOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            activity?.requestedOrientation =
                prevOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(isLandscape) {
        activity?.requestedOrientation = if (isLandscape)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
    LaunchedEffect(volumeOverlay)     { if (volumeOverlay    != null) { delay(1500); volumeOverlay    = null } }
    LaunchedEffect(brightnessOverlay) { if (brightnessOverlay != null) { delay(1500); brightnessOverlay = null } }

    DisposableEffect(Unit) {
        onDispose { player.value?.release(); player.value = null; torrentService.stopStream() }
    }
    DisposableEffect(Unit) {
        VideoPlaybackService.start(context.applicationContext, title)
        onDispose { VideoPlaybackService.stop(context.applicationContext) }
    }
    DisposableEffect(player.value) {
        val currentEx = player.value ?: return@DisposableEffect onDispose {}
        val session = MediaSession.Builder(context.applicationContext, currentEx).setId("sc_video_player").build()
        onDispose { session.release() }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) { androidx.lifecycle.Lifecycle.Event.ON_STOP -> player.value?.pause(); else -> {} }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ex = player.value
    var isPlaying         by remember { mutableStateOf(true) }
    var positionMs        by remember { mutableStateOf(0L) }
    var durationMs        by remember { mutableStateOf(0L) }
    var controlsVisible   by remember { mutableStateOf(true) }
    var lastInteractionTs by remember { mutableStateOf(System.currentTimeMillis()) }
    var resizeMode        by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed     by remember { mutableStateOf(1f) }

    LaunchedEffect(ex) {
        ex ?: return@LaunchedEffect
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlaybackStateChanged(state: Int) { durationMs = ex.duration.coerceAtLeast(0L) }
            override fun onPlayerError(error: PlaybackException) {
                if (sources.size <= 1) return
                val failedUrl = activeAutoSource?.url ?: streamUrl
                failedSourceUrls.value = failedSourceUrls.value + failedUrl
                val failed = failedSourceUrls.value
                val nextIdx = sources.indexOfFirst { it.url !in failed }
                if (nextIdx >= 0) { autoSwitchBanner = "Source failed · trying ${sources[nextIdx].label}…"; autoSwitchIdx.value = nextIdx }
                else autoSwitchBanner = "All sources failed"
            }
        }
        ex.addListener(listener)
        while (true) {
            positionMs = ex.currentPosition.coerceAtLeast(0L)
            durationMs = ex.duration.coerceAtLeast(0L)
            isPlaying  = ex.isPlaying
            delay(500)
        }
    }

    var showCastDialog by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible, lastInteractionTs, anyDeviceCasting, showCastDialog) {
        if (controlsVisible && !anyDeviceCasting && !showCastDialog) {
            delay(5_000)
            if (System.currentTimeMillis() - lastInteractionTs >= 4_900) controlsVisible = false
        }
    }

    // Watch progress persistence
    if (progressKey != null) {
        val appContext = context.applicationContext
        LaunchedEffect(ex, progressKey.tmdbId) {
            ex ?: return@LaunchedEffect
            while (true) {
                delay(10_000)
                val pos = ex.currentPosition.coerceAtLeast(0L)
                val dur = ex.duration.coerceAtLeast(0L)
                if (dur > 0L && pos > 0L) {
                    runCatching {
                        com.streamcloud.app.data.library.LibraryDb.get(appContext)
                            .watchProgress().upsert(
                                com.streamcloud.app.data.library.WatchProgressEntity(
                                    tmdbId = progressKey.tmdbId, title = progressKey.title,
                                    posterUrl = progressKey.posterUrl, mediaType = progressKey.mediaType,
                                    positionMs = pos, durationMs = dur,
                                    updatedAt = System.currentTimeMillis(), sourceRoute = progressKey.sourceRoute,
                                )
                            )
                    }
                }
            }
        }
        DisposableEffect(progressKey.tmdbId) {
            onDispose {
                val cur = ex
                if (cur != null) {
                    val pos = cur.currentPosition.coerceAtLeast(0L)
                    val dur = cur.duration.coerceAtLeast(0L)
                    if (dur > 0L && pos > 0L) {
                        Thread {
                            runCatching {
                                com.streamcloud.app.data.library.LibraryDb.get(appContext).watchProgress().let { dao ->
                                    kotlinx.coroutines.runBlocking {
                                        dao.upsert(com.streamcloud.app.data.library.WatchProgressEntity(
                                            tmdbId = progressKey.tmdbId, title = progressKey.title,
                                            posterUrl = progressKey.posterUrl, mediaType = progressKey.mediaType,
                                            positionMs = pos, durationMs = dur,
                                            updatedAt = System.currentTimeMillis(), sourceRoute = progressKey.sourceRoute,
                                        ))
                                    }
                                }
                            }
                        }.start()
                    }
                }
            }
        }
    }

    fun bumpInteraction() { controlsVisible = true; lastInteractionTs = System.currentTimeMillis() }

    // ── Skip intervals ────────────────────────────────────────────────────
    val activeSource = activeAutoSource ?: sources.firstOrNull { it.id == selectedSourceId }
    val allSkipIntervals = activeSource?.skipIntervals.orEmpty()
    var dismissedIntervalEnd by remember { mutableStateOf(-1L) }
    val activeSkipInterval = remember(positionMs, allSkipIntervals, dismissedIntervalEnd) {
        allSkipIntervals.firstOrNull { it.startMs <= positionMs && positionMs < it.endMs && it.endMs != dismissedIntervalEnd }
    }

    // ── Next episode countdown ────────────────────────────────────────────
    val nextBingeEpisode = if (currentBingeIndex in 0 until (bingeEpisodes.size - 1))
        bingeEpisodes[currentBingeIndex + 1] else null
    var nextEpisodeDismissed by remember { mutableStateOf(false) }
    var nextEpisodeCountdown by remember { mutableStateOf(10) }
    val showNextEpisodeCard  = nextBingeEpisode != null &&
        durationMs > 0L && (durationMs - positionMs) in 1L..30_000L &&
        !nextEpisodeDismissed && isPlaying

    LaunchedEffect(showNextEpisodeCard) {
        if (!showNextEpisodeCard) return@LaunchedEffect
        nextEpisodeCountdown = 10
        repeat(10) { delay(1_000); nextEpisodeCountdown-- }
        if (!nextEpisodeDismissed && nextBingeEpisode != null) onPlayBingeEpisode?.invoke(nextBingeEpisode)
    }

    // ── UI state ──────────────────────────────────────────────────────────
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val playerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isTv) { if (isTv) try { playerFocusRequester.requestFocus() } catch (_: Exception) {} }

    var locked            by remember { mutableStateOf(false) }
    var showSourcesSheet  by remember { mutableStateOf(false) }
    var showSpeedSheet    by remember { mutableStateOf(false) }
    var showSubsSheet     by remember { mutableStateOf(false) }
    var showAudioSheet    by remember { mutableStateOf(false) }
    var showSubtitleStyle by remember { mutableStateOf(false) }
    var showSidePanel     by remember { mutableStateOf(false) }
    var sidePanelTab      by remember { mutableStateOf(0) }

    // Series label for top bar
    val episodeLabel = when {
        seasonNumber != null && episodeNumber != null -> buildString {
            append("S$seasonNumber E$episodeNumber")
            if (!episodeTitle.isNullOrBlank()) append(" · $episodeTitle")
        }
        !episodeTitle.isNullOrBlank() -> episodeTitle
        else -> subtitle
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(playerFocusRequester).focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val p = player.value ?: return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter  -> { if (p.isPlaying) p.pause() else p.play(); bumpInteraction(); true }
                    Key.DirectionRight   -> { p.seekTo((p.currentPosition + 10_000L).coerceAtMost(p.duration.coerceAtLeast(0L))); bumpInteraction(); true }
                    Key.DirectionLeft    -> { p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L)); bumpInteraction(); true }
                    Key.DirectionUp, Key.DirectionDown -> { bumpInteraction(); true }
                    Key.MediaPlayPause   -> { if (p.isPlaying) p.pause() else p.play(); bumpInteraction(); true }
                    Key.MediaPlay        -> { p.play(); bumpInteraction(); true }
                    Key.MediaPause       -> { p.pause(); bumpInteraction(); true }
                    Key.MediaFastForward -> { p.seekTo((p.currentPosition + 30_000L).coerceAtMost(p.duration.coerceAtLeast(0L))); bumpInteraction(); true }
                    Key.MediaRewind      -> { p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L)); bumpInteraction(); true }
                    else -> false
                }
            }
    ) {
        // Video surface
        if (anyDeviceCasting) {
            com.streamcloud.app.cast.CastRemoteController(
                title = title, streamUrl = resolvedUrl.orEmpty(),
                artworkUrl = artworkUrl, onBack = onBack, modifier = Modifier.fillMaxSize(),
            )
        } else if (needsWebView && resolvedUrl != null) {
            EmbedWebView(resolvedUrl!!)
        } else if (ex != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }.also { playerViewRef.value = it }
                },
                update = { view ->
                    playerViewRef.value = view
                    view.player = ex
                    view.resizeMode = resizeMode
                    applySubtitleStyle(view, subtitleStyle)
                },
            )
        }

        // Gesture layer
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(density) { maxWidth.toPx() }
            var lastTapTime by remember { mutableStateOf(0L) }
            var lastTapX    by remember { mutableStateOf(0f) }
            Box(Modifier.fillMaxSize().pointerInput(ex) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x; val startY = down.position.y
                    val startTime = System.currentTimeMillis()
                    var dragging = false; var dragSide = startX
                    while (true) {
                        val event  = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            if (!dragging) {
                                if (System.currentTimeMillis() - startTime < 400) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapTime < 400 && kotlin.math.abs(startX - lastTapX) < 160f) {
                                        ex?.let { p ->
                                            val seekMs = (seekIncrementSec.toIntOrNull() ?: 10) * 1_000L
                                            val dir = if (startX < widthPx / 2f) -seekMs else +seekMs
                                            p.seekTo((p.currentPosition + dir).coerceAtLeast(0L))
                                        }
                                        bumpInteraction(); lastTapTime = 0L
                                    } else { bumpInteraction(); lastTapTime = now; lastTapX = startX }
                                }
                            }
                            break
                        }
                        val dx = change.position.x - startX; val dy = change.position.y - startY
                        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        if (!dragging && dist > slop) {
                            if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) { dragging = true; dragSide = startX }
                            else break
                        }
                        if (dragging) {
                            change.consume()
                            val delta = -(change.position.y - change.previousPosition.y) * 0.003f
                            if (dragSide < widthPx / 2f && gestureVolumeOn) {
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val cur    = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (cur + (delta * maxVol).toInt()).coerceIn(0, maxVol), 0)
                                volumeOverlay = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                            } else if (dragSide >= widthPx / 2f && gestureBrightnessOn) {
                                val lp = window?.attributes ?: break
                                val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                                lp.screenBrightness = (cur + delta).coerceIn(0.01f, 1f)
                                window?.attributes = lp; brightnessOverlay = lp.screenBrightness
                            }
                        }
                    }
                }
            })
        }

        // Gesture indicator pills
        volumeOverlay?.let { v ->
            Box(Modifier.align(Alignment.CenterStart).padding(start = 28.dp)) {
                SwipeIndicatorPill(Icons.Default.VolumeUp, "Volume", v)
            }
        }
        brightnessOverlay?.let { b ->
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 28.dp)) {
                SwipeIndicatorPill(Icons.Default.Brightness6, "Brightness", b)
            }
        }

        // Auto-switch banner
        autoSwitchBanner?.let { banner ->
            Box(Modifier.align(Alignment.TopCenter).padding(top = 22.dp)
                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text(banner, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Cast status
        if (anyDeviceCasting) {
            Box(Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 14.dp)) {
                com.streamcloud.app.cast.CastButton(showDialog = showCastDialog, onShowDialogChange = { showCastDialog = it })
            }
        }

        // PiP back handler
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && pipEnabledOn) {
            BackHandler {
                runCatching { activity?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) }
                    .onFailure { onBack() }
            }
        }

        // ── Pause artwork overlay ─────────────────────────────────────────
        AnimatedVisibility(
            visible = !isPlaying && !controlsVisible && !needsWebView && !anyDeviceCasting,
            enter = fadeIn(tween(400)), exit = fadeOut(tween(300)),
        ) {
            PauseArtworkOverlay(artworkUrl = artworkUrl, title = title,
                episodeLabel = episodeLabel, modifier = Modifier.fillMaxSize())
        }

        // ── Player controls overlay ───────────────────────────────────────
        AnimatedVisibility(visible = controlsVisible && !needsWebView && !anyDeviceCasting,
            enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                if (!locked) {
                    // Title bar
                    Column(Modifier.align(Alignment.TopStart)
                        .padding(start = 28.dp, top = 22.dp, end = if (isLandscape) 220.dp else 90.dp)) {
                        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                        if (!episodeLabel.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(episodeLabel, color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                // Top-right icon row
                Row(Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!locked && !anyDeviceCasting) {
                        com.streamcloud.app.cast.CastButton(showDialog = showCastDialog, onShowDialogChange = { showCastDialog = it })
                    }
                    PlayerCapsuleIcon(if (locked) Icons.Default.LockOpen else Icons.Default.Lock,
                        if (locked) "Unlock" else "Lock controls") { locked = !locked; bumpInteraction() }
                    if (!locked) PlayerCapsuleIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
                }

                if (!locked) {
                    // Centre playback buttons
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                        OutlinedPlayIcon(Icons.Default.Replay10, "Rewind") {
                            ex?.seekTo((ex.currentPosition - (seekIncrementSec.toIntOrNull() ?: 10) * 1_000L).coerceAtLeast(0L))
                            bumpInteraction()
                        }
                        OutlinedPlayIcon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pause" else "Play", big = true) {
                            ex ?: return@OutlinedPlayIcon
                            if (ex.isPlaying) ex.pause() else ex.play(); bumpInteraction()
                        }
                        OutlinedPlayIcon(Icons.Default.Forward10, "Forward") {
                            ex?.seekTo((ex.currentPosition + (seekIncrementSec.toIntOrNull() ?: 10) * 1_000L).coerceAtMost(durationMs))
                            bumpInteraction()
                        }
                    }

                    // Bottom bar
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 22.dp)) {
                        Slider(
                            value = if (durationMs > 0) positionMs / durationMs.toFloat() else 0f,
                            onValueChange = { v -> ex?.seekTo((v * durationMs).toLong()); bumpInteraction() },
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.30f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TimestampChip(formatTime(positionMs))
                            TimestampChip(formatTime(durationMs))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            PlayerToolbarPill(
                                onSourcesClick       = if (sources.isNotEmpty()) {{ showSourcesSheet = true; bumpInteraction() }} else null,
                                isLandscape          = isLandscape,
                                onRotate             = { isLandscape = !isLandscape; bumpInteraction() },
                                isFill               = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL,
                                onFitClick           = {
                                    resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT)
                                        AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    bumpInteraction()
                                },
                                currentSpeed         = playbackSpeed,
                                onSpeedClick         = { showSpeedSheet = true; bumpInteraction() },
                                onSubsClick          = { showSubsSheet = true; bumpInteraction() },
                                onAudioClick         = { showAudioSheet = true; bumpInteraction() },
                                onSubtitleStyleClick = { showSubtitleStyle = true; bumpInteraction() },
                                effectiveUrl         = effectiveUrl,
                                effectiveHeaders     = effectiveHeaders,
                                context              = context,
                                hasBinge             = bingeEpisodes.size > 1,
                                onBingeClick         = { showSidePanel = !showSidePanel; sidePanelTab = 0; bumpInteraction() },
                            )
                        }
                    }
                }
            }
        }

        // ── Skip intro pill ───────────────────────────────────────────────
        if (activeSkipInterval != null && !locked) {
            SkipIntroPill(
                type      = activeSkipInterval.type,
                onClick   = { ex?.seekTo(activeSkipInterval.endMs); dismissedIntervalEnd = activeSkipInterval.endMs; bumpInteraction() },
                onDismiss = { dismissedIntervalEnd = activeSkipInterval.endMs },
                modifier  = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 110.dp),
            )
        }

        // ── Next episode card ─────────────────────────────────────────────
        AnimatedVisibility(visible = showNextEpisodeCard && nextBingeEpisode != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 110.dp)) {
            if (nextBingeEpisode != null) {
                NextEpisodeCard(
                    episode    = nextBingeEpisode,
                    countdown  = nextEpisodeCountdown,
                    onPlayNext = { onPlayBingeEpisode?.invoke(nextBingeEpisode); nextEpisodeDismissed = true },
                    onDismiss  = { nextEpisodeDismissed = true },
                )
            }
        }

        // ── Side panel ────────────────────────────────────────────────────
        if (isLandscape && (bingeEpisodes.size > 1 || sources.isNotEmpty())) {
            if (!showSidePanel) {
                Box(Modifier.align(Alignment.CenterEnd)
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { showSidePanel = true; bumpInteraction() }
                    .padding(horizontal = 6.dp, vertical = 18.dp)) {
                    Icon(Icons.Default.ChevronLeft, "Open panel", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            AnimatedVisibility(visible = showSidePanel,
                enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd)) {
                PlayerSidePanel(
                    bingeEpisodes     = bingeEpisodes,
                    currentBingeIndex = currentBingeIndex,
                    sources           = sources,
                    selectedSourceId  = selectedSourceId,
                    activeTabIndex    = sidePanelTab,
                    onTabSelected     = { sidePanelTab = it },
                    onEpisodeClick    = { ep -> onPlayBingeEpisode?.invoke(ep); showSidePanel = false },
                    onSourceClick     = { src -> onSwitchSource?.invoke(src); showSidePanel = false },
                    onClose           = { showSidePanel = false },
                )
            }
        }

        // WebView header bar
        if (needsWebView) {
            Row(Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                .padding(top = 12.dp, start = 8.dp, end = 16.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Spacer(Modifier.width(4.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }

        // Loading / error state
        if (ex == null && !needsWebView) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (resolveError != null) {
                    Text(resolveError!!, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                } else {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(when {
                        streamUrl.startsWith("magnet:", true) || streamUrl.contains("&_sc_fidx=", ignoreCase = true) ->
                            when (val ts = torrentState) {
                                is TorrentState.Connecting -> "Connecting to peers…"
                                is TorrentState.Streaming  -> if (ts.peers > 0) "Buffering • ${ts.peers} peers • ${ts.downloadSpeed / 1024} KB/s" else "Buffering…"
                                else -> "Connecting to peers…"
                            }
                        else -> "Loading…"
                    }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ── Bottom sheets ─────────────────────────────────────────────────
        if (showSourcesSheet) {
            SourcesPickerSheet(
                sources          = sources,
                selectedSourceId = selectedSourceId,
                sourceErrors     = sourceErrors,
                onPick           = { src -> showSourcesSheet = false; onSwitchSource?.invoke(src) },
                onDismiss        = { showSourcesSheet = false },
                onRefresh        = onRefresh,
                nuvioScanning    = nuvioScanning,
            )
        }

        if (showSpeedSheet) {
            val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
                Column(Modifier.padding(bottom = 32.dp)) {
                    Text("Playback Speed", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    speeds.forEach { speed ->
                        val label = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = { if (speed == playbackSpeed) Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable {
                                playbackSpeed = speed; ex?.playbackParameters = PlaybackParameters(speed); showSpeedSheet = false
                            },
                        )
                    }
                }
            }
        }

        if (showSubsSheet) {
            val textGroups = ex?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT } ?: emptyList()
            val subsDisabled = ex?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
            ModalBottomSheet(onDismissRequest = { showSubsSheet = false }) {
                Column(Modifier.padding(bottom = 32.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Subtitles", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showSubsSheet = false; showSubtitleStyle = true }) {
                            Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("Style")
                        }
                    }
                    if (textGroups.isEmpty() && addonSubtitles.isEmpty()) {
                        Text("No subtitle tracks available", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        ListItem(headlineContent = { Text("Off") }, leadingContent = {
                            RadioButton(selected = subsDisabled, onClick = {
                                ex?.trackSelectionParameters = ex?.trackSelectionParameters?.buildUpon()
                                    ?.setDisabledTrackTypes(setOf(C.TRACK_TYPE_TEXT))?.build() ?: return@RadioButton
                                showSubsSheet = false
                            })
                        })
                        textGroups.forEach { group ->
                            repeat(group.mediaTrackGroup.length) { i ->
                                val fmt = group.mediaTrackGroup.getFormat(i)
                                ListItem(headlineContent = { Text(fmt.label ?: fmt.language ?: "Track ${i + 1}") },
                                    leadingContent = {
                                        RadioButton(selected = !subsDisabled && group.isTrackSelected(i), onClick = {
                                            ex?.trackSelectionParameters = ex?.trackSelectionParameters?.buildUpon()
                                                ?.setDisabledTrackTypes(emptySet())
                                                ?.addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                                                ?.build() ?: return@RadioButton
                                            showSubsSheet = false
                                        })
                                    })
                            }
                        }
                        if (addonSubtitles.isNotEmpty()) {
                            if (textGroups.isNotEmpty()) {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                Text("From addons", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                            }
                            addonSubtitles.forEach { sub ->
                                ListItem(
                                    headlineContent = { Text(sub.label) },
                                    supportingContent = { Text(sub.addonName, style = MaterialTheme.typography.bodySmall) },
                                    leadingContent = { Icon(Icons.Default.Subtitles, null) },
                                    modifier = Modifier.clickable {
                                        val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                                            .setMimeType("application/x-subrip")
                                            .setLanguage(sub.lang.ifBlank { null })
                                            .setLabel(sub.label)
                                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                            .build()
                                        val current = ex?.currentMediaItem ?: return@clickable
                                        val pos = ex?.currentPosition ?: 0L
                                        ex?.setMediaItem(current.buildUpon().setSubtitleConfigurations(listOf(subConfig)).build(), pos)
                                        ex?.prepare(); showSubsSheet = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showAudioSheet) {
            val audioGroups = ex?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO } ?: emptyList()
            ModalBottomSheet(onDismissRequest = { showAudioSheet = false }) {
                Column(Modifier.padding(bottom = 32.dp)) {
                    Text("Audio Track", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    if (audioGroups.isEmpty() || (audioGroups.size == 1 && audioGroups[0].mediaTrackGroup.length <= 1)) {
                        Text("No alternate audio tracks available", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        audioGroups.forEach { group ->
                            repeat(group.mediaTrackGroup.length) { i ->
                                val fmt = group.mediaTrackGroup.getFormat(i)
                                ListItem(headlineContent = { Text(fmt.label ?: fmt.language ?: "Track ${i + 1}") },
                                    leadingContent = {
                                        RadioButton(selected = group.isTrackSelected(i), onClick = {
                                            ex?.trackSelectionParameters = ex?.trackSelectionParameters?.buildUpon()
                                                ?.addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                                                ?.build() ?: return@RadioButton
                                            showAudioSheet = false
                                        })
                                    })
                            }
                        }
                    }
                }
            }
        }

        if (showSubtitleStyle) {
            SubtitleStyleSheet(
                style     = subtitleStyle,
                onChanged = { newStyle -> subtitleStyle = newStyle; SubtitleStylePrefs.save(context, newStyle); applySubtitleStyle(playerViewRef.value, newStyle) },
                onReset   = { subtitleStyle = SubtitleStylePrefs.reset(context); applySubtitleStyle(playerViewRef.value, subtitleStyle) },
                onDismiss = { showSubtitleStyle = false },
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Pause artwork overlay
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun PauseArtworkOverlay(artworkUrl: String?, title: String, episodeLabel: String?, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(model = artworkUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(16.dp))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (artworkUrl.isNullOrBlank()) 0.85f else 0.60f)))
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!artworkUrl.isNullOrBlank()) {
                AsyncImage(model = artworkUrl, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.height(140.dp).padding(bottom = 20.dp))
            }
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            if (!episodeLabel.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(episodeLabel, color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
        Icon(Icons.Default.Pause, "Paused", tint = Color.White.copy(alpha = 0.30f),
            modifier = Modifier.align(Alignment.BottomEnd).size(60.dp).padding(16.dp))
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Skip intro pill
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SkipIntroPill(type: String, onClick: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (type.lowercase()) {
        "outro", "credits" -> "Skip Credits"; "recap" -> "Skip Recap"; else -> "Skip Intro"
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f))
            .tvFocusBorder(RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.40f))
            .clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Dismiss", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Next episode card
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun NextEpisodeCard(episode: BingeEpisode, countdown: Int, onPlayNext: () -> Unit, onDismiss: () -> Unit) {
    val epLabel = "S${episode.seasonNumber} E${episode.episodeNumber}" + (episode.episodeTitle?.let { " · $it" } ?: "")
    Box(Modifier.width(280.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.85f)).padding(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Up Next", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "Dismiss", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!episode.posterUrl.isNullOrBlank()) {
                AsyncImage(model = episode.posterUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(8.dp))
            }
            Text(episode.title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text(epLabel, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onPlayNext, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Play in ${countdown}s")
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Subtitle style sheet
// ────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleStyleSheet(style: SubtitleStyleState, onChanged: (SubtitleStyleState) -> Unit,
    onReset: () -> Unit, onDismiss: () -> Unit) {
    val colorOptions = listOf(
        "White"  to android.graphics.Color.WHITE,
        "Yellow" to android.graphics.Color.YELLOW,
        "Green"  to android.graphics.Color.GREEN,
        "Cyan"   to android.graphics.Color.CYAN,
        "Orange" to android.graphics.Color.parseColor("#FF8C00"),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 36.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Subtitle Style", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("Reset") }
            }
            Spacer(Modifier.height(12.dp))
            SubtitleControlRow("Font size: ${style.fontSizeSp.toInt()}sp") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = { onChanged(style.copy(fontSizeSp = (style.fontSizeSp - 2f).coerceAtLeast(8f))) },
                        modifier = Modifier.size(36.dp)) { Text("−") }
                    Text(style.fontSizeSp.toInt().toString(), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(min = 32.dp), textAlign = TextAlign.Center)
                    FilledIconButton(onClick = { onChanged(style.copy(fontSizeSp = (style.fontSizeSp + 2f).coerceAtMost(48f))) },
                        modifier = Modifier.size(36.dp)) { Text("+") }
                }
            }
            Spacer(Modifier.height(8.dp))
            SubtitleControlRow("Colour") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colorOptions.forEach { (name, argb) ->
                        val selected = style.colorArgb == argb
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(argb))
                            .then(if (selected) Modifier.tvFocusBorder(CircleShape) else Modifier)
                            .clickable { onChanged(style.copy(colorArgb = argb)) },
                            contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Default.Check, name,
                                tint = if (argb == android.graphics.Color.WHITE) Color.Black else Color.White,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SubtitleControlRow("Outline") {
                Switch(checked = style.outlineEnabled, onCheckedChange = { onChanged(style.copy(outlineEnabled = it)) })
            }
            Spacer(Modifier.height(8.dp))
            SubtitleControlRow("Bold") {
                Switch(checked = style.bold, onCheckedChange = { onChanged(style.copy(bold = it)) })
            }
            Spacer(Modifier.height(8.dp))
            SubtitleControlRow("Opacity: ${(style.opacityFraction * 100).toInt()}%") {
                Slider(value = style.opacityFraction, onValueChange = { onChanged(style.copy(opacityFraction = it)) },
                    modifier = Modifier.width(160.dp))
            }
            Spacer(Modifier.height(8.dp))
            SubtitleControlRow("Position: ${(style.bottomOffsetFraction * 100).toInt()}%") {
                Slider(value = style.bottomOffsetFraction, onValueChange = { onChanged(style.copy(bottomOffsetFraction = it)) },
                    valueRange = 0f..0.25f, modifier = Modifier.width(160.dp))
            }
            Spacer(Modifier.height(8.dp))
            SubtitleControlRow("Delay: ${style.delayMs}ms") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = { onChanged(style.copy(delayMs = style.delayMs - 100)) },
                        modifier = Modifier.size(36.dp)) { Text("−") }
                    Text("${style.delayMs}ms", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(min = 60.dp), textAlign = TextAlign.Center)
                    FilledIconButton(onClick = { onChanged(style.copy(delayMs = style.delayMs + 100)) },
                        modifier = Modifier.size(36.dp)) { Text("+") }
                    if (style.delayMs != 0) TextButton(onClick = { onChanged(style.copy(delayMs = 0)) }) { Text("Reset") }
                }
            }
        }
    }
}

@Composable
private fun SubtitleControlRow(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        control()
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Player side panel
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlayerSidePanel(
    bingeEpisodes: List<BingeEpisode>, currentBingeIndex: Int,
    sources: List<PlayerSource>, selectedSourceId: String?,
    activeTabIndex: Int, onTabSelected: (Int) -> Unit,
    onEpisodeClick: (BingeEpisode) -> Unit, onSourceClick: (PlayerSource) -> Unit,
    onClose: () -> Unit,
) {
    val tabs = buildList {
        if (bingeEpisodes.size > 1) add("Episodes")
        if (sources.isNotEmpty()) add("Sources")
    }
    Column(Modifier.width(300.dp).fillMaxHeight().background(Color(0xF0111111))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { idx, tab ->
                val selected = idx == activeTabIndex
                Box(Modifier.clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onTabSelected(idx) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(tab, color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        when (tabs.getOrNull(activeTabIndex)) {
            "Episodes" -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(bingeEpisodes) { idx, ep ->
                    val isCurrent = idx == currentBingeIndex
                    Row(Modifier.fillMaxWidth()
                        .background(if (isCurrent) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onEpisodeClick(ep) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (!ep.posterUrl.isNullOrBlank()) {
                            AsyncImage(model = ep.posterUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 64.dp, height = 42.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("S${ep.seasonNumber} E${ep.episodeNumber}",
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.labelSmall)
                            Text(ep.episodeTitle ?: ep.title, color = Color.White,
                                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (isCurrent) Icon(Icons.Default.PlayArrow, "Now playing",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            "Sources" -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(sources) { src ->
                    val isSelected = src.id == selectedSourceId
                    Row(Modifier.fillMaxWidth()
                        .background(if (isSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSourceClick(src) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (src.isMagnet) Icons.Default.Bolt else Icons.Default.PlayArrow, null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(src.label, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(src.addonName + (src.qualityTag?.let { " · $it" } ?: ""),
                                color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
                        }
                        src.fileSizeBytes?.let {
                            Text(PlayerSource.formatFileSize(it), color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Sources picker sheet
// ────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesPickerSheet(
    sources: List<PlayerSource>, selectedSourceId: String?,
    sourceErrors: Map<String, String> = emptyMap(),
    onPick: (PlayerSource) -> Unit, onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null, nuvioScanning: Boolean = false,
) {
    val safeSources    = remember(sources) { sources.distinctBy { it.id } }
    val addonFilters   = remember(safeSources) { listOf("All") + safeSources.map { it.addonName }.distinct() }
    var activeFilter   by remember(safeSources) { mutableStateOf("All") }
    val filtered = remember(activeFilter, safeSources) {
        if (activeFilter == "All") safeSources else safeSources.filter { it.addonName == activeFilter }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF111111), scrimColor = Color.Black.copy(alpha = 0.7f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onRefresh?.invoke() }, enabled = onRefresh != null && !nuvioScanning) {
                    if (nuvioScanning) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "Reload", tint = if (onRefresh != null) Color.White else Color.White.copy(alpha = 0.3f))
                }
                Text("Streams", color = Color.White, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }
            AnimatedVisibility(visible = nuvioScanning) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Scanning Nuvio providers…", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
            // Per-addon error banner
            val currentAddonError = if (activeFilter != "All") sourceErrors[activeFilter] else null
            if (currentAddonError != null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(currentAddonError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                items(addonFilters, key = { it }) { name ->
                    SourceFilterChip(name, name == activeFilter, sourceErrors.containsKey(name) && name != "All") { activeFilter = name }
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(filtered, key = { it.id }) { src ->
                    StreamPickerRow(src = src, selected = src.id == selectedSourceId, onClick = { onPick(src) })
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(when {
                            nuvioScanning -> "Scanning for streams…"
                            currentAddonError != null -> "No streams — $activeFilter returned an error."
                            else -> "No streams from $activeFilter."
                        }, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceFilterChip(label: String, selected: Boolean, hasError: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).tvFocusBorder(RoundedCornerShape(50))
        .background(when { selected -> Color.White; hasError -> Color(0xFF3B1515); else -> Color.White.copy(alpha = 0.12f) })
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = if (selected) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge)
            if (hasError && !selected) Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun StreamPickerRow(src: PlayerSource, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tvFocusBorder(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick).padding(12.dp)) {
        Icon(if (src.isMagnet) Icons.Default.Bolt else Icons.Default.PlayArrow, null,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(src.label, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 3)
            Text("${src.addonName} · ${if (src.isMagnet) "Torrent" else "Direct"}" + (src.qualityTag?.let { " · $it" } ?: ""),
                color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!src.debridHost.isNullOrBlank()) StreamBadge("⚡", Color(0xFFFFC107))
            src.fileSizeBytes?.let { StreamBadge(PlayerSource.formatFileSize(it), Color.White.copy(alpha = 0.20f)) }
            src.qualityTag?.let { q ->
                StreamBadge(q, when (q) {
                    "4K" -> Color(0xFF6200EE); "1440p" -> Color(0xFF0057B7)
                    "1080p" -> Color(0xFF1976D2); "720p" -> Color(0xFF388E3C); else -> Color.White.copy(alpha = 0.15f)
                })
            }
        }
    }
}

@Composable
private fun StreamBadge(label: String, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 5.dp, vertical = 2.dp)) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Private composable helpers
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun OutlinedPlayIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String,
    big: Boolean = false, onClick: () -> Unit) {
    val size = if (big) 84.dp else 60.dp; val iconSize = if (big) 48.dp else 32.dp
    Box(Modifier.size(size).clip(CircleShape).tvFocusBorder(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun PlayerCapsuleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(50))
        .background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TimestampChip(text: String) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
        .padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PlayerToolbarPill(
    onSourcesClick: (() -> Unit)?, isLandscape: Boolean = true,
    onRotate: (() -> Unit)? = null, isFill: Boolean = false, onFitClick: () -> Unit = {},
    currentSpeed: Float = 1f, onSpeedClick: () -> Unit = {},
    onSubsClick: () -> Unit = {}, onAudioClick: () -> Unit = {},
    onSubtitleStyleClick: () -> Unit = {},
    effectiveUrl: String = "", effectiveHeaders: Map<String, String> = emptyMap(),
    context: Context? = null, hasBinge: Boolean = false, onBingeClick: () -> Unit = {},
) {
    val speedLabel = if (currentSpeed % 1f == 0f) "${currentSpeed.toInt()}x" else "${currentSpeed}x"
    val scrollState = rememberScrollState()
    Row(Modifier.fillMaxWidth(if (isLandscape) 0.75f else 1f).clip(RoundedCornerShape(50))
        .background(Color.Black.copy(alpha = 0.55f)).horizontalScroll(scrollState)
        .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolbarItem(Icons.Default.AspectRatio, if (isFill) "Fill" else "Fit") { onFitClick() }
        ToolbarItem(Icons.Default.Speed, speedLabel) { onSpeedClick() }
        ToolbarItem(Icons.Default.ClosedCaption, "Subs") { onSubsClick() }
        ToolbarItem(Icons.Default.Tune, "Sub Style") { onSubtitleStyleClick() }
        ToolbarItem(Icons.Default.VolumeUp, "Audio") { onAudioClick() }
        ToolbarItem(Icons.AutoMirrored.Filled.CompareArrows, "Sources", enabled = onSourcesClick != null) { onSourcesClick?.invoke() }
        if (hasBinge) ToolbarItem(Icons.Default.QueuePlayNext, "Episodes") { onBingeClick() }
        if (effectiveUrl.startsWith("http", true) && context != null) {
            ToolbarItem(Icons.Default.OpenInNew, "External") {
                try {
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_VIEW, Uri.parse(effectiveUrl)).apply {
                            setDataAndType(Uri.parse(effectiveUrl), "video/*")
                            if (effectiveHeaders.isNotEmpty())
                                putExtra("headers", effectiveHeaders.entries.joinToString("\r\n") { "${it.key}: ${it.value}" })
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }, "Open with…"
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (_: Exception) {}
            }
        }
        if (onRotate != null) ToolbarItem(Icons.Default.ScreenRotation, if (isLandscape) "Portrait" else "Landscape") { onRotate() }
    }
}

@Composable
private fun SwipeIndicatorPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.70f))
            .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
        LinearProgressIndicator(progress = { value }, modifier = Modifier.width(60.dp).height(4.dp).clip(RoundedCornerShape(50)),
            color = Color.White, trackColor = Color.White.copy(alpha = 0.25f))
        Text("${(value * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToolbarItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Row(Modifier.clip(RoundedCornerShape(50)).tvFocusBorder(RoundedCornerShape(50))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Utilities
// ────────────────────────────────────────────────────────────────────────────

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000; val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun EmbedWebView(url: String) {
    AndroidView(modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                @Suppress("DEPRECATION")
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                android.webkit.CookieManager.getInstance().let { cm ->
                    cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(this, true)
                }
                val activity = ctx as? android.app.Activity
                var customVideoView: View? = null
                var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onShowCustomView(view: View, callback: android.webkit.WebChromeClient.CustomViewCallback) {
                        customVideoView?.let { (activity?.window?.decorView as? android.view.ViewGroup)?.removeView(it) }
                        customVideoView = view; customViewCallback = callback
                        (activity?.window?.decorView as? android.view.ViewGroup)?.addView(view, -1, -1)
                    }
                    override fun onHideCustomView() {
                        customVideoView?.let { (activity?.window?.decorView as? android.view.ViewGroup)?.removeView(it) }
                        customVideoView = null; customViewCallback?.onCustomViewHidden(); customViewCallback = null
                    }
                }
                webViewClient = android.webkit.WebViewClient(); loadUrl(url)
            }
        },
        update = { webView ->
            val current = webView.originalUrl ?: webView.url
            if (current != url) webView.loadUrl(url)
        },
    )
}

fun extractEmbedUrl(input: String): String {
    val s = input.trim()
    if (s.isEmpty() || s.startsWith("magnet:", true) || s.startsWith("http://", true) || s.startsWith("https://", true)) return s
    return Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: s
}
