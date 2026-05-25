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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcloud.app.data.plugins.CloudStreamPlugin
import com.streamcloud.app.data.plugins.CloudStreamRepo
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.ui.viewmodel.PluginsState
import com.streamcloud.app.ui.viewmodel.PluginsViewModel

private enum class PluginsPage { CloudStream, Stremio, Nuvio }

private val ColourCloudStream = Color(0xFF5B8DEF)
private val ColourStremio     = Color(0xFF9B6CE0)
private val ColourNuvio       = Color(0xFF4CAF88)
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
                onClick     = onCloudStream,
            )

            AddonHubCard(
                icon        = Icons.Default.Cast,
                iconTint    = ColourStremio,
                title       = "Stremio",
                description = "Add Stremio-compatible addons via manifest URL. " +
                    "Works with Torrentio, Cinemeta, and more.",
                badgeText   = if (stremioCount > 0) "$stremioCount addons" else null,
                onClick     = onStremio,
            )

            AddonHubCard(
                icon        = Icons.Default.Extension,
                iconTint    = ColourNuvio,
                title       = "Nuvio",
                description = "Browse and install Nuvio JavaScript providers " +
                    "from community repositories.",
                badgeText   = if (nuvioCount > 0) "$nuvioCount providers" else null,
                onClick     = onNuvio,
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
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(28.dp))
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

