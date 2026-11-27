package com.streamcloud.app.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
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
import androidx.compose.foundation.layout.matchParentSize
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
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.Executors

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
internal class SeekPreviewLoader(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "StreamCloud-frame-previews").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val failedFrames = mutableSetOf<String>()
    private val unavailableSources = mutableSetOf<String>()

    suspend fun loadFrames(
        sourceUrl: String,
        headers: Map<String, String>,
        durationMs: Long,
        centerPositionMs: Long,
    ): List<SeekPreviewFrame> = withContext(dispatcher) {
        if (sourceUrl.isBlank() || durationMs <= 0L) return@withContext emptyList()

        val sourceKey = fingerprint(sourceUrl, headers)
        val positions = seekPreviewPositions(durationMs, centerPositionMs)

        val keys = positions.associateWith { frameKey(sourceKey, it) }
        val missing = positions.filter { position ->
            val key = keys.getValue(position)
            cache.get(key) == null && key !in failedFrames
        }

        if (missing.isNotEmpty() && sourceKey !in unavailableSources) {
            val retriever = MediaMetadataRetriever()
            try {
                val uri = Uri.parse(sourceUrl)
                when (uri.scheme?.lowercase()) {
                    "content" -> retriever.setDataSource(appContext, uri)
                    "file" -> retriever.setDataSource(uri.path ?: sourceUrl)
                    null -> retriever.setDataSource(sourceUrl)
                    else -> retriever.setDataSource(sourceUrl, headers)
                }

                missing.forEach { positionMs ->
                    val key = keys.getValue(positionMs)
                    val frame = try {
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
                    } catch (_: RuntimeException) {
                        null
                    }
                    if (frame == null) {
                        failedFrames += key
                    } else {
                        val thumbnail = frame.toPreviewBitmap()
                        cache.put(key, thumbnail)
                    }
                }
            } catch (_: Exception) {
                unavailableSources += sourceKey
            } finally {
                runCatching { retriever.release() }
            }
        }

        positions.map { positionMs ->
            SeekPreviewFrame(positionMs, cache.get(keys.getValue(positionMs)))
        }
    }

    override fun close() {
        cache.evictAll()
        dispatcher.close()
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
