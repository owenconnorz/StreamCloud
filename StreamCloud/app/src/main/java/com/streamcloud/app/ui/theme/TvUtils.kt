package com.streamcloud.app.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val TvOverscanPadding = 24.dp

fun Modifier.tvFocusBorder(
    shape: Shape = RoundedCornerShape(12.dp),
    borderWidth: Dp = 3.dp,
    color: Color = Color.White,
): Modifier = composed {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    if (!isTv) return@composed this
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) borderWidth else 0.dp,
            color = if (focused) color else Color.Transparent,
            shape = shape,
        )
}

/**
 * Keeps TV remote traversal within a visual row or grid before Compose moves
 * focus to the next region. This is a no-op for touch-first form factors.
 */
fun Modifier.tvFocusGroup(): Modifier = composed {
    if (LocalUiFormFactor.current == UiFormFactor.Tv) focusGroup() else this
}
