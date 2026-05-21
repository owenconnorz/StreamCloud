package com.streamcloud.app.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

class TorrServerBinary(private val context: Context) {

    companion object {
        private const val TAG = "TorrServerBinary"
        const val PORT = 8091
        private const val STARTUP_TIMEOUT_MS = 25_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 300L


        private val LINKER_PATH: String get() {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"
            return if (abi.contains("64")) "/system/bin/linker64"
            else "/system/bin/linker"
        }
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

        val linker = LINKER_PATH
        Log.d(TAG, "Starting TorrServer via $linker port=$PORT binary=${binaryFile.absolutePath}")




        val pb = ProcessBuilder(
            linker,
            binaryFile.absolutePath,
            "--port", PORT.toString(),
            "--path", configDir.absolutePath,
        )
        pb.directory(configDir)
        pb.redirectErrorStream(true)

        pb.environment().apply {
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("PATH", "/system/bin:/system/xbin")
            put("TERM", "dumb")
        }

        process = pb.start()

        val outputLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val proc = process!!
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "[ts] $line")
                    outputLines.add(line)
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                Log.d(TAG, "TorrServer started successfully on $baseUrl")
                return@withContext
            }
            if (!isProcessAlive(process)) {
                val code = runCatching { process?.exitValue() }.getOrNull() ?: -1
                Thread.sleep(200)
                val output = outputLines.take(10).joinToString(" | ").take(600).ifEmpty { "(no output)" }
                process = null
                throw TorrentException("TorrServer died (exit=$code) $output")
            }
            delay(HEALTH_CHECK_INTERVAL_MS)
        }

        stop()
        val output = outputLines.take(10).joinToString(" | ").take(600).ifEmpty { "(no output)" }
        throw TorrentException("TorrServer timeout (${STARTUP_TIMEOUT_MS / 1000}s) $output")
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
