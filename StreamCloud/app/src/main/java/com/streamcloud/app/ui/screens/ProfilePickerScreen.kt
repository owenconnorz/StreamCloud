package com.streamcloud.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamcloud.app.data.profiles.BUILT_IN_AVATAR_SEEDS
import com.streamcloud.app.data.profiles.ProfileRepository
import com.streamcloud.app.data.profiles.UserProfile
import com.streamcloud.app.data.profiles.builtInAvatarUrl
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.tvFocusBorder
import java.security.MessageDigest

private fun hashPin(pin: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

private sealed interface PickerView {
    data object Grid : PickerView
    data class Edit(val profile: UserProfile, val isNew: Boolean) : PickerView
}

@Composable
fun ProfilePickerScreen(
    repo: ProfileRepository,
    onDone: () -> Unit,
) {
    val profiles by repo.profiles.collectAsState(initial = repo.currentProfiles())
    val activeId by repo.activeProfileId.collectAsState(initial = repo.currentActiveId())

    var view        by remember { mutableStateOf<PickerView>(PickerView.Grid) }
    var editProfile by remember { mutableStateOf<UserProfile?>(null) }
    var editIsNew   by remember { mutableStateOf(false) }

    var pinTarget   by remember { mutableStateOf<UserProfile?>(null) }

    BackHandler(view !is PickerView.Grid) {
        view = PickerView.Grid
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        AnimatedContent(
            targetState = view,
            transitionSpec = {
                if (targetState is PickerView.Edit) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "profileNav",
        ) { currentView ->
            when {
                currentView is PickerView.Grid -> {
                    ProfileGridView(
                        profiles  = profiles,
                        activeId  = activeId,
                        onSelect  = { p ->
                            if (p.pinHash.isNotEmpty() && p.id != activeId) {
                                pinTarget = p
                            } else {
                                repo.setActiveProfile(p.id)
                                onDone()
                            }
                        },
                        onEdit    = { p ->
                            editProfile = p
                            editIsNew   = false
                            view = PickerView.Edit(p, false)
                        },
                        onAddNew  = {
                            val newP = UserProfile.create("New Profile")
                            editProfile = newP
                            editIsNew   = true
                            view = PickerView.Edit(newP, true)
                        },
                        onDone    = onDone,
                    )
                }
                currentView is PickerView.Edit && editProfile != null -> {
                    EditProfileView(
                        profile   = editProfile!!,
                        isNew     = editIsNew,
                        onBack    = { view = PickerView.Grid },
                        onSave    = { updated ->
                            repo.saveProfile(updated)
                            if (editIsNew) repo.setActiveProfile(updated.id)
                            view = PickerView.Grid
                        },
                        onDelete  = { id ->
                            repo.deleteProfile(id)
                            view = PickerView.Grid
                        },
                    )
                }
                else -> { /* fallback */ }
            }
        }

        if (pinTarget != null) {
            PinEntryDialog(
                profileName = pinTarget!!.name,
                onConfirm   = { enteredPin ->
                    if (hashPin(enteredPin) == pinTarget!!.pinHash) {
                        repo.setActiveProfile(pinTarget!!.id)
                        pinTarget = null
                        onDone()
                    } else {
                        pinTarget = null
                    }
                },
                onDismiss   = { pinTarget = null },
            )
        }
    }
}

@Composable
private fun ProfileGridView(
    profiles: List<UserProfile>,
    activeId: String?,
    onSelect: (UserProfile) -> Unit,
    onEdit: (UserProfile) -> Unit,
    onAddNew: () -> Unit,
    onDone: () -> Unit,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    val initialFocusRequester = remember { FocusRequester() }
    val initialFocusId = profiles.firstOrNull { it.id == activeId }?.id
        ?: profiles.firstOrNull()?.id
    LaunchedEffect(isTv, initialFocusId) {
        if (isTv) {
            repeat(10) {
                if (runCatching { initialFocusRequester.requestFocus() }.isSuccess) {
                    return@LaunchedEffect
                }
                delay(120)
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            "Who's watching?",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Spacer(Modifier.height(36.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ProfileGridItem(
                    profile  = profile,
                    isActive = profile.id == activeId,
                    onSelect = { onSelect(profile) },
                    onEdit   = { onEdit(profile) },
                    initialFocusRequester = if (isTv && profile.id == initialFocusId) {
                        initialFocusRequester
                    } else null,
                )
            }
            item {
                AddProfileItem(
                    onClick = onAddNew,
                    initialFocusRequester = if (isTv && profiles.isEmpty()) initialFocusRequester else null,
                )
            }
        }

        if (!isTv) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDone,
                shape   = RoundedCornerShape(50),
                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E4EA8)),
                modifier = Modifier
                    .padding(horizontal = 80.dp)
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.height(24.dp))
        } else {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileGridItem(
    profile: UserProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // On TV the avatar Box handles clicks; Column stays non-clickable to
        // avoid a second focusable node fighting for D-pad input.
        modifier = if (isTv) Modifier else Modifier.clickable(onClick = onSelect),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier
                    .size(120.dp)
                    .then(
                        if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else Modifier
                    )
                    .clip(CircleShape)
                    .then(
                        if (isActive)
                            Modifier.border(3.dp, Color(0xFF1E4EA8), CircleShape)
                        else Modifier
                    )
                    .then(
                        if (isTv)
                            // tvFocusBorder makes the circle focusable and shows a white
                            // border when focused. combinedClickable routes single press to
                            // onSelect and a held centre button to onEdit.
                            Modifier
                                .tvFocusBorder(CircleShape)
                                .combinedClickable(onClick = onSelect, onLongClick = onEdit)
                        else Modifier
                    ),
            ) {
                AvatarImage(
                    profile  = profile,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Edit pencil is replaced by hold-to-edit on TV.
            if (!isTv) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E4EA8))
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            profile.name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddProfileItem(
    onClick: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
) {
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (isTv) Modifier else Modifier.clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .size(120.dp)
                .then(
                    if (initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else Modifier
                )
                .clip(CircleShape)
                .background(Color(0xFF2A2A2E))
                .then(
                    if (isTv) {
                        Modifier.tvFocusBorder(CircleShape).clickable(onClick = onClick)
                    } else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add Profile",
                tint = Color(0xFF8E8E93),
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Add Profile",
            color = Color(0xFF8E8E93),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AvatarImage(profile: UserProfile, modifier: Modifier = Modifier) {
    val url = when {
        profile.avatarUrl.isNotBlank() -> profile.avatarUrl
        profile.avatarSeed.isNotBlank() -> builtInAvatarUrl(profile.avatarSeed)
        else -> builtInAvatarUrl(profile.name)
    }
    if (url.isNotBlank()) {
        AsyncImage(
            model = url,
            contentDescription = profile.name,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(Color(0xFF2A2A2E)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Person, null, tint = Color(0xFF8E8E93), modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun EditProfileView(
    profile: UserProfile,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (UserProfile) -> Unit,
    onDelete: (String) -> Unit,
) {
    var name         by rememberSaveable { mutableStateOf(profile.name) }
    var customUrl    by rememberSaveable { mutableStateOf(profile.avatarUrl) }
    var avatarSeed   by rememberSaveable { mutableStateOf(profile.avatarSeed.ifBlank { profile.name }) }
    var pinHash      by rememberSaveable { mutableStateOf(profile.pinHash) }

    var showPinDialog  by remember { mutableStateOf(false) }
    var showDelConfirm by remember { mutableStateOf(false) }

    val preview = profile.copy(
        name      = name.ifBlank { "Profile" },
        avatarUrl = customUrl,
        avatarSeed = avatarSeed,
    )

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
            }
            Text(
                if (isNew) "New Profile" else "Edit Profile",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape),
                        ) {
                            AvatarImage(preview, Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                preview.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text(
                                if (pinHash.isNotEmpty()) "PIN locked" else "No PIN",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 20) name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color(0xFF1E4EA8),
                            unfocusedBorderColor = Color(0xFF3A3A3C),
                            focusedLabelColor    = Color(0xFF1E4EA8),
                            unfocusedLabelColor  = Color(0xFF8E8E93),
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White,
                            cursorColor          = Color(0xFF1E4EA8),
                        ),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Custom avatar URL",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Paste an image link, or leave empty to use the avatar catalog below.",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        placeholder = { Text("https://...", color = Color(0xFF48484A)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color(0xFF1E4EA8),
                            unfocusedBorderColor = Color(0xFF3A3A3C),
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White,
                            cursorColor          = Color(0xFF1E4EA8),
                        ),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Choose an avatar",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Select an avatar for this profile.",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.height(((BUILT_IN_AVATAR_SEEDS.size / 5 + 1) * 72).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = false,
                    ) {
                        items(BUILT_IN_AVATAR_SEEDS) { seed ->
                            val selected = avatarSeed == seed && customUrl.isBlank()
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (selected)
                                            Modifier.border(2.dp, Color(0xFF1E4EA8), CircleShape)
                                        else Modifier
                                    )
                                    .clickable {
                                        avatarSeed = seed
                                        customUrl  = ""
                                    },
                            ) {
                                AsyncImage(
                                    model = builtInAvatarUrl(seed),
                                    contentDescription = seed,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (selected) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(Color(0x661E4EA8)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Security",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add a PIN to lock this profile before switching into it.",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showPinDialog = true },
                        shape   = RoundedCornerShape(12.dp),
                        colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E4EA8)),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(
                            if (pinHash.isNotEmpty()) Icons.Default.Lock else Icons.Default.LockOpen,
                            null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pinHash.isNotEmpty()) "Change PIN" else "Set PIN Lock",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (pinHash.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { pinHash = "" },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Remove PIN", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = { showDelConfirm = true },
                    shape   = RoundedCornerShape(12.dp),
                    colors  = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF3D1A1A),
                        contentColor   = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Profile", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
        ) {
            Button(
                onClick = {
                    onSave(
                        profile.copy(
                            name       = name.ifBlank { "Profile" },
                            avatarUrl  = customUrl,
                            avatarSeed = avatarSeed,
                            pinHash    = pinHash,
                        )
                    )
                },
                shape   = RoundedCornerShape(12.dp),
                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E4EA8)),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Save Changes", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showPinDialog) {
        PinSetDialog(
            currentHash = pinHash,
            onSet       = { newHash -> pinHash = newHash; showPinDialog = false },
            onDismiss   = { showPinDialog = false },
        )
    }

    if (showDelConfirm) {
        AlertDialog(
            onDismissRequest = { showDelConfirm = false },
            title  = { Text("Delete Profile?") },
            text   = { Text("This will permanently remove \"${profile.name}\" and all its settings.") },
            confirmButton = {
                TextButton(onClick = { showDelConfirm = false; onDelete(profile.id) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelConfirm = false }) { Text("Cancel") }
            },
            containerColor = Color(0xFF1C1C1E),
            titleContentColor  = Color.White,
            textContentColor   = Color(0xFF8E8E93),
        )
    }
}

@Composable
private fun PinSetDialog(
    currentHash: String,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var step    by remember { mutableStateOf(if (currentHash.isNotEmpty()) 0 else 1) }
    var current by remember { mutableStateOf("") }
    var first   by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error   by remember { mutableStateOf("") }

    val (title, hint, value, setter) = when (step) {
        0    -> Tuple4("Enter current PIN",  "Current PIN", current) { v: String -> current = v }
        1    -> Tuple4("Set new PIN",        "New PIN (4 digits)", first) { v: String -> first = v }
        else -> Tuple4("Confirm new PIN",    "Confirm PIN", confirm) { v: String -> confirm = v }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) setter(it) },
                    label = { Text(hint) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF1E4EA8),
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        cursorColor          = Color(0xFF1E4EA8),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = ""
                when (step) {
                    0 -> {
                        if (hashPin(current) != currentHash) {
                            error = "Incorrect PIN"
                        } else {
                            step = 1
                            current = ""
                        }
                    }
                    1 -> {
                        if (first.length < 4) {
                            error = "PIN must be 4 digits"
                        } else {
                            step = 2
                        }
                    }
                    2 -> {
                        if (confirm != first) {
                            error = "PINs don't match"
                            confirm = ""
                        } else {
                            onSet(hashPin(first))
                        }
                    }
                }
            }) { Text("Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Color(0xFF1C1C1E),
        titleContentColor = Color.White,
        textContentColor  = Color.White,
    )
}

@Composable
private fun PinEntryDialog(
    profileName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN") },
        text = {
            Column {
                Text("\"$profileName\" is PIN protected.", color = Color(0xFF8E8E93), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (error) {
                    Text("Incorrect PIN", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF1E4EA8),
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        cursorColor          = Color(0xFF1E4EA8),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = false
                onConfirm(pin)
            }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Color(0xFF1C1C1E),
        titleContentColor = Color.White,
        textContentColor  = Color.White,
    )
}

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
private operator fun <A, B, C, D> Tuple4<A, B, C, D>.component1() = a
private operator fun <A, B, C, D> Tuple4<A, B, C, D>.component2() = b
private operator fun <A, B, C, D> Tuple4<A, B, C, D>.component3() = c
private operator fun <A, B, C, D> Tuple4<A, B, C, D>.component4() = d
