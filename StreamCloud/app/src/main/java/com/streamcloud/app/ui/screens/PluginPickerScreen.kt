package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import coil.compose.AsyncImage
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.ui.icons.AppIcons as Icons
import com.streamcloud.app.ui.viewmodel.MoviesViewModel
import kotlinx.coroutines.launch

private val SourcePickerBackground = Color(0xFF0D192B)
private val SourcePickerCard = Color(0xFF1B2B45)
private val SourcePickerInput = Color(0xFF1A2B47)
private val SourcePickerAccent = Color(0xFF4D70E8)

private enum class SourceFilter(
    val label: String,
    val icon: ImageVector?,
) {
    ALL("All", Icons.Default.Category),
    MOVIES("Movies", Icons.Default.Movie),
    TV("TV Shows", Icons.Default.Tv),
    ANIME("Anime", Icons.Default.Theaters),
    ADULT("Adult", null),
    FAVOURITES("Favourites", Icons.Default.FavoriteBorder),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PluginPickerScreen(
    onBack: () -> Unit,
    onOpenPlugin: (internalName: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: MoviesViewModel = viewModel(factory = MoviesViewModel.factory(context))
    val state by vm.state.collectAsState()
    val pluginRepository = remember(context) {
        PluginRepository(context.applicationContext)
    }
    val favourites by pluginRepository.pickerFavorites.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()

    var search by rememberSaveable { mutableStateOf("") }
    var selectedFilterName by rememberSaveable { mutableStateOf(SourceFilter.ALL.name) }
    val selectedFilter = SourceFilter.entries.firstOrNull { it.name == selectedFilterName }
        ?: SourceFilter.ALL
    val visiblePlugins = remember(state.installedPlugins, search, selectedFilter, favourites) {
        state.installedPlugins
            .asSequence()
            .filter { it.matchesPickerFilter(selectedFilter, favourites) }
            .filter { plugin ->
                search.isBlank() ||
                    plugin.name.contains(search, ignoreCase = true) ||
                    plugin.internalName.contains(search, ignoreCase = true) ||
                    plugin.tvTypes.orEmpty().any { it.contains(search, ignoreCase = true) }
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sources", fontWeight = FontWeight.Bold)
                        Text(
                            "Browse and manage providers",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAFBBD0),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFE7EDFA),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SourcePickerBackground,
                    titleContentColor = Color.White,
                ),
            )
        },
        containerColor = SourcePickerBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp)),
                placeholder = { Text("Search sources", color = Color(0xFFAFBBD0)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFFCFD8E9))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SourcePickerInput,
                    unfocusedContainerColor = SourcePickerInput,
                    disabledContainerColor = SourcePickerInput,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Color.White,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(SourceFilter.entries.toList(), key = { it.name }) { filter ->
                    SourceFilterButton(
                        filter = filter,
                        selected = selectedFilter == filter,
                        onClick = { selectedFilterName = filter.name },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (state.installedPlugins.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color(0xFF8DA1C0),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "No sources installed",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Install CloudStream plugins in Settings → Plugins.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFAFBBD0),
                        )
                    }
                }
            } else if (visiblePlugins.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            search.isNotBlank() -> "No sources match “$search”"
                            selectedFilter == SourceFilter.FAVOURITES -> "No favourite sources yet"
                            else -> "No ${selectedFilter.label.lowercase()} sources installed"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFAFBBD0),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = 4.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = visiblePlugins,
                        key = { "${it.sourceRepoId}:${it.internalName}" },
                    ) { plugin ->
                        SourcePluginRow(
                            plugin = plugin,
                            isFavourite = plugin.internalName in favourites,
                            onClick = { onOpenPlugin(plugin.internalName) },
                            onToggleFavourite = {
                                scope.launch {
                                    pluginRepository.togglePickerFavorite(plugin.internalName)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceFilterButton(
    filter: SourceFilter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) SourcePickerAccent else SourcePickerCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (filter == SourceFilter.ADULT) {
            Text(
                "18+",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        } else {
            Icon(
                imageVector = filter.icon ?: Icons.Default.Category,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = Color.White,
            )
        }
        Text(
            filter.label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SourcePluginRow(
    plugin: InstalledPlugin,
    isFavourite: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(SourcePickerCard)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF101D31)),
            contentAlignment = Alignment.Center,
        ) {
            if (!plugin.iconUrl.isNullOrBlank()) {
                AsyncImage(
                    model = plugin.iconUrl,
                    contentDescription = "${plugin.name} icon",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp)
                        .clip(RoundedCornerShape(9.dp)),
                )
            } else {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = Color(0xFF8DA1C0),
                    modifier = Modifier.size(27.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                plugin.name,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                plugin.pickerCategoryLabels().take(2).forEach { label ->
                    Text(
                        label,
                        color = Color(0xFFDDE6F6),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF30425F))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        maxLines = 1,
                    )
                }
            }
        }

        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
                tint = if (isFavourite) Color(0xFFFF7292) else Color(0xFFAFBBD0),
                modifier = Modifier.size(21.dp),
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "Open ${plugin.name}",
            tint = Color(0xFFD3DDF0),
            modifier = Modifier
                .size(23.dp)
                .padding(end = 2.dp),
        )
    }
}

private fun InstalledPlugin.matchesPickerFilter(
    filter: SourceFilter,
    favourites: Set<String>,
): Boolean {
    val types = tvTypes.orEmpty().map { it.lowercase().replace(Regex("[^a-z0-9]+"), "") }
    return when (filter) {
        SourceFilter.ALL -> true
        SourceFilter.MOVIES -> !isAdultPlugin() &&
            types.any { it.contains("movie") || it.contains("film") }
        SourceFilter.TV -> !isAdultPlugin() && types.any { type ->
            listOf("tv", "series", "show", "drama", "cartoon", "ova", "live").any(type::contains)
        }
        SourceFilter.ANIME -> !isAdultPlugin() && types.any { it.contains("anime") || it.contains("manga") }
        SourceFilter.ADULT -> isAdultPlugin()
        SourceFilter.FAVOURITES -> internalName in favourites
    }
}

private fun InstalledPlugin.pickerCategoryLabels(): List<String> {
    if (isAdultPlugin()) return listOf("Adult")
    return tvTypes.orEmpty().mapNotNull { raw ->
        val type = raw.lowercase().replace(Regex("[^a-z0-9]+"), "")
        when {
            type.contains("movie") || type.contains("film") -> "Movies"
            type.contains("anime") || type.contains("manga") -> "Anime"
            type.contains("tv") || type.contains("series") || type.contains("show") -> "TV Shows"
            type.contains("drama") -> "Drama"
            type.contains("cartoon") || type.contains("animation") -> "Animation"
            type.contains("live") -> "Live TV"
            raw.isNotBlank() -> raw.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .replaceFirstChar { it.uppercase() }
            else -> null
        }
    }.distinct().take(2).ifEmpty { listOf("CloudStream") }
}