// ─── CloudStream sub-screen ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudStreamPluginsPage(
    vm: PluginsViewModel,
    state: PluginsState,
    onBack: () -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addUrl  by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("CloudStream") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, "Add repo")
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.info?.let  { item { StatusBanner(it, isError = false) { vm.clearMessages() } } }
            state.error?.let { item { StatusBanner(it, isError = true)  { vm.clearMessages() } } }

            if (state.installed.isNotEmpty()) {
                item { SectionLabel("Installed (${state.installed.size})") }
                items(
                    state.installed,
                    key = { p -> "inst_${p.sourceRepoId}_${p.internalName}_${p.installedAt}" },
                ) { p ->
                    InstalledRow(p, onUninstall = { vm.uninstall(p) })
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            item { SectionLabel("Repositories") }

            if (state.repos.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(20.dp),
                    ) {
                        Text(
                            "No repositories yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tap + and paste a CloudStream repo.json URL to get started.\n\n" +
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

            items(state.repos, key = { it.id }) { repo ->
                RepoCard(
                    repo             = repo,
                    plugins          = state.pluginsByRepo[repo.id].orEmpty(),
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

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add CloudStream Repository") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = addName, onValueChange = { addName = it },
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = addUrl, onValueChange = { addUrl = it },
                        label = { Text("repo.json URL") },
                        placeholder = { Text("https://example.com/repo.json") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Paste a CloudStream repo.json URL — e.g., the community extensions list or any community fork.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addRepo(addName, addUrl)
                    addName = ""; addUrl = ""; showAdd = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }
}

// ─── Stremio sub-screen ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StremioAddonsPage(
    vm: PluginsViewModel,
    state: PluginsState,
    onBack: () -> Unit,
) {
    var showAdd    by remember { mutableStateOf(false) }
    var stremioUrl by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Stremio Addons") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, "Add addon")
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.info?.let  { item { StatusBanner(it, isError = false) { vm.clearMessages() } } }
            state.error?.let { item { StatusBanner(it, isError = true)  { vm.clearMessages() } } }

            item { SectionLabel("Installed addons (${state.stremioAddons.size})") }

            if (state.stremioAddons.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                    ) {
                        Text(
                            "No Stremio addons yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tap + and paste any Stremio addon manifest URL.\n\n" +
                                "Examples:\n" +
                                "• https://v3-cinemeta.strem.io/manifest.json\n" +
                                "• https://torrentio.strem.fun/manifest.json\n" +
                                "• https://nuviostreams.hayd.uk/manifest.json (NuvioStreams)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(state.stremioAddons, key = { it.manifestUrl }) { addon ->
                StremioAddonRow(addon, onRemove = { vm.removeStremioAddon(addon.manifestUrl) })
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Stremio Addon") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = stremioUrl, onValueChange = { stremioUrl = it },
                        label = { Text("Manifest URL") },
                        placeholder = { Text("https://your-addon.com/manifest.json") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Paste any Stremio addon manifest URL.\n\n" +
                            "Examples that work right away:\n" +
                            "• https://v3-cinemeta.strem.io/manifest.json\n" +
                            "• https://torrentio.strem.fun/manifest.json\n" +
                            "• https://nuviostreams.hayd.uk/manifest.json",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.addingStremio,
                    onClick = {
                        vm.addStremioAddon(stremioUrl)
                        stremioUrl = ""
                        showAdd = false
                    },
                ) {
                    if (state.addingStremio)
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else
                        Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }
}

// ─── Nuvio sub-screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NuvioProvidersPage(
    vm: PluginsViewModel,
    state: PluginsState,
    onBack: () -> Unit,
) {
    var showBrowse     by remember { mutableStateOf(false) }
    var nuvioRepoInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nuvio Providers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showBrowse = true }) {
                        Icon(Icons.Default.Add, "Browse repo")
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.info?.let  { item { StatusBanner(it, isError = false) { vm.clearMessages() } } }
            state.error?.let { item { StatusBanner(it, isError = true)  { vm.clearMessages() } } }

            item { SectionLabel("Installed providers (${state.nuvioProviders.size})") }

            if (state.nuvioProviders.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                    ) {
                        Text(
                            "No Nuvio providers installed",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tap + to browse a Nuvio provider repository.\n\n" +
                                "Popular repos:\n" +
                                "• https://raw.githubusercontent.com/yoruix/nuvio-providers/main/\n" +
                                "• https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/main/\n\n" +
                                "Nuvio providers run inside an embedded JavaScript engine (QuickJS) — " +
                                "no native libraries needed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(state.nuvioProviders, key = { it.id }) { p ->
                NuvioProviderRow(p, onRemove = { vm.uninstallNuvioProvider(p.id) })
            }
        }
    }

    if (showBrowse) {
        AlertDialog(
            onDismissRequest = { showBrowse = false; nuvioRepoInput = "" },
            title = { Text("Browse Nuvio Repo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nuvioRepoInput, onValueChange = { nuvioRepoInput = it },
                        label = { Text("Repo URL") },
                        placeholder = { Text("https://raw.githubusercontent.com/yoruix/nuvio-providers/main/") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { vm.loadNuvioRepo(nuvioRepoInput) }) {
                            if (state.loadingNuvioRepo)
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else
                                Text("Load")
                        }
                    }
                    val mf = state.nuvioRepoManifest
                    if (mf != null) {
                        Text(
                            "${mf.name ?: "Repo"} · ${mf.allProviders.size} providers",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Column(
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            mf.allProviders.forEach { entry ->
                                val installing = entry.id in state.installingNuvioIds
                                val already    = state.nuvioProviders.any { it.id == entry.id }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.name,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        if (!entry.description.isNullOrBlank()) {
                                            Text(
                                                entry.description,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    when {
                                        installing -> CircularProgressIndicator(
                                            Modifier.size(18.dp), strokeWidth = 2.dp,
                                        )
                                        already -> Icon(
                                            Icons.Default.CheckCircle, "Installed",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        else -> TextButton(
                                            onClick = { vm.installNuvioProvider(entry) },
                                        ) { Text("Install") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBrowse = false; nuvioRepoInput = "" }) {
                    Text("Done")
                }
            },
        )
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
private fun InstalledRow(p: InstalledPlugin, onUninstall: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        if (!p.iconUrl.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = p.iconUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Icon(
                Icons.Default.Extension, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "v${p.version} · ${p.internalName}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(onClick = onUninstall) {
            Icon(Icons.Default.Delete, "Uninstall", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NuvioProviderRow(
    p: com.streamcloud.app.data.nuvio.InstalledNuvioProvider,
    onRemove: () -> Unit,
) {
    val lastError = com.streamcloud.app.data.nuvio.NuvioRuntime.lastError(p.id)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        if (!p.logo.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = p.logo,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Icon(
                Icons.Default.Extension, null,
                tint = if (lastError != null) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lastError != null) {
                Text(
                    lastError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "Nuvio provider · JS",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StremioAddonRow(
    addon: com.streamcloud.app.data.stremio.InstalledStremioAddon,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        if (!addon.logo.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = addon.logo,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Icon(
                Icons.Default.Extension, null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                addon.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Stremio · ${addon.id}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
        }
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
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    repo.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    repo.url,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onFetch) {
                    Icon(Icons.Default.Refresh, "Fetch")
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, "Remove repo", tint = MaterialTheme.colorScheme.error)
            }
        }
        if (plugins.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${plugins.size} plugins · tap to ${if (expanded) "collapse" else "expand"}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { expanded = !expanded },
            )
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        if (!plugin.iconUrl.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = plugin.iconUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                plugin.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            plugin.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "v${plugin.version} · ${plugin.language ?: "?"} · ${plugin.tvTypes?.joinToString() ?: ""}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when {
            installing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            installed -> IconButton(onClick = onUninstall) {
                Icon(Icons.Default.Delete, "Uninstall", tint = MaterialTheme.colorScheme.error)
            }
            else -> IconButton(onClick = onInstall) {
                Icon(Icons.Default.CloudDownload, "Install", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
