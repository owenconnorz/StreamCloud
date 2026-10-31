package com.streamcloud.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcloud.app.R
import kotlinx.coroutines.launch
import com.streamcloud.app.data.plugins.CloudStreamPlugin
import com.streamcloud.app.data.plugins.CloudStreamRepo
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.ui.viewmodel.PluginsState
import com.streamcloud.app.ui.viewmodel.PluginsViewModel
import com.streamcloud.app.ui.viewmodel.ProviderTestResult
import com.streamcloud.app.ui.viewmodel.TestStatus

private enum class PluginsPage {
    CloudStream,
    CloudStreamRepository,
    Stremio,
    StremioRepository,
    Nuvio,
    NuvioRepository,
    Tester,
}

private val ColourCloudStream = Color(0xFF5B8DEF)
private val ColourStremio     = Color(0xFF9B6CE0)
private val ColourNuvio       = Color(0xFF4CAF88)
private val ColourTester      = Color(0xFFE8974F)
private val AddonIconBg       = Color(0xFF1B2D52)
private val installedPluginFilters = listOf("All", "Movies", "TV Shows", "Adult", "Anime")

@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: PluginsViewModel = viewModel(factory = PluginsViewModel.factory(context))
    val state by vm.state.collectAsState()

    var currentPage by remember { mutableStateOf<PluginsPage?>(null) }
    var selectedCloudStreamRepo by remember { mutableStateOf<CloudStreamRepo?>(null) }
    var selectedStremioRepo by remember {
        mutableStateOf<com.streamcloud.app.data.stremio.StremioSavedRepo?>(null)
    }
    var selectedNuvioRepo by remember {
        mutableStateOf<com.streamcloud.app.data.nuvio.NuvioSavedRepo?>(null)
    }
    BackHandler(enabled = currentPage != null) {
        currentPage = when (currentPage) {
            PluginsPage.CloudStreamRepository -> PluginsPage.CloudStream
            PluginsPage.StremioRepository -> PluginsPage.Stremio
            PluginsPage.NuvioRepository -> PluginsPage.Nuvio
            else -> null
        }
    }

    AnimatedContent(
        targetState = currentPage,
        transitionSpec = {
            if (targetState != null)
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            else
                slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
        },
        label = "plugins_page",
    ) { page ->
        when (page) {
            null -> PluginsHubPage(
                onBack        = onBack,
                csCount       = state.installed.size,
                stremioCount  = state.stremioAddons.size,
                nuvioCount    = state.nuvioProviders.size,
                onCloudStream = { currentPage = PluginsPage.CloudStream },
                onStremio     = { currentPage = PluginsPage.Stremio },
                onNuvio       = { currentPage = PluginsPage.Nuvio },
                onTester      = { currentPage = PluginsPage.Tester },
            )
            PluginsPage.CloudStream -> CloudStreamPluginsPage(
                vm     = vm,
                state  = state,
                onBack = { currentPage = null },
                onOpenRepository = { repo ->
                    selectedCloudStreamRepo = repo
                    currentPage = PluginsPage.CloudStreamRepository
                },
            )
            PluginsPage.CloudStreamRepository -> selectedCloudStreamRepo?.let { repo ->
                CloudStreamRepositoryPage(
                    repo = repo,
                    state = state,
                    vm = vm,
                    onBack = { currentPage = PluginsPage.CloudStream },
                )
            } ?: Text("Repository unavailable")
            PluginsPage.Stremio -> StremioAddonsPage(
                vm     = vm,
                state  = state,
                onBack = { currentPage = null },
                onOpenRepository = { repo ->
                    selectedStremioRepo = repo
                    currentPage = PluginsPage.StremioRepository
                },
            )
            PluginsPage.StremioRepository -> selectedStremioRepo?.let { repo ->
                StremioRepositoryPage(
                    repo = repo,
                    state = state,
                    vm = vm,
                    onBack = { currentPage = PluginsPage.Stremio },
                )
            } ?: Text("Add-on unavailable")
            PluginsPage.Nuvio -> NuvioProvidersPage(
                vm     = vm,
                state  = state,
                onBack = { currentPage = null },
                onOpenRepository = { repo ->
                    selectedNuvioRepo = repo
                    currentPage = PluginsPage.NuvioRepository
                },
            )
            PluginsPage.NuvioRepository -> selectedNuvioRepo?.let { repo ->
                NuvioRepositoryPage(
                    repo = repo,
                    state = state,
                    vm = vm,
                    onBack = { currentPage = PluginsPage.Nuvio },
                )
            } ?: Text("Repository unavailable")
            PluginsPage.Tester -> ProviderTesterPage(
                vm     = vm,
                state  = state,
                onBack = { currentPage = null },
            )
        }
    }
}

