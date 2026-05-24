@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

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
    open var score: Score? = null,
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
    override var score: Score? = null,
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
    override var score: Score? = null,
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
    override var score: Score? = null,
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
    override var score: Score? = null,
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
    override var score: Score? = null,
) : SearchResponse(name, url, apiName, type, posterUrl, posterHeaders, id, quality)

open class LoadResponse(
    open var name: String,
    open var url: String,
    open var apiName: String,
    open var type: TvType,
    open var posterUrl: String? = null,
    open var year: Int? = null,
    open var plot: String? = null,
    @Deprecated("Use score", replaceWith = ReplaceWith("score"))
    open var rating: Int? = null,
    open var score: Score? = null,
    open var tags: List<String>? = null,
    open var duration: Int? = null,
    open var trailers: MutableList<TrailerData> = mutableListOf(),
    open var recommendations: List<SearchResponse>? = null,
    open var actors: List<ActorData>? = null,
    open var comingSoon: Boolean = false,
    open var syncData: MutableMap<String, String> = mutableMapOf(),
    open var posterHeaders: Map<String, String>? = null,
    open var backgroundPosterUrl: String? = null,
    open var contentRating: String? = null,
    open var logoUrl: String? = null,
    open var uniqueUrl: String = "",
) {
    companion object {
        // ── Sync id prefixes (set by sync providers at runtime) ─────────────
        var malIdPrefix    = ""
        var kitsuIdPrefix  = ""
        var aniListIdPrefix = ""
        var simklIdPrefix  = ""
        var isTrailersEnabled = true

        // ── Helpers used by plugin init blocks ────────────────────────────
        fun LoadResponse.isMovie(): Boolean =
            this.type.isMovieType() || this is MovieLoadResponse

        @JvmName("addActorNames")
        fun LoadResponse.addActors(actors: List<String>?) {
            this.actors = actors?.map { ActorData(Actor(it)) }
        }

        @JvmName("addActors")
        fun LoadResponse.addActors(actors: List<Pair<Actor, String?>>?) {
            this.actors = actors?.map { (actor, role) -> ActorData(actor, roleString = role) }
        }

        @JvmName("addActorsRole")
        fun LoadResponse.addActors(actors: List<Pair<Actor, ActorRole?>>?) {
            this.actors = actors?.map { (actor, role) -> ActorData(actor, role = role) }
        }

        @JvmName("addActorsOnly")
        fun LoadResponse.addActors(actors: List<Actor>?) {
            this.actors = actors?.map { actor -> ActorData(actor) }
        }

        // ── Trailer helpers ───────────────────────────────────────────────
        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(
            trailerUrl: String?,
            referer: String? = null,
            addRaw: Boolean = false,
        ) {
            if (!isTrailersEnabled || trailerUrl.isNullOrBlank()) return
            this.trailers.add(TrailerData(trailerUrl, referer, addRaw))
        }

        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(
            trailerUrl: String?,
            referer: String? = null,
            addRaw: Boolean = false,
            headers: Map<String, String> = mapOf(),
        ) {
            if (!isTrailersEnabled || trailerUrl.isNullOrBlank()) return
            this.trailers.add(TrailerData(trailerUrl, referer, addRaw, headers))
        }

        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(
            trailerUrls: List<String>?,
            referer: String? = null,
            addRaw: Boolean = false,
        ) {
            if (!isTrailersEnabled || trailerUrls == null) return
            trailers.addAll(trailerUrls.map { TrailerData(it, referer, addRaw) })
        }

        // ── Score / rating helpers ────────────────────────────────────────
        fun LoadResponse.addScore(score: String?, maxValue: Int = 10) {
            this.score = Score.from(score, maxValue)
        }
        fun LoadResponse.addScore(score: Score?) { this.score = score }

        @Suppress("DEPRECATION")
        fun LoadResponse.addRating(text: String?) { this.score = Score.from10(text) }
        @Suppress("DEPRECATION")
        fun LoadResponse.addRating(value: Int?) { this.score = Score.fromOld(value) }

        fun LoadResponse.addDuration(input: String?) {
            this.duration = getDurationFromString(input) ?: this.duration
        }

        // ── Sync id helpers ───────────────────────────────────────────────
        fun LoadResponse.addMalId(id: Int?) {
            if (id != null) this.syncData[malIdPrefix] = id.toString()
        }
        fun LoadResponse.addKitsuId(id: Int?) {
            if (id != null) this.syncData[kitsuIdPrefix] = id.toString()
        }
        fun LoadResponse.addAniListId(id: Int?) {
            if (id != null) this.syncData[aniListIdPrefix] = id.toString()
        }
        fun LoadResponse.addImdbId(id: String?) {
            if (id != null) this.syncData["imdb"] = id
        }
        fun LoadResponse.addImdbUrl(url: String?) {
            val id = url?.substringAfter("tt")?.let { "tt$it" } ?: return
            addImdbId(id)
        }
        fun LoadResponse.addTMDbId(id: String?) {
            if (id != null) this.syncData["tmdb"] = id
        }
        @Suppress("UNUSED_PARAMETER")
        fun LoadResponse.addTraktId(id: String?) {}

        fun LoadResponse.getMalId(): String?      = this.syncData[malIdPrefix]
        fun LoadResponse.getKitsuId(): String?    = this.syncData[kitsuIdPrefix]
        fun LoadResponse.getAniListId(): String?  = this.syncData[aniListIdPrefix]
        fun LoadResponse.getImdbId(): String?     = this.syncData["imdb"]
        fun LoadResponse.getTMDbId(): String?     = this.syncData["tmdb"]
    }
}

