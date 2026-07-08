package com.streamcloud.app.ui

internal const val SETTINGS_ROUTE = "settings"
internal const val SETTINGS_PLUGINS_ROUTE = "plugins"
internal const val SETTINGS_COLLECTIONS_ROUTE = "collections"

private val settingsAreaRoutes = setOf(
    SETTINGS_ROUTE,
    SETTINGS_PLUGINS_ROUTE,
    SETTINGS_COLLECTIONS_ROUTE,
)

internal fun isSettingsAreaRoute(route: String?): Boolean = route in settingsAreaRoutes

internal fun shouldShowGlobalMiniPlayer(
    currentRoute: String?,
    isMediaRoute: Boolean,
): Boolean = currentRoute != null && !isMediaRoute && !isSettingsAreaRoute(currentRoute)
