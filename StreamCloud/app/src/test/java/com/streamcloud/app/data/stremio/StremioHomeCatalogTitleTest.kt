package com.streamcloud.app.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Test

class StremioHomeCatalogTitleTest {
    @Test
    fun movieAndSeriesCatalogsIncludeTheirMediaType() {
        val movieRow = row(type = "movie")
        val seriesRow = row(type = "series")

        assertEquals("Netflix · Movies", movieRow.displayCatalogTitle())
        assertEquals("Netflix · TV Shows", seriesRow.displayCatalogTitle())
    }

    @Test
    fun doesNotRepeatAnExistingMediaTypeSuffix() {
        assertEquals(
            "Netflix Movies",
            row(type = "movie", catalogName = "Netflix Movies").displayCatalogTitle(),
        )
    }

    private fun row(
        type: String,
        catalogName: String = "Netflix",
    ) = StremioHomeRow(
        addonId = "netflix",
        addonName = "Netflix",
        catalogId = "netflix",
        catalogName = catalogName,
        type = type,
        items = emptyList(),
    )
}
