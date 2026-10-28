package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.spotify.SpotifyHomeCard
import com.streamcloud.app.data.spotify.SpotifyHomeFeed
import com.streamcloud.app.data.spotify.SpotifyTrack

private data class SpotifyBrowseTile(
    val title: String,
    val query: String,
    val filter: SpotifySearchFilter,
    val color: Color,
)

private val SpotifySearchCategories = listOf(
    SpotifyBrowseTile("Music", "new music", SpotifySearchFilter.Tracks, Color(0xFFE8118B)),
    SpotifyBrowseTile("Podcasts", "podcasts", SpotifySearchFilter.Podcasts, Color(0xFF006450)),
    SpotifyBrowseTile("Audiobooks", "audiobooks", SpotifySearchFilter.All, Color(0xFF243A72)),
    SpotifyBrowseTile("Live Events", "live music", SpotifySearchFilter.All, Color(0xFF8100D7)),
)

private val SpotifyMoreForYouTiles = listOf(
    SpotifyBrowseTile("Made For You", "mix", SpotifySearchFilter.Playlists, Color(0xFF8A68A6)),
    SpotifyBrowseTile("Charts", "top songs", SpotifySearchFilter.Playlists, Color(0xFF71418D)),
    SpotifyBrowseTile("New Releases", "new releases", SpotifySearchFilter.Albums, Color(0xFF1674D6)),
    SpotifyBrowseTile("Upcoming releases", "upcoming releases", SpotifySearchFilter.Albums, Color(0xFF006858)),
    SpotifyBrowseTile("Discover", "discover weekly", SpotifySearchFilter.Playlists, Color(0xFF8A68A6)),
    SpotifyBrowseTile("Radio", "radio", SpotifySearchFilter.Playlists, Color(0xFFDB1685)),
)

private val SpotifyGenreTiles = listOf(
    SpotifyBrowseTile("Pop", "pop", SpotifySearchFilter.Playlists, Color(0xFF437F99)),
    SpotifyBrowseTile("Dance / Electronic", "dance electronic", SpotifySearchFilter.Playlists, Color(0xFFB32C91)),
    SpotifyBrowseTile("Hip-Hop", "hip hop", SpotifySearchFilter.Playlists, Color(0xFF777777)),
    SpotifyBrowseTile("R&B", "r and b", SpotifySearchFilter.Playlists, Color(0xFF543B58)),
    SpotifyBrowseTile("Rock", "rock", SpotifySearchFilter.Playlists, Color(0xFF0D754B)),
    SpotifyBrowseTile("Alternative", "alternative", SpotifySearchFilter.Playlists, Color(0xFF43519C)),
)

private val SpotifyAudiobookTiles = listOf(
    SpotifyBrowseTile("Audiobook Charts", "audiobook charts", SpotifySearchFilter.All, Color(0xFF243A72)),
    SpotifyBrowseTile("Editors’ picks", "editors audiobook picks", SpotifySearchFilter.All, Color(0xFF476F82)),
    SpotifyBrowseTile("Biography & Memoir", "biography memoir audiobook", SpotifySearchFilter.All, Color(0xFFDF1269)),
    SpotifyBrowseTile("Business Biography", "business biography audiobook", SpotifySearchFilter.All, Color(0xFF707070)),
    SpotifyBrowseTile("Entertainment", "entertainment audiobook", SpotifySearchFilter.All, Color(0xFF1377D8)),
    SpotifyBrowseTile("Memoir", "memoir audiobook", SpotifySearchFilter.All, Color(0xFF178317)),
)

private val SpotifyMoodTiles = listOf(
    SpotifyBrowseTile("In the car", "in the car", SpotifySearchFilter.Playlists, Color(0xFF293F9D)),
    SpotifyBrowseTile("Travel", "travel", SpotifySearchFilter.Playlists, Color(0xFF1166C7)),
    SpotifyBrowseTile("Throwback", "throwback", SpotifySearchFilter.Playlists, Color(0xFF8A68A6)),
    SpotifyBrowseTile("Mood", "mood", SpotifySearchFilter.Playlists, Color(0xFFE11A86)),
    SpotifyBrowseTile("Party", "party", SpotifySearchFilter.Playlists, Color(0xFFB32C91)),
    SpotifyBrowseTile("Workout Music", "workout", SpotifySearchFilter.Playlists, Color(0xFF777777)),
)

private val SpotifyExploreTiles = listOf(
    SpotifyBrowseTile("Decades", "decades playlists", SpotifySearchFilter.Playlists, Color(0xFF9D6652)),
    SpotifyBrowseTile("Hometown", "hometown playlists", SpotifySearchFilter.Playlists, Color(0xFF51364F)),
    SpotifyBrowseTile("Carnival Sounds", "carnival music", SpotifySearchFilter.Playlists, Color(0xFF3249AA)),
    SpotifyBrowseTile("Black Voices", "black voices", SpotifySearchFilter.Playlists, Color(0xFF153F72)),
    SpotifyBrowseTile("Netflix", "netflix playlists", SpotifySearchFilter.Playlists, Color(0xFFE21B31)),
    SpotifyBrowseTile("Disney", "disney playlists", SpotifySearchFilter.Playlists, Color(0xFF365AC0)),
)

