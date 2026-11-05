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

    @Test
    fun `parses Beeg homepage recommendations as safe tags with thumbnails`() {
        val tags = BeegRepository.parseRecommendedTags(
            """
            [
              {
                "id": 26750,
                "tg_name": "Sweetie Fox",
                "tg_slug": "SweetieFox",
                "thumbs": [
                  {"id": 100485375801667, "crops": [{"id": 1936002}]}
                ]
              },
              {
                "id": 5,
                "tg_name": "Bad slug",
                "tg_slug": "../unsafe",
                "thumbs": []
              },
              {
                "id": {"unexpected": true},
                "tg_name": "Malformed record",
                "tg_slug": "Malformed"
              },
              {
                "id": 26750,
                "tg_name": "Duplicate",
                "tg_slug": "SweetieFox",
                "thumbs": []
              }
            ]
            """.trimIndent(),
        )

        assertEquals(1, tags.size)
        assertEquals("Sweetie Fox", tags.single().name)
        assertEquals("SweetieFox", tags.single().slug)
        assertEquals(
            "https://thumbs.externulls.com/photos/100485375801667/to.webp" +
                "?crop_id=1936002&size_new=112x112",
            tags.single().thumbnailUrl,
        )
    }

    @Test
    fun `tag video listings use validated tag slug and requested page offset`() {
        val first = BeegRepository.buildVideoListUrl(listOf("SweetieFox"), page = 1)
        val next = BeegRepository.buildVideoListUrl(listOf("SweetieFox"), page = 4)

        assertEquals("/tag/videos/sweetiefox", first.encodedPath)
        assertEquals("0", first.queryParameter("offset"))
        assertEquals("60", next.queryParameter("offset"))
    }

    @Test
    fun `full Beeg page advertises another page for infinite scrolling`() {
        val records = (1..20).joinToString(",") { id ->
            """{"file":{"id":$id,"data":[{"cd_column":"sf_name","cd_value":"Video $id"}]}}"""
        }

        val page = BeegRepository.parseVideoPage("[$records]")

        assertEquals(20, page.items.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `play url response becomes secure Beeg-hosted native stream`() {
        val url = BeegRepository.buildNativeStreamUrl(
            "key=MOvnrjZiJkpBZicVEJyTWA,end=1793814578,limit=10/data=a038903d34.m3u8",
        )

        assertEquals(
            "https://video.beeg.com/key=MOvnrjZiJkpBZicVEJyTWA,end=1793814578,limit=10/data=a038903d34.m3u8",
            url,
        )
    }

    @Test
    fun `current Beeg multi rendition HLS response becomes a secure native stream`() {
        val response =
            "key=QP-yzQNOkcIHTLuc4FqqTA,end=1793825111,limit=10" +
                "/data=059ebed732/media=hls4A" +
                "/multi=426x240:240p:YWJj,640x360:360p:ZGVm" +
                "/_TPL_/478282460755444.mp4.m3u8"

        assertEquals(
            "https://video.beeg.com/$response",
            BeegRepository.buildNativeStreamUrl(response),
        )
    }

    @Test
    fun `play url parser rejects non-provider paths and malformed values`() {
        assertEquals(
            null,
            BeegRepository.buildNativeStreamUrl(
                "https://untrusted.example/video.m3u8",
            ),
        )
        assertEquals(
            null,
            BeegRepository.buildNativeStreamUrl(
                "key=abc,end=1,limit=10/data=../../video.m3u8",
            ),
        )
        assertEquals(
            null,
            BeegRepository.buildNativeStreamUrl(
                "key=QP-yzQNOkcIHTLuc4FqqTA,end=1793825111,limit=10" +
                    "/data=059ebed732/media=hls4A" +
                    "/multi=426x240:240p:YWJj" +
                    "/_TPL_/../../../evil.mp4.m3u8",
            ),
        )
    }

    @Test
    fun `only secure Beeg video page URLs are accepted for stream resolution`() {
        assertEquals(
            "https://beeg.com/-012345",
            BeegRepository.normalizeBeegVideoPageUrl("https://beeg.com/-012345"),
        )
        assertEquals(
            null,
            BeegRepository.normalizeBeegVideoPageUrl("https://example.com/-012345"),
        )
        assertEquals(
            null,
            BeegRepository.normalizeBeegVideoPageUrl("https://beeg.com/account"),
        )
    }
}
