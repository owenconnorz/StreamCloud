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

/**
 * UI mode the user has picked manually, or "Auto" to let the runtime detect
 * the form factor. Persisted in [SettingsRepository.uiMode].
 */
enum class UiModeOverride { Auto, Mobile, Tablet, Tv;
    companion object {
        fun fromStorage(value: String?): UiModeOverride =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Auto
    }
}

/** The form factor we ultimately render against — one of three concrete kinds. */
enum class UiFormFactor(val fontScale: Float, val isTouchOptimised: Boolean) {
    Mobile(fontScale = 1.0f, isTouchOptimised = true),

    /**
     * Tablet — boosts font slightly + same touch model. Ideal on 600dp+
     * displays where the bottom nav is still touchable but text needs more
     * presence to fill the wider canvas.
     */
    Tablet(fontScale = 1.10f, isTouchOptimised = true),

    /**
     * TV / leanback — biggest font, lower-density touch model so D-pad focus
     * outlines and remote interactions feel comfortable from a distance.
     */
    Tv(fontScale = 1.30f, isTouchOptimised = false),
}

/**
 * Composable-friendly view of the resolved form factor. Read via
 * `LocalUiFormFactor.current` anywhere a screen wants to vary layout.
 */
val LocalUiFormFactor = staticCompositionLocalOf { UiFormFactor.Mobile }

/**
 * Auto-detect the device's form factor. Order:
 *   1. UiModeManager TV check — `UI_MODE_TYPE_TELEVISION` is set on Android
 *      TV / Google TV / Fire TV.
 *   2. Smallest-width >= 600dp → Tablet.
 *   3. Everything else → Mobile.
 */
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

/**
 * Provides the resolved [UiFormFactor] + a font-scaled [Density] so every
 * `sp`/text-size-driven piece of UI grows with the chosen mode without
 * breaking dp-based layouts. Wrap your theme content with this once at the
 * root and read [LocalUiFormFactor.current] anywhere downstream.
 */
@Composable
fun ProvideUiFormFactor(formFactor: UiFormFactor, content: @Composable () -> Unit) {
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * formFactor.fontScale,
    )
    // Re-evaluate when the user rotates / multitasks — Configuration changes
    // bubble back through LocalConfiguration and trigger recomposition.
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