// ─── Hub ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginsHubPage(
    onBack: () -> Unit,
    csCount: Int,
    stremioCount: Int,
    nuvioCount: Int,
    onCloudStream: () -> Unit,
    onStremio: () -> Unit,
    onNuvio: () -> Unit,
    onTester: () -> Unit,
) {
    Scaffold(
        containerColor = PluginUiPalette.background,
        topBar = {
            TopAppBar(
                title = { Text("Plugins & Addons") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PluginUiPalette.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Choose a plugin ecosystem to manage",
                style = MaterialTheme.typography.bodyMedium,
                color = PluginUiPalette.muted,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            AddonHubCard(
                icon        = Icons.Default.Cloud,
                iconTint    = ColourCloudStream,
                title       = "CloudStream",
                description = "Install plugins from community repositories. " +
                    "Supports movies, shows, anime and more.",
                badgeText   = if (csCount > 0) "$csCount installed" else null,
                logoUrl     = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/ic_launcher-playstore.png",
                onClick     = onCloudStream,
            )

            AddonHubCard(
                icon        = Icons.Default.Cast,
                iconTint    = ColourStremio,
                title       = "Stremio",
                description = "Add Stremio-compatible addons via manifest URL. " +
                    "Works with Torrentio, Cinemeta, and more.",
                badgeText   = if (stremioCount > 0) "$stremioCount addons" else null,
                logoRes     = R.drawable.stremio_app_logo,
                onClick     = onStremio,
            )

            AddonHubCard(
                icon        = Icons.Default.Extension,
                iconTint    = ColourNuvio,
                title       = "Nuvio",
                description = "Browse and install Nuvio JavaScript providers " +
                    "from community repositories.",
                badgeText   = if (nuvioCount > 0) "$nuvioCount providers" else null,
                logoRes     = R.drawable.nuvio_app_logo,
                onClick     = onNuvio,
            )

            AddonHubCard(
                icon        = Icons.Default.Tune,
                iconTint    = ColourTester,
                title       = "Provider Tester",
                description = "Test all installed providers against The Dark Knight (2008). " +
                    "Instantly see which ones return streams.",
                badgeText   = run {
                    val total = csCount + stremioCount + nuvioCount
                    if (total > 0) "$total to test" else null
                },
                onClick     = onTester,
            )
        }
    }
}

@Composable
private fun AddonHubCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    badgeText: String?,
    logoRes: Int? = null,
    logoUrl: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PluginUiPalette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AddonIconBg),
            contentAlignment = Alignment.Center,
        ) {
            var imageOk by remember(logoUrl) { mutableStateOf(logoUrl != null) }
            if (logoRes != null) {
                Image(
                    painter = painterResource(id = logoRes),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp)
                        .clip(RoundedCornerShape(11.dp)),
                )
            } else if (logoUrl != null && imageOk) {
                coil.compose.AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                    onError = { imageOk = false },
                )
            } else {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = PluginUiPalette.text,
                )
                if (!badgeText.isNullOrBlank()) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(iconTint.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = iconTint,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = PluginUiPalette.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = PluginUiPalette.muted.copy(alpha = 0.55f),
            modifier = Modifier.size(22.dp),
        )
    }
}

