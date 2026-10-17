package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.stremio.StremioHomeRow
import org.junit.Assert.assertEquals
import org.junit.Test

class StremioHomeRowOrderingTest {
    @Test
    fun savedOrderIsAppliedAndNewCatalogsStayAtTheEnd() {
        val rows = listOf(row("first"), row("second"), row("third"))

        val ordered = orderStremioHomeRows(
            rows = rows,
            disabledCatalogsCsv = null,
            orderCsv = "addon:movie:third,addon:movie:first",
        )

        assertEquals(listOf("third", "first", "second"), ordered.map { it.catalogId })
    }

    @Test
    fun hiddenCatalogsAreExcludedFromTheSavedOrder() {
        val rows = listOf(row("first"), row("second"), row("third"))

        val ordered = orderStremioHomeRows(
            rows = rows,
            disabledCatalogsCsv = "addon:movie:third",
            orderCsv = "addon:movie:third,addon:movie:first",
        )

        assertEquals(listOf("first", "second"), ordered.map { it.catalogId })
    }

    private fun row(catalogId: String) = StremioHomeRow(
        addonId = "addon",
        addonName = "Test addon",
        catalogId = catalogId,
        catalogName = catalogId,
        type = "movie",
        items = emptyList(),
    )
}
