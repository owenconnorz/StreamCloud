package com.aioweb.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aioweb.app.BuildConfig
import com.aioweb.app.data.ServiceLocator
import com.aioweb.app.data.collections.HomeCollections
import com.aioweb.app.data.plugins.PluginRepository
import com.aioweb.app.data.updater.UpdateChecker
import com.aioweb.app.data.updater.UpdateInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings hub — OpenTune-style visual language:
 *   • Large "Settings" title, hero app card with version chip.
 *   • 2×2 tile grid (Appearance / Player & audio / Storage / Privacy) for quick access.
 *   • Horizontal chip row for auxiliary sections (Integration = CloudStream plugins,
 *     YT Music account).
 *   • Grouped sections — USER INTERFACE / PLAYER & CONTENT / PRIVACY & SECURITY /
 *     STORAGE & DATA / SYSTEM & ABOUT — with colored icon tiles on each row.
 *
 * Rows expand inline (no sub-navigation) so the whole configuration surface
 * remains on one scroll. Complex choices (quality, equalizer, navigation-bar
 * reorder) drop a Material dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(onOpenPlugins: () -> Unit) {
    val context = LocalContext.current
    val sl = remember { ServiceLocator.get(context) }
    val pluginRepo = remember { PluginRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    // --- State hoisting for every setting ------------------------------
    var backendUrl by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var nsfw by remember { mutableStateOf(false) }
    var videoQuality by remember { mutableStateOf("auto") }
    var audioQuality by remember { mutableStateOf("high") }
    var extLinks by remember { mutableStateOf(true) }
    var autoplay by remember { mutableStateOf(true) }
    var subs by remember { mutableStateOf(true) }
    var dlWifi by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf(false) }
    var pluginsCacheBytes by remember { mutableStateOf(0L) }
    var hfToken by remember { mutableStateOf("") }
    var dynamicColor by remember { mutableStateOf(false) }
    var eqEnabled by remember { mutableStateOf(false) }
    var eqPreset by remember { mutableStateOf("flat") }
    var bassBoost by remember { mutableStateOf(false) }
    var enabledCollections by remember { mutableStateOf<Set<String>>(emptySet()) }
    var uiMode by remember { mutableStateOf("Auto") }

    // Dialog flags
    var showQualityVideoDialog by remember { mutableStateOf(false) }
    var showQualityAudioDialog by remember { mutableStateOf(false) }
    var showEqDialog by remember { mutableStateOf(false) }
    var showCollectionsDialog by remember { mutableStateOf(false) }
    var showNavOrderDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showUiModeDialog by remember { mutableStateOf(false) }

    // Which hub rows are currently expanded. Using a set lets multiple rows be
    // open at once, which feels natural on a single-page settings surface.
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun toggle(id: String) {
        expanded = if (id in expanded) expanded - id else expanded + id
    }

    LaunchedEffect(Unit) {
        backendUrl = sl.settings.backendUrl.first()
        provider = sl.settings.aiProvider.first()
        model = sl.settings.aiModel.first()
        nsfw = sl.settings.nsfwEnabled.first()
        videoQuality = sl.settings.videoQuality.first()
        audioQuality = sl.settings.audioQuality.first()
        extLinks = sl.settings.externalLinksInBrowser.first()
        autoplay = sl.settings.autoplayNext.first()
        subs = sl.settings.subtitlesEnabled.first()
        dlWifi = sl.settings.downloadOverWifiOnly.first()
        hfToken = sl.settings.hfToken.first()
        dynamicColor = sl.settings.dynamicColor.first()
        eqEnabled = sl.settings.eqEnabled.first()
        eqPreset = sl.settings.eqPreset.first()
        bassBoost = sl.settings.bassBoost.first()
        uiMode = sl.settings.uiMode.first()
        val csv = sl.settings.homeCollectionsCsv.first()
        enabledCollections = csv?.takeIf { it.isNotBlank() }?.split(",")?.toSet()
            ?: HomeCollections.ALL.filter { it.defaultEnabled }.map { it.id }.toSet()
        pluginsCacheBytes = pluginRepo.pluginsCacheSize()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Settings",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(20.dp))

        // ---- Hero app card ----------------------------------------------
        HeroCard()
        Spacer(Modifier.height(16.dp))

        // ---- 2x2 big tiles ----------------------------------------------
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigTile(
                    icon = Icons.Default.Palette,
                    label = "Appearance",
                    tint = Color(0xFF5B8DEF),
                    onClick = { toggle("ui-appearance") },
                    modifier = Modifier.weight(1f),
                )
                BigTile(
                    icon = Icons.Default.PlayArrow,
                    label = "Player and\naudio",
                    tint = Color(0xFFB49BFF),
                    onClick = { toggle("pc-player") },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigTile(
                    icon = Icons.Default.FormatListBulleted,
                    label = "Storage",
                    tint = Color(0xFFA9B0BD),
                    onClick = { toggle("sd-storage") },
                    modifier = Modifier.weight(1f),
                )
                BigTile(
                    icon = Icons.Default.Shield,
                    label = "Privacy",
                    tint = Color(0xFFF2AFBC),
                    onClick = { toggle("ps-privacy") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- Horizontal chip row ----------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChipTile(
                icon = Icons.Default.Extension,
                label = "Integration",
                tint = Color(0xFFB49BFF),
                onClick = onOpenPlugins,
            )
            ChipTile(
                icon = Icons.Default.MusicNote,
                label = "Account",
                tint = Color(0xFF5B8DEF),
                onClick = { toggle("account") },
            )
            ChipTile(
                icon = Icons.Default.AutoAwesome,
                label = "AI",
                tint = Color(0xFFFFD479),
                onClick = { toggle("ai-defaults") },
            )
            ChipTile(
                icon = Icons.Default.Chat,
                label = "Discord",
                tint = Color(0xFF7289DA),
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                },
            )
        }
        AnimatedVisibility(visible = "account" in expanded) {
            Column(Modifier.padding(top = 8.dp)) {
                SettingsCard { YtMusicAccountRow() }
            }
        }
        AnimatedVisibility(visible = "ai-defaults" in expanded) {
            Column(Modifier.padding(top = 8.dp)) {
                SettingsCard {
                    Text(
                        "AI defaults",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    ProviderRow(
                        label = "OpenAI · gpt-5.1",
                        selected = provider == "openai",
                        onClick = {
                            provider = "openai"; model = "gpt-5.1"; saved = false
                            scope.launch { sl.settings.setAiProvider("openai"); sl.settings.setAiModel("gpt-5.1") }
                        },
                    )
                    ProviderRow(
                        label = "Anthropic · Claude Sonnet 4.5",
                        selected = provider == "anthropic",
                        onClick = {
                            provider = "anthropic"; model = "claude-sonnet-4-5-20250929"; saved = false
                            scope.launch { sl.settings.setAiProvider("anthropic"); sl.settings.setAiModel("claude-sonnet-4-5-20250929") }
                        },
                    )
                    ProviderRow(
                        label = "Google · Gemini 2.5 Pro",
                        selected = provider == "gemini",
                        onClick = {
                            provider = "gemini"; model = "gemini-2.5-pro"; saved = false
                            scope.launch { sl.settings.setAiProvider("gemini"); sl.settings.setAiModel("gemini-2.5-pro") }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // ---- USER INTERFACE ---------------------------------------------
        GroupHeader("User interface")
        SettingsCard {
            HubRow(
                icon = Icons.Default.Palette,
                tint = Color(0xFF5B8DEF),
                title = "Appearance",
                subtitle = if (dynamicColor) "Material You" else "Dark theme",
                expanded = "ui-appearance" in expanded,
                onClick = { toggle("ui-appearance") },
            )
            AnimatedVisibility(visible = "ui-appearance" in expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ToggleRow(
                        icon = Icons.Default.AutoAwesome,
                        title = "Material You (Monet)",
                        subtitle = "Match colors to your wallpaper · Android 12+",
                        checked = dynamicColor,
                        onChange = {
                            dynamicColor = it
                            scope.launch { sl.settings.setDynamicColor(it) }
                        },
                    )
                    InnerNavRow(
                        icon = Icons.Default.Visibility,
                        title = "Layout / device",
                        subtitle = when (uiMode) {
                            "Mobile" -> "Forced Mobile · compact"
                            "Tablet" -> "Forced Tablet · larger"
                            "Tv" -> "Forced TV · leanback"
                            else -> "Auto · adapts to phone / tablet / TV"
                        },
                        onClick = { showUiModeDialog = true },
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            HubRow(
                icon = Icons.Default.Reorder,
                tint = Color(0xFFB49BFF),
                title = "Navigation bar",
                subtitle = "Reorder tabs",
                chevron = true,
                expanded = false,
                onClick = { showNavOrderDialog = true },
            )
        }

        // ---- PLAYER & CONTENT -------------------------------------------
        GroupHeader("Player & content")
        SettingsCard {
            HubRow(
                icon = Icons.Default.PlayArrow,
                tint = Color(0xFFB49BFF),
                title = "Player and audio",
                subtitle = "Audio quality · ${audioQuality.replaceFirstChar { it.uppercase() }}",
                expanded = "pc-player" in expanded,
                onClick = { toggle("pc-player") },
            )
            AnimatedVisibility(visible = "pc-player" in expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    InnerNavRow(
                        icon = Icons.Default.HighQuality,
                        title = "Default video quality",
                        subtitle = videoQuality.replaceFirstChar { it.uppercase() } +
                            if (videoQuality.matches(Regex("\\d+"))) "p" else "",
                        onClick = { showQualityVideoDialog = true },
                    )
                    InnerNavRow(
                        icon = Icons.Default.GraphicEq,
                        title = "Audio quality",
                        subtitle = audioQuality.replaceFirstChar { it.uppercase() },
                        onClick = { showQualityAudioDialog = true },
                    )
                    ToggleRow(
                        icon = Icons.Default.PlayCircle,
                        title = "Autoplay next",
                        subtitle = "Continue with the next song / episode automatically",
                        checked = autoplay,
                        onChange = { autoplay = it; scope.launch { sl.settings.setAutoplayNext(it) } },
                    )
                    ToggleRow(
                        icon = Icons.Default.Subtitles,
                        title = "Subtitles",
                        subtitle = "Show subtitles when available",
                        checked = subs,
                        onChange = { subs = it; scope.launch { sl.settings.setSubtitlesEnabled(it) } },
                    )
                    ToggleRow(
                        icon = Icons.Default.GraphicEq,
                        title = "Equalizer",
                        subtitle = if (eqEnabled) "On · ${eqPreset.replaceFirstChar { it.uppercase() }} preset"
                                   else "Off — tap to enable & pick a preset",
                        checked = eqEnabled,
                        onChange = {
                            eqEnabled = it
                            scope.launch { sl.settings.setEqEnabled(it) }
                        },
                    )
                    if (eqEnabled) {
                        InnerNavRow(
                            icon = Icons.Default.GraphicEq,
                            title = "EQ preset",
                            subtitle = eqPreset.replaceFirstChar { it.uppercase() },
                            onClick = { showEqDialog = true },
                        )
                    }
                    ToggleRow(
                        icon = Icons.Default.GraphicEq,
                        title = "Bass boost",
                        subtitle = "Adds extra low-end punch",
                        checked = bassBoost,
                        onChange = {
                            bassBoost = it
                            scope.launch { sl.settings.setBassBoost(it) }
                        },
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            HubRow(
                icon = Icons.Default.Language,
                tint = Color(0xFF8E8E9E),
                title = "Content",
                subtitle = "Backend URL, HuggingFace token, home collections",
                expanded = "pc-content" in expanded,
                onClick = { toggle("pc-content") },
            )
            AnimatedVisibility(visible = "pc-content" in expanded) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it; saved = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Backend URL") },
                        supportingText = { Text("Your StreamCloud FastAPI deployment.") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        colors = settingsTfColors(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hfToken,
                        onValueChange = { hfToken = it; saved = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("HuggingFace token") },
                        supportingText = { Text("NSFW image gen + image editing. huggingface.co/settings/tokens.") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        colors = settingsTfColors(),
                    )
                    Spacer(Modifier.height(8.dp))
                    InnerNavRow(
                        icon = Icons.Default.PlayCircle,
                        title = "Home collections",
                        subtitle = "${enabledCollections.size} of ${HomeCollections.ALL.size} enabled",
                        onClick = { showCollectionsDialog = true },
                    )
                    ToggleRow(
                        icon = Icons.Default.Visibility,
                        title = "Show Adult tab (18+)",
                        subtitle = "Replaces Library with Adult section",
                        checked = nsfw,
                        onChange = {
                            nsfw = it
                            scope.launch { sl.settings.setNsfwEnabled(it) }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                sl.settings.setBackendUrl(backendUrl.trim().trimEnd('/'))
                                sl.settings.setHfToken(hfToken.trim())
                                saved = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(if (saved) Icons.Default.Check else Icons.Default.Cloud, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (saved) "Saved" else "Save backend + HF token")
                    }
                }
            }
        }

        // ---- PRIVACY & SECURITY -----------------------------------------
        GroupHeader("Privacy & security")
        SettingsCard {
            HubRow(
                icon = Icons.Default.Shield,
                tint = Color(0xFFF2AFBC),
                title = "Privacy",
                subtitle = "External links, data behaviour",
                expanded = "ps-privacy" in expanded,
                onClick = { toggle("ps-privacy") },
            )
            AnimatedVisibility(visible = "ps-privacy" in expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ToggleRow(
                        icon = Icons.Default.OpenInBrowser,
                        title = "Open external links in browser",
                        subtitle = "Otherwise opens inside an in-app webview",
                        checked = extLinks,
                        onChange = { extLinks = it; scope.launch { sl.settings.setExternalLinksInBrowser(it) } },
                    )
                }
            }
        }

        // ---- STORAGE & DATA ---------------------------------------------
        GroupHeader("Storage & data")
        SettingsCard {
            HubRow(
                icon = Icons.Default.FormatListBulleted,
                tint = Color(0xFFA9B0BD),
                title = "Storage",
                subtitle = "Cache · ${formatBytes(pluginsCacheBytes)}",
                expanded = "sd-storage" in expanded,
                onClick = { toggle("sd-storage") },
            )
            AnimatedVisibility(visible = "sd-storage" in expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    InnerNavRow(
                        icon = Icons.Default.DeleteSweep,
                        title = "Clear app cache",
                        subtitle = "Free up temporary files",
                        onClick = {
                            scope.launch {
                                pluginRepo.clearAppCache()
                                pluginsCacheBytes = pluginRepo.pluginsCacheSize()
                            }
                        },
                    )
                    InnerNavRow(
                        icon = Icons.Default.Extension,
                        title = "CloudStream plugins",
                        subtitle = "${formatBytes(pluginsCacheBytes)} on device",
                        onClick = onOpenPlugins,
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            HubRow(
                icon = Icons.Default.Download,
                tint = Color(0xFFB49BFF),
                title = "Downloads",
                subtitle = if (dlWifi) "Wi-Fi only" else "Any network",
                expanded = "sd-downloads" in expanded,
                onClick = { toggle("sd-downloads") },
            )
            AnimatedVisibility(visible = "sd-downloads" in expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ToggleRow(
                        icon = Icons.Default.Wifi,
                        title = "Download over Wi-Fi only",
                        subtitle = "Avoid using mobile data for downloads",
                        checked = dlWifi,
                        onChange = { dlWifi = it; scope.launch { sl.settings.setDownloadOverWifiOnly(it) } },
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            HubRow(
                icon = Icons.Default.CloudUpload,
                tint = Color(0xFFB49BFF),
                title = "Backup and restore",
                subtitle = "Export / import your library, playlists and settings",
                chevron = true,
                expanded = false,
                onClick = {
                    // Stub — actual backup pipeline is P2. Surface a toast-style info.
                    android.widget.Toast.makeText(
                        context,
                        "Backup & restore is coming soon",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }

        // ---- SYSTEM & ABOUT ---------------------------------------------
        GroupHeader("System & about")
        SettingsCard {
            HubRow(
                icon = Icons.Default.Link,
                tint = Color(0xFF5B8DEF),
                title = "Open supported links",
                subtitle = "Open supported link by default",
                chevron = true,
                expanded = false,
                onClick = {
                    runCatching {
                        val intent = Intent(
                            android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                        ).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                },
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            HubRow(
                icon = Icons.Default.Science,
                tint = Color(0xFFB49BFF),
                title = "Experimental Settings",
                subtitle = "Misc",
                chevron = true,
                expanded = false,
                onClick = {
                    android.widget.Toast.makeText(
                        context,
                        "No experimental flags yet",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            UpdaterRow()
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            HubRow(
                icon = Icons.Default.Info,
                tint = Color(0xFF8E8E9E),
                title = "About",
                subtitle = "StreamCloud · v${BuildConfig.VERSION_NAME}",
                chevron = true,
                expanded = false,
                onClick = { showAboutDialog = true },
            )
        }
        Spacer(Modifier.height(40.dp))
    }

    // ---- Dialogs --------------------------------------------------------
    if (showQualityVideoDialog) {
        QualityDialog(
            title = "Default video quality",
            options = listOf(
                "auto" to "Auto (recommended)",
                "1080" to "1080p",
                "720" to "720p",
                "480" to "480p",
            ),
            selected = videoQuality,
            onSelect = {
                videoQuality = it
                scope.launch { sl.settings.setVideoQuality(it) }
                showQualityVideoDialog = false
            },
            onDismiss = { showQualityVideoDialog = false },
        )
    }
    if (showQualityAudioDialog) {
        QualityDialog(
            title = "Audio quality",
            options = listOf(
                "high" to "High (best available)",
                "medium" to "Medium",
                "low" to "Low (data saver)",
            ),
            selected = audioQuality,
            onSelect = {
                audioQuality = it
                scope.launch { sl.settings.setAudioQuality(it) }
                showQualityAudioDialog = false
            },
            onDismiss = { showQualityAudioDialog = false },
        )
    }
    if (showEqDialog) {
        QualityDialog(
            title = "Equalizer preset",
            options = listOf(
                "flat" to "Flat (no change)",
                "pop" to "Pop",
                "rock" to "Rock",
                "jazz" to "Jazz",
                "bass" to "Bass booster",
                "vocal" to "Vocal",
            ),
            selected = eqPreset,
            onSelect = {
                eqPreset = it
                scope.launch { sl.settings.setEqPreset(it) }
                showEqDialog = false
            },
            onDismiss = { showEqDialog = false },
        )
    }
    if (showCollectionsDialog) {
        CollectionsDialog(
            enabled = enabledCollections,
            onToggle = { id, on ->
                enabledCollections =
                    if (on) enabledCollections + id else enabledCollections - id
            },
            onSave = {
                val ordered = HomeCollections.ALL.map { it.id }.filter { it in enabledCollections }
                scope.launch { sl.settings.setHomeCollections(ordered) }
                showCollectionsDialog = false
            },
            onDismiss = { showCollectionsDialog = false },
        )
    }
    if (showNavOrderDialog) {
        NavOrderDialog(
            nsfw = nsfw,
            onDismiss = { showNavOrderDialog = false },
        )
    }
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
    if (showUiModeDialog) {
        UiModeDialog(
            current = uiMode,
            onPick = { picked ->
                uiMode = picked
                showUiModeDialog = false
                scope.launch { sl.settings.setUiMode(picked) }
            },
            onDismiss = { showUiModeDialog = false },
        )
    }
}

// ======================================================================
//                               Layout atoms
// ======================================================================

@Composable
private fun HeroCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "StreamCloud",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BigTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChipTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GroupHeader(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        content = content,
    )
}

@Composable
private fun HubRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    expanded: Boolean,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "chevron",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (chevron) Icons.Default.ChevronRight else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (chevron) 0f else rotation),
        )
    }
}

/** Smaller nested row used inside expanded HubRow sections. */
@Composable
private fun InnerNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProviderRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

// ======================================================================
//                              Dialogs
// ======================================================================

@Composable
private fun QualityDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 10.dp),
                    ) {
                        RadioButton(selected = selected == value, onClick = { onSelect(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun CollectionsDialog(
    enabled: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Home collections") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Pick which rows show on the Movies tab. The first enabled collection drives the top hero banner.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                HomeCollections.ALL.forEach { c ->
                    val checked = c.id in enabled
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(c.id, !checked) }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            c.emoji,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium)
                            Text(c.subtitle, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = checked, onCheckedChange = { onToggle(c.id, it) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Navigation-bar reorder dialog — lets the user shuffle the middle tabs
 * (Movies / Music / AI / Library-or-Adult) with up/down buttons and persists
 * the result as a CSV in [SettingsRepository.setNavTabOrder].
 *
 * Settings is intentionally not in this list — it's pinned at the end of the
 * nav bar so the user can never lose access to this screen.
 */
@Composable
private fun NavOrderDialog(nsfw: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sl = remember(context) { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()

    data class NavItem(val id: String, val label: String, val icon: ImageVector)

    // Build the initial ordered list from SettingsRepo. Library is ALWAYS
    // present; Adult is additive and only appears when the NSFW toggle is on.
    // Anything missing is appended at the end so a newly-added tab remains
    // reachable.
    val all = remember(nsfw) {
        buildList {
            add(NavItem("movies", "Movies", Icons.Default.PlayArrow))
            add(NavItem("music", "Music", Icons.Default.MusicNote))
            add(NavItem("ai", "AI", Icons.Default.AutoAwesome))
            add(NavItem("library", "Library", Icons.Default.FormatListBulleted))
            if (nsfw) add(NavItem("adult", "Adult", Icons.Default.Visibility))
        }
    }
    val byId = all.associateBy { it.id }
    var order by remember { mutableStateOf<List<NavItem>>(all) }

    LaunchedEffect(nsfw) {
        val csv = sl.settings.navTabOrderCsv.first()
        val savedOrder = csv?.split(",")?.mapNotNull { byId[it.trim()] } ?: emptyList()
        val remaining = all.filter { it.id !in savedOrder.map(NavItem::id) }
        order = (savedOrder + remaining).distinctBy { it.id }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reorder navigation bar") },
        text = {
            Column {
                Text(
                    "Use the arrows to reorder tabs. Settings is always pinned at the end.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                order.forEachIndexed { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            item.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    order = order.toMutableList().apply {
                                        val tmp = this[index]
                                        this[index] = this[index - 1]
                                        this[index - 1] = tmp
                                    }
                                }
                            },
                            enabled = index > 0,
                        ) { Icon(Icons.Default.ArrowUpward, "Move up") }
                        IconButton(
                            onClick = {
                                if (index < order.lastIndex) {
                                    order = order.toMutableList().apply {
                                        val tmp = this[index]
                                        this[index] = this[index + 1]
                                        this[index + 1] = tmp
                                    }
                                }
                            },
                            enabled = index < order.lastIndex,
                        ) { Icon(Icons.Default.ArrowDownward, "Move down") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { sl.settings.setNavTabOrder(order.map { it.id }) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About StreamCloud") },
        text = {
            Column {
                Text("Version ${BuildConfig.VERSION_NAME} · code ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}"),
                        ),
                    )
                }) { Text("Source code (GitHub)") }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/issues/new"),
                        ),
                    )
                }) {
                    Icon(Icons.Default.BugReport, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Report a bug")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun settingsTfColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

// ======================================================================
//                              Composite rows
// ======================================================================

@Composable
private fun UpdaterRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { UpdateChecker(context.applicationContext) }

    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !checking && !downloading) {
                checking = true; status = null; update = null
                scope.launch {
                    try {
                        val info = checker.fetchLatest(includeOlder = false)
                        update = info
                        status = if (info == null) "You're on the latest build."
                        else "${info.title} available · ${formatBytes(info.sizeBytes)}"
                    } catch (e: Exception) {
                        status = "Check failed: ${e.message}"
                    } finally {
                        checking = false
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF5B8DEF).copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.SystemUpdate, null, tint = Color(0xFF5B8DEF), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Updates",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                status ?: "v${BuildConfig.VERSION_NAME} · Tap to check for updates",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (downloading) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        when {
            checking || downloading -> CircularProgressIndicator(
                Modifier.size(20.dp), strokeWidth = 2.dp,
            )
            update?.isNewerThanInstalled == true -> Button(
                onClick = {
                    val info = update ?: return@Button
                    downloading = true; progress = 0f
                    scope.launch {
                        try {
                            val apk = checker.downloadApk(info) { progress = it }
                            checker.launchInstaller(apk)
                            status = "Launching installer…"
                        } catch (e: Exception) {
                            status = "Download failed: ${e.message}"
                        } finally {
                            downloading = false
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp),
            ) { Text("Install") }
        }
    }
}

/**
 * "YouTube Music account" inline row — tapping opens the WebView login flow
 * which captures the `Cookie:` header and persists it to SettingsRepository.
 */
@Composable
private fun YtMusicAccountRow() {
    val context = LocalContext.current
    val sl = remember(context) { ServiceLocator.get(context) }
    val cookie by sl.settings.ytMusicCookie.collectAsState(initial = "")
    val userName by sl.settings.ytMusicUserName.collectAsState(initial = "")
    val signedIn = cookie.isNotBlank()
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !signedIn) {
                val intent = Intent(
                    context,
                    com.aioweb.app.ui.account.YtMusicLoginActivity::class.java,
                )
                context.startActivity(intent)
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (signedIn) Icons.Default.Logout else Icons.Default.Login,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (signedIn) "YouTube Music" else "Sign in to YouTube Music",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (signedIn) userName.ifBlank { "Signed in" }
                else "Personalised mixes, recommendations and library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (signedIn) {
            TextButton(onClick = {
                scope.launch {
                    sl.settings.clearYtMusicAccount()
                    com.aioweb.app.data.newpipe.NewPipeDownloader.instance.ytMusicCookie = ""
                    runCatching {
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    }
                }
            }) { Text("Sign out") }
        }
    }
}


@Composable
private fun UiModeDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple("Auto", "Auto-detect", "Phone, tablet or TV based on the device"),
        Triple("Mobile", "Mobile", "Compact phone layout"),
        Triple("Tablet", "Tablet", "Wider canvas, slightly larger text"),
        Triple("Tv", "TV / leanback", "Largest text, designed for D-pad / remote"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layout / device") },
        text = {
            Column {
                options.forEach { (id, label, sub) ->
                    val selected = current.equals(id, ignoreCase = true)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(id) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = { onPick(id) })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
