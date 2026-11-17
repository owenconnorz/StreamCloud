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
import androidx.compose.runtime.mutableFloatStateOf
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
import java.io.File

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

internal enum class PlaybackTarget(val label: String) {
    PHONE("Phone"),
    WATCH("Watch"),
}

@Composable
private fun WearMusicApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateChecker = remember { WearUpdateChecker(context.applicationContext) }
    val folders = remember { mutableStateListOf<WatchFolder>() }
    var items by remember { mutableStateOf<List<WatchMusicItem>>(emptyList()) }
    var rootId by remember { mutableStateOf("") }
    var currentParent by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("Music") }
    var target by remember { mutableStateOf(PlaybackTarget.PHONE) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("Connect StreamCloud on your phone to load music.") }
    var phoneSnapshot by remember { mutableStateOf(emptyWearSnapshot()) }
    var watchSnapshot by remember { mutableStateOf(emptyWearSnapshot()) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<WearUpdateInfo?>(null) }
    var downloadedUpdate by remember { mutableStateOf<File?>(null) }
    var updateMessage by remember { mutableStateOf("") }
    var updateProgress by remember { mutableFloatStateOf(0f) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(0) }
    var pageSize by remember { mutableStateOf(40) }

    val activePlaybackTarget = when {
        watchSnapshot.playing && !phoneSnapshot.playing -> PlaybackTarget.WATCH
        phoneSnapshot.playing && !watchSnapshot.playing -> PlaybackTarget.PHONE
        target == PlaybackTarget.WATCH && watchSnapshot.hasItem -> PlaybackTarget.WATCH
        target == PlaybackTarget.PHONE && phoneSnapshot.hasItem -> PlaybackTarget.PHONE
        watchSnapshot.hasItem -> PlaybackTarget.WATCH
        phoneSnapshot.hasItem -> PlaybackTarget.PHONE
        else -> target
    }
    val activeSnapshot = if (activePlaybackTarget == PlaybackTarget.WATCH) {
        watchSnapshot
    } else {
        phoneSnapshot
    }
    val updateChipTitle = when {
        downloadingUpdate -> "Downloading update · ${(updateProgress * 100).toInt()}%"
        downloadedUpdate != null -> "Install downloaded update"
        else -> "StreamCloud update available"
    }
    val updateChipDetails = updateMessage.ifBlank {
        availableUpdate?.let { "${it.tagName} · ${formatUpdateSize(it.sizeBytes)}" }.orEmpty()
    }

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
            if (response.optBoolean("ok")) showNowPlaying = true
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
                watchSnapshot = WearPlaybackSnapshot(
                    hasItem = true,
                    playing = true,
                    title = item.title,
                    artist = item.artist,
                    artwork = item.artwork,
                    positionMs = 0L,
                    durationMs = 0L,
                )
                message = "Playing on the watch."
                showNowPlaying = true
            }.onFailure { error ->
                loading = false
                message = error.message ?: "Couldn't start watch playback."
            }
        }
    }

    fun selectOutput(destination: PlaybackTarget) {
        if (loading) return
        if (destination == activePlaybackTarget) {
            target = destination
            return
        }

        scope.launch {
            loading = true
            var destinationStarted = false
            var sourcePaused = false
            var sourceWasPlaying = false
            try {
                if (destination == PlaybackTarget.WATCH) {
                    val current = phoneSnapshot
                    if (!current.hasItem) {
                        target = destination
                        message = "Watch selected for the next track."
                        return@launch
                    }
                    sourceWasPlaying = current.playing
                    val pausedPhone = WearMusicBridge.request(
                        context,
                        "transport",
                        JSONObject().put("action", "pause"),
                    )
                    if (!pausedPhone.optBoolean("ok")) {
                        error(pausedPhone.optString("message", "Couldn't pause phone playback."))
                    }
                    sourcePaused = true
                    val refreshedPhone = WearMusicBridge.request(context, "state")
                    if (!refreshedPhone.optBoolean("ok")) {
                        error(refreshedPhone.optString("message", "Couldn't read the current phone track."))
                    }
                    val transfer = parsePhoneSnapshot(refreshedPhone)
                    require(transfer.hasItem) { "Phone playback ended before it could be transferred." }
                    val phoneQueue = transfer.queue.ifEmpty {
                        listOf(
                            WearWatchTrack(
                                mediaId = transfer.mediaId,
                                title = transfer.title,
                                artist = transfer.artist,
                                artwork = transfer.artwork,
                            ),
                        )
                    }
                    val watchQueue = phoneQueue.filter { isWatchPlayableTrackId(it.mediaId) }
                    val watchIndex = watchQueue.indexOfFirst { it.mediaId == transfer.mediaId }
                    require(watchIndex >= 0) {
                        "This track needs phone playback because it isn't supported on the watch."
                    }
                    WearWatchPlayback.playQueue(
                        context = context,
                        tracks = watchQueue,
                        startIndex = watchIndex,
                        startPositionMs = transfer.positionMs,
                        startPlaying = sourceWasPlaying,
                    )
                    destinationStarted = true
                    phoneSnapshot = transfer.copy(playing = false)
                    watchSnapshot = WearWatchPlayback.snapshot(context)
                    target = destination
                    sourcePaused = false
                    message = "Playback transferred to the watch."
                } else {
                    val current = watchSnapshot
                    if (!current.hasItem) {
                        target = destination
                        message = "Phone selected for the next track."
                        return@launch
                    }
                    sourceWasPlaying = current.playing
                    WearWatchPlayback.transport(context, "pause")
                    sourcePaused = true
                    val transfer = WearWatchPlayback.snapshot(context)
                    require(transfer.hasItem) { "Watch playback ended before it could be transferred." }
                    val watchQueue = transfer.queue.ifEmpty {
                        listOf(
                            WearWatchTrack(
                                mediaId = transfer.mediaId,
                                title = transfer.title,
                                artist = transfer.artist,
                                artwork = transfer.artwork,
                            ),
                        )
                    }
                    require(watchQueue.any { it.mediaId == transfer.mediaId }) {
                        "The current watch track can't be transferred to your phone."
                    }
                    val serializedQueue = JSONArray().apply {
                        watchQueue.forEach { track ->
                            put(
                                JSONObject()
                                    .put("mediaId", track.mediaId)
                                    .put("title", track.title)
                                    .put("artist", track.artist)
                                    .put("artwork", track.artwork),
                            )
                        }
                    }
                    val startedPhone = WearMusicBridge.request(
                        context,
                        "playWatchQueue",
                        JSONObject()
                            .put("queue", serializedQueue)
                            .put("mediaId", transfer.mediaId)
                            .put("positionMs", transfer.positionMs)
                            .put("playing", sourceWasPlaying),
                    )
                    if (!startedPhone.optBoolean("ok")) {
                        error(startedPhone.optString("message", "Couldn't start phone playback."))
                    }
                    destinationStarted = true
                    watchSnapshot = transfer.copy(playing = false)
                    phoneSnapshot = WearPlaybackSnapshot(
                        hasItem = true,
                        playing = sourceWasPlaying,
                        title = transfer.title,
                        artist = transfer.artist,
                        artwork = transfer.artwork,
                        positionMs = transfer.positionMs,
                        durationMs = transfer.durationMs,
                        mediaId = transfer.mediaId,
                        queue = watchQueue,
                        currentIndex = transfer.currentIndex,
                    )
                    target = destination
                    sourcePaused = false
                    message = "Playback transferred to your phone."
                }
            } catch (error: Exception) {
                if (destinationStarted) {
                    runCatching {
                        if (destination == PlaybackTarget.WATCH) {
                            WearWatchPlayback.transport(context, "pause")
                        } else {
                            WearMusicBridge.request(
                                context,
                                "transport",
                                JSONObject().put("action", "pause"),
                            )
                        }
                    }
                }
                if (sourcePaused && sourceWasPlaying) {
                    runCatching {
                        if (destination == PlaybackTarget.WATCH) {
                            WearMusicBridge.request(
                                context,
                                "transport",
                                JSONObject().put("action", "play"),
                            )
                        } else {
                            WearWatchPlayback.transport(context, "play")
                        }
                    }
                }
                message = error.message ?: "Couldn't switch playback output."
            } finally {
                loading = false
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

    fun installLatestUpdate() {
        val update = availableUpdate ?: return
        if (downloadingUpdate) return
        scope.launch {
            downloadingUpdate = true
            updateProgress = 0f
            updateMessage = ""
            try {
                val apk = downloadedUpdate?.takeIf(File::exists)
                    ?: run {
                        var lastPercent = -1
                        updateChecker.download(update) { progress ->
                            val percent = (progress * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                updateProgress = progress
                            }
                        }.also { downloadedUpdate = it }
                    }

                if (!updateChecker.canInstallPackages()) {
                    updateMessage = "Allow installs for StreamCloud, then tap here again."
                    updateChecker.openInstallPermissionSettings()
                } else {
                    updateChecker.launchInstaller(apk)
                    updateMessage = "Confirm installation in the Android installer."
                }
            } catch (error: Exception) {
                updateMessage = "Update failed: ${error.message ?: "try again"}"
            } finally {
                downloadingUpdate = false
            }
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
        scope.launch {
            availableUpdate = runCatching { updateChecker.fetchLatest() }.getOrNull()
        }
        launch {
            var firstWatchStateRead = true
            while (true) {
                val watchState = runCatching { WearWatchPlayback.snapshot(context) }.getOrNull()
                if (watchState != null) {
                    watchSnapshot = watchState
                    if (firstWatchStateRead && watchState.hasItem) showNowPlaying = true
                    firstWatchStateRead = false
                }
                delay(1_000L)
            }
        }
        var firstPhoneStateRead = true
        while (true) {
            val state = runCatching { WearMusicBridge.request(context, "state") }.getOrNull()
            if (state?.optBoolean("ok") == true) {
                val parsedState = parsePhoneSnapshot(state)
                phoneSnapshot = parsedState
                if (firstPhoneStateRead && parsedState.hasItem) showNowPlaying = true
                firstPhoneStateRead = false
            }
            delay(2_000L)
        }
    }

    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            if (showNowPlaying) {
                WearNowPlayingScreen(
                    snapshot = activeSnapshot,
                    selectedOutput = target,
                    statusMessage = message,
                    updateTitle = if (availableUpdate != null) updateChipTitle else null,
                    updateDetails = updateChipDetails,
                    updateEnabled = !downloadingUpdate,
                    onSelectOutput = ::selectOutput,
                    onUpdate = ::installLatestUpdate,
                    onBrowse = { showNowPlaying = false },
                    onTransport = { action, positionMs ->
                        scope.launch {
                            runCatching {
                                if (activePlaybackTarget == PlaybackTarget.WATCH) {
                                    WearWatchPlayback.transport(context, action, positionMs)
                                } else {
                                    WearMusicBridge.request(
                                        context,
                                        "transport",
                                        JSONObject()
                                            .put("action", action)
                                            .put("positionMs", positionMs),
                                    ).also { response ->
                                        if (!response.optBoolean("ok")) {
                                            error(response.optString("message", "Playback control failed."))
                                        }
                                    }
                                }
                            }.onSuccess {
                                message = if (action == "seek") "" else "Playback updated."
                            }.onFailure { error ->
                                message = error.message ?: "Couldn't control playback."
                            }
                        }
                    },
                )
            } else {
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
                if (availableUpdate != null) {
                    item {
                        WearUpdateChip(
                            title = updateChipTitle,
                            details = updateChipDetails,
                            enabled = !downloadingUpdate,
                            onClick = ::installLatestUpdate,
                        )
                    }
                }
                if (phoneSnapshot.hasItem || watchSnapshot.hasItem) {
                    item {
                        Chip(
                            onClick = { showNowPlaying = true },
                            label = { Text("Now Playing") },
                            secondaryLabel = {
                                Text(
                                    activeSnapshot.title.ifBlank { "Open player" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
            }
            }
        }
    }
}

private fun emptyWearSnapshot() = WearPlaybackSnapshot(
    hasItem = false,
    playing = false,
    title = "",
    artist = "",
    artwork = "",
    positionMs = 0L,
    durationMs = 0L,
)

private fun parsePhoneSnapshot(state: JSONObject) = WearPlaybackSnapshot(
    hasItem = state.optBoolean("hasItem"),
    playing = state.optBoolean("playing"),
    title = state.optString("title"),
    artist = state.optString("artist"),
    artwork = state.optString("artwork"),
    positionMs = state.optLong("positionMs").coerceAtLeast(0L),
    durationMs = state.optLong("durationMs").coerceAtLeast(0L),
    mediaId = state.optString("mediaId"),
    queue = parsePlaybackQueue(state.optJSONArray("queue")),
    currentIndex = state.optInt("currentIndex").coerceAtLeast(0),
)

private fun formatUpdateSize(bytes: Long): String =
    if (bytes >= 1024L * 1024L) {
        "${"%.1f".format(bytes / (1024f * 1024f))} MB"
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
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

private fun parsePlaybackQueue(array: JSONArray?): List<WearWatchTrack> =
    buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val mediaId = item.optString("mediaId")
            if (mediaId.isBlank()) continue
            add(
                WearWatchTrack(
                    mediaId = mediaId,
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    artwork = item.optString("artwork"),
                ),
            )
        }
    }

private fun isWatchPlayableTrack(item: WatchMusicItem): Boolean =
    item.playable &&
        !item.browsable &&
        isWatchPlayableTrackId(item.id)

private fun isWatchPlayableTrackId(mediaId: String): Boolean =
    mediaId.startsWith("https://music.youtube.com/watch?v=") ||
        mediaId.startsWith("https://www.youtube.com/watch?v=")

private const val MAX_WATCH_QUEUE_ITEMS = 100
private const val WATCH_QUEUE_PREVIOUS_TRACKS = 10
