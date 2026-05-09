package com.aioweb.app.ui

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
import com.aioweb.app.data.ServiceLocator
import com.aioweb.app.player.NativePlayerScreen
import com.aioweb.app.ui.screens.AdultScreen
import com.aioweb.app.ui.screens.AiScreen
import com.aioweb.app.ui.screens.LibraryScreen
import com.aioweb.app.ui.screens.MovieDetailScreen
import com.aioweb.app.ui.screens.MoviesScreen
import com.aioweb.app.ui.screens.MusicScreen
import com.aioweb.app.ui.screens.PluginsScreen
import com.aioweb.app.ui.screens.SettingsHubScreen
import com.aioweb.app.ui.theme.LocalUiFormFactor
import com.aioweb.app.ui.theme.UiFormFactor
import com.aioweb.app.ui.viewmodel.AdultViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URLDecoder
import java.net.URLEncoder

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Movies   : Tab("movies",   "Movies",   Icons.Filled.Theaters)
    data object Music    : Tab("music",    "Music",    Icons.Filled.MusicNote)
    data object Ai       : Tab("ai",       "AI",       Icons.Filled.AutoAwesome)
    data object Library  : Tab("library",  "Library",  Icons.Filled.Bookmarks)
    data object Adult    : Tab("adult",    "Adult",    Icons.Filled.Whatshot)
    data object Settings : Tab("settings", "Settings", Icons.Filled.Settings)
}