class TrailerData(
    val extractorUrl: String,
    val referer: String? = null,
    val raw: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

class ActorData(
    val actor: Actor,
    val role: ActorRole? = null,
    val roleString: String? = null,
    val voiceActor: Actor? = null,
)
class Actor(val name: String, val image: String? = null)
enum class ActorRole { Main, Supporting, Background }

open class MovieLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    open var dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null,
) : LoadResponse(name, url, apiName, type, posterUrl, year, plot, rating, score, tags, duration,
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
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null,
) : LoadResponse(name, url, apiName, type, posterUrl, year, plot, rating, score, tags, duration,
    trailers, recommendations, actors, comingSoon, posterHeaders, backgroundPosterUrl, contentRating)

// LiveStreamLoadResponse is a concrete class (not a typealias) so plugins can
// reference it at runtime via instanceof/cast/reflection/class-literal.
class LiveStreamLoadResponse(
    name: String,
    url: String,
    apiName: String,
    type: TvType = TvType.Live,
    override var dataUrl: String,
    posterUrl: String? = null,
    year: Int? = null,
    plot: String? = null,
    rating: Int? = null,
    score: Score? = null,
    tags: List<String>? = null,
    duration: Int? = null,
    trailers: MutableList<TrailerData> = mutableListOf(),
    recommendations: List<SearchResponse>? = null,
    actors: List<ActorData>? = null,
    comingSoon: Boolean = false,
    posterHeaders: Map<String, String>? = null,
    backgroundPosterUrl: String? = null,
    contentRating: String? = null,
) : MovieLoadResponse(
    name, url, apiName, type, dataUrl, posterUrl, year, plot,
    rating, score, tags, duration, trailers, recommendations,
    actors, comingSoon, posterHeaders, backgroundPosterUrl, contentRating,
)

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

// ── Score — represents a content rating (0..1_000_000_000 internally) ─────────

