package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XHamsterRepositoryTest {

    @Test
    fun parsesSearchResultsAndProviderPagination() {
        val page = parseXHamsterListing(
            html = pageHtml(
                """
                {
                  "page": 1,
                  "perPage": 1,
                  "paginationComponent": {
                    "currentPageNumber": 1,
                    "lastPageNumber": 4
                  },
                  "searchResult": {
                    "videoThumbProps": [{
                      "pageURL": "https://xhamster.com/videos/xhTest_7",
                      "title": "Example title",
                      "imageURL": "https://ic-vt-nss.xhcdn.com/example.webp",
                      "previewThumbURL": "https://ic-vt-nss.xhcdn.com/preview.webp",
                      "duration": 3661,
                      "views": 1234,
                      "landing": { "name": "Example creator" }
                    }]
                  }
                }
                """.trimIndent(),
            ),
            requestedPage = 1,
        )

        assertEquals(1, page.items.size)
        assertEquals("xhTest_7", page.items.single().id)
        assertEquals("Example title", page.items.single().title)
        assertEquals("1:01:01", page.items.single().durationLabel)
        assertEquals("1234", page.items.single().views)
        assertEquals("Example creator", page.items.single().tags)
        assertEquals(AdultSource.XHamster, page.items.single().source)
        assertTrue(page.hasMore)
    }

    @Test
    fun parsesHomeFeedShapeWithoutSearchPagination() {
        val page = parseXHamsterListing(
            html = pageHtml(
                """
                {
                  "layoutPage": {
                    "pageNum": 2,
                    "videoListProps": {
                      "videoThumbProps": [{
                        "pageURL": "/videos/clip-52",
                        "title": "Home feed item",
                        "thumbURL": "https://ic-vt-nss.xhcdn.com/thumb.webp",
                        "duration": 92
                      }]
                    }
                  }
                }
                """.trimIndent(),
            ),
            requestedPage = 2,
        )

        assertEquals("clip-52", page.items.single().id)
        assertEquals("https://xhamster.com/videos/clip-52", page.items.single().embedUrl)
        assertEquals("1:32", page.items.single().durationLabel)
        assertFalse(page.hasMore)
    }

    @Test
    fun rejectsNonProviderVideoLinks() {
        val page = parseXHamsterListing(
            html = pageHtml(
                """
                {
                  "searchResult": {
                    "videoThumbProps": [{
                      "pageURL": "https://not-xhamster.example/videos/other",
                      "title": "Ignored"
                    }]
                  }
                }
                """.trimIndent(),
            ),
            requestedPage = 1,
        )

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun encodesSearchQueryAsOnePathSegment() {
        assertEquals(
            "https://xhamster.com/search/a%20b?page=3",
            buildXHamsterListingUrl("a b", 3),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeVideoIdentifiers() {
        XHamsterRepository.videoPageUrl("../other-site")
    }

    private fun pageHtml(json: String) = """
        <html>
          <body>
            <script id="initials-script" type="application/json">
              $json
            </script>
          </body>
        </html>
    """.trimIndent()
}
