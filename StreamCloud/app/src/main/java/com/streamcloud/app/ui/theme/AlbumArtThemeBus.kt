package com.streamcloud.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
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

/**
 * Publishes dynamic accent colors derived from the currently-playing track's album art.
 * Mirrors Metrolist's palette extraction approach:
 *
 *  [accent]          — vibrant / light-vibrant / dominant swatch (primary color)
 *  [accentSecondary] — muted / dark-muted swatch (secondary / container color)
 *  [hasArtwork]      — true while a track with artwork is playing; lets Theme.kt
 *                      decide whether to apply the music-derived scheme at all
 *
 * Both flows stay at their DEFAULT values when no track is playing so the rest of the
 * app doesn't lurch when paused.
 */
@UnstableApi
object AlbumArtThemeBus {

    /** Fallback accent when no artwork is available — Material violet, Metrolist-style. */
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

    /** Returns (vibrant, muted) pair or null on failure. */
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

                val palette = Palette.from(bitmap).generate()

                // Primary: vibrant → lightVibrant → dominant
                val vibrant = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.dominantSwatch
                    ?: return@runCatching null

                // Secondary: muted → darkMuted → darkVibrant (complementary, lower saturation)
                val muted = palette.mutedSwatch
                    ?: palette.darkMutedSwatch
                    ?: palette.darkVibrantSwatch
                    ?: vibrant  // fallback to vibrant if nothing else

                Pair(Color(vibrant.rgb), Color(muted.rgb))
            }.getOrNull()
        }
}
