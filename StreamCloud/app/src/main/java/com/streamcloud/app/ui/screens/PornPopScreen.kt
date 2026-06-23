package com.streamcloud.app.ui.screens

import android.net.Uri
import android.webkit.CookieManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import androidx.compose.foundation.text.KeyboardOptions
import coil.compose.AsyncImage
import com.streamcloud.app.data.api.PornPopApiClient.resultMediaUrl
import com.streamcloud.app.data.api.PornPopTaskResponse
import com.streamcloud.app.ui.viewmodel.PornPopMode
import com.streamcloud.app.ui.viewmodel.PornPopState
import com.streamcloud.app.ui.viewmodel.PornPopViewModel

private val Purple = Color(0xFF7C3AED)
private val PurpleLight = Color(0xFFBB86FC)
private val PurpleSurface = Color(0xFF1E1033)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PornPopScreen() {
    val context = LocalContext.current
    val vm: PornPopViewModel = viewModel(factory = PornPopViewModel.factory(context))
    val state by vm.state.collectAsState()

    // Read pornpop.ai session cookies (set by a future WebView login screen)
    val cookie = remember {
        runCatching {
            CookieManager.getInstance().getCookie("https://pornpop.ai")
        }.getOrNull()
    }

    var activeTab by remember { mutableIntStateOf(0) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> vm.setImage(uri) }

    LaunchedEffect(activeTab) {
        if (activeTab == 1) vm.loadMyTasks(cookie)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "PornPop",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "18+ · AI-Powered Adult Content",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = Purple.copy(alpha = 0.18f),
                shape = RoundedCornerShape(50),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.AutoAwesome, null,
                        tint = PurpleLight, modifier = Modifier.size(14.dp))
                    Text("AI Studio",
                        color = PurpleLight,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── Tabs ─────────────────────────────────────────────────────────────
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = Purple,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                    color = Purple,
                )
            },
        ) {
            Tab(
                selected = activeTab == 0,
                onClick  = { activeTab = 0 },
                text     = { Text("Create") },
                icon     = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) },
            )
            Tab(
                selected = activeTab == 1,
                onClick  = { activeTab = 1 },
                text     = { Text("My Work") },
                icon     = { Icon(Icons.Filled.Collections, null, Modifier.size(18.dp)) },
            )
        }

        when (activeTab) {
            0 -> CreateTab(vm = vm, state = state, cookie = cookie,
                onPickImage = { imagePicker.launch("image/*") })
            1 -> MyWorkTab(state = state)
        }
    }
}

// ── Create tab ─────────────────────────────────────────────────────────────

