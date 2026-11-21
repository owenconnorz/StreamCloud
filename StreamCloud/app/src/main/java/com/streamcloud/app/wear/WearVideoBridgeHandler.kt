package com.streamcloud.app.wear

import android.content.Context
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.AdultWebsiteRepository
import com.streamcloud.app.data.api.BeegRepository
import com.streamcloud.app.data.api.EpornerApi
import com.streamcloud.app.data.api.EpornerPlaybackResolver
import com.streamcloud.app.data.api.PornhubRepository
import com.streamcloud.app.data.api.RedGifsRepository
import com.streamcloud.app.data.api.RedditAdultRepository
import com.streamcloud.app.data.api.XFreeRepository
import com.streamcloud.app.data.api.XHamsterRepository
import com.streamcloud.app.data.api.XnxxRepository
import com.streamcloud.app.data.api.PornMzRepository
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.network.Net
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioStream
import com.streamcloud.app.data.nuvio.InstalledNuvioProvider
import com.streamcloud.app.data.nuvio.NuvioStream
import com.streamcloud.app.data.api.AdultItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Phone-side catalog and stream resolver for the Wear companion.
 *
 * Provider credentials, browser cookies, and age/PIN settings remain on the
 * phone. Only provider-declared playable URLs and non-sensitive request headers
 * are returned for the current watch playback session.
 */
internal object WearVideoBridgeHandler {
    private const val MAX_CONCURRENT_PROVIDER_REQUESTS = 3
    private const val MAX_MOVIE_STREAMS = 15
    private const val MAX_STREAMS_PER_PROVIDER = 5

    suspend fun handle(context: Context, request: JSONObject): JSONObject {
        val services = ServiceLocator.get(context.applicationContext)
        return when (request.optString("operation")) {
            "movies" -> moviePage(
                services = services,
                query = request.optString("query").trim(),
                page = request.optInt("page", 1).coerceAtLeast(1),
            )
            "movieBrowse" -> movieBrowse(
                services = services,
                category = request.optString("category", "popular"),
                genreId = request.optInt("genreId").takeIf { it > 0 },
                page = request.optInt("page", 1).coerceAtLeast(1),
            )
            "movieDetails" -> movieDetails(
                services = services,
                tmdbId = request.optLong("tmdbId"),
            )
            "continueWatching" -> continueWatching(context.applicationContext)
            "downloads" -> downloads(context.applicationContext)
            "movieStreams" -> movieStreams(
                context = context.applicationContext,
                services = services,
                tmdbId = request.optLong("tmdbId"),
                title = request.optString("title"),
                year = request.optInt("year").takeIf { it > 0 },
            )
            "adultPage" -> adultPage(
                services = services,
                query = request.optString("query").trim(),
                page = request.optInt("page", 1).coerceAtLeast(1),
                pin = request.optString("pin"),
                after = request.optString("after").takeIf(String::isNotBlank),
            )
            "adultPlay" -> adultPlay(
                context = context.applicationContext,
                services = services,
                sourceName = request.optString("source"),
                videoId = request.optString("videoId"),
                pageUrl = request.optString("pageUrl"),
                directUrl = request.optString("directUrl"),
                pin = request.optString("pin"),
            )
            else -> JSONObject().put("ok", false).put("message", "Unknown video request.")
        }
    }

    private suspend fun moviePage(
        services: ServiceLocator,
        query: String,
        page: Int,
    ): JSONObject {
        val response = if (query.isBlank()) {
            services.tmdb.popular(services.tmdbApiKey, page)
        } else {
            services.tmdb.search(services.tmdbApiKey, query, page)
        }
        val movies = JSONArray(response.results.take(30).map(::serializeMovie))
        return JSONObject()
            .put("ok", true)
            .put("items", movies)
            .put("page", response.page)
            .put("hasMore", response.page < response.totalPages)
    }

    private suspend fun movieBrowse(
        services: ServiceLocator,
        category: String,
        genreId: Int?,
        page: Int,
    ): JSONObject {
        val response = when (category) {
            "trending" -> services.tmdb.trending("week", services.tmdbApiKey, page)
            "top_rated" -> services.tmdb.topRated(services.tmdbApiKey, page)
            "genre" -> {
                require(genreId != null) { "Choose a movie genre first." }
                services.tmdb.discover(
                    apiKey = services.tmdbApiKey,
                    withGenres = genreId.toString(),
                    page = page,
                )
            }
            else -> services.tmdb.popular(services.tmdbApiKey, page)
        }
        return JSONObject()
            .put("ok", true)
            .put("items", JSONArray(response.results.take(30).map(::serializeMovie)))
            .put("page", response.page)
            .put("hasMore", response.page < response.totalPages)
    }

