package com.streamcloud.app.data.downloads

import android.content.Context
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.newpipe.NewPipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object MusicDownloader {

    private const val MAX_PARALLEL = 3

    private val http = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
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


    suspend fun download(context: Context, url: String, title: String): File =
        withContext(Dispatchers.IO) {
            val outFile = fileFor(context, url)

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
