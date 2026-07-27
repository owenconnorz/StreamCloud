package com.streamcloud.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import android.util.Log
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbCastMember
import com.streamcloud.app.data.api.TmdbCredits
import com.streamcloud.app.data.api.TmdbEpisode
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.data.api.TmdbTvSeasonSummary
import com.streamcloud.app.data.api.TmdbVideo
import com.streamcloud.app.data.nuvio.InstalledNuvioProvider
import com.streamcloud.app.data.nuvio.NuvioStream
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.data.downloads.MovieDownloader
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.ui.components.MagnetOptionsSheet
import com.streamcloud.app.data.library.WatchedMovieEntity
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioStream
import com.streamcloud.app.player.PlayerSource
import com.streamcloud.app.player.StreamCacheRepository
import com.streamcloud.app.player.WatchProgressKey
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.MoviesThemeWrapper
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.viewmodel.MoviesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    movieId: Long,
    mediaType: String = "movie",
    onBack: () -> Unit,
    onPlay: (initialUrl: String, title: String, sources: List<PlayerSource>, progressKey: WatchProgressKey) -> Unit,
    onOpenCsPluginForMovie: (internalName: String, title: String) -> Unit = { _, _ -> },
    onMovieClick: (Long) -> Unit = {},
    onTvClick: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val moviesThemeName by sl.settings.moviesTheme.collectAsState(initial = "violet")

    var movie by remember { mutableStateOf<TmdbMovie?>(null) }
    var videos by remember { mutableStateOf<List<TmdbVideo>>(emptyList()) }
    var imdbId by remember { mutableStateOf<String?>(null) }
    var credits by remember { mutableStateOf<TmdbCredits?>(null) }
    var similar by remember { mutableStateOf<List<TmdbMovie>>(emptyList()) }
    var certification by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var overviewExpanded by remember { mutableStateOf(false) }
    var trailerTypeFilter by remember { mutableStateOf("Trailer") }

    val installedAddons by sl.stremio.addons.collectAsState(initial = emptyList())
    val installedNuvio by sl.nuvio.installed.collectAsState(initial = emptyList())
    val installedCsPlugins by sl.plugins.installed.collectAsState(initial = emptyList())
    var resolving by remember { mutableStateOf(false) }
    var resolverMessage by remember { mutableStateOf<String?>(null) }
    var resolutionJob by remember { mutableStateOf<Job?>(null) }

    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val playBtnFocus = remember { FocusRequester() }
    LaunchedEffect(movie != null) {
        if (isTv && movie != null) try { playBtnFocus.requestFocus() } catch (_: Exception) {}
    }

    var showStreamPicker by remember { mutableStateOf(false) }
    var pickerSeason by remember { mutableStateOf<Int?>(null) }
    var pickerEpisode by remember { mutableStateOf<Int?>(null) }
    var pickerEpTitle by remember { mutableStateOf<String?>(null) }

    var tvSeasons by remember { mutableStateOf<List<TmdbTvSeasonSummary>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var tvEpisodes by remember { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var loadingEpisodes by remember { mutableStateOf(false) }

    var csPickerPlugin by remember { mutableStateOf<InstalledPlugin?>(null) }
    var csPickerSources by remember { mutableStateOf<List<ExtractorLink>>(emptyList()) }
    var csPickerSubs by remember { mutableStateOf<List<SubtitleFile>>(emptyList()) }
    var csPickerLoading by remember { mutableStateOf(false) }
    var csPickerError by remember { mutableStateOf<String?>(null) }
    var csPickerSelSource by remember { mutableStateOf<ExtractorLink?>(null) }
    var csPickerSelSub by remember { mutableStateOf<SubtitleFile?>(null) }

    LaunchedEffect(movieId) {
        imdbId = null; movie = null; credits = null; similar = emptyList()
        certification = null; overviewExpanded = false

        try {
            val movieJob = async {
                if (mediaType == "tv") sl.tmdb.tvDetails(movieId, sl.tmdbApiKey)
                else sl.tmdb.details(movieId, sl.tmdbApiKey)
            }
            val videosJob = async {
                runCatching {
                    if (mediaType == "tv") sl.tmdb.tvVideos(movieId, sl.tmdbApiKey).results
                    else sl.tmdb.videos(movieId, sl.tmdbApiKey).results
                }.getOrDefault(emptyList())
            }
            val imdbJob = async {
                runCatching {
                    if (mediaType == "tv") sl.tmdb.tvExternalIds(movieId, sl.tmdbApiKey).imdbId
                    else sl.tmdb.externalIds(movieId, sl.tmdbApiKey).imdbId
                }.getOrNull()
            }
            val creditsJob = async {
                runCatching {
                    if (mediaType == "tv") sl.tmdb.tvCredits(movieId, sl.tmdbApiKey)
                    else sl.tmdb.movieCredits(movieId, sl.tmdbApiKey)
                }.getOrNull()
            }
            val similarJob = async {
                runCatching {
                    if (mediaType == "tv") sl.tmdb.tvSimilar(movieId, sl.tmdbApiKey).results
                    else sl.tmdb.movieSimilar(movieId, sl.tmdbApiKey).results
                }.getOrDefault(emptyList())
            }
            val certJob = async {
                runCatching {
                    if (mediaType == "tv") {
                        sl.tmdb.tvContentRatings(movieId, sl.tmdbApiKey).results
                            .firstOrNull { it.country == "US" }?.rating?.takeIf { it.isNotBlank() }
                    } else {
                        val rdResult = sl.tmdb.movieReleaseDates(movieId, sl.tmdbApiKey).results
                            .firstOrNull { it.country == "US" }
                        rdResult?.releaseDates
                            ?.filter { it.certification.isNotBlank() }
                            ?.maxByOrNull { it.type }
                            ?.certification
                    }
                }.getOrNull()
            }

            val tmdbMovie = movieJob.await()
            movie = tmdbMovie
            videos = videosJob.await()
            imdbId = imdbJob.await()
            credits = creditsJob.await()
            similar = similarJob.await().take(12)
            certification = certJob.await()

            if (mediaType == "tv") {
                val seasons = tmdbMovie.seasons.filter { it.seasonNumber > 0 }
                tvSeasons = seasons
                if (seasons.isNotEmpty() && selectedSeason == null) {
                    selectedSeason = seasons.first().seasonNumber
                }
            }
        } catch (e: Exception) {
            error = "Failed to load: ${e.message}"
        }
    }

    LaunchedEffect(selectedSeason) {
        val s = selectedSeason ?: return@LaunchedEffect
        if (mediaType != "tv") return@LaunchedEffect
        loadingEpisodes = true
        tvEpisodes = runCatching {
            sl.tmdb.tvSeasonDetail(movieId, s, sl.tmdbApiKey).episodes
        }.getOrDefault(emptyList())
        loadingEpisodes = false
    }

    val watchedDao = remember { LibraryDb.get(context.applicationContext).watchedMovies() }
    val isWatched by watchedDao.isWatched(movieId).collectAsState(initial = false)

    fun playMovie() {
        imdbId ?: run { resolverMessage = "Loading IMDB id… try again in a second."; return }
        if (installedAddons.isEmpty() && installedNuvio.isEmpty() && installedCsPlugins.isEmpty()) {
            resolverMessage = "No Stremio addons, Nuvio providers or CloudStream plugins installed."
            return
        }
        resolverMessage = null; pickerSeason = null; pickerEpisode = null; pickerEpTitle = null
        showStreamPicker = true
    }

    fun playEpisode(seasonNum: Int, episodeNum: Int, episodeTitle: String?) {
        imdbId ?: run { resolverMessage = "Loading IMDB id… try again in a second."; return }
        if (installedAddons.isEmpty() && installedNuvio.isEmpty()) {
            resolverMessage = "No Stremio addons or Nuvio providers installed."
            return
        }
        pickerSeason = seasonNum; pickerEpisode = episodeNum; pickerEpTitle = episodeTitle
        showStreamPicker = true
    }

    fun toggleWatched() {
        val m = movie ?: return
        scope.launch {
            if (isWatched) {
                watchedDao.unmark(movieId)
            } else {
                watchedDao.mark(
                    WatchedMovieEntity(
                        tmdbId = movieId, title = m.displayTitle,
                        posterUrl = m.posterUrl, mediaType = mediaType,
                    ),
                )
            }
        }
    }

    fun openCsSourcePicker(plugin: InstalledPlugin) {
        val title = movie?.displayTitle ?: return
        val year = movie?.year()
        csPickerPlugin = plugin; csPickerSources = emptyList(); csPickerSubs = emptyList()
        csPickerSelSource = null; csPickerSelSub = null; csPickerError = null; csPickerLoading = true
        scope.launch {
            try {
                val results = runCatching { PluginRuntime.search(context, plugin.filePath, title) }.getOrDefault(emptyList())
                val best = pickBestMatch(results, title, year)
                if (best == null) { csPickerError = "No results found for \"$title\" in ${plugin.name}."; return@launch }
                val detail = runCatching { PluginRuntime.loadDetail(context, plugin.filePath, best.url) }.getOrNull()
                val dataStr = when (detail) {
                    is MovieLoadResponse -> detail.dataUrl
                    is LiveStreamLoadResponse -> detail.dataUrl
                    is TvSeriesLoadResponse -> detail.episodes.singleOrNull()?.data
                    is AnimeLoadResponse -> detail.episodes.values.flatten().singleOrNull()?.data
                    else -> null
                } ?: run { csPickerError = "Could not load details from ${plugin.name}."; return@launch }
                val (links, subs) = runCatching { PluginRuntime.loadLinks(context, plugin.filePath, dataStr) }
                    .getOrElse { emptyList<ExtractorLink>() to emptyList<SubtitleFile>() }
                if (links.isEmpty()) { csPickerError = "No streams found for \"$title\" in ${plugin.name}."; return@launch }
                csPickerSources = links.sortedByDescending { it.quality }
                csPickerSubs = subs
                csPickerSelSource = csPickerSources.first()
            } catch (e: Throwable) {
                csPickerError = "Error: ${e.message}"
            } finally {
                csPickerLoading = false
            }
        }
    }

    val moviesVm: MoviesViewModel = viewModel(factory = MoviesViewModel.factory(context))
    val watchlistIds = moviesVm.state.collectAsState().value.watchlist.map { it.tmdbId }.toSet()
    val inWatchlist = movie?.id?.let { it in watchlistIds } ?: false

    var actionsExpanded by remember { mutableStateOf(false) }
    var magnetSource by remember { mutableStateOf<PlayerSource?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    val downloadDao = remember { LibraryDb.get(context.applicationContext).movieDownloads() }
    val downloadEntry by downloadDao.watchById(movieId).collectAsState(initial = null)
    val downloadProgressMap by MovieDownloader.progressFlow.collectAsState(initial = emptyMap())
    val downloadProgress = downloadProgressMap[movieId]

    MoviesThemeWrapper(moviesThemeName) {
    val scrollState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionDown -> { scrollScope.launch { scrollState.scrollBy(300f) }; true }
                        Key.DirectionUp   -> { scrollScope.launch { scrollState.scrollBy(-300f) }; true }
                        else -> false
                    }
                } else false
            }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {

            // ── Backdrop ──────────────────────────────────────────────────────
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                AsyncImage(
                    model = movie?.backdropUrl ?: movie?.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.3f), Color.Transparent, Color.Transparent,
                                MaterialTheme.colorScheme.background)
                        )
                    )
                )
            }

            // ── Main content ──────────────────────────────────────────────────
            Column(
                Modifier.padding(horizontal = 20.dp).offset(y = (-50).dp),
            ) {
                // Title
                Text(
                    movie?.displayTitle ?: "Loading…",
                    style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                )

                // TV genres subtitle
                movie?.genres?.takeIf { it.isNotEmpty() && mediaType == "tv" }?.let { genres ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        genres.joinToString(" • ") { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Play button ───────────────────────────────────────────────
                val addonCount = installedAddons.size + installedNuvio.size + installedCsPlugins.size
                val playEnabled = imdbId != null && addonCount > 0 && !resolving
                if (mediaType == "tv") {
                    val firstSeason = tvSeasons.firstOrNull()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                firstSeason?.let { playEpisode(it.seasonNumber, 1, null) }
                                    ?: run { resolverMessage = "Seasons not loaded yet." }
                            },
                            enabled = playEnabled && firstSeason != null,
                            modifier = Modifier.weight(1f).height(52.dp)
                                .tvFocusBorder(RoundedCornerShape(50))
                                .then(if (isTv) Modifier.focusRequester(playBtnFocus) else Modifier),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                                disabledContainerColor = MaterialTheme.colorScheme.surface,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Play S${firstSeason?.seasonNumber ?: 1}E1",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                        AnimatedVisibility(
                            visible = actionsExpanded,
                            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MovieActionCircle(
                                    icon = if (inWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    active = inWatchlist,
                                ) {
                                    movie?.let { moviesVm.toggleWatchlist(it.id, it.displayTitle, it.posterUrl, mediaType) }
                                }
                                MovieActionCircle(
                                    icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.Check,
                                    active = isWatched,
                                ) { toggleWatched() }
                            }
                        }
                        MovieActionCircle(
                            icon = if (actionsExpanded) Icons.Default.Close else Icons.Default.MoreVert,
                            active = false,
                        ) { actionsExpanded = !actionsExpanded }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.weight(1f)) {
                            PlayMovieCta(
                                addonCount = addonCount,
                                enabled = playEnabled,
                                loading = resolving,
                                downloadProgress = downloadProgress,
                                onClick = { playMovie() },
                                modifier = if (isTv) Modifier.focusRequester(playBtnFocus) else Modifier,
                            )
                        }
                        AnimatedVisibility(
                            visible = actionsExpanded,
                            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                        ) {
                            val dlDone = downloadEntry?.status == "done"
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MovieActionCircle(
                                    icon = if (inWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    active = inWatchlist,
                                ) {
                                    movie?.let { moviesVm.toggleWatchlist(it.id, it.displayTitle, it.posterUrl, mediaType) }
                                }
                                MovieActionCircle(
                                    icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.Check,
                                    active = isWatched,
                                ) { toggleWatched() }
                            }
                        }
                        MovieActionCircle(
                            icon = if (actionsExpanded) Icons.Default.Close else Icons.Default.MoreVert,
                            active = false,
                        ) { actionsExpanded = !actionsExpanded }
                    }
                }

                resolverMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(16.dp))

                // ── Metadata row: Year · Runtime · Cert · IMDb ────────────────
                movie?.let { m ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val year = (m.releaseDate ?: m.firstAirDate)?.substringBefore('-')
                            ?.takeIf { it.length == 4 }
                        year?.let {
                            Text(
                                if (mediaType == "tv") {
                                    val endY = m.lastAirDate?.substringBefore('-')?.takeIf { it.length == 4 }
                                    val ongoing = m.status in listOf("Returning Series", "In Production", "Planned")
                                    if (endY != null && !ongoing) "$it–$endY" else "$it–"
                                } else it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        m.displayRuntime()?.let { rt ->
                            MetaDot()
                            Text(rt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        certification?.takeIf { it.isNotBlank() }?.let { cert ->
                            MetaDot()
                            CertBadge(cert)
                        }
                        if (m.voteAverage > 0) {
                            MetaDot()
                            ImdbBadge(m.voteAverage)
                        }
                    }
                }

                // ── CS source finder (movies) ──────────────────────────────────
                val csTitle = movie?.displayTitle.orEmpty()
                if (mediaType != "tv" && installedCsPlugins.isNotEmpty() && csTitle.isNotBlank()) {
                    val safePlugins = remember(installedCsPlugins) { installedCsPlugins.filter { !it.isAdultPlugin() } }
                    if (safePlugins.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Find in Source", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            safePlugins.forEach { plugin ->
                                Box(
                                    Modifier.clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable { openCsSourcePicker(plugin) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(plugin.name, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Director / Writer / Creator ────────────────────────────────
                credits?.let { cr ->
                    if (mediaType == "tv") {
                        val creatorNames: List<String> =
                            movie?.createdBy?.map { it.name }?.takeIf { it.isNotEmpty() }
                                ?: cr.crew
                                    .filter { it.job?.lowercase() in listOf("creator", "executive producer") }
                                    .distinctBy { it.name }.take(3).map { it.name }
                        if (creatorNames.isNotEmpty()) {
                            CreditsRow("Creator", creatorNames.joinToString(", "))
                        }
                    } else {
                        val directors = cr.crew.filter { it.job == "Director" }.distinctBy { it.name }.take(3)
                        val writers = cr.crew.filter { it.job in listOf("Writer", "Screenplay", "Story") }
                            .distinctBy { it.name }.take(3)
                        if (directors.isNotEmpty()) CreditsRow("Director", directors.joinToString(", ") { it.name })
                        if (writers.isNotEmpty()) CreditsRow("Writer", writers.joinToString(", ") { it.name })
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ── Overview ──────────────────────────────────────────────────
                val overview = movie?.overview?.takeIf { it.isNotBlank() } ?: "—"
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (overviewExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!overviewExpanded && overview.length > 180) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Show More ▾",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.clickable { overviewExpanded = true },
                    )
                }

                // ── Production / Networks ─────────────────────────────────────
                val logos: List<Pair<String, String?>> = remember(movie) {
                    if (mediaType == "tv") {
                        movie?.networks?.map { it.name to it.logoUrl } ?: emptyList()
                    } else {
                        movie?.productionCompanies?.filter { it.logoPath != null }
                            ?.map { it.name to it.logoUrl } ?: emptyList()
                    }
                }
                if (logos.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    val sectionTitle = if (mediaType == "tv") "Networks" else "Production"
                    SectionHeader(sectionTitle)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        logos.forEach { (name, logoUrl) ->
                            Box(
                                Modifier.size(width = 100.dp, height = 56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White)
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (logoUrl != null) {
                                    AsyncImage(model = logoUrl, contentDescription = name,
                                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                } else {
                                    Text(name, style = MaterialTheme.typography.labelSmall,
                                        color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // ── Cast ──────────────────────────────────────────────────────
                val cast = remember(credits) { credits?.cast?.take(15) ?: emptyList() }
                if (cast.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    SectionHeader("Cast")
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Cast LazyRow (outside horizontal padding so it goes edge-to-edge)
            val cast = remember(credits) { credits?.cast?.take(15) ?: emptyList() }
            if (cast.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(cast, key = { it.id }) { member ->
                        CastCard(member)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Column(Modifier.padding(horizontal = 20.dp)) {

                // ── Trailers ──────────────────────────────────────────────────
                val allTrailerTypes = remember(videos) { videos.map { it.type }.distinct() }
                val filteredVideos = remember(videos, trailerTypeFilter) {
                    videos.filter { it.type == trailerTypeFilter && it.site.equals("YouTube", ignoreCase = true) }
                }
                if (videos.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionHeader("Trailers")
                        if (allTrailerTypes.size > 1) {
                            var showTypeMenu by remember { mutableStateOf(false) }
                            Box {
                                Row(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable { showTypeMenu = true }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(trailerTypeFilter, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Icon(Icons.Default.KeyboardArrowDown, null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(
                                    expanded = showTypeMenu,
                                    onDismissRequest = { showTypeMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    allTrailerTypes.forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(type) },
                                            onClick = { trailerTypeFilter = type; showTypeMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Trailers grid (outside padding to go wider)
            val filteredVideos = remember(videos, trailerTypeFilter) {
                videos.filter { it.type == trailerTypeFilter && it.site.equals("YouTube", ignoreCase = true) }
                    .ifEmpty { videos.filter { it.site.equals("YouTube", ignoreCase = true) } }
            }
            if (filteredVideos.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filteredVideos, key = { it.key }) { vid ->
                        TrailerCard(vid) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vid.watchUrl)))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Column(Modifier.padding(horizontal = 20.dp)) {

                // ── TV: Seasons poster carousel + episode list ─────────────────
                if (mediaType == "tv" && tvSeasons.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    SectionHeader("Seasons")
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (mediaType == "tv" && tvSeasons.isNotEmpty()) {
                // Season poster carousel
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(tvSeasons.filter { it.seasonNumber > 0 }, key = { it.seasonNumber }) { season ->
                        val isSelected = selectedSeason == season.seasonNumber
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(100.dp)
                                .tvFocusBorder(RoundedCornerShape(10.dp))
                                .clickable { selectedSeason = season.seasonNumber },
                        ) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .then(
                                        if (isSelected) Modifier.border(
                                            2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)
                                        ) else Modifier
                                    ),
                            ) {
                                AsyncImage(
                                    model = season.posterUrl,
                                    contentDescription = season.name ?: "Season ${season.seasonNumber}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                season.name ?: "Season ${season.seasonNumber}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Episodes header
                selectedSeason?.let { s ->
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        SectionHeader("Season $s")
                        Spacer(Modifier.height(12.dp))
                    }
                }

                // Episode cards (horizontal Nuvio-style)
                when {
                    loadingEpisodes -> Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                    tvEpisodes.isEmpty() -> Column(Modifier.padding(horizontal = 20.dp)) {
                        Text("No episodes found.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(tvEpisodes, key = { "${it.seasonNumber}x${it.episodeNumber}" }) { ep ->
                            NuvioEpisodeCard(ep) { playEpisode(ep.seasonNumber, ep.episodeNumber, ep.name) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Column(Modifier.padding(horizontal = 20.dp)) {

                // ── Movie/Show Details table ──────────────────────────────────
                val m = movie
                if (m != null) {
                    Spacer(Modifier.height(28.dp))
                    SectionHeader(if (mediaType == "tv") "Show Details" else "Movie Details")
                    Spacer(Modifier.height(12.dp))

                    val detailRows = buildList {
                        m.status?.takeIf { it.isNotBlank() }?.let { add("Status" to it) }
                        m.displayReleaseInfo()?.let { add("Release Info" to it) }
                        m.displayRuntime()?.let { add("Runtime" to it) }
                        certification?.takeIf { it.isNotBlank() }?.let { add("Certification" to it) }
                        m.originCountry.firstOrNull()?.uppercase()?.takeIf { it.isNotBlank() }
                            ?.let { add("Origin Country" to it) }
                        m.originalLanguage?.uppercase()?.takeIf { it.isNotBlank() }
                            ?.let { add("Original Language" to it) }
                    }
                    detailRows.forEachIndexed { idx, (label, value) ->
                        if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground)
                            Text(value, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ── More Like This ────────────────────────────────────────────
                if (similar.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    SectionHeader("More Like This")
                    Spacer(Modifier.height(12.dp))
                }

                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }

            // Similar grid (outside padding for wider cards)
            if (similar.isNotEmpty()) {
                val similarRows = similar.chunked(2)
                Column(Modifier.padding(horizontal = 20.dp)) {
                    similarRows.forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { sm ->
                                Box(
                                    Modifier.weight(1f)
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable {
                                            if (mediaType == "tv") onTvClick(sm.id) else onMovieClick(sm.id)
                                        },
                                ) {
                                    AsyncImage(
                                        model = sm.backdropUrl ?: sm.posterUrl,
                                        contentDescription = sm.displayTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Box(
                                        Modifier.fillMaxWidth().align(Alignment.BottomStart)
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.7f))))
                                            .padding(8.dp),
                                    ) {
                                        Text(sm.displayTitle, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // Footer
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Powered by TMDB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        // ── Floating back button ──────────────────────────────────────────────
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(12.dp).clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.45f))
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
    }

    // ── CS source picker sheet ────────────────────────────────────────────────
    val csSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pickerPlugin = csPickerPlugin
    if (pickerPlugin != null) {
        ModalBottomSheet(
            onDismissRequest = { csPickerPlugin = null },
            sheetState = csSheetState,
            containerColor = Color(0xFF1C1C1E),
            contentColor = Color.White,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(pickerPlugin.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White, modifier = Modifier.weight(1f))
                    if (csPickerLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                when {
                    csPickerLoading && csPickerSources.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(12.dp))
                                Text("Searching ${pickerPlugin.name}…", color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    csPickerError != null -> {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp)) {
                            Column {
                                Text(csPickerError ?: "", color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(16.dp))
                                TextButton(onClick = { csPickerPlugin = null }) {
                                    Text("Close", color = Color.White.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth().height(300.dp)) {
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("Sources", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                                    if (!csPickerLoading && csPickerSources.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("${csPickerSources.size}", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                                    }
                                }
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(csPickerSources, key = { it.url }) { link ->
                                        val isSelected = link.url == csPickerSelSource?.url
                                        Row(
                                            Modifier.fillMaxWidth().clickable { csPickerSelSource = link }
                                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (isSelected) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                                            else Spacer(Modifier.width(24.dp))
                                            Column(Modifier.weight(1f)) {
                                                val qLabel = csQualityLabel(link.quality)
                                                val sourceName = link.name.ifBlank { link.source }
                                                Text(buildString { append(sourceName); if (qLabel != null) append(" · $qLabel") },
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                            VerticalDivider(color = Color.White.copy(alpha = 0.1f))
                            Column(Modifier.weight(1f)) {
                                Text("Subtitles", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                LazyColumn(Modifier.fillMaxSize()) {
                                    item(key = "no-sub") {
                                        val isSelected = csPickerSelSub == null
                                        Row(Modifier.fillMaxWidth().clickable { csPickerSelSub = null }
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            if (isSelected) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                                            else Spacer(Modifier.width(24.dp))
                                            Text("No Subtitles", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f))
                                        }
                                    }
                                    items(csPickerSubs, key = { it.url }) { sub ->
                                        val isSelected = sub.url == csPickerSelSub?.url
                                        Row(Modifier.fillMaxWidth().clickable { csPickerSelSub = sub }
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            if (isSelected) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                                            else Spacer(Modifier.width(24.dp))
                                            Text(sub.lang, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { csPickerPlugin = null }) { Text("Cancel", color = Color.White.copy(alpha = 0.6f)) }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val selLink = csPickerSelSource ?: return@Button
                                    val m = movie
                                    val displayTitle = m?.displayTitle ?: "Playback"
                                    val progressKey = WatchProgressKey(tmdbId = movieId, title = displayTitle,
                                        posterUrl = m?.posterUrl ?: m?.backdropUrl, mediaType = mediaType)
                                    val ps = csPickerSources.toCsPlayerSources(pickerPlugin.name)
                                    val selIdx = csPickerSources.indexOf(selLink)
                                    val selPs = ps.find { it.url == selLink.url }
                                        ?: if (selIdx >= 0 && selIdx < ps.size) ps[selIdx] else ps.firstOrNull() ?: return@Button
                                    val reordered = listOf(selPs) + ps.filter { it.url != selPs.url }
                                    csPickerPlugin = null
                                    onPlay(selPs.url, displayTitle, reordered, progressKey)
                                },
                                enabled = csPickerSelSource != null,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp),
                            ) { Text("Apply", color = Color.White) }
                        }
                    }
                }
            }
        }
    }

    if (showStreamPicker) {
        StreamPickerOverlay(
            movie = movie, mediaType = mediaType, tmdbId = movieId, imdbId = imdbId,
            season = pickerSeason, episode = pickerEpisode, episodeTitle = pickerEpTitle,
            installedAddons = installedAddons, installedNuvio = installedNuvio, installedCsPlugins = installedCsPlugins,
            onBack = { showStreamPicker = false },
            onPlay = { url, sources ->
                showStreamPicker = false
                val m = movie
                val displayTitle = buildString {
                    append(m?.displayTitle ?: "Playback")
                    val s = pickerSeason; val e = pickerEpisode
                    if (s != null && e != null) append(" S${s}E${e}")
                    pickerEpTitle?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                val progressKey = WatchProgressKey(tmdbId = movieId, title = displayTitle,
                    posterUrl = m?.posterUrl ?: m?.backdropUrl, mediaType = mediaType)
                onPlay(url, displayTitle, sources, progressKey)
            },
            onDownload = { source ->
                if (source.isMagnet) {
                    magnetSource = source
                } else {
                    showStreamPicker = false
                    val m = movie
                    scope.launch {
                        runCatching {
                            MovieDownloader.download(
                                context = context,
                                tmdbId = movieId,
                                title = m?.displayTitle ?: "Movie",
                                posterUrl = m?.posterUrl,
                                mediaType = mediaType,
                                url = source.url,
                                headers = source.headers,
                            )
                        }.onFailure { e ->
                            downloadError = e.message?.takeIf { it.isNotBlank() }
                                ?: "Download failed — the source may have expired."
                        }
                    }
                }
            },
        )
    }

    if (resolving && mediaType != "tv") {
        StreamingLoadingOverlay(
            title = movie?.displayTitle ?: "Loading…",
            backdropUrl = movie?.backdropUrl ?: movie?.posterUrl,
            onBack = { resolutionJob?.cancel(); resolving = false; resolverMessage = null },
        )
    }

    magnetSource?.let { src ->
        MagnetOptionsSheet(
            source = src,
            tmdbId = movieId,
            title = movie?.displayTitle ?: "",
            posterUrl = movie?.posterUrl,
            mediaType = mediaType,
            onDismiss = { magnetSource = null },
        )
    }

    downloadError?.let { msg ->
        androidx.compose.runtime.LaunchedEffect(msg) {
            kotlinx.coroutines.delay(4_000)
            downloadError = null
        }
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
        ) {
            androidx.compose.material3.Snackbar(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
                androidx.compose.material3.Text(
                    msg,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    } // MoviesThemeWrapper
}

// ─────────────────────────────────────────────────────────────────────────────
// Small UI components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MovieActionCircle(
    icon: ImageVector,
    active: Boolean,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(26.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun MetaDot() {
    Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CertBadge(cert: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(cert, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImdbBadge(rating: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFF5C518)).padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text("IMDb", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color.Black, fontSize = 10.sp)
        }
        Text(String.format("%.1f", rating), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun CreditsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label:  ", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// Cast card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CastCard(member: TmdbCastMember) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            Modifier.size(68.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (member.profileUrl != null) {
                AsyncImage(model = member.profileUrl, contentDescription = member.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        member.name.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(member.name, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        member.creditRole.takeIf { it.isNotBlank() }?.let { role ->
            Text(role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Trailer card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrailerCard(video: TmdbVideo, onClick: () -> Unit) {
    Column(Modifier.width(220.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(model = video.thumbnailUrl, contentDescription = video.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(0.55f)).align(Alignment.Center)) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(24.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(video.name ?: video.type, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        video.publishedAt?.substringBefore('-')?.let { year ->
            Text(year, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Nuvio-style episode card (horizontal carousel)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NuvioEpisodeCard(ep: TmdbEpisode, onClick: () -> Unit) {
    Column(Modifier.width(300.dp).tvFocusBorder(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(model = ep.stillUrl, contentDescription = ep.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Episode badge top-left
            Box(
                Modifier.padding(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .align(Alignment.TopStart),
            ) {
                Text("S${ep.seasonNumber}E${ep.episodeNumber}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White, fontSize = 10.sp)
            }
            // Bottom gradient + title
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f))))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Column {
                    Text(ep.name ?: "Episode ${ep.episodeNumber}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ep.overview?.takeIf { it.isNotBlank() }?.let { ov ->
                        Text(ov, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.75f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ep.runtime?.let { rt ->
                            Text("${rt}m", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.7f))
                        }
                        if (ep.voteAverage > 0) {
                            Box(Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFF5C518)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                                Text("IMDb", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.Black, fontSize = 9.sp)
                            }
                            Text(String.format("%.1f", ep.voteAverage), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.85f))
                        }
                        ep.airDate?.takeIf { it.isNotBlank() }?.let { date ->
                            Text(date.replace('-', ' ').let {
                                val parts = it.split(" ")
                                if (parts.size == 3) "${parts[0]} ${monthAbbr(parts[1])} ${parts[2]}" else it
                            }, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.6f))
                        }
                    }
                }
            }
        }
    }
}

private fun monthAbbr(m: String): String = when (m) {
    "01" -> "Jan"; "02" -> "Feb"; "03" -> "Mar"; "04" -> "Apr"
    "05" -> "May"; "06" -> "Jun"; "07" -> "Jul"; "08" -> "Aug"
    "09" -> "Sep"; "10" -> "Oct"; "11" -> "Nov"; "12" -> "Dec"
    else -> m
}

// ─────────────────────────────────────────────────────────────────────────────
// Play CTA
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlayMovieCta(
    addonCount: Int,
    enabled: Boolean,
    loading: Boolean,
    downloadProgress: Float? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary  = MaterialTheme.colorScheme.primary
    val surface  = MaterialTheme.colorScheme.surface
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val isDownloading = downloadProgress != null
    val animatedFill by animateFloatAsState(
        targetValue = downloadProgress ?: 0f,
        label = "dlFill",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .tvFocusBorder(RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .then(
                if (isDownloading)
                    Modifier.drawBehind {
                        drawRect(color = surface)
                        drawRect(color = primary, size = Size(width = size.width * animatedFill, height = size.height))
                    }
                else
                    Modifier.background(if (enabled) primary else surface)
            )
            .clickable(enabled = enabled && !isDownloading, onClick = onClick),
    ) {
        when {
            isDownloading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Download, null, tint = onPrimary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Downloading · ${(animatedFill * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = onPrimary,
                )
            }
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = onPrimary)
                Spacer(Modifier.width(12.dp))
                Text("Finding best stream…",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = onPrimary)
            }
            else -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null,
                    tint = if (enabled) onPrimary else onSurfaceVariant,
                    modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play Movie",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (enabled) onPrimary else onSurfaceVariant)
                if (addonCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("· $addonCount source${if (addonCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) onPrimary.copy(0.8f) else onSurfaceVariant)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stream conversion helpers (unchanged)
// ─────────────────────────────────────────────────────────────────────────────

private fun StremioStream.toPlayerSource(addon: InstalledStremioAddon): PlayerSource? {
    val playable = toPlayableUrl() ?: return null
    val isMagnet = playable.startsWith("magnet:")
    val label = title?.takeIf { it.isNotBlank() } ?: name ?: description ?: "Stream"
    return PlayerSource(
        id = addon.id + "::" + (infoHash ?: ytId ?: url ?: "").take(64) + "::" + label.hashCode(),
        url = playable, label = label, addonName = addon.name, qualityTag = qualityTag(), isMagnet = isMagnet,
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

private fun StremioStream.toPlayableUrl(): String? = when {
    !url.isNullOrBlank() -> url
    !ytId.isNullOrBlank() -> "https://www.youtube.com/watch?v=$ytId"
    !infoHash.isNullOrBlank() -> {
        val baseTrackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://9.rarbg.com:2810/announce",
        )
        val trackers = (sources?.filter { it.startsWith("tracker:") }?.map { it.removePrefix("tracker:") }
            ?: emptyList()) + baseTrackers
        val name = title?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: "Stream"
        val trk = trackers.joinToString("&") { "tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        "magnet:?xt=urn:btih:$infoHash&dn=$name&$trk"
    }
    else -> null
}

private fun StremioStream.qualityTag(): String? {
    val haystack = listOfNotNull(name, title, description).joinToString(" ").lowercase()
    return when {
        "2160" in haystack || "4k" in haystack || "uhd" in haystack -> "4K"
        "1440" in haystack -> "1440p"
        "1080" in haystack -> "1080p"
        "720" in haystack -> "720p"
        "480" in haystack -> "480p"
        "hd" in haystack -> "HD"
        else -> null
    }
}

private fun PlayerSource.qualityScore(): Int {
    val q = when (qualityTag) { "4K" -> 4; "1440p" -> 3; "1080p" -> 3; "720p" -> 2; "480p" -> 1; else -> 0 }
    return q * 10 + if (!isMagnet) 1 else 0
}

private fun NuvioStream.toPlayerSource(provider: InstalledNuvioProvider): PlayerSource {
    val cleanName = name?.trim()?.takeIf { it.isNotBlank() }
    val label = buildString {
        if (!cleanName.isNullOrBlank()) append(cleanName)
        val desc = title?.trim()?.takeIf { it.isNotBlank() }
        if (!desc.isNullOrBlank()) { if (isNotEmpty()) append("\n"); append(desc) }
        if (isEmpty()) append("Stream")
    }
    val qualityHint = quality?.takeIf { it.isNotBlank() }
        ?: name?.lines()?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()
    return PlayerSource(
        id = "nuvio::${provider.id}::${url.hashCode()}::${label.hashCode()}",
        url = url, label = label, addonName = provider.name,
        qualityTag = normaliseNuvioQuality(qualityHint), isMagnet = url.startsWith("magnet:"),
        headers = headers ?: emptyMap(),
    )
}

private fun normaliseNuvioQuality(q: String?): String? {
    if (q.isNullOrBlank()) return null
    val s = q.trim()
    return when {
        s.equals("4K", ignoreCase = true) || s.contains("2160") || s.contains("uhd", true) -> "4K"
        s.contains("1440") || s.equals("2K", ignoreCase = true) -> "1440p"
        s.contains("1080") || s.equals("fhd", true) || s.equals("fullhd", true) || s.equals("full hd", true) -> "1080p"
        s.contains("720") || s.equals("hd", ignoreCase = true) -> "720p"
        s.contains("480") || s.equals("sd", ignoreCase = true) -> "480p"
        s.contains("360") -> "360p"
        else -> s
    }
}

private suspend fun resolveCsPluginForMovie(
    context: android.content.Context,
    plugin: InstalledPlugin,
    title: String,
    year: Int?,
): List<PlayerSource> = try {
    val results = runCatching { PluginRuntime.search(context, plugin.filePath, title) }.getOrDefault(emptyList())
    val best = pickBestMatch(results, title, year) ?: return emptyList()
    val detail = runCatching { PluginRuntime.loadDetail(context, plugin.filePath, best.url) }.getOrNull() ?: return emptyList()
    val dataStr: String? = when (detail) {
        is MovieLoadResponse -> detail.dataUrl
        is LiveStreamLoadResponse -> detail.dataUrl
        is TvSeriesLoadResponse -> { val eps = detail.episodes; if (eps.size == 1) eps.first().data else null }
        is AnimeLoadResponse -> { val eps = detail.episodes.values.flatten(); if (eps.size == 1) eps.first().data else null }
        else -> null
    }
    val data = dataStr ?: return emptyList()
    val (links, _) = runCatching { PluginRuntime.loadLinks(context, plugin.filePath, data, isCasting = false) }.getOrElse { return emptyList() }
    links.toCsPlayerSources(plugin.name)
} catch (_: Throwable) { emptyList() }

private fun pickBestMatch(results: List<SearchResponse>, title: String, year: Int?): SearchResponse? {
    if (results.isEmpty()) return null
    val cleanTitle = title.normalizedTitle()
    return results.map { sr ->
        var score = 0
        if (sr.name.normalizedTitle() == cleanTitle) score += 50
        else if (sr.name.normalizedTitle().contains(cleanTitle)) score += 10
        if (year != null) {
            val srYear = (sr as? com.lagradost.cloudstream3.MovieSearchResponse)?.year
                ?: (sr as? com.lagradost.cloudstream3.TvSeriesSearchResponse)?.year
            if (srYear == year) score += 30
        }
        sr to score
    }.sortedByDescending { it.second }.firstOrNull()?.first
}

private fun String.normalizedTitle(): String = lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private fun List<ExtractorLink>.toCsPlayerSources(pluginDisplayName: String): List<PlayerSource> =
    this.mapIndexedNotNull { idx, link ->
        if (link.url.isBlank()) return@mapIndexedNotNull null
        val extractorName = link.source.takeIf { it.isNotBlank() } ?: pluginDisplayName
        val label = when {
            link.name.isBlank() -> extractorName
            link.name.trim().equals(pluginDisplayName.trim(), ignoreCase = true) -> extractorName
            else -> link.name
        }
        PlayerSource(id = "cs::$pluginDisplayName::${link.url.hashCode()}::$idx", url = link.url,
            label = label, addonName = extractorName, qualityTag = csQualityLabel(link.quality),
            isMagnet = link.url.startsWith("magnet:"),
            headers = buildMap { if (link.referer.isNotBlank()) put("Referer", link.referer); putAll(link.headers) })
    }

private fun csQualityLabel(q: Int): String? = when {
    q >= 2160 -> "4K"; q >= 1440 -> "1440p"; q >= 1080 -> "1080p"
    q >= 720 -> "720p"; q >= 480 -> "480p"; q >= 360 -> "360p"
    q > 0 -> "${q}p"; else -> null
}

private fun TmdbMovie.year(): Int? =
    releaseDate?.takeIf { it.isNotBlank() }?.substringBefore('-')?.toIntOrNull()

private suspend fun speedProbeAndReorder(sources: List<PlayerSource>): List<PlayerSource> {
    if (sources.size <= 1) return sources
    val probeClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).followRedirects(true).build()
    data class ProbeResult(val source: PlayerSource, val latencyMs: Long, val alive: Boolean)
    val results: List<ProbeResult> = kotlinx.coroutines.coroutineScope {
        sources.map { src ->
            async(Dispatchers.IO) {
                if (src.isMagnet || src.url.isBlank()) return@async ProbeResult(src, Long.MAX_VALUE, false)
                val start = System.currentTimeMillis()
                val ok = runCatching {
                    val req = Request.Builder().url(src.url).header("Range", "bytes=0-0")
                        .apply { src.headers.forEach { (k, v) -> header(k, v) } }.build()
                    probeClient.newCall(req).execute().use { resp -> resp.code in 200..299 || resp.code == 206 || resp.code == 302 }
                }.getOrElse { false }
                val latency = System.currentTimeMillis() - start
                Log.d("StreamCloud", "Speed probe ${src.addonName}/${src.qualityTag}: alive=$ok latency=${latency}ms")
                ProbeResult(src, if (ok) latency else Long.MAX_VALUE, ok)
            }
        }.awaitAll()
    }
    fun effectiveScore(r: ProbeResult): Long {
        if (!r.alive) return Long.MAX_VALUE
        val bonus = when (r.source.qualityTag) { "4K" -> 800L; "1440p" -> 600L; "1080p" -> 400L; "720p" -> 200L; "480p" -> 100L; else -> 0L }
        return r.latencyMs - bonus
    }
    return results.sortedBy { effectiveScore(it) }.map { it.source }
}
