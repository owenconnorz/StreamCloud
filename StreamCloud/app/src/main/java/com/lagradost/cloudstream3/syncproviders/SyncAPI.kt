@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.TvType

interface SyncAPI {
    val name: String
    val idPrefix: String
    val mainUrl: String
    val requiresLogin: Boolean get() = false
    val createAccountUrl: String? get() = null
    val icon: Int get() = 0
    val hasScore: Boolean get() = false
    val hasStatus: Boolean get() = false
    val hasWatchedAmount: Boolean get() = false

    enum class SyncStatus { Watching, Completed, OnHold, Dropped, PlanToWatch, Rewatching, None }

    data class LibraryMetadata(
        val syncId: String,
        val name: String,
        val type: TvType? = null,
        val score: Int? = null,
        val status: SyncStatus? = null,
        val watchedEpisodes: Int? = null,
        val maxEpisodes: Int? = null,
        val isFavorite: Boolean? = null,
        val tags: List<String>? = null,
        val startDate: String? = null,
        val endDate: String? = null,
        val nextAiringEpisode: Int? = null,
        val url: String? = null,
        val posterUrl: String? = null,
        val otherUrls: List<String>? = null,
    )

    data class LibraryList(
        val name: String,
        val items: List<LibraryMetadata>,
        val status: SyncStatus = SyncStatus.None,
    )

    data class SyncSearchResult(
        val name: String,
        val syncId: String,
        val url: String,
        val posterUrl: String? = null,
    )

    suspend fun search(name: String): List<SyncSearchResult>? = null
    suspend fun score(id: String, status: SyncStatus): Boolean = false
    suspend fun libraryLoad(): List<LibraryList>? = null
    fun getIdFromUrl(url: String): String = ""
}
