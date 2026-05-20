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
                    YtmPlaylist(id = it.id, title = it.title, thumbnail = it.thumbnail, subtitle = it.subtitle, isAlbum = it.isAlbum)
                },
                albums = cached.albums.map {
                    YtmPlaylist(id = it.id, title = it.title, thumbnail = it.thumbnail, subtitle = it.subtitle, isAlbum = it.isAlbum)
                },
            )
        }.onFailure { Log.w(TAG, "read failed", it) }.getOrNull()
    }

    suspend fun write(context: Context, library: YtMusicLibrary) = withContext(Dispatchers.IO) {
        runCatching {
            val payload = CachedLibrary(
                playlists = library.playlists.map {
                    CachedPlaylist(id = it.id, title = it.title, thumbnail = it.thumbnail, subtitle = it.subtitle, isAlbum = it.isAlbum)
                },
                albums = library.albums.map {
                    CachedPlaylist(id = it.id, title = it.title, thumbnail = it.thumbnail, subtitle = it.subtitle, isAlbum = it.isAlbum)
                },
            )
            file(context).writeText(json.encodeToString(CachedLibrary.serializer(), payload))
        }.onFailure { Log.w(TAG, "write failed", it) }
        Unit
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
