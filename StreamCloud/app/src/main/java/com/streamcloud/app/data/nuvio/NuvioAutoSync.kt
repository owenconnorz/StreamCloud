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
            val push = service.syncAll(accessToken)
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
}