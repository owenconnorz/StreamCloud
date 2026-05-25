@file:Suppress(
    "UNUSED",
    "UnusedReceiverParameter",
    "MemberVisibilityCanBePrivate",
    "UNUSED_PARAMETER",
    "DeprecationError",
    "DEPRECATION_ERROR",
)
package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.threadSafeListOf
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromCodeToLangTagIETF
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromLanguageToTagIETF
import okhttp3.Interceptor
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Annotations (no-op at runtime – plugins use them as opt-in markers)
// ---------------------------------------------------------------------------

@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class Prerelease

@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class InternalAPI

@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
annotation class UnsafeSSL

// ---------------------------------------------------------------------------
// Top-level Jackson mapper (mirrors com.lagradost.cloudstream3.mapper in the
// real CloudStream library – used by AppUtils, plugins, and the app itself)
// ---------------------------------------------------------------------------

val mapper: JsonMapper by lazy {
    JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const val AllLanguagesName = "universal"

const val PROVIDER_STATUS_KEY        = "PROVIDER_STATUS_KEY"
const val PROVIDER_STATUS_BETA_ONLY  = 3
const val PROVIDER_STATUS_SLOW       = 2
const val PROVIDER_STATUS_OK         = 1
const val PROVIDER_STATUS_DOWN       = 0

// ---------------------------------------------------------------------------
// Exceptions
// ---------------------------------------------------------------------------

class ErrorLoadingException(message: String? = null) : Exception(message)

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

@Suppress("UNUSED_PARAMETER")
enum class TvType(value: Int?) {
    Movie(1), AnimeMovie(2), TvSeries(3), Cartoon(4), Anime(5), OVA(6),
    Torrent(7), Documentary(8), AsianDrama(9), Live(10), NSFW(11), Others(12),
    Music(13), AudioBook(14), CustomMedia(15), Audio(16), Podcast(17), Video(18),
}

fun TvType.isMovieType(): Boolean = when (this) {
    TvType.AnimeMovie, TvType.Live, TvType.Movie, TvType.Torrent, TvType.Video -> true
    else -> false
}

fun TvType.isAudioType(): Boolean = when (this) {
    TvType.Audio, TvType.AudioBook, TvType.Music, TvType.Podcast -> true
    else -> false
}

fun TvType.isLiveStream(): Boolean = this == TvType.Live
fun TvType.isAnimeOp(): Boolean = this == TvType.Anime || this == TvType.OVA

fun TvType?.isEpisodeBased(): Boolean = when (this) {
    TvType.Anime, TvType.AsianDrama, TvType.Cartoon, TvType.TvSeries -> true
    else -> false
}

fun TvType.getFolderPrefix(): String = when (this) {
    TvType.Anime -> "Anime"; TvType.AnimeMovie -> "Movies"; TvType.AsianDrama -> "AsianDramas"
    TvType.Audio -> "Audio"; TvType.AudioBook -> "AudioBooks"; TvType.Cartoon -> "Cartoons"
    TvType.CustomMedia -> "Media"; TvType.Documentary -> "Documentaries"; TvType.Live -> "LiveStreams"
    TvType.Movie -> "Movies"; TvType.Music -> "Music"; TvType.NSFW -> "NSFW"
    TvType.OVA -> "OVAs"; TvType.Others -> "Others"; TvType.Podcast -> "Podcasts"
    TvType.Torrent -> "Torrents"; TvType.TvSeries -> "TVSeries"; TvType.Video -> "Videos"
    else -> "Others"
}

enum class DubStatus(val id: Int) {
    None(-1),
    Dubbed(1),
    Subbed(0),
}

enum class ShowStatus { Completed, Ongoing }

@Suppress("UNUSED_PARAMETER")
enum class SearchQuality(value: Int?) {
    Cam(1), CamRip(2), HdCam(3), Telesync(4), WorkPrint(5), Telecine(6),
    HQ(7), HD(8), HDR(9), BlueRay(10), DVD(11), SD(12), FourK(13),
    UHD(14), SDR(15), WebRip(16)
}

enum class AutoDownloadMode(val value: Int) {
    Disable(0), FilterByLang(1), All(2), NsfwOnly(3);
    companion object {
        infix fun getEnum(value: Int): AutoDownloadMode? = entries.firstOrNull { it.value == value }
    }
}

enum class VPNStatus { None, MightBeNeeded, Torrent }
enum class ProviderType { MetaProvider, DirectProvider }
enum class ActorRole { Main, Supporting, Background }

enum class TrackerType {
    MOVIE, TV, TV_SHORT, ONA, OVA, SPECIAL, MUSIC;
    companion object {
        fun getTypes(type: TvType): Set<TrackerType> = when (type) {
            TvType.Movie -> setOf(MOVIE); TvType.AnimeMovie -> setOf(MOVIE)
            TvType.TvSeries -> setOf(TV, TV_SHORT)
            TvType.Anime -> setOf(TV, TV_SHORT, ONA, OVA)
            TvType.OVA -> setOf(OVA, SPECIAL, ONA)
            TvType.Others -> setOf(MUSIC)
            else -> emptySet()
        }
    }
}

enum class SimklSyncServices(val originalName: String) {
    Simkl("simkl"), Imdb("imdb"), Tmdb("tmdb"), AniList("anilist"), Mal("mal"),
}

// ---------------------------------------------------------------------------
// Score
// ---------------------------------------------------------------------------

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
class Score private constructor(
    @JsonProperty("data") private val data: Int,
) {
    override fun hashCode(): Int = data.hashCode()
    override fun equals(other: Any?): Boolean = other is Score && data == other.data
    override fun toString(): String = toString(10)

    @Suppress("DEPRECATION_ERROR")
    @Deprecated("toOld() is deprecated.", level = DeprecationLevel.ERROR)
    fun toOld(): Int = toInt(10000)

    fun toByte(maxScore: Int): Byte  = toLong(maxScore).toByte()
    fun toInt(maxScore: Int = 10): Int = toLong(maxScore).toInt()
    fun toLong(maxScore: Int = 10): Long = (data.toLong() * maxScore.toLong()) / MAX.toLong()
    fun toFloat(maxScore: Int = 10): Float = (data.toFloat() / MAX.toFloat()) * maxScore.toFloat()
    fun toDouble(maxScore: Int = 10): Double = (data.toDouble() / MAX.toDouble()) * maxScore.toDouble()

    fun toString(maxScore: Int, decimals: Int = 1, removeTrailingZeros: Boolean = true, decimalChar: Char = '.'): String {
        return "%.${decimals}f".format(toDouble(maxScore))
    }

    fun toStringNull(minScore: Double, maxScore: Int, decimals: Int = 1, removeTrailingZeros: Boolean = true, decimalChar: Char = '.'): String? {
        if (toDouble() < minScore) return null
        return toString(maxScore, decimals, removeTrailingZeros, decimalChar)
    }

    companion object {
        const val MAX: Int     = 1_000_000_000
        const val MIN: Int     = 0
        const val MAX_ZEROS: Int = 9

        @Suppress("DEPRECATION_ERROR")
        @Deprecated("Score.fromOld is deprecated.", level = DeprecationLevel.ERROR)
        fun fromOld(value: Int?): Score? {
            if (value == null) return null
            if (value < 0 || value > 10000) return null
            return Score(value * 100_000)
        }

        fun from(value: Int?, maxScore: Int): Score? {
            if (value == null || value < 0 || value > maxScore) return null
            return Score((MAX / maxScore) * value)
        }

        fun from(value: Double?, maxScore: Int): Score? {
            if (value == null || value < 0.0 || value > maxScore) return null
            return Score(((MAX / maxScore).toDouble() * value).roundToInt())
        }

        fun from(value: Float?, maxScore: Int): Score? {
            if (value == null || value < 0.0f || value > maxScore) return null
            return Score(((MAX / maxScore).toFloat() * value).roundToInt())
        }

        fun from(value: String?, maxScore: Int): Score? =
            from(value?.trim()?.toDoubleOrNull()?.absoluteValue, maxScore)

        fun from5(value: Int?): Score?    = from(value, 5)
        fun from10(value: Int?): Score?   = from(value, 10)
        fun from100(value: Int?): Score?  = from(value, 100)
        fun from5(value: Double?): Score?  = from(value, 5)
        fun from10(value: Double?): Score? = from(value, 10)
        fun from100(value: Double?): Score? = from(value, 100)
        fun from5(value: Float?): Score?   = from(value, 5)
        fun from10(value: Float?): Score?  = from(value, 10)
        fun from100(value: Float?): Score? = from(value, 100)
        fun from5(value: String?): Score?  = from(value, 5)
        fun from10(value: String?): Score? = from(value, 10)
        fun from100(value: String?): Score? = from(value, 100)
    }
}

// ---------------------------------------------------------------------------
// Simple data classes
// ---------------------------------------------------------------------------

data class Actor(val name: String, val image: String? = null)

data class ActorData(
    val actor: Actor,
    val role: ActorRole? = null,
    val roleString: String? = null,
    val voiceActor: Actor? = null,
)

data class TrailerData(
    val extractorUrl: String,
    val referer: String?,
    val raw: Boolean,
    val headers: Map<String, String> = mapOf(),
)

data class NextAiring(val episode: Int, val unixTime: Long, val season: Int? = null)

data class SeasonData(val season: Int, val name: String? = null, val displaySeason: Int? = null)

data class Tracker(
    val malId: Int? = null, val kitsuId: String? = null, val aniId: String? = null,
    val image: String? = null, val cover: String? = null,
)

data class ProvidersInfoJson(
    @JsonProperty("name") var name: String,
    @JsonProperty("url") var url: String,
    @JsonProperty("credentials") var credentials: String? = null,
    @JsonProperty("status") var status: Int,
)

data class SettingsJson(@JsonProperty("enableAdult") var enableAdult: Boolean = false)

data class MainPageRequest(val name: String, val data: String, val horizontalImages: Boolean = false)

typealias MainPageData = MainPageRequest

fun mainPage(url: String, name: String, horizontalImages: Boolean = false): MainPageRequest =
    MainPageRequest(name = name, data = url, horizontalImages = horizontalImages)

fun mainPageOf(vararg elements: MainPageRequest): List<MainPageRequest> = elements.toList()

fun mainPageOf(vararg elements: Pair<String, String>): List<MainPageRequest> =
    elements.map { (url, name) -> MainPageRequest(name = name, data = url) }

// ---------------------------------------------------------------------------
// Episode
// ---------------------------------------------------------------------------

data class Episode
@Deprecated("Use newEpisode method", level = DeprecationLevel.ERROR)
constructor(
    var data: String,
    var name: String? = null,
    var season: Int? = null,
    var episode: Int? = null,
    var posterUrl: String? = null,
    var score: Score? = null,
    var description: String? = null,
    var date: Long? = null,
    var runTime: Int? = null,
) {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use score instead", replaceWith = ReplaceWith("score"), level = DeprecationLevel.ERROR)
    var rating: Int?
        set(value) { this.score = Score.from(value, 100) }
        get() = score?.toInt(100)
}

fun Episode.addDate(date: String?, format: String = "yyyy-MM-dd") {
    try { this.date = SimpleDateFormat(format, Locale.getDefault()).parse(date ?: return)?.time }
    catch (_: Exception) {}
}

fun Episode.addDate(date: Date?) { this.date = date?.time }

fun MainAPI.newEpisode(url: String, initializer: Episode.() -> Unit = {}, fix: Boolean = true): Episode {
    @Suppress("DEPRECATION_ERROR")
    return Episode(data = if (fix) fixUrl(url) else url).apply(initializer)
}

fun <T> MainAPI.newEpisode(data: T, initializer: Episode.() -> Unit = {}): Episode {
    if (data is String) return newEpisode(url = data, initializer = initializer)
    @Suppress("DEPRECATION_ERROR")
    return Episode(data = data?.toJson() ?: throw ErrorLoadingException("invalid newEpisode")).apply(initializer)
}

// ---------------------------------------------------------------------------
// IDownloadableMinimum
// ---------------------------------------------------------------------------

interface IDownloadableMinimum {
    val url: String
    val referer: String
    val headers: Map<String, String>
}

// ---------------------------------------------------------------------------
// SubtitleFile / AudioFile
// ---------------------------------------------------------------------------

data class SubtitleFile(
    var lang: String,
    var url: String,
    var headers: Map<String, String>? = null,
) {
    val langTag: String? get() = fromCodeToLangTagIETF(lang) ?: fromLanguageToTagIETF(lang, true)
}

suspend fun newSubtitleFile(lang: String, url: String, initializer: suspend SubtitleFile.() -> Unit = {}): SubtitleFile =
    SubtitleFile(lang, url).apply { initializer() }

data class AudioFile(
    var url: String,
    var headers: Map<String, String>? = null,
)

suspend fun newAudioFile(url: String, initializer: suspend AudioFile.() -> Unit = {}): AudioFile =
    AudioFile(url).apply { initializer() }

// ---------------------------------------------------------------------------
// Search response types
// ---------------------------------------------------------------------------

interface SearchResponse {
    val name: String
    val url: String
    val apiName: String
    var type: TvType?
    var posterUrl: String?
    var posterHeaders: Map<String, String>?
    var id: Int?
    var quality: SearchQuality?
    var score: Score?
}

data class MovieSearchResponse
@Deprecated("Use newMovieSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    var year: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use newMovieSearchResponse", level = DeprecationLevel.ERROR)
    constructor(name: String, url: String, apiName: String, type: TvType?, posterUrl: String? = null,
                year: Int? = null, id: Int? = null, quality: SearchQuality? = null,
                posterHeaders: Map<String, String>? = null) :
        this(name, url, apiName, type, posterUrl, id, year, quality, posterHeaders, null)
}

