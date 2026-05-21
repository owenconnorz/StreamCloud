package com.streamcloud.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamcloud.app.BuildConfig
import com.streamcloud.app.data.AppLogger
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.collections.HomeCollections
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.updater.UpdateChecker
import com.streamcloud.app.data.updater.UpdateInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ── Hub icon colours ────────────────────────────────────────────────────────
private val HubIconBg  = Color(0xFF1B2D52)
private val HubIconFg  = Color(0xFF5B8DEF)

// ── Section accent colours ──────────────────────────────────────────────────
private val ColourAppearance = Color(0xFF5B8DEF)
private val ColourAccount    = Color(0xFF4CAF88)
private val ColourPlayer     = Color(0xFFB49BFF)
private val ColourAi         = Color(0xFFFFD479)
private val ColourContent    = Color(0xFFFF9B5E)
private val ColourPrivacy    = Color(0xFFF2AFBC)
private val ColourStorage    = Color(0xFFA9C96C)
private val ColourSystem     = Color(0xFF8E9CBE)
private val ColourSonos      = Color(0xFF56C8D8)

// ── Page enum ───────────────────────────────────────────────────────────────
private enum class SettingsPage {
    SystemUpdate, Appearance, PlayerAudio, Account,
    ListenTogether, Content, AiLyrics, Privacy,
    Storage, BackupRestore, About, Logs
}

