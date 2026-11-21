package com.streamcloud.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Button
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

internal enum class WearDestination(val label: String) {
    HOME("Home"),
    MUSIC("Music"),
    MUSIC_PLAYER("Music Player"),
    MOVIES("Movies"),
    MOVIE_PLAYER("Movie Player"),
    ADULT("Adult"),
    ADULT_CATEGORIES("Adult Categories"),
    ADULT_TRENDING("Adult Trending"),
    ADULT_PLAYER("Adult Player"),
    CONTINUE_WATCHING("Continue Watching"),
    MOVIE_GENRES("Movie Genres"),
    POPULAR("Popular"),
    MOVIE_DETAILS("Movie Details"),
    DOWNLOADS("Downloads"),
    SETTINGS("Settings"),
}

private val Violet = Color(0xFFE24DFF)
private val Pink = Color(0xFFFF4EA8)
private val Ink = Color(0xFF08070D)
private val Muted = Color(0xFFB9ADC3)
private val Panel = Color(0xFF18121F)

@Composable
internal fun WearHomeScreen(
    snapshot: WearPlaybackSnapshot,
    onNavigate: (WearDestination) -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF26102E), Ink, Color(0xFF050407)),
                ),
            )
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            ) {
                Text("STREAMCLOUD", color = Violet, style = MaterialTheme.typography.caption2, fontWeight = FontWeight.Bold)
                Text("MUSIC · MOVIES · MORE", color = Color.White, style = MaterialTheme.typography.caption1)
            }
        }
        item {
            if (snapshot.hasItem) {
                NowPlayingTile(
                    snapshot = snapshot,
                    onClick = { onNavigate(WearDestination.MUSIC_PLAYER) },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF32133A), Panel)))
                        .border(1.dp, Color(0xFF75337D), RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HubGlyph("♪", "Music")
                        Column {
                            Text("Nothing playing", color = Color.White, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.SemiBold)
                            Text("Browse your phone library", color = Muted, style = MaterialTheme.typography.caption2)
                        }
                    }
                }
            }
        }
        item {
            Text("EXPLORE", color = Muted, style = MaterialTheme.typography.caption2, fontWeight = FontWeight.Bold)
        }
        item { HubNavigationRow("♪", "Music", "Library and playlists", onClick = { onNavigate(WearDestination.MUSIC) }) }
        item { HubNavigationRow("▣", "Movies", "Browse, genres and popular", onClick = { onNavigate(WearDestination.MOVIES) }) }
        item { HubNavigationRow("♡", "Adult", "18+ · phone provider", onClick = { onNavigate(WearDestination.ADULT) }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                HubQuickLink("▶", "Continue watching", Modifier.weight(1f)) { onNavigate(WearDestination.CONTINUE_WATCHING) }
                HubQuickLink("↓", "Downloads", Modifier.weight(1f)) { onNavigate(WearDestination.DOWNLOADS) }
                HubQuickLink("≡", "Settings", Modifier.weight(1f)) { onNavigate(WearDestination.SETTINGS) }
            }
        }
        item { Spacer(Modifier.size(4.dp)) }
    }
}

