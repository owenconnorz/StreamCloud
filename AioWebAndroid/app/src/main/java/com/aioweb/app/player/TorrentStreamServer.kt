package com.aioweb.app.player

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Streams a magnet/torrent through libtorrent4j and exposes the largest media
 * file via a small embedded HTTP server, so ExoPlayer can play it like any URL.
 *
 * **Why the previous implementation never started playback** (user report:
 *  "connecting to peer but no movie starts playing"):
 *  - libtorrent4j allocates the destination file at its **full size** on disk
 *    from the moment metadata is received (sparse allocation). So
 *    `target.length()` returned e.g. 2 GB instantly even though zero bytes
 *    were downloaded. `waitForBytes` / `blockUntilAvailable` would short-
 *    circuit, `RandomAccessFile.read()` would happily return zero-filled
 *    sparse holes, and ExoPlayer received garbage — failing the container
 *    parse silently.
 *  - The fix is to gate reads on **piece availability** (`handle.havePiece(p)`)
 *    not file length, and to dynamically boost the priority of any piece a
 *    client requests so playback advances even when the user seeks ahead.
 *
 * One instance handles a single torrent at a time. Call [stop] when done.
 */
class TorrentStreamServer(context: Context) {

    private val downloadDir: File = File(context.cacheDir, "torrents").apply { mkdirs() }
    private val sessionManager = SessionManager()
    private var nano: HttpServer? = null
    private val handleRef = AtomicReference<TorrentHandle?>(null)
    private val targetFileRef = AtomicReference<File?>(null)
    @Volatile private var totalBytes: Long = 0L

