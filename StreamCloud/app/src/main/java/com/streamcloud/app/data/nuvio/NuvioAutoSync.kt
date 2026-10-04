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
import com.streamcloud.app.data.nuvio.NuvioAccountScopeStore
import com.streamcloud.app.data.nuvio.stableKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    private const val PENDING_WATCH_PROGRESS_DELETES = "pending_watch_progress_deletes"
    private const val WATCH_PROGRESS_REMOTE_IDENTITIES = "watch_progress_remote_identities"
    private val syncMutex = Mutex()

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
        enqueue(context, progressOnly = false)
    }

    /**
     * Playback position changes are frequent and replaceable. Keep at most one
     * sync in the unique chain for them; explicit changes and watch completion
     * still append a guaranteed follow-up through [request].
     */
    fun requestProgress(context: Context) {
        enqueue(context, progressOnly = true)
    }

    private fun enqueue(context: Context, progressOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<NuvioAutoSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            syncRequestWorkPolicy(progressOnly),
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
        val key = addonDeletePreferenceKey(context, userId, profileIndex)
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
        val key = addonDeletePreferenceKey(context, userId, profileIndex)
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
        return prefs.getStringSet(addonDeletePreferenceKey(context, userId, profileIndex), emptySet())
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
        val key = addonDeletePreferenceKey(context, userId, profileIndex)
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
        val profileIndex = selected?.let {
            profiles.nuvioProfileIndex(userId, it.id)
        } ?: return null
        return userId to profileIndex
    }

    private fun addonDeletePreferenceKey(
        context: Context,
        userId: String,
        profileIndex: Int,
    ): String {
        val accountKey = stableKey("$userId:$profileIndex").take(16)
        val scopedKey = "${PENDING_ADDON_DELETES}_$accountKey"
        val legacyKey = "${PENDING_ADDON_DELETES}_${userId}_$profileIndex"
        val prefs = context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val legacy = prefs.getStringSet(legacyKey, null)?.toSet() ?: return scopedKey
        val current = prefs.getStringSet(scopedKey, emptySet()).orEmpty().toSet()
        check(
            prefs.edit()
                .putStringSet(scopedKey, current + legacy)
                .remove(legacyKey)
                .commit(),
        ) { "Could not migrate the Nuvio addon deletion requests" }
        return scopedKey
    }

    /**
     * Records the deletion before scheduling work. This is intentionally
     * separate from the network request: a user can remove an item while
     * offline, and Nuvio must not recreate it on the next pull.
     */
    suspend fun requestLibraryDelete(context: Context, tmdbId: Long, mediaType: String) {
        val contentType = when (mediaType.lowercase()) {
            "tv", "series" -> "series"
            "movie" -> "movie"
            else -> return
        }
        val appContext = context.applicationContext
        val services = ServiceLocator.get(appContext)
        val userId = services.settings.nuvioUserId.first().trim()
        if (userId.isBlank()) return
        NuvioAccountScopeStore.setCurrentUserId(appContext, userId)
        val profiles = services.profiles
        val localProfileId = profiles.currentActiveId()
            ?: profiles.currentProfiles().firstOrNull()?.id
            ?: return
        if (profiles.nuvioProfileIndex(userId, localProfileId) == null) return
        val key = "$contentType:tmdb:$tmdbId"
        val prefs = appContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val preferenceKey = libraryDeletePreferenceKey(userId, localProfileId)
        val pending = prefs.getStringSet(preferenceKey, emptySet()).orEmpty().toMutableSet()
        pending += key
        // The process may be stopped immediately after the user removes an item.
        // A tombstone must be on disk before any background work is scheduled.
        check(prefs.edit().putStringSet(preferenceKey, pending).commit()) {
            "Could not persist the Nuvio library deletion request"
        }
        request(context)
    }

    suspend fun recordWatchProgressDelete(
        context: Context,
        tmdbId: Long,
        mediaType: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ) {
        if (tmdbId <= 0L || mediaType !in setOf("movie", "tv", "series")) return
        val appContext = context.applicationContext
        val services = ServiceLocator.get(appContext)
        val userId = services.settings.nuvioUserId.first().trim()
        if (userId.isBlank()) return
        NuvioAccountScopeStore.setCurrentUserId(appContext, userId)
        val profiles = services.profiles
        val localProfileId = profiles.currentActiveId()
            ?.takeIf { it.isNotBlank() }
            ?: profiles.currentProfiles().firstOrNull()?.id?.takeIf { it.isNotBlank() }
            ?: "default"

        val normalizedMediaType = if (mediaType == "series") "tv" else mediaType
        val serialized = listOf(
            tmdbId.toString(),
            normalizedMediaType,
            seasonNumber.coerceAtLeast(0).toString(),
            episodeNumber.coerceAtLeast(0).toString(),
        ).joinToString("|")
        val prefs = appContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val key = watchProgressDeletePreferenceKey(userId, localProfileId)
        val pending = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        pending += serialized
        val progressIdentity = NuvioWatchProgressDeleteKey(
            tmdbId = tmdbId,
            mediaType = normalizedMediaType,
            seasonNumber = seasonNumber.coerceAtLeast(0),
            episodeNumber = episodeNumber.coerceAtLeast(0),
        )
        val identityKey = watchProgressRemoteIdentityPreferenceKey(
            userId,
            localProfileId,
            progressIdentity,
        )
        prefs.getStringSet(identityKey, emptySet()).orEmpty()
            .mapNotNull(NuvioWatchProgressDeleteKey::deserialize)
            .forEach { pending += it.serialize() }
        check(prefs.edit().putStringSet(key, pending).commit()) {
            "Could not persist the Nuvio watch progress deletion request"
        }
    }

    internal fun recordWatchProgressRemoteIdentities(
        context: Context,
        userId: String,
        localProfileId: String,
        identities: Collection<NuvioWatchProgressDeleteKey>,
    ) {
        if (identities.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val grouped = identities
            .filter { !it.remoteContentId.isNullOrBlank() }
            .groupBy {
                watchProgressRemoteIdentityPreferenceKey(userId, localProfileId, it)
            }
        if (grouped.isEmpty()) return
        val pendingPreferenceKey = watchProgressDeletePreferenceKey(userId, localProfileId)
        val previousPending = prefs.getStringSet(pendingPreferenceKey, emptySet()).orEmpty().toSet()
        val pending = previousPending.toMutableSet()
        val pendingKeys = previousPending.mapNotNull(NuvioWatchProgressDeleteKey::deserialize)
        identities.forEach { identity ->
            if (pendingKeys.any {
                    it.matchesLocalProgress(
                        identity.tmdbId,
                        identity.mediaType,
                        identity.seasonNumber,
                        identity.episodeNumber,
                    )
                }
            ) {
                // Keep the original Nuvio identifiers on the pending delete so retries
                // target the exact remote row, not only its resolved TMDB identity.
                pending += identity.serialize()
            }
        }
        val editor = prefs.edit()
        grouped.forEach { (key, records) ->
            val existing = prefs.getStringSet(key, emptySet()).orEmpty()
            editor.putStringSet(key, existing + records.map(NuvioWatchProgressDeleteKey::serialize))
        }
        if (pending != previousPending) {
            editor.putStringSet(pendingPreferenceKey, pending)
        }
        check(editor.commit()) {
            "Could not persist Nuvio watch progress remote identities or pending deletes"
        }
    }

    internal fun pendingWatchProgressDeleteKeys(
        context: Context,
        userId: String,
        localProfileId: String,
    ): List<NuvioWatchProgressDeleteKey> =
        pendingWatchProgressDeletes(context.applicationContext, userId, localProfileId)

    internal fun clearConfirmedWatchProgressDeletes(
        context: Context,
        userId: String,
        localProfileId: String,
        deletes: Collection<NuvioWatchProgressDeleteKey>,
    ) {
        if (deletes.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val key = watchProgressDeletePreferenceKey(userId, localProfileId)
        val pending = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (pending.removeAll(deletes.map(NuvioWatchProgressDeleteKey::serialize).toSet())) {
            check(prefs.edit().putStringSet(key, pending).commit()) {
                "Could not clear confirmed Nuvio watch progress deletions"
            }
        }
    }

    suspend fun pushPendingWatchProgressDeletes(
        context: Context,
        accessToken: String,
        userId: String? = null,
        localProfileId: String? = null,
    ): List<NuvioWatchProgressDeleteKey> {
        val appContext = context.applicationContext
        val services = ServiceLocator.get(appContext)
        val targetUserId = userId?.trim()?.takeIf { it.isNotBlank() }
            ?: services.settings.nuvioUserId.first().trim()
        val targetProfileId = localProfileId?.takeIf { it.isNotBlank() }
            ?: services.profiles.currentActiveId()
                ?: services.profiles.currentProfiles().firstOrNull()?.id
                ?: return emptyList()
        if (targetUserId.isBlank()) return emptyList()
        val pending = pendingWatchProgressDeletes(appContext, targetUserId, targetProfileId)
        if (pending.isEmpty()) return emptyList()

        try {
            NuvioAccountService.get(appContext).deleteWatchProgressItems(
                accessToken = accessToken,
                keys = pending,
                userId = targetUserId,
                localProfileId = targetProfileId,
            )
            // Keep tombstones until the following progress pull confirms the rows
            // are absent. A successful delete RPC can still be followed by a stale pull.
            pending
        } finally {
            // Keep deleted rows out of the local library even when the remote
            // tombstone fails and must be retried.
            withContext(NonCancellable) {
                val dao = LibraryDb.getForProfile(
                    appContext,
                    targetProfileId,
                    targetUserId,
                ).watchProgress()
                pending.forEach { delete ->
                    dao.removeKey(
                        delete.tmdbId,
                        delete.mediaType,
                        delete.seasonNumber,
                        delete.episodeNumber,
                    )
                }
            }
        }
    }

    suspend fun pushPendingLibraryDeletes(
        context: Context,
        accessToken: String,
        userId: String? = null,
        localProfileId: String? = null,
    ) {
        val appContext = context.applicationContext
        val services = ServiceLocator.get(appContext)
        val targetUserId = userId?.trim()?.takeIf { it.isNotBlank() }
            ?: services.settings.nuvioUserId.first().trim()
        val targetProfileId = localProfileId?.takeIf { it.isNotBlank() }
            ?: services.profiles.currentActiveId()
                ?: services.profiles.currentProfiles().firstOrNull()?.id
                ?: return
        val pending = pendingLibraryDeletes(appContext, targetUserId, targetProfileId)
        if (pending.isEmpty()) return

        NuvioAccountService.get(appContext).deleteLibraryItems(
            accessToken,
            pending.map { NuvioLibraryDeleteKey(it.contentId, it.contentType) },
            userId = targetUserId,
            localProfileId = targetProfileId,
        )
        clearLibraryDeletes(appContext, pending, targetUserId, targetProfileId)
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

        return syncNowDetailed(appContext).map { Unit }
    }

    data class SyncOutcome(
        val pull: NuvioPullResult,
        val push: NuvioSyncResult,
    )

    suspend fun syncNowDetailed(context: Context): Result<SyncOutcome> =
        syncMutex.withLock { syncNowDetailedLocked(context.applicationContext) }

    /**
     * Serialize the tombstone, local removal, and cloud sync with every other
     * sync. The caller may optimistically hide the item first; removing it again
     * under this lock prevents an in-flight pull from resurrecting it.
     */
    suspend fun deleteWatchProgressAndSyncNow(
        context: Context,
        tmdbId: Long,
        mediaType: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): Result<SyncOutcome> {
        val appContext = context.applicationContext
        val progressDao = LibraryDb.get(appContext).watchProgress()
        return syncMutex.withLock {
            var deleteIntentRecorded = false
            try {
                recordWatchProgressDelete(
                    appContext,
                    tmdbId,
                    mediaType,
                    seasonNumber,
                    episodeNumber,
                )
                deleteIntentRecorded = true
                progressDao.removeKey(
                    tmdbId,
                    mediaType,
                    seasonNumber,
                    episodeNumber,
                )
                val result = syncNowDetailedLocked(appContext)
                if (result.isSuccess) {
                    val services = ServiceLocator.get(appContext)
                    val userId = services.settings.nuvioUserId.first().trim()
                    val localProfileId = services.profiles.currentActiveId()
                        ?.takeIf { it.isNotBlank() }
                        ?: services.profiles.currentProfiles().firstOrNull()?.id
                            ?.takeIf { it.isNotBlank() }
                        ?: "default"
                    val deleteStillPending = userId.isNotBlank() &&
                        pendingWatchProgressDeleteKeys(
                            appContext,
                            userId,
                            localProfileId,
                        ).any {
                            it.matchesLocalProgress(
                                tmdbId,
                                mediaType,
                                seasonNumber,
                                episodeNumber,
                            )
                        }
                    if (deleteStillPending) request(appContext)
                }
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            } finally {
                if (deleteIntentRecorded) {
                    // A pull/merge can restore the row during the sync. Remove it
                    // again before releasing the sync lock, but only after its
                    // durable delete intent was recorded.
                    withContext(NonCancellable) {
                        progressDao.removeKey(
                            tmdbId,
                            mediaType,
                            seasonNumber,
                            episodeNumber,
                        )
                    }
                }
            }
        }
    }

    private suspend fun syncNowDetailedLocked(context: Context): Result<SyncOutcome> {
        val appContext = context.applicationContext
        val settings = ServiceLocator.get(appContext).settings
        val services = ServiceLocator.get(appContext)
        val accessToken = settings.nuvioAccessToken.first().trim()
        val userId = settings.nuvioUserId.first().trim()
        if (accessToken.isBlank()) {
            return Result.failure(IllegalStateException("No Nuvio session is active"))
        }
        if (userId.isBlank()) {
            return Result.failure(IllegalStateException("The Nuvio session has no account ID"))
        }
        val localProfileId = services.profiles.currentActiveId()
            ?.takeIf { it.isNotBlank() }
            ?: services.profiles.currentProfiles().firstOrNull()?.id
            ?: "default"

        NuvioAccountScopeStore.setCurrentUserId(appContext, userId)
        val service = NuvioAccountService.get(appContext)

        suspend fun attempt(token: String): Result<SyncOutcome> = try {
            val pull = service.syncPull(token, userId, localProfileId)
            if (pull.errors.isNotEmpty()) {
                Result.failure(IllegalStateException(pull.errors.distinct().joinToString("; ")))
            } else {
                val push = service.syncAll(
                    accessToken = token,
                    userId = userId,
                    localProfileId = pull.localProfileId.ifBlank { localProfileId },
                )
                if (push.errors.isNotEmpty()) {
                    Result.failure(IllegalStateException(push.errors.distinct().joinToString("; ")))
                } else {
                    Result.success(SyncOutcome(pull, push))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

        val firstAttempt = attempt(accessToken)
        if (firstAttempt.isSuccess) return firstAttempt

        val firstFailure = firstAttempt.exceptionOrNull()
            ?: IllegalStateException("Nuvio sync failed")
        val refreshToken = settings.nuvioRefreshToken.first().trim()
        if (refreshToken.isBlank()) return Result.failure(firstFailure)

        val refreshed = service.refreshToken(refreshToken)
        if (refreshed.isFailure) {
            val refreshFailure = refreshed.exceptionOrNull()
            return Result.failure(
                IllegalStateException(
                    "Nuvio sync failed: ${firstFailure.message ?: "unknown error"}; " +
                        "token refresh failed: ${refreshFailure?.message ?: "unknown error"}",
                    refreshFailure,
                ),
            )
        }

        if (settings.nuvioUserId.first().trim() != userId) {
            return Result.failure(
                IllegalStateException("The Nuvio account changed while sync was running"),
            )
        }
        val session = refreshed.getOrThrow()
        val refreshedUserId = session.user?.id?.takeIf { it.isNotBlank() }
        if (refreshedUserId != null && refreshedUserId != userId) {
            return Result.failure(
                IllegalStateException("Token refresh returned a different Nuvio account"),
            )
        }
        settings.setNuvioSession(
            accessToken = session.access_token,
            refreshToken = session.refresh_token.ifBlank { refreshToken },
            email = session.user?.email ?: settings.nuvioEmail.first(),
            userId = refreshedUserId ?: userId,
        )
        return attempt(session.access_token)
    }

    private data class PendingDelete(
        val serialized: String,
        val contentType: String,
        val contentId: String,
    )

    private fun pendingWatchProgressDeletes(
        context: Context,
        userId: String,
        localProfileId: String,
    ): List<NuvioWatchProgressDeleteKey> {
        val prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val key = watchProgressDeletePreferenceKey(userId, localProfileId)
        return prefs.getStringSet(key, emptySet()).orEmpty()
            .mapNotNull(NuvioWatchProgressDeleteKey::deserialize)
    }

    private fun pendingLibraryDeletes(
        context: Context,
        userId: String,
        localProfileId: String,
    ): List<PendingDelete> {
        val prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val scopedKey = libraryDeletePreferenceKey(userId, localProfileId)
        val legacyDeletes = prefs.getStringSet(PENDING_LIBRARY_DELETES, null)
        if (legacyDeletes != null) {
            val scopedDeletes = prefs.getStringSet(scopedKey, emptySet()).orEmpty()
            check(
                prefs.edit()
                    .putStringSet(scopedKey, scopedDeletes + legacyDeletes)
                    .remove(PENDING_LIBRARY_DELETES)
                    .commit(),
            ) { "Could not migrate the pending Nuvio library deletions" }
        }
        return prefs.getStringSet(scopedKey, emptySet()).orEmpty().mapNotNull { value ->
            val separator = value.indexOf(':')
            if (separator <= 0 || separator == value.lastIndex) return@mapNotNull null
            val contentType = value.substring(0, separator)
            val contentId = value.substring(separator + 1)
            PendingDelete(value, contentType, contentId)
        }
    }

    private fun clearLibraryDeletes(
        context: Context,
        deletes: Collection<PendingDelete>,
        userId: String,
        localProfileId: String,
    ) {
        if (deletes.isEmpty()) return
        val prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val key = libraryDeletePreferenceKey(userId, localProfileId)
        val pending = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        deletes.forEach { pending.remove(it.serialized) }
        check(prefs.edit().putStringSet(key, pending).commit()) {
            "Could not clear confirmed Nuvio library deletions"
        }
    }

    /**
     * Pulling before pending deletions are accepted by Nuvio can resurrect a
     * locally removed item. Keep every automatic pull behind this gate.
     */
    suspend fun pullAfterPendingLibraryDeletes(
        context: Context,
        @Suppress("UNUSED_PARAMETER") accessToken: String,
    ): Result<NuvioPullResult> = runCatching {
        syncNowDetailed(context).getOrThrow().pull
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}

internal fun syncRequestWorkPolicy(progressOnly: Boolean): ExistingWorkPolicy =
    if (progressOnly) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE


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