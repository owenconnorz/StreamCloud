package com.streamcloud.app.ui.theme

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
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
    isLowRamDevice: Boolean = false,
): Int? {
    if (isLowRamDevice) return null
    if (current.refreshRate.isFinite() && current.refreshRate in 48f..60.5f) return null

    // Keep the system-selected resolution; older TV firmware may black-screen on output changes.
    // Use only modes Android reports at that resolution and cap automatic changes at 60 Hz.
    val safeModes = supportedModes
        .asSequence()
        .filter {
            it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight &&
                it.refreshRate.isFinite() &&
                it.refreshRate in 48f..60.5f
        }
        .toList()
    val candidate = safeModes.maxByOrNull { it.refreshRate } ?: return null
    return candidate.modeId.takeIf { it != current.modeId }
}

@Suppress("DEPRECATION")
internal fun findAutomaticTvDisplayModeId(activity: Activity): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !allowsTvModeSwitch(activity)) return null

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
        isLowRamDevice = isLowRamDevice(activity),
    )
}

@Suppress("DEPRECATION")
internal fun findMatchingTvDisplayModeId(activity: Activity, frameRate: Float): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        !allowsTvModeSwitch(activity) ||
        !frameRate.isFinite() ||
        frameRate <= 0f
    ) {
        return null
    }

    val display = activity.windowManager.defaultDisplay
    val activeMode = display.mode
    return display.supportedModes
        .asSequence()
        .filter { mode ->
            mode.physicalWidth == activeMode.physicalWidth &&
                mode.physicalHeight == activeMode.physicalHeight &&
                mode.refreshRate.isFinite() &&
                mode.refreshRate <= 60.5f
        }
        .filter { mode ->
            (1..5).any { multiple ->
                kotlin.math.abs(mode.refreshRate - frameRate * multiple) <= 0.75f
            }
        }
        .maxByOrNull { it.refreshRate }
        ?.modeId
}

private fun isLowRamDevice(activity: Activity): Boolean =
    (activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.isLowRamDevice
        ?: true

private fun allowsTvModeSwitch(activity: Activity): Boolean = !isLowRamDevice(activity)
