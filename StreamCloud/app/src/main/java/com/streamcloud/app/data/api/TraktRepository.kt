package com.streamcloud.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class TraktDeviceCodeResponse(
    @SerialName("device_code")      val deviceCode:   String = "",
    @SerialName("user_code")        val userCode:     String = "",
    @SerialName("verification_url") val verifyUrl:    String = "",
    @SerialName("expires_in")       val expiresIn:    Int    = 600,
    @SerialName("interval")         val interval:     Int    = 5,
)

@Serializable
data class TraktTokenResponse(
    @SerialName("access_token")  val accessToken:  String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in")    val expiresIn:    Long   = 0,
    val error: String? = null,
)

@Serializable
data class TraktUserSettings(
    val user: TraktUser? = null,
)

@Serializable
data class TraktUser(
    val username: String = "",
    val name: String = "",
)

@Serializable
data class TraktHistoryItem(
    val id: Long = 0,
    val watched_at: String = "",
    val type: String = "",
    val movie: TraktMovie? = null,
    val episode: TraktEpisode? = null,
    val show: TraktShow? = null,
)

@Serializable
data class TraktMovie(
    val title: String = "",
    val year: Int = 0,
    val ids: TraktIds = TraktIds(),
)

@Serializable
data class TraktShow(
    val title: String = "",
    val year: Int = 0,
    val ids: TraktIds = TraktIds(),
)

@Serializable
data class TraktEpisode(
    val season: Int = 0,
    val number: Int = 0,
    val title: String = "",
    val ids: TraktIds = TraktIds(),
)

@Serializable
data class TraktIds(
    val trakt: Int = 0,
    val tmdb: Int = 0,
    val imdb: String = "",
    val tvdb: Int = 0,
)

/**
 * Trakt.tv API client.
 *
 * Setup: register a free app at https://trakt.tv/oauth/applications/new
 * Set the client_id in Settings > Integrations > Trakt.
 *
 * Auth: uses the device activation flow (user visits trakt.tv/activate with a short code).
 * No browser redirect needed — ideal for TV/mobile apps.
 */
object TraktRepository {

    private const val BASE = "https://api.trakt.tv"
    private const val API_VERSION = "2"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient          = true
        coerceInputValues  = true
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun headers(clientId: String, accessToken: String? = null) =
        okhttp3.Headers.Builder()
            .add("Content-Type", "application/json")
            .add("trakt-api-version", API_VERSION)
            .add("trakt-api-key", clientId)
            .also { if (accessToken != null) it.add("Authorization", "Bearer $accessToken") }
            .build()

    /** Step 1: request a device code pair. User visits verifyUrl and enters userCode. */
    suspend fun requestDeviceCode(clientId: String): TraktDeviceCodeResponse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = """{"client_id":"$clientId"}""".toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$BASE/oauth/device/code")
                    .headers(headers(clientId))
                    .post(body)
                    .build()
                val resp = client.newCall(req).execute()
                val raw = resp.body?.string() ?: return@runCatching null
                json.decodeFromString<TraktDeviceCodeResponse>(raw)
            }.getOrNull()
        }

    /** Step 2: poll for token using device_code. Returns null if still pending. */
    suspend fun pollForToken(
        clientId: String,
        clientSecret: String,
        deviceCode: String,
    ): TraktTokenResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val body = """{
                "code": "$deviceCode",
                "client_id": "$clientId",
                "client_secret": "$clientSecret"
            }""".toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE/oauth/device/token")
                .headers(headers(clientId))
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            if (resp.code == 200) {
                val raw = resp.body?.string() ?: return@runCatching null
                json.decodeFromString<TraktTokenResponse>(raw)
            } else null
        }.getOrNull()
    }

    /** Fetch the logged-in user's settings (to get username). */
    suspend fun getUserSettings(clientId: String, accessToken: String): TraktUserSettings? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$BASE/users/settings")
                    .headers(headers(clientId, accessToken))
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val raw = resp.body?.string() ?: return@runCatching null
                json.decodeFromString<TraktUserSettings>(raw)
            }.getOrNull()
        }

    /** Fetch the user's watch history (movies + shows, most recent first). */
    suspend fun getHistory(
        clientId: String,
        accessToken: String,
        limit: Int = 50,
    ): List<TraktHistoryItem> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$BASE/sync/history?limit=$limit")
                .headers(headers(clientId, accessToken))
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val raw = resp.body?.string() ?: return@runCatching emptyList()
            json.decodeFromString<List<TraktHistoryItem>>(raw)
        }.getOrElse { emptyList() }
    }

    /** Scrobble (mark as watched) a movie by TMDB id. */
    suspend fun scrobbleMovie(
        clientId: String,
        accessToken: String,
        tmdbId: Int,
        title: String,
        year: Int = 0,
        progress: Double = 100.0,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = """{
                "movie": { "title": "${title.replace(""","'")}", "year": $year, "ids": { "tmdb": $tmdbId } },
                "progress": $progress
            }""".toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE/scrobble/stop")
                .headers(headers(clientId, accessToken))
                .post(body)
                .build()
            client.newCall(req).execute().code in 200..204
        }.getOrElse { false }
    }

    /** Scrobble (mark as watched) an episode by TMDB show id + season/episode. */
    suspend fun scrobbleEpisode(
        clientId: String,
        accessToken: String,
        showTmdbId: Int,
        showTitle: String,
        season: Int,
        episode: Int,
        progress: Double = 100.0,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = """{
                "show": { "title": "${showTitle.replace(""","'")}", "ids": { "tmdb": $showTmdbId } },
                "episode": { "season": $season, "number": $episode },
                "progress": $progress
            }""".toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE/scrobble/stop")
                .headers(headers(clientId, accessToken))
                .post(body)
                .build()
            client.newCall(req).execute().code in 200..204
        }.getOrElse { false }
    }
}