    private suspend fun movieDetails(
        services: ServiceLocator,
        tmdbId: Long,
    ): JSONObject {
        require(tmdbId > 0L) { "Choose a movie first." }
        val details = services.tmdb.details(tmdbId, services.tmdbApiKey)
        val credits = runCatching {
            services.tmdb.movieCredits(tmdbId, services.tmdbApiKey)
        }.getOrNull()
        val similar = runCatching {
            services.tmdb.movieSimilar(tmdbId, services.tmdbApiKey, 1)
        }.getOrNull()
        val cast = JSONArray(
            credits?.cast.orEmpty().take(6).map { person ->
                JSONObject()
                    .put("name", person.name)
                    .put("role", person.character.orEmpty())
                    .put("artwork", person.profileUrl.orEmpty())
            },
        )
        return JSONObject()
            .put("ok", true)
            .put("id", details.id)
            .put("title", details.displayTitle)
            .put("overview", details.overview.orEmpty())
            .put("artwork", details.backdropUrl ?: details.posterUrl.orEmpty())
            .put("poster", details.posterUrl.orEmpty())
            .put("year", details.displayReleaseInfo()?.take(4)?.toIntOrNull() ?: 0)
            .put("runtime", details.displayRuntime().orEmpty())
            .put("rating", details.voteAverage)
            .put("votes", details.voteCount)
            .put("genres", JSONArray(details.genres.map { it.name }))
            .put("director", credits?.crew.orEmpty().firstOrNull { it.job == "Director" }?.name.orEmpty())
            .put("cast", cast)
            .put("similar", JSONArray(similar?.results.orEmpty().take(8).map(::serializeMovie)))
    }

    private suspend fun continueWatching(context: Context): JSONObject {
        val entries = LibraryDb.get(context).watchProgress().continueWatching().first()
        val items = JSONArray(entries.map { entry ->
            val percent = if (entry.durationMs > 0L) {
                ((entry.positionMs.toDouble() / entry.durationMs) * 100).toInt().coerceIn(0, 100)
            } else {
                null
            }
            val episodeLabel = if (entry.seasonNumber > 0 && entry.episodeNumber > 0) {
                "S${entry.seasonNumber} E${entry.episodeNumber}"
            } else {
                "Movie"
            }
            JSONObject()
                .put("id", entry.tmdbId)
                .put("title", entry.title)
                .put("artwork", entry.posterUrl.orEmpty())
                .put("mediaType", entry.mediaType)
                .put("seasonNumber", entry.seasonNumber)
                .put("episodeNumber", entry.episodeNumber)
                .put("positionMs", entry.positionMs)
                .put("durationMs", entry.durationMs)
                .put("progress", percent ?: JSONObject.NULL)
                .put("subtitle", listOfNotNull(episodeLabel, percent?.let { "$it% watched" }).joinToString(" · "))
                .put("watchSupported", entry.mediaType == "movie" && entry.sourceRoute.isNullOrBlank())
        })
        return JSONObject().put("ok", true).put("items", items)
    }

    private suspend fun downloads(context: Context): JSONObject {
        val db = LibraryDb.get(context)
        val movieItems = db.movieDownloads().latestForWear().map { download ->
            JSONObject()
                .put("id", download.tmdbId)
                .put("title", download.title)
                .put("artwork", download.posterUrl.orEmpty())
                .put("mediaType", download.mediaType)
                .put("kind", "video")
                .put("status", download.status)
                .put("progress", (download.progress * 100).toInt().coerceIn(0, 100))
                .put("sizeBytes", download.sizeBytes)
                .put("subtitle", "${download.status.replaceFirstChar { it.uppercase() }} · ${download.sizeBytes.formatBytes()}")
                .put("availableOffline", download.status == "done" && !download.filePath.isNullOrBlank())
        }
        val musicItems = db.tracks().downloadedForWear().map { track ->
            JSONObject()
                .put("id", "${track.title}:${track.artist}".hashCode().toString())
                .put("title", track.title)
                .put("artist", track.artist)
                .put("artwork", track.thumbnail.orEmpty())
                .put("kind", "music")
                .put("status", "downloaded")
                .put("subtitle", listOf(track.artist, "Available offline on phone").filter(String::isNotBlank).joinToString(" · "))
                .put("availableOffline", true)
        }
        return JSONObject()
            .put("ok", true)
            .put("items", JSONArray(movieItems + musicItems))
            .put("phoneOnly", true)
    }

