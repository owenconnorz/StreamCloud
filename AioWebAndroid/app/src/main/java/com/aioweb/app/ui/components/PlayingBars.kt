package com.aioweb.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * Three white vertical bars that loop independently — Metrolist's signature
 * "now playing" indicator that overlays the album thumbnail of the currently
 * active track.
 *
 * When [paused] is true the bars freeze at their last computed position so the
 * indicator remains visible but stops moving (matches Metrolist's exact
 * behaviour during pause).
 */
@Composable
fun PlayingBars(
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    barColor: Color = Color.White,
    backgroundTint: Color = Color.Black.copy(alpha = 0.45f),
) {
    val transition = rememberInfiniteTransition(label = "playing-bars")
    // Each bar uses a different animation period so they don't sync up — the
    // visual rhythm feels organic rather than mechanical.
    val bar1 by transition.animateBar(periodMs = 600, paused = paused, label = "b1")
    val bar2 by transition.animateBar(periodMs = 800, paused = paused, label = "b2")
    val bar3 by transition.animateBar(periodMs = 700, paused = paused, label = "b3")

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundTint),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val barWidth = size.width / 6f
            val maxH = size.height
            val gap = barWidth
            val bars = floatArrayOf(bar1, bar2, bar3)
            for (i in 0 until 3) {
                val x = i * (barWidth + gap) + barWidth
                val h = (bars[i].coerceIn(0.2f, 1f)) * maxH
                drawLine(
                    color = barColor,
                    start = Offset(x + barWidth / 2f, maxH),
                    end = Offset(x + barWidth / 2f, maxH - h),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateBar(
    periodMs: Int,
    paused: Boolean,
    label: String,
): androidx.compose.runtime.State<Float> = animateFloat(
    initialValue = 0.3f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
        animation = tween(
            // Setting durationMillis = Int.MAX when paused freezes the value.
            durationMillis = if (paused) 100_000 else periodMs,
            easing = LinearEasing,
        ),
        repeatMode = RepeatMode.Reverse,
    ),
    label = label,
)
