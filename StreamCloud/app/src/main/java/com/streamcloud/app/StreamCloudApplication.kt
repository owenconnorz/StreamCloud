package com.streamcloud.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.streamcloud.app.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class StreamCloudApplication : Application(), ImageLoaderFactory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {



            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

    override fun onCreate() {
        super.onCreate()






        com.streamcloud.app.data.network.Net.init(cacheDir)

        NewPipe.init(
            com.streamcloud.app.data.newpipe.NewPipeDownloader.instance,
            Localization.DEFAULT,
            ContentCountry.DEFAULT,
        )


        com.lagradost.cloudstream3.installPrefs(this)
        com.lagradost.cloudstream3.extractors.registerAllExtractors()
        com.lagradost.cloudstream3.extractors.registerExtraExtractors()





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
