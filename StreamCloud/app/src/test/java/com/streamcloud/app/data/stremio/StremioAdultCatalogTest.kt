package com.streamcloud.app.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Test

class StremioAdultCatalogTest {
    @Test
    fun homeLayoutIncludesAdultCatalogsBeyondTheDefaultLimitAndCatalogGroups() {
        val regularCatalogs = (1..9).map { index ->
            StremioCatalogDef(
                type = "movie",
                id = "regular-$index",
                name = "Regular $index",
            )
        }
        val explicitAdultCatalog = StremioCatalogDef(
            type = "movie",
            id = "hentai",
            name = "Hentai Anime",
        )
        val groupedAdultCatalog = StremioCatalogDef(
            type = "movie",
            id = "indian-18",
            name = "Indian 18+",
        )
        val manifest = StremioManifest(
            id = "mixed",
            name = "Mixed Catalogs",
            catalogs = regularCatalogs + explicitAdultCatalog,
            catalogGroups = listOf(
                StremioManifestGroup(
                    name = "Regional",
                    catalogs = listOf(groupedAdultCatalog),
                ),
            ),
        )

        assertEquals(
            (1..8).map { "regular-$it" } + listOf("hentai", "indian-18"),
            stremioHomeLayoutCatalogs("Mixed Catalogs", manifest).map { it.id },
        )
    }

    @Test
    fun adultAddonExposesItsCatalogsPastTheDefaultLimit() {
        val manifest = StremioManifest(
            id = "adult",
            name = "Catalogs",
            catalogs = (1..10).map { index ->
                StremioCatalogDef(
                    type = "movie",
                    id = "catalog-$index",
                    name = "Catalog $index",
                )
            },
        )

        assertEquals(
            (1..10).map { "catalog-$it" },
            stremioHomeLayoutCatalogs("Adult Movies", manifest).map { it.id },
        )
    }

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
