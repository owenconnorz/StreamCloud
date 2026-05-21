package com.streamcloud.app.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MoviePlayerSession {

    private val _sources = MutableStateFlow<List<PlayerSource>>(emptyList())
    val sourcesFlow: StateFlow<List<PlayerSource>> = _sources.asStateFlow()


    val sources: List<PlayerSource> get() = _sources.value


    private val _nuvioScanning = MutableStateFlow(false)
    val nuvioScanningFlow: StateFlow<Boolean> = _nuvioScanning.asStateFlow()

    var progressKey: WatchProgressKey? = null
        private set


    var tmdbId: Long = 0L
        private set


    var mediaType: String = "movie"
        private set

    fun set(
        newSources: List<PlayerSource>,
        progressKey: WatchProgressKey? = null,
        tmdbId: Long = 0L,
        mediaType: String = "movie",
    ) {
        _sources.value = newSources
        this.progressKey = progressKey
        this.tmdbId = tmdbId
        this.mediaType = mediaType
        _nuvioScanning.value = false
    }

    fun setNuvioScanning(scanning: Boolean) { _nuvioScanning.value = scanning }


    fun mergeSources(additionalSources: List<PlayerSource>) {
        if (additionalSources.isEmpty()) return
        val existing = _sources.value
        val existingIds = existing.mapTo(HashSet()) { it.id }
        val genuinelyNew = additionalSources.filter { it.id !in existingIds }
        if (genuinelyNew.isEmpty()) return
        fun score(s: PlayerSource): Int {
            val q = when (s.qualityTag) {
                "4K" -> 5; "1440p" -> 4; "1080p" -> 3; "720p" -> 2; "480p" -> 1; else -> 0
            }
            return q * 10 + if (!s.isMagnet) 1 else 0
        }
        _sources.value = (existing + genuinelyNew).sortedByDescending { score(it) }
    }

    fun clear() {
        _sources.value = emptyList()
        _nuvioScanning.value = false
        progressKey = null
        tmdbId = 0L
        mediaType = "movie"
    }
}

data class WatchProgressKey(
    val tmdbId: Long,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,
)
