package com.streamcloud.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.streamcloud.app.data.util.ThumbnailCache

/**
 * Composable for music artwork / thumbnails.
 *
 * Uses the shared [ThumbnailCache] image loader, so every image is backed
 * by the 200 MB persistent disk cache — no re-downloading the same artwork
 * between sessions or screen transitions.
 *
 * Features
 * ─────────
 * • URL is automatically upgraded to a higher-quality variant before loading
 * • Crossfade animation on load
 * • Rounded placeholder shown while loading or on error
 * • [cacheKey] lets callers share a single on-disk entry for the same album
 *   art served at different resolutions
 *
 * @param url         Source URL (YouTube Music, ytimg, etc.)
 * @param modifier    Applied to the outer container
 * @param size        Convenience square-size shorthand (overrides modifier dimensions)
 * @param shape       Clip shape — defaults to 8 dp rounded rectangle
 * @param contentScale Coil ContentScale, default Crop
 * @param cacheKey    Optional stable key; if omitted, derived automatically
 *                    via [ThumbnailCache.cacheKey]
 */
@Composable
fun MusicThumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    contentScale: ContentScale = ContentScale.Crop,
    cacheKey: String? = null,
) {
    val context = LocalContext.current
    val upgraded = ThumbnailCache.upgradeUrl(url)
    val key      = cacheKey ?: ThumbnailCache.cacheKey(url)

    val request = ImageRequest.Builder(context)
        .data(upgraded)
        .apply { if (key != null) memoryCacheKey(key).diskCacheKey(key) }
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .crossfade(300)
        .build()

    val finalModifier = if (size != null) modifier.size(size) else modifier

    if (url.isNullOrBlank()) {
        MusicThumbnailPlaceholder(finalModifier, shape)
    } else {
        SubcomposeAsyncImage(
            model = request,
            imageLoader = ThumbnailCache.loader(context),
            contentDescription = null,
            contentScale = contentScale,
            modifier = finalModifier.clip(shape),
            error = {
                MusicThumbnailPlaceholder(Modifier, shape)
            },
        )
    }
}

@Composable
private fun MusicThumbnailPlaceholder(modifier: Modifier, shape: Shape) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color(0xFF555555),
        )
    }
}
