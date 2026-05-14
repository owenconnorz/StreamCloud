package com.aioweb.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.aioweb.app.audio.MusicController
import com.aioweb.app.audio.PlaybackBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Publishes a "dynamic" accent [Color] derived from the currently playing
 * track's album art (Palette → vibrant swatch fallbacks). Subscribed to by
 * [AioWebTheme] so the app's primary color follows the music in real time —
 * Metrolist / SimpMusic parity.
 *
 * The flow stays at [DEFAULT] when no track is playing, so the rest of the
 * app's colors don't lurch when paused.
 */
@UnstableApi
object AlbumArtThemeBus {
    /** The app's "no music playing" fallback accent — matches [Violet]. */
    val DEFAULT = Color(0xFF7C5CFF)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _accent = MutableStateFlow(DEFAULT)
    val accent: StateFlow<Color> = _accent.asStateFlow()

    @Volatile private var attached = false

    /**
     * Wires this bus to [PlaybackBus]. Each time the playing track changes we
     * load its artwork and run Palette to compute a vibrant accent. Idempotent.
     */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext

        scope.launch {
            // Make sure the underlying MusicController is alive so PlaybackBus
            // actually emits — otherwise we'd just sit on DEFAULT forever.
            runCatching { PlaybackBus.ensureAttached(app) }

            PlaybackBus.nowPlayingMediaId.collect { _ ->
                val artwork = withContext(Dispatchers.Main) {
                    runCatching {
                        MusicController.get(app)
                            .currentMediaItem?.mediaMetadata?.artworkUri?.toString()
                    }.getOrNull()
                }
                _accent.value = computeAccent(app, artwork) ?: DEFAULT
            }
        }
    }

    private suspend fun computeAccent(context: Context, url: String?): Color? {
        if (url.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(160)
                    .build()
                val res = ImageLoader(context).execute(req) as? SuccessResult ?: return@runCatching null
                val bitmap: Bitmap = (res.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
                val palette = Palette.from(bitmap).generate()
                val swatch = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.dominantSwatch
                    ?: palette.mutedSwatch
                swatch?.let { Color(it.rgb) }
            }.getOrNull()
        }
    }
}