// ─── CloudStream / CS3 sub-screen ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudStreamPluginsPage(
    vm: PluginsViewModel,
    state: PluginsState,
    onBack: () -> Unit,
    onOpenRepository: (CloudStreamRepo) -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var addName    by remember { mutableStateOf("") }
    var addUrl     by remember { mutableStateOf("") }
    var showRepoDialog by remember { mutableStateOf(false) }
    var installedSearch by remember { mutableStateOf("") }
    var showInstalledSearch by remember { mutableStateOf(false) }
    var installedFilter by remember { mutableStateOf("All") }
    var showInstalledFilterMenu by remember { mutableStateOf(false) }

    var pluginHasSettings by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val filteredInstalled = remember(state.installed, installedSearch, installedFilter) {
        state.installed.filter { plugin ->
            val searchable = "${plugin.name} ${plugin.internalName} ${plugin.description.orEmpty()} " +
                plugin.tvTypes.orEmpty().joinToString(" ")
            (installedSearch.isBlank() || searchable.contains(installedSearch, ignoreCase = true)) &&
                matchesInstalledPluginCategory(plugin, installedFilter)
        }
    }

    LaunchedEffect(state.installed) {
        state.installed.forEach { plugin ->
            if (!pluginHasSettings.containsKey(plugin.filePath)) {
                scope.launch {
                    val has = runCatching { PluginRuntime.hasSettings(context, plugin.filePath) }.getOrDefault(false)
                    pluginHasSettings = pluginHasSettings + (plugin.filePath to has)
                }
            }
        }
    }

    if (showRepoDialog) {
        AddNamedRepositoryDialog(
            title = "Add CloudStream Repository",
            description = "Add a CloudStream repository to browse and install plugins.",
            urlLabel = "repo.json URL",
            urlPlaceholder = "https://example.com/repo.json",
            saveLabel = "Save Repository",
            accent = ColourCloudStream,
            name = addName,
            url = addUrl,
            busy = false,
            onNameChange = { addName = it },
            onUrlChange = { addUrl = it },
            onSave = {
                vm.addRepo(addName, addUrl)
                addName = ""
                addUrl = ""
                showRepoDialog = false
            },
            onDismiss = { showRepoDialog = false },
        )
    }

    Scaffold(
        containerColor = PluginUiPalette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("CloudStream / CS3") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PluginUiPalette.background,
                    titleContentColor = PluginUiPalette.text,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.info?.let  { item { StatusBanner(it, isError = false) { vm.clearMessages() } } }
            state.error?.let { item { StatusBanner(it, isError = true)  { vm.clearMessages() } } }

            // The three summary cards and repository section lead, as in the reference.
            item {
                val packageCount = state.pluginsByRepo.values
                    .flatten()
                    .distinctBy { it.internalName ?: it.name }
                    .size
                    .coerceAtLeast(state.repos.sumOf { it.pluginCount })
                    .coerceAtLeast(state.installed.size)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OverviewStatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Cloud,
                        value = state.repos.size.toString(),
                        label = "repositories",
                    )
                    OverviewStatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Extension,
                        value = state.installed.size.toString(),
                        label = "installed",
                    )
                    OverviewStatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.CloudDownload,
                        value = packageCount.toString(),
                        label = "CS3 packages",
                    )
                }
            }

            // Info blurb
            item {
                Text(
                    "Install .cs3 plugin packages from community repository URLs. Each repository lists plugins you can install individually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PluginUiPalette.muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }

            item {
                PluginMainSectionHeader(
                    icon = Icons.Default.Cloud,
                    title = "CS3 Repositories",
                    count = state.repos.size,
                    accent = ColourCloudStream,
                    actionLabel = "Add Repository",
                    onAction = { showRepoDialog = true },
                )
            }

            if (state.repos.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(PluginUiPalette.card)
                            .padding(20.dp),
                    ) {
                        Text(
                            "No repositories yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = PluginUiPalette.text,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Add a repository URL above to get started.\n\n" +
                                "Popular repos:\n" +
                                "• https://raw.githubusercontent.com/recloudstream/extensions/builds\n" +
                                "• https://raw.githubusercontent.com/SaurabhKaperwan/CSX/master\n" +
                                "• https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PluginUiPalette.muted,
                        )
                    }
                }
            }

            items(state.repos, key = { it.id }) { repo ->
                RepoCard(
                    repo             = repo,
                    pluginCount      = state.pluginsByRepo[repo.id]?.size ?: repo.pluginCount,
                    isLoading        = state.loadingRepoIds.contains(repo.id),
                    onOpen           = { onOpenRepository(repo) },
                    onRemove         = { vm.removeRepo(repo.id) },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Installed plugins",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = PluginUiPalette.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "(${state.installed.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ColourCloudStream,
                    )
                    IconButton(onClick = { showInstalledSearch = !showInstalledSearch }) {
                        Icon(Icons.Default.Search, "Search installed plugins", tint = PluginUiPalette.muted)
                    }
                    Box {
                        IconButton(onClick = { showInstalledFilterMenu = true }) {
                            Icon(Icons.Default.Tune, "Filter installed plugins", tint = PluginUiPalette.muted)
                        }
                        DropdownMenu(
                            expanded = showInstalledFilterMenu,
                            onDismissRequest = { showInstalledFilterMenu = false },
                        ) {
                            installedPluginFilters.forEach { filter ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (filter == installedFilter) "✓  $filter" else filter)
                                    },
                                    onClick = {
                                        installedFilter = filter
                                        showInstalledFilterMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (showInstalledSearch) {
                item {
                    OutlinedTextField(
                        value = installedSearch,
                        onValueChange = { installedSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Search installed plugins") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            IconButton(onClick = {
                                installedSearch = ""
                                showInstalledSearch = false
                            }) {
                                Text("×", color = PluginUiPalette.muted, fontSize = 20.sp)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PluginUiPalette.text,
                            unfocusedTextColor = PluginUiPalette.text,
                            focusedBorderColor = ColourCloudStream,
                            unfocusedBorderColor = PluginUiPalette.border,
                            focusedLabelColor = PluginUiPalette.muted,
                            unfocusedLabelColor = PluginUiPalette.muted,
                            cursorColor = ColourCloudStream,
                        ),
                    )
                }
            }
            if (filteredInstalled.isEmpty()) {
                item {
                    RepositoryEmptyCard(
                        if (state.installed.isEmpty()) "No plugins installed"
                        else "No plugins match this filter",
                        if (state.installed.isEmpty()) "Open a repository above and install a plugin."
                        else "Try another category or search term.",
                    )
                }
            } else {
                items(
                    filteredInstalled,
                    key = { p -> "inst_${p.sourceRepoId}_${p.internalName}_${p.installedAt}" },
                ) { p ->
                    InstalledRow(
                        p = p,
                        onUninstall = { vm.uninstall(p) },
                        onSettings = if (pluginHasSettings[p.filePath] == true) {
                            { PluginSettingsActivity.start(context, p.filePath, p.name) }
                        } else null,
                    )
                }
            }
        }
    }
}

// ─── Stremio "Addons" sub-screen ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StremioAddonsPage(
    vm: PluginsViewModel,
    state: PluginsState,
    onBack: () -> Unit,
    onOpenRepository: (com.streamcloud.app.data.stremio.StremioSavedRepo) -> Unit,
) {
    var stremioName by remember { mutableStateOf("") }
    var stremioUrl by remember { mutableStateOf("") }
    var showAddonDialog by remember { mutableStateOf(false) }

    // On TV: keep the manifest field inside a dialog so scrolling the page
    // never focuses it and opens the keyboard unexpectedly.
    if (showAddonDialog) {
        AddNamedRepositoryDialog(
            title = "Add Stremio Add-on",
            description = "Save a Stremio manifest to review and install the add-on.",
            urlLabel = "Manifest URL",
            urlPlaceholder = "https://your-addon.com/manifest.json",
            saveLabel = "Save Add-on",
            accent = ColourStremio,
            name = stremioName,
            url = stremioUrl,
            busy = state.addingStremioRepo,
            onNameChange = { stremioName = it },
            onUrlChange = { stremioUrl = it },
            onSave = {
                vm.addStremioRepo(stremioName, stremioUrl)
                stremioName = ""
                stremioUrl = ""
                showAddonDialog = false
            },
            onDismiss = { showAddonDialog = false },
        )
    }

    Scaffold(
        containerColor = PluginUiPalette.background,
        topBar = {
            TopAppBar(
                title = { Text("Stremio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PluginUiPalette.background,
                    titleContentColor = PluginUiPalette.text,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.info?.let  { item { StatusBanner(it, isError = false) { vm.clearMessages() } } }
            state.error?.let { item { StatusBanner(it, isError = true)  { vm.clearMessages() } } }

            item {
                Text(
                    "Save an add-on manifest here, then open it to review its details and install it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PluginUiPalette.muted,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            item {
                PluginMainSectionHeader(
                    icon = Icons.Default.Extension,
                    title = "Stremio Add-ons",
                    count = state.stremioSavedRepos.size,
                    accent = ColourStremio,
                    actionLabel = "Add Add-on",
                    onAction = { showAddonDialog = true },
                )
            }
            if (state.stremioSavedRepos.isEmpty()) {
                item { RepositoryEmptyCard("No add-ons saved yet", "Select Add Add-on to enter a display name and manifest URL.") }
            }
            items(state.stremioSavedRepos, key = { "stremio_repo_${it.id}" }) { repo ->
                SavedRepositoryRow(
                    name = repo.name,
                    url = repo.manifestUrl,
                    detail = "Open to review and install",
                    logoUrl = repo.logo,
                    icon = Icons.Default.Extension,
                    accent = ColourStremio,
                    onClick = { onOpenRepository(repo) },
                    onDelete = { vm.removeStremioRepo(repo.id) },
                )
            }

            item { Spacer(Modifier.height(4.dp)) }
            item { SectionLabel("Installed Add-ons (${state.stremioAddons.size})") }
            if (state.stremioAddons.isNotEmpty()) {
                items(state.stremioAddons, key = { it.manifestUrl }) { addon ->
                    StremioAddonRow(
                        addon    = addon,
                        onRemove = { vm.removeStremioAddon(addon.manifestUrl) },
                        onRefresh = { vm.syncStremioCollections(addon.manifestUrl) },
                    )
                }
            } else {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(PluginUiPalette.card)
                            .padding(20.dp),
                    ) {
                        Text(
                            "No addons installed yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = PluginUiPalette.text,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Popular addons to try:\n" +
                                "• https://v3-cinemeta.strem.io/manifest.json\n" +
                                "• https://torrentio.strem.fun/manifest.json\n" +
                                "• https://nuviostreams.hayd.uk/manifest.json",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PluginUiPalette.muted,
                        )
                    }
                }
            }
        }
    }
}

// ─── Nuvio "Plugins" sub-screen ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NuvioProvidersPage(
    vm: PluginsViewModel,
    state: PluginsState,
    onBack: () -> Unit,
    onOpenRepository: (com.streamcloud.app.data.nuvio.NuvioSavedRepo) -> Unit,
) {
    var nuvioRepoName by remember { mutableStateOf("") }
    var nuvioRepoInput by remember { mutableStateOf("") }
    var showRepoDialog by remember { mutableStateOf(false) }

    if (showRepoDialog) {
        AddNamedRepositoryDialog(
            title = "Add Nuvio Repository",
            description = "Add a Nuvio repository to browse and install providers.",
            urlLabel = "Repository URL",
            urlPlaceholder = "https://raw.githubusercontent.com/…/main/",
            saveLabel = "Save Repository",
            accent = ColourNuvio,
            name = nuvioRepoName,
            url = nuvioRepoInput,
            busy = state.loadingNuvioRepo,
            onNameChange = { nuvioRepoName = it },
            onUrlChange = { nuvioRepoInput = it },
            onSave = {
                vm.loadNuvioRepo(nuvioRepoInput, nuvioRepoName)
                nuvioRepoName = ""
                nuvioRepoInput = ""
                showRepoDialog = false
            },
            onDismiss = { showRepoDialog = false },
        )
    }

    Scaffold(
        containerColor = PluginUiPalette.background,
        topBar = {
            TopAppBar(
                title = { Text("Nuvio Providers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PluginUiPalette.background,
                    titleContentColor = PluginUiPalette.text,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.info?.let  { item { StatusBanner(it, isError = false) { vm.clearMessages() } } }
            state.error?.let { item { StatusBanner(it, isError = true)  { vm.clearMessages() } } }

            // Overview cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OverviewStatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Cloud,
                        value = state.nuvioSavedRepos.size.toString(),
                        label = "repositories",
                        accent = ColourNuvio,
                    )
                    OverviewStatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Extension,
                        value = state.nuvioProviders.size.toString(),
                        label = "installed",
                        accent = ColourNuvio,
                    )
                    OverviewStatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.CloudDownload,
                    value = state.nuvioProviders.map { it.repoUrl }.distinct().size.toString(),
                        label = "provider repos",
                        accent = ColourNuvio,
                    )
                }
            }

            item {
                Text(
                    "Add a provider repository, then open it to browse and install providers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PluginUiPalette.muted,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            item {
                PluginMainSectionHeader(
                    icon = Icons.Default.Cloud,
                    title = "Nuvio Repositories",
                    count = state.nuvioSavedRepos.size,
                    accent = ColourNuvio,
                    actionLabel = "Add Repository",
                    onAction = { showRepoDialog = true },
                )
            }
            if (state.nuvioSavedRepos.isEmpty()) {
                item { RepositoryEmptyCard("No repositories saved yet", "Select Add Repository to enter a display name and repository URL.") }
            }
            items(state.nuvioSavedRepos, key = { "repo_${it.id}" }) { savedRepo ->
                val count = state.nuvioProviders.count { it.repoUrl == savedRepo.url }
                SavedRepositoryRow(
                    name = savedRepo.name ?: "Nuvio repository",
                    url = savedRepo.url,
                    detail = if (count > 0) "$count providers installed · Open to browse" else "Open to browse and install providers",
                    logoUrl = null,
                    icon = Icons.Default.Cloud,
                    accent = ColourNuvio,
                    onClick = { onOpenRepository(savedRepo) },
                    onDelete = { vm.removeNuvioSavedRepo(savedRepo.id) },
                )
            }

            // Installed providers section
            if (state.nuvioSavedRepos.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
            }
            item { SectionLabel("Installed Providers (${state.nuvioProviders.size})") }
            if (state.nuvioProviders.isNotEmpty()) {
                items(state.nuvioProviders, key = { it.id }) { p ->
                    NuvioProviderRow(
                        p        = p,
                        onRemove = { vm.uninstallNuvioProvider(p.id) },
                    )
                }
            } else {
                item { RepositoryEmptyCard("No providers installed", "Open a repository and choose Install on a provider.") }
            }
        }
    }
}

