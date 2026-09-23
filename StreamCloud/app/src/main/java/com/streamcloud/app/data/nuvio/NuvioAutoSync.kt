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
            ExistingWorkPolicy.KEEP,
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

        return runCatching {
            val service = NuvioAccountService.get(applicationContext)
            service.syncPull(token)
            service.syncAll(token)
            Result.success()
        }.getOrElse { error ->
            Log.w("NuvioAutoSync", "automatic sync failed: ${error.message}")
            Result.retry()
        }
    }
}