package com.streamcloud.app.data.ytmusic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent on-disk cache for YT Music playlist track lists.
 *
 * Stored in [android.content.Context.filesDir] (not cacheDir) so the data
 * survives "Clear Cache" and is only removed on "Clear Storage / Data".
 * This matches Metrolist's approach — playlist caches are treated as user
 * data, not throwaway HTTP caches.
 *
 * Stale-while-revalidate: the UI renders cached data immediately while a
 * fresh fetch happens in the background. The cache is replaced once the
 * network result lands.
 */
object PlaylistCache {
    private const val TAG = "PlaylistCache"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class CachedSong(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val thumbnail: String? = null,
        val durationSeconds: Long? = null,
    )

    private fun dir(context: android.content.Context): File =
        File(context.filesDir, "playlist_tracks").apply { mkdirs() }

    private fun fileFor(context: android.content.Context, playlistId: String): File =
        File(dir(context), "$playlistId.json")

    suspend fun read(context: android.content.Context, playlistId: String): List<YtmSong>? = withContext(Dispatchers.IO) {
        val f = fileFor(context, playlistId)
        if (!f.exists() || f.length() == 0L) return@withContext null
        runCatching {
            json.decodeFromString(ListSerializer(CachedSong.serializer()), f.readText()).map {
                YtmSong(
                    videoId = it.videoId,
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    thumbnail = it.thumbnail,
                    durationSeconds = it.durationSeconds,
                )
            }
        }.onFailure { Log.w(TAG, "read failed", it) }.getOrNull()
    }

    suspend fun write(context: android.content.Context, playlistId: String, songs: List<YtmSong>) = withContext(Dispatchers.IO) {
        runCatching {
            val payload = songs.map {
                CachedSong(
                    videoId = it.videoId,
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    thumbnail = it.thumbnail,
                    durationSeconds = it.durationSeconds,
                )
            }
            fileFor(context, playlistId).writeText(
                json.encodeToString(ListSerializer(CachedSong.serializer()), payload),
            )
        }.onFailure { Log.w(TAG, "write failed", it) }
        Unit
    }

    fun delete(context: android.content.Context, playlistId: String) {
        runCatching { fileFor(context, playlistId).delete() }
            .onFailure { Log.w(TAG, "delete failed", it) }
    }
}
