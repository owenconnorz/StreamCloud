package com.aioweb.app.data.ytmusic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lightweight on-disk cache for YT Music playlist track lists. Lets the
 * playlist screen render its list immediately on open while a fresh fetch
 * happens in the background.
 *
 * Storage: one JSON file per playlist id under `cacheDir/playlist_tracks/`.
 * No expiry — the UI always replaces with the network result once it lands,
 * so a stale cache never persists for more than a few seconds of viewing.
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

    private fun dir(context: Context): File =
        File(context.cacheDir, "playlist_tracks").apply { mkdirs() }

    private fun fileFor(context: Context, playlistId: String): File =
        File(dir(context), "$playlistId.json")

    suspend fun read(context: Context, playlistId: String): List<YtmSong>? = withContext(Dispatchers.IO) {
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

    suspend fun write(context: Context, playlistId: String, songs: List<YtmSong>) = withContext(Dispatchers.IO) {
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
}
