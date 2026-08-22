package com.streamcloud.app.ui.theme

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val TvOverscanPadding = 24.dp
private const val TvFocusScale = 1.035f
private const val TvHorizontalRepeatGateMs = 80L
private const val TvVerticalRepeatGateMs = 112L

fun Modifier.tvFocusBorder(
    shape: Shape = RoundedCornerShape(12.dp),
    borderWidth: Dp = 3.dp,
    color: Color = Color.White,
): Modifier = composed {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    if (!isTv) return@composed this
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) TvFocusScale else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "tvFocusScale",
    )
    val animatedBorderWidth by animateDpAsState(
        targetValue = if (focused) borderWidth else 0.dp,
        animationSpec = tween(durationMillis = 120),
        label = "tvFocusBorder",
    )

    this
        .onFocusChanged { focused = it.isFocused }
        // Focusable already asks a Lazy list to reveal the focused item. A second,
        // unconditional bringIntoView request here made nested TV rails fight the
        // parent list and could scroll vertically while moving across a row.
        .focusable()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(
            width = animatedBorderWidth,
            color = if (focused) color else Color.Transparent,
            shape = shape,
        )
}

/**
 * Keeps TV remote traversal within a visual row or grid before Compose moves
 * focus to the next region. This is a no-op for touch-first form factors.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tvFocusGroup(): Modifier = composed {
    if (LocalUiFormFactor.current == UiFormFactor.Tv) {
        this.focusGroup()
    } else {
        this
    }
}

/**
 * Handles held D-pad events at a controlled cadence while allowing the first
 * press to use Compose's normal spatial focus search.
 */
fun Modifier.tvDpadRepeatThrottle(
    horizontalGateMs: Long = TvHorizontalRepeatGateMs,
    verticalGateMs: Long = TvVerticalRepeatGateMs,
): Modifier = composed {
    if (LocalUiFormFactor.current != UiFormFactor.Tv) return@composed this
    val focusManager = LocalFocusManager.current
    val lastRepeatAt = remember { longArrayOf(0L) }

    this.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action != AndroidKeyEvent.ACTION_DOWN || native.repeatCount <= 0) {
            return@onPreviewKeyEvent false
        }
        val direction = when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
            AndroidKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
            else -> null
        } ?: return@onPreviewKeyEvent false

        val isVertical = direction == FocusDirection.Up || direction == FocusDirection.Down
        val gate = if (isVertical) verticalGateMs else horizontalGateMs
        val now = SystemClock.uptimeMillis()
        if (now - lastRepeatAt[0] < gate) return@onPreviewKeyEvent true

        lastRepeatAt[0] = now
        focusManager.moveFocus(direction)
        // Repeated D-pad events must never fall through to a LazyColumn when
        // focus reaches an edge. Otherwise the focus search and list scrolling
        // run together, causing skipped rows and noticeable input lag.
        true
    }
}
