package com.streamcloud.app.ui.theme

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val TvOverscanPadding = 24.dp
val LocalTvFocusColor = staticCompositionLocalOf { Color.White }
val LocalTvFastScrollActive = staticCompositionLocalOf { false }
private const val TvFocusScale = 1.035f
private const val TvHorizontalRepeatGateMs = 80L
internal const val TvVerticalRepeatGateMs = 112L
private const val TvVerticalFastScrollVelocityDpPerSec = 3200f
private const val TvVerticalFastScrollEndTimeoutMs = 160L
private const val TvVerticalFastScrollMaxFrameDtSec = 0.048f
private const val TvFastScrollNone = 0
private const val TvFastScrollVertical = 1

internal fun shouldMoveFocusForDpadRepeat(
    repeatCount: Int,
    elapsedSinceLastRepeatMs: Long,
    gateMs: Long,
): Boolean = repeatCount > 0 && elapsedSinceLastRepeatMs >= gateMs

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
    val fastScrollActive = LocalTvFastScrollActive.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && !fastScrollActive) TvFocusScale else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "tvFocusScale",
    )
    val animatedBorderWidth by animateDpAsState(
        targetValue = if (focused && !fastScrollActive) borderWidth else 0.dp,
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
            color = if (focused && !fastScrollActive && showFocusFill) {
                focusColor.copy(alpha = 0.34f)
            } else {
                Color.Transparent
            },
            shape = shape,
        )
        .border(
            width = if (showOutline && focused && !fastScrollActive) {
                maxOf(animatedBorderWidth, 3.dp)
            } else {
                0.dp
            },
            color = if (showOutline && focused && !fastScrollActive) focusColor else Color.Transparent,
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
 *
 * Set [bubblePhase] on a shared page/root container to handle only repeats that
 * were not already consumed by a screen-specific preview handler.
 */
fun Modifier.tvDpadRepeatThrottle(
    horizontalGateMs: Long = TvHorizontalRepeatGateMs,
    verticalGateMs: Long = TvVerticalRepeatGateMs,
    verticalOnly: Boolean = false,
    horizontalOnly: Boolean = false,
    bubblePhase: Boolean = false,
    onMoveFocusFailed: (FocusDirection) -> Unit = {},
): Modifier = composed {
    if (LocalUiFormFactor.current != UiFormFactor.Tv) return@composed this
    val focusManager = LocalFocusManager.current
    val latestOnMoveFocusFailed = rememberUpdatedState(onMoveFocusFailed)
    val lastRepeatAt = remember { longArrayOf(0L) }

    val repeatHandler: (KeyEvent) -> Boolean = { event ->
        val native = event.nativeKeyEvent
        if (
            native.action != AndroidKeyEvent.ACTION_DOWN ||
            native.repeatCount <= 0
        ) {
            false
        } else {
            val direction = when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_DOWN ->
                    if (horizontalOnly) null else FocusDirection.Down
                AndroidKeyEvent.KEYCODE_DPAD_UP ->
                    if (horizontalOnly) null else FocusDirection.Up
                // When verticalOnly, let Left/Right fall through to nested LazyRows.
                AndroidKeyEvent.KEYCODE_DPAD_LEFT ->
                    if (verticalOnly) null else FocusDirection.Left
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (verticalOnly) null else FocusDirection.Right
                else -> null
            }
            if (direction == null) {
                false
            } else {
                val isVertical =
                    direction == FocusDirection.Up || direction == FocusDirection.Down
                val gate = if (isVertical) verticalGateMs else horizontalGateMs
                val now = SystemClock.uptimeMillis()
                if (
                    !shouldMoveFocusForDpadRepeat(
                        repeatCount = native.repeatCount,
                        elapsedSinceLastRepeatMs = now - lastRepeatAt[0],
                        gateMs = gate,
                    )
                ) {
                    true
                } else {
                    lastRepeatAt[0] = now
                    // Handle only repeats. A first press remains native; a repeat
                    // moves once and is consumed to prevent a duplicate focus step.
                    if (!focusManager.moveFocus(direction)) {
                        latestOnMoveFocusFailed.value(direction)
                    }
                    true
                }
            }
        }
    }
    if (bubblePhase) this.onKeyEvent(repeatHandler) else this.onPreviewKeyEvent(repeatHandler)
}

/**
 * Keeps native one-step D-pad focus movement, but turns held vertical repeats
 * into a smooth list scroll. On release (or at the list edge), the caller
 * chooses a visible landing target and restores focus to it.
 */
