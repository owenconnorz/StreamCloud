package com.streamcloud.app.data.profiles

import android.content.Context
import com.streamcloud.app.data.nuvio.stableKey
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

    fun nuvioProfileIndex(userId: String, profileId: String): Int? {
        val accountId = userId.trim()
        if (accountId.isBlank()) return null
        claimLegacyNuvioMappings(accountId)
        return _profiles.value.firstOrNull { it.id == profileId }
            ?.nuvioProfileIndexes
            ?.get(accountId)
    }

    fun profilesForNuvioAccount(userId: String): List<UserProfile> {
        val accountId = userId.trim()
        return if (accountId.isBlank()) {
            _profiles.value
        } else {
            claimLegacyNuvioMappings(accountId)
            _profiles.value.filter {
                accountId in it.nuvioProfileIndexes ||
                    (it.nuvioProfileIndexes.isEmpty() && it.nuvioProfileIndex == null)
            }
        }
    }

    private fun claimLegacyNuvioMappings(accountId: String) {
        val migrated = _profiles.value.map { profile ->
            val legacyIndex = profile.nuvioProfileIndex
            if (legacyIndex != null && profile.nuvioProfileIndexes.isEmpty()) {
                profile.copy(
                    nuvioProfileIndex = null,
                    nuvioProfileIndexes = mapOf(accountId to legacyIndex),
                )
            } else if (legacyIndex != null) {
                profile.copy(nuvioProfileIndex = null)
            } else {
                profile
            }
        }
        if (migrated != _profiles.value) {
            _profiles.value = migrated
            persist()
        }
    }

    fun setNuvioProfileIndexes(userId: String, indexes: Map<String, Int>) {
        val accountId = userId.trim()
        if (accountId.isBlank() || indexes.isEmpty()) return
        _profiles.value = _profiles.value.map { profile ->
            indexes[profile.id]?.let {
                profile.copy(
                    nuvioProfileIndex = null,
                    nuvioProfileIndexes = profile.nuvioProfileIndexes + (accountId to it),
                )
            } ?: profile
        }
        persist()
    }

    /**
     * Merge the profiles returned by Nuvio without syncing PIN hashes.
     *
     * Profiles created locally remain available, while profiles previously imported
     * from Nuvio are updated or removed to match the account's current profile list.
     */
    fun mergeNuvioProfiles(
        userId: String,
        remoteProfiles: List<UserProfile>,
        preferredLocalProfileId: String? = null,
    ) {
        val accountId = userId.trim()
        if (accountId.isBlank()) return

        // Old versions stored one global Nuvio profile index. Claim that legacy
        // mapping for the first account that syncs it; never reuse it for later
        // accounts.
        val current = _profiles.value.map { profile ->
            val oldIndex = profile.nuvioProfileIndex
            if (oldIndex != null && profile.nuvioProfileIndexes.isEmpty()) {
                profile.copy(
                    nuvioProfileIndex = null,
                    nuvioProfileIndexes = mapOf(accountId to oldIndex),
                )
            } else if (oldIndex != null) {
                profile.copy(nuvioProfileIndex = null)
            } else {
                profile
            }
        }
        val indexedRemoteProfiles = remoteProfiles.filter { it.nuvioProfileIndex != null }
        if (indexedRemoteProfiles.isEmpty()) {
            _profiles.value = current.map { profile ->
                profile.copy(nuvioProfileIndexes = profile.nuvioProfileIndexes - accountId)
            }
            if (_activeId.value !in _profiles.value.map { it.id }) {
                setActiveProfile(_profiles.value.firstOrNull()?.id)
            }
            persist()
            return
        }

        val localOnlyByName = current
            .filter { it.nuvioProfileIndexes.isEmpty() }
            .groupBy { it.name.trim().lowercase() }
        val claimedLocalIds = mutableSetOf<String>()
        val preferredLocal = current.firstOrNull {
            it.id == preferredLocalProfileId && it.nuvioProfileIndexes.isEmpty()
        }
        val imported = indexedRemoteProfiles.map { remote ->
            val existing = current.firstOrNull {
                it.nuvioProfileIndexes[accountId] == remote.nuvioProfileIndex
            } ?: preferredLocal?.takeIf {
                indexedRemoteProfiles.size == 1 && it.id !in claimedLocalIds
            } ?: localOnlyByName[remote.name.trim().lowercase()]
                ?.singleOrNull { it.id !in claimedLocalIds }
            if (existing == null) {
                remote.copy(
                    id = "nuvio-${stableKey("$accountId:${remote.nuvioProfileIndex}").take(16)}",
                    nuvioProfileIndex = null,
                    nuvioProfileIndexes = mapOf(
                        accountId to requireNotNull(remote.nuvioProfileIndex),
                    ),
                )
            } else {
                claimedLocalIds += existing.id
                remote.copy(
                    id = existing.id,
                    pinHash = existing.pinHash,
                    nuvioProfileIndex = null,
                    nuvioProfileIndexes = existing.nuvioProfileIndexes +
                        (accountId to requireNotNull(remote.nuvioProfileIndex)),
                )
            }
        }
        val importedIds = imported.map { it.id }.toSet()
        val retained = current.mapNotNull { profile ->
            if (profile.id in importedIds) return@mapNotNull null
            val oldIndex = profile.nuvioProfileIndexes[accountId]
            if (oldIndex != null && indexedRemoteProfiles.none { it.nuvioProfileIndex == oldIndex }) {
                profile.copy(nuvioProfileIndexes = profile.nuvioProfileIndexes - accountId)
            } else {
                profile
            }
        }
        _profiles.value = imported + retained

        val active = _profiles.value.firstOrNull { it.id == _activeId.value }
        if (active?.nuvioProfileIndexes?.containsKey(accountId) != true) {
            _profiles.value.firstOrNull { accountId in it.nuvioProfileIndexes }
                ?.let { setActiveProfile(it.id) }
        }
        persist()
    }

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
