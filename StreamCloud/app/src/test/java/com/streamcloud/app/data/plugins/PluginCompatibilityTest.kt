package com.streamcloud.app.data.plugins

import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.amapIndexed
import com.lagradost.cloudstream3.amapNotNull
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.utils.StringUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression and coverage tests for the CloudStream plugin-compatibility layer.
 */
class PluginCompatibilityTest {

    @Test
    fun adultSourcesAreRecognizedFromCloudStreamTypesAndNames() {
        assertTrue(installedPlugin(name = "Example", types = listOf("NSFW")).isAdultPlugin())
        assertTrue(installedPlugin(name = "XNXX", types = listOf("Movie")).isAdultPlugin())
        assertTrue(installedPlugin(name = "Example", types = listOf("HentaiSub")).isAdultPlugin())
        assertFalse(installedPlugin(name = "AnimeCloud", types = listOf("Anime")).isAdultPlugin())
        assertFalse(installedPlugin(name = "MovieLand", types = listOf("Movie")).isAdultPlugin())
    }

    @Test
    fun pickerClassifiesMoviesTvAnimeAndNsfwFromMetadata() {
        assertEquals(
            CloudStreamContentKind.MOVIE,
            detectCloudStreamContentKind(
                mediaType = "movie",
                isAdult = false,
                genreIds = listOf(28),
                genreNames = listOf("Action"),
                originalLanguage = "en",
                originCountries = listOf("US"),
                title = "Tenet",
            ),
        )
        assertEquals(
            CloudStreamContentKind.TV_SERIES,
            detectCloudStreamContentKind(
                mediaType = "tv",
                isAdult = false,
                genreIds = emptyList(),
                genreNames = listOf("Drama"),
                originalLanguage = "en",
                originCountries = listOf("US"),
                title = "Example Series",
            ),
        )
        assertEquals(
            CloudStreamContentKind.ANIME,
            detectCloudStreamContentKind(
                mediaType = "tv",
                isAdult = false,
                genreIds = listOf(16),
                genreNames = listOf("Animation"),
                originalLanguage = "ja",
                originCountries = listOf("JP"),
                title = "Example",
            ),
        )
        assertEquals(
            CloudStreamContentKind.NSFW,
            detectCloudStreamContentKind(
                mediaType = "movie",
                isAdult = true,
                genreIds = listOf(16),
                genreNames = listOf("Animation"),
                originalLanguage = "ja",
                originCountries = listOf("JP"),
                title = "Example",
            ),
        )
    }

    @Test
    fun pickerKeepsUnknownPluginTypesButAvoidsExplicitlyUnrelatedProviders() {
        val animeOnly = installedPlugin(name = "AnimeCloud", types = listOf("Anime"))
        val untyped = installedPlugin(name = "GeneralProvider", types = emptyList())
        val adult = installedPlugin(name = "AdultProvider", types = listOf("NSFW"))

        assertFalse(animeOnly.supportsPickerContent(CloudStreamContentKind.MOVIE))
        assertTrue(animeOnly.supportsPickerContent(CloudStreamContentKind.ANIME))
        assertTrue(untyped.supportsPickerContent(CloudStreamContentKind.MOVIE))
        assertFalse(adult.supportsPickerContent(CloudStreamContentKind.MOVIE))
        assertTrue(adult.supportsPickerContent(CloudStreamContentKind.NSFW))
        assertFalse(untyped.supportsPickerContent(CloudStreamContentKind.NSFW))
    }

    @Test
    fun pickerSkipsUnrelatedCloudStreamApisBeforeSearching() {
        val animeApi = object : MainAPI() {
            override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
        }
        val movieApi = object : MainAPI() {
            override val supportedTypes = setOf(TvType.Movie)
        }
        val misdeclaredMovieApi = object : MainAPI() {
            override val supportedTypes = setOf(TvType.AsianDrama)
        }

        assertFalse(animeApi.supportsPickerContent(CloudStreamContentKind.MOVIE))
        assertTrue(movieApi.supportsPickerContent(CloudStreamContentKind.MOVIE))
        assertTrue(animeApi.supportsPickerContent(CloudStreamContentKind.ANIME))
        assertTrue(movieApi.supportsPickerSearch(CloudStreamContentKind.TV_SERIES))
        assertFalse(animeApi.supportsPickerSearch(CloudStreamContentKind.TV_SERIES))
        assertTrue(
            selectPickerSearchApis(
                apis = listOf(misdeclaredMovieApi, animeApi),
                kind = CloudStreamContentKind.MOVIE,
            ).isEmpty(),
        )
        assertEquals(
            listOf(misdeclaredMovieApi),
            selectPickerSearchApis(
                apis = listOf(misdeclaredMovieApi, animeApi),
                kind = CloudStreamContentKind.MOVIE,
                allowMovieApiFallback = true,
            ),
        )
        assertEquals(
            listOf(movieApi),
            selectPickerSearchApis(
                apis = listOf(movieApi, misdeclaredMovieApi),
                kind = CloudStreamContentKind.MOVIE,
                allowMovieApiFallback = true,
            ),
        )
    }

