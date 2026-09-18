package com.streamcloud.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * Loads non-adult movie/video artwork and tries [fallbackUrl] once if the
 * primary image is missing or fails to load.
 *
 * Sizing, clipping and aspect ratio intentionally remain the caller's
 * responsibility so the same component can be used for posters and backdrops.
 */
@Composable
fun MovieArtwork(
    primaryUrl: String?,
    fallbackUrl: String? = null,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val urls = remember(primaryUrl, fallbackUrl) {
        movieArtworkUrls(primaryUrl, fallbackUrl)
    }
    var urlIndex by remember(urls) { mutableStateOf(0) }
    val currentUrl = urls.getOrNull(urlIndex)

    if (currentUrl == null) {
        MovieArtworkUnavailable(
            contentDescription = contentDescription,
            modifier = modifier,
        )
        return
    }

    SubcomposeAsyncImage(
        model = currentUrl,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clearAndSetSemantics {
                        this.contentDescription = contentDescription
                        stateDescription = "Loading image"
                    },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        error = {
            MovieArtworkUnavailable(
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
            )
        },
        onError = {
            if (urls.getOrNull(urlIndex) == currentUrl && urlIndex + 1 < urls.size) {
                urlIndex += 1
            }
        },
    )
}

internal fun movieArtworkUrls(primaryUrl: String?, fallbackUrl: String?): List<String> =
    listOfNotNull(
        primaryUrl?.trim()?.takeIf(String::isNotEmpty),
        fallbackUrl?.trim()?.takeIf(String::isNotEmpty),
    ).distinct()

@Composable
private fun MovieArtworkUnavailable(
    contentDescription: String,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clearAndSetSemantics {
                this.contentDescription = "$contentDescription. Image unavailable"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Image unavailable",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}