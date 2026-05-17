package com.streamcloud.app.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Public Reddit `.json` endpoint — no OAuth required for public (and NSFW) subreddits.
 * The only hard requirement is a non-default User-Agent; without it Reddit replies 429.
 */
interface RedditApi {
    @GET("r/{sub}/{sort}/.json")
    suspend fun listing(
        @Path("sub") subreddit: String,
        @Path("sort") sort: String,
        @Query("limit") limit: Int = 50,
        @Query("after") after: String? = null,
        @Query("raw_json") rawJson: Int = 1,
        @Query("include_over_18") over18: String = "on",
        @Header("User-Agent") userAgent: String = USER_AGENT,
    ): RedditListing

    companion object {
        // Match the StreamCloud web client's UA — Reddit logs and rate-limits per UA.
        const val USER_AGENT = "android:com.streamcloud.app:v1.0.0 (by /u/streamcloud_app)"
    }
}

@Serializable
data class RedditListing(
    val kind: String? = null,
    val data: RedditListingData? = null,
)

@Serializable
data class RedditListingData(
    val after: String? = null,
    val before: String? = null,
    val children: List<RedditChild> = emptyList(),
)

@Serializable
data class RedditChild(
    val kind: String? = null,
    val data: RedditPost? = null,
)

/**
 * Reddit post — only fields needed for the adult grid + media resolution.
 * `JsonObject` is used for nested polymorphic blobs so we can dig in lazily
 * without exhaustively modelling Reddit's huge schema.
 */
@Serializable
data class RedditPost(
    val id: String = "",
    val title: String = "",
    val subreddit: String = "",
    val author: String = "",
    val permalink: String = "",
    val url: String = "",
    val thumbnail: String? = null,
    val post_hint: String? = null,
    val is_video: Boolean = false,
    val is_gallery: Boolean = false,
    val over_18: Boolean = false,
    val score: Int = 0,
    val media: JsonObject? = null,
    val secure_media: JsonObject? = null,
    val preview: JsonObject? = null,
    val media_metadata: JsonObject? = null,
)
