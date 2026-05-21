package com.streamcloud.app.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

enum class UiModeOverride { Auto, Mobile, Tablet, Tv;
    companion object {
        fun fromStorage(value: String?): UiModeOverride =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Auto
    }
}

enum class UiFormFactor(val fontScale: Float, val isTouchOptimised: Boolean) {
    Mobile(fontScale = 1.0f, isTouchOptimised = true),


    Tablet(fontScale = 1.10f, isTouchOptimised = true),


    Tv(fontScale = 1.30f, isTouchOptimised = false),
}

val LocalUiFormFactor = staticCompositionLocalOf { UiFormFactor.Mobile }

fun detectFormFactor(context: Context): UiFormFactor {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
        return UiFormFactor.Tv
    }
    val sw = context.resources.configuration.smallestScreenWidthDp
    return if (sw >= 600) UiFormFactor.Tablet else UiFormFactor.Mobile
}

fun UiModeOverride.resolve(context: Context): UiFormFactor = when (this) {
    UiModeOverride.Auto -> detectFormFactor(context)
    UiModeOverride.Mobile -> UiFormFactor.Mobile
    UiModeOverride.Tablet -> UiFormFactor.Tablet
    UiModeOverride.Tv -> UiFormFactor.Tv
}

@Composable
fun ProvideUiFormFactor(formFactor: UiFormFactor, content: @Composable () -> Unit) {
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * formFactor.fontScale,
    )


    @Suppress("UNUSED_VARIABLE")
    val cfg = LocalConfiguration.current
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val swProbe = ctx.resources.configuration.smallestScreenWidthDp

    CompositionLocalProvider(
        LocalUiFormFactor provides formFactor,
        LocalDensity provides scaledDensity,
        content = content,
    )
}
