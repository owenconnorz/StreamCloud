package com.streamcloud.app.data.updates

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.ytmusic.YtMusicArtistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Periodic background worker that loads each locally-followed artist's page and checks
 * whether a new album or single has appeared since the last check.  Posts a notification
 * via [NewReleaseNotifier] for every new release found.
 */
class NewReleaseCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val TAG = "NewReleaseCheckWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting new-release check")
        val db  = LibraryDb.get(applicationContext)
        val dao = db.followedArtists()

        val followed = dao.all().first()
        if (followed.isEmpty()) {
            Log.d(TAG, "No followed artists — skipping")
            return@withContext Result.success()
        }

        val newReleases = mutableListOf<NewReleaseNotifier.NewRelease>()

        for (artist in followed) {
            runCatching {
                val page = YtMusicArtistRepository.load(artist.channelId)
                    ?: run {
                        Log.w(TAG, "Failed to load page for ${artist.name}")
                        return@runCatching
                    }

                // Find the latest release across albums + singles (most recently added = first in list)
                val latestRelease = (page.albums + page.singles).firstOrNull()
                val latestId = latestRelease?.url?.substringAfterLast("/")?.trim()
                    ?: latestRelease?.title?.hashCode()?.toString()

                if (latestId != null && latestId != artist.latestReleaseId) {
                    if (artist.latestReleaseId != null && latestRelease != null) {
                        // Only notify when we already had a baseline (avoids spam on first follow)
                        val releaseType = if (page.singles.firstOrNull()?.url == latestRelease.url) "Single" else "Album"
                        Log.d(TAG, "New release for ${artist.name}: ${latestRelease.title}")
                        newReleases += NewReleaseNotifier.NewRelease(
                            artistName  = artist.name,
                            releaseTitle = latestRelease.title,
                            releaseType  = releaseType,
                        )
                    }
                    // Update baseline regardless so the next run compares correctly
                    dao.updateLatestRelease(artist.channelId, latestId)
                }
            }.onFailure {
                Log.w(TAG, "Check failed for ${artist.name}: ${it.message}")
            }
        }

        if (newReleases.isNotEmpty()) {
            Log.d(TAG, "Posting ${newReleases.size} new-release notification(s)")
            NewReleaseNotifier.notify(applicationContext, newReleases)
        } else {
            Log.d(TAG, "No new releases found")
        }

        Result.success()
    }
}