class Score private constructor(private val data: Int) {
    companion object {
        const val MAX = 1_000_000_000
        val NONE: Score get() = Score(0)
        fun from(value: Int): Score = Score(value.coerceIn(0, MAX))
        fun from(value: Float): Score = from((value * MAX / 10.0f).toInt())
        fun from(value: Double): Score = from((value * MAX / 10.0).toInt())
        fun fromDecimal(value: Number): Score = from(value.toFloat())
        // from10: parse a string on a 0-10 scale ("8.5" → 8.5/10)
        fun from10(value: String): Score = from(value.trim().toDoubleOrNull() ?: 0.0)
        fun from10(value: Number): Score = from(value.toDouble())
        // from100: parse a string on a 0-100 scale ("85" → 85/100 = 8.5/10)
        fun from100(value: String): Score = from((value.trim().toDoubleOrNull() ?: 0.0) / 10.0)
        fun from100(value: Number): Score = from(value.toDouble() / 10.0)
        // from1000: parse a string on a 0-1000 scale
        fun from1000(value: String): Score = from((value.trim().toDoubleOrNull() ?: 0.0) / 100.0)
        fun from1000(value: Number): Score = from(value.toDouble() / 100.0)
    }
    override fun hashCode(): Int = data.hashCode()
    override fun equals(other: Any?): Boolean = other is Score && data == other.data
    fun toInt(maxScore: Int = 10): Int = toLong(maxScore).toInt()
    fun toFloat(maxScore: Int = 10): Float = (data.toFloat() * maxScore) / MAX.toFloat()
    fun toLong(maxScore: Int = 10): Long = (data.toLong() * maxScore) / MAX.toLong()
    override fun toString(): String = String.format("%.1f", toFloat())
}

// ── Thread-safe list helper ────────────────────────────────────────────────────

fun <T> threadSafeListOf(vararg elements: T): MutableList<T> =
    java.util.Collections.synchronizedList(mutableListOf(*elements))

// ── Settings / override data classes ──────────────────────────────────────────

class SettingsJson(var enableAdult: Boolean = false)

data class ProvidersInfoJson(
    val name: String = "",
    val url: String = "",
    val credentials: String? = null,
)

class ErrorLoadingException(message: String? = null) : Exception(message)

class SubtitleFile(val lang: String, val url: String)

// ── APIHolder — plugin registry ────────────────────────────────────────────────

object APIHolder {
    /** Unix timestamp in seconds. Plugins call getUnixTime() (Kotlin getter). */
    val unixTime: Long get() = System.currentTimeMillis() / 1000L
    /** Unix timestamp in milliseconds. Plugins call getUnixTimeMS(). */
    val unixTimeMS: Long get() = System.currentTimeMillis()

    val allProviders: MutableList<MainAPI> = threadSafeListOf()
    var apis: List<MainAPI> = threadSafeListOf()
    var apiMap: Map<String, Int>? = null

    fun initAll() {
        synchronized(allProviders) {
            for (api in allProviders) runCatching { api.init() }
        }
        apiMap = null
    }

    private fun initMap(force: Boolean = false) {
        if (apiMap == null || force)
            apiMap = apis.mapIndexed { i, a -> a.name to i }.toMap()
    }

    fun addPluginMapping(plugin: MainAPI) {
        synchronized(allProviders) { allProviders.add(plugin) }
        synchronized(allProviders) {
            apis = apis + plugin
            initMap(true)
        }
    }

    fun removePluginMapping(plugin: MainAPI) {
        synchronized(allProviders) { allProviders.remove(plugin) }
        synchronized(allProviders) {
            apis = apis.filter { it != plugin }
            initMap(true)
        }
    }

    fun getApiFromNameNull(apiName: String?): MainAPI? {
        if (apiName == null) return null
        synchronized(allProviders) {
            initMap()
            return apiMap?.get(apiName)?.let { apis.getOrNull(it) }
                ?: allProviders.firstOrNull { it.name == apiName }
        }
    }

    fun getApiFromUrlNull(url: String?): MainAPI? {
        if (url == null) return null
        synchronized(allProviders) {
            return allProviders.firstOrNull { url.startsWith(it.mainUrl) }
        }
    }
}

