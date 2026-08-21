package com.streamcloud.app.ui.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

fun Modifier.verticalScrollbar(
    state: LazyListState,
    width: Dp = 3.dp,
    dragGestureWidth: Dp = 48.dp,
    minThumbHeight: Dp = 48.dp,
    fixedThumbHeight: Dp? = null,
    color: Color = Color.White.copy(alpha = 0.35f),
    activeColor: Color = color,
    fadeOutDelayMs: Int = 800,
    alwaysVisible: Boolean = false,
    minItemCountForScroll: Int = 1,
    headerItems: Int = 0,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }

    val isScrolling = state.isScrollInProgress
    val layoutInfo = state.layoutInfo
    val contentItemCount = (layoutInfo.totalItemsCount - headerItems).coerceAtLeast(0)
    val hasScrollableContent = contentItemCount > minItemCountForScroll &&
        contentItemCount > layoutInfo.visibleItemsInfo.size
    val alpha by animateFloatAsState(
        targetValue = if (hasScrollableContent && (alwaysVisible || isScrolling || isDragging)) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isScrolling || isDragging) 150 else fadeOutDelayMs,
        ),
        label = "scrollbar_alpha",
    )

    this
        .pointerInput(state) {
            while (true) {
                awaitPointerEventScope {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (size.width - down.position.x > dragGestureWidth.toPx()) return@awaitPointerEventScope
                    val info = state.layoutInfo
                    val contentItems = (info.totalItemsCount - headerItems).coerceAtLeast(0)
                    if (contentItems <= minItemCountForScroll ||
                        contentItems <= info.visibleItemsInfo.size
                    ) return@awaitPointerEventScope
                    isDragging = true
                    down.consume()
                    drag(down.id) { change ->
                        val fraction = (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                        val totalItems = state.layoutInfo.totalItemsCount
                        val scrollableItems = (totalItems - headerItems).coerceAtLeast(0)
                        if (scrollableItems > 0) {
                            scope.launch {
                                state.scrollToItem(
                                    (headerItems + (fraction * (scrollableItems - 1)).toInt())
                                        .coerceIn(headerItems, totalItems - 1)
                                )
                            }
                        }
                        change.consume()
                    }
                    isDragging = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (alpha == 0f) return@drawWithContent

            val info = state.layoutInfo
            val totalItems = info.totalItemsCount
            val contentItems = (totalItems - headerItems).coerceAtLeast(0)
            if (contentItems <= minItemCountForScroll) return@drawWithContent

            val visibleItems = info.visibleItemsInfo
            if (visibleItems.isEmpty()) return@drawWithContent

            val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
            val firstItem = visibleItems.first()

            val avgItemHeight = visibleItems.sumOf { it.size } / visibleItems.size.toFloat()
            val estimatedTotalHeight = (avgItemHeight * contentItems)
                .coerceAtLeast(viewportHeight.toFloat())

            val calculatedThumbHeightPx = ((viewportHeight / estimatedTotalHeight) * viewportHeight)
                .coerceAtLeast(minThumbHeight.toPx())
            val thumbHeightPx = (fixedThumbHeight?.toPx() ?: calculatedThumbHeightPx)
                .coerceAtMost(viewportHeight.toFloat())

            val scrolledPast = ((firstItem.index - headerItems).coerceAtLeast(0) * avgItemHeight) - firstItem.offset
            val maxScroll = estimatedTotalHeight - viewportHeight
            val scrollFraction = if (maxScroll > 0) (scrolledPast / maxScroll).coerceIn(0f, 1f) else 0f

            val thumbTop = scrollFraction * (viewportHeight - thumbHeightPx)
            val barWidth = width.toPx()

            drawRoundRect(
                color = (if (isDragging) activeColor else color).copy(
                    alpha = (if (isDragging) activeColor else color).alpha * alpha
                ),
                topLeft = Offset(size.width - barWidth - 2.dp.toPx(), thumbTop),
                size = Size(barWidth, thumbHeightPx),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
}
