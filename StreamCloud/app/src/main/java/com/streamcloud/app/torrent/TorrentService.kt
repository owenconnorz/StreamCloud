package com.streamcloud.app.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder

private val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "avi", "webm", "ts", "m4v", "mov", "wmv", "flv",
)

class TorrentService(context: Context) {

    companion object {
        private const val TAG = "TorrentService"
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.bittor.pw:1337/announce",
        )
    }

    private val appContext = context.applicationContext
    private val binary = TorrServerBinary(appContext)
    private val api    = TorrServerApi(binary)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TorrentState>(TorrentState.Idle)
    val state: StateFlow<TorrentState> = _state.asStateFlow()

    private var statsJob: Job? = null
    private var currentHash: String? = null


    suspend fun startStream(
        infoHash: String,
        fileIdx: Int? = null,
        filename: String? = null,
        trackers: List<String> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        stopStream()
        _state.value = TorrentState.Connecting

        binary.start()

        val magnet = buildMagnet(infoHash, trackers)
        Log.d(TAG, "Starting stream: infoHash=$infoHash fileIdx=$fileIdx")

        val hash = api.addTorrent(magnet)
            ?: throw TorrentException("TorrServer rejected the torrent (addTorrent returned null)")
        currentHash = hash

        val resolvedIdx = resolveFileIndex(hash, fileIdx, filename)
        val streamUrl   = api.getStreamUrl(magnet, resolvedIdx)
        Log.d(TAG, "Stream URL → $streamUrl")

        startStatsPolling(hash)

        _state.value = TorrentState.Streaming(localUrl = streamUrl)
        streamUrl
    }


    suspend fun startStreamFromMagnet(magnet: String): String = withContext(Dispatchers.IO) {

        val fidxRegex = Regex("[&?]_sc_fidx=(\\d+)", RegexOption.IGNORE_CASE)
        val fileIdxHint = fidxRegex.find(magnet)?.groupValues?.get(1)?.toIntOrNull()
        val cleanMagnet = magnet.replace(fidxRegex, "")


        val infoHash = Regex("urn:btih:([a-zA-Z0-9]{32,40})", RegexOption.IGNORE_CASE)
            .find(cleanMagnet)?.groupValues?.get(1)
            ?: throw TorrentException("Could not parse infoHash from magnet: ${cleanMagnet.take(80)}")


        val trackers = Regex("[&?]tr=([^&]+)").findAll(cleanMagnet)
            .map {
                runCatching { URLDecoder.decode(it.groupValues[1], "UTF-8") }
                    .getOrDefault(it.groupValues[1])
            }
            .filter { it.isNotBlank() }
            .toList()

        Log.d(TAG, "startStreamFromMagnet infoHash=$infoHash fileIdxHint=$fileIdxHint trackers=${trackers.size}")


        startStream(infoHash = infoHash, fileIdx = fileIdxHint, filename = null, trackers = trackers)
    }

    fun stopStream() {
        statsJob?.cancel()
        statsJob = null
        currentHash?.let { hash ->
            runCatching { runBlocking(Dispatchers.IO) { api.dropTorrent(hash) } }
        }
        currentHash = null
        _state.value = TorrentState.Idle
    }

    fun shutdown() {
        stopStream()
        binary.stop()
    }



    private fun buildMagnet(infoHash: String, extraTrackers: List<String>): String {
        val trackers = (DEFAULT_TRACKERS + extraTrackers).distinct()
        return "magnet:?xt=urn:btih:$infoHash" +
            trackers.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
    }

    private suspend fun resolveFileIndex(
        hash: String,
        requestedIdx: Int?,
        filename: String?,
    ): Int {

        val deadline = System.currentTimeMillis() + 15_000L
        var files: List<TorrServerFile> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            files = api.getTorrentStats(hash)?.files ?: emptyList()
            if (files.isNotEmpty()) break
            Log.d(TAG, "Waiting for torrent metadata…")
            delay(800L)
        }

        if (files.isEmpty()) {
            Log.w(TAG, "No file list after metadata timeout; guessing idx=${requestedIdx?.plus(1) ?: 1}")
            return requestedIdx?.plus(1) ?: 1
        }
        Log.d(TAG, "Torrent has ${files.size} files")


        if (!filename.isNullOrBlank()) {
            val name = filename.trim()
            files.firstOrNull { it.path.substringAfterLast('/').equals(name, true) }
                ?.also { Log.d(TAG, "Resolved by exact filename: ${it.path} id=${it.id}"); return it.id }
            files.firstOrNull { it.path.contains(name, true) }
                ?.also { Log.d(TAG, "Resolved by partial filename: ${it.path} id=${it.id}"); return it.id }
        }


        if (requestedIdx != null) {
            val tsIdx = requestedIdx + 1
            if (files.any { it.id == tsIdx }) {
                Log.d(TAG, "Resolved by ID offset: id=$tsIdx"); return tsIdx
            }
        }


        if (requestedIdx != null && requestedIdx in files.indices) {
            val f = files[requestedIdx]
            Log.d(TAG, "Resolved by positional index [$requestedIdx]: ${f.path} id=${f.id}")
            return f.id
        }


        val best = files
            .filter { f -> f.path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS }
            .maxByOrNull { it.length }
            ?: files.maxByOrNull { it.length }
        Log.d(TAG, "Resolved by largest video fallback: id=${best?.id ?: 1}")
        return best?.id ?: 1
    }

    private fun startStatsPolling(hash: String) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                try {
                    val stats = api.getTorrentStats(hash)
                    val cur   = _state.value
                    if (stats != null && cur is TorrentState.Streaming) {
                        _state.value = cur.copy(
                            downloadSpeed  = stats.downloadSpeed,
                            uploadSpeed    = stats.uploadSpeed,
                            peers          = stats.peers,
                            seeds          = stats.seeds,
                            preloadedBytes = stats.preloadedBytes,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stats polling error", e)
                }
                delay(1_500L)
            }
        }
    }
}
