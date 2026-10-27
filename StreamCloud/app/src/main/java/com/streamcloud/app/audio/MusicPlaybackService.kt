    private suspend fun spotifyCollectionPage(
        cookie: String,
        parentId: String,
        offset: Int,
        pageSize: Int,
    ): ProviderBrowsePage {
        val page = when {
            parentId.startsWith(SPOTIFY_PLAYLIST_PREFIX) ->
                SpotifyWebPlayerRepository.getPlaylistTracksPage(
                    cookie,
                    parentId.removePrefix(SPOTIFY_PLAYLIST_PREFIX),
                    offset,
                )
            parentId.startsWith(SPOTIFY_ALBUM_PREFIX) ->
                SpotifyWebPlayerRepository.getAlbumTracksPage(
                    cookie,
                    parentId.removePrefix(SPOTIFY_ALBUM_PREFIX),
                    offset,
                )
            parentId.startsWith(SPOTIFY_ARTIST_PREFIX) -> {
                val result = SpotifyWebPlayerRepository.getArtistTopTracks(
                    cookie,
                    parentId.removePrefix(SPOTIFY_ARTIST_PREFIX),
                )
                result.copy(
                    items = result.items.drop(offset).take(pageSize),
                    nextOffset = if (offset + pageSize < result.totalCount) {
                        offset + pageSize
                    } else null,
                )
            }
            else -> SpotifyLibraryPage(emptyList(), 0, null)
        }
        return ProviderBrowsePage(
            items = page.items.take(pageSize).map(::spotifyTrackMediaItem),
            totalCount = page.totalCount,
            startIndex = offset,
        )
    }

    private fun spotifyPlaylistItem(item: SpotifyPlaylist): MediaItem =
        playlist("$SPOTIFY_PLAYLIST_PREFIX${item.id}", item.name, item.imageUrl)

    private fun spotifyAlbumItem(item: SpotifyAlbum): MediaItem = MediaItem.Builder()
        .setMediaId("$SPOTIFY_ALBUM_PREFIX${item.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.name)
                .setArtist(item.artists)
                .setArtworkUri(item.imageUrl?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .build(),
        )
        .build()

    private fun spotifyArtistItem(item: SpotifyArtist): MediaItem = MediaItem.Builder()
        .setMediaId("$SPOTIFY_ARTIST_PREFIX${item.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.name)
                .setArtworkUri(item.imageUrl?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                .build(),
        )
        .build()

    private fun spotifyTrackMediaItem(track: SpotifyTrack): MediaItem = MediaItem.Builder()
        .setMediaId("$SPOTIFY_TRACK_PREFIX${track.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artists)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.imageUrl?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply {
                    putString(YtPlayback.EXTRA_SPOTIFY_TRACK_ID, track.id)
                })
                .build(),
        )
        .build()

    private suspend fun spotifyTracksForBrowseQueue(parentId: String): List<MediaItem> {
        val cookie = sl.settings.spotifyCookie.first()
        if (cookie.isBlank()) {
            return listOf(errorItem(parentId, "Sign in to Spotify in Settings, then retry."))
        }
        val tracks = when {
            parentId == LIKED_ID -> collectSpotifyTracks { offset ->
                SpotifyWebPlayerRepository.getSavedTracksPage(cookie, offset)
            }
            parentId.startsWith(SPOTIFY_PLAYLIST_PREFIX) -> collectSpotifyTracks { offset ->
                SpotifyWebPlayerRepository.getPlaylistTracksPage(
                    cookie,
                    parentId.removePrefix(SPOTIFY_PLAYLIST_PREFIX),
                    offset,
                )
            }
            parentId.startsWith(SPOTIFY_ALBUM_PREFIX) -> collectSpotifyTracks { offset ->
                SpotifyWebPlayerRepository.getAlbumTracksPage(
                    cookie,
                    parentId.removePrefix(SPOTIFY_ALBUM_PREFIX),
                    offset,
                )
            }
            parentId.startsWith(SPOTIFY_ARTIST_PREFIX) ->
                SpotifyWebPlayerRepository.getArtistTopTracks(
                    cookie,
                    parentId.removePrefix(SPOTIFY_ARTIST_PREFIX),
                ).items
            else -> emptyList()
        }
        return tracks.map(::spotifyTrackMediaItem)
    }

    private suspend fun collectSpotifyTracks(
        fetchPage: suspend (Int) -> SpotifyLibraryPage<SpotifyTrack>,
    ): List<SpotifyTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val page = fetchPage(offset)
            tracks += page.items
            val nextOffset = page.nextOffset ?: break
            if (nextOffset <= offset || tracks.size >= page.totalCount) break
            offset = nextOffset
        }
        return tracks
    }

    private suspend fun resolveSpotifyQueue(items: List<MediaItem>): List<MediaItem> =
        coroutineScope {
            val semaphore = Semaphore(4)
            items.map { item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { resolveSpotifyTrackForPlayback(item) }
                }
            }.awaitAll()
        }

    private suspend fun resolveSpotifyTrackForPlayback(item: MediaItem): MediaItem {
        val spotifyTrackId = item.mediaMetadata.extras
            ?.getString(YtPlayback.EXTRA_SPOTIFY_TRACK_ID)
            ?.takeIf(String::isNotBlank)
            ?: return item
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        val match = YtMusicSearchRepository.songs("$title $artist").firstOrNull()
            ?: throw IllegalStateException("Couldn't find \"$title\" on YouTube Music for playback.")
        val watchUrl = match.url
        val videoId = watchUrl.substringAfter("v=", missingDelimiterValue = "").substringBefore("&")
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Couldn't resolve \"$title\" to a playable track.")
        return ytmSong(
            videoId = videoId,
            title = match.title,
            artist = match.uploader,
            album = item.mediaMetadata.albumTitle?.toString(),
            thumbnail = match.thumbnail,
            watchUrl = watchUrl,
            isMusicVideo = false,
            spotifyTrackId = spotifyTrackId,
        )
    }


    private suspend fun ytPlaylistTracks(playlistId: String): List<MediaItem> {
        return try {
            val cookie = sl.settings.ytMusicCookie.first()
            if (cookie.isBlank()) return listOf(errorItem(playlistId, "Sign in to YouTube Music, then retry."))
            val tracks = YtMusicLibraryRepository.playlistTracks(cookie, playlistId)
            tracks.map(::ytmSongItem).ifEmpty {
                listOf(errorItem(playlistId, "No tracks loaded. Retry after checking your connection."))
            }
        } catch (e: Throwable) {
            listOf(errorItem(playlistId, "Couldn't load YouTube Music playlist: ${e.message ?: "try again"}"))
        }
    }

    private suspend fun ytArtistTopSongs(channelId: String): List<MediaItem> {
        return try {
            val page = YtMusicArtistRepository.load(channelId)
                ?: return listOf(errorItem(channelId, "Couldn't load artist. Try again."))
            page.topTracks.map { t ->
                val videoId = t.url.substringAfter("v=").substringBefore("&")
                ytmSong(videoId, t.title, t.uploader, null, t.thumbnail, t.url, t.isVideo)
            }
        } catch (e: Throwable) {
            listOf(errorItem(channelId, "Couldn't load artist: ${e.message ?: "try again"}"))
        }
    }

    private suspend fun roomTracks(query: suspend (dao: com.streamcloud.app.data.library.TrackDao) -> List<TrackEntity>): List<MediaItem> =
        runCatching {
            val dao = LibraryDb.get(this).tracks()
            query(dao).map(::trackEntityItem)
        }.getOrElse { error ->
            listOf(errorItem("library", "Couldn't load library: ${error.message ?: "try again"}"))
        }

    private suspend fun localPlaylistTracks(rawId: String): List<MediaItem> {
        val id = rawId.toLongOrNull()
            ?: return listOf(errorItem(rawId, "Playlist is no longer available."))
        return runCatching {
            LibraryDb.get(this).localPlaylists().tracksForPlaylist(id).first().map(::trackEntityItem)
        }.getOrElse { error ->
            listOf(errorItem(rawId, "Couldn't load playlist: ${error.message ?: "try again"}"))
        }
    }

    private suspend fun localSongChildren(page: Int, pageSize: Int): List<MediaItem> =
        runCatching {
            val safePage = page.coerceAtLeast(0)
            val size = pageSize.takeIf { it > 0 } ?: DEFAULT_LIBRARY_PAGE_SIZE
            localMedia.loadAudioPage(safePage * size, size).items.map(::localAudioItem)
        }.getOrElse { error ->
            listOf(errorItem(SONGS_ID, "Couldn't load device songs: ${error.message ?: "grant media access and retry"}"))
        }

    private fun localAudioItem(item: LocalAudioItem): MediaItem = MediaItem.Builder()
        .setMediaId(item.uri.toString())
        .setUri(item.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .setAlbumTitle(item.album)
                .setArtworkUri(item.artworkUri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun pageItems(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0) return items
        val start = page.coerceAtLeast(0).toLong() * pageSize.toLong()
        return if (start >= items.size) emptyList() else items.drop(start.toInt()).take(pageSize)
    }

    private fun errorItem(
        source: String,
        message: String,
        title: String = "Couldn't load",
    ): MediaItem = MediaItem.Builder()
        .setMediaId("$ERROR_PREFIX$source")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(message)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .setExtras(Bundle().apply { putBoolean(EXTRA_RETRYABLE, true) })
                .build(),
        )
        .build()

    private suspend fun quickAddCurrentItem(): SessionResult {
        val destination = androidAutoQuickAddDestination
        val playlistId = destination.removePrefix(LOCAL_DESTINATION_PREFIX).toLongOrNull()
            ?: return quickAddResult(SessionResult.RESULT_ERROR_BAD_VALUE, "Choose a playlist in Android Auto settings.")
        val item = kotlinx.coroutines.withContext(Dispatchers.Main) {
            session?.player?.currentMediaItem
        } ?: return quickAddResult(SessionResult.RESULT_ERROR_BAD_VALUE, "Nothing is playing.")
        return runCatching {
            val db = LibraryDb.get(this)
            val playlists = db.localPlaylists()
            val metadata = item.mediaMetadata
            val url = item.mediaId.takeIf { it.isNotBlank() }
                ?: return@runCatching quickAddResult(SessionResult.RESULT_ERROR_BAD_VALUE, "This item cannot be added.")
            if (db.tracks().byUrl(url) == null) {
                db.tracks().upsert(TrackEntity(
                    url = url,
                    title = metadata.title?.toString().orEmpty().ifBlank { "Unknown title" },
                    artist = metadata.artist?.toString().orEmpty().ifBlank { "Unknown artist" },
                    durationSec = 0L,
                    thumbnail = metadata.artworkUri?.toString(),
                ))
            }
            when (playlists.appendTrackIfPlaylistExists(playlistId, url)) {
                1 -> quickAddResult(SessionResult.RESULT_SUCCESS, "Added to playlist.")
                0 -> quickAddResult(SessionResult.RESULT_SUCCESS, "Already in playlist.")
                else -> {
                    androidAutoQuickAddDestination = ""
                    sl.settings.setAndroidAutoQuickAddDestination("")
                    quickAddResult(SessionResult.RESULT_ERROR_BAD_VALUE, "Playlist was deleted. Choose another playlist.")
                }
            }
        }.getOrElse {
            AppLogger.w(TAG, "Android Auto quick-add failed: ${it.message}")
            quickAddResult(SessionResult.RESULT_ERROR_UNKNOWN, "Couldn't add to playlist. Try again.")
        }
    }

    private fun quickAddResult(code: Int, message: String): SessionResult =
        SessionResult(code, Bundle().apply { putString(EXTRA_COMMAND_MESSAGE, message) })



    private fun trackEntityItem(t: TrackEntity): MediaItem {
        val localFileUri = t.localPath
            ?.takeUnless { it.startsWith("cache:") }
            ?.let { java.io.File(it) }
            ?.takeIf(java.io.File::exists)
            ?.toUri()
        return MediaItem.Builder()
        .setMediaId(t.url)
        .setUri(localFileUri ?: t.url.toUri())
        .setCustomCacheKey(t.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setArtworkUri(t.thumbnail?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()
    }

    private fun ytmSongItem(s: YtmSong): MediaItem {
        val url = "https://music.youtube.com/watch?v=${s.videoId}"
        return ytmSong(s.videoId, s.title, s.artist, s.album, s.thumbnail, url, s.isVideo)
    }

    private fun ytmSong(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        thumbnail: String?,
        watchUrl: String,
        isMusicVideo: Boolean = false,
        spotifyTrackId: String? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(watchUrl)
        .setUri(watchUrl)
        .setCustomCacheKey(watchUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(thumbnail?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply {
                    putString(YtPlayback.EXTRA_VIDEO_ID, videoId)
                    putString(YtPlayback.EXTRA_WATCH_URL, watchUrl)
                    putBoolean(YtPlayback.EXTRA_IS_MUSIC_VIDEO, isMusicVideo)
                    spotifyTrackId?.takeIf(String::isNotBlank)
                        ?.let { putString(YtPlayback.EXTRA_SPOTIFY_TRACK_ID, it) }
                })
                .build(),
        )
        .build()

    private fun folder(id: String, title: String, artworkUrl: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun playlist(id: String, title: String, artworkUrl: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        )
        .build()

    private fun staticBrowsable(id: String): MediaItem? = when {
        id == ROOT_ID       -> buildRoot()
        id == HOME_ID       -> folder(HOME_ID,       "Home")
        id == AndroidAutoHomeContent.SPEED_DIAL_ID ->
            folder(id, "Speed dial")
        id == AndroidAutoHomeContent.QUICK_PICKS_ID ->
            folder(id, "Quick picks")
        id == LIBRARY_ID    -> folder(LIBRARY_ID,    "Your Library")
        id == PLAYLISTS_ID  -> folder(PLAYLISTS_ID,  "My Playlists")
        id == ALBUMS_ID     -> folder(ALBUMS_ID,     "Albums")
        id == ARTISTS_ID    -> folder(ARTISTS_ID,    "Artists")
        id == SONGS_ID      -> folder(SONGS_ID,      "Device Songs")
        id == HOME_FEED_ID  -> folder(HOME_FEED_ID,  "YT Music Home")
        id == RECENT_ID     -> playlist(RECENT_ID,    "Recently Played")
        id == ON_REPEAT_ID  -> playlist(ON_REPEAT_ID, "On Repeat")
        id == LIKED_ID      -> playlist(LIKED_ID,     "Liked Songs")
        id == DOWNLOADED_ID -> playlist(DOWNLOADED_ID,"Downloads")
        id.startsWith(SPOTIFY_PLAYLIST_PREFIX) ->
            playlist(id, "Spotify Playlist")
        id.startsWith(SPOTIFY_ALBUM_PREFIX) ->
            folder(id, "Spotify Album")
        id.startsWith(SPOTIFY_ARTIST_PREFIX) ->
            folder(id, "Spotify Artist")
        id.startsWith(YT_HOME_SECTION_PREFIX) -> {
            val shelf = AndroidAutoHomeContent.feedShelves(ytHomeFeed)
                .firstOrNull { it.mediaId == id } ?: return null
            folder(id, shelf.title, shelf.artworkUrl)
        }
        id.startsWith(YT_PLAYLIST_PREFIX) -> {
            val plId = id.removePrefix(YT_PLAYLIST_PREFIX)
            val pl = ytLibrary.playlists.find { it.id == plId }
            if (pl != null) ytPlaylistBrowsable(id, pl) else null
        }
        id.startsWith(YT_ALBUM_PREFIX) -> {
            val alId = id.removePrefix(YT_ALBUM_PREFIX)
            val al = ytLibrary.albums.find { it.id == alId }
            if (al != null) ytPlaylistBrowsable(id, al) else null
        }
        id.startsWith(YT_ARTIST_PREFIX) -> {
            val arId = id.removePrefix(YT_ARTIST_PREFIX)
            val ar = ytLibrary.artists.find { it.channelId == arId }
            if (ar != null) MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ar.name)
                        .setSubtitle(ar.subtitle)
                        .setArtworkUri(ar.thumbnail?.let(Uri::parse))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                        .build()
                ).build()
            else null
        }
        id.startsWith(YT_HOME_BROWSE_PREFIX) ->
            playlist(id, "YouTube Music playlist")
        id.startsWith(LOCAL_PLAYLIST_PREFIX) ->
            playlist(id, "Local playlist")
        else -> null
    }

    private fun ytPlaylistBrowsable(mediaId: String, pl: YtmPlaylist): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(pl.title)
                    .setSubtitle(pl.subtitle)
                    .setArtworkUri(pl.thumbnail?.let(Uri::parse))
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build(),
            )
            .build()

    /**
     * A YouTube CDN URL is signed for the Innertube client that minted it. Google can reject the
     * URL with 403 before `expire=` when that client is gated or its session token is invalidated.
     * Refresh through a bounded number of distinct client generations rather than allowing
     * ExoPlayer to keep using the invalid cached URL.
     */
    private fun retryWithFreshYoutubeStream(error: androidx.media3.common.PlaybackException): Boolean {
        val rangeReadFailure = isYoutubeRangeReadFailure(error)
        if (!isYoutubeCdn403(error) && !rangeReadFailure) return false

        val videoId = youtubeVideoId(exoPlayer.currentMediaItem?.mediaId) ?: return false
        val recoveryAttempt = youtubeStreamRecoveryAttempts.merge(videoId, 1) { previous, _ ->
            previous + 1
        } ?: 1
        if (recoveryAttempt > MAX_YOUTUBE_STREAM_RECOVERIES) {
            AppLogger.w(
                TAG,
                "YouTube stream read failed for $videoId after " +
                    "$MAX_YOUTUBE_STREAM_RECOVERIES distinct refreshes; not retrying again",
            )
            return false
        }

        val rejected = StreamUrlCache.remove(videoId)
        rejected?.url?.let(YtStreamIdentity::fingerprint)?.let { fingerprint ->
            rejectedStreamFingerprintsByVideoId
                .computeIfAbsent(videoId) { ConcurrentHashMap.newKeySet() }
                .add(fingerprint)
        }
        rejected?.clientLabel?.takeIf { it.isNotBlank() }?.let { client ->
            rejectedYouTubeClientsByVideoId
                .computeIfAbsent(videoId) { ConcurrentHashMap.newKeySet() }
                .add(client)
            YtPlayerUtils.recordCdnFailure(client)
        }
        if (recoveryAttempt == 1) {
            preferMaintainedExtractorVideoIds.add(videoId)
        } else {
            preferMaintainedExtractorVideoIds.remove(videoId)
        }
        // A POSITION_OUT_OF_RANGE error can be caused by a stale partial cache span. Do not carry
        // its byte position into the newly resolved URL; the fresh stream must start at byte zero.
        val resumePosition = if (rangeReadFailure) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)
        val currentItem = exoPlayer.currentMediaItem
        val cacheKey = currentItem?.localConfiguration?.customCacheKey
            ?: currentItem?.localConfiguration?.uri?.toString()
            ?: currentItem?.mediaId
            ?: "https://music.youtube.com/watch?v=$videoId"
        val preservesCompletedDownload =
            com.streamcloud.app.data.downloads.YtMusicDownloadUtil.isDownloaded(cacheKey)
        runCatching { playerCache.removeResource(cacheKey) }
            .onFailure { cacheError ->
                AppLogger.w(TAG, "Could not evict partial player cache for $videoId: ${cacheError.message}")
            }
        if (!preservesCompletedDownload) {
            runCatching { downloadCache.removeResource(cacheKey) }
                .onFailure { cacheError ->
                    AppLogger.w(TAG, "Could not evict partial download cache for $videoId: ${cacheError.message}")
                }
        }
        AppLogger.w(
            TAG,
            "YouTube ${if (rangeReadFailure) "range read failure" else "CDN 403"} for $videoId; " +
                "discarded ${rejected?.clientLabel ?: "uncached"} URL and stale " +
                "${if (preservesCompletedDownload) "player" else "player/download"} cache data; " +
                "trying recovery generation $recoveryAttempt/$MAX_YOUTUBE_STREAM_RECOVERIES",
        )

        // ResolvingDataSource invokes resolveStreamUrl() on prepare. It sees the removed cache
        // entry and the extractor-recovery marker, so it does not spend its one retry on another
        // URL from the client family that just failed.
        exoPlayer.prepare()
        if (resumePosition > 0L) exoPlayer.seekTo(resumePosition)
        exoPlayer.playWhenReady = true
        return true
    }

    private fun isYoutubeCdn403(error: androidx.media3.common.PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun isYoutubeRangeReadFailure(error: androidx.media3.common.PlaybackException): Boolean {
        if (error.errorCode ==
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        ) {
            return true
        }
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 416) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun youtubeVideoId(mediaId: String?): String? {
        val raw = mediaId.orEmpty()
        if (!raw.contains("youtube.com/watch")) return null
        return raw.substringAfter("v=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    private fun requestedSearchQuery(item: MediaItem): String? =
        item.requestMetadata.searchQuery
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: item.mediaMetadata.title
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        item.mediaId.isBlank() &&
                        item.localConfiguration?.uri == null
                }

    /**
     * Android Auto calls onSearch and onGetSearchResult as separate requests, while voice play
     * arrives through onAddMediaItems with RequestMetadata.searchQuery. Keep the result briefly
     * in-process so every route returns the exact same playable media item without duplicate
     * YouTube searches.
     */
    private suspend fun searchCarMusic(query: String): AndroidAutoSearchOutcome<MediaItem> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return androidAutoSearchOutcome(emptyList(), anyProviderSucceeded = true)
        }
        val selectedProvider = sl.settings.musicHomeProvider.first()
        val configuredLimit = sl.settings.androidAutoSongsSearchLimit.first()
        val localLimit = configuredLimit.takeUnless { it == "unlimited" }?.toIntOrNull() ?: Int.MAX_VALUE
        val cacheKey = "${selectedProvider.id}\u0000$configuredLimit\u0000$normalizedQuery"
        val nowMs = System.currentTimeMillis()
        carSearchResults[cacheKey]?.let { cached ->
            if (nowMs < cached.expiresAtMs) return cached.outcome
            carSearchResults.remove(cacheKey, cached)
        }
        if (selectedProvider == MusicProvider.Spotify) {
            val cookie = sl.settings.spotifyCookie.first()
            if (cookie.isBlank()) {
                return androidAutoSearchOutcome(emptyList(), anyProviderSucceeded = false)
            }
            SpotifyPlaylistRepository.initialize(applicationContext)
            val outcome = runCatching {
                SpotifyPlaylistRepository.searchTracks(cookie, normalizedQuery)
                    .take(localLimit)
                    .map(::spotifyTrackMediaItem)
            }.onFailure {
                AppLogger.w(TAG, "Android Auto Spotify search failed for \"$normalizedQuery\": ${it.message}")
            }.fold(
                onSuccess = { androidAutoSearchOutcome(it, anyProviderSucceeded = true) },
                onFailure = { androidAutoSearchOutcome(emptyList(), anyProviderSucceeded = false) },
            )
            if (outcome.state != AndroidAutoSearchState.PROVIDERS_UNAVAILABLE) {
                carSearchResults[cacheKey] = CachedCarSearchOutcome(
                    outcome = outcome,
                    expiresAtMs = System.currentTimeMillis() + CAR_SEARCH_RESULT_CACHE_TTL_MS,
                )
            }
            return outcome
        }
        val databaseMatches = runCatching {
            LibraryDb.get(this).tracks().search(
                "%$normalizedQuery%",
                localLimit,
            )
        }.getOrDefault(emptyList()).map(::trackEntityItem)
        val localMatches = runCatching {
            // MediaStore accepts bounded pages. Avoid arithmetic on Int.MAX_VALUE for the
            // unlimited option and stop only when the provider has no continuation.
            val batch = 100
            buildList {
                var offset = 0
                while (localLimit == Int.MAX_VALUE || size < localLimit) {
                    val requestSize = if (localLimit == Int.MAX_VALUE) batch
                    else minOf(batch, localLimit - size)
                    val page = localMedia.searchAudio(normalizedQuery, offset, requestSize)
                    addAll(page.items.map(::localAudioItem))
                    if (!page.hasMore) break
                    if (page.items.isEmpty() || offset > Int.MAX_VALUE - page.items.size) break
                    offset += page.items.size
                }
            }
        }.onFailure {
            AppLogger.w(TAG, "Android Auto local search failed: ${it.message}")
        }.getOrDefault(emptyList())
        if (selectedProvider == MusicProvider.Offline) {
            val localAndRoom = (databaseMatches + localMatches)
                .distinctBy(MediaItem::mediaId)
                .sortedByDescending { carSearchScore(it, normalizedQuery) }
                .take(localLimit)
            val outcome = androidAutoSearchOutcome(localAndRoom, anyProviderSucceeded = true)
            carSearchResults[cacheKey] = CachedCarSearchOutcome(
                outcome = outcome,
                expiresAtMs = System.currentTimeMillis() + CAR_SEARCH_RESULT_CACHE_TTL_MS,
            )
            return outcome
        }
        var remoteProviderSucceeded = false
        val nativeTracks = runCatching { YtMusicSearchRepository.songsWithStatus(normalizedQuery) }
            .onFailure { AppLogger.w(TAG, "Android Auto search failed for \"$normalizedQuery\": ${it.message}") }
            .getOrNull()
        if (nativeTracks != null) remoteProviderSucceeded = true
        val youtubeMatches = if (!nativeTracks.isNullOrEmpty()) {
            nativeTracks.take(YOUTUBE_SEARCH_RESULT_CAP).map { track ->
                ytmSong(
                    videoId = track.url.substringAfter("v=").substringBefore("&"),
                    title = track.title, artist = track.uploader, album = null,
                    thumbnail = track.thumbnail, watchUrl = track.url, isMusicVideo = track.isVideo,
                )
            }
        } else {
            val fallbackResult = runCatching { NewPipeRepository.searchSongs(normalizedQuery) }
                .onFailure { AppLogger.w(TAG, "Android Auto fallback search failed: ${it.message}") }
            if (fallbackResult.isSuccess) remoteProviderSucceeded = true
            fallbackResult.getOrDefault(emptyList())
                .take(YOUTUBE_SEARCH_RESULT_CAP)
                .map { track ->
                    ytmSong(
                        videoId = track.url.substringAfter("v=").substringBefore("&"),
                        title = track.title, artist = track.uploader, album = null,
                        thumbnail = track.thumbnail, watchUrl = track.url, isMusicVideo = track.isVideo,
                    )
                }
        }
        val localAndRoom = (databaseMatches + localMatches)
            .distinctBy(MediaItem::mediaId)
            .sortedByDescending { carSearchScore(it, normalizedQuery) }
            .take(localLimit)
        val items = (localAndRoom + youtubeMatches)
            .distinctBy(MediaItem::mediaId)
            .sortedByDescending { carSearchScore(it, normalizedQuery) }
        val outcome = androidAutoSearchOutcome(items, remoteProviderSucceeded)
        if (carSearchResults.size >= 24) carSearchResults.clear()
        if (outcome.state != AndroidAutoSearchState.PROVIDERS_UNAVAILABLE) {
            carSearchResults[cacheKey] = CachedCarSearchOutcome(
                outcome = outcome,
                expiresAtMs = System.currentTimeMillis() + CAR_SEARCH_RESULT_CACHE_TTL_MS,
            )
        }
        return outcome
    }

    private suspend fun searchCarMusicSafely(query: String): AndroidAutoSearchOutcome<MediaItem> =
        try {
            searchCarMusic(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogger.w(TAG, "Android Auto search could not complete for \"$query\": ${error.message}")
            androidAutoSearchOutcome(emptyList(), anyProviderSucceeded = false)
        }

    /** Treat a spoken “title artist” match as stronger than either field alone. */
    private fun carSearchScore(item: MediaItem, query: String): Int {
        val normalized = query.lowercase().trim()
        val title = item.mediaMetadata.title?.toString()?.lowercase()?.trim().orEmpty()
        val artist = item.mediaMetadata.artist?.toString()?.lowercase()?.trim().orEmpty()
        return when {
            "$title $artist" == normalized || "$artist $title" == normalized -> 4
            title == normalized -> 3
            title.contains(normalized) && artist.contains(normalized) -> 2
            title.contains(normalized) || artist.contains(normalized) -> 1
            else -> 0
        }
    }

    private fun attachUri(item: MediaItem): MediaItem {
        if (item.localConfiguration?.uri != null) return item
        val id = item.mediaId.ifBlank { return item }
        return item.buildUpon().setUri(id).setCustomCacheKey(id).build()
    }

    private fun isBrowseQueueParent(mediaId: String): Boolean =
        mediaId in setOf(RECENT_ID, ON_REPEAT_ID, LIKED_ID, DOWNLOADED_ID) ||
            mediaId.startsWith(LOCAL_PLAYLIST_PREFIX) ||
            mediaId.startsWith(YT_PLAYLIST_PREFIX) ||
            mediaId.startsWith(YT_ALBUM_PREFIX) ||
            mediaId.startsWith(YT_ARTIST_PREFIX) ||
            mediaId.startsWith(YT_HOME_BROWSE_PREFIX) ||
            mediaId.startsWith(SPOTIFY_PLAYLIST_PREFIX) ||
            mediaId.startsWith(SPOTIFY_ALBUM_PREFIX) ||
            mediaId.startsWith(SPOTIFY_ARTIST_PREFIX)

    private fun withBrowseQueueContext(
        item: MediaItem,
        parentId: String,
        index: Int,
    ): MediaItem {
        if (item.mediaId.startsWith(ERROR_PREFIX)) return item
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putString(EXTRA_BROWSE_QUEUE_PARENT_ID, parentId)
            putInt(EXTRA_BROWSE_QUEUE_INDEX, index)
        }
        val metadata = item.mediaMetadata.buildUpon().setExtras(extras).build()
        return item.buildUpon().setMediaMetadata(metadata).build()
    }

    private data class BrowseQueueRequest(
        val parentId: String,
        val selectedMediaId: String,
        val preferredIndex: Int?,
    )

    private data class ProviderBrowsePage(
        val items: List<MediaItem>,
        val totalCount: Int,
        val startIndex: Int = 0,
    )

    private fun browseQueueRequest(item: MediaItem): BrowseQueueRequest? {
        val extras = item.mediaMetadata.extras
        val parentId = extras?.getString(EXTRA_BROWSE_QUEUE_PARENT_ID)
            ?.takeIf(::isBrowseQueueParent)
            ?: item.mediaId.takeIf(::isBrowseQueueParent)
            ?: return null
        val preferredIndex = extras
            ?.takeIf { it.containsKey(EXTRA_BROWSE_QUEUE_INDEX) }
            ?.getInt(EXTRA_BROWSE_QUEUE_INDEX)
        return BrowseQueueRequest(parentId, item.mediaId, preferredIndex)
    }

    private fun browseQueueStartIndex(
        queue: List<MediaItem>,
        request: BrowseQueueRequest,
    ): Int? {
        if (request.selectedMediaId == request.parentId) return 0
        return androidAutoQueueStartIndex(
            queueMediaIds = queue.map(MediaItem::mediaId),
            selectedMediaId = request.selectedMediaId,
            preferredIndex = request.preferredIndex,
        )
    }

    private suspend fun loadBrowseQueue(parentId: String): List<MediaItem> = when {
        parentId == RECENT_ID -> roomTracks { it.recent().first() }
        parentId == ON_REPEAT_ID ->
            roomTracks { it.mostPlayed().first().filter { track -> track.playCount > 0 } }
        parentId == LIKED_ID &&
            sl.settings.musicHomeProvider.first() == MusicProvider.Spotify ->
            spotifyTracksForBrowseQueue(parentId)
        parentId == LIKED_ID -> likedChildren()
        parentId == DOWNLOADED_ID -> roomTracks { it.downloaded().first() }
        parentId.startsWith(SPOTIFY_PLAYLIST_PREFIX) ||
            parentId.startsWith(SPOTIFY_ALBUM_PREFIX) ||
            parentId.startsWith(SPOTIFY_ARTIST_PREFIX) ->
            spotifyTracksForBrowseQueue(parentId)
        parentId.startsWith(LOCAL_PLAYLIST_PREFIX) ->
            localPlaylistTracks(parentId.removePrefix(LOCAL_PLAYLIST_PREFIX))
        parentId.startsWith(YT_PLAYLIST_PREFIX) ->
            ytPlaylistTracks(parentId.removePrefix(YT_PLAYLIST_PREFIX))
        parentId.startsWith(YT_ALBUM_PREFIX) ->
            ytPlaylistTracks(parentId.removePrefix(YT_ALBUM_PREFIX))
        parentId.startsWith(YT_ARTIST_PREFIX) ->
            ytArtistTopSongs(parentId.removePrefix(YT_ARTIST_PREFIX))
        parentId.startsWith(YT_HOME_BROWSE_PREFIX) ->
            ytPlaylistTracks(parentId.removePrefix(YT_HOME_BROWSE_PREFIX))
        else -> emptyList()
    }

    companion object {
        private const val TAG    = "MusicPlaybackService"
        private const val CROSSFADE_HANDOFF_DURATION_MS = 350L
        // Keep a small byte runway for the immediate next songs. A 50 MiB speculative warm-up
        // competes with a cold first stream on constrained mobile and TV connections.
        private const val BUFFERED_PREFETCH_TRACK_COUNT = 1
        private const val BUFFERED_PREFETCH_BYTES = 256L * 1024L
        private const val BUFFERED_PREFETCH_BUFFER_BYTES = 64 * 1024
        private const val AUTHENTICATED_LIBRARY_SYNC_DELAY_MS = 5_000L
        private const val STREAM_WEB_SESSION_HEADER = "X-StreamCloud-Web-Session"
        private const val STREAM_WEB_SESSION_VALUE = "1"
        private const val STREAM_VIDEO_ID_HEADER = "X-StreamCloud-Video-Id"
        private const val MAX_YOUTUBE_STREAM_RECOVERIES = 2
        const val ROOT_ID        = "streamcloud_root"
        const val HOME_ID        = "streamcloud_home"
        const val LIBRARY_ID     = "streamcloud_library"
        const val RECENT_ID      = "streamcloud_recent"
        const val ON_REPEAT_ID   = "streamcloud_on_repeat"
        const val LIKED_ID       = "streamcloud_liked"
        const val DOWNLOADED_ID  = "streamcloud_downloaded"
        const val PLAYLISTS_ID   = "streamcloud_playlists"
        const val ALBUMS_ID      = "streamcloud_albums"
        const val ARTISTS_ID     = "streamcloud_artists"
        const val SONGS_ID       = "streamcloud_songs"
        const val HOME_FEED_ID   = "streamcloud_home_feed"
        const val YT_PLAYLIST_PREFIX     = "ytpl_"
        const val YT_ALBUM_PREFIX        = "ytalbum_"
        const val YT_ARTIST_PREFIX       = "ytartist_"
        const val YT_HOME_SECTION_PREFIX = "yths_"
        const val YT_HOME_BROWSE_PREFIX  = "ythbr_"
        const val SPOTIFY_PLAYLIST_PREFIX = "sppl_"
        const val SPOTIFY_ALBUM_PREFIX = "spal_"
        const val SPOTIFY_ARTIST_PREFIX = "spar_"
        const val SPOTIFY_TRACK_PREFIX = "sptrack_"
        const val LOCAL_PLAYLIST_PREFIX  = "localpl_"
        const val LOCAL_DESTINATION_PREFIX = "local:"
        const val ERROR_PREFIX = "streamcloud_error_"
        const val EXTRA_RETRYABLE = "com.streamcloud.app.extra.RETRYABLE"
        const val EXTRA_COMMAND_MESSAGE = "com.streamcloud.app.extra.COMMAND_MESSAGE"
        private const val EXTRA_BROWSE_QUEUE_PARENT_ID =
            "com.streamcloud.app.extra.BROWSE_QUEUE_PARENT_ID"
        private const val EXTRA_BROWSE_QUEUE_INDEX =
            "com.streamcloud.app.extra.BROWSE_QUEUE_INDEX"
        const val DEFAULT_LIBRARY_PAGE_SIZE = 50
        const val QUEUE_STATE_PREFS = "streamcloud_playback_state"
        const val QUEUE_STATE_KEY = "queue_snapshot_v1"
        const val QUEUE_SNAPSHOT_INTERVAL_MS = 10_000L
        const val MAX_PERSISTED_QUEUE_ITEMS = 100
        const val MAX_QUEUE_SNAPSHOT_BYTES = 96 * 1024
        const val YOUTUBE_SEARCH_RESULT_CAP = 100
        const val CAR_SEARCH_RESULT_CACHE_TTL_MS = 60_000L
        val AUTO_SECTION_IDS = listOf(
            "playlists", "artists", "albums", "liked_songs", "songs",
            "home", "recently_played", "on_repeat", "downloads",
        )
        private val ANDROID_AUTO_SOURCE_PARENTS = setOf(
            ROOT_ID,
            HOME_ID,
            LIBRARY_ID,
            PLAYLISTS_ID,
            ALBUMS_ID,
            ARTISTS_ID,
            LIKED_ID,
        )

        const val ACTION_LIKE   = "com.streamcloud.app.action.like"
        const val ACTION_REPEAT = "com.streamcloud.app.action.repeat"
        const val ACTION_QUICK_ADD = "com.streamcloud.app.action.quick_add"
        val LIKE_COMMAND   = SessionCommand(ACTION_LIKE,   Bundle())
        val REPEAT_COMMAND = SessionCommand(ACTION_REPEAT, Bundle())
        val QUICK_ADD_COMMAND = SessionCommand(ACTION_QUICK_ADD, Bundle())
    }
}
