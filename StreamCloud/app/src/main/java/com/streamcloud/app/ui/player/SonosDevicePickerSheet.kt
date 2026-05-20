package com.streamcloud.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamcloud.app.data.sonos.SonosDevice
import com.streamcloud.app.data.sonos.SonosRepository

/**
 * Bottom sheet that lets the user discover Sonos devices and cast the
 * currently playing track to one of them.
 *
 * States rendered:
 *  - Discovering  → spinner + "Scanning for Sonos devices…"
 *  - DevicesFound → scrollable list of zone players; tap to connect
 *  - Connecting   → spinner + "Connecting to [name]…"
 *  - Casting      → "Now casting to [name]" + Disconnect button
 *  - Error        → red error message + Retry button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonosDevicePickerSheet(
    videoId: String,
    title: String,
    watchUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val castState by SonosRepository.castState.collectAsState()

    LaunchedEffect(Unit) {
        if (castState !is SonosRepository.CastState.Casting) {
            SonosRepository.startDiscovery(context)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1A1A1A),
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.SpeakerGroup,
                    contentDescription = null,
                    tint = Color(0xFF4FC3F7),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Cast to Sonos",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White.copy(alpha = 0.6f))
                }
            }

            Spacer(Modifier.height(16.dp))

            when (val state = castState) {
                is SonosRepository.CastState.Discovering -> {
                    SheetMessage("Scanning for Sonos devices on your network…", showSpinner = true)
                }

                is SonosRepository.CastState.DevicesFound -> {
                    Text(
                        "Select a device",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.devices, key = { it.udn }) { device ->
                            DeviceRow(device = device) {
                                SonosRepository.connect(
                                    context = context,
                                    device = device,
                                    videoId = videoId,
                                    title = title,
                                    watchUrl = watchUrl,
                                )
                            }
                        }
                    }
                }

                is SonosRepository.CastState.Connecting -> {
                    SheetMessage("Connecting…", showSpinner = true)
                }

                is SonosRepository.CastState.Casting -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Speaker,
                            contentDescription = null,
                            tint = Color(0xFF4FC3F7),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Now casting to",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            state.device.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            state.title,
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                SonosRepository.disconnect()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF992222),
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Disconnect")
                        }
                    }
                }

                is SonosRepository.CastState.Error -> {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.message,
                            color = Color(0xFFFF6B6B),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { SonosRepository.startDiscovery(context) },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Try Again", color = Color.White)
                        }
                    }
                }

                SonosRepository.CastState.Idle -> {
                    SheetMessage("Opening scanner…", showSpinner = true)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: SonosDevice, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.07f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Speaker,
                contentDescription = null,
                tint = Color(0xFF4FC3F7),
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    device.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    device.host,
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SheetMessage(text: String, showSpinner: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF4FC3F7),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(text, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
    }
}