@Composable
fun AioWebApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val nsfwEnabled by sl.settings.nsfwEnabled.collectAsState(initial = false)
    val navOrderCsv by sl.settings.navTabOrderCsv.collectAsState(initial = null)

    // Bind the global "now playing" bus once — every track change in the
    // foreground media service propagates to PlaybackBus.nowPlayingMediaId.
    LaunchedEffect(Unit) {
        runCatching { com.aioweb.app.audio.PlaybackBus.ensureAttached(context) }
        // Hook the album-art-driven accent color so the theme follows the
        // currently playing track's artwork (Metrolist / SimpMusic parity).
        runCatching { com.aioweb.app.ui.theme.AlbumArtThemeBus.attach(context) }
    }

    val tabs = remember(nsfwEnabled, navOrderCsv) {
        // Build the pool of tabs available given the NSFW toggle. Library is
        // ALWAYS present; Adult is additive (only appears when the NSFW toggle
        // is on). The bottom nav is horizontally scrollable so all tabs fit.
        val pool: Map<String, Tab> = buildMap {
            put(Tab.Movies.route, Tab.Movies)
            put(Tab.Music.route, Tab.Music)
            put(Tab.Ai.route, Tab.Ai)
            put(Tab.Library.route, Tab.Library)
            if (nsfwEnabled) put(Tab.Adult.route, Tab.Adult)
        }
        // Apply user-defined ordering, skipping unknown/dropped ids. Anything
        // not listed in the CSV is appended in its natural order so new tabs
        // remain reachable even if the user has never reordered.
        val requestedOrder = navOrderCsv
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { pool[it.trim()] }
            ?.distinct()
            .orEmpty()
        val seen = requestedOrder.map { it.route }.toSet()
        val middle = requestedOrder + pool.values.filter { it.route !in seen }
        // Settings is always pinned at the end so the user can never lose
        // access to this screen via a misconfigured nav order.
        middle + Tab.Settings
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        bottomBar = {
            val showBar = currentRoute == null || tabs.any { it.route == currentRoute }
            // Only show the bottom bar on Mobile. Tablet/TV use a NavigationRail
            // rendered alongside the content (see Row wrapper below).
            val useRail = LocalUiFormFactor.current != UiFormFactor.Mobile
            if (showBar && !useRail) {
                Column {
                    // Global mini-player — appears above the nav bar on every tab
                    // (Music, Library, Movies, AI, Settings) whenever audio is queued
                    // into the MusicPlaybackService. Hidden otherwise via AnimatedVisibility.
                    // Hidden on the Music tab to avoid duplication with its rich mini-player.
                    if (currentRoute != Tab.Music.route) {
                        com.aioweb.app.ui.player.GlobalMiniPlayer(
                            onExpand = {
                                // No tab navigation needed — the global
                                // NowPlayingSheet renders on top of whatever
                                // tab is active. Just emit the expand event.
                                com.aioweb.app.ui.player.PlayerExpandBus.requestExpand()
                            },
                        )
                    }
                    // Custom horizontally-scrollable nav bar — Material 3's
                    // NavigationBar forces equal weight distribution which gets
                    // cramped at 5+ tabs. This bar styles items the same way
                    // (pill indicator, label, icon) but lets the user swipe
                    // sideways when more tabs are enabled (e.g. Adult).
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
                        onOpenCloudStreamPlugin = { internalName ->
                            val n = URLEncoder.encode(internalName, "UTF-8")
                            nav.navigate("cloudstream/$n")
                        },
                    )
                }
                composable(
                    "cloudstream/{name}",
                    arguments = listOf(navArgument("name") { type = NavType.StringType }),
                ) { entry ->
                    val name = URLDecoder.decode(entry.arguments!!.getString("name")!!, "UTF-8")
                    com.aioweb.app.ui.screens.CloudStreamPluginScreen(
                        internalName = name,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "movie/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) {
                    MovieDetailScreen(
                        movieId = it.arguments!!.getLong("id"),
                        onBack = { nav.popBackStack() },
                        onPlay = { initialUrl, title, sources, progressKey ->
                            com.aioweb.app.player.MoviePlayerSession.set(sources, progressKey)
                            val u = URLEncoder.encode(initialUrl, "UTF-8")
                            val t = URLEncoder.encode(title, "UTF-8")
                            nav.navigate("player/movie/$u/$t")
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
                    )
                }
                composable(
                    "artist/{url}",
                    arguments = listOf(navArgument("url") { type = NavType.StringType }),
                ) { entry ->
                    val url = URLDecoder.decode(entry.arguments!!.getString("url")!!, "UTF-8")
                    com.aioweb.app.ui.screens.MusicArtistScreen(
                        channelUrl = url,
                        onBack = { nav.popBackStack() },
                        onPlay = { /* TODO: wire to MusicPlaybackService via session */ },
                    )
                }
                composable(Tab.Ai.route)       { AiScreen() }
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
                    com.aioweb.app.ui.screens.YtPlaylistScreen(
                        playlistId = id,
                        title = title,
                        onBack = { nav.popBackStack() },
                        onPlay = { /* TODO: wire to MusicPlaybackService */ },
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
                // Eporner-specific player: resolves direct MP4 by video id (with embed fallback),
                // then plays natively. If the resolved URL is HTML (embed page), the unified
                // player falls back to WebView automatically.
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
                            onBack = { nav.popBackStack() },
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { androidx.compose.material3.CircularProgressIndicator() }
                    }
                }
                // Generic player route used by Movies (CloudStream/torrent/HTTP) and any other source.
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
                // Stremio-resolved movie player — pulls the full source list from MoviePlayerSession
                // so the in-player "Sources" button can swap streams without leaving the player.
                composable(
                    "player/movie/{url}/{title}",
                    arguments = listOf(
                        navArgument("url")   { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                    )
                ) { entry ->
                    val initial = URLDecoder.decode(entry.arguments!!.getString("url")!!, "UTF-8")
                    val title = URLDecoder.decode(entry.arguments!!.getString("title")!!, "UTF-8")
                    val sources = com.aioweb.app.player.MoviePlayerSession.sources
                    var currentUrl by remember(initial) { mutableStateOf(initial) }
                    var currentId by remember(initial) {
                        mutableStateOf(sources.firstOrNull { it.url == initial }?.id)
                    }
                    val active = sources.firstOrNull { it.id == currentId }
                    val subtitle = active?.let { "${it.addonName}${it.qualityTag?.let { q -> " · $q" } ?: ""}" }
                    NativePlayerScreen(
                        streamUrl = currentUrl,
                        title = title,
                        subtitle = subtitle,
                        sources = sources,
                        selectedSourceId = currentId,
                        onSwitchSource = { src ->
                            currentUrl = src.url
                            currentId = src.id
                        },
                        progressKey = com.aioweb.app.player.MoviePlayerSession.progressKey,
                        onBack = { nav.popBackStack() },
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
                    // On Tablet/TV the bottom-bar branch is suppressed, so the
                    // global mini-player renders here above the content's bottom
                    // edge instead. On Mobile this branch is skipped.
                    if (showRail && currentRoute != Tab.Music.route) {
                        com.aioweb.app.ui.player.GlobalMiniPlayer(
                            onExpand = {
                                com.aioweb.app.ui.player.PlayerExpandBus.requestExpand()
                            },
                        )
                    }
                }
            }
        }
        // App-wide NowPlayingSheet — renders on TOP of whatever tab is active
        // so swipe-up on the GlobalMiniPlayer works from any screen.
        com.aioweb.app.ui.player.GlobalNowPlayingSheet()
    }
}

private fun navigateToTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