fun Modifier.tvDpadVerticalFastScroll(
    scrollableState: ScrollableState,
    onFastScrollingChanged: (Boolean) -> Unit = {},
    shouldHaltForward: () -> Boolean = { false },
    onSingleVerticalPress: (direction: Int) -> Boolean = { false },
    onScrollSettled: (direction: Int) -> Unit,
): Modifier = composed {
    if (LocalUiFormFactor.current != UiFormFactor.Tv) return@composed this

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latestOnFastScrollingChanged = rememberUpdatedState(onFastScrollingChanged)
    val latestShouldHaltForward = rememberUpdatedState(shouldHaltForward)
    val latestOnSingleVerticalPress = rememberUpdatedState(onSingleVerticalPress)
    val latestOnScrollSettled = rememberUpdatedState(onScrollSettled)
    val scrollJob = remember { AtomicReference<Job?>(null) }
    val endTimer = remember { AtomicReference<Job?>(null) }
    val mode = remember { AtomicInteger(TvFastScrollNone) }
    val direction = remember { AtomicInteger(0) }
    val isActive = remember { AtomicInteger(0) }
    val scrollGeneration = remember { AtomicInteger(0) }
    val timerGeneration = remember { AtomicInteger(0) }

    DisposableEffect(scrollableState) {
        onDispose {
            scrollGeneration.incrementAndGet()
            timerGeneration.incrementAndGet()
            scrollJob.getAndSet(null)?.cancel()
            endTimer.getAndSet(null)?.cancel()
            val wasScrolling = mode.getAndSet(TvFastScrollNone) == TvFastScrollVertical
            direction.set(0)
            if (wasScrolling && isActive.getAndSet(0) != 0) {
                latestOnFastScrollingChanged.value(false)
            }
        }
    }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val isHorizontal = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
            native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
        val sign = when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP -> -1
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> 0
        }

        fun setFastScrolling(active: Boolean) {
            val next = if (active) 1 else 0
            if (isActive.getAndSet(next) != next) {
                latestOnFastScrollingChanged.value(active)
            }
        }

        fun finishFastScroll(expectedGeneration: Int? = null) {
            if (expectedGeneration != null &&
                scrollGeneration.get() != expectedGeneration
            ) {
                return
            }
            val previousMode = mode.getAndSet(TvFastScrollNone)
            if (previousMode != TvFastScrollVertical) return
            val finalDirection = direction.getAndSet(0)
            scrollGeneration.incrementAndGet()
            timerGeneration.incrementAndGet()
            scrollJob.getAndSet(null)?.cancel()
            endTimer.getAndSet(null)?.cancel()
            setFastScrolling(false)
            latestOnScrollSettled.value(if (finalDirection == 0) 1 else finalDirection)
        }

        if (isHorizontal) {
            if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                mode.get() == TvFastScrollVertical
            ) {
                finishFastScroll()
            }
            return@onPreviewKeyEvent false
        }
        if (sign == 0) return@onPreviewKeyEvent false

        if (native.action == AndroidKeyEvent.ACTION_UP) {
            if (mode.get() == TvFastScrollVertical) finishFastScroll()
            return@onPreviewKeyEvent false
        }
        if (native.action != AndroidKeyEvent.ACTION_DOWN) {
            return@onPreviewKeyEvent false
        }
        if (native.repeatCount == 0) {
            // Keep single presses native unless a lazy-row boundary needs a
            // safe, asynchronous focus handoff to an off-screen destination.
            return@onPreviewKeyEvent latestOnSingleVerticalPress.value.invoke(sign)
        }

        val atScrollEdge = (sign > 0 && !scrollableState.canScrollForward) ||
            (sign < 0 && !scrollableState.canScrollBackward)
        if (atScrollEdge || (sign > 0 && latestShouldHaltForward.value.invoke())) {
            if (mode.get() == TvFastScrollVertical) finishFastScroll()
            return@onPreviewKeyEvent true
        }

        val needsStart = mode.get() != TvFastScrollVertical ||
            direction.get() != sign ||
            scrollJob.get()?.isActive != true
        if (needsStart) {
            val currentGeneration = scrollGeneration.incrementAndGet()
            scrollJob.getAndSet(null)?.cancel()
            mode.set(TvFastScrollVertical)
            direction.set(sign)
            setFastScrolling(true)
            val velocityPxPerSecond = with(density) {
                TvVerticalFastScrollVelocityDpPerSec.dp.toPx()
            }
            scrollJob.set(
                scope.launch {
                    try {
                        scrollableState.scroll {
                            var lastFrame = withFrameNanos { it }
                            while (true) {
                                val now = withFrameNanos { it }
                                val deltaSeconds = ((now - lastFrame) / 1_000_000_000f)
                                    .coerceAtMost(TvVerticalFastScrollMaxFrameDtSec)
                                lastFrame = now
                                if (sign > 0 && latestShouldHaltForward.value.invoke()) break
                                val delta = sign * velocityPxPerSecond * deltaSeconds
                                if (scrollBy(delta) == 0f && delta != 0f) break
                            }
                        }
                        finishFastScroll(currentGeneration)
                    } catch (_: CancellationException) {
                        // Expected when the key is released or direction changes.
                    }
                },
            )
        }

        endTimer.getAndSet(null)?.cancel()
        val timerToken = timerGeneration.incrementAndGet()
        val scrollToken = scrollGeneration.get()
        endTimer.set(
            scope.launch {
                delay(TvVerticalFastScrollEndTimeoutMs)
                if (timerGeneration.get() == timerToken) {
                    finishFastScroll(scrollToken)
                }
            },
        )
        true
    }
}
