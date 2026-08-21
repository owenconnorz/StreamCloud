package com.streamcloud.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.streamcloud.app.audio.DjVoicePreset
import com.streamcloud.app.ui.viewmodel.DjSession
import com.streamcloud.app.ui.viewmodel.DjUiState

private val DJ_PROMPTS = listOf("Late-night chill", "Feel-good pop", "Focus mode", "Energy boost")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjSheet(
    request: String,
    onRequestChange: (String) -> Unit,
    voicePreset: DjVoicePreset,
    onVoicePresetChange: (DjVoicePreset) -> Unit,
    narrationEnabled: Boolean,
    onNarrationEnabledChange: (Boolean) -> Unit,
    state: DjUiState,
    startingMix: Boolean,
    onBuildMix: () -> Unit,
    onPlayMix: (DjSession) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("StreamCloud DJ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Build an original music session from a mood, genre, artist, or activity.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = request,
                onValueChange = onRequestChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What should we play?") },
                placeholder = { Text("e.g. upbeat 2000s pop for a drive") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(18.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DJ_PROMPTS.forEach { prompt ->
                    AssistChip(
                        onClick = { onRequestChange(prompt) },
                        label = { Text(prompt) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }

            Text("Voice style", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DjVoicePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = voicePreset == preset,
                        onClick = { onVoicePresetChange(preset) },
                        label = { Text(preset.label) },
                    )
                }
            }
            Text(
                voicePreset.description + ". These are original StreamCloud voice styles.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Spoken introductions", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Play a short DJ intro before your mix starts.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = narrationEnabled, onCheckedChange = onNarrationEnabledChange)
            }

            Button(
                onClick = onBuildMix,
                enabled = request.trim().length >= 2 && !state.loading && !startingMix,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (state.loading) "Building your mix…" else "Build my mix")
            }

            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.session?.let { session ->
                HorizontalDivider()
                Text("Your ${session.request} mix", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(session.narration, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    session.tracks.take(3).joinToString(" • ") { "${it.title} — ${it.uploader}" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onPlayMix(session) },
                    enabled = !startingMix,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (startingMix) "Starting DJ mix…" else "Start DJ mix")
                }
            }
        }
    }
}