data class TvSeriesSearchResponse
@Deprecated("Use newTvSeriesSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    var year: Int? = null,
    var episodes: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use newTvSeriesSearchResponse", level = DeprecationLevel.ERROR)
    constructor(name: String, url: String, apiName: String, type: TvType?, posterUrl: String? = null,
                year: Int? = null, episodes: Int? = null, id: Int? = null, quality: SearchQuality? = null,
                posterHeaders: Map<String, String>? = null) :
        this(name, url, apiName, type, posterUrl, year, episodes, id, quality, posterHeaders, null)
}

data class AnimeSearchResponse
@Deprecated("Use newAnimeSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    var year: Int? = null,
    var dubStatus: EnumSet<DubStatus>? = null,
    var otherName: String? = null,
    var episodes: MutableMap<DubStatus, Int> = mutableMapOf(),
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use newAnimeSearchResponse", level = DeprecationLevel.ERROR)
    constructor(name: String, url: String, apiName: String, type: TvType? = null,
                posterUrl: String? = null, year: Int? = null, dubStatus: EnumSet<DubStatus>? = null,
                otherName: String? = null, episodes: MutableMap<DubStatus, Int> = mutableMapOf(),
                id: Int? = null, quality: SearchQuality? = null, posterHeaders: Map<String, String>? = null) :
        this(name, url, apiName, type, posterUrl, year, dubStatus, otherName, episodes, id, quality, posterHeaders, null)
}

