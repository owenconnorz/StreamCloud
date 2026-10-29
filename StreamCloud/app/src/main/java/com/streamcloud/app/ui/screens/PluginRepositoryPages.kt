package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamcloud.app.data.plugins.CloudStreamPlugin
import com.streamcloud.app.data.plugins.CloudStreamRepo
import com.streamcloud.app.data.stremio.StremioSavedRepo
import com.streamcloud.app.data.nuvio.NuvioSavedRepo
import com.streamcloud.app.ui.icons.AppIcons as Icons
import com.streamcloud.app.ui.viewmodel.PluginsState
import com.streamcloud.app.ui.viewmodel.PluginsViewModel

private val CloudStreamBlue = Color(0xFF2684FF)
private val NuvioGreen = Color(0xFF4CAF88)
private val StremioPurple = Color(0xFF9B6CE0)

internal object PluginUiPalette {
    val background = Color(0xFF0B1727)
    val card = Color(0xFF1B2B42)
    val cardRaised = Color(0xFF223650)
    val border = Color(0xFF35557A)
    val text = Color(0xFFF2F6FC)
    val muted = Color(0xFFAFC1D8)
    val blue = Color(0xFF1678F5)
}

@Composable
internal fun AddNamedRepositoryDialog(
    title: String,
    description: String,
    urlLabel: String,
    urlPlaceholder: String,
    saveLabel: String,
    accent: Color,
    name: String,
    url: String,
    busy: Boolean,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 760.dp),
            shape = RoundedCornerShape(26.dp),
            color = PluginUiPalette.background,
            border = BorderStroke(1.dp, PluginUiPalette.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(PluginUiPalette.cardRaised),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            title,
                            color = PluginUiPalette.text,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            description,
                            color = PluginUiPalette.muted,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(50))
                            .background(PluginUiPalette.cardRaised),
                    ) {
                        Text("×", color = PluginUiPalette.text, fontSize = 30.sp, lineHeight = 30.sp)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Display name") },
                    placeholder = { Text("Enter display name") },
                    leadingIcon = { Icon(Icons.Default.Extension, null, tint = PluginUiPalette.muted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    colors = repositoryFieldColors(accent),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text(urlLabel) },
                    placeholder = { Text(urlPlaceholder) },
                    leadingIcon = { Icon(Icons.Default.Cloud, null, tint = PluginUiPalette.muted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = repositoryFieldColors(accent),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(58.dp),
                        shape = RoundedCornerShape(30.dp),
                        border = BorderStroke(1.dp, PluginUiPalette.border),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PluginUiPalette.text),
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.titleMedium)
                    }
                    Button(
                        onClick = onSave,
                        enabled = name.isNotBlank() && url.isNotBlank() && !busy,
                        modifier = Modifier.weight(1f).height(58.dp),
                        shape = RoundedCornerShape(30.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PluginUiPalette.blue,
                            contentColor = Color.White,
                        ),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "Checking…" else saveLabel, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun repositoryFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PluginUiPalette.text,
    unfocusedTextColor = PluginUiPalette.text,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedBorderColor = accent,
    unfocusedBorderColor = PluginUiPalette.border,
    focusedLabelColor = PluginUiPalette.muted,
    unfocusedLabelColor = PluginUiPalette.muted,
    focusedPlaceholderColor = PluginUiPalette.muted,
    unfocusedPlaceholderColor = PluginUiPalette.muted,
    cursorColor = accent,
)

@Composable
internal fun SavedRepositoryRow(
    name: String,
    url: String,
    detail: String,
    logoUrl: String?,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .border(1.dp, PluginUiPalette.border, RoundedCornerShape(15.dp))
            .background(PluginUiPalette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!logoUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)),
                )
            } else {
                Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = PluginUiPalette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = PluginUiPalette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "Remove repository", tint = MaterialTheme.colorScheme.error)
        }
        Icon(Icons.Default.ChevronRight, null, tint = PluginUiPalette.muted)
    }
}

