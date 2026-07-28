package com.streamcloud.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.collections.HomeCollections
import com.streamcloud.app.data.library.CollectionFolderEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.UserCollectionEntity
import com.streamcloud.app.data.nuvio.NuvioAccountService
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioCatalogDef
import com.streamcloud.app.data.stremio.StremioRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── JSON export/import ────────────────────────────────────────────────────────

private val CollectionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class CollectionExportBundle(
    val version: Int = 1,
    val collections: List<CollectionExportEntry>,
)

@Serializable
private data class CollectionExportEntry(
    val name: String,
    val coverUrl: String = "",
    val isPinned: Boolean = false,
    val viewMode: String = "rows",
    val folders: List<FolderExportEntry> = emptyList(),
)

@Serializable
private data class FolderExportEntry(
    val name: String,
    val coverUrl: String = "",
    val tileShape: String = "wide",
    val linkedCategoryId: String = "",
    val providerType: String = "tmdb",
    val hideTitle: Boolean = false,
)

// ── Colors ───────────────────────────────────────────────────────────────────

private val DeleteRed = Color(0xFFD32F2F)
private val EditBlue  = Color(0xFF2196F3)
private val CardBg    = Color(0xFF1E1E1E)
private val ScreenBg  = Color(0xFF121212)
private val SubItemBg = Color(0xFF252525)

// ── Encoding helpers ──────────────────────────────────────────────────────────

private const val SEP = "|||"
private fun encodeCs(internalName: String, sectionName: String, displayName: String) =
    "$internalName$SEP$sectionName$SEP$displayName"
private fun encodeStremio(addonId: String, catalogType: String, catalogId: String, catalogName: String) =
    "$addonId$SEP$catalogType$SEP$catalogId$SEP$catalogName"

data class CsSelectable(val internalName: String, val sectionName: String, val displayName: String)
data class StremioSelectable(val addonId: String, val catalogType: String, val catalogId: String, val catalogName: String)

fun decodeCsId(encoded: String): CsSelectable? {
    if (encoded.isBlank()) return null
    val parts = encoded.split(SEP)
    return CsSelectable(
        internalName = parts.getOrElse(0) { "" },
        sectionName  = parts.getOrElse(1) { "" },
        displayName  = parts.getOrElse(2) { parts.getOrElse(0) { "" } },
    )
}

fun decodeStremioId(encoded: String): StremioSelectable? {
    if (encoded.isBlank()) return null
    val parts = encoded.split(SEP)
    return StremioSelectable(
        addonId     = parts.getOrElse(0) { "" },
        catalogType = parts.getOrElse(1) { "" },
        catalogId   = parts.getOrElse(2) { "" },
        catalogName = parts.getOrElse(3) { "" },
    )
}

fun decodeTmdbCategoryLabel(id: String): String {
    if (id.isBlank()) return "None selected"
    HomeCollections.byId(id)?.let { return it.title }
    return when {
        id.startsWith("list:")            -> "TMDB List #${id.removePrefix("list:")}"
        id.startsWith("collection:")      -> "Movie Collection #${id.removePrefix("collection:")}"
        id.startsWith("person:")          -> "Person #${id.removePrefix("person:")}"
        id.startsWith("company:")         -> "Company #${id.removePrefix("company:")}"
        id.startsWith("network:")         -> "TV Network #${id.removePrefix("network:")}"
        id.startsWith("discover_movie:")  -> {
            val p = id.removePrefix("discover_movie:")
            "Discover Movies ($p)"
        }
        id.startsWith("discover_tv:")     -> {
            val p = id.removePrefix("discover_tv:")
            "Discover TV ($p)"
        }
        else                              -> id
    }
}

// ── Genres ───────────────────────────────────────────────────────────────────

private val MOVIE_GENRES = listOf(
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
    80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
    14 to "Fantasy", 27 to "Horror", 9648 to "Mystery", 10749 to "Romance",
    878 to "Science Fiction", 53 to "Thriller", 37 to "Western",
)

private val TV_GENRES = listOf(
    10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy",
    80 to "Crime", 18 to "Drama", 10751 to "Family", 10762 to "Kids",
    9648 to "Mystery", 10765 to "Sci-Fi & Fantasy", 10767 to "Talk",
    10768 to "War & Politics", 37 to "Western",
)

