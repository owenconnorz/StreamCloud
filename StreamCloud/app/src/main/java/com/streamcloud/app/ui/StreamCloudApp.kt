package com.streamcloud.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.player.NativePlayerScreen
import com.streamcloud.app.ui.screens.AdultScreen
import com.streamcloud.app.ui.screens.LibraryScreen
import com.streamcloud.app.ui.screens.MovieDetailScreen
import com.streamcloud.app.ui.screens.MoviesScreen
import com.streamcloud.app.ui.screens.MusicScreen
import com.streamcloud.app.ui.screens.PluginsScreen
import com.streamcloud.app.ui.screens.SettingsHubScreen
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.viewmodel.AdultViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import java.net.URLDecoder
import java.net.URLEncoder

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Movies   : Tab("movies",   "Movies",   Icons.Filled.Theaters)
    data object Music    : Tab("music",    "Music",    Icons.Filled.MusicNote)
    data object Library  : Tab("library",  "Library",  Icons.Filled.Bookmarks)
    data object Adult    : Tab("adult",    "Adult",    Icons.Filled.Whatshot)
    data object Settings : Tab("settings", "Settings", Icons.Filled.Settings)
}

@Composable
fun StreamCloudApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val isMediaRoute = currentRoute != null && (
        currentRoute == Tab.Movies.route ||
        currentRoute.startsWith("cloudstream") ||
        currentRoute.startsWith("cs-detail/") ||
        currentRoute.startsWith("cs-section/") ||
        currentRoute.startsWith("movie/") ||
        currentRoute.startsWith("tv/")
    )

    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val nsfwEnabled by sl.settings.nsfwEnabled.collectAsState(initial = false)
    val navOrderCsv by sl.settings.navTabOrderCsv.collectAsState(initial = null)



    LaunchedEffect(Unit) {
        runCatching { com.streamcloud.app.audio.PlaybackBus.ensureAttached(context) }


        runCatching { com.streamcloud.app.ui.theme.AlbumArtThemeBus.attach(context) }
    }

    val tabs = remember(nsfwEnabled, navOrderCsv) {



        val pool: Map<String, Tab> = buildMap {
            put(Tab.Movies.route, Tab.Movies)
            put(Tab.Music.route, Tab.Music)
            put(Tab.Library.route, Tab.Library)
            if (nsfwEnabled) put(Tab.Adult.route, Tab.Adult)
        }



        val requestedOrder = navOrderCsv
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { pool[it.trim()] }
            ?.distinct()
            .orEmpty()
        val seen = requestedOrder.map { it.route }.toSet()
        val middle = requestedOrder + pool.values.filter { it.route !in seen }


        middle + Tab.Settings
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        bottomBar = {
            val showBar = currentRoute == null || tabs.any { it.route == currentRoute }


            val useRail = LocalUiFormFactor.current != UiFormFactor.Mobile
            if (showBar && !useRail) {
                Column {




                    if (currentRoute != Tab.Music.route && !isMediaRoute) {
                        com.streamcloud.app.ui.player.GlobalMiniPlayer(
                            onExpand = {



                                com.streamcloud.app.ui.player.PlayerExpandBus.requestExpand()
                            },
                        )
                    }





                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            tabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                ScrollableNavBarItem(
                                    icon = tab.icon,
                                    label = tab.label,
                                    selected = selected,
                                    onClick = { navigateToTab(nav, tab.route) },
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val useRail = LocalUiFormFactor.current != UiFormFactor.Mobile
        val showRail = useRail &&
            (currentRoute == null || tabs.any { it.route == currentRoute })
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (showRail) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    Spacer(Modifier.height(16.dp))
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateToTab(nav, tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelLarge) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        NavHost(
                navController = nav,
                startDestination = Tab.Movies.route,
            ) {
                composable(Tab.Movies.route) {
                    MoviesScreen(
                        onMovieClick = { id -> nav.navigate("movie/$id") },
                        onTvClick = { id -> nav.navigate("tv/$id") },
                        onOpenCloudStreamPlugin = { internalName ->
                            val n = URLEncoder.encode(internalName, "UTF-8")
                            nav.navigate("cloudstream/$n")
                        },
                        onProfileClick = { navigateToTab(nav, Tab.Settings.route) },
                        onOpenCatalog = { src, t, sub ->
                            val s = URLEncoder.encode(src, "UTF-8")
                            val tt = URLEncoder.encode(t, "UTF-8")
                            val ss = URLEncoder.encode(sub.ifBlank { " " }, "UTF-8")
                            nav.navigate("catalog/$s/$tt/$ss")
                        },
                        onOpenStremio = { addonId, type, metaId, ttl, poster ->
                            val a = URLEncoder.encode(addonId, "UTF-8")
                            val ty = URLEncoder.encode(type, "UTF-8")
                            val m = URLEncoder.encode(metaId, "UTF-8")
                            val tt = URLEncoder.encode(ttl, "UTF-8")
                            val p = URLEncoder.encode(poster.orEmpty().ifBlank { " " }, "UTF-8")
                            nav.navigate("stremio-detail/$a/$ty/$m/$tt/$p")
                        },
                        onOpenCsItem = { plugin, itemUrl, itemName, poster ->
                            val p = URLEncoder.encode(plugin, "UTF-8")
                            val u = URLEncoder.encode(itemUrl, "UTF-8")
                            val n = URLEncoder.encode(itemName, "UTF-8")
                            val po = URLEncoder.encode(poster.orEmpty().ifBlank { " " }, "UTF-8")
                            nav.navigate("cs-detail/$p/$u/$n/$po")
                        },
                        onViewAllCsSection = { plugin, section, displayName ->
                            val p = URLEncoder.encode(plugin, "UTF-8")
                            val s = URLEncoder.encode(section, "UTF-8")
                            val d = URLEncoder.encode(displayName, "UTF-8")
                            nav.navigate("cs-section/$p/$s/$d")
                        },
                    )
                }
                composable(
                    "catalog/{src}/{title}/{subtitle}",
                    arguments = listOf(
                        navArgument("src") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                        navArgument("subtitle") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val src = URLDecoder.decode(entry.arguments!!.getString("src")!!, "UTF-8")
                    val t = URLDecoder.decode(entry.arguments!!.getString("title")!!, "UTF-8")
                    val sub = URLDecoder.decode(entry.arguments!!.getString("subtitle")!!, "UTF-8")
                    com.streamcloud.app.ui.screens.CatalogPageScreen(
                        source = src,
                        title = t,
                        subtitle = sub.trim(),
                        onBack = { nav.popBackStack() },
                        onMovieClick = { id -> nav.navigate("movie/$id") },
                        onOpenStremio = { addonId, type, metaId, ttl, poster ->
                            val a = URLEncoder.encode(addonId, "UTF-8")
                            val ty = URLEncoder.encode(type, "UTF-8")
                            val m = URLEncoder.encode(metaId, "UTF-8")
                            val tt = URLEncoder.encode(ttl, "UTF-8")
                            val p = URLEncoder.encode(poster.orEmpty().ifBlank { " " }, "UTF-8")
                            nav.navigate("stremio-detail/$a/$ty/$m/$tt/$p")
                        },
                    )
                }
                composable(
                    "cloudstream/{name}",
                    arguments = listOf(navArgument("name") { type = NavType.StringType }),
                ) { entry ->
                    val name = URLDecoder.decode(entry.arguments!!.getString("name")!!, "UTF-8")
                    com.streamcloud.app.ui.screens.CloudStreamPluginScreen(
                        internalName = name,
                        onBack = { nav.popBackStack() },
                        onOpenItem = { plugin, itemUrl, itemName, poster ->
                            val p = URLEncoder.encode(plugin, "UTF-8")
                            val u = URLEncoder.encode(itemUrl, "UTF-8")
                            val n = URLEncoder.encode(itemName, "UTF-8")
                            val po = URLEncoder.encode(poster.orEmpty().ifBlank { " " }, "UTF-8")
                            nav.navigate("cs-detail/$p/$u/$n/$po")
                        },
                    )
                }
                composable(
                    "cloudstream-movie/{name}/{title}",
                    arguments = listOf(
                        navArgument("name")  { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val name  = URLDecoder.decode(entry.arguments!!.getString("name")!!,  "UTF-8")
                    val title = URLDecoder.decode(entry.arguments!!.getString("title")!!, "UTF-8")
                    com.streamcloud.app.ui.screens.CloudStreamPluginScreen(
                        internalName = name,
                        initialSearch = title,
                        onBack = { nav.popBackStack() },
                        onOpenItem = { plugin, itemUrl, itemName, poster ->
                            val p  = URLEncoder.encode(plugin,  "UTF-8")
                            val u  = URLEncoder.encode(itemUrl, "UTF-8")
                            val n  = URLEncoder.encode(itemName, "UTF-8")
                            val po = URLEncoder.encode(poster.orEmpty().ifBlank { " " }, "UTF-8")
                            nav.navigate("cs-detail/$p/$u/$n/$po")
                        },
                    )
                }
                composable(
                    "cs-section/{plugin}/{section}/{display}",
                    arguments = listOf(
                        navArgument("plugin")  { type = NavType.StringType },
                        navArgument("section") { type = NavType.StringType },
                        navArgument("display") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val plugin  = URLDecoder.decode(entry.arguments!!.getString("plugin")!!,  "UTF-8")
                    val section = URLDecoder.decode(entry.arguments!!.getString("section")!!, "UTF-8")
                    val display = URLDecoder.decode(entry.arguments!!.getString("display")!!, "UTF-8")
                    com.streamcloud.app.ui.screens.CsSectionListScreen(
                        pluginInternalName = plugin,
                        sectionName = section,
                        pluginDisplayName = display,
                        onBack = { nav.popBackStack() },
                        onOpenItem = { p, u, n, po ->
                            val ep = URLEncoder.encode(p, "UTF-8")
                            val eu = URLEncoder.encode(u, "UTF-8")
                            val en = URLEncoder.encode(n, "UTF-8")
                            val epo = URLEncoder.encode(po.orEmpty().ifBlank { " " }, "UTF-8")
                            nav.navigate("cs-detail/$ep/$eu/$en/$epo")
                        },
                    )
                }
                composable(
                    "cs-detail/{plugin}/{url}/{name}/{poster}",
                    arguments = listOf(
                        navArgument("plugin") { type = NavType.StringType },
                        navArgument("url") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType },
                        navArgument("poster") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val plugin = URLDecoder.decode(entry.arguments!!.getString("plugin")!!, "UTF-8")
                    val itemUrl = URLDecoder.decode(entry.arguments!!.getString("url")!!, "UTF-8")
                    val itemName = URLDecoder.decode(entry.arguments!!.getString("name")!!, "UTF-8")
                    val poster = URLDecoder.decode(entry.arguments!!.getString("poster")!!, "UTF-8").trim()
                    com.streamcloud.app.ui.screens.CloudStreamDetailScreen(
                        pluginInternalName = plugin,
                        url = itemUrl,
                        initialTitle = itemName,
                        initialPoster = poster.takeIf { it.isNotBlank() },
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
                    )
                }
                composable(
                    "movie/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) {
                    MovieDetailScreen(
                        movieId = it.arguments!!.getLong("id"),
                        mediaType = "movie",
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
                        onArtistClick = { url ->
                            val u = URLEncoder.encode(url, "UTF-8")
                            nav.navigate("artist/$u")
                        },
                        onOpenPlaylist = { id, title ->
                            val i = URLEncoder.encode(id, "UTF-8")
                            val t = URLEncoder.encode(title, "UTF-8")
                            nav.navigate("yt-playlist/$i/$t")
                        },




                        onProfileClick = { navigateToTab(nav, Tab.Settings.route) },
                    )
                }
                composable(
                    "artist/{url}",
                    arguments = listOf(navArgument("url") { type = NavType.StringType }),
                ) { entry ->
                    val url = URLDecoder.decode(entry.arguments!!.getString("url")!!, "UTF-8")
                    com.streamcloud.app.ui.screens.MusicArtistScreen(
                        channelUrl = url,
                        onBack = { nav.popBackStack() },
                        onPlay = {  },
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
                    "yt-playlist/{id}/{title}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val id = URLDecoder.decode(entry.arguments!!.getString("id")!!, "UTF-8")
                    val title = URLDecoder.decode(entry.arguments!!.getString("title")!!, "UTF-8")
                    com.streamcloud.app.ui.screens.YtPlaylistScreen(
                        playlistId = id,
                        title = title,
                        onBack = { nav.popBackStack() },
                        onPlay = {  },
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
                    val ctx = LocalContext.current
                    val vm: AdultViewModel = viewModel(factory = AdultViewModel.factory(ctx))
                    var resolved by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(id) { resolved = vm.resolveStreamUrl(id, embed) }
                    if (resolved != null) {
                        NativePlayerScreen(
                            streamUrl = resolved!!,
                            title = title,
                            headers = mapOf("Referer" to "https://www.eporner.com/"),
                            onBack = { nav.popBackStack() },
                        )
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
                    SettingsHubScreen(onOpenPlugins = { nav.navigate("plugins") })
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
}

private fun navigateToTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun ScrollableNavBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val indicator = MaterialTheme.colorScheme.primary
    val onIndicator = MaterialTheme.colorScheme.onPrimary
    val unselectedTint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .height(32.dp)
                .width(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) indicator else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) onIndicator else unselectedTint,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else unselectedTint,
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
