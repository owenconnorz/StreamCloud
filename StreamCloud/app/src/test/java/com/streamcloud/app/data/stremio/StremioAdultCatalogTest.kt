package com.streamcloud.app.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Test

class StremioAdultCatalogTest {
    @Test
    fun adultHomeRowsIncludesEveryAdultAddonAndCatalog() {
        val rows = listOf(
            row(addon = "XXX Club", catalog = "Popular"),
            row(addon = "Community Addon", catalog = "Adult Movies"),
            row(
                addon = "Mixed Catalog Addon",
                catalog = "Featured",
                genres = listOf("Drama", "Erotic"),
            ),
            row(addon = "Movie Catalog", catalog = "Trending", genres = listOf("Action")),
        )

        assertEquals(
            listOf("Popular", "Adult Movies", "Featured"),
            adultStremioHomeRows(rows).map { it.catalogName },
        )
    }

    @Test
    fun anExplicitAdultCatalogIsRecognizedWithoutGenreMetadata() {
        val row = row(
            addon = "Community Addon",
            catalog = "Popular",
            isAdultCatalog = true,
        )

        assertEquals(listOf(row), adultStremioHomeRows(listOf(row)))
    }

    private fun row(
        addon: String,
        catalog: String,
        genres: List<String>? = null,
        isAdultCatalog: Boolean = false,
    ) = StremioHomeRow(
        addonId = addon.lowercase().replace(' ', '-'),
        addonName = addon,
        catalogId = catalog.lowercase().replace(' ', '-'),
        catalogName = catalog,
        type = "movie",
        items = listOf(
            StremioMetaPreview(
                id = "item-$catalog",
                type = "movie",
                name = catalog,
                genres = genres,
            ),
        ),
        isAdultCatalog = isAdultCatalog,
    )
}
