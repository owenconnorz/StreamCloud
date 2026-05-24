package com.lagradost.cloudstream3.mvvm

import android.util.Log
import kotlinx.coroutines.*

private const val TAG = "CloudStream"

fun logError(throwable: Throwable) {
    Log.e(TAG, throwable.message ?: "Unknown error", throwable)
}

fun <T> safe(work: () -> T): T? = try { work() } catch (e: Throwable) { logError(e); null }

suspend fun <T> safeAsync(work: suspend () -> T): T? =
    try { work() } catch (e: Throwable) { logError(e); null }

fun CoroutineScope.launchSafe(block: suspend CoroutineScope.() -> Unit): Job =
    launch { try { block() } catch (e: Throwable) { logError(e) } }
