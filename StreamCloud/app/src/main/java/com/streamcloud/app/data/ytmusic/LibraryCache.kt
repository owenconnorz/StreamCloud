package com.streamcloud.app.data.ytmusic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent on-disk cache for the YT Music library screen (playlists + albums list).
 *
 * Stored in [Context.filesDir] so it survives "Clear Cache". Only cleared on
 * "Clear Storage / Data". This lets the Library tab render instantly on open
 * (stale-while-revalidate) even with no network connection.
 *
 * Track-count accuracy:
 * YouTube's library-browse API returns a stale count in the subtitle text
 * ("Playlist · 31 songs") that often disagrees with the real paginated count.
 * [updatePlaylistCount] is called by [YtPlaylistScreen] after a full load
 * so the cached entry always shows the correct number next time the card renders.
 */
object LibraryCache {

    private const val TAG = "LibraryCache"
    private const val FILE_NAME = "ytm_library.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class CachedPlaylist(
        val id: String,
        val title: String,
        val thumbnail: String? = null,
        val subtitle: String? = null,
        val isAlbum: Boolean = false,
        /** Real track count from the last full pagination — overrides the stale subtitle count. */
        val cachedTrackCount: Int? = null,
    )

    @Serializable
    private data class CachedLibrary(
        val playlists: List<CachedPlaylist> = emptyList(),
        val albums: List<CachedPlaylist> = emptyList(),
    )

    private fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)

    suspend fun read(context: Context): YtMusicLibrary? = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return@withContext null
        runCatching {
            val cached = json.decodeFromString(CachedLibrary.serializer(), f.readText())
            YtMusicLibrary(
                playlists = cached.playlists.map {
                    YtmPlaylist(
                        id = it.id,
                        title = it.title,
                        thumbnail = it.thumbnail,
                        subtitle = it.subtitle,
                        isAlbum = it.isAlbum,
                        cachedTrackCount = it.cachedTrackCount,
                    )
                },
                albums = cached.albums.map {
                    YtmPlaylist(
                        id = it.id,
                        title = it.title,
                        thumbnail = it.thumbnail,
                        subtitle = it.subtitle,
                        isAlbum = it.isAlbum,
                        cachedTrackCount = it.cachedTrackCount,
                    )
                },
            )
        }.onFailure { Log.w(TAG, "read failed", it) }.getOrNull()
    }

    suspend fun write(context: Context, library: YtMusicLibrary) = withContext(Dispatchers.IO) {
        runCatching {
            val payload = CachedLibrary(
                playlists = library.playlists.map {
                    CachedPlaylist(
                        id = it.id,
                        title = it.title,
                        thumbnail = it.thumbnail,
                        subtitle = it.subtitle,
                        isAlbum = it.isAlbum,
                        cachedTrackCount = it.cachedTrackCount,
                    )
                },
                albums = library.albums.map {
                    CachedPlaylist(
                        id = it.id,
                        title = it.title,
                        thumbnail = it.thumbnail,
                        subtitle = it.subtitle,
                        isAlbum = it.isAlbum,
                        cachedTrackCount = it.cachedTrackCount,
                    )
                },
            )
            file(context).writeText(json.encodeToString(CachedLibrary.serializer(), payload))
        }.onFailure { Log.w(TAG, "write failed", it) }
        Unit
    }

    /**
     * Update the stored track count for a single playlist after a full load.
     *
     * Called by [YtPlaylistScreen] once [YtMusicLibraryRepository.playlistTracks]
     * finishes paging through all continuations.  The real count replaces whatever
     * stale number YouTube returned in the library-browse subtitle text.
     *
     * ID matching strips the "VL" prefix both ways so `PLxxx` matches `VLPLxxx`
     * and vice versa — YouTube uses both forms in different API paths.
     */
    suspend fun updatePlaylistCount(
        context: Context,
        playlistId: String,
        count: Int,
    ) = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return@withContext
        runCatching {
            val cached = json.decodeFromString(CachedLibrary.serializer(), f.readText())

            fun matches(id: String): Boolean {
                val a = id.removePrefix("VL")
                val b = playlistId.removePrefix("VL")
                return a == b
            }

            val updated = CachedLibrary(
                playlists = cached.playlists.map { p ->
                    if (matches(p.id)) p.copy(cachedTrackCount = count) else p
                },
                albums = cached.albums.map { a ->
                    if (matches(a.id)) a.copy(cachedTrackCount = count) else a
                },
            )
            f.writeText(json.encodeToString(CachedLibrary.serializer(), updated))
            Log.d(TAG, "updatePlaylistCount: $playlistId → $count")
        }.onFailure { Log.w(TAG, "updatePlaylistCount failed", it) }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