// ─── Shared private helpers ───────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = PluginUiPalette.text,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun OverviewStatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    accent: Color = ColourCloudStream,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, PluginUiPalette.border, RoundedCornerShape(16.dp))
            .background(PluginUiPalette.card)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(25.dp))
        Column {
            Text(
                value,
                color = PluginUiPalette.text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                label,
                color = PluginUiPalette.muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PluginMainSectionHeader(
    icon: ImageVector,
    title: String,
    count: Int,
    accent: Color,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
        Text(
            "$title ($count)",
            modifier = Modifier.weight(1f),
            color = PluginUiPalette.text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(4.dp))
            Text(actionLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private fun matchesInstalledPluginCategory(plugin: InstalledPlugin, category: String): Boolean {
    if (category == "All") return true
    val metadata = buildString {
        append(plugin.name)
        append(' ')
        append(plugin.internalName)
        append(' ')
        append(plugin.description.orEmpty())
        append(' ')
        append(plugin.tvTypes.orEmpty().joinToString(" "))
    }.lowercase()
    return when (category) {
        "Movies" -> plugin.tvTypes.orEmpty().any { it.contains("movie", true) } ||
            metadata.contains("movie") || metadata.contains("film")
        "TV Shows" -> plugin.tvTypes.orEmpty().any {
            it.contains("tv", true) || it.contains("series", true) || it.contains("show", true)
        } || listOf("series", "tv show", "episode").any(metadata::contains)
        "Adult" -> plugin.isAdultPlugin()
        "Anime" -> metadata.contains("anime")
        else -> true
    }
}

@Composable
private fun StatChip(label: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

@Composable
private fun StatusBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
            )
            .clickable(onClick = onDismiss)
            .padding(12.dp),
    ) {
        Text(
            text,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InstalledRow(
    p: InstalledPlugin,
    onUninstall: () -> Unit,
    onSettings: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, PluginUiPalette.border, RoundedCornerShape(14.dp))
            .background(PluginUiPalette.card)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AddonIconBg),
            contentAlignment = Alignment.Center,
        ) {
            if (!p.iconUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = p.iconUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Icon(
                    Icons.Default.Extension, null,
                    tint = ColourCloudStream,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.name,
                color = PluginUiPalette.text,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "v${p.version} · ${p.internalName}",
                color = PluginUiPalette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Plugin settings",
                    tint = PluginUiPalette.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onUninstall) {
            Icon(Icons.Default.Delete, "Uninstall", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = PluginUiPalette.muted)
    }
}

@Composable
private fun NuvioProviderRow(
    p: com.streamcloud.app.data.nuvio.InstalledNuvioProvider,
    onRemove: () -> Unit,
) {
    val lastError = com.streamcloud.app.data.nuvio.NuvioRuntime.lastError(p.id)
    val repoHost = remember(p.repoUrl) {
        runCatching { java.net.URI(p.repoUrl).host }.getOrElse { p.repoUrl }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PluginUiPalette.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (lastError != null) MaterialTheme.colorScheme.errorContainer
                        else ColourNuvio.copy(alpha = 0.14f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (!p.logo.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = p.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Text(
                        p.name.firstOrNull()?.uppercaseChar()?.toString() ?: "N",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (lastError != null) MaterialTheme.colorScheme.error else ColourNuvio,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Repo tag
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ColourNuvio.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        repoHost ?: "nuvio",
                        style = MaterialTheme.typography.labelSmall,
                        color = ColourNuvio,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    p.name,
                    color = PluginUiPalette.text,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!p.description.isNullOrBlank()) {
                    Text(
                        p.description,
                        color = PluginUiPalette.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
        if (lastError != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                lastError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!p.version.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EcosystemChip("v${p.version}", ColourNuvio)
                EcosystemChip("Nuvio", ColourNuvio)
            }
        }
    }
}

@Composable
private fun StremioAddonRow(
    addon: com.streamcloud.app.data.stremio.InstalledStremioAddon,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PluginUiPalette.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AddonIconBg),
                contentAlignment = Alignment.Center,
            ) {
                if (!addon.logo.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = addon.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Icon(
                        Icons.Default.Extension, null,
                        tint = ColourStremio,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    addon.name,
                    color = PluginUiPalette.text,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    addon.id,
                    color = PluginUiPalette.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "Refresh", tint = PluginUiPalette.muted, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EcosystemChip("Active", ColourStremio)
            if (!addon.version.isNullOrBlank()) EcosystemChip("v${addon.version}", ColourStremio)
            EcosystemChip("Stremio", PluginUiPalette.muted)
        }
    }
}

@Composable
private fun EcosystemChip(label: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color,
        )
    }
}

@Composable
private fun RepoCard(
    repo: CloudStreamRepo,
    pluginCount: Int,
    isLoading: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, PluginUiPalette.border, RoundedCornerShape(14.dp))
            .background(PluginUiPalette.card)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ColourCloudStream.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Cloud, null, tint = ColourCloudStream, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                repo.name,
                color = PluginUiPalette.text,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                repo.url,
                color = PluginUiPalette.muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (pluginCount > 0) "$pluginCount plugins · Tap to browse and install"
                else "Tap to browse and load plugins",
                style = MaterialTheme.typography.labelSmall,
                color = ColourCloudStream,
            )
        }
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ColourCloudStream)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, "Remove repository", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = PluginUiPalette.muted)
    }
}

