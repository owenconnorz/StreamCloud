package com.streamcloud.app.ui.screens

import com.streamcloud.app.data.ytmusic.MoodChip
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicScreenTest {

    @Test
    fun quickChipsKeepRemoteLabelsAndFillMissingCategories() {
        val chips = buildMusicQuickChips(
            listOf(MoodChip("Relax", null), MoodChip("Indie", null)),
        )

        assertEquals(listOf("Relax", "Indie", "Podcast", "Workout", "Focus", "Sleep", "Party", "Chill"), chips.map { it.label })
    }

    @Test
    fun combinedSuggestionsUseOneDeduplicatedBar() {
        val labels = buildCombinedMusicSuggestions(
            listOf(MoodChip("Chill", null), MoodChip("Podcast", null)),
        )

        assertEquals(
            listOf("Chill", "Podcast", "Top hits 2026", "Lo-fi beats", "Workout", "Throwback", "K-pop", "Hip hop", "Jazz", "EDM", "Acoustic"),
            labels,
        )
    }
}