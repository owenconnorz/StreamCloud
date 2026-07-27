package com.streamcloud.app.data.downloads

import android.app.DownloadManager
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
import kotlinx.coroutines.delay
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
    private val activeDmIds = mutableMapOf<Long, Long>()

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

        if (isLocalhost(url)) {
            downloadViaOkHttp(ctx, dao, tmdbId, title, posterUrl, mediaType, url, headers)
        } else {
            downloadViaSystemManager(ctx, dao, tmdbId, title, posterUrl, mediaType, url, headers)
        }
    }

    // ── Android DownloadManager path (all external URLs) ─────────────────────

    private suspend fun downloadViaSystemManager(
        ctx: Context,
        dao: com.streamcloud.app.data.library.MovieDownloadDao,
        tmdbId: Long,
        title: String,
        posterUrl: String?,
        mediaType: String,
        url: String,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val ext = fileExt(url)
        val fileName = "${safeTitle(title)}_$tmdbId.$ext"

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("StreamCloud")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MOVIES,
                "StreamCloud/$fileName",
            )
            .addRequestHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
            )

        headers.forEach { (k, v) -> request.addRequestHeader(k, v) }

        val dmId = dm.enqueue(request)
        synchronized(activeDmIds) { activeDmIds[tmdbId] = dmId }

        dao.upsert(
            MovieDownloadEntity(
                tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                mediaType = mediaType, streamUrl = url, status = "downloading", progress = 0f,
            ),
        )
        MovieDownloadNotifier.postProgress(ctx, tmdbId, title, null)

        try {
            var lastFraction = 0f
            while (true) {
                delay(1_000)

                val cursor = dm.query(DownloadManager.Query().setFilterById(dmId))
                if (cursor == null || !cursor.moveToFirst()) {
                    cursor?.close()
                    break
                }

                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                )
                val downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                )
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                )
                val reason = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                )
                cursor.close()

                val fraction = if (total > 0L) downloaded.toFloat() / total.toFloat() else lastFraction
                if (fraction != lastFraction) {
                    lastFraction = fraction
                    setProgress(tmdbId, fraction)
                    MovieDownloadNotifier.postProgress(ctx, tmdbId, title, fraction.takeIf { total > 0L })
                    dao.upsert(
                        MovieDownloadEntity(
                            tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                            mediaType = mediaType, streamUrl = url,
                            status = "downloading", progress = fraction, sizeBytes = total,
                        ),
                    )
                }

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val filePath = dm.getUriForDownloadedFile(dmId)?.toString()
                            ?: File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                                "StreamCloud/$fileName",
                            ).absolutePath
                        dao.upsert(
                            MovieDownloadEntity(
                                tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                                mediaType = mediaType, streamUrl = url,
                                filePath = filePath, status = "done", progress = 1f,
                                sizeBytes = total,
                            ),
                        )
                        setProgress(tmdbId, null)
                        MovieDownloadNotifier.postComplete(ctx, tmdbId, title)
                        return@withContext
                    }

                    DownloadManager.STATUS_FAILED -> {
                        dao.upsert(
                            MovieDownloadEntity(
                                tmdbId = tmdbId, title = title, posterUrl = posterUrl,
                                mediaType = mediaType, streamUrl = url, status = "error", progress = 0f,
                            ),
                        )
                        setProgress(tmdbId, null)
                        MovieDownloadNotifier.cancel(ctx, tmdbId)
                        error("Download failed (error code $reason). The URL may have expired or require a direct link.")
                    }
                }
            }
        } finally {
            synchronized(activeDmIds) { activeDmIds.remove(tmdbId) }
            setProgress(tmdbId, null)
            synchronized(activeJobs) { activeJobs.remove(tmdbId) }
        }
    }

    // ── OkHttp path (localhost / TorrServer only) ─────────────────────────────

    private suspend fun downloadViaOkHttp(
        ctx: Context,
        dao: com.streamcloud.app.data.library.MovieDownloadDao,
        tmdbId: Long,
        title: String,
        posterUrl: String?,
        mediaType: String,
        url: String,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
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
                    downloadLocalhostViaMediaStore(ctx, tmdbId, st, ext, url, headers) { f ->
                        setProgress(tmdbId, f)
                        MovieDownloadNotifier.postProgress(ctx, tmdbId, title, f)
                    }
                } else {
                    downloadLocalhostLegacy(ctx, tmdbId, st, ext, url, headers) { f ->
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

    private fun downloadLocalhostViaMediaStore(
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
            streamFromLocalhost(url, headers) { inputStream, total ->
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    pipe(inputStream, out, total, onProgress)
                }
            }
            ctx.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            uri.toString()
        } catch (e: Throwable) {
            ctx.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun downloadLocalhostLegacy(
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
        streamFromLocalhost(url, headers) { inputStream, total ->
            tmp.outputStream().use { out -> pipe(inputStream, out, total, onProgress) }
        }
        tmp.renameTo(outFile)
        return outFile.absolutePath
    }

    private fun <T> streamFromLocalhost(
        url: String,
        headers: Map<String, String>,
        block: (InputStream, Long) -> T,
    ): T {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .also { b -> headers.forEach { (k, v) -> b.header(k, v) } }
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
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

    // ── Public helpers ────────────────────────────────────────────────────────

    suspend fun remove(context: Context, tmdbId: Long) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext

        synchronized(activeJobs) { activeJobs.remove(tmdbId) }?.cancel()

        val dmId = synchronized(activeDmIds) { activeDmIds.remove(tmdbId) }
        if (dmId != null) {
            runCatching {
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(dmId)
            }
        }

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