@Composable
private fun PluginRow(
    plugin: CloudStreamPlugin,
    installing: Boolean,
    installed: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AddonIconBg),
            contentAlignment = Alignment.Center,
        ) {
            if (!plugin.iconUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = plugin.iconUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Text(
                    plugin.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = ColourCloudStream,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                plugin.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            plugin.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = PluginUiPalette.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EcosystemChip("v${plugin.version}", ColourCloudStream)
                plugin.language?.let { EcosystemChip(it, PluginUiPalette.muted) }
                plugin.tvTypes?.firstOrNull()?.let { EcosystemChip(it, PluginUiPalette.muted) }
            }
        }
        Spacer(Modifier.width(6.dp))
        when {
            installing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            installed -> FilledTonalIconButton(
                onClick = onUninstall,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Default.Delete, "Uninstall", modifier = Modifier.size(16.dp))
            }
            else -> FilledTonalIconButton(
                onClick = onInstall,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = ColourCloudStream.copy(alpha = 0.14f),
                    contentColor = ColourCloudStream,
                ),
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Default.CloudDownload, "Install", modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ─── Provider Tester ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderTesterPage(
    vm: PluginsViewModel,
    state: PluginsState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    Scaffold(
        containerColor = PluginUiPalette.background,
        topBar = {
            TopAppBar(
                title = { Text("Provider Tester") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PluginUiPalette.background,
                    titleContentColor = PluginUiPalette.text,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                TesterHeaderCard(
                    running = state.testRunning,
                    results = state.testResults,
                    onRun = { vm.runAllProviderTests(context) },
                )
            }

            if (state.installed.isNotEmpty()) {
                item { TesterSectionHeader("CloudStream", ColourCloudStream, state.installed.size) }
                items(state.installed, key = { it.internalName }) { plugin ->
                    ProviderTestRow(
                        logo = plugin.iconUrl,
                        name = plugin.name,
                        ecosystemColor = ColourCloudStream,
                        result = state.testResults.find { it.id == plugin.internalName },
                    )
                }
            }

            if (state.stremioAddons.isNotEmpty()) {
                item { TesterSectionHeader("Stremio", ColourStremio, state.stremioAddons.size) }
                items(state.stremioAddons, key = { it.manifestUrl }) { addon ->
                    ProviderTestRow(
                        logo = addon.logo,
                        name = addon.name,
                        ecosystemColor = ColourStremio,
                        result = state.testResults.find { it.id == addon.manifestUrl },
                    )
                }
            }

            if (state.nuvioProviders.isNotEmpty()) {
                item { TesterSectionHeader("Nuvio", ColourNuvio, state.nuvioProviders.size) }
                items(state.nuvioProviders, key = { it.id }) { provider ->
                    ProviderTestRow(
                        logo = provider.logo,
                        name = provider.name,
                        ecosystemColor = ColourNuvio,
                        result = state.testResults.find { it.id == provider.id },
                    )
                }
            }

            if (state.installed.isEmpty() && state.stremioAddons.isEmpty() && state.nuvioProviders.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No providers installed yet.\n" +
                                "Install plugins from CloudStream, Stremio, or Nuvio first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PluginUiPalette.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TesterHeaderCard(
    running: Boolean,
    results: List<ProviderTestResult>,
    onRun: () -> Unit,
) {
    val passed    = results.count { it.status == TestStatus.Success && it.streamCount > 0 }
    val empty     = results.count { it.status == TestStatus.Success && it.streamCount == 0 }
    val failed    = results.count { it.status == TestStatus.Error }
    val pending   = results.count { it.status == TestStatus.Idle || it.status == TestStatus.Running }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PluginUiPalette.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp, 66.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AddonIconBg),
            ) {
                coil.compose.AsyncImage(
                    model = "https://image.tmdb.org/t/p/w92/qJ2tW6WMkB347Fnh0o5Iq7fWZFY.jpg",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "TEST FILM",
                    style = MaterialTheme.typography.labelSmall,
                    color = PluginUiPalette.muted,
                )
                Text(
                    "The Dark Knight",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "2008 · tt0468569 · TMDB 155",
                    style = MaterialTheme.typography.bodySmall,
                    color = PluginUiPalette.muted,
                )
                Text(
                    "A provider passes if it returns ≥ 1 stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PluginUiPalette.muted,
                )
            }
        }

        if (results.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (passed  > 0) TesterScorePill("$passed passed",  Color(0xFF4CAF50))
                if (empty   > 0) TesterScorePill("$empty empty",    Color(0xFFFF9800))
                if (failed  > 0) TesterScorePill("$failed failed",  MaterialTheme.colorScheme.error)
                if (pending > 0) TesterScorePill("$pending pending", PluginUiPalette.muted)
            }
        }

        Button(
            onClick = onRun,
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ColourTester),
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text("Running tests…")
            } else {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (results.isEmpty()) "Run All Tests" else "Run Again")
            }
        }
    }
}

