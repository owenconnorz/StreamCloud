package com.streamcloud.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.EpornerCategory
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.ui.screens.adult.RedditFeedView
import com.streamcloud.app.ui.viewmodel.AdultViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdultScreen(
    onPlay: (videoId: String, fallbackEmbed: String, title: String) -> Unit,
    screenTitle: String = "Adult",
    screenSubtitle: String = "",
) {
    val context = LocalContext.current
    val vm: AdultViewModel = viewModel(factory = AdultViewModel.factory(context))
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    var detailItem by remember { mutableStateOf<AdultItem?>(null) }
    var query by remember { mutableStateOf("") }
    var showCategoryPicker by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // Infinite scroll for Eporner grid: trigger loadMore when near the bottom
    LaunchedEffect(gridState) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val last  = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && last >= total - 6
        }.collect { reachedEnd -> if (reachedEnd && state.source == AdultSource.Eporner) vm.loadMore() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Title row (only shown for Eporner) ──────────────────────────
        if (state.source == AdultSource.Eporner) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        screenTitle,
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        screenSubtitle.ifBlank { "18+ \u00b7 ${state.source.label}" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.refresh() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Source switcher tabs: Eporner / Reddit ───────────────────────
        val sources = listOf(AdultSource.Eporner, AdultSource.Reddit)
        val selectedTabIndex = sources.indexOfFirst { it == state.source }.coerceAtLeast(0)
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
        ) {
            sources.forEachIndexed { index, source ->
                Tab(
                    selected = index == selectedTabIndex,
                    onClick  = { vm.setSource(source) },
                    text     = { Text(source.label) },
                )
            }
        }

        if (state.source == AdultSource.Reddit) {
            // ── Reddit swipe-up feed ─────────────────────────────────────
            RedditFeedView(
                vm         = vm,
                onPlayItem = { item ->
                    onPlay(item.id, item.streamUrl.orEmpty(), item.title)
                },
                modifier   = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            // ── Eporner: search field, categories, grid ──────────────────
            Spacer(Modifier.height(8.dp))

            // Search field
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; vm.search(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                placeholder = { Text("Search videos\u2026") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (state.loading) CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor   = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            Spacer(Modifier.height(8.dp))

            // Category + active-category chip row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showCategoryPicker = true },
                    shape   = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Category, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Categories")
                }

                state.selectedCategory?.let { cat ->
                    InputChip(
                        selected  = true,
                        onClick   = { vm.selectCategory(null); query = "" },
                        label     = { Text(cat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = "Clear category", Modifier.size(16.dp))
                        },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement   = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { v ->
                    AdultCard(v) { detailItem = v }
                }
                if (state.loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    }
                }
                if (!state.hasMore && state.items.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "No more results",
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .wrapContentWidth(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
        }
    }

    if (showCategoryPicker) {
        CategoryPickerSheet(
            categories       = state.categories,
            loadingCategories = state.loadingCategories,
            categorySearch   = state.categorySearch,
            selectedCategory = state.selectedCategory,
            onSearchChange   = { vm.setCategorySearch(it) },
            onSelect         = { cat ->
                vm.selectCategory(cat)
                query = ""
                showCategoryPicker = false
            },
            onDismiss        = { showCategoryPicker = false },
        )
    }

    detailItem?.let { item ->
        EpornerDetailSheet(
            item     = item,
            context  = context,
            onPlay   = {
                detailItem = null
                if (item.source == AdultSource.Reddit) {
                    // Reddit items: pass the stream URL directly as the embed
                    onPlay(item.id, item.streamUrl.orEmpty(), item.title)
                } else {
                    onPlay(item.epornerId ?: item.id, item.embedUrl.orEmpty(), item.title)
                }
            },
            onDismiss = { detailItem = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    categories: List<EpornerCategory>,
    loadingCategories: Boolean,
    categorySearch: String,
    selectedCategory: EpornerCategory?,
    onSearchChange: (String) -> Unit,
    onSelect: (EpornerCategory?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filtered = remember(categorySearch, categories) {
        if (categorySearch.isBlank()) categories
        else categories.filter { it.title.contains(categorySearch, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Categories",
                    style    = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color    = MaterialTheme.colorScheme.onSurface,
                )
                if (selectedCategory != null) {
                    TextButton(onClick = { onSelect(null) }) {
                        Text("Clear")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Category search field
            OutlinedTextField(
                value          = categorySearch,
                onValueChange  = onSearchChange,
                modifier       = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder    = { Text("Search categories\u2026") },
                singleLine     = true,
                leadingIcon    = { Icon(Icons.Default.Search, null) },
                trailingIcon   = {
                    if (categorySearch.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                shape          = RoundedCornerShape(14.dp),
                colors         = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor      = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor    = MaterialTheme.colorScheme.outline,
                    focusedContainerColor   = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            Spacer(Modifier.height(12.dp))

            when {
                loadingCategories -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
                categories.isEmpty() -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No categories available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                filtered.isEmpty() -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No categories match \"$categorySearch\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier            = Modifier.fillMaxWidth(),
                        contentPadding      = PaddingValues(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.id.ifBlank { it.title } }) { cat ->
                            val isSelected = selectedCategory?.id == cat.id
                            Surface(
                                modifier  = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelect(cat) },
                                color     = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                shape     = RoundedCornerShape(10.dp),
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        cat.title,
                                        style  = MaterialTheme.typography.bodyMedium,
                                        color  = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    if (cat.count > 0) {
                                        Text(
                                            formatCount(cat.count),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected)
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000     -> "%.1fK".format(n / 1_000.0)
    else           -> n.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpornerDetailSheet(
    item: AdultItem,
    context: Context,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope      = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        LibraryDb.get(context).watchlist()
            .isWatchlisted(epornerWatchlistId(item.id))
            .collect { saved = it }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            if (!item.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model              = item.thumbnail,
                    contentDescription = item.title,
                    contentScale       = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }

            Text(
                item.title,
                style    = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color    = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                item.durationLabel?.let { InfoPill(Icons.Default.Timer, it) }
                item.views?.let         { InfoPill(Icons.Default.Visibility, "$it views") }
                item.rating?.let        { InfoPill(Icons.Default.Star, it) }
            }

            item.tags?.takeIf { it.isNotBlank() }?.let { tagStr ->
                val tags = tagStr.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(14)
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding        = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(tags) { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                tag,
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick  = onPlay,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick  = {
                        scope.launch {
                            val db  = LibraryDb.get(context)
                            val wid = epornerWatchlistId(item.id)
                            if (saved) {
                                db.watchlist().remove(wid)
                            } else {
                                db.watchlist().add(
                                    WatchlistEntity(
                                        tmdbId    = wid,
                                        title     = item.title,
                                        posterUrl = item.thumbnail,
                                        mediaType = "eporner",
                                        csPlugin  = "eporner",
                                        csUrl     = item.embedUrl.orEmpty(),
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint     = if (saved) Color(0xFF7C5CFF) else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (saved) "Saved" else "Save")
                }
            }
        }
    }
}

@Composable
private fun InfoPill(icon: ImageVector, label: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun epornerWatchlistId(epornerId: String): Long =
    (-9_000_000_000L) - (epornerId.hashCode().toLong() and 0xFFFFFL)

@Composable
private fun AdultCard(v: AdultItem, onClick: () -> Unit) {
    val cardBg  = MaterialTheme.colorScheme.surface
    val textFg  = MaterialTheme.colorScheme.onSurface

    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model              = v.thumbnail,
                contentDescription = v.title,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
            // gradient for duration readability
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                        )
                    )
            )
            v.durationLabel?.let {
                Text(
                    it,
                    color    = Color.White,
                    style    = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Icon(
                Icons.Default.PlayCircle,
                null,
                tint     = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
        Text(
            v.title,
            style    = MaterialTheme.typography.bodyMedium,
            color    = textFg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
