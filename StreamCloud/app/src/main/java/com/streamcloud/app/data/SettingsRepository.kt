package com.streamcloud.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamcloud.app.BuildConfig
import com.streamcloud.app.data.plugins.PinnedCsSection
import com.streamcloud.app.data.plugins.csHomeSectionsJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.dataStore by preferencesDataStore("streamcloud_settings")

object SettingsKeys {
    val BACKEND_URL = stringPreferencesKey("backend_url")
    val AI_PROVIDER = stringPreferencesKey("ai_provider")
    val AI_MODEL = stringPreferencesKey("ai_model")
    val NSFW_ENABLED = booleanPreferencesKey("nsfw_enabled")
    val VIDEO_QUALITY = stringPreferencesKey("video_quality")
    val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
    val EXTERNAL_LINKS_IN_BROWSER = booleanPreferencesKey("ext_links_in_browser")
    val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
    val SUBTITLES_ENABLED = booleanPreferencesKey("subs_enabled")
    val DOWNLOAD_OVER_WIFI_ONLY = booleanPreferencesKey("dl_wifi_only")
    val SAFE_MODE_PIN = stringPreferencesKey("safe_mode_pin")
    val THEME = stringPreferencesKey("theme")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
    val EQ_PRESET = stringPreferencesKey("eq_preset")
    val BASS_BOOST = booleanPreferencesKey("bass_boost")
    val HF_TOKEN = stringPreferencesKey("hf_token")
    val HOME_COLLECTIONS = stringPreferencesKey("home_collections")
    val YT_MUSIC_COOKIE = stringPreferencesKey("yt_music_cookie")
    val YT_MUSIC_USER_NAME = stringPreferencesKey("yt_music_user_name")
    val YT_MUSIC_USER_AVATAR = stringPreferencesKey("yt_music_user_avatar")
    val NAV_TAB_ORDER    = stringPreferencesKey("nav_tab_order")
    val NAV_HIDDEN_TABS  = stringPreferencesKey("nav_hidden_tabs")
    val PLAYLIST_THUMBS = stringPreferencesKey("playlist_thumbs")
    val UI_MODE = stringPreferencesKey("ui_mode")
    val ADULT_REDDIT_SUBS = stringPreferencesKey("adult_reddit_subs")
    val REDDIT_USERNAME     = stringPreferencesKey("reddit_username")
    val REDDIT_ACCOUNTS_RAW = stringPreferencesKey("reddit_accounts_raw")
    val COLOR_PALETTE = stringPreferencesKey("color_palette")
    val MOVIES_THEME = stringPreferencesKey("movies_theme")


    val HIGH_REFRESH_RATE       = booleanPreferencesKey("high_refresh_rate")
    val NEW_MINI_PLAYER_DESIGN  = booleanPreferencesKey("new_mini_player_design")
    val PURE_BLACK_MINI_PLAYER  = booleanPreferencesKey("pure_black_mini_player")
    val NEW_PLAYER_DESIGN       = booleanPreferencesKey("new_player_design")


    val SKIP_SILENCE            = booleanPreferencesKey("skip_silence")
    val KEEP_SCREEN_ON          = booleanPreferencesKey("keep_screen_on")
    val PERSISTENT_QUEUE        = booleanPreferencesKey("persistent_queue")
    val CROSSFADE_DURATION      = stringPreferencesKey("crossfade_duration")


    val LISTEN_HISTORY_ENABLED  = booleanPreferencesKey("listen_history_enabled")
    val PAUSE_LISTEN_HISTORY    = booleanPreferencesKey("pause_listen_history")


    val SAFE_SEARCH             = booleanPreferencesKey("safe_search")
    val EXPLICIT_CONTENT        = booleanPreferencesKey("explicit_content")
    val CONTENT_LANGUAGE        = stringPreferencesKey("content_language")
    val CONTENT_COUNTRY         = stringPreferencesKey("content_country")


    val LYRICS_SOURCE           = stringPreferencesKey("lyrics_source")
    val SYNCED_LYRICS           = booleanPreferencesKey("synced_lyrics")


    val LOUDNESS_NORMALIZATION  = booleanPreferencesKey("loudness_normalization")


    val CANVAS_ENABLED = booleanPreferencesKey("canvas_enabled")
    val POSTER_STYLE = stringPreferencesKey("poster_style")

