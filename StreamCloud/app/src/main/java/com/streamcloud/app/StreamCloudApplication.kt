package com.streamcloud.app

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.streamcloud.app.data.util.ThumbnailCache
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.updates.PluginUpdateWorker
import com.streamcloud.app.data.updates.NewReleaseCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

class StreamCloudApplication : Application(), ImageLoaderFactory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun newImageLoader(): ImageLoader = ThumbnailCache.loader(this)

    override fun onCreate() {
        installCrashCapture()
        super.onCreate()

        com.streamcloud.app.data.network.Net.init(cacheDir)

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "plugin_update_check",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PluginUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build(),
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "new_release_check",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NewReleaseCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build(),
        )

        NewPipe.init(
            com.streamcloud.app.data.newpipe.NewPipeDownloader.instance,
            Localization.DEFAULT,
            ContentCountry.DEFAULT,
        )

        runCatching { com.lagradost.cloudstream3.installPrefs(this) }
            .onFailure { Log.e("StreamCloud", "installPrefs failed", it) }
        runCatching { com.lagradost.cloudstream3.extractors.registerAllExtractors() }
            .onFailure { Log.e("StreamCloud", "registerAllExtractors failed", it) }
        runCatching { com.lagradost.cloudstream3.extractors.registerExtraExtractors() }
            .onFailure { Log.e("StreamCloud", "registerExtraExtractors failed", it) }

        scope.launch {
            val cookie = ServiceLocator.get(this@StreamCloudApplication).settings.spotifyCookie.first()
            if (cookie.isNotBlank()) {
                com.streamcloud.app.data.spotify.SpotifyCanvasRepository.setSpotifyCookie(cookie)
            }
        }

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.streamcloud.app.data.downloads.YtMusicDownloadUtil.downloadManager(this@StreamCloudApplication)
        }

        scope.launch {
            com.streamcloud.app.data.ytmusic.YtNSigDescrambler.warmUp()
        }

        scope.launch {
            ServiceLocator.get(this@StreamCloudApplication).settings.ytMusicCookie
                .collectLatest { cookie ->
                    com.streamcloud.app.data.newpipe.NewPipeDownloader.instance.ytMusicCookie = cookie
                }
        }
    }

    private fun installCrashCapture() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val report = buildString {
                    append("Thread: ${thread.name}\n")
                    append("Android: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
                    append(sw.toString())
                }
                File(filesDir, CRASH_FILE).writeText(report)
                Log.e("StreamCloud", "CRASH CAPTURED:\n$report")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
