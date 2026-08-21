                        onMovieClick = { id -> nav.navigate("movie/$id") },
                        onTvClick = { id -> nav.navigate("tv/$id") },
                    )
                }
                composable(
                    "tv/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) {
                    MovieDetailScreen(
                        movieId = it.arguments!!.getLong("id"),
                        mediaType = "tv",
                        onBack = { nav.popBackStack() },
                        onPlay = { initialUrl, title, sources, progressKey ->
                            com.streamcloud.app.player.MoviePlayerSession.set(
                                sources, progressKey,
                                tmdbId = progressKey.tmdbId,
                                mediaType = progressKey.mediaType,
                            )
                            val u = URLEncoder.encode(initialUrl, "UTF-8")
                            val t = URLEncoder.encode(title, "UTF-8")
                            nav.navigate("player/movie/$u/$t")
                        },
                        onOpenCsPluginForMovie = { name, title ->
                            val n = URLEncoder.encode(name,  "UTF-8")
                            val t = URLEncoder.encode(title, "UTF-8")
                            nav.navigate("cloudstream-movie/$n/$t")
                        },
                        onMovieClick = { id -> nav.navigate("movie/$id") },
                        onTvClick = { id -> nav.navigate("tv/$id") },
                    )
                }
                composable(
                    "stremio-detail/{addonId}/{type}/{metaId}/{title}/{poster}",
                    arguments = listOf(
                        navArgument("addonId") { type = NavType.StringType },
                        navArgument("type") { type = NavType.StringType },
                        navArgument("metaId") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                        navArgument("poster") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val a = URLDecoder.decode(entry.arguments!!.getString("addonId")!!, "UTF-8")
                    val t = URLDecoder.decode(entry.arguments!!.getString("type")!!, "UTF-8")
                    val m = URLDecoder.decode(entry.arguments!!.getString("metaId")!!, "UTF-8")
                    val tt = URLDecoder.decode(entry.arguments!!.getString("title")!!, "UTF-8")
                    val pp = URLDecoder.decode(entry.arguments!!.getString("poster")!!, "UTF-8").trim()
                    com.streamcloud.app.ui.screens.StremioDetailScreen(
                        addonId = a,
                        type = t,
                        metaId = m,
                        initialTitle = tt,
                        initialPoster = pp.takeIf { it.isNotBlank() },
                        onBack = { nav.popBackStack() },
                        onPlay = { url, title ->




                            val u = URLEncoder.encode("direct://$url", "UTF-8")
                            val tArg = URLEncoder.encode(title, "UTF-8")





                            nav.navigate("player/eporner/$u/x/$tArg")
                        },
                    )
                }
                composable(Tab.Music.route)    {
                    MusicScreen(
                        onArtistClick = { url, thumb ->
                            val u = URLEncoder.encode(url, "UTF-8")
                            val t = URLEncoder.encode(thumb.orEmpty(), "UTF-8")
                            nav.navigate("artist/$u?thumb=$t")
                        },
                        onOpenPlaylist = { id, title ->
                            val i = URLEncoder.encode(id, "UTF-8")
                            val t = URLEncoder.encode(title, "UTF-8")
                            nav.navigate("yt-playlist/$i/$t")
                        },
                        onSearchClick = { nav.navigate("music-search") },
                        onSearchWithQuery = { q -> nav.navigate("music-search?q=${java.net.URLEncoder.encode(q, "UTF-8")}") },
                        onProfileClick = { navigateToTab(nav, Tab.Settings.route) },
                    )
                }
                composable("movie-search") {
                    MovieSearchScreen(
                        onBack = { nav.popBackStack() },
                        onMovieClick = { id -> nav.navigate("movie/$id") },
                        onTvClick = { id -> nav.navigate("tv/$id") },
                        onOpenCsItem = { plugin, itemUrl, itemName, poster ->
                            val p = URLEncoder.encode(plugin, "UTF-8")
                            val u = URLEncoder.encode(itemUrl, "UTF-8")
                            val n = URLEncoder.encode(itemName, "UTF-8")
                            val po = URLEncoder.encode(poster ?: "", "UTF-8")
                            nav.navigate("cs-detail/$p/$u/$n/$po")
                        },
                        onOpenStremio = { addonId, type, metaId, title, poster ->
                            val a = URLEncoder.encode(addonId, "UTF-8")
                            val ty = URLEncoder.encode(type, "UTF-8")
                            val m = URLEncoder.encode(metaId, "UTF-8")
                            val tt = URLEncoder.encode(title, "UTF-8")
                            val p = URLEncoder.encode(poster ?: "", "UTF-8")
                            nav.navigate("stremio-detail/$a/$ty/$m/$tt/$p")
                        },
                    )
                }
                composable("plugin-picker") {
                    PluginPickerScreen(
                        onBack = { nav.popBackStack() },
                        onOpenPlugin = { internalName ->
                            val n = URLEncoder.encode(internalName, "UTF-8")
                            nav.navigate("cloudstream/$n")
                        },
                    )
                }
                composable(
                    "music-search?q={q}",
                    arguments = listOf(navArgument("q") { defaultValue = "" }),
                ) { entry ->
                    MusicSearchScreen(
                        initialQuery = entry.arguments?.getString("q") ?: "",
                        onBack = { nav.popBackStack() },
                        onArtistClick = { url, thumb ->
                            val u = URLEncoder.encode(url, "UTF-8")
                            val t = URLEncoder.encode(thumb.orEmpty(), "UTF-8")
                            nav.navigate("artist/$u?thumb=$t")
                        },
                        onOpenPlaylist = { id, title ->
                            val i = URLEncoder.encode(id, "UTF-8")
                            val t = URLEncoder.encode(title, "UTF-8")
                            nav.navigate("yt-playlist/$i/$t")
                        },
                    )
                }
                composable(
                    "artist/{url}?thumb={thumb}",
                    arguments = listOf(
                        navArgument("url") { type = NavType.StringType },
                        navArgument("thumb") {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                    ),
                ) { entry ->
                    val url = URLDecoder.decode(entry.arguments!!.getString("url")!!, "UTF-8")
                    val thumb = entry.arguments!!.getString("thumb")
                        ?.let { URLDecoder.decode(it, "UTF-8") }
                        ?.takeIf { it.isNotBlank() }
                    val artistContext = LocalContext.current
                    val artistVm: com.streamcloud.app.ui.viewmodel.MusicViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel(
                            factory = com.streamcloud.app.ui.viewmodel.MusicViewModel.factory(artistContext)
                        )
                    com.streamcloud.app.ui.screens.MusicArtistScreen(
                        channelUrl = url,
                        initialAvatar = thumb,
                        onBack = { nav.popBackStack() },
                        onPlay = { tracks, startIndex -> artistVm.play(tracks, startIndex) },
                        onAlbumClick = { id, title, thumb ->
                            val i = URLEncoder.encode(id, "UTF-8")
                            val t = URLEncoder.encode(title, "UTF-8")
                            val th = URLEncoder.encode(thumb.orEmpty(), "UTF-8")
                            nav.navigate("yt-playlist/$i/$t?thumb=$th")
                        },
                        onArtistClick = { artistUrl, artistThumb ->
                            val u = URLEncoder.encode(artistUrl, "UTF-8")
                            val t = URLEncoder.encode(artistThumb.orEmpty(), "UTF-8")
                            nav.navigate("artist/$u?thumb=$t")
                        },
                    )
                }
                composable(Tab.Library.route)  {
                    LibraryScreen(
                        onOpenPlaylist = { id, title ->
                            val i = URLEncoder.encode(id, "UTF-8")
                            val t = URLEncoder.encode(title, "UTF-8")
                            nav.navigate("yt-playlist/$i/$t")
                        },
                        onOpenArtist = { url ->
                            val u = URLEncoder.encode(url, "UTF-8")
                            nav.navigate("artist/$u")
                        },
                        onProfileClick = { navigateToTab(nav, Tab.Settings.route) },
                        onMovieClick = { id -> nav.navigate("movie/$id") },
                        onPlayLocalFile = { filePath, title, tmdbId, mediaType ->
                            val videoUri = if (filePath.startsWith("content://")) filePath else "file://$filePath"
                            com.streamcloud.app.player.MoviePlayerSession.set(
                                listOf(
                                    com.streamcloud.app.player.PlayerSource(
                                        id = "local::$tmdbId",
                                        url = videoUri,
                                        label = title,
                                        addonName = "Downloaded",
                                    )
                                ),
                                progressKey = com.streamcloud.app.player.WatchProgressKey(
                                    tmdbId = tmdbId,
                                    title = title,
                                    posterUrl = null,
                                    mediaType = mediaType,
                                ),
                                tmdbId = tmdbId,
                                mediaType = mediaType,
                            )
                            val u = java.net.URLEncoder.encode(videoUri, "UTF-8")
                            val t = java.net.URLEncoder.encode(title, "UTF-8")
                            nav.navigate("player/movie/$u/$t")
                        },
                        onTvClick = { id -> nav.navigate("tv/$id") },
                        onCsClick = { plugin, itemUrl, itemName, poster ->
                            val p  = URLEncoder.encode(plugin,  "UTF-8")
                            val u  = URLEncoder.encode(itemUrl, "UTF-8")
                            val n  = URLEncoder.encode(itemName, "UTF-8")
                            val po = URLEncoder.encode(poster.orEmpty().ifBlank { " " }, "UTF-8")
                            nav.navigate("cs-detail/$p/$u/$n/$po")
                        },
                    )
                }
                composable(
                    "yt-playlist/{id}/{title}?thumb={thumb}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                        navArgument("thumb") {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        },
                    ),
                ) { entry ->
                    val id = URLDecoder.decode(entry.arguments!!.getString("id")!!, "UTF-8")
                    val title = URLDecoder.decode(entry.arguments!!.getString("title")!!, "UTF-8")
                    val thumb = entry.arguments!!.getString("thumb")
                        ?.let { URLDecoder.decode(it, "UTF-8") }
                        ?.takeIf { it.isNotBlank() }
                    com.streamcloud.app.ui.screens.YtPlaylistScreen(
                        playlistId = id,
                        title = title,
                        initialThumb = thumb,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Tab.Adult.route) {
                    AdultScreen(onPlay = { videoId, embed, title ->
                        val v = URLEncoder.encode(videoId, "UTF-8")
                        val e = URLEncoder.encode(embed, "UTF-8")
                        val t = URLEncoder.encode(title, "UTF-8")
                        nav.navigate("player/eporner/$v/$e/$t")
                    })
                }



                composable(
                    "player/eporner/{id}/{embed}/{title}",
                    arguments = listOf(
                        navArgument("id")    { type = NavType.StringType },
                        navArgument("embed") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                    )
                ) { entry ->
                    val id    = URLDecoder.decode(entry.arguments!!.getString("id")!!,    "UTF-8")
                    val embed = URLDecoder.decode(entry.arguments!!.getString("embed")!!, "UTF-8")
                    val title = URLDecoder.decode(entry.arguments!!.getString("title")!!, "UTF-8")
                    var resolved by remember(id, embed) {
                        mutableStateOf<com.streamcloud.app.data.api.EpornerResolvedPlayback?>(null)
                    }
                    var resolveError by remember(id, embed) { mutableStateOf<String?>(null) }
                    var resolveAttempt by remember(id, embed) { mutableStateOf(0) }
                    LaunchedEffect(id, embed, resolveAttempt) {
                        resolved = null
                        resolveError = null
                        runCatching { com.streamcloud.app.data.api.EpornerPlaybackResolver.resolve(id, embed) }
                            .onSuccess { resolved = it }
                            .onFailure {
                                resolveError = it.message
                                    ?.takeIf(String::isNotBlank)
                                    ?: "Eporner could not prepare this video."
                            }
                    }
                    if (resolved != null) {
                        NativePlayerScreen(
                            streamUrl = resolved!!.url,
                            title = title,
                            headers = resolved!!.headers,
                            onBack = { nav.popBackStack() },
                        )
                    } else if (resolveError != null) {
                        Column(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(24.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "This Eporner video could not be prepared.",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                resolveError!!,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(20.dp))
                            androidx.compose.material3.Button(
                                onClick = { resolveAttempt++ },
                            ) { Text("Try again") }
                            androidx.compose.material3.TextButton(
                                onClick = { nav.popBackStack() },
                            ) { Text("Go back") }
                        }
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { androidx.compose.material3.CircularProgressIndicator() }
                    }
                }

                composable(
                    "player/url/{url}/{title}",
                    arguments = listOf(
                        navArgument("url")   { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                    )
                ) { entry ->
                    val url   = URLDecoder.decode(entry.arguments!!.getString("url")!!,   "UTF-8")
                    val title = URLDecoder.decode(entry.arguments!!.getString("title")!!, "UTF-8")
                    NativePlayerScreen(
                        streamUrl = url,
                        title = title,
                        onBack = { nav.popBackStack() },
                    )
                }


                composable(
                    "player/movie/{url}/{title}",
                    arguments = listOf(
                        navArgument("url")   { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                    )
                ) { entry ->
                    val initial = URLDecoder.decode(entry.arguments!!.getString("url")!!, "UTF-8")
                    val title = URLDecoder.decode(entry.arguments!!.getString("title")!!, "UTF-8")


                    val sources by com.streamcloud.app.player.MoviePlayerSession.sourcesFlow.collectAsState()
                    val nuvioScanning by com.streamcloud.app.player.MoviePlayerSession.nuvioScanningFlow.collectAsState()
                    val sourceErrors by com.streamcloud.app.player.MoviePlayerSession.sourceErrorsFlow.collectAsState()
                    val addonSubtitles by com.streamcloud.app.player.MoviePlayerSession.addonSubtitlesFlow.collectAsState()
                    val bingeEpisodes by com.streamcloud.app.player.MoviePlayerSession.bingeEpisodesFlow.collectAsState()
                    var currentUrl by remember(initial) { mutableStateOf(initial) }
                    var currentId by remember(initial) {
                        mutableStateOf(sources.firstOrNull { it.url == initial }?.id)
                    }
                    val active = sources.firstOrNull { it.id == currentId }
                    val subtitle = active?.let { "${it.addonName}${it.qualityTag?.let { q -> " · $q" } ?: ""}" }
                    val refreshScope = rememberCoroutineScope()


                    var switchKey by remember(initial) { mutableStateOf(0) }
                    NativePlayerScreen(
                        streamUrl = currentUrl,
                        title = title,
                        subtitle = subtitle,
                        headers = active?.headers ?: emptyMap(),
                        sources = sources,
                        selectedSourceId = currentId,
                        restartKey = switchKey,
                        nuvioScanning = nuvioScanning,
                        forceDirectPlay = true,
                        artworkUrl = com.streamcloud.app.player.MoviePlayerSession.progressKey?.posterUrl,
                        onSwitchSource = { src ->
                            currentUrl = src.url
                            currentId = src.id
                            switchKey++
                        },
                        progressKey = com.streamcloud.app.player.MoviePlayerSession.progressKey,
                        onBack = { nav.popBackStack() },
                        // ── Series / binge ────────────────────────────────────
                        seasonNumber      = com.streamcloud.app.player.MoviePlayerSession.seasonNumber,
                        episodeNumber     = com.streamcloud.app.player.MoviePlayerSession.episodeNumber,
                        episodeTitle      = com.streamcloud.app.player.MoviePlayerSession.episodeTitle,
                        bingeEpisodes     = bingeEpisodes,
                        currentBingeIndex = com.streamcloud.app.player.MoviePlayerSession.currentBingeIndex,
                        onPlayBingeEpisode = { ep ->
                            val pk = ep.progressKey ?: com.streamcloud.app.player.WatchProgressKey(
                                tmdbId = ep.tmdbId, title = ep.title,
                                posterUrl = ep.posterUrl, mediaType = "tv",
                            )
                            val newIdx = bingeEpisodes.indexOf(ep)
                            com.streamcloud.app.player.MoviePlayerSession.set(
                                newSources        = emptyList(),
                                progressKey       = pk,
                                tmdbId            = ep.tmdbId,
                                mediaType         = "tv",
                                seasonNumber      = ep.seasonNumber,
                                episodeNumber     = ep.episodeNumber,
                                episodeTitle      = ep.episodeTitle,
                                bingeEpisodes     = bingeEpisodes,
                                currentBingeIndex = newIdx,
                            )
                            nav.navigate("player/${URLEncoder.encode("about:blank", "UTF-8")}/${URLEncoder.encode(ep.title, "UTF-8")}")
                        },
                        // ── Addon subtitles ───────────────────────────────────
                        addonSubtitles = addonSubtitles,
                        // ── Per-provider errors ───────────────────────────────
                        sourceErrors   = sourceErrors,
                        onRefresh = {
                            refreshScope.launch {
                                val tmdbId = com.streamcloud.app.player.MoviePlayerSession.tmdbId
                                val mediaType = com.streamcloud.app.player.MoviePlayerSession.mediaType
                                if (tmdbId == 0L) return@launch
                                com.streamcloud.app.player.MoviePlayerSession.setNuvioScanning(true)
                                val newSources = runCatching {
                                    sl.nuvio.resolveAll(tmdbId.toString(), mediaType)
                                        .map { (provider, stream) ->
                                            val label = stream.title?.takeIf { it.isNotBlank() }
                                                ?: stream.name?.takeIf { it.isNotBlank() }
                                                ?: "Stream"
                                            com.streamcloud.app.player.PlayerSource(
                                                id = "nuvio::${provider.id}::${stream.url.hashCode()}::${label.hashCode()}",
                                                url = stream.url,
                                                label = label,
                                                addonName = provider.name,
                                                qualityTag = nuvioQualityTag(stream.quality),
                                                isMagnet = stream.url.startsWith("magnet:"),
                                                headers = stream.headers ?: emptyMap(),
                                            )
                                        }
                                }.getOrDefault(emptyList())
                                com.streamcloud.app.player.MoviePlayerSession.setNuvioScanning(false)
                                com.streamcloud.app.player.MoviePlayerSession.mergeSources(newSources)
                            }
                        },
                    )
                }
                composable(Tab.Settings.route) {
                    SettingsHubScreen(
                        onOpenPlugins     = { nav.navigate("plugins") },
                        onOpenCollections = { nav.navigate("collections") },
                        onSwitchProfile   = { showProfilePicker = true },
                    )
                }
                composable("plugins") {
                    PluginsScreen(onBack = { nav.popBackStack() })
                }
            }
                    }



                    if (showRail && currentRoute != Tab.Music.route && !isMediaRoute) {
                        com.streamcloud.app.ui.player.GlobalMiniPlayer(
                            onExpand = {
                                com.streamcloud.app.ui.player.PlayerExpandBus.requestExpand()
                            },
                        )
                    }
                }

                // TV Nuvio-style popup navigation
                if (isTv && showRail) {
                    // Semi-transparent scrim that closes the nav on click
                    if (tvNavOpen) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { tvNavOpen = false },
                        )
                    }
                    // Hamburger button — always visible at top-left
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(TvOverscanPadding),
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .tvFocusBorder(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                .focusRequester(firstRailFocus)
                                .clickable { tvNavOpen = !tvNavOpen },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = "Navigation",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    // Animated slide-in nav panel
                    androidx.compose.animation.AnimatedVisibility(
                        visible = tvNavOpen,
                        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxHeight(),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp,
                            shadowElevation = 12.dp,
                            shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(240.dp),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .padding(vertical = TvOverscanPadding, horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "StreamCloud",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                )
                                // Search shortcut
                                TvNavRow(
                                    icon = Icons.Default.Search,
                                    label = "Search",
                                    selected = currentRoute == "movie-search",
                                    onClick = {
                                        tvNavOpen = false
                                        nav.navigate("movie-search")
                                    },
                                )
                                tabs.forEach { tab ->
                                    TvNavRow(
                                        icon = tab.icon,
                                        label = tab.label,
                                        selected = currentRoute == tab.route,
                                        onClick = {
                                            tvNavOpen = false
                                            navigateToTab(nav, tab.route)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Nuvio-style flat bottom nav bar
                if (!useRail && (showMiniPlayer || currentRoute == null || tabs.any { it.route == currentRoute })) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    ) {
                        if (showMiniPlayer) {
                            com.streamcloud.app.ui.player.GlobalMiniPlayer(
                                onExpand = {
                                    com.streamcloud.app.ui.player.PlayerExpandBus.requestExpand()
                                },
                            )
                        }
                        val showBar = currentRoute == null ||
                            (tabs.any { it.route == currentRoute } && currentRoute != Tab.Settings.route)
                        if (showBar) {
                            val effectiveShowLabel = navExpanded && showNavLabels
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(start = 16.dp, end = 16.dp, bottom = navOuterBottomPad),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (navLiquidGlass) {
                                    // Nuvio-faithful glass pill: real hazeEffect blur behind,
                                    // no border, no shine/shadow overlays, accent-pill selected
                                    // indicator, dynamic width on label collapse.
                                    val pillHPad by animateDpAsState(
                                        targetValue = if (effectiveShowLabel) 0.dp else 32.dp,
                                        animationSpec = tween(300),
                                        label = "pillHPad",
                                    )
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = pillHPad)
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(50))
                                            .hazeEffect(state = hazeState) { blurRadius = 50.dp }
                                            .background(Color(0xFF0D0D0D).copy(alpha = 0.78f)),
                                    ) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp, vertical = navPillVPad),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            tabs.forEach { tab ->
                                                val selected = currentRoute == tab.route
                                                if (tab.route == Tab.Settings.route) {
                                                    ProfileNavItem(
                                                        selected = selected,
                                                        showLabel = effectiveShowLabel,
                                                        onClick = { navigateToTab(nav, tab.route) },
                                                    )
                                                } else {
                                                    NuvioNavItem(
                                                        icon = tab.icon,
                                                        label = tab.label,
                                                        selected = selected,
                                                        showLabel = effectiveShowLabel,
                                                        onClick = { navigateToTab(nav, tab.route) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = navPillColor,
                                        shadowElevation = 10.dp,
                                        tonalElevation = 4.dp,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp, vertical = navPillVPad),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            tabs.forEach { tab ->
                                                val selected = currentRoute == tab.route
                                                if (tab.route == Tab.Settings.route) {
                                                    ProfileNavItem(
                                                        selected = selected,
                                                        showLabel = effectiveShowLabel,
                                                        onClick = { navigateToTab(nav, tab.route) },
                                                    )
                                                } else {
                                                    NuvioNavItem(
                                                        icon = tab.icon,
                                                        label = tab.label,
                                                        selected = selected,
                                                        showLabel = effectiveShowLabel,
                                                        onClick = { navigateToTab(nav, tab.route) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }


        com.streamcloud.app.ui.player.GlobalNowPlayingSheet(
            onOpenSettings = { navigateToTab(nav, Tab.Settings.route) },
            onOpenArtistSearch = { artistName ->



                val q = java.net.URLEncoder.encode(artistName, "UTF-8")
                val searchUrl = "https://www.youtube.com/results?search_query=$q"
                val encoded = URLEncoder.encode(searchUrl, "UTF-8")
                nav.navigate("artist/$encoded")
            },
        )
    }

    // Profile picker overlay — floats above everything
    if (showProfilePicker) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A)),
        ) {
            ProfilePickerScreen(
                repo   = sl.profiles,
                onDone = { showProfilePicker = false },
            )
        }
    }
    } // end outer Box
}

@Composable
private fun TvNavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
             else Color.Transparent
    val tint = if (selected) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .tvFocusBorder(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}

private fun navigateToTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun NuvioNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    showLabel: Boolean = true,
    glassActive: Boolean = false,
    onClick: () -> Unit,
) {
    val iconTint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8E8E93),
        label = "navIconTint",
    )
    val selectedBg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
        label = "navItemBg",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(selectedBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(28.dp),
        )
        if (showLabel) {
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = iconTint,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun ProfileNavItem(
    selected: Boolean,
    showLabel: Boolean,
    glassActive: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val sl = remember(context) { ServiceLocator.get(context) }
    val ytAvatar by sl.settings.ytMusicUserAvatar.collectAsState(initial = "")
    val ytCookie by sl.settings.ytMusicCookie.collectAsState(initial = "")

    // When ytMusicUserAvatar is blank (JS scraping during login didn't capture it),
    // try the YouTube Music account/account_menu API with the stored cookie —
    // this is the primary Metrolist approach.  Fall back to the device Google
    // account via AccountManager as a last resort.
    var deviceAvatar by remember { mutableStateOf("") }
    LaunchedEffect(ytAvatar, ytCookie) {
        if (ytAvatar.isNotBlank() || deviceAvatar.isNotBlank()) return@LaunchedEffect
        val photoUrl = when {
            ytCookie.isNotBlank() ->
                GoogleAccountHelper.fetchFromYtMusicApi(ytCookie)
                    ?: GoogleAccountHelper.getPhotoUrl(context)
            else ->
                GoogleAccountHelper.getPhotoUrl(context)
        }
        if (!photoUrl.isNullOrBlank()) {
            if (ytCookie.isNotBlank()) {
                // Persist so subsequent launches show the photo instantly
                sl.settings.setYtMusicUserAvatar(photoUrl)
                // ytAvatar will update via DataStore flow — no need to set deviceAvatar
            } else {
                deviceAvatar = photoUrl
            }
        }
    }
    val avatar = if (ytAvatar.isNotBlank()) ytAvatar else deviceAvatar

    val iconTint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8E8E93),
        label = "profileNavIconTint",
    )
    val selectedBg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
        label = "profileNavItemBg",
    )
    val itemLabel = if (avatar.isNotBlank()) "Profile" else "Settings"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(selectedBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        ProfileAvatarCircle(avatar = avatar, size = 28.dp, tint = iconTint)
        if (showLabel) {
            Spacer(Modifier.height(3.dp))
            Text(
                itemLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = iconTint,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun ProfileAvatarCircle(avatar: String, size: androidx.compose.ui.unit.Dp, tint: Color) {
    val context = LocalContext.current
    if (avatar.isNotBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(avatar)
                .crossfade(true)
                .allowHardware(false)
                .build(),
            contentDescription = "Profile",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Icon(
            Icons.Filled.Settings,
            contentDescription = "Settings",
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

private fun nuvioQualityTag(q: String?): String? {
    if (q.isNullOrBlank()) return null
    val s = q.trim()
    return when {
        s.equals("4K", ignoreCase = true) || s.contains("2160") || s.contains("uhd", ignoreCase = true) -> "4K"
        s.contains("1440") || s.equals("2K", ignoreCase = true) -> "1440p"
        s.contains("1080") || s.equals("fhd", ignoreCase = true) || s.equals("fullhd", ignoreCase = true) -> "1080p"
        s.contains("720")  || s.equals("hd",  ignoreCase = true) -> "720p"
        s.contains("480")  || s.equals("sd",  ignoreCase = true) -> "480p"
        s.contains("360") -> "360p"
        else -> s
    }
}
