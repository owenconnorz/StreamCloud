package com.aioweb.app.data.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aioweb.app.BuildConfig
import com.aioweb.app.data.network.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub release shape — we only need a few fields. The CI workflow tags releases
 * `build-<run_number>` and uploads `*.apk` files as assets.
 */
@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

data class UpdateInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val publishedAt: String?,
    val apkUrl: String,
    val sizeBytes: Long,
    val isNewerThanInstalled: Boolean,
)

class UpdateChecker(private val context: Context) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Returns the latest release if newer than the running build, or any release if [includeOlder]. */
    suspend fun fetchLatest(includeOlder: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val owner = BuildConfig.GITHUB_OWNER
        val repo = BuildConfig.GITHUB_REPO
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases?per_page=10")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "StreamCloud-Updater")
            .build()
        val body = http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> resp.body?.string().orEmpty()
                404 -> error(
                    "GitHub repo `$owner/$repo` has no releases yet, or is private.\n" +
                            "First push to GitHub triggers a build that publishes the first release."
                )
                403 -> error("GitHub API rate limit hit. Try again in a few minutes.")
                else -> error("GitHub API HTTP ${resp.code} ${resp.message}")
            }
        }
        val releases = Net.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(GhRelease.serializer()), body,
        )
        if (releases.isEmpty()) return@withContext null
        val latest = releases
            .firstOrNull { rel -> rel.assets.any { it.name.endsWith(".apk", ignoreCase = true) } }
            ?: error("Latest release has no APK assets attached. Re-run the GitHub Actions build.")

        val apk = pickBestApk(latest.assets) ?: return@withContext null

        val tagBuildNumber = Regex("(\\d+)").find(latest.tagName)?.value?.toIntOrNull() ?: 0
        val isNewer = tagBuildNumber > BuildConfig.VERSION_CODE
        if (!isNewer && !includeOlder) return@withContext null

        UpdateInfo(
            tagName = latest.tagName,
            title = latest.name ?: latest.tagName,
            notes = latest.body.orEmpty().take(2000),
            htmlUrl = latest.htmlUrl,
            publishedAt = latest.publishedAt,
            apkUrl = apk.downloadUrl,
            sizeBytes = apk.size,
            isNewerThanInstalled = isNewer,
        )
    }

    /** Prefer signed release > unsigned release > debug, matching our CI output names. */
    private fun pickBestApk(assets: List<GhAsset>): GhAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { it.name.contains("release-signed", true) }
            ?: apks.firstOrNull { it.name.contains("release", true) && !it.name.contains("UNSIGNED", true) }
            ?: apks.firstOrNull { it.name.contains("debug", true) }
            ?: apks.firstOrNull()
    }

    /**
     * Downloads [info.apkUrl] to app cache and returns the local file.
     * [onProgress] reports 0..1 inclusive.
     */
    suspend fun downloadApk(info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = File(context.cacheDir, "update-${info.tagName}.apk").also {
                if (it.exists()) it.delete()
            }
            val req = Request.Builder().url(info.apkUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download HTTP ${resp.code}")
                val body = resp.body ?: error("Empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf); if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
            target
        }

    /**
     * Prompts the user to install the APK using Android's built-in installer.
     * Caller must hold the `REQUEST_INSTALL_PACKAGES` permission (declared in manifest).
     */
    fun launchInstaller(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