// ── Navigation ────────────────────────────────────────────────────────────────

private sealed class CollNav {
    object List : CollNav()
    data class EditCollection(val collection: UserCollectionEntity) : CollNav()
    data class EditFolder(val collectionId: Long, val folder: CollectionFolderEntity?) : CollNav()
}

// ── Screen entry point ────────────────────────────────────────────────────────

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
            onEdit = { nav = CollNav.EditCollection(it) },
            onDelete = { col ->
                scope.launch {
                    db.collectionFolders().deleteForCollection(col.id)
                    db.userCollections().delete(col.id)
                    if (col.sourceAddonId.isNotBlank()) {
                        val sl = ServiceLocator.get(context)
                        sl.settings.addDeletedManagedCollection("${col.sourceAddonId}::${col.name}")
                        val token = sl.settings.nuvioAccessToken.first()
                        if (token.isNotBlank()) {
                            runCatching { NuvioAccountService.get(context).syncAll(token) }
                        }
                    }
                }
            },
        )

        is CollNav.EditCollection -> EditCollectionView(
            db = db,
            collection = cur.collection,
            onBack = { nav = CollNav.List },
            onSave = { name, isPinned, viewMode ->
                scope.launch {
                    db.userCollections().upsert(cur.collection.copy(name = name, isPinned = isPinned, viewMode = viewMode))
                    nav = CollNav.List
                }
            },
            onAddFolder = {
                scope.launch {
                    val fid = db.collectionFolders().upsert(
                        CollectionFolderEntity(collectionId = cur.collection.id, name = "New Folder")
                    )
                    val folder = db.collectionFolders().forCollectionOnce(cur.collection.id)
                        .firstOrNull { it.id == fid }
                    nav = CollNav.EditFolder(cur.collection.id, folder)
                }
            },
            onEditFolder = { nav = CollNav.EditFolder(cur.collection.id, it) },
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
            onSave = { name, coverUrl, tileShape, linkedCategoryId, providerType, hideTitle ->
                scope.launch {
                    val entity = cur.folder?.copy(
                        name = name, coverUrl = coverUrl, tileShape = tileShape,
                        linkedCategoryId = linkedCategoryId, providerType = providerType,
                        hideTitle = hideTitle,
                    ) ?: CollectionFolderEntity(
                        collectionId = cur.collectionId,
                        name = name, coverUrl = coverUrl, tileShape = tileShape,
                        linkedCategoryId = linkedCategoryId, providerType = providerType,
                        hideTitle = hideTitle,
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
    val context = LocalContext.current
    val dbCollections by db.userCollections().all().collectAsState(initial = emptyList())
    val allFolders by db.collectionFolders().all().collectAsState(initial = emptyList())
    val folderCountMap = remember(allFolders) {
        allFolders.groupBy { it.collectionId }.mapValues { it.value.size }
    }
    var pendingDelete by remember { mutableStateOf<UserCollectionEntity?>(null) }
    val scope = rememberCoroutineScope()
    var snackMessage by remember { mutableStateOf<String?>(null) }

    // Drag-to-reorder state
    val ordered = remember { mutableStateListOf<UserCollectionEntity>() }
    var dragging by remember { mutableStateOf(false) }
    var dragIdx by remember { mutableIntStateOf(-1) }
    var accY by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(dbCollections) {
        if (!dragging) {
            ordered.clear()
            ordered.addAll(dbCollections)
        }
    }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    if (content != null) {
                        val bundle = CollectionJson.decodeFromString<CollectionExportBundle>(content)
                        bundle.collections.forEach { col ->
                            val colId = db.userCollections().upsert(
                                UserCollectionEntity(
                                    name = col.name, coverUrl = col.coverUrl,
                                    isPinned = col.isPinned, viewMode = col.viewMode,
                                    createdAt = System.currentTimeMillis(),
                                )
                            )
                            col.folders.forEachIndexed { idx, folder ->
                                db.collectionFolders().upsert(
                                    CollectionFolderEntity(
                                        collectionId = colId, name = folder.name,
                                        coverUrl = folder.coverUrl, tileShape = folder.tileShape,
                                        linkedCategoryId = folder.linkedCategoryId,
                                        providerType = folder.providerType,
                                        hideTitle = folder.hideTitle, sortOrder = idx,
                                    )
                                )
                            }
                        }
                        snackMessage = "Imported ${bundle.collections.size} collection(s)"
                    } else {
                        snackMessage = "Could not read file"
                    }
                }.onFailure { snackMessage = "Invalid JSON: ${it.message?.take(60)}" }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                "${dbCollections.size} collection(s) · ${allFolders.size} folder(s)",
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
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Export button
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val collections = db.userCollections().all().first()
                                val folders = db.collectionFolders().all().first()
                                val foldersByCollection = folders.groupBy { it.collectionId }
                                val bundle = CollectionExportBundle(
                                    collections = collections.map { col ->
                                        CollectionExportEntry(
                                            name = col.name, coverUrl = col.coverUrl,
                                            isPinned = col.isPinned, viewMode = col.viewMode,
                                            folders = (foldersByCollection[col.id] ?: emptyList()).map { f ->
                                                FolderExportEntry(
                                                    name = f.name, coverUrl = f.coverUrl,
                                                    tileShape = f.tileShape,
                                                    linkedCategoryId = f.linkedCategoryId,
                                                    providerType = f.providerType,
                                                    hideTitle = f.hideTitle,
                                                )
                                            },
                                        )
                                    }
                                )
                                val json = CollectionJson.encodeToString(bundle)
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, json)
                                    putExtra(Intent.EXTRA_SUBJECT, "StreamCloud Collections")
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Export Collections"))
                            }.onFailure { snackMessage = "Export failed: ${it.message?.take(60)}" }
                        }
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Text("Export", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                // Import button
                Button(
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                ) {
                    Text("Import JSON", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Your Collections",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                ordered.forEachIndexed { i, col ->
                    CollectionCard(
                        collection = col,
                        folderCount = folderCountMap[col.id] ?: 0,
                        onEdit = { if (!dragging) onEdit(col) },
                        onDelete = { if (!dragging) pendingDelete = col },
                        isBeingDragged = dragIdx == i,
                        cardModifier = Modifier.onGloballyPositioned { coords ->
                            if (itemHeightPx == 0f) itemHeightPx = coords.size.height.toFloat()
                        },
                        dragHandleModifier = Modifier.pointerInput(col.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { _ ->
                                    val idx = ordered.indexOf(col)
                                    if (idx >= 0) { dragging = true; dragIdx = idx; accY = 0f }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    accY += dragAmount.y
                                    val threshold = (itemHeightPx + 10.dp.toPx()).coerceAtLeast(80f)
                                    while (accY > threshold / 2f && dragIdx < ordered.size - 1) {
                                        ordered.add(dragIdx + 1, ordered.removeAt(dragIdx)); dragIdx++; accY -= threshold
                                    }
                                    while (accY < -threshold / 2f && dragIdx > 0) {
                                        ordered.add(dragIdx - 1, ordered.removeAt(dragIdx)); dragIdx--; accY += threshold
                                    }
                                },
                                onDragEnd = {
                                    scope.launch {
                                        ordered.forEachIndexed { idx, c -> db.userCollections().updateOrder(c.id, idx) }
                                    }
                                    dragging = false; dragIdx = -1
                                },
                                onDragCancel = { dragging = false; dragIdx = -1 },
                            )
                        },
                    )
                    if (i < ordered.size - 1) Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // Snackbar
        snackMessage?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = { snackMessage = null }) { Text("OK") } },
            ) { Text(msg) }
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
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CollectionCard(
    collection: UserCollectionEntity,
    folderCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    isBeingDragged: Boolean = false,
    cardModifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isBeingDragged) Color(0xFF2C2C2E) else CardBg,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isBeingDragged) 1f else 0f)
            .then(cardModifier),
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
                        if (collection.viewMode == "tabbed_grid") append(" · Tabbed Grid")
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
            Icon(
                Icons.Default.Menu, "Drag to reorder",
                tint = if (isBeingDragged) Color.White else Color.Gray,
                modifier = Modifier.size(20.dp).then(dragHandleModifier),
            )
        }
    }
}

