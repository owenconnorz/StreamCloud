package com.streamcloud.app.ui.screens

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.streamcloud.app.data.local.LocalAudioItem
import com.streamcloud.app.data.local.LocalImageItem
import com.streamcloud.app.data.local.LocalMediaPermissions
import com.streamcloud.app.data.local.LocalMediaSection
import com.streamcloud.app.data.local.LocalVideoItem
import com.streamcloud.app.ui.viewmodel.LocalFilesViewModel
import com.streamcloud.app.ui.viewmodel.PagedLocalMediaState
import kotlinx.coroutines.flow.collectLatest

@Composable
fun LocalFilesScreen(
    onPlayAudio: (LocalAudioItem) -> Unit,
    onPlayVideo: (LocalVideoItem) -> Unit,
    onOpenImage: (LocalImageItem) -> Unit,
) {
    val context = LocalContext.current
    val vm: LocalFilesViewModel = viewModel(factory = LocalFilesViewModel.factory(context))
    val state by vm.state.collectAsState()
    var activeSection by rememberSaveable { mutableStateOf(LocalMediaSection.Music) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val promptedSections = remember { mutableStateListOf<String>() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRevision++ }

    val requiredPermissions = remember(activeSection) {
        LocalMediaPermissions.requiredPermissions(activeSection, Build.VERSION.SDK_INT)
    }
    val missingPermissions = remember(activeSection, permissionRevision) {
        requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    val hasPermission = missingPermissions.isEmpty()

    LaunchedEffect(activeSection, permissionRevision, hasPermission) {
        if (hasPermission) {
            vm.ensureLoaded(activeSection)
        } else if (activeSection.name !in promptedSections) {
            promptedSections += activeSection.name
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "Local Files",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Browse music, videos, and images stored on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = activeSection.ordinal) {
            LocalMediaSection.entries.forEach { section ->
                Tab(
                    selected = section == activeSection,
                    onClick = { activeSection = section },
                    text = { Text(section.label) },
                    icon = {
                        Icon(
                            when (section) {
                                LocalMediaSection.Music -> Icons.Default.MusicNote
                                LocalMediaSection.Videos -> Icons.Default.Movie
                                LocalMediaSection.Images -> Icons.Default.Image
                            },
                            contentDescription = section.label,
                        )
                    },
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (!hasPermission) {
                PermissionRequiredState(
                    section = activeSection,
                    onRetry = {
                        permissionLauncher.launch(requiredPermissions.toTypedArray())
                    },
                )
            } else {
                when (activeSection) {
                    LocalMediaSection.Music -> AudioSection(
                        state = state.music,
                        onRetry = { vm.refresh(LocalMediaSection.Music) },
                        onLoadMore = { vm.loadMore(LocalMediaSection.Music) },
                        onPlay = { item ->
                            if (isUriAvailable(context, item.uri)) onPlayAudio(item)
                            else showMissingMediaToast(context, "audio file")
                        },
                    )

                    LocalMediaSection.Videos -> VideoSection(
                        state = state.videos,
                        onRetry = { vm.refresh(LocalMediaSection.Videos) },
                        onLoadMore = { vm.loadMore(LocalMediaSection.Videos) },
                        onPlay = { item ->
                            if (isUriAvailable(context, item.uri)) onPlayVideo(item)
                            else showMissingMediaToast(context, "video")
                        },
                    )

                    LocalMediaSection.Images -> ImageSection(
                        state = state.images,
                        onRetry = { vm.refresh(LocalMediaSection.Images) },
                        onLoadMore = { vm.loadMore(LocalMediaSection.Images) },
                        onOpen = { item ->
                            if (isUriAvailable(context, item.uri)) onOpenImage(item)
                            else showMissingMediaToast(context, "image")
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun LocalImageViewerScreen(
    imageUri: String,
    title: String,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model = Uri.parse(imageUri),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = {
                    CircularProgressIndicator(color = Color.White)
                },
                error = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Image unavailable", color = Color.White)
                    }
                },
            )
        }
    }
}

@Composable
private fun AudioSection(
    state: PagedLocalMediaState<LocalAudioItem>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onPlay: (LocalAudioItem) -> Unit,
) {
    val listState = rememberLazyListState()
    LoadMoreEffect(
        listState = listState,
        itemCount = state.items.size,
        hasMore = state.hasMore,
        loading = state.isLoading || state.isLoadingMore,
        onLoadMore = onLoadMore,
    )
    MediaListContainer(
        listState = listState,
        isLoading = state.isLoading,
        isEmpty = state.items.isEmpty(),
        emptyLabel = "No local music found.",
        error = state.error,
        onRetry = onRetry,
        footerLoading = state.isLoadingMore,
    ) {
        items(state.items, key = { it.id }) { item ->
            MediaRow(
                title = item.title,
                subtitle = listOfNotNull(item.artist.takeIf { it.isNotBlank() }, item.album).joinToString(" • "),
                detail = formatDuration(item.durationMs),
                placeholder = Icons.Default.MusicNote,
                imageModel = item.artworkUri,
                onClick = { onPlay(item) },
            )
        }
    }
}

@Composable
private fun VideoSection(
    state: PagedLocalMediaState<LocalVideoItem>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onPlay: (LocalVideoItem) -> Unit,
) {
    val listState = rememberLazyListState()
    LoadMoreEffect(
        listState = listState,
        itemCount = state.items.size,
        hasMore = state.hasMore,
        loading = state.isLoading || state.isLoadingMore,
        onLoadMore = onLoadMore,
    )
    MediaListContainer(
        listState = listState,
        isLoading = state.isLoading,
        isEmpty = state.items.isEmpty(),
        emptyLabel = "No local videos found.",
        error = state.error,
        onRetry = onRetry,
        footerLoading = state.isLoadingMore,
    ) {
        items(state.items, key = { it.id }) { item ->
            MediaRow(
                title = item.title,
                subtitle = "On-device video",
                detail = formatDuration(item.durationMs),
                placeholder = Icons.Default.Movie,
                imageModel = item.uri,
                isVideo = true,
                onClick = { onPlay(item) },
            )
        }
    }
}

@Composable
private fun ImageSection(
    state: PagedLocalMediaState<LocalImageItem>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (LocalImageItem) -> Unit,
) {
    val listState = rememberLazyListState()
    LoadMoreEffect(
        listState = listState,
        itemCount = state.items.size,
        hasMore = state.hasMore,
        loading = state.isLoading || state.isLoadingMore,
        onLoadMore = onLoadMore,
    )
    MediaListContainer(
        listState = listState,
        isLoading = state.isLoading,
        isEmpty = state.items.isEmpty(),
        emptyLabel = "No local images found.",
        error = state.error,
        onRetry = onRetry,
        footerLoading = state.isLoadingMore,
    ) {
        items(state.items, key = { it.id }) { item ->
            MediaRow(
                title = item.title,
                subtitle = "On-device image",
                detail = null,
                placeholder = Icons.Default.Image,
                imageModel = item.uri,
                imageHeight = 84.dp,
                onClick = { onOpen(item) },
            )
        }
    }
}

@Composable
private fun MediaListContainer(
    listState: LazyListState,
    isLoading: Boolean,
    isEmpty: Boolean,
    emptyLabel: String,
    error: String?,
    onRetry: () -> Unit,
    footerLoading: Boolean,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    when {
        isLoading && isEmpty -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        isEmpty -> {
            EmptyMediaState(
                label = error ?: emptyLabel,
                actionLabel = if (error != null) "Retry" else null,
                onAction = if (error != null) onRetry else null,
            )
        }

        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (error != null) {
                    item {
                        ErrorCard(message = error, onRetry = onRetry)
                    }
                }
                content()
                if (footerLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(
    title: String,
    subtitle: String,
    detail: String?,
    placeholder: ImageVector,
    imageModel: Any?,
    onClick: () -> Unit,
    isVideo: Boolean = false,
    imageHeight: androidx.compose.ui.unit.Dp = 64.dp,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalMediaThumbnail(
                model = imageModel,
                placeholder = placeholder,
                modifier = Modifier
                    .size(width = imageHeight * 1.2f, height = imageHeight)
                    .clip(RoundedCornerShape(10.dp)),
                isVideo = isVideo,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (placeholder == Icons.Default.Image) Icons.Default.Image else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocalMediaThumbnail(
    model: Any?,
    placeholder: ImageVector,
    modifier: Modifier,
    isVideo: Boolean,
) {
    val context = LocalContext.current
    val request = remember(model, isVideo) {
        ImageRequest.Builder(context)
            .data(model)
            .apply { if (isVideo) videoFrameMillis(0) }
            .crossfade(true)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        loading = {
            ThumbnailFallback(placeholder = placeholder, modifier = Modifier.fillMaxSize())
        },
        error = {
            ThumbnailFallback(placeholder = placeholder, modifier = Modifier.fillMaxSize())
        },
    )
}

@Composable
private fun ThumbnailFallback(
    placeholder: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            placeholder,
            contentDescription = null,
            tint = Color(0xFF8A8A8A),
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun PermissionRequiredState(
    section: LocalMediaSection,
    onRetry: () -> Unit,
) {
    val label = when (section) {
        LocalMediaSection.Music -> "Allow music access to browse and play on-device audio."
        LocalMediaSection.Videos -> "Allow video access to browse and play on-device videos."
        LocalMediaSection.Images -> "Allow image access to browse and view on-device photos."
    }
    EmptyMediaState(
        label = label,
        actionLabel = "Grant access",
        onAction = onRetry,
        icon = Icons.Default.PermMedia,
    )
}

@Composable
private fun EmptyMediaState(
    label: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    icon: ImageVector = Icons.Default.Folder,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun LoadMoreEffect(
    listState: LazyListState,
    itemCount: Int,
    hasMore: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState, itemCount, hasMore, loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collectLatest { lastVisible ->
                if (itemCount > 0 && hasMore && !loading && lastVisible >= itemCount - 5) {
                    onLoadMore()
                }
            }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun isUriAvailable(context: android.content.Context, uri: Uri): Boolean {
    return runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}

private fun showMissingMediaToast(context: android.content.Context, mediaLabel: String) {
    Toast.makeText(context, "This $mediaLabel is no longer available on the device.", Toast.LENGTH_SHORT).show()
}
