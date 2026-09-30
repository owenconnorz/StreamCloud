package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.buildStreamProviderPriorityEntries
import com.streamcloud.app.data.orderStreamProviderEntries
import com.streamcloud.app.data.StreamProviderPriorityEntry
import com.streamcloud.app.ui.theme.tvFocusBorder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun ProviderPriorityContent() {
    val context = LocalContext.current
    val serviceLocator = remember { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val addons by serviceLocator.stremio.addons.collectAsState(initial = emptyList())
    val nuvioProviders by serviceLocator.nuvio.installed.collectAsState(initial = emptyList())
    val plugins by serviceLocator.plugins.installed.collectAsState(initial = emptyList())
    val savedOrder by produceState<List<String>?>(
        initialValue = null,
        serviceLocator.settings.streamProviderPriorityOrder,
    ) {
        serviceLocator.settings.streamProviderPriorityOrder.collect { value = it }
    }
    val autoPlay by serviceLocator.settings.autoplayBestStream.collectAsState(initial = false)

    val entries = remember(addons, nuvioProviders, plugins) {
        buildStreamProviderPriorityEntries(addons, nuvioProviders, plugins)
    }
    val storedEntries = remember(entries, savedOrder) {
        orderStreamProviderEntries(entries, savedOrder.orEmpty())
    }
    var draftOrder by remember(entries, savedOrder) {
        mutableStateOf(storedEntries.map { it.key })
    }
    val orderedEntries = remember(entries, draftOrder) {
        orderStreamProviderEntries(entries, draftOrder)
    }

    fun moveProvider(index: Int, offset: Int) {
        if (savedOrder == null) return
        val targetIndex = index + offset
        if (targetIndex !in orderedEntries.indices) return
        val nextOrder = orderedEntries.map { it.key }.toMutableList()
        val moved = nextOrder.removeAt(index)
        nextOrder.add(targetIndex, moved)
        draftOrder = nextOrder
        scope.launch {
            serviceLocator.settings.setStreamProviderPriorityOrder(nextOrder)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Move your preferred providers to the top. StreamCloud tries higher entries first and keeps the remaining results as playback fallbacks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Auto-play in this order",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(3.dp))
                    Text(
                        "Skip the source picker and start with the first provider that returns a stream.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoPlay,
                    onCheckedChange = { enabled ->
                        scope.launch { serviceLocator.settings.setAutoplayBestStream(enabled) }
                    },
                )
            }
        }

        Text(
            "PRIORITY ORDER",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )

        if (orderedEntries.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    "No stream providers are installed yet. Add a Stremio add-on, Nuvio provider, or CloudStream plugin first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            orderedEntries.forEachIndexed { index, entry ->
                StreamProviderPriorityRow(
                    entry = entry,
                    position = index + 1,
                    canMoveUp = savedOrder != null && index > 0,
                    canMoveDown = savedOrder != null && index < orderedEntries.lastIndex,
                    onMoveUp = { moveProvider(index, -1) },
                    onMoveDown = { moveProvider(index, 1) },
                )
            }
        }
    }
}

@Composable
private fun StreamProviderPriorityRow(
    entry: StreamProviderPriorityEntry,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    position.toString(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    entry.kindLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.tvFocusBorder(CircleShape),
            ) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = "Move ${entry.name} up",
                    tint = if (canMoveUp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.tvFocusBorder(CircleShape),
            ) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = "Move ${entry.name} down",
                    tint = if (canMoveDown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}