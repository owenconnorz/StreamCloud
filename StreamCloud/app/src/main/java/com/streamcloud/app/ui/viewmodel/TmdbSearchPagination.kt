package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.api.TmdbMovie

/**
 * Paging metadata for one TMDB search rail. The query and generation make page responses
 * self-identifying, so a cancelled request cannot be applied to a newer search.
 */
data class TmdbSearchPagination(
    val query: String = "",
    val generation: Long = 0,
    val page: Int = 0,
    val totalPages: Int = 1,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val endReached: Boolean
        get() = page > 0 && page >= totalPages

    val canLoad: Boolean
        get() = query.isNotBlank() && !isLoading && error == null && !endReached

    fun reset(query: String, generation: Long): TmdbSearchPagination =
        TmdbSearchPagination(query = query, generation = generation, isLoading = true)

    fun reuse(query: String, generation: Long): TmdbSearchPagination =
        copy(query = query, generation = generation, isLoading = false, error = null)

    fun beginLoad(): TmdbSearchPagination =
        copy(isLoading = true, error = null)

    fun complete(
        query: String,
        generation: Long,
        responsePage: Int,
        responseTotalPages: Int,
    ): TmdbSearchPagination {
        if (this.query != query || this.generation != generation) return this
        val acceptedPage = responsePage.coerceAtLeast(1)
        return copy(
            page = acceptedPage,
            // Trust the provider's final page. The lower bound only handles malformed
            // responses which claim fewer pages than the page they just returned.
            totalPages = responseTotalPages.coerceAtLeast(acceptedPage),
            isLoading = false,
            error = null,
        )
    }

    fun fail(query: String, generation: Long, message: String): TmdbSearchPagination =
        if (this.query != query || this.generation != generation) this
        else copy(isLoading = false, error = message)
}

internal fun mergeTmdbSearchResults(
    existing: List<TmdbMovie>,
    incoming: List<TmdbMovie>,
): List<TmdbMovie> = (existing + incoming).distinctBy { it.id }