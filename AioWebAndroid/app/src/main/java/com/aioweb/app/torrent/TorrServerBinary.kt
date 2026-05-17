package com.aioweb.app.torrent

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

/**
 * Manages the TorrServer binary lifecycle.
 *
 * The binary is bundled as `jniLibs/<abi>/libtorrserver.so` and extracted by the
 * package manager to `nativeLibraryDir` at install time.
 *
 * Android 10+ applies strict linker-namespace rules to .so files executed directly
 * from nativeLibraryDir, which prevents symbol version resolution and causes
 * "version: inaccessible or not found" + exit=127.
 *
 * Fix: copy the binary to the app's private files directory (no .so extension)
 * before executing — that path is treated as a plain executable, not a shared
 * library, and gets the default unrestricted linker namespace.
 *
 * Adapted from NuvioTV (https://github.com/NuvioMedia/NuvioTV).
 */
class TorrServerBinary(private val context: Context) {

    companion object {
        private const val TAG = "TorrServerBinary"
        const val PORT = 8091
        private const val STARTUP_TIMEOUT_MS = 25_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 300L
        private const val BINARY_NAME = "torrserver"      // no .so — avoids namespace issue
    }

    private var process: Process? = null

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    val baseUrl: String get() = "http://127.0.0.1:$PORT"

    /** Installed location — has .so extension, subject to namespace restrictions. */
    private val installedBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libtorrserver.so")

    /** Executable copy in private files dir — no .so, unrestricted namespace. */
    private val executableBinary: File
        get() = File(context.filesDir, BINARY_NAME)

    private val configDir: File
        get() = File(context.filesDir, "torrserver_data").also { it.mkdirs() }

    val isBinaryAvailable: Boolean get() = installedBinary.exists()

    fun isRunning(): Boolean {
        return try {
            val req = Request.Builder().url("$baseUrl/echo").build()
            healthClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ensure a fresh copy of the binary exists in filesDir.
     * Re-copies whenever the installed binary is newer or differs in size.
     */
    private fun prepareExecutable(): File {
        val src = installedBinary
        val dst = executableBinary

        val needsCopy = !dst.exists()
            || dst.length() != src.length()
            || dst.lastModified() < src.lastModified()

        if (needsCopy) {
            Log.d(TAG, "Copying binary ${src.length()} bytes → ${dst.absolutePath}")
            src.copyTo(dst, overwrite = true)
        }

        if (!dst.canExecute()) {
            dst.setExecutable(true, false)
        }

        return dst
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning()) {
            Log.d(TAG, "TorrServer already running")
            return@withContext
        }

        killOrphanedProcess()

        if (!isBinaryAvailable) {
            throw TorrentException("TorrServer binary not found at ${installedBinary.absolutePath}")
        }

        val binaryFile = prepareExecutable()
        Log.d(TAG, "Starting TorrServer port=$PORT binary=${binaryFile.absolutePath}")

        val pb = ProcessBuilder(
            binaryFile.absolutePath,
            "--port", PORT.toString(),
            "--path", configDir.absolutePath,
        )
        pb.directory(configDir)
        pb.redirectErrorStream(true)

        // Go binaries on Android need HOME and TMPDIR; without them the runtime
        // may panic or exit immediately on some devices.
        pb.environment().apply {
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("PATH", "/system/bin:/system/xbin")
            put("TERM", "dumb")
        }

        process = pb.start()

        // Collect output for diagnostics — captures what TorrServer prints before dying.
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

        // Wait for healthy response; detect early crash.
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                Log.d(TAG, "TorrServer started successfully")
                return@withContext
            }
            if (!isProcessAlive(process)) {
                val code = runCatching { process?.exitValue() }.getOrNull() ?: -1
                Thread.sleep(200)
                val output = outputLines.take(10).joinToString(" | ").take(500).ifEmpty { "(no output)" }
                process = null
                throw TorrentException("TorrServer died (exit=$code) $output")
            }
            delay(HEALTH_CHECK_INTERVAL_MS)
        }

        stop()
        val output = outputLines.take(10).joinToString(" | ").take(500).ifEmpty { "(no output)" }
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
