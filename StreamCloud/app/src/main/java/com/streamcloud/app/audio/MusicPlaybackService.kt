package com.streamcloud.app.audio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.streamcloud.app.MainActivity
import com.streamcloud.app.data.AppLogger
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.downloads.DownloadCaches
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.TrackDao
import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import com.streamcloud.app.data.ytmusic.StreamUrlCache
import com.streamcloud.app.data.ytmusic.HomeSection
import com.streamcloud.app.data.ytmusic.YtMusicArtistRepository
import com.streamcloud.app.data.ytmusic.YtMusicHomeFeed
import com.streamcloud.app.data.ytmusic.YtMusicHomeRepository
import com.streamcloud.app.data.ytmusic.YtMusicLibrary
import com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository
import com.streamcloud.app.data.ytmusic.YtMusicStreamResolver
import com.streamcloud.app.data.ytmusic.YtmPlaylist
import com.streamcloud.app.data.ytmusic.YtmSong
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var audioFx: AudioFx? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var playerCache: SimpleCache
    private lateinit var downloadCache: SimpleCache
    private lateinit var dataSourceFactory: ResolvingDataSource.Factory
    private lateinit var bufferedCacheDataSourceFactory: CacheDataSource.Factory
    private val bufferedPrefetchJobs = ConcurrentHashMap<String, Job>()
    private val bufferedPrefetchWriters = ConcurrentHashMap<String, CacheWriter>()
    private val bufferedPrefetchPermit = Semaphore(1)

    // ── Crossfade engine ──────────────────────────────────────────────────────
    // True crossfade uses TWO ExoPlayer instances.
    //
    //  exoPlayer  — primary (owns the MediaSession / notification).
    //  xfPlayer   — secondary, spun up `crossfadeDurationMs` before the end of
    //               the current track and immediately starts playing the next
    //               track at volume 0.  Both fade in opposite directions so the
    //               listener hears a smooth overlap.
    //
    // After the primary player advances to the next track (onMediaItemTransition)
    // the primary is seeked to match xfPlayer's current position and both do a
    // final silent hand-off: xfPlayer stays audible until the primary has
    // buffered, then the primary unmutes and xfPlayer is released.
    @Volatile private var crossfadeDurationMs: Long = 0L
    private var xfPlayer: ExoPlayer? = null          // secondary cross-fade player
    private var xfCrossfading: Boolean = false        // cross-fade in progress
    private var xfStartMs: Long = 0L                 // wall-clock when xf began
    private var xfNextMediaItem: MediaItem? = null   // guard against double-init
    private var xfHandoffPending: Boolean = false    // waiting for primary to buffer
    private var xfHandoffXfPlayer: ExoPlayer? = null // secondary ref during handoff
    private lateinit var exoPlayer: ExoPlayer


    @Volatile private var ytLibrary: YtMusicLibrary = YtMusicLibrary()
    @Volatile private var ytHomeFeed: YtMusicHomeFeed = YtMusicHomeFeed()

    @Volatile private var ytMusicCookieForStream: String = ""
    @Volatile private var isCurrentLiked: Boolean = false
    /**
     * A GVS URL can be invalidated by YouTube even though its `expire=` value is hours away, or
     * answer the first Media3 range read with an out-of-range source error. Retrying that exact
     * cached URL just repeats the failure. Track one automatic refresh per song and remember the
     * rejecting Innertube client so the resolver selects the next client.
     */
    private val youtubeStreamRetriedVideoIds = ConcurrentHashMap.newKeySet<String>()
    private val rejectedYouTubeClientByVideoId = ConcurrentHashMap<String, String>()
    /**
     * A 403 means an Innertube URL was accepted by the player API but rejected by the CDN. On the
     * automatic recovery path, use an independent maintained extractor before trying more URLs from
     * the same client family.
     */
    private val preferMaintainedExtractorVideoIds = ConcurrentHashMap.newKeySet<String>()
    private val carSearchResults = ConcurrentHashMap<String, List<MediaItem>>()

    private data class ResolvedStream(
        val url: String,
        val userAgent: String,
        val requiresWebSessionHeaders: Boolean,
    )





    private val sl by lazy { ServiceLocator.get(applicationContext) }

    override fun onCreate() {
        super.onCreate()

        playerCache = DownloadCaches.playerCache(this)
        downloadCache = DownloadCaches.downloadCache(this)

        dataSourceFactory = buildDataSourceFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val musicAudioAttrs = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            // Start as soon as enough audio is available for a smooth first beat. Keep Media3's
            // generous steady-state buffer and rebuffer thresholds so this only improves the
            // initial tap-to-audio delay rather than trading playback stability for speed.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        50_000,
                        50_000,
                        750,
                        2_000,
                    )
                    .build(),
            )
            .setAudioAttributes(musicAudioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                playWhenReady = false
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (retryWithFreshYoutubeStream(error)) return
                        AppLogger.e(TAG, "ExoPlayer error code=${error.errorCode} msg=${error.message}", error.cause)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        val label = when (state) {
                            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                            androidx.media3.common.Player.STATE_READY -> "READY"
                            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN($state)"
                        }
                        AppLogger.i(TAG, "playback state → $label")
                    }
                    override fun onIsPlayingChanged(playing: Boolean) {
                        // The cast flow pauses the primary session player before replacing a
                        // Sonos URI. A secondary crossfade player is not session-owned, so it
                        // must be stopped explicitly or the upcoming song can leak from the phone.
                        if (!playing && !playWhenReady) {
                            val crossfade = xfPlayer
                            xfPlayer = null
                            crossfade?.stop()
                            crossfade?.release()
                            val handoff = xfHandoffXfPlayer
                            xfHandoffXfPlayer = null
                            if (handoff !== crossfade) {
                                handoff?.stop()
                                handoff?.release()
                            }
                            xfCrossfading = false
                            xfHandoffPending = false
                            xfNextMediaItem = null
                            exoPlayer.volume = 1f
                        }
                    }
                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        mediaItem?.let { item ->
                            youtubeVideoId(item.mediaId)?.let { videoId ->
                                // A newly started track gets its own one-shot 403 recovery budget.
                                youtubeStreamRetriedVideoIds.remove(videoId)
                                rejectedYouTubeClientByVideoId.remove(videoId)
                                preferMaintainedExtractorVideoIds.remove(videoId)
                            }
                        }
                        // Keep the next few queue entries warm after every transition, including
                        // queues created by Android Auto or another MediaSession client.
                        prefetchUpcomingStreams()
                        refreshLikedState()
                        if (xfCrossfading) {
                            // Primary advanced to the next track while xfPlayer was fading in.
                            // Hand off: keep xfPlayer audible, seek primary to match, then swap.
                            val xf = xfPlayer
                            xfCrossfading = false
                            xfPlayer = null
                            xfNextMediaItem = null
                            if (xf != null && xf.playbackState != Player.STATE_IDLE) {
                                val syncPos = xf.currentPosition.coerceAtLeast(0L)
                                exoPlayer.volume = 0f
                                if (syncPos > 300L) exoPlayer.seekTo(syncPos)
                                xfHandoffPending = true
                                xfHandoffXfPlayer = xf
                            } else {
                                xf?.release()
                                exoPlayer.volume = 1f
                            }
                        } else {
                            // No active crossfade — restore full volume (e.g. very short track
                            // that ended before the fade-out window even started).
                            exoPlayer.volume = 1f
                        }
                    }
                    override fun onTimelineChanged(
                        timeline: androidx.media3.common.Timeline,
                        reason: Int,
                    ) {
                        // Queue edits do not always produce a transition immediately (for example,
                        // when a user adds several songs while the current item is playing).
                        prefetchUpcomingStreams()
                    }
                    override fun onRepeatModeChanged(repeatMode: Int) {
                        session?.setCustomLayout(buildCustomLayout())
                    }
                })
            }

        val sessionActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivityIntent)
            .build()
        session?.setCustomLayout(buildCustomLayout())

        audioFx = AudioFx(applicationContext, player.audioSessionId).also { it.start() }
        exoPlayer = player

        // Keep crossfadeDurationMs in sync with the DataStore setting.
        ioScope.launch {
            sl.settings.crossfadeDuration.collect { raw ->
                val secs = raw.toLongOrNull() ?: 0L
                crossfadeDurationMs = secs * 1_000L
                if (crossfadeDurationMs <= 0L) {
                    // Crossfade turned off — abort any in-progress xfPlayer on main thread.
                    ioScope.launch(Dispatchers.Main) {
                        if (::exoPlayer.isInitialized) {
                            xfPlayer?.let { xf -> xf.stop(); xf.release() }
                            xfPlayer = null
                            xfCrossfading = false
                            xfNextMediaItem = null
                            xfHandoffXfPlayer?.let { xf -> xf.stop(); xf.release() }
                            xfHandoffXfPlayer = null
                            xfHandoffPending = false
                            exoPlayer.volume = 1f
                        }
                    }
                }
            }
        }

        // ── Crossfade monitor (50 ms poll, main thread) ──────────────────────────
        // Three phases:
        //
        //  HANDOFF   — xfPlayer finished the fade-in; primary is seeking to sync pos.
        //               Keep xfPlayer audible until primary is buffered, then swap.
        //
        //  CROSSFADE — both players running; ramp exoPlayer 1→0, xfPlayer 0→1.
        //
        //  IDLE      — normal playback.  When `remaining ≤ cfMs`, spin up xfPlayer
        //               with the next track and enter CROSSFADE.
        ioScope.launch(Dispatchers.Main) {
            while (true) {
                delay(50L)
                if (!::exoPlayer.isInitialized) continue
                val cfMs = crossfadeDurationMs

                // ── HANDOFF phase ──────────────────────────────────────────────
                if (xfHandoffPending) {
                    val hf = xfHandoffXfPlayer
                    when {
                        exoPlayer.playbackState == Player.STATE_READY && exoPlayer.isPlaying -> {
                            // Primary has buffered at the sync position — complete swap.
                            exoPlayer.volume = 1f
                            hf?.stop(); hf?.release()
                            xfHandoffXfPlayer = null
                            xfHandoffPending = false
                        }
                        hf != null && hf.isPlaying -> {
                            // Primary still buffering — keep secondary audible so there's no gap.
                            if (hf.volume < 1f) hf.volume = 1f
                        }
                        else -> {
                            // Secondary gone or stalled — just restore primary.
                            exoPlayer.volume = 1f
                            xfHandoffXfPlayer = null
                            xfHandoffPending = false
                        }
                    }
                    continue
                }

                // ── Crossfade disabled ─────────────────────────────────────────
                if (cfMs <= 0L) {
                    // Abort any lingering secondary player (safety net for the settings change).
                    xfPlayer?.let { xf -> xf.stop(); xf.release(); xfPlayer = null }
                    xfCrossfading = false; xfNextMediaItem = null
                    if (exoPlayer.volume < 1f) exoPlayer.volume = 1f
                    continue
                }

                // ── CROSSFADE phase ────────────────────────────────────────────
                if (xfCrossfading) {
                    val xf = xfPlayer
                    val elapsed = System.currentTimeMillis() - xfStartMs
                    val fraction = (elapsed.toFloat() / cfMs.toFloat()).coerceIn(0f, 1f)
                    exoPlayer.volume = (1f - fraction).coerceIn(0f, 1f)
                    xf?.volume = fraction
                    continue
                }

                // ── IDLE: watch for the fade-out window ────────────────────────
                if (exoPlayer.playbackState != Player.STATE_READY || !exoPlayer.isPlaying) {
                    if (exoPlayer.volume < 1f) exoPlayer.volume = 1f
                    continue
                }
                val duration = exoPlayer.duration
                val position = exoPlayer.currentPosition
                if (duration <= 0L || position < 0L) continue

                val remaining = duration - position
                if (remaining > cfMs) {
                    // Outside the fade-out window — full volume.
                    if (exoPlayer.volume < 1f) exoPlayer.volume = 1f
                    continue
                }
                if (remaining <= 0L) continue  // already ended; transition handles it

                // Inside the fade-out window: determine the next track.
                val currentIdx = exoPlayer.currentMediaItemIndex
                val nextIdx = when (exoPlayer.repeatMode) {
                    Player.REPEAT_MODE_ONE -> currentIdx          // same track repeats
                    Player.REPEAT_MODE_ALL ->
                        if (exoPlayer.mediaItemCount > 0)
                            (currentIdx + 1) % exoPlayer.mediaItemCount
                        else -1
                    else ->
                        if (currentIdx < exoPlayer.mediaItemCount - 1) currentIdx + 1 else -1
                }
                if (nextIdx < 0) {
                    // No next track — just let the track end naturally.
                    exoPlayer.volume = (remaining.toFloat() / cfMs.toFloat()).coerceIn(0f, 1f)
                    continue
                }

                if (xfPlayer == null) {
                    // Spin up the secondary player for the upcoming track.
                    val nextItem = runCatching { exoPlayer.getMediaItemAt(nextIdx) }.getOrNull()
                    if (nextItem != null && nextItem.mediaId != xfNextMediaItem?.mediaId) {
                        xfNextMediaItem = nextItem
                        // Align xfStartMs so that elapsed = cfMs - remaining at this instant.
                        xfStartMs = System.currentTimeMillis() - (cfMs - remaining)
                        xfCrossfading = true

                        val xf = ExoPlayer.Builder(this@MusicPlaybackService)
                            .setMediaSourceFactory(
                                DefaultMediaSourceFactory(this@MusicPlaybackService)
                                    .setDataSourceFactory(dataSourceFactory)
                            )
                            .build()
                        // Initial volume mirrors how far into the crossfade window we already are.
                        xf.volume = (1f - remaining.toFloat() / cfMs.toFloat()).coerceIn(0f, 1f)
                        xf.setMediaItem(attachUri(nextItem))
                        xf.prepare()
                        xf.playWhenReady = true
                        xfPlayer = xf
                    }
                }
                // Fade out primary while crossfade is underway.
                exoPlayer.volume = (remaining.toFloat() / cfMs.toFloat()).coerceIn(0f, 1f)
            }
        }

        YtPlayerUtils.appContext = applicationContext
        ioScope.launch { YtPlayerUtils.warmUp() }




        ioScope.launch {
            sl.settings.ytMusicCookie.collect { cookie ->
                ytMusicCookieForStream = cookie
                YtPlayerUtils.ytMusicCookie = cookie
                if (cookie.isNotBlank()) {
                    ytLibrary = YtMusicLibraryRepository.sync(cookie)
                    ytHomeFeed = YtMusicHomeRepository.load(cookie)
                }
            }
        }




        ioScope.launch {
            sl.settings.contentLanguage.collect { YtPlayerUtils.contentLanguage = it }
        }
        ioScope.launch {
            sl.settings.contentCountry.collect { YtPlayerUtils.contentCountry = it }
        }
    }



    private fun buildDataSourceFactory(): ResolvingDataSource.Factory {









        // Force IPv4-only DNS for CDN requests.
        //
        // YouTube's player API embeds the source IP of the player request into the CDN URL
        // as `ip=<address>`.  If the player API goes via IPv4 (e.g. 82.132.x.x) but the
        // ExoPlayer OkHttp client connects to googlevideo.com via IPv6 (OkHttp's default
        // "Happy Eyeballs" prefers IPv6 when available), the CDN sees a different source IP
        // and returns HTTP 403 with an *empty body* — the exact symptom we observed.
        //
        // Fix: filter DNS results to IPv4 addresses only so the CDN request IP always
        // matches the `ip=` embedded in the URL.  Fall back to any address if no IPv4
        // record is available (shouldn't happen for googlevideo.com, but safe fallback).
        val ipv4OnlyDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                okhttp3.Dns.SYSTEM.lookup(hostname)
                    .filter { it is java.net.Inet4Address }
                    .ifEmpty { okhttp3.Dns.SYSTEM.lookup(hostname) }
        }

        val streamOkHttp = OkHttpClient.Builder()
            .dns(ipv4OnlyDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val cookie = ytMusicCookieForStream
                val host = req.url.host
                val builder = req.newBuilder()
                val streamSessionMarker = req.header(STREAM_WEB_SESSION_HEADER)
                val useWebSessionHeaders = when (streamSessionMarker) {
                    // The resolving data source explicitly marks every music stream. This must win
                    // over its user agent: a maintained extractor can resolve via a web page but
                    // deliberately play anonymously, and adding a logged-in browser session to
                    // that URL causes the CDN 403 this fallback is designed to avoid.
                    STREAM_WEB_SESSION_VALUE -> true
                    "0" -> false
                    // Redirects no longer carry the internal marker because it is stripped before
                    // the first network request. Only genuine browser streams use the web profile.
                    else -> req.header("User-Agent").orEmpty().startsWith("Mozilla/")
                }

                val hasPot = req.url.queryParameter("pot") != null
                // This header tells the in-app interceptor which profile to apply. It must never
                // reach YouTube's CDN as an actual HTTP header.
                builder.removeHeader(STREAM_WEB_SESSION_HEADER)

                if (cookie.isNotBlank() && useWebSessionHeaders) {
                    when {
                        // music.youtube.com API requests — always send cookie + browser headers.
                        host.endsWith("music.youtube.com") -> {
                            builder.header("Cookie", cookie)
                                   .header("Origin", "https://music.youtube.com")
                                   .header("Referer", "https://music.youtube.com/")
                        }
                        // Googlevideo.com URLs created by WEB_REMIX/TVHTML5 are tied to the
                        // browser session that generated their PoToken. Anonymous app-client
                        // URLs deliberately skip this block: mixing a browser cookie/visitor
                        // session with an ANDROID_VR or TESTSUITE URL can cause a CDN 403.
                        host.contains("googlevideo.com") -> {
                            builder.header("Cookie", cookie)
                                   .header("Origin", "https://music.youtube.com")
                                   .header("Referer", "https://music.youtube.com/")
                            if (hasPot) {
                                builder.header("Sec-Fetch-Dest", "audio")
                                       .header("Sec-Fetch-Mode", "cors")
                                       .header("Sec-Fetch-Site", "cross-site")
                            }
                        }
                    }
                }

                // X-Goog-Visitor-Id is meaningful only for the same web/PoToken session that
                // created the URL. Sending it to anonymous Android-client streams can conflict
                // with their signed client context.
                val vd = YtPlayerUtils.cachedVisitorData
                if (useWebSessionHeaders && vd != null) {
                    builder.header("X-Goog-Visitor-Id", vd)
                }

                val response = chain.proceed(builder.build())

                // Log 403 CDN response bodies — the body often contains the exact failure reason
                // (e.g. "Video unavailable", IP mismatch, missing auth) which is invisible from
                // the ExoPlayer error alone.
                if (response.code == 403 && host.contains("googlevideo.com")) {
                    val body = response.peekBody(400).string().take(300)
                    val sessionProfile = if (useWebSessionHeaders) "web" else "anonymous"
                    AppLogger.e(TAG, "CDN 403 ($sessionProfile client) body: $body")
                    AppLogger.e(TAG, "CDN 403 url: ${req.url.toString().take(120)}")
                }

                response
            }
            .build()

        val httpFactory = OkHttpDataSource.Factory(streamOkHttp)

        val playerCacheFactory = CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // CacheWriter requires a CacheDataSource. This dedicated path checks playerCache first,
        // resolves the watch URL only on a miss, then writes the byte range back under the same
        // watch URL cache key used by the foreground player's custom cache key.
        bufferedCacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(
                ResolvingDataSource.Factory(httpFactory) { dataSpec ->
                    resolveMusicDataSpec(dataSpec)
                },
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val chainedCacheFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(playerCacheFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return ResolvingDataSource.Factory(chainedCacheFactory) { dataSpec ->
            resolveMusicDataSpec(dataSpec)
        }
    }

    private fun resolveMusicDataSpec(dataSpec: DataSpec): DataSpec {
        val cacheKey = dataSpec.key ?: dataSpec.uri.toString()
        val watchUrl = if (cacheKey.startsWith("http")) cacheKey
        else "https://music.youtube.com/watch?v=$cacheKey"

        // Only skip resolution for fully-completed ExoPlayer downloads.
        // The download cache is guaranteed to contain the full file in that case,
        // so ExoPlayer can seek anywhere (including to the moov atom at the end of
        // an mp4 file) without needing a live stream URL.
        if (com.streamcloud.app.data.downloads.YtMusicDownloadUtil.isDownloaded(watchUrl)) {
            return dataSpec
        }

        // Legacy MusicDownloader files stored as real paths in the library DB.
        val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
        val localPath = runBlocking(Dispatchers.IO) { dao.byUrl(watchUrl)?.localPath }
        if (localPath != null && !localPath.startsWith("cache:") && File(localPath).exists()) {
            return dataSpec.withUri(localPath.toUri())
        }

        // Always resolve to the real stream URL for everything else.
        // CacheDataSource handles player-cache hits transparently — there is no need
        // for an early-return here, and doing so would leave the raw watch URL as the
        // HTTP fallback, causing ExoPlayer to receive an HTML page when it seeks to an
        // uncached range (e.g. the moov atom at the end of a mp4 stream).
        val videoId = watchUrl.substringAfter("v=", "").substringBefore("&")
        val stream = resolveStreamUrl(videoId, watchUrl)

        return dataSpec.buildUpon()
            .setUri(stream.url.toUri())
            .setHttpRequestHeaders(
                dataSpec.httpRequestHeaders + mapOf(
                    "User-Agent" to stream.userAgent,
                    STREAM_WEB_SESSION_HEADER to
                        if (stream.requiresWebSessionHeaders) STREAM_WEB_SESSION_VALUE else "0",
                )
            )
            .build()
    }


    private fun resolveStreamUrl(videoId: String, watchUrl: String): ResolvedStream {
        val now = System.currentTimeMillis()

        val rejectedClient = rejectedYouTubeClientByVideoId[videoId]
        val preferMaintainedExtractor = videoId in preferMaintainedExtractorVideoIds
        StreamUrlCache.getEntry(videoId)?.takeIf { entry ->
            // Never resurrect the exact client that just returned a CDN 403.
            rejectedClient == null || entry.clientLabel != rejectedClient
        }?.let { entry ->
            val ttl = StreamUrlCache.ttlSeconds(videoId) ?: 0
            Log.d(TAG, "StreamUrlCache hit for $videoId (ttl=${ttl}s)")
            return ResolvedStream(
                url = entry.url,
                userAgent = entry.userAgent,
                requiresWebSessionHeaders = entry.requiresWebSessionHeaders,
            )
        }

        if (preferMaintainedExtractor) {
            resolveMaintainedExtractorStream(videoId, now)?.let { stream ->
                rejectedYouTubeClientByVideoId.remove(videoId)
                preferMaintainedExtractorVideoIds.remove(videoId)
                return stream
            }
            AppLogger.w(TAG, "Maintained extractor recovery failed for $videoId; trying Innertube fallback")
        }

        // Innertube is the primary resolver (matches Metrolist's YTPlayerUtils.playerResponseForPlayback).
        // Our client chain now includes ANDROID_VR_1_43 / ANDROID_VR_1_61 which return plain CDN
        // URLs that never need n-parameter descrambling — fast and reliable.
        // The maintained extractor chain remains a last resort for normal playback, but is
        // deliberately preferred after a CDN 403 above.
        val innertubeResult = runBlocking(Dispatchers.IO) {
            runCatching {
                YtMusicStreamResolver.resolveInnertube(
                    videoId = videoId,
                    excludedClientLabels = rejectedClient?.let(::setOf).orEmpty(),
                )
            }
        }
        val entry = innertubeResult.getOrNull()
        if (entry != null) {
            rejectedYouTubeClientByVideoId.remove(videoId)
            AppLogger.i(
                TAG,
                "Innertube resolved $videoId via ${entry.clientLabel} " +
                    "(cached ${(entry.expiryMs - now) / 1000}s)",
            )
            return ResolvedStream(
                url = entry.url,
                userAgent = entry.userAgent,
                requiresWebSessionHeaders = entry.requiresWebSessionHeaders,
            )
        }
        AppLogger.w(TAG, "Innertube failed for $videoId: ${innertubeResult.exceptionOrNull()?.message}")

        resolveMaintainedExtractorStream(videoId, now)?.let { return it }

        val err = "Innertube, PipePipe, and BravePipe all failed to resolve stream for $videoId"
        AppLogger.e(TAG, err, innertubeResult.exceptionOrNull())
        error(err)
    }

    private fun resolveMaintainedExtractorStream(videoId: String, now: Long): ResolvedStream? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val extracted = runBlocking(Dispatchers.IO) {
            runCatching { NewPipeRepository.resolveVerifiedAudioStream(watchUrl) }
        }.getOrElse { error ->
            AppLogger.w(TAG, "Maintained extractor failed for $videoId: ${error.message}")
            return null
        } ?: return null

        StreamUrlCache.put(
            videoId = videoId,
            url = extracted.url,
            userAgent = extracted.userAgent,
            expiryMs = now + 3_600_000L,
            clientLabel = extracted.resolverLabel,
        )
        AppLogger.i(TAG, "${extracted.resolverLabel} fallback resolved $videoId after range validation")
        return ResolvedStream(
            url = extracted.url,
            userAgent = extracted.userAgent,
            requiresWebSessionHeaders = false,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player ?: return
        if (!p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        bufferedPrefetchWriters.values.forEach { it.cancel() }
        bufferedPrefetchJobs.values.forEach { it.cancel() }
        bufferedPrefetchWriters.clear()
        bufferedPrefetchJobs.clear()
        ioScope.cancel()
        audioFx?.release(); audioFx = null
        xfPlayer?.stop(); xfPlayer?.release(); xfPlayer = null
        xfHandoffXfPlayer?.stop(); xfHandoffXfPlayer?.release(); xfHandoffXfPlayer = null
        session?.run { player.release(); release(); session = null }
        super.onDestroy()
    }



    private fun buildCustomLayout(): List<CommandButton> {
        val repeatMode = session?.player?.repeatMode ?: Player.REPEAT_MODE_OFF
        val likeIcon = if (isCurrentLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        val repeatIcon = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            else                   -> CommandButton.ICON_REPEAT_OFF
        }
        val likeBtn = CommandButton.Builder(likeIcon)
            .setSessionCommand(LIKE_COMMAND)
            .setDisplayName(if (isCurrentLiked) "Unlike" else "Like")
            .build()
        val repeatBtn = CommandButton.Builder(repeatIcon)
            .setSessionCommand(REPEAT_COMMAND)
            .setDisplayName("Repeat")
            .build()
        return listOf(likeBtn, repeatBtn)
    }

    private fun refreshLikedState() {
        val url = session?.player?.currentMediaItem?.mediaId ?: return
        ioScope.launch {
            val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
            isCurrentLiked = dao.isLiked(url).first() ?: false
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                session?.setCustomLayout(buildCustomLayout())
            }
        }
    }

    /**
     * Warm the active queue item and a short runway of following tracks. Signed URLs are resolved
     * for the full runway, while the immediate following tracks also receive cached audio bytes.
     */
    private fun prefetchUpcomingStreams() {
        if (!::exoPlayer.isInitialized) return
        var itemIndex = exoPlayer.currentMediaItemIndex
        if (itemIndex < 0 || exoPlayer.mediaItemCount == 0) return

        // Let Media3 choose the next index so shuffled queues, repeat-all wrapping, and repeat-one
        // use the exact same navigation order as the player itself.
        val visitedIndices = mutableSetOf<Int>()
        val timeline = exoPlayer.currentTimeline
        val videoIds = buildList {
            while (
                size < YtMusicStreamResolver.PLAYBACK_LOOKAHEAD_COUNT &&
                itemIndex >= 0 &&
                visitedIndices.add(itemIndex)
            ) {
                videoIdFor(exoPlayer.getMediaItemAt(itemIndex))?.let(::add)
                itemIndex = timeline.getNextWindowIndex(
                    itemIndex,
                    exoPlayer.repeatMode,
                    exoPlayer.shuffleModeEnabled,
                )
            }
        }
        YtMusicStreamResolver.primeQueue(videoIds, currentIndex = 0)
        prefetchBufferedQueueItems()
    }

    private fun prefetchMediaItems(items: Iterable<MediaItem>) {
        YtMusicStreamResolver.prime(items.mapNotNull(::videoIdFor))
    }

    private fun videoIdFor(item: MediaItem): String? =
        item.mediaMetadata.extras
            ?.getString(YtPlayback.EXTRA_VIDEO_ID)
            ?.takeIf { it.isNotBlank() }
            ?: youtubeVideoId(item.mediaId)

    /**
     * Resolve and cache the first bytes of a rolling set of upcoming tracks, not just their
     * signed URLs.
     * CacheWriter uses the same resolving/cache data source as the foreground player, so the
     * cached range is immediately reusable when Media3 transitions to that item.
     */
    private fun prefetchBufferedQueueItems() {
        if (!::dataSourceFactory.isInitialized || !::exoPlayer.isInitialized) return
        var itemIndex = exoPlayer.currentMediaItemIndex
        if (itemIndex < 0 || exoPlayer.mediaItemCount < 2) return

        val timeline = exoPlayer.currentTimeline
        val visitedIndices = mutableSetOf<Int>()
        val items = buildList {
            while (
                size < BUFFERED_PREFETCH_TRACK_COUNT + 1 &&
                itemIndex >= 0 &&
                visitedIndices.add(itemIndex)
            ) {
                add(exoPlayer.getMediaItemAt(itemIndex))
                itemIndex = timeline.getNextWindowIndex(
                    itemIndex,
                    exoPlayer.repeatMode,
                    exoPlayer.shuffleModeEnabled,
                )
            }
        }

        val upcomingItems = items.drop(1).take(BUFFERED_PREFETCH_TRACK_COUNT)
        val wantedVideoIds = upcomingItems.mapNotNull(::videoIdFor).toSet()
        cancelObsoleteBufferedPrefetches(wantedVideoIds)
        upcomingItems.forEach(::prefetchBufferedItem)
    }

    private fun prefetchBufferedItem(item: MediaItem) {
        val videoId = videoIdFor(item) ?: return
        val watchUrl = item.mediaId.takeIf { it.startsWith("http") } ?: return
        // Completed downloads are already served by downloadCache. Sending their watch URL to the
        // byte warmer would fetch HTML through the HTTP upstream instead of the downloaded audio.
        if (com.streamcloud.app.data.downloads.YtMusicDownloadUtil.isDownloaded(watchUrl)) return
        val job = ioScope.launch(start = CoroutineStart.LAZY) {
            try {
                bufferedPrefetchPermit.withPermit {
                    val dataSpec = DataSpec.Builder()
                        .setUri(watchUrl)
                        .setKey(watchUrl)
                        .setPosition(0L)
                        .setLength(BUFFERED_PREFETCH_BYTES)
                        .build()
                    val writer = CacheWriter(
                        bufferedCacheDataSourceFactory.createDataSource(),
                        dataSpec,
                        ByteArray(BUFFERED_PREFETCH_BUFFER_BYTES),
                        null,
                    )
                    bufferedPrefetchWriters[videoId] = writer
                    try {
                        writer.cache()
                    } finally {
                        bufferedPrefetchWriters.remove(videoId, writer)
                    }
                }
                AppLogger.i(TAG, "Buffered next-track audio for $videoId")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Speculative buffering is best effort. The foreground player still resolves and
                // streams this item normally if the CDN, cache, or resolver rejects the warm-up.
                AppLogger.w(TAG, "Buffered prefetch failed for $videoId: ${error.message}")
            } finally {
                bufferedPrefetchJobs.remove(videoId)
            }
        }
        if (bufferedPrefetchJobs.putIfAbsent(videoId, job) == null) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun cancelObsoleteBufferedPrefetches(wantedVideoIds: Set<String>) {
        bufferedPrefetchWriters
            .filterKeys { it !in wantedVideoIds }
            .values
            .forEach { it.cancel() }
        bufferedPrefetchJobs
            .filterKeys { it !in wantedVideoIds }
            .values
            .forEach { it.cancel() }
    }

    private suspend fun toggleLike() {
        val s = session ?: return
        // Capture player state on the main thread before switching to IO.
        val (url, meta) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val item = s.player.currentMediaItem
            Pair(item?.mediaId, item?.mediaMetadata)
        }
        if (url == null) return
        val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
        val currentlyLiked = dao.isLiked(url).first() ?: false
        if (currentlyLiked) {
            dao.setLikedAt(url, null)
        } else {
            val existing = dao.byUrl(url)
            if (existing != null) {
                dao.setLikedAt(url, System.currentTimeMillis())
            } else {
                dao.upsert(
                    TrackEntity(
                        url = url,
                        title = meta?.title?.toString() ?: "",
                        artist = meta?.artist?.toString() ?: "",
                        durationSec = 0L,
                        thumbnail = meta?.artworkUri?.toString(),
                        likedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        isCurrentLiked = !currentlyLiked
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            s.setCustomLayout(buildCustomLayout())
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(buildRoot(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = staticBrowsable(mediaId)
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
                )
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val fut = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            ioScope.launch {
                val children: List<MediaItem> = when {
                    parentId == ROOT_ID      -> rootChildren()
                    parentId == HOME_ID      -> homeChildren()
                    parentId == LIBRARY_ID   -> libraryChildren()
                    parentId == RECENT_ID    -> roomTracks { it.recent().first() }
                    parentId == ON_REPEAT_ID -> roomTracks { it.mostPlayed().first() }
                    parentId == LIKED_ID     -> likedChildren()
                    parentId == DOWNLOADED_ID -> roomTracks { it.downloaded().first() }
                    parentId == PLAYLISTS_ID  -> playlistsFolderChildren()
                    parentId == ALBUMS_ID     -> albumsFolderChildren()
                    parentId == ARTISTS_ID    -> artistsFolderChildren()
                    parentId == HOME_FEED_ID  -> homeFeedChildren()
                    parentId.startsWith(YT_PLAYLIST_PREFIX) -> ytPlaylistTracks(
                        parentId.removePrefix(YT_PLAYLIST_PREFIX),
                    )
                    parentId.startsWith(YT_ALBUM_PREFIX) -> ytPlaylistTracks(
                        parentId.removePrefix(YT_ALBUM_PREFIX),
                    )
                    parentId.startsWith(YT_ARTIST_PREFIX) -> ytArtistTopSongs(
                        parentId.removePrefix(YT_ARTIST_PREFIX),
                    )
                    parentId.startsWith(YT_HOME_SECTION_PREFIX) -> homeSectionItems(parentId)
                    parentId.startsWith(YT_HOME_BROWSE_PREFIX) -> ytPlaylistTracks(
                        parentId.removePrefix(YT_HOME_BROWSE_PREFIX),
                    )
                    else -> emptyList()
                }
                prefetchMediaItems(children)
                fut.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
            }
            return fut
        }


        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val voiceQuery = mediaItems
                .singleOrNull()
                ?.let(::requestedSearchQuery)
                ?: return Futures.immediateFuture(
                    mediaItems
                        .map(::attachUri)
                        .also(::prefetchMediaItems)
                        .toMutableList(),
                )
            val future = SettableFuture.create<MutableList<MediaItem>>()
            ioScope.launch {
                val matches = searchCarMusic(voiceQuery)
                if (matches.isEmpty()) {
                    AppLogger.w(TAG, "Android Auto voice search returned no songs for \"$voiceQuery\"")
                    future.set(mutableListOf())
                } else {
                    AppLogger.i(TAG, "Android Auto voice search matched \"$voiceQuery\" to ${matches.first().mediaMetadata.title}")
                    prefetchMediaItems(matches)
                    future.set(mutableListOf(matches.first()))
                }
            }
            return future
        }


        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val fut = SettableFuture.create<LibraryResult<Void>>()
            ioScope.launch {
                val items = searchCarMusic(query)
                session.notifySearchResultChanged(browser, query, items.size, params)
                fut.set(LibraryResult.ofVoid())
            }
            return fut
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val fut = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            ioScope.launch {
                val allItems = searchCarMusic(query)
                prefetchMediaItems(allItems)
                val items = ImmutableList.copyOf(
                    if (pageSize <= 0) allItems
                    else allItems.drop(page * pageSize).take(pageSize),
                )
                fut.set(LibraryResult.ofItemList(items, params))
            }
            return fut
        }


        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val fut = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            ioScope.launch {
                val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
                val recent = runCatching { dao.recent().first() }.getOrElse { emptyList() }
                val items = recent.map(::trackEntityItem)
                fut.set(MediaSession.MediaItemsWithStartPosition(items, 0, 0))
            }
            return fut
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val defaultResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            val sessionCommands = defaultResult.availableSessionCommands.buildUpon()
                .add(LIKE_COMMAND)
                .add(REPEAT_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_LIKE -> ioScope.launch { toggleLike() }
                ACTION_REPEAT -> {
                    val player = session.player
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    session.setCustomLayout(buildCustomLayout())
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }



    private fun buildRoot(): MediaItem = folder(ROOT_ID, "StreamCloud")

    private fun rootChildren(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        if (ytHomeFeed.sections.isNotEmpty())
            items += folder(HOME_FEED_ID, "YT Music Home")
        items += playlist(RECENT_ID,    "Recently Played")
        items += playlist(LIKED_ID,     "Liked Songs")
        items += playlist(ON_REPEAT_ID, "On Repeat")
        items += playlist(DOWNLOADED_ID,"Downloads")
        if (ytLibrary.playlists.isNotEmpty()) items += folder(PLAYLISTS_ID, "My Playlists")
        if (ytLibrary.albums.isNotEmpty())    items += folder(ALBUMS_ID,    "Albums")
        if (ytLibrary.artists.isNotEmpty())   items += folder(ARTISTS_ID,   "Artists")
        return items
    }

    private fun homeChildren(): List<MediaItem> = listOf(
        playlist(RECENT_ID,    "Recently Played"),
        playlist(ON_REPEAT_ID, "On Repeat"),
        playlist(LIKED_ID,     "Liked Songs"),
    )

    private fun libraryChildren(): List<MediaItem> {
        val items = mutableListOf(
            playlist(LIKED_ID,      "Liked Songs"),
            playlist(DOWNLOADED_ID, "Downloads"),
        )
        if (ytLibrary.playlists.isNotEmpty()) items += folder(PLAYLISTS_ID, "My Playlists")
        if (ytLibrary.albums.isNotEmpty())    items += folder(ALBUMS_ID,    "Albums")
        if (ytLibrary.artists.isNotEmpty())   items += folder(ARTISTS_ID,   "Artists")
        return items
    }

    private fun playlistsFolderChildren(): List<MediaItem> =
        ytLibrary.playlists.filter { !it.isAlbum }.map { pl ->
            MediaItem.Builder()
                .setMediaId("$YT_PLAYLIST_PREFIX${pl.id}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(pl.title)
                        .setSubtitle(pl.subtitle)
                        .setArtworkUri(pl.thumbnail?.let(Uri::parse))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build(),
                )
                .build()
        }

    private fun albumsFolderChildren(): List<MediaItem> =
        ytLibrary.albums.map { al ->
            MediaItem.Builder()
                .setMediaId("$YT_ALBUM_PREFIX${al.id}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(al.title)
                        .setSubtitle(al.subtitle)
                        .setArtworkUri(al.thumbnail?.let(Uri::parse))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                        .build(),
                )
                .build()
        }

    private fun artistsFolderChildren(): List<MediaItem> =
        ytLibrary.artists.map { ar ->
            MediaItem.Builder()
                .setMediaId("$YT_ARTIST_PREFIX${ar.channelId}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ar.name)
                        .setSubtitle(ar.subtitle)
                        .setArtworkUri(ar.thumbnail?.let(Uri::parse))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                        .build(),
                )
                .build()
        }

    private fun homeFeedChildren(): List<MediaItem> =
        ytHomeFeed.sections.mapIndexedNotNull { idx, section ->
            val title = when (section) {
                is HomeSection.PlaylistRail -> section.title
                is HomeSection.SongRail     -> section.title
                is HomeSection.MoodChips    -> return@mapIndexedNotNull null
            }
            if (title.isBlank()) return@mapIndexedNotNull null
            folder("$YT_HOME_SECTION_PREFIX$idx", title)
        }

    private fun homeSectionItems(sectionId: String): List<MediaItem> {
        val idx = sectionId.removePrefix(YT_HOME_SECTION_PREFIX).toIntOrNull() ?: return emptyList()
        return when (val section = ytHomeFeed.sections.getOrNull(idx) ?: return emptyList()) {
            is HomeSection.SongRail -> section.items.map(::ytmSongItem)
            is HomeSection.PlaylistRail -> section.items.mapNotNull { pl ->
                val isVideoId = pl.id.length == 11 && pl.id.matches(Regex("[a-zA-Z0-9_-]+"))
                    && !pl.id.startsWith("VL") && !pl.id.startsWith("MPREb_") && !pl.id.startsWith("PL")
                if (isVideoId) {
                    val url = "https://music.youtube.com/watch?v=${pl.id}"
                    ytmSong(pl.id, pl.title, pl.subtitle ?: "", null, pl.thumbnail, url, pl.isVideo)
                } else {
                    MediaItem.Builder()
                        .setMediaId("$YT_HOME_BROWSE_PREFIX${pl.id}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(pl.title)
                                .setSubtitle(pl.subtitle)
                                .setArtworkUri(pl.thumbnail?.let(Uri::parse))
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                .build(),
                        )
                        .build()
                }
            }
            is HomeSection.MoodChips -> emptyList()
        }
    }


    private suspend fun likedChildren(): List<MediaItem> {
        val ytmLiked = ytLibrary.likedSongs
        if (ytmLiked.isNotEmpty()) return ytmLiked.map(::ytmSongItem)
        val local = runCatching {
            LibraryDb.get(this@MusicPlaybackService).tracks().liked().first()
        }.getOrElse { emptyList() }
        return local.map(::trackEntityItem)
    }


    private suspend fun ytPlaylistTracks(playlistId: String): List<MediaItem> {
        return try {
            val cookie = sl.settings.ytMusicCookie.first()
            if (cookie.isBlank()) return emptyList()
            YtMusicLibraryRepository.playlistTracks(cookie, playlistId).map(::ytmSongItem)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private suspend fun ytArtistTopSongs(channelId: String): List<MediaItem> {
        return try {
            val page = YtMusicArtistRepository.load(channelId) ?: return emptyList()
            page.topTracks.map { t ->
                val videoId = t.url.substringAfter("v=").substringBefore("&")
                ytmSong(videoId, t.title, t.uploader, null, t.thumbnail, t.url, t.isVideo)
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private suspend fun roomTracks(query: suspend (dao: com.streamcloud.app.data.library.TrackDao) -> List<TrackEntity>): List<MediaItem> =
        runCatching {
            val dao = LibraryDb.get(this).tracks()
            query(dao).map(::trackEntityItem)
        }.getOrElse { emptyList() }



    private fun trackEntityItem(t: TrackEntity): MediaItem = MediaItem.Builder()
        .setMediaId(t.url)
        .setUri(t.url)
        .setCustomCacheKey(t.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setArtworkUri(t.thumbnail?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun ytmSongItem(s: YtmSong): MediaItem {
        val url = "https://music.youtube.com/watch?v=${s.videoId}"
        return ytmSong(s.videoId, s.title, s.artist, s.album, s.thumbnail, url, s.isVideo)
    }

    private fun ytmSong(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        thumbnail: String?,
        watchUrl: String,
        isMusicVideo: Boolean = false,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(watchUrl)
        .setUri(watchUrl)
        .setCustomCacheKey(watchUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(thumbnail?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply {
                    putString(YtPlayback.EXTRA_VIDEO_ID, videoId)
                    putString(YtPlayback.EXTRA_WATCH_URL, watchUrl)
                    putBoolean(YtPlayback.EXTRA_IS_MUSIC_VIDEO, isMusicVideo)
                })
                .build(),
        )
        .build()

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun playlist(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        )
        .build()

    private fun staticBrowsable(id: String): MediaItem? = when {
        id == ROOT_ID       -> buildRoot()
        id == HOME_ID       -> folder(HOME_ID,       "Home")
        id == LIBRARY_ID    -> folder(LIBRARY_ID,    "Your Library")
        id == PLAYLISTS_ID  -> folder(PLAYLISTS_ID,  "My Playlists")
        id == ALBUMS_ID     -> folder(ALBUMS_ID,     "Albums")
        id == ARTISTS_ID    -> folder(ARTISTS_ID,    "Artists")
        id == HOME_FEED_ID  -> folder(HOME_FEED_ID,  "YT Music Home")
        id == RECENT_ID     -> playlist(RECENT_ID,    "Recently Played")
        id == ON_REPEAT_ID  -> playlist(ON_REPEAT_ID, "On Repeat")
        id == LIKED_ID      -> playlist(LIKED_ID,     "Liked Songs")
        id == DOWNLOADED_ID -> playlist(DOWNLOADED_ID,"Downloads")
        id.startsWith(YT_HOME_SECTION_PREFIX) -> {
            val idx = id.removePrefix(YT_HOME_SECTION_PREFIX).toIntOrNull() ?: return null
            val section = ytHomeFeed.sections.getOrNull(idx) ?: return null
            val title = when (section) {
                is HomeSection.PlaylistRail -> section.title
                is HomeSection.SongRail     -> section.title
                is HomeSection.MoodChips    -> return null
            }
            folder(id, title)
        }
        id.startsWith(YT_PLAYLIST_PREFIX) -> {
            val plId = id.removePrefix(YT_PLAYLIST_PREFIX)
            val pl = ytLibrary.playlists.find { it.id == plId }
            if (pl != null) ytPlaylistBrowsable(id, pl) else null
        }
        id.startsWith(YT_ALBUM_PREFIX) -> {
            val alId = id.removePrefix(YT_ALBUM_PREFIX)
            val al = ytLibrary.albums.find { it.id == alId }
            if (al != null) ytPlaylistBrowsable(id, al) else null
        }
        id.startsWith(YT_ARTIST_PREFIX) -> {
            val arId = id.removePrefix(YT_ARTIST_PREFIX)
            val ar = ytLibrary.artists.find { it.channelId == arId }
            if (ar != null) MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ar.name)
                        .setSubtitle(ar.subtitle)
                        .setArtworkUri(ar.thumbnail?.let(Uri::parse))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                        .build()
                ).build()
            else null
        }
        else -> null
    }

    private fun ytPlaylistBrowsable(mediaId: String, pl: YtmPlaylist): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(pl.title)
                    .setSubtitle(pl.subtitle)
                    .setArtworkUri(pl.thumbnail?.let(Uri::parse))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build(),
            )
            .build()

    /**
     * A YouTube CDN URL is signed for the Innertube client that minted it. Google can reject the
     * URL with 403 before `expire=` when that client is gated or its session token is invalidated.
     * Refresh once with the next compatible client rather than allowing ExoPlayer to keep using
     * the invalid cached URL.
     */
    private fun retryWithFreshYoutubeStream(error: androidx.media3.common.PlaybackException): Boolean {
        val rangeReadFailure = isYoutubeRangeReadFailure(error)
        if (!isYoutubeCdn403(error) && !rangeReadFailure) return false

        val videoId = youtubeVideoId(exoPlayer.currentMediaItem?.mediaId) ?: return false
        if (!youtubeStreamRetriedVideoIds.add(videoId)) {
            AppLogger.w(
                TAG,
                "YouTube stream read failed for $videoId after automatic refresh; not retrying again",
            )
            return false
        }

        val rejected = StreamUrlCache.remove(videoId)
        rejected?.clientLabel?.takeIf { it.isNotBlank() }?.let { client ->
            rejectedYouTubeClientByVideoId[videoId] = client
        }
        preferMaintainedExtractorVideoIds.add(videoId)
        // A POSITION_OUT_OF_RANGE error can be caused by a stale partial cache span. Do not carry
        // its byte position into the newly resolved URL; the fresh stream must start at byte zero.
        val resumePosition = if (rangeReadFailure) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)
        val currentItem = exoPlayer.currentMediaItem
        val cacheKey = currentItem?.localConfiguration?.customCacheKey
            ?: currentItem?.localConfiguration?.uri?.toString()
            ?: currentItem?.mediaId
            ?: "https://music.youtube.com/watch?v=$videoId"
        val preservesCompletedDownload =
            com.streamcloud.app.data.downloads.YtMusicDownloadUtil.isDownloaded(cacheKey)
        runCatching { playerCache.removeResource(cacheKey) }
            .onFailure { cacheError ->
                AppLogger.w(TAG, "Could not evict partial player cache for $videoId: ${cacheError.message}")
            }
        if (!preservesCompletedDownload) {
            runCatching { downloadCache.removeResource(cacheKey) }
                .onFailure { cacheError ->
                    AppLogger.w(TAG, "Could not evict partial download cache for $videoId: ${cacheError.message}")
                }
        }
        AppLogger.w(
            TAG,
            "YouTube ${if (rangeReadFailure) "range read failure" else "CDN 403"} for $videoId; " +
                "discarded ${rejected?.clientLabel ?: "uncached"} URL and stale " +
                "${if (preservesCompletedDownload) "player" else "player/download"} cache data; " +
                "trying the independent extractor chain",
        )

        // ResolvingDataSource invokes resolveStreamUrl() on prepare. It sees the removed cache
        // entry and the extractor-recovery marker, so it does not spend its one retry on another
        // URL from the client family that just failed.
        exoPlayer.prepare()
        if (resumePosition > 0L) exoPlayer.seekTo(resumePosition)
        exoPlayer.playWhenReady = true
        return true
    }

    private fun isYoutubeCdn403(error: androidx.media3.common.PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun isYoutubeRangeReadFailure(error: androidx.media3.common.PlaybackException): Boolean {
        if (error.errorCode ==
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        ) {
            return true
        }
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 416) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun youtubeVideoId(mediaId: String?): String? {
        val raw = mediaId.orEmpty()
        if (!raw.contains("youtube.com/watch")) return null
        return raw.substringAfter("v=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    private fun requestedSearchQuery(item: MediaItem): String? =
        item.requestMetadata.searchQuery
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: item.mediaMetadata.title
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        item.mediaId.isBlank() &&
                        item.localConfiguration?.uri == null
                }

    /**
     * Android Auto calls onSearch and onGetSearchResult as separate requests, while voice play
     * arrives through onAddMediaItems with RequestMetadata.searchQuery. Keep the result briefly
     * in-process so every route returns the exact same playable media item without duplicate
     * YouTube searches.
     */
    private suspend fun searchCarMusic(query: String): List<MediaItem> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()
        carSearchResults[normalizedQuery]?.let { return it }
        val tracks = runCatching { NewPipeRepository.searchSongs(normalizedQuery) }
            .onFailure { AppLogger.w(TAG, "Android Auto search failed for \"$normalizedQuery\": ${it.message}") }
            .getOrDefault(emptyList())
        val items = tracks.map { track ->
            val videoId = track.url.substringAfter("v=").substringBefore("&")
            ytmSong(
                videoId = videoId,
                title = track.title,
                artist = track.uploader,
                album = null,
                thumbnail = track.thumbnail,
                watchUrl = track.url,
                isMusicVideo = track.isVideo,
            )
        }
        if (carSearchResults.size >= 24) carSearchResults.clear()
        carSearchResults[normalizedQuery] = items
        return items
    }

    private fun attachUri(item: MediaItem): MediaItem {
        if (item.localConfiguration?.uri != null) return item
        val id = item.mediaId.ifBlank { return item }
        return item.buildUpon().setUri(id).setCustomCacheKey(id).build()
    }

    companion object {
        private const val TAG    = "MusicPlaybackService"
        // Keep a small byte runway for the immediate next songs. A 50 MiB speculative warm-up
        // competes with a cold first stream on constrained mobile and TV connections.
        private const val BUFFERED_PREFETCH_TRACK_COUNT = 2
        private const val BUFFERED_PREFETCH_BYTES = 256L * 1024L
        private const val BUFFERED_PREFETCH_BUFFER_BYTES = 64 * 1024
        private const val STREAM_WEB_SESSION_HEADER = "X-StreamCloud-Web-Session"
        private const val STREAM_WEB_SESSION_VALUE = "1"
        const val ROOT_ID        = "streamcloud_root"
        const val HOME_ID        = "streamcloud_home"
        const val LIBRARY_ID     = "streamcloud_library"
        const val RECENT_ID      = "streamcloud_recent"
        const val ON_REPEAT_ID   = "streamcloud_on_repeat"
        const val LIKED_ID       = "streamcloud_liked"
        const val DOWNLOADED_ID  = "streamcloud_downloaded"
        const val PLAYLISTS_ID   = "streamcloud_playlists"
        const val ALBUMS_ID      = "streamcloud_albums"
        const val ARTISTS_ID     = "streamcloud_artists"
        const val HOME_FEED_ID   = "streamcloud_home_feed"
        const val YT_PLAYLIST_PREFIX     = "ytpl_"
        const val YT_ALBUM_PREFIX        = "ytalbum_"
        const val YT_ARTIST_PREFIX       = "ytartist_"
        const val YT_HOME_SECTION_PREFIX = "yths_"
        const val YT_HOME_BROWSE_PREFIX  = "ythbr_"

        const val ACTION_LIKE   = "com.streamcloud.app.action.like"
        const val ACTION_REPEAT = "com.streamcloud.app.action.repeat"
        val LIKE_COMMAND   = SessionCommand(ACTION_LIKE,   Bundle())
        val REPEAT_COMMAND = SessionCommand(ACTION_REPEAT, Bundle())
    }
}
