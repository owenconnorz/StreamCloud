@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.streamcloud.app.ui.components

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.api.TmdbVideo
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val TAG = "MovieTrailerPreview"

private data class ResolvedTrailer(
    val url: String,
    val userAgent: String?,
)

/**
 * Resolves an audio-enabled, looping TMDB trailer preview. Focused cards use a delay;
 * detail banners can use the already-loaded videos and start immediately.
 */
@Composable
internal fun TmdbTrailerPreview(
    movie: TmdbMovie,
    modifier: Modifier = Modifier,
    videos: List<TmdbVideo>? = null,
    startDelayMs: Long = 6_000L,
) {
    val context = LocalContext.current.applicationContext
    val services = remember(context) { ServiceLocator.get(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED)
    }
    var trailer by remember(movie.id, movie.title == null) { mutableStateOf<ResolvedTrailer?>(null) }
    var playbackFailed by remember(movie.id, movie.title == null) { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(movie.id, movie.title == null, isResumed, videos, startDelayMs) {
        trailer = null
        playbackFailed = false
        if (!isResumed) return@LaunchedEffect

        if (startDelayMs > 0L) delay(startDelayMs)
        try {
            val availableVideos = videos ?: if (movie.title != null) {
                services.tmdb.videos(movie.id, services.tmdbApiKey).results
            } else {
                services.tmdb.tvVideos(movie.id, services.tmdbApiKey).results
            }
            val video = availableVideos
                .filter {
                    it.site.equals("YouTube", ignoreCase = true) &&
                        (it.type.equals("Trailer", ignoreCase = true) ||
                            it.type.equals("Teaser", ignoreCase = true))
                }
                .minWithOrNull(
                    compareBy<TmdbVideo> {
                        if (it.type.equals("Trailer", ignoreCase = true)) 0 else 1
                    }.thenBy {
                        if (it.name.orEmpty().contains("official", ignoreCase = true)) 0 else 1
                    },
                )
                ?: return@LaunchedEffect

            val stream = YtPlayerUtils.resolveVideoStream(video.key)
            val url = stream.url?.takeIf { stream.isMusicVideo && it.isNotBlank() }
                ?: return@LaunchedEffect
            trailer = ResolvedTrailer(url = url, userAgent = stream.userAgent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Could not prepare trailer preview for TMDB item ${movie.id}", failure)
        }
    }

    val resolvedTrailer = trailer
    if (resolvedTrailer != null && !playbackFailed && isResumed) {
        TrailerPlayer(
            trailer = resolvedTrailer,
            modifier = modifier,
            onPlaybackError = { error ->
                Log.w(TAG, "Trailer preview playback failed for TMDB item ${movie.id}", error)
                playbackFailed = true
            },
        )
    }
}

@Composable
private fun TrailerPlayer(
    trailer: ResolvedTrailer,
    modifier: Modifier,
    onPlaybackError: (PlaybackException) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(trailer.url, trailer.userAgent) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                trailer.userAgent?.takeIf { it.isNotBlank() }
                    ?: "StreamCloud/1.0",
            )
            .setAllowCrossProtocolRedirects(true)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                volume = 1f
                repeatMode = Player.REPEAT_MODE_ONE
            }
    }
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val surfaceRef = remember(player) { mutableStateOf<Surface?>(null) }
    val releasedRef = remember(player) { mutableStateOf(false) }
    val attachSurface: (SurfaceTexture) -> Unit = remember(player) {
        { texture ->
            if (!releasedRef.value) {
                player.setVideoSurface(null)
                surfaceRef.value?.release()
                Surface(texture).also { surface ->
                    surfaceRef.value = surface
                    player.setVideoSurface(surface)
                }
            }
        }
    }
    val surfaceListener = remember(player) {
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                attachSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                if (!releasedRef.value) {
                    player.setVideoSurface(null)
                    surfaceRef.value?.release()
                    surfaceRef.value = null
                }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                currentOnPlaybackError(error)
            }
        }
        player.addListener(listener)
        onDispose {
            releasedRef.value = true
            player.removeListener(listener)
            player.setVideoSurface(null)
            surfaceRef.value?.release()
            surfaceRef.value = null
            player.release()
        }
    }

    LaunchedEffect(player, trailer.url) {
        player.setMediaItem(MediaItem.fromUri(trailer.url))
        player.prepare()
        player.playWhenReady = true
    }

    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                surfaceTextureListener = surfaceListener
            }
        },
        update = { view ->
            if (view.isAvailable && surfaceRef.value == null) {
                view.surfaceTexture?.let(attachSurface)
            }
        },
        modifier = modifier,
    )
}