package com.streamcloud.app.ui.player

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository
import com.streamcloud.app.data.ytmusic.YtMusicPlaylistRepository
import com.streamcloud.app.data.ytmusic.YtmPlaylist
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(

    videoId: String?,
    songTitle: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sl = remember(context) { ServiceLocator.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val cookie by sl.settings.ytMusicCookie.collectAsState(initial = "")
    var playlists by remember { mutableStateOf<List<YtmPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    var sort by remember { mutableStateOf(Sort.Name) }
    var showCreate by remember { mutableStateOf(false) }
    var busyPlaylistId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            fetchError = null
            try {
                val c = cookie
                if (c.isBlank()) {
                    fetchError = "Sign in to YouTube Music to see your playlists"
                    playlists = emptyList()
                } else {
                    val lib = YtMusicLibraryRepository.sync(c)
                    playlists = lib.playlists
                    if (lib.failureReason != null) fetchError = lib.failureReason
                }
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(cookie) { reload() }

    val sortedPlaylists = remember(playlists, sort) {
        when (sort) {
            Sort.Name -> playlists.sortedBy { it.title.lowercase() }
            Sort.Songs -> playlists.sortedByDescending { it.songCount() }
            Sort.Recent -> playlists
        }
    }

    fun add(playlist: YtmPlaylist) {
        val vid = videoId
        if (vid.isNullOrBlank()) {
            Toast.makeText(context, "No song to add — start playing one first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            busyPlaylistId = playlist.id
            val c = cookie
            val ok = c.isNotBlank() &&
                YtMusicPlaylistRepository.addVideoToPlaylist(c, playlist.id, vid)
            Toast.makeText(
                context,
                if (ok) "Added \"$songTitle\" to ${playlist.title}"
                else "Couldn't add to ${playlist.title}",
                Toast.LENGTH_SHORT,
            ).show()
            busyPlaylistId = null
            if (ok) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0E0E0E),
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp, max = 640.dp)
                .padding(horizontal = 16.dp),
        ) {

            CreatePlaylistTile(onClick = { showCreate = true })

            Spacer(Modifier.height(14.dp))


            SortChip(sort = sort, onChange = { sort = it })

            Spacer(Modifier.height(12.dp))


            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                playlists.isEmpty() -> EmptyState(fetchError ?: "You don't have any YT Music playlists yet")
                else -> LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sortedPlaylists, key = { it.id }) { pl ->
                        PlaylistRow(
                            playlist = pl,
                            isAdding = busyPlaylistId == pl.id,
                            onClick = { add(pl) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            cookie = cookie,
            seedVideoId = videoId,
            onCreated = { _, name ->
                showCreate = false
                Toast.makeText(context, "Playlist \"$name\" created", Toast.LENGTH_SHORT).show()
                reload()
            },
            onDismiss = { showCreate = false },
        )
    }
}

@Composable
private fun CreatePlaylistTile(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1B2B2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, "Create", tint = Color(0xFFB8E0DA), modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Create playlist",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun SortChip(sort: Sort, onChange: (Sort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFB8E0DA))
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Text(sort.label, color = Color(0xFF0E2625),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFB8E0DA))
                    .clickable { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, "Pick sort", tint = Color(0xFF0E2625))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Sort.values().forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        onClick = { onChange(s); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: YtmPlaylist, isAdding: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable(enabled = !isAdding, onClick = onClick)
            .padding(10.dp),
    ) {
        if (!playlist.thumbnail.isNullOrBlank()) {
            AsyncImage(
                model = playlist.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.LibraryMusic, null, tint = Color.White.copy(alpha = 0.6f)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = playlist.subtitle?.takeIf { it.isNotBlank() }
                ?: playlist.songCountLabel()
            Text(sub, color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isAdding) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFFB8E0DA))
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.LibraryMusic, null, tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(10.dp))
        Text(message, color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CreatePlaylistDialog(
    cookie: String,
    seedVideoId: String?,
    onCreated: (id: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create playlist") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Playlist name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (cookie.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Sign in to YT Music to sync — until you do, this won't create a real playlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && name.isNotBlank() && cookie.isNotBlank(),
                onClick = {
                    scope.launch {
                        loading = true
                        val id = YtMusicPlaylistRepository.createPlaylist(
                            cookie = cookie,
                            title = name.trim(),
                            seedVideoId = seedVideoId,
                        )
                        loading = false
                        if (id != null) {
                            onCreated(id, name.trim())
                        } else {
                            Toast.makeText(context, "Couldn't create playlist", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) { Text(if (loading) "Creating…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class Sort(val label: String) { Name("Name"), Songs("Songs"), Recent("Recent") }

private fun YtmPlaylist.songCountLabel(): String {
    val n = songCount()
    return if (n > 0) "$n songs" else (subtitle ?: "")
}

private fun YtmPlaylist.songCount(): Int {
    val sub = subtitle ?: return 0
    val m = Regex("(\\d+)\\s*songs?", RegexOption.IGNORE_CASE).find(sub)
    return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}