    private suspend fun movieStreams(
        context: Context,
        services: ServiceLocator,
        tmdbId: Long,
        title: String,
        year: Int?,
    ): JSONObject {
        require(tmdbId > 0L) { "Choose a movie first." }
        val details = services.tmdb.details(tmdbId, services.tmdbApiKey)
        val imdbId = runCatching {
            services.tmdb.externalIds(tmdbId, services.tmdbApiKey).imdbId
        }.getOrNull()
        val providerRequestGate = Semaphore(MAX_CONCURRENT_PROVIDER_REQUESTS)
        val streams = coroutineScope {
            val stremioJob = async {
                val addons = services.stremio.addons.first()
                collectStremioStreams(services, addons, imdbId, tmdbId, providerRequestGate)
            }
            val nuvioJob = async {
                val providers = services.nuvio.installed.first()
                collectNuvioStreams(services, providers, tmdbId, imdbId, providerRequestGate)
            }
            val pluginJob = async {
                val plugins = services.plugins.installed.first().filterNot(InstalledPlugin::isAdultPlugin)
                collectPluginStreams(
                    context,
                    plugins,
                    title.ifBlank { details.displayTitle },
                    year,
                    providerRequestGate,
                )
            }
            awaitAll(stremioJob, nuvioJob, pluginJob).flatten()
                .distinctBy { it.optString("url") }
                .take(MAX_MOVIE_STREAMS)
        }
        return JSONObject()
            .put("ok", true)
            .put("title", details.displayTitle)
            .put("overview", details.overview.orEmpty())
            .put("artwork", details.backdropUrl ?: details.posterUrl.orEmpty())
            .put("streams", JSONArray(streams))
            .put(
                "message",
                if (streams.isEmpty()) {
                    "No direct watch-ready streams were found. Check your phone’s installed providers."
                } else {
                    ""
                },
            )
    }

