package com.streamcloud.app.data.downloads

import android.content.Context
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.MovieDownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

object MovieDownloader {

    private const val MAX_PARALLEL = 2

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val gate = Semaphore(MAX_PARALLEL)

    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val progressFlow: Flow<Map<Long, Float>> = _progress.asStateFlow()

    private val activeJobs = mutableMapOf<Long, Job>()

    private fun setProgress(id: Long, fraction: Float?) {
        _progress.value = _progress.value.toMutableMap().also { m ->
            if (fraction == null) m.remove(id) else m[id] = fraction
        }
    }

    private fun movieDir(context: Context): File =
        (context.applicationContext.getExternalFilesDir("movies")
            ?: File(context.applicationContext.filesDir, "movies"))
            .apply { mkdirs() }

    private fun fileFor(context: Context, tmdbId: Long, url: String): File {
        val ext = url.substringBefore('?').substringAfterLast('.')
            .lowercase().takeIf { it.length in 2..4 && it.all { c -> c.isLetter() } } ?: "mp4"
        return File(movieDir(context), "movie_${tmdbId}.$ext")
    }

    fun isDownloaded(context: Context, tmdbId: Long): Boolean {
        val dir = movieDir(context)
        return dir.listFiles()?.any { it.name.startsWith("movie_${tmdbId}.") && it.length() > 0 } == true
    }

    suspend fun download(
        context: Context,
        tmdbId: Long,
        title: String,
        posterUrl: String?,
        mediaType: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val dao = LibraryDb.get(ctx).movieDownloads()
        val outFile = fileFor(ctx, tmdbId, url)

        if (outFile.exists() && outFile.length() > 0) {
            dao.upsert(
                MovieDownloadEntity(
                    tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                    mediaType = mediaType, streamUrl = url,
                    filePath = outFile.absolutePath, status = "done",
                    progress = 1f, sizeBytes = outFile.length(),
                ),
            )
            return@withContext
        }

        dao.upsert(
            MovieDownloadEntity(
                tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                mediaType = mediaType, streamUrl = url, status = "queued", progress = 0f,
            ),
        )
        setProgress(tmdbId, 0f)

        gate.withPermit {
            try {
                dao.upsert(
                    MovieDownloadEntity(
                        tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                        mediaType = mediaType, streamUrl = url, status = "downloading", progress = 0f,
                    ),
                )

                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }

                http.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val total = resp.body?.contentLength() ?: -1L
                    val tmp = File(outFile.absolutePath + ".part")
                    var written = 0L
                    resp.body!!.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(32 * 1024)
                            var n: Int
                            var lastDbUpdate = 0L
                            while (input.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                written += n
                                if (total > 0) {
                                    val fraction = written.toFloat() / total.toFloat()
                                    setProgress(tmdbId, fraction)
                                    val now = System.currentTimeMillis()
                                    if (now - lastDbUpdate > 2000) {
                                        lastDbUpdate = now
                                        dao.upsert(
                                            MovieDownloadEntity(
                                                tmdbId = tmdbId, title = title,
                                                posterUrl = posterUrl, mediaType = mediaType,
                                                streamUrl = url, status = "downloading",
                                                progress = fraction, sizeBytes = total,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    tmp.renameTo(outFile)
                    dao.upsert(
                        MovieDownloadEntity(
                            tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                            mediaType = mediaType, streamUrl = url,
                            filePath = outFile.absolutePath, status = "done",
                            progress = 1f, sizeBytes = outFile.length(),
                        ),
                    )
                }
            } catch (e: Throwable) {
                dao.upsert(
                    MovieDownloadEntity(
                        tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                        mediaType = mediaType, streamUrl = url, status = "error", progress = 0f,
                    ),
                )
            } finally {
                setProgress(tmdbId, null)
                synchronized(activeJobs) { activeJobs.remove(tmdbId) }
            }
        }
    }

    suspend fun remove(context: Context, tmdbId: Long) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        setProgress(tmdbId, null)
        LibraryDb.get(ctx).movieDownloads().remove(tmdbId)
        movieDir(ctx).listFiles()
            ?.filter { it.name.startsWith("movie_${tmdbId}.") }
            ?.forEach { it.delete() }
    }
}
