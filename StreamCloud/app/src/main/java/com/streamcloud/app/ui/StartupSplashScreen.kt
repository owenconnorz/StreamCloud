package com.streamcloud.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor

@Composable
internal fun StartupSplashScreen() {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashNavy),
        contentAlignment = Alignment.Center,
    ) {
        val wideLayout = isTv || maxWidth >= 600.dp
        SplashBackground()

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = if (wideLayout) 18.dp else 34.dp)
                .padding(horizontal = if (wideLayout) 48.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CloudPlayMark(
                modifier = Modifier.size(
                    width = if (wideLayout) 214.dp else 178.dp,
                    height = if (wideLayout) 148.dp else 123.dp,
                ),
            )
            Spacer(Modifier.height(if (wideLayout) 20.dp else 16.dp))
            StreamCloudWordmark(isTv = wideLayout)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "MUSIC  •  MOVIES  •  TV  •  MORE",
                color = Color(0xFFCFD3E2),
                fontSize = if (wideLayout) 17.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = if (wideLayout) 4.4.sp else 3.5.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(if (wideLayout) 88.dp else 84.dp))
            SplashProgressBar(
                modifier = Modifier
                    .width(if (wideLayout) 420.dp else 268.dp)
                    .height(8.dp),
            )
            Spacer(Modifier.height(15.dp))
            Text(
                text = "Loading your entertainment...",
                color = Color(0xFFD2D5E1),
                fontSize = if (wideLayout) 17.sp else 14.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.1.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SplashBackground() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(SplashNavy)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x1A164CFF), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.46f),
                radius = w * 0.7f,
            ),
        )

        val upperWave = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.48f, 0f)
            cubicTo(w * 0.42f, h * 0.055f, w * 0.36f, h * 0.105f, w * 0.23f, h * 0.13f)
            cubicTo(w * 0.11f, h * 0.15f, w * 0.035f, h * 0.18f, 0f, h * 0.29f)
            close()
        }
        drawPath(
            path = upperWave,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF061C5C), Color(0xFF063A9A), Color(0x002B74FF)),
                start = Offset(0f, 0f),
                end = Offset(w * 0.43f, h * 0.22f),
            ),
        )
        val upperFold = Path().apply {
            moveTo(0f, h * 0.18f)
            cubicTo(w * 0.09f, h * 0.115f, w * 0.23f, h * 0.13f, w * 0.35f, h * 0.075f)
            cubicTo(w * 0.43f, h * 0.04f, w * 0.47f, h * 0.015f, w * 0.5f, 0f)
            lineTo(w * 0.58f, 0f)
            cubicTo(w * 0.48f, h * 0.12f, w * 0.35f, h * 0.19f, w * 0.21f, h * 0.21f)
            cubicTo(w * 0.11f, h * 0.23f, w * 0.04f, h * 0.29f, 0f, h * 0.37f)
            close()
        }
        drawPath(
            upperFold,
            Brush.linearGradient(
                colors = listOf(Color(0xAA0A2A8F), Color(0x773154DC), Color(0x002C42C9)),
                start = Offset(0f, h * 0.12f),
                end = Offset(w * 0.45f, h * 0.28f),
            ),
        )

        val lowerWave = Path().apply {
            moveTo(0f, h * 0.865f)
            cubicTo(w * 0.19f, h * 0.775f, w * 0.32f, h * 0.84f, w * 0.47f, h * 0.885f)
            cubicTo(w * 0.65f, h * 0.94f, w * 0.81f, h * 0.875f, w, h * 0.71f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            lowerWave,
            Brush.linearGradient(
                colors = listOf(Color(0xFF06154B), Color(0xFF1026A0), Color(0xFF3512A9)),
                start = Offset(0f, h * 0.82f),
                end = Offset(w, h),
            ),
        )
        drawPath(
            lowerWave,
            Brush.linearGradient(
                colors = listOf(Color(0x99509DFF), Color(0x665B4BFF), Color(0x006F3CFF)),
                start = Offset(0f, h * 0.79f),
                end = Offset(w, h * 0.9f),
            ),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        val lowerFold = Path().apply {
            moveTo(0f, h * 0.925f)
            cubicTo(w * 0.19f, h * 0.825f, w * 0.34f, h * 0.88f, w * 0.49f, h * 0.935f)
            cubicTo(w * 0.7f, h * 1.01f, w * 0.89f, h * 0.84f, w, h * 0.77f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            lowerFold,
            Brush.linearGradient(
                colors = listOf(Color(0xFF07102F), Color(0xFF19116B), Color(0xFF3810A7)),
                start = Offset(0f, h * 0.88f),
                end = Offset(w, h),
            ),
        )
        drawPath(
            lowerFold,
            Brush.linearGradient(
                colors = listOf(Color(0xBB1684FF), Color(0x885E4AFF), Color(0x007F35FF)),
                start = Offset(0f, h * 0.84f),
                end = Offset(w, h * 0.9f),
            ),
            style = Stroke(width = 1.3.dp.toPx()),
        )
    }
}

@Composable
private fun CloudPlayMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cloud = Path().apply {
            moveTo(w * 0.23f, h * 0.79f)
            cubicTo(w * 0.09f, h * 0.79f, w * 0.015f, h * 0.69f, w * 0.015f, h * 0.55f)
            cubicTo(w * 0.015f, h * 0.41f, w * 0.12f, h * 0.30f, w * 0.27f, h * 0.29f)
            cubicTo(w * 0.31f, h * 0.10f, w * 0.47f, h * 0.005f, w * 0.62f, h * 0.055f)
            cubicTo(w * 0.75f, h * 0.095f, w * 0.83f, h * 0.20f, w * 0.84f, h * 0.34f)
            cubicTo(w * 0.98f, h * 0.38f, w * 1.02f, h * 0.53f, w * 0.97f, h * 0.65f)
            cubicTo(w * 0.93f, h * 0.75f, w * 0.85f, h * 0.80f, w * 0.73f, h * 0.80f)
            close()
        }
        drawPath(
            path = cloud,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF35D5FF), Color(0xFF2888FF), Color(0xFF8053FF), Color(0xFFD53DEB)),
                start = Offset(w * 0.12f, h * 0.23f),
                end = Offset(w * 0.89f, h * 0.76f),
            ),
        )

        val play = Path().apply {
            moveTo(w * 0.46f, h * 0.35f)
            cubicTo(w * 0.44f, h * 0.34f, w * 0.42f, h * 0.36f, w * 0.42f, h * 0.40f)
            lineTo(w * 0.42f, h * 0.65f)
            cubicTo(w * 0.42f, h * 0.69f, w * 0.45f, h * 0.70f, w * 0.48f, h * 0.68f)
            lineTo(w * 0.69f, h * 0.55f)
            cubicTo(w * 0.72f, h * 0.53f, w * 0.72f, h * 0.49f, w * 0.69f, h * 0.47f)
            close()
        }
        drawPath(play, color = Color(0xFF030714))
    }
}

@Composable
private fun StreamCloudWordmark(isTv: Boolean) {
    val gradient = remember {
        Brush.linearGradient(
            colors = listOf(Color(0xFF27B7FF), Color(0xFF4778FF), Color(0xFFAA63F5)),
        )
    }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Stream",
            color = Color.White,
            fontSize = if (isTv) 58.sp else 43.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1.8).sp,
            fontFamily = FontFamily.SansSerif,
            maxLines = 1,
        )
        Text(
            text = "Cloud",
            style = TextStyle(
                brush = gradient,
                fontSize = if (isTv) 58.sp else 43.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.8).sp,
                fontFamily = FontFamily.SansSerif,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun SplashProgressBar(
    modifier: Modifier = Modifier,
) {
    val sweepTransition = rememberInfiniteTransition(label = "splash progress sweep")
    val sweepPosition by sweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "splash progress",
    )
    Canvas(
        modifier = modifier
            .clip(CircleShape)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
    ) {
        drawRoundRect(
            color = Color(0xFF292E3D),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
        )
        val fillWidth = size.width * 0.48f
        val startX = (size.width - fillWidth) * sweepPosition
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF3BC8FF), Color(0xFF6284FF), Color(0xFF9D54F4)),
                start = Offset(startX, 0f),
                end = Offset(startX + fillWidth, size.height),
            ),
            topLeft = Offset(startX, 0f),
            size = Size(fillWidth, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
        )
    }
}

private val SplashNavy = Color(0xFF030714)
