package com.streamcloud.app.data.nuvio

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamcloud.app.data.SettingsRepository
import com.streamcloud.app.data.library.LibraryDb
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Keeps Nuvio sync independent of the Settings screen. Local changes request a
 * network-backed sync, while the periodic job catches changes made on another
 * device even when StreamCloud has not been opened recently.
 */
object NuvioAutoSync {
    private const val PERIODIC_WORK = "nuvio_auto_sync_periodic"
    private const val IMMEDIATE_WORK = "nuvio_auto_sync_immediate"
    private const val SYNC_PREFS = "nuvio_sync"
    private const val PENDING_LIBRARY_DELETES = "pending_library_deletes"

    fun installPeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<NuvioAutoSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun request(context: Context) {
        val request = OneTimeWorkRequestBuilder<NuvioAutoSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    /**
     * Records the deletion before scheduling work. This is intentionally
     * separate from the network request: a user can remove an item while
     * offline, and Nuvio must not recreate it on the next pull.
     */
    fun requestLibraryDelete(context: Context, tmdbId: Long, mediaType: String) {
        val contentType = when (mediaType.lowercase()) {
            "tv", "series" -> "series"
            "movie" -> "movie"
            else -> return
        }
        val key = "$contentType:tmdb:$tmdbId"
        val prefs = context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(PENDING_LIBRARY_DELETES, emptySet()).orEmpty().toMutableSet()
        pending += key
        prefs.edit().putStringSet(PENDING_LIBRARY_DELETES, pending).apply()
        request(context)
    }

    suspend fun pushPendingLibraryDeletes(context: Context, accessToken: String) {
        val appContext = context.applicationContext
        val pending = pendingLibraryDeletes(appContext)
        if (pending.isEmpty()) return

        NuvioAccountService.get(appContext).deleteLibraryItems(
            accessToken,
            pending.map { NuvioLibraryDeleteKey(it.contentId, it.contentType) },
        )
        clearLibraryDeletes(appContext, pending)
    }

    /**
     * Pulling before pending deletions are accepted by Nuvio can resurrect a
     * locally removed item. Keep every automatic pull behind this gate.
     */
    suspend fun pullAfterPendingLibraryDeletes(
        context: Context,
        accessToken: String,
    ): Result<NuvioPullResult> = runCatching {
        pushPendingLibraryDeletes(context, accessToken)
        NuvioAccountService.get(context.applicationContext).syncPull(accessToken)
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}

class NuvioAutoSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        val token = settings.nuvioAccessToken.first().trim()
        if (token.isBlank()) return Result.success()

        val service = NuvioAccountService.get(applicationContext)

        suspend fun syncWith(accessToken: String): String? {
            pushPendingLibraryDeletes(applicationContext, accessToken)
            val push = service.syncAll(accessToken)
            if (push.errors.isNotEmpty()) {
                return push.errors.distinct().joinToString("; ")
            }
            val pull = service.syncPull(accessToken)
            val errors = (push.errors + pull.errors).distinct()
            return errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }

        val firstAttempt = runCatching { syncWith(token) }
        if (firstAttempt.isSuccess && firstAttempt.getOrNull() == null) {
            return Result.success()
        }

        val refreshToken = settings.nuvioRefreshToken.first().trim()
        if (refreshToken.isNotBlank()) {
            val refreshed = service.refreshToken(refreshToken)
            if (refreshed.isSuccess) {
                val session = refreshed.getOrThrow()
                settings.setNuvioSession(
                    accessToken = session.access_token,
                    refreshToken = session.refresh_token.ifBlank { refreshToken },
                    email = session.user?.email ?: settings.nuvioEmail.first(),
                    userId = session.user?.id ?: settings.nuvioUserId.first(),
                )
                val retry = runCatching { syncWith(session.access_token) }
                if (retry.isSuccess && retry.getOrNull() == null) {
                    return Result.success()
                }
                Log.w(
                    "NuvioAutoSync",
                    "automatic sync failed after token refresh: ${
                        retry.exceptionOrNull()?.message ?: retry.getOrNull()
                    }",
                )
                return Result.retry()
            }
            Log.w("NuvioAutoSync", "Nuvio token refresh failed: ${refreshed.exceptionOrNull()?.message}")
        }

        Log.w(
            "NuvioAutoSync",
            "automatic sync failed: ${
                firstAttempt.exceptionOrNull()?.message ?: firstAttempt.getOrNull()
            }",
        )
        return Result.retry()
    }

    private data class PendingDelete(
        val serialized: String,
        val contentType: String,
        val contentId: String,
    )

    private fun pendingLibraryDeletes(context: Context): List<PendingDelete> {
        val prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(PENDING_LIBRARY_DELETES, emptySet()).orEmpty().mapNotNull { value ->
            val separator = value.indexOf(':')
            if (separator <= 0 || separator == value.lastIndex) return@mapNotNull null
            val contentType = value.substring(0, separator)
            val contentId = value.substring(separator + 1)
            PendingDelete(value, contentType, contentId)
        }
    }

    private fun clearLibraryDeletes(context: Context, deletes: Collection<PendingDelete>) {
        if (deletes.isEmpty()) return
        val prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(PENDING_LIBRARY_DELETES, emptySet()).orEmpty().toMutableSet()
        deletes.forEach { pending.remove(it.serialized) }
        prefs.edit().putStringSet(PENDING_LIBRARY_DELETES, pending).apply()
    }
}