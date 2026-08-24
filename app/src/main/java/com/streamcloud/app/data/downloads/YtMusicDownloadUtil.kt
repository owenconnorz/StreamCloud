package com.streamcloud.app.data.downloads

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import com.streamcloud.app.data.AppLogger
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.StreamUrlCache
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import com.streamcloud.app.data.ytmusic.YtMusicStreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
object YtMusicDownloadUtil {
    private const val TAG = "YtMusicDownloadUtil"
    private const val STREAM_WEB_SESSION_HEADER = "X-StreamCloud-Web-Session"
    private const val STREAM_WEB_SESSION_VALUE = "1"
    private const val FALLBACK_STREAM_USER_AGENT =
        "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var _downloadManager: DownloadManager? = null

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private data class DownloadStream(
        val url: String,
        val userAgent: String,
        val requiresWebSessionHeaders: Boolean,
    )

    private val downloadHttpClient: OkHttpClient by lazy {
        // The player response binds Googlevideo URLs to the source IP that requested them.
        // Keeping downloads on IPv4 matches the playback transport and avoids CDN 403s caused
        // by Android/OkHttp switching the actual media request to IPv6.
        val ipv4OnlyDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                okhttp3.Dns.SYSTEM.lookup(hostname)
                    .filter { it is java.net.Inet4Address }
                    .ifEmpty { okhttp3.Dns.SYSTEM.lookup(hostname) }
        }

        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .dns(ipv4OnlyDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val useWebSessionHeaders =
                    request.header(STREAM_WEB_SESSION_HEADER) == STREAM_WEB_SESSION_VALUE
                val host = request.url.host
                val builder = request.newBuilder()
                    .removeHeader(STREAM_WEB_SESSION_HEADER)

                // WEB_REMIX/PoToken URLs are bound to the browser session that minted them.
                // Android-client and maintained-extractor URLs must deliberately stay anonymous.
                val cookie = YtPlayerUtils.ytMusicCookie
                if (cookie.isNotBlank() && useWebSessionHeaders) {
                    when {
                        host.endsWith("music.youtube.com") -> {
                            builder.header("Cookie", cookie)
                                .header("Origin", "https://music.youtube.com")
                                .header("Referer", "https://music.youtube.com/")
                        }
                        host.contains("googlevideo.com") -> {
                            builder.header("Cookie", cookie)
                                .header("Origin", "https://music.youtube.com")
                                .header("Referer", "https://music.youtube.com/")
                            if (request.url.queryParameter("pot") != null) {
                                builder.header("Sec-Fetch-Dest", "audio")
                                    .header("Sec-Fetch-Mode", "cors")
                                    .header("Sec-Fetch-Site", "cross-site")
                            }
                        }
                    }
                }
                if (useWebSessionHeaders) {
                    YtPlayerUtils.cachedVisitorData?.let {
                        builder.header("X-Goog-Visitor-Id", it)
                    }
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    private val downloadProbeHttpClient: OkHttpClient by lazy {
        downloadHttpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    @Synchronized
    fun downloadManager(context: Context): DownloadManager {
        _downloadManager?.let { return it }

        val ctx = context.applicationContext
        val downloadCache = DownloadCaches.downloadCache(ctx)
        val playerCache  = DownloadCaches.playerCache(ctx)
        // Use playerCache (not downloadCache) in the upstream chain.
        // DownloadManager already writes directly to downloadCache; having downloadCache
        // in the factory too causes simultaneous read/write lock contention that
        // serialises every download segment — the main reason downloads were slow.
        // playerCache lets us reuse data already buffered from streaming, harmlessly.
        val dataSourceFactory = ResolvingDataSource.Factory(
            CacheDataSource.Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(downloadHttpClient)
                        .setUserAgent(FALLBACK_STREAM_USER_AGENT),
                ),
        ) { dataSpec ->
            // Use the stable video ID, like OpenTune. Old URL-keyed requests remain readable so
            // users do not lose a queued or completed download after updating the app.
            val requestId = dataSpec.key ?: dataSpec.uri.toString()
            val videoId = videoIdFromDownloadId(requestId)
            val watchUrl = watchUrlForDownloadId(requestId)

            if (findDownload(downloads.value, requestId)?.state == Download.STATE_COMPLETED) {
                return@Factory dataSpec
            }





            val requestLength = if (dataSpec.length >= 0) dataSpec.length else 1
            if (playerCache.isCached(requestId, dataSpec.position, requestLength)) {
                return@Factory dataSpec
            }

            val stream = resolveDownloadStream(videoId, watchUrl)
            dataSpec.buildUpon()
                .setUri(stream.url.toUri())
                .setHttpRequestHeaders(
                    dataSpec.httpRequestHeaders + mapOf(
                        "User-Agent" to stream.userAgent,
                        STREAM_WEB_SESSION_HEADER to
                            if (stream.requiresWebSessionHeaders) STREAM_WEB_SESSION_VALUE else "0",
                    ),
                )
                .build()
        }

        val manager = DownloadManager(
            ctx,
            DownloadCaches.databaseProvider(ctx),
            downloadCache,
            dataSourceFactory,
            // Inline executor: tasks run on DownloadManager's own thread.
            // A thread pool here adds queuing overhead and can stall when
            // runBlocking URL-resolution holds pool threads — matching Metrolist.
            Executor(Runnable::run),
        ).apply {
            maxParallelDownloads = 3
            minRetryCount = 5
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
                    if (download.state == Download.STATE_FAILED) {
                        AppLogger.w(
                            TAG,
                            "Download failed for ${download.request.id}: ${finalException?.message}",
                        )
                    }
                }

                override fun onDownloadRemoved(
                    downloadManager: DownloadManager,
                    download: Download,
                ) {
                    downloads.update { map ->
                        map.toMutableMap().apply { remove(download.request.id) }
                    }
                    clearRoomLocalPath(ctx, watchUrlForDownloadId(download.request.id))
                }
            })
        }

