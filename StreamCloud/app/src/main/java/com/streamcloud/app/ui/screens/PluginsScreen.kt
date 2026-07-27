package com.streamcloud.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.streamcloud.app.data.plugins.CloudStreamPlugin
import com.streamcloud.app.data.plugins.CloudStreamRepo
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.ui.viewmodel.PluginsState
import com.streamcloud.app.ui.viewmodel.PluginsViewModel
import com.streamcloud.app.ui.viewmodel.ProviderTestResult
import com.streamcloud.app.ui.viewmodel.TestStatus

private enum class PluginsPage { CloudStream, Stremio, Nuvio, Tester }

private val ColourCloudStream = Color(0xFF5B8DEF)
private val ColourStremio     = Color(0xFF9B6CE0)
private val ColourNuvio       = Color(0xFF4CAF88)
private val ColourTester      = Color(0xFFE8974F)
private val AddonIconBg       = Color(0xFF1B2D52)

@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: PluginsViewModel = viewModel(factory = PluginsViewModel.factory(context))
    val state by vm.state.collectAsState()

    var currentPage by remember { mutableStateOf<PluginsPage?>(null) }
    BackHandler(enabled = currentPage != null) { currentPage = null }

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
            )
            PluginsPage.Stremio -> StremioAddonsPage(
                vm     = vm,
                state  = state,
                onBack = { currentPage = null },
            )
            PluginsPage.Nuvio -> NuvioProvidersPage(
                vm     = vm,
                state  = state,
                onBack = { currentPage = null },
            )
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Plugins & Addons") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                logoUrl     = "https://www.stremio.com/website/stremio-logo-small.png",
                onClick     = onStremio,
            )

            AddonHubCard(
                icon        = Icons.Default.Extension,
                iconTint    = ColourNuvio,
                title       = "Nuvio",
                description = "Browse and install Nuvio JavaScript providers " +
                    "from community repositories.",
                badgeText   = if (nuvioCount > 0) "$nuvioCount providers" else null,
                logoUrl     = "https://raw.githubusercontent.com/Nuvio-Streams/nuvio-streams/main/app/src/main/ic_launcher-playstore.png",
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
    logoUrl: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
            if (logoUrl != null) {
                coil.compose.AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
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
                    color = MaterialTheme.colorScheme.onSurface,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var addName    by remember { mutableStateOf("") }
    var addUrl     by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    var pluginHasSettings by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val snackbarHostState = remember { SnackbarHostState() }

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
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

            // Overview chips
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { StatChip("${state.repos.size} repositories", ColourCloudStream) }
                    item { StatChip("${state.installed.size} installed", ColourCloudStream) }
                    item { StatChip("CS3 packages", MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            // Info blurb
            item {
                Text(
                    "Install .cs3 plugin packages from community repository URLs. Each repository lists plugins you can install individually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }

            // Add repository section
            item {
                SectionLabel("Add CloudStream Repository")
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = addUrl,
                        onValueChange = { addUrl = it },
                        label = { Text("repo.json URL") },
                        placeholder = { Text("https://example.com/repo.json") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = {
                            vm.addRepo(addName, addUrl)
                            addName = ""
                            addUrl = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = addName.isNotBlank() && addUrl.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ColourCloudStream),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Repository")
                    }
                }
            }

            // Installed plugins section
            if (state.installed.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
                item { SectionLabel("Installed Plugins (${state.installed.size})") }
                items(
                    state.installed,
                    key = { p -> "inst_${p.sourceRepoId}_${p.internalName}_${p.installedAt}" },
                ) { p ->
                    InstalledRow(
                        p          = p,
                        onUninstall = { vm.uninstall(p) },
                        onSettings  = if (pluginHasSettings[p.filePath] == true) {
                            { PluginSettingsActivity.start(context, p.filePath, p.name) }
                        } else null,
                    )
                }
            }

            // CS3 Repositories section
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionLabel("CS3 Repositories (${state.repos.size})") }

            if (state.repos.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(20.dp),
                    ) {
                        Text(
                            "No repositories yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Add a repository URL above to get started.\n\n" +
                                "Popular repos:\n" +
                                "• https://raw.githubusercontent.com/recloudstream/extensions/builds\n" +
                                "• https://raw.githubusercontent.com/SaurabhKaperwan/CSX/master\n" +
                                "• https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Search filter for plugins
            if (state.repos.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search plugins…") },
                        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }

            items(state.repos, key = { it.id }) { repo ->
                RepoCard(
                    repo             = repo,
                    plugins          = state.pluginsByRepo[repo.id].orEmpty()
                        .let { list ->
                            if (searchQuery.isBlank()) list
                            else list.filter { it.name.contains(searchQuery, ignoreCase = true) }
                        },
                    isLoading        = state.loadingRepoIds.contains(repo.id),
                    installingNames  = state.installingNames,
                    installedNames   = state.installed.map { it.internalName }.toSet(),
                    onFetch          = { vm.fetchRepo(repo) },
                    onRemove         = { vm.removeRepo(repo.id) },
                    onInstall        = { vm.install(repo, it) },
                    onUninstall      = { plugin ->
                        val key = plugin.internalName ?: plugin.name
                        state.installed.firstOrNull { it.internalName == key }?.let(vm::uninstall)
                    },
                )
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
) {
    val scope = rememberCoroutineScope()
    var stremioUrl by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Addons") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
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

            // Overview chips
            if (state.stremioAddons.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { StatChip("${state.stremioAddons.size} addons", ColourStremio) }
                        item { StatChip("${state.stremioAddons.size} active", ColourStremio) }
                        item { StatChip("Stremio", MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            // Add Addon section
            item { SectionLabel("Add Addon") }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = stremioUrl,
                        onValueChange = { stremioUrl = it },
                        label = { Text("Manifest URL") },
                        placeholder = { Text("https://your-addon.com/manifest.json") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Text(
                        "Paste any Stremio addon manifest URL, e.g. torrentio.strem.fun/manifest.json",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { vm.addStremioAddon(stremioUrl); stremioUrl = "" },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = stremioUrl.isNotBlank() && !state.addingStremio,
                        colors = ButtonDefaults.buttonColors(containerColor = ColourStremio),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (state.addingStremio) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Installing…")
                        } else {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Install Addon")
                        }
                    }
                }
            }

            // Installed Addons section
            if (state.stremioAddons.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
                item { SectionLabel("Installed Addons (${state.stremioAddons.size})") }
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
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(20.dp),
                    ) {
                        Text(
                            "No addons installed yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Popular addons to try:\n" +
                                "• https://v3-cinemeta.strem.io/manifest.json\n" +
                                "• https://torrentio.strem.fun/manifest.json\n" +
                                "• https://nuviostreams.hayd.uk/manifest.json",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
) {
    var nuvioRepoInput by remember { mutableStateOf("") }

    // Group providers by repo URL for the installed repos section
    val providersByRepo = remember(state.nuvioProviders) {
        state.nuvioProviders.groupBy { it.repoUrl }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Plugins") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
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

            // Overview chips
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { StatChip("${state.nuvioSavedRepos.size} repos", ColourNuvio) }
                    item { StatChip("${state.nuvioProviders.size} providers", ColourNuvio) }
                    item {
                        StatChip(
                            if (state.nuvioProviders.isEmpty()) "No plugins" else "Plugins enabled",
                            if (state.nuvioProviders.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                            else ColourNuvio,
                        )
                    }
                }
            }

            // Install repo section
            item { SectionLabel("Install Plugin Repository") }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = nuvioRepoInput,
                        onValueChange = { nuvioRepoInput = it },
                        label = { Text("Repository URL") },
                        placeholder = { Text("https://raw.githubusercontent.com/…/main/") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = { vm.loadNuvioRepo(nuvioRepoInput) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = nuvioRepoInput.isNotBlank() && !state.loadingNuvioRepo,
                        colors = ButtonDefaults.buttonColors(containerColor = ColourNuvio),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (state.loadingNuvioRepo) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Loading…")
                        } else {
                            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Load Repository")
                        }
                    }
                }
            }

            // Loaded manifest: show repo + providers
            val mf = state.nuvioRepoManifest
            if (mf != null) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ColourNuvio.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.CloudDownload, null, tint = ColourNuvio, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    mf.name ?: "Repository",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                )
                                Text(
                                    "${mf.allProviders.size} providers available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        mf.allProviders.forEach { entry ->
                            val installing = entry.id in state.installingNuvioIds
                            val already    = state.nuvioProviders.any { it.id == entry.id }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(10.dp),
                            ) {
                                if (!entry.logo.isNullOrBlank() || !entry.icon.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = entry.logo ?: entry.icon,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(ColourNuvio.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            entry.name.firstOrNull()?.uppercaseChar()?.toString() ?: "N",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = ColourNuvio,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!entry.description.isNullOrBlank()) {
                                        Text(
                                            entry.description,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (!entry.version.isNullOrBlank()) {
                                        Text(
                                            "v${entry.version}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ColourNuvio,
                                        )
                                    }
                                }
                                when {
                                    installing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    already -> Icon(
                                        Icons.Default.CheckCircle, "Installed",
                                        tint = ColourNuvio,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    else -> TextButton(
                                        onClick = { vm.installNuvioProvider(entry) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = ColourNuvio),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) { Text("Install", style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                        }
                    }
                }
            }

            // Installed Repositories section
            if (state.nuvioSavedRepos.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
                item { SectionLabel("Installed Repositories (${state.nuvioSavedRepos.size})") }
                items(state.nuvioSavedRepos, key = { "repo_${it.id}" }) { savedRepo ->
                    val repoProviderCount = providersByRepo[savedRepo.url]?.size ?: 0
                    NuvioSavedRepoRow(
                        repo     = savedRepo,
                        providerCount = repoProviderCount,
                        onBrowse = {
                            nuvioRepoInput = savedRepo.url
                            vm.loadNuvioRepo(savedRepo.url)
                        },
                        onDelete = { vm.removeNuvioSavedRepo(savedRepo.id) },
                    )
                }
            }

            // Installed Providers section
            if (state.nuvioProviders.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
                item { SectionLabel("Providers (${state.nuvioProviders.size})") }
                items(state.nuvioProviders, key = { it.id }) { p ->
                    NuvioProviderRow(
                        p        = p,
                        onRemove = { vm.uninstallNuvioProvider(p.id) },
                    )
                }
            } else if (state.nuvioSavedRepos.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(20.dp),
                    ) {
                        Text(
                            "No Nuvio providers installed",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Load a Nuvio provider repository URL above to browse and install providers.\n\n" +
                                "Popular repos:\n" +
                                "• https://raw.githubusercontent.com/yoruix/nuvio-providers/main/\n" +
                                "• https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/main/",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ─── Shared private helpers ───────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
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
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "v${p.version} · ${p.internalName}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Plugin settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onUninstall) {
            Icon(Icons.Default.Delete, "Uninstall", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!p.description.isNullOrBlank()) {
                    Text(
                        p.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun NuvioSavedRepoRow(
    repo: com.streamcloud.app.data.nuvio.NuvioSavedRepo,
    providerCount: Int,
    onBrowse: () -> Unit,
    onDelete: () -> Unit,
) {
    val host = remember(repo.url) {
        runCatching { java.net.URI(repo.url).host }.getOrElse { repo.url }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onBrowse)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ColourNuvio.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.CloudDownload, null,
                tint = ColourNuvio,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                repo.name ?: host ?: repo.url,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                host ?: repo.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (providerCount > 0) {
                Text(
                    "$providerCount providers installed",
                    style = MaterialTheme.typography.labelSmall,
                    color = ColourNuvio,
                )
            }
        }
        IconButton(onClick = onBrowse) {
            Icon(Icons.Default.Refresh, "Browse repo", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete, "Remove repo",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    addon.id,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EcosystemChip("Active", ColourStremio)
            if (!addon.version.isNullOrBlank()) EcosystemChip("v${addon.version}", ColourStremio)
            EcosystemChip("Stremio", MaterialTheme.colorScheme.onSurfaceVariant)
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
    plugins: List<CloudStreamPlugin>,
    isLoading: Boolean,
    installingNames: Set<String>,
    installedNames: Set<String>,
    onFetch: () -> Unit,
    onRemove: () -> Unit,
    onInstall: (CloudStreamPlugin) -> Unit,
    onUninstall: (CloudStreamPlugin) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ColourCloudStream.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Cloud, null, tint = ColourCloudStream, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    repo.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    repo.url,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onFetch) {
                    Icon(Icons.Default.Refresh, "Fetch", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, "Remove repo", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
        if (plugins.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${plugins.size} plugins",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ColourCloudStream,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.Tune else Icons.Default.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                plugins.forEach { plugin ->
                    val internalKey = plugin.internalName ?: plugin.name
                    PluginRow(
                        plugin      = plugin,
                        installing  = plugin.name in installingNames,
                        installed   = internalKey in installedNames,
                        onInstall   = { onInstall(plugin) },
                        onUninstall = { onUninstall(plugin) },
                    )
                }
            }
        } else if (!isLoading) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EcosystemChip("Tap refresh to load plugins", ColourCloudStream)
            }
        }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EcosystemChip("v${plugin.version}", ColourCloudStream)
                plugin.language?.let { EcosystemChip(it, MaterialTheme.colorScheme.onSurfaceVariant) }
                plugin.tvTypes?.firstOrNull()?.let { EcosystemChip(it, MaterialTheme.colorScheme.onSurfaceVariant) }
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Provider Tester") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The Dark Knight",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "2008 · tt0468569 · TMDB 155",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "A provider passes if it returns ≥ 1 stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (results.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (passed  > 0) TesterScorePill("$passed passed",  Color(0xFF4CAF50))
                if (empty   > 0) TesterScorePill("$empty empty",    Color(0xFFFF9800))
                if (failed  > 0) TesterScorePill("$failed failed",  MaterialTheme.colorScheme.error)
                if (pending > 0) TesterScorePill("$pending pending", MaterialTheme.colorScheme.onSurfaceVariant)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
            }
        }
    }
}
