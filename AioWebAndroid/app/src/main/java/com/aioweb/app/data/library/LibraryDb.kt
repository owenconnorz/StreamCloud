package com.aioweb.app.data.library

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
}

/**
 * Resume-playback state for a movie/episode. Keyed by the TMDB id we use
 * everywhere else (Movies tab, MovieDetailScreen). Updated periodically by
 * the player and read by the "Continue Watching" row on the home screen.
 */
@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey @ColumnInfo(name = "tmdb_id") val tmdbId: Long,
    val title: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "media_type") val mediaType: String, // "movie" or "tv"
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface WatchProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchProgressEntity)

    /**
     * Anything between 1% and 95% watched is "in progress". Below 1% means the
     * user barely opened it, above 95% means they finished it — both should
     * fall out of the Continue Watching row.
     */
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

@Database(
    entities = [TrackEntity::class, WatchProgressEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class LibraryDb : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun watchProgress(): WatchProgressDao

    companion object {
        @Volatile private var INSTANCE: LibraryDb? = null
        fun get(context: Context): LibraryDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext, LibraryDb::class.java, "streamcloud-library.db",
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
