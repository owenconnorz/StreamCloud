package com.streamcloud.app.ui.theme

import android.app.Activity
import android.os.Build
import android.view.Display

internal data class TvDisplayModeCandidate(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float,
)

internal fun selectAutomaticTvDisplayModeId(
    current: TvDisplayModeCandidate,
    supportedModes: Iterable<TvDisplayModeCandidate>,
): Int? {
    val hdModes = supportedModes
        .asSequence()
        .filter {
            it.physicalWidth >= 1920 &&
                it.physicalHeight >= 1080 &&
                it.refreshRate.isFinite() &&
                it.refreshRate in 48f..122f
        }
        .toList()
    if (hdModes.isEmpty()) return null

    val currentIsHd = current.physicalWidth >= 1920 && current.physicalHeight >= 1080
    if (currentIsHd && current.refreshRate > 122f) return null
    val resolutionModes = if (currentIsHd) {
        hdModes.filter {
            it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight
        }
    } else {
        val bestPixelCount = hdModes.maxOf { it.physicalWidth.toLong() * it.physicalHeight }
        hdModes.filter { it.physicalWidth.toLong() * it.physicalHeight == bestPixelCount }
    }

    val candidate = resolutionModes.maxByOrNull { it.refreshRate } ?: return null
    return candidate.modeId.takeIf { it != current.modeId }
}

@Suppress("DEPRECATION")
internal fun findAutomaticTvDisplayModeId(activity: Activity): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

    val display = activity.windowManager.defaultDisplay
    fun Display.Mode.toCandidate() = TvDisplayModeCandidate(
        modeId = modeId,
        physicalWidth = physicalWidth,
        physicalHeight = physicalHeight,
        refreshRate = refreshRate,
    )
    val current = display.mode.toCandidate()
    return selectAutomaticTvDisplayModeId(
        current = current,
        supportedModes = display.supportedModes.map { it.toCandidate() },
    )
}

@Suppress("DEPRECATION")
internal fun findMatchingTvDisplayModeId(activity: Activity, frameRate: Float): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !frameRate.isFinite() || frameRate <= 0f) {
        return null
    }

    val display = activity.windowManager.defaultDisplay
    val activeMode = display.mode
    return display.supportedModes
        .asSequence()
        .filter { mode ->
            mode.physicalWidth == activeMode.physicalWidth &&
                mode.physicalHeight == activeMode.physicalHeight
        }
        .filter { mode ->
            (1..5).any { multiple ->
                kotlin.math.abs(mode.refreshRate - frameRate * multiple) <= 0.75f
            }
        }
        .maxByOrNull { it.refreshRate }
        ?.modeId
}
