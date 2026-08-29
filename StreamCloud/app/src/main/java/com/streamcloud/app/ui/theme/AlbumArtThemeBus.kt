package com.streamcloud.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.media3.common.util.UnstableApi
import androidx.palette.graphics.Palette
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.streamcloud.app.data.util.ThumbnailCache
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.audio.PlaybackBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@UnstableApi
object AlbumArtThemeBus {

    val DEFAULT           = Color(0xFF7C5CFF)
    val DEFAULT_SECONDARY = Color(0xFF4A3A99)
    private val DEFAULT_MINI_BG = Color(0xFF12101E)
    private val DEFAULT_NAV_BG  = Color(0xFF1C1826)
    private val DEFAULT_BG_TINT = Color(0xFF09080F)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _accent          = MutableStateFlow(DEFAULT)
    private val _accentSecondary = MutableStateFlow(DEFAULT_SECONDARY)
    private val _hasArtwork      = MutableStateFlow(false)
    private val _miniPlayerBg    = MutableStateFlow(DEFAULT_MINI_BG)
    private val _navPillBg       = MutableStateFlow(DEFAULT_NAV_BG)
    private val _vibrant         = MutableStateFlow(DEFAULT)
    private val _bgTint          = MutableStateFlow(DEFAULT_BG_TINT)

    val accent:          StateFlow<Color>   = _accent.asStateFlow()
    val accentSecondary: StateFlow<Color>   = _accentSecondary.asStateFlow()
    val hasArtwork:      StateFlow<Boolean> = _hasArtwork.asStateFlow()
    /** Very dark tinted background for the miniplayer card (L≈0.12). */
    val miniPlayerBg:    StateFlow<Color>   = _miniPlayerBg.asStateFlow()
    /** Slightly lighter/different tint for the floating nav pill (L≈0.22). */
    val navPillBg:       StateFlow<Color>   = _navPillBg.asStateFlow()
    /** Raw vibrant accent — use for text highlights and selected-tab indicators. */
    val vibrant:         StateFlow<Color>   = _vibrant.asStateFlow()
    /** Barely-perceptible hue tint for full-screen backgrounds (L≈0.06). */
    val bgTint:          StateFlow<Color>   = _bgTint.asStateFlow()

    @Volatile private var attached = false

    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext

        scope.launch {
            runCatching { PlaybackBus.ensureAttached(app) }

            PlaybackBus.nowPlayingMediaId.collectLatest { _ ->
                val artworkUrl = withContext(Dispatchers.Main) {
                    runCatching {
                        MusicController.get(app)
                            .currentMediaItem?.mediaMetadata?.artworkUri?.toString()
                    }.getOrNull()
                }

                if (artworkUrl.isNullOrBlank()) {
                    resetToDefaults()
                    return@collect
                }

                val result = computePalette(app, artworkUrl)
                if (result != null) {
                    _accent.value          = result.accent
                    _accentSecondary.value = result.accentSecondary
                    _miniPlayerBg.value    = result.miniPlayerBg
                    _navPillBg.value       = result.navPillBg
                    _vibrant.value         = result.vibrant
                    _bgTint.value          = result.bgTint
                    _hasArtwork.value      = true
                } else {
                    resetToDefaults()
                }
            }
        }
    }

    private fun resetToDefaults() {
        _hasArtwork.value      = false
        _accent.value          = DEFAULT
        _accentSecondary.value = DEFAULT_SECONDARY
        _miniPlayerBg.value    = DEFAULT_MINI_BG
        _navPillBg.value       = DEFAULT_NAV_BG
        _vibrant.value         = DEFAULT
        _bgTint.value          = DEFAULT_BG_TINT
    }

    private data class PaletteResult(
        val accent: Color,
        val accentSecondary: Color,
        val miniPlayerBg: Color,
        val navPillBg: Color,
        val vibrant: Color,
        val bgTint: Color,
    )

    /**
     * A real colour quantized from the artwork. Keep this separate from the
     * Android Palette classes so the selection rules remain unit-testable.
     */
    internal data class AlbumArtColorSample(
        val hue: Float,
        val saturation: Float,
        val lightness: Float,
        val population: Int,
    )

    /**
     * Select a meaningful colour from the artwork without inventing a hue.
     *
     * Palette's neutral swatches use hue 0, so increasing their saturation
     * makes black-and-white artwork incorrectly become red. Prefer a
     * sufficiently represented chromatic swatch, otherwise keep the most
     * populous neutral swatch genuinely neutral.
     */
    internal fun selectAlbumArtColorSample(
        samples: List<AlbumArtColorSample>,
    ): AlbumArtColorSample? {
        val usable = samples.filter {
            it.population > 0 &&
                it.lightness in 0.06f..0.94f &&
                it.saturation in 0f..1f
        }
        if (usable.isEmpty()) return null

        val totalPopulation = usable.sumOf { it.population }.coerceAtLeast(1)
        val meaningfulPopulation = maxOf(8, (totalPopulation * 0.025f).roundToInt())
        val chromatic = usable.filter {
            it.saturation >= 0.18f && it.population >= meaningfulPopulation
        }
        val candidates = chromatic.ifEmpty { usable }

        return candidates.maxByOrNull {
            it.population.toFloat() * (1f + it.saturation * 0.35f)
        }
    }

    /** Preserve the artwork's hue and saturation; only adjust lightness for legibility. */
    internal fun themeHslForAlbumArt(sample: AlbumArtColorSample): FloatArray =
        floatArrayOf(
            sample.hue,
            (sample.saturation * 1.08f).coerceAtMost(0.86f),
            sample.lightness.coerceIn(0.46f, 0.68f),
        )

    private suspend fun computePalette(context: Context, url: String): PaletteResult? =
        withContext(Dispatchers.IO) {
            try {
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(200)
                    .build()
                val res = ThumbnailCache.loader(context).execute(req) as? SuccessResult
                    ?: return@runCatching null
                val bitmap: Bitmap = (res.drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching null

                val palette = Palette.from(bitmap).maximumColorCount(32).generate()
                val primary = selectAlbumArtColorSample(
                    palette.swatches.map { swatch ->
                        val hsl = swatch.hsl
                        AlbumArtColorSample(
                            hue = hsl[0],
                            saturation = hsl[1],
                            lightness = hsl[2],
                            population = swatch.population,
                        )
                    },
                ) ?: return@runCatching null

                // Keep the actual artwork hue and saturation. In particular,
                // neutral covers must stay neutral instead of becoming hue 0/red.
                val hslV = themeHslForAlbumArt(primary)
                val accent = Color(ColorUtils.HSLToColor(hslV))

                // ── Secondary (legacy compat) ──────────────────────────────────────
                val hslSec = hslV.copyOf()
                hslSec[1] = hslV[1] * 0.65f
                hslSec[2] = (hslV[2] - 0.28f).coerceAtLeast(0.18f)
                val secondary = Color(ColorUtils.HSLToColor(hslSec))

                // ── Miniplayer background ─────────────────────────────────────────
                // Same hue as accent, very dark (L≈0.12), moderate saturation.
                // Mirrors Metrolist's dark-muted/dominant-dark treatment.
                val hslMini = hslV.copyOf()
                hslMini[1] = hslV[1] * 0.55f
                hslMini[2] = 0.12f
                val miniPlayerBg = Color(ColorUtils.HSLToColor(hslMini))

                // ── Nav-pill background ───────────────────────────────────────────
                // Same hue but distinctly lighter (L≈0.22) and less saturated than
                // the miniplayer — gives a clearly different colour to each element.
                val hslNav = hslV.copyOf()
                hslNav[1] = hslV[1] * 0.32f
                hslNav[2] = 0.22f
                val navPillBg = Color(ColorUtils.HSLToColor(hslNav))

                // ── Screen background tint ────────────────────────────────────────
                // Barely perceptible — same hue, very low saturation, almost-black.
                val hslBg = hslV.copyOf()
                hslBg[1] = 0.08f
                hslBg[2] = 0.06f
                val bgTint = Color(ColorUtils.HSLToColor(hslBg))

                PaletteResult(accent, secondary, miniPlayerBg, navPillBg, accent, bgTint)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
}