@Composable
internal fun RepositoryEmptyCard(title: String, message: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, PluginUiPalette.border, RoundedCornerShape(14.dp))
            .background(PluginUiPalette.card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(title, color = PluginUiPalette.text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        Text(message, style = MaterialTheme.typography.bodySmall, color = PluginUiPalette.muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudStreamRepositoryPage(
    repo: CloudStreamRepo,
    state: PluginsState,
    vm: PluginsViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(repo.id) {
        if (state.pluginsByRepo[repo.id] == null) vm.fetchRepo(repo)
    }
    val plugins = state.pluginsByRepo[repo.id].orEmpty()
    var query by remember(repo.id) { mutableStateOf("") }
    var selectedFilter by remember(repo.id) { mutableStateOf("All") }
    val filters = listOf("All", "Movies", "TV Shows", "Adult", "Anime")
    val filtered = remember(plugins, query, selectedFilter) {
        plugins.filter { plugin ->
            val matchesQuery = query.isBlank() ||
                plugin.name.contains(query, ignoreCase = true) ||
                plugin.description.orEmpty().contains(query, ignoreCase = true)
            matchesQuery && matchesCloudStreamCategory(plugin, selectedFilter)
        }
    }
    val installed = remember(state.installed) { state.installed.map { it.internalName }.toSet() }
    val pluginCounts = remember(plugins) {
        filters.associateWith { category ->
            plugins.count { matchesCloudStreamCategory(it, category) }
        }
    }
    var showRepositoryMenu by remember(repo.id) { mutableStateOf(false) }

    Scaffold(
        containerColor = PluginUiPalette.background,
        topBar = {
            TopAppBar(
                title = { Text("Repository", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRepositoryMenu = true }) {
                        Text("⋮", color = PluginUiPalette.text, fontSize = 24.sp)
                    }
                    DropdownMenu(
                        expanded = showRepositoryMenu,
                        onDismissRequest = { showRepositoryMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Refresh repository") },
                            onClick = {
                                showRepositoryMenu = false
                                vm.fetchRepo(repo)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove repository") },
                            onClick = {
                                showRepositoryMenu = false
                                vm.removeRepo(repo.id)
                                onBack()
                            },
                        )
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                RepositoryHeader(
                    name = repo.name,
                    url = repo.url,
                    subtitle = if (state.loadingRepoIds.contains(repo.id)) "Loading plugins…"
                        else "${plugins.size} plugins available",
                    icon = Icons.Default.Cloud,
                    accent = CloudStreamBlue,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search plugins in this repository…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(14.dp),
                    colors = repositoryFieldColors(CloudStreamBlue),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(filters) { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text("$filter (${pluginCounts[filter] ?: 0})") },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CloudStreamBlue,
                                selectedLabelColor = Color.White,
                                containerColor = PluginUiPalette.card,
                                labelColor = PluginUiPalette.muted,
                            ),
                        )
                    }
                }
            }
            if (state.loadingRepoIds.contains(repo.id)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (state.error != null && plugins.isEmpty()) {
                item { RepositoryMessage(state.error, isError = true, accent = CloudStreamBlue) }
                item {
                    Button(onClick = { vm.fetchRepo(repo) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry")
                    }
                }
            } else {
                item { RepositorySectionTitle("Available plugins (${filtered.size})", CloudStreamBlue) }
                if (filtered.isEmpty()) {
                    item {
                        RepositoryMessage(
                            if (plugins.isEmpty()) "No plugins are listed in this repository."
                            else "No plugins match this search or filter.",
                            isError = false,
                            accent = CloudStreamBlue,
                        )
                    }
                } else {
                    items(filtered, key = { "${repo.id}_${it.internalName ?: it.name}" }) { plugin ->
                        CloudStreamPluginInstallRow(
                            plugin = plugin,
                            installing = plugin.name in state.installingNames,
                            installed = (plugin.internalName ?: plugin.name) in installed,
                            onInstall = { vm.install(repo, plugin) },
                            onUninstall = {
                                val key = plugin.internalName ?: plugin.name
                                state.installed.firstOrNull { it.internalName == key }?.let(vm::uninstall)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NuvioRepositoryPage(
    repo: NuvioSavedRepo,
    state: PluginsState,
    vm: PluginsViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(repo.url) { vm.loadNuvioRepo(repo.url) }
    val manifest = state.nuvioRepoManifest.takeIf { state.nuvioRepoUrl == repo.url }
    var query by remember(repo.url) { mutableStateOf("") }
    var selectedFilter by remember(repo.url) { mutableStateOf("All") }
    val filters = listOf("All", "Movies", "TV Shows", "Anime", "Adult")
    val installedIds = remember(state.nuvioProviders) { state.nuvioProviders.map { it.id }.toSet() }
    val filterCounts = remember(manifest) {
        filters.associateWith { filter ->
            manifest?.allProviders.orEmpty().count { entry ->
                matchesNuvioCategory("${entry.name} ${entry.description.orEmpty()}".lowercase(), filter)
            }
        }
    }
    val providers = remember(manifest, query, selectedFilter) {
        manifest?.allProviders.orEmpty().filter { entry ->
            val metadata = "${entry.name} ${entry.description.orEmpty()}".lowercase()
            val matchesQuery = query.isBlank() || metadata.contains(query.lowercase())
            matchesQuery && matchesNuvioCategory(metadata, selectedFilter)
        }
    }

    Scaffold(
        containerColor = PluginUiPalette.background,
        topBar = {
            TopAppBar(
                title = { Text("Repository", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                RepositoryHeader(
                    name = repo.name ?: manifest?.name ?: "Nuvio repository",
                    url = repo.url,
                    subtitle = if (state.loadingNuvioRepo) "Loading providers…"
                        else "${manifest?.allProviders?.size ?: 0} providers available",
                    icon = Icons.Default.Cloud,
                    accent = NuvioGreen,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search providers in this repository…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(14.dp),
                    colors = repositoryFieldColors(NuvioGreen),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(filters) { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text("$filter (${filterCounts[filter] ?: 0})") },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NuvioGreen,
                                selectedLabelColor = Color.White,
                                containerColor = PluginUiPalette.card,
                                labelColor = PluginUiPalette.muted,
                            ),
                        )
                    }
                }
            }
            if (state.loadingNuvioRepo) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(color = NuvioGreen) }
                }
            } else if (state.error != null && manifest == null) {
                item { RepositoryMessage(state.error, isError = true, accent = NuvioGreen) }
                item {
                    Button(
                        onClick = { vm.loadNuvioRepo(repo.url) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NuvioGreen),
                    ) { Text("Retry") }
                }
            } else {
                item {
                    RepositorySectionTitle(
                        "Available providers (${providers.size})",
                        NuvioGreen,
                    )
                }
                if (providers.isEmpty()) {
                    item {
                        RepositoryMessage(
                            if (manifest?.allProviders.isNullOrEmpty()) "This repository has no providers."
                            else "No providers match this search or filter.",
                            isError = false,
                            accent = NuvioGreen,
                        )
                    }
                } else {
                    items(providers, key = { "nuvio_${it.id}" }) { entry ->
                        NuvioProviderInstallRow(
                            name = entry.name,
                            description = entry.description,
                            logo = entry.logo ?: entry.icon,
                            version = entry.version,
                            accent = NuvioGreen,
                            installing = entry.id in state.installingNuvioIds,
                            installed = entry.id in installedIds,
                            onInstall = { vm.installNuvioProvider(entry) },
                            onUninstall = { vm.uninstallNuvioProvider(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun matchesCloudStreamCategory(plugin: CloudStreamPlugin, category: String): Boolean {
    if (category == "All") return true
    val metadata = buildString {
        append(plugin.name)
        append(' ')
        append(plugin.internalName.orEmpty())
        append(' ')
        append(plugin.description.orEmpty())
        append(' ')
        append(plugin.tvTypes.orEmpty().joinToString(" "))
    }.lowercase()
    return when (category) {
        "Movies" -> plugin.tvTypes.orEmpty().any { it.contains("movie", ignoreCase = true) } ||
            metadata.contains("movie") || metadata.contains("film")
        "TV Shows" -> plugin.tvTypes.orEmpty().any {
            it.contains("tv", ignoreCase = true) || it.contains("series", ignoreCase = true) ||
                it.contains("show", ignoreCase = true)
        } || listOf("series", "tv show", "episode").any(metadata::contains)
        "Adult" -> listOf("adult", "porn", "xxx", "nsfw", "erotic", "xnxx", "xhamster", "pornhub")
            .any(metadata::contains)
        "Anime" -> metadata.contains("anime")
        else -> true
    }
}

private fun matchesNuvioCategory(metadata: String, category: String): Boolean = when (category) {
    "Movies" -> metadata.contains("movie") || metadata.contains("film")
    "TV Shows" -> listOf("series", "tv show", "episode", "tv").any(metadata::contains)
    "Anime" -> metadata.contains("anime")
    "Adult" -> listOf("adult", "porn", "xxx", "nsfw", "erotic", "xnxx", "xhamster", "pornhub")
        .any(metadata::contains)
    else -> true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StremioRepositoryPage(
    repo: StremioSavedRepo,
    state: PluginsState,
    vm: PluginsViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(repo.manifestUrl) { vm.fetchStremioRepo(repo) }
    val manifest = state.stremioRepoManifest.takeIf {
        state.stremioRepoManifestUrl == repo.manifestUrl
    }
    val catalogs = remember(manifest) {
        (manifest?.catalogs.orEmpty() + manifest?.catalogGroups.orEmpty().flatMap { it.catalogs })
            .distinctBy { "${it.type}:${it.id}" }
    }
    val installed = state.stremioAddons.any {
        it.manifestUrl.equals(repo.manifestUrl, ignoreCase = true)
    }
    Scaffold(
        containerColor = PluginUiPalette.background,
        topBar = {
            TopAppBar(
                title = { Text("Repository", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                RepositoryHeader(
                    name = repo.name,
                    url = repo.manifestUrl,
                    subtitle = manifest?.let { "v${it.version ?: "?"} · ${catalogs.size} catalogs" }
                        ?: "Stremio addon manifest",
                    icon = Icons.Default.Extension,
                    accent = StremioPurple,
                    logoUrl = manifest?.logo ?: manifest?.icon ?: repo.logo,
                )
            }
            if (state.loadingStremioRepo) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(color = StremioPurple) }
                }
            } else if (manifest == null) {
                item {
                    RepositoryMessage(
                        state.error ?: "Could not read this add-on manifest.",
                        isError = true,
                        accent = StremioPurple,
                    )
                }
                item {
                    Button(
                        onClick = { vm.fetchStremioRepo(repo) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = StremioPurple),
                    ) { Text("Retry") }
                }
            } else {
                state.error?.let { message ->
                    item { RepositoryMessage(message, isError = true, accent = StremioPurple) }
                }
                state.info?.let { message ->
                    item { RepositoryMessage(message, isError = false, accent = StremioPurple) }
                }
                item {
                    RepositoryMessage(
                        manifest.description?.takeIf(String::isNotBlank)
                            ?: "This Stremio manifest describes one add-on. Install it to add its available streams and catalogs.",
                        isError = false,
                        accent = StremioPurple,
                    )
                }
                item {
                    RepositorySectionTitle("Supported content", StremioPurple)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        manifest.types.distinct().forEach { type ->
                            RepositoryTag(type.replaceFirstChar(Char::uppercase), StremioPurple)
                        }
                        if (manifest.types.isEmpty()) RepositoryTag("Addon", StremioPurple)
                    }
                }
                item {
                    RepositorySectionTitle("Catalogs (${catalogs.size})", StremioPurple)
                }
                if (catalogs.isEmpty()) {
                    item {
                        RepositoryMessage(
                            "This add-on provides streams but does not publish browseable catalogs.",
                            isError = false,
                            accent = StremioPurple,
                        )
                    }
                } else {
                    items(catalogs, key = { "${it.type}_${it.id}" }) { catalog ->
                        RepositoryListInfoRow(
                            icon = Icons.Default.Extension,
                            accent = StremioPurple,
                            title = catalog.name ?: catalog.id,
                            subtitle = catalog.type.replaceFirstChar(Char::uppercase),
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            if (installed) {
                                vm.removeStremioAddon(repo.manifestUrl)
                            } else {
                                vm.addStremioAddon(repo.manifestUrl)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.addingStremio,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (installed) MaterialTheme.colorScheme.errorContainer
                            else StremioPurple,
                            contentColor = if (installed) MaterialTheme.colorScheme.onErrorContainer
                            else Color.White,
                        ),
                    ) {
                        if (state.addingStremio) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (installed) "Remove Add-on" else "Install Add-on")
                    }
                }
            }
        }
    }
}

@Composable
private fun RepositoryHeader(
    name: String,
    url: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    logoUrl: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, PluginUiPalette.border, RoundedCornerShape(16.dp))
            .background(PluginUiPalette.card)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!logoUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = PluginUiPalette.text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Text(url, style = MaterialTheme.typography.bodySmall, color = PluginUiPalette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = accent)
        }
    }
}

@Composable
private fun RepositorySectionTitle(text: String, accent: Color) {
    Text(
        text,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = PluginUiPalette.text,
    )
}

@Composable
private fun RepositoryMessage(text: String, isError: Boolean, accent: Color) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isError) MaterialTheme.colorScheme.errorContainer
                else PluginUiPalette.card,
            )
            .padding(14.dp),
        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
        else PluginUiPalette.muted,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun RepositoryTag(text: String, accent: Color) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = accent,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun RepositoryListInfoRow(icon: ImageVector, accent: Color, title: String, subtitle: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PluginUiPalette.card)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = PluginUiPalette.text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PluginUiPalette.muted)
        }
    }
}

@Composable
private fun CloudStreamPluginInstallRow(
    plugin: CloudStreamPlugin,
    installing: Boolean,
    installed: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val categoryTags = when {
        matchesCloudStreamCategory(plugin, "Adult") -> listOf("Adult")
        else -> plugin.tvTypes.orEmpty().take(2)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, PluginUiPalette.border, RoundedCornerShape(14.dp))
            .background(PluginUiPalette.card)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PluginLogo(plugin.name, plugin.iconUrl, CloudStreamBlue)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(plugin.name, color = PluginUiPalette.text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                plugin.description ?: "v${plugin.version} · ${plugin.internalName ?: plugin.name}",
                style = MaterialTheme.typography.bodySmall,
                color = PluginUiPalette.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                RepositoryTag("v${plugin.version}", CloudStreamBlue)
                categoryTags.forEach { RepositoryTag(it, CloudStreamBlue) }
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            installing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            installed -> TextButton(onClick = onUninstall) { Text("Remove") }
            else -> Button(
                onClick = onInstall,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CloudStreamBlue),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Install")
            }
        }
    }
}

@Composable
private fun NuvioProviderInstallRow(
    name: String,
    description: String?,
    logo: String?,
    version: String?,
    accent: Color,
    installing: Boolean,
    installed: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val metadata = "$name ${description.orEmpty()}".lowercase()
    val categoryTags = listOf("Movies", "TV Shows", "Anime", "Adult")
        .filter { matchesNuvioCategory(metadata, it) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, PluginUiPalette.border, RoundedCornerShape(14.dp))
            .background(PluginUiPalette.card)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PluginLogo(name, logo, accent)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = PluginUiPalette.text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = PluginUiPalette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (!version.isNullOrBlank()) RepositoryTag("v$version", accent)
                categoryTags.take(2).forEach { RepositoryTag(it, accent) }
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            installing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent)
            installed -> TextButton(onClick = onUninstall) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            else -> Button(
                onClick = onInstall,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Install")
            }
        }
    }
}

@Composable
private fun PluginLogo(name: String, logoUrl: String?, accent: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!logoUrl.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Text(name.firstOrNull()?.uppercaseChar()?.toString() ?: "?", color = accent, fontWeight = FontWeight.Bold)
        }
    }
}
