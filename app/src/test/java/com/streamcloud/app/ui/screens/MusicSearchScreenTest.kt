package com.streamcloud.app.ui.screens

import com.streamcloud.app.ui.theme.UiFormFactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSearchScreenTest {

    @Test
    fun phoneSearchOpensAsAnEditableField() {
        assertTrue(shouldExpandMusicSearchBar("", UiFormFactor.Mobile))
    }

    @Test
    fun largerLayoutsKeepCompactSearchWithoutHandoffQuery() {
        assertFalse(shouldExpandMusicSearchBar("", UiFormFactor.Tablet))
        assertFalse(shouldExpandMusicSearchBar("", UiFormFactor.Tv))
    }

    @Test
    fun handoffQueryAlwaysOpensTheField() {
        assertTrue(shouldExpandMusicSearchBar("late night jazz", UiFormFactor.Tv))
    }
}