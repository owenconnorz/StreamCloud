package com.streamcloud.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSearchScreenTest {

    @Test
    fun phoneSearchStartsWithCompactSearchAction() {
        assertFalse(shouldExpandMusicSearchBar(""))
    }

    @Test
    fun compactSearchStaysHiddenWithoutHandoffQuery() {
        assertFalse(shouldExpandMusicSearchBar(""))
    }

    @Test
    fun handoffQueryAlwaysOpensTheField() {
        assertTrue(shouldExpandMusicSearchBar("late night jazz"))
    }
}