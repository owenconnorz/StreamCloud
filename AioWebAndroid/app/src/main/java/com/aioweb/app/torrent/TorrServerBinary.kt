package com.aioweb.app.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages the TorrServer binary lifecycle.
 *
 * The binary is bundled in `jniLibs/<abi>/libtorrserver.so` and installed
 * to `nativeLibraryDir` by the Android package manager at install time.
 * Because it lives there as a .so file Android marks it executable automatically.
 *
 * Adapted from NuvioTV (https://github.com/NuvioMedia/NuvioTV).
 */
class TorrServerBinary(private val context: Context) {

    companion object {
        private const val TAG = "TorrServerBinary"
        const val PORT = 8091
        private const val STARTUP_TIMEOUT_MS = 20_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 300L
    }

    private var process: Process? = null

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    val baseUrl: String get() = "http://127.0.0.1:$PORT"

    private val binaryFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libtorrserver.so")

    private val configDir: File
        get() = File(context.filesDir, "torrserver").also { it.mkdirs() }

    val isBinaryAvailable: Boolean get() = binaryFile.exists()

    fun isRunning(): Boolean {
        return try {
            val req = Request.Builder().url("$baseUrl/echo").build()
            healthClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning()) {
            Log.d(TAG, "TorrServer already running")
            return@withContext
        }

        killOrphanedProcess()

        if (!isBinaryAvailable) {
            throw TorrentException("TorrServer binary not found at ${binaryFile.absolutePath}")
        }

        if (!binaryFile.canExecute()) {
            binaryFile.setExecutable(true)
        }

        Log.d(TAG, "Starting TorrServer on port $PORT from ${binaryFile.absolutePath}")
        val pb = ProcessBuilder(
            binaryFile.absolutePath,
            "--port", PORT.toString(),
            "--path", configDir.absolutePath,
        )
        pb.directory(configDir)
        pb.redirectErrorStream(true)
        process = pb.start()

        // Forward stdout/stderr to logcat in a daemon thread.
        val proc = process!!
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { Log.d(TAG, "[ts] $it") }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        // Wait for healthy response, detect early crash.
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                Log.d(TAG, "TorrServer started successfully")
                return@withContext
            }
            if (!isProcessAlive(process)) {
                val code = runCatching { process?.exitValue() }.getOrNull() ?: -1
                process = null
                throw TorrentException("TorrServer process died on launch (exit=$code)")
            }
            delay(HEALTH_CHECK_INTERVAL_MS)
        }

        stop()
        throw TorrentException("TorrServer did not respond within ${STARTUP_TIMEOUT_MS / 1000}s")
    }

    private fun killOrphanedProcess() {
        try {
            Request.Builder().url("$baseUrl/shutdown").build()
                .let { healthClient.newCall(it).execute().close() }
            Thread.sleep(1_000)
            Log.d(TAG, "Shut down orphaned TorrServer instance")
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            Request.Builder().url("$baseUrl/shutdown").build()
                .let { healthClient.newCall(it).execute().close() }
        } catch (_: Exception) {}

        process?.let { proc ->
            try {
                Thread.sleep(2_000)
                if (isProcessAlive(proc)) proc.destroyForcibly() else Unit
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
        }
        process = null
        Log.d(TAG, "TorrServer stopped")
    }

    private fun isProcessAlive(proc: Process?): Boolean {
        if (proc == null) return false
        return try { proc.exitValue(); false }
        catch (_: IllegalThreadStateException) { true }
        catch (_: Exception) { false }
    }
}
