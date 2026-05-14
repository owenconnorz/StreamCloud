package com.aioweb.app.data.downloads

import android.content.Context
import com.aioweb.app.data.library.LibraryDb
import com.aioweb.app.data.newpipe.NewPipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Music downloader.
 *
 * Resolves the audio stream via NewPipe, then writes it to
 * `/Android/data/.../files/music/<sanitised>.m4a` and stores the path in Room
 * (`TrackEntity.localPath`) so the song can be replayed without internet.
 *
 * Improvements over the original single-shot version (Metrolist parity):
 *  • **Parallel** — up to 3 downloads run simultaneously via [Semaphore],
 *    matching Metrolist's "Download all" behaviour.
 *  • **System notifications** via [MusicDownloadNotifier] — every in-flight
 *    download posts an ongoing progress entry, and the final "Downloaded"
 *    confirmation auto-dismisses after a few seconds.
 *  • **Per-URL progress flow** still exposed via [progressFlow] for in-app UI
 *    (download ring on the song row).
 */
object MusicDownloader {

    private const val MAX_PARALLEL = 3

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gate = Semaphore(MAX_PARALLEL)

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progressFlow: Flow<Map<String, Float>> = _progress.asStateFlow()

    private fun setProgress(url: String, fraction: Float?) {
        _progress.value = _progress.value.toMutableMap().also { m ->
            if (fraction == null) m.remove(url) else m[url] = fraction
        }
    }

    private fun musicDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "music").apply { mkdirs() }

    private fun fileFor(context: Context, url: String): File {
        val name = url.hashCode().toString().replace("-", "n")
        return File(musicDir(context), "$name.m4a")
    }

    fun isDownloaded(context: Context, url: String): Boolean {
        val f = fileFor(context, url)
        return f.exists() && f.length() > 0
    }

    /**
     * Download the audio stream for [url]. Concurrent calls are throttled to
     * [MAX_PARALLEL]; tasks beyond the limit suspend until a permit frees up.
     *
     * Side-effects:
     *  - Writes Room `TrackEntity.localPath` on success
     *  - Posts an ongoing system notification for the duration, replaced with
     *    a "Downloaded" confirmation when complete
     *  - Updates the in-app [progressFlow] for the song-row download ring
     */
    suspend fun download(context: Context, url: String, title: String): File =
        withContext(Dispatchers.IO) {
            val outFile = fileFor(context, url)
            // Already downloaded → fast path: just patch Room and bail out.
            if (outFile.exists() && outFile.length() > 0) {
                LibraryDb.get(context).tracks().setLocalPath(url, outFile.absolutePath)
                return@withContext outFile
            }

            gate.withPermit {
                val dao = LibraryDb.get(context).tracks()
                setProgress(url, 0f)
                MusicDownloadNotifier.postProgress(context, url, title, fraction = null)
                try {
                    val audio = NewPipeRepository.resolveAudioStream(url)
                    val req = Request.Builder().url(audio)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
                        )
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val total = resp.body?.contentLength() ?: -1L
                        val tmp = File(outFile.absolutePath + ".part")
                        resp.body!!.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                var written = 0L
                                var lastNotifyAt = 0L
                                while (true) {
                                    read = input.read(buf)
                                    if (read < 0) break
                                    out.write(buf, 0, read)
                                    written += read
                                    if (total > 0) {
                                        val frac = written.toFloat() / total
                                        setProgress(url, frac)
                                        // Throttle notification updates to ~250ms
                                        // to avoid binder spam on big files.
                                        val now = System.currentTimeMillis()
                                        if (now - lastNotifyAt > 250) {
                                            MusicDownloadNotifier.postProgress(
                                                context, url, title, frac,
                                            )
                                            lastNotifyAt = now
                                        }
                                    }
                                }
                            }
                        }
                        tmp.renameTo(outFile)
                    }
                    dao.setLocalPath(url, outFile.absolutePath)
                    MusicDownloadNotifier.postComplete(context, url, title)
                    outFile
                } catch (t: Throwable) {
                    MusicDownloadNotifier.cancel(context, url)
                    throw t
                } finally {
                    setProgress(url, null)
                }
            }
        }

    suspend fun delete(context: Context, url: String) = withContext(Dispatchers.IO) {
        fileFor(context, url).delete()
        LibraryDb.get(context).tracks().setLocalPath(url, null)
        MusicDownloadNotifier.cancel(context, url)
    }
}