    /**
     * Public trackers injected into every magnet/torrent we add. A bare
     * `magnet:?xt=urn:btih:…` with no `tr=` params relies entirely on DHT,
     * which can take 30–120 s to bootstrap on a fresh session — too slow for
     * a "tap Play → it works" experience. These eight trackers (from
     * `ngosang/trackerslist`'s `best.txt`) put metadata + peers within reach
     * within a couple of seconds for >95 % of torrents.
     */
    private val publicTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://retracker.lanta-net.ru:2710/announce",
    )

    /**
     * One-time SessionManager configuration. Default libtorrent4j settings
     * have DHT in *fallback* mode and LSD/uTP off — bare magnet links never
     * resolve under those defaults. Force-enable DHT, LSD, uTP, UPnP, NAT-PMP
     * and bump the metadata size cap so massive multi-file packs work.
     */
    private fun configureSession() {
        runCatching {
            val sp = sessionManager.settings()
            sp.setEnableDht(true)
            sp.setEnableLsd(true)
            // Bump the max metadata size — multi-file packs (anime seasons) can
            // ship 5–10 MB .torrent infos and the default 1 MB cap silently
            // refuses them, so `fetchMagnet` returns null with no error.
            runCatching { sp.setMaxMetadataSize(50 * 1024 * 1024) }
            // Active limits — defaults are stingy for foreground streaming.
            runCatching { sp.connectionsLimit(200) }
            runCatching { sp.activeLimit(100) }
            runCatching { sp.activeDownloads(20) }
            runCatching { sp.activeSeeds(20) }
            sessionManager.applySettings(sp)
        }
    }

    /** Append our public-tracker fallback list to a magnet if it has none. */
    private fun augmentMagnet(magnet: String): String {
        if (!magnet.startsWith("magnet:", ignoreCase = true)) return magnet
        val existing = Regex("(?i)[?&]tr=([^&]+)").findAll(magnet).count()
        if (existing >= 3) return magnet
        val extras = publicTrackers.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
        return magnet + extras
    }

    /** Inject the same trackers into a live [TorrentHandle] post-add. */
    private fun injectTrackersOn(handle: TorrentHandle) {
        runCatching {
            val current = handle.trackers().map { it.url() }.toSet()
            publicTrackers.filter { it !in current }.forEach { url ->
                runCatching {
                    handle.addTracker(org.libtorrent4j.AnnounceEntry(url))
                }
            }
            handle.forceReannounce()
        }
    }

    /**
     * Starts the torrent and the local HTTP server.
     * Returns a `http://127.0.0.1:<port>/stream` URL that can be fed to ExoPlayer,
     * or `null` if metadata couldn't be fetched within [metadataTimeoutMs].
     */
    suspend fun start(
        magnetOrUrl: String,
        metadataTimeoutMs: Long = 60_000L,
    ): String? {
        sessionManager.start()
        configureSession()

        // Augment bare magnet links with public trackers so DHT isn't the only
        // peer source — that's what makes "Connecting to peer" succeed within
        // a couple of seconds on fresh sessions instead of stalling 30–120 s.
        val augmentedMagnet = augmentMagnet(magnetOrUrl)
        Log.i(TAG, "Starting: ${if (augmentedMagnet.length > 80) augmentedMagnet.take(80) + "…" else augmentedMagnet}")

        // 1. Resolve metadata (magnet → TorrentInfo bytes; .torrent URL → fetched bytes).
        val infoBytes: ByteArray = when {
            augmentedMagnet.startsWith("magnet:", ignoreCase = true) ->
                sessionManager.fetchMagnet(
                    augmentedMagnet,
                    (metadataTimeoutMs / 1000).toInt().coerceAtLeast(15),
                    downloadDir,
                ) ?: run {
                    Log.w(TAG, "fetchMagnet returned null after ${metadataTimeoutMs}ms")
                    return null
                }
            augmentedMagnet.startsWith("http", ignoreCase = true) ->
                runCatching { URL(augmentedMagnet).openStream().use { it.readBytes() } }.getOrNull()
                    ?: return null
            else -> File(augmentedMagnet).takeIf { it.exists() }?.readBytes() ?: return null
        }

        val torrentInfo = runCatching { TorrentInfo.bdecode(infoBytes) }.getOrNull() ?: return null

        // 2. Wait for TorrentHandle to be ready and largest file index identified.
        val handle = withTimeoutOrNull(metadataTimeoutMs) {
            startTorrentAndAwaitHandle(torrentInfo)
        } ?: return null
        injectTrackersOn(handle)

        // 3. Pick the largest file as the playback target.
        val files = torrentInfo.files()
        var largestIdx = 0
        var largestSize = 0L
        for (i in 0 until files.numFiles()) {
            val size = files.fileSize(i)
            if (size > largestSize) {
                largestSize = size
                largestIdx = i
            }
        }
        totalBytes = largestSize
        val relPath = files.filePath(largestIdx)
        val absFile = File(downloadDir, relPath)
        targetFileRef.set(absFile)
        Log.i(TAG, "Selected file $relPath ($largestSize bytes)")

        // 4. Sequentially prioritise that file; deprioritise others to save bandwidth.
        val prios = Array(files.numFiles()) { Priority.IGNORE }
        prios[largestIdx] = Priority.TOP_PRIORITY
        handle.prioritizeFiles(prios)
        runCatching {
            handle.flags = handle.flags.or_(org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD)
        }
        // Many MP4 containers store the `moov` atom (codec config) at the END.
        // Force the first AND last several pieces of the playable file to TOP
        // priority + tight deadlines so ExoPlayer's initial header/tail probes
        // succeed within seconds.
        val pieceLen = torrentInfo.pieceLength().toLong()
        val fileOffset = files.fileOffset(largestIdx)
        val firstPiece = (fileOffset / pieceLen).toInt().coerceAtLeast(0)
        val lastPiece = ((fileOffset + largestSize - 1) / pieceLen)
            .toInt().coerceIn(0, torrentInfo.numPieces() - 1)
        runCatching {
            val headBoost = 8
            val tailBoost = 8
            for (p in firstPiece until (firstPiece + headBoost).coerceAtMost(torrentInfo.numPieces())) {
                handle.piecePriority(p, Priority.TOP_PRIORITY)
                runCatching { handle.setPieceDeadline(p, 1_000) }
            }
            for (p in (lastPiece - tailBoost + 1).coerceAtLeast(0)..lastPiece) {
                handle.piecePriority(p, Priority.TOP_PRIORITY)
                runCatching { handle.setPieceDeadline(p, 2_000) }
            }
        }

        // 5. Start NanoHTTPD on a free port.
        val server = HttpServer(
            target = absFile,
            handle = handle,
            fileOffset = fileOffset,
            pieceLen = pieceLen,
            fileSize = largestSize,
            firstPiece = firstPiece,
            lastPiece = lastPiece,
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, /* daemon */ true)
        nano = server
        val port = server.listeningPort
        val name = URLEncoder.encode(absFile.name, "UTF-8")
        return "http://127.0.0.1:$port/stream/$name"
    }

    private suspend fun startTorrentAndAwaitHandle(torrentInfo: TorrentInfo): TorrentHandle? =
        suspendCancellableCoroutine { cont ->
            val listener = object : AlertListener {
                override fun types(): IntArray = intArrayOf(
                    AlertType.ADD_TORRENT.swig(),
                    AlertType.METADATA_RECEIVED.swig(),
                )
                override fun alert(alert: Alert<*>) {
                    val h = sessionManager.find(torrentInfo.infoHash()) ?: return
                    if (h.isValid && handleRef.compareAndSet(null, h)) {
                        if (cont.isActive) cont.resume(h)
                    }
                }
            }
            sessionManager.addListener(listener)
            sessionManager.download(torrentInfo, downloadDir)
            // Fast-path: handle may already exist
            sessionManager.find(torrentInfo.infoHash())?.let {
                if (it.isValid && handleRef.compareAndSet(null, it) && cont.isActive) cont.resume(it)
            }
            cont.invokeOnCancellation { runCatching { sessionManager.removeListener(listener) } }
        }

    fun stop() {
        runCatching { nano?.stop() }
        runCatching { handleRef.get()?.let { sessionManager.remove(it) } }
        runCatching { sessionManager.stop() }
    }

    /**
     * Tiny HTTP server with HTTP Range support, driven by **piece availability**
     * (not file length — see class-level comment for why that distinction matters).
     */
    private class HttpServer(
        private val target: File,
        private val handle: TorrentHandle,
        private val fileOffset: Long,
        private val pieceLen: Long,
        private val fileSize: Long,
        private val firstPiece: Int,
        private val lastPiece: Int,
    ) : NanoHTTPD(0) {

        override fun serve(session: IHTTPSession): Response {
            val rangeHeader = session.headers["range"]
            val (start, end) = parseRange(rangeHeader, fileSize)
            val length = end - start + 1
            Log.i(TAG, "serve range $start-$end (length=$length) range='$rangeHeader'")

            // Compute the piece span this client request covers in the file.
            val firstNeeded = pieceForFileByte(start)
            val lastNeeded = pieceForFileByte(end)
            // Boost the requested pieces to TOP priority + tight deadlines so
            // seeks (or ExoPlayer's tail probe) advance immediately. We don't
            // wait for the whole range — only the initial 2 MB before responding.
            boostPieces(firstNeeded, lastNeeded)

            // Pre-block only for an *initial* window (~2 MB from `start`).
            val initialWindow = minOf(2L * 1024 * 1024, length)
            val initialLastByte = start + initialWindow - 1
            val initialLastPiece = pieceForFileByte(initialLastByte)
            val haveInitial = waitForPieces(firstNeeded, initialLastPiece, timeoutMs = 45_000L)
            if (!haveInitial) {
                Log.w(TAG, "Timed out waiting for initial pieces $firstNeeded..$initialLastPiece")
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE, "text/plain",
                    "Torrent: no peers / initial window timed out",
                )
            }

            val raf = RandomAccessFile(target, "r")
            raf.seek(start)
            val stream = object : java.io.InputStream() {
                private var remaining = length

                /** Wait until the piece containing the next byte to read is downloaded. */
                private fun ensurePiece(fileByte: Long, timeoutMs: Long = 60_000L): Boolean {
                    val piece = pieceForFileByte(fileByte)
                    if (handle.havePiece(piece)) return true
                    runCatching {
                        handle.piecePriority(piece, Priority.TOP_PRIORITY)
                        handle.setPieceDeadline(piece, 1_500)
                    }
                    return waitForPieces(piece, piece, timeoutMs)
                }

                override fun read(): Int {
                    if (remaining <= 0) return -1
                    if (!ensurePiece(raf.filePointer)) return -1
                    val v = raf.read()
                    if (v >= 0) remaining--
                    return v
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    val toRead = minOf(len.toLong(), remaining).toInt()
                    if (!ensurePiece(raf.filePointer)) return -1
                    // Only read as far as the CURRENT piece — re-enter on next call
                    // to ensure the next piece is available before reading past it.
                    val currentPiece = pieceForFileByte(raf.filePointer)
                    val nextPieceFileByte =
                        ((currentPiece + 1).toLong() * pieceLen - fileOffset).coerceIn(0L, fileSize)
                    val safeLen = minOf(toRead.toLong(), nextPieceFileByte - raf.filePointer
                        .let { it - fileOffset }
                        .let { fileByteToReadablePos(it) }
                    ).toInt().coerceAtLeast(1)
                    val n = raf.read(b, off, safeLen)
                    if (n > 0) remaining -= n
                    return n
                }
                override fun close() { runCatching { raf.close() } }

                /** Bytes remaining in current piece, computed from RAF pointer. */
                private fun fileByteToReadablePos(positionInFile: Long): Long {
                    val piece = pieceForFileByte(positionInFile)
                    val pieceEndInFile = ((piece + 1).toLong() * pieceLen) - fileOffset
                    return (pieceEndInFile - positionInFile).coerceAtLeast(1L)
                }
            }

            val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val resp = newFixedLengthResponse(status, guessMimeType(target.name), stream, length)
            resp.addHeader("Accept-Ranges", "bytes")
            if (rangeHeader != null) {
                resp.addHeader("Content-Range", "bytes $start-$end/$fileSize")
            }
            resp.addHeader("Content-Length", length.toString())
            return resp
        }

        private fun pieceForFileByte(fileByte: Long): Int {
            val globalByte = fileOffset + fileByte
            val piece = (globalByte / pieceLen).toInt()
            return piece.coerceIn(firstPiece, lastPiece)
        }

        private fun boostPieces(first: Int, last: Int) {
            runCatching {
                val window = (last - first + 1).coerceAtMost(64)
                for (p in first..(first + window - 1)) {
                    if (!handle.havePiece(p)) {
                        handle.piecePriority(p, Priority.TOP_PRIORITY)
                        handle.setPieceDeadline(p, ((p - first) * 250L + 500L).toInt())
                    }
                }
            }
        }

        /**
         * Block until every piece in [first]..[last] reports `havePiece(p) == true`,
         * or [timeoutMs] elapses. Returns `true` on success.
         */
        private fun waitForPieces(first: Int, last: Int, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                var allHave = true
                var p = first
                while (p <= last) {
                    if (!handle.havePiece(p)) { allHave = false; break }
                    p++
                }
                if (allHave) return true
                if (System.currentTimeMillis() > deadline) return false
                try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
            }
        }

        private fun guessMimeType(name: String): String {
            val n = name.lowercase()
            return when {
                n.endsWith(".mp4") || n.endsWith(".m4v") -> "video/mp4"
                n.endsWith(".mkv")                       -> "video/x-matroska"
                n.endsWith(".webm")                      -> "video/webm"
                n.endsWith(".avi")                       -> "video/x-msvideo"
                n.endsWith(".mov")                       -> "video/quicktime"
                else                                     -> "video/mp4"
            }
        }

        private fun parseRange(header: String?, total: Long): Pair<Long, Long> {
            if (header == null || !header.startsWith("bytes=")) return 0L to (total - 1)
            val raw = header.removePrefix("bytes=").substringBefore(",")
            val parts = raw.split("-")
            val start = parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            // Suffix range `bytes=-N` → last N bytes (used by ExoPlayer for MP4 tail probe).
            return when {
                start == null && end != null -> {
                    val s = (total - end).coerceAtLeast(0L)
                    s to (total - 1)
                }
                start != null && end == null -> start.coerceIn(0, total - 1) to (total - 1)
                start != null && end != null ->
                    start.coerceIn(0, total - 1) to end.coerceIn(start, total - 1)
                else -> 0L to (total - 1)
            }
        }
    }

    companion object { private const val TAG = "TorrentStream" }
}
