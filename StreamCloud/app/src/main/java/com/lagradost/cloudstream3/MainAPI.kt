@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3

enum class TvType {
    Movie, AnimeMovie, TvSeries, Anime, OVA, Cartoon, Documentary, AsianDrama,
    Live, Torrent, NSFW, Music, AudioBook, JAV, Hentai, CartoonHentai,
    Others
}

enum class DubStatus { Subbed, Dubbed, None }
enum class ShowStatus { Completed, Ongoing, OnHiatus, Cancelled }
enum class SearchQuality { HD, FHD, BluRay, UHD, FourK, SD, CamRip, Cam, HDR, DVD, WebRip, HDCam }

enum class VPNStatus { None, MightBeNeeded, Torrent }

enum class ProviderType { MetaProvider, DirectProvider }

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

// LiveStreamLoadResponse — alias for MovieLoadResponse with TvType.Live.
// Compiled plugins reference this class name directly when using newLiveStreamLoadResponse().
typealias LiveStreamLoadResponse = MovieLoadResponse

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

// ── newHomePageResponse overloads (matching the real CloudStream SDK) ─────────

fun newHomePageResponse(name: String, list: List<SearchResponse>,
    hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(listOf(HomePageList(name, list)),
        hasNext = hasNext ?: list.isNotEmpty())

/** Single HomePageList variant — this is what most modern plugins use. */
fun newHomePageResponse(list: HomePageList, hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(listOf(list), hasNext = hasNext ?: list.list.isNotEmpty())

fun newHomePageResponse(items: List<HomePageList>, hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(items, hasNext = hasNext ?: items.any { it.list.isNotEmpty() })

fun newHomePageResponse(request: MainPageRequest, list: List<SearchResponse>,
    hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(listOf(HomePageList(request.name, list, request.horizontalImages)),
        hasNext = hasNext ?: list.isNotEmpty())

// ── Quality / Extractor types ─────────────────────────────────────────────────

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


// ── Settings / override data classes ────────────────────────────────────────

class SettingsJson(
    var enableAdult: Boolean = false,
)

data class ProvidersInfoJson(
    val name: String = "",
    val url: String = "",
    val credentials: String? = null,
)

class ErrorLoadingException(message: String? = null) : Exception(message)

// ── APIHolder — tracks all registered providers ───────────────────────────────
object APIHolder {
    val allProviders: MutableList<MainAPI> = java.util.Collections.synchronizedList(mutableListOf())

    fun addPluginMapping(plugin: MainAPI) {
        synchronized(allProviders) { allProviders.add(plugin) }
    }

    fun removePluginMapping(plugin: MainAPI) {
        synchronized(allProviders) { allProviders.remove(plugin) }
    }

    fun getApiFromNameNull(apiName: String?): MainAPI? {
        if (apiName == null) return null
        synchronized(allProviders) { return allProviders.firstOrNull { it.name == apiName } }
    }
}

// ── MainAPI ───────────────────────────────────────────────────────────────────

abstract class MainAPI {
    /**
     * companion object — plugins access MainAPI.Companion for:
     *   - settingsForProvider (ShowBox calls getSettingsForProvider())
     *   - overrideData
     *   - USER_AGENT constant
     *   - getQualityFromName()
     */
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        const val CLOUDSTREAM_AGENT = "CloudStream"

        // settingsForProvider generates getSettingsForProvider()/setSettingsForProvider()
        // JVM getters — ShowBox calls getSettingsForProvider() on the companion instance
        var settingsForProvider: SettingsJson = SettingsJson()
        var overrideData: HashMap<String, ProvidersInfoJson>? = null

        fun getQualityFromName(name: String?): Int = when (name?.lowercase()) {
            null -> 0
            "144p" -> 144; "240p" -> 240; "360p" -> 360; "480p" -> 480
            "720p" -> 720; "1080p" -> 1080; "1440p" -> 1440
            "2160p", "4k" -> 2160
            "hd" -> 720; "fhd" -> 1080; "uhd" -> 2160; "sd" -> 480
            else -> name.filter(Char::isDigit).toIntOrNull() ?: 0
        }
    }

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

// ── Utility functions ─────────────────────────────────────────────────────────

fun fixUrl(url: String, mainUrl: String): String = when {
    url.isBlank() -> ""
    url.startsWith("http") -> url
    url.startsWith("//") -> "https:$url"
    url.startsWith("/") -> mainUrl.trimEnd('/') + url
    else -> "$mainUrl/$url"
}
fun fixUrlNull(url: String?, mainUrl: String): String? = url?.let { fixUrl(it, mainUrl) }
// Top-level form (called without receiver)
fun fixUrlNull(url: String?): String? = url?.takeIf { it.isNotBlank() }

// Extension form — compiled plugins reference fixUrlNull(MainAPI, String?)
// (Kotlin extension functions compile to static methods with receiver as first arg)
fun MainAPI.fixUrlNull(url: String?): String? = url?.let {
    if (it.isBlank()) null else fixUrl(it, this.mainUrl)
}

suspend fun <T> safeApiCall(apiCall: suspend () -> T): T? =
    try { apiCall() } catch (_: Exception) { null }

fun getQualityFromName(name: String?): Int = when (name?.lowercase()) {
    null -> 0
    "144p" -> 144; "240p" -> 240; "360p" -> 360; "480p" -> 480
    "720p" -> 720; "1080p" -> 1080; "1440p" -> 1440
    "2160p", "4k" -> 2160
    "hd" -> 720; "fhd" -> 1080; "uhd" -> 2160; "sd" -> 480
    else -> name.filter(Char::isDigit).toIntOrNull() ?: 0
}

// ── SearchResponse builders ───────────────────────────────────────────────────

inline fun MainAPI.newMovieSearchResponse(
    name: String, url: String, type: TvType = TvType.Movie,
    fix: Boolean = true, initializer: MovieSearchResponse.() -> Unit = {},
): MovieSearchResponse = MovieSearchResponse(
    name = name, url = if (fix) fixUrl(url, this.mainUrl) else url, apiName = this.name, type = type,
).apply(initializer)

inline fun MainAPI.newAnimeSearchResponse(
    name: String, url: String, type: TvType = TvType.Anime,
    fix: Boolean = true, initializer: AnimeSearchResponse.() -> Unit = {},
): AnimeSearchResponse = AnimeSearchResponse(
    name = name, url = if (fix) fixUrl(url, this.mainUrl) else url, apiName = this.name, type = type,
).apply(initializer)

inline fun MainAPI.newTvSeriesSearchResponse(
    name: String, url: String, type: TvType = TvType.TvSeries,
    fix: Boolean = true, initializer: TvSeriesSearchResponse.() -> Unit = {},
): TvSeriesSearchResponse = TvSeriesSearchResponse(
    name = name, url = if (fix) fixUrl(url, this.mainUrl) else url, apiName = this.name, type = type,
).apply(initializer)

inline fun MainAPI.newLiveSearchResponse(
    name: String, url: String, type: TvType = TvType.Live,
    fix: Boolean = true, initializer: LiveSearchResponse.() -> Unit = {},
): LiveSearchResponse = LiveSearchResponse(
    name = name, url = if (fix) fixUrl(url, this.mainUrl) else url, apiName = this.name, type = type,
).apply(initializer)

inline fun MainAPI.newTorrentSearchResponse(
    name: String, url: String, type: TvType = TvType.Torrent,
    fix: Boolean = true, initializer: TorrentSearchResponse.() -> Unit = {},
): TorrentSearchResponse = TorrentSearchResponse(
    name = name, url = if (fix) fixUrl(url, this.mainUrl) else url, apiName = this.name, type = type,
).apply(initializer)

// ── LoadResponse builders ─────────────────────────────────────────────────────

inline fun MainAPI.newMovieLoadResponse(
    name: String, url: String, type: TvType, dataUrl: String,
    initializer: MovieLoadResponse.() -> Unit = {},
): MovieLoadResponse = MovieLoadResponse(
    name = name, url = url, apiName = this.name, type = type, dataUrl = dataUrl,
).apply(initializer)

inline fun MainAPI.newTvSeriesLoadResponse(
    name: String, url: String, type: TvType = TvType.TvSeries, episodes: List<Episode>,
    initializer: TvSeriesLoadResponse.() -> Unit = {},
): TvSeriesLoadResponse = TvSeriesLoadResponse(
    name = name, url = url, apiName = this.name, type = type, episodes = episodes,
).apply(initializer)

inline fun MainAPI.newAnimeLoadResponse(
    name: String, url: String, type: TvType = TvType.Anime,
    initializer: TvSeriesLoadResponse.() -> Unit = {},
): TvSeriesLoadResponse = TvSeriesLoadResponse(
    name = name, url = url, apiName = this.name, type = type, episodes = emptyList(),
).apply(initializer)

/** Live stream — wraps MovieLoadResponse with TvType.Live. */
inline fun MainAPI.newLiveStreamLoadResponse(
    name: String,
    url: String,
    dataUrl: String,
    initializer: MovieLoadResponse.() -> Unit = {},
): MovieLoadResponse = MovieLoadResponse(
    name = name, url = url, apiName = this.name, type = TvType.Live, dataUrl = dataUrl,
).apply(initializer)

inline fun newEpisode(data: String, initializer: Episode.() -> Unit = {}): Episode =
    Episode(data = data).apply(initializer)

// ── ExtractorLinkType and newExtractorLink ─────────────────────────────────────

enum class ExtractorLinkType { VIDEO, M3U8, DASH, MAGNET, TORRENT }

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

// ── Mutation helpers ──────────────────────────────────────────────────────────

fun SearchResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url; if (headers != null) this.posterHeaders = headers
}
fun MovieSearchResponse.addYear(y: Int?) { this.year = y }
fun TvSeriesSearchResponse.addYear(y: Int?) { this.year = y }
fun TvSeriesSearchResponse.addEpisodes(count: Int?) { this.episodes = count }
fun AnimeSearchResponse.addDubStatus(sub: Boolean = false, dub: Boolean = false) {
    this.dubStatus = (this.dubStatus ?: EnumSet()).also {
        if (sub) it.add(DubStatus.Subbed); if (dub) it.add(DubStatus.Dubbed)
    }
}

fun LoadResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url; if (headers != null) this.posterHeaders = headers
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

// ── Base64 helpers ────────────────────────────────────────────────────────────

fun base64Decode(s: String): String =
    String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))
fun base64Encode(s: String): String =
    android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)
fun base64DecodeArray(s: String): ByteArray =
    android.util.Base64.decode(s, android.util.Base64.DEFAULT)

fun String.isVideoFile(): Boolean = lowercase().substringAfterLast('.') in setOf(
    "mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "m3u8", "ts", "mpd", "3gp",
)