@Composable
private fun NowPlayingTile(snapshot: WearPlaybackSnapshot, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFB63DC9), RoundedCornerShape(26.dp)),
        icon = {
            if (snapshot.artwork.isNotBlank()) {
                AsyncImage(
                    model = snapshot.artwork,
                    contentDescription = "Current track artwork",
                    modifier = Modifier.size(38.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                HubGlyph("♪", "Current track")
            }
        },
        label = {
            Text(snapshot.title.ifBlank { "Now Playing" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
        },
        secondaryLabel = {
            Text(snapshot.artist.ifBlank { if (snapshot.playing) "Playing now" else "Paused" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

@Composable
private fun HubNavigationRow(icon: String, title: String, detail: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = { HubGlyph(icon, title) },
        label = { Text(title, color = Color.White, maxLines = 1, fontWeight = FontWeight.SemiBold) },
        secondaryLabel = { Text(detail, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun HubQuickLink(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        CompactButton(
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            WearLineIcon(icon, tint = Pink, size = 18.dp)
        }
        Text(label, color = Muted, style = MaterialTheme.typography.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HubGlyph(glyph: String, label: String) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(0xFF35163E))
            .border(1.dp, Color(0xFF8D3C97), CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        WearLineIcon(glyph, tint = Pink, size = 18.dp)
    }
}

@Composable
internal fun WearUnavailableDestination(
    destination: WearDestination,
    explanation: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF25102C), Ink, Color(0xFF050407))))
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF35163E))
                    .border(1.dp, Color(0xFF8D3C97), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(destinationGlyph(destination), color = Pink, style = MaterialTheme.typography.title2)
            }
        }
        item {
            Text(destination.label.uppercase(), color = Violet, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        item {
            Text(explanation, color = Color(0xFFE6DDE9), style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
        }
        item {
            Chip(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(actionLabel, color = Color.White, maxLines = 2, textAlign = TextAlign.Center) },
            )
        }
        item {
            Text("StreamCloud keeps accounts and provider settings on your phone.", color = Muted, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun WearSettingsScreen(
    selectedOutput: PlaybackTarget,
    phoneConnected: Boolean,
    updateTitle: String?,
    updateDetails: String,
    updateEnabled: Boolean,
    onSelectOutput: (PlaybackTarget) -> Unit,
    onUpdate: () -> Unit,
    onDownloads: () -> Unit,
    onBack: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF25102C), Ink, Color(0xFF050407))))
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item {
            Text("SETTINGS", color = Violet, style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold)
        }
        item { Text("Playback output", color = Color.White, style = MaterialTheme.typography.caption1) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaybackTarget.entries.forEach { target ->
                    Button(
                        onClick = { onSelectOutput(target) },
                        modifier = Modifier.semantics {
                            contentDescription = "${target.label} audio output${if (target == selectedOutput) ", selected" else ""}"
                        },
                    ) {
                        Text(if (target == selectedOutput) "✓ ${target.label}" else target.label, style = MaterialTheme.typography.caption2)
                    }
                }
            }
        }
        item {
            Text(
                "Provider sign-in, cookies, adult access and provider choices stay on your phone.",
                color = Muted,
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Panel)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("APPEARANCE", color = Violet, style = MaterialTheme.typography.caption2, fontWeight = FontWeight.Bold)
                Text("StreamCloud violet watch theme", color = Muted, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
            }
        }
        item {
            Chip(
                onClick = onDownloads,
                modifier = Modifier.fillMaxWidth(),
                icon = { WearLineIcon("↓", tint = Pink, size = 18.dp) },
                label = { Text("Downloads", maxLines = 1) },
                secondaryLabel = { Text("View files saved on your phone", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Panel)
                    .border(1.dp, Color(0xFF63346C), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("DEVICE SYNC", color = Violet, style = MaterialTheme.typography.caption2, fontWeight = FontWeight.Bold)
                Text(
                    if (phoneConnected) "Phone connected" else "Connect your phone to sync",
                    color = if (phoneConnected) Color.White else Muted,
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (updateTitle != null) {
            item {
                Chip(
                    onClick = onUpdate,
                    enabled = updateEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(updateTitle, maxLines = 2, textAlign = TextAlign.Center) },
                    secondaryLabel = { Text(updateDetails, maxLines = 2, textAlign = TextAlign.Center) },
                )
            }
        } else {
            item { Text("No watch update is currently available.", color = Muted, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
        }
        item {
            Chip(onClick = onBack, modifier = Modifier.fillMaxWidth(), label = { Text("Back to StreamCloud") })
        }
    }
}

private fun destinationGlyph(destination: WearDestination): String = when (destination) {
    WearDestination.MUSIC, WearDestination.MUSIC_PLAYER -> "♪"
    WearDestination.MOVIES, WearDestination.MOVIE_PLAYER, WearDestination.MOVIE_DETAILS -> "▣"
    WearDestination.ADULT, WearDestination.ADULT_CATEGORIES, WearDestination.ADULT_TRENDING, WearDestination.ADULT_PLAYER -> "♡"
    WearDestination.DOWNLOADS -> "↓"
    WearDestination.SETTINGS -> "≡"
    WearDestination.MOVIE_GENRES -> "⌁"
    WearDestination.POPULAR -> "P"
    WearDestination.CONTINUE_WATCHING -> "▶"
    else -> "▶"
}