fun AnimeSearchResponse.addDubStatus(status: DubStatus, episodes: Int? = null) {
    this.dubStatus = dubStatus?.also { it.add(status) } ?: EnumSet.of(status)
    if (this.type?.isMovieType() != true && episodes != null && episodes > 0)
        this.episodes[status] = episodes
}
fun AnimeSearchResponse.addDubStatus(isDub: Boolean, episodes: Int? = null) =
    addDubStatus(if (isDub) DubStatus.Dubbed else DubStatus.Subbed, episodes)
fun AnimeSearchResponse.addDub(episodes: Int?) {
    if (episodes == null || episodes <= 0) return; addDubStatus(DubStatus.Dubbed, episodes)
}
fun AnimeSearchResponse.addSub(episodes: Int?) {
    if (episodes == null || episodes <= 0) return; addDubStatus(DubStatus.Subbed, episodes)
}
fun AnimeSearchResponse.addDubStatus(dubExist: Boolean, subExist: Boolean, dubEpisodes: Int? = null, subEpisodes: Int? = null) {
    if (dubExist) addDubStatus(DubStatus.Dubbed, dubEpisodes)
    if (subExist) addDubStatus(DubStatus.Subbed, subEpisodes)
}
fun AnimeSearchResponse.addDubStatus(status: String, episodes: Int? = null) {
    if (status.contains("(dub)", ignoreCase = true)) addDubStatus(DubStatus.Dubbed, episodes)
    else if (status.contains("(sub)", ignoreCase = true)) addDubStatus(DubStatus.Subbed, episodes)
}

data class LiveSearchResponse
@Deprecated("Use newLiveSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = null,
    override var posterUrl: String? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    var lang: String? = null,
    override var score: Score? = null,
) : SearchResponse {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use newLiveSearchResponse", level = DeprecationLevel.ERROR)
    constructor(name: String, url: String, apiName: String, type: TvType?, posterUrl: String? = null,
                id: Int? = null, quality: SearchQuality? = null, posterHeaders: Map<String, String>? = null,
                lang: String? = null) :
        this(name, url, apiName, type, posterUrl, id, quality, posterHeaders, lang, null)
}

data class TorrentSearchResponse
@Deprecated("Use newTorrentSearchResponse", level = DeprecationLevel.ERROR)
constructor(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType?,
    override var posterUrl: String?,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var score: Score? = null,
) : SearchResponse {
    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use newTorrentSearchResponse", level = DeprecationLevel.ERROR)
    constructor(name: String, url: String, apiName: String, type: TvType?, posterUrl: String?,
                id: Int? = null, quality: SearchQuality? = null, posterHeaders: Map<String, String>? = null) :
        this(name, url, apiName, type, posterUrl, id, quality, posterHeaders, null)
}

