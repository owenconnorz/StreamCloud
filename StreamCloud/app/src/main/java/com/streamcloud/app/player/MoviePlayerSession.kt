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

    private val _sourceErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val sourceErrorsFlow: StateFlow<Map<String, String>> = _sourceErrors.asStateFlow()
    val sourceErrors: Map<String, String> get() = _sourceErrors.value

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitlesFlow: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    var progressKey: WatchProgressKey? = null
        private set

    var tmdbId: Long = 0L
        private set

    var mediaType: String = "movie"
        private set

    // ── Series / binge fields ─────────────────────────────────────────────────
    var seasonNumber: Int? = null
        private set

    var episodeNumber: Int? = null
        private set

    var episodeTitle: String? = null
        private set

    private val _bingeEpisodes = MutableStateFlow<List<BingeEpisode>>(emptyList())
    val bingeEpisodesFlow: StateFlow<List<BingeEpisode>> = _bingeEpisodes.asStateFlow()
    val bingeEpisodes: List<BingeEpisode> get() = _bingeEpisodes.value

    var currentBingeIndex: Int = -1
        private set

    // ─────────────────────────────────────────────────────────────────────────

    fun set(
        newSources: List<PlayerSource>,
        progressKey: WatchProgressKey? = null,
        tmdbId: Long = 0L,
        mediaType: String = "movie",
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeTitle: String? = null,
        bingeEpisodes: List<BingeEpisode> = emptyList(),
        currentBingeIndex: Int = -1,
    ) {
        _sources.value = newSources
        this.progressKey = progressKey
        this.tmdbId = tmdbId
        this.mediaType = mediaType
        this.seasonNumber = seasonNumber
        this.episodeNumber = episodeNumber
        this.episodeTitle = episodeTitle
        _bingeEpisodes.value = bingeEpisodes
        this.currentBingeIndex = currentBingeIndex
        _nuvioScanning.value = false
        _sourceErrors.value = emptyMap()
        _addonSubtitles.value = emptyList()
    }

    fun setNuvioScanning(scanning: Boolean) { _nuvioScanning.value = scanning }

    fun setSourceError(addonName: String, error: String) {
        _sourceErrors.value = _sourceErrors.value + (addonName to error)
    }

    fun clearSourceError(addonName: String) {
        _sourceErrors.value = _sourceErrors.value - addonName
    }

    fun setAddonSubtitles(subtitles: List<AddonSubtitle>) {
        _addonSubtitles.value = subtitles
    }

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
        _sourceErrors.value = emptyMap()
        _addonSubtitles.value = emptyList()
        progressKey = null
        tmdbId = 0L
        mediaType = "movie"
        seasonNumber = null
        episodeNumber = null
        episodeTitle = null
        _bingeEpisodes.value = emptyList()
        currentBingeIndex = -1
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class WatchProgressKey(
    val tmdbId: Long,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,
    val sourceRoute: String? = null,
)

data class BingeEpisode(
    val tmdbId: Long,
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val posterUrl: String? = null,
    val episodeTitle: String? = null,
    val progressKey: WatchProgressKey? = null,
)

data class AddonSubtitle(
    val url: String,
    val lang: String,
    val label: String,
    val addonName: String,
)
