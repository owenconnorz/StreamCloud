package com.streamcloud.app.ads

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.advertisingDataStore by preferencesDataStore("streamcloud_advertising")

internal object AdPreferences {
    private val PREVIEW = booleanPreferencesKey("offline_preview_enabled")

    @Composable
    fun previewEnabled(context: Context): State<Boolean> {
        val appContext = context.applicationContext
        val flow = remember(appContext) {
            appContext.advertisingDataStore.data.map { it[PREVIEW] ?: false }
        }
        return flow.collectAsState(initial = false)
    }

    suspend fun setPreviewEnabled(context: Context, enabled: Boolean) {
        context.advertisingDataStore.edit { it[PREVIEW] = enabled }
    }
}

@Composable
fun AdvertisingSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = AdPreferences.previewEnabled(context)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Advertising", style = MaterialTheme.typography.titleMedium)
        Text(
            "Live ads are off. The publisher must confirm the banner size and connect " +
                "an advertising consent service before ads can be requested.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "This switch shows a preview on Movies and Adult browsing pages. " +
                "It does not give consent or enable tracking.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Offline 300 × 100 banner preview")
                Text(
                    "No ads are loaded and no revenue is generated",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = preview.value,
                onCheckedChange = {
                    scope.launch { AdPreferences.setPreviewEnabled(context, it) }
                },
            )
        }
        TextButton(onClick = { openExternalHttps(context, ExoClickTag.PRIVACY_URL) }) {
            Text("ExoClick privacy policy")
        }
    }
}