package com.streamcloud.app.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal data class WearUpdateInfo(
    val tagName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val versionCode: Int,
)

internal class WearUpdateChecker(private val context: Context) {
    suspend fun fetchLatest(): WearUpdateInfo? = withContext(Dispatchers.IO) {
        val url =
            "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases?per_page=10"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "StreamCloud-Wear-Updater")
        }
        val responseBody = try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                error(
                    when (code) {
                        HttpURLConnection.HTTP_NOT_FOUND ->
                            "No StreamCloud releases are available yet."
                        HttpURLConnection.HTTP_FORBIDDEN ->
                            "GitHub is temporarily limiting update checks."
                        else -> "GitHub update check failed (HTTP $code)."
                    },
                )
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val releases = JSONArray(responseBody)
        val latestWearRelease = (0 until releases.length())
            .asSequence()
            .mapNotNull { releases.optJSONObject(it) }
            .firstOrNull { release ->
                !release.optBoolean("draft") &&
                    !release.optBoolean("prerelease") &&
                    (0 until release.optJSONArray("assets")?.length().orZero())
                        .asSequence()
                        .mapNotNull { release.optJSONArray("assets")?.optJSONObject(it) }
                        .any { it.optString("name").equals(WEAR_APK_NAME, ignoreCase = true) }
            } ?: return@withContext null

        val tagName = latestWearRelease.optString("tag_name")
        val releaseVersion = Regex("(\\d+)").find(tagName)?.value?.toIntOrNull() ?: 0
        if (releaseVersion <= BuildConfig.VERSION_CODE) return@withContext null

        val assets = latestWearRelease.optJSONArray("assets") ?: return@withContext null
        val asset = (0 until assets.length())
            .asSequence()
            .mapNotNull(assets::optJSONObject)
            .firstOrNull { it.optString("name").equals(WEAR_APK_NAME, ignoreCase = true) }
            ?: return@withContext null
        val downloadUrl = asset.optString("browser_download_url")
        if (downloadUrl.isBlank()) return@withContext null

        WearUpdateInfo(
            tagName = tagName,
            downloadUrl = downloadUrl,
            sizeBytes = asset.optLong("size"),
            versionCode = releaseVersion,
        )
    }

    suspend fun download(
        update: WearUpdateInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "updates/streamcloud-wear-${update.versionCode}.apk")
        target.parentFile?.mkdirs()
        target.delete()

        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
            setRequestProperty("User-Agent", "StreamCloud-Wear-Updater")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("APK download failed (HTTP $code).")
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: update.sizeBytes
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0L) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (total > 0L && target.length() != total) {
                error("The downloaded update was incomplete.")
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        if (!target.isFile || target.length() == 0L) {
            target.delete()
            error("The downloaded update was incomplete.")
        }
        onProgress(1f)
        target
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchInstaller(apkFile: File) {
        check(canInstallPackages()) { "Allow StreamCloud to install updates first." }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        const val WEAR_APK_NAME = "StreamCloud-wear-release.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val DOWNLOAD_TIMEOUT_MS = 120_000
        const val BUFFER_SIZE = 64 * 1024
    }
}
