@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.streamcloud.app.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.streamcloud.app.data.livetv.LiveTvChannel
import com.streamcloud.app.data.livetv.LiveTvSource
import com.streamcloud.app.data.livetv.SourceType
import com.streamcloud.app.ui.viewmodel.LiveTvTab
import com.streamcloud.app.ui.viewmodel.LiveTvViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ── Helper ────────────────────────────────────────────────────────────────────

private fun qualityBadge(name: String, group: String = ""): String {
    val t = "$name $group".uppercase()
    return when {
        "4K" in t || "2160" in t || "UHD" in t -> "4K"
        "FHD" in t || "1080" in t              -> "FHD"
        "HD" in t || "720" in t                -> "HD"
        else                                    -> "SD"
    }
}

private fun nowStr(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

// ── Main Screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTvScreen(onPlayChannel: (url: String, title: String, subtitle: String?) -> Unit) {
    val context = LocalContext.current
    val vm: LiveTvViewModel = viewModel(factory = LiveTvViewModel.factory(context))
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showAddSheet     by remember { mutableStateOf(false) }
    var showSourcesSheet by remember { mutableStateOf(false) }

    // Auto-scroll to selected channel
    LaunchedEffect(state.selectedChannel?.id) {
        val idx = state.displayChannels.indexOfFirst { it.id == state.selectedChannel?.id }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── 1. Mini player ────────────────────────────────────────────
            MiniPlayerBox(
                channel  = state.selectedChannel,
                onExpand = {
                    state.selectedChannel?.let { ch ->
                        onPlayChannel(ch.url, ch.name, ch.currentProgram.ifBlank { null })
                    }
                },
            )

            // ── 2. Channel info bar ───────────────────────────────────────
            if (state.selectedChannel != null) {
                val ch = state.selectedChannel!!
                val isFav = ch.id in state.favorites
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // CH number
                        val chNum = if (ch.chno > 0) "CH ${ch.chno}" else "CH —"
                        Text(
                            chNum,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        // Group
                        Text(
                            ch.group,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        // Manage sources
                        IconButton(
                            onClick = { showSourcesSheet = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.Settings, "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Logo
                        if (ch.logo.isNotBlank()) {
                            AsyncImage(
                                model = ch.logo,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                ch.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                QualityBadge(qualityBadge(ch.name, ch.group))
                                if (ch.language.isBlank() || ch.language.lowercase().startsWith("en")) {
                                    LangBadge("EN")
                                } else if (ch.language.isNotBlank()) {
                                    LangBadge(ch.language.take(2).uppercase())
                                }
                            }
                        }
                        // Favourite heart
                        IconButton(onClick = { vm.toggleFavorite(ch.id) }) {
                            Icon(
                                if (isFav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (isFav) "Unfavourite" else "Favourite",
                                tint = if (isFav) Color(0xFFE53935)
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Now playing
                    if (ch.currentProgram.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "NOW \u2014",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                ch.currentProgram,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
            }

            // ── 3. Tab row: Search | Favourites | Recently Watched ────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Search chip (CHANNELS tab)
                FilterChip(
                    selected = state.activeTab == LiveTvTab.CHANNELS,
                    onClick  = { vm.setActiveTab(LiveTvTab.CHANNELS) },
                    label    = { Text("Search channels") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, null, Modifier.size(16.dp))
                    },
                )
                FilterChip(
                    selected = state.activeTab == LiveTvTab.FAVORITES,
                    onClick  = { vm.setActiveTab(LiveTvTab.FAVORITES) },
                    label    = { Text("Favourites") },
                    leadingIcon = {
                        Icon(Icons.Default.Favorite, null, Modifier.size(16.dp))
                    },
                )
                // Recently Watched with count badge
                val recentCount = state.recentlyWatched.size
                FilterChip(
                    selected = state.activeTab == LiveTvTab.RECENT,
                    onClick  = { vm.setActiveTab(LiveTvTab.RECENT) },
                    label    = { Text("Recently Watched") },
                    trailingIcon = if (recentCount > 0) ({
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.activeTab == LiveTvTab.RECENT)
                                        MaterialTheme.colorScheme.onPrimary.copy(0.2f)
                                    else MaterialTheme.colorScheme.primary
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$recentCount",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (state.activeTab == LiveTvTab.RECENT)
                                    MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }) else null,
                )
            }

            // ── 4. Search field (only on CHANNELS tab) ────────────────────
            if (state.activeTab == LiveTvTab.CHANNELS) {
                OutlinedTextField(
                    value         = state.searchQuery,
                    onValueChange = { vm.setSearch(it) },
                    placeholder   = { Text("Search channels…") },
                    leadingIcon   = { Icon(Icons.Default.Search, null) },
                    trailingIcon  = if (state.searchQuery.isNotBlank()) ({
                        IconButton(onClick = { vm.setSearch("") }) {
                            Icon(Icons.Default.Close, "Clear")
                        }
                    }) else null,
                    singleLine    = true,
                    shape         = RoundedCornerShape(0.dp),
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor   = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
                        focusedContainerColor   = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
                    ),
                )
            }

            // ── 5. EPG header ─────────────────────────────────────────────
            val total = state.channels.size
            val selCh = state.selectedChannel
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("CHANNELS $total")
                        if (selCh != null && selCh.chno > 0) append("   CH ${selCh.chno}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    // Time slots
                    val now = nowStr()
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("NOW $now", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    // Settings icon (when no channel selected)
                    if (selCh == null) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { showSourcesSheet = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Settings, "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f))

            // ── 6. EPG channel list ───────────────────────────────────────
            val displayChannels = state.displayChannels
            if (displayChannels.isEmpty() && !state.loading) {
                EmptyChannelsList(
                    tab     = state.activeTab,
                    onAdd   = { showAddSheet = true },
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(displayChannels, key = { _, ch -> ch.id }) { idx, ch ->
                        EpgChannelRow(
                            channel    = ch,
                            displayNum = if (ch.chno > 0) ch.chno else idx + 1,
                            isSelected = ch.id == state.selectedChannel?.id,
                            isFavorite = ch.id in state.favorites,
                            onClick    = {
                                vm.selectChannel(ch)
                                vm.addToRecent(ch)
                            },
                            onFavorite = { vm.toggleFavorite(ch.id) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f),
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }

        // ── FAB ──────────────────────────────────────────────────────────────
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 80.dp, end = 16.dp)
        ) {
            FloatingActionButton(
                onClick        = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                shape          = CircleShape,
            ) {
                Icon(Icons.Default.Add, "Add source", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }

    // ── Add Source sheet ──────────────────────────────────────────────────────
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            containerColor   = MaterialTheme.colorScheme.surface,
        ) {
            AddSourceContent(
                onAdd     = { src -> vm.addSource(src); showAddSheet = false },
                onDismiss = { showAddSheet = false },
                newId     = vm.newSourceId(),
            )
        }
    }

    // ── Manage Sources sheet ──────────────────────────────────────────────────
    if (showSourcesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSourcesSheet = false },
            containerColor   = MaterialTheme.colorScheme.surface,
        ) {
            SourcesContent(
                sources             = state.sources,
                englishOnly         = state.englishOnly,
                onToggleEnglish     = { vm.toggleEnglishOnly() },
                hideDeadStreams     = state.hideDeadStreams,
                onToggleDeadStreams  = { vm.toggleHideDeadStreams() },
                probing             = state.probing,
                probedCount         = state.probedCount,
                totalChannels       = state.channels.size,
                onRemove            = { vm.removeSource(it) },
                onRefresh           = { scope.launch { vm.refreshChannels() }; showSourcesSheet = false },
                onDismiss           = { showSourcesSheet = false },
            )
        }
    }
}

// ── Mini Player ───────────────────────────────────────────────────────────────

@Composable
private fun MiniPlayerBox(
    channel: LiveTvChannel?,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
            .also { it.playWhenReady = true }
    }

    LaunchedEffect(channel?.id) {
        if (channel != null) {
            val item = MediaItem.fromUri(channel.url)
            exoPlayer.setMediaItem(item)
            exoPlayer.prepare()
        } else {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (channel == null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.LiveTv, null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Select a channel below",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 13.sp,
                )
            }
        }

        if (channel != null) {
            // Gradient scrim bottom
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(0.6f))
                        )
                    )
            )

            // LIVE badge — top right
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFCC0000))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(4.dp))
                Text("LIVE", color = Color.White, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }

            // Channel logo — bottom left
            if (channel.logo.isNotBlank()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(0.4f)),
                )
            }

            // Fullscreen expand button — bottom right
            IconButton(
                onClick  = onExpand,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
            ) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "Full screen",
                    tint     = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

