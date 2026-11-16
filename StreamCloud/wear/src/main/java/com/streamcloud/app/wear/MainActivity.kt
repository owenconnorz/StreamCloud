package com.streamcloud.app.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.items
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearMusicApp() }
    }
}

private data class WatchMusicItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val artist: String,
    val artwork: String,
    val browsable: Boolean,
    val playable: Boolean,
    val parentId: String,
    val page: Int,
    val index: Int,
)

private data class WatchFolder(
    val id: String,
    val title: String,
    val parentId: String,
)

private enum class PlaybackTarget(val label: String) {
    PHONE("Phone"),
    WATCH("Watch"),
}

@Composable
private fun WearMusicApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folders = remember { mutableStateListOf<WatchFolder>() }
    var items by remember { mutableStateOf<List<WatchMusicItem>>(emptyList()) }
    var rootId by remember { mutableStateOf("") }
    var currentParent by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("Music") }
    var target by remember { mutableStateOf(PlaybackTarget.PHONE) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("Connect StreamCloud on your phone to load music.") }
    var phonePlaying by remember { mutableStateOf(false) }
    var phoneTitle by remember { mutableStateOf("") }
    var watchPlaying by remember { mutableStateOf(false) }
    var watchTitle by remember { mutableStateOf("") }
    var currentPage by remember { mutableStateOf(0) }
    var pageSize by remember { mutableStateOf(40) }

    fun acceptItems(response: JSONObject) {
        items = parseItems(response.optJSONArray("items"), response.optString("parentId"))
        currentPage = response.optInt("page", 0)
        pageSize = response.optInt("pageSize", 40).coerceAtLeast(1)
        loading = false
        message = if (items.isEmpty()) "Nothing to show here yet." else ""
    }

    fun loadRoot() {
        scope.launch {
            loading = true
            val response = runCatching {
                WearMusicBridge.request(context, "root")
            }.getOrElse { error ->
                loading = false
                message = error.message ?: "Couldn't connect to your phone."
                return@launch
            }
            if (!response.optBoolean("ok")) {
                loading = false
                message = response.optString("message", "Couldn't load music.")
                return@launch
            }
            folders.clear()
            rootId = response.optString("rootId")
            currentParent = rootId
            title = "Music"
            acceptItems(response.put("parentId", rootId))
        }
    }

    fun openFolder(item: WatchMusicItem) {
        scope.launch {
            loading = true
            val response = runCatching {
                WearMusicBridge.request(
                    context,
                    "children",
                    JSONObject().put("parentId", item.id).put("page", 0),
                )
            }.getOrElse { error ->
                loading = false
                message = error.message ?: "Couldn't open that section."
                return@launch
            }
            if (!response.optBoolean("ok")) {
                loading = false
                message = response.optString("message", "Couldn't open that section.")
                return@launch
            }
            folders.add(WatchFolder(currentParent, title, folders.lastOrNull()?.parentId.orEmpty()))
            currentParent = item.id
            title = item.title
            acceptItems(response)
        }
    }

    fun goBack() {
        if (folders.isEmpty()) {
            loadRoot()
            return
        }
        val folder = folders.removeAt(folders.lastIndex)
        scope.launch {
            loading = true
            val response = runCatching {
                if (folder.id == rootId) WearMusicBridge.request(context, "root")
                else WearMusicBridge.request(
                    context,
                    "children",
                    JSONObject().put("parentId", folder.id).put("page", 0),
                )
            }.getOrElse { error ->
                loading = false
                message = error.message ?: "Couldn't return to that section."
                return@launch
            }
            if (!response.optBoolean("ok")) {
                loading = false
                message = response.optString("message", "Couldn't return to that section.")
                return@launch
            }
            currentParent = folder.id
            title = folder.title
            acceptItems(response.put("parentId", folder.id))
        }
    }

    fun playOnPhone(item: WatchMusicItem) {
        scope.launch {
            loading = true
            val response = runCatching {
                WearMusicBridge.request(
                    context,
                    "playPhone",
                    JSONObject()
                        .put("parentId", item.parentId)
                        .put("page", item.page)
                        .put("index", item.index),
                )
            }.getOrElse { error ->
                loading = false
                message = error.message ?: "Couldn't start phone playback."
                return@launch
            }
            loading = false
            message = response.optString("message", "Phone playback updated.")
        }
    }

    fun playOnWatch(item: WatchMusicItem) {
        scope.launch {
            loading = true
            val response = runCatching {
                WearMusicBridge.request(
                    context,
                    "resolveWatch",
                    JSONObject().put("mediaId", item.id),
                )
            }.getOrElse { error ->
                loading = false
                message = error.message ?: "Couldn't prepare watch playback."
                return@launch
            }
            if (!response.optBoolean("ok")) {
                loading = false
                message = response.optString("message", "This track needs phone playback.")
                return@launch
            }
            val compatibleItems = items.filter(::isWatchPlayableTrack)
            val selectedIndex = compatibleItems.indexOfFirst { it.id == item.id }
            if (selectedIndex < 0) {
                loading = false
                message = "Open a YouTube Music playlist and choose one of its tracks."
                return@launch
            }
            val queueStart = (selectedIndex - WATCH_QUEUE_PREVIOUS_TRACKS).coerceAtLeast(0)
            val watchQueue = compatibleItems.drop(queueStart).take(MAX_WATCH_QUEUE_ITEMS)
            runCatching {
                WearWatchPlayback.playQueue(
                    context = context,
                    tracks = watchQueue.map {
                        WearWatchTrack(
                            mediaId = it.id,
                            title = it.title,
                            artist = it.artist,
                            artwork = it.artwork,
                        )
                    },
                    startIndex = selectedIndex - queueStart,
                )
            }.onSuccess {
                loading = false
                watchPlaying = true
                watchTitle = item.title
                message = "Playing on the watch."
            }.onFailure { error ->
                loading = false
                message = error.message ?: "Couldn't start watch playback."
            }
        }
    }

    fun loadMore() {
        if (loading || currentParent.isBlank()) return
        scope.launch {
            loading = true
            val nextPage = currentPage + 1
            val response = runCatching {
                WearMusicBridge.request(
                    context,
                    "children",
                    JSONObject()
                        .put("parentId", currentParent)
                        .put("page", nextPage)
                        .put("pageSize", pageSize),
                )
            }.getOrElse { error ->
                loading = false
                message = error.message ?: "Couldn't load more tracks."
                return@launch
            }
            if (!response.optBoolean("ok")) {
                loading = false
                message = response.optString("message", "Couldn't load more tracks.")
                return@launch
            }
            val moreItems = parseItems(response.optJSONArray("items"), currentParent)
            items = items + moreItems
            currentPage = nextPage
            loading = false
            message = if (moreItems.isEmpty()) "You've reached the end." else ""
        }
    }

    fun performAction(item: WatchMusicItem) {
        if (item.browsable) openFolder(item)
        else if (item.playable) {
            if (target == PlaybackTarget.PHONE) playOnPhone(item) else playOnWatch(item)
        }
    }

    LaunchedEffect(Unit) {
        loadRoot()
        while (true) {
            val state = runCatching { WearMusicBridge.request(context, "state") }.getOrNull()
            if (state?.optBoolean("ok") == true) {
                phonePlaying = state.optBoolean("playing")
                phoneTitle = state.optString("title")
            }
            delay(5_000L)
        }
    }

    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            ScalingLazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101827))
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        "StreamCloud",
                        color = Color(0xFF1BC8E8),
                        style = MaterialTheme.typography.title3,
                    )
                }
                item {
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.title2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (folders.isNotEmpty()) {
                    item {
                        Chip(
                            onClick = ::goBack,
                            label = { Text("Back") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Chip(
                        onClick = {
                            target = if (target == PlaybackTarget.PHONE) {
                                PlaybackTarget.WATCH
                            } else {
                                PlaybackTarget.PHONE
                            }
                        },
                        label = { Text("Play on ${target.label}") },
                        secondaryLabel = { Text("Tap to switch") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (phoneTitle.isNotBlank()) {
                    item {
                        Chip(
                            onClick = {
                                scope.launch {
                                    val response = runCatching {
                                        WearMusicBridge.request(
                                            context,
                                            "transport",
                                            JSONObject().put("action", "previous"),
                                        )
                                    }.getOrNull()
                                    message = response?.optString("message")
                                        ?: "Couldn't skip to the previous track."
                                }
                            },
                            label = { Text("Previous") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Chip(
                            onClick = {
                                scope.launch {
                                    val response = runCatching {
                                        WearMusicBridge.request(
                                            context,
                                            "transport",
                                            JSONObject().put("action", if (phonePlaying) "pause" else "play"),
                                        )
                                    }.getOrNull()
                                    message = response?.optString("message")
                                        ?: "Couldn't control phone playback."
                                }
                            },
                            label = { Text(if (phonePlaying) "Pause phone" else "Resume phone") },
                            secondaryLabel = {
                                Text(phoneTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Chip(
                            onClick = {
                                scope.launch {
                                    val response = runCatching {
                                        WearMusicBridge.request(
                                            context,
                                            "transport",
                                            JSONObject().put("action", "next"),
                                        )
                                    }.getOrNull()
                                    message = response?.optString("message")
                                        ?: "Couldn't skip to the next track."
                                }
                            },
                            label = { Text("Next") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (watchTitle.isNotBlank()) {
                    item {
                        Chip(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        WearWatchPlayback.setPlaying(context, !watchPlaying)
                                    }.onSuccess {
                                        watchPlaying = !watchPlaying
                                        message = if (watchPlaying) "Playing on the watch." else "Paused on the watch."
                                    }.onFailure { error ->
                                        message = error.message ?: "Couldn't control watch playback."
                                    }
                                }
                            },
                            label = { Text(if (watchPlaying) "Pause watch" else "Resume watch") },
                            secondaryLabel = { Text(watchTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (loading) {
                    item { Text("Loading…", color = Color.LightGray) }
                }
                if (message.isNotBlank()) {
                    item {
                        Text(
                            message,
                            color = Color(0xFFB8C4D8),
                            style = MaterialTheme.typography.caption2,
                        )
                    }
                }
                items(items) { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Chip(
                            onClick = { performAction(item) },
                            label = {
                                Text(
                                    item.title.ifBlank { "Untitled" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            secondaryLabel = {
                                Text(
                                    item.subtitle.ifBlank { item.artist },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (item.browsable && item.playable) {
                            Chip(
                                onClick = {
                                    if (target == PlaybackTarget.PHONE) playOnPhone(item)
                                    else {
                                        message = "Open the playlist and choose a track for watch playback."
                                    }
                                },
                                label = { Text("Play playlist on phone") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else if (item.playable && target == PlaybackTarget.WATCH) {
                            Chip(
                                onClick = { playOnWatch(item) },
                                label = { Text("Play on watch") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (items.size >= pageSize) {
                    item {
                        Chip(
                            onClick = ::loadMore,
                            label = { Text("Load more") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (watchTitle.isNotBlank()) {
                    item {
                        Text(
                            "On watch: $watchTitle",
                            color = Color(0xFF1BC8E8),
                            style = MaterialTheme.typography.caption2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun parseItems(array: JSONArray?, parentId: String): List<WatchMusicItem> =
    buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                WatchMusicItem(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    subtitle = item.optString("subtitle"),
                    artist = item.optString("artist"),
                    artwork = item.optString("artwork"),
                    browsable = item.optBoolean("browsable"),
                    playable = item.optBoolean("playable"),
                    parentId = item.optString("parentId", parentId),
                    page = item.optInt("page"),
                    index = item.optInt("index", index),
                ),
            )
        }
    }

private fun isWatchPlayableTrack(item: WatchMusicItem): Boolean =
    item.playable &&
        !item.browsable &&
        (item.id.startsWith("https://music.youtube.com/watch?v=") ||
            item.id.startsWith("https://www.youtube.com/watch?v="))

private const val MAX_WATCH_QUEUE_ITEMS = 100
private const val WATCH_QUEUE_PREVIOUS_TRACKS = 10