    val DYNAMIC_MINI_PLAYER_THEME = booleanPreferencesKey("dynamic_mini_player_theme")
    val NAV_LABELS                = booleanPreferencesKey("nav_labels")

    val SPOTIFY_COOKIE    = stringPreferencesKey("spotify_cookie")
    val SPOTIFY_USER_NAME = stringPreferencesKey("spotify_user_name")

    val CS_HOME_SECTIONS  = stringPreferencesKey("cs_home_sections")
    val STREMIO_HOME_CATALOGS = stringPreferencesKey("stremio_home_catalogs")

    val SMART_TRIMMER          = booleanPreferencesKey("smart_trimmer")
    val VIDEO_CACHE_MAX_MB     = stringPreferencesKey("video_cache_max_mb")
    val IMAGE_CACHE_MAX_MB     = stringPreferencesKey("image_cache_max_mb")
    val POSTER_CACHE_MAX_COUNT = stringPreferencesKey("poster_cache_max_count")

    val NUVIO_ACCESS_TOKEN  = stringPreferencesKey("nuvio_access_token")
    val NUVIO_REFRESH_TOKEN = stringPreferencesKey("nuvio_refresh_token")
    val NUVIO_EMAIL         = stringPreferencesKey("nuvio_email")
    val NUVIO_USER_ID       = stringPreferencesKey("nuvio_user_id")

    val MUSIC_SEARCH_HISTORY = stringPreferencesKey("music_search_history")

    val ADULT_SOURCE = stringPreferencesKey("adult_source")
    val INTRODB_API_KEY             = stringPreferencesKey("introdb_api_key")
    val SUBTITLE_SOURCE             = stringPreferencesKey("subtitle_source")
    val PARENTAL_GUIDE_ENABLED      = booleanPreferencesKey("parental_guide_enabled")
    val HOLD_TO_SPEED_ENABLED       = booleanPreferencesKey("hold_to_speed_enabled")
    val HOLD_TO_SPEED_VALUE         = stringPreferencesKey("hold_to_speed_value")
    val ANNOUNCEMENT_SEEN_VERSION   = stringPreferencesKey("announcement_seen_version")
    val LIBRARY_WATCHLIST_SORT      = stringPreferencesKey("library_watchlist_sort")

    // ── Adult content ──────────────────────────────────────────────────────────
    val AGE_GATE_CONFIRMED  = booleanPreferencesKey("age_gate_confirmed")
    val ADULT_LOCK_ENABLED  = booleanPreferencesKey("adult_lock_enabled")

    // ── Trakt.tv ────────────────────────────────────────────────────────────────
    val TRAKT_ACCESS_TOKEN  = stringPreferencesKey("trakt_access_token")
    val TRAKT_REFRESH_TOKEN = stringPreferencesKey("trakt_refresh_token")
    val TRAKT_USERNAME      = stringPreferencesKey("trakt_username")
    val TRAKT_CLIENT_ID     = stringPreferencesKey("trakt_client_id")

    // ── Simkl ────────────────────────────────────────────────────────────────
    val SIMKL_ACCESS_TOKEN  = stringPreferencesKey("simkl_access_token")
    val SIMKL_CLIENT_ID     = stringPreferencesKey("simkl_client_id")
}

