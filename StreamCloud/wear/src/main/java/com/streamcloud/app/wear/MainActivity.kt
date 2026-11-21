package com.streamcloud.app.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.items
import coil.compose.AsyncImage
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
    var phoneConnected by remember { mutableStateOf(false) }
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
    var musicRequestGeneration by remember { mutableIntStateOf(0) }
    var librarySection by remember { mutableStateOf(WearLibrarySection.MUSIC) }
    var videoItems by remember { mutableStateOf<List<WearVideoItem>>(emptyList()) }
    var similarMovies by remember { mutableStateOf<List<WearVideoItem>>(emptyList()) }
    var videoPage by remember { mutableIntStateOf(1) }
    var videoHasMore by remember { mutableStateOf(false) }
    var videoLoading by remember { mutableStateOf(false) }
    var adultAfter by remember { mutableStateOf("") }
    var videoRequestGeneration by remember { mutableIntStateOf(0) }
    var selectedMovie by remember { mutableStateOf<WearVideoItem?>(null) }
    var videoStreams by remember { mutableStateOf<List<WearVideoStream>>(emptyList()) }
    var videoQuery by remember { mutableStateOf("") }
    var adultPin by remember { mutableStateOf("") }
    var videoTitle by remember { mutableStateOf("") }
    var videoUrl by remember { mutableStateOf("") }
    var videoHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var videoMimeType by remember { mutableStateOf("") }
    var showVideoPlayer by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(WearDestination.HOME) }
    var selectedMovieGenre by remember { mutableStateOf<WearMovieGenre?>(null) }
    var selectedAdultCategory by remember { mutableStateOf<String?>(null) }
    var catalogReturnDestination by remember { mutableStateOf(WearDestination.MOVIES) }

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
        val requestGeneration = ++musicRequestGeneration
        scope.launch {
            loading = true
            val response = runCatching {
                WearMusicBridge.request(context, "root")
            }.getOrElse { error ->
                loading = false
                message = error.message ?: "Couldn't connect to your phone."
                return@launch
            }
            if (requestGeneration != musicRequestGeneration) return@launch
            if (!response.optBoolean("ok")) {
                loading = false
                message = response.optString("message", "Couldn't load music.")
                return@launch
            }
            folders.clear()
            rootId = response.optString("rootId")
            librarySection = WearLibrarySection.MUSIC
            val home = runCatching {
                WearMusicBridge.request(
                    context,
                    "children",
                    JSONObject()
                        .put("parentId", WATCH_MUSIC_HOME_ID)
                        .put("page", 0)
                        .put("pageSize", pageSize),
                )
            }.getOrElse { error ->
                loading = false
                message = error.message ?: "Couldn't load Music."
                return@launch
            }
            if (requestGeneration != musicRequestGeneration) return@launch
            if (!home.optBoolean("ok")) {
                loading = false
                message = home.optString("message", "Couldn't load Music.")
                return@launch
            }
            currentParent = WATCH_MUSIC_HOME_ID
            title = "Music"
            acceptItems(home.put("parentId", WATCH_MUSIC_HOME_ID))
        }
    }

    fun loadMusicSection(section: WearLibrarySection) {
        if (section != WearLibrarySection.MUSIC && section != WearLibrarySection.LIBRARY) return
        librarySection = section
        destination = WearDestination.MUSIC
        val requestGeneration = ++musicRequestGeneration
        videoRequestGeneration++
        videoItems = emptyList()
        videoHasMore = false
        videoLoading = false
        selectedMovie = null
        videoStreams = emptyList()
        videoQuery = ""
        adultPin = ""
        folders.clear()
        val parentId = if (section == WearLibrarySection.LIBRARY) {
            WATCH_LIBRARY_ID
        } else {
            WATCH_MUSIC_HOME_ID
        }
        val sectionTitle = if (section == WearLibrarySection.LIBRARY) "Your Library" else "Music"
        items = emptyList()
        currentParent = parentId
        title = sectionTitle
        scope.launch {
            loading = true
            message = ""
            val response = runCatching {
                WearMusicBridge.request(
                    context,
                    "children",
                    JSONObject()
                        .put("parentId", parentId)
                        .put("page", 0)
                        .put("pageSize", pageSize),
                )
            }.getOrElse { error ->
                if (requestGeneration != musicRequestGeneration) return@launch
                loading = false
                message = error.message ?: "Couldn't load $sectionTitle."
                return@launch
            }
            if (requestGeneration != musicRequestGeneration) return@launch
            if (!response.optBoolean("ok")) {
                loading = false
                message = response.optString("message", "Couldn't load $sectionTitle.")
                return@launch
            }
            currentParent = parentId
            title = sectionTitle
            acceptItems(response.put("parentId", parentId))
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
            loadMusicSection(librarySection)
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

    fun loadVideoLibrary(
        section: WearLibrarySection,
        query: String = videoQuery,
        page: Int = 1,
        append: Boolean = false,
    ) {
        if (section != WearLibrarySection.MOVIES && section != WearLibrarySection.ADULT) return
        if (section == WearLibrarySection.MOVIES) selectedMovieGenre = null
        destination = if (section == WearLibrarySection.MOVIES) WearDestination.MOVIES else WearDestination.ADULT
        musicRequestGeneration++
        val requestGeneration = ++videoRequestGeneration
        scope.launch {
            videoLoading = true
            if (!append) {
                message = ""
                selectedMovie = null
                videoStreams = emptyList()
                if (section == WearLibrarySection.ADULT) adultAfter = ""
            }
            val response = runCatching {
                val operation = if (section == WearLibrarySection.MOVIES) "movies" else "adultPage"
                val request = JSONObject()
                    .put("query", query)
                    .put("page", page)
                if (section == WearLibrarySection.ADULT) {
                    request.put("pin", adultPin)
                    if (append && adultAfter.isNotBlank()) request.put("after", adultAfter)
                }
                WearMusicBridge.request(
                    context = context,
                    operation = operation,
                    values = request,
                    timeoutMs = VIDEO_REQUEST_TIMEOUT_MS,
                )
            }.getOrElse { error ->
                if (requestGeneration != videoRequestGeneration) return@launch
                videoLoading = false
                videoHasMore = false
                message = error.message ?: "Couldn't load videos."
                return@launch
            }
            if (requestGeneration != videoRequestGeneration) return@launch
            videoLoading = false
            if (!response.optBoolean("ok")) {
                videoHasMore = false
                if (!append) videoItems = emptyList()
                message = response.optString("message", "Couldn't load videos.")
                return@launch
            }
            val receivedItems = parseVideoItems(response.optJSONArray("items"), section)
            videoItems = if (append) {
                (videoItems + receivedItems).distinctBy(WearVideoItem::id)
            } else {
                receivedItems
            }
            videoPage = page
            videoHasMore = response.optBoolean("hasMore") && receivedItems.isNotEmpty()
            if (section == WearLibrarySection.ADULT) {
                adultAfter = response.optString("nextCursor")
            }
            message = response.optString("message")
            if (section == WearLibrarySection.ADULT) {
                val provider = response.optString("source")
                if (provider.isNotBlank() && videoItems.isNotEmpty()) {
                    message = "$provider · ${videoItems.size} videos"
                }
            } else if (videoItems.isEmpty()) {
                message = "No movies found."
            }
        }
    }

    fun loadMovieBrowse(
        category: String,
        genre: WearMovieGenre? = null,
        page: Int = 1,
        append: Boolean = false,
    ) {
        librarySection = WearLibrarySection.MOVIES
        val returnToGenreList = category == "genre" && destination == WearDestination.MOVIE_GENRES
        selectedMovieGenre = genre
        videoQuery = ""
        catalogReturnDestination = when (category) {
            "genre" -> WearDestination.MOVIE_GENRES
            "popular" -> WearDestination.POPULAR
            else -> WearDestination.MOVIES
        }
        destination = if (returnToGenreList) WearDestination.MOVIE_GENRES else WearDestination.POPULAR
        musicRequestGeneration++
        val requestGeneration = ++videoRequestGeneration
        scope.launch {
            videoLoading = true
            if (!append) {
                videoItems = emptyList()
                selectedMovie = null
                videoStreams = emptyList()
                message = ""
            }
            val response = runCatching {
                WearMusicBridge.request(
                    context,
                    "movieBrowse",
                    JSONObject()
                        .put("category", category)
                        .put("genreId", genre?.id ?: 0)
                        .put("page", page),
                    timeoutMs = VIDEO_REQUEST_TIMEOUT_MS,
                )
            }.getOrElse { error ->
                if (requestGeneration != videoRequestGeneration) return@launch
                videoLoading = false
                message = error.message ?: "Couldn't load movies."
                return@launch
            }
            if (requestGeneration != videoRequestGeneration) return@launch
            videoLoading = false
            if (!response.optBoolean("ok")) {
                message = response.optString("message", "Couldn't load movies.")
                if (!append) videoItems = emptyList()
                return@launch
            }
            val received = parseVideoItems(response.optJSONArray("items"), WearLibrarySection.MOVIES)
            videoItems = if (append) (videoItems + received).distinctBy(WearVideoItem::id) else received
            videoPage = response.optInt("page", page)
            videoHasMore = response.optBoolean("hasMore") && received.isNotEmpty()
            message = if (videoItems.isEmpty()) "No movies found." else ""
        }
    }

    fun loadPhoneCollection(operation: String, targetDestination: WearDestination) {
        destination = targetDestination
        if (targetDestination == WearDestination.CONTINUE_WATCHING) {
            librarySection = WearLibrarySection.MOVIES
            catalogReturnDestination = WearDestination.CONTINUE_WATCHING
        }
        selectedMovie = null
        videoStreams = emptyList()
        val requestGeneration = ++videoRequestGeneration
        musicRequestGeneration++
        scope.launch {
            videoLoading = true
            message = ""
            val response = runCatching {
                WearMusicBridge.request(context, operation, timeoutMs = VIDEO_REQUEST_TIMEOUT_MS)
            }.getOrElse { error ->
                if (requestGeneration != videoRequestGeneration) return@launch
                videoLoading = false
                message = error.message ?: "Couldn't load ${targetDestination.label.lowercase()}."
                videoItems = emptyList()
                return@launch
            }
            if (requestGeneration != videoRequestGeneration) return@launch
            videoLoading = false
            if (!response.optBoolean("ok")) {
                videoItems = emptyList()
                message = response.optString("message", "Couldn't load ${targetDestination.label.lowercase()}.")
                return@launch
            }
            videoItems = parseVideoItems(response.optJSONArray("items"), WearLibrarySection.MOVIES)
            videoHasMore = false
            message = if (videoItems.isEmpty()) {
                if (targetDestination == WearDestination.CONTINUE_WATCHING) "Nothing to resume yet." else "No phone downloads found."
            } else {
                ""
            }
        }
    }

    fun loadAdultCategory(label: String, query: String) {
        selectedAdultCategory = label
        librarySection = WearLibrarySection.ADULT
        destination = WearDestination.ADULT_CATEGORIES
        loadVideoLibrary(WearLibrarySection.ADULT, query)
        destination = WearDestination.ADULT_CATEGORIES
    }

    fun selectLibrarySection(section: WearLibrarySection) {
        when (section) {
            WearLibrarySection.MUSIC,
            WearLibrarySection.LIBRARY -> loadMusicSection(section)
            WearLibrarySection.MOVIES,
            WearLibrarySection.ADULT -> {
                destination = if (section == WearLibrarySection.MOVIES) WearDestination.MOVIES else WearDestination.ADULT
                librarySection = section
                if (section != WearLibrarySection.ADULT) adultPin = ""
                videoQuery = ""
                loadVideoLibrary(section, "")
            }
        }
    }

    fun openVideoItem(item: WearVideoItem) {
        if (destination == WearDestination.CONTINUE_WATCHING) {
            catalogReturnDestination = WearDestination.CONTINUE_WATCHING
        } else if (destination == WearDestination.ADULT_CATEGORIES && selectedAdultCategory != null) {
            catalogReturnDestination = WearDestination.ADULT_CATEGORIES
        }
        destination = if (librarySection == WearLibrarySection.ADULT) {
            WearDestination.ADULT
        } else {
            WearDestination.MOVIE_DETAILS
        }
        videoRequestGeneration++
        videoLoading = false
        similarMovies = emptyList()
        scope.launch {
            loading = true
            message = if (item.source.isBlank()) "Finding watch-ready streams…" else "Preparing video…"
            if (item.source.isBlank()) {
                selectedMovie = item
                videoStreams = emptyList()
                if (!item.watchSupported) {
                    loading = false
                    message = "This title can resume on your phone, but this watch build cannot stream it directly."
                    return@launch
                }
                val details = runCatching {
                    WearMusicBridge.request(
                        context = context,
                        operation = "movieDetails",
                        values = JSONObject().put("tmdbId", item.id.toLongOrNull() ?: 0L),
                    )
                }.getOrNull()
                val response = runCatching {
                    WearMusicBridge.request(
                        context = context,
                        operation = "movieStreams",
                        values = JSONObject()
                            .put("tmdbId", item.id.toLongOrNull() ?: 0L)
                            .put("title", item.title)
                            .put("year", item.year),
                        timeoutMs = VIDEO_REQUEST_TIMEOUT_MS,
                    )
                }.getOrElse { error ->
                    loading = false
                    message = error.message ?: "Couldn't find a movie stream."
                    return@launch
                }
                loading = false
                if (!response.optBoolean("ok")) {
                    message = response.optString("message", "Couldn't find a movie stream.")
                    return@launch
                }
                val genres = details?.optJSONArray("genres")
                val cast = details?.optJSONArray("cast")
                similarMovies = parseVideoItems(
                    details?.optJSONArray("similar"),
                    WearLibrarySection.MOVIES,
                )
                val detailLines = buildList {
                    details?.optString("runtime")?.takeIf(String::isNotBlank)?.let(::add)
                    details?.optDouble("rating", 0.0)?.takeIf { it > 0.0 }?.let { add("★ ${"%.1f".format(it)}") }
                    if (genres != null) {
                        val labels = (0 until genres.length()).mapNotNull { genres.optString(it).takeIf(String::isNotBlank) }
                        if (labels.isNotEmpty()) add(labels.joinToString(" · "))
                    }
                    details?.optString("director")?.takeIf(String::isNotBlank)?.let { add("Director: $it") }
                    if (cast != null && cast.length() > 0) {
                        val names = (0 until minOf(cast.length(), 4))
                            .mapNotNull { cast.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                        if (names.isNotEmpty()) add("Cast: ${names.joinToString(", ")}")
                    }
                }
                selectedMovie = item.copy(
                    overview = details?.optString("overview")?.takeIf(String::isNotBlank)
                        ?: response.optString("overview", item.overview),
                    artwork = details?.optString("artwork")?.takeIf(String::isNotBlank)
                        ?: response.optString("artwork", item.artwork),
                    details = detailLines.joinToString("\n"),
                )
                videoStreams = parseVideoStreams(response.optJSONArray("streams"))
                message = response.optString("message")
                if (videoStreams.isEmpty() && message.isBlank()) {
                    message = "No direct watch-ready streams were found."
                }
            } else {
                val response = runCatching {
                    WearMusicBridge.request(
                        context = context,
                        operation = "adultPlay",
                        values = JSONObject()
                            .put("source", item.source)
                            .put("videoId", item.id)
                            .put("pageUrl", item.pageUrl)
                            .put("directUrl", item.directUrl)
                            .put("pin", adultPin),
                        timeoutMs = VIDEO_REQUEST_TIMEOUT_MS,
                    )
                }.getOrElse { error ->
                    loading = false
                    message = error.message ?: "Couldn't prepare this video."
                    return@launch
                }
                loading = false
                if (!response.optBoolean("ok")) {
                    message = response.optString("message", "Couldn't prepare this video.")
                    return@launch
                }
                videoTitle = item.title
                videoUrl = response.optString("url")
                videoHeaders = parseHeaders(response.optJSONObject("headers"))
                videoMimeType = response.optString("mimeType")
                if (videoUrl.isBlank()) {
                    message = "The provider did not return a playable watch stream."
                } else {
                    showNowPlaying = false
                    showVideoPlayer = true
                    destination = WearDestination.ADULT_PLAYER
                    message = ""
                }
            }
        }
    }

    fun playVideoStream(stream: WearVideoStream) {
        videoTitle = selectedMovie?.title ?: stream.title
        videoUrl = stream.url
        videoHeaders = stream.headers
        videoMimeType = stream.mimeType.ifBlank {
            if (stream.url.substringBefore('?').endsWith(".m3u8", true)) "application/x-mpegURL" else ""
        }
        showNowPlaying = false
        showVideoPlayer = true
        destination = if (librarySection == WearLibrarySection.ADULT) {
            WearDestination.ADULT_PLAYER
        } else {
            WearDestination.MOVIE_PLAYER
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
            while (true) {
                val watchState = runCatching { WearWatchPlayback.snapshot(context) }.getOrNull()
                if (watchState != null) {
                    watchSnapshot = watchState
                }
                delay(1_000L)
            }
        }
        while (true) {
            val state = runCatching { WearMusicBridge.request(context, "state") }.getOrNull()
            phoneConnected = state?.optBoolean("ok") == true
            if (state?.optBoolean("ok") == true) {
                val parsedState = parsePhoneSnapshot(state)
                phoneSnapshot = parsedState
            }
            delay(2_000L)
        }
    }

    val wearColors = androidx.wear.compose.material.Colors(
        primary = Color(0xFFE34DFF),
        primaryVariant = Color(0xFF9E38B6),
        secondary = Color(0xFFFF4EA8),
        secondaryVariant = Color(0xFFC73582),
        background = Color(0xFF08070D),
        surface = Color(0xFF18121F),
        error = Color(0xFFFF667A),
        onPrimary = Color(0xFF16091A),
        onSecondary = Color(0xFF1C0711),
        onBackground = Color(0xFFF7F0F8),
        onSurface = Color(0xFFF7F0F8),
        onError = Color(0xFF16070A),
    )
    BackHandler(enabled = !showVideoPlayer && destination != WearDestination.HOME) {
        if (showNowPlaying) {
            showNowPlaying = false
            destination = WearDestination.HOME
        } else if (destination == WearDestination.MOVIE_DETAILS) {
            selectedMovie = null
            videoStreams = emptyList()
            destination = catalogReturnDestination
        } else if (destination == WearDestination.POPULAR && selectedMovieGenre != null) {
            destination = WearDestination.MOVIE_GENRES
        } else if (destination == WearDestination.ADULT_CATEGORIES && selectedAdultCategory != null) {
            selectedAdultCategory = null
            destination = WearDestination.ADULT_CATEGORIES
        } else {
            destination = WearDestination.HOME
        }
    }
    MaterialTheme(colors = wearColors) {
        Scaffold(timeText = { TimeText() }) {
            if (showVideoPlayer) {
                WearVideoPlayerScreen(
                    title = videoTitle,
                    streamUrl = videoUrl,
                    headers = videoHeaders,
                    mimeType = videoMimeType,
                    startPositionMs = selectedMovie?.positionMs ?: 0L,
                    onBack = {
                        showVideoPlayer = false
                        destination = if (librarySection == WearLibrarySection.ADULT && selectedAdultCategory != null) {
                            WearDestination.ADULT_CATEGORIES
                        } else if (librarySection == WearLibrarySection.ADULT) {
                            WearDestination.ADULT
                        } else {
                            catalogReturnDestination
                        }
                    },
                )
            } else if (showNowPlaying) {
                WearNowPlayingScreen(
                    snapshot = activeSnapshot,
                    selectedOutput = target,
                    statusMessage = message,
                    updateTitle = if (availableUpdate != null) updateChipTitle else null,
                    updateDetails = updateChipDetails,
                    updateEnabled = !downloadingUpdate,
                    onSelectOutput = ::selectOutput,
                    onUpdate = ::installLatestUpdate,
                    onBrowse = {
                        showNowPlaying = false
                        loadMusicSection(WearLibrarySection.LIBRARY)
                    },
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
            } else if (destination == WearDestination.HOME) {
                WearHomeScreen(
                    snapshot = activeSnapshot.takeIf { it.hasItem } ?: emptyWearSnapshot(),
                    onNavigate = { next ->
                        when (next) {
                            WearDestination.HOME -> destination = WearDestination.HOME
                            WearDestination.MUSIC -> loadMusicSection(WearLibrarySection.MUSIC)
                            WearDestination.MUSIC_PLAYER -> {
                                destination = next
                                showNowPlaying = true
                            }
                            WearDestination.MOVIES -> {
                                librarySection = WearLibrarySection.MOVIES
                                selectedMovieGenre = null
                                catalogReturnDestination = WearDestination.MOVIES
                                videoQuery = ""
                                loadVideoLibrary(WearLibrarySection.MOVIES, "")
                            }
                            WearDestination.POPULAR -> loadMovieBrowse("popular")
                            WearDestination.MOVIE_GENRES -> {
                                selectedMovieGenre = null
                                videoItems = emptyList()
                                message = ""
                                destination = WearDestination.MOVIE_GENRES
                            }
                            WearDestination.ADULT -> {
                                librarySection = WearLibrarySection.ADULT
                                selectedAdultCategory = null
                                videoQuery = ""
                                loadVideoLibrary(WearLibrarySection.ADULT, "")
                            }
                            WearDestination.ADULT_CATEGORIES -> {
                                selectedAdultCategory = null
                                videoItems = emptyList()
                                message = ""
                                librarySection = WearLibrarySection.ADULT
                                destination = WearDestination.ADULT_CATEGORIES
                            }
                            WearDestination.ADULT_TRENDING -> {
                                librarySection = WearLibrarySection.ADULT
                                selectedAdultCategory = "Trending"
                                loadVideoLibrary(WearLibrarySection.ADULT, "")
                                destination = WearDestination.ADULT_TRENDING
                            }
                            WearDestination.CONTINUE_WATCHING ->
                                loadPhoneCollection("continueWatching", WearDestination.CONTINUE_WATCHING)
                            WearDestination.DOWNLOADS -> loadPhoneCollection("downloads", WearDestination.DOWNLOADS)
                            WearDestination.SETTINGS -> destination = WearDestination.SETTINGS
                            WearDestination.MOVIE_PLAYER -> {
                                if (videoUrl.isNotBlank()) showVideoPlayer = true
                                else selectLibrarySection(WearLibrarySection.MOVIES)
                            }
                            WearDestination.ADULT_PLAYER -> {
                                if (videoUrl.isNotBlank()) showVideoPlayer = true
                                else selectLibrarySection(WearLibrarySection.ADULT)
                            }
                            WearDestination.MOVIE_DETAILS -> selectLibrarySection(WearLibrarySection.MOVIES)
                            else -> destination = next
                        }
                    },
                )
            } else if (destination == WearDestination.CONTINUE_WATCHING) {
                WearContinueWatchingScreen(
                    items = videoItems,
                    loading = videoLoading,
                    message = message,
                    onSelectItem = { item ->
                        if (item.watchSupported) {
                            openVideoItem(item)
                        } else {
                            message = "TV episodes and provider-specific items must be resumed on your phone."
                        }
                    },
                    onBack = { destination = WearDestination.HOME },
                )
            } else if (destination == WearDestination.MOVIE_GENRES) {
                WearMovieGenresScreen(
                    selectedGenre = selectedMovieGenre,
                    items = videoItems,
                    loading = videoLoading,
                    message = message,
                    hasMore = videoHasMore,
                    onSelectGenre = { genre ->
                        catalogReturnDestination = WearDestination.MOVIE_GENRES
                        loadMovieBrowse("genre", genre)
                    },
                    onSelectItem = ::openVideoItem,
                    onLoadMore = {
                        if (selectedMovieGenre != null && videoHasMore && !videoLoading) {
                            loadMovieBrowse(
                                category = "genre",
                                genre = selectedMovieGenre,
                                page = videoPage + 1,
                                append = true,
                            )
                        }
                    },
                    onBack = {
                        if (selectedMovieGenre != null) {
                            selectedMovieGenre = null
                            videoItems = emptyList()
                            destination = WearDestination.MOVIE_GENRES
                        } else {
                            destination = WearDestination.HOME
                        }
                    },
                )
            } else if (destination == WearDestination.DOWNLOADS) {
                WearDownloadsScreen(
                    items = videoItems,
                    loading = videoLoading,
                    message = message,
                    onBack = { destination = WearDestination.HOME },
                )
            } else if (destination == WearDestination.MOVIE_PLAYER) {
                WearUnavailableDestination(
                    destination = destination,
                    explanation = "A movie player opens after a supported stream is selected. Some sources require phone playback.",
                    actionLabel = "Browse movies",
                    onAction = { selectLibrarySection(WearLibrarySection.MOVIES) },
                )
            } else if (destination == WearDestination.ADULT_PLAYER) {
                WearUnavailableDestination(
                    destination = destination,
                    explanation = "Choose a video from the phone-configured source. Only streams supported by the watch can play here.",
                    actionLabel = "Browse Adult",
                    onAction = { selectLibrarySection(WearLibrarySection.ADULT) },
                )
            } else if (destination == WearDestination.SETTINGS) {
                WearSettingsScreen(
                    selectedOutput = activePlaybackTarget,
                    phoneConnected = phoneConnected,
                    updateTitle = if (availableUpdate != null) updateChipTitle else null,
                    updateDetails = updateChipDetails,
                    updateEnabled = !downloadingUpdate,
                    onSelectOutput = ::selectOutput,
                    onUpdate = ::installLatestUpdate,
                    onDownloads = { loadPhoneCollection("downloads", WearDestination.DOWNLOADS) },
                    onBack = { destination = WearDestination.HOME },
                )
            } else if (destination == WearDestination.ADULT_CATEGORIES) {
                WearAdultCategoriesScreen(
                    selectedCategory = selectedAdultCategory,
                    items = videoItems,
                    loading = videoLoading,
                    message = message,
                    adultPin = adultPin,
                    onPinChange = { adultPin = it },
                    onSelectCategory = { label, query ->
                        loadAdultCategory(label, query)
                    },
                    onSelectItem = ::openVideoItem,
                    onBack = {
                        if (selectedAdultCategory != null) {
                            selectedAdultCategory = null
                            videoItems = emptyList()
                            destination = WearDestination.ADULT_CATEGORIES
                        } else {
                            destination = WearDestination.HOME
                        }
                    },
                )
            } else if (destination == WearDestination.MOVIES ||
                destination == WearDestination.POPULAR ||
                destination == WearDestination.MOVIE_DETAILS ||
                destination == WearDestination.ADULT ||
                destination == WearDestination.ADULT_TRENDING
            ) {
                WearVideoLibraryScreen(
                    section = if (destination == WearDestination.ADULT || destination == WearDestination.ADULT_TRENDING) {
                        WearLibrarySection.ADULT
                    } else {
                        WearLibrarySection.MOVIES
                    },
                    items = videoItems,
                    selectedMovie = selectedMovie,
                    streams = videoStreams,
                    relatedItems = similarMovies,
                    query = videoQuery,
                    adultPin = adultPin,
                    loading = videoLoading || (selectedMovie != null && loading),
                    hasMore = videoHasMore,
                    message = message,
                    onQueryChange = {
                        videoQuery = it
                        videoHasMore = false
                    },
                    onPinChange = { adultPin = it },
                    onSearch = {
                        selectedAdultCategory = null
                        catalogReturnDestination = if (librarySection == WearLibrarySection.ADULT) {
                            WearDestination.ADULT
                        } else {
                            WearDestination.MOVIES
                        }
                        loadVideoLibrary(librarySection, videoQuery)
                    },
                    onLoadMore = {
                        if (videoHasMore && !videoLoading) {
                            when (destination) {
                                WearDestination.MOVIE_GENRES -> loadMovieBrowse(
                                    category = "genre",
                                    genre = selectedMovieGenre,
                                    page = videoPage + 1,
                                    append = true,
                                )
                                WearDestination.POPULAR -> loadMovieBrowse(
                                    category = if (selectedMovieGenre != null) "genre" else "popular",
                                    genre = selectedMovieGenre,
                                    page = videoPage + 1,
                                    append = true,
                                )
                                WearDestination.ADULT_TRENDING -> {
                                    loadVideoLibrary(
                                        section = WearLibrarySection.ADULT,
                                        query = "",
                                        page = videoPage + 1,
                                        append = true,
                                    )
                                    destination = WearDestination.ADULT_TRENDING
                                }
                                else -> loadVideoLibrary(
                                    section = librarySection,
                                    query = videoQuery,
                                    page = videoPage + 1,
                                    append = true,
                                )
                            }
                        }
                    },
                    onSelectSection = ::selectLibrarySection,
                    onSelectItem = ::openVideoItem,
                    onSelectRelatedItem = ::openVideoItem,
                    onSelectStream = ::playVideoStream,
                    onBackToItems = {
                        selectedMovie = null
                        videoStreams = emptyList()
                        message = ""
                        destination = if (librarySection == WearLibrarySection.ADULT && selectedAdultCategory != null) {
                            WearDestination.ADULT_CATEGORIES
                        } else if (librarySection == WearLibrarySection.ADULT) {
                            WearDestination.ADULT
                        } else {
                            catalogReturnDestination
                        }
                    },
                    screenTitle = when (destination) {
                        WearDestination.POPULAR -> selectedMovieGenre?.label ?: "Popular"
                        WearDestination.ADULT_TRENDING -> "Trending"
                        WearDestination.MOVIE_DETAILS -> "Movie details"
                        WearDestination.ADULT -> "Adult"
                        else -> "Movies"
                    },
                    onOpenMovieGenres = {
                        selectedMovieGenre = null
                        videoItems = emptyList()
                        message = ""
                        destination = WearDestination.MOVIE_GENRES
                    },
                    onOpenPopular = { loadMovieBrowse("popular") },
                    onOpenContinueWatching = {
                        loadPhoneCollection("continueWatching", WearDestination.CONTINUE_WATCHING)
                    },
                    onOpenAdultCategories = {
                        selectedAdultCategory = null
                        videoItems = emptyList()
                        message = ""
                        destination = WearDestination.ADULT_CATEGORIES
                    },
                    onOpenAdultTrending = {
                        selectedAdultCategory = "Trending"
                        loadVideoLibrary(WearLibrarySection.ADULT, "")
                        destination = WearDestination.ADULT_TRENDING
                    },
                )
            } else {
            ScalingLazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF08070D))
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        "StreamCloud",
                        color = Color(0xFFE34DFF),
                        style = MaterialTheme.typography.title3,
                    )
                }
                item {
                    WearLibraryNavigation(
                        selectedSection = librarySection,
                        onSelectSection = ::selectLibrarySection,
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
                            icon = {
                                if (item.artwork.isNotBlank()) {
                                    AsyncImage(
                                        model = item.artwork,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    androidx.compose.foundation.Image(
                                        painter = painterResource(
                                            if (item.browsable) {
                                                R.drawable.ic_wear_library
                                            } else {
                                                R.drawable.ic_wear_music
                                            },
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            },
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

private fun parseVideoItems(
    array: JSONArray?,
    section: WearLibrarySection,
): List<WearVideoItem> =
    buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                WearVideoItem(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    subtitle = item.optString("subtitle"),
                    artwork = item.optString("artwork"),
                    overview = item.optString("overview"),
                    year = item.optInt("year"),
                    source = if (section == WearLibrarySection.ADULT) item.optString("source") else "",
                    pageUrl = item.optString("pageUrl"),
                    directUrl = item.optString("directUrl"),
                    mediaType = item.optString("mediaType", "movie"),
                    details = item.optString("details"),
                    kind = item.optString("kind", "video"),
                    status = item.optString("status"),
                    progressPct = if (item.has("progress") && !item.isNull("progress")) {
                        item.optInt("progress").coerceIn(0, 100)
                    } else {
                        null
                    },
                    positionMs = item.optLong("positionMs").coerceAtLeast(0L),
                    durationMs = item.optLong("durationMs").coerceAtLeast(0L),
                    watchSupported = item.optBoolean("watchSupported", true),
                    availableOffline = item.optBoolean("availableOffline"),
                ),
            )
        }
    }

private fun parseVideoStreams(array: JSONArray?): List<WearVideoStream> =
    buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url")
            if (url.isBlank()) continue
            add(
                WearVideoStream(
                    title = item.optString("title"),
                    provider = item.optString("provider"),
                    quality = item.optString("quality"),
                    url = url,
                    headers = parseHeaders(item.optJSONObject("headers")),
                    mimeType = item.optString("mimeType"),
                ),
            )
        }
    }

private fun parseHeaders(objectValue: JSONObject?): Map<String, String> =
    buildMap {
        if (objectValue == null) return@buildMap
        val iterator = objectValue.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val safe = key.equals("User-Agent", true) ||
                key.equals("Referer", true) ||
                key.equals("Origin", true) ||
                key.equals("Accept", true) ||
                key.equals("Accept-Language", true)
            val value = objectValue.optString(key)
            if (safe && value.isNotBlank()) put(key, value)
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
private const val VIDEO_REQUEST_TIMEOUT_MS = 90_000L
private const val WATCH_MUSIC_HOME_ID = "streamcloud_home"
private const val WATCH_LIBRARY_ID = "streamcloud_library"
