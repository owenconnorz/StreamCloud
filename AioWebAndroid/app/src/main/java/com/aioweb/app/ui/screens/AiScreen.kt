package com.aioweb.app.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Image as ComposeImage
import com.aioweb.app.ui.viewmodel.AiViewModel

private enum class AiTab(val label: String) { Chat("Chat"), Image("Image gen"), Edit("Image edit") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen() {
    val context = LocalContext.current
    val vm: AiViewModel = viewModel(factory = AiViewModel.factory(context))
    val state by vm.state.collectAsState()

    var tab by remember { mutableStateOf(AiTab.Chat) }
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Spacer(Modifier.height(12.dp))
        Text(
            "AI",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Text(
            "Powered by Emergent Universal Key",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(14.dp))

        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            AiTab.values().forEach {
                Tab(
                    selected = tab == it,
                    onClick = { tab = it },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = {
                        Icon(
                            when (it) {
                                AiTab.Chat -> Icons.Default.AutoAwesome
                                AiTab.Image -> Icons.Default.Image
                                AiTab.Edit -> Icons.Default.Brush
                            },
                            null
                        )
                    },
                    text = { Text(it.label) }
                )
            }
        }

        when (tab) {
            AiTab.Chat -> {
                val listState = rememberLazyListState()
                LaunchedEffect(state.messages.size) {
                    if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.messages.isEmpty()) {
                        item { EmptyChatHint() }
                    }
                    items(state.messages) { msg ->
                        MessageBubble(msg.fromUser, msg.text)
                    }
                    if (state.loading) {
                        item { TypingDots() }
                    }
                    state.error?.let {
                        item {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
                ChatInput(
                    value = input,
                    onValueChange = { input = it },
                    onSend = {
                        if (input.isNotBlank()) {
                            vm.sendChat(input)
                            input = ""
                        }
                    }
                )
            }

            AiTab.Image -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.imagePrompt,
                        onValueChange = vm::setImagePrompt,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Describe the image (e.g. 'neon cyberpunk cat eating ramen, 4k')") },
                        minLines = 3,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        )
                    )
                    // NSFW (uncensored) toggle — proxies to fal.ai using the user's key.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "NSFW (uncensored)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (state.nsfwMode)
                                    "Routed to HuggingFace · uses your HF token (Settings)"
                                else
                                    "Off — uses Nano Banana with safety filters",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.nsfwMode, onCheckedChange = vm::setNsfwMode)
                    }
                    Button(
                        onClick = vm::generateImage,
                        enabled = !state.imageLoading && state.imagePrompt.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (state.imageLoading) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.AutoAwesome, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (state.nsfwMode) "Generate with HuggingFace (uncensored)"
                                else "Generate with Nano Banana"
                            )
                        }
                    }
                    state.imageError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    state.generatedImageBase64?.let { b64 ->
                        val bytes = remember(b64) { Base64.decode(b64, Base64.DEFAULT) }
                        val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        if (bmp != null) {
                            ComposeImage(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Generated image",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                            )
                        }
                    }
                }
            }

            AiTab.Edit -> {
                EditImageTab(state = state, vm = vm)
            }
        }
    }
}

@Composable
private fun EmptyChatHint() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(32.dp)
    ) {
        Box(
            Modifier.size(80.dp).clip(RoundedCornerShape(50)).background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Ask AioWeb AI anything",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Powered by GPT, Claude & Gemini",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(fromUser: Boolean, text: String) {
    Row(Modifier.fillMaxWidth()) {
        if (fromUser) Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            if (!fromUser) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                        )
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (fromUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text,
                    color = if (fromUser) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (fromUser) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp))
                }
            }
        }
        if (!fromUser) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun TypingDots() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        CircularProgressIndicator(
            Modifier.size(20.dp), strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInput(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(12.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message AioWeb AI…") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(enabled = value.isNotBlank(), onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send, "Send",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditImageTab(state: com.aioweb.app.ui.viewmodel.AiState, vm: AiViewModel) {
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    vm.setSourceImage(b64)
                }
            }
        }
    }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { pickImage.launch("image/*") },
            contentAlignment = Alignment.Center,
        ) {
            val src = state.sourceImageBase64
            if (src != null) {
                val bytes = remember(src) { Base64.decode(src, Base64.DEFAULT) }
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bmp != null) {
                    ComposeImage(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Source",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap to pick an image",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.editPrompt,
            onValueChange = vm::setEditPrompt,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Describe the edit (e.g. 'add neon lights, cinematic')") },
            minLines = 2,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Button(
            onClick = vm::editImage,
            enabled = !state.editLoading
                && !state.sourceImageBase64.isNullOrBlank()
                && state.editPrompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (state.editLoading) {
                CircularProgressIndicator(
                    Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.Brush, null)
                Spacer(Modifier.width(8.dp))
                Text("Edit with HuggingFace")
            }
        }
        state.editError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        state.editedImageBase64?.let { b64 ->
            val bytes = remember(b64) { Base64.decode(b64, Base64.DEFAULT) }
            val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) {
                ComposeImage(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Edited image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}