fun SearchResponse.addQuality(quality: String) { this.quality = getQualityFromString(quality) }
fun SearchResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url; this.posterHeaders = headers
}

// ---------------------------------------------------------------------------
// SearchResponseList
// ---------------------------------------------------------------------------

data class SearchResponseList
@Deprecated("Use newSearchResponseList method", level = DeprecationLevel.ERROR)
constructor(
    val items: List<SearchResponse>,
    val hasNext: Boolean = false
)

fun newSearchResponseList(list: List<SearchResponse>, hasNext: Boolean? = null): SearchResponseList {
    @Suppress("DEPRECATION_ERROR")
    return SearchResponseList(list, hasNext = hasNext ?: list.isNotEmpty())
}

fun List<SearchResponse>.toNewSearchResponseList(hasNext: Boolean? = null): SearchResponseList =
    newSearchResponseList(this, hasNext)

// ---------------------------------------------------------------------------
// HomePage types
// ---------------------------------------------------------------------------

data class HomePageList(val name: String, var list: List<SearchResponse>, val isHorizontalImages: Boolean = false)

data class HomePageResponse
@Deprecated("Use newHomePageResponse method", level = DeprecationLevel.ERROR)
constructor(
    val items: List<HomePageList>,
    val hasNext: Boolean = false
)

fun newHomePageResponse(name: String, list: List<SearchResponse>, hasNext: Boolean? = null): HomePageResponse {
    @Suppress("DEPRECATION_ERROR")
    return HomePageResponse(listOf(HomePageList(name, list)), hasNext = hasNext ?: list.isNotEmpty())
}
fun newHomePageResponse(data: MainPageRequest, list: List<SearchResponse>, hasNext: Boolean? = null): HomePageResponse {
    @Suppress("DEPRECATION_ERROR")
    return HomePageResponse(listOf(HomePageList(data.name, list, data.horizontalImages)), hasNext = hasNext ?: list.isNotEmpty())
}
fun newHomePageResponse(list: HomePageList, hasNext: Boolean? = null): HomePageResponse {
    @Suppress("DEPRECATION_ERROR")
    return HomePageResponse(listOf(list), hasNext = hasNext ?: list.list.isNotEmpty())
}
fun newHomePageResponse(list: List<HomePageList>, hasNext: Boolean? = null): HomePageResponse {
    @Suppress("DEPRECATION_ERROR")
    return HomePageResponse(list, hasNext = hasNext ?: list.any { it.list.isNotEmpty() })
}

// ---------------------------------------------------------------------------
// EpisodeResponse interface
// ---------------------------------------------------------------------------

interface EpisodeResponse {
    var showStatus: ShowStatus?
    var nextAiring: NextAiring?
    var seasonNames: List<SeasonData>?
    fun getLatestEpisodes(): Map<DubStatus, Int?>
    fun getTotalEpisodeIndex(episode: Int, season: Int): Int
}

@JvmName("addSeasonNamesString")
fun EpisodeResponse.addSeasonNames(names: List<String>) {
    this.seasonNames = if (names.isEmpty()) null else names.mapIndexed { i, s -> SeasonData(i + 1, s) }
}
@JvmName("addSeasonNamesSeasonData")
fun EpisodeResponse.addSeasonNames(names: List<SeasonData>) { this.seasonNames = names.ifEmpty { null } }

// ---------------------------------------------------------------------------
// LoadResponse interface
// ---------------------------------------------------------------------------

interface LoadResponse {
    var name: String
    var url: String
    var apiName: String
    var type: TvType
    var posterUrl: String?
    var year: Int?
    var plot: String?
    var score: Score?
    var tags: List<String>?
    var duration: Int?
    var trailers: MutableList<TrailerData>
    var recommendations: List<SearchResponse>?
    var actors: List<ActorData>?
    var comingSoon: Boolean
    var syncData: MutableMap<String, String>
    var posterHeaders: Map<String, String>?
    var backgroundPosterUrl: String?
    var logoUrl: String?
    var contentRating: String?
    var uniqueUrl: String

    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use score instead", replaceWith = ReplaceWith("score"), level = DeprecationLevel.ERROR)
    var rating: Int?
        get() = score?.toOld()
        set(value) { @Suppress("DEPRECATION_ERROR") this.score = Score.fromOld(value) }

