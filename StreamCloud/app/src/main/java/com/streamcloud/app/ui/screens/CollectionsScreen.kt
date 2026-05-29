package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.collections.HomeCollections
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.UserCollectionEntity
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import kotlinx.coroutines.launch

private val DeleteRed = Color(0xFFD32F2F)
private val EditBlue  = Color(0xFF2196F3)
private val CardBg    = Color(0xFF1E1E1E)
private val ScreenBg  = Color(0xFF121212)

private sealed class CollNav {
    object List : CollNav()
    data class EditCollection(val collection: UserCollectionEntity) : CollNav()
    data class EditFolder(val collectionId: Long, val folder: CollectionFolderEntity?) : CollNav()
}

@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    installedCsPlugins: List<InstalledPlugin> = emptyList(),
    installedStremioAddons: List<InstalledStremioAddon> = emptyList(),
    onOpenCatalog: (source: String, title: String, subtitle: String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val db = remember { LibraryDb.get(context) }
    val scope = rememberCoroutineScope()

    var nav by remember { mutableStateOf<CollNav>(CollNav.List) }

    when (val cur = nav) {
        is CollNav.List -> CollectionsList(
            db = db,
            onBack = onBack,
            onNewCollection = {
                scope.launch {
                    val newId = db.userCollections().upsert(
                        UserCollectionEntity(name = "New Collection", createdAt = System.currentTimeMillis())
                    )
                    val entity = db.userCollections().byId(newId) ?: return@launch
                    nav = CollNav.EditCollection(entity)
                }
            },
            onEdit = { collection -> nav = CollNav.EditCollection(collection) },
            onDelete = { collection ->
                scope.launch {
                    db.collectionFolders().deleteForCollection(collection.id)
                    db.userCollections().delete(collection.id)
                }
            },
        )

        is CollNav.EditCollection -> EditCollectionView(
            db = db,
            collection = cur.collection,
            onBack = { nav = CollNav.List },
            onSave = { updatedName, isPinned ->
                scope.launch {
                    db.userCollections().upsert(cur.collection.copy(name = updatedName, isPinned = isPinned))
                    nav = CollNav.List
                }
            },
            onAddFolder = {
                scope.launch {
                    val folderId = db.collectionFolders().upsert(
                        CollectionFolderEntity(collectionId = cur.collection.id, name = "New Folder")
                    )
                    val folder = db.collectionFolders().forCollectionOnce(cur.collection.id)
                        .firstOrNull { it.id == folderId }
                    nav = CollNav.EditFolder(cur.collection.id, folder)
                }
            },
            onEditFolder = { folder -> nav = CollNav.EditFolder(cur.collection.id, folder) },
            onDeleteFolder = { folder -> scope.launch { db.collectionFolders().delete(folder.id) } },
        )

        is CollNav.EditFolder -> EditFolderView(
            folder = cur.folder,
            installedCsPlugins = installedCsPlugins,
            installedStremioAddons = installedStremioAddons,
            onBack = {
                scope.launch {
                    val parent = db.userCollections().byId(cur.collectionId)
                    nav = if (parent != null) CollNav.EditCollection(parent) else CollNav.List
                }
            },
            onSave = { name, coverUrl, tileShape, linkedCategoryId, providerType ->
                scope.launch {
                    val entity = cur.folder?.copy(
                        name = name,
                        coverUrl = coverUrl,
                        tileShape = tileShape,
                        linkedCategoryId = linkedCategoryId,
                        providerType = providerType,
                    ) ?: CollectionFolderEntity(
                        collectionId = cur.collectionId,
                        name = name,
                        coverUrl = coverUrl,
                        tileShape = tileShape,
                        linkedCategoryId = linkedCategoryId,
                        providerType = providerType,
                    )
                    db.collectionFolders().upsert(entity)
                    val parent = db.userCollections().byId(cur.collectionId)
                    nav = if (parent != null) CollNav.EditCollection(parent) else CollNav.List
                }
            },
        )
    }
}

// ── Collections List ──────────────────────────────────────────────────────────

