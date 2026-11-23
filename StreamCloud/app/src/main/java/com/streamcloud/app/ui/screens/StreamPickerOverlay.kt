package com.streamcloud.app.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.buildStreamProviderPriorityEntries
import com.streamcloud.app.data.firstReadyProviderItem
import com.streamcloud.app.data.orderStreamProviderEntries
import com.streamcloud.app.data.prioritizeStreamPickerTabs
import com.streamcloud.app.data.shouldShowAutoPlayResolvingState
import com.streamcloud.app.data.shouldKeepStreamPickerTab
import com.streamcloud.app.data.streamPickerTabLabel
import com.streamcloud.app.ui.theme.LegacyMoviesThemeWrapper
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.nuvio.InstalledNuvioProvider
import com.streamcloud.app.data.nuvio.NuvioRuntime
import com.streamcloud.app.data.nuvio.NuvioStream
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.CloudStreamSearchMatch
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.data.plugins.cloudStreamNoLinksConfirmed
import com.streamcloud.app.data.plugins.CloudStreamContentKind
import com.streamcloud.app.data.plugins.detectCloudStreamContentKind
import com.streamcloud.app.data.plugins.hasPlausibleCloudStreamTitleMatch
import com.streamcloud.app.data.plugins.searchResponseMatchesContentKind
import com.streamcloud.app.data.plugins.supportsPickerContent
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioStream
import com.streamcloud.app.player.PlayerSource
import com.streamcloud.app.player.SourceLanguageFilter
import com.streamcloud.app.player.SourceQualityFilter
import com.streamcloud.app.player.toAddonSubtitles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private data class PickerGroupState(
    val addonName: String,
    val streams: List<PlayerSource> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val emptyResultConfirmed: Boolean = false,
)

