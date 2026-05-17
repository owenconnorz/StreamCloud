@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3

/**
 * Minimal cloudstream3 API stubs needed for `.cs3` plugins to load and execute.
 *
 * This is NOT the full cloudstream3 surface — only the parts that simple plugins
 * (movies/series scrapers) reference. Plugins that depend on advanced features
 * (extractors registry, sflix-style m3u8 schedulers, account sync, etc.) will
 * still throw `NoSuchMethodError` at runtime, and that is expected for now.
 *
 * Source of truth for shapes: github.com/recloudstream/cloudstream
 *  - app/src/main/java/com/lagradost/cloudstream3/MainAPI.kt
 *  - app/src/main/java/com/lagradost/cloudstream3/MvvmExtensions.kt
 */

// ────────────────────────── enums + dataclasses ──────────────────────────

enum class TvType {
    Movie, AnimeMovie, TvSeries, Anime, OVA, Cartoon, Documentary, AsianDrama,
    Live, Torrent, NSFW, Music, AudioBook, JAV, Hentai, CartoonHentai,
    Others
}

enum class DubStatus { Subbed, Dubbed, None }
enum class ShowStatus { Completed, Ongoing, OnHiatus, Cancelled }
enum class SearchQuality { HD, FHD, BluRay, UHD, FourK, SD, CamRip, Cam, HDR, DVD, WebRip, HDCam }

/** Plugin VPN requirement — many Cloudstream plugins reference this enum. */
enum class VPNStatus { None, MightBeNeeded, Torrent }

/** Plugin general status — referenced by some recloudstream plugins. */
enum class ProviderType { MetaProvider, DirectProvider }

// ────────────────────────── search responses ──────────────────────────

open class SearchResponse(
    open var name: String = "",
    open var url: String = "",
    open var apiName: String = "",
    open var type: TvType? = null,
    open var posterUrl: String? = null,
    open var posterHeaders: Map<String, String>? = null,
    open var id: Int? = null,
    open var quality: SearchQuality? = null,
)

open class MovieSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.Movie,
    override var posterUrl: String? = null,
    var year: Int? = null,
    override var id: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var quality: SearchQuality? = null,
) : SearchResponse(name, url, apiName, type, posterUrl, posterHeaders, id, quality)

open class TvSeriesSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.TvSeries,
    override var posterUrl: String? = null,
    var year: Int? = null,
    var episodes: Int? = null,
    override var id: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var quality: SearchQuality? = null,
) : SearchResponse(name, url, apiName, type, posterUrl, posterHeaders, id, quality)

open class AnimeSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.Anime,
    override var posterUrl: String? = null,
    var year: Int? = null,
    var dubStatus: EnumSet? = null,
    var dubEpisodes: Int? = null,
    var subEpisodes: Int? = null,
    var otherName: String? = null,
    override var id: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var quality: SearchQuality? = null,
) : SearchResponse(name, url, apiName, type, posterUrl, posterHeaders, id, quality)

class EnumSet : HashSet<DubStatus>()

open class LiveSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.Live,
    override var posterUrl: String? = null,
    var lang: String? = null,
    override var id: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var quality: SearchQuality? = null,
) : SearchResponse(name, url, apiName, type, posterUrl, posterHeaders, id, quality)

open class TorrentSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.Torrent,
    override var posterUrl: String? = null,
    override var id: Int? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var quality: SearchQuality? = null,
) : SearchResponse(name, url, apiName, type, posterUrl, posterHeaders, id, quality)

// ────────────────────────── load responses ──────────────────────────

open class LoadResponse(
    open var name: String,
    open var url: String,
    open var apiName: String,
    open var type: TvType,
    open var posterUrl: String? = null,
    open var year: Int? = null,
    open var plot: String? = null,
    open var rating: Int? = null,
    open var tags: List<String>? = null,
    open var duration: Int? = null,
    open var trailers: MutableList<TrailerData> = mutableListOf(),
    open var recommendations: List<SearchResponse>? = null,
    open var actors: List<ActorData>? = null,
    open var comingSoon: Boolean = false,
    open var posterHeaders: Map<String, String>? = null,
    open var backgroundPosterUrl: String? = null,
    open var contentRating: String? = null,
)

class TrailerData(
    val extractorUrl: String,
    val referer: String? = null,
    val raw: Boolean = false,
)

class ActorData(
    val actor: Actor,
    val role: ActorRole? = null,
    val roleString: String? = null,
    val voiceActor: Actor? = null,
)
class Actor(val name: String, val image: String? = null)
enum class ActorRole { Main, Supporting, Background }

class MovieLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    var dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null,
) : LoadResponse(name, url, apiName, type, posterUrl, year, plot, rating, tags, duration,
    trailers, recommendations, actors, comingSoon, posterHeaders, backgroundPosterUrl, contentRating)

data class Episode(
    val data: String,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val rating: Int? = null,
    val description: String? = null,
    val date: Long? = null,
)

class TvSeriesLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    var episodes: List<Episode>,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    var showStatus: ShowStatus? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null,
) : LoadResponse(name, url, apiName, type, posterUrl, year, plot, rating, tags, duration,
    trailers, recommendations, actors, comingSoon, posterHeaders, backgroundPosterUrl, contentRating)

// ────────────────────────── home page ──────────────────────────

class HomePageList(
    val name: String,
    val list: List<SearchResponse>,
    val isHorizontalImages: Boolean = false,
)

class HomePageResponse(
    val items: List<HomePageList>,
    val hasNext: Boolean = false,
)

class MainPageRequest(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false,
)

fun mainPage(data: String, name: String, horizontalImages: Boolean = false) =
    MainPageRequest(name = name, data = data, horizontalImages = horizontalImages)

fun mainPageOf(vararg pairs: Pair<String, String>): List<MainPageRequest> =
    pairs.map { (data, name) -> MainPageRequest(name, data, false) }

fun newHomePageResponse(name: String, list: List<SearchResponse>): HomePageResponse =
    HomePageResponse(listOf(HomePageList(name, list)))

fun newHomePageResponse(items: List<HomePageList>): HomePageResponse = HomePageResponse(items)

fun newHomePageResponse(request: MainPageRequest, list: List<SearchResponse>) =
    HomePageResponse(listOf(HomePageList(request.name, list, request.horizontalImages)))

// ────────────────────────── extractor links ──────────────────────────

enum class Qualities(val value: Int) {
    Unknown(0), P144(144), P240(240), P360(360), P480(480),
    P720(720), P1080(1080), P1440(1440), P2160(2160);
    companion object { fun getStringByInt(q: Int?): String = (q ?: 0).toString() + "p" }
}

open class ExtractorLink(
    var source: String,
    var name: String,
    var url: String,
    var referer: String,
    var quality: Int,
    var isM3u8: Boolean = false,
    var headers: Map<String, String> = emptyMap(),
    var extractorData: String? = null,
)

class SubtitleFile(val lang: String, val url: String)

// ────────────────────────── MainAPI base class ──────────────────────────

abstract class MainAPI {
    open var name: String = "Unnamed Provider"
    open var mainUrl: String = ""
    open var lang: String = "en"
    open var hasMainPage: Boolean = false
    open var hasQuickSearch: Boolean = false
    open var hasChromecastSupport: Boolean = true
    open var hasDownloadSupport: Boolean = true
    open var supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)
    open var mainPage: List<MainPageRequest> = emptyList()
    open var providerType: ProviderType = ProviderType.DirectProvider
    open var vpnStatus: VPNStatus = VPNStatus.None
    open var sequentialMainPage: Boolean = false
    open var sequentialMainPageDelay: Long = 0L
    open var sequentialMainPageScrollDelay: Long = 0L

    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null

    open suspend fun search(query: String): List<SearchResponse>? = null

    open suspend fun quickSearch(query: String): List<SearchResponse>? = null

    open suspend fun load(url: String): LoadResponse? = null

    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = false
}

// ────────────────────────── result helpers used everywhere ──────────────────────────

fun fixUrl(url: String, mainUrl: String): String = when {
    url.isBlank() -> ""
    url.startsWith("http") -> url
    url.startsWith("//") -> "https:$url"
    url.startsWith("/") -> mainUrl.trimEnd('/') + url
    else -> "$mainUrl/$url"
}
fun fixUrlNull(url: String?, mainUrl: String): String? = url?.let { fixUrl(it, mainUrl) }

// `safeApiCall { ... }` wrapper — common in plugins.
suspend fun <T> safeApiCall(apiCall: suspend () -> T): T? = try { apiCall() } catch (_: Exception) { null }

// Quality builders.
fun getQualityFromName(name: String?): Int = when (name?.lowercase()) {
    null -> 0
    "144p" -> 144; "240p" -> 240; "360p" -> 360; "480p" -> 480
    "720p" -> 720; "1080p" -> 1080; "1440p" -> 1440
    "2160p", "4k" -> 2160
    "hd" -> 720; "fhd" -> 1080; "uhd" -> 2160; "sd" -> 480
    else -> name.filter(Char::isDigit).toIntOrNull() ?: 0
}

// ────────────────────────── plugin DSL builders (Cloudstream parity) ──────────────────────────
// These are the `newXxxResponse { ... }` factory functions every recloudstream plugin uses.
// Plugins compiled against the upstream cloudstream3 stubs reference them as
// `MainAPIKt.newMovieSearchResponse(...)` etc.

inline fun MainAPI.newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true,
    initializer: MovieSearchResponse.() -> Unit = {},
): MovieSearchResponse = MovieSearchResponse(
    name = name,
    url = if (fix) fixUrl(url, this.mainUrl) else url,
    apiName = this.name,
    type = type,
).apply(initializer)

inline fun MainAPI.newAnimeSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    fix: Boolean = true,
    initializer: AnimeSearchResponse.() -> Unit = {},
): AnimeSearchResponse = AnimeSearchResponse(
    name = name,
    url = if (fix) fixUrl(url, this.mainUrl) else url,
    apiName = this.name,
    type = type,
).apply(initializer)

