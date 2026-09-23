package com.streamcloud.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.streamcloud.app.BuildConfig
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.player.PlayerSource
import com.streamcloud.app.player.WatchProgressKey
import kotlinx.coroutines.flow.first

/**
 * Opens Stremio catalog items in the same detail experience as TMDB/Nuvio
 * items whenever the addon metadata can be matched to TMDB. Addon-only IDs
 * still use the original Stremio page as a deliberate fallback.
 */
@Composable
fun StremioUnifiedDetailScreen(
    addonId: String,
    type: String,
    metaId: String,
    initialTitle: String,
    initialPoster: String?,
    onBack: () -> Unit,
    onPlay: (String, String, List<PlayerSource>, WatchProgressKey) -> Unit,
    onDirectStremioPlay: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val serviceLocator = remember { ServiceLocator.get(context.applicationContext) }
    var resolved by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var resolving by remember { mutableStateOf(true) }

    LaunchedEffect(addonId, type, metaId, initialTitle) {
        resolving = true
        resolved = runCatching {
            val isTv = type.equals("series", ignoreCase = true) ||
                type.equals("tv", ignoreCase = true) ||
                type.equals("show", ignoreCase = true)
            val requestedType = if (isTv) "tv" else "movie"
            val addon = serviceLocator.stremio.addons.first().firstOrNull {
                it.id == addonId || it.name == addonId
            }
            val meta = addon?.let {
                serviceLocator.stremio.fetchMeta(it, type, metaId)
            }
            val candidateId = meta?.id?.takeIf { it.isNotBlank() } ?: metaId
            val title = meta?.name?.takeIf { it.isNotBlank() } ?: initialTitle
            val apiKey = BuildConfig.TMDB_API_KEY
            if (apiKey.isBlank()) return@runCatching null

            val match: TmdbMovie? = if (
                candidateId.startsWith("tt", ignoreCase = true)
            ) {
                val result = serviceLocator.tmdb.find(
                    externalId = candidateId,
                    apiKey = apiKey,
                )
                if (requestedType == "tv") result.tvResults.firstOrNull()
                else result.movieResults.firstOrNull()
            } else {
                if (title.isBlank()) null
                else if (requestedType == "tv") {
                    serviceLocator.tmdb.searchTv(apiKey, title).results.firstOrNull()
                } else {
                    serviceLocator.tmdb.search(apiKey, title).results.firstOrNull()
                }
            }
            match?.id?.takeIf { it > 0 }?.let { it to requestedType }
        }.getOrNull()
        resolving = false
    }

    val match = resolved
    when {
        match != null -> {
            MovieDetailScreen(
                movieId = match.first,
                mediaType = match.second,
                onBack = onBack,
                onPlay = onPlay,
                onOpenCsPluginForMovie = { _, _ -> },
                onMovieClick = { onBack() },
                onTvClick = { onBack() },
            )
        }
        resolving -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        else -> {
            StremioDetailScreen(
                addonId = addonId,
                type = type,
                metaId = metaId,
                initialTitle = initialTitle,
                initialPoster = initialPoster,
                onBack = onBack,
                onPlay = onDirectStremioPlay,
            )
        }
    }
}