package com.streamcloud.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.ui.icons.AppIcons as Icons
import java.util.Locale

/** The active section shown in the playback information panel. */
enum class PlaybackInfoTab(val label: String) {
    CHAPTERS("Chapters"),
    RECOMMENDATIONS("Recommended"),
    DETAILS("Stats"),
}

/** Already-resolved title and episode copy; this component does not perform lookups. */
data class PlaybackInfoMetadata(
    val title: String,
    val episodeLabel: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val contentRating: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
)

/** A chapter timestamp in milliseconds. Labels are optional and never synthesized. */
data class PlaybackChapter(
    val timestampMs: Long,
    val title: String? = null,
)

/** Live values sampled from the active Media3 player by the caller. */
data class PlaybackInfoStats(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Float = 1f,
    val resolution: String = "Unavailable",
    val frameRate: String = "Unavailable",
    val videoCodec: String = "Unavailable",
    val videoBitrate: String = "Unavailable",
    val audio: String = "Unavailable",
    val subtitles: String = "Off",
    val state: String = "Preparing",
)

/**
 * A TV-friendly information overlay for an active stream.
 *
 * The host owns playback and metadata resolution. The component only presents the supplied
 * values and emits navigation, seek, close, and recommendation-selection events.
 */
@Composable
fun PlaybackInfoPanel(
    metadata: PlaybackInfoMetadata,
    chapters: List<PlaybackChapter>,
    recommendations: List<TmdbMovie>,
    recommendationsLoading: Boolean = false,
    playbackStats: PlaybackInfoStats,
    selectedTab: PlaybackInfoTab,
    onTabChange: (PlaybackInfoTab) -> Unit,
    onSeekTo: (Long) -> Unit,
    onClose: () -> Unit,
    onOpenRecommendation: (TmdbMovie) -> Unit,
    initialFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val progressDuration = playbackStats.durationMs.takeIf { it > 0L }
        ?: metadata.durationMs?.takeIf { it > 0L }
        ?: 0L
    val progress = if (progressDuration > 0L) {
        (playbackStats.positionMs.toFloat() / progressDuration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val backgroundUrl = metadata.artworkUrl?.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.76f))
            .zIndex(2f),
        contentAlignment = Alignment.Center,
    ) {
        if (backgroundUrl != null) {
            AsyncImage(
                model = backgroundUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.94f),
                        0.46f to Color.Black.copy(alpha = 0.86f),
                        1f to Color.Black.copy(alpha = 0.70f),
                    ),
                ),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(28.dp),
            color = colors.surface.copy(alpha = 0.94f),
            tonalElevation = 8.dp,
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(0.86f)
                        .fillMaxHeight()
                        .padding(start = 42.dp, top = 38.dp, end = 36.dp, bottom = 34.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "NOW PLAYING",
                            color = colors.primary,
                            style = MaterialTheme.typography.labelLarge,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = metadata.title,
                            color = colors.onSurface,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 34.sp,
                                lineHeight = 40.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        metadata.episodeLabel?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(9.dp))
                            Text(
                                text = it,
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val metaLine = listOfNotNull(
                            metadata.year?.takeIf { it.isNotBlank() },
                            metadata.contentRating?.takeIf { it.isNotBlank() },
                            formatInfoTime(progressDuration).takeIf { progressDuration > 0L },
                        ).joinToString("   ·   ")
                        if (metaLine.isNotBlank()) {
                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = metaLine,
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        metadata.overview?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(22.dp))
                            Text(
                                text = it,
                                color = colors.onSurface.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (playbackStats.isPlaying) colors.primary else colors.onSurfaceVariant),
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    text = if (playbackStats.isPlaying) "Playing" else "Paused",
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Text(
                                text = "${formatInfoTime(playbackStats.positionMs)} / ${formatInfoTime(progressDuration)}",
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(colors.onSurface.copy(alpha = 0.18f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .background(colors.primary),
                            )
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(colors.outline.copy(alpha = 0.4f)),
                )

                Column(
                    modifier = Modifier
                        .weight(1.14f)
                        .fillMaxHeight()
                        .padding(start = 30.dp, top = 26.dp, end = 28.dp, bottom = 24.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PlaybackInfoTab.values().forEach { tab ->
                                PanelTab(
                                    tab = tab,
                                    selected = selectedTab == tab,
                                    onClick = { onTabChange(tab) },
                                    focusRequester = if (tab == selectedTab) initialFocusRequester else null,
                                )
                            }
                        }
                        PanelIconButton(
                            contentDescription = "Close playback information",
                            onClick = onClose,
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    when (selectedTab) {
                        PlaybackInfoTab.DETAILS -> DetailsContent(
                            stats = playbackStats,
                        )
                        PlaybackInfoTab.CHAPTERS -> ChaptersContent(
                            chapters = chapters,
                            currentPositionMs = playbackStats.positionMs,
                            onSeekTo = onSeekTo,
                        )
                        PlaybackInfoTab.RECOMMENDATIONS -> RecommendationsContent(
                            recommendations = recommendations,
                            loading = recommendationsLoading,
                            onOpenRecommendation = onOpenRecommendation,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTab(
    tab: PlaybackInfoTab,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val icon = when (tab) {
        PlaybackInfoTab.DETAILS -> Icons.Default.Info
        PlaybackInfoTab.CHAPTERS -> Icons.Default.Schedule
        PlaybackInfoTab.RECOMMENDATIONS -> Icons.Default.Movie
    }
    Row(
        modifier = Modifier
            .let { base ->
                if (focusRequester != null) base.focusRequester(focusRequester) else base
            }
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    focused -> colors.primary
                    selected -> colors.primary.copy(alpha = 0.16f)
                    else -> Color.Transparent
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Tab, onClick = onClick)
            .focusable()
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) colors.onPrimary else if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            tab.label,
            color = if (focused) colors.onPrimary else if (selected) colors.onSurface else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun PanelIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (focused) colors.primary else colors.surfaceVariant)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        // The wrapper supplies a descriptive action label to accessibility services.
        androidx.compose.foundation.layout.Box(
            Modifier.semanticsDescription(contentDescription),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun DetailsContent(
    stats: PlaybackInfoStats,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Playback stats",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                icon = Icons.Default.Tv,
                label = "Resolution",
                value = stats.resolution,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Default.Movie,
                label = "Frame rate",
                value = stats.frameRate,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Default.Info,
                label = "Video",
                value = stats.videoCodec,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                icon = Icons.Default.VolumeUp,
                label = "Audio",
                value = stats.audio,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Default.ClosedCaption,
                label = "Subtitles",
                value = stats.subtitles,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Default.Speed,
                label = "Speed",
                value = String.format(Locale.getDefault(), "%.2f×", stats.playbackSpeed),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                icon = Icons.Default.Wifi,
                label = "Video bitrate",
                value = stats.videoBitrate,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Default.Download,
                label = "Buffered",
                value = formatInfoTime((stats.bufferedPositionMs - stats.positionMs).coerceAtLeast(0L)),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Default.PlayArrow,
                label = "Status",
                value = stats.state,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.72f))
            .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChaptersContent(
    chapters: List<PlaybackChapter>,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Chapters",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(14.dp))
        if (chapters.isEmpty()) {
            EmptyPanelState(
                icon = Icons.Default.Schedule,
                title = "No chapter markers",
                detail = "Chapter markers aren’t available for this stream.",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(chapters.sortedBy { it.timestampMs }) { chapter ->
                    val current = currentPositionMs >= chapter.timestampMs &&
                        (chapters.filter { it.timestampMs > chapter.timestampMs }
                            .minOfOrNull { it.timestampMs }?.let { currentPositionMs < it } ?: true)
                    ChapterRow(
                        chapter = chapter,
                        current = current,
                        onClick = { onSeekTo(chapter.timestampMs) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: PlaybackChapter,
    current: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    focused -> colors.primary
                    current -> colors.primary.copy(alpha = 0.15f)
                    else -> colors.surfaceVariant.copy(alpha = 0.46f)
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatInfoTime(chapter.timestampMs),
            color = if (focused) colors.onPrimary else colors.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.width(15.dp))
        chapter.title?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = if (focused) colors.onPrimary else colors.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (current) {
            Spacer(Modifier.width(10.dp))
            Text(
                "CURRENT",
                color = if (focused) colors.onPrimary.copy(alpha = 0.84f) else colors.primary,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun RecommendationsContent(
    recommendations: List<TmdbMovie>,
    loading: Boolean,
    onOpenRecommendation: (TmdbMovie) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "More like this",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(14.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(top = 38.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (recommendations.isEmpty()) {
            EmptyPanelState(
                icon = Icons.Default.Movie,
                title = "No recommendations",
                detail = "There aren’t any related titles to show right now.",
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(end = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(recommendations) { recommendation ->
                    RecommendationCard(
                        item = recommendation,
                        onClick = { onOpenRecommendation(recommendation) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    item: TmdbMovie,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val title = item.title?.takeIf { it.isNotBlank() }
        ?: item.name?.takeIf { it.isNotBlank() }
        ?: "Untitled"
    val imageUrl = item.posterUrl?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant.copy(alpha = 0.45f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(196.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "$title poster",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.Movie, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            color = colors.onSurface,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val year = item.releaseDate?.take(4)?.takeIf { it.length == 4 }
            ?: item.firstAirDate?.take(4)?.takeIf { it.length == 4 }
        if (year != null) {
            Spacer(Modifier.height(4.dp))
            Text(year, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyPanelState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(5.dp))
        Text(
            detail,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatInfoTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val totalSeconds = milliseconds / 1_000L
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(Locale.getDefault(), minutes / 60, minutes % 60, seconds)
    } else {
        "%d:%02d".format(Locale.getDefault(), minutes, seconds)
    }
}

private fun Modifier.semanticsDescription(description: String): Modifier =
    this.then(
        Modifier.semantics {
            contentDescription = description
        },
    )
