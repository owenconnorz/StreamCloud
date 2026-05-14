package com.aioweb.app.ui.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tiny event bus for "expand the now-playing sheet" requests fired from the
 * global MiniPlayer (swipe-up gesture or thumbnail tap). [MusicScreen]
 * collects this flow and toggles its local `showNowPlaying` state, so the
 * sheet opens automatically after the user is routed to the Music tab.
 *
 * Lives outside any ViewModel so it can be reached from a Composable invoked
 * outside the main NavHost without dragging extra plumbing through.
 */
object PlayerExpandBus {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun requestExpand() {
        _events.tryEmit(Unit)
    }
}