// ── Edit Collection ───────────────────────────────────────────────────────────

@Composable
private fun EditCollectionView(
    db: LibraryDb,
    collection: UserCollectionEntity,
    onBack: () -> Unit,
    onSave: (name: String, isPinned: Boolean, viewMode: String) -> Unit,
    onAddFolder: () -> Unit,
    onEditFolder: (CollectionFolderEntity) -> Unit,
    onDeleteFolder: (CollectionFolderEntity) -> Unit,
) {
    var nameInput by remember(collection.id) { mutableStateOf(collection.name) }
    var coverInput by remember(collection.id) { mutableStateOf(collection.coverUrl) }
    var isPinned by remember(collection.id) { mutableStateOf(collection.isPinned) }
    var viewMode by remember(collection.id) { mutableStateOf(collection.viewMode.ifBlank { "rows" }) }
    val dbFolders by db.collectionFolders().forCollection(collection.id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val orderedFolders = remember { mutableStateListOf<CollectionFolderEntity>() }
    var draggingFolder by remember { mutableStateOf(false) }
    var folderDragIdx by remember { mutableIntStateOf(-1) }
    var folderAccY by remember { mutableFloatStateOf(0f) }
    var folderItemHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(dbFolders) {
        if (!draggingFolder) {
            orderedFolders.clear()
            orderedFolders.addAll(dbFolders)
        }
    }

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
                value = nameInput, onValueChange = { nameInput = it },
                label = { Text("Collection name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = coverInput, onValueChange = { coverInput = it },
                label = { Text("Cover image URL (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            // Pin toggle
            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pin Above Catalogs", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        Text(
                            "Show this collection on the Movies home screen.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        )
                    }
                    Switch(checked = isPinned, onCheckedChange = { isPinned = it })
                }
            }
            Spacer(Modifier.height(12.dp))
            // View mode selector
            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Home Display Mode", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    Text(
                        "How this collection is laid out when tapped from the home screen.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("rows" to "Rows", "tabbed_grid" to "Tabbed Grid").forEach { (mode, label) ->
                            val sel = viewMode == mode
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) EditBlue else Color(0xFF2C2C2C))
                                    .clickable { viewMode = mode }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (sel) Color.White else Color.Gray,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
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
            if (orderedFolders.isEmpty()) {
                Text(
                    "No folders yet. Tap \"+Add Folder\" to create one.",
                    style = MaterialTheme.typography.bodyMedium, color = Color.Gray,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                orderedFolders.forEachIndexed { i, folder ->
                    Spacer(Modifier.height(8.dp))
                    FolderCard(
                        folder = folder,
                        onEdit = { if (!draggingFolder) onEditFolder(folder) },
                        onDelete = { if (!draggingFolder) onDeleteFolder(folder) },
                        isBeingDragged = folderDragIdx == i,
                        cardModifier = Modifier.onGloballyPositioned { coords ->
                            if (folderItemHeightPx == 0f) folderItemHeightPx = coords.size.height.toFloat()
                        },
                        dragHandleModifier = Modifier.pointerInput(folder.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { _ ->
                                    val idx = orderedFolders.indexOf(folder)
                                    if (idx >= 0) { draggingFolder = true; folderDragIdx = idx; folderAccY = 0f }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    folderAccY += dragAmount.y
                                    val threshold = (folderItemHeightPx + 8.dp.toPx()).coerceAtLeast(70f)
                                    while (folderAccY > threshold / 2f && folderDragIdx < orderedFolders.size - 1) {
                                        orderedFolders.add(folderDragIdx + 1, orderedFolders.removeAt(folderDragIdx))
                                        folderDragIdx++; folderAccY -= threshold
                                    }
                                    while (folderAccY < -threshold / 2f && folderDragIdx > 0) {
                                        orderedFolders.add(folderDragIdx - 1, orderedFolders.removeAt(folderDragIdx))
                                        folderDragIdx--; folderAccY += threshold
                                    }
                                },
                                onDragEnd = {
                                    scope.launch {
                                        orderedFolders.forEachIndexed { idx, f -> db.collectionFolders().updateOrder(f.id, idx) }
                                    }
                                    draggingFolder = false; folderDragIdx = -1
                                },
                                onDragCancel = { draggingFolder = false; folderDragIdx = -1 },
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Button(
            onClick = { onSave(nameInput.ifBlank { "New Collection" }, isPinned, viewMode) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(52.dp),
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
    isBeingDragged: Boolean = false,
    cardModifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val providerLabel = when (folder.providerType) {
        "cloudstream" -> "CloudStream"
        "stremio"     -> "Stremio"
        "trakt"       -> "Trakt"
        else          -> "TMDB"
    }
    val categoryDisplay = when (folder.providerType) {
        "cloudstream" -> {
            val ids = folder.linkedCategoryId.split("\n").filter { it.isNotBlank() }
            when {
                ids.isEmpty() -> "No section"
                ids.size == 1 -> decodeCsId(ids[0])?.let { "${it.displayName} › ${it.sectionName}" } ?: "1 section"
                else          -> "${ids.size} sections"
            }
        }
        "stremio" -> {
            val ids = folder.linkedCategoryId.split("\n").filter { it.isNotBlank() }
            when {
                ids.isEmpty() -> "No catalog"
                ids.size == 1 -> decodeStremioId(ids[0])?.let { it.catalogName.ifBlank { it.catalogId } } ?: "1 catalog"
                else          -> "${ids.size} catalogs"
            }
        }
        "trakt" -> traktSourceLabel(folder.linkedCategoryId)
        else    -> decodeTmdbCategoryLabel(folder.linkedCategoryId)
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isBeingDragged) Color(0xFF2C2C2E) else CardBg,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isBeingDragged) 1f else 0f)
            .then(cardModifier),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    val shape = folder.tileShape.replaceFirstChar { it.uppercaseChar() }
                    val extra = if (folder.hideTitle) " · Hidden title" else ""
                    Text(
                        "$providerLabel · $categoryDisplay · $shape$extra",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
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
            Icon(
                Icons.Default.Menu, "Drag to reorder",
                tint = if (isBeingDragged) Color.White else Color.Gray,
                modifier = Modifier.size(18.dp).then(dragHandleModifier),
            )
        }
    }
}

private fun traktSourceLabel(id: String) = when (id) {
    "trending_movies"      -> "Trending Movies"
    "trending_shows"       -> "Trending TV Shows"
    "popular_movies"       -> "Popular Movies"
    "popular_shows"        -> "Popular TV Shows"
    "watchlist_movies"     -> "My Watchlist (Movies)"
    "watchlist_shows"      -> "My Watchlist (Shows)"
    "recommended_movies"   -> "Recommended Movies"
    "recommended_shows"    -> "Recommended TV Shows"
    else                   -> id.ifBlank { "No source" }
}

// ── Edit Folder ───────────────────────────────────────────────────────────────

private val PROVIDER_TABS = listOf("TMDB", "CloudStream", "Stremio", "Trakt")

@Composable
private fun EditFolderView(
    folder: CollectionFolderEntity?,
    installedCsPlugins: List<InstalledPlugin>,
    installedStremioAddons: List<InstalledStremioAddon>,
    onBack: () -> Unit,
    onSave: (name: String, coverUrl: String, tileShape: String, linkedCategoryId: String, providerType: String, hideTitle: Boolean) -> Unit,
) {
    var nameInput by remember { mutableStateOf(folder?.name ?: "") }
    var coverInput by remember { mutableStateOf(folder?.coverUrl ?: "") }
    var tileShape by remember { mutableStateOf(folder?.tileShape ?: "wide") }
    var hideTitle by remember { mutableStateOf(folder?.hideTitle ?: false) }
    val initTab = when (folder?.providerType) { "cloudstream" -> 1; "stremio" -> 2; "trakt" -> 3; else -> 0 }
    var selectedTab by remember { mutableIntStateOf(initTab) }
    var linkedCategory by remember { mutableStateOf(folder?.linkedCategoryId ?: "") }
    var showTmdbPicker by remember { mutableStateOf(false) }

    val currentProviderType = when (selectedTab) { 1 -> "cloudstream"; 2 -> "stremio"; 3 -> "trakt"; else -> "tmdb" }
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
            Text("Basics", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nameInput, onValueChange = { nameInput = it },
                label = { Text("Folder name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Appearance", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = coverInput, onValueChange = { coverInput = it },
                label = { Text("Cover image URL (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            // Tile shape
            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Tile Shape", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shapes.forEach { shape ->
                            val sel = tileShape == shape
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) EditBlue else Color(0xFF2C2C2C))
                                    .clickable { tileShape = shape }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    shape.replaceFirstChar { it.uppercaseChar() },
                                    color = if (sel) Color.White else Color.Gray,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Hide title toggle
            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide Folder Title", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        Text(
                            "Don't show the folder label on its tile.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        )
                    }
                    Switch(checked = hideTitle, onCheckedChange = { hideTitle = it })
                }
            }
            Spacer(Modifier.height(20.dp))
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
                        onClick = { selectedTab = index; linkedCategory = "" },
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
                3 -> TraktProviderSection(linkedCategory) { linkedCategory = it }
            }
            Spacer(Modifier.height(24.dp))
        }
        Button(
            onClick = {
                onSave(
                    nameInput.ifBlank { "New Folder" }, coverInput.trim(),
                    tileShape, linkedCategory, currentProviderType, hideTitle,
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(52.dp),
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

// ── Provider Sections ─────────────────────────────────────────────────────────

@Composable
private fun TmdbProviderSection(selected: String, onPick: () -> Unit) {
    val label = decodeTmdbCategoryLabel(selected)
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
                Text("Selected Source", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Text("Browse", color = EditBlue, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TraktProviderSection(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "trending_movies"    to "Trending Movies",
        "trending_shows"     to "Trending TV Shows",
        "popular_movies"     to "Popular Movies",
        "popular_shows"      to "Popular TV Shows",
        "watchlist_movies"   to "My Watchlist (Movies)",
        "watchlist_shows"    to "My Watchlist (Shows)",
        "recommended_movies" to "Recommended Movies",
        "recommended_shows"  to "Recommended TV Shows",
    )
    Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
        Column {
            options.forEachIndexed { idx, (id, label) ->
                val sel = selected == id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (sel) Color(0xFF1A2E44) else Color.Transparent)
                        .clickable { onSelect(id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (sel) EditBlue else Color.Gray.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sel) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sel) Color.White else Color.LightGray,
                    )
                }
                if (idx < options.size - 1) HorizontalDivider(color = Color(0xFF2A2A2A))
            }
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
            style = MaterialTheme.typography.bodyMedium, color = Color.Gray,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectedIds = remember(selected) {
        selected.split("\n").filter { it.isNotBlank() }.toMutableSet()
    }
    var expandedPlugin by remember {
        mutableStateOf(selectedIds.firstOrNull()?.let { decodeCsId(it)?.internalName })
    }
    var sectionMap by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    var loadingPlugin by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(expandedPlugin) {
        val internalName = expandedPlugin ?: return@LaunchedEffect
        if (sectionMap.containsKey(internalName)) return@LaunchedEffect
        val plugin = plugins.firstOrNull { it.internalName == internalName } ?: return@LaunchedEffect
        loadingPlugin = internalName
        runCatching {
            val apis = PluginRuntime.load(context, plugin.filePath)
            val sections = apis.flatMap { api ->
                val pages = if (api.mainPage.isNotEmpty()) api.mainPage
                else listOf(com.lagradost.cloudstream3.MainPageRequest(api.name, "", false))
                pages.map { req -> req.name to api.name }
            }
            sectionMap = sectionMap + (internalName to sections)
        }
        loadingPlugin = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        plugins.forEach { plugin ->
            val isExpanded = expandedPlugin == plugin.internalName
            val sections = sectionMap[plugin.internalName] ?: emptyList()
            val isLoading = loadingPlugin == plugin.internalName
            val pluginSelectedCount = selectedIds.count { decodeCsId(it)?.internalName == plugin.internalName }

            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expandedPlugin = if (isExpanded) null else plugin.internalName }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!plugin.iconUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = plugin.iconUrl, contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                plugin.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.LightGray,
                            )
                            if (pluginSelectedCount > 0) {
                                Text("$pluginSelectedCount section(s) selected", style = MaterialTheme.typography.bodySmall, color = EditBlue)
                            } else {
                                Text("Tap to browse sections", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Color.Gray)
                    }

                    if (isExpanded) {
                        HorizontalDivider(color = Color(0xFF333333))
                        if (isLoading) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = EditBlue)
                                Spacer(Modifier.width(12.dp))
                                Text("Loading sections…", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (sections.isEmpty()) {
                            Text("No sections found.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(16.dp))
                        } else {
                            sections.forEach { (sectionName, displayName) ->
                                val encodedId = encodeCs(plugin.internalName, sectionName, displayName)
                                val isSelected = encodedId in selectedIds
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (isSelected) Color(0xFF1A2E44) else SubItemBg)
                                        .clickable {
                                            val newSet = if (isSelected) selectedIds - encodedId else selectedIds + encodedId
                                            onSelect(newSet.joinToString("\n"))
                                        }
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                                            .background(if (isSelected) EditBlue else Color.Gray.copy(alpha = 0.3f))
                                            .then(if (!isSelected) Modifier.border(1.dp, Color.Gray, RoundedCornerShape(4.dp)) else Modifier),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(sectionName, style = MaterialTheme.typography.bodyMedium, color = if (isSelected) Color.White else Color.LightGray)
                                }
                            }
                        }
                    }
                }
            }
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
            style = MaterialTheme.typography.bodyMedium, color = Color.Gray,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        return
    }

    val context = LocalContext.current
    val selectedIds = remember(selected) {
        selected.split("\n").filter { it.isNotBlank() }.toMutableSet()
    }
    var expandedAddon by remember {
        mutableStateOf(selectedIds.firstOrNull()?.let { decodeStremioId(it)?.addonId })
    }
    var catalogMap by remember { mutableStateOf<Map<String, List<StremioCatalogDef>>>(emptyMap()) }
    var loadingAddon by remember { mutableStateOf<String?>(null) }
    val stremioRepo = remember { StremioRepository(context.applicationContext) }

    LaunchedEffect(expandedAddon) {
        val addonId = expandedAddon ?: return@LaunchedEffect
        if (catalogMap.containsKey(addonId)) return@LaunchedEffect
        val addon = addons.firstOrNull { it.id == addonId } ?: return@LaunchedEffect
        loadingAddon = addonId
        runCatching {
            val manifest = stremioRepo.fetchManifest(addon.manifestUrl)
            catalogMap = catalogMap + (addonId to manifest.catalogs)
        }
        loadingAddon = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        addons.forEach { addon ->
            val isExpanded = expandedAddon == addon.id
            val catalogs = catalogMap[addon.id] ?: emptyList()
            val isLoading = loadingAddon == addon.id
            val addonSelectedCount = selectedIds.count { decodeStremioId(it)?.addonId == addon.id }

            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expandedAddon = if (isExpanded) null else addon.id }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!addon.logo.isNullOrBlank()) {
                            AsyncImage(
                                model = addon.logo, contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                addon.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.LightGray,
                            )
                            if (addonSelectedCount > 0) {
                                Text("$addonSelectedCount catalog(s) selected", style = MaterialTheme.typography.bodySmall, color = EditBlue)
                            } else {
                                Text("Tap to browse catalogs", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Color.Gray)
                    }

                    if (isExpanded) {
                        HorizontalDivider(color = Color(0xFF333333))
                        if (isLoading) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = EditBlue)
                                Spacer(Modifier.width(12.dp))
                                Text("Loading catalogs…", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (catalogs.isEmpty()) {
                            Text("No catalogs found.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(16.dp))
                        } else {
                            catalogs.forEach { catalog ->
                                val catName = catalog.name ?: "${catalog.type}/${catalog.id}"
                                val encodedId = encodeStremio(addon.id, catalog.type, catalog.id, catName)
                                val isSelected = encodedId in selectedIds
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (isSelected) Color(0xFF1A2E44) else SubItemBg)
                                        .clickable {
                                            val newSet = if (isSelected) selectedIds - encodedId else selectedIds + encodedId
                                            onSelect(newSet.joinToString("\n"))
                                        }
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                                            .background(if (isSelected) EditBlue else Color.Gray.copy(alpha = 0.3f))
                                            .then(if (!isSelected) Modifier.border(1.dp, Color.Gray, RoundedCornerShape(4.dp)) else Modifier),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(catName, style = MaterialTheme.typography.bodyMedium, color = if (isSelected) Color.White else Color.LightGray)
                                        Text("${catalog.type} · ${catalog.id}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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

// ── TMDB picker dialog ────────────────────────────────────────────────────────

@Composable
private fun TmdbPickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var pickerTab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a TMDB source") },
        text = {
            Column {
                TabRow(selectedTabIndex = pickerTab) {
                    Tab(selected = pickerTab == 0, onClick = { pickerTab = 0 }, text = { Text("Presets") })
                    Tab(selected = pickerTab == 1, onClick = { pickerTab = 1 }, text = { Text("Custom") })
                }
                Spacer(Modifier.height(8.dp))
                when (pickerTab) {
                    0 -> LazyColumn(modifier = Modifier.height(360.dp)) {
                        item {
                            TmdbCategoryRow("None", "No linked catalog", current.isBlank()) { onPick("") }
                            HorizontalDivider()
                        }
                        items(HomeCollections.ALL) { cat ->
                            TmdbCategoryRow(cat.title, cat.subtitle, current == cat.id) { onPick(cat.id) }
                        }
                    }
                    1 -> Column(
                        Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TmdbCustomSourceCard("TMDB List", "Enter a public TMDB list ID", "list:", current) { onPick(it) }
                        TmdbCustomSourceCard("Movie Collection", "TMDB movie collection ID (e.g. 131295 = MCU)", "collection:", current) { onPick(it) }
                        TmdbCustomSourceCard("Person Filmography", "TMDB person ID (e.g. 6193 = Leonardo DiCaprio)", "person:", current) { onPick(it) }
                        TmdbCustomSourceCard("Production Company", "TMDB company ID (e.g. 174 = Warner Bros)", "company:", current) { onPick(it) }
                        TmdbCustomSourceCard("TV Network", "TMDB network ID (e.g. 49 = HBO)", "network:", current) { onPick(it) }
                        TmdbGenrePickerCard("Discover Movies by Genre", MOVIE_GENRES, "discover_movie:", current) { onPick(it) }
                        TmdbGenrePickerCard("Discover TV Shows by Genre", TV_GENRES, "discover_tv:", current) { onPick(it) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TmdbCustomSourceCard(
    title: String,
    hint: String,
    prefix: String,
    current: String,
    onPick: (String) -> Unit,
) {
    val initVal = if (current.startsWith(prefix)) current.removePrefix(prefix) else ""
    var input by remember { mutableStateOf(initVal) }
    val isActive = current.startsWith(prefix) && current.removePrefix(prefix).isNotBlank()

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) Color(0xFF1A2E44) else CardBg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("ID", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { if (input.isNotBlank()) onPick("$prefix${input.trim()}") },
                    enabled = input.isNotBlank(),
                ) { Text("Use") }
            }
        }
    }
}

@Composable
private fun TmdbGenrePickerCard(
    title: String,
    genres: List<Pair<Int, String>>,
    prefix: String,
    current: String,
    onPick: (String) -> Unit,
) {
    val activeGenreId = if (current.startsWith(prefix)) {
        current.removePrefix(prefix).removePrefix("genre=").toIntOrNull()
    } else null

    var expanded by remember { mutableStateOf(activeGenreId != null) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (activeGenreId != null) Color(0xFF1A2E44) else CardBg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                    if (activeGenreId != null) {
                        val name = genres.firstOrNull { it.first == activeGenreId }?.second ?: "Genre $activeGenreId"
                        Text(name, style = MaterialTheme.typography.bodySmall, color = EditBlue)
                    }
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Color.Gray)
            }
            if (expanded) {
                HorizontalDivider(color = Color(0xFF333333))
                genres.forEach { (id, name) ->
                    val sel = id == activeGenreId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (sel) Color(0xFF1A2E44) else Color.Transparent)
                            .clickable { onPick("${prefix}genre=$id") }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (sel) EditBlue else Color.Gray.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sel) Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(name, style = MaterialTheme.typography.bodySmall, color = if (sel) Color.White else Color.LightGray)
                    }
                }
            }
        }
    }
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
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}
