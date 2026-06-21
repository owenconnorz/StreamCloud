package com.streamcloud.app

import android.app.Application
import android.os.Build
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

class StreamCloudApplication : Application(), ImageLoaderFactory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun newImageLoader(): ImageLoader = ThumbnailCache.loader(this)

    override fun onCreate() {
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

        NewPipe.init(
            com.streamcloud.app.data.newpipe.NewPipeDownloader.instance,
            Localization.DEFAULT,
            ContentCountry.DEFAULT,
        )


        runCatching { com.lagradost.cloudstream3.installPrefs(this) }
        runCatching { com.lagradost.cloudstream3.extractors.registerAllExtractors() }
        runCatching { com.lagradost.cloudstream3.extractors.registerExtraExtractors() }

        scope.launch {
            val cookie = ServiceLocator.get(this@StreamCloudApplication).settings.spotifyCookie.first()
            if (cookie.isNotBlank()) {
                com.streamcloud.app.data.spotify.SpotifyCanvasRepository.setSpotifyCookie(cookie)
            }
        }





        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.streamcloud.app.data.downloads.YtMusicDownloadUtil.downloadManager(this@StreamCloudApplication)
        }

        // Pre-fetch YouTube player JS + extract nsig function so n-param descrambling
        // is ready before the first track plays (avoids extra latency on first playback).
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
}
