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
import com.streamcloud.app.data.AppLogger
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.newpipe.NewPipeRepository
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
            val watchUrl = dataSpec.uri.toString()
            val cacheKey  = dataSpec.key ?: watchUrl


            if (downloads.value[cacheKey]?.state == Download.STATE_COMPLETED) {
                return@Factory dataSpec
            }





            val requestLength = if (dataSpec.length >= 0) dataSpec.length else 1
            if (playerCache.isCached(cacheKey, dataSpec.position, requestLength)) {
                return@Factory dataSpec
            }




            val videoId = watchUrl.substringAfter("v=", "").substringBefore("&")
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
                }

                override fun onDownloadRemoved(
                    downloadManager: DownloadManager,
                    download: Download,
                ) {
                    downloads.update { map ->
                        map.toMutableMap().apply { remove(download.request.id) }
                    }
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

    private fun resolveDownloadStream(videoId: String, watchUrl: String): DownloadStream =
        runBlocking(Dispatchers.IO) {
            val resolvedEntry = if (videoId.isNotBlank()) {
                runCatching { YtMusicStreamResolver.resolveInnertube(videoId) }
                    .onFailure { error ->
                        AppLogger.w(TAG, "Shared stream resolver failed for $videoId: ${error.message}")
                    }
                    .getOrNull()
            } else null
            if (resolvedEntry != null) {
                return@runBlocking DownloadStream(
                    url = resolvedEntry.url,
                    userAgent = resolvedEntry.userAgent,
                    requiresWebSessionHeaders = resolvedEntry.requiresWebSessionHeaders,
                )
            }

            // Preserve the downloader fallback when Innertube is unavailable. Unlike the old
            // primary path, it is reached only after the same resolver playback uses.
            DownloadStream(
                url = NewPipeRepository.resolveAudioStream(watchUrl),
                userAgent = FALLBACK_STREAM_USER_AGENT,
                requiresWebSessionHeaders = false,
            )
        }


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