    companion object {
        var malIdPrefix       = ""
        var kitsuIdPrefix     = ""
        var aniListIdPrefix   = ""
        var simklIdPrefix     = ""
        var isTrailersEnabled = true

        fun addIdToString(idString: String?, database: SimklSyncServices, id: String?): String? {
            if (id == null) return idString
            return (readIdFromString(idString) + mapOf(database to id)).toJson()
        }

        fun readIdFromString(idString: String?): Map<SimklSyncServices, String> =
            tryParseJson(idString) ?: emptyMap()

        fun LoadResponse.isMovie(): Boolean = this.type.isMovieType() || this is MovieLoadResponse

        @JvmName("addActorNames")
        fun LoadResponse.addActors(actors: List<String>?) {
            this.actors = actors?.map { ActorData(Actor(it)) }
        }
        @JvmName("addActors")
        fun LoadResponse.addActors(actors: List<Pair<Actor, String?>>?) {
            this.actors = actors?.map { (a, r) -> ActorData(a, roleString = r) }
        }
        @JvmName("addActorsRole")
        fun LoadResponse.addActors(actors: List<Pair<Actor, ActorRole?>>?) {
            this.actors = actors?.map { (a, r) -> ActorData(a, role = r) }
        }
        @JvmName("addActorsOnly")
        fun LoadResponse.addActors(actors: List<Actor>?) {
            this.actors = actors?.map { ActorData(it) }
        }

        private fun LoadResponse.addSimklId(database: SimklSyncServices, id: String?) {
            try {
                this.syncData[simklIdPrefix] = addIdToString(this.syncData[simklIdPrefix], database, id.toString()) ?: return
            } catch (_: Exception) {}
        }

        fun LoadResponse.getMalId(): String?     = this.syncData[malIdPrefix]
        fun LoadResponse.getKitsuId(): String?   = this.syncData[kitsuIdPrefix]
        fun LoadResponse.getAniListId(): String? = this.syncData[aniListIdPrefix]
        fun LoadResponse.getImdbId(): String?    = try { readIdFromString(this.syncData[simklIdPrefix])[SimklSyncServices.Imdb] } catch (_: Exception) { null }
        fun LoadResponse.getTMDbId(): String?    = try { readIdFromString(this.syncData[simklIdPrefix])[SimklSyncServices.Tmdb] } catch (_: Exception) { null }

        fun LoadResponse.addMalId(id: Int?) {
            this.syncData[malIdPrefix] = (id ?: return).toString()
            this.addSimklId(SimklSyncServices.Mal, id.toString())
        }
        fun LoadResponse.addKitsuId(id: Int?) { this.syncData[kitsuIdPrefix] = (id ?: return).toString() }
        fun LoadResponse.addAniListId(id: Int?) {
            this.syncData[aniListIdPrefix] = (id ?: return).toString()
            this.addSimklId(SimklSyncServices.AniList, id.toString())
        }
        fun LoadResponse.addSimklId(id: Int?)  { this.addSimklId(SimklSyncServices.Simkl, id.toString()) }
        fun LoadResponse.addImdbUrl(url: String?) { addImdbId(imdbUrlToIdNullable(url)) }
        fun LoadResponse.addImdbId(id: String?)   { this.addSimklId(SimklSyncServices.Imdb, id) }
        @Suppress("UNUSED_PARAMETER") fun LoadResponse.addTraktId(id: String?) {}
        @Suppress("UNUSED_PARAMETER") fun LoadResponse.addKitsuId(id: String?) {}
        fun LoadResponse.addTMDbId(id: String?)   { this.addSimklId(SimklSyncServices.Tmdb, id) }

        fun LoadResponse.addScore(score: String?, maxValue: Int = 10) { this.score = Score.from(score, maxValue) }
        fun LoadResponse.addScore(score: Score?) { this.score = score }

        @Suppress("DEPRECATION_ERROR")
        @Deprecated("Use addScore", replaceWith = ReplaceWith("addScore"), level = DeprecationLevel.ERROR)
        fun LoadResponse.addRating(text: String?)  { this.score = Score.from10(text) }
        @Suppress("DEPRECATION_ERROR")
        @Deprecated("Use addScore", replaceWith = ReplaceWith("addScore"), level = DeprecationLevel.ERROR)
        fun LoadResponse.addRating(value: Int?)    { @Suppress("DEPRECATION_ERROR") this.score = Score.fromOld(value) }

        fun LoadResponse.addDuration(input: String?) {
            this.duration = getDurationFromString(input) ?: this.duration
        }

        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(trailerUrl: String?, referer: String? = null, addRaw: Boolean = false) {
            if (!isTrailersEnabled || trailerUrl.isNullOrBlank()) return
            this.trailers.add(TrailerData(trailerUrl, referer, addRaw))
        }
        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(trailerUrl: String?, referer: String? = null, addRaw: Boolean = false, headers: Map<String, String> = mapOf()) {
            if (!isTrailersEnabled || trailerUrl.isNullOrBlank()) return
            this.trailers.add(TrailerData(trailerUrl, referer, addRaw, headers))
        }
        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(trailerUrls: List<String>?, referer: String? = null, addRaw: Boolean = false) {
            if (!isTrailersEnabled || trailerUrls == null) return
            trailers.addAll(trailerUrls.map { TrailerData(it, referer, addRaw) })
        }
    }
}

fun LoadResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url; this.posterHeaders = headers
}

// ---------------------------------------------------------------------------
// Concrete LoadResponse data classes
// ---------------------------------------------------------------------------

data class MovieLoadResponse
@Deprecated("Use newMovieLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    var dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url,
) : LoadResponse

suspend fun <T> MainAPI.newMovieLoadResponse(
    name: String, url: String, type: TvType, data: T?,
    initializer: suspend MovieLoadResponse.() -> Unit = {}
): MovieLoadResponse {
    if (data is String) return newMovieLoadResponse(name, url, type, dataUrl = data, initializer = initializer)
    val dataUrl = data?.toJson() ?: ""
    @Suppress("DEPRECATION_ERROR")
    return MovieLoadResponse(name = name, url = url, apiName = this.name, type = type,
        dataUrl = dataUrl, comingSoon = dataUrl.isBlank()).apply { initializer() }
}

suspend fun MainAPI.newMovieLoadResponse(
    name: String, url: String, type: TvType, dataUrl: String,
    initializer: suspend MovieLoadResponse.() -> Unit = {}
): MovieLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    return MovieLoadResponse(name = name, url = url, apiName = this.name, type = type,
        dataUrl = dataUrl, comingSoon = dataUrl.isBlank()).apply { initializer() }
}

// ---------------------------------------------------------------------------

data class TvSeriesLoadResponse
@Deprecated("Use newTvSeriesLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    var episodes: List<Episode>,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var showStatus: ShowStatus? = null,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var nextAiring: NextAiring? = null,
    override var seasonNames: List<SeasonData>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url,
) : LoadResponse, EpisodeResponse {
    override fun getLatestEpisodes(): Map<DubStatus, Int?> {
        val maxSeason = episodes.maxOfOrNull { it.season ?: Int.MIN_VALUE }.takeUnless { it == Int.MIN_VALUE }
        val max = episodes.filter { it.season == maxSeason }.maxOfOrNull { it.episode ?: Int.MIN_VALUE }.takeUnless { it == Int.MIN_VALUE }
        return mapOf(DubStatus.None to max)
    }
    override fun getTotalEpisodeIndex(episode: Int, season: Int): Int {
        val displayMap = seasonNames?.associate { it.season to it.displaySeason } ?: emptyMap()
        return episodes.count { e ->
            val s = displayMap[e.season] ?: e.season ?: Int.MIN_VALUE
            s in 1..<season
        } + episode
    }
}

