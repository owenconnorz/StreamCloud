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

internal fun selectAutomaticHighRefreshModeId(
    current: TvDisplayModeCandidate,
    supportedModes: Iterable<TvDisplayModeCandidate>,
): Int? {
    val candidate = supportedModes
        .asSequence()
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        // Only request standard 120 Hz modes; do not force an arbitrary maximum
        // such as 144/165 Hz, which some TV firmware handles poorly.
        .filter { it.refreshRate.isFinite() && kotlin.math.abs(it.refreshRate - 120f) <= 2f }
        .minByOrNull { kotlin.math.abs(it.refreshRate - 120f) }
        ?: return null
    return candidate.modeId.takeIf { it != current.modeId }
}

@Suppress("DEPRECATION")
internal fun findAutomaticHighRefreshTvDisplayModeId(activity: Activity): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

    val display = activity.windowManager.defaultDisplay
    fun Display.Mode.toCandidate() = TvDisplayModeCandidate(
        modeId = modeId,
        physicalWidth = physicalWidth,
        physicalHeight = physicalHeight,
        refreshRate = refreshRate,
    )
    val current = display.mode.toCandidate()
    return selectAutomaticHighRefreshModeId(
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
