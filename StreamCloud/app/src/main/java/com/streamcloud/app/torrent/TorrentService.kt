package com.streamcloud.app.torrent

import android.content.Context
import android.util.Log
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioEventType
import com.nuvio.engine.NuvioStream
import com.nuvio.engine.NuvioTorrentProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class TorrentService(context: Context) {

    companion object {
        private const val TAG = "TorrentService"
        private const val MEMORY_CACHE_BYTES = 64L * 1024L * 1024L
        private const val DISK_CACHE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val STREAM_IDLE_TIMEOUT_MS = 30_000
        private const val WARM_TORRENT_TIMEOUT_MS = 60_000
        private const val STATS_INTERVAL_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val engineMutex = Mutex()
    private val _state = MutableStateFlow<TorrentState>(TorrentState.Idle)
    val state: StateFlow<TorrentState> = _state.asStateFlow()

    @Volatile
    private var engine: NuvioEngine? = null

    @Volatile
    private var currentTorrentId: String? = null

    private var currentStream: NuvioStream? = null
    private var statsJob: Job? = null
    private var eventsJob: Job? = null
    private val streamRevision = AtomicLong(0L)

    suspend fun startStream(
        infoHash: String,
        fileIdx: Int? = null,
        filename: String? = null,
        trackers: List<String> = emptyList(),
    ): String {
        val magnet = TorrentMagnet.fromInfoHash(infoHash, trackers)
        return startPreparedStream(magnet.uri, fileIdx, filename)
    }

    suspend fun startStreamFromMagnet(magnet: String): String {
        val parsed = TorrentMagnet.parse(magnet)
        return startPreparedStream(parsed.uri, parsed.fileIndexHint, filename = null)
    }

    /**
     * Creates a separate short-lived stream for downloads without interrupting
     * the stream currently used by the player. SC Engine retires idle routes
     * and warm torrents according to the configured timeouts.
     */
    suspend fun resolveStreamUrlFromMagnet(magnet: String): String = withContext(Dispatchers.IO) {
        val prepared = TorrentMagnet.parse(magnet)
        val activeEngine = getOrCreateEngine()
        val torrentId = activeEngine.addMagnet(prepared.uri)
        val files = activeEngine.files(torrentId)
        val index = TorrentFileSelector.select(files, prepared.fileIndexHint, filename = null)
        activeEngine.prepareStream(
            torrentId = torrentId,
            fileIndex = index,
        ).url
    }

    private suspend fun startPreparedStream(
        magnetUri: String,
        fileIndex: Int?,
        filename: String?,
    ): String = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            streamRevision.incrementAndGet()
            stopActiveStream()
            _state.value = TorrentState.Connecting

            try {
                val activeEngine = getOrCreateEngine()
                Log.d(TAG, "Adding torrent for playback; requested file index=$fileIndex")
                val torrentId = activeEngine.addMagnet(magnetUri)
                val files = activeEngine.files(torrentId)
                val selectedIndex = TorrentFileSelector.select(files, fileIndex, filename)
                val stream = activeEngine.prepareStream(
                    torrentId = torrentId,
                    fileIndex = selectedIndex,
                    filenameHint = filename,
                )

                currentTorrentId = torrentId
                currentStream = stream
                _state.value = TorrentState.Streaming(localUrl = stream.url)
                startStatsPolling(activeEngine, stream)
                Log.d(TAG, "SC Engine stream prepared for file index ${stream.fileIndex}")
                stream.url
            } catch (cancelled: CancellationException) {
                stopActiveStream()
                throw cancelled
            } catch (error: Exception) {
                stopActiveStream()
                _state.value = TorrentState.Error(error.message ?: "Torrent stream could not start")
                throw TorrentException("SC Engine could not prepare the torrent stream", error)
            }
        }
    }

    fun stopStream() {
        val requestedRevision = streamRevision.incrementAndGet()
        _state.value = TorrentState.Idle
        scope.launch {
            operationMutex.withLock {
                // A newer playback request supersedes a delayed stop from the
                // previous player's dispose callback.
                if (streamRevision.get() == requestedRevision) stopActiveStream()
            }
        }
    }

    fun shutdown() {
        streamRevision.incrementAndGet()
        scope.launch {
            operationMutex.withLock {
                stopActiveStream()
                engineMutex.withLock {
                    eventsJob?.cancel()
                    eventsJob = null
                    val activeEngine = engine
                    engine = null
                    runCatching { activeEngine?.shutdown() }
                        .onFailure { Log.w(TAG, "Engine shutdown failed", it) }
                }
            }
        }
    }

    private suspend fun getOrCreateEngine(): NuvioEngine = engineMutex.withLock {
        engine?.let { return@withLock it }

        val dataDirectory = File(appContext.filesDir, "nuvio-engine")
        val cacheDirectory = File(appContext.cacheDir, "nuvio-engine")
        check(dataDirectory.mkdirs() || dataDirectory.isDirectory) {
            "Could not create SC Engine data directory"
        }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create SC Engine cache directory"
        }

        NuvioEngine.create(
            NuvioEngineConfig(
                dataDirectory = dataDirectory,
                cacheDirectory = cacheDirectory,
                memoryCacheCapacityBytes = MEMORY_CACHE_BYTES,
                diskCacheCapacityBytes = DISK_CACHE_BYTES,
                torrentProfile = NuvioTorrentProfile.Balanced,
                streamInactivityTimeoutMilliseconds = STREAM_IDLE_TIMEOUT_MS,
                warmTorrentTimeoutMilliseconds = WARM_TORRENT_TIMEOUT_MS,
            ),
        ).also { created ->
            engine = created
            eventsJob?.cancel()
            eventsJob = scope.launch {
                created.events.collect { event ->
                    if (
                        event.type == NuvioEventType.TorrentError &&
                        event.torrentId == currentTorrentId
                    ) {
                        _state.value = TorrentState.Error(
                            event.message ?: "Torrent connection failed",
                        )
                    }
                }
            }
        }
    }

    private fun startStatsPolling(activeEngine: NuvioEngine, stream: NuvioStream) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                try {
                    val engineStats = activeEngine.stats.value
                    val streamStats = activeEngine.currentStreamStats(stream.id)
                    val current = _state.value as? TorrentState.Streaming
                    if (current?.localUrl == stream.url) {
                        _state.value = current.copy(
                            downloadSpeed = engineStats.downloadRateBytesPerSecond,
                            uploadSpeed = engineStats.uploadRateBytesPerSecond,
                            peers = engineStats.connectedPeers,
                            seeds = engineStats.connectedSeeds,
                            preloadedBytes = streamStats.contiguousReadyBytes,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "SC Engine stats update failed", error)
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private suspend fun stopActiveStream() {
        statsJob?.cancel()
        statsJob = null
        val stream = currentStream
        currentStream = null
        currentTorrentId = null
        if (stream != null) {
            runCatching { engine?.stopStream(stream.id) }
                .onFailure { Log.w(TAG, "Could not stop torrent stream cleanly", it) }
        }
        _state.value = TorrentState.Idle
    }
}