@Composable
internal fun SpotifySearchBrowseContent(
    feed: SpotifyHomeFeed?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSearch: (String, SpotifySearchFilter) -> Unit,
    onPlayTrack: (SpotifyTrack) -> Unit,
    onOpenCard: (SpotifyHomeCard) -> Unit,
) {
    val artwork = feed?.sections.orEmpty()
        .flatMap { it.items }
        .mapNotNull { it.imageUrl?.takeIf(String::isNotBlank) }
    val discoveries = feed?.sections.orEmpty()
        .flatMap { it.items }
        .distinctBy { it.id }
        .take(12)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "search-categories") {
            SpotifyBrowseCategoryGrid(
                SpotifySearchCategories,
                artwork,
                tileHeight = 82.dp,
                onSearch = onSearch,
            )
        }

        item(key = "discover-title") {
            SpotifyBrowseSectionTitle(
                title = "Discover something new",
                actionLabel = "See all",
                onAction = { onSearch("discover", SpotifySearchFilter.Playlists) },
            )
        }
        if (discoveries.isEmpty()) {
            item(key = "discover-loading") {
                when {
                    loading -> Row(
                        Modifier.fillMaxWidth().height(170.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Loading Spotify discoveries…")
                    }
                    error != null -> Column(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                    else -> Text(
                        "Spotify has no discoveries to show right now.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item(key = "discover-cards") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(discoveries, key = { "discover-${it.id}" }) { card ->
                        SpotifyDiscoveryCard(card = card, onClick = { onOpenCard(card) })
                    }
                }
            }
        }

        item(key = "more-for-you") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SpotifyBrowseSectionTitle(
                    title = "More for you",
                    actionLabel = "See all",
                    onAction = { onSearch("mix", SpotifySearchFilter.Playlists) },
                )
                SpotifyBrowseCategoryGrid(
                    SpotifyMoreForYouTiles,
                    artwork,
                    tileHeight = 108.dp,
                    onSearch = onSearch,
                )
            }
        }

        item(key = "genres") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SpotifyBrowseSectionTitle(
                    title = "Genres",
                    actionLabel = "See all",
                    onAction = { onSearch("music genres", SpotifySearchFilter.Playlists) },
                )
                SpotifyBrowseCategoryGrid(
                    SpotifyGenreTiles,
                    artwork,
                    tileHeight = 108.dp,
                    onSearch = onSearch,
                )
            }
        }

        item(key = "audiobooks") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SpotifyBrowseSectionTitle(
                    title = "Audiobooks",
                    actionLabel = "See all",
                    onAction = { onSearch("audiobooks", SpotifySearchFilter.All) },
                )
                SpotifyBrowseCategoryGrid(
                    SpotifyAudiobookTiles,
                    artwork,
                    tileHeight = 108.dp,
                    onSearch = onSearch,
                )
            }
        }

        item(key = "moods") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SpotifyBrowseSectionTitle(
                    title = "Moods & Activities",
                    actionLabel = "See all",
                    onAction = { onSearch("mood playlists", SpotifySearchFilter.Playlists) },
                )
                SpotifyBrowseCategoryGrid(
                    SpotifyMoodTiles,
                    artwork,
                    tileHeight = 108.dp,
                    onSearch = onSearch,
                )
            }
        }

        item(key = "explore") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SpotifyBrowseSectionTitle(
                    title = "Explore",
                    actionLabel = "See all",
                    onAction = { onSearch("explore playlists", SpotifySearchFilter.Playlists) },
                )
                SpotifyBrowseCategoryGrid(
                    SpotifyExploreTiles,
                    artwork,
                    tileHeight = 108.dp,
                    onSearch = onSearch,
                )
            }
        }
    }
}

@Composable
private fun SpotifyBrowseSectionTitle(
    title: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                Text(actionLabel, color = Color.LightGray)
            }
        }
    }
}

@Composable
private fun SpotifyBrowseCategoryGrid(
    categories: List<SpotifyBrowseTile>,
    artwork: List<String>,
    tileHeight: androidx.compose.ui.unit.Dp,
    onSearch: (String, SpotifySearchFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        categories.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEachIndexed { columnIndex, category ->
                    val artworkIndex = rowIndex * 2 + columnIndex
                    SpotifyBrowseCategoryTile(
                        category = category,
                        imageUrl = artwork.getOrNull(artworkIndex % artwork.size.coerceAtLeast(1)),
                        modifier = Modifier.weight(1f).height(tileHeight),
                        onClick = { onSearch(category.query, category.filter) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpotifyBrowseCategoryTile(
    category: SpotifyBrowseTile,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(category.color)
            .clickable(onClick = onClick),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.CenterEnd)
                    .size(90.dp)
                    .rotate(12f)
                    .padding(4.dp),
            )
        }
        Text(
            category.title,
            modifier = Modifier.align(Alignment.TopStart)
                .fillMaxWidth(0.72f)
                .padding(10.dp),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpotifyDiscoveryCard(
    card: SpotifyHomeCard,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(160.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.size(width = 160.dp, height = 210.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (!card.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                ),
            )
            Text(
                card.title,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            card.subtitle.ifBlank { "Spotify" },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