// ── MainAPI ────────────────────────────────────────────────────────────────────

abstract class MainAPI {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        const val CLOUDSTREAM_AGENT = "CloudStream"

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

    open fun init() {}

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

// ── Shared Jackson mapper ─────────────────────────────────────────────────────
// Plugins call MainAPIKt.getMapper() — this top-level val compiles to a static
// getter on MainAPIKt. Declared here (not MainActivity.kt) so it lives in the
// MainAPIKt class that plugins reference. JsonMapper extends ObjectMapper, so
// existing writeValueAsString/readValue calls in MainActivity.kt still work.
//
// Field-based visibility (ANY) is critical for cross-ClassLoader serialization:
// Plugin classes are loaded by DexClassLoader. Jackson's default getter-based
// introspection cannot reliably discover constructor parameters for those classes
// via KotlinModule (which reads @kotlin.Metadata that may not be accessible
// across ClassLoaders on Android). Using field access instead makes Jackson call
// getDeclaredFields() on the class — this works reliably regardless of which
// ClassLoader loaded the class, allowing toJson()/parseJson() to work for
// arbitrary plugin data classes.
val mapper: JsonMapper by lazy {
    JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        // Suppress failures on empty/unknown beans — produce {} rather than throw.
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        // Field-based access: works across DexClassLoader / app ClassLoader boundary.
        .visibility(PropertyAccessor.ALL,   JsonAutoDetect.Visibility.NONE)
        .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
        .build()
}

// ── JSON helpers (used pervasively by CloudStream plugins) ────────────────────

// toJson: serialize any object to a compact JSON string via the shared mapper.
// Plugins call MainAPIKt.toJson(Object) — must be a top-level fun.
fun toJson(value: Any): String = mapper.writeValueAsString(value)

// parseJson: deserialize a JSON string to T.
// Uses TypeReference so generic types (e.g. List<Episode>) survive erasure.
inline fun <reified T> parseJson(value: String): T =
    mapper.readValue(value, object : TypeReference<T>() {})

// tryParseJson: null-safe version — returns null on any parse failure.
inline fun <reified T> tryParseJson(value: String?): T? = runCatching {
    if (value.isNullOrBlank()) null else parseJson<T>(value)
}.getOrNull()

// AppUtils is referenced by some plugins as a thin adapter over the above fns.
object AppUtils {
    fun toJson(value: Any): String = mapper.writeValueAsString(value)
    inline fun <reified T> parseJson(value: String): T =
        mapper.readValue(value, object : TypeReference<T>() {})
    inline fun <reified T> tryParseJson(value: String?): T? = runCatching {
        if (value.isNullOrBlank()) null else parseJson<T>(value)
    }.getOrNull()
}

// ── Top-level helpers ──────────────────────────────────────────────────────────

fun mainPage(data: String, name: String, horizontalImages: Boolean = false) =
    MainPageRequest(name = name, data = data, horizontalImages = horizontalImages)

fun mainPageOf(vararg pairs: Pair<String, String>): List<MainPageRequest> =
    pairs.map { (data, name) -> MainPageRequest(name, data, false) }

// ── getDurationFromString ─────────────────────────────────────────────────────
fun getDurationFromString(input: String?): Int? {
    val clean = input?.trim()?.replace(" ", "") ?: return null
    var seconds = 0
    Regex("(\\d+)\\s*(hr|hour|min|sec)", RegexOption.IGNORE_CASE).findAll(input).forEach {
        val time = it.groupValues[1].toIntOrNull() ?: return@forEach
        seconds += when (it.groupValues[2].lowercase()) {
            "hr", "hour" -> time * 3600
            "min"        -> time * 60
            else         -> time
        }
    }
    return if (seconds > 0) seconds / 60 else null
}

fun newHomePageResponse(name: String, list: List<SearchResponse>, hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(listOf(HomePageList(name, list)), hasNext = hasNext ?: list.isNotEmpty())

fun newHomePageResponse(list: HomePageList, hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(listOf(list), hasNext = hasNext ?: list.list.isNotEmpty())

fun newHomePageResponse(items: List<HomePageList>, hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(items, hasNext = hasNext ?: items.any { it.list.isNotEmpty() })

fun newHomePageResponse(request: MainPageRequest, list: List<SearchResponse>, hasNext: Boolean? = null): HomePageResponse =
    HomePageResponse(listOf(HomePageList(request.name, list, request.horizontalImages)),
        hasNext = hasNext ?: list.isNotEmpty())

fun fixUrl(url: String, mainUrl: String): String = when {
    url.isBlank() -> ""
    url.startsWith("http") -> url
    url.startsWith("//") -> "https:$url"
    url.startsWith("/") -> mainUrl.trimEnd('/') + url
    else -> "$mainUrl/$url"
}
fun fixUrlNull(url: String?, mainUrl: String): String? = url?.let { fixUrl(it, mainUrl) }
fun fixUrlNull(url: String?): String? = url?.takeIf { it.isNotBlank() }
fun MainAPI.fixUrlNull(url: String?): String? =
    url?.let { if (it.isBlank()) null else fixUrl(it, this.mainUrl) }

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

// getQualityFromString: returns the SearchQuality enum value that best matches
// the given string. Plugins call this on MainAPIKt; must return SearchQuality.
fun getQualityFromString(string: String?): SearchQuality? = when (string?.trim()?.lowercase()) {
    "hd", "hd quality", "720p", "720" -> SearchQuality.HD
    "fhd", "full hd", "fullhd", "1080p", "1080" -> SearchQuality.FHD
    "bluray", "blu ray", "blu-ray", "blueray" -> SearchQuality.BluRay
    "uhd", "4k", "ultra hd", "2160p", "2160" -> SearchQuality.UHD
    "fourk" -> SearchQuality.FourK
    "sd", "standard", "480p", "360p" -> SearchQuality.SD
    "camrip", "cam rip", "cam-rip" -> SearchQuality.CamRip
    "cam", "tc", "telesync" -> SearchQuality.Cam
    "hdcam", "hd cam", "hd-cam" -> SearchQuality.HDCam
    "hdr", "hdr10", "hdr10+" -> SearchQuality.HDR
    "dvd", "dvdrip", "dvd rip", "dvd-rip" -> SearchQuality.DVD
    "webrip", "web rip", "web-rip" -> SearchQuality.WebRip
    else -> null
}

// ── SearchResponse builders ────────────────────────────────────────────────────

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

// ── LoadResponse builders ──────────────────────────────────────────────────────

// ── newMovieLoadResponse ─────────────────────────────────────────────────────
// Two overloads mirroring the real CloudStream signatures so that pre-compiled
// plugin DEX files resolve the static methods correctly.

/** Generic overload — data: T? is serialized to JSON via AppUtils.toJson().
 *  JVM signature: newMovieLoadResponse(MainAPI,String,String,TvType,Object,Function2,Continuation)
 *  This is what most modern plugins call (data class LoadData → JSON string). */
suspend fun <T> MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    data: T?,
    initializer: suspend MovieLoadResponse.() -> Unit = {},
): MovieLoadResponse {
    val dataUrl = when (data) {
        null -> ""
        is String -> data
        else -> com.lagradost.cloudstream3.mapper.writeValueAsString(data)
    }
    @Suppress("DEPRECATION")
    val builder = MovieLoadResponse(
        name = name, url = url, apiName = this.name, type = type, dataUrl = dataUrl,
    )
    builder.initializer()
    return builder
}

/** String overload — dataUrl is used directly.
 *  JVM signature: newMovieLoadResponse(MainAPI,String,String,TvType,String,Function2,Continuation) */
suspend fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    dataUrl: String,
    initializer: suspend MovieLoadResponse.() -> Unit = {},
): MovieLoadResponse {
    @Suppress("DEPRECATION")
    val builder = MovieLoadResponse(
        name = name, url = url, apiName = this.name, type = type, dataUrl = dataUrl,
    )
    builder.initializer()
    return builder
}

suspend fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    episodes: List<Episode>,
    initializer: suspend TvSeriesLoadResponse.() -> Unit = {},
): TvSeriesLoadResponse {
    @Suppress("DEPRECATION")
    val builder = TvSeriesLoadResponse(
        name = name, url = url, apiName = this.name, type = type, episodes = episodes,
    )
    builder.initializer()
    return builder
}

suspend fun MainAPI.newAnimeLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    comingSoonIfNone: Boolean = true,
    initializer: suspend TvSeriesLoadResponse.() -> Unit = {},
): TvSeriesLoadResponse {
    @Suppress("DEPRECATION")
    val builder = TvSeriesLoadResponse(
        name = name, url = url, apiName = this.name, type = type, episodes = emptyList(),
    )
    builder.initializer()
    return builder
}