suspend fun MainAPI.newTvSeriesLoadResponse(
    name: String, url: String, type: TvType, episodes: List<Episode>,
    initializer: suspend TvSeriesLoadResponse.() -> Unit = {}
): TvSeriesLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    return TvSeriesLoadResponse(name = name, url = url, apiName = this.name, type = type,
        episodes = episodes, comingSoon = episodes.isEmpty()).apply { initializer() }
}

// ---------------------------------------------------------------------------

data class AnimeLoadResponse
@Deprecated("Use newAnimeLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    var engName: String? = null,
    var japName: String? = null,
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    var episodes: MutableMap<DubStatus, List<Episode>> = mutableMapOf(),
    override var showStatus: ShowStatus? = null,
    override var plot: String? = null,
    override var tags: List<String>? = null,
    var synonyms: List<String>? = null,
    override var score: Score? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var nextAiring: NextAiring? = null,
    override var seasonNames: List<SeasonData>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url,
) : LoadResponse, EpisodeResponse {
    override fun getLatestEpisodes(): Map<DubStatus, Int?> {
        return episodes.map { (status, eps) ->
            val maxSeason = eps.maxOfOrNull { it.season ?: Int.MIN_VALUE }.takeUnless { it == Int.MIN_VALUE }
            status to eps.filter { it.season == maxSeason }.maxOfOrNull { it.episode ?: Int.MIN_VALUE }.takeUnless { it == Int.MIN_VALUE }
        }.toMap()
    }
    override fun getTotalEpisodeIndex(episode: Int, season: Int): Int {
        val displayMap = seasonNames?.associate { it.season to it.displaySeason } ?: emptyMap()
        return (episodes.values.maxOfOrNull { eps ->
            eps.count { e -> (displayMap[e.season] ?: e.season ?: Int.MIN_VALUE) in 1..<season }
        } ?: 0) + episode
    }
}

fun AnimeLoadResponse.addEpisodes(status: DubStatus, episodes: List<Episode>?) {
    if (episodes.isNullOrEmpty()) return
    this.episodes[status] = (this.episodes[status] ?: emptyList()) + episodes
}

suspend fun MainAPI.newAnimeLoadResponse(
    name: String, url: String, type: TvType, comingSoonIfNone: Boolean = true,
    initializer: suspend AnimeLoadResponse.() -> Unit = {}
): AnimeLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    val builder = AnimeLoadResponse(name = name, url = url, apiName = this.name, type = type)
    builder.initializer()
    if (comingSoonIfNone) {
        builder.comingSoon = builder.episodes.values.all { it.isNullOrEmpty() }
    }
    return builder
}

// ---------------------------------------------------------------------------

data class LiveStreamLoadResponse
@Deprecated("Use newLiveStreamLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    override var name: String,
    override var url: String,
    override var apiName: String,
    var dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var type: TvType = TvType.Live,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url,
) : LoadResponse

suspend fun MainAPI.newLiveStreamLoadResponse(
    name: String, url: String, dataUrl: String,
    initializer: suspend LiveStreamLoadResponse.() -> Unit = {}
): LiveStreamLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    return LiveStreamLoadResponse(name = name, url = url, apiName = this.name,
        dataUrl = dataUrl, comingSoon = dataUrl.isBlank()).apply { initializer() }
}

// ---------------------------------------------------------------------------

data class TorrentLoadResponse
@Deprecated("Use newTorrentLoadResponse method", level = DeprecationLevel.ERROR)
constructor(
    override var name: String,
    override var url: String,
    override var apiName: String,
    var magnet: String?,
    var torrent: String?,
    override var plot: String?,
    override var type: TvType = TvType.Torrent,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: MutableList<TrailerData> = mutableListOf(),
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: MutableMap<String, String> = mutableMapOf(),
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String = url,
) : LoadResponse

suspend fun MainAPI.newTorrentLoadResponse(
    name: String, url: String, magnet: String? = null, torrent: String? = null,
    initializer: suspend TorrentLoadResponse.() -> Unit = {}
): TorrentLoadResponse {
    @Suppress("DEPRECATION_ERROR")
    return TorrentLoadResponse(name = name, url = url, apiName = this.name,
        magnet = magnet, torrent = torrent, plot = null,
        comingSoon = magnet.isNullOrBlank() && torrent.isNullOrBlank()).apply { initializer() }
}

// ---------------------------------------------------------------------------
// LoadResponse extension helpers
// ---------------------------------------------------------------------------

fun LoadResponse?.isEpisodeBased(): Boolean {
    if (this == null) return false
    return this is EpisodeResponse && this.type.isEpisodeBased()
}
fun LoadResponse?.isAnimeBased(): Boolean {
    if (this == null) return false
    return this.type == TvType.Anime || this.type == TvType.OVA
}

fun getDurationFromString(input: String?): Int? {
    val cleanInput = input?.trim()?.replace(" ", "") ?: return null
    Regex("(\\d+\\shr)|(\\d+\\shour)|(\\d+\\smin)|(\\d+\\ssec)").findAll(input).let { values ->
        var seconds = 0
        values.forEach {
            val timeText = it.value
            if (timeText.isNotBlank()) {
                val time = timeText.filter(Char::isDigit).trim().toInt()
                val scale = timeText.filter { c -> !c.isDigit() }.trim()
                seconds += when (scale) { "hr", "hour" -> time * 3600; "min" -> time * 60; "sec" -> time; else -> 0 }
            }
        }
        if (seconds > 0) return seconds / 60
    }
    Regex("([0-9]*)h.*?([0-9]*)m").find(cleanInput)?.groupValues?.let { v ->
        if (v.size == 3) { val h = v[1].toIntOrNull(); val m = v[2].toIntOrNull(); if (h != null && m != null) return h * 60 + m }
    }
    Regex("([0-9]*)m").find(cleanInput)?.groupValues?.let { v ->
        if (v.size == 2) return v[1].toIntOrNull()
    }
    return null
}

