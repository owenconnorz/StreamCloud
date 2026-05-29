package com.streamcloud.app.data.library

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val url: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnail: String?,
    @ColumnInfo(name = "liked_at") val likedAt: Long? = null,
    @ColumnInfo(name = "last_played") val lastPlayed: Long? = null,
    @ColumnInfo(name = "play_count") val playCount: Int = 0,
    @ColumnInfo(name = "local_path") val localPath: String? = null,
)

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: TrackEntity)

    @Query("UPDATE tracks SET last_played = :ts, play_count = play_count + 1 WHERE url = :url")
    suspend fun bumpPlayed(url: String, ts: Long)

    @Query("UPDATE tracks SET liked_at = :ts WHERE url = :url")
    suspend fun setLikedAt(url: String, ts: Long?)

    @Query("UPDATE tracks SET local_path = :path WHERE url = :url")
    suspend fun setLocalPath(url: String, path: String?)

    @Query("SELECT * FROM tracks WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE last_played IS NOT NULL ORDER BY last_played DESC LIMIT 100")
    fun recent(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE liked_at IS NOT NULL ORDER BY liked_at DESC")
    fun liked(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE local_path IS NOT NULL ORDER BY title ASC")
    fun downloaded(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY play_count DESC LIMIT 30")
    fun mostPlayed(): Flow<List<TrackEntity>>

    @Query("SELECT liked_at IS NOT NULL FROM tracks WHERE url = :url")
    fun isLiked(url: String): Flow<Boolean?>

    @Query("UPDATE tracks SET last_played = NULL WHERE last_played IS NOT NULL")
    suspend fun clearRecent()
}

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey @ColumnInfo(name = "tmdb_id") val tmdbId: Long,
    val title: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface WatchProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchProgressEntity)


    @Query(
        "SELECT * FROM watch_progress " +
            "WHERE duration_ms > 0 " +
            "AND CAST(position_ms AS REAL) / duration_ms BETWEEN 0.01 AND 0.95 " +
            "ORDER BY updated_at DESC LIMIT 30",
    )
    fun continueWatching(): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress WHERE tmdb_id = :tmdbId")
    suspend fun remove(tmdbId: Long)

    @Query("SELECT * FROM watch_progress WHERE tmdb_id = :tmdbId LIMIT 1")
    suspend fun byId(tmdbId: Long): WatchProgressEntity?
}

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey @ColumnInfo(name = "tmdb_id") val tmdbId: Long,
    val title: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "cs_plugin", defaultValue = "") val csPlugin: String = "",
    @ColumnInfo(name = "cs_url", defaultValue = "") val csUrl: String = "",
)

@Dao
interface WatchlistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entry: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE tmdb_id = :tmdbId")
    suspend fun remove(tmdbId: Long)

    @Query("SELECT * FROM watchlist ORDER BY added_at DESC")
    fun all(): Flow<List<WatchlistEntity>>

    @Query("SELECT COUNT(*) > 0 FROM watchlist WHERE tmdb_id = :tmdbId")
    fun isWatchlisted(tmdbId: Long): Flow<Boolean>
}

@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlist_id", "track_url"])
data class PlaylistTrackEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "track_url") val trackUrl: String,
    val position: Int = 0,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
)

@Dao
interface LocalPlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPlaylist(playlist: LocalPlaylistEntity): Long

    @Query("DELETE FROM local_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :id")
    suspend fun clearPlaylistTracks(id: Long)

    @Query("SELECT * FROM local_playlists ORDER BY created_at DESC")
    fun allPlaylists(): Flow<List<LocalPlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrack(entry: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_url = :trackUrl")
    suspend fun removeTrack(playlistId: Long, trackUrl: String)

    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.url = pt.track_url
        WHERE pt.playlist_id = :playlistId
        ORDER BY pt.position ASC
    """)
    fun tracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = :playlistId")
    fun trackCount(playlistId: Long): Flow<Int>
}

@Entity(tableName = "audio_formats")
data class FormatEntity(
    @PrimaryKey @ColumnInfo(name = "video_id") val videoId: String,
    val itag: Int,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val codecs: String,
    val bitrate: Long,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int?,
    @ColumnInfo(name = "content_length") val contentLength: Long,
    @ColumnInfo(name = "loudness_db") val loudnessDb: Double?,
)

@Dao
interface FormatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(format: FormatEntity)

    @Query("SELECT * FROM audio_formats WHERE video_id = :videoId LIMIT 1")
    suspend fun byVideoId(videoId: String): FormatEntity?
}

// ── User-defined Collections ─────────────────────────────────────────────────

@Entity(tableName = "user_collections")
data class UserCollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String = "",
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "collection_folders")
data class CollectionFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "collection_id") val collectionId: Long,
    val name: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String = "",
    @ColumnInfo(name = "tile_shape") val tileShape: String = "wide",
    @ColumnInfo(name = "linked_category_id") val linkedCategoryId: String = "",
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

@Dao
interface UserCollectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserCollectionEntity): Long

    @Query("DELETE FROM user_collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM user_collections ORDER BY sort_order ASC, created_at ASC")
    fun all(): Flow<List<UserCollectionEntity>>

    @Query("SELECT * FROM user_collections WHERE is_pinned = 1 ORDER BY sort_order ASC, created_at ASC")
    fun pinned(): Flow<List<UserCollectionEntity>>

    @Query("SELECT * FROM user_collections WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): UserCollectionEntity?

    @Query("UPDATE user_collections SET sort_order = :order WHERE id = :id")
    suspend fun updateOrder(id: Long, order: Int)
}

@Dao
interface CollectionFolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CollectionFolderEntity): Long

    @Query("DELETE FROM collection_folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM collection_folders WHERE collection_id = :collectionId")
    suspend fun deleteForCollection(collectionId: Long)

    @Query("SELECT * FROM collection_folders WHERE collection_id = :collectionId ORDER BY sort_order ASC")
    fun forCollection(collectionId: Long): Flow<List<CollectionFolderEntity>>

    @Query("SELECT * FROM collection_folders WHERE collection_id = :collectionId ORDER BY sort_order ASC")
    suspend fun forCollectionOnce(collectionId: Long): List<CollectionFolderEntity>

    @Query("SELECT * FROM collection_folders")
    fun all(): Flow<List<CollectionFolderEntity>>
}

// ── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [
        TrackEntity::class,
        WatchProgressEntity::class,
        WatchlistEntity::class,
        LocalPlaylistEntity::class,
        PlaylistTrackEntity::class,
        FormatEntity::class,
        UserCollectionEntity::class,
        CollectionFolderEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class LibraryDb : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun watchProgress(): WatchProgressDao
    abstract fun watchlist(): WatchlistDao
    abstract fun localPlaylists(): LocalPlaylistDao
    abstract fun formats(): FormatDao
    abstract fun userCollections(): UserCollectionDao
    abstract fun collectionFolders(): CollectionFolderDao

    companion object {
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watchlist ADD COLUMN cs_plugin TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE watchlist ADD COLUMN cs_url TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_collections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        cover_url TEXT NOT NULL DEFAULT '',
                        is_pinned INTEGER NOT NULL DEFAULT 0,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS collection_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        collection_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        cover_url TEXT NOT NULL DEFAULT '',
                        tile_shape TEXT NOT NULL DEFAULT 'wide',
                        linked_category_id TEXT NOT NULL DEFAULT '',
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile private var INSTANCE: LibraryDb? = null
        fun get(context: Context): LibraryDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext, LibraryDb::class.java, "streamcloud-library.db",
            ).addMigrations(MIGRATION_5_6, MIGRATION_6_7).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
