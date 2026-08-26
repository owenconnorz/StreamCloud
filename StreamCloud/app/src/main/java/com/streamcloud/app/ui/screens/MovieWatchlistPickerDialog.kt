package com.streamcloud.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.MovieWatchlistEntity
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.data.library.reconcileMovieWatchlists
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup
import kotlinx.coroutines.launch

private const val DEFAULT_MOVIE_WATCHLIST_ID = 0L

@Composable
fun MovieWatchlistPickerDialog(
    entry: WatchlistEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember(context) { LibraryDb.get(context.applicationContext) }
    val customLists by db.movieWatchlists().all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selectedIds by remember(entry.tmdbId) { mutableStateOf<Set<Long>>(emptySet()) }
    var loaded by remember(entry.tmdbId) { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }

    LaunchedEffect(entry.tmdbId) {
        val selected = db.movieWatchlists().watchlistIdsForMovie(entry.tmdbId).toMutableSet()
        if (db.watchlist().byId(entry.tmdbId) != null) selected += DEFAULT_MOVIE_WATCHLIST_ID
        selectedIds = selected
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose watchlists") },
        text = {
            Column(
                modifier = Modifier.tvFocusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                if (!loaded) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                } else {
                    WatchlistChoiceRow(
                        name = "Watchlist",
                        checked = DEFAULT_MOVIE_WATCHLIST_ID in selectedIds,
                        onToggle = {
                            selectedIds = selectedIds.toggle(DEFAULT_MOVIE_WATCHLIST_ID)
                        },
                    )
                    customLists.forEach { list ->
                        WatchlistChoiceRow(
                            name = list.name,
                            checked = list.id in selectedIds,
                            onToggle = { selectedIds = selectedIds.toggle(list.id) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        label = { Text("New watchlist") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = newListName.isNotBlank(),
                        modifier = Modifier.tvFocusBorder(RoundedCornerShape(50)),
                        onClick = {
                            val name = newListName.trim()
                            if (name.isBlank()) return@Button
                            scope.launch {
                                val id = db.movieWatchlists().create(MovieWatchlistEntity(name = name))
                                selectedIds = selectedIds + id
                                newListName = ""
                            }
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create watchlist")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = loaded,
                onClick = {
                    scope.launch {
                        db.reconcileMovieWatchlists(entry, selectedIds)
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun WatchlistChoiceRow(
    name: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusBorder(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id