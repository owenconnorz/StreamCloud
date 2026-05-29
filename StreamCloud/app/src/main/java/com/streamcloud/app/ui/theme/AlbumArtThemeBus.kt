package com.streamcloud.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.media3.common.util.UnstableApi
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.audio.PlaybackBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
object AlbumArtThemeBus {

    val DEFAULT           = Color(0xFF7C5CFF)
    val DEFAULT_SECONDARY = Color(0xFF4A3A99)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _accent          = MutableStateFlow(DEFAULT)
    private val _accentSecondary = MutableStateFlow(DEFAULT_SECONDARY)
    private val _hasArtwork      = MutableStateFlow(false)

    val accent:          StateFlow<Color>   = _accent.asStateFlow()
    val accentSecondary: StateFlow<Color>   = _accentSecondary.asStateFlow()
    val hasArtwork:      StateFlow<Boolean> = _hasArtwork.asStateFlow()

    @Volatile private var attached = false

    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext

        scope.launch {
            runCatching { PlaybackBus.ensureAttached(app) }

            PlaybackBus.nowPlayingMediaId.collect { _ ->
                val artworkUrl = withContext(Dispatchers.Main) {
                    runCatching {
                        MusicController.get(app)
                            .currentMediaItem?.mediaMetadata?.artworkUri?.toString()
                    }.getOrNull()
                }

                if (artworkUrl.isNullOrBlank()) {
                    _hasArtwork.value = false
                    _accent.value          = DEFAULT
                    _accentSecondary.value = DEFAULT_SECONDARY
                    return@collect
                }

                val result = computePalette(app, artworkUrl)
                if (result != null) {
                    _accent.value          = result.first
                    _accentSecondary.value = result.second
                    _hasArtwork.value      = true
                } else {
                    _hasArtwork.value = false
                    _accent.value          = DEFAULT
                    _accentSecondary.value = DEFAULT_SECONDARY
                }
            }
        }
    }


    private suspend fun computePalette(context: Context, url: String): Pair<Color, Color>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(200)
                    .build()
                val res = ImageLoader(context).execute(req) as? SuccessResult ?: return@runCatching null
                val bitmap: Bitmap = (res.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null

                val palette = Palette.from(bitmap).maximumColorCount(24).generate()

                // Pick the most vibrant swatch available
                val primarySwatch = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.darkVibrantSwatch
                    ?: palette.dominantSwatch
                    ?: return@runCatching null

                // Boost the primary: push saturation up to at least 60%,
                // and lock lightness into the 55–72% range so it's always vivid on dark backgrounds
                val hslPrimary = primarySwatch.hsl.copyOf()
                hslPrimary[1] = hslPrimary[1].coerceAtLeast(0.60f)   // Saturation: min 60%
                hslPrimary[2] = hslPrimary[2].coerceIn(0.55f, 0.72f) // Lightness: 55–72%
                val accent = Color(ColorUtils.HSLToColor(hslPrimary))

                // Derive the secondary/container colour from the same hue:
                // darker (–28 lightness) and less saturated (×65%) for tinted backgrounds
                val hslSecondary = hslPrimary.copyOf()
                hslSecondary[1] = (hslPrimary[1] * 0.65f).coerceAtLeast(0.25f)
                hslSecondary[2] = (hslPrimary[2] - 0.28f).coerceAtLeast(0.18f)
                val secondary = Color(ColorUtils.HSLToColor(hslSecondary))

                Pair(accent, secondary)
            }.getOrNull()
        }
}
