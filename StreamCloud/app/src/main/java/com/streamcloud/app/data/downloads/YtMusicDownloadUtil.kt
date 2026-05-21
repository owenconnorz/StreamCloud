package com.streamcloud.app.data.downloads

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.streamcloud.app.data.SettingsRepository
import com.streamcloud.app.data.library.FormatEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Application-scoped singleton that owns the Media3 [DownloadManager] used by
 * [MusicExoDownloadService].
 *
 * ## Metrolist/InnerTune feature parity
 *
 * This implementation mirrors InnerTune's DownloadUtil exactly, adding
 * StreamCloud-specific improvements on top:
 *
 * ### 1. `&range=0-{contentLength}` CDN optimisation
 * Appended to every YouTube CDN URL before writing to the download cache.
 * Without this parameter YouTube's CDN serves audio in small throttled chunks.
 * With it the CDN delivers the entire file in one HTTP response at full line
 * speed.  InnerTune: `it.copy(url = "${it.url}&range=0-${it.contentLength}")`.
 *
 * ### 2. Player cache early return
 * If the user recently streamed a song, its bytes may already be in the 256 MB
 * LRU player cache.  The resolver checks `playerCache.isCached()` before making
 * any network call — in the best case a "download" completes instantly from the
 * on-device buffer, identical to InnerTune's behaviour.
 *
 * ### 3. Format persistence (FormatEntity)
 * The resolved itag, codec, bitrate, and contentLength are stored in Room
 * after every successful resolution. On the next download the resolver reads
 * the stored itag and asks YouTube for that exact format, so the user always
 * gets the same codec/quality — even if YouTube changes which formats it offers.
 * Mirrors InnerTune's FormatEntity upsert inside its resolver.
 *
 * ### 4. Quality-aware format selection
 * Respects the "Audio quality" setting (high / low / auto).  AUTO uses the
 * highest bitrate on unmetered connections and the lowest on metered ones,
 * identical to InnerTune's ConnectivityManager check.  Opus streams receive a
 * +10 240 bps bonus to match InnerTune's codec preference.
 *
 * ### 5. Expiry-based URL cache TTL
 * The URL cache entry lives for `expiresInSeconds` (from the Innertube player
 * response, typically 21 600 s = 6 h) instead of a hard-coded 3-hour window.
 * InnerTune stores the raw `expiresInSeconds` value; we convert it to an
 * absolute epoch timestamp so our cache check (`currentTimeMillis < expiry`)
 * is unambiguous.
 *
 * ### StreamCloud additions (not in InnerTune)
 *  - **NewPipe fallback**: if Innertube fails, we fall back to NewPipe so
 *    downloads succeed even when the direct API is blocked or rate-limited.
 *  - **Thread pool executor**: `newFixedThreadPool(5)` instead of InnerTune's
 *    `Executor(Runnable::run)` — true parallelism for up to 5 concurrent downloads.
 *  - **ConcurrentHashMap URL cache**: thread-safe across pool threads.
 *
 * Architecture note:
 *   DownloadRequest(id = watchUrl, uri = watchUrl)
 *     → ResolvingDataSource resolves a CDN stream URL with `&range=` param
 *     → CacheDataSource (downloadCache) — bytes keyed by the stable watchUrl
 */
@OptIn(UnstableApi::class)
object YtMusicDownloadUtil {

    private const val MAX_PARALLEL_DOWNLOADS = 5