    @Test
    fun repositoryFallbackAllowsMovieLabelsOrUnknownLegacyMetadata() {
        assertTrue(installedPlugin(name = "MovieProvider", types = listOf("Movie", "TvSeries"))
            .allowsPickerMovieApiFallback())
        assertTrue(installedPlugin(name = "LegacyProvider", types = emptyList())
            .allowsPickerMovieApiFallback())
        assertFalse(installedPlugin(name = "AnimeProvider", types = listOf("AnimeMovie"))
            .allowsPickerMovieApiFallback())
        assertFalse(installedPlugin(name = "SeriesProvider", types = listOf("TvSeries"))
            .allowsPickerMovieApiFallback())
        assertFalse(installedPlugin(name = "GeneralProvider", types = listOf("All"))
            .allowsPickerMovieApiFallback())
        assertFalse(installedPlugin(name = "AdultProvider", types = listOf("Movie", "NSFW"))
            .allowsPickerMovieApiFallback())
    }

    @Test
    fun pickerRejectsUnrelatedTitlesInsteadOfChoosingTheFirstSearchResult() {
        assertTrue(hasPlausibleCloudStreamTitleMatch("Tenet (2020)", "Tenet"))
        assertTrue(hasPlausibleCloudStreamTitleMatch("Meet the Parents", "Meet the Parents"))
        assertFalse(hasPlausibleCloudStreamTitleMatch("Baby Dragon I", "Tenet"))
    }

    @Test
    fun cloudStreamTabsHideOnlyAfterAnExplicitEmptyOutcome() {
        assertTrue(cloudStreamNoLinksConfirmed("No search results for \"Tenet\"."))
        assertTrue(cloudStreamNoLinksConfirmed("StreamPlay: API returned 0 results"))
        assertTrue(cloudStreamNoLinksConfirmed("No streams were returned by StreamPlay."))
        assertFalse(cloudStreamNoLinksConfirmed("No matching title found in MovieBoxProvider."))
        assertFalse(cloudStreamNoLinksConfirmed("No provider API declares support for movie."))
        assertFalse(cloudStreamNoLinksConfirmed("Plugin timed out after 120 seconds."))
        assertFalse(cloudStreamNoLinksConfirmed("NoSuchMethodError: missing plugin API"))
        assertFalse(cloudStreamNoLinksConfirmed("JsonSyntaxException: expected object but got array"))
        assertFalse(cloudStreamNoLinksConfirmed("NoClassDefFoundError: missing provider class"))
    }

    @Test
    fun pluginCompatibilitySurfaceIncludesReportedCloudStreamSymbols() {
        assertEquals("two%20words", StringUtils.encodeUrl("two words"))
        val response = NiceResponse(
            code = 200,
            text = "response body",
            headers = mapOf("X-Plugin-Header" to listOf("present")),
            url = "https://example.test",
        )
        assertEquals(
            "response body",
            response.getTextLarge(),
        )
        assertEquals("present", response.headers["X-Plugin-Header"])
        assertEquals("present", response.headerMap["X-Plugin-Header"]?.single())
        assertEquals(
            okhttp3.Headers::class.java,
            NiceResponse::class.java.getMethod("getHeaders").returnType,
        )

        val cloudflareClass = Class.forName("com.lagradost.cloudstream3.network.CloudflareKiller")
        assertTrue(
            okhttp3.Interceptor::class.java.isAssignableFrom(cloudflareClass),
        )
        val ktorUrlUtils = Class.forName("io.ktor.http.URLUtilsKt")
        val parsedUrl = ktorUrlUtils.getMethod("Url", String::class.java)
            .invoke(null, "https://example.test/path")
        assertEquals("example.test", parsedUrl.javaClass.getMethod("getHost").invoke(parsedUrl))
    }

    @Test
    fun pickerRejectsMovieSearchResponsesForTvShows() {
        val provider = object : MainAPI() {}
        val movie = provider.newMovieSearchResponse(name = "Tenet", url = "/tenet")
        assertTrue(searchResponseMatchesContentKind(movie, CloudStreamContentKind.MOVIE))
        assertFalse(searchResponseMatchesContentKind(movie, CloudStreamContentKind.TV_SERIES))
        assertTrue(searchResponseMatchesPickerFallback(movie, CloudStreamContentKind.TV_SERIES))
        assertFalse(searchResponseMatchesPickerFallback(movie, CloudStreamContentKind.MOVIE))
    }

