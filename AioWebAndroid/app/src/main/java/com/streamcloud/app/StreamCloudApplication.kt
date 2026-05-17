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
        NewPipe.init(
            com.streamcloud.app.data.newpipe.NewPipeDownloader.instance,
            Localization.DEFAULT,           // en/US
            ContentCountry.DEFAULT,         // US
        )
        // CloudStream plugins call top-level `setKey/getKey` from MainActivityKt — wire
        // them to a SharedPreferences instance scoped to this process.
        com.lagradost.cloudstream3.installPrefs(this)

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
