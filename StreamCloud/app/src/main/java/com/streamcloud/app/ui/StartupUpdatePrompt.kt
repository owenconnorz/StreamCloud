package com.streamcloud.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamcloud.app.BuildConfig
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.updater.UpdateChecker
import com.streamcloud.app.data.updater.UpdateInfo
import com.streamcloud.app.ui.theme.tvFocusBorder
import kotlinx.coroutines.launch

/**
 * Checks for a newer release after the first app surface is available and
 * presents it once per release, matching the prominent update prompt used by
 * Nuvio. A failed network check is deliberately not persisted so it can retry.
 */
@Composable
fun StartupUpdatePrompt(enabled: Boolean) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context.applicationContext) }
    val checker = remember { UpdateChecker(context.applicationContext) }
    val dismissedTag by sl.settings.startupUpdatePromptDismissedTag.collectAsState(initial = "")
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(enabled) {
        if (!enabled || checking || update != null) return@LaunchedEffect
        checking = true
        errorMessage = null
        try {
            val info = checker.fetchLatest(includeOlder = false)
            if (info?.isNewerThanInstalled == true && info.tagName != dismissedTag) {
                update = info
            }
        } catch (_: Exception) {
            // Startup updates must never block the app when GitHub is unavailable.
        } finally {
            checking = false
        }
    }

    val info = update ?: return
    val installFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { installFocusRequester.requestFocus() }
    }

    fun dismissForThisRelease() {
        scope.launch { sl.settings.setStartupUpdatePromptDismissedTag(info.tagName) }
        update = null
    }

    Dialog(
        onDismissRequest = { if (!installing) dismissForThisRelease() },
        properties = DialogProperties(
            dismissOnBackPress = !installing,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp,
        ) {
            Column(Modifier.padding(26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = RoundedCornerShape(17.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Update available",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            info.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "StreamCloud ${info.tagName} is ready to install.",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "You are currently on v${BuildConfig.VERSION_NAME}. Download the latest version now?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "${formatBytes(info.sizeBytes)}  •  ${info.tagName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "What’s new",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        info.notes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                RoundedCornerShape(12.dp),
                            )
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (installing) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Downloading update… ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(7.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                errorMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        enabled = !installing,
                        onClick = ::dismissForThisRelease,
                        modifier = Modifier.tvFocusBorder(RoundedCornerShape(12.dp)),
                    ) {
                        Text("Later")
                    }
                    Button(
                        enabled = !installing,
                        onClick = {
                            installing = true
                            progress = 0f
                            errorMessage = null
                            scope.launch {
                                try {
                                    val apk = checker.downloadApk(info) { progress = it }
                                    checker.launchInstaller(apk)
                                    sl.settings.setStartupUpdatePromptDismissedTag(info.tagName)
                                    update = null
                                } catch (error: Exception) {
                                    errorMessage = "Download failed: ${error.message ?: "Unknown error"}"
                                } finally {
                                    installing = false
                                }
                            }
                        },
                        modifier = Modifier
                            .focusRequester(installFocusRequester)
                            .tvFocusBorder(RoundedCornerShape(12.dp)),
                    ) {
                        if (installing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Update")
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "APK"
    val mb = bytes / (1024f * 1024f)
    return if (mb >= 1f) "${"%.1f".format(mb)} MB" else "${bytes / 1024} KB"
}