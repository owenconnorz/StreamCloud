package com.streamcloud.app.ui.theme

/**
 * Remembers focus without keeping a lazy item's pin alive across navigation.
 * Restoring a child can synchronously re-enter the group's focus callbacks.
 */
internal class TvFocusRestorePolicy(
    private val saveChild: () -> Boolean,
    private val restoreChild: () -> Boolean,
) {
    private var restoring = false

    fun onExit() {
        if (!restoring) saveChild()
    }

    fun onEnter(): Boolean {
        if (restoring) return false
        restoring = true
        return try {
            restoreChild()
        } finally {
            restoring = false
        }
    }
}