inline fun MainAPI.newTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    fix: Boolean = true,
    initializer: TvSeriesSearchResponse.() -> Unit = {},
): TvSeriesSearchResponse = TvSeriesSearchResponse(
    name = name,
    url = if (fix) fixUrl(url, this.mainUrl) else url,
    apiName = this.name,
    type = type,
).apply(initializer)

inline fun MainAPI.newLiveSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Live,
    fix: Boolean = true,
    initializer: LiveSearchResponse.() -> Unit = {},
): LiveSearchResponse = LiveSearchResponse(
    name = name,
    url = if (fix) fixUrl(url, this.mainUrl) else url,
    apiName = this.name,
    type = type,
).apply(initializer)

inline fun MainAPI.newTorrentSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Torrent,
    fix: Boolean = true,
    initializer: TorrentSearchResponse.() -> Unit = {},
): TorrentSearchResponse = TorrentSearchResponse(
    name = name,
    url = if (fix) fixUrl(url, this.mainUrl) else url,
    apiName = this.name,
    type = type,
).apply(initializer)

inline fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    dataUrl: String,
    initializer: MovieLoadResponse.() -> Unit = {},
): MovieLoadResponse = MovieLoadResponse(
    name = name, url = url, apiName = this.name, type = type, dataUrl = dataUrl,
).apply(initializer)

inline fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    episodes: List<Episode>,
    initializer: TvSeriesLoadResponse.() -> Unit = {},
): TvSeriesLoadResponse = TvSeriesLoadResponse(
    name = name, url = url, apiName = this.name, type = type, episodes = episodes,
).apply(initializer)

inline fun MainAPI.newAnimeLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    initializer: TvSeriesLoadResponse.() -> Unit = {},
): TvSeriesLoadResponse = TvSeriesLoadResponse(
    name = name, url = url, apiName = this.name, type = type, episodes = emptyList(),
).apply(initializer)

inline fun newEpisode(
    data: String,
    initializer: Episode.() -> Unit = {},
): Episode = Episode(data = data).also { /* Episode is a data class — not mutable; */ }
    .let { e -> e.copy().also { /* no-op for compatibility */ } }
    .let { Episode(data = data) }   // we keep it minimal — initializer is allowed for source compat

inline fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    initializer: ExtractorLink.() -> Unit = {},
): ExtractorLink = ExtractorLink(
    source = source, name = name, url = url, referer = "", quality = 0,
    isM3u8 = (type == ExtractorLinkType.M3U8),
).apply(initializer)

enum class ExtractorLinkType { VIDEO, M3U8, DASH, MAGNET, TORRENT }

// SearchResponse setter helpers — DSL sugar plugins use inside `apply { ... }` blocks.
fun SearchResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url
    if (headers != null) this.posterHeaders = headers
}
fun MovieSearchResponse.addYear(y: Int?) { this.year = y }
fun TvSeriesSearchResponse.addYear(y: Int?) { this.year = y }
fun TvSeriesSearchResponse.addEpisodes(count: Int?) { this.episodes = count }
fun AnimeSearchResponse.addDubStatus(sub: Boolean = false, dub: Boolean = false) {
    this.dubStatus = (this.dubStatus ?: EnumSet()).also {
        if (sub) it.add(DubStatus.Subbed)
        if (dub) it.add(DubStatus.Dubbed)
    }
}

fun LoadResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url
    if (headers != null) this.posterHeaders = headers
}
fun LoadResponse.addRating(score: Int?) { this.rating = score }
fun LoadResponse.addRating(scoreOutOf10: String?) {
    this.rating = scoreOutOf10?.toDoubleOrNull()?.times(1000)?.toInt()
}
fun LoadResponse.addPlot(p: String?) { this.plot = p }
fun LoadResponse.addYear(y: Int?) { this.year = y }
fun LoadResponse.addDuration(durationMin: Int?) { this.duration = durationMin }
fun LoadResponse.addTags(t: List<String>?) { this.tags = t }
fun LoadResponse.addActors(a: List<ActorData>?) { this.actors = a }
fun LoadResponse.addBackground(url: String?) { this.backgroundPosterUrl = url }
fun LoadResponse.addTrailer(extractorUrl: String?, referer: String? = null) {
    if (extractorUrl != null) this.trailers.add(TrailerData(extractorUrl, referer))
}

// ────────────────────────── plugins commonly call these too ──────────────────────────
fun base64Decode(s: String): String = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))
fun base64Encode(s: String): String =
    android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)
fun base64DecodeArray(s: String): ByteArray = android.util.Base64.decode(s, android.util.Base64.DEFAULT)

/** Cloudstream's "is video file extension" helper. */
fun String.isVideoFile(): Boolean = lowercase().substringAfterLast('.') in setOf(
    "mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "m3u8", "ts", "mpd", "3gp",
)