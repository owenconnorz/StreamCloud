package com.streamcloud.app.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun WearLineIcon(
    glyph: String,
    tint: Color,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension
        val stroke = unit * 0.085f
        val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun point(x: Float, y: Float) = Offset(this.size.width * x, this.size.height * y)

        when (glyph) {
            "♪", "music" -> {
                drawLine(tint, point(0.62f, 0.14f), point(0.62f, 0.68f), stroke, StrokeCap.Round)
                drawLine(tint, point(0.62f, 0.16f), point(0.87f, 0.22f), stroke, StrokeCap.Round)
                drawOval(tint, topLeft = point(0.34f, 0.66f), size = Size(unit * 0.27f, unit * 0.2f))
                drawOval(tint, topLeft = point(0.62f, 0.74f), size = Size(unit * 0.25f, unit * 0.19f))
            }
            "▣", "movie", "movies" -> {
                drawRoundRect(tint, topLeft = point(0.17f, 0.32f), size = Size(unit * 0.66f, unit * 0.52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.06f), style = style)
                drawLine(tint, point(0.17f, 0.32f), point(0.83f, 0.18f), stroke, StrokeCap.Round)
                drawLine(tint, point(0.28f, 0.3f), point(0.4f, 0.22f), stroke)
                drawLine(tint, point(0.51f, 0.27f), point(0.63f, 0.2f), stroke)
                drawLine(tint, point(0.72f, 0.23f), point(0.81f, 0.18f), stroke)
            }
            "♡", "adult", "heart" -> {
                val path = Path().apply {
                    moveTo(point(0.5f, 0.82f).x, point(0.5f, 0.82f).y)
                    cubicTo(point(0.38f, 0.68f).x, point(0.38f, 0.68f).y, point(0.14f, 0.49f).x, point(0.14f, 0.49f).y, point(0.18f, 0.32f).x, point(0.18f, 0.32f).y)
                    cubicTo(point(0.22f, 0.12f).x, point(0.22f, 0.12f).y, point(0.43f, 0.15f).x, point(0.43f, 0.15f).y, point(0.5f, 0.34f).x, point(0.5f, 0.34f).y)
                    cubicTo(point(0.57f, 0.15f).x, point(0.57f, 0.15f).y, point(0.78f, 0.12f).x, point(0.78f, 0.12f).y, point(0.82f, 0.32f).x, point(0.82f, 0.32f).y)
                    cubicTo(point(0.86f, 0.49f).x, point(0.86f, 0.49f).y, point(0.62f, 0.68f).x, point(0.62f, 0.68f).y, point(0.5f, 0.82f).x, point(0.5f, 0.82f).y)
                    close()
                }
                drawPath(path, tint, style = style)
            }
            "▶", "player", "play" -> {
                val path = Path().apply {
                    moveTo(point(0.32f, 0.18f).x, point(0.32f, 0.18f).y)
                    lineTo(point(0.77f, 0.5f).x, point(0.77f, 0.5f).y)
                    lineTo(point(0.32f, 0.82f).x, point(0.32f, 0.82f).y)
                    close()
                }
                drawPath(path, tint)
            }
            "⌁", "genres", "categories" -> {
                val cell = unit * 0.25f
                for (row in 0..1) for (column in 0..1) {
                    drawRoundRect(
                        tint,
                        topLeft = point(0.2f + column * 0.36f, 0.2f + row * 0.36f),
                        size = Size(cell, cell),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.04f),
                        style = style,
                    )
                }
            }
            "P", "popular" -> {
                val path = Path()
                for (index in 0 until 10) {
                    val radius = if (index % 2 == 0) unit * 0.39f else unit * 0.17f
                    val angle = -PI / 2.0 + index * PI / 5.0
                    val x = this.size.width / 2f + cos(angle).toFloat() * radius
                    val y = this.size.height / 2f + sin(angle).toFloat() * radius
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, tint, style = style)
            }
            "↗", "trending" -> {
                val path = Path().apply {
                    moveTo(point(0.18f, 0.76f).x, point(0.18f, 0.76f).y)
                    lineTo(point(0.43f, 0.55f).x, point(0.43f, 0.55f).y)
                    lineTo(point(0.58f, 0.64f).x, point(0.58f, 0.64f).y)
                    lineTo(point(0.82f, 0.3f).x, point(0.82f, 0.3f).y)
                    moveTo(point(0.62f, 0.3f).x, point(0.62f, 0.3f).y)
                    lineTo(point(0.82f, 0.3f).x, point(0.82f, 0.3f).y)
                    lineTo(point(0.82f, 0.5f).x, point(0.82f, 0.5f).y)
                }
                drawPath(path, tint, style = style)
            }
            "↓", "download" -> {
                drawLine(tint, point(0.5f, 0.14f), point(0.5f, 0.62f), stroke, StrokeCap.Round)
                drawLine(tint, point(0.31f, 0.45f), point(0.5f, 0.64f), stroke, StrokeCap.Round)
                drawLine(tint, point(0.69f, 0.45f), point(0.5f, 0.64f), stroke, StrokeCap.Round)
                drawLine(tint, point(0.2f, 0.8f), point(0.8f, 0.8f), stroke, StrokeCap.Round)
            }
            "≡", "settings", "gear" -> {
                drawCircle(tint, radius = unit * 0.32f, center = point(0.5f, 0.5f), style = style)
                drawCircle(tint, radius = unit * 0.1f, center = point(0.5f, 0.5f), style = style)
                for (index in 0..7) {
                    val angle = index * PI / 4.0
                    val start = point(0.5f + cos(angle).toFloat() * 0.37f, 0.5f + sin(angle).toFloat() * 0.37f)
                    val end = point(0.5f + cos(angle).toFloat() * 0.47f, 0.5f + sin(angle).toFloat() * 0.47f)
                    drawLine(tint, start, end, stroke, StrokeCap.Round)
                }
            }
            "home" -> {
                val path = Path().apply {
                    moveTo(point(0.13f, 0.48f).x, point(0.13f, 0.48f).y)
                    lineTo(point(0.5f, 0.18f).x, point(0.5f, 0.18f).y)
                    lineTo(point(0.87f, 0.48f).x, point(0.87f, 0.48f).y)
                    lineTo(point(0.78f, 0.48f).x, point(0.78f, 0.48f).y)
                    lineTo(point(0.78f, 0.82f).x, point(0.78f, 0.82f).y)
                    lineTo(point(0.22f, 0.82f).x, point(0.22f, 0.82f).y)
                    lineTo(point(0.22f, 0.48f).x, point(0.22f, 0.48f).y)
                    close()
                }
                drawPath(path, tint, style = style)
                drawRoundRect(tint, topLeft = point(0.42f, 0.58f), size = Size(unit * 0.16f, unit * 0.24f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.03f), style = style)
            }
            "library" -> {
                drawRoundRect(tint, topLeft = point(0.19f, 0.21f), size = Size(unit * 0.62f, unit * 0.61f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.05f), style = style)
                drawLine(tint, point(0.4f, 0.21f), point(0.4f, 0.82f), stroke)
                drawLine(tint, point(0.59f, 0.21f), point(0.59f, 0.82f), stroke)
            }
            else -> {
                drawCircle(tint, radius = unit * 0.29f, center = point(0.44f, 0.44f), style = style)
                drawLine(tint, point(0.66f, 0.66f), point(0.87f, 0.87f), stroke, StrokeCap.Round)
            }
        }
    }
}
