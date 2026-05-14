package com.aioweb.app.ui.screens

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aioweb.app.data.plugins.CloudStreamPlugin
import com.aioweb.app.data.plugins.CloudStreamRepo
import com.aioweb.app.data.plugins.InstalledPlugin
import com.aioweb.app.ui.viewmodel.PluginsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: PluginsViewModel = viewModel(factory = PluginsViewModel.factory(context))
    val state by vm.state.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addUrl by remember { mutableStateOf("") }

    var showAddStremio by remember { mutableStateOf(false) }
    var stremioUrl by remember { mutableStateOf("") }

    var showAddNuvio by remember { mutableStateOf(false) }
    var nuvioRepoInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("CloudStream Plugins") },
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
            state.info?.let {
                item {
                    StatusBanner(it, isError = false) { vm.clearMessages() }
                }
            }
            state.error?.let {
                item {
                    StatusBanner(it, isError = true) { vm.clearMessages() }
                }
            }

            if (state.installed.isNotEmpty()) {
                item {
                    SectionLabel("Installed (${state.installed.size})")
                }
                items(
                    state.installed,
                    key = { p -> "inst_${p.sourceRepoId}_${p.internalName}_${p.installedAt}" },
                ) { p ->
                    InstalledRow(p, onUninstall = { vm.uninstall(p) })
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // ── Stremio addons ────────────────────────────
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Stremio addons (${state.stremioAddons.size})")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showAddStremio = true }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add addon")
                    }
                }
            }
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
                            "Paste a Stremio addon manifest URL (or any addon's homepage). " +
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
            item { Spacer(Modifier.height(8.dp)) }

            // ── Nuvio JS providers ────────────────────────────
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Nuvio providers (${state.nuvioProviders.size})")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showAddNuvio = true }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Browse repo")
                    }
                }
            }
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
                            "Paste a Nuvio provider repo URL (the one with manifest.json listing .js files). " +
                                "Examples:\n" +
                                "• https://raw.githubusercontent.com/yoruix/nuvio-providers/main/\n" +
                                "• https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/main/\n\n" +
                                "Nuvio providers run inside an embedded JavaScript engine (Mozilla " +
                                "Rhino) — no native libraries needed. Some advanced providers may not " +
                                "be fully compatible.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.nuvioProviders, key = { it.id }) { p ->
                NuvioProviderRow(p, onRemove = { vm.uninstallNuvioProvider(p.id) })
            }
            item { Spacer(Modifier.height(8.dp)) }

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
                            "Tap the + icon and paste a CloudStream `repo.json` URL to get started. " +
                                    "Popular community lists:\n" +
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
                    repo = repo,
                    plugins = state.pluginsByRepo[repo.id].orEmpty(),
                    isLoading = state.loadingRepoIds.contains(repo.id),
                    installingNames = state.installingNames,
                    installedNames = state.installed.map { it.internalName }.toSet(),
                    onFetch = { vm.fetchRepo(repo) },
                    onRemove = { vm.removeRepo(repo.id) },
                    onInstall = { vm.install(repo, it) },
                    onUninstall = { plugin ->
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
                        "Tip: paste a CloudStream `repo.json` URL — e.g., the Cloudstream community extensions list, or any community fork.",
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
            }
        )
    }

    if (showAddStremio) {
        AlertDialog(
            onDismissRequest = { showAddStremio = false },
            title = { Text("Add Stremio addon") },
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
                        "Paste any Stremio addon manifest URL. Examples that just work:\n" +
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
                        stremioUrl = ""; showAddStremio = false
                    },
                ) {
                    if (state.addingStremio) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddStremio = false }) { Text("Cancel") }
            },
        )
    }

    if (showAddNuvio) {
        AlertDialog(
            onDismissRequest = { showAddNuvio = false },
            title = { Text("Browse Nuvio repo") },
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
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { vm.loadNuvioRepo(nuvioRepoInput) }) {
                            if (state.loadingNuvioRepo) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Text("Load")
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
                            Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            mf.allProviders.forEach { entry ->
                                val installing = entry.id in state.installingNuvioIds
                                val already = state.nuvioProviders.any { it.id == entry.id }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                        if (!entry.description.isNullOrBlank()) {
                                            Text(
                                                entry.description, maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    when {
                                        installing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        already -> Icon(
                                            Icons.Default.CheckCircle,
                                            "Installed",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        else -> TextButton(onClick = { vm.installNuvioProvider(entry) }) {
                                            Text("Install")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddNuvio = false; nuvioRepoInput = "" }) { Text("Done") }
            },
        )
    }
}

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
                else MaterialTheme.colorScheme.primaryContainer
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
            Text(p.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("v${p.version} · ${p.internalName}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = onUninstall) {
            Icon(Icons.Default.Delete, "Uninstall", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NuvioProviderRow(
    p: com.aioweb.app.data.nuvio.InstalledNuvioProvider,
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
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.name, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Nuvio provider · JS",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StremioAddonRow(
    addon: com.aioweb.app.data.stremio.InstalledStremioAddon,
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
                addon.name, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Stremio · ${addon.id}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                Text(repo.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Text(repo.url, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                modifier = Modifier.clickable { expanded = !expanded }
            )
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                plugins.forEach { plugin ->
                    val internalKey = plugin.internalName ?: plugin.name
                    PluginRow(
                        plugin = plugin,
                        installing = plugin.name in installingNames,
                        installed = internalKey in installedNames,
                        onInstall = { onInstall(plugin) },
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
            Text(plugin.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            plugin.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("v${plugin.version} · ${plugin.language ?: "?"} · ${plugin.tvTypes?.joinToString() ?: ""}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
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