// ---------------------------------------------------------------------------
// Search builder helpers
// ---------------------------------------------------------------------------

fun MainAPI.newMovieSearchResponse(
    name: String, url: String, type: TvType = TvType.Movie, fix: Boolean = true,
    initializer: MovieSearchResponse.() -> Unit = {},
): MovieSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    return MovieSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type).apply(initializer)
}

fun MainAPI.newTvSeriesSearchResponse(
    name: String, url: String, type: TvType = TvType.TvSeries, fix: Boolean = true,
    initializer: TvSeriesSearchResponse.() -> Unit = {},
): TvSeriesSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    return TvSeriesSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type).apply(initializer)
}

fun MainAPI.newAnimeSearchResponse(
    name: String, url: String, type: TvType = TvType.Anime, fix: Boolean = true,
    initializer: AnimeSearchResponse.() -> Unit = {},
): AnimeSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    return AnimeSearchResponse(name, if (fix) fixUrl(url) else url, this.name, type).apply(initializer)
}

fun MainAPI.newLiveSearchResponse(
    name: String, url: String, type: TvType = TvType.Live, fix: Boolean = true,
    initializer: LiveSearchResponse.() -> Unit = {},
): LiveSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    return LiveSearchResponse(name = name, url = if (fix) fixUrl(url) else url, apiName = this.name, type = type).apply(initializer)
}

fun MainAPI.newTorrentSearchResponse(
    name: String, url: String, type: TvType = TvType.Torrent, fix: Boolean = true,
    initializer: TorrentSearchResponse.() -> Unit = {},
): TorrentSearchResponse {
    @Suppress("DEPRECATION_ERROR")
    return TorrentSearchResponse(name = name, url = if (fix) fixUrl(url) else url, apiName = this.name, type = type, posterUrl = null).apply(initializer)
}

// ---------------------------------------------------------------------------
// MainAPI abstract class
// ---------------------------------------------------------------------------

abstract class MainAPI {
    companion object {
        var overrideData: HashMap<String, ProvidersInfoJson>? = null
        var settingsForProvider: SettingsJson = SettingsJson()
    }

    fun init() { overrideData?.get(this::class.simpleName)?.let { overrideWithNewData(it) } }

    fun overrideWithNewData(data: ProvidersInfoJson) {
        if (!canBeOverridden) return
        this.name = data.name
        if (data.url.isNotBlank() && data.url != "NONE") this.mainUrl = data.url
        this.storedCredentials = data.credentials
    }

    open var name: String = "NONE"
    open var mainUrl: String = "NONE"
    open var storedCredentials: String? = null
    open var canBeOverridden: Boolean = true
    open var sequentialMainPage: Boolean = false
    open var sequentialMainPageDelay: Long = 0L
    open var sequentialMainPageScrollDelay: Long = 0L
    var lastHomepageRequest: Long = 0L
    open var lang: String = "en"
    open val instantLinkLoading: Boolean = false
    open val hasChromecastSupport: Boolean = true
    open val hasDownloadSupport: Boolean = true
    open val usesWebView: Boolean = false
    var sourcePlugin: String? = null
    open val hasMainPage: Boolean = false
    open val hasQuickSearch: Boolean = false
    open val loadLinksTimeoutMs: Long? = null
    open val getMainPageTimeoutMs: Long? = null
    open val searchTimeoutMs: Long? = null
    open val quickSearchTimeoutMs: Long? = null
    open val loadTimeoutMs: Long? = null
    open val supportedSyncNames: Set<SyncIdName> = setOf()
    open val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.OVA)
    open val vpnStatus: VPNStatus = VPNStatus.None
    open val providerType: ProviderType = ProviderType.DirectProvider
    open val mainPage: List<MainPageRequest> = listOf(MainPageRequest("", "", false))

    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null

    open suspend fun search(query: String, page: Int): SearchResponseList? {
        val results = search(query) ?: return null
        @Suppress("DEPRECATION_ERROR")
        return SearchResponseList(results, false)
    }

    open suspend fun search(query: String): List<SearchResponse>? { throw NotImplementedError() }
    open suspend fun quickSearch(query: String): List<SearchResponse>? { throw NotImplementedError() }
    open suspend fun load(url: String): LoadResponse? { throw NotImplementedError() }
    open suspend fun extractorVerifierJob(extractorData: String?) { throw NotImplementedError() }

    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean = false

    open fun getVideoInterceptor(extractorLink: com.lagradost.cloudstream3.utils.ExtractorLink): Interceptor? = null
    open suspend fun getLoadUrl(name: SyncIdName, id: String): String? = null
}

// ---------------------------------------------------------------------------
// APIHolder
// ---------------------------------------------------------------------------

object APIHolder {
    val unixTime: Long   get() = System.currentTimeMillis() / 1000L
    val unixTimeMS: Long get() = System.currentTimeMillis()

    val allProviders: MutableList<MainAPI> = threadSafeListOf()
    var apis: List<MainAPI> = threadSafeListOf()
    var apiMap: Map<String, Int>? = null

    fun initAll() {
        synchronized(allProviders) { for (api in allProviders) api.init() }
        apiMap = null
    }

    fun addPluginMapping(plugin: MainAPI) {
        synchronized(apis) { apis = apis + plugin }
        initMap(true)
    }

    fun removePluginMapping(plugin: MainAPI) {
        synchronized(apis) { apis = apis.filter { it != plugin } }
        initMap(true)
    }

    private fun initMap(forcedUpdate: Boolean = false) {
        synchronized(apis) {
            if (apiMap == null || forcedUpdate)
                apiMap = apis.mapIndexed { index, api -> api.name to index }.toMap()
        }
    }

