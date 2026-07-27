package com.streamcloud.app.data.torrent

import com.streamcloud.app.data.api.YtsApi
import com.streamcloud.app.data.api.YtsMovie

class TorrentRepository(private val api: YtsApi) {

    suspend fun findByImdbId(imdbId: String): YtsMovie? = runCatching {
        val resp = api.movieDetails(imdbId)
        resp.data.movie?.takeIf { it.torrents.isNotEmpty() }
    }.getOrNull()

    suspend fun findByTitle(title: String): List<YtsMovie> = runCatching {
        api.listMovies(title).data.movies
    }.getOrDefault(emptyList())

    suspend fun find(imdbId: String?, title: String?, year: Int?): YtsMovie? {
        if (!imdbId.isNullOrBlank()) {
            val byId = findByImdbId(imdbId)
            if (byId != null) return byId
        }
        if (!title.isNullOrBlank()) {
            val results = findByTitle(title)
            return if (year != null)
                results.firstOrNull { it.year == year } ?: results.firstOrNull()
            else
                results.firstOrNull()
        }
        return null
    }
}
