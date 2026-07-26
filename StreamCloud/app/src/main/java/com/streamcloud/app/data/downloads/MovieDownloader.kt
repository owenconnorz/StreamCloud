package com.streamcloud.app.data.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.io.InputStream
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

    private fun fileExt(url: String): String =
        url.substringBefore('?').substringAfterLast('.')
            .lowercase().takeIf { it.length in 2..4 && it.all { c -> c.isLetter() } } ?: "mp4"

    private fun legacyDir(context: Context): File =
        (context.applicationContext.getExternalFilesDir("movies")
            ?: File(context.applicationContext.filesDir, "movies"))
            .apply { mkdirs() }

    fun isDownloaded(context: Context, tmdbId: Long): Boolean {
        val dir = legacyDir(context)
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

        val existing = dao.getByTmdbId(tmdbId)
        if (existing?.status == "done" && !existing.filePath.isNullOrBlank()) {
            val stillExists = if (existing.filePath.startsWith("content://")) {
                try { ctx.contentResolver.openInputStream(Uri.parse(existing.filePath))?.close(); true }
                catch (_: Exception) { false }
            } else {
                File(existing.filePath).let { it.exists() && it.length() > 0 }
            }
            if (stillExists) return@withContext
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

                val ext = fileExt(url)
                val safeTitle = title.replace(Regex("[/\\\\:*?\"<>|]"), "_")

                var lastDbUpdate = 0L
                val filePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    downloadViaMediaStore(ctx, tmdbId, safeTitle, ext, url, headers) { fraction ->
                        setProgress(tmdbId, fraction)
                        val now = System.currentTimeMillis()
                        if (now - lastDbUpdate > 2000) {
                            lastDbUpdate = now
                            // progress DB update happens in finally block
                        }
                    }
                } else {
                    downloadLegacy(ctx, tmdbId, safeTitle, ext, url, headers) { fraction ->
                        setProgress(tmdbId, fraction)
                    }
                }

                dao.upsert(
                    MovieDownloadEntity(
                        tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                        mediaType = mediaType, streamUrl = url,
                        filePath = filePath, status = "done", progress = 1f,
                    ),
                )
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

    // API 29+: saves to Movies/StreamCloud/ in public shared storage (visible in Samsung My Files)
    private fun downloadViaMediaStore(
        ctx: Context,
        tmdbId: Long,
        safeTitle: String,
        ext: String,
        url: String,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${safeTitle}_$tmdbId.$ext")
            put(MediaStore.Video.Media.MIME_TYPE, "video/$ext")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StreamCloud")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            streamDownload(url, headers) { inputStream, total ->
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    pipe(inputStream, out, total, onProgress)
                }
            }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, done, null, null)
            return uri.toString()
        } catch (e: Throwable) {
            ctx.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    // Pre-API-29 fallback: saves to app-private external files dir
    private fun downloadLegacy(
        ctx: Context,
        tmdbId: Long,
        safeTitle: String,
        ext: String,
        url: String,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
    ): String {
        val outFile = File(legacyDir(ctx), "${safeTitle}_$tmdbId.$ext")
        val tmp = File(outFile.absolutePath + ".part")
        streamDownload(url, headers) { inputStream, total ->
            tmp.outputStream().use { out -> pipe(inputStream, out, total, onProgress) }
        }
        tmp.renameTo(outFile)
        return outFile.absolutePath
    }

    private fun <T> streamDownload(url: String, headers: Map<String, String>, block: (InputStream, Long) -> T): T {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .also { b -> headers.forEach { (k, v) -> b.header(k, v) } }
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            block(resp.body!!.byteStream(), resp.body?.contentLength() ?: -1L)
        }
    }

    private fun pipe(inputStream: InputStream, out: java.io.OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buf = ByteArray(32 * 1024)
        var written = 0L
        var n: Int
        while (inputStream.read(buf).also { n = it } != -1) {
            out.write(buf, 0, n)
            written += n
            if (total > 0) onProgress(written.toFloat() / total.toFloat())
        }
    }

    suspend fun remove(context: Context, tmdbId: Long) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        setProgress(tmdbId, null)
        val dao = LibraryDb.get(ctx).movieDownloads()
        val entry = dao.getByTmdbId(tmdbId)
        dao.remove(tmdbId)
        val filePath = entry?.filePath
        if (!filePath.isNullOrBlank()) {
            if (filePath.startsWith("content://")) {
                try { ctx.contentResolver.delete(Uri.parse(filePath), null, null) } catch (_: Exception) { }
            } else {
                File(filePath).delete()
            }
        }
        // Clean up any old-naming-scheme files
        legacyDir(ctx).listFiles()
            ?.filter { it.name.startsWith("movie_${tmdbId}.") }
            ?.forEach { it.delete() }
    }
}