// ── Quality / Language badges ─────────────────────────────────────────────────

@Composable
private fun QualityBadge(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LangBadge(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── EPG Channel Row ───────────────────────────────────────────────────────────

@Composable
private fun EpgChannelRow(
    channel: LiveTvChannel,
    displayNum: Int,
    isSelected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val bg = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(0.25f)
    else Color.Transparent

    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .then(
                if (isSelected) Modifier.border(
                    width = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(0.dp),
                ).padding(start = 2.5.dp) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Left column: ch number ────────────────────────────────────────
        Text(
            "$displayNum",
            style    = MaterialTheme.typography.labelSmall,
            color    = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp).padding(start = 8.dp),
            textAlign = TextAlign.Center,
        )

        // ── Logo ──────────────────────────────────────────────────────────
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logo.isNotBlank()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                Icon(
                    Icons.Default.Tv, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // ── Name + badges ─────────────────────────────────────────────────
        Column(Modifier.width(90.dp)) {
            Text(
                channel.name,
                style    = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color    = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                QualityBadge(qualityBadge(channel.name, channel.group))
                val langLabel = when {
                    channel.language.isBlank() ||
                    channel.language.lowercase().startsWith("en") -> "EN"
                    else -> channel.language.take(2).uppercase()
                }
                LangBadge(langLabel)
            }
        }

        Spacer(Modifier.width(8.dp))

        // ── Right column: program info ────────────────────────────────────
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(0.6f)
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LIVE pill
            Box(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFCC0000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text("LIVE", color = Color.White, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.currentProgram.ifBlank { "Guide pending…" },
                    style    = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color    = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.currentProgram.isNotBlank()) {
                    Text(
                        nowStr(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Favourite heart ───────────────────────────────────────────────
        IconButton(
            onClick  = onFavorite,
            modifier = Modifier.size(36.dp).padding(end = 4.dp),
        ) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint     = if (isFavorite) Color(0xFFE53935)
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyChannelsList(tab: LiveTvTab, onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                if (tab == LiveTvTab.FAVORITES) Icons.Default.FavoriteBorder
                else Icons.Default.LiveTv,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                when (tab) {
                    LiveTvTab.FAVORITES -> "No favourites yet"
                    LiveTvTab.RECENT    -> "No recently watched"
                    LiveTvTab.CHANNELS  -> "No live TV sources yet"
                },
                style     = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (tab == LiveTvTab.CHANNELS) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add an M3U playlist, Xtream credentials, or a single stream URL.",
                    style     = MaterialTheme.typography.bodySmall,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAdd, shape = RoundedCornerShape(28.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Source")
                }
            }
        }
    }
}

// ── Add Source Sheet Content ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSourceContent(
    onAdd: (LiveTvSource) -> Unit,
    onDismiss: () -> Unit,
    newId: String,
) {
    var selectedType by remember { mutableStateOf(SourceType.M3U_URL) }
    var name         by remember { mutableStateOf("") }
    var url          by remember { mutableStateOf("") }
    var epgUrl       by remember { mutableStateOf("") }
    var server       by remember { mutableStateOf("") }
    var username     by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var showPass     by remember { mutableStateOf(false) }

    val isValid = name.isNotBlank() && when (selectedType) {
        SourceType.M3U_URL -> url.isNotBlank()
        SourceType.XTREAM  -> server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        SourceType.SINGLE  -> url.isNotBlank()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        )
        Spacer(Modifier.height(16.dp))
        Text("Add Source",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceTypeChip("M3U URL",    Icons.Default.Link,          selectedType == SourceType.M3U_URL, Modifier.weight(1f)) { selectedType = SourceType.M3U_URL }
            SourceTypeChip("Xtream",     Icons.Default.VpnKey,        selectedType == SourceType.XTREAM,  Modifier.weight(1f)) { selectedType = SourceType.XTREAM }
            SourceTypeChip("Single",     Icons.Default.OndemandVideo,  selectedType == SourceType.SINGLE,  Modifier.weight(1f)) { selectedType = SourceType.SINGLE }
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(value = name, onValueChange = { name = it },
            label = { Text("Source name") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(12.dp))

        when (selectedType) {
            SourceType.M3U_URL -> {
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text("M3U / M3U8 URL") },
                    placeholder = { Text("http://…/playlist.m3u") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = epgUrl, onValueChange = { epgUrl = it },
                    label = { Text("EPG / XMLTV URL (optional)") },
                    placeholder = { Text("http://…/epg.xml") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
            SourceType.XTREAM -> {
                OutlinedTextField(value = server, onValueChange = { server = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://provider.com:8080") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text("Username") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text("Password") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                 if (showPass) "Hide" else "Show")
                        }
                    })
            }
            SourceType.SINGLE -> {
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text("Stream URL") },
                    placeholder = { Text("http://…/stream.m3u8") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        }

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp)) { Text("Cancel") }
            Button(
                onClick = {
                    onAdd(LiveTvSource(id = newId, name = name.trim(), type = selectedType,
                        url = url.trim(), xtreamServer = server.trim(),
                        xtreamUser = username.trim(), xtreamPass = password, epgUrl = epgUrl.trim()))
                },
                enabled = isValid, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
            ) { Text("Add Source") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SourceTypeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (selected) 4.dp else 0.dp,
    ) {
        Column(Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

// ── Sources Management Sheet ──────────────────────────────────────────────────

@Composable
private fun SourcesContent(
    sources: List<LiveTvSource>,
    englishOnly: Boolean,
    onToggleEnglish: () -> Unit,
    hideDeadStreams: Boolean,
    onToggleDeadStreams: () -> Unit,
    probing: Boolean,
    probedCount: Int,
    totalChannels: Int,
    onRemove: (LiveTvSource) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally)
            .size(width = 40.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(0.2f)))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("My Sources",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "Refresh",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))

        // English-only toggle
        SettingsToggleRow(
            icon = Icons.Outlined.Language,
            title = "English channels only",
            subtitle = "Hides channels in other languages",
            checked = englishOnly,
            onToggle = onToggleEnglish,
        )
        Spacer(Modifier.height(8.dp))

        // Hide dead streams toggle
        SettingsToggleRow(
            icon = Icons.Outlined.SignalWifiStatusbarConnectedNoInternet4,
            title = "Hide dead streams",
            subtitle = if (probing) "Checking $probedCount / $totalChannels streams\u2026"
                       else if (hideDeadStreams && totalChannels > 0) "Checked \u2014 unreachable channels hidden"
                       else "Probes each stream when loading",
            checked = hideDeadStreams,
            onToggle = onToggleDeadStreams,
        )
        Spacer(Modifier.height(12.dp))

        if (sources.isEmpty()) {
            Text("No sources added yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp))
        } else {
            sources.forEach { src ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center) {
                        Icon(when (src.type) {
                                 SourceType.M3U_URL -> Icons.Default.Link
                                 SourceType.XTREAM  -> Icons.Default.VpnKey
                                 SourceType.SINGLE  -> Icons.Default.OndemandVideo
                             }, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(src.name, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (src.type) {
                                 SourceType.M3U_URL -> "M3U Playlist"
                                 SourceType.XTREAM  -> "Xtream: ${src.xtreamServer.substringAfter("//").take(28)}"
                                 SourceType.SINGLE  -> "Single stream"
                             },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onRemove(src) }) {
                        Icon(Icons.Default.Delete, "Remove",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text("Close")
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}