// ══════════════════════════════════════════════════════════════════════════════
//                          Main entry composable
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(onOpenPlugins: () -> Unit) {
    val context = LocalContext.current
    val sl      = remember { ServiceLocator.get(context) }
    val pluginRepo = remember { PluginRepository(context.applicationContext) }
    val scope   = rememberCoroutineScope()

    // ── All settings state ─────────────────────────────────────────────────
    var backendUrl          by remember { mutableStateOf("") }
    var provider            by remember { mutableStateOf("") }
    var model               by remember { mutableStateOf("") }
    var nsfw                by remember { mutableStateOf(false) }
    var videoQuality        by remember { mutableStateOf("auto") }
    var audioQuality        by remember { mutableStateOf("high") }
    var extLinks            by remember { mutableStateOf(true) }
    var autoplay            by remember { mutableStateOf(true) }
    var subs                by remember { mutableStateOf(true) }
    var dlWifi              by remember { mutableStateOf(true) }
    var hfToken             by remember { mutableStateOf("") }
    var dynamicColor        by remember { mutableStateOf(false) }
    var eqEnabled           by remember { mutableStateOf(false) }
    var eqPreset            by remember { mutableStateOf("flat") }
    var bassBoost           by remember { mutableStateOf(false) }
    var enabledCollections  by remember { mutableStateOf<Set<String>>(emptySet()) }
    var uiMode              by remember { mutableStateOf("Auto") }
    var themeMode           by remember { mutableStateOf("dark") }
    var colorPalette        by remember { mutableStateOf("default") }
    var highRefreshRate     by remember { mutableStateOf(true) }
    var newMiniPlayer       by remember { mutableStateOf(true) }
    var pureBlackMiniPlayer by remember { mutableStateOf(false) }
    var newPlayerDesign     by remember { mutableStateOf(true) }
    var skipSilence         by remember { mutableStateOf(false) }
    var keepScreenOn        by remember { mutableStateOf(false) }
    var persistentQueue     by remember { mutableStateOf(true) }
    var crossfadeDuration   by remember { mutableStateOf("0") }
    var listenHistory       by remember { mutableStateOf(true) }
    var pauseListenHistory  by remember { mutableStateOf(false) }
    var safeSearch          by remember { mutableStateOf(false) }
    var explicitContent     by remember { mutableStateOf(true) }
    var contentLanguage     by remember { mutableStateOf("en") }
    var contentCountry      by remember { mutableStateOf("US") }
    var lyricsSource        by remember { mutableStateOf("lrclib") }
    var syncedLyrics        by remember { mutableStateOf(true) }
    var loudnessNorm        by remember { mutableStateOf(false) }
    var pluginsCacheBytes   by remember { mutableStateOf(0L) }

    // ── Dialog visibility flags ────────────────────────────────────────────
    var showQualityVideoDialog  by remember { mutableStateOf(false) }
    var showQualityAudioDialog  by remember { mutableStateOf(false) }
    var showEqDialog            by remember { mutableStateOf(false) }
    var showCollectionsDialog   by remember { mutableStateOf(false) }
    var showNavOrderDialog      by remember { mutableStateOf(false) }
    var showAboutDialog         by remember { mutableStateOf(false) }
    var showUiModeDialog        by remember { mutableStateOf(false) }
    var showAiDialog            by remember { mutableStateOf(false) }
    var showBackendDialog       by remember { mutableStateOf(false) }
    var showCrossfadeDialog     by remember { mutableStateOf(false) }
    var showLanguageDialog      by remember { mutableStateOf(false) }
    var showCountryDialog       by remember { mutableStateOf(false) }
    var showLyricsSourceDialog  by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        backendUrl          = sl.settings.backendUrl.first()
        provider            = sl.settings.aiProvider.first()
        model               = sl.settings.aiModel.first()
        nsfw                = sl.settings.nsfwEnabled.first()
        videoQuality        = sl.settings.videoQuality.first()
        audioQuality        = sl.settings.audioQuality.first()
        extLinks            = sl.settings.externalLinksInBrowser.first()
        autoplay            = sl.settings.autoplayNext.first()
        subs                = sl.settings.subtitlesEnabled.first()
        dlWifi              = sl.settings.downloadOverWifiOnly.first()
        hfToken             = sl.settings.hfToken.first()
        dynamicColor        = sl.settings.dynamicColor.first()
        eqEnabled           = sl.settings.eqEnabled.first()
        eqPreset            = sl.settings.eqPreset.first()
        bassBoost           = sl.settings.bassBoost.first()
        uiMode              = sl.settings.uiMode.first()
        themeMode           = sl.settings.theme.first()
        colorPalette        = sl.settings.colorPalette.first()
        highRefreshRate     = sl.settings.highRefreshRate.first()
        newMiniPlayer       = sl.settings.newMiniPlayerDesign.first()
        pureBlackMiniPlayer = sl.settings.pureBlackMiniPlayer.first()
        newPlayerDesign     = sl.settings.newPlayerDesign.first()
        skipSilence         = sl.settings.skipSilence.first()
        keepScreenOn        = sl.settings.keepScreenOn.first()
        persistentQueue     = sl.settings.persistentQueue.first()
        crossfadeDuration   = sl.settings.crossfadeDuration.first()
        listenHistory       = sl.settings.listenHistoryEnabled.first()
        pauseListenHistory  = sl.settings.pauseListenHistory.first()
        lyricsSource        = sl.settings.lyricsSource.first()
        syncedLyrics        = sl.settings.syncedLyrics.first()
        loudnessNorm        = sl.settings.loudnessNormalization.first()
        safeSearch          = sl.settings.safeSearch.first()
        explicitContent     = sl.settings.explicitContent.first()
        contentLanguage     = sl.settings.contentLanguage.first()
        contentCountry      = sl.settings.contentCountry.first()
        val csv = sl.settings.homeCollectionsCsv.first()
        enabledCollections  = csv?.takeIf { it.isNotBlank() }?.split(",")?.toSet()
            ?: HomeCollections.ALL.filter { it.defaultEnabled }.map { it.id }.toSet()
        pluginsCacheBytes   = pluginRepo.pluginsCacheSize()
    }

    // ── Page navigation state ──────────────────────────────────────────────
    var currentPage by remember { mutableStateOf<SettingsPage?>(null) }

    BackHandler(enabled = currentPage != null) { currentPage = null }

    // ── Animated page transitions ──────────────────────────────────────────
    AnimatedContent(
        targetState = currentPage,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            } else {
                slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
            }
        },
        label = "settings_page",
    ) { page ->
        when (page) {

            // ── Hub ──────────────────────────────────────────────────────
            null -> SettingsHubList(onNavigate = { currentPage = it })

            // ── System update ────────────────────────────────────────────
            SettingsPage.SystemUpdate -> SubPageScaffold(
                title = "System update",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    UpdaterRow()
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    SettingNav(
                        icon = Icons.Default.Link, tint = ColourSystem,
                        title = "Open supported links",
                        subtitle = "Set StreamCloud as default for supported URLs",
                        onClick = {
                            runCatching {
                                val intent = Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.Science, tint = ColourSystem,
                        title = "Experimental settings",
                        subtitle = "Misc developer flags",
                        onClick = {
                            android.widget.Toast.makeText(context, "No experimental flags yet", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }

            // ── Appearance ───────────────────────────────────────────────
            SettingsPage.Appearance -> SubPageScaffold(
                title = "Appearance",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                    ) {
                        Text(
                            "Theme Mode",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            data class TM(val id: String, val label: String, val bg: Color, val useLightIcon: Boolean)
                            listOf(
                                TM("system", "Auto",  Color(0xFF2C2826), true),
                                TM("light",  "Light", Color(0xFFF5F0EE), false),
                                TM("dark",   "Dark",  Color(0xFF1A1210), true),
                                TM("black",  "Black", Color.Black,       true),
                            ).forEach { tm ->
                                ThemeModeItem(
                                    id = tm.id, label = tm.label, bg = tm.bg,
                                    useLightIcon = tm.useLightIcon,
                                    selected = themeMode == tm.id,
                                    accent = MaterialTheme.colorScheme.primary,
                                    onClick = {
                                        themeMode = tm.id
                                        scope.launch { sl.settings.setTheme(tm.id) }
                                    },
                                )
                            }
                        }
                    }
                    SettingDivider()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                    ) {
                        Text(
                            "Color Palette",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PaletteDynamicItem(
                                selected = colorPalette == "dynamic",
                                accent   = MaterialTheme.colorScheme.primary,
                                outline  = MaterialTheme.colorScheme.outlineVariant,
                                onClick  = {
                                    colorPalette = "dynamic"; dynamicColor = true
                                    scope.launch {
                                        sl.settings.setColorPalette("dynamic")
                                        sl.settings.setDynamicColor(true)
                                    }
                                },
                            )
                            listOf(
                                Triple("default", Color(0xFF8B6E6A), Color(0xFFC97B6C)),
                                Triple("warm",    Color(0xFFE8B87A), Color(0xFFD4824A)),
                                Triple("coral",   Color(0xFFE8A0A0), Color(0xFFD45858)),
                                Triple("violet",  Color(0xFFB8A0DC), Color(0xFF7B54C2)),
                                Triple("blue",    Color(0xFF8AB4E8), Color(0xFF3B6CAC)),
                                Triple("indigo",  Color(0xFF8888CC), Color(0xFF3B3B9C)),
                            ).forEach { (id, topC, bottomC) ->
                                PaletteItem(
                                    topColor = topC, bottomColor = bottomC,
                                    selected = colorPalette == id,
                                    accent   = MaterialTheme.colorScheme.primary,
                                    outline  = MaterialTheme.colorScheme.outlineVariant,
                                    onClick  = {
                                        colorPalette = id
                                        if (dynamicColor) {
                                            dynamicColor = false
                                            scope.launch { sl.settings.setDynamicColor(false) }
                                        }
                                        scope.launch { sl.settings.setColorPalette(id) }
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    SettingToggle(
                        icon = Icons.Default.Speed, tint = ColourAppearance,
                        title = "Enable high refresh rate",
                        subtitle = "Force the display to run at its highest supported rate (e.g. 120 Hz)",
                        checked = highRefreshRate,
                        onChange = { highRefreshRate = it; scope.launch { sl.settings.setHighRefreshRate(it) } },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.BrightnessHigh, tint = ColourAppearance,
                        title = "Device layout",
                        subtitle = "Override form factor · Mobile / Tablet / TV",
                        onClick = { showUiModeDialog = true },
                    )
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    SubSectionLabel("Mini-player")
                    SettingToggle(
                        icon = Icons.Default.AspectRatio, tint = ColourAppearance,
                        title = "New mini player design",
                        checked = newMiniPlayer,
                        onChange = { newMiniPlayer = it; scope.launch { sl.settings.setNewMiniPlayerDesign(it) } },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.DarkMode, tint = ColourAppearance,
                        title = "Pure black mini-player",
                        checked = pureBlackMiniPlayer,
                        onChange = { pureBlackMiniPlayer = it; scope.launch { sl.settings.setPureBlackMiniPlayer(it) } },
                    )
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    SubSectionLabel("Player")
                    SettingToggle(
                        icon = Icons.Default.Palette, tint = ColourAppearance,
                        title = "New player design",
                        checked = newPlayerDesign,
                        onChange = { newPlayerDesign = it; scope.launch { sl.settings.setNewPlayerDesign(it) } },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.Reorder, tint = ColourAppearance,
                        title = "Navigation bar",
                        subtitle = "Reorder tabs",
                        onClick = { showNavOrderDialog = true },
                    )
                }
            }

            // ── Player & audio ───────────────────────────────────────────
            SettingsPage.PlayerAudio -> SubPageScaffold(
                title = "Player and audio",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    SettingNav(
                        icon = Icons.Default.HighQuality, tint = ColourPlayer,
                        title = "Default video quality",
                        value = videoQuality.replaceFirstChar { it.uppercase() } +
                            if (videoQuality.matches(Regex("\\d+"))) "p" else "",
                        onClick = { showQualityVideoDialog = true },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.GraphicEq, tint = ColourPlayer,
                        title = "Audio quality",
                        value = audioQuality.replaceFirstChar { it.uppercase() },
                        onClick = { showQualityAudioDialog = true },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.PlayCircle, tint = ColourPlayer,
                        title = "Autoplay next",
                        subtitle = "Continue with the next song / episode automatically",
                        checked = autoplay,
                        onChange = { autoplay = it; scope.launch { sl.settings.setAutoplayNext(it) } },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.VolumeOff, tint = ColourPlayer,
                        title = "Skip silence",
                        subtitle = "Automatically skip silent parts in tracks",
                        checked = skipSilence,
                        onChange = { skipSilence = it; scope.launch { sl.settings.setSkipSilence(it) } },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.BrightnessHigh, tint = ColourPlayer,
                        title = "Keep screen on",
                        subtitle = "Prevent screen from turning off while playing",
                        checked = keepScreenOn,
                        onChange = { keepScreenOn = it; scope.launch { sl.settings.setKeepScreenOn(it) } },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.QueueMusic, tint = ColourPlayer,
                        title = "Persistent queue",
                        subtitle = "Restore your queue when you reopen the app",
                        checked = persistentQueue,
                        onChange = { persistentQueue = it; scope.launch { sl.settings.setPersistentQueue(it) } },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.GraphicEq, tint = ColourPlayer,
                        title = "Crossfade",
                        value = if (crossfadeDuration == "0") "Off" else "${crossfadeDuration}s",
                        onClick = { showCrossfadeDialog = true },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.Subtitles, tint = ColourPlayer,
                        title = "Subtitles",
                        subtitle = "Show subtitles when available",
                        checked = subs,
                        onChange = { subs = it; scope.launch { sl.settings.setSubtitlesEnabled(it) } },
                    )
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    SubSectionLabel("Equalizer")
                    SettingToggle(
                        icon = Icons.Default.GraphicEq, tint = ColourPlayer,
                        title = "Equalizer",
                        subtitle = if (eqEnabled) "On · ${eqPreset.replaceFirstChar { it.uppercase() }} preset" else "Off",
                        checked = eqEnabled,
                        onChange = { eqEnabled = it; scope.launch { sl.settings.setEqEnabled(it) } },
                    )
                    if (eqEnabled) {
                        SettingDivider()
                        SettingNav(
                            icon = Icons.Default.GraphicEq, tint = ColourPlayer,
                            title = "EQ preset",
                            value = eqPreset.replaceFirstChar { it.uppercase() },
                            onClick = { showEqDialog = true },
                        )
                    }
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.GraphicEq, tint = ColourPlayer,
                        title = "Loudness normalization",
                        subtitle = "Reduce volume differences between tracks",
                        checked = loudnessNorm,
                        onChange = { loudnessNorm = it; scope.launch { sl.settings.setLoudnessNormalization(it) } },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.GraphicEq, tint = ColourPlayer,
                        title = "Bass boost",
                        subtitle = "Adds extra low-end punch",
                        checked = bassBoost,
                        onChange = { bassBoost = it; scope.launch { sl.settings.setBassBoost(it) } },
                    )
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    SubSectionLabel("Lyrics")
                    SettingNav(
                        icon = Icons.Default.Subtitles, tint = ColourPlayer,
                        title = "Lyrics source",
                        value = when (lyricsSource) {
                            "musixmatch" -> "Musixmatch"
                            "genius"     -> "Genius"
                            else         -> "LRCLib"
                        },
                        onClick = { showLyricsSourceDialog = true },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.Subtitles, tint = ColourPlayer,
                        title = "Synchronized lyrics",
                        subtitle = "Show time-synced scrolling lyrics when available",
                        checked = syncedLyrics,
                        onChange = { syncedLyrics = it; scope.launch { sl.settings.setSyncedLyrics(it) } },
                    )
                }
            }

            // ── Account ──────────────────────────────────────────────────
            SettingsPage.Account -> SubPageScaffold(
                title = "Account",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    YtMusicAccountRow()
                }
            }

            // ── Listen Together (Sonos) ───────────────────────────────────
            SettingsPage.ListenTogether -> SubPageScaffold(
                title = "Listen Together",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    SettingNav(
                        icon = Icons.Default.Group, tint = ColourSonos,
                        title = "Cast to Sonos speaker",
                        subtitle = "Stream the current track to a Sonos device on your network",
                        onClick = {
                            android.widget.Toast.makeText(
                                context,
                                "Open the player then tap the cast icon to choose a Sonos device",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    SettingNav(
                        icon = Icons.Default.Chat, tint = Color(0xFF7289DA),
                        title = "Discord community",
                        subtitle = "Join the StreamCloud server",
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    )
                }
            }

            // ── Content ──────────────────────────────────────────────────
            SettingsPage.Content -> SubPageScaffold(
                title = "Content",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    SettingNav(
                        icon = Icons.Default.Translate, tint = ColourContent,
                        title = "Content language",
                        value = contentLanguage.uppercase(),
                        onClick = { showLanguageDialog = true },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.Public, tint = ColourContent,
                        title = "Content country",
                        value = contentCountry.uppercase(),
                        onClick = { showCountryDialog = true },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.Block, tint = ColourContent,
                        title = "Explicit content",
                        subtitle = "Show songs and videos with explicit lyrics",
                        checked = explicitContent,
                        onChange = { explicitContent = it; scope.launch { sl.settings.setExplicitContent(it) } },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.PlayCircle, tint = ColourContent,
                        title = "Home collections",
                        value = "${enabledCollections.size} of ${HomeCollections.ALL.size}",
                        onClick = { showCollectionsDialog = true },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.Visibility, tint = ColourContent,
                        title = "Show Adult tab (18+)",
                        subtitle = "Replaces Library with Adult section",
                        checked = nsfw,
                        onChange = { nsfw = it; scope.launch { sl.settings.setNsfwEnabled(it) } },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.Language, tint = ColourContent,
                        title = "Backend URL",
                        subtitle = backendUrl.ifBlank { "Not set" },
                        onClick = { showBackendDialog = true },
                    )
                }
            }

            // ── AI Lyrics Translation ─────────────────────────────────────
            SettingsPage.AiLyrics -> SubPageScaffold(
                title = "AI Lyrics Translation",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    SettingNav(
                        icon = Icons.Default.AutoAwesome, tint = ColourAi,
                        title = "AI provider",
                        value = when (provider) {
                            "openai"    -> "OpenAI · gpt-5.1"
                            "anthropic" -> "Claude Sonnet 4.5"
                            "gemini"    -> "Gemini 2.5 Pro"
                            else        -> provider.ifBlank { "OpenAI" }
                        },
                        onClick = { showAiDialog = true },
                    )
                }
            }

            // ── Privacy ───────────────────────────────────────────────────
            SettingsPage.Privacy -> SubPageScaffold(
                title = "Privacy",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    SettingToggle(
                        icon = Icons.Default.History, tint = ColourPrivacy,
                        title = "Enable listening history",
                        subtitle = "Track the songs you play",
                        checked = listenHistory,
                        onChange = { listenHistory = it; scope.launch { sl.settings.setListenHistoryEnabled(it) } },
                    )
                    if (listenHistory) {
                        SettingDivider()
                        SettingToggle(
                            icon = Icons.Default.History, tint = ColourPrivacy,
                            title = "Pause listening history",
                            subtitle = "Temporarily stop recording new plays",
                            checked = pauseListenHistory,
                            onChange = { pauseListenHistory = it; scope.launch { sl.settings.setPauseListenHistory(it) } },
                        )
                        SettingDivider()
                        SettingNav(
                            icon = Icons.Default.DeleteSweep, tint = ColourPrivacy,
                            title = "Clear listening history",
                            subtitle = "Remove all recently played tracks",
                            onClick = {
                                scope.launch {
                                    com.streamcloud.app.data.library.LibraryDb.get(context).tracks().clearRecent()
                                    android.widget.Toast.makeText(context, "Listening history cleared", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.Shield, tint = ColourPrivacy,
                        title = "Safe search",
                        subtitle = "Filter explicit search results",
                        checked = safeSearch,
                        onChange = { safeSearch = it; scope.launch { sl.settings.setSafeSearch(it) } },
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.OpenInBrowser, tint = ColourPrivacy,
                        title = "Open external links in browser",
                        subtitle = "Otherwise opens inside an in-app webview",
                        checked = extLinks,
                        onChange = { extLinks = it; scope.launch { sl.settings.setExternalLinksInBrowser(it) } },
                    )
                }
            }

            // ── Storage ───────────────────────────────────────────────────
            SettingsPage.Storage -> SubPageScaffold(
                title = "Storage",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    SettingNav(
                        icon = Icons.Default.DeleteSweep, tint = ColourStorage,
                        title = "Clear app cache",
                        subtitle = "Free up temporary files",
                        onClick = {
                            scope.launch {
                                pluginRepo.clearAppCache()
                                pluginsCacheBytes = pluginRepo.pluginsCacheSize()
                            }
                        },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.Extension, tint = ColourStorage,
                        title = "CloudStream plugins",
                        subtitle = "${formatBytes(pluginsCacheBytes)} on device",
                        onClick = onOpenPlugins,
                    )
                    SettingDivider()
                    SettingToggle(
                        icon = Icons.Default.Wifi, tint = ColourStorage,
                        title = "Download over Wi-Fi only",
                        subtitle = "Avoid using mobile data for downloads",
                        checked = dlWifi,
                        onChange = { dlWifi = it; scope.launch { sl.settings.setDownloadOverWifiOnly(it) } },
                    )
                }
            }

            // ── Backup and restore ────────────────────────────────────────
            SettingsPage.BackupRestore -> SubPageScaffold(
                title = "Backup and restore",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    SettingNav(
                        icon = Icons.Default.CloudUpload, tint = ColourStorage,
                        title = "Export library",
                        subtitle = "Save your liked songs, playlists and settings to a file",
                        onClick = {
                            android.widget.Toast.makeText(context, "Backup & restore coming soon", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.Download, tint = ColourStorage,
                        title = "Import library",
                        subtitle = "Restore from a previously exported backup file",
                        onClick = {
                            android.widget.Toast.makeText(context, "Backup & restore coming soon", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }

            // ── About ─────────────────────────────────────────────────────
            SettingsPage.About -> SubPageScaffold(
                title = "About",
                onBack = { currentPage = null },
            ) {
                SettingsGroup {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(HubIconBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.MusicNote, null, tint = HubIconFg, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "StreamCloud",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    SettingNav(
                        icon = Icons.Default.Cloud, tint = ColourSystem,
                        title = "Source code",
                        subtitle = "github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}",
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}")),
                            )
                        },
                    )
                    SettingDivider()
                    SettingNav(
                        icon = Icons.Default.BugReport, tint = ColourSystem,
                        title = "Report a bug",
                        subtitle = "Open a GitHub issue",
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/issues/new")),
                            )
                        },
                    )
                }
            }

            // ── App logs ──────────────────────────────────────────────────
            SettingsPage.Logs -> LogsPage(onBack = { currentPage = null })
        }
    }

    // ── Dialogs (rendered above all pages) ────────────────────────────────
    if (showQualityVideoDialog) {
        QualityDialog(
            title = "Default video quality",
            options = listOf(
                "auto" to "Auto (recommended)",
                "1080" to "1080p", "720" to "720p", "480" to "480p",
            ),
            selected = videoQuality,
            onSelect = { videoQuality = it; scope.launch { sl.settings.setVideoQuality(it) }; showQualityVideoDialog = false },
            onDismiss = { showQualityVideoDialog = false },
        )
    }
    if (showQualityAudioDialog) {
        QualityDialog(
            title = "Audio quality",
            options = listOf(
                "high" to "High (best available)", "medium" to "Medium", "low" to "Low (data saver)",
            ),
            selected = audioQuality,
            onSelect = { audioQuality = it; scope.launch { sl.settings.setAudioQuality(it) }; showQualityAudioDialog = false },
            onDismiss = { showQualityAudioDialog = false },
        )
    }
    if (showEqDialog) {
        QualityDialog(
            title = "Equalizer preset",
            options = listOf(
                "flat" to "Flat (no change)", "pop" to "Pop", "rock" to "Rock",
                "jazz" to "Jazz", "bass" to "Bass booster", "vocal" to "Vocal",
            ),
            selected = eqPreset,
            onSelect = { eqPreset = it; scope.launch { sl.settings.setEqPreset(it) }; showEqDialog = false },
            onDismiss = { showEqDialog = false },
        )
    }
    if (showAiDialog) {
        QualityDialog(
            title = "AI provider",
            options = listOf(
                "openai"    to "OpenAI · gpt-5.1",
                "anthropic" to "Anthropic · Claude Sonnet 4.5",
                "gemini"    to "Google · Gemini 2.5 Pro",
            ),
            selected = provider,
            onSelect = { p ->
                provider = p
                model = when (p) {
                    "openai"    -> "gpt-5.1"
                    "anthropic" -> "claude-sonnet-4-5-20250929"
                    else        -> "gemini-2.5-pro"
                }
                scope.launch { sl.settings.setAiProvider(p); sl.settings.setAiModel(model) }
                showAiDialog = false
            },
            onDismiss = { showAiDialog = false },
        )
    }
    if (showBackendDialog) {
        BackendDialog(
            initialUrl = backendUrl, initialToken = hfToken,
            onSave = { url, token ->
                backendUrl = url; hfToken = token
                scope.launch { sl.settings.setBackendUrl(url.trim().trimEnd('/')); sl.settings.setHfToken(token.trim()) }
                showBackendDialog = false
            },
            onDismiss = { showBackendDialog = false },
        )
    }
    if (showCollectionsDialog) {
        CollectionsDialog(
            enabled = enabledCollections,
            onToggle = { id, on -> enabledCollections = if (on) enabledCollections + id else enabledCollections - id },
            onSave = {
                val ordered = HomeCollections.ALL.map { it.id }.filter { it in enabledCollections }
                scope.launch { sl.settings.setHomeCollections(ordered) }
                showCollectionsDialog = false
            },
            onDismiss = { showCollectionsDialog = false },
        )
    }
    if (showNavOrderDialog) {
        NavOrderDialog(nsfw = nsfw, onDismiss = { showNavOrderDialog = false })
    }
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
    if (showCrossfadeDialog) {
        QualityDialog(
            title = "Crossfade duration",
            options = listOf("0" to "Off", "3" to "3 seconds", "5" to "5 seconds", "8" to "8 seconds"),
            selected = crossfadeDuration,
            onSelect = { crossfadeDuration = it; scope.launch { sl.settings.setCrossfadeDuration(it) }; showCrossfadeDialog = false },
            onDismiss = { showCrossfadeDialog = false },
        )
    }
    if (showLanguageDialog) {
        QualityDialog(
            title = "Content language",
            options = listOf(
                "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
                "pt" to "Portuguese", "ja" to "Japanese", "ko" to "Korean",
                "zh" to "Chinese", "ar" to "Arabic", "hi" to "Hindi",
            ),
            selected = contentLanguage,
            onSelect = { contentLanguage = it; scope.launch { sl.settings.setContentLanguage(it) }; showLanguageDialog = false },
            onDismiss = { showLanguageDialog = false },
        )
    }
    if (showCountryDialog) {
        QualityDialog(
            title = "Content country",
            options = listOf(
                "US" to "United States", "GB" to "United Kingdom", "AU" to "Australia",
                "CA" to "Canada", "DE" to "Germany", "FR" to "France", "ES" to "Spain",
                "BR" to "Brazil", "IN" to "India", "JP" to "Japan",
                "KR" to "South Korea", "ZA" to "South Africa",
            ),
            selected = contentCountry,
            onSelect = { contentCountry = it; scope.launch { sl.settings.setContentCountry(it) }; showCountryDialog = false },
            onDismiss = { showCountryDialog = false },
        )
    }
    if (showLyricsSourceDialog) {
        QualityDialog(
            title = "Lyrics source",
            options = listOf(
                "lrclib"     to "LRCLib (default, free)",
                "musixmatch" to "Musixmatch",
                "genius"     to "Genius",
            ),
            selected = lyricsSource,
            onSelect = { lyricsSource = it; scope.launch { sl.settings.setLyricsSource(it) }; showLyricsSourceDialog = false },
            onDismiss = { showLyricsSourceDialog = false },
        )
    }
    if (showUiModeDialog) {
        UiModeDialog(
            current = uiMode,
            onPick = { uiMode = it; scope.launch { sl.settings.setUiMode(it) }; showUiModeDialog = false },
            onDismiss = { showUiModeDialog = false },
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//                         Hub list (landing page)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SettingsHubList(onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val checker = remember { UpdateChecker(context.applicationContext) }
    var updateLabel by remember { mutableStateOf<String?>(null) }
    val errorCount by AppLogger.errorCount.collectAsState()

    LaunchedEffect(Unit) {
        runCatching {
            val info = checker.fetchLatest(includeOlder = false)
            if (info?.isNewerThanInstalled == true) updateLabel = info.title
        }
    }

    data class HubItem(
        val page: SettingsPage,
        val icon: ImageVector,
        val title: String,
        val badge: String? = null,
        val badgeError: Boolean = false,
    )

    val hubItems = listOf(
        HubItem(SettingsPage.SystemUpdate,  Icons.Default.SystemUpdate, "System update",         updateLabel),
        HubItem(SettingsPage.Appearance,    Icons.Default.Palette,      "Appearance"),
        HubItem(SettingsPage.PlayerAudio,   Icons.Default.PlayArrow,    "Player and audio"),
        HubItem(SettingsPage.Account,       Icons.Default.Person,       "Account"),
        HubItem(SettingsPage.ListenTogether,Icons.Default.Group,        "Listen Together"),
        HubItem(SettingsPage.Content,       Icons.Default.Public,       "Content"),
        HubItem(SettingsPage.AiLyrics,      Icons.Default.Translate,    "AI Lyrics Translation"),
        HubItem(SettingsPage.Privacy,       Icons.Default.Shield,       "Privacy"),
        HubItem(SettingsPage.Storage,       Icons.Default.Storage,      "Storage"),
        HubItem(SettingsPage.BackupRestore, Icons.Default.CloudUpload,  "Backup and restore"),
        HubItem(SettingsPage.About,         Icons.Default.Info,         "About"),
        HubItem(
            page = SettingsPage.Logs,
            icon = Icons.Default.BugReport,
            title = "App logs",
            badge = if (errorCount > 0) "$errorCount error${if (errorCount == 1) "" else "s"}" else null,
            badgeError = errorCount > 0,
        ),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        items(hubItems) { hubItem ->
            HubRow(
                icon       = hubItem.icon,
                title      = hubItem.title,
                badge      = hubItem.badge,
                badgeError = hubItem.badgeError,
                onClick    = { onNavigate(hubItem.page) },
            )
        }
    }
}

@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    badge: String? = null,
    badgeError: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (badgeError && !badge.isNullOrBlank()) Color(0xFF3D1A1A) else HubIconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (badgeError && !badge.isNullOrBlank()) MaterialTheme.colorScheme.error else HubIconFg,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!badge.isNullOrBlank()) {
                Text(
                    badge,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (badgeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//                         Sub-page scaffold
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, bottom = 20.dp),
            )
            content()
            Spacer(Modifier.height(48.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//                   Layout pickers (Theme Mode & Color Palette)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ThemeModeItem(
    id: String,
    label: String,
    bg: Color,
    useLightIcon: Boolean,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val iconTint = if (useLightIcon) Color.White else Color(0xFF1A1210)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(bg)
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) accent else Color.White.copy(alpha = 0.18f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (id) {
                "system", "light" -> Icon(Icons.Default.BrightnessHigh, label, tint = iconTint, modifier = Modifier.size(22.dp))
                "dark"            -> Icon(Icons.Default.DarkMode, label, tint = iconTint, modifier = Modifier.size(22.dp))
                else              -> {}
            }
            if (selected) {
                Box(
                    Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaletteItem(
    topColor: Color,
    bottomColor: Color,
    selected: Boolean,
    accent: Color,
    outline: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) accent else outline.copy(alpha = 0.5f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().clip(CircleShape)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(topColor))
            Box(Modifier.weight(1f).fillMaxWidth().background(bottomColor))
        }
        if (selected) {
            Box(
                Modifier
                    .size(16.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
    }
}

@Composable
private fun PaletteDynamicItem(
    selected: Boolean,
    accent: Color,
    outline: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) accent else outline.copy(alpha = 0.5f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Palette, "Dynamic", tint = accent, modifier = Modifier.size(22.dp))
        if (selected) {
            Box(
                Modifier
                    .size(16.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//                        Metrolist-style atoms
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SubSectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        content = content,
    )
}

@Composable
private fun SettingDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 72.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

@Composable
private fun IconBox(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SettingNav(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        IconBox(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SettingToggle(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        IconBox(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//                         Composite rows
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun YtMusicAccountRow() {
    val context = LocalContext.current
    val sl      = remember(context) { ServiceLocator.get(context) }
    val cookie   by sl.settings.ytMusicCookie.collectAsState(initial = "")
    val userName by sl.settings.ytMusicUserName.collectAsState(initial = "")
    val signedIn = cookie.isNotBlank()
    val scope   = rememberCoroutineScope()

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !signedIn) {
                context.startActivity(
                    Intent(context, com.streamcloud.app.ui.account.YtMusicLoginActivity::class.java),
                )
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(if (signedIn) Icons.Default.Logout else Icons.Default.Login, ColourAccount)
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
                maxLines = 1,
            )
        }
        if (signedIn) {
            TextButton(onClick = {
                scope.launch {
                    sl.settings.clearYtMusicAccount()
                    com.streamcloud.app.data.newpipe.NewPipeDownloader.instance.ytMusicCookie = ""
                    runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
                }
            }) { Text("Sign out") }
        } else {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun UpdaterRow() {
    val context    = LocalContext.current
    val scope      = rememberCoroutineScope()
    val checker    = remember { UpdateChecker(context.applicationContext) }
    var checking   by remember { mutableStateOf(false) }
    var status     by remember { mutableStateOf<String?>(null) }
    var update     by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress   by remember { mutableStateOf(0f) }

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
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        IconBox(Icons.Default.SystemUpdate, ColourSystem)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Check for updates",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                status ?: "v${BuildConfig.VERSION_NAME} · Tap to check",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (downloading) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        when {
            checking || downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
            else -> Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//                               Dialogs
// ══════════════════════════════════════════════════════════════════════════════

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
private fun BackendDialog(
    initialUrl: String,
    initialToken: String,
    onSave: (url: String, token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url   by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backend & HuggingFace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Backend URL") },
                    supportingText = { Text("Your StreamCloud FastAPI deployment.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("HuggingFace token") },
                    supportingText = { Text("NSFW image gen + image editing.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, token) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Pick which rows show on the Movies tab.",
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
                        Text(c.emoji, modifier = Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.titleLarge)
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

@Composable
private fun NavOrderDialog(nsfw: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sl      = remember(context) { ServiceLocator.get(context) }
    val scope   = rememberCoroutineScope()

    data class NavItem(val id: String, val label: String, val icon: ImageVector)

    val all = remember(nsfw) {
        buildList {
            add(NavItem("movies",  "Movies",  Icons.Default.PlayArrow))
            add(NavItem("music",   "Music",   Icons.Default.MusicNote))
            add(NavItem("ai",      "AI",      Icons.Default.AutoAwesome))
            add(NavItem("library", "Library", Icons.Default.FormatListBulleted))
            if (nsfw) add(NavItem("adult", "Adult", Icons.Default.Visibility))
        }
    }
    val byId  = all.associateBy { it.id }
    var order by remember { mutableStateOf<List<NavItem>>(all) }

    LaunchedEffect(nsfw) {
        val csv = sl.settings.navTabOrderCsv.first()
        val saved = csv?.split(",")?.mapNotNull { byId[it.trim()] } ?: emptyList()
        val remaining = all.filter { it.id !in saved.map(NavItem::id) }
        order = (saved + remaining).distinctBy { it.id }
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
                        Text(item.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                if (index > 0) order = order.toMutableList().apply {
                                    val t = this[index]; this[index] = this[index - 1]; this[index - 1] = t
                                }
                            },
                            enabled = index > 0,
                        ) { Icon(Icons.Default.ArrowUpward, "Move up") }
                        IconButton(
                            onClick = {
                                if (index < order.lastIndex) order = order.toMutableList().apply {
                                    val t = this[index]; this[index] = this[index + 1]; this[index + 1] = t
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
                Text("Version ${BuildConfig.VERSION_NAME} · code ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}")))
                }) { Text("Source code (GitHub)") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun UiModeDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        Triple("Auto",   "Auto-detect",   "Phone, tablet or TV based on the device"),
        Triple("Mobile", "Mobile",        "Compact phone layout"),
        Triple("Tablet", "Tablet",        "Wider canvas, slightly larger text"),
        Triple("Tv",     "TV / Leanback", "Largest text, designed for D-pad / remote"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layout / device") },
        text = {
            Column {
                options.forEach { (id, label, sub) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(id) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    ) {
                        RadioButton(selected = current.equals(id, ignoreCase = true), onClick = { onPick(id) })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
//                              App Logs page
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun LogsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by AppLogger.entriesFlow.collectAsState()
    val errorCount by AppLogger.errorCount.collectAsState()

    val errorColor  = MaterialTheme.colorScheme.error
    val warnColor   = Color(0xFFFFB74D)
    val infoColor   = MaterialTheme.colorScheme.onSurfaceVariant

    fun levelColor(level: AppLogger.Level) = when (level) {
        AppLogger.Level.ERROR -> errorColor
        AppLogger.Level.WARN  -> warnColor
        AppLogger.Level.INFO  -> infoColor
    }

    fun levelLabel(level: AppLogger.Level) = when (level) {
        AppLogger.Level.ERROR -> "E"
        AppLogger.Level.WARN  -> "W"
        AppLogger.Level.INFO  -> "I"
    }

    val sdf = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                TextButton(onClick = {
                    val text = AppLogger.formatAll()
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("StreamCloud logs", text))
                    android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy all")
                }
                TextButton(
                    onClick = { AppLogger.clear() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
                }
            }
        }

        Row(
            Modifier.padding(start = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "App logs",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (errorCount > 0) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "$errorCount",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No log entries yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries) { entry ->
                    val lc = levelColor(entry.level)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when (entry.level) {
                                    AppLogger.Level.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                    AppLogger.Level.WARN  -> Color(0xFFFFB74D).copy(alpha = 0.08f)
                                    AppLogger.Level.INFO  -> MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            levelLabel(entry.level),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = lc,
                            modifier = Modifier.width(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.tag,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = lc,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    sdf.format(java.util.Date(entry.timeMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            Text(
                                entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1024        -> String.format("%.0f KB", bytes / 1024.0)
    else                 -> "$bytes B"
}
