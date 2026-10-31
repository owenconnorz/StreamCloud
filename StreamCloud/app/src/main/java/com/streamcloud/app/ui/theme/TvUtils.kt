package com.streamcloud.app.ui.theme

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val TvOverscanPadding = 24.dp
val LocalTvFocusColor = staticCompositionLocalOf { Color.White }
private const val TvFocusScale = 1.035f
private const val TvHorizontalRepeatGateMs = 80L
private const val TvVerticalRepeatGateMs = 112L

fun Modifier.tvFocusBorder(
    shape: Shape = RoundedCornerShape(12.dp),
    borderWidth: Dp = 3.dp,
    color: Color = Color.White,
    showOutline: Boolean = true,
    showFocusFill: Boolean = true,
): Modifier = composed {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    if (!isTv) return@composed this
    val focusColor = LocalTvFocusColor.current.takeUnless { it == Color.White }
        ?: color.takeUnless { it == Color.White }
        ?: MaterialTheme.colorScheme.primary
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
        .background(
            color = if (focused && showFocusFill) focusColor.copy(alpha = 0.34f) else Color.Transparent,
            shape = shape,
        )
        .border(
            width = if (showOutline && focused) maxOf(animatedBorderWidth, 3.dp) else 0.dp,
            color = if (showOutline && focused) focusColor else Color.Transparent,
            shape = shape,
        )
}

/**
 * Keeps TV remote traversal within a visual row or grid before Compose moves
 * focus to the next region. This is a no-op for touch-first form factors.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tvFocusGroup(
    restoreFocusedChild: Boolean = false,
    focusRequester: FocusRequester? = null,
): Modifier = composed {
    if (LocalUiFormFactor.current == UiFormFactor.Tv) {
        val internalRequester = remember { FocusRequester() }
        val requester = focusRequester ?: internalRequester.takeIf { restoreFocusedChild }
        val withRequester = if (requester != null) this.focusRequester(requester) else this
        if (restoreFocusedChild && requester != null) {
            withRequester
                .tvUnpinnedFocusRestorer(requester)
                .focusGroup()
        } else {
            withRequester.focusGroup()
        }
    } else {
        this
    }
}

/**
 * Handles held D-pad events at a controlled cadence while allowing the first
 * press to use Compose's normal spatial focus search.
 *
 * Set [verticalOnly] = true when the container holds nested horizontal LazyRows:
 * it limits throttling to Up/Down so Left/Right events fall through naturally
 * to the inner row's own focus traversal.
 */
fun Modifier.tvDpadRepeatThrottle(
    horizontalGateMs: Long = TvHorizontalRepeatGateMs,
    verticalGateMs: Long = TvVerticalRepeatGateMs,
    handleInitialPresses: Boolean = false,
    verticalOnly: Boolean = false,
    horizontalOnly: Boolean = false,
): Modifier = composed {
    if (LocalUiFormFactor.current != UiFormFactor.Tv) return@composed this
    val focusManager = LocalFocusManager.current
    val lastRepeatAt = remember { longArrayOf(0L) }

    this.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (
            native.action != AndroidKeyEvent.ACTION_DOWN ||
            (native.repeatCount <= 0 && !handleInitialPresses)
        ) {
            return@onPreviewKeyEvent false
        }
        val direction = when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_DOWN ->
                if (horizontalOnly) null else FocusDirection.Down
            AndroidKeyEvent.KEYCODE_DPAD_UP ->
                if (horizontalOnly) null else FocusDirection.Up
            // When verticalOnly, let Left/Right fall through to nested LazyRows.
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (verticalOnly) null else FocusDirection.Left
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (verticalOnly) null else FocusDirection.Right
            else -> null
        } ?: return@onPreviewKeyEvent false

        val isVertical = direction == FocusDirection.Up || direction == FocusDirection.Down
        val gate = if (isVertical) verticalGateMs else horizontalGateMs
        val now = SystemClock.uptimeMillis()
        if (native.repeatCount > 0 && now - lastRepeatAt[0] < gate) {
            return@onPreviewKeyEvent true
        }

        lastRepeatAt[0] = now
        // Let a boundary press fall through when Compose cannot find another
        // focus target. Parent surfaces can then handle the handoff (for
        // example, returning from a rail to the top TV navigation) instead of
        // trapping the remote at the edge of a nested list.
        focusManager.moveFocus(direction)
    }
}

/**
 * Lets normal focus traversal handle a single Up/Down press. Held repeats
 * scroll the parent TV list, then [onVerticalLanding] returns focus to the
 * closest visible row. This avoids trapping focus in a row after the parent
 * list has moved far enough to recycle it.
 */
fun Modifier.tvDpadVerticalFastScroll(
    listState: LazyListState,
    onVerticalLanding: (direction: Int) -> Unit,
): Modifier = composed {
    if (LocalUiFormFactor.current != UiFormFactor.Tv) return@composed this

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scrollJob = remember { AtomicReference<Job?>(null) }
    val idleTimer = remember { AtomicReference<Job?>(null) }
    val active = remember { AtomicBoolean(false) }
    val direction = remember { AtomicInteger(0) }

    fun finish(cancelScroll: Boolean = true) {
        val wasActive = active.getAndSet(false)
        val lastDirection = direction.getAndSet(0)
        idleTimer.getAndSet(null)?.cancel()
        val job = scrollJob.getAndSet(null)
        if (cancelScroll) job?.cancel()
        if (wasActive) onVerticalLanding(if (lastDirection == 0) 1 else lastDirection)
    }

    DisposableEffect(Unit) {
        onDispose {
            active.set(false)
            scrollJob.getAndSet(null)?.cancel()
            idleTimer.getAndSet(null)?.cancel()
        }
    }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val keyCode = native.keyCode
        val sign = when (keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP -> -1
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> 0
        }
        if (sign == 0) return@onPreviewKeyEvent false

        if (native.action == AndroidKeyEvent.ACTION_UP) {
            if (active.get()) finish()
            return@onPreviewKeyEvent false
        }
        if (native.action != AndroidKeyEvent.ACTION_DOWN || native.repeatCount == 0) {
            return@onPreviewKeyEvent false
        }

        val atEdge = if (sign > 0) !listState.canScrollForward else !listState.canScrollBackward
        if (atEdge) {
            if (active.get()) finish()
            return@onPreviewKeyEvent true
        }

        val needsNewScroll = !active.get() ||
            direction.get() != sign ||
            scrollJob.get()?.isActive != true
        if (needsNewScroll) {
            if (active.get()) finish()
            direction.set(sign)
            active.set(true)
            val velocityPxPerSecond = with(density) { 2200.dp.toPx() }
            scrollJob.set(
                scope.launch {
                    try {
                        listState.scroll {
                            var previousFrame = withFrameNanos { it }
                            while (active.get()) {
                                val frame = withFrameNanos { it }
                                val deltaSeconds = ((frame - previousFrame) / 1_000_000_000f)
                                    .coerceAtMost(0.048f)
                                previousFrame = frame
                                val canContinue = if (sign > 0) {
                                    listState.canScrollForward
                                } else {
                                    listState.canScrollBackward
                                }
                                if (!canContinue) break
                                val consumed = scrollBy(sign * velocityPxPerSecond * deltaSeconds)
                                if (consumed == 0f) break
                            }
                        }
                        finish(cancelScroll = false)
                    } catch (_: CancellationException) {
                        // Expected when the D-pad is released or the direction changes.
                    }
                },
            )
        }

        idleTimer.getAndSet(null)?.cancel()
        idleTimer.set(
            scope.launch {
                delay(180L)
                finish()
            },
        )
        true
    }
}