    private suspend fun collectStremioStreams(
        services: ServiceLocator,
        addons: List<InstalledStremioAddon>,
        imdbId: String?,
        tmdbId: Long,
        requestGate: Semaphore,
    ): List<JSONObject> = coroutineScope {
        addons.map { addon ->
            async {
                val ids = buildList {
                    imdbId?.takeIf(String::isNotBlank)?.let(::add)
                    add("tmdb:$tmdbId")
                }.distinct()
                ids.flatMap { id ->
                    withTimeoutOrNull(20_000L) {
                        requestGate.withPermit {
                            runCatching {
                                services.stremio.fetchStreams(addon, "movie", id)
                                    .asSequence()
                                    .mapNotNull { it.toWearStream(addon.name) }
                                    .take(MAX_STREAMS_PER_PROVIDER)
                                    .toList()
                            }.getOrDefault(emptyList())
                        }
                    }.orEmpty()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun collectNuvioStreams(
        services: ServiceLocator,
        providers: List<InstalledNuvioProvider>,
        tmdbId: Long,
        imdbId: String?,
        requestGate: Semaphore,
    ): List<JSONObject> = coroutineScope {
        providers.map { provider ->
            async {
                withTimeoutOrNull(30_000L) {
                    requestGate.withPermit {
                        runCatching {
                            services.nuvio.resolveSingle(
                                provider = provider,
                                tmdbId = tmdbId.toString(),
                                mediaType = "movie",
                                season = null,
                                episode = null,
                                imdbId = imdbId,
                            )
                                .asSequence()
                                .mapNotNull { it.toWearStream(provider.name) }
                                .take(MAX_STREAMS_PER_PROVIDER)
                                .toList()
                        }.getOrDefault(emptyList())
                    }
                }.orEmpty()
            }
        }.awaitAll().flatten()
    }

    private suspend fun collectPluginStreams(
        context: Context,
        plugins: List<InstalledPlugin>,
        title: String,
        year: Int?,
        requestGate: Semaphore,
    ): List<JSONObject> = coroutineScope {
        if (title.isBlank()) return@coroutineScope emptyList()
        plugins.map { plugin ->
            async {
                withTimeoutOrNull(30_000L) {
                    requestGate.withPermit {
                        runCatching {
                            val results = PluginRuntime.search(context, plugin.filePath, title)
                            val cleanTitle = title.normalizedTitle()
                            val best = results.maxByOrNull { result ->
                                val candidate = result.name.normalizedTitle()
                                when {
                                    candidate == cleanTitle -> 3
                                    candidate.contains(cleanTitle) || cleanTitle.contains(candidate) -> 2
                                    else -> 0
                                }
                            }?.takeIf { result ->
                                val candidate = result.name.normalizedTitle()
                                candidate == cleanTitle ||
                                    candidate.contains(cleanTitle) ||
                                    cleanTitle.contains(candidate)
                            } ?: return@runCatching emptyList()
                            val detail = PluginRuntime.loadDetail(context, plugin.filePath, best.url)
                                ?: return@runCatching emptyList()
                            val data = when (detail) {
                                is MovieLoadResponse -> detail.dataUrl
                                is TvSeriesLoadResponse -> detail.episodes.singleOrNull()?.data
                                is AnimeLoadResponse -> detail.episodes.values.flatten().singleOrNull()?.data
                                else -> null
                            } ?: return@runCatching emptyList()
                            PluginRuntime.loadLinks(context, plugin.filePath, data, isCasting = false)
                                .first
                                .asSequence()
                                .filterNot { it.url.startsWith("magnet:", ignoreCase = true) }
                                .mapNotNull { link ->
                                    val url = safeHttpUrl(link.url) ?: return@mapNotNull null
                                    val headers = link.headers.toMutableMap()
                                    if (link.referer.isNotBlank()) headers["Referer"] = link.referer
                                    JSONObject()
                                        .put("title", link.name.ifBlank { link.source.ifBlank { plugin.name } })
                                        .put("provider", plugin.name)
                                        .put("quality", link.quality.takeIf { it > 0 }?.let { "${it}p" }.orEmpty())
                                        .put("url", url)
                                        .put("headers", JSONObject(headers.filterKeys(::isSafeWatchHeader)))
                                }
                                .take(MAX_STREAMS_PER_PROVIDER)
                                .toList()
                        }.getOrDefault(emptyList())
                    }
                }.orEmpty()
            }
        }.awaitAll().flatten()
    }

    private fun serializeMovie(movie: TmdbMovie): JSONObject =
        JSONObject()
            .put("id", movie.id)
            .put("title", movie.displayTitle)
            .put("overview", movie.overview.orEmpty())
            .put("artwork", movie.backdropUrl ?: movie.posterUrl.orEmpty())
            .put("poster", movie.posterUrl.orEmpty())
            .put("subtitle", listOfNotNull(
                movie.displayReleaseInfo(),
                movie.voteAverage.takeIf { it > 0 }?.let { "★ ${"%.1f".format(it)}" },
            ).joinToString(" · "))
            .put("year", movie.displayReleaseInfo()?.take(4)?.toIntOrNull() ?: 0)
            .put("rating", movie.voteAverage)
            .put("votes", movie.voteCount)
            .put("mediaType", "movie")

    private suspend fun adultPage(
        services: ServiceLocator,
        query: String,
        page: Int,
        pin: String,
        after: String?,
    ): JSONObject {
        requireAdultAuthorization(services, pin)
        val source = AdultSource.valueOf(services.settings.adultSource.first())
        var nextCursor: String? = null
        val (items, hasMore) = when (source) {
            AdultSource.Eporner -> {
                val api = Net.retrofit("https://www.eporner.com/").create(EpornerApi::class.java)
                val result = api.search(query, perPage = 24, page = page)
                result.videos.map { video ->
                    AdultItem(
                        id = video.id,
                        title = video.title,
                        thumbnail = video.defaultThumb?.src,
                        previewImage = video.defaultThumb?.src,
                        durationLabel = video.lengthMin,
                        streamUrl = video.bestMp4(),
                        source = source,
                        embedUrl = video.embed,
                        views = video.views.toString(),
                    )
                } to (result.total_count > page * result.per_page || result.videos.size >= result.per_page)
            }
            AdultSource.Pornhub -> PornhubRepository.fetch(query, page).let { it.items to it.hasMore }
            AdultSource.XHamster -> XHamsterRepository.fetch(
                query = query,
                page = page,
                orientation = com.streamcloud.app.data.api.XHamsterOrientation.fromPreference(
                    services.settings.xhamsterOrientation.first(),
                ),
            ).let { it.items to it.hasMore }
            AdultSource.XFree -> XFreeRepository.fetch(query, page).let { it.items to it.hasMore }
            AdultSource.PornMz -> PornMzRepository.fetchPage(query, page).let { it.items to it.hasMore }
            AdultSource.Beeg -> BeegRepository.fetchPage(query, page).let { it.items to it.hasMore }
            AdultSource.Xnxx -> XnxxRepository.fetchPage(query, page).let { it.items to it.hasMore }
            AdultSource.Porn4Days, AdultSource.PornHd3x, AdultSource.PornSlash ->
                AdultWebsiteRepository.fetchPage(source, query, page).let { it.items to it.hasMore }
            AdultSource.Reddit -> {
                val subreddit = services.settings.adultRedditSubsCsv.first()
                    .split(',', '\n').firstOrNull(String::isNotBlank)?.trim()
                    ?: "nsfw"
                val (videos, cursor) = RedditAdultRepository.fetch(
                    subreddit = subreddit,
                    after = after,
                )
                nextCursor = cursor
                videos to (cursor != null)
            }
            AdultSource.RedGifs -> RedGifsRepository.fetchTrending(page = page, count = 24)
        }
        val serialized = JSONArray()
        items.take(if (source == AdultSource.Reddit) 50 else 18).forEach { item ->
            serialized.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("subtitle", listOfNotNull(item.durationLabel, item.views?.takeIf(String::isNotBlank)?.let { "$it views" }).joinToString(" · "))
                    .put("artwork", item.thumbnail.orEmpty())
                    .put("source", item.source.name)
                    .put("pageUrl", item.embedUrl.orEmpty())
                    .put(
                        "directUrl",
                        if (item.source == AdultSource.Reddit || item.source == AdultSource.RedGifs) {
                            safeHttpUrl(item.streamUrl).orEmpty()
                        } else {
                            ""
                        },
                    )
                    .put("playable", item.isVideo)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("source", source.label)
            .put("items", serialized)
            .put("page", page)
            .put("hasMore", hasMore)
            .put("nextCursor", nextCursor.orEmpty())
            .put("message", if (items.isEmpty()) "No videos found for this provider." else "")
    }

    private suspend fun adultPlay(
        context: Context,
        services: ServiceLocator,
        sourceName: String,
        videoId: String,
        pageUrl: String,
        directUrl: String,
        pin: String,
    ): JSONObject {
        requireAdultAuthorization(services, pin)
        val source = AdultSource.valueOf(sourceName)
        val playback = when (source) {
            AdultSource.Eporner -> EpornerPlaybackResolver.resolve(videoId, pageUrl)
                .let { it.url to it.headers }
            AdultSource.Pornhub -> PornhubRepository.resolve(videoId, pageUrl)
                .let { it.url to it.headers }
            AdultSource.Beeg -> BeegRepository.resolvePlayback(
                pageUrl,
                AdultWebsiteRepository.browserUserAgent(context),
            ).let { it.url to it.headers }
            AdultSource.Xnxx -> XnxxRepository.resolvePlayback(
                pageUrl,
                AdultWebsiteRepository.browserUserAgent(context),
            ).let { it.url to it.headers }
            AdultSource.Porn4Days, AdultSource.PornHd3x, AdultSource.PornSlash ->
                AdultWebsiteRepository.resolvePlayback(
                    source,
                    pageUrl,
                    AdultWebsiteRepository.browserUserAgent(context),
                ).let { it.url to it.headers }
            AdultSource.Reddit, AdultSource.RedGifs -> {
                require(isAllowedDirectAdultUrl(source, directUrl)) {
                    "${source.label} did not provide a supported direct video URL."
                }
                directUrl to emptyMap()
            }
            AdultSource.XHamster, AdultSource.XFree, AdultSource.PornMz ->
                error("${source.label} requires its official phone player and cannot be streamed directly on the watch.")
        }
        val streamUrl = safeHttpUrl(playback.first)
            ?: error("${source.label} did not provide a valid direct video stream.")
        val safeHeaders = playback.second.filterKeys(::isSafeWatchHeader)
        return JSONObject()
            .put("ok", true)
            .put("url", streamUrl)
            .put("headers", JSONObject(safeHeaders))
            .put("mimeType", if (streamUrl.substringBefore('?').endsWith(".m3u8", true)) "application/x-mpegURL" else "")
            .put("title", source.label)
    }

    private suspend fun requireAdultAuthorization(services: ServiceLocator, pin: String) {
        check(services.settings.ageGateConfirmed.first()) {
            "Confirm the 18+ age gate in StreamCloud on your phone before browsing here."
        }
        val lockEnabled = services.settings.adultLockEnabled.first()
        val expectedPin = services.settings.safeModePin.first()
        if (lockEnabled && expectedPin.isNotBlank()) {
            check(pin.isNotBlank() && constantTimeEquals(pin, expectedPin)) {
                "Enter the Adult PIN configured in StreamCloud."
            }
        }
    }

    private fun constantTimeEquals(first: String, second: String): Boolean {
        if (first.length != second.length) return false
        var difference = 0
        first.indices.forEach { difference = difference or (first[it].code xor second[it].code) }
        return difference == 0
    }

    private fun Long.formatBytes(): String = when {
        this >= 1024L * 1024L -> "${"%.1f".format(this / (1024f * 1024f))} MB"
        this >= 1024L -> "${(this / 1024L).coerceAtLeast(1L)} KB"
        else -> "$this B"
    }

    private fun StremioStream.toWearStream(providerName: String): JSONObject? {
        if (!infoHash.isNullOrBlank()) return null
        val safeUrl = safeHttpUrl(this.url) ?: return null
        val headers = behaviorHints?.proxyHeaders?.get("request")?.let { raw ->
            runCatching { JSONObject(raw.toString()) }.getOrNull()
        }?.let { rawHeaders ->
            JSONObject().apply {
                val keys = rawHeaders.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isSafeWatchHeader(key)) put(key, rawHeaders.optString(key))
                }
            }
        } ?: JSONObject()
        return JSONObject()
            .put("title", title ?: name ?: providerName)
            .put("provider", providerName)
            .put("quality", qualityLabel(title.orEmpty(), name.orEmpty(), description.orEmpty()))
            .put("url", safeUrl)
            .put("headers", headers)
    }

    private fun NuvioStream.toWearStream(providerName: String): JSONObject? {
        if (url.startsWith("magnet:", true) || !infoHash.isNullOrBlank()) return null
        val safeUrl = safeHttpUrl(url) ?: return null
        return JSONObject()
            .put("title", title ?: name ?: providerName)
            .put("provider", provider ?: providerName)
            .put("quality", quality.orEmpty())
            .put("url", safeUrl)
            .put("headers", JSONObject(headers.orEmpty().filterKeys(::isSafeWatchHeader)))
    }

    private fun qualityLabel(vararg labels: String): String {
        val combined = labels.joinToString(" ")
        return Regex("""(?i)(2160|1440|1080|720|480|360)\s*p?""")
            .find(combined)?.value?.replace(Regex("\\s"), "")?.lowercase()
            .orEmpty()
    }

    private fun safeHttpUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf {
            uri.scheme in setOf("https", "http") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port in 1..65535)
        }
    }

    private fun isSafeWatchHeader(name: String): Boolean =
        name.equals("User-Agent", true) ||
            name.equals("Referer", true) ||
            name.equals("Origin", true) ||
            name.equals("Accept", true) ||
            name.equals("Accept-Language", true)

    private fun isAllowedDirectAdultUrl(source: AdultSource, rawUrl: String): Boolean {
        val host = runCatching { URI(rawUrl).host?.lowercase() }.getOrNull() ?: return false
        return when (source) {
            AdultSource.Reddit -> host == "v.redd.it" ||
                host.endsWith(".redditstatic.com") ||
                host == "preview.redd.it" ||
                host == "i.redd.it"
            AdultSource.RedGifs -> host == "redgifs.com" ||
                host.endsWith(".redgifs.com") ||
                host.endsWith(".gifdeliverynetwork.com")
            else -> false
        }
    }

    private fun String.normalizedTitle(): String =
        lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

}
