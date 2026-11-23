package com.streamcloud.app.ui.screens

import android.content.Context
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.buildStreamProviderPriorityEntries
import com.streamcloud.app.data.orderStreamProviderEntries
import com.streamcloud.app.data.plugins.CloudStreamContentKind
import com.streamcloud.app.data.plugins.detectCloudStreamContentKind
import com.streamcloud.app.data.plugins.supportsPickerContent
import com.streamcloud.app.data.streamPickerTabLabel
import com.streamcloud.app.player.PlayerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Refreshes player sources through the same installed-provider discovery paths as the
 * movie details picker. Results are reported provider-by-provider so the player can
 * display streams as soon as each provider finishes.
 */
internal suspend fun discoverPlayerSources(
    context: Context,
    services: ServiceLocator,
    tmdbId: Long,
    mediaType: String,
    title: String,
    season: Int?,
    episode: Int?,
    onProviderNames: suspend (List<String>) -> Unit,
    onProviderResult: suspend (
        providerName: String,
        streams: List<PlayerSource>,
        error: String?,
        removeIfEmpty: Boolean,
    ) -> Unit,
    onPartialCloudStreamSources: suspend (List<PlayerSource>) -> Unit,
) = coroutineScope {
    val metadataTask = async(Dispatchers.IO) {
        val movie = try {
            if (mediaType.equals("tv", ignoreCase = true)) {
                services.tmdb.tvDetails(tmdbId, services.tmdbApiKey)
            } else {
                services.tmdb.details(tmdbId, services.tmdbApiKey)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        val imdbId = try {
            if (mediaType.equals("tv", ignoreCase = true)) {
                services.tmdb.tvExternalIds(tmdbId, services.tmdbApiKey).imdbId
            } else {
                services.tmdb.externalIds(tmdbId, services.tmdbApiKey).imdbId
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        movie to imdbId
    }
    val addonsTask = async {
        try {
            services.stremio.addons.first()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
    }
    val nuvioTask = async {
        try {
            services.nuvio.installed.first()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
    }
    val pluginsTask = async {
        try {
            services.plugins.installed.first()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
    }

    val (metadata, imdbId) = metadataTask.await()
    val addons = addonsTask.await()
    val nuvioProviders = nuvioTask.await()
    val plugins = pluginsTask.await()
    val contentKind = metadata?.let {
        detectCloudStreamContentKind(
            mediaType = mediaType,
            isAdult = it.adult,
            genreIds = it.genreIds,
            genreNames = it.genres.map { genre -> genre.name },
            originalLanguage = it.originalLanguage,
            originCountries = it.originCountry,
            title = it.displayTitle,
        )
    } ?: if (mediaType.equals("tv", ignoreCase = true)) {
        CloudStreamContentKind.TV_SERIES
    } else {
        CloudStreamContentKind.MOVIE
    }
    val searchTitle = metadata?.displayTitle?.takeIf { it.isNotBlank() } ?: title
    val year = (metadata?.releaseDate ?: metadata?.firstAirDate)
        ?.substringBefore('-')
        ?.toIntOrNull()
    val eligiblePlugins = plugins.filter { it.supportsPickerContent(contentKind) }
    val entries = buildStreamProviderPriorityEntries(addons, nuvioProviders, eligiblePlugins)
    val savedOrder = runCatching { services.settings.streamProviderPriorityOrder.first() }
        .getOrDefault(emptyList())
    val orderedEntries = orderStreamProviderEntries(entries, savedOrder)
    onProviderNames(orderedEntries.map { entry -> streamPickerTabLabel(entry.key, entry.name) })

    coroutineScope {
        val jobs = buildList {
            addons.forEach { addon ->
                add(async {
                    val sources = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(20_000L) {
                            try {
                                val ids = buildList {
                                    if (!imdbId.isNullOrBlank()) add(imdbId)
                                    if (!imdbId.orEmpty().startsWith("tmdb:")) add("tmdb:$tmdbId")
                                }
                                val seen = mutableSetOf<String>()
                                ids.flatMap { id ->
                                    withTimeoutOrNull(20_000L) {
                                        services.stremio.fetchStreams(
                                            addon,
                                            if (mediaType.equals("tv", true)) "series" else "movie",
                                            id,
                                        ).mapNotNull { it.pickerToPlayerSource(addon) }
                                    }.orEmpty()
                                }.filter { seen.add(it.url) }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                emptyList()
                            }
                        }.orEmpty()
                    }
                    onProviderResult(addon.name, sources, null, removeIfEmpty = sources.isEmpty())
                })
            }

            nuvioProviders.forEach { provider ->
                add(async {
                    val streams = withTimeoutOrNull(30_000L) {
                        try {
                            services.nuvio.resolveSingle(
                                provider = provider,
                                tmdbId = tmdbId.toString(),
                                mediaType = mediaType,
                                season = season,
                                episode = episode,
                                imdbId = imdbId,
                            ).map { it.pickerToPlayerSource(provider) }.distinctBy { it.url }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    }.orEmpty()
                    val error = if (streams.isEmpty()) {
                        com.streamcloud.app.data.nuvio.NuvioRuntime.lastError(provider.id)
                    } else {
                        null
                    }
                    onProviderResult(provider.name, streams, error, removeIfEmpty = streams.isEmpty())
                })
            }

            eligiblePlugins.forEach { plugin ->
                add(async {
                    val result = withTimeoutOrNull(120_000L) {
                        try {
                            resolveCsForPicker(
                                context = context,
                                plugin = plugin,
                                title = searchTitle,
                                year = year,
                                contentKind = contentKind,
                                season = season,
                                episode = episode,
                                onPartialStreams = onPartialCloudStreamSources,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            CloudStreamPickerResult(
                                streams = emptyList(),
                                error = "CloudStream plugin failed: ${error.message ?: "unknown error"}",
                            )
                        }
                    } ?: CloudStreamPickerResult(
                        streams = emptyList(),
                        error = "CloudStream plugin timed out after 120 seconds.",
                    )
                    val providerName = streamPickerTabLabel("cs:${plugin.internalName}", plugin.name)
                    onProviderResult(
                        providerName,
                        result.streams,
                        result.error,
                        removeIfEmpty = result.emptyResultConfirmed,
                    )
                })
            }
        }
        jobs.awaitAll()
    }
}
