package com.streamcloud.app.player

import android.content.Context
import android.graphics.Color as AndroidColor

data class SubtitleStyleState(
    val fontSizeSp: Float = 16f,
    val colorArgb: Int = AndroidColor.WHITE,
    val outlineEnabled: Boolean = true,
    val outlineColorArgb: Int = AndroidColor.BLACK,
    val bold: Boolean = false,
    val opacityFraction: Float = 1f,
    val bottomOffsetFraction: Float = 0.08f,
    val delayMs: Int = 0,
)

object SubtitleStylePrefs {
    private const val PREFS_NAME = "subtitle_style_v1"

    fun load(context: Context): SubtitleStyleState {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SubtitleStyleState(
            fontSizeSp           = p.getFloat("fontSizeSp", 16f),
            colorArgb            = p.getInt("colorArgb", AndroidColor.WHITE),
            outlineEnabled       = p.getBoolean("outlineEnabled", true),
            outlineColorArgb     = p.getInt("outlineColorArgb", AndroidColor.BLACK),
            bold                 = p.getBoolean("bold", false),
            opacityFraction      = p.getFloat("opacityFraction", 1f),
            bottomOffsetFraction = p.getFloat("bottomOffsetFraction", 0.08f),
            delayMs              = p.getInt("delayMs", 0),
        )
    }

    fun save(context: Context, state: SubtitleStyleState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putFloat("fontSizeSp",           state.fontSizeSp)
            putInt("colorArgb",              state.colorArgb)
            putBoolean("outlineEnabled",     state.outlineEnabled)
            putInt("outlineColorArgb",       state.outlineColorArgb)
            putBoolean("bold",               state.bold)
            putFloat("opacityFraction",      state.opacityFraction)
            putFloat("bottomOffsetFraction", state.bottomOffsetFraction)
            putInt("delayMs",                state.delayMs)
            apply()
        }
    }

    fun reset(context: Context): SubtitleStyleState {
        val default = SubtitleStyleState()
        save(context, default)
        return default
    }
}