suspend fun MainAPI.newLiveStreamLoadResponse(
    name: String, url: String, dataUrl: String,
    initializer: suspend LiveStreamLoadResponse.() -> Unit = {},
): LiveStreamLoadResponse = LiveStreamLoadResponse(
    name = name, url = url, apiName = this.name, type = TvType.Live, dataUrl = dataUrl,
).apply { initializer() }

inline fun newEpisode(data: String, initializer: Episode.() -> Unit = {}): Episode =
    Episode(data = data).apply(initializer)

// ── ExtractorLink builder ──────────────────────────────────────────────────────

inline fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    initializer: ExtractorLink.() -> Unit = {},
): ExtractorLink = ExtractorLink(
    source = source, name = name, url = url, referer = "", quality = 0,
    isM3u8 = (type == ExtractorLinkType.M3U8), type = type,
).apply(initializer)

// ── Mutation helpers ───────────────────────────────────────────────────────────

fun SearchResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url; if (headers != null) this.posterHeaders = headers
}
fun SearchResponse.addScore(s: Score?) { this.score = s }
fun SearchResponse.addScore(s: Float?) { this.score = s?.let { Score.from(it) } }
fun SearchResponse.addScore(s: Double?) { this.score = s?.let { Score.from(it) } }
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
fun LoadResponse.addScore(s: Score?) { this.score = s }
fun LoadResponse.addScore(s: Float?) { this.score = s?.let { Score.from(it) } }
fun LoadResponse.addScore(s: Double?) { this.score = s?.let { Score.from(it) } }
fun LoadResponse.addPlot(p: String?) { this.plot = p }
fun LoadResponse.addYear(y: Int?) { this.year = y }
fun LoadResponse.addDuration(durationMin: Int?) { this.duration = durationMin }
fun LoadResponse.addTags(t: List<String>?) { this.tags = t }
fun LoadResponse.addActors(a: List<ActorData>?) { this.actors = a }
fun LoadResponse.addBackground(url: String?) { this.backgroundPosterUrl = url }
fun LoadResponse.addTrailer(extractorUrl: String?, referer: String? = null) {
    if (extractorUrl != null) this.trailers.add(TrailerData(extractorUrl, referer))
}

// ── Base64 helpers ─────────────────────────────────────────────────────────────

fun base64Decode(s: String): String =
    String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))
fun base64Encode(s: String): String =
    android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)
fun base64Encode(b: ByteArray): String =
    android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
fun base64DecodeArray(s: String): ByteArray =
    android.util.Base64.decode(s, android.util.Base64.DEFAULT)

fun String.isVideoFile(): Boolean = lowercase().substringAfterLast('.') in setOf(
    "mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "m3u8", "ts", "mpd", "3gp",
)
