@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SyncAPI

open class AniListApi(accountId: String = "anilist") : AccountManager(accountId), SyncAPI {

    override val name: String = "AniList"
    override val idPrefix: String = "anilist"
    override val mainUrl: String = "https://anilist.co"
    override val requiresLogin: Boolean = false

    override fun loginInfo(): AuthUser? = null

    data class Title(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
        val userPreferred: String? = null,
    )

    data class CoverImage(
        val medium: String? = null,
        val large: String? = null,
        val extraLarge: String? = null,
        val color: String? = null,
    )

    data class MediaCoverImage(
        val medium: String? = null,
        val large: String? = null,
        val extraLarge: String? = null,
    )

    data class MediaTitle(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
        val userPreferred: String? = null,
    )

    data class SeasonNextAiringEpisode(
        val airingAt: Int? = null,
        val timeUntilAiring: Int? = null,
        val episode: Int? = null,
    )

    data class SeasonMedia(
        val id: Int? = null,
        val title: MediaTitle? = null,
        val coverImage: MediaCoverImage? = null,
        val meanScore: Int? = null,
        val episodes: Int? = null,
        val nextAiringEpisode: SeasonNextAiringEpisode? = null,
        val genres: List<String>? = null,
        val averageScore: Int? = null,
        val format: String? = null,
        val status: String? = null,
        val season: String? = null,
        val seasonYear: Int? = null,
    )

    data class LikePageInfo(
        val total: Int? = null,
        val currentPage: Int? = null,
        val lastPage: Int? = null,
        val hasNextPage: Boolean? = null,
        val perPage: Int? = null,
    )

    data class Recommendation(
        val id: Int? = null,
        val rating: Int? = null,
        val mediaRecommendation: SeasonMedia? = null,
    )

    data class RecommendationEdge(
        val node: Recommendation? = null,
    )

    data class RecommendationConnection(
        val pageInfo: LikePageInfo? = null,
        val nodes: List<Recommendation>? = null,
        val edges: List<RecommendationEdge>? = null,
    )
}
