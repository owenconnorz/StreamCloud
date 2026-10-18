package com.streamcloud.app.data.plugins

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.extractors.registerAllExtractors
import com.lagradost.cloudstream3.extractors.registerExtraExtractors
import com.lagradost.cloudstream3.installPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Initializes CloudStream's global extractor registry away from the UI thread.
 * PluginRuntime waits for this before loading or invoking a plugin, so startup
 * work can proceed without racing extractor registration.
 */
object CloudStreamExtractorBootstrap {
    private const val TAG = "CloudStreamBootstrap"

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var started = false

    fun start(context: Context) {
        synchronized(lock) {
            if (started) return
            started = true
            val appContext = context.applicationContext
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        runCatching { installPrefs(appContext) }
                            .onFailure { Log.e(TAG, "installPrefs failed", it) }
                        runCatching { registerAllExtractors() }
                            .onFailure { Log.e(TAG, "registerAllExtractors failed", it) }
                        runCatching { registerExtraExtractors() }
                            .onFailure { Log.e(TAG, "registerExtraExtractors failed", it) }
                    }
                } finally {
                    ready.complete(Unit)
                }
            }
        }
    }

    suspend fun awaitReady(context: Context) {
        start(context)
        ready.await()
    }
}
