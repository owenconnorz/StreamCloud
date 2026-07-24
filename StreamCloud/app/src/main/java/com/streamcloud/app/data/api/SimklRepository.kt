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
data class SimklPinResponse(
    val user_code: String = "",
    val device_code: String = "",
    val verification_url: String = "",
    val expires_in: Int = 600,
    val interval: Int = 5,
)

@Serializable
data class SimklTokenResponse(
    val access_token: String = "",
    val token_type: String = "",
    val error: String? = null,
)

@Serializable
data class SimklUser(
    val name: String = "",
    val avatar: String = "",
)

@Serializable
data class SimklHistoryMovie(
    val title: String = "",
    val year: Int = 0,
    val ids: SimklIds = SimklIds(),
    val watched_at: String? = null,
)

@Serializable
data class SimklHistoryShow(
    val title: String = "",
    val year: Int = 0,
    val ids: SimklIds = SimklIds(),
)

@Serializable
data class SimklIds(
    val simkl: Int = 0,
    val tmdb: String = "",
    val imdb: String = "",
    val tvdb: String = "",
)

@Serializable
data class SimklWatchedMovie(
    val movie: SimklHistoryMovie = SimklHistoryMovie(),
    val watched_at: String? = null,
)

@Serializable
data class SimklAllItems(
    val movies: List<SimklWatchedMovie> = emptyList(),
)

/**
 * Simkl API client.
 *
 * Setup: register a free app at https://simkl.com/settings/developer
 * Set the client_id in Settings > Integrations > Simkl.
 *
 * Auth: uses the PIN/device flow — user visits simkl.com/pin with a short code.
 */
object SimklRepository {

    private const val BASE = "https://api.simkl.com"

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
            .add("simkl-api-key", clientId)
            .also { if (accessToken != null) it.add("Authorization", "Bearer $accessToken") }
            .build()

    /** Step 1: get a PIN code for device activation. */
    suspend fun requestPin(clientId: String): SimklPinResponse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$BASE/oauth/pin?client_id=$clientId")
                    .headers(headers(clientId))
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val raw = resp.body?.string() ?: return@runCatching null
                json.decodeFromString<SimklPinResponse>(raw)
            }.getOrNull()
        }

    /** Step 2: poll for token using device_code. Returns null if still pending. */
    suspend fun pollForToken(clientId: String, userCode: String): SimklTokenResponse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$BASE/oauth/pin/$userCode?client_id=$clientId")
                    .headers(headers(clientId))
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.code == 200) {
                    val raw = resp.body?.string() ?: return@runCatching null
                    json.decodeFromString<SimklTokenResponse>(raw)
                } else null
            }.getOrNull()
        }

    /** Fetch the logged-in user info. */
    suspend fun getUser(clientId: String, accessToken: String): SimklUser? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$BASE/users/settings")
                    .headers(headers(clientId, accessToken))
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val raw = resp.body?.string() ?: return@runCatching null
                json.decodeFromString<SimklUser>(raw)
            }.getOrNull()
        }

    /** Get all watched movies for the user. */
    suspend fun getAllWatched(clientId: String, accessToken: String): SimklAllItems =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$BASE/sync/all-items/movies/watched")
                    .headers(headers(clientId, accessToken))
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val raw = resp.body?.string() ?: return@runCatching SimklAllItems()
                json.decodeFromString<SimklAllItems>(raw)
            }.getOrElse { SimklAllItems() }
        }

    /** Mark a movie as watched by TMDB id. */
    suspend fun markMovieWatched(
        clientId: String,
        accessToken: String,
        tmdbId: Int,
        title: String,
        year: Int = 0,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val safeTitle = title.replace(""", "'")
            val body = """{
                "movies": [
                    { "title": "$safeTitle", "year": $year, "ids": { "tmdb": $tmdbId } }
                ]
            }""".toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE/sync/history")
                .headers(headers(clientId, accessToken))
                .post(body)
                .build()
            client.newCall(req).execute().code in 200..204
        }.getOrElse { false }
    }
}
