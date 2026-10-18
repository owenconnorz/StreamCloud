package com.streamcloud.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamcloud.app.data.watchparty.*
import com.streamcloud.app.ui.icons.AppIcons as Icons

@Composable
fun WatchPartyPage(
    media: PartyMedia? = null,
    onWatch: ((PartyMedia) -> Unit)? = null,
    showPageTitle: Boolean = true,
) {
    val context = LocalContext.current
    remember(context) { WatchPartyRepository.initialize(context); true }
    val state by WatchPartyRepository.state.collectAsState()
    var server by remember { mutableStateOf(WatchPartyRepository.serverUrl) }
    var name by remember { mutableStateOf(WatchPartyRepository.displayName) }
    var code by remember { mutableStateOf("") }
    var showJoin by remember { mutableStateOf(false) }
    var configOpen by remember { mutableStateOf(server.isBlank()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val room = state.room

    fun configured(action: () -> Unit) {
        WatchPartyRepository.saveConfiguration(server, name).fold(
            onSuccess = { localError = null; configOpen = false; action() },
            onFailure = { localError = it.message; configOpen = true },
        )
    }
    fun invite(room: PartyRoom) {
        val formatted = room.code.chunked(4).joinToString("-")
        val text = "Join my StreamCloud Watch Party: $formatted\nServer: ${WatchPartyRepository.serverUrl}\nOpen Settings > Watch Party and enter the code."
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "Invite friends",
            )
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showPageTitle) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Watch Party", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (room == null) "Invite your friends. Everyone plays their own source and stays in sync."
                        else "${room.members.size} of ${room.maxMembers} watching · ${if (state.connected) "Connected" else "Reconnecting"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { configOpen = !configOpen }, enabled = room == null && !state.busy) {
                    Icon(Icons.Filled.Settings, "Party settings")
                }
            }
        }
        val error = localError ?: state.error
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (configOpen) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Party settings", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = server, onValueChange = { server = it },
                        label = { Text("StreamCloud party server") },
                        supportingText = { Text("Use your published StreamCloud room-server address.") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = room == null && !state.busy,
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) },
                        label = { Text("Your display name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = room == null,
                    )
                    if (room == null) {
                        Button(onClick = { configured {} }, enabled = !state.busy) { Text("Save settings") }
                    }
                }
            }
        }
        if (room == null) {
            Text("Watch together", style = MaterialTheme.typography.headlineMedium)
            Text(
                media?.title ?: "Start a party, then choose a film or episode. Friends join using your StreamCloud invite code.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { configured { WatchPartyRepository.create(media) } },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Start watch party")
            }
            OutlinedButton(
                onClick = { showJoin = !showJoin },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(if (showJoin) "Cancel joining" else "Join with a code")
            }
            if (showJoin) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(8) },
                        label = { Text("8-character StreamCloud code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    )
                    Button(
                        onClick = { configured { WatchPartyRepository.join(code) } },
                        enabled = !state.busy && code.length == 8,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("Join party")
                    }
                }
            }
            Text(
                "Everyone chooses their own source. Private stream links and provider details are never shared.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val formatted = room.code.chunked(4).joinToString("-")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                room.members.take(6).forEach { member ->
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(member.name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (state.connected) {
                    IconButton(onClick = { invite(room) }) {
                        Icon(Icons.Filled.Add, "Invite a friend")
                    }
                }
            }
            Text("Invite a friend", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.connected) "Party code" else "Reconnecting…", style = MaterialTheme.typography.labelMedium)
                    Text(formatted, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(room.code)) }) {
                    Text("Copy")
                }
            }
            val selected = room.media
            Text(selected?.title ?: "Waiting for the host to choose a film", style = MaterialTheme.typography.titleMedium)
            if (selected != null && selected.key != media?.key) {
                if (onWatch != null) {
                    Button(
                        onClick = { onWatch(selected) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("Open film and choose a source") }
                } else {
                    Text("Open this film from Settings → Watch Party to choose your own source.")
                }
            }
            if (selected != null && selected.key == media?.key) {
                Text(if (state.canControl) "Playback controls are shared." else "Playback follows the host.")
            }
            if (state.isHost) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Only I can control playback")
                        Text("Guests can still pause or rewind locally.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = room.hostOnly, onCheckedChange = WatchPartyRepository::hostOnly, enabled = state.connected)
                }
            } else {
                Text(
                    if (room.hostOnly) "The host controls playback. You can pause or rewind for yourself."
                    else "Everyone can control playback.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "Everyone selects their own source; StreamCloud never shares private stream links or provider credentials.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("People", style = MaterialTheme.typography.titleMedium)
                    room.members.forEach { member ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(member.name.firstOrNull()?.uppercase() ?: "?")
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    member.name + if (member.id == room.hostId) " · Host" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (!member.connected) "Reconnecting" else member.status.replaceFirstChar(Char::uppercase),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.isHost && member.id != state.memberId) {
                                TextButton(onClick = { confirm = member.id }, enabled = state.connected) { Text("Remove") }
                            }
                        }
                    }
                }
            }
            Text(
                "Use the same cut of the film. Different runtimes or edits may not line up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isHost) {
                OutlinedButton(onClick = { confirm = "end" }, enabled = state.connected, modifier = Modifier.fillMaxWidth()) {
                    Text("End party for everyone")
                }
            }
            Button(
                onClick = WatchPartyRepository::leave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Leave party") }
        }
    }
    confirm?.let { target ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (target == "end") "End this party?" else "Remove this person?") },
            text = { Text(if (target == "end") "Everyone will leave the room." else "They will be disconnected. Anyone with the invite code can join again.") },
            confirmButton = { TextButton(onClick = {
                if (target == "end") WatchPartyRepository.end() else WatchPartyRepository.kick(target)
                confirm = null
            }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun WatchPartyPlayerDialog(
    media: PartyMedia?,
    onDismiss: () -> Unit,
    onWatch: ((PartyMedia) -> Unit)?,
) {
    val screenWidth = LocalContext.current.resources.configuration.screenWidthDp.dp
    val panelWidth = if (screenWidth >= 600.dp) {
        (screenWidth * 0.58f).coerceAtLeast(360.dp).coerceAtMost(680.dp)
    } else screenWidth
    val panelShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
            Surface(
                Modifier.width(panelWidth).fillMaxHeight(),
                shape = panelShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Watch party", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    WatchPartyPage(
                        media = media,
                        onWatch = onWatch?.let { watch -> { selected -> onDismiss(); watch(selected) } },
                        showPageTitle = false,
                    )
                }
            }
        }
    }
}