@Composable
private fun CollectionsList(
    db: LibraryDb,
    onBack: () -> Unit,
    onNewCollection: () -> Unit,
    onEdit: (UserCollectionEntity) -> Unit,
    onDelete: (UserCollectionEntity) -> Unit,
) {
    val collections by db.userCollections().all().collectAsState(initial = emptyList())
    val allFolders by db.collectionFolders().all().collectAsState(initial = emptyList())
    val folderCountMap = remember(allFolders) {
        allFolders.groupBy { it.collectionId }.mapValues { it.value.size }
    }
    var pendingDelete by remember { mutableStateOf<UserCollectionEntity?>(null) }

    Column(Modifier.fillMaxSize().background(ScreenBg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                "Collections",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "${collections.size} collection(s) · ${allFolders.size} folder(s)",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onNewCollection,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EditBlue),
        ) {
            Text("New Collection", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Your Collections",
            style = MaterialTheme.typography.labelLarge,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(collections, key = { it.id }) { collection ->
                CollectionCard(
                    collection = collection,
                    folderCount = folderCountMap[collection.id] ?: 0,
                    onEdit = { onEdit(collection) },
                    onDelete = { pendingDelete = collection },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    pendingDelete?.let { col ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${col.name}\"?") },
            text = { Text("This will permanently delete the collection and all its folders.") },
            confirmButton = {
                TextButton(onClick = { onDelete(col); pendingDelete = null }) {
                    Text("Delete", color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CollectionCard(
    collection: UserCollectionEntity,
    folderCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardBg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        collection.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    val meta = buildString {
                        append("$folderCount folder(s)")
                        if (collection.isPinned) append(" · Pinned")
                    }
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit", tint = EditBlue, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = DeleteRed, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Icon(Icons.Default.Menu, "Drag handle", tint = Color.Gray, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Edit Collection ───────────────────────────────────────────────────────────

@Composable
private fun EditCollectionView(
    db: LibraryDb,
    collection: UserCollectionEntity,
    onBack: () -> Unit,
    onSave: (name: String, isPinned: Boolean) -> Unit,
    onAddFolder: () -> Unit,
    onEditFolder: (CollectionFolderEntity) -> Unit,
    onDeleteFolder: (CollectionFolderEntity) -> Unit,
) {
    var nameInput by remember(collection.id) { mutableStateOf(collection.name) }
    var coverInput by remember(collection.id) { mutableStateOf(collection.coverUrl) }
    var isPinned by remember(collection.id) { mutableStateOf(collection.isPinned) }
    val folders by db.collectionFolders().forCollection(collection.id).collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().background(ScreenBg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                "Edit Collection",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Collection name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = coverInput,
                onValueChange = { coverInput = it },
                label = { Text("Cover image URL (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(12.dp))

            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pin Above Catalogs", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        Text(
                            "Show this collection on the Movies home screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                    Switch(checked = isPinned, onCheckedChange = { isPinned = it })
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Folders", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                TextButton(onClick = onAddFolder) {
                    Icon(Icons.Default.Add, null, tint = EditBlue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Folder", color = EditBlue)
                }
            }

            if (folders.isEmpty()) {
                Text(
                    "No folders yet. Tap \"+Add Folder\" to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                folders.forEach { folder ->
                    Spacer(Modifier.height(8.dp))
                    FolderCard(
                        folder = folder,
                        onEdit = { onEditFolder(folder) },
                        onDelete = { onDeleteFolder(folder) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        Button(
            onClick = { onSave(nameInput.ifBlank { "New Collection" }, isPinned) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EditBlue),
        ) {
            Text("Save Changes", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun FolderCard(
    folder: CollectionFolderEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val providerLabel = when (folder.providerType) {
        "cloudstream" -> "CloudStream"
        "stremio"     -> "Stremio"
        else          -> "TMDB"
    }
    val categoryName = when (folder.providerType) {
        "cloudstream" -> folder.linkedCategoryId.ifBlank { "No plugin" }
        "stremio"     -> folder.linkedCategoryId.ifBlank { "No addon" }
        else          -> HomeCollections.byId(folder.linkedCategoryId)?.title ?: "No category"
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardBg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Text(
                        "$providerLabel · $categoryName · ${folder.tileShape.replaceFirstChar { it.uppercaseChar() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit", tint = EditBlue, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = DeleteRed, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Icon(Icons.Default.Menu, "Drag handle", tint = Color.Gray, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Edit Folder ───────────────────────────────────────────────────────────────

private val PROVIDER_TABS = listOf("TMDB / Nuvio", "CloudStream", "Stremio")

@Composable
private fun EditFolderView(
    folder: CollectionFolderEntity?,
    installedCsPlugins: List<InstalledPlugin>,
    installedStremioAddons: List<InstalledStremioAddon>,
    onBack: () -> Unit,
    onSave: (name: String, coverUrl: String, tileShape: String, linkedCategoryId: String, providerType: String) -> Unit,
) {
    var nameInput by remember { mutableStateOf(folder?.name ?: "") }
    var coverInput by remember { mutableStateOf(folder?.coverUrl ?: "") }
    var tileShape by remember { mutableStateOf(folder?.tileShape ?: "wide") }
    val initTab = when (folder?.providerType) {
        "cloudstream" -> 1; "stremio" -> 2; else -> 0
    }
    var selectedTab by remember { mutableIntStateOf(initTab) }
    var linkedCategory by remember { mutableStateOf(folder?.linkedCategoryId ?: "") }
    var showTmdbPicker by remember { mutableStateOf(false) }

    val currentProviderType = when (selectedTab) {
        1 -> "cloudstream"; 2 -> "stremio"; else -> "tmdb"
    }
    val shapes = listOf("poster", "square", "wide")

    Column(Modifier.fillMaxSize().background(ScreenBg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                "Edit Folder",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Basics ────────────────────────────────────────────────────────
            Text("Basics", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(16.dp))

            // ── Appearance ────────────────────────────────────────────────────
            Text("Appearance", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = coverInput,
                onValueChange = { coverInput = it },
                label = { Text("Cover image URL (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(12.dp))

            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Tile Shape", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shapes.forEach { shape ->
                            val selected = tileShape == shape
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) EditBlue else Color(0xFF2C2C2C))
                                    .clickable { tileShape = shape }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    shape.replaceFirstChar { it.uppercaseChar() },
                                    color = if (selected) Color.White else Color.Gray,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Provider / Catalog Source ─────────────────────────────────────
            Text("Catalog Source", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(Modifier.height(8.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = CardBg,
                contentColor = EditBlue,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
            ) {
                PROVIDER_TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            linkedCategory = ""
                        },
                        text = {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                0 -> TmdbProviderSection(linkedCategory, onPick = { showTmdbPicker = true })
                1 -> CsProviderSection(installedCsPlugins, linkedCategory) { linkedCategory = it }
                2 -> StremioProviderSection(installedStremioAddons, linkedCategory) { linkedCategory = it }
            }

            Spacer(Modifier.height(24.dp))
        }

        Button(
            onClick = {
                onSave(
                    nameInput.ifBlank { "New Folder" },
                    coverInput.trim(),
                    tileShape,
                    linkedCategory,
                    currentProviderType,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EditBlue),
        ) {
            Text("Save", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }

    if (showTmdbPicker) {
        TmdbPickerDialog(
            current = linkedCategory,
            onPick = { linkedCategory = it; showTmdbPicker = false },
            onDismiss = { showTmdbPicker = false },
        )
    }
}

// ── Provider sections ─────────────────────────────────────────────────────────

@Composable
private fun TmdbProviderSection(selected: String, onPick: () -> Unit) {
    val catName = HomeCollections.byId(selected)?.title ?: "None selected"
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CardBg,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Selected Category", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                Text(catName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Text("Browse", color = EditBlue, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CsProviderSection(
    plugins: List<InstalledPlugin>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    if (plugins.isEmpty()) {
        Text(
            "No CloudStream plugins installed. Install plugins from Settings → Plugins & Addons.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        plugins.forEach { plugin ->
            val isSelected = selected == plugin.internalName
            ProviderPickerRow(
                name = plugin.name,
                subtitle = plugin.internalName,
                iconUrl = plugin.iconUrl,
                isSelected = isSelected,
                onClick = { onSelect(if (isSelected) "" else plugin.internalName) },
            )
        }
    }
}

@Composable
private fun StremioProviderSection(
    addons: List<InstalledStremioAddon>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    if (addons.isEmpty()) {
        Text(
            "No Stremio addons installed. Install addons from Settings → Plugins & Addons.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        addons.forEach { addon ->
            val isSelected = selected == addon.id
            ProviderPickerRow(
                name = addon.name,
                subtitle = addon.id,
                iconUrl = addon.logo,
                isSelected = isSelected,
                onClick = { onSelect(if (isSelected) "" else addon.id) },
            )
        }
    }
}

@Composable
private fun ProviderPickerRow(
    name: String,
    subtitle: String,
    iconUrl: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFF1A2E44) else CardBg,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isSelected) Modifier.border(1.dp, EditBlue, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!iconUrl.isNullOrBlank()) {
                AsyncImage(
                    model = iconUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) Color.White else Color.LightGray,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                )
            }
            if (isSelected) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EditBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── TMDB Category Picker Dialog ───────────────────────────────────────────────

@Composable
private fun TmdbPickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a TMDB category") },
        text = {
            LazyColumn(modifier = Modifier.height(380.dp)) {
                item {
                    TmdbCategoryRow(title = "None", subtitle = "No linked catalog", selected = current.isBlank()) {
                        onPick("")
                    }
                    HorizontalDivider()
                }
                items(HomeCollections.ALL) { cat ->
                    TmdbCategoryRow(
                        title = cat.title,
                        subtitle = cat.subtitle,
                        selected = current == cat.id,
                        onClick = { onPick(cat.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun TmdbCategoryRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) EditBlue else Color.Gray.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}
