package com.streamcloud.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Single source of truth for the mini and full now-playing anchors. Progress is
 * zero at the mini-player anchor and one at the full-screen anchor.
 */
@Stable
class PlayerSurfaceState internal constructor(
    private val scope: CoroutineScope,
) {
    var progress by mutableFloatStateOf(0f)
        private set

    private var animationJob: Job? = null

    fun dragByPixels(deltaY: Float, availableHeightPx: Int) {
        if (availableHeightPx <= 0) return
        animationJob?.cancel()
        progress = (progress - deltaY / availableHeightPx).coerceIn(0f, 1f)
    }

    fun expand() {
        animateTo(1f)
    }

    fun collapse() {
        animateTo(0f)
    }

    private fun animateTo(target: Float) {
        animationJob?.cancel()
        if (progress == target) return
        animationJob = scope.launch {
            Animatable(progress).animateTo(
                targetValue = target,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ) {
                this@PlayerSurfaceState.progress = value
            }
        }
    }
}

@Composable
fun rememberPlayerSurfaceState(): PlayerSurfaceState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlayerSurfaceState(scope) }
}