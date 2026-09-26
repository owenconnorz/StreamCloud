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
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.library.LibraryDb
import kotlinx.coroutines.CancellationException
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
    private const val PENDING_ADDON_DELETES = "pending_addon_deletes"

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
     * Save explicit addon deletion intent before changing local state. The
     * server stores addons per Nuvio profile, so keep the intent scoped to the
     * account and profile that were selected when the user removed it.
     */
    suspend fun recordAddonDelete(context: Context, manifestUrl: String) {
        val canonicalUrl = canonicalNuvioAddonUrl(manifestUrl)
        if (canonicalUrl.isBlank()) return
        val scope = addonSyncScope(context) ?: return
        val (userId, profileIndex) = scope
        val key = addonDeletePreferenceKey(userId, profileIndex)
        val prefs = context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        pending += canonicalUrl
        check(prefs.edit().putStringSet(key, pending).commit()) {
            "Could not persist the Nuvio addon deletion request"
        }
    }

    /**
     * A user adding an addon again after deleting it cancels the local
     * tombstone so the next sync can restore it to Nuvio.
     */
    suspend fun recordAddonAddition(context: Context, manifestUrl: String) {
        val canonicalUrl = canonicalNuvioAddonUrl(manifestUrl)
        if (canonicalUrl.isBlank()) return
        val scope = addonSyncScope(context) ?: return
        val (userId, profileIndex) = scope
        val key = addonDeletePreferenceKey(userId, profileIndex)
        val prefs = context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val targetKey = normalizedNuvioAddonKey(canonicalUrl)
        val pending = prefs.getStringSet(key, emptySet()).orEmpty()
            .filterNot { normalizedNuvioAddonKey(it) == targetKey }
            .toSet()
        check(prefs.edit().putStringSet(key, pending).commit()) {
            "Could not update the Nuvio addon deletion requests"
        }
    }

    internal fun pendingAddonDeletes(
        context: Context,
        userId: String,
        profileIndex: Int,
    ): Set<String> {
        if (userId.isBlank()) return emptySet()
        val prefs = context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(addonDeletePreferenceKey(userId, profileIndex), emptySet())
            .orEmpty()
            .map(::canonicalNuvioAddonUrl)
            .filter { it.isNotBlank() }
            .toSet()
    }

    internal fun clearAddonDeletes(
        context: Context,
        userId: String,
        profileIndex: Int,
        deletedUrls: Set<String>,
    ) {
        if (userId.isBlank() || deletedUrls.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val key = addonDeletePreferenceKey(userId, profileIndex)
        val deletedKeys = deletedUrls.map(::normalizedNuvioAddonKey).toSet()
        val pending = prefs.getStringSet(key, emptySet()).orEmpty()
            .filterNot { normalizedNuvioAddonKey(it) in deletedKeys }
            .toSet()
        check(prefs.edit().putStringSet(key, pending).commit()) {
            "Could not clear confirmed Nuvio addon deletions"
        }
    }

    private suspend fun addonSyncScope(context: Context): Pair<String, Int>? {
        val appContext = context.applicationContext
        val services = ServiceLocator.get(appContext)
        val userId = services.settings.nuvioUserId.first().trim()
        if (userId.isBlank()) return null

        val profiles = services.profiles
        val selected = profiles.currentProfiles().firstOrNull {
            it.id == profiles.currentActiveId()
        } ?: profiles.currentProfiles().firstOrNull()
        val profileIndex = selected?.nuvioProfileIndex ?: return null
        return userId to profileIndex
    }

    private fun addonDeletePreferenceKey(userId: String, profileIndex: Int): String =
        "${PENDING_ADDON_DELETES}_${userId}_$profileIndex"

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
        // The process may be stopped immediately after the user removes an item.
        // A tombstone must be on disk before any background work is scheduled.
        prefs.edit().putStringSet(PENDING_LIBRARY_DELETES, pending).commit()
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
     * Completes the same safe pull-then-push cycle used by background sync.
     * Callers that need fresh account data before continuing can await this
     * instead of merely enqueueing WorkManager and reading stale local state.
     */
    suspend fun syncNow(context: Context): Result<Unit> {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)
        val token = settings.nuvioAccessToken.first().trim()
        if (token.isBlank()) return Result.success(Unit)

        val service = NuvioAccountService.get(appContext)

        suspend fun attempt(accessToken: String): Result<String?> = try {
            pushPendingLibraryDeletes(appContext, accessToken)
            val pull = service.syncPull(accessToken)
            if (pull.errors.isNotEmpty()) {
                Result.success(pull.errors.distinct().joinToString("; "))
            } else {
                val push = service.syncAll(accessToken)
                Result.success(
                    push.errors.takeIf { it.isNotEmpty() }
                        ?.distinct()
                        ?.joinToString("; "),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

        val firstAttempt = attempt(token)
        if (firstAttempt.isSuccess && firstAttempt.getOrNull() == null) {
            return Result.success(Unit)
        }

        val firstMessage = firstAttempt.exceptionOrNull()?.message
            ?: firstAttempt.getOrNull()
            ?: "Nuvio sync failed"
        val refreshToken = settings.nuvioRefreshToken.first().trim()
        if (refreshToken.isBlank()) {
            return Result.failure(
                firstAttempt.exceptionOrNull() ?: IllegalStateException(firstMessage),
            )
        }

        val refreshed = service.refreshToken(refreshToken)
        if (refreshed.isFailure) {
            val refreshFailure = refreshed.exceptionOrNull()
            return Result.failure(
                IllegalStateException(
                    "Nuvio sync failed: $firstMessage; token refresh failed: " +
                        (refreshFailure?.message ?: "unknown error"),
                    refreshFailure,
                ),
            )
        }

        val session = refreshed.getOrThrow()
        settings.setNuvioSession(
            accessToken = session.access_token,
            refreshToken = session.refresh_token.ifBlank { refreshToken },
            email = session.user?.email ?: settings.nuvioEmail.first(),
            userId = session.user?.id ?: settings.nuvioUserId.first(),
        )

        val retry = attempt(session.access_token)
        if (retry.isSuccess && retry.getOrNull() == null) {
            return Result.success(Unit)
        }

        return Result.failure(
            retry.exceptionOrNull()
                ?: IllegalStateException(
                    "Nuvio sync failed after token refresh: " +
                        (retry.getOrNull() ?: "unknown error"),
                ),
        )
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
        prefs.edit().putStringSet(PENDING_LIBRARY_DELETES, pending).commit()
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
        val outcome = NuvioAutoSync.syncNow(applicationContext)
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            Log.w("NuvioAutoSync", "automatic sync failed: ${failure.message}", failure)
            return Result.retry()
        }
        return Result.success()
    }

}