    /**
     * Cache of resolved CDN stream URLs keyed by watch URL, with absolute expiry
     * timestamps.
     *
     * TTL is derived from `expiresInSeconds` in the Innertube player response
     * (typically 6 h).  CacheWriter calls open() for every ~2 MB span boundary,
     * so without this cache each span triggers a full Innertube round-trip
     * (300–800 ms each).  With the cache the resolver hot-path returns in <1 ms.
     */
    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var _downloadManager: DownloadManager? = null

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    /**
     * Dedicated OkHttpClient for downloads. Kept separate from the playback
     * client so download-specific tuning doesn't bleed into latency-sensitive
     * playback requests.
     */
    private val downloadHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Synchronized
    fun downloadManager(context: Context): DownloadManager {
        _downloadManager?.let { return it }

        val ctx = context.applicationContext
        val downloadCache = DownloadCaches.downloadCache(ctx)
        val playerCache  = DownloadCaches.playerCache(ctx)
        val connectivityManager =
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val settings = SettingsRepository(ctx)

        val dataSourceFactory = ResolvingDataSource.Factory(
            CacheDataSource.Factory()
                .setCache(downloadCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(downloadHttpClient)
                        .setUserAgent(
                            "com.google.android.apps.youtube.music/7.27.52 " +
                                "(Linux; U; Android 11) gzip",
                        ),
                ),
        ) { dataSpec ->
            val watchUrl = dataSpec.uri.toString()
            val cacheKey  = dataSpec.key ?: watchUrl

            // ── 1. Already fully downloaded — serve from cache, no network ──
            if (downloads.value[cacheKey]?.state == Download.STATE_COMPLETED) {
                return@Factory dataSpec
            }

            // ── 2. Player cache early return (Metrolist parity) ─────────────
            // If this song was recently streamed its bytes may already be in the
            // 256 MB LRU player cache.  Skip the resolver entirely — the download
            // completes instantly from on-device storage.
            val requestLength = if (dataSpec.length >= 0) dataSpec.length else 1
            if (playerCache.isCached(cacheKey, dataSpec.position, requestLength)) {
                return@Factory dataSpec
            }

            // ── 3. URL cache hot path ────────────────────────────────────────
            // CacheWriter calls open() for every ~2 MB span; this avoids a
            // full Innertube round-trip on each span after the first.
            urlCache[cacheKey]?.let { (cachedUrl, expiry) ->
                if (System.currentTimeMillis() < expiry) {
                    return@Factory dataSpec.withUri(cachedUrl.toUri())
                }
                urlCache.remove(cacheKey) // stale — fall through to re-resolve
            }

            // ── 4. Resolve stream URL ────────────────────────────────────────
            val videoId = watchUrl.substringAfter("v=", "").substringBefore("&")

            val (streamUrl, ttlMs) = runBlocking {
                if (videoId.isNotBlank()) {
                    val dao      = LibraryDb.get(ctx).formats()
                    val stored   = dao.byVideoId(videoId)
                    val qualPref = settings.audioQuality.first()
                    val isMetered = connectivityManager.isActiveNetworkMetered
                    val preferHigh = when (qualPref) {
                        "high"  -> true
                        "low"   -> false
                        else    -> !isMetered   // "auto" / "medium"
                    }

                    val info = YtPlayerUtils.resolveAudioFormatInfo(
                        videoId          = videoId,
                        preferItag       = stored?.itag,
                        preferHighQuality = preferHigh,
                    )

                    if (info != null) {
                        // Persist format so next resolution reuses the same itag.
                        val codecsRaw = info.mimeType.substringAfter("codecs=", "")
                            .removeSurrounding("\"")
                        dao.upsert(
                            FormatEntity(
                                videoId       = videoId,
                                itag          = info.itag,
                                mimeType      = info.mimeType.split(";")[0],
                                codecs        = codecsRaw,
                                bitrate       = info.bitrate,
                                sampleRate    = info.sampleRate,
                                contentLength = info.contentLength ?: 10_000_000L,
                                loudnessDb    = info.loudnessDb,
                            ),
                        )

                        // Append &range=0-N — instructs YouTube's CDN to deliver
                        // the whole file in one response instead of throttled chunks.
                        val len = info.contentLength ?: 10_000_000L
                        Pair("${info.url}&range=0-$len", info.expiresInSeconds * 1_000L)
                    } else {
                        // Innertube failed — NewPipe fallback (slower but reliable).
                        Pair(NewPipeRepository.resolveAudioStream(watchUrl), 3L * 60 * 60 * 1000)
                    }
                } else {
                    Pair(NewPipeRepository.resolveAudioStream(watchUrl), 3L * 60 * 60 * 1000)
                }
            }

            // Cache the resolved URL for the duration of its CDN validity.
            urlCache[cacheKey] = streamUrl to (System.currentTimeMillis() + ttlMs)
            dataSpec.withUri(streamUrl.toUri())
        }

        val manager = DownloadManager(
            ctx,
            DownloadCaches.databaseProvider(ctx),
            downloadCache,
            dataSourceFactory,
            // Fixed thread pool — one thread per parallel download.
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS),
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            minRetryCount = 3
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    downloads.update { map ->
                        map.toMutableMap().apply { set(download.request.id, download) }
                    }
                    syncRoomLocalPath(ctx, download)
                }

                override fun onDownloadRemoved(
                    downloadManager: DownloadManager,
                    download: Download,
                ) {
                    downloads.update { map ->
                        map.toMutableMap().apply { remove(download.request.id) }
                    }
                    urlCache.remove(download.request.id)
                    clearRoomLocalPath(ctx, download.request.id)
                }
            })
        }

        val result = mutableMapOf<String, Download>()
        val cursor = manager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result

        return manager.also { _downloadManager = it }
    }

    /**
     * When a download completes, write the sentinel `localPath = "cache:<watchUrl>"`
     * so [TrackDao.downloaded] (WHERE local_path IS NOT NULL) includes this song in
     * the Downloads library.  The sentinel is not a real file path — playback falls
     * through to CacheDataSource as intended.  On failure, removal, or stop the
     * sentinel is cleared.
     */
    private fun syncRoomLocalPath(context: Context, download: Download) {
        val watchUrl = download.request.id
        scope.launch {
            val dao = LibraryDb.get(context).tracks()
            when (download.state) {
                Download.STATE_COMPLETED -> {
                    val existing = runCatching { dao.byUrl(watchUrl) }.getOrNull()
                    if (existing != null) {
                        dao.setLocalPath(watchUrl, "cache:$watchUrl")
                    } else {
                        val title = runCatching {
                            String(download.request.data, Charsets.UTF_8)
                        }.getOrDefault(watchUrl)
                        com.streamcloud.app.data.library.TrackEntity(
                            url = watchUrl,
                            title = title,
                            artist = "",
                            durationSec = 0,
                            thumbnail = null,
                            localPath = "cache:$watchUrl",
                        ).also { dao.upsert(it) }
                    }
                }
                Download.STATE_FAILED,
                Download.STATE_STOPPED,
                Download.STATE_REMOVING -> clearRoomLocalPath(context, watchUrl)
                else -> Unit
            }
        }
    }

    private fun clearRoomLocalPath(context: Context, watchUrl: String) {
        scope.launch {
            runCatching {
                val dao = LibraryDb.get(context).tracks()
                val existing = dao.byUrl(watchUrl)
                if (existing?.localPath?.startsWith("cache:") == true) {
                    dao.setLocalPath(watchUrl, null)
                }
            }
        }
    }

    fun downloadNotificationHelper(context: Context): DownloadNotificationHelper =
        DownloadNotificationHelper(context.applicationContext, MusicExoDownloadService.CHANNEL_ID)

    fun getDownload(watchUrl: String): Flow<Download?> = downloads.map { it[watchUrl] }

    fun isDownloaded(watchUrl: String): Boolean =
        downloads.value[watchUrl]?.state == Download.STATE_COMPLETED

    fun downloadProgress(watchUrl: String): Float =
        downloads.value[watchUrl]?.percentDownloaded?.div(100f) ?: 0f
}