    @Test
    fun legacyPluginHostActivityReferenceResolvesSafely() {
        assertEquals("com.lagradost.cloudstream3.MainActivity", MainActivity::class.java.name)
        MainActivity.afterPluginsLoadedEvent.invoke(true)
    }

    @Test
    fun sourceDiscoverySupportsPagedAndLegacyCloudStreamSearch() = runBlocking {
        val pagedProvider = object : MainAPI() {
            override suspend fun search(query: String, page: Int) =
                newSearchResponseList(
                    listOf(newMovieSearchResponse(name = "Paged result", url = "/paged")),
                    hasNext = false,
                )
        }
        val legacyProvider = object : MainAPI() {
            override suspend fun search(query: String): List<SearchResponse> =
                listOf(newMovieSearchResponse(name = "Legacy result", url = "/legacy"))
        }

        assertEquals(
            "Paged result",
            searchProviderForDiscovery(pagedProvider, "Example").single().name,
        )
        assertEquals(
            "Legacy result",
            searchProviderForDiscovery(legacyProvider, "Example").single().name,
        )
    }

    @Test
    fun sourceDiscoverySearchesMultipleApisConcurrently() = runBlocking {
        val activeSearches = AtomicInteger(0)
        val maximumConcurrentSearches = AtomicInteger(0)
        val apis = (1..6).map { index ->
            object : MainAPI() {
                override suspend fun search(query: String, page: Int) = try {
                    val active = activeSearches.incrementAndGet()
                    maximumConcurrentSearches.updateAndGet { maxOf(it, active) }
                    delay(100)
                    newSearchResponseList(
                        listOf(newMovieSearchResponse(name = "Result $index", url = "/$index")),
                        hasNext = false,
                    )
                } finally {
                    activeSearches.decrementAndGet()
                }
            }
        }

        val outcomes = searchApisForDiscovery(
            apis = apis,
            query = "Example",
            maxConcurrent = 4,
            timeoutMs = 1_000,
        )

        assertEquals(6, outcomes.sumOf { it.results.size })
        assertEquals(4, maximumConcurrentSearches.get())
    }

    @Test
    fun amapOnListReceiverDoublesEveryElement() = runBlocking {
        val input: List<Int> = listOf(1, 2, 3, 4, 5)
        val result = input.amap { it * 2 }
        assertEquals(listOf(2, 4, 6, 8, 10), result)
    }

    @Test
    fun parCollectionsKtExposesAmapWithListParameter() {
        val parCollectionsClass = Class.forName("com.lagradost.cloudstream3.ParCollectionsKt")
        val amapMethods = parCollectionsClass.methods.filter { it.name == "amap" }
        assertTrue(
            "ParCollectionsKt must have at least one method named exactly 'amap'",
            amapMethods.isNotEmpty(),
        )
        val listOverload = amapMethods.firstOrNull { m ->
            m.parameterTypes.isNotEmpty() && m.parameterTypes.first() == java.util.List::class.java
        }
        assertNotNull("ParCollectionsKt must expose amap(java.util.List, ...)", listOverload)
    }

    @Test
    fun amapIndexedOnListReceiverUsesIndexAndValue() = runBlocking {
        val input = listOf("a", "b", "c")
        val result = input.amapIndexed { index, value -> "$index:$value" }
        assertEquals(listOf("0:a", "1:b", "2:c"), result)
    }

    @Test
    fun parCollectionsKtExposesAmapIndexedWithListParameter() {
        val parCollectionsClass = Class.forName("com.lagradost.cloudstream3.ParCollectionsKt")
        val methods = parCollectionsClass.methods.filter { it.name == "amapIndexed" }
        assertTrue(
            "ParCollectionsKt must have at least one method named exactly 'amapIndexed'",
            methods.isNotEmpty(),
        )
        val listOverload = methods.firstOrNull { m ->
            m.parameterTypes.isNotEmpty() && m.parameterTypes.first() == java.util.List::class.java
        }
        assertNotNull("ParCollectionsKt must expose amapIndexed(java.util.List, ...)", listOverload)
    }

    @Test
    fun amapNotNullDropsNulls() = runBlocking {
        val input = listOf(1, 2, 3, 4)
        val result = input.amapNotNull { n -> if (n % 2 == 0) n else null }
        assertEquals(listOf(2, 4), result)
        assertFalse(result.contains(1))
    }

    private fun installedPlugin(name: String, types: List<String>) = InstalledPlugin(
        name = name,
        internalName = name,
        version = 1,
        filePath = "",
        sourceRepoId = "",
        sourceUrl = "",
        installedAt = 0L,
        tvTypes = types,
    )
}