    fun getApiFromNameNull(apiName: String?): MainAPI? {
        if (apiName == null) return null
        synchronized(allProviders) {
            initMap()
            synchronized(apis) {
                return apiMap?.get(apiName)?.let { apis.getOrNull(it) }
                    ?: allProviders.firstOrNull { it.name == apiName }
            }
        }
    }

    fun getApiFromUrlNull(url: String?): MainAPI? {
        if (url == null) return null
        synchronized(allProviders) { allProviders.forEach { if (url.startsWith(it.mainUrl)) return it } }
        return null
    }

    fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

// ---------------------------------------------------------------------------
// URL helpers
// ---------------------------------------------------------------------------

fun base64Decode(string: String): String {
    val bytes = base64DecodeArray(string)
    return buildString(bytes.size) { for (b in bytes) append((b.toInt() and 0xFF).toChar()) }
}

fun base64DecodeArray(string: String): ByteArray = android.util.Base64.decode(string, android.util.Base64.DEFAULT)
fun base64Encode(array: ByteArray): String = android.util.Base64.encodeToString(array, android.util.Base64.NO_WRAP)

fun MainAPI.fixUrl(url: String): String {
    if (url.startsWith("http") || url.startsWith("{\"") || url.startsWith("[")) return url
    if (url.isEmpty()) return ""
    if (url.startsWith("//")) return "https:$url"
    return if (url.startsWith('/')) mainUrl + url else "$mainUrl/$url"
}

fun MainAPI.fixUrlNull(url: String?): String? = if (url.isNullOrEmpty()) null else fixUrl(url)

fun MainAPI.updateUrl(url: String): String {
    return try {
        val original = URI(url); val updated = URI(mainUrl)
        URI(updated.scheme, original.userInfo, updated.host, updated.port, original.path, original.query, original.fragment).toString()
    } catch (_: Throwable) { url }
}

fun sortUrls(urls: Set<com.lagradost.cloudstream3.utils.ExtractorLink>): List<com.lagradost.cloudstream3.utils.ExtractorLink> =
    urls.sortedBy { -it.quality }

fun capitalizeString(str: String): String = try { str.replaceFirstChar(Char::titlecase) } catch (_: Exception) { str }
fun capitalizeStringNullable(str: String?): String? = if (str == null) null else capitalizeString(str)
fun fixTitle(str: String): String = str.split(" ").joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) }

fun imdbUrlToId(url: String): String? =
    Regex("/title/(tt[0-9]*)").find(url)?.groupValues?.get(1)
        ?: Regex("tt[0-9]{5,}").find(url)?.groupValues?.get(0)

fun imdbUrlToIdNullable(url: String?): String? = if (url == null) null else imdbUrlToId(url)

fun fetchUrls(text: String?): List<String> {
    if (text.isNullOrEmpty()) return listOf()
    return Regex("""(https?://(www\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_+.~#?&/=]*))""")
        .findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
}

fun getQualityFromString(string: String?): SearchQuality? {
    val check = (string ?: return null).trim().lowercase().replace(" ", "")
    return when (check) {
        "cam" -> SearchQuality.Cam; "camrip" -> SearchQuality.CamRip
        "hdcam", "hdtc", "hdts" -> SearchQuality.HdCam
        "highquality", "hq" -> SearchQuality.HQ
        "highdefinition", "hdrip", "hd", "hdtv" -> SearchQuality.HD
        "rip" -> SearchQuality.CamRip
        "telecine", "tc" -> SearchQuality.Telecine; "telesync", "ts" -> SearchQuality.Telesync
        "dvd", "dvdrip", "dvdscr" -> SearchQuality.DVD
        "blueray", "bluray", "blu", "br", "blue" -> SearchQuality.BlueRay
        "fhd" -> SearchQuality.HD; "standard", "sd" -> SearchQuality.SD
        "4k" -> SearchQuality.FourK; "uhd" -> SearchQuality.UHD
        "wp", "workprint" -> SearchQuality.WorkPrint
        "webrip", "webdl", "web" -> SearchQuality.WebRip
        "hdr" -> SearchQuality.HDR; "sdr" -> SearchQuality.SDR
        else -> null
    }
}

fun getQualityFromName(qualityName: String?): Int {
    if (qualityName == null) return com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    val match = qualityName.lowercase().replace("p", "").trim()
    return when (match) { "4k" -> com.lagradost.cloudstream3.utils.Qualities.P2160 else -> null }?.value
        ?: match.toIntOrNull() ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
}

suspend fun <T> safeApiCall(apiCall: suspend () -> T): T? = try { apiCall() } catch (_: Exception) { null }

// ---------------------------------------------------------------------------
// Anilist / Tracker data classes (used by APIHolder.getTracker)
// ---------------------------------------------------------------------------

data class AniSearch(@JsonProperty("data") var data: Data? = Data()) {
    data class Data(@JsonProperty("Page") var page: Page? = Page()) {
        data class Page(@JsonProperty("media") var media: ArrayList<Media> = arrayListOf()) {
            data class Media(
                @JsonProperty("title") var title: Title? = null,
                @JsonProperty("id") var id: Int? = null,
                @JsonProperty("idMal") var idMal: Int? = null,
                @JsonProperty("seasonYear") var seasonYear: Int? = null,
                @JsonProperty("format") var format: String? = null,
                @JsonProperty("coverImage") var coverImage: CoverImage? = null,
                @JsonProperty("bannerImage") var bannerImage: String? = null,
            ) {
                data class CoverImage(
                    @JsonProperty("extraLarge") var extraLarge: String? = null,
                    @JsonProperty("large") var large: String? = null,
                )
                data class Title(
                    @JsonProperty("romaji") var romaji: String? = null,
                    @JsonProperty("english") var english: String? = null,
                ) {
                    fun isMatchingTitles(title: String?): Boolean {
                        if (title == null) return false
                        return english.equals(title, true) || romaji.equals(title, true)
                    }
                }
            }
        }
    }
}
