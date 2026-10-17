package com.streamcloud.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.streamcloud.app.data.watchparty.*

@Composable
fun WatchPartyPage(
    media: PartyMedia? = null,
    onWatch: ((PartyMedia) -> Unit)? = null,
) {
    val context = LocalContext.current
    remember(context) { WatchPartyRepository.initialize(context); true }
    val state by WatchPartyRepository.state.collectAsState()
    var server by remember { mutableStateOf(WatchPartyRepository.serverUrl) }
    var name by remember { mutableStateOf(WatchPartyRepository.displayName) }
    var code by remember { mutableStateOf("") }
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Watch Party", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Watch together on phones and TVs. Everyone plays their own authorised source; playback stays in sync.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val error = localError ?: state.error
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick = { configOpen = !configOpen }) {
            Text(if (configOpen) "Hide server settings" else "Server and display name")
        }
        if (configOpen) {
            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("StreamCloud party server HTTPS address") },
                supportingText = { Text("Use your published room service, not a Nuvio server or a private development URL.") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = room == null && !state.busy,
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text("Your display name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = room == null,
            )
            if (room == null) Button(onClick = { configured {} }, enabled = !state.busy) { Text("Save settings") }
        }
        if (room == null) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Start a party", style = MaterialTheme.typography.titleLarge)
                    Text(media?.title ?: "Create a room, then open a film or episode in StreamCloud's video player.")
                    Button(onClick = { configured { WatchPartyRepository.create(media) } }, enabled = !state.busy) {
                        Text("Create room")
                    }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Join friends", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(8) },
                        label = { Text("Eight-character invite code") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                    )
                    Button(onClick = { configured { WatchPartyRepository.join(code) } }, enabled = !state.busy && code.length == 8) {
                        Text("Join room")
                    }
                    Text("These are StreamCloud rooms. Nuvio invite codes cannot be used here.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (state.connected) "Your invite code" else "Reconnecting…", style = MaterialTheme.typography.labelLarge)
                    val formatted = room.code.chunked(4).joinToString("-")
                    Text(formatted, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace)
                    Text("${room.members.size} / ${room.maxMembers} people · ${if (state.isHost) "You are the host" else "Guest"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(room.code)) }) { Text("Copy code") }
                        OutlinedButton(onClick = {
                            val text = "Join my StreamCloud Watch Party: $formatted\nServer: ${WatchPartyRepository.serverUrl}\nOpen Settings > Watch Party and enter the code."
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                "Invite friends",
                            ))
                        }) { Text("Invite") }
                    }
                    val selected = room.media
                    Text(selected?.title ?: "Waiting for the host to choose a film", style = MaterialTheme.typography.titleMedium)
                    if (selected != null && selected.key != media?.key) {
                        if (onWatch != null) {
                            Button(onClick = { onWatch(selected) }) { Text("Open film / choose source") }
                        } else {
                            Text("Open this film from Settings → Watch Party to choose your own source.")
                        }
                    }
                    if (selected != null && selected.key == media?.key) {
                        Text(if (state.canControl) "Playback controls are shared." else "Playback follows the host.")
                    }
                    Text("Use the same cut of the film. Different runtimes or edits may not line up.", style = MaterialTheme.typography.bodySmall)
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("People", style = MaterialTheme.typography.titleLarge)
                    room.members.forEach { member ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(member.name + if (member.id == room.hostId) " · Host" else "")
                                Text(
                                    if (!member.connected) "Reconnecting" else member.status.replaceFirstChar(Char::uppercase),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (state.isHost && member.id != state.memberId) {
                                TextButton(onClick = { confirm = member.id }, enabled = state.connected) { Text("Remove") }
                            }
                        }
                    }
                }
            }
            if (state.isHost) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Only the host controls playback")
                        Text("Turn off to let everyone pause, play and seek.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = room.hostOnly, onCheckedChange = WatchPartyRepository::hostOnly, enabled = state.connected)
                }
                OutlinedButton(onClick = { confirm = "end" }, enabled = state.connected) { Text("End party for everyone") }
            }
            OutlinedButton(onClick = WatchPartyRepository::leave) { Text("Leave party") }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Back to film") } },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                WatchPartyPage(media, onWatch = onWatch?.let { watch ->
                    { selected -> onDismiss(); watch(selected) }
                })
            }
        },
    )
}
