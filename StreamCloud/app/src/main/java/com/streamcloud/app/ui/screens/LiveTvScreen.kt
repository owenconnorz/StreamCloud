@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.streamcloud.app.ui.screens

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.streamcloud.app.data.livetv.LiveTvChannel
import com.streamcloud.app.data.livetv.LiveTvSource
import com.streamcloud.app.data.livetv.SourceType
import com.streamcloud.app.ui.viewmodel.LiveTvViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTvScreen() {
    val context   = LocalContext.current
    val vm: LiveTvViewModel = viewModel(factory = LiveTvViewModel.factory(context))
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    var showAddSheet    by remember { mutableStateOf(false) }
    var showSourcesSheet by remember { mutableStateOf(false) }
    var showSearch      by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {

        // ── Main content ────────────────────────────────────────────────────
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                LiveTvTopBar(
                    searchActive = showSearch,
                    query        = state.searchQuery,
                    onQueryChange = { vm.setSearch(it) },
                    onSearchToggle = { showSearch = !showSearch; if (!showSearch) vm.setSearch("") },
                    onManageSources = { showSourcesSheet = true },
                    channelCount = state.filteredChannels.size,
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAddSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.Add, "Add source", tint = MaterialTheme.colorScheme.onPrimary)
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {

                // ── Group filter chips ──────────────────────────────────────
                if (state.groups.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected  = state.selectedGroup == null,
                            onClick   = { vm.selectGroup(null) },
                            label     = { Text("All") },
                        )
                        state.groups.forEach { g ->
                            FilterChip(
                                selected = state.selectedGroup == g,
                                onClick  = { vm.selectGroup(if (state.selectedGroup == g) null else g) },
                                label    = { Text(g, maxLines = 1) },
                            )
                        }
                    }
                }

                // ── Body ────────────────────────────────────────────────────
                when {
                    state.loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(16.dp))
                                Text("Loading channels…",
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    state.sources.isEmpty() -> EmptySourcesPlaceholder { showAddSheet = true }
                    state.filteredChannels.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No channels found",
                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                                 style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(
                                start = 12.dp, end = 12.dp,
                                top = 4.dp, bottom = 96.dp
                            ),
                            verticalArrangement   = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.filteredChannels, key = { it.id }) { ch ->
                                ChannelCard(channel = ch) { vm.selectChannel(ch) }
                            }
                        }
                    }
                }

                // Error snackbar
                state.error?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, null,
                                 tint = MaterialTheme.colorScheme.onErrorContainer,
                                 modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(err,
                                 color = MaterialTheme.colorScheme.onErrorContainer,
                                 style = MaterialTheme.typography.bodySmall,
                                 modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ── Fullscreen player ────────────────────────────────────────────────
        AnimatedVisibility(
            visible = state.selectedChannel != null,
            enter   = slideInVertically(tween(350)) { it },
            exit    = slideOutVertically(tween(300)) { it },
        ) {
            state.selectedChannel?.let { ch ->
                LiveTvPlayerScreen(channel = ch, onClose = { vm.selectChannel(null) })
            }
        }
    }

    // ── Add Source bottom sheet ─────────────────────────────────────────────
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            containerColor   = MaterialTheme.colorScheme.surface,
        ) {
            AddSourceContent(
                onAdd = { src ->
                    vm.addSource(src)
                    showAddSheet = false
                },
                onDismiss = { showAddSheet = false },
                newId = vm.newSourceId(),
            )
        }
    }

    // ── Sources management bottom sheet ────────────────────────────────────
    if (showSourcesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSourcesSheet = false },
            containerColor   = MaterialTheme.colorScheme.surface,
        ) {
            SourcesContent(
                sources   = state.sources,
                onRemove  = { vm.removeSource(it) },
                onRefresh = { scope.launch { vm.refreshChannels() }; showSourcesSheet = false },
                onDismiss = { showSourcesSheet = false },
            )
        }
    }
}

// ── Top Bar ──────────────────────────────────────────────────────────────────