private data class CloudStreamPickerResult(
    val streams: List<PlayerSource>,
    val error: String? = null,
    val emptyResultConfirmed: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamPickerOverlay(
    movie: TmdbMovie?,
    mediaType: String,
    tmdbId: Long,
    imdbId: String?,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    installedAddons: List<InstalledStremioAddon>,
    installedNuvio: List<InstalledNuvioProvider>,
    installedCsPlugins: List<InstalledPlugin>,
    autoPlayBest: Boolean = false,
    onBack: () -> Unit,
    onPlay: (url: String, sources: List<PlayerSource>) -> Unit,
    onDownload: ((source: PlayerSource) -> Unit)? = null,
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val moviesThemeName by sl.settings.moviesTheme.collectAsState(initial = "modern_blue")
    val qualityFilterEnabled by sl.settings.sourceQualityFilterEnabled.collectAsState(initial = false)
    val selectedQualities by sl.settings.sourceQualityFilterQualities.collectAsState(
        initial = SourceQualityFilter.defaultSelected,
    )
    val languageFilterEnabled by sl.settings.sourceLanguageFilterEnabled.collectAsState(initial = false)
    val selectedLanguages by sl.settings.sourceLanguageFilterLanguages.collectAsState(
        initial = SourceLanguageFilter.defaultSelected,
    )
    val savedProviderOrder by produceState<List<String>?>(
        initialValue = null,
        sl.settings.streamProviderPriorityOrder,
    ) {
        sl.settings.streamProviderPriorityOrder.collect { value = it }
    }
    val providerOrderIsLoaded = savedProviderOrder != null

    val contentKind = remember(movie, mediaType) {
        detectCloudStreamContentKind(
            mediaType = mediaType,
            isAdult = movie?.adult == true,
            genreIds = movie?.genreIds.orEmpty(),
            genreNames = movie?.genres.orEmpty().map { it.name },
            originalLanguage = movie?.originalLanguage,
            originCountries = movie?.originCountry.orEmpty(),
            title = movie?.displayTitle.orEmpty(),
        )
    }
    val eligibleCs = remember(installedCsPlugins, contentKind) {
        installedCsPlugins.filter { it.supportsPickerContent(contentKind) }
    }

    val providerEntries = remember(installedAddons, installedNuvio, eligibleCs) {
        buildStreamProviderPriorityEntries(installedAddons, installedNuvio, eligibleCs)
    }
    val groupOrder: List<Pair<String, String>> = remember(providerEntries, savedProviderOrder) {
        orderStreamProviderEntries(providerEntries, savedProviderOrder.orEmpty())
            .map { it.key to it.name }
    }
    val providerKeySet = remember(groupOrder) { groupOrder.mapTo(mutableSetOf()) { it.first } }
    val csTitle = movie?.displayTitle.orEmpty()
    val csYear = movie?.pickerYear()

    var groups by remember(providerKeySet) {
        mutableStateOf(groupOrder.associate { (key, name) -> key to PickerGroupState(name) })
    }
    var selectedTabKey by remember { mutableStateOf("all") }
    var revision by remember { mutableStateOf(0) }

    fun updateGroup(
        key: String,
        streams: List<PlayerSource>,
        error: String? = null,
        emptyResultConfirmed: Boolean = streams.isEmpty(),
    ) {
        groups = groups.toMutableMap().apply {
            val existing = get(key) ?: return
            put(
                key,
                existing.copy(
                    streams = streams,
                    isLoading = false,
                    error = error,
                    emptyResultConfirmed = emptyResultConfirmed,
                ),
            )
        }
    }

    fun updatePartialGroupStreams(key: String, streams: List<PlayerSource>) {
        groups = groups.toMutableMap().apply {
            val existing = get(key) ?: return
            put(key, existing.copy(streams = streams))
        }
    }

    LaunchedEffect(
        revision,
        providerKeySet,
        providerOrderIsLoaded,
        groupOrder,
        installedAddons,
        installedNuvio,
        eligibleCs,
        tmdbId,
        imdbId,
        mediaType,
        season,
        episode,
        csTitle,
        csYear,
    ) {
        if (!providerOrderIsLoaded) return@LaunchedEffect
        groups = groupOrder.associate { (key, name) -> key to PickerGroupState(name) }
        selectedTabKey = "all"

        val stremioType = if (mediaType == "tv") "series" else "movie"
        val tt = imdbId ?: ""

        coroutineScope {
            installedAddons.forEach { addon ->
                launch {
                    val sources = withContext(Dispatchers.IO) {
                        try {
                            val ids = buildList {
                                if (tt.isNotBlank()) add(tt)
                                if (!tt.startsWith("tmdb:")) add("tmdb:$tmdbId")
                            }
                            val seen = mutableSetOf<String>()
                            ids.flatMap { id ->
                                withTimeoutOrNull(20_000L) {
                                    sl.stremio.fetchStreams(addon, stremioType, id)
                                        .mapNotNull { it.pickerToPlayerSource(addon) }
                                } ?: emptyList()
                            }.filter { seen.add(it.url) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.d("StreamPicker", "Stremio ${addon.name}: ${e.message}")
                            emptyList()
                        }
                    }
                    updateGroup("stremio:${addon.id}", streams = sources)
                }
            }

            installedNuvio.forEach { provider ->
                launch {
                    val streams = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(30_000L) {
                            try {
                                val seen = mutableSetOf<String>()
                                sl.nuvio.resolveSingle(
                                    provider = provider,
                                    tmdbId = tmdbId.toString(),
                                    mediaType = mediaType,
                                    season = season,
                                    episode = episode,
                                    imdbId = imdbId,
                                ).map { it.pickerToPlayerSource(provider) }
                                    .filter { seen.add(it.url) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.d("StreamPicker", "Nuvio ${provider.name}: ${e.message}")
                                emptyList()
                            }
                        } ?: emptyList()
                    }
                    val err = if (streams.isEmpty()) NuvioRuntime.lastError(provider.id) else null
                    updateGroup("nuvio:${provider.id}", streams = streams, error = err)
                }
            }

            eligibleCs.forEach { plugin ->
                launch {
                    val result = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(120_000L) {
                            try {
                                resolveCsForPicker(
                                    context = context,
                                    plugin = plugin,
                                    title = csTitle,
                                    year = csYear,
                                    contentKind = contentKind,
                                    season = season,
                                    episode = episode,
                                    onPartialStreams = { partialStreams ->
                                        withContext(Dispatchers.Main.immediate) {
                                            updatePartialGroupStreams(
                                                "cs:${plugin.internalName}",
                                                partialStreams,
                                            )
                                        }
                                    },
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.d("StreamPicker", "CS ${plugin.name}: ${e.message}")
                                CloudStreamPickerResult(
                                    streams = emptyList(),
                                    error = "CloudStream plugin failed: ${e.message ?: "unknown error"}",
                                )
                            }
                        } ?: CloudStreamPickerResult(
                            streams = emptyList(),
                            error = "CloudStream plugin timed out after 120 seconds.",
                        )
                    }
                    updateGroup(
                        "cs:${plugin.internalName}",
                        streams = result.streams,
                        error = result.error,
                        emptyResultConfirmed = result.emptyResultConfirmed,
                    )
                }
            }
        }
    }

    val isAnyLoading = groups.values.any { it.isLoading }
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val firstPickerFocus = remember { FocusRequester() }
    val sourcesByProvider = remember(groups) {
          groups.mapValues { (_, group) ->
              group.streams.sortedWith(
                  compareBy<PlayerSource> { it.isMagnet }
                      .thenByDescending { pickerQualityRank(it.qualityTag) },
              )
          }
      }
      val filteredSourcesByProvider = remember(
          sourcesByProvider,
          qualityFilterEnabled,
          selectedQualities,
          languageFilterEnabled,
          selectedLanguages,
      ) {
          sourcesByProvider.mapValues { (_, sources) ->
              val qualityFiltered = SourceQualityFilter.filter(
                  sources,
                  qualityFilterEnabled,
                  selectedQualities,
              )
              SourceLanguageFilter.filter(
                  qualityFiltered,
                  languageFilterEnabled,
                  selectedLanguages,
              )
          }
      }
      val unfilteredSources = remember(sourcesByProvider, groupOrder) {
          groupOrder.flatMap { (key, _) ->
              sourcesByProvider[key].orEmpty()
          }
      }
      val allSources = remember(filteredSourcesByProvider, groupOrder) {
          groupOrder.flatMap { (key, _) ->
              filteredSourcesByProvider[key].orEmpty()
          }
      }
      val firstReadyPreferredSource = remember(groups, groupOrder, filteredSourcesByProvider) {
          firstReadyProviderItem(
              providerOrder = groupOrder.map { it.first },
              itemsByProvider = filteredSourcesByProvider,
              loadingProviders = groups.filterValues { it.isLoading }.keys,
          )
      }
        val showAutoPlayProgress = shouldShowAutoPlayResolvingState(
        autoPlayEnabled = autoPlayBest,
        isLoading = isAnyLoading,
        hasSources = allSources.isNotEmpty(),
    )
    LaunchedEffect(isTv, showAutoPlayProgress) {
        if (isTv && !showAutoPlayProgress) {
            runCatching { firstPickerFocus.requestFocus() }
        }
    }

    // Start from the first completed provider in priority order. CloudStream can emit
    // partial links while loading, so don't leave the picker before the preferred provider
    // has finished and its remaining links have been added to the player source list.
      var hasAutoPlayed by remember { mutableStateOf(false) }
      LaunchedEffect(autoPlayBest, firstReadyPreferredSource, allSources, providerOrderIsLoaded) {
          if (!providerOrderIsLoaded || !autoPlayBest || hasAutoPlayed) {
              return@LaunchedEffect
          }
          val firstPreferredSource = firstReadyPreferredSource
          if (firstPreferredSource != null) {
              hasAutoPlayed = true
              onPlay(firstPreferredSource.url, allSources)
          }
      }

    val providerTabs = remember(groups, groupOrder) {
        prioritizeStreamPickerTabs(
            groupOrder.mapNotNull { (key, name) ->
                val group = groups[key] ?: return@mapNotNull null
                if (
                    shouldKeepStreamPickerTab(
                        isLoading = group.isLoading,
                        hasStreams = group.streams.isNotEmpty(),
                        emptyResultConfirmed = group.emptyResultConfirmed,
                    )
                ) {
                    key to name
                } else {
                    null
                }
            }
        ).map { (key, name) -> key to streamPickerTabLabel(key, name) }
    }
    val tabs = remember(providerTabs) { listOf("All") + providerTabs.map { it.second } }
    val selectedProviderKey = selectedTabKey.takeIf { key ->
        key != "all" && providerTabs.any { it.first == key }
    }
    val safeTab = selectedProviderKey
        ?.let { key -> providerTabs.indexOfFirst { it.first == key } + 1 }
        ?.coerceAtMost(tabs.lastIndex)
        ?: 0
    LaunchedEffect(selectedTabKey, selectedProviderKey) {
        if (selectedTabKey != "all" && selectedProviderKey == null) {
            selectedTabKey = "all"
        }
    }

    val visibleGroups = remember(groups, safeTab, groupOrder, selectedProviderKey) {
        if (safeTab == 0) {
            groupOrder.mapNotNull { (key, name) ->
                val g = groups[key] ?: return@mapNotNull null
                if (
                    !shouldKeepStreamPickerTab(
                        isLoading = g.isLoading,
                        hasStreams = g.streams.isNotEmpty(),
                        emptyResultConfirmed = g.emptyResultConfirmed,
                    )
                ) return@mapNotNull null
                Triple(key, streamPickerTabLabel(key, name), g)
            }
        } else {
            groupOrder.mapNotNull { (key, name) ->
                if (key != selectedProviderKey) return@mapNotNull null
                val g = groups[key] ?: return@mapNotNull null
                Triple(key, streamPickerTabLabel(key, name), g)
            }
        }
    }

    LegacyMoviesThemeWrapper(moviesThemeName) {
    if (showAutoPlayProgress) {
        val playbackTitle = buildString {
            append(movie?.displayTitle ?: "Finding a stream")
            if (season != null && episode != null) append(" · S${season}E${episode}")
            episodeTitle?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }
        StreamingLoadingOverlay(
            title = playbackTitle,
            backdropUrl = movie?.backdropUrl ?: movie?.posterUrl,
            onBack = onBack,
        )
    } else {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            ) {
                val backdropUrl = movie?.backdropUrl ?: movie?.posterUrl
                if (backdropUrl != null) {
                    AsyncImage(
                        model = backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Black.copy(alpha = 0.90f),
                                )
                            )
                        )
                )

                // Back button hidden on TV — remote Back navigates away;
                // focus goes to the source list anchor instead.
                if (!isTv) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(4.dp)
                            .tvFocusBorder(RoundedCornerShape(50)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                }

                IconButton(
                    onClick = { revision++ },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(4.dp),
                ) {
                    if (isAnyLoading) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Color.White)
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        movie?.displayTitle ?: "Select a stream",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (season != null && episode != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            buildString {
                                append("Season $season · Episode $episode")
                                episodeTitle?.takeIf { it.isNotBlank() }
                                    ?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.78f),
                        )
                    }
                }
            }

            if (tabs.size > 1) {
                ScrollableTabRow(
                    selectedTabIndex = safeTab,
                    modifier = Modifier.tvFocusGroup(),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    edgePadding = 8.dp,
                ) {
                    tabs.forEachIndexed { i, tab ->
                        Tab(
                            selected = i == safeTab,
                            onClick = {
                                selectedTabKey = if (i == 0) {
                                    "all"
                                } else {
                                    providerTabs.getOrNull(i - 1)?.first ?: "all"
                                }
                            },
                            text = {
                                Text(
                                    tab,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (i == safeTab) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().tvFocusGroup(),
            ) {
                // TV: invisible anchor so D-pad navigates straight into streams on open.
                if (isTv) {
                    item(key = "tv-focus-anchor") {
                        Box(Modifier.size(1.dp).focusRequester(firstPickerFocus).focusable())
                    }
                }

                visibleGroups.forEach { (key, addonName, groupState) ->
                    if (safeTab == 0) {
                        item(key = "hdr:$key") {
                            PickerSectionHeader(
                                name = addonName,
                                isLoading = groupState.isLoading,
                            )
                        }
                    } else if (groupState.isLoading) {
                        item(key = "hdr:$key") {
                            PickerSectionHeader(name = addonName, isLoading = true)
                        }
                    }

                    if (groupState.isLoading) {
                        item(key = "prog:$key") {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }

                    val displayedStreams = filteredSourcesByProvider[key].orEmpty()
                    if (!groupState.isLoading || groupState.streams.isNotEmpty()) {
                        val sections = displayedStreams.pickerInnerSections(addonName)
                        val groupError = groupState.error
                        if (
                            !groupState.isLoading &&
                            groupState.streams.isEmpty() &&
                            !groupError.isNullOrBlank()
                        ) {
                            item(key = "error:$key") {
                                Text(
                                    groupError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp),
                                )
                            }
                        }
                        sections.forEachIndexed { sectionIdx, (innerName, sectionStreams) ->
                            if (sections.size > 1 || innerName != addonName) {
                                item(key = "inner:$key:$sectionIdx:$innerName") {
                                    Text(
                                        innerName,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            start = 4.dp,
                                            top = 8.dp,
                                            bottom = 2.dp,
                                        ),
                                    )
                                }
                            }
                            // Key is namespaced with the provider key so streams from
                            // two providers that share an id (e.g. both query Torrentio)
                            // never produce duplicate LazyColumn keys.
                            itemsIndexed(sectionStreams, key = { idx, src -> "s:$key:${src.id}:$idx" }) { _, src ->
                                PickerStreamCard(
                                    source = src,
                                    onClick = { onPlay(src.url, allSources) },
                                    onDownload = onDownload?.let { cb ->
                                        if (src.isMagnet || pickerIsDirectStream(src.url)) {
                                            { cb(src) }
                                        } else null
                                    },
                                )
                            }
                        }
                    }
                }

                if (!isAnyLoading && allSources.isEmpty()) {
                    item(key = "no_results") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (
                                        unfilteredSources.isNotEmpty() &&
                                        (qualityFilterEnabled || languageFilterEnabled)
                                    ) {
                                        "No streams match the current filters"
                                    } else {
                                        "No streams found"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (
                                        unfilteredSources.isNotEmpty() &&
                                        (qualityFilterEnabled || languageFilterEnabled)
                                    ) {
                                        "Change filters in Settings > Player & playback"
                                    } else {
                                        "Tap the refresh button to try again"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    }
    } // MoviesThemeWrapper
}

private fun List<PlayerSource>.pickerInnerSections(
    addonName: String,
): List<Pair<String, List<PlayerSource>>> {
    val result = mutableListOf<Pair<String, MutableList<PlayerSource>>>()
    for (stream in this) {
        val inner = stream.pickerInnerName()
        val last = result.lastOrNull()
        if (last == null || last.first != inner) {
            result.add(inner to mutableListOf(stream))
        } else {
            last.second.add(stream)
        }
    }
    return result.map { (name, streams) -> name to streams.toList() }
}

private fun PlayerSource.pickerInnerName(): String {
    val firstLine = label.lines().firstOrNull()?.trim() ?: return addonName
    return firstLine.split("|").firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: addonName
}

@Composable
private fun PickerSectionHeader(name: String, isLoading: Boolean) {
    Row(
        Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        if (isLoading) {
            CircularProgressIndicator(
                Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PickerStreamCard(
    source: PlayerSource,
    onClick: () -> Unit,
    onDownload: (() -> Unit)? = null,
) {
    val lines = source.label.lines().map { it.trim() }.filter { it.isNotBlank() }
    val titleLine = lines.firstOrNull() ?: source.addonName
    val descLines = lines.drop(1)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .tvFocusBorder(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = if (onDownload != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    titleLine,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val q = source.qualityTag
                if (!q.isNullOrBlank()) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            q,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (source.isMagnet) {
                    Text(
                        "⚡",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            descLines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onDownload != null) {
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download this stream",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private fun TmdbMovie.pickerYear(): Int? =
    releaseDate?.takeIf { it.isNotBlank() }?.substringBefore('-')?.toIntOrNull()

private fun StremioStream.pickerToPlayerSource(addon: InstalledStremioAddon): PlayerSource? {
    val playable = pickerPlayableUrl() ?: return null
    val isMagnet = playable.startsWith("magnet:")
    val label = title?.takeIf { it.isNotBlank() } ?: name ?: description ?: "Stream"
    val quality = pickerQualityTag()
    return PlayerSource(
        id = "${addon.id}::${(infoHash ?: ytId ?: url ?: "").take(64)}::${label.hashCode()}",
        url = playable,
        label = label,
        addonName = addon.name,
        qualityTag = quality,
        isMagnet = isMagnet,
        headers = stremioProxyRequestHeaders(),
    )
}

private fun StremioStream.stremioProxyRequestHeaders(): Map<String, String> {
    val proxyHeaders = behaviorHints?.proxyHeaders ?: return emptyMap()
    val requestObj = proxyHeaders["request"] as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
    val result = mutableMapOf<String, String>()
    for ((k, v) in requestObj) {
        val prim = v as? kotlinx.serialization.json.JsonPrimitive ?: continue
        result[k] = prim.content
    }
    return result
}

private fun StremioStream.pickerPlayableUrl(): String? = when {
    !url.isNullOrBlank() -> url
    !ytId.isNullOrBlank() -> "https://www.youtube.com/watch?v=$ytId"
    !infoHash.isNullOrBlank() -> {
        val baseTrackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
        )
        val trackers = (sources?.filter { it.startsWith("tracker:") }
            ?.map { it.removePrefix("tracker:") } ?: emptyList()) + baseTrackers
        val dn = title?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: "Stream"
        val trk = trackers.joinToString("&") { "tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        "magnet:?xt=urn:btih:$infoHash&dn=$dn&$trk"
    }
    else -> null
}

private fun StremioStream.pickerQualityTag(): String? {
    val h = listOfNotNull(name, title, description).joinToString(" ").lowercase()
    return when {
        "2160" in h || "4k" in h || "uhd" in h -> "4K"
        "1440" in h -> "1440p"
        "1080" in h -> "1080p"
        "720" in h -> "720p"
        "480" in h -> "480p"
        "hd" in h -> "HD"
        else -> null
    }
}

private fun NuvioStream.pickerToPlayerSource(provider: InstalledNuvioProvider): PlayerSource {
    val cleanName = name?.trim()?.takeIf { it.isNotBlank() }
    val label = buildString {
        if (!cleanName.isNullOrBlank()) append(cleanName)
        val desc = title?.trim()?.takeIf { it.isNotBlank() }
        if (!desc.isNullOrBlank()) {
            if (isNotEmpty()) append("\n")
            append(desc)
        }
        if (isEmpty()) append("Stream")
    }
    val qualityHint = quality?.takeIf { it.isNotBlank() }
        ?: name?.lines()?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()
    return PlayerSource(
        id = "nuvio::${provider.id}::${url.hashCode()}::${label.hashCode()}",
        url = url,
        label = label,
        addonName = provider.name,
        qualityTag = pickerNormaliseQuality(qualityHint),
        isMagnet = url.startsWith("magnet:"),
        headers = headers ?: emptyMap(),
        subtitleTracks = toAddonSubtitles(provider.name),
        languageTag = language,
    )
}

/**
 * Returns true if [url] looks like a single downloadable video file (MP4, MKV, AVI, etc.)
 * rather than an HLS/DASH adaptive playlist or a generic opaque URL.
 * Magnet links are handled separately via [PlayerSource.isMagnet].
 */
private fun pickerIsDirectStream(url: String): Boolean {
    val path = url.substringBefore('?').lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".mpd")
        || path.contains("/hls/") || path.contains("/dash/")
    ) return false
    val directExts = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "flv", "wmv", "m4v")
    val ext = path.substringAfterLast('.').takeIf { it.length in 2..4 }
    if (ext != null && ext in directExts) return true
    val isLocalhost = url.startsWith("http://127.") || url.startsWith("http://localhost")
    return isLocalhost
}

private fun pickerQualityRank(q: String?): Int = when (q) {
    "4K"    -> 50
    "1440p" -> 40
    "1080p" -> 30
    "720p"  -> 20
    "HD"    -> 20
    "480p"  -> 10
    "360p"  ->  5
    else    -> 15  // unknown quality: better than 480p, probably HD
}

private fun pickerNormaliseQuality(q: String?): String? {
    if (q.isNullOrBlank()) return null
    val s = q.trim()
    return when {
        s.equals("4K", ignoreCase = true) || s.contains("2160") || s.contains("uhd", ignoreCase = true) -> "4K"
        s.contains("1440") || s.equals("2K", ignoreCase = true) -> "1440p"
        s.contains("1080") || s.equals("fhd", ignoreCase = true) || s.equals("fullhd", ignoreCase = true) -> "1080p"
        s.contains("720") || s.equals("hd", ignoreCase = true) -> "720p"
        s.contains("480") || s.equals("sd", ignoreCase = true) -> "480p"
        s.contains("360") -> "360p"
        else -> s
    }
}

private suspend fun resolveCsForPicker(
    context: Context,
    plugin: InstalledPlugin,
    title: String,
    year: Int?,
    contentKind: CloudStreamContentKind,
    season: Int?,
    episode: Int?,
    onPartialStreams: suspend (List<PlayerSource>) -> Unit = {},
): CloudStreamPickerResult {
    return try {
        if (title.isBlank()) {
            return CloudStreamPickerResult(emptyList(), "A title is required to search this plugin.")
        }
        val results: List<CloudStreamSearchMatch> = try {
            PluginRuntime.searchWithApi(context, plugin.filePath, title, contentKind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return CloudStreamPickerResult(
                emptyList(),
                "CloudStream search failed: ${e.message ?: "unknown error"}",
            )
        }
        if (results.isEmpty()) {
            val message = PluginRuntime.lastErrorFor(plugin.filePath)
                ?: "No results found for this title in ${plugin.name}."
            return CloudStreamPickerResult(
                emptyList(),
                message,
                emptyResultConfirmed = cloudStreamNoLinksConfirmed(message),
            )
        }
        val best = pickerBestMatch(results, title, year, contentKind)
            ?: return CloudStreamPickerResult(
                emptyList(),
                "No matching title found in ${plugin.name}.",
                emptyResultConfirmed = true,
            )
        val detail = try {
            PluginRuntime.loadDetail(
                context = context,
                filePath = plugin.filePath,
                url = best.response.url,
                preferredApi = best.api,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return CloudStreamPickerResult(
            emptyList(),
            PluginRuntime.lastErrorFor(plugin.filePath)
                ?: "Could not load the matching title from ${plugin.name}.",
        )
        val dataStr: String? = when (detail) {
            is MovieLoadResponse -> detail.dataUrl
            is TvSeriesLoadResponse -> {
                pickerEpisodeData(detail.episodes, season, episode)
            }
            is AnimeLoadResponse -> {
                val eps = detail.episodes.values.flatten()
                pickerEpisodeData(eps, season, episode)
            }
            else -> null
        }
        val data = dataStr ?: run {
            val isEpisodeResponse =
                detail is TvSeriesLoadResponse || detail is AnimeLoadResponse
            val message = if (isEpisodeResponse && season != null && episode != null) {
                "${plugin.name} does not list episode S${season}E${episode}."
            } else if (isEpisodeResponse) {
                "Choose a TV episode before loading sources from ${plugin.name}."
            } else {
                "${plugin.name} returned an unsupported title type."
            }
            return CloudStreamPickerResult(
                emptyList(),
                message,
                emptyResultConfirmed = cloudStreamNoLinksConfirmed(message),
            )
        }
        val sourceRoute = "cs:${plugin.internalName}|||${best.response.url}|||${best.response.name}|||"
        val partialSources = CopyOnWriteArrayList<PlayerSource>()
        val seenPartialUrls = ConcurrentHashMap.newKeySet<String>()
        val (links, _) = try {
            PluginRuntime.loadLinks(
                context = context,
                filePath = plugin.filePath,
                data = data,
                isCasting = false,
                preferredApi = best.api,
                onResolvedLink = { link ->
                    val source = listOf(link)
                        .pickerToCsSources(plugin.name, sourceRoute)
                        .firstOrNull()
                    if (source != null && seenPartialUrls.add(source.url)) {
                        partialSources += source
                        onPartialStreams(partialSources.toList())
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return CloudStreamPickerResult(
                emptyList(),
                "Could not load streams from ${plugin.name}: ${e.message ?: "unknown error"}",
            )
        }
        val sources = links.pickerToCsSources(plugin.name, sourceRoute).distinctBy { it.url }
        val error = if (sources.isEmpty()) {
            PluginRuntime.lastErrorFor(plugin.filePath)
                ?: "No streams were returned by ${plugin.name}."
        } else {
            null
        }
        CloudStreamPickerResult(
            streams = sources,
            error = error,
            emptyResultConfirmed = sources.isEmpty() && cloudStreamNoLinksConfirmed(error),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        CloudStreamPickerResult(
            emptyList(),
            "CloudStream could not resolve sources from ${plugin.name}.",
        )
    }
}

private fun pickerEpisodeData(
    episodes: List<Episode>,
    season: Int?,
    episode: Int?,
): String? {
    if (season != null && episode != null) {
        return episodes.firstOrNull { it.season == season && it.episode == episode }?.data
            ?: episodes.singleOrNull()
                ?.takeIf { it.season == null || it.episode == null }
                ?.data
    }
    return episodes.singleOrNull()?.data
}

private fun pickerBestMatch(
    results: List<CloudStreamSearchMatch>,
    title: String,
    year: Int?,
    contentKind: CloudStreamContentKind,
): CloudStreamSearchMatch? {
    if (results.isEmpty()) return null
    return results
        .asSequence()
        .filter { searchResponseMatchesContentKind(it.response, contentKind) }
        .filter { hasPlausibleCloudStreamTitleMatch(it.response.name, title) }
        .map { match ->
            var score = if (match.response.name.equals(title, ignoreCase = true)) 100 else 50
            if (year != null) {
                val responseYear = (match.response as? com.lagradost.cloudstream3.MovieSearchResponse)?.year
                    ?: (match.response as? com.lagradost.cloudstream3.TvSeriesSearchResponse)?.year
                if (responseYear == year) score += 30
            }
            match to score
        }
        .sortedByDescending { it.second }
        .firstOrNull()
        ?.first
}

private fun List<ExtractorLink>.pickerToCsSources(
    pluginName: String,
    sourceRoute: String,
): List<PlayerSource> =
    mapIndexedNotNull { idx, link ->
        if (link.url.isBlank()) return@mapIndexedNotNull null
        val extractorName = link.source.takeIf { it.isNotBlank() } ?: pluginName
        val label = when {
            link.name.isBlank() -> extractorName
            link.name.trim().equals(pluginName.trim(), ignoreCase = true) -> extractorName
            else -> link.name
        }
        val quality: String? = when {
            link.quality >= 2160 -> "4K"
            link.quality >= 1440 -> "1440p"
            link.quality >= 1080 -> "1080p"
            link.quality >= 720 -> "720p"
            link.quality >= 480 -> "480p"
            link.quality > 0 -> "${link.quality}p"
            else -> null
        }
        PlayerSource(
            id = "cs::$pluginName::${link.url.hashCode()}::$idx",
            url = link.url,
            label = label,
            addonName = extractorName,
            qualityTag = quality,
            isMagnet = link.url.startsWith("magnet:"),
            sourceRoute = sourceRoute,
            headers = buildMap {
                if (link.referer.isNotBlank()) put("Referer", link.referer)
                putAll(link.headers)
            },
        )
    }
