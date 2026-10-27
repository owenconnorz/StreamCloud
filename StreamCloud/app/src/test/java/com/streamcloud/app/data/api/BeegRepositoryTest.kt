package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeegRepositoryTest {
    @Test
    fun `maps public video records into adult items`() {
        val page = BeegRepository.parseVideoPage(
            """
            [
              {
                "file": {
                  "id": 12345,
                  "fl_duration": 95,
                  "data": [
                    {"cd_column": "sf_name", "cd_value": "Sample video"},
                    {"cd_column": "other", "cd_value": "ignored"}
                  ]
                },
                "fc_facts": [
                  {"fc_thumbs": [12, 24], "fc_st_views": 5432}
                ]
              }
            ]
            """.trimIndent(),
        )

        val item = page.items.single()
        assertEquals("12345", item.id)
        assertEquals("Sample video", item.title)
        assertEquals(AdultSource.Beeg, item.source)
        assertEquals("https://beeg.com/-012345", item.embedUrl)
        assertEquals(
            "https://thumbs.externulls.com/videos/12345/12.webp?size=480x270",
            item.thumbnail,
        )
        assertEquals("1:35", item.durationLabel)
        assertEquals("5432", item.views)
        assertFalse(page.hasMore)
    }

    @Test
    fun `ignores malformed records and records without a title`() {
        val page = BeegRepository.parseVideoPage(
            """
            [
              {"file":{"id":2,"data":[{"cd_column":"other","cd_value":"No title"}]}},
              {"file":{"id":0,"data":[{"cd_column":"sf_name","cd_value":"Invalid id"}]}}
            ]
            """.trimIndent(),
        )

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `search parser accepts only safe tag slugs with videos`() {
        val slugs = BeegRepository.parseSearchTags(
            """
            [
              {"tg_slug":"Alt_Rock","tg_videos_count":12},
              {"tg_slug":"empty","tg_videos_count":0},
              {"tg_slug":"bad/tag","tg_videos_count":8},
              {"tg_slug":"Alt_Rock","tg_videos_count":5}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("Alt_Rock"), slugs)
    }

    @Test
    fun `listing requests use the requested page offset`() {
        val first = BeegRepository.buildVideoListUrl(listOf("index"), page = 1)
        val next = BeegRepository.buildVideoListUrl(listOf("music", "rock"), page = 3)

        assertEquals("store.externulls.com", first.host)
        assertEquals("0", first.queryParameter("offset"))
        assertEquals("20", first.queryParameter("limit"))
        assertEquals("40", next.queryParameter("offset"))
        assertEquals("20", next.queryParameter("limit"))
    }
}
