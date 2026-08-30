package com.streamcloud.app.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalAudioItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val uri: Uri,
    val artworkUri: Uri?,
)

data class LocalVideoItem(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val uri: Uri,
)

data class LocalImageItem(
    val id: Long,
    val title: String,
    val uri: Uri,
)

data class LocalMediaPage<T>(
    val items: List<T>,
    val hasMore: Boolean,
)

class LocalMediaRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun loadAudioPage(offset: Int, limit: Int): LocalMediaPage<LocalAudioItem> =
        withContext(Dispatchers.IO) {
            queryPage(
                contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                ),
                selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.SIZE} > 0",
                sortColumn = MediaStore.Audio.Media.DATE_ADDED,
                offset = offset,
                limit = limit,
            ) { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val albumId = cursor.getLong(albumIdCol)
                        add(
                            LocalAudioItem(
                                id = id,
                                title = cursor.getString(titleCol).orEmpty()
                                    .ifBlank { cursor.getString(nameCol).orEmpty() }
                                    .ifBlank { "Unknown title" },
                                artist = cursor.getString(artistCol).orEmpty().ifBlank { "Unknown artist" },
                                album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() },
                                durationMs = cursor.getLong(durationCol),
                                uri = uri,
                                artworkUri = albumId.takeIf { it > 0L }?.let {
                                    Uri.parse("content://media/external/audio/albumart/$it")
                                },
                            ),
                        )
                    }
                }
            }
        }

    /** Searches device music without loading the complete MediaStore catalog into memory. */
    suspend fun searchAudio(query: String, offset: Int, limit: Int): LocalMediaPage<LocalAudioItem> =
        withContext(Dispatchers.IO) {
            val escaped = query.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            val match = "%$escaped%"
            queryPage(
                contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                ),
                selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                    "${MediaStore.Audio.Media.SIZE} > 0 AND (" +
                    "${MediaStore.Audio.Media.TITLE} LIKE ? ESCAPE '\\' OR " +
                    "${MediaStore.Audio.Media.ARTIST} LIKE ? ESCAPE '\\')",
                selectionArgs = arrayOf(match, match),
                sortColumn = MediaStore.Audio.Media.TITLE,
                offset = offset,
                limit = limit,
            ) { cursor -> audioItems(cursor) }
        }

    suspend fun loadVideoPage(offset: Int, limit: Int): LocalMediaPage<LocalVideoItem> =
        withContext(Dispatchers.IO) {
            queryPage(
                contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                ),
                selection = "${MediaStore.Video.Media.SIZE} > 0",
                sortColumn = MediaStore.Video.Media.DATE_ADDED,
                offset = offset,
                limit = limit,
            ) { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        add(
                            LocalVideoItem(
                                id = id,
                                title = cursor.getString(titleCol).orEmpty()
                                    .ifBlank { cursor.getString(nameCol).orEmpty() }
                                    .ifBlank { "Untitled video" },
                                durationMs = cursor.getLong(durationCol),
                                uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                            ),
                        )
                    }
                }
            }
        }

    suspend fun loadImagePage(offset: Int, limit: Int): LocalMediaPage<LocalImageItem> =
        withContext(Dispatchers.IO) {
            queryPage(
                contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                ),
                selection = "${MediaStore.Images.Media.SIZE} > 0",
                sortColumn = MediaStore.Images.Media.DATE_ADDED,
                offset = offset,
                limit = limit,
            ) { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        add(
                            LocalImageItem(
                                id = id,
                                title = cursor.getString(nameCol).orEmpty().ifBlank { "Untitled image" },
                                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                            ),
                        )
                    }
                }
            }
        }

    private fun <T> queryPage(
        contentUri: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>? = null,
        sortColumn: String,
        offset: Int,
        limit: Int,
        mapper: (Cursor) -> List<T>,
    ): LocalMediaPage<T> {
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            resolver.query(
                contentUri,
                projection,
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    selectionArgs?.let {
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it)
                    }
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortColumn))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                },
                null,
            )
        } else {
            resolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                "$sortColumn DESC LIMIT $limit OFFSET $offset",
            )
        }
        val items = cursor?.use(mapper).orEmpty()
        return LocalMediaPage(items = items, hasMore = items.size == limit)
    }

    private fun audioItems(cursor: Cursor): List<LocalAudioItem> {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        return buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                add(LocalAudioItem(
                    id, cursor.getString(titleCol).orEmpty().ifBlank { cursor.getString(nameCol).orEmpty() }
                        .ifBlank { "Unknown title" },
                    cursor.getString(artistCol).orEmpty().ifBlank { "Unknown artist" },
                    cursor.getString(albumCol)?.takeIf { it.isNotBlank() }, cursor.getLong(durationCol),
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    albumId.takeIf { it > 0L }?.let { Uri.parse("content://media/external/audio/albumart/$it") },
                ))
            }
        }
    }
}
