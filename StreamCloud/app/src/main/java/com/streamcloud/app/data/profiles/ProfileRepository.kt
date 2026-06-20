package com.streamcloud.app.data.profiles

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProfileRepository(context: Context) {

    private val prefs = context.getSharedPreferences("sc_profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: Flow<List<UserProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeProfileId: Flow<String?> = _activeId.asStateFlow()

    val activeProfile: Flow<UserProfile?> = combine(_profiles, _activeId) { list, id ->
        list.find { it.id == id } ?: list.firstOrNull()
    }

    fun currentProfiles(): List<UserProfile> = _profiles.value

    fun currentActiveId(): String? = _activeId.value

    private fun loadProfiles(): List<UserProfile> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<UserProfile>>(raw) }.getOrElse { emptyList() }
    }

    fun saveProfile(profile: UserProfile) {
        val current = _profiles.value.toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = profile else current.add(profile)
        _profiles.value = current
        persist()
    }

    fun deleteProfile(id: String) {
        _profiles.value = _profiles.value.filter { it.id != id }
        if (_activeId.value == id) {
            setActiveProfile(_profiles.value.firstOrNull()?.id)
        }
        persist()
    }

    fun setActiveProfile(id: String?) {
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    private fun persist() {
        prefs.edit().putString(KEY_LIST, json.encodeToString(_profiles.value)).apply()
    }

    companion object {
        private const val KEY_LIST   = "profiles_json"
        private const val KEY_ACTIVE = "active_profile_id"
    }
}
