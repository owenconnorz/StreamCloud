package com.streamcloud.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.api.TmdbMovie

internal enum class SeekPreviewTab(val label: String) {
    CHAPTERS("Chapters"),
    RECOMMENDED("Recommended"),
    STATS("Stats"),
}

internal data class SeekPreviewStats(
    val resolution: String,
    val frameRate: String,
    val videoCodec: String,
    val videoBitrate: String,
    val audio: String,
    val subtitles: String,
    val status: String,
)

@Composable
internal fun SeekPreviewPanel(
    selectedTab: SeekPreviewTab,
    onTabSelected: (SeekPreviewTab) -> Unit,
    frames: List<SeekPreviewFrame>,
    centerPositionMs: Long,
    framesLoading: Boolean,
    framesUnavailable: Boolean,
    recommendations: List<TmdbMovie>,
    recommendationsLoading: Boolean,
    stats: SeekPreviewStats,
    onFocusChanged: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenRecommendation: (TmdbMovie) -> Unit,
    initialFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeekPreviewTab.values().forEachIndexed { index, tab ->
                var focused by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier)
                        .clip(RoundedCornerShape(8.dp))
                        .onFocusChanged {
                            focused = it.isFocused
                            onFocusChanged(it.isFocused)
                        }
                        .clickable(role = Role.Tab) { onTabSelected(tab) }
                        .focusable(),
                    color = when {
                        focused -> MaterialTheme.colorScheme.primary
                        selectedTab == tab -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else -> Color.White.copy(alpha = 0.08f)
                    },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = tab.label,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (selectedTab) {
            SeekPreviewTab.CHAPTERS -> SeekPreviewStrip(
                frames = frames,
                centerPositionMs = centerPositionMs,
                isLoading = framesLoading,
                previewUnavailable = framesUnavailable,
                isTv = true,
                onFocusChanged = onFocusChanged,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
            )
            SeekPreviewTab.RECOMMENDED -> when {
                recommendationsLoading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = Color.White,
                    )
                    Text("Loading recommendations…", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
                recommendations.isEmpty() -> Text(
                    "Recommendations aren’t available for this title.",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                )
                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(end = 4.dp),
                ) {
                    items(recommendations, key = { it.id }) { movie ->
                        Column(
                            modifier = Modifier
                                .width(92.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .onFocusChanged { onFocusChanged(it.isFocused) }
                                .clickable { onOpenRecommendation(movie) }
                                .focusable(),
                        ) {
                            AsyncImage(
                                model = movie.posterUrl,
                                contentDescription = movie.displayTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(66.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color.White.copy(alpha = 0.10f)),
                            )
                            Text(
                                movie.displayTitle,
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
            SeekPreviewTab.STATS -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SeekStat("Resolution", stats.resolution, Modifier.weight(1f))
                    SeekStat("Frame rate", stats.frameRate, Modifier.weight(1f))
                    SeekStat("Video", stats.videoCodec, Modifier.weight(1f))
                    SeekStat("Bitrate", stats.videoBitrate, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SeekStat("Audio", stats.audio, Modifier.weight(1f))
                    SeekStat("Subtitles", stats.subtitles, Modifier.weight(1f))
                    SeekStat("Playback", stats.status, Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SeekStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Color.White.copy(alpha = 0.58f), fontSize = 10.sp, maxLines = 1)
        Text(
            value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
