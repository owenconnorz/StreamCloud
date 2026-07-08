package com.streamcloud.app.data.updates

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamcloud.app.data.library.ArtistReleaseNotificationEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.ReleaseEventType
import com.streamcloud.app.data.newpipe.NewPipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that checks every followed artist for new releases and
 * persists deduplicated in-app notifications.
 *
 * Deduplication is enforced at the DB layer via the unique index on `release_id`
 * (OnConflictStrategy.IGNORE).  The worker never emits duplicate notifications even
 * if it is retried or runs concurrently (KEEP policy in the scheduler).
 */
class ArtistReleaseCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val TAG = "ArtistReleaseCheck"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = LibraryDb.get(applicationContext)
        val settings = com.streamcloud.app.data.ServiceLocator.get(applicationContext).settings

        val notificationsEnabled = runCatching { settings.releaseNotificationsEnabled.first() }
            .getOrDefault(true)
        if (!notificationsEnabled) {
            Log.d(TAG, "Release notifications disabled — skipping")
            return@withContext Result.success()
        }

        val followed = runCatching { db.artistFollows().allFollowedOnce() }.getOrElse { e ->
            Log.e(TAG, "Failed to load followed artists: ${e.message}", e)
            return@withContext Result.retry()
        }

        if (followed.isEmpty()) {
            Log.d(TAG, "No followed artists — skipping")
            return@withContext Result.success()
        }

        Log.d(TAG, "Checking releases for ${followed.size} followed artist(s)")

        var newCount = 0
        for (follow in followed) {
            try {
                val page = NewPipeRepository.loadArtist(
                    follow.channelUrl.ifBlank { follow.artistId }
                ) ?: continue

                // Check albums and singles for new releases
                val releases = buildList {
                    for (album in page.albums) {
                        add(Triple(album.url, album.title, ReleaseEventType.ALBUM))
                    }
                    for (single in page.singles) {
                        add(Triple(single.url, single.title, ReleaseEventType.SINGLE_EP))
                    }
                }

                for ((releaseId, releaseTitle, eventType) in releases) {
                    if (releaseId.isBlank()) continue
                    val inserted = db.artistReleaseNotifications().insertIfAbsent(
                        ArtistReleaseNotificationEntity(
                            artistId = follow.artistId,
                            artistName = follow.artistName,
                            releaseId = releaseId,
                            releaseTitle = releaseTitle,
                            releaseThumbnail = null,
                            eventType = eventType.name,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    if (inserted > 0) {
                        newCount++
                        Log.d(TAG, "New release: ${follow.artistName} — $releaseTitle ($eventType)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Release check failed for artist ${follow.artistName}: ${e.message}", e)
                // Continue with the next artist rather than failing the whole job.
            }
        }

        // Prune notifications older than 90 days to avoid unbounded growth.
        val pruneBeforeTs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
        runCatching { db.artistReleaseNotifications().pruneOlderThan(pruneBeforeTs) }

        Log.d(TAG, "Release check complete. New notifications: $newCount")
        Result.success()
    }
}
