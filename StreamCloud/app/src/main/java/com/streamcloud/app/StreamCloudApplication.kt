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
            // Animated GIF / WEBP support for Reddit feed cards (Adult tab).
            // ImageDecoderDecoder uses the platform animator on API 28+ (faster,
            // less RAM); GifDecoder is the legacy software fallback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

    override fun onCreate() {
        super.onCreate()
        // NewPipe Extractor needs a Downloader, a Localization and a ContentCountry.
        // Without all three, music search/stream extraction silently produces empty results
        // on some YouTube responses (PoToken / visitor_data flows).
        // Initialise the OkHttp HTTP disk cache before any Retrofit client is created.
        // TMDB returns Cache-Control: public, max-age=28800 (8 h) — subsequent launches
        // serve all home-page data from disk in milliseconds instead of hitting the network.
        com.streamcloud.app.data.network.Net.init(cacheDir)

        NewPipe.init(
            com.streamcloud.app.data.newpipe.NewPipeDownloader.instance,
            Localization.DEFAULT,           // en/US
            ContentCountry.DEFAULT,         // US
        )
        // CloudStream plugins call top-level `setKey/getKey` from MainActivityKt — wire
        // them to a SharedPreferences instance scoped to this process.
        com.lagradost.cloudstream3.installPrefs(this)

        // Eagerly initialise the Media3 DownloadManager so its in-memory `downloads`
        // StateFlow is populated from the SQLite index on every app start — not just
        // when a new download is enqueued. Without this, tick marks disappear after a
        // force-close because the lazy singleton is never initialised.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.streamcloud.app.data.downloads.YtMusicDownloadUtil.downloadManager(this@StreamCloudApplication)
        }

        // Mirror the persisted YT Music cookie into the NewPipe HTTP shim so authenticated
        // requests Just Work after process restart. The flow keeps the in-memory copy in
        // sync with future logins/logouts.
        scope.launch {
            ServiceLocator.get(this@StreamCloudApplication).settings.ytMusicCookie
                .collectLatest { cookie ->
                    com.streamcloud.app.data.newpipe.NewPipeDownloader.instance.ytMusicCookie = cookie
                }
        }
    }
}
