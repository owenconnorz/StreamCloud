package com.streamcloud.app.player

import kotlin.math.roundToInt

internal const val DEFAULT_SYSTEM_BRIGHTNESS = 128
private const val SYSTEM_BRIGHTNESS_MIN = 1
private const val SYSTEM_BRIGHTNESS_MAX = 255

internal fun adjustedSystemBrightness(
    brightnessAtGestureStart: Int,
    cumulativeGestureDelta: Float,
): Int {
    val startingFraction = brightnessAtGestureStart.coerceIn(0, SYSTEM_BRIGHTNESS_MAX).toFloat() /
        SYSTEM_BRIGHTNESS_MAX.toFloat()
    val adjustedFraction = (startingFraction + cumulativeGestureDelta)
        .coerceIn(SYSTEM_BRIGHTNESS_MIN.toFloat() / SYSTEM_BRIGHTNESS_MAX, 1f)
    return (adjustedFraction * SYSTEM_BRIGHTNESS_MAX).roundToInt()
        .coerceIn(SYSTEM_BRIGHTNESS_MIN, SYSTEM_BRIGHTNESS_MAX)
}

internal fun systemBrightnessFraction(value: Int): Float =
    value.coerceIn(SYSTEM_BRIGHTNESS_MIN, SYSTEM_BRIGHTNESS_MAX).toFloat() / SYSTEM_BRIGHTNESS_MAX.toFloat()
