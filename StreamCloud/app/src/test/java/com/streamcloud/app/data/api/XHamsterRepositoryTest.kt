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
    fun parsesCategoryListingVideosAndPagination() {
        val page = parseXHamsterListing(
            html = pageHtml(
                """
                {
                  "perPage": 1,
                  "pagesCategoryComponent": {
                    "trendingVideoListProps": {
                      "videoThumbProps": [{
                        "pageURL": "/videos/category-clip",
                        "title": "Category result",
                        "thumbURL": "https://ic-vt-nss.xhcdn.com/category.webp",
                        "duration": 90
                      }]
                    },
                    "paginationProps": {
                      "currentPageNumber": 1,
                      "lastPageNumber": 3
                    }
                  }
                }
                """.trimIndent(),
            ),
            requestedPage = 1,
        )

        assertEquals("category-clip", page.items.single().id)
        assertEquals("Category result", page.items.single().title)
        assertTrue(page.hasMore)
    }

    @Test
    fun parsesOnlyMainSiteCategoryCardsFromOfficialCatalog() {
        val categories = parseXHamsterCategories(
            """
            <html><body>
              <a href="/categories/amateur" class="nav">Amateur</a>
              <a href="https://xhamster.com/categories/amateur">
                <img src="https://ic-tt-nss.xhcdn.com/amateur.webp" alt="Amateur">
                <h3>Amateur</h3>
              </a>
              <a href="/gay/categories/big-cock">
                <img data-src="https://ic-tt-nss.xhcdn.com/gay.webp" alt="Big Cock">
              </a>
              <a href="https://outside.example/categories/ignored">
                <img src="https://outside.example/ignored.webp" alt="Ignored">
              </a>
            </body></html>
            """.trimIndent(),
        )

        assertEquals(1, categories.size)
        assertEquals("/categories/amateur", categories[0].path)
        assertEquals("https://ic-tt-nss.xhcdn.com/amateur.webp", categories[0].thumbnail)
    }

    @Test
    fun usesTheOfficialMenuCategoryUrl() {
        assertEquals(
            "https://xhamster.com/categories?from=menu",
            buildXHamsterCategoriesUrl(),
        )
    }

    @Test
    fun reportsOfficialAgeAndRegionGateInsteadOfEmptyCategories() {
        val error = runCatching {
            parseXHamsterCategories(
                """
                <html><body>
                  <h1>Join xHamster for free</h1>
                  <p>You need to log in/sign up so you don't have to go through verification process again</p>
                  <p>Do you want to see content for United States?</p>
                </body></html>
                """.trimIndent(),
            )
        }.exceptionOrNull()

        assertTrue(error is XHamsterUnavailableException)
        assertTrue(error?.message.orEmpty().contains("official age or region check"))
    }

    @Test
    fun putsOfficialPopularCategoriesFirstAndKeepsCategoryImages() {
        val categories = parseXHamsterCategories(
            """
            <html><body>
              <nav>
                <a href="/categories/behind-the-scenes">Behind the Scenes</a>
              </nav>
              <section id="trending">
                <a href="/categories/mature">
                  <img src="https://ic-tt-nss.xhcdn.com/mature.webp" alt="Mature">
                  <h3>Mature</h3>
                </a>
                <a href="/categories/amateur">
                  <img src="https://ic-tt-nss.xhcdn.com/amateur.webp" alt="Amateur">
                </a>
              </section>
              <section id="production">
                <a href="/categories/behind-the-scenes">
                  <img data-src="https://ic-tt-nss.xhcdn.com/behind.webp" alt="Behind the Scenes">
                </a>
              </section>
            </body></html>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "/categories/mature",
                "/categories/amateur",
                "/categories/behind-the-scenes",
            ),
            categories.map(XHamsterCategory::path),
        )
        assertEquals(
            "https://ic-tt-nss.xhcdn.com/behind.webp",
            categories.last().thumbnail,
        )
    }

    @Test
    fun keepsAllOfficialCategoryDirectoryPagesAvailableForTheFullList() {
        val paths = parseXHamsterCategoryIndexPaths(
            """
            <html><body>
              <a href="/categories">All</a>
              <a href="/categories-0">#</a>
              <a href="/categories-a">A</a>
              <a href="https://xhamster.com/categories-z?from=menu">Z</a>
              <a href="/categories-4k">4K</a>
              <a href="/categories-vr">VR</a>
              <a href="/gay/categories">Gay</a>
              <a href="https://outside.example/categories-a">External</a>
            </body></html>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "/categories",
                "/categories-0",
                "/categories-4k",
                "/categories-a",
                "/categories-vr",
                "/categories-z",
            ),
            paths,
        )
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

    @Test
    fun buildsOfficialCategoryListingAndPaginationUrls() {
        assertEquals(
            "https://xhamster.com/categories/amateur/3",
            buildXHamsterListingUrl("", 3, "/categories/amateur"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGaySpecificCategoryPaths() {
        buildXHamsterListingUrl("", 1, "/gay/categories/big-cock")
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