        val result = mutableMapOf<String, Download>()
        val cursor = manager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
        migrateLegacyPendingDownloads(manager, result.values)

        return manager.also { _downloadManager = it }
    }

    /**
     * Versions before the OpenTune-style ID contract used full watch URLs as DownloadRequest
     * identifiers. Restart unfinished work under the stable video ID so an old stuck request
     * cannot keep its UI row permanently queued after an app update.
     */
    private fun migrateLegacyPendingDownloads(
        manager: DownloadManager,
        existingDownloads: Collection<Download>,
    ) {
        existingDownloads
            .filter { download ->
                download.request.id.startsWith("http", ignoreCase = true) &&
                    download.state in setOf(
                        Download.STATE_QUEUED,
                        Download.STATE_DOWNLOADING,
                        Download.STATE_RESTARTING,
                        Download.STATE_FAILED,
                    )
            }
            .forEach { legacy ->
                val videoId = videoIdFromDownloadId(legacy.request.id)
                if (videoId.isBlank() || videoId.startsWith("http", ignoreCase = true)) return@forEach
                AppLogger.i(TAG, "Migrating legacy download ${legacy.request.id} to $videoId")
                manager.removeDownload(legacy.request.id)
                manager.addDownload(
                    DownloadRequest.Builder(videoId, videoId.toUri())
                        .setData(legacy.request.data)
                        .setCustomCacheKey(videoId)
                        .build(),
                )
            }
    }

    private fun resolveDownloadStream(videoId: String, watchUrl: String): DownloadStream =
        runBlocking(Dispatchers.IO) {
            // A signed CDN URL can be valid when playback cached it yet no longer be readable
            // when Media3 starts the offline transfer. Probe one byte with the same client/session
            // headers before giving the URL to DownloadManager; otherwise its retry loop remains
            // "Downloading" at 0% while repeatedly opening the same rejected stream.
            val rejectedClients = mutableSetOf<String>()
            repeat(MAX_INNERTUBE_STREAM_ATTEMPTS) {
                val resolvedEntry = if (videoId.isNotBlank()) {
                    runCatching {
                        YtMusicStreamResolver.resolveInnertube(videoId, rejectedClients)
                    }.onFailure { error ->
                        AppLogger.w(TAG, "Shared stream resolver failed for $videoId: ${error.message}")
                    }.getOrNull()
                } else null

                if (resolvedEntry != null) {
                    val stream = DownloadStream(
                        url = resolvedEntry.url,
                        userAgent = resolvedEntry.userAgent,
                        requiresWebSessionHeaders = resolvedEntry.requiresWebSessionHeaders,
                    )
                    if (canReadDownloadStream(stream)) {
                        return@runBlocking stream
                    }

                    AppLogger.w(
                        TAG,
                        "CDN probe rejected ${resolvedEntry.clientLabel ?: "unknown"} for $videoId; trying fallback",
                    )
                    StreamUrlCache.remove(videoId)
                    resolvedEntry.clientLabel?.let(rejectedClients::add)
                }
            }

            // Preserve the maintained extractor fallback. It is reached after an Innertube URL
            // has failed a real byte-read probe, not only when player-response resolution fails.
            val fallbackStream = DownloadStream(
                url = NewPipeRepository.resolveAudioStream(watchUrl),
                userAgent = FALLBACK_STREAM_USER_AGENT,
                requiresWebSessionHeaders = false,
            )
            if (!canReadDownloadStream(fallbackStream)) {
                AppLogger.w(TAG, "Extractor fallback probe failed for $videoId; Media3 will retry it")
            }
            fallbackStream
        }

    private fun canReadDownloadStream(stream: DownloadStream): Boolean =
        runCatching {
            val request = Request.Builder()
                .url(stream.url)
                .header("Range", "bytes=0-1")
                .header("User-Agent", stream.userAgent)
                .header(
                    STREAM_WEB_SESSION_HEADER,
                    if (stream.requiresWebSessionHeaders) STREAM_WEB_SESSION_VALUE else "0",
                )
                .build()
            downloadProbeHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful && (response.body?.byteStream()?.read() ?: -1) >= 0
            }
        }.onFailure { error ->
            AppLogger.w(TAG, "CDN probe failed: ${error.message}")
        }.getOrDefault(false)

    private const val MAX_INNERTUBE_STREAM_ATTEMPTS = 2


    private fun syncRoomLocalPath(context: Context, download: Download) {
        val watchUrl = watchUrlForDownloadId(download.request.id)
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

    /** Stable identifier used by new Media3 requests, matching OpenTune's download contract. */
    fun downloadId(videoId: String): String = videoId.trim()

    fun videoIdFromDownloadId(downloadId: String): String {
        if (!downloadId.startsWith("http", ignoreCase = true)) return downloadId.trim()
        return downloadId
            .substringAfter("v=", missingDelimiterValue = "")
            .substringBefore("&")
            .ifBlank { downloadId }
    }

    fun watchUrlForDownloadId(downloadId: String): String =
        if (downloadId.startsWith("http", ignoreCase = true)) downloadId
        else "https://music.youtube.com/watch?v=${videoIdFromDownloadId(downloadId)}"

    private fun findDownload(
        downloadMap: Map<String, Download>,
        downloadId: String,
    ): Download? {
        val videoId = videoIdFromDownloadId(downloadId)
        return downloadMap[downloadId]
            ?: downloadMap[videoId]
            ?: downloadMap[watchUrlForDownloadId(videoId)]
    }

    fun getDownload(downloadId: String): Flow<Download?> =
        downloads.map { findDownload(it, downloadId) }

    fun isDownloaded(downloadId: String): Boolean =
        findDownload(downloads.value, downloadId)?.state == Download.STATE_COMPLETED

    /**
     * Returns the exact cache key used by a completed download. New requests use
     * the stable video ID, while older URL-keyed requests retain their original
     * key so they remain playable after an update.
     */
    fun completedDownloadCacheKey(downloadId: String): String? {
        val download = findDownload(downloads.value, downloadId)
            ?.takeIf { it.state == Download.STATE_COMPLETED }
            ?: return null
        return download.request.customCacheKey ?: download.request.id
    }

    fun downloadProgress(downloadId: String): Float =
        findDownload(downloads.value, downloadId)?.percentDownloaded?.div(100f) ?: 0f
}