@Composable
private fun LiveTvTopBar(
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onManageSources: () -> Unit,
    channelCount: Int,
) {
    Surface(
        color    = MaterialTheme.colorScheme.background,
        modifier = Modifier.statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searchActive) {
                IconButton(onClick = onSearchToggle) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search",
                         tint = MaterialTheme.colorScheme.onBackground)
                }
                TextField(
                    value         = query,
                    onValueChange = onQueryChange,
                    modifier      = Modifier.weight(1f),
                    placeholder   = { Text("Search channels…") },
                    singleLine    = true,
                    trailingIcon  = {
                        if (query.isNotEmpty())
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Default.Close, "Clear")
                            }
                    },
                    shape  = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor  = Color.Transparent,
                    ),
                )
            } else {
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Live TV",
                        style      = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color      = MaterialTheme.colorScheme.onBackground,
                    )
                    if (channelCount > 0)
                        Text(
                            "$channelCount channels",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                IconButton(onClick = onSearchToggle) {
                    Icon(Icons.Default.Search, "Search",
                         tint = MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = onManageSources) {
                    Icon(Icons.Default.Settings, "Manage sources",
                         tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}

// ── Channel Card ─────────────────────────────────────────────────────────────

@Composable
private fun ChannelCard(channel: LiveTvChannel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column {
            // Logo area
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo.isNotBlank()) {
                    AsyncImage(
                        model          = channel.logo,
                        contentDescription = channel.name,
                        contentScale   = ContentScale.Fit,
                        modifier       = Modifier.fillMaxSize().padding(8.dp),
                    )
                } else {
                    Icon(
                        Icons.Default.Tv,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(40.dp),
                    )
                }

                // LIVE badge
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFCC0000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(Color.White, CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("LIVE", color = Color.White, fontSize = 9.sp,
                         fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }

            // Channel info
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    channel.name,
                    color      = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    style      = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    channel.currentProgram.ifBlank { channel.group },
                    color  = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style  = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ── Empty State ───────────────────────────────────────────────────────────────

@Composable
private fun EmptySourcesPlaceholder(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.LiveTv,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "No live TV sources yet",
                style  = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color  = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Add an M3U playlist, Xtream credentials, or a single stream URL to get started.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onAdd,
                shape   = RoundedCornerShape(28.dp),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add Source")
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
        // Handle
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "Add Source",
            style  = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color  = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))

        // Type selector
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceTypeChip(
                label    = "M3U URL",
                icon     = Icons.Default.Link,
                selected = selectedType == SourceType.M3U_URL,
                modifier = Modifier.weight(1f),
                onClick  = { selectedType = SourceType.M3U_URL },
            )
            SourceTypeChip(
                label    = "Xtream",
                icon     = Icons.Default.VpnKey,
                selected = selectedType == SourceType.XTREAM,
                modifier = Modifier.weight(1f),
                onClick  = { selectedType = SourceType.XTREAM },
            )
            SourceTypeChip(
                label    = "Single",
                icon     = Icons.Default.OndemandVideo,
                selected = selectedType == SourceType.SINGLE,
                modifier = Modifier.weight(1f),
                onClick  = { selectedType = SourceType.SINGLE },
            )
        }

        Spacer(Modifier.height(20.dp))

        // Name field (always)
        OutlinedTextField(
            value         = name,
            onValueChange = { name = it },
            label         = { Text("Source name") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            shape         = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(12.dp))

        // Type-specific fields
        when (selectedType) {
            SourceType.M3U_URL -> {
                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    label         = { Text("M3U / M3U8 URL") },
                    placeholder   = { Text("http://…/playlist.m3u") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value         = epgUrl,
                    onValueChange = { epgUrl = it },
                    label         = { Text("EPG / XMLTV URL (optional)") },
                    placeholder   = { Text("http://…/epg.xml") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            SourceType.XTREAM -> {
                OutlinedTextField(
                    value         = server,
                    onValueChange = { server = it },
                    label         = { Text("Server URL") },
                    placeholder   = { Text("http://provider.com:8080") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value         = username,
                    onValueChange = { username = it },
                    label         = { Text("Username") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value         = password,
                    onValueChange = { password = it },
                    label         = { Text("Password") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                    visualTransformation = if (showPass) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (showPass) "Hide password" else "Show password",
                            )
                        }
                    },
                )
            }
            SourceType.SINGLE -> {
                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    label         = { Text("Stream URL") },
                    placeholder   = { Text("http://…/stream.m3u8") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // Action buttons
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick  = onDismiss,
                modifier = Modifier.weight(1f),
                shape    = RoundedCornerShape(28.dp),
            ) { Text("Cancel") }

            Button(
                onClick  = {
                    onAdd(LiveTvSource(
                        id           = newId,
                        name         = name.trim(),
                        type         = selectedType,
                        url          = url.trim(),
                        xtreamServer = server.trim(),
                        xtreamUser   = username.trim(),
                        xtreamPass   = password,
                        epgUrl       = epgUrl.trim(),
                    ))
                },
                enabled  = isValid,
                modifier = Modifier.weight(1f),
                shape    = RoundedCornerShape(28.dp),
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
    Surface(
        onClick   = onClick,
        modifier  = modifier,
        shape     = RoundedCornerShape(12.dp),
        color     = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (selected) 4.dp else 0.dp,
    ) {
        Column(
            Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint     = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                label,
                style  = MaterialTheme.typography.labelSmall,
                color  = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                         else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

// ── Sources Management Sheet ──────────────────────────────────────────────────

@Composable
private fun SourcesContent(
    sources: List<LiveTvSource>,
    onRemove: (LiveTvSource) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "My Sources",
                style  = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color  = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "Refresh",
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (sources.isEmpty()) {
            Text(
                "No sources added yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            sources.forEach { src ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when (src.type) {
                                SourceType.M3U_URL -> Icons.Default.Link
                                SourceType.XTREAM  -> Icons.Default.VpnKey
                                SourceType.SINGLE  -> Icons.Default.OndemandVideo
                            },
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(src.name,
                             fontWeight = FontWeight.SemiBold,
                             color      = MaterialTheme.colorScheme.onSurface,
                             maxLines   = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (src.type) {
                                SourceType.M3U_URL -> "M3U Playlist"
                                SourceType.XTREAM  -> "Xtream: ${src.xtreamServer.substringAfter("//").take(28)}"
                                SourceType.SINGLE  -> "Single stream"
                            },
                            style  = MaterialTheme.typography.bodySmall,
                            color  = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onRemove(src) }) {
                        Icon(Icons.Default.Delete, "Remove",
                             tint = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text("Close")
        }
    }
}

// ── Fullscreen Player ─────────────────────────────────────────────────────────

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LiveTvPlayerScreen(channel: LiveTvChannel, onClose: () -> Unit) {
    val context = LocalContext.current

    val player = remember(channel.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(channel.url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(channel.url) { onDispose { player.release() } }

    var isPlaying    by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    val hideKey      = remember { mutableStateOf(0) }

    // Auto-hide controls after 4 s
    LaunchedEffect(hideKey.value, showControls) {
        if (!showControls) return@LaunchedEffect
        delay(4_000)
        showControls = false
    }

    // Mirror player play state
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    showControls = !showControls
                    hideKey.value++
                }
            }
    ) {
        // Video surface
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            player.setVideoSurface(Surface(st))
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            player.setVideoSurface(null); return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter   = fadeIn(tween(200)),
            exit    = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f    to Color.Black.copy(alpha = 0.70f),
                            0.25f to Color.Transparent,
                            0.75f to Color.Transparent,
                            1f    to Color.Black.copy(alpha = 0.70f),
                        )
                    )
            ) {
                // Top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.KeyboardArrowDown, "Close",
                             tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            channel.name,
                            color      = Color.White,
                            style      = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                        )
                        if (channel.currentProgram.isNotBlank())
                            Text(
                                "Now: ${channel.currentProgram}",
                                color  = Color.White.copy(alpha = 0.75f),
                                style  = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                    }
                    // LIVE pill
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFCC0000))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text("LIVE", color = Color.White, fontSize = 11.sp,
                             fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // Centre play / pause
                IconButton(
                    onClick  = { if (player.isPlaying) player.pause() else player.play(); hideKey.value++ },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .background(Color.Black.copy(alpha = 0.40f), CircleShape),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Play/Pause",
                        tint     = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }

                // Bottom: channel group
                if (channel.group.isNotBlank()) {
                    Text(
                        channel.group,
                        color    = Color.White.copy(alpha = 0.6f),
                        style    = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}
