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

    private fun isManifestUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
            || path.contains("/hls/") || path.contains("/dash/")
    }

    private fun isLocalhost(url: String) =
        url.startsWith("http://127.") || url.startsWith("http://localhost")

    private fun safeTitle(title: String) =
        title.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    private fun legacyDir(context: Context): File =
        (context.applicationContext.getExternalFilesDir("movies")
            ?: File(context.applicationContext.filesDir, "movies"))
            .apply { mkdirs() }

    fun isDownloaded(context: Context, tmdbId: Long): Boolean {
        val dir = legacyDir(context)
        return dir.listFiles()
            ?.any { it.name.startsWith("movie_${tmdbId}.") && it.length() > 0 } == true
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

        // Skip if already downloaded and file still exists
        val existing = dao.getByTmdbId(tmdbId)
        if (existing?.status == "done" && !existing.filePath.isNullOrBlank()) {
            val stillExists = when {
                existing.filePath.startsWith("content://") -> runCatching {
                    ctx.contentResolver.openInputStream(Uri.parse(existing.filePath))?.close(); true
                }.getOrDefault(false)
                existing.filePath.startsWith("file://") ->
                    File(Uri.parse(existing.filePath).path ?: "").let { it.exists() && it.length() > 0 }
                else -> File(existing.filePath).let { it.exists() && it.length() > 0 }
            }
            if (stillExists) return@withContext
        }

        // Manifest streams cannot be saved as a single file
        if (!isLocalhost(url) && isManifestUrl(url)) {
            error("This stream is an HLS/DASH adaptive playlist — it can't be saved as a single file. Choose a direct MP4 or MKV source instead.")
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
                MovieDownloadNotifier.postProgress(ctx, tmdbId, title, null)
                dao.upsert(
                    MovieDownloadEntity(
                        tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                        mediaType = mediaType, streamUrl = url, status = "downloading", progress = 0f,
                    ),
                )

                val ext = fileExt(url)
                val st = safeTitle(title)

                val filePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    downloadViaMediaStore(ctx, tmdbId, st, ext, url, headers) { f ->
                        setProgress(tmdbId, f)
                        MovieDownloadNotifier.postProgress(ctx, tmdbId, title, f)
                    }
                } else {
                    downloadLegacy(ctx, tmdbId, st, ext, url, headers) { f ->
                        setProgress(tmdbId, f)
                        MovieDownloadNotifier.postProgress(ctx, tmdbId, title, f)
                    }
                }

                dao.upsert(
                    MovieDownloadEntity(
                        tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                        mediaType = mediaType, streamUrl = url,
                        filePath = filePath, status = "done", progress = 1f,
                    ),
                )
                MovieDownloadNotifier.postComplete(ctx, tmdbId, title)
            } catch (e: Throwable) {
                dao.upsert(
                    MovieDownloadEntity(
                        tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                        mediaType = mediaType, streamUrl = url, status = "error", progress = 0f,
                    ),
                )
                MovieDownloadNotifier.cancel(ctx, tmdbId)
                throw e
            } finally {
                setProgress(tmdbId, null)
                synchronized(activeJobs) { activeJobs.remove(tmdbId) }
            }
        }
    }

    // API 29+: saves to Movies/StreamCloud/ in public shared storage
    private fun downloadViaMediaStore(
        ctx: Context,
        tmdbId: Long,
        st: String,
        ext: String,
        url: String,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${st}_$tmdbId.$ext")
            put(MediaStore.Video.Media.MIME_TYPE, "video/$ext")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StreamCloud")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        return try {
            streamUrl(url, headers) { inputStream, total ->
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    pipe(inputStream, out, total, onProgress)
                }
            }
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null, null,
            )
            uri.toString()
        } catch (e: Throwable) {
            ctx.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    // Pre-API-29 fallback
    private fun downloadLegacy(
        ctx: Context,
        tmdbId: Long,
        st: String,
        ext: String,
        url: String,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
    ): String {
        val outFile = File(legacyDir(ctx), "${st}_$tmdbId.$ext")
        val tmp = File(outFile.absolutePath + ".part")
        streamUrl(url, headers) { inputStream, total ->
            tmp.outputStream().use { out -> pipe(inputStream, out, total, onProgress) }
        }
        tmp.renameTo(outFile)
        return outFile.absolutePath
    }

    /**
     * Executes an HTTP GET with all [headers] passed through.
     * Uses a longer connect timeout for localhost (TorrServer needs time to gather peers).
     * No content-type filtering — whatever the server sends, we stream it.
     */
    private fun <T> streamUrl(
        url: String,
        headers: Map<String, String>,
        block: (InputStream, Long) -> T,
    ): T {
        val connectTimeout = if (isLocalhost(url)) 60L else 30L
        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .also { b -> headers.forEach { (k, v) -> b.header(k, v) } }
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} — the server refused the download. The source may require authentication or have expired.")
            block(resp.body!!.byteStream(), resp.body!!.contentLength())
        }
    }

    private fun pipe(
        inputStream: InputStream,
        out: java.io.OutputStream,
        total: Long,
        onProgress: (Float) -> Unit,
    ) {
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

        synchronized(activeJobs) { activeJobs.remove(tmdbId) }?.cancel()
        setProgress(tmdbId, null)

        val dao = LibraryDb.get(ctx).movieDownloads()
        val entry = dao.getByTmdbId(tmdbId)
        dao.remove(tmdbId)

        val filePath = entry?.filePath
        if (!filePath.isNullOrBlank()) {
            runCatching {
                when {
                    filePath.startsWith("content://") ->
                        ctx.contentResolver.delete(Uri.parse(filePath), null, null)
                    filePath.startsWith("file://") ->
                        File(Uri.parse(filePath).path ?: return@runCatching).delete()
                    else -> File(filePath).delete()
                }
            }
        }

        legacyDir(ctx).listFiles()
            ?.filter { it.name.startsWith("movie_${tmdbId}.") }
            ?.forEach { it.delete() }
    }
}
