package com.streamcloud.app.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class SeekPreviewFrame(
    val positionMs: Long,
    val bitmap: Bitmap?,
)

internal fun seekPreviewPositions(durationMs: Long, centerPositionMs: Long): List<Long> {
    if (durationMs <= 0L) return emptyList()
    val lastFrameMs = (durationMs - 1L).coerceAtLeast(0L)
    val gapMs = (durationMs / 18L).coerceIn(5_000L, 6 * 60_000L)
    val center = centerPositionMs.coerceIn(0L, lastFrameMs)
    return (-2..2)
        .map { center + it * gapMs }
        .filter { it in 0L..lastFrameMs }
}

/**
 * Extracts small preview frames from the active stream. Work is serialized off the UI thread;
 * preview failures never affect the ExoPlayer instance or interrupt playback.
 */
@OptIn(UnstableApi::class)
internal class SeekPreviewLoader(context: Context) : Closeable {
    private companion object {
        const val FAILED_FRAME_RETRY_DELAY_MS = 20_000L
        const val UNAVAILABLE_SOURCE_RETRY_DELAY_MS = 8_000L
        const val MAX_FRAME_BATCH_MS = 18_000L
        const val TAG = "SeekPreviewLoader"
    }

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "StreamCloud-frame-previews").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val extractionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StreamCloud-frame-extraction").apply { isDaemon = true }
    }
    private val extractionInFlight = AtomicBoolean(false)
    private val activeRetriever = AtomicReference<MediaMetadataRetriever?>(null)

    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val failedFrames = mutableMapOf<String, Long>()
    private val unavailableSources = mutableMapOf<String, Long>()

    suspend fun loadFrames(
        sourceUrl: String,
        dataSourceFactory: DataSource.Factory,
        isManifest: Boolean,
        headers: Map<String, String>,
        durationMs: Long,
        centerPositionMs: Long,
    ): List<SeekPreviewFrame> = withContext(dispatcher) {
        if (sourceUrl.isBlank() || durationMs <= 0L) return@withContext emptyList()

        val sourceKey = fingerprint("$sourceUrl\n$isManifest", headers)
        val positions = seekPreviewPositions(durationMs, centerPositionMs)
        val now = SystemClock.elapsedRealtime()

        val keys = positions.associateWith { frameKey(sourceKey, it) }
        val missing = positions.filter { position ->
            val key = keys.getValue(position)
            cache.get(key) == null && (failedFrames[key] ?: 0L) <= now
        }

        if (
            missing.isNotEmpty() &&
            (unavailableSources[sourceKey] ?: 0L) <= now
        ) {
            if (extractionInFlight.compareAndSet(false, true)) {
                val frameFuture = extractionExecutor.submit<Map<Long, Bitmap?>> {
                    val retriever = MediaMetadataRetriever()
                    var mediaDataSource: Media3RetrieverDataSource? = null
                    activeRetriever.set(retriever)
                    try {
                        if (isManifest) {
                            retriever.setDataSource(sourceUrl, headers)
                        } else {
                            val source = Media3RetrieverDataSource(
                                dataSourceFactory,
                                Uri.parse(sourceUrl),
                            )
                            mediaDataSource = source
                            retriever.setDataSource(source)
                        }
                        missing.associateWith { positionMs ->
                            runCatching {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                    retriever.getScaledFrameAtTime(
                                        positionMs * 1_000L,
                                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                        320,
                                        180,
                                    )
                                } else {
                                    retriever.getFrameAtTime(
                                        positionMs * 1_000L,
                                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    )
                                }
                            }.getOrNull()
                        }
                    } finally {
                        runCatching { mediaDataSource?.close() }
                        if (activeRetriever.compareAndSet(retriever, null)) {
                            runCatching { retriever.release() }
                        }
                        extractionInFlight.set(false)
                    }
                }

                try {
                    val extracted = runInterruptible {
                        frameFuture.get(MAX_FRAME_BATCH_MS, TimeUnit.MILLISECONDS)
                    }
                    extracted.forEach { (positionMs, bitmap) ->
                        val key = keys.getValue(positionMs)
                        if (bitmap == null) {
                            failedFrames[key] =
                                SystemClock.elapsedRealtime() + FAILED_FRAME_RETRY_DELAY_MS
                        } else {
                            cache.put(key, bitmap.toPreviewBitmap())
                            failedFrames.remove(key)
                        }
                    }
                    if (extracted.values.none { it != null }) {
                        unavailableSources[sourceKey] =
                            SystemClock.elapsedRealtime() + UNAVAILABLE_SOURCE_RETRY_DELAY_MS
                    }
                } catch (timeout: TimeoutException) {
                    frameFuture.cancel(true)
                    releaseActiveRetriever()
                    Log.w(TAG, "Frame extraction timed out")
                    unavailableSources[sourceKey] =
                        SystemClock.elapsedRealtime() + UNAVAILABLE_SOURCE_RETRY_DELAY_MS
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    frameFuture.cancel(true)
                    releaseActiveRetriever()
                    throw cancelled
                } catch (failure: ExecutionException) {
                    Log.w(
                        TAG,
                        "Frame extraction failed (${failure.cause?.javaClass?.simpleName ?: "unknown"})",
                    )
                    unavailableSources[sourceKey] =
                        SystemClock.elapsedRealtime() + UNAVAILABLE_SOURCE_RETRY_DELAY_MS
                } catch (failure: Exception) {
                    Log.w(TAG, "Frame extraction failed (${failure.javaClass.simpleName})")
                    unavailableSources[sourceKey] =
                        SystemClock.elapsedRealtime() + UNAVAILABLE_SOURCE_RETRY_DELAY_MS
                }
            } else {
                unavailableSources[sourceKey] =
                    SystemClock.elapsedRealtime() + UNAVAILABLE_SOURCE_RETRY_DELAY_MS
            }
        }

        positions.map { positionMs ->
            SeekPreviewFrame(positionMs, cache.get(keys.getValue(positionMs)))
        }
    }

    override fun close() {
        releaseActiveRetriever()
        extractionExecutor.shutdownNow()
        cache.evictAll()
        dispatcher.close()
    }

    private fun releaseActiveRetriever() {
        activeRetriever.getAndSet(null)?.let { retriever ->
            runCatching { retriever.release() }
        }
    }

    private fun fingerprint(url: String, headers: Map<String, String>): String {
        val requestIdentity = buildString {
            append(url)
            headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
                append('\n').append(name.lowercase()).append(':').append(value)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(requestIdentity.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun frameKey(sourceKey: String, positionMs: Long) =
        "$sourceKey:${positionMs / 1_000L}"
}

@OptIn(UnstableApi::class)
private class Media3RetrieverDataSource(
    private val factory: DataSource.Factory,
    private val uri: Uri,
) : MediaDataSource() {
    private var activeSource: DataSource? = null
    private var nextPosition = -1L
    private var knownSize: Long? = null

    @Synchronized
    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0L) return -1
        if (activeSource == null || nextPosition != position) {
            closeActiveSource()
            val source = factory.createDataSource()
            val remaining = knownSize?.minus(position)?.coerceAtLeast(0L)
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(position)
                .apply { remaining?.let { setLength(it) } }
                .build()
            source.open(spec)
            activeSource = source
            nextPosition = position
        }

        val source = activeSource ?: return -1
        val bytesAvailable = knownSize
            ?.minus(position)
            ?.coerceAtLeast(0L)
            ?.coerceAtMost(size.toLong())
            ?.toInt()
            ?: size
        if (bytesAvailable <= 0) return -1

        var totalRead = 0
        while (totalRead < bytesAvailable) {
            val count = source.read(buffer, offset + totalRead, bytesAvailable - totalRead)
            if (count == C.RESULT_END_OF_INPUT) break
            if (count <= 0) break
            totalRead += count
            nextPosition += count
        }
        return if (totalRead == 0) -1 else totalRead
    }

    @Synchronized
    @Throws(IOException::class)
    override fun getSize(): Long {
        knownSize?.let { return it }
        val probe = factory.createDataSource()
        return try {
            probe.open(DataSpec.Builder().setUri(uri).build()).also { length ->
                if (length >= 0L) knownSize = length
            }
        } catch (_: IOException) {
            C.LENGTH_UNSET.toLong()
        } finally {
            runCatching { probe.close() }
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        closeActiveSource()
    }

    private fun closeActiveSource() {
        runCatching { activeSource?.close() }
        activeSource = null
        nextPosition = -1L
    }
}

private fun Bitmap.toPreviewBitmap(): Bitmap {
    val scale = minOf(320f / width.coerceAtLeast(1), 180f / height.coerceAtLeast(1), 1f)
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    if (targetWidth == width && targetHeight == height) return this
    val scaled = Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    if (scaled !== this) recycle()
    return scaled
}

@Composable
internal fun SeekPreviewStrip(
    frames: List<SeekPreviewFrame>,
    centerPositionMs: Long,
    isLoading: Boolean,
    previewUnavailable: Boolean,
    isTv: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.focusGroup(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Frame previews",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = if (isTv) 13.sp else 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    strokeWidth = 1.5.dp,
                )
            }
        }
        Spacer(Modifier.height(7.dp))

        when {
            frames.isNotEmpty() && (frames.any { it.bitmap != null } || isLoading) -> {
                val selectedPosition = frames
                    .minByOrNull { kotlin.math.abs(it.positionMs - centerPositionMs) }
                    ?.positionMs
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 7.dp),
                    contentPadding = PaddingValues(end = 2.dp),
                ) {
                    items(frames, key = { it.positionMs }) { frame ->
                        val selected = frame.positionMs == selectedPosition
                        val shape = RoundedCornerShape(if (isTv) 8.dp else 6.dp)
                        val cardWidth = if (isTv) 144.dp else 92.dp
                        val imageHeight = if (isTv) 80.dp else 52.dp
                        Column(
                            modifier = Modifier
                                .width(cardWidth)
                                .clip(shape)
                                .onFocusChanged { onFocusChanged(it.isFocused) }
                                .clickable { onSeek(frame.positionMs) }
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.18f),
                                    shape = shape,
                                )
                                .semantics {
                                    contentDescription = "Seek to ${formatPreviewTime(frame.positionMs)}"
                                },
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(cardWidth)
                                    .height(imageHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (frame.bitmap != null) {
                                    Image(
                                        bitmap = frame.bitmap.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .clip(shape)
                                            .background(Color.White.copy(alpha = 0.12f)),
                                    )
                                }
                            }
                            Text(
                                text = formatPreviewTime(frame.positionMs),
                                color = Color.White,
                                fontSize = if (isTv) 11.sp else 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(horizontal = 4.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            previewUnavailable -> Text(
                text = "Frame previews aren’t available for this stream",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = if (isTv) 12.sp else 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatPreviewTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
