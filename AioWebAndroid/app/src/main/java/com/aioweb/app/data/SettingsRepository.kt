package com.aioweb.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aioweb.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("aioweb_settings")

object SettingsKeys {
    val BACKEND_URL = stringPreferencesKey("backend_url")
    val AI_PROVIDER = stringPreferencesKey("ai_provider")
    val AI_MODEL = stringPreferencesKey("ai_model")
    val NSFW_ENABLED = booleanPreferencesKey("nsfw_enabled")
    val VIDEO_QUALITY = stringPreferencesKey("video_quality")     // auto / 1080 / 720 / 480
    val AUDIO_QUALITY = stringPreferencesKey("audio_quality")     // high / medium / low
    val EXTERNAL_LINKS_IN_BROWSER = booleanPreferencesKey("ext_links_in_browser")
    val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
    val SUBTITLES_ENABLED = booleanPreferencesKey("subs_enabled")
    val DOWNLOAD_OVER_WIFI_ONLY = booleanPreferencesKey("dl_wifi_only")
    val SAFE_MODE_PIN = stringPreferencesKey("safe_mode_pin")     // 4-digit PIN for NSFW
    val THEME = stringPreferencesKey("theme")                     // dark / system
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")    // Monet (Android 12+)
    val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
    val EQ_PRESET = stringPreferencesKey("eq_preset")             // flat / pop / rock / jazz / bass / vocal
    val BASS_BOOST = booleanPreferencesKey("bass_boost")
    val HF_TOKEN = stringPreferencesKey("hf_token")               // HuggingFace token for NSFW + image edit
    val HOME_COLLECTIONS = stringPreferencesKey("home_collections") // CSV of enabled collection ids
    val YT_MUSIC_COOKIE = stringPreferencesKey("yt_music_cookie")     // Raw "Cookie:" header from music.youtube.com
    val YT_MUSIC_USER_NAME = stringPreferencesKey("yt_music_user_name")
    val YT_MUSIC_USER_AVATAR = stringPreferencesKey("yt_music_user_avatar")
    val NAV_TAB_ORDER = stringPreferencesKey("nav_tab_order")     // CSV of tab ids (movies,music,ai,library|adult)
    val PLAYLIST_THUMBS = stringPreferencesKey("playlist_thumbs") // JSON {playlistId: uriString}
    val UI_MODE = stringPreferencesKey("ui_mode")                 // Auto / Mobile / Tablet / Tv
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

    /**
     * Enabled "Home collections" — null/blank means "use defaults from
     * [com.aioweb.app.data.collections.HomeCollections]".
     */
    val homeCollectionsCsv: Flow<String?> = context.dataStore.data.map { it[SettingsKeys.HOME_COLLECTIONS] }

    /** Raw `Cookie:` header captured from music.youtube.com after login (Metrolist parity). */
    val ytMusicCookie: Flow<String> = context.dataStore.data.map { it[SettingsKeys.YT_MUSIC_COOKIE] ?: "" }
    val ytMusicUserName: Flow<String> = context.dataStore.data.map { it[SettingsKeys.YT_MUSIC_USER_NAME] ?: "" }
    val ytMusicUserAvatar: Flow<String> = context.dataStore.data.map { it[SettingsKeys.YT_MUSIC_USER_AVATAR] ?: "" }

    /**
     * User-defined bottom navigation order. CSV of tab ids (subset of
     * `movies,music,ai,library,adult`). `settings` is always appended last.
     * Null/blank => use the hardcoded default order.
     */
    val navTabOrderCsv: Flow<String?> = context.dataStore.data.map { it[SettingsKeys.NAV_TAB_ORDER] }

    /**
     * User-chosen custom playlist cover art, keyed by playlist id. Stored as a
     * JSON object `{playlistId: uriString}` so we don't need a Room migration
     * for what is essentially a UI preference.
     */
    val playlistThumbsJson: Flow<String> = context.dataStore.data.map {
        it[SettingsKeys.PLAYLIST_THUMBS] ?: "{}"
    }

    /** UI form factor override — `Auto` lets the runtime detect mobile/tablet/TV. */
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

    suspend fun setNavTabOrder(ids: List<String>) =
        context.dataStore.edit { it[SettingsKeys.NAV_TAB_ORDER] = ids.joinToString(",") }

    suspend fun setUiMode(mode: String) =
        context.dataStore.edit { it[SettingsKeys.UI_MODE] = mode }

    suspend fun setPlaylistThumb(playlistId: String, uri: String?) =
        context.dataStore.edit { prefs ->
            val current = prefs[SettingsKeys.PLAYLIST_THUMBS] ?: "{}"
            // Light-touch JSON edit — avoids pulling in a JSON lib for this
            // single-key map operation. Handles up to dozens of playlists fine.
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
}