@Composable
private fun TesterScorePill(label: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

@Composable
private fun TesterSectionHeader(name: String, color: Color, count: Int) {
    Row(
        Modifier.padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Text(
            name.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = color,
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = PluginUiPalette.muted,
        )
    }
}

@Composable
private fun ProviderTestRow(
    logo: String?,
    name: String,
    ecosystemColor: Color,
    result: ProviderTestResult?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PluginUiPalette.card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AddonIconBg),
            contentAlignment = Alignment.Center,
        ) {
            if (!logo.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Text(
                    name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = ecosystemColor,
                )
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (result?.status) {
                TestStatus.Error -> Text(
                    result.error ?: "Error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TestStatus.Success -> Text(
                    "${result.durationMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = PluginUiPalette.muted,
                )
                else -> {}
            }
        }

        Box(Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.CenterEnd) {
            when (result?.status) {
                TestStatus.Running ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                TestStatus.Success -> {
                    val passing = result.streamCount > 0
                    val tint    = if (passing) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "${result.streamCount}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = tint,
                        )
                    }
                }
                TestStatus.Error ->
                    Text(
                        "✗",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error,
                    )
                else ->
                    Text(
                        "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PluginUiPalette.muted.copy(alpha = 0.35f),
                    )
            }
        }
    }
}
