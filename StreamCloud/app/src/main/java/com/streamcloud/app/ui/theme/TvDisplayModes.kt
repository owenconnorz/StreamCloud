package com.streamcloud.app.ui.theme

import android.app.Activity
import android.os.Build

@Suppress("DEPRECATION")
internal fun findHighestSupportedTvDisplayModeId(activity: Activity): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

    val display = activity.windowManager.defaultDisplay
    val activeMode = display.mode
    return display.supportedModes
        .asSequence()
        .filter { mode ->
            mode.physicalWidth == activeMode.physicalWidth &&
                mode.physicalHeight == activeMode.physicalHeight
        }
        .maxByOrNull { it.refreshRate }
        ?.modeId
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