@Composable
private fun CreateTab(
    vm: PornPopViewModel,
    state: PornPopState,
    cookie: String?,
    onPickImage: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

        // ── Mode chips ───────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PornPopMode.entries.forEach { mode ->
                ModeChip(
                    label    = mode.label,
                    selected = state.mode == mode,
                    icon     = when (mode) {
                        PornPopMode.Image   -> Icons.Filled.Image
                        PornPopMode.Video   -> Icons.Filled.Videocam
                        PornPopMode.Undress -> Icons.Filled.Visibility
                    },
                    onClick  = { vm.setMode(mode) },
                )
            }
        }

        // ── Image upload card ────────────────────────────────────────────
        val imageRequired = state.mode != PornPopMode.Image
        ImagePickerCard(
            uri      = state.selectedImageUri,
            required = imageRequired,
            onClick  = onPickImage,
        )

        // ── Prompt field (Image + Video modes) ───────────────────────────
        if (state.mode != PornPopMode.Undress) {
            OutlinedTextField(
                value       = state.prompt,
                onValueChange = vm::setPrompt,
                modifier    = Modifier.fillMaxWidth(),
                label       = { Text("Prompt") },
                placeholder = {
                    Text(
                        if (state.mode == PornPopMode.Video)
                            "Describe the motion or scene (optional)…"
                        else
                            "Describe what you want to generate…"
                    )
                },
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }

        // ── Style selector (Image + Video modes) ─────────────────────────
        if (state.mode != PornPopMode.Undress) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Style",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val styles = listOf("realistic", "anime", "hentai", "3d", "illustrated")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    styles.forEach { s ->
                        StyleChip(
                            label    = s.replaceFirstChar { it.uppercase() },
                            selected = state.style == s,
                            onClick  = { vm.setStyle(s) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // ── Video-mode note ──────────────────────────────────────────────
        if (state.mode == PornPopMode.Video) {
            Surface(
                color  = Purple.copy(alpha = 0.10f),
                shape  = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Info, null,
                        tint = PurpleLight, modifier = Modifier.size(18.dp))
                    Text(
                        "Video generation takes 1–3 minutes. You can close this screen — check My Work when done.",
                        color = PurpleLight,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ── Generate button ──────────────────────────────────────────────
        Button(
            onClick  = { vm.generate(cookie) },
            enabled  = !state.generating,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = Purple),
        ) {
            if (state.generating) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(22.dp),
                    color       = Color.White,
                    strokeWidth = 2.5.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    state.progressLabel.ifBlank { "Generating…" },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (state.mode) {
                        PornPopMode.Image   -> "Generate Image"
                        PornPopMode.Video   -> "Generate Video"
                        PornPopMode.Undress -> "Undress Photo"
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        // ── Error card ───────────────────────────────────────────────────
        state.error?.let { err ->
            Surface(
                color  = MaterialTheme.colorScheme.errorContainer,
                shape  = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp))
                    Text(
                        err,
                        color  = MaterialTheme.colorScheme.onErrorContainer,
                        style  = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ── Result card ──────────────────────────────────────────────────
        state.resultUrl?.let { url ->
            ResultCard(url = url, isVideo = state.isVideo)
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ── My Work tab ────────────────────────────────────────────────────────────

@Composable
private fun MyWorkTab(state: PornPopState) {
    when {
        state.loadingTasks -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Purple)
            }
        }
        state.myTasks.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Collections, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(52.dp))
                    Text("No generations yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium)
                    Text("Create something in the Create tab",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        else -> {
            LazyVerticalGrid(
                columns  = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement   = Arrangement.spacedBy(12.dp),
            ) {
                items(state.myTasks) { task ->
                    TaskCard(task)
                }
            }
        }
    }
}

// ── Small composables ──────────────────────────────────────────────────────

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val bg = if (selected) Purple else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        color    = bg,
        shape    = RoundedCornerShape(50),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Text(label, color = fg,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun StyleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderMod = if (selected)
        Modifier.border(1.5.dp, Purple, RoundedCornerShape(8.dp)) else Modifier
    Surface(
        color    = if (selected) Purple.copy(alpha = 0.18f)
                   else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape    = RoundedCornerShape(8.dp),
        modifier = modifier.clickable(onClick = onClick).then(borderMod),
    ) {
        Text(
            label,
            color  = if (selected) PurpleLight else MaterialTheme.colorScheme.onSurface,
            style  = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun ImagePickerCard(
    uri: Uri?,
    required: Boolean,
    onClick: () -> Unit,
) {
    val height = if (uri != null) 200.dp else 140.dp
    val border = if (required && uri == null)
        Modifier.border(2.dp, Purple.copy(alpha = 0.6f), RoundedCornerShape(16.dp)) else Modifier

    Surface(
        color    = MaterialTheme.colorScheme.surfaceContainerLow,
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clickable(onClick = onClick)
            .then(border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (uri != null) {
                AsyncImage(
                    model              = uri,
                    contentDescription = "Selected image",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                )
                // "Change" overlay
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f),
                            RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Edit, "Change", tint = Color.White,
                            modifier = Modifier.size(28.dp))
                        Text("Tap to change", color = Color.White,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.AddPhotoAlternate,
                        "Upload",
                        tint = if (required) Purple else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        if (required) "Upload image (required)" else "Upload image (optional)",
                        color = if (required) PurpleLight
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "JPG / PNG / WebP · max 5 MB",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(url: String, isVideo: Boolean) {
    val context = LocalContext.current
    Surface(
        color  = PurpleSurface,
        shape  = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = PurpleLight,
                    modifier = Modifier.size(18.dp))
                Text("Generated Result",
                    color = PurpleLight,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
            if (isVideo) {
                val player = remember(url) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(url))
                        repeatMode = ExoPlayer.REPEAT_MODE_ONE
                        prepare()
                        playWhenReady = true
                    }
                }
                DisposableEffect(url) { onDispose { player.release() } }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = true
                                layoutParams  = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                this.player = player
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                AsyncImage(
                    model              = url,
                    contentDescription = "Generated image",
                    contentScale       = ContentScale.FillWidth,
                    modifier           = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

@Composable
private fun TaskCard(task: PornPopTaskResponse) {
    val url = task.resultMediaUrl()
    val status = task.status ?: "pending"
    Box(
        Modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model              = url,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                when (status) {
                    "pending", "processing" -> Icons.Filled.HourglassEmpty
                    "failed", "error"       -> Icons.Filled.ErrorOutline
                    else                    -> Icons.Filled.Schedule
                },
                contentDescription = status,
                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }
        // Status pill overlay
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                    )
                )
                .padding(8.dp),
        ) {
            Text(
                status.replaceFirstChar { it.uppercase() },
                color = when (status) {
                    in listOf("completed", "done", "success") -> Color(0xFF69F0AE)
                    in listOf("failed", "error")              -> Color(0xFFFF5252)
                    else                                      -> Color.White
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
