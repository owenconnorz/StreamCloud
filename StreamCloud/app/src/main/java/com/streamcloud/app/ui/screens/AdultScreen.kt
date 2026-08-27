package com.streamcloud.app.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import com.streamcloud.app.ui.theme.tvFocusBorder
import com.streamcloud.app.ui.theme.tvFocusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.streamcloud.app.data.api.AdultItem
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.EpornerCategory
import com.streamcloud.app.data.api.PornhubCategory
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.WatchlistEntity
import com.streamcloud.app.ui.screens.adult.RedditFeedView
import com.streamcloud.app.ui.screens.adult.RedGifsFeedView
import com.streamcloud.app.ui.viewmodel.AdultViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdultScreen(
    onPlay: (videoId: String, fallbackEmbed: String, title: String) -> Unit,
    onOpenRedditLogin: () -> Unit = {},
    onOpenSearch: (AdultSource) -> Unit = {},
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
    var showAllPornhubCategories by remember { mutableStateOf(false) }
    var showProviderPicker by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    BackHandler(enabled = showAllPornhubCategories) {
        showAllPornhubCategories = false
    }
    BackHandler(enabled = showProviderPicker) {
        showProviderPicker = false
    }

    // Age gate: show blocking overlay until user confirms 18+
    if (!state.ageGateConfirmed) {
        AgeGateOverlay(onConfirm = { vm.confirmAgeGate() })
        return
    }

    // PIN lock: show lock screen if adult lock is enabled and not yet unlocked this session
    if (state.adultLockEnabled && state.safeModePin.isNotBlank() && !state.lockUnlocked) {
        PinLockScreen(onUnlock = { pin -> vm.unlockWithPin(pin) })
        return
    }

    if (showProviderPicker) {
        AdultProviderPicker(
            selectedSource = state.source,
            onBack = { showProviderPicker = false },
            onSelect = { source ->
                vm.setSource(source)
                query = ""
                showProviderPicker = false
            },
        )
        return
    }

    // Infinite scroll for Eporner grid: trigger loadMore when near the bottom
    LaunchedEffect(gridState) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val last  = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && last >= total - 6
        }.collect { reachedEnd ->
            if (reachedEnd && state.source in setOf(AdultSource.Eporner, AdultSource.Pornhub)) {
                vm.loadMore()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Title row and provider controls ──────────────────────────────
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
            if (state.source in setOf(AdultSource.Eporner, AdultSource.Pornhub)) {
                IconButton(
                    onClick = { onOpenSearch(state.source) },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search ${state.source.label}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(
                onClick = { showProviderPicker = true },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = "Plugins",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.source in setOf(AdultSource.Eporner, AdultSource.Pornhub)) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { vm.refresh() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (state.source == AdultSource.Pornhub && showAllPornhubCategories) {
            PornhubCategoriesPage(
                categories = state.pornhubCategories,
                loading = state.loadingPornhubCategories,
                onBack = { showAllPornhubCategories = false },
                onSelect = { category ->
                    vm.selectPornhubCategory(category)
                    query = category.title
                    showAllPornhubCategories = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else if (state.source == AdultSource.Reddit) {
            // ── Reddit swipe-up feed ─────────────────────────────────────
            RedditFeedView(
                vm         = vm,
                onOpenRedditLogin = onOpenRedditLogin,
                onPlayItem = { item ->
                    val directUrl = item.streamUrl.orEmpty()
                    onPlay("direct://$directUrl", directUrl, item.title)
                },
                modifier   = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else if (state.source == AdultSource.RedGifs) {
            // ── RedGifs swipe-up feed ────────────────────────────────────
            RedGifsFeedView(
                vm         = vm,
                onPlayItem = { item ->
                    val directUrl = item.streamUrl.orEmpty()
                    onPlay("direct://$directUrl", directUrl, item.title)
                },
                modifier   = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            // ── Searchable provider grid ──────────────────────────────────
            Spacer(Modifier.height(8.dp))

            if (state.source == AdultSource.Pornhub && query.isBlank()) {
                PornhubCategoryCarousel(
                    categories = state.pornhubCategories,
                    loading = state.loadingPornhubCategories,
                    onViewAll = {
                        vm.loadPornhubCategories()
                        showAllPornhubCategories = true
                    },
                    onSelect = { category ->
                        vm.selectPornhubCategory(category)
                        query = category.title
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            // Category + active-category chip row (Eporner only)
            if (state.source == AdultSource.Eporner) Row(
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
                columns = if (state.source == AdultSource.Pornhub) {
                    GridCells.Adaptive(300.dp)
                } else {
                    GridCells.Fixed(2)
                },
                modifier = Modifier.tvFocusGroup(),
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
            item       = item,
            context    = context,
            onDownload = { vm.downloadVideo(item) },
            onPlay     = {
                vm.recordHistory(item)
                detailItem = null
                if (item.source == AdultSource.Reddit) {
                    onPlay(item.id, item.streamUrl.orEmpty(), item.title)
                } else if (item.source == AdultSource.Pornhub) {
                    onPlay("pornhub://${item.id}", item.embedUrl.orEmpty(), item.title)
                } else {
                    onPlay(item.epornerId ?: item.id, item.embedUrl.orEmpty(), item.title)
                }
            },
            onDismiss  = { detailItem = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdultProviderPicker(
    selectedSource: AdultSource,
    onBack: () -> Unit,
    onSelect: (AdultSource) -> Unit,
) {
    val providers = listOf(
        AdultSource.Eporner,
        AdultSource.Pornhub,
        AdultSource.Reddit,
        AdultSource.RedGifs,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Plugins") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .tvFocusGroup(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Choose a provider",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(providers, key = { it.name }) { provider ->
                val selected = provider == selectedSource
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(74.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .clickable { onSelect(provider) },
                    shape = RoundedCornerShape(17.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    tonalElevation = if (selected) 3.dp else 0.dp,
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProviderBadge(provider)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                provider.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (selected) {
                                Text(
                                    "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                )
                            }
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "Open ${provider.label}",
                            tint = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderBadge(provider: AdultSource) {
    val brand = when (provider) {
        // Official site favicons keep the selector recognizable without
        // shipping third-party artwork in the APK.
        AdultSource.Eporner -> ProviderBrand(
            logoUrl = "https://www.eporner.com/favicon.ico",
            background = Color(0xFF25262A),
            fallbackForeground = Color(0xFFFFB300),
            fallbackLabel = "E",
        )
        AdultSource.Pornhub -> ProviderBrand(
            logoUrl = "https://www.pornhub.com/favicon.ico",
            background = Color(0xFFFF9800),
            fallbackForeground = Color.Black,
            fallbackLabel = "P",
        )
        AdultSource.Reddit -> ProviderBrand(
            logoUrl = "https://www.redditstatic.com/desktop2x/img/favicon/favicon-96x96.png",
            background = Color(0xFFFF4500),
            fallbackForeground = Color.White,
            fallbackLabel = "R",
        )
        AdultSource.RedGifs -> ProviderBrand(
            logoUrl = "https://www.redgifs.com/favicon.ico",
            background = Color(0xFF00BCD4),
            fallbackForeground = Color.White,
            fallbackLabel = "RG",
        )
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(brand.background),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = brand.logoUrl,
            contentDescription = "${provider.label} logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(32.dp),
            loading = {
                ProviderFallbackBadge(brand)
            },
            error = {
                ProviderFallbackBadge(brand)
            },
        )
    }
}

private data class ProviderBrand(
    val logoUrl: String,
    val background: Color,
    val fallbackForeground: Color,
    val fallbackLabel: String,
)

@Composable
private fun ProviderFallbackBadge(brand: ProviderBrand) {
    if (brand.fallbackLabel == "E") {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            tint = brand.fallbackForeground,
            modifier = Modifier.size(24.dp),
        )
    } else {
        Text(
            brand.fallbackLabel,
            color = brand.fallbackForeground,
            fontWeight = FontWeight.Bold,
            fontSize = if (brand.fallbackLabel.length > 1) 13.sp else 18.sp,
        )
    }
}

@Composable
fun AdultSearchScreen(
    source: AdultSource,
    onBack: () -> Unit,
    onPlay: (videoId: String, fallbackEmbed: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: AdultViewModel = viewModel(factory = AdultViewModel.factory(context))
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(source) {
        vm.setSource(source)
        focusRequester.requestFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.search(it)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        "Search ${source.label} videos\u2026",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                vm.search("")
                            }) {
                                Icon(Icons.Default.Close, "Clear")
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }

        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        LazyVerticalGrid(
            columns = if (source == AdultSource.Pornhub) {
                GridCells.Adaptive(300.dp)
            } else {
                GridCells.Fixed(2)
            },
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .tvFocusGroup(),
        ) {
            items(state.items, key = { it.id }) { item ->
                AdultCard(item) {
                    onPlay(
                        if (item.source == AdultSource.Pornhub) "pornhub://${item.id}"
                        else item.epornerId ?: item.id,
                        item.embedUrl.orEmpty(),
                        item.title,
                    )
                }
            }
        }
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
                                    .tvFocusBorder(RoundedCornerShape(10.dp))
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

@Composable
private fun PornhubCategoryCarousel(
    categories: List<PornhubCategory>,
    loading: Boolean,
    onViewAll: () -> Unit,
    onSelect: (PornhubCategory) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Porn Categories",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onViewAll) {
                Text("All categories")
            }
        }
        when {
            loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(116.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            }
            categories.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    categories.take(18),
                    key = { it.id.ifBlank { it.title } },
                ) { category ->
                    PornhubCategoryCard(
                        category = category,
                        onClick = { onSelect(category) },
                        modifier = Modifier.width(190.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PornhubCategoriesPage(
    categories: List<PornhubCategory>,
    loading: Boolean,
    onBack: () -> Unit,
    onSelect: (PornhubCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(Color.Black)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Text(
                "All Porn Categories",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF9000))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.tvFocusGroup(),
            ) {
                items(categories, key = { it.id.ifBlank { it.title } }) { category ->
                    PornhubCategoryCard(
                        category = category,
                        onClick = { onSelect(category) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PornhubCategoryCard(
    category: PornhubCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(6.dp))
            .tvFocusBorder(RoundedCornerShape(6.dp))
            .background(Color(0xFF242424))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = category.thumbnail,
            contentDescription = category.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        ) {
            Text(
                category.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            category.countLabel?.let { count ->
                Text(
                    count,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpornerDetailSheet(
    item: AdultItem,
    context: Context,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)? = null,
) {
    val scope      = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        LibraryDb.get(context).watchlist()
            .isWatchlisted(adultWatchlistId(item))
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
                            val wid = adultWatchlistId(item)
                            if (saved) {
                                db.watchlist().remove(wid)
                            } else {
                                db.watchlist().add(
                                    WatchlistEntity(
                                        tmdbId    = wid,
                                        title     = item.title,
                                        posterUrl = item.thumbnail,
                                        mediaType = item.source.name.lowercase(),
                                        csPlugin  = item.source.name.lowercase(),
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

            onDownload?.let { dl ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    OutlinedButton(
                        onClick  = dl,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download Video")
                    }
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

private fun adultWatchlistId(item: AdultItem): Long {
    val stableKey = if (item.source == AdultSource.Eporner) {
        item.id
    } else {
        "${item.source.name}:${item.id}"
    }
    return (-9_000_000_000L) - (stableKey.hashCode().toLong() and 0xFFFFFL)
}


@Composable
private fun AgeGateOverlay(onConfirm: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Text(
                "18+",
                fontSize   = 64.sp,
                fontWeight = FontWeight.Black,
                color      = Color(0xFF7C5CFF),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Adult Content",
                style      = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "This section contains explicit 18+ content. By continuing you confirm you are at least 18 years old.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick  = onConfirm,
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("I am 18 or older — Enter", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PinLockScreen(onUnlock: (String) -> Boolean) {
    var pin      by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint     = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Adult Content Locked",
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter your PIN to access this section.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value           = pin,
                onValueChange   = { pin = it.take(8); pinError = false },
                label           = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction    = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (!onUnlock(pin)) pinError = true
                }),
                isError    = pinError,
                singleLine = true,
                modifier   = Modifier.fillMaxWidth(),
                shape      = RoundedCornerShape(14.dp),
            )
            if (pinError) {
                Text(
                    "Incorrect PIN",
                    color    = MaterialTheme.colorScheme.error,
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick  = { if (!onUnlock(pin)) pinError = true },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
            ) {
                Text("Unlock", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AdultCard(v: AdultItem, onClick: () -> Unit) {
    val cardBg  = MaterialTheme.colorScheme.surface
    val textFg  = MaterialTheme.colorScheme.onSurface

    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .tvFocusBorder(RoundedCornerShape(12.dp))
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
            if (v.source != AdultSource.Pornhub) {
                Icon(
                    Icons.Default.PlayCircle,
                    null,
                    tint     = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
                )
            }
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
