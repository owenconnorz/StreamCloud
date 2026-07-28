package com.streamcloud.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.listentogether.ListenTogetherRepository
import com.streamcloud.app.data.listentogether.LtCommand
import com.streamcloud.app.data.listentogether.LtConnectionState
import com.streamcloud.app.data.listentogether.LtMember
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val LtColour = Color(0xFF56C8D8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherPage() {
    val context = LocalContext.current
    val sl = remember(context) { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val connectionState by ListenTogetherRepository.state.collectAsState()
    val members by ListenTogetherRepository.members.collectAsState()

    var displayName by rememberSaveable { mutableStateOf("") }
    var joinCode by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Observe incoming commands for toasts / error messages
    LaunchedEffect(Unit) {
        ListenTogetherRepository.commands.collect { cmd ->
            when (cmd) {
                is LtCommand.MemberJoin ->
                    Toast.makeText(context, "🎵 ${cmd.name} joined the room", Toast.LENGTH_SHORT).show()
                is LtCommand.MemberLeave ->
                    Toast.makeText(context, "${cmd.name} left the room", Toast.LENGTH_SHORT).show()
                is LtCommand.PromotedToHost ->
                    Toast.makeText(context, "You are now the host!", Toast.LENGTH_LONG).show()
                is LtCommand.TrackChange ->
                    Toast.makeText(context, "Host changed track: ${cmd.trackTitle}", Toast.LENGTH_SHORT).show()
                is LtCommand.ServerError -> errorMessage = cmd.message
                else -> {}
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        // ── Error banner ──────────────────────────────────────────────────────
        val currentError = (connectionState as? LtConnectionState.Error)?.message ?: errorMessage
        if (currentError != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        currentError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            errorMessage = null
                            if (connectionState is LtConnectionState.Error) {
                                ListenTogetherRepository.disconnect()
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        when (val state = connectionState) {

            // ── Idle / Error: create or join UI ───────────────────────────────
            is LtConnectionState.Idle, is LtConnectionState.Error -> {

                // Info card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = LtColour.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Groups, null,
                            tint = LtColour,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Real-time sync", fontWeight = FontWeight.Bold, color = LtColour)
                            Text(
                                "Create a room and share the 6-character code. " +
                                    "Guests hear every play, pause, and seek you make.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Display name field
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Your display name") },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    shape = RoundedCornerShape(14.dp),
                )

                // Create room button
                Button(
                    onClick = {
                        errorMessage = null
                        if (displayName.isBlank()) {
                            errorMessage = "Enter your display name first"
                            return@Button
                        }
                        scope.launch {
                            val backendUrl = sl.settings.backendUrl.first()
                            ListenTogetherRepository.createRoom(backendUrl)
                                .onSuccess { code ->
                                    ListenTogetherRepository.connect(backendUrl, code, displayName, context)
                                }
                                .onFailure { e ->
                                    errorMessage = "Could not create room: ${e.message}"
                                }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LtColour),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create a Room", fontWeight = FontWeight.SemiBold)
                }

                // Divider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        "  or join  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }

                // Join row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it.uppercase().take(6) },
                        label = { Text("Room code") },
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("ABC123") },
                    )
                    Button(
                        onClick = {
                            errorMessage = null
                            when {
                                displayName.isBlank() -> errorMessage = "Enter your display name first"
                                joinCode.length != 6 -> errorMessage = "Room codes are 6 characters"
                                else -> scope.launch {
                                    val backendUrl = sl.settings.backendUrl.first()
                                    ListenTogetherRepository.connect(
                                        backendUrl, joinCode.uppercase(), displayName, context,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LtColour),
                    ) {
                        Text("Join", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ── Connecting ────────────────────────────────────────────────────
            is LtConnectionState.Connecting -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(color = LtColour)
                        Text(
                            "Connecting…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { ListenTogetherRepository.disconnect() }) {
                            Text("Cancel")
                        }
                    }
                }
            }

            // ── Connected: session view ───────────────────────────────────────
            is LtConnectionState.Connected -> {

                // Room code card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = LtColour.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50)),
                            )
                            Text(
                                if (state.isHost) "You are the Host" else "Listening as Guest",
                                fontWeight = FontWeight.SemiBold,
                                color = LtColour,
                            )
                        }
                        Text(
                            state.roomCode,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 8.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (state.isHost) "Share this code with friends"
                            else "Host controls the playback for everyone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(state.roomCode))
                            Toast.makeText(context, "Room code copied!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                Icons.Default.ContentCopy, null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Copy Code")
                        }
                    }
                }

                // Members list
                if (members.isNotEmpty()) {
                    Text(
                        "Members (${members.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            members.forEachIndexed { idx, member ->
                                LtMemberRow(member = member)
                                if (idx < members.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 60.dp),
                                        thickness = 0.5.dp,
                                    )
                                }
                            }
                        }
                    }
                }

                // Guest hint
                if (!state.isHost) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Info, null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "The host controls play, pause, and seek. " +
                                    "Your playback mirrors theirs automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }

                    // Re-sync button (guest only)
                    OutlinedButton(
                        onClick = { ListenTogetherRepository.requestSync() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Re-sync with Host")
                    }
                }

                // Leave button
                OutlinedButton(
                    onClick = { ListenTogetherRepository.disconnect() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.ExitToApp, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Leave Room", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LtMemberRow(member: LtMember) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (member.isHost) LtColour.copy(alpha = 0.25f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                member.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontWeight = FontWeight.Bold,
                color = if (member.isHost) LtColour else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            member.name,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (member.isHost) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = LtColour.copy(alpha = 0.15f),
            ) {
                Text(
                    "HOST",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = LtColour,
                )
            }
        }
    }
}
