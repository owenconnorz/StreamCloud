package com.streamcloud.app.ui.screens

import com.streamcloud.app.data.ytmusic.MoodChip
import com.streamcloud.app.ui.theme.UiFormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicScreenTest {

    @Test
    fun mobileMusicHomeUsesInlineSearch() {
        assertTrue(isInlineMusicSearch(UiFormFactor.Mobile))
    }

    @Test
    fun largerMusicLayoutsKeepSearchAsHeaderAction() {
        assertEquals(false, isInlineMusicSearch(UiFormFactor.Tablet))
        assertEquals(false, isInlineMusicSearch(UiFormFactor.Tv))
    }

    @Test
    fun quickChipsKeepRemoteLabelsAndFillMissingCategories() {
        val chips = buildMusicQuickChips(
            listOf(MoodChip("Relax", null), MoodChip("Indie", null)),
        )

        assertEquals(listOf("Relax", "Indie", "Podcast", "Workout", "Focus", "Sleep", "Party", "Chill"), chips.map { it.label })
    }
}