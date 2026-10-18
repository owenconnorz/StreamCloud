package com.streamcloud.app.ui.screens

import android.text.format.DateFormat
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import com.streamcloud.app.data.api.TmdbMovie
import java.util.Date
import java.util.Locale

@Composable
internal fun TvIdleRecommendationDialog(
    item: TmdbMovie,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onWake: () -> Unit,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    var pendingKeyCode by remember { mutableIntStateOf(AndroidKeyEvent.KEYCODE_UNKNOWN) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val releaseInfo = item.displayReleaseInfo()
    val overview = item.overview?.takeIf { it.isNotBlank() }
    val timeText = remember(context, nowMillis) {
        DateFormat.getTimeFormat(context).format(Date(nowMillis))
    }
    val dateText = remember(context, nowMillis) {
        DateFormat.getLongDateFormat(context).format(Date(nowMillis))
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    Dialog(
        onDismissRequest = onWake,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (
                        native.action != AndroidKeyEvent.ACTION_DOWN &&
                        native.action != AndroidKeyEvent.ACTION_UP
                    ) {
                        return@onPreviewKeyEvent false
                    }
                    when (native.action) {
                        AndroidKeyEvent.ACTION_DOWN -> {
                            if (native.repeatCount == 0) pendingKeyCode = native.keyCode
                            true
                        }
                        AndroidKeyEvent.ACTION_UP -> {
                            if (native.keyCode == pendingKeyCode) {
                                pendingKeyCode = AndroidKeyEvent.KEYCODE_UNKNOWN
                                if (native.keyCode.isConfirmKey()) onOpen() else onWake()
                            }
                            true
                        }
                        else -> false
                    }
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onOpen,
                ),
        ) {
            val artworkUrl = item.backdropUrl
                ?.takeIf { it.isNotBlank() }
                ?: item.posterUrl.orEmpty()
            Crossfade(
                targetState = artworkUrl,
                animationSpec = tween(durationMillis = 900),
                label = "tvIdleArtwork",
            ) { imageUrl ->
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.88f),
                                0.52f to Color.Black.copy(alpha = 0.48f),
                                1f to Color.Black.copy(alpha = 0.08f),
                            ),
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.32f),
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.72f),
                            ),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 72.dp, vertical = 48.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "STREAMCLOUD",
                        color = Color.White.copy(alpha = 0.84f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = timeText,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Light,
                        )
                        Text(
                            text = dateText,
                            color = Color.White.copy(alpha = 0.76f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .padding(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "RECOMMENDED FOR YOU",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = item.displayTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (item.title != null) "FILM" else "SERIES",
                            color = Color.White.copy(alpha = 0.86f),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        )
                        if (releaseInfo != null) {
                            Text(
                                text = releaseInfo,
                                color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (item.voteAverage > 0.0) {
                            Text(
                                text = String.format(
                                    Locale.getDefault(),
                                    "★ %.1f",
                                    item.voteAverage,
                                ),
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    if (overview != null) {
                        Text(
                            text = overview,
                            color = Color.White.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 25.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.38f),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(
                            text = "Press OK for details  ·  Any other button to return",
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(count.coerceAtMost(8)) { dotIndex ->
                            Box(
                                Modifier
                                    .width(if (dotIndex == index % 8) 18.dp else 7.dp)
                                    .height(7.dp)
                                    .background(
                                        color = if (dotIndex == index % 8) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.White.copy(alpha = 0.62f)
                                        },
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Int.isConfirmKey(): Boolean =
    this == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        this == AndroidKeyEvent.KEYCODE_ENTER ||
        this == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
        this == AndroidKeyEvent.KEYCODE_BUTTON_A
