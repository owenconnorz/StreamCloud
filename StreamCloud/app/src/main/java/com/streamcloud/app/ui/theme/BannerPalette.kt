package com.streamcloud.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.streamcloud.app.data.util.ThumbnailCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Immutable
data class BannerPalette(
    val accent: Color,
    val onAccent: Color,
    val backgroundTint: Color,
    val surfaceTint: Color,
    val accentContainer: Color,
)

@Composable
fun rememberBannerPalette(
    imageUrl: String?,
    fallbackAccent: Color,
    fallbackOnAccent: Color,
    fallbackBackground: Color,
    fallbackSurface: Color,
): BannerPalette {
    val context = LocalContext.current
    var extracted by remember(imageUrl) { mutableStateOf<BannerPalette?>(null) }

    LaunchedEffect(context, imageUrl) {
        extracted = if (imageUrl.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { loadBannerPalette(context, imageUrl) }
        }
    }

    return extracted ?: BannerPalette(
        accent = fallbackAccent,
        onAccent = fallbackOnAccent,
        backgroundTint = fallbackBackground,
        surfaceTint = fallbackSurface,
        accentContainer = fallbackAccent.copy(alpha = 0.18f),
    )
}

private suspend fun loadBannerPalette(context: Context, imageUrl: String): BannerPalette? {
    return try {
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false)
            .size(256)
            .build()
        val result = ThumbnailCache.loader(context).execute(request) as? SuccessResult
            ?: return null
        val bitmap: Bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        paletteFrom(bitmap)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun paletteFrom(bitmap: Bitmap): BannerPalette? {
    val swatches = Palette.from(bitmap).maximumColorCount(32).generate().swatches
    if (swatches.isEmpty()) return null

    val usable = swatches.filter { swatch -> swatch.hsl[2] in 0.06f..0.94f }
    if (usable.isEmpty()) return null

    val totalPopulation = usable.sumOf { it.population }.coerceAtLeast(1)
    val meaningfulPopulation = maxOf(8, (totalPopulation * 0.025f).roundToInt())
    val chromatic = usable.filter {
        it.hsl[1] >= 0.18f && it.population >= meaningfulPopulation
    }
    // Prefer a well-represented chromatic colour, but keep monochrome banners neutral.
    val swatch = (chromatic.ifEmpty { usable }).maxByOrNull {
        it.population.toFloat() * (1f + it.hsl[1] * 0.35f)
    } ?: return null

    val sourceHsl = swatch.hsl
    val accentHsl = floatArrayOf(
        sourceHsl[0],
        (sourceHsl[1] * 1.08f).coerceAtMost(0.86f),
        sourceHsl[2].coerceIn(0.46f, 0.68f),
    )
    val accentInt = ColorUtils.HSLToColor(accentHsl)
    val onAccentInt = if (
        ColorUtils.calculateContrast(android.graphics.Color.WHITE, accentInt) >=
        ColorUtils.calculateContrast(android.graphics.Color.BLACK, accentInt)
    ) {
        android.graphics.Color.WHITE
    } else {
        android.graphics.Color.BLACK
    }

    val backgroundHsl = floatArrayOf(sourceHsl[0], sourceHsl[1] * 0.18f, 0.045f)
    val surfaceHsl = floatArrayOf(sourceHsl[0], sourceHsl[1] * 0.28f, 0.12f)
    val accentContainerHsl = floatArrayOf(sourceHsl[0], sourceHsl[1] * 0.62f, 0.22f)

    return BannerPalette(
        accent = Color(accentInt),
        onAccent = Color(onAccentInt),
        backgroundTint = Color(ColorUtils.HSLToColor(backgroundHsl)),
        surfaceTint = Color(ColorUtils.HSLToColor(surfaceHsl)),
        accentContainer = Color(ColorUtils.HSLToColor(accentContainerHsl)),
    )
}