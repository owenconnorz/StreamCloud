package com.streamcloud.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.player.NativePlayerScreen
import com.streamcloud.app.ui.screens.AdultScreen
import com.streamcloud.app.ui.screens.LibraryScreen
import com.streamcloud.app.ui.screens.MovieDetailScreen
import com.streamcloud.app.ui.screens.MovieSearchScreen
import com.streamcloud.app.ui.screens.MoviesScreen
import com.streamcloud.app.ui.screens.MusicScreen
import com.streamcloud.app.ui.screens.MusicSearchScreen
import com.streamcloud.app.ui.screens.PluginPickerScreen
import com.streamcloud.app.ui.screens.PluginsScreen
import com.streamcloud.app.ui.screens.SettingsHubScreen
import com.streamcloud.app.ui.screens.ProfilePickerScreen
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.viewmodel.AdultViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import android.os.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.data.util.GoogleAccountHelper
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.streamcloud.app.ui.theme.AlbumArtThemeBus
import com.streamcloud.app.ui.theme.AllMoviesThemes
import com.streamcloud.app.ui.theme.TvOverscanPadding
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.streamcloud.app.ui.theme.tvFocusBorder
import java.net.URLDecoder
import java.net.URLEncoder

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Movies   : Tab("movies",   "Movies",   Icons.Filled.Theaters)
    data object Music    : Tab("music",    "Music",    Icons.Filled.MusicNote)
    data object Library  : Tab("library",  "Library",  Icons.Filled.Bookmarks)
    data object Adult    : Tab("adult",    "Adult",    Icons.Filled.Whatshot)
    data object Settings : Tab("settings", "Settings", Icons.Filled.Settings)
}

@OptIn(UnstableApi::class)
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

    // Resolve the correct start destination ONCE — before the NavHost is created.
    // We read the saved order directly from DataStore (one fast suspend call) so the
    // NavHost is created with the right startDestination from the very first frame it
    // appears, rather than always opening on Movies.
    var resolvedStartRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val csv  = sl.settings.navTabOrderCsv.first()
        val nsfw = sl.settings.nsfwEnabled.first()

        val validRoutes = buildSet<String> {
            add(Tab.Movies.route)
            add(Tab.Music.route)
            add(Tab.Library.route)
            if (nsfw) add(Tab.Adult.route)
        }

        resolvedStartRoute = if (!csv.isNullOrBlank()) {
            csv.split(",")
                .map { it.trim() }
                .firstOrNull { it in validRoutes }
                ?: Tab.Movies.route
        } else {
            Tab.Movies.route
        }
    }

    val navLiquidGlass by sl.settings.navLiquidGlass.collectAsState(initial = false)
    val hazeState = rememberHazeState()

    // Dynamic album-art theme — distinct colour per UI layer (Metrolist-style)
    val navPillBgColor by AlbumArtThemeBus.navPillBg.collectAsState()
    val dynamicMiniTheme by sl.settings.dynamicMiniPlayerTheme.collectAsState(initial = true)
    val showNavLabels by sl.settings.navLabels.collectAsState(initial = true)

    // Movie theme colour for the nav pill — used when on any movie-related route
    val moviesThemeNameForPill by sl.settings.moviesTheme.collectAsState(initial = "violet")
    val movieNavPillColor = remember(moviesThemeNameForPill) {
        AllMoviesThemes.find { it.id == moviesThemeNameForPill }?.container ?: Color(0xFF3E2070)
    }
    val isMoviesRoute = remember(currentRoute) {
        val r = currentRoute ?: return@remember false
        r == Tab.Movies.route ||
        r == "movie-search" ||
        r == "collections" ||
        r.startsWith("movie/") ||
        r.startsWith("tv/") ||
        r.startsWith("cs-detail/") ||
        r.startsWith("cs-section/") ||
        r.startsWith("catalog/") ||
        r.startsWith("stremio-detail/") ||
        r.startsWith("cloudstream") ||
        r.startsWith("collection-folder/")
    }

    val navPillColor by animateColorAsState(
        targetValue = when {
            isMoviesRoute    -> movieNavPillColor
            dynamicMiniTheme -> navPillBgColor
            else             -> Color(0xFF1C1C1E)
        },
        animationSpec = tween(600),
        label = "navPillBg",
    )

    // Scroll-driven nav expand/collapse — expands when scrolling up, collapses on scroll down
    var navExpanded by remember { mutableStateOf(true) }
    val navScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                when {
                    available.y < -8f -> navExpanded = false
                    available.y >  8f -> navExpanded = true
                }
                return Offset.Zero
            }
        }
    }
    // Always expand when navigating to a new top-level tab
    LaunchedEffect(currentRoute) { navExpanded = true }

    // Swipeable tabs (all tabs except Settings)
    val swipeableTabs = remember(tabs) { tabs.filter { it.route != Tab.Settings.route } }

    // Animated pill size — shrinks when user scrolls down
    val navPillVPad by animateDpAsState(
        targetValue = if (navExpanded) 6.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navPillVPad",
    )
    val navOuterBottomPad by animateDpAsState(
        targetValue = if (navExpanded) 12.dp else 6.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navOuterBottomPad",
    )

    // Show miniplayer on all non-media routes (including music home)
    val showMiniPlayer = currentRoute != null && !isMediaRoute

    // Profile picker — show on launch when profiles exist; also triggered from Settings
    var showProfilePicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (sl.profiles.currentProfiles().isNotEmpty()) showProfilePicker = true
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {},
    ) { padding ->
        val useRail = LocalUiFormFactor.current != UiFormFactor.Mobile
        val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
        val showRail = useRail &&
            (currentRoute == null || tabs.any { it.route == currentRoute })
        val firstRailFocus = remember { FocusRequester() }
        LaunchedEffect(showRail) {
            // On non-TV form factors, firstRailFocus is attached to the first NavigationRailItem.
            // On TV, it is attached to the hamburger button — see focusRequester() below.
            if (showRail && !isTv) try { firstRailFocus.requestFocus() } catch (_: Exception) {}
        }
        // TV popup nav state — auto-close on route change
        var tvNavOpen by remember { mutableStateOf(false) }
        LaunchedEffect(currentRoute) { tvNavOpen = false }
        // Restore focus to the hamburger whenever the TV nav panel closes so that
        // D-pad navigation continues to work in the content area.
        LaunchedEffect(tvNavOpen) {
            if (!tvNavOpen && isTv) try { firstRailFocus.requestFocus() } catch (_: Exception) {}
        }
        // Intercept the back button to close the TV nav panel instead of exiting the app.
        BackHandler(enabled = isTv && tvNavOpen) { tvNavOpen = false }
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (showRail && !isTv) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    Spacer(Modifier.height(16.dp))
                    tabs.forEachIndexed { idx, tab ->
                        val selected = currentRoute == tab.route
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateToTab(nav, tab.route) },
                            modifier = if (idx == 0) Modifier.focusRequester(firstRailFocus) else Modifier,
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
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .nestedScroll(navScrollConnection)
                    .pointerInput(swipeableTabs, currentRoute) {
                        // Only activate on main tab screens (not sub-screens / settings)
                        if (currentRoute == null ||
                            swipeableTabs.none { it.route == currentRoute }) return@pointerInput

                        val edgePx = 52.dp.toPx()

                        awaitEachGesture {
                            // Wait for first finger down — don't require it to be unconsumed
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val startY = down.position.y

                            // Only fire from the left or right edge strip
                            if (startX > edgePx && startX < size.width - edgePx) return@awaitEachGesture

                            var endX = startX
                            var endY = startY

                            // Track pointer at Final pass so children handle their gestures first
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                endX = change.position.x
                                endY = change.position.y
                                if (!change.pressed) break
                            }

                            val dx = endX - startX
                            val dy = endY - startY

                            // Must be sufficiently horizontal (2:1 ratio) and at least 72dp
                            if (kotlin.math.abs(dx) < kotlin.math.abs(dy) * 2f) return@awaitEachGesture
                            if (kotlin.math.abs(dx) < 72.dp.toPx()) return@awaitEachGesture

                            val idx = swipeableTabs.indexOfFirst { it.route == currentRoute }
                            if (idx < 0) return@awaitEachGesture

                            val target = swipeableTabs.getOrNull(
                                if (dx > 0) idx - 1 else idx + 1
                            ) ?: return@awaitEachGesture

                            navigateToTab(nav, target.route)
                        }
                    }
            ) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        val startRoute = resolvedStartRoute
                        if (startRoute != null) NavHost(
                navController = nav,
                startDestination = startRoute,
            ) {
                composable(Tab.Movies.route) {
                    MoviesScreen(
                        onMovieClick = { id -> nav.navigate("movie/$id") },
                        onTvClick = { id -> nav.navigate("tv/$id") },
                        onOpenCloudStreamPlugin = { internalName ->
                            val n = URLEncoder.encode(internalName, "UTF-8")
                            nav.navigate("cloudstream/$n")
                        },
                        onSearchClick = { nav.navigate("movie-search") },
                        onPluginsClick = { nav.navigate("plugin-picker") },
                        onProfileClick = { navigateToTab(nav, Tab.Settings.route) },
                        onOpenCollections = { nav.navigate("collections") },
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
                        onOpenCollectionFolder = { folderId ->
                            nav.navigate("collection-folder/$folderId")
                        },
                    )
                }
                composable(
                    "collection-folder/{folderId}",
                    arguments = listOf(navArgument("folderId") { type = NavType.LongType }),
                ) { entry ->
                    val folderId = entry.arguments!!.getLong("folderId")
                    com.streamcloud.app.ui.screens.CollectionFolderPageScreen(
                        folderId = folderId,
                        onBack = { nav.popBackStack() },
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
                    )
                }
                composable("collections") {
                    val ctx = LocalContext.current
                    val pluginRepo = remember { com.streamcloud.app.data.plugins.PluginRepository(ctx.applicationContext) }
                    val stremioRepo = remember { com.streamcloud.app.data.stremio.StremioRepository(ctx.applicationContext) }
                    val installedPlugins by pluginRepo.installed.collectAsState(initial = emptyList())
                    val installedAddons by stremioRepo.addons.collectAsState(initial = emptyList())
                    com.streamcloud.app.ui.screens.CollectionsScreen(
                        onBack = { nav.popBackStack() },
                        installedCsPlugins = installedPlugins,
                        installedStremioAddons = installedAddons,
                        onOpenCatalog = { src, t, sub ->
                            val s = URLEncoder.encode(src, "UTF-8")
                            val tt = URLEncoder.encode(t, "UTF-8")
                            val ss = URLEncoder.encode(sub.ifBlank { " " }, "UTF-8")
                            nav.navigate("catalog/$s/$tt/$ss")
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
                        onPlay = { track -> artistVm.play(track) },
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
                    val plContext = LocalContext.current
                    val plScope = rememberCoroutineScope()
                    com.streamcloud.app.ui.screens.YtPlaylistScreen(
                        playlistId = id,
                        title = title,
                        initialThumb = thumb,
                        onBack = { nav.popBackStack() },
                        onPlay = { song ->
                            plScope.launch {
                                com.streamcloud.app.data.ytmusic.YtPlayback.playSong(
                                    plContext, song, withAutoRadio = false,
                                )
                            }
                        },
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
                                            .hazeEffect(state = hazeState) { blurRadius = 24.dp }
                                            .background(Color(0xFF1C1C1E).copy(alpha = 0.55f)),
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