class SettingsRepository(private val context: Context) {
    val backendUrl: Flow<String> = context.dataStore.data.map {
        it[SettingsKeys.BACKEND_URL] ?: BuildConfig.DEFAULT_BACKEND_URL
    }
    val aiProvider: Flow<String> = context.dataStore.data.map { it[SettingsKeys.AI_PROVIDER] ?: "openai" }
    val aiModel: Flow<String> = context.dataStore.data.map { it[SettingsKeys.AI_MODEL] ?: "gpt-5.1" }
    val nsfwEnabled: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.NSFW_ENABLED] ?: false }
    val videoQuality: Flow<String> = context.dataStore.data.map { it[SettingsKeys.VIDEO_QUALITY] ?: "auto" }
    val audioQuality: Flow<String> = context.dataStore.data.map { it[SettingsKeys.AUDIO_QUALITY] ?: "high" }
    val externalLinksInBrowser: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.EXTERNAL_LINKS_IN_BROWSER] ?: true }
    val autoplayNext: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.AUTOPLAY_NEXT] ?: true }
    val subtitlesEnabled: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.SUBTITLES_ENABLED] ?: true }
    val downloadOverWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.DOWNLOAD_OVER_WIFI_ONLY] ?: true }
    val safeModePin: Flow<String> = context.dataStore.data.map { it[SettingsKeys.SAFE_MODE_PIN] ?: "" }
    val theme: Flow<String> = context.dataStore.data.map { it[SettingsKeys.THEME] ?: "dark" }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.DYNAMIC_COLOR] ?: false }
    val eqEnabled: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.EQ_ENABLED] ?: false }
    val eqPreset: Flow<String> = context.dataStore.data.map { it[SettingsKeys.EQ_PRESET] ?: "flat" }
    val bassBoost: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.BASS_BOOST] ?: false }
    val falApiKey: Flow<String> = context.dataStore.data.map { it[SettingsKeys.HF_TOKEN] ?: "" }
    val hfToken: Flow<String> = context.dataStore.data.map { it[SettingsKeys.HF_TOKEN] ?: "" }


    val homeCollectionsCsv: Flow<String?> = context.dataStore.data.map { it[SettingsKeys.HOME_COLLECTIONS] }

    val stremioDisabledCatalogsCsv: Flow<String?> = context.dataStore.data.map { it[SettingsKeys.STREMIO_HOME_CATALOGS] }


    val ytMusicCookie: Flow<String> = context.dataStore.data.map { it[SettingsKeys.YT_MUSIC_COOKIE] ?: "" }
    val ytMusicUserName: Flow<String> = context.dataStore.data.map { it[SettingsKeys.YT_MUSIC_USER_NAME] ?: "" }
    val ytMusicUserAvatar: Flow<String> = context.dataStore.data.map { it[SettingsKeys.YT_MUSIC_USER_AVATAR] ?: "" }


    val navTabOrderCsv:  Flow<String?> = context.dataStore.data.map { it[SettingsKeys.NAV_TAB_ORDER] }
    val navHiddenTabsCsv: Flow<String>  = context.dataStore.data.map { it[SettingsKeys.NAV_HIDDEN_TABS] ?: "" }


    val playlistThumbsJson: Flow<String> = context.dataStore.data.map {
        it[SettingsKeys.PLAYLIST_THUMBS] ?: "{}"
    }


    val uiMode: Flow<String> = context.dataStore.data.map {
        it[SettingsKeys.UI_MODE] ?: "Auto"
    }

    suspend fun setBackendUrl(url: String) = context.dataStore.edit { it[SettingsKeys.BACKEND_URL] = url }
    suspend fun setAiProvider(p: String) = context.dataStore.edit { it[SettingsKeys.AI_PROVIDER] = p }
    suspend fun setAiModel(m: String) = context.dataStore.edit { it[SettingsKeys.AI_MODEL] = m }
    suspend fun setNsfwEnabled(b: Boolean) = context.dataStore.edit { it[SettingsKeys.NSFW_ENABLED] = b }
    suspend fun setVideoQuality(q: String) = context.dataStore.edit { it[SettingsKeys.VIDEO_QUALITY] = q }
    suspend fun setAudioQuality(q: String) = context.dataStore.edit { it[SettingsKeys.AUDIO_QUALITY] = q }
    suspend fun setExternalLinksInBrowser(b: Boolean) = context.dataStore.edit { it[SettingsKeys.EXTERNAL_LINKS_IN_BROWSER] = b }
    suspend fun setAutoplayNext(b: Boolean) = context.dataStore.edit { it[SettingsKeys.AUTOPLAY_NEXT] = b }
    suspend fun setSubtitlesEnabled(b: Boolean) = context.dataStore.edit { it[SettingsKeys.SUBTITLES_ENABLED] = b }
    suspend fun setDownloadOverWifiOnly(b: Boolean) = context.dataStore.edit { it[SettingsKeys.DOWNLOAD_OVER_WIFI_ONLY] = b }
    suspend fun setSafeModePin(p: String) = context.dataStore.edit { it[SettingsKeys.SAFE_MODE_PIN] = p }
    suspend fun setTheme(t: String) = context.dataStore.edit { it[SettingsKeys.THEME] = t }
    suspend fun setDynamicColor(b: Boolean) = context.dataStore.edit { it[SettingsKeys.DYNAMIC_COLOR] = b }
    suspend fun setEqEnabled(b: Boolean) = context.dataStore.edit { it[SettingsKeys.EQ_ENABLED] = b }
    suspend fun setEqPreset(p: String) = context.dataStore.edit { it[SettingsKeys.EQ_PRESET] = p }
    suspend fun setBassBoost(b: Boolean) = context.dataStore.edit { it[SettingsKeys.BASS_BOOST] = b }
    suspend fun setFalApiKey(k: String) = context.dataStore.edit { it[SettingsKeys.HF_TOKEN] = k }
    suspend fun setHfToken(k: String) = context.dataStore.edit { it[SettingsKeys.HF_TOKEN] = k }

    suspend fun setHomeCollections(ids: List<String>) =
        context.dataStore.edit { it[SettingsKeys.HOME_COLLECTIONS] = ids.joinToString(",") }

    suspend fun setStremioDisabledCatalogs(keys: Set<String>) =
        context.dataStore.edit { it[SettingsKeys.STREMIO_HOME_CATALOGS] = keys.joinToString(",") }

    suspend fun setNavTabOrder(ids: List<String>) =
        context.dataStore.edit { it[SettingsKeys.NAV_TAB_ORDER] = ids.joinToString(",") }

    suspend fun setNavHiddenTabs(csv: String) =
        context.dataStore.edit { it[SettingsKeys.NAV_HIDDEN_TABS] = csv }

    suspend fun setUiMode(mode: String) =
        context.dataStore.edit { it[SettingsKeys.UI_MODE] = mode }

    suspend fun setPlaylistThumb(playlistId: String, uri: String?) =
        context.dataStore.edit { prefs ->
            val current = prefs[SettingsKeys.PLAYLIST_THUMBS] ?: "{}"


            val map = current
                .removePrefix("{").removeSuffix("}")
                .split(",").filter { it.isNotBlank() }
                .mapNotNull {
                    val parts = it.split(":", limit = 2)
                    if (parts.size != 2) return@mapNotNull null
                    parts[0].trim().trim('"') to parts[1].trim().trim('"')
                }
                .toMap()
                .toMutableMap()
            if (uri.isNullOrBlank()) map.remove(playlistId) else map[playlistId] = uri
            prefs[SettingsKeys.PLAYLIST_THUMBS] = map.entries.joinToString(",", "{", "}") {
                "\"${it.key}\":\"${it.value}\""
            }
        }

    suspend fun setYtMusicCookie(cookie: String) =
        context.dataStore.edit { it[SettingsKeys.YT_MUSIC_COOKIE] = cookie }
    suspend fun setYtMusicUser(name: String, avatar: String) = context.dataStore.edit {
        it[SettingsKeys.YT_MUSIC_USER_NAME] = name
        it[SettingsKeys.YT_MUSIC_USER_AVATAR] = avatar
    }
    suspend fun clearYtMusicAccount() = context.dataStore.edit {
        it.remove(SettingsKeys.YT_MUSIC_COOKIE)
        it.remove(SettingsKeys.YT_MUSIC_USER_NAME)
        it.remove(SettingsKeys.YT_MUSIC_USER_AVATAR)
    }

    val colorPalette: Flow<String> = context.dataStore.data.map { it[SettingsKeys.COLOR_PALETTE] ?: "default" }
    suspend fun setColorPalette(s: String) = context.dataStore.edit { it[SettingsKeys.COLOR_PALETTE] = s }

    val moviesTheme: Flow<String> = context.dataStore.data.map { it[SettingsKeys.MOVIES_THEME] ?: "violet" }
    suspend fun setMoviesTheme(s: String) = context.dataStore.edit { it[SettingsKeys.MOVIES_THEME] = s }


    val adultRedditSubsCsv: Flow<String> = context.dataStore.data.map {
        it[SettingsKeys.ADULT_REDDIT_SUBS] ?: ""
    }
    suspend fun setAdultRedditSubs(csv: String) =
        context.dataStore.edit { it[SettingsKeys.ADULT_REDDIT_SUBS] = csv }

    val adultSource: Flow<String> = context.dataStore.data.map {
        it[SettingsKeys.ADULT_SOURCE] ?: "Eporner"
    }
    suspend fun setAdultSource(source: String) =
        context.dataStore.edit { it[SettingsKeys.ADULT_SOURCE] = source }


    val highRefreshRate: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.HIGH_REFRESH_RATE] ?: true }
    val newMiniPlayerDesign: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.NEW_MINI_PLAYER_DESIGN] ?: true }
    val pureBlackMiniPlayer: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.PURE_BLACK_MINI_PLAYER] ?: false }
    val newPlayerDesign: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.NEW_PLAYER_DESIGN] ?: true }

    suspend fun setHighRefreshRate(b: Boolean) = context.dataStore.edit { it[SettingsKeys.HIGH_REFRESH_RATE] = b }
    suspend fun setNewMiniPlayerDesign(b: Boolean) = context.dataStore.edit { it[SettingsKeys.NEW_MINI_PLAYER_DESIGN] = b }
    suspend fun setPureBlackMiniPlayer(b: Boolean) = context.dataStore.edit { it[SettingsKeys.PURE_BLACK_MINI_PLAYER] = b }
    suspend fun setNewPlayerDesign(b: Boolean) = context.dataStore.edit { it[SettingsKeys.NEW_PLAYER_DESIGN] = b }


    val musicSearchHistory: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.MUSIC_SEARCH_HISTORY]
            ?.takeIf { it.isNotBlank() }
            ?.split("|||")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    suspend fun addMusicSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[SettingsKeys.MUSIC_SEARCH_HISTORY]
                ?.split("|||")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
            current.removeIf { it.equals(trimmed, ignoreCase = true) }
            current.add(0, trimmed)
            prefs[SettingsKeys.MUSIC_SEARCH_HISTORY] = current.take(20).joinToString("|||")
        }
    }

    suspend fun removeMusicSearchHistory(query: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[SettingsKeys.MUSIC_SEARCH_HISTORY]
                ?.split("|||")?.filter { it.isNotBlank() && !it.equals(query, ignoreCase = true) }
                ?: emptyList()
            prefs[SettingsKeys.MUSIC_SEARCH_HISTORY] = current.joinToString("|||")
        }
    }

    suspend fun clearMusicSearchHistory() =
        context.dataStore.edit { it.remove(SettingsKeys.MUSIC_SEARCH_HISTORY) }


    val skipSilence: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.SKIP_SILENCE] ?: false }
    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.KEEP_SCREEN_ON] ?: false }
    val persistentQueue: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.PERSISTENT_QUEUE] ?: true }
    val crossfadeDuration: Flow<String> = context.dataStore.data.map { it[SettingsKeys.CROSSFADE_DURATION] ?: "0" }

    suspend fun setSkipSilence(b: Boolean) = context.dataStore.edit { it[SettingsKeys.SKIP_SILENCE] = b }
    suspend fun setKeepScreenOn(b: Boolean) = context.dataStore.edit { it[SettingsKeys.KEEP_SCREEN_ON] = b }
    suspend fun setPersistentQueue(b: Boolean) = context.dataStore.edit { it[SettingsKeys.PERSISTENT_QUEUE] = b }
    suspend fun setCrossfadeDuration(s: String) = context.dataStore.edit { it[SettingsKeys.CROSSFADE_DURATION] = s }


    val listenHistoryEnabled: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.LISTEN_HISTORY_ENABLED] ?: true }
    val pauseListenHistory: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.PAUSE_LISTEN_HISTORY] ?: false }

    suspend fun setListenHistoryEnabled(b: Boolean) = context.dataStore.edit { it[SettingsKeys.LISTEN_HISTORY_ENABLED] = b }
    suspend fun setPauseListenHistory(b: Boolean) = context.dataStore.edit { it[SettingsKeys.PAUSE_LISTEN_HISTORY] = b }


    val safeSearch: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.SAFE_SEARCH] ?: false }
    val explicitContent: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.EXPLICIT_CONTENT] ?: true }
    val contentLanguage: Flow<String> = context.dataStore.data.map { it[SettingsKeys.CONTENT_LANGUAGE] ?: "en" }
    val contentCountry: Flow<String> = context.dataStore.data.map { it[SettingsKeys.CONTENT_COUNTRY] ?: "US" }

    suspend fun setSafeSearch(b: Boolean) = context.dataStore.edit { it[SettingsKeys.SAFE_SEARCH] = b }
    suspend fun setExplicitContent(b: Boolean) = context.dataStore.edit { it[SettingsKeys.EXPLICIT_CONTENT] = b }
    suspend fun setContentLanguage(s: String) = context.dataStore.edit { it[SettingsKeys.CONTENT_LANGUAGE] = s }
    suspend fun setContentCountry(s: String) = context.dataStore.edit { it[SettingsKeys.CONTENT_COUNTRY] = s }


    val lyricsSource: Flow<String> = context.dataStore.data.map { it[SettingsKeys.LYRICS_SOURCE] ?: "lrclib" }
    val syncedLyrics: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.SYNCED_LYRICS] ?: true }

    suspend fun setLyricsSource(s: String) = context.dataStore.edit { it[SettingsKeys.LYRICS_SOURCE] = s }
    suspend fun setSyncedLyrics(b: Boolean) = context.dataStore.edit { it[SettingsKeys.SYNCED_LYRICS] = b }


    val loudnessNormalization: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.LOUDNESS_NORMALIZATION] ?: false }

    suspend fun setLoudnessNormalization(b: Boolean) = context.dataStore.edit { it[SettingsKeys.LOUDNESS_NORMALIZATION] = b }


    val canvasEnabled: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.CANVAS_ENABLED] ?: true }

    suspend fun setCanvasEnabled(b: Boolean) = context.dataStore.edit { it[SettingsKeys.CANVAS_ENABLED] = b }

    val posterStyle: Flow<String> = context.dataStore.data.map { it[SettingsKeys.POSTER_STYLE] ?: "portrait" }

    suspend fun setPosterStyle(s: String) = context.dataStore.edit { it[SettingsKeys.POSTER_STYLE] = s }

    val dynamicMiniPlayerTheme: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.DYNAMIC_MINI_PLAYER_THEME] ?: true }

    suspend fun setDynamicMiniPlayerTheme(b: Boolean) = context.dataStore.edit { it[SettingsKeys.DYNAMIC_MINI_PLAYER_THEME] = b }

    val navLabels: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.NAV_LABELS] ?: true }

    suspend fun setNavLabels(b: Boolean) = context.dataStore.edit { it[SettingsKeys.NAV_LABELS] = b }


    val spotifyCookie: Flow<String>    = context.dataStore.data.map { it[SettingsKeys.SPOTIFY_COOKIE]    ?: "" }
    val spotifyUserName: Flow<String>  = context.dataStore.data.map { it[SettingsKeys.SPOTIFY_USER_NAME] ?: "" }

    suspend fun setSpotifyCookie(cookie: String) = context.dataStore.edit { it[SettingsKeys.SPOTIFY_COOKIE] = cookie }
    suspend fun setSpotifyUserName(name: String) = context.dataStore.edit { it[SettingsKeys.SPOTIFY_USER_NAME] = name }
    suspend fun clearSpotifyAccount() = context.dataStore.edit {
        it.remove(SettingsKeys.SPOTIFY_COOKIE)
        it.remove(SettingsKeys.SPOTIFY_USER_NAME)
    }

    val csHomeSections: Flow<List<PinnedCsSection>> = context.dataStore.data.map { prefs ->
        val json = prefs[SettingsKeys.CS_HOME_SECTIONS] ?: return@map emptyList()
        try { csHomeSectionsJson.decodeFromString(json) } catch (_: Throwable) { emptyList() }
    }

    suspend fun setCsHomeSections(sections: List<PinnedCsSection>) =
        context.dataStore.edit { it[SettingsKeys.CS_HOME_SECTIONS] = csHomeSectionsJson.encodeToString(sections) }

    val smartTrimmer: Flow<Boolean>        = context.dataStore.data.map { it[SettingsKeys.SMART_TRIMMER] ?: false }
    val videoCacheMaxMb: Flow<String>      = context.dataStore.data.map { it[SettingsKeys.VIDEO_CACHE_MAX_MB] ?: "unlimited" }
    val imageCacheMaxMb: Flow<String>      = context.dataStore.data.map { it[SettingsKeys.IMAGE_CACHE_MAX_MB] ?: "unlimited" }
    val posterCacheMaxCount: Flow<String>  = context.dataStore.data.map { it[SettingsKeys.POSTER_CACHE_MAX_COUNT] ?: "1024" }

    suspend fun setSmartTrimmer(b: Boolean)        = context.dataStore.edit { it[SettingsKeys.SMART_TRIMMER] = b }
    suspend fun setVideoCacheMaxMb(s: String)      = context.dataStore.edit { it[SettingsKeys.VIDEO_CACHE_MAX_MB] = s }
    suspend fun setImageCacheMaxMb(s: String)      = context.dataStore.edit { it[SettingsKeys.IMAGE_CACHE_MAX_MB] = s }
    suspend fun setPosterCacheMaxCount(s: String)  = context.dataStore.edit { it[SettingsKeys.POSTER_CACHE_MAX_COUNT] = s }

    val nuvioAccessToken: Flow<String>  = context.dataStore.data.map { it[SettingsKeys.NUVIO_ACCESS_TOKEN]  ?: "" }
    val nuvioRefreshToken: Flow<String> = context.dataStore.data.map { it[SettingsKeys.NUVIO_REFRESH_TOKEN] ?: "" }
    val nuvioEmail: Flow<String>        = context.dataStore.data.map { it[SettingsKeys.NUVIO_EMAIL]         ?: "" }
    val nuvioUserId: Flow<String>       = context.dataStore.data.map { it[SettingsKeys.NUVIO_USER_ID]       ?: "" }

    suspend fun setNuvioSession(accessToken: String, refreshToken: String, email: String, userId: String) =
        context.dataStore.edit {
            it[SettingsKeys.NUVIO_ACCESS_TOKEN]  = accessToken
            it[SettingsKeys.NUVIO_REFRESH_TOKEN] = refreshToken
            it[SettingsKeys.NUVIO_EMAIL]         = email
            it[SettingsKeys.NUVIO_USER_ID]       = userId
        }

    suspend fun clearNuvioSession() = context.dataStore.edit {
        it.remove(SettingsKeys.NUVIO_ACCESS_TOKEN)
        it.remove(SettingsKeys.NUVIO_REFRESH_TOKEN)
        it.remove(SettingsKeys.NUVIO_EMAIL)
        it.remove(SettingsKeys.NUVIO_USER_ID)
    }

    // ── Reddit login session ───────────────────────────────────────────
    val redditUsername: Flow<String> =
        context.dataStore.data.map { it[SettingsKeys.REDDIT_USERNAME] ?: "" }

    suspend fun setRedditUsername(name: String) =
        context.dataStore.edit { it[SettingsKeys.REDDIT_USERNAME] = name }

    suspend fun clearRedditUsername() =
        context.dataStore.edit { it.remove(SettingsKeys.REDDIT_USERNAME) }

    // ── Reddit multi-account support ──────────────────────────────────
    // Stored as: "user1|@|cookies1|||user2|@|cookies2"
    private fun parseRedditAccounts(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.split("|||").mapNotNull { entry ->
            val idx = entry.indexOf("|@|")
            if (idx < 0) null else entry.substring(0, idx) to entry.substring(idx + 3)
        }
    }

    private fun encodeRedditAccounts(accounts: List<Pair<String, String>>): String =
        accounts.joinToString("|||") { "${it.first}|@|${it.second}" }

    val redditAccounts: kotlinx.coroutines.flow.Flow<List<Pair<String, String>>> =
        context.dataStore.data.map { prefs ->
            parseRedditAccounts(prefs[SettingsKeys.REDDIT_ACCOUNTS_RAW] ?: "")
        }

    suspend fun addRedditAccount(username: String, cookies: String) {
        if (username.isBlank() || cookies.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = parseRedditAccounts(
                prefs[SettingsKeys.REDDIT_ACCOUNTS_RAW] ?: "").toMutableList()
            current.removeIf { it.first == username }
            current.add(0, username to cookies)
            prefs[SettingsKeys.REDDIT_ACCOUNTS_RAW] = encodeRedditAccounts(current.take(5))
        }
    }

    suspend fun removeRedditAccount(username: String) {
        context.dataStore.edit { prefs ->
            val current = parseRedditAccounts(
                prefs[SettingsKeys.REDDIT_ACCOUNTS_RAW] ?: "")
                .filter { it.first != username }
            prefs[SettingsKeys.REDDIT_ACCOUNTS_RAW] = encodeRedditAccounts(current)
        }
    }

    val introDbApiKey: Flow<String>         = context.dataStore.data.map { it[SettingsKeys.INTRODB_API_KEY] ?: "" }
    suspend fun setIntroDbApiKey(k: String) = context.dataStore.edit { it[SettingsKeys.INTRODB_API_KEY] = k }

    val subtitleSource: Flow<String>          = context.dataStore.data.map { it[SettingsKeys.SUBTITLE_SOURCE] ?: "any" }
    suspend fun setSubtitleSource(s: String)  = context.dataStore.edit { it[SettingsKeys.SUBTITLE_SOURCE] = s }

    val parentalGuideEnabled: Flow<Boolean>        = context.dataStore.data.map { it[SettingsKeys.PARENTAL_GUIDE_ENABLED] ?: true }
    suspend fun setParentalGuideEnabled(b: Boolean) = context.dataStore.edit { it[SettingsKeys.PARENTAL_GUIDE_ENABLED] = b }

    val holdToSpeedEnabled: Flow<Boolean>        = context.dataStore.data.map { it[SettingsKeys.HOLD_TO_SPEED_ENABLED] ?: false }
    suspend fun setHoldToSpeedEnabled(b: Boolean) = context.dataStore.edit { it[SettingsKeys.HOLD_TO_SPEED_ENABLED] = b }

    val holdToSpeedValue: Flow<String>          = context.dataStore.data.map { it[SettingsKeys.HOLD_TO_SPEED_VALUE] ?: "2.0" }
    suspend fun setHoldToSpeedValue(s: String)  = context.dataStore.edit { it[SettingsKeys.HOLD_TO_SPEED_VALUE] = s }

    val announcementSeenVersion: Flow<String>          = context.dataStore.data.map { it[SettingsKeys.ANNOUNCEMENT_SEEN_VERSION] ?: "" }
    suspend fun setAnnouncementSeenVersion(v: String)  = context.dataStore.edit { it[SettingsKeys.ANNOUNCEMENT_SEEN_VERSION] = v }

    val libraryWatchlistSort: Flow<String>          = context.dataStore.data.map { it[SettingsKeys.LIBRARY_WATCHLIST_SORT] ?: "added" }
    suspend fun setLibraryWatchlistSort(s: String)  = context.dataStore.edit { it[SettingsKeys.LIBRARY_WATCHLIST_SORT] = s }

    // ── Adult content settings ──────────────────────────────────────────────────
    val ageGateConfirmed: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.AGE_GATE_CONFIRMED] ?: false }
    val adultLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.ADULT_LOCK_ENABLED] ?: false }

    suspend fun setAgeGateConfirmed(b: Boolean) = context.dataStore.edit { it[SettingsKeys.AGE_GATE_CONFIRMED] = b }
    suspend fun setAdultLockEnabled(b: Boolean)  = context.dataStore.edit { it[SettingsKeys.ADULT_LOCK_ENABLED] = b }

    // ── Trakt.tv ────────────────────────────────────────────────────────────────
    val traktAccessToken:  Flow<String> = context.dataStore.data.map { it[SettingsKeys.TRAKT_ACCESS_TOKEN]  ?: "" }
    val traktRefreshToken: Flow<String> = context.dataStore.data.map { it[SettingsKeys.TRAKT_REFRESH_TOKEN] ?: "" }
    val traktUsername:     Flow<String> = context.dataStore.data.map { it[SettingsKeys.TRAKT_USERNAME]      ?: "" }
    val traktClientId:     Flow<String> = context.dataStore.data.map { it[SettingsKeys.TRAKT_CLIENT_ID]     ?: "" }

    suspend fun setTraktSession(accessToken: String, refreshToken: String, username: String) =
        context.dataStore.edit {
            it[SettingsKeys.TRAKT_ACCESS_TOKEN]  = accessToken
            it[SettingsKeys.TRAKT_REFRESH_TOKEN] = refreshToken
            it[SettingsKeys.TRAKT_USERNAME]      = username
        }
    suspend fun setTraktClientId(id: String) = context.dataStore.edit { it[SettingsKeys.TRAKT_CLIENT_ID] = id }
    suspend fun clearTraktSession() = context.dataStore.edit {
        it.remove(SettingsKeys.TRAKT_ACCESS_TOKEN)
        it.remove(SettingsKeys.TRAKT_REFRESH_TOKEN)
        it.remove(SettingsKeys.TRAKT_USERNAME)
    }

    // ── Simkl ────────────────────────────────────────────────────────────────
    val simklAccessToken: Flow<String> = context.dataStore.data.map { it[SettingsKeys.SIMKL_ACCESS_TOKEN] ?: "" }
    val simklClientId:    Flow<String> = context.dataStore.data.map { it[SettingsKeys.SIMKL_CLIENT_ID]    ?: "" }

    suspend fun setSimklAccessToken(token: String) = context.dataStore.edit { it[SettingsKeys.SIMKL_ACCESS_TOKEN] = token }
    suspend fun setSimklClientId(id: String)        = context.dataStore.edit { it[SettingsKeys.SIMKL_CLIENT_ID]    = id }
    suspend fun clearSimklSession() = context.dataStore.edit { it.remove(SettingsKeys.SIMKL_ACCESS_TOKEN) }